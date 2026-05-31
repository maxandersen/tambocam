//DEPS dev.tamboui:tamboui-image:LATEST
//DEPS dev.tamboui:tamboui-widgets:LATEST
//DEPS dev.tamboui:tamboui-jline3-backend:LATEST
//DEPS dev.tamboui:tamboui-panama-backend:LATEST

//SOURCES AsciiArtRenderer.java CameraDevice.java FfmpegCameraFrames.java FrameSource.java FrameSources.java SyntheticCameraFrames.java
// Prevents OSX from showing up in the terminal when running the demo
//JAVA_OPTIONS -Dapple.awt.UIElement=true



import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

import static dev.tamboui.export.ExportRequest.export;

import dev.tamboui.image.Image;
import dev.tamboui.image.ImageData;
import dev.tamboui.image.ImageScaling;
import dev.tamboui.image.capability.TerminalImageCapabilities;
import dev.tamboui.image.protocol.BrailleProtocol;
import dev.tamboui.image.protocol.HalfBlockProtocol;
import dev.tamboui.image.protocol.ImageProtocol;
import dev.tamboui.internal.record.RecordingBackend;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Overflow;
import dev.tamboui.style.Style;
import dev.tamboui.terminal.Backend;
import dev.tamboui.terminal.BackendFactory;
import dev.tamboui.terminal.Frame;
import dev.tamboui.terminal.Terminal;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.text.Text;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.paragraph.Paragraph;

/**
 * Terminalcam-style TamboUI demo.
 */
public class TamboCamDemo {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ImageProtocol HALF_BLOCK = new HalfBlockProtocol();
    private static final ImageProtocol BRAILLE = new BrailleProtocol();
    private static final String[] RENDER_MODES = {"half-block", "braille", "ascii"};
    private static final List<String> PALETTES = Arrays.asList("night", "amber", "ice");

    private final TerminalImageCapabilities capabilities;
    private final List<CameraDevice> availableCameras;
    private List<ImageData> frames;
    private String sourceName;
    private FfmpegCameraFrames camera;
    private boolean liveCamera;
    private boolean running = true;
    private boolean playing = true;
    private boolean hudVisible = true;
    private int frameIndex;
    private int paletteIndex;
    private int brightness = 100;
    private int contrast = 100;
    private int cameraIndex = -1;
    private int renderMode;
    private ImageProtocol protocol;
    private final AsciiArtRenderer asciiRenderer = new AsciiArtRenderer();
    private dev.tamboui.buffer.Buffer lastBuffer;
    private ImageData lastFrame;
    private String status = "Booted synthetic camera feed";
    private String flashMessage;
    private long flashExpiry;

