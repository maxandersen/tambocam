
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.tamboui.image.ImageData;

/**
 * Live camera frame source using ffmpeg.
 * <p>
 * Auto-detects supported camera modes (resolution + framerate) by probing
 * ffmpeg, then picks the smallest usable resolution to minimize scaling
 * overhead. Reads raw RGB24 frames from stdout for maximum throughput.
 * <p>
 * Supports macOS (avfoundation), Linux (v4l2), and Windows (dshow).
 */
final class FfmpegCameraFrames implements AutoCloseable {

    /** Output frame width after ffmpeg scaling. */
    private static final int FRAME_W = 160;
    /** Output frame height after ffmpeg scaling. */
    private static final int FRAME_H = 96;
    /** Bytes per raw RGB24 frame. */
    private static final int FRAME_BYTES = FRAME_W * FRAME_H * 3;

    /** Pattern to parse modes like: 1280x720@[30.000030 30.000030]fps */
    private static final Pattern MODE_PATTERN =
        Pattern.compile("(\\d+)x(\\d+)@\\[([\\d.]+)\\s");

    private final AtomicReference<ImageData> latestFrame;
    private final AtomicReference<String> status;
    private final AtomicInteger frameCount;
    private final String commandLine;
    private final Process process;
    private final Thread readerThread;
    private volatile boolean closed;

    private FfmpegCameraFrames(AtomicReference<ImageData> latestFrame, AtomicReference<String> status,
            AtomicInteger frameCount, String commandLine, Process process, Thread readerThread) {
        this.latestFrame = latestFrame;
        this.status = status;
        this.frameCount = frameCount;
        this.commandLine = commandLine;
        this.process = process;
        this.readerThread = readerThread;
    }

    static FfmpegCameraFrames start(int deviceIndex) throws IOException {
        AtomicReference<ImageData> latestFrame =
            new AtomicReference<ImageData>(createWaitingFrame("Probing camera " + deviceIndex + "..."));
        AtomicReference<String> status =
            new AtomicReference<String>("Probing camera " + deviceIndex + "...");
        AtomicInteger frameCount = new AtomicInteger(0);

        String os = System.getProperty("os.name", "").toLowerCase();

        // Probe camera to discover supported modes
        List<CameraMode> modes = probeCamera(deviceIndex, os);

        // Build capture command with best available mode
        List<String> cmd = buildCommand(deviceIndex, os, modes);

        String commandLineStr = formatCommand(cmd);
        status.set("Starting: " + commandLineStr);
        latestFrame.set(createWaitingFrame("Starting camera " + deviceIndex + "..."));

        ProcessBuilder builder = new ProcessBuilder(cmd);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("ffmpeg not found — is it installed? " + e.getMessage(), e);
        }

        Thread reader = new Thread(
            new RawFrameReaderTask(process.getInputStream(), latestFrame, status, frameCount),
            "tambocam-reader");
        reader.setDaemon(true);
        reader.start();

        Thread stderrReader = new Thread(
            new StderrReaderTask(process.getErrorStream(), status),
            "tambocam-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        return new FfmpegCameraFrames(latestFrame, status, frameCount, commandLineStr, process, reader);
    }

    // ── Camera mode probing ─────────────────────────────────────────────

    /**
     * A supported camera capture mode.
     */
    private static final class CameraMode {
        final int width;
        final int height;
        final int fps;

        CameraMode(int width, int height, int fps) {
            this.width = width;
            this.height = height;
            this.fps = fps;
        }

        /** Total pixels — used to pick the smallest resolution. */
        int pixels() {
            return width * height;
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + fps;
        }
    }

