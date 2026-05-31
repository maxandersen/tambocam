

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import dev.tamboui.image.ImageData;

final class FrameSources {

    private FrameSources() {
    }

    static FrameSource load(String[] args) throws IOException {
        if (args.length == 0) {
            return autoDetectCamera();
        }
        if ("--camera".equals(args[0]) || args[0].startsWith("--camera=")) {
            int device = 0;
            if (args[0].startsWith("--camera=")) {
                device = Integer.parseInt(args[0].substring("--camera=".length()));
            }
            return camera(device);
        }
        Path path = Path.of(args[0]);
        if (!Files.exists(path)) {
            return syntheticWithStatus("Path not found, using synthetic feed: " + path);
        }
        if (Files.isDirectory(path)) {
            return directory(path);
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".gif")) {
            return gif(path);
        }
        if (isStillImage(lower)) {
            return still(path);
        }
        return syntheticWithStatus("Unsupported source, using synthetic feed: " + path.getFileName());
    }

    static FrameSource autoDetectCamera() {
        try {
            List<CameraDevice> devices = CameraDevice.discover();
            if (!devices.isEmpty()) {
                CameraDevice first = devices.get(0);
                return camera(first.index(), first.name());
            }
        } catch (Exception e) {
            // camera failed — fall through to synthetic
        }
        return synthetic();
    }

    static FrameSource synthetic() {
        List<ImageData> frames = new ArrayList<ImageData>();
        for (int i = 0; i < 6; i++) {
            frames.add(SyntheticCameraFrames.create(160, 96, i));
        }
        return new FrameSource(frames, "synthetic camera", "Synthetic animated camera feed");
    }

    static FrameSource syntheticWithStatus(String status) {
        return new FrameSource(synthetic().frames(), "synthetic camera", status);
    }

    static FrameSource still(Path path) throws IOException {
        List<ImageData> frames = new ArrayList<ImageData>();
        frames.add(ImageData.fromPath(path));
        return new FrameSource(frames, path.getFileName().toString(), "Loaded still image");
    }

    static FrameSource directory(Path dir) throws IOException {
        List<Path> files;
        try (Stream<Path> stream = Files.list(dir)) {
            files = stream
                .filter(Files::isRegularFile)
                .filter(path -> isStillImage(path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        }
        if (files.isEmpty()) {
            return syntheticWithStatus("No images found in directory, using synthetic feed");
        }
        List<ImageData> frames = new ArrayList<ImageData>();
        for (Path file : files) {
            frames.add(ImageData.fromPath(file));
        }
        return new FrameSource(frames, dir.getFileName().toString(), "Loaded " + frames.size() + " frame(s) from directory");
    }

    static FrameSource gif(Path path) throws IOException {
        List<ImageData> frames = new ArrayList<ImageData>();
        try (ImageInputStream stream = ImageIO.createImageInputStream(path.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return syntheticWithStatus("Could not decode GIF, using synthetic feed");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, false, false);
                int count = reader.getNumImages(true);
                for (int i = 0; i < count; i++) {
                    BufferedImage frame = reader.read(i);
                    frames.add(ImageData.fromBufferedImage(frame));
                }
            } finally {
                reader.dispose();
            }
        }
        if (frames.isEmpty()) {
            return syntheticWithStatus("GIF had no readable frames, using synthetic feed");
        }
        return new FrameSource(frames, path.getFileName().toString(), "Loaded GIF with " + frames.size() + " frame(s)");
    }

    static FrameSource camera(int deviceIndex) throws IOException {
        return camera(deviceIndex, null);
    }

    static FrameSource camera(int deviceIndex, String deviceName) throws IOException {
        FfmpegCameraFrames camera = FfmpegCameraFrames.start(deviceIndex);
        List<ImageData> initial = new ArrayList<ImageData>();
        initial.add(camera.latestFrame());
        String label = deviceName != null ? deviceName : "camera " + deviceIndex;
        return new FrameSource(initial, label, "Starting " + label, camera);
    }

    private static boolean isStillImage(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".webp") || name.endsWith(".bmp");
    }
}
