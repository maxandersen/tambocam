

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

import dev.tamboui.image.ImageData;

final class SyntheticCameraFrames {

    private SyntheticCameraFrames() {
    }

    static ImageData create(int width, int height, int step) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(8, 14, 24), width, height, new Color(28, 52, 70)));
            g.fillRect(0, 0, width, height);

            g.setColor(new Color(255, 255, 255, 18));
            for (int y = 0; y < height; y += 6) {
                g.drawLine(0, y, width, y);
            }

            int orbit = step * 8;
            g.setColor(new Color(70, 200, 255, 140));
            g.fill(new Ellipse2D.Double(12 + orbit, 14, 36, 36));

            g.setColor(new Color(255, 210, 90, 180));
            g.fill(new RoundRectangle2D.Double(width / 2.0 - 24, 18 + (step % 3) * 3, 48, 58, 12, 12));

            g.setColor(new Color(255, 90, 120, 190));
            g.fill(new Ellipse2D.Double(width - 56 - orbit, height / 2.0 - 18, 42, 42));

            g.setStroke(new BasicStroke(3f));
            g.setColor(new Color(255, 255, 255, 120));
            g.draw(new RoundRectangle2D.Double(18, height - 34, width - 36, 18, 8, 8));

            int markerX = 26 + (step * 18) % (width - 60);
            g.setColor(new Color(120, 255, 160, 210));
            g.fill(new RoundRectangle2D.Double(markerX, height - 30, 24, 10, 6, 6));

            g.setColor(new Color(255, 255, 255, 220));
            g.drawString("TamboUI CAM", 12, height - 42);
            g.drawString("frame " + step, width - 52, height - 42);
        } finally {
            g.dispose();
        }
        return ImageData.fromBufferedImage(image);
    }
}
