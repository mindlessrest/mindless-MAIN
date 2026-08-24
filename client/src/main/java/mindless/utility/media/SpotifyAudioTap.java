package mindless.utility.media;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The Java end of MindlessAudioTap.dll: PCM that came from Spotify and from nothing else.
 *
 * <p>The native side attaches to Spotify's process tree through the Windows process-loopback
 * device rather than to an output endpoint, so what arrives here has already been separated from
 * the rest of the machine by the audio engine. There is deliberately no fallback path: if the tap
 * cannot attach, this reports that and returns no samples, because the only other thing Windows
 * would give us is the mixed endpoint carrying Minecraft, Discord and everything else.
 *
 * <p>Loading follows {@link NativeMediaBridge}: unpack the bundled DLL beside the game's data
 * directory, then let JNA bind it from there.
 */
public final class SpotifyAudioTap {
    /** Mirrors the TapStatus enum in spotify_tap.cpp. */
    public static final int STATUS_STOPPED = 0;
    public static final int STATUS_SEARCHING = 1;
    public static final int STATUS_CAPTURING = 2;
    public static final int STATUS_UNSUPPORTED = 3;
    public static final int STATUS_FAILED = 4;

    private static final String LIBRARY_BASENAME = "MindlessAudioTap";
    private static final String BUNDLED_LIBRARY_RESOURCE = "/mindless/native/MindlessAudioTap.dll";

    private static volatile String lastLoadFailure;

    private final TapLibrary library;

    private SpotifyAudioTap(TapLibrary library) {
        this.library = library;
    }

    /** Loads the tap, or returns null when it is unavailable on this machine. */
    public static SpotifyAudioTap tryLoad() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            lastLoadFailure = "per-process audio capture is a Windows feature";
            return null;
        }

        lastLoadFailure = null;
        File extracted = extractBundledLibrary();

        if (extracted != null && extracted.isFile()) {
            try {
                return new SpotifyAudioTap((TapLibrary) Native.loadLibrary(
                        extracted.getAbsolutePath(), TapLibrary.class));
            }
            catch (Throwable throwable) {
                recordLoadFailure(extracted.getAbsolutePath(), throwable);
            }
        }

        try {
            return new SpotifyAudioTap((TapLibrary) Native.loadLibrary(
                    LIBRARY_BASENAME, TapLibrary.class));
        }
        catch (Throwable throwable) {
            recordLoadFailure(LIBRARY_BASENAME, throwable);
        }

        return null;
    }

    public static String getLastLoadFailure() {
        return lastLoadFailure;
    }

    /** Begins looking for Spotify. Cheap to call when already running. */
    public void start() {
        try {
            library.MindlessTapStart();
        }
        catch (Throwable ignored) {
        }
    }

    /** Detaches and releases the capture thread. */
    public void stop() {
        try {
            library.MindlessTapStop();
        }
        catch (Throwable ignored) {
        }
    }

    /**
     * Drains buffered audio into {@code destination} as interleaved stereo frames.
     *
     * @return frames written, which is zero when nothing new has arrived
     */
    public int poll(float[] destination, int maxFrames) {
        if (destination == null || maxFrames <= 0) {
            return 0;
        }
        try {
            return library.MindlessTapPoll(destination, Math.min(maxFrames, destination.length / 2));
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    public int getStatus() {
        try {
            return library.MindlessTapStatus();
        }
        catch (Throwable ignored) {
            return STATUS_FAILED;
        }
    }

    public int getSampleRate() {
        try {
            int rate = library.MindlessTapSampleRate();
            return rate > 0 ? rate : 48000;
        }
        catch (Throwable ignored) {
            return 48000;
        }
    }

    /** The Spotify process being listened to, or 0 when not attached. Diagnostic only. */
    public int getTargetPid() {
        try {
            return library.MindlessTapTargetPid();
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    public String getError() {
        try {
            Pointer pointer = library.MindlessTapError();
            if (pointer == null) {
                return "";
            }
            String message = pointer.getString(0);
            return message == null ? "" : message;
        }
        catch (Throwable ignored) {
            return "";
        }
    }

    /** A short line for the settings panel, so a silent visualizer can explain itself. */
    public String describeStatus() {
        switch (getStatus()) {
            case STATUS_CAPTURING:
                return "Capturing Spotify";
            case STATUS_SEARCHING:
                return "Waiting for Spotify";
            case STATUS_UNSUPPORTED:
                return "Needs Windows 10 build 20348 or newer";
            case STATUS_FAILED: {
                String error = getError();
                return error.isEmpty() ? "Capture failed" : "Capture failed: " + error;
            }
            default:
                return "Stopped";
        }
    }

    private static File extractBundledLibrary() {
        Minecraft mc = Minecraft.getMinecraft();
        File dataDir = mc != null ? mc.mcDataDir : null;
        if (dataDir == null) {
            return null;
        }

        InputStream inputStream = SpotifyAudioTap.class.getResourceAsStream(BUNDLED_LIBRARY_RESOURCE);
        if (inputStream == null) {
            lastLoadFailure = "MindlessAudioTap.dll is not bundled in this build";
            return null;
        }

        try {
            byte[] bundledBytes = readAllBytes(inputStream);
            if (bundledBytes.length == 0) {
                return null;
            }

            File mindlessDir = new File(dataDir, "mindless");
            if (!mindlessDir.exists() && !mindlessDir.mkdirs()) {
                return null;
            }

            File targetFile = new File(mindlessDir, "MindlessAudioTap.dll");
            // Length is enough to tell a stale copy from a current one, and re-writing a DLL that
            // is already mapped into this process would fail anyway.
            if (targetFile.isFile() && targetFile.length() == bundledBytes.length) {
                return targetFile;
            }

            FileOutputStream outputStream = null;
            try {
                outputStream = new FileOutputStream(targetFile, false);
                outputStream.write(bundledBytes);
                outputStream.flush();
            }
            finally {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    }
                    catch (IOException ignored) {
                    }
                }
            }
            return targetFile;
        }
        catch (IOException error) {
            lastLoadFailure = "could not unpack MindlessAudioTap.dll: " + error.getMessage();
            return null;
        }
        finally {
            try {
                inputStream.close();
            }
            catch (IOException ignored) {
            }
        }
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    private static void recordLoadFailure(String source, Throwable throwable) {
        if (throwable == null) {
            return;
        }
        String message = throwable.getClass().getSimpleName();
        if (throwable.getMessage() != null && !throwable.getMessage().trim().isEmpty()) {
            message += ": " + throwable.getMessage().trim();
        }
        lastLoadFailure = source + " -> " + message;
    }

    public interface TapLibrary extends Library {
        int MindlessTapStart();

        void MindlessTapStop();

        int MindlessTapPoll(float[] out, int maxFrames);

        int MindlessTapStatus();

        int MindlessTapSampleRate();

        int MindlessTapTargetPid();

        Pointer MindlessTapError();
    }
}
