

import java.awt.image.BufferedImage;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.buffer.Cell;
import dev.tamboui.image.ImageData;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Renders an image as colored ASCII art directly into a tamboui buffer.
 * <p>
 * Each terminal cell maps to a region of pixels. The average brightness
 * selects a character from a ramp (dark → bright), and the average color
 * is applied as a foreground color. This matches the approach used by
 * <a href="https://gitlab.com/here_forawhile/terminalcam">terminalcam</a>.
 *
 * <h2>Color depth</h2>
 * Lower color depths produce fewer unique styles, which means tamboui's
 * buffer diff finds more identical cells between frames → less terminal I/O
 * → smoother rendering. This mirrors terminalcam's color quantization.
 * <ul>
 *   <li><strong>24bit</strong> — full RGB (most colors, most output)</li>
 *   <li><strong>256c</strong> — 6×6×6 color cube (good balance)</li>
 *   <li><strong>16c</strong> — ANSI 16 colors (fast, retro look)</li>
 *   <li><strong>gray</strong> — 24-step grayscale</li>
 *   <li><strong>green</strong> — green-on-black terminal look</li>
 *   <li><strong>off</strong> — characters only, default fg</li>
 * </ul>
 */
final class AsciiArtRenderer {

    /** terminalcam's long ramp (dark → bright). */
    static final String RAMP_LONG =
        " .'`^\",:;Il!i><~+_-?][}{1)(|/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";

    /** terminalcam's short ramp. */
    static final String RAMP_SHORT = " .:-=+*#%@";

    static final String[] COLOR_DEPTH_NAMES = {"24bit", "256c", "16c", "gray", "green", "off"};

    private String ramp = RAMP_LONG;
    private int colorDepthIndex;

    String rampName() {
        return ramp.length() > 20 ? "long" : "short";
    }

    void cycleRamp() {
        ramp = ramp.equals(RAMP_LONG) ? RAMP_SHORT : RAMP_LONG;
    }

    String colorDepthName() {
        return COLOR_DEPTH_NAMES[colorDepthIndex];
    }

    void cycleColorDepth() {
        colorDepthIndex = (colorDepthIndex + 1) % COLOR_DEPTH_NAMES.length;
    }

    /**
     * Renders the image as ASCII art into the given buffer area.
     *
     * @param data the image to render
     * @param area the target area in the buffer
     * @param buffer the buffer to write into
     */
    void render(ImageData data, Rect area, Buffer buffer) {
        BufferedImage img = data.toBufferedImage();
        int imgW = img.getWidth();
        int imgH = img.getHeight();
        int cols = area.width();
        int rows = area.height();

        if (cols <= 0 || rows <= 0) {
            return;
        }

        double scaleX = (double) imgW / cols;
        double scaleY = (double) imgH / rows;

        int rampLen = ramp.length();
        int depth = colorDepthIndex;

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int px0 = (int) (col * scaleX);
                int py0 = (int) (row * scaleY);
                int px1 = Math.min(imgW, (int) ((col + 1) * scaleX));
                int py1 = Math.min(imgH, (int) ((row + 1) * scaleY));

                if (px1 <= px0) {
                    px1 = px0 + 1;
                }
                if (py1 <= py0) {
                    py1 = py0 + 1;
                }

                long rSum = 0, gSum = 0, bSum = 0;
                int count = 0;
                for (int py = py0; py < py1; py++) {
                    for (int px = px0; px < px1; px++) {
                        int argb = img.getRGB(px, py);
                        rSum += (argb >> 16) & 0xff;
                        gSum += (argb >> 8) & 0xff;
                        bSum += argb & 0xff;
                        count++;
                    }
                }

                int r = (int) (rSum / count);
                int g = (int) (gSum / count);
                int b = (int) (bSum / count);

                // Luminance for character selection
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                int charIdx = (int) (lum / 255.0 * (rampLen - 1));
                charIdx = Math.max(0, Math.min(rampLen - 1, charIdx));
                char ch = ramp.charAt(charIdx);

                // Color based on depth — lower depths = fewer unique styles = faster diff
                Style style;
                switch (depth) {
                    case 0: // 24bit — full RGB
                        style = Style.EMPTY.fg(Color.rgb(r, g, b));
                        break;
                    case 1: // 256c — quantize to 6×6×6 cube (216 colors)
                        style = Style.EMPTY.fg(Color.rgb(
                            quantize6(r), quantize6(g), quantize6(b)));
                        break;
                    case 2: // 16c — snap to nearest ANSI primary
                        style = Style.EMPTY.fg(Color.rgb(
                            snap16(r), snap16(g), snap16(b)));
                        break;
                    case 3: // gray — 24-step grayscale
                        int grayStep = Math.round((float) lum * 23 / 255) * 255 / 23;
                        style = Style.EMPTY.fg(Color.rgb(grayStep, grayStep, grayStep));
                        break;
                    case 4: // green — brightness mapped to green channel
                        int greenVal = Math.max(20, (int) lum);
                        style = Style.EMPTY.fg(Color.rgb(0, greenVal, 0));
                        break;
                    default: // off — default terminal foreground
                        style = Style.EMPTY;
                        break;
                }

                int bx = area.x() + col;
                int by = area.y() + row;
                if (bx < buffer.area().width() && by < buffer.area().height()) {
                    buffer.set(bx, by, new Cell(String.valueOf(ch), style));
                }
            }
        }
    }

    /**
     * Quantizes a 0-255 channel value to the 6-step cube used by 256-color terminals.
     * Maps to one of: 0, 51, 102, 153, 204, 255.
     */
    private static int quantize6(int value) {
        return Math.round((float) value * 5 / 255) * 51;
    }

    /**
     * Snaps a 0-255 channel value to 0 or 255 (basic ANSI 16-color primary).
     */
    private static int snap16(int value) {
        return value > 85 ? 255 : 0;
    }
}
