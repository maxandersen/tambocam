

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.tamboui.image.ImageData;

/**
 * Live camera frame source using ffmpeg with avfoundation (macOS).
 * <p>
 * Reads <strong>raw RGB24</strong> frames from ffmpeg stdout — no PNG
 * encode/decode overhead. This matches the approach used by terminalcam
 * for maximum throughput.
 */
final class FfmpegCameraFrames implements AutoCloseable {

    /** Output frame width after ffmpeg scaling. */
    private static final int FRAME_W = 160;
    /** Output frame height after ffmpeg scaling. */
    private static final int FRAME_H = 96;
    /** Bytes per raw RGB24 frame. */
    private static final int FRAME_BYTES = FRAME_W * FRAME_H * 3;

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
            new AtomicReference<ImageData>(createWaitingFrame("Connecting to camera " + deviceIndex + "..."));
        AtomicReference<String> status =
            new AtomicReference<String>("Waiting for camera " + deviceIndex + "...");
        AtomicInteger frameCount = new AtomicInteger(0);

        // Build platform-specific capture command, matching terminalcam's approach:
        // - Output raw RGB24 — no encode/decode overhead
        // - fps=15 in filter to balance smoothness vs throughput
        // - Scale to terminal-friendly size in filter chain
        List<String> cmd = new java.util.ArrayList<String>();
        cmd.add("ffmpeg");
        cmd.add("-loglevel");
        cmd.add("error");

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            // macOS: avfoundation
            cmd.add("-f");
            cmd.add("avfoundation");
            cmd.add("-framerate");
            cmd.add("30");
            cmd.add("-video_size");
            cmd.add("1280x720");
            cmd.add("-pixel_format");
            cmd.add("yuyv422");
            cmd.add("-i");
            cmd.add(deviceIndex + ":none");
        } else if (os.contains("win")) {
            // Windows: dshow
            cmd.add("-f");
            cmd.add("dshow");
            cmd.add("-framerate");
            cmd.add("30");
            cmd.add("-video_size");
            cmd.add("640x480");
            cmd.add("-i");
            cmd.add("video=" + deviceIndex);
        } else {
            // Linux: v4l2
            cmd.add("-f");
            cmd.add("v4l2");
            cmd.add("-framerate");
            cmd.add("30");
            cmd.add("-video_size");
            cmd.add("640x480");
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

        ProcessBuilder builder = new ProcessBuilder(cmd);
        StringBuilder cmdLine = new StringBuilder();
        for (String arg : cmd) {
            if (cmdLine.length() > 0) {
                cmdLine.append(' ');
            }
            if (arg.contains(" ") || arg.contains("(") || arg.contains(")")) {
                cmdLine.append('"').append(arg).append('"');
            } else {
                cmdLine.append(arg);
            }
        }
        String commandLineStr = cmdLine.toString();

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("ffmpeg not found — is it installed? " + e.getMessage(), e);
        }

        Thread reader = new Thread(
            new RawFrameReaderTask(process.getInputStream(), latestFrame, status, frameCount),
            "terminalcam-reader");
        reader.setDaemon(true);
        reader.start();

        Thread stderrReader = new Thread(
            new StderrReaderTask(process.getErrorStream(), status),
            "terminalcam-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();

        return new FfmpegCameraFrames(latestFrame, status, frameCount, commandLineStr, process, reader);
    }

    ImageData latestFrame() {
        return latestFrame.get();
    }

    String status() {
        return status.get();
    }

    /**
     * Returns the number of real frames received from ffmpeg so far.
     *
     * @return frame count, 0 means no real frames yet
     */
    int receivedFrames() {
        return frameCount.get();
    }

    /**
     * Returns the ffmpeg command line that was launched.
     *
     * @return the command string
     */
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

    /**
     * Converts raw RGB24 bytes into an ImageData.
     * Each pixel is 3 bytes: R, G, B. No PNG overhead.
     */
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
                    // Read exactly one complete frame (like terminalcam)
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