    /**
     * Demo entry point.
     *
     * @param args optional CLI args
     * @throws Exception on unexpected failure
     */
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "--list-cameras".equals(args[0])) {
            CameraDevice.listAndPrint();
            return;
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printUsage();
            return;
        }
        new TamboCamDemo(args).run();
    }

    private static void printUsage() {
        System.out.println("tambocam — Terminal camera monitor");
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  tambocam                    synthetic test pattern");
        System.out.println("  tambocam --camera            macOS camera (device 0)");
        System.out.println("  tambocam --camera=N          specific camera device");
        System.out.println("  tambocam --list-cameras      list available cameras");
        System.out.println("  tambocam <image>             display a still image");
        System.out.println("  tambocam <file.gif>          animate a GIF");
        System.out.println("  tambocam <directory>         cycle images from dir");
        System.out.println();
        System.out.println("Controls:");
        System.out.println("  SPACE  play/pause    m  mode    p  palette    h  toggle HUD");
        System.out.println("  +/-    brightness    [/]  contrast    q  quit");
        System.out.println("  n      next camera (or next frame if no cameras)");
        System.out.println("  b      prev camera (or prev frame if no cameras)");
        System.out.println("  s      save snapshot (PNG + SVG)");
    }

    /**
     * Creates the demo and detects terminal image capabilities.
     *
     * @param args optional source path, either a still image, GIF, or directory of images
     * @throws IOException if backend setup fails
     */
    public TamboCamDemo(String[] args) throws IOException {
        try (Backend backend = BackendFactory.create()) {
            if (backend instanceof RecordingBackend) {
                this.capabilities = TerminalImageCapabilities.withSupport(java.util.EnumSet.of(
                    dev.tamboui.image.capability.TerminalImageProtocol.HALF_BLOCK,
                    dev.tamboui.image.capability.TerminalImageProtocol.BRAILLE));
            } else {
                this.capabilities = TerminalImageCapabilities.detect();
            }
        }
        this.protocol = capabilities.supports(dev.tamboui.image.capability.TerminalImageProtocol.BRAILLE)
            ? BRAILLE : HALF_BLOCK;
        this.renderMode = protocol == BRAILLE ? 1 : 0;
        this.availableCameras = CameraDevice.discover();
        FrameSource source = FrameSources.load(args);
        this.frames = source.frames();
        this.sourceName = source.name();
        this.status = source.status();
        this.camera = source.camera();
        this.liveCamera = source.liveCamera();
        if (liveCamera && args.length > 0 && args[0].startsWith("--camera")) {
            int device = 0;
            if (args[0].startsWith("--camera=")) {
                device = Integer.parseInt(args[0].substring("--camera=".length()));
            }
            cameraIndex = indexOfDevice(device);
        }
    }

    private int indexOfDevice(int deviceIndex) {
        for (int i = 0; i < availableCameras.size(); i++) {
            if (availableCameras.get(i).index() == deviceIndex) {
                return i;
            }
        }
        return -1;
    }

    private void switchCamera(CameraDevice device) {
        if (camera != null) {
            camera.close();
        }
        try {
            FrameSource source = FrameSources.camera(device.index(), device.name());
            this.camera = source.camera();
            this.frames = source.frames();
            this.sourceName = source.name();
            this.status = "Switching to " + device.name();
            this.liveCamera = true;
            this.frameIndex = 0;
        } catch (IOException e) {
            this.status = "Camera switch failed: " + e.getMessage();
        }
    }

    /**
     * Runs the interactive demo loop.
     *
     * @throws Exception on unexpected failure
     */
    public void run() throws Exception {
        try (Backend backend = BackendFactory.create()) {
            backend.enableRawMode();
            backend.enterAlternateScreen();
            backend.hideCursor();

            Terminal<Backend> terminal = new Terminal<>(backend);
            backend.onResize(() -> terminal.draw(this::render));

            while (running) {
                terminal.draw(this::render);
                int c = backend.read(8);
                handleInput(c);
                if (!liveCamera && playing) {
                    frameIndex = (frameIndex + 1) % frames.size();
                }
            }
        } finally {
            if (camera != null) {
                camera.close();
            }
        }
    }

    private void handleInput(int c) {
        switch (c) {
            case 'q':
            case 'Q':
            case 3:
                running = false;
                break;
            case ' ':
                playing = !playing;
                status = playing ? "Playback resumed" : "Playback paused";
                break;
            case 'h':
            case 'H':
                hudVisible = !hudVisible;
                status = hudVisible ? "HUD enabled" : "HUD hidden";
                break;
            case 'p':
            case 'P':
                paletteIndex = (paletteIndex + 1) % PALETTES.size();
                status = "Palette switched to " + PALETTES.get(paletteIndex);
                break;
            case 'm':
            case 'M':
                renderMode = (renderMode + 1) % RENDER_MODES.length;
                if (renderMode == 0) {
                    protocol = HALF_BLOCK;
                } else if (renderMode == 1) {
                    protocol = BRAILLE;
                }
                status = "Mode: " + RENDER_MODES[renderMode];
                break;
            case '4':
                if (renderMode == 2) {
                    asciiRenderer.cycleColorDepth();
                    status = "ASCII color: " + asciiRenderer.colorDepthName();
                }
                break;
            case '5':
                if (renderMode == 2) {
                    asciiRenderer.cycleRamp();
                    status = "ASCII ramp: " + asciiRenderer.rampName();
                }
                break;
            case '+':
                brightness = Math.min(160, brightness + 10);
                status = "Brightness " + brightness + "%";
                break;
            case '-':
                brightness = Math.max(40, brightness - 10);
                status = "Brightness " + brightness + "%";
                break;
            case ']':
                contrast = Math.min(160, contrast + 10);
                status = "Contrast " + contrast + "%";
                break;
            case '[':
                contrast = Math.max(40, contrast - 10);
                status = "Contrast " + contrast + "%";
                break;
            case 'n':
            case 'N':
                if (!availableCameras.isEmpty()) {
                    cameraIndex = (cameraIndex + 1) % availableCameras.size();
                    CameraDevice next = availableCameras.get(cameraIndex);
                    status = "Switching to " + next.name() + "...";
                    switchCamera(next);
                } else if (!liveCamera) {
                    frameIndex = (frameIndex + 1) % frames.size();
                    status = "Advanced to next frame";
                }
                break;
            case 'b':
            case 'B':
                if (!availableCameras.isEmpty()) {
                    cameraIndex = (cameraIndex - 1 + availableCameras.size()) % availableCameras.size();
                    CameraDevice prev = availableCameras.get(cameraIndex);
                    status = "Switching to " + prev.name() + "...";
                    switchCamera(prev);
                } else if (!liveCamera) {
                    frameIndex = (frameIndex - 1 + frames.size()) % frames.size();
                    status = "Previous frame";
                }
                break;
            case 's':
            case 'S':
                saveSnapshot();
                break;
            default:
                break;
        }
    }

    private void flash(String message) {
        flashMessage = message;
        flashExpiry = System.currentTimeMillis() + 3000;
        status = message;
    }

    private void saveSnapshot() {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            String pngName = "tambocam_" + ts + ".png";
            String svgName = "tambocam_" + ts + ".svg";
            if (lastFrame != null) {
                javax.imageio.ImageIO.write(lastFrame.toBufferedImage(), "png", Paths.get(pngName).toFile());
            }
            if (lastBuffer != null) {
                export(lastBuffer).toFile(Paths.get(svgName));
            }
            flash("✔ Saved " + pngName + " + " + svgName);
        } catch (IOException e) {
            flash("Save failed: " + e.getMessage());
        }
    }

    private void render(Frame frame) {
        Rect area = frame.area();
        List<Rect> rows = Layout.vertical().constraints(
            Constraint.length(3),
            Constraint.fill(),
            Constraint.length(5)
        ).split(area);

        renderHeader(frame, rows.get(0));
        renderViewport(frame, rows.get(1));
        renderFooter(frame, rows.get(2));
    }

    private void renderHeader(Frame frame, Rect area) {
        String liveTag;
        Color liveColor;
        if (liveCamera && camera != null && camera.receivedFrames() > 0) {
            liveTag = "● LIVE";
            liveColor = Color.GREEN;
        } else if (liveCamera) {
            liveTag = "◌ WAITING";
            liveColor = Color.YELLOW;
        } else if (playing) {
            liveTag = "▶ PLAY";
            liveColor = Color.CYAN;
        } else {
            liveTag = "‖ PAUSED";
            liveColor = Color.YELLOW;
        }

        Paragraph header = Paragraph.builder()
            .text(Text.from(Line.from(
                Span.raw(" TAMBOCAM ").bold().reversed(),
                Span.raw("  "),
                Span.raw(sourceName).bold().cyan(),
                !availableCameras.isEmpty()
                    ? Span.raw(" [" + (cameraIndex + 1) + "/" + availableCameras.size() + "]").dim()
                    : Span.raw(""),
                Span.raw("  "),
                Span.raw(LocalTime.now().format(CLOCK)).green(),
                Span.raw("  "),
                Span.raw(liveTag).bold().fg(liveColor)
            )))
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.CYAN))
                .build())
            .build();
        frame.renderWidget(header, area);
    }

    private void renderViewport(Frame frame, Rect area) {
        List<Rect> cols = Layout.horizontal().constraints(
            Constraint.fill(),
            Constraint.length(hudVisible ? 28 : 0)
        ).split(area);

        ImageData currentFrame = liveCamera ? camera.latestFrame() : frames.get(frameIndex);
        if (liveCamera) {
            status = camera.status();
        }
        // Flash message overrides status for a few seconds
        if (flashMessage != null) {
            if (System.currentTimeMillis() < flashExpiry) {
                status = flashMessage;
            } else {
                flashMessage = null;
            }
        }

        ImageData adjusted = adjustFrame(currentFrame);
        lastFrame = adjusted;
        lastBuffer = frame.buffer();
        Rect viewport = cols.get(0);

        if (renderMode == 2) {
            // ASCII art mode — render border, then ASCII art inside
            Block border = Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.BLUE))
                .title(Title.from(Line.from(Span.raw(" " + sourceName + " [ASCII] ").blue())))
                .build();
            frame.renderWidget(border, viewport);
            Rect inner = border.inner(viewport);
            asciiRenderer.render(adjusted, inner, frame.buffer());
        } else {
            Image image = Image.builder()
                .data(adjusted)
                .scaling(ImageScaling.FILL)
                .protocol(protocol)
                .block(Block.builder()
                    .borders(Borders.ALL)
                    .borderType(BorderType.ROUNDED)
                    .borderStyle(Style.EMPTY.fg(Color.BLUE))
                    .title(Title.from(Line.from(Span.raw(" " + sourceName + " ").blue())))
                    .build())
                .build();
            frame.renderWidget(image, viewport);
        }

        if (hudVisible && cols.size() > 1 && cols.get(1).width() > 0) {
            frame.renderWidget(buildHud(), cols.get(1));
        }
    }

    private Paragraph buildHud() {
        return Paragraph.builder()
            .text(Text.from(
                Line.from(Span.raw(sourceName).bold().green(),
                    !availableCameras.isEmpty()
                        ? Span.raw(" (" + (cameraIndex + 1) + "/" + availableCameras.size() + ")").dim()
                        : Span.raw("")),
                Line.from(Span.raw("palette: ").dim(), Span.raw(PALETTES.get(paletteIndex)).yellow()),
                Line.from(Span.raw("mode: ").dim(), Span.raw(RENDER_MODES[renderMode]).cyan()),
                renderMode == 2
                    ? Line.from(Span.raw("ascii: ").dim(),
                        Span.raw(asciiRenderer.colorDepthName()).cyan(),
                        Span.raw(" / ").dim(),
                        Span.raw(asciiRenderer.rampName()).cyan())
                    : Line.from(Span.raw("protocol: ").dim(), Span.raw(protocol.name()).cyan()),
                Line.from(Span.raw("brightness: ").dim(), Span.raw(brightness + "%").magenta()),
                Line.from(Span.raw("contrast: ").dim(), Span.raw(contrast + "%").magenta()),
                Line.empty(),
                Line.from(Span.raw("status").bold().green()),
                Line.from(Span.raw(status).fg(
                    status.contains("LIVE") ? Color.GREEN
                        : status.contains("error") || status.contains("failed") ? Color.RED
                        : Color.YELLOW)),
                Line.empty(),
                Line.from(Span.raw(camera != null ? "cmd: " + camera.commandLine() : "").dim())
            ))
            .overflow(Overflow.WRAP_WORD)
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.GREEN))
                .title(Title.from(Line.from(Span.raw(" control HUD ").green())))
                .build())
            .build();
    }

    private void renderFooter(Frame frame, Rect area) {
        Paragraph footer = Paragraph.builder()
            .text(Text.from(
                Line.from(
                    key("space"), label(liveCamera ? " pause  " : " play/pause  "),
                    key("m"), label(" mode  "),
                    key("p"), label(" palette  "),
                    key("n/b"), label(!availableCameras.isEmpty() ? " cam  " : " frame  "),
                    key("s"), label(" save  "),
                    key("h"), label(" hud  "),
                    key("q"), label(" quit")
                ),
                Line.from(
                    key("+/-"), label(" bright  "),
                    key("[/]"), label(" contrast  "),
                    renderMode == 2
                        ? Span.raw("").dim()
                        : Span.raw("").dim(),
                    renderMode == 2 ? key("4") : Span.raw(""),
                    renderMode == 2 ? label(" ascii color  ") : Span.raw(""),
                    renderMode == 2 ? key("5") : Span.raw(""),
                    renderMode == 2 ? label(" ramp") : Span.raw("")
                )
            ))
            .block(Block.builder()
                .borders(Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.EMPTY.fg(Color.DARK_GRAY))
                .build())
            .build();
        frame.renderWidget(footer, area);
    }

    private Span key(String text) {
        return Span.raw(text).bold().yellow();
    }

    private Span label(String text) {
        return Span.raw(text).dim();
    }

    private ImageData adjustFrame(ImageData source) {
        // Skip pixel processing when all adjustments are at defaults
        if (brightness == 100 && contrast == 100 && paletteIndex == 0) {
            return source;
        }
        java.awt.image.BufferedImage in = source.toBufferedImage();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(in.getWidth(), in.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        double brightnessFactor = brightness / 100.0;
        double contrastFactor = contrast / 100.0;
        int tint = paletteIndex;
        for (int y = 0; y < in.getHeight(); y++) {
            for (int x = 0; x < in.getWidth(); x++) {
                int argb = in.getRGB(x, y);
                int a = (argb >>> 24) & 0xff;
                int r = (argb >>> 16) & 0xff;
                int g = (argb >>> 8) & 0xff;
                int b = argb & 0xff;
                r = tweak(r, brightnessFactor, contrastFactor);
                g = tweak(g, brightnessFactor, contrastFactor);
                b = tweak(b, brightnessFactor, contrastFactor);
                if (tint == 1) {
                    g = Math.min(255, (int) (g * 0.85));
                    b = Math.min(255, (int) (b * 0.45));
                } else if (tint == 2) {
                    r = Math.min(255, (int) (r * 0.7));
                    g = Math.min(255, (int) (g * 1.05));
                }
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return ImageData.fromBufferedImage(out);
    }

    private int tweak(int value, double brightnessFactor, double contrastFactor) {
        double centered = (value - 128) * contrastFactor + 128;
        int adjusted = (int) Math.round(centered * brightnessFactor);
        return Math.max(0, Math.min(255, adjusted));
    }

}
