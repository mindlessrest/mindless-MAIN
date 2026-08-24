package mindless.utility.media;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraft.client.Minecraft;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * The client's half of the media bridge.
 *
 * <p>The native side used to expose one call that returned everything -- title, artist, album,
 * position, the artwork as base64 and the entire timed-lyrics array -- and this class polled it
 * twenty times a second. Every one of those polls re-serialised a hundred kilobytes of artwork and
 * lyrics that had not changed since the track started, and most of the caching in here existed
 * purely to throw the duplicate work away again after paying for it.
 *
 * <p>It is now a call per thing, each polled at the rate that thing actually changes: position
 * every tick because it moves continuously and costs a hundred bytes, the track description only
 * when a track might have changed, artwork and lyrics only once per track and only if something is
 * actually going to draw them. A feature switched off in the mini player is never fetched at all.
 */
final class NativeMediaBridge {
    private static final String[] LIBRARY_BASENAMES = new String[] { "MindlessMediaBridge", "RavenMediaBridge" };
    private static final String BUNDLED_LIBRARY_RESOURCE = "/mindless/native/MindlessMediaBridge.dll";

    /** The source application changes only when a different player takes over the session. */
    private static final long APP_POLL_INTERVAL_MS = 1000L;
    /** Title/artist/album change once a track; this only has to be quick enough to look instant. */
    private static final long DESCRIPTION_POLL_INTERVAL_MS = 400L;
    /** How often to re-ask while a lyrics lookup is still in flight. */
    private static final long LYRICS_RETRY_INTERVAL_MS = 1000L;

    private static volatile String lastLoadFailure;

    private final MediaBridgeLibrary library;

    private String cachedSourceApp = "";
    private String cachedTitle = "";
    private String cachedArtist = "";
    private String cachedAlbum = "";
    private long lastAppPollAt;
    private long lastDescriptionPollAt;

    private String artworkTrackKey = "";
    private byte[] artworkBytes;
    private String artworkKey = "";

    private String lyricsTrackKey = "";
    private String lyricsState = "none";
    private long lastLyricsPollAt;
    private List<TimedLyrics.LyricsLine> lyricsLines;

    private NativeMediaBridge(MediaBridgeLibrary library) {
        this.library = library;
    }

