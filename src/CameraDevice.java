

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers camera devices via ffmpeg/avfoundation (macOS).
 */
final class CameraDevice {

    private static final Pattern DEVICE_PATTERN =
        Pattern.compile("\\[(\\d+)]\\s+(.+)");

    private final int index;
    private final String name;

    CameraDevice(int index, String name) {
        this.index = index;
        this.name = name;
    }

    int index() {
        return index;
    }

    String name() {
        return name;
    }

    @Override
    public String toString() {
        return "[" + index + "] " + name;
    }

    /**
     * Discovers available camera devices and prints them to stdout.
     */
    static void listAndPrint() {
        System.out.println("Discovering cameras via ffmpeg...");
        List<CameraDevice> devices = discover();
        if (devices.isEmpty()) {
            System.out.println("No cameras found.");
            System.out.println("  macOS:   requires ffmpeg with avfoundation");
            System.out.println("  Linux:   requires /dev/videoN devices (v4l2)");
            System.out.println("  Windows: dshow discovery not yet supported");
        } else {
            System.out.println("Available cameras:");
            for (CameraDevice d : devices) {
                System.out.println("  " + d);
            }
            System.out.println();
            System.out.println("Usage: tambocam --camera=" + devices.get(0).index());
        }
    }

    /**
     * Discovers available camera devices using ffmpeg.
     *
     * @return an unmodifiable list of discovered devices, may be empty
     */
    static List<CameraDevice> discover() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return discoverAvfoundation();
        }
        if (os.contains("linux")) {
            return discoverV4l2();
        }
        // Windows dshow discovery not yet implemented
        return Collections.emptyList();
    }

    private static List<CameraDevice> discoverV4l2() {
        List<CameraDevice> devices = new ArrayList<CameraDevice>();
        try {
            for (int i = 0; i < 10; i++) {
                java.io.File dev = new java.io.File("/dev/video" + i);
                if (dev.exists()) {
                    // Try to get device name via v4l2-ctl
                    String name = "video" + i;
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                            "v4l2-ctl", "--device=/dev/video" + i, "--info"
                        );
                        pb.redirectErrorStream(true);
                        Process proc = pb.start();
                        BufferedReader reader = new BufferedReader(
                            new InputStreamReader(proc.getInputStream()));
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.contains("Card type")) {
                                int colon = line.indexOf(':');
                                if (colon >= 0) {
                                    name = line.substring(colon + 1).trim();
                                }
                            }
                        }
                        proc.waitFor();
                    } catch (Exception e) {
                        // v4l2-ctl not available, use generic name
                    }
                    devices.add(new CameraDevice(i, name));
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return Collections.unmodifiableList(devices);
    }

    private static List<CameraDevice> discoverAvfoundation() {
        List<CameraDevice> devices = new ArrayList<CameraDevice>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-hide_banner", "-f", "avfoundation",
                "-list_devices", "true", "-i", ""
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()));

            boolean inVideo = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("AVFoundation video devices")) {
                    inVideo = true;
                    continue;
                }
                if (line.contains("AVFoundation audio devices")) {
                    break;
                }
                if (inVideo) {
                    Matcher m = DEVICE_PATTERN.matcher(line);
                    if (m.find()) {
                        int idx = Integer.parseInt(m.group(1));
                        String name = m.group(2).trim();
                        devices.add(new CameraDevice(idx, name));
                    }
                }
            }
            proc.waitFor();
        } catch (Exception e) {
            // ffmpeg not available — return empty list
        }
        return Collections.unmodifiableList(devices);
    }
}
