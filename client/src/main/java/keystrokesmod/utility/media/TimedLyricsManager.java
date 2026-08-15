package keystrokesmod.utility.media;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import keystrokesmod.Raven;
import keystrokesmod.utility.NetworkUtils;

import java.io.StringReader;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TimedLyricsManager {
    private static final int MAX_CACHED_TRACKS = 128;
    private static final String LRCLIB_GET_URL = "https://lrclib.net/api/get";
    private static final String LRCLIB_SEARCH_URL = "https://lrclib.net/api/search";
    private static final String NETEASE_SEARCH_URL = "https://music.163.com/api/search/get";
    private static final String NETEASE_LYRIC_URL = "https://music.163.com/api/song/lyric";
    private static final Pattern LRC_TIMESTAMP_PATTERN = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?\\]");
    private static final Pattern DECORATION_PATTERN = Pattern.compile("\\s*(\\([^)]*\\)|\\[[^]]*\\]|\\{[^}]*\\})\\s*");
    private static final Pattern NETEASE_CREDIT_LINE_PATTERN = Pattern.compile("^[?ï¼Ÿ\u4e00-\u9fff]{1,8}\\s*[:ï¼š]");

    private final Map<String, TimedLyrics> cache = new LinkedHashMap<String, TimedLyrics>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TimedLyrics> eldest) {
            return size() > MAX_CACHED_TRACKS;
        }
    };

    private volatile String activeTrackKey = "";
    private volatile String pendingTrackKey = "";
    private volatile TimedLyrics currentLyrics = TimedLyrics.empty();
    private Future<?> pendingRequest;

    public synchronized void updateTrack(SystemMediaInfo mediaInfo) {
        String trackKey = buildTrackKey(mediaInfo);
        if (trackKey.isEmpty()) {
            cancelPendingRequest();
            activeTrackKey = "";
            pendingTrackKey = "";
            currentLyrics = TimedLyrics.empty();
            return;
        }

        boolean trackChanged = !trackKey.equals(activeTrackKey);
        activeTrackKey = trackKey;
        TimedLyrics cachedLyrics = cache.get(trackKey);
        if (cachedLyrics != null) {
            cancelPendingRequest();
            pendingTrackKey = "";
            currentLyrics = cachedLyrics;
            return;
        }

        if (trackChanged || !currentLyrics.isAvailable()) {
            currentLyrics = TimedLyrics.loading();
        }
        if (trackKey.equals(pendingTrackKey)) {
            return;
        }

        cancelPendingRequest();
        pendingTrackKey = trackKey;
        final SystemMediaInfo requestInfo = mediaInfo;
        final String requestTrackKey = trackKey;
        pendingRequest = Raven.getCachedExecutor().submit(new Runnable() {
            @Override
            public void run() {
                TimedLyrics lyrics = fetchTimedLyrics(requestInfo);
                cacheLyrics(requestTrackKey, lyrics);
                synchronized (TimedLyricsManager.this) {
                    if (requestTrackKey.equals(activeTrackKey)) {
                        currentLyrics = lyrics;
                    }
                    if (requestTrackKey.equals(pendingTrackKey)) {
                        pendingTrackKey = "";
                        pendingRequest = null;
                    }
                }
            }
        });
    }

    public TimedLyrics getCurrentLyrics() {
        return currentLyrics;
    }

    /**
     * Called when the native bridge supplies lyrics directly (new bridge).
     * Bypasses Java-side HTTP fetching — the DLL owns the fetch.
     * When lyricsAvailable=false the DLL is still loading; show LOADING state.
     * When lyricsAvailable=true and lines are non-empty, deliver them directly.
     */
    public synchronized void updateTrackFromNative(SystemMediaInfo mediaInfo,
            boolean lyricsAvailable, List<TimedLyrics.LyricsLine> lyricsLines) {
        String trackKey = buildTrackKey(mediaInfo);
        if (trackKey.isEmpty()) {
            cancelPendingRequest();
            activeTrackKey = "";
            pendingTrackKey = "";
            currentLyrics = TimedLyrics.empty();
            return;
        }

        boolean trackChanged = !trackKey.equals(activeTrackKey);
        activeTrackKey = trackKey;

        // DLL owns lyrics fetching — cancel any in-flight Java HTTP request
        cancelPendingRequest();
        pendingTrackKey = "";

        if (lyricsAvailable && lyricsLines != null && !lyricsLines.isEmpty()) {
            TimedLyrics lyrics = TimedLyrics.of(lyricsLines);
            cache.put(trackKey, lyrics);
            currentLyrics = lyrics;
        } else if (trackChanged || !currentLyrics.isAvailable()) {
            // DLL still fetching — show loading state
            currentLyrics = TimedLyrics.loading();
        }
    }

    private void cancelPendingRequest() {
        if (pendingRequest != null) {
            pendingRequest.cancel(true);
            pendingRequest = null;
        }
    }

    private synchronized void cacheLyrics(String trackKey, TimedLyrics lyrics) {
        if (!cache.containsKey(trackKey) && cache.size() >= MAX_CACHED_TRACKS) {
            java.util.Iterator<String> iterator = cache.keySet().iterator();
            if (iterator.hasNext()) cache.remove(iterator.next());
        }
        cache.put(trackKey, lyrics);
    }

    private static TimedLyrics fetchTimedLyrics(SystemMediaInfo mediaInfo) {
        try {
            JsonObject exactRecord = fetchExactRecord(mediaInfo);
            if (exactRecord != null) {
                TimedLyrics exactLyrics = parseLyricsRecord(exactRecord);
                if (exactLyrics.isAvailable()) {
                    return exactLyrics;
                }
            }

            JsonObject fallbackRecord = fetchFallbackRecord(mediaInfo);
            if (fallbackRecord != null) {
                TimedLyrics fallbackLyrics = parseLyricsRecord(fallbackRecord);
                if (fallbackLyrics.isAvailable()) {
                    return fallbackLyrics;
                }
            }

            TimedLyrics neteaseLyrics = fetchNeteaseTimedLyrics(mediaInfo);
            if (neteaseLyrics.isAvailable()) {
                return neteaseLyrics;
            }
        }
        catch (Exception ignored) {
        }

        return TimedLyrics.unavailable("No synced lyrics found");
    }

    private static JsonObject fetchExactRecord(SystemMediaInfo mediaInfo) {
        String title = mediaInfo.getTitle().trim();
        String artist = mediaInfo.getArtist().trim();
        if (title.isEmpty() || artist.isEmpty()) {
            return null;
        }

        StringBuilder url = new StringBuilder(LRCLIB_GET_URL)
                .append("?track_name=").append(urlEncode(title))
                .append("&artist_name=").append(urlEncode(artist));

        if (!mediaInfo.getAlbum().trim().isEmpty()) {
            url.append("&album_name=").append(urlEncode(mediaInfo.getAlbum().trim()));
        }
        if (mediaInfo.getDurationMs() > 0L) {
            url.append("&duration=").append(Math.max(1L, mediaInfo.getDurationMs() / 1000L));
        }

        String response = NetworkUtils.getTextFromURL(url.toString(), false, false);
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        JsonElement element = new JsonParser().parse(new StringReader(response));
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonObject fetchFallbackRecord(SystemMediaInfo mediaInfo) {
        String title = mediaInfo.getTitle().trim();
        if (title.isEmpty()) {
            return null;
        }

        StringBuilder url = new StringBuilder(LRCLIB_SEARCH_URL)
                .append("?track_name=").append(urlEncode(title));
        if (!mediaInfo.getArtist().trim().isEmpty()) {
            url.append("&artist_name=").append(urlEncode(mediaInfo.getArtist().trim()));
        }
        if (!mediaInfo.getAlbum().trim().isEmpty()) {
            url.append("&album_name=").append(urlEncode(mediaInfo.getAlbum().trim()));
        }

        String response = NetworkUtils.getTextFromURL(url.toString(), false, false);
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        JsonElement element = new JsonParser().parse(new StringReader(response));
        if (element == null || !element.isJsonArray()) {
            return null;
        }

        JsonArray array = element.getAsJsonArray();
        List<JsonObject> candidates = new ArrayList<JsonObject>();
        for (JsonElement candidate : array) {
            if (candidate == null || !candidate.isJsonObject()) {
                continue;
            }

            JsonObject object = candidate.getAsJsonObject();
            String syncedLyrics = getString(object, "syncedLyrics");
            if (syncedLyrics.isEmpty()) {
                continue;
            }

            candidates.add(object);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        final long targetDurationSeconds = Math.max(1L, mediaInfo.getDurationMs() / 1000L);
        final String targetTitle = normalizeKeyPart(mediaInfo.getTitle());
        final String targetArtist = normalizeKeyPart(mediaInfo.getArtist());
        final String targetAlbum = normalizeKeyPart(mediaInfo.getAlbum());
        candidates.sort(new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject first, JsonObject second) {
                return Long.compare(
                        getLyricsCandidateScore(first, targetTitle, targetArtist, targetAlbum, targetDurationSeconds),
                        getLyricsCandidateScore(second, targetTitle, targetArtist, targetAlbum, targetDurationSeconds)
                );
            }
        });

        return candidates.get(0);
    }

    private static TimedLyrics fetchNeteaseTimedLyrics(SystemMediaInfo mediaInfo) {
        String title = mediaInfo.getTitle().trim();
        String artist = mediaInfo.getArtist().trim();
        if (title.isEmpty() || artist.isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        String normalizedTitle = normalizeKeyPart(title);
        if (normalizedTitle.isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }
        String normalizedArtist = normalizeKeyPart(artist);
        long targetDurationMs = Math.max(0L, mediaInfo.getDurationMs());

        String searchBody = "s=" + urlEncode(title + " " + artist) + "&type=1&limit=10&offset=0";
        String searchResponse = NetworkUtils.postTextFromURL(NETEASE_SEARCH_URL, searchBody);
        if (searchResponse == null || searchResponse.trim().isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        JsonElement searchElement = new JsonParser().parse(new StringReader(searchResponse));
        if (searchElement == null || !searchElement.isJsonObject()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }
        JsonObject searchObject = searchElement.getAsJsonObject();
        if (getLong(searchObject, "code") != 200L) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }
        JsonObject result = searchObject.has("result") && searchObject.get("result").isJsonObject()
                ? searchObject.getAsJsonObject("result") : null;
        if (result == null || !result.has("songs") || !result.get("songs").isJsonArray()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        JsonArray songs = result.getAsJsonArray("songs");
        long bestSongId = 0L;
        long bestDurationDelta = Long.MAX_VALUE;
        for (JsonElement songElement : songs) {
            if (songElement == null || !songElement.isJsonObject()) {
                continue;
            }
            JsonObject song = songElement.getAsJsonObject();
            if (!normalizeKeyPart(getString(song, "name")).equals(normalizedTitle)) {
                continue;
            }
            if (!artistMatches(normalizedArtist, normalizeKeyPart(getArtistNames(song)))) {
                continue;
            }
            long songId = getLong(song, "id");
            if (songId <= 0L) {
                continue;
            }
            long songDuration = Math.max(0L, getLong(song, "duration"));
            long durationDelta = targetDurationMs > 0L && songDuration > 0L
                    ? Math.abs(songDuration - targetDurationMs) : 0L;
            if (durationDelta < bestDurationDelta) {
                bestDurationDelta = durationDelta;
                bestSongId = songId;
            }
        }

        if (bestSongId <= 0L) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        String lyricBody = "id=" + bestSongId + "&lv=-1&kv=-1&tv=-1";
        String lyricResponse = NetworkUtils.postTextFromURL(NETEASE_LYRIC_URL, lyricBody);
        if (lyricResponse == null || lyricResponse.trim().isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        JsonElement lyricElement = new JsonParser().parse(new StringReader(lyricResponse));
        if (lyricElement == null || !lyricElement.isJsonObject()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }
        JsonObject lyricObject = lyricElement.getAsJsonObject();
        JsonObject lrc = lyricObject.has("lrc") && lyricObject.get("lrc").isJsonObject()
                ? lyricObject.getAsJsonObject("lrc") : null;
        if (lrc == null) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }
        String lrcText = getString(lrc, "lyric");
        if (lrcText.isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        List<TimedLyrics.LyricsLine> lines = parseSyncedLyrics(stripNeteaseCreditLines(lrcText));
        return lines.isEmpty() ? TimedLyrics.unavailable("No synced lyrics found") : TimedLyrics.of(lines);
    }

    private static String getArtistNames(JsonObject song) {
        StringBuilder artistNames = new StringBuilder();
        if (song.has("artists") && song.get("artists").isJsonArray()) {
            JsonArray artists = song.getAsJsonArray("artists");
            for (JsonElement artistElement : artists) {
                if (artistElement == null || !artistElement.isJsonObject()) {
                    continue;
                }
                String artistName = getString(artistElement.getAsJsonObject(), "name");
                if (artistName.isEmpty()) {
                    continue;
                }
                if (artistNames.length() > 0) {
                    artistNames.append(" / ");
                }
                artistNames.append(artistName);
            }
        }
        return artistNames.toString();
    }

    private static boolean artistMatches(String expected, String candidate) {
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        return candidate.equals(expected) || candidate.contains(expected) || expected.contains(candidate);
    }

    private static String stripNeteaseCreditLines(String lrcText) {
        if (lrcText == null || lrcText.isEmpty()) {
            return lrcText;
        }
        String[] rawLines = lrcText.split("\\r?\\n");
        StringBuilder builder = new StringBuilder();
        for (String rawLine : rawLines) {
            if (rawLine == null || rawLine.trim().isEmpty()) {
                continue;
            }
            Matcher matcher = LRC_TIMESTAMP_PATTERN.matcher(rawLine);
            int lastMatchEnd = -1;
            while (matcher.find()) {
                lastMatchEnd = matcher.end();
            }
            String text = lastMatchEnd >= 0 && lastMatchEnd < rawLine.length() ? rawLine.substring(lastMatchEnd).trim() : "";
            if (text.isEmpty()) {
                continue;
            }
            if (NETEASE_CREDIT_LINE_PATTERN.matcher(text).find()) {
                continue;
            }
            builder.append(rawLine).append("\n");
        }
        return builder.toString();
    }

    private static TimedLyrics parseLyricsRecord(JsonObject record) {
        String syncedLyrics = getString(record, "syncedLyrics");
        if (syncedLyrics.isEmpty()) {
            return TimedLyrics.unavailable("No synced lyrics found");
        }

        List<TimedLyrics.LyricsLine> lines = parseSyncedLyrics(syncedLyrics);
        return lines.isEmpty() ? TimedLyrics.unavailable("No synced lyrics found") : TimedLyrics.of(lines);
    }

    private static List<TimedLyrics.LyricsLine> parseSyncedLyrics(String syncedLyrics) {
        ArrayList<TimedLyrics.LyricsLine> lines = new ArrayList<TimedLyrics.LyricsLine>();
        if (syncedLyrics == null || syncedLyrics.trim().isEmpty()) {
            return lines;
        }

        String[] rawLines = syncedLyrics.split("\\r?\\n");
        for (String rawLine : rawLines) {
            if (rawLine == null || rawLine.trim().isEmpty()) {
                continue;
            }

            Matcher matcher = LRC_TIMESTAMP_PATTERN.matcher(rawLine);
            ArrayList<Long> timestamps = new ArrayList<Long>();
            int lastMatchEnd = -1;
            while (matcher.find()) {
                timestamps.add(parseTimestampMs(matcher.group(1), matcher.group(2), matcher.group(3)));
                lastMatchEnd = matcher.end();
            }

            if (timestamps.isEmpty()) {
                continue;
            }

            String text = lastMatchEnd >= 0 && lastMatchEnd < rawLine.length() ? rawLine.substring(lastMatchEnd).trim() : "";
            if (text.isEmpty()) {
                continue;
            }

            for (Long timestamp : timestamps) {
                lines.add(new TimedLyrics.LyricsLine(timestamp, text));
            }
        }

        lines.sort(new Comparator<TimedLyrics.LyricsLine>() {
            @Override
            public int compare(TimedLyrics.LyricsLine first, TimedLyrics.LyricsLine second) {
                return Long.compare(first.getTimestampMs(), second.getTimestampMs());
            }
        });
        return lines;
    }

    private static long parseTimestampMs(String minutesPart, String secondsPart, String fractionPart) {
        long minutes = parseLong(minutesPart);
        long seconds = parseLong(secondsPart);
        long milliseconds = 0L;
        if (fractionPart != null && !fractionPart.isEmpty()) {
            if (fractionPart.length() == 1) {
                milliseconds = parseLong(fractionPart) * 100L;
            }
            else if (fractionPart.length() == 2) {
                milliseconds = parseLong(fractionPart) * 10L;
            }
            else {
                milliseconds = parseLong(fractionPart.substring(0, Math.min(3, fractionPart.length())));
            }
        }
        return minutes * 60000L + seconds * 1000L + milliseconds;
    }

    private static String buildTrackKey(SystemMediaInfo mediaInfo) {
        if (mediaInfo == null || !mediaInfo.isAvailable()) {
            return "";
        }

        String title = normalizeKeyPart(mediaInfo.getTitle());
        String artist = normalizeKeyPart(mediaInfo.getArtist());
        if (title.isEmpty() || artist.isEmpty()) {
            return "";
        }

        return title + "|" + artist;
    }

    private static String normalizeKeyPart(String value) {
        if (value == null) {
            return "";
        }

        String normalized = value.trim().toLowerCase();
        normalized = DECORATION_PATTERN.matcher(normalized).replaceAll(" ");
        normalized = normalized.replace('-', ' ');
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private static long getLyricsCandidateScore(JsonObject candidate, String targetTitle,
                                                String targetArtist, String targetAlbum,
                                                long targetDurationSeconds) {
        long titlePenalty = getTextMatchPenalty(targetTitle, normalizeKeyPart(getString(candidate, "trackName")));
        long artistPenalty = getTextMatchPenalty(targetArtist, normalizeKeyPart(getString(candidate, "artistName")));
        long albumPenalty = targetAlbum.isEmpty() ? 0L
                : getTextMatchPenalty(targetAlbum, normalizeKeyPart(getString(candidate, "albumName")));
        long candidateDuration = Math.max(0L, getLong(candidate, "duration"));
        long durationPenalty = candidateDuration <= 0L ? 600L
                : Math.min(600L, Math.abs(candidateDuration - targetDurationSeconds));
        return titlePenalty * 1_000_000L
                + artistPenalty * 100_000L
                + albumPenalty * 10_000L
                + durationPenalty;
    }

    private static long getTextMatchPenalty(String target, String candidate) {
        if (target == null || target.isEmpty()) {
            return 0L;
        }
        if (candidate == null || candidate.isEmpty()) {
            return 4L;
        }
        if (target.equals(candidate)) {
            return 0L;
        }
        if (target.contains(candidate) || candidate.contains(target)) {
            return 1L;
        }
        return 4L;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private static String getString(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static long getLong(JsonObject object, String key) {
        return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsLong() : 0L;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        }
        catch (Exception ignored) {
            return 0L;
        }
    }
}