    public static NativeMediaBridge tryLoad() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }

        lastLoadFailure = null;
        extractBundledLibrary();

        for (File candidate : getLibraryCandidates()) {
            if (!candidate.isFile()) {
                continue;
            }

            try {
                MediaBridgeLibrary library = (MediaBridgeLibrary) Native.loadLibrary(candidate.getAbsolutePath(), MediaBridgeLibrary.class);
                return new NativeMediaBridge(library);
            }
            catch (Throwable throwable) {
                recordLoadFailure(candidate.getAbsolutePath(), throwable);
            }
        }

        for (String baseName : LIBRARY_BASENAMES) {
            try {
                MediaBridgeLibrary library = (MediaBridgeLibrary) Native.loadLibrary(baseName, MediaBridgeLibrary.class);
                return new NativeMediaBridge(library);
            }
            catch (Throwable throwable) {
                recordLoadFailure(baseName, throwable);
            }
        }

        return null;
    }

    public static String getLastLoadFailure() {
        return lastLoadFailure;
    }

    /**
     * Reads the current session.
     *
     * @param wantLyrics  whether anything is going to draw lyrics; when false they are never fetched
     * @param wantArtwork whether anything is going to draw album art; when false it is never fetched
     */
    public SystemMediaInfo poll(boolean wantLyrics, boolean wantArtwork) {
        if (library == null) {
            return SystemMediaInfo.unavailable();
        }

        try {
            JsonObject progress = readJson(library.GetProgress());
            if (progress == null || !getBoolean(progress, "available", false)) {
                clearTrackCaches();
                return SystemMediaInfo.unavailable();
            }

            long now = System.currentTimeMillis();

            if (now - lastAppPollAt >= APP_POLL_INTERVAL_MS || cachedSourceApp.isEmpty()) {
                lastAppPollAt = now;
                JsonObject app = readJson(library.GetApp());
                if (app != null) {
                    cachedSourceApp = getString(app, "sourceApp", cachedSourceApp);
                }
            }

            if (now - lastDescriptionPollAt >= DESCRIPTION_POLL_INTERVAL_MS || cachedTitle.isEmpty()) {
                lastDescriptionPollAt = now;
                JsonObject description = readJson(library.GetSongDescription());
                if (description != null) {
                    cachedTitle = getString(description, "title", "");
                    cachedArtist = getString(description, "artist", "");
                    cachedAlbum = getString(description, "album", "");
                }
            }

            String trackKey = cachedSourceApp + "" + cachedTitle + "" + cachedArtist + "" + cachedAlbum;

            byte[] thumbnailBytes = null;
            String thumbnailKey = "";
            if (wantArtwork) {
                refreshArtwork(trackKey);
                thumbnailBytes = artworkBytes;
                thumbnailKey = artworkKey;
            }
            else {
                // Nothing is drawing it, so drop what we were holding rather than pinning a
                // bitmap for a panel that is switched off.
                artworkTrackKey = "";
                artworkBytes = null;
                artworkKey = "";
            }

            List<TimedLyrics.LyricsLine> lines = null;
            if (wantLyrics) {
                refreshLyrics(trackKey, now);
                lines = lyricsLines;
            }
            else {
                lyricsTrackKey = "";
                lyricsState = "none";
                lyricsLines = null;
            }

            return new SystemMediaInfo(
                    true,
                    cachedSourceApp,
                    cachedTitle,
                    cachedArtist,
                    cachedAlbum,
                    getString(progress, "status", ""),
                    getLong(progress, "positionMs", 0L),
                    getLong(progress, "durationMs", 0L),
                    getLong(progress, "sampledAtMs", now),
                    thumbnailKey,
                    thumbnailBytes,
                    lines != null && !lines.isEmpty(),
                    lines
            );
        }
        catch (Throwable ignored) {
            return SystemMediaInfo.unavailable();
        }
    }

    private void refreshArtwork(String trackKey) {
        if (trackKey.equals(artworkTrackKey)) {
            return;
        }

        JsonObject artwork = readJson(library.GetArtwork());
        artworkTrackKey = trackKey;
        artworkBytes = null;
        artworkKey = "";

        if (artwork == null || !getBoolean(artwork, "available", false)) {
            return;
        }

        String base64 = getString(artwork, "pngBase64", "");
        if (base64.isEmpty()) {
            return;
        }

        try {
            artworkBytes = Base64.getDecoder().decode(base64);
            artworkKey = Integer.toHexString(Arrays.hashCode(artworkBytes));
        }
        catch (IllegalArgumentException ignored) {
            artworkBytes = null;
            artworkKey = "";
        }
    }

    /**
     * Fetches lyrics at most once per track, and stops asking once the answer is known.
     *
     * <p>The native side reports a state rather than just a list. {@code absent} means every
     * provider was asked and none of them had this track, which is a permanent answer for as long
     * as it is playing -- so nothing here ever asks again. {@code pending} means a lookup is still
     * running, which is the only case worth re-polling, and then only about once a second.
     */
    private void refreshLyrics(String trackKey, long now) {
        if (!trackKey.equals(lyricsTrackKey)) {
            lyricsTrackKey = trackKey;
            lyricsState = "none";
            lyricsLines = null;
            lastLyricsPollAt = 0L;
        }

        if ("ready".equals(lyricsState) || "absent".equals(lyricsState)) {
            return;
        }

        if (now - lastLyricsPollAt < LYRICS_RETRY_INTERVAL_MS) {
            return;
        }
        lastLyricsPollAt = now;

        JsonObject lyrics = readJson(library.GetLyrics());
        if (lyrics == null) {
            return;
        }

        lyricsState = getString(lyrics, "state", "none");
        if (!"ready".equals(lyricsState) || !lyrics.has("lines") || !lyrics.get("lines").isJsonArray()) {
            return;
        }

        List<TimedLyrics.LyricsLine> parsed = new ArrayList<TimedLyrics.LyricsLine>();
        for (JsonElement element : lyrics.get("lines").getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject line = element.getAsJsonObject();
            long timestamp = getLong(line, "timestampMs", -1L);
            if (timestamp >= 0) {
                parsed.add(new TimedLyrics.LyricsLine(timestamp, getString(line, "text", "")));
            }
        }
        lyricsLines = Collections.unmodifiableList(parsed);
    }

    private void clearTrackCaches() {
        cachedSourceApp = "";
        cachedTitle = "";
        cachedArtist = "";
        cachedAlbum = "";
        artworkTrackKey = "";
        artworkBytes = null;
        artworkKey = "";
        lyricsTrackKey = "";
        lyricsState = "none";
        lyricsLines = null;
    }

    // ------------------------------------------------------------------ visualiser

    public void audioStart() {
        if (library != null) {
            try {
                library.AudioStart();
            }
            catch (Throwable ignored) {
            }
        }
    }

    public void audioStop() {
        if (library != null) {
            try {
                library.AudioStop();
            }
            catch (Throwable ignored) {
            }
        }
    }

    public int audioStatus() {
        if (library == null) {
            return 0;
        }
        try {
            return library.AudioStatus();
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    public void audioConfigure(int bars, double smoothing) {
        if (library != null) {
            try {
                library.AudioConfigure(bars, smoothing);
            }
            catch (Throwable ignored) {
            }
        }
    }

    /** Fills {@code bars} with heights in 0..1 and returns how many were written. */
    public int readSpectrum(float[] bars) {
        if (library == null || bars == null || bars.length == 0) {
            return 0;
        }
        try {
            return library.GetSpectrum(bars, bars.length);
        }
        catch (Throwable ignored) {
            return 0;
        }
    }

    public String audioError() {
        if (library == null) {
            return "";
        }
        try {
            return readString(library.AudioError());
        }
        catch (Throwable ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ plumbing

    private String readString(Pointer pointer) {
        if (pointer == null) {
            return "";
        }
        try {
            String value = pointer.getString(0, "UTF-8");
            return value == null ? "" : value;
        }
        finally {
            try {
                library.FreeMindlessString(pointer);
            }
            catch (Throwable ignored) {
            }
        }
    }

    /** Reads and frees a native string, then parses it. Null when it was not a JSON object. */
    private JsonObject readJson(Pointer pointer) {
        String json = readString(pointer);
        if (json.trim().isEmpty()) {
            return null;
        }

        try {
            JsonElement element = new JsonParser().parse(new StringReader(json));
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        }
        catch (Throwable ignored) {
            return null;
        }
    }

    private static File[] getLibraryCandidates() {
        Minecraft mc = Minecraft.getMinecraft();
        File dataDir = mc != null ? mc.mcDataDir : null;
        if (dataDir == null) {
            return new File[0];
        }

        File mindlessDir = new File(dataDir, "mindless");
        return new File[] {
                new File(mindlessDir, "MindlessMediaBridge.dll"),
                new File(mindlessDir, "RavenMediaBridge.dll"),
                new File(new File(mindlessDir, "media-helper"), "MindlessMediaBridge.dll"),
                new File(new File(mindlessDir, "media-helper"), "RavenMediaBridge.dll")
        };
    }

    private static void extractBundledLibrary() {
        Minecraft mc = Minecraft.getMinecraft();
        File dataDir = mc != null ? mc.mcDataDir : null;
        if (dataDir == null) {
            return;
        }

        InputStream inputStream = NativeMediaBridge.class.getResourceAsStream(BUNDLED_LIBRARY_RESOURCE);
        if (inputStream == null) {
            return;
        }

        try {
            byte[] bundledBytes = readAllBytes(inputStream);
            if (bundledBytes.length == 0) {
                return;
            }

            File mindlessDir = new File(dataDir, "mindless");
            if (!mindlessDir.exists() && !mindlessDir.mkdirs()) {
                return;
            }

            File targetFile = new File(mindlessDir, "MindlessMediaBridge.dll");
            if (targetFile.isFile() && targetFile.length() == bundledBytes.length) {
                return;
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
        }
        catch (IOException ignored) {
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

    private static boolean getBoolean(JsonObject jsonObject, String key, boolean fallback) {
        return jsonObject.has(key) && jsonObject.get(key).isJsonPrimitive() ? jsonObject.get(key).getAsBoolean() : fallback;
    }

    private static String getString(JsonObject jsonObject, String key, String fallback) {
        return jsonObject.has(key) && jsonObject.get(key).isJsonPrimitive() ? jsonObject.get(key).getAsString() : fallback;
    }

    private static long getLong(JsonObject jsonObject, String key, long fallback) {
        return jsonObject.has(key) && jsonObject.get(key).isJsonPrimitive() ? jsonObject.get(key).getAsLong() : fallback;
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

    public interface MediaBridgeLibrary extends Library {
        Pointer GetApp();

        Pointer GetSongDescription();

        Pointer GetProgress();

        Pointer GetArtwork();

        Pointer GetLyrics();

        void FreeMindlessString(Pointer pointer);

        void AudioStart();

        void AudioStop();

        int AudioStatus();

        int AudioTargetPid();

        void AudioConfigure(int bars, double smoothing);

        int GetSpectrum(float[] bars, int maxBars);

        Pointer AudioError();
    }
}
