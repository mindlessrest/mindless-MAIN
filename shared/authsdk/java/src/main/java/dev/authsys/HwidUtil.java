package dev.authsys;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Hardware ID generation utility. Combines CPU identifier and disk serial number
 * into a deterministic, machine-specific identifier.
 *
 * <p>Algorithm: reverse( sha256hex(cpu_id) + sha256hex(disk_serial) )
 *
 * <p>The result is cached after first computation — hardware doesn't change at runtime.
 * If either value is unavailable, empty string is used (sha256("") is deterministic).
 */
public final class HwidUtil {

    private static volatile String cachedHwid = null;

    private HwidUtil() {}

    /**
     * Returns the hardware ID for this machine. Cached after first call.
     *
     * @return 128-character hex string (reversed concatenation of two SHA-256 hashes)
     */
    public static String getHWID() {
        if (cachedHwid != null) {
            return cachedHwid;
        }

        synchronized (HwidUtil.class) {
            if (cachedHwid != null) {
                return cachedHwid;
            }

            String cpuId = getCpuId();
            String diskSerial = getDiskSerial();

            String cpuHash = CryptoUtil.sha256Hex(cpuId);
            String diskHash = CryptoUtil.sha256Hex(diskSerial);

            // Concatenate the two 64-char hex strings, then reverse the whole 128-char result
            String combined = cpuHash + diskHash;
            cachedHwid = new StringBuilder(combined).reverse().toString();
            return cachedHwid;
        }
    }

    /**
     * Gets CPU identifier. Windows: WMI ProcessorId. Linux: /proc/cpuinfo Serial
     * or concatenation of vendor_id + model name + cpu MHz.
     */
    private static String getCpuId() {
        try {
            if (isWindows()) {
                return runCommand("wmic", "cpu", "get", "ProcessorId", "/value")
                        .replace("ProcessorId=", "").trim();
            } else {
                return getLinuxCpuId();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Reads /proc/cpuinfo for Serial, or falls back to vendor_id + model name + cpu MHz. */
    private static String getLinuxCpuId() {
        try {
            List<String> lines = Files.readAllLines(Paths.get("/proc/cpuinfo"));

            // Try "Serial" field first (common on ARM/Raspberry Pi)
            for (String line : lines) {
                if (line.startsWith("Serial")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2) {
                        return parts[1].trim();
                    }
                }
            }

            // Fallback: concatenate vendor_id + model name + cpu MHz from first CPU block
            String vendorId = "";
            String modelName = "";
            String cpuMhz = "";

            for (String line : lines) {
                if (line.startsWith("vendor_id") && vendorId.isEmpty()) {
                    vendorId = extractValue(line);
                } else if (line.startsWith("model name") && modelName.isEmpty()) {
                    modelName = extractValue(line);
                } else if (line.startsWith("cpu MHz") && cpuMhz.isEmpty()) {
                    cpuMhz = extractValue(line);
                }
                // Stop after first processor block (empty line separates blocks)
                if (line.trim().isEmpty() && !vendorId.isEmpty()) {
                    break;
                }
            }

            return (vendorId + modelName + cpuMhz).replaceAll("\\s+", "");
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Gets disk serial number. Windows: WMI Win32_DiskDrive. Linux: /sys/block/sda/device/serial
     * or udevadm ID_SERIAL_SHORT.
     */
    private static String getDiskSerial() {
        try {
            if (isWindows()) {
                return runCommand("wmic", "diskdrive", "get", "SerialNumber", "/value")
                        .replace("SerialNumber=", "").trim();
            } else {
                return getLinuxDiskSerial();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Tries /sys/block/sda/device/serial, then falls back to udevadm. */
    private static String getLinuxDiskSerial() {
        try {
            Path serialPath = Paths.get("/sys/block/sda/device/serial");
            if (Files.exists(serialPath)) {
                String serial = new String(Files.readAllBytes(serialPath)).trim();
                if (!serial.isEmpty()) {
                    return serial;
                }
            }
        } catch (Exception ignored) {}

        // Fallback: parse udevadm output for ID_SERIAL_SHORT
        try {
            String output = runCommand("udevadm", "info", "--query=all", "--name=/dev/sda");
            for (String line : output.split("\n")) {
                if (line.contains("ID_SERIAL_SHORT=")) {
                    return line.split("ID_SERIAL_SHORT=", 2)[1].trim();
                }
            }
        } catch (Exception ignored) {}

        return "";
    }

    /** Runs a command and returns its stdout as a string. */
    private static String runCommand(String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        process.waitFor();
        return output.toString().trim();
    }

    /** Extracts the value after ":" in a /proc/cpuinfo line. */
    private static String extractValue(String line) {
        String[] parts = line.split(":", 2);
        return parts.length == 2 ? parts[1].trim() : "";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