    /**
     * Probes a camera device by running ffmpeg with an intentionally unsupported
     * setting. ffmpeg prints "Supported modes:" to stderr, which we parse.
     */
    private static List<CameraMode> probeCamera(int deviceIndex, String os) {
        List<CameraMode> modes = new ArrayList<CameraMode>();
        try {
            List<String> cmd = new ArrayList<String>();
            cmd.add("ffmpeg");
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");

            if (os.contains("mac")) {
                cmd.add("-f");
                cmd.add("avfoundation");
                // Use an intentionally unsupported framerate to trigger mode listing
                cmd.add("-framerate");
                cmd.add("1");
                cmd.add("-video_size");
                cmd.add("1x1");
                cmd.add("-i");
                cmd.add(deviceIndex + ":none");
            } else if (os.contains("win")) {
                cmd.add("-f");
                cmd.add("dshow");
                cmd.add("-list_options");
                cmd.add("true");
                cmd.add("-i");
                cmd.add("video=" + deviceIndex);
            } else {
                // Linux v4l2 — try v4l2-ctl first
                return probeV4l2(deviceIndex);
            }
            cmd.add("-t");
            cmd.add("0");
            cmd.add("-f");
            cmd.add("null");
            cmd.add("-");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher m = MODE_PATTERN.matcher(line);
                if (m.find()) {
                    int w = Integer.parseInt(m.group(1));
                    int h = Integer.parseInt(m.group(2));
                    double fpsVal = Double.parseDouble(m.group(3));
                    int fps = (int) Math.round(fpsVal);
                    // Deduplicate: only add if we don't already have this exact mode
                    boolean duplicate = false;
                    for (CameraMode existing : modes) {
                        if (existing.width == w && existing.height == h && existing.fps == fps) {
                            duplicate = true;
                            break;
                        }
                    }
                    if (!duplicate) {
                        modes.add(new CameraMode(w, h, fps));
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // Probe failed — will fall back to defaults
        }
        return modes;
    }

    /**
     * Probes Linux v4l2 camera capabilities.
     */
    private static List<CameraMode> probeV4l2(int deviceIndex) {
        List<CameraMode> modes = new ArrayList<CameraMode>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "v4l2-ctl", "--device=/dev/video" + deviceIndex, "--list-formats-ext"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));

            Pattern sizePattern = Pattern.compile("Size.*?(\\d+)x(\\d+)");
            Pattern fpsPattern = Pattern.compile("Interval.*?\\((\\d+)\\.\\d+ fps\\)");
            int lastW = 0, lastH = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher sm = sizePattern.matcher(line);
                if (sm.find()) {
                    lastW = Integer.parseInt(sm.group(1));
                    lastH = Integer.parseInt(sm.group(2));
                }
                Matcher fm = fpsPattern.matcher(line);
                if (fm.find() && lastW > 0) {
                    int fps = Integer.parseInt(fm.group(1));
                    modes.add(new CameraMode(lastW, lastH, fps));
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // v4l2-ctl not available
        }
        return modes;
    }

    /**
     * Picks the best camera mode: smallest resolution ≥ our output size,
     * preferring higher framerates.
     */
    private static CameraMode pickBestMode(List<CameraMode> modes) {
        if (modes.isEmpty()) {
            return null;
        }
        // Sort by: pixels ascending, then fps descending
        Collections.sort(modes, new Comparator<CameraMode>() {
            @Override
            public int compare(CameraMode a, CameraMode b) {
                int byPixels = Integer.compare(a.pixels(), b.pixels());
                if (byPixels != 0) {
                    return byPixels;
                }
                return Integer.compare(b.fps, a.fps); // higher fps first
            }
        });
        // Pick smallest resolution that's at least as big as our output
        for (CameraMode mode : modes) {
            if (mode.width >= FRAME_W && mode.height >= FRAME_H) {
                return mode;
            }
        }
        // All modes are smaller than output (unlikely) — use the largest
        return modes.get(modes.size() - 1);
    }

    // ── Command building ────────────────────────────────────────────────

    private static List<String> buildCommand(int deviceIndex, String os, List<CameraMode> modes) {
        CameraMode best = pickBestMode(modes);

        List<String> cmd = new ArrayList<String>();
        cmd.add("ffmpeg");
        cmd.add("-loglevel");
        cmd.add("error");

        if (os.contains("mac")) {
            cmd.add("-f");
            cmd.add("avfoundation");
            if (best != null) {
                cmd.add("-framerate");
                cmd.add(String.valueOf(best.fps));
                cmd.add("-video_size");
                cmd.add(best.width + "x" + best.height);
            }
            cmd.add("-pixel_format");
            cmd.add("yuyv422");
            cmd.add("-i");
            cmd.add(deviceIndex + ":none");
        } else if (os.contains("win")) {
            cmd.add("-f");
            cmd.add("dshow");
            if (best != null) {
                cmd.add("-framerate");
                cmd.add(String.valueOf(best.fps));
                cmd.add("-video_size");
                cmd.add(best.width + "x" + best.height);
            }
            cmd.add("-i");
            cmd.add("video=" + deviceIndex);
        } else {
            cmd.add("-f");
            cmd.add("v4l2");
            if (best != null) {
                cmd.add("-framerate");
                cmd.add(String.valueOf(best.fps));
                cmd.add("-video_size");
                cmd.add(best.width + "x" + best.height);
            }
            cmd.add("-i");
            cmd.add("/dev/video" + deviceIndex);
        }

        cmd.add("-vf");
        cmd.add("fps=15,scale=" + FRAME_W + ":" + FRAME_H
            + ":force_original_aspect_ratio=decrease,pad="
            + FRAME_W + ":" + FRAME_H + ":(ow-iw)/2:(oh-ih)/2:black");
        cmd.add("-an");
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("rgb24");
        cmd.add("pipe:1");

        return cmd;
    }

