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
import java.util.List;

final class NativeMediaBridge {
    private static final String[] LIBRARY_BASENAMES = new String[] { "MindlessMediaBridge", "RavenMediaBridge" };
    private static final String BUNDLED_LIBRARY_RESOURCE = "/mindless/native/MindlessMediaBridge.dll";
    private static volatile String lastLoadFailure;
    private final MediaBridgeLibrary library;
    private String cachedThumbnailBase64 = "";
    private byte[] cachedThumbnailBytes;
    private String cachedThumbnailKey = "";
    /** Same idea as the thumbnail cache: the DLL resends the whole lyrics array every poll. */
    private String cachedLyricsJson = "";
    private List<TimedLyrics.LyricsLine> cachedLyricsLines;

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

    public SystemMediaInfo poll() {
        if (library == null) {
            return SystemMediaInfo.unavailable();
        }

        Pointer pointer = null;
        try {
            pointer = library.GetNowPlayingJson();
            if (pointer == null) {
                return SystemMediaInfo.unavailable();
            }

            String json = pointer.getString(0);
            if (json == null || json.trim().isEmpty()) {
                return SystemMediaInfo.unavailable();
            }

            JsonElement jsonElement = new JsonParser().parse(new StringReader(json));
            if (jsonElement == null || !jsonElement.isJsonObject()) {
                return SystemMediaInfo.unavailable();
            }

            return parseMediaInfo(jsonElement.getAsJsonObject());
        }
        catch (Throwable ignored) {
            return SystemMediaInfo.unavailable();
        }
        finally {
            if (pointer != null) {
                try {
                    library.FreeMindlessString(pointer);
                }
                catch (Throwable ignored) {
                }
            }
        }
    }

    private SystemMediaInfo parseMediaInfo(JsonObject jsonObject) {
        if (jsonObject == null || !getBoolean(jsonObject, "available", false)) {
            return SystemMediaInfo.unavailable();
        }

        String thumbnailBase64 = getString(jsonObject, "thumbnailPngBase64", "");
        byte[] thumbnailBytes = null;
        String thumbnailKey = "";
        if (!thumbnailBase64.isEmpty()) {
            if (thumbnailBase64.equals(cachedThumbnailBase64)) {
                thumbnailBytes = cachedThumbnailBytes;
                thumbnailKey = cachedThumbnailKey;
            }
            else {
                try {
                    thumbnailBytes = Base64.getDecoder().decode(thumbnailBase64);
                    thumbnailKey = Integer.toHexString(Arrays.hashCode(thumbnailBytes));
                    cachedThumbnailBase64 = thumbnailBase64;
                    cachedThumbnailBytes = thumbnailBytes;
                    cachedThumbnailKey = thumbnailKey;
                }
                catch (IllegalArgumentException ignored) {
                    thumbnailBytes = null;
                    thumbnailKey = "";
                    clearThumbnailCache();
                }
            }
        }
        else {
            clearThumbnailCache();
        }

        boolean lyricsAvailable = getBoolean(jsonObject, "lyricsAvailable", false);
        List<TimedLyrics.LyricsLine> lyricsLines = null;
        if (lyricsAvailable && jsonObject.has("lyrics") && jsonObject.get("lyrics").isJsonArray()) {
            // The DLL resends the entire lyrics array on every poll -- 20 times a second. Parsing
            // it each time allocated a fresh list of fresh LyricsLine objects, and everything
            // downstream keyed its caches on that identity. Reuse the parse when the payload is
            // byte-for-byte the same, exactly as the thumbnail above already does.
            String lyricsJson = jsonObject.get("lyrics").toString();
            if (cachedLyricsLines != null && lyricsJson.equals(cachedLyricsJson)) {
                lyricsLines = cachedLyricsLines;
            } else {
                List<TimedLyrics.LyricsLine> parsed = new ArrayList<TimedLyrics.LyricsLine>();
                for (com.google.gson.JsonElement elem : jsonObject.get("lyrics").getAsJsonArray()) {
                    if (!elem.isJsonObject()) continue;
                    JsonObject lineObj = elem.getAsJsonObject();
                    long ts = getLong(lineObj, "timestampMs", -1L);
                    String text = getString(lineObj, "text", "");
                    if (ts >= 0) {
                        parsed.add(new TimedLyrics.LyricsLine(ts, text));
                    }
                }
                lyricsLines = java.util.Collections.unmodifiableList(parsed);
                cachedLyricsJson = lyricsJson;
                cachedLyricsLines = lyricsLines;
            }
        } else {
            cachedLyricsJson = "";
            cachedLyricsLines = null;
        }

        return new SystemMediaInfo(
                true,
                getString(jsonObject, "sourceApp", ""),
                getString(jsonObject, "title", ""),
                getString(jsonObject, "artist", ""),
                getString(jsonObject, "album", ""),
                getString(jsonObject, "status", ""),
                getLong(jsonObject, "positionMs", 0L),
                getLong(jsonObject, "durationMs", 0L),
                getLong(jsonObject, "sampledAtMs", System.currentTimeMillis()),
                thumbnailKey,
                thumbnailBytes,
                lyricsAvailable,
                lyricsLines
        );
    }

    private void clearThumbnailCache() {
        cachedThumbnailBase64 = "";
        cachedThumbnailBytes = null;
        cachedThumbnailKey = "";
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
        Pointer GetNowPlayingJson();
        void FreeMindlessString(Pointer pointer);
    }
}

