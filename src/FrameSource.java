

import java.util.List;

import dev.tamboui.image.ImageData;

final class FrameSource {

    private final List<ImageData> frames;
    private final String name;
    private final String status;
    private final FfmpegCameraFrames camera;

    FrameSource(List<ImageData> frames, String name, String status) {
        this(frames, name, status, null);
    }

    FrameSource(List<ImageData> frames, String name, String status, FfmpegCameraFrames camera) {
        this.frames = frames;
        this.name = name;
        this.status = status;
        this.camera = camera;
    }

    List<ImageData> frames() {
        return frames;
    }

    String name() {
        return name;
    }

    String status() {
        return status;
    }

    boolean liveCamera() {
        return camera != null;
    }

    FfmpegCameraFrames camera() {
        return camera;
    }
}