    private static String formatCommand(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String arg : cmd) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            if (arg.contains(" ") || arg.contains("(") || arg.contains(")")) {
                sb.append('"').append(arg).append('"');
            } else {
                sb.append(arg);
            }
        }
        return sb.toString();
    }

    // ── Accessors ───────────────────────────────────────────────────────

    ImageData latestFrame() {
        return latestFrame.get();
    }

    String status() {
        return status.get();
    }

    int receivedFrames() {
        return frameCount.get();
    }

    String commandLine() {
        return commandLine;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        process.destroyForcibly();
        readerThread.interrupt();
    }

    // ── Raw RGB24 → ImageData conversion ────────────────────────────────

    private static ImageData rgb24ToImageData(byte[] rgb, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int offset = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = rgb[offset++] & 0xff;
                int g = rgb[offset++] & 0xff;
                int b = rgb[offset++] & 0xff;
                image.setRGB(x, y, (0xff << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return ImageData.fromBufferedImage(image);
    }

    // ── Background raw frame reader ─────────────────────────────────────

    private static final class RawFrameReaderTask implements Runnable {

        private final InputStream input;
        private final AtomicReference<ImageData> latestFrame;
        private final AtomicReference<String> status;
        private final AtomicInteger frameCount;

        RawFrameReaderTask(InputStream input, AtomicReference<ImageData> latestFrame,
                AtomicReference<String> status, AtomicInteger frameCount) {
            this.input = input;
            this.latestFrame = latestFrame;
            this.status = status;
            this.frameCount = frameCount;
        }

        @Override
        public void run() {
            byte[] buf = new byte[FRAME_BYTES];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    int offset = 0;
                    while (offset < FRAME_BYTES) {
                        int n = input.read(buf, offset, FRAME_BYTES - offset);
                        if (n == -1) {
                            status.set("Camera stream ended");
                            return;
                        }
                        offset += n;
                    }
                    latestFrame.set(rgb24ToImageData(buf, FRAME_W, FRAME_H));
                    int count = frameCount.incrementAndGet();
                    status.set("● LIVE — " + count + " frames");
                }
            } catch (Exception e) {
                if (!Thread.currentThread().isInterrupted()) {
                    status.set("Camera read failed: " + e.getMessage());
                }
            }
        }
    }

    // ── Background stderr reader ────────────────────────────────────────

    private static final class StderrReaderTask implements Runnable {

        private final InputStream input;
        private final AtomicReference<String> status;

        StderrReaderTask(InputStream input, AtomicReference<String> status) {
            this.input = input;
            this.status = status;
        }

        @Override
        public void run() {
            try {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] tmp = new byte[1024];
                int n;
                while ((n = input.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);
                }
                if (buf.size() > 0) {
                    String text = new String(buf.toByteArray(), StandardCharsets.UTF_8).trim();
                    if (!text.isEmpty()) {
                        String[] lines = text.split("\n");
                        status.set("ffmpeg error: " + lines[lines.length - 1].trim());
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }
    }

    // ── Waiting placeholder ─────────────────────────────────────────────

    private static ImageData createWaitingFrame(String message) {
        BufferedImage image = new BufferedImage(FRAME_W, FRAME_H, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(10, 10, 10));
            g.fillRect(0, 0, FRAME_W, FRAME_H);
            g.setColor(new Color(30, 30, 30));
            for (int i = -FRAME_H; i < FRAME_W; i += 8) {
                g.drawLine(i, 0, i + FRAME_H, FRAME_H);
                g.drawLine(i + FRAME_H, 0, i, FRAME_H);
            }
            g.setColor(new Color(80, 80, 80));
            g.drawOval(FRAME_W / 2 - 16, FRAME_H / 2 - 20, 32, 32);
            g.drawOval(FRAME_W / 2 - 12, FRAME_H / 2 - 16, 24, 24);
            g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
            g.setColor(new Color(200, 200, 0));
            g.drawString(message, 8, FRAME_H / 2 + 24);
            g.setColor(new Color(255, 200, 0, 180));
            g.fillOval(FRAME_W / 2 - 3, FRAME_H / 2 - 4, 6, 6);
        } finally {
            g.dispose();
        }
        return ImageData.fromBufferedImage(image);
    }
}
