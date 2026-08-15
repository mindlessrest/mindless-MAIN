package keystrokesmod.utility.media;

import java.util.List;

public final class SystemMediaInfo {
    private static final SystemMediaInfo UNAVAILABLE = new SystemMediaInfo(false, "", "", "", "", "", 0L, 0L, "", null);

    private final boolean available;
    private final String sourceApp;
    private final String title;
    private final String artist;
    private final String album;
    private final String status;
    private final long positionMs;
    private final long durationMs;
    private final long sampledAtMs;
    private final String albumArtKey;
    private final byte[] albumArtBytes;
    private final boolean nativeLyricsAvailable;
    private final List<TimedLyrics.LyricsLine> nativeLyricsLines;

    /** Convenience: no sampledAtMs, no native lyrics. */
    public SystemMediaInfo(boolean available, String sourceApp, String title, String artist, String album, String status,
                           long positionMs, long durationMs, String albumArtKey, byte[] albumArtBytes) {
        this(available, sourceApp, title, artist, album, status, positionMs, durationMs, System.currentTimeMillis(), albumArtKey, albumArtBytes, false, null);
    }

    /** Convenience: no sampledAtMs, with native lyrics (used by mergeMediaInfo). */
    public SystemMediaInfo(boolean available, String sourceApp, String title, String artist, String album, String status,
                           long positionMs, long durationMs, String albumArtKey, byte[] albumArtBytes,
                           boolean nativeLyricsAvailable, List<TimedLyrics.LyricsLine> nativeLyricsLines) {
        this(available, sourceApp, title, artist, album, status, positionMs, durationMs, System.currentTimeMillis(), albumArtKey, albumArtBytes, nativeLyricsAvailable, nativeLyricsLines);
    }

    /** Convenience: with sampledAtMs, no native lyrics (legacy compat). */
    public SystemMediaInfo(boolean available, String sourceApp, String title, String artist, String album, String status,
                           long positionMs, long durationMs, long sampledAtMs, String albumArtKey, byte[] albumArtBytes) {
        this(available, sourceApp, title, artist, album, status, positionMs, durationMs, sampledAtMs, albumArtKey, albumArtBytes, false, null);
    }

    /** Canonical constructor. */
    public SystemMediaInfo(boolean available, String sourceApp, String title, String artist, String album, String status,
                           long positionMs, long durationMs, long sampledAtMs, String albumArtKey, byte[] albumArtBytes,
                           boolean nativeLyricsAvailable, List<TimedLyrics.LyricsLine> nativeLyricsLines) {
        this.available = available;
        this.sourceApp = sourceApp == null ? "" : sourceApp;
        this.title = title == null ? "" : title;
        this.artist = artist == null ? "" : artist;
        this.album = album == null ? "" : album;
        this.status = status == null ? "" : status;
        this.positionMs = Math.max(0L, positionMs);
        this.durationMs = Math.max(0L, durationMs);
        this.sampledAtMs = Math.max(0L, sampledAtMs);
        this.albumArtKey = albumArtKey == null ? "" : albumArtKey;
        this.albumArtBytes = albumArtBytes;
        this.nativeLyricsAvailable = nativeLyricsAvailable;
        this.nativeLyricsLines = nativeLyricsLines;
    }

    public static SystemMediaInfo unavailable() {
        return UNAVAILABLE;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getSourceApp() {
        return sourceApp;
    }

    public String getTitle() {
        return title;
    }

    public String getArtist() {
        return artist;
    }

    public String getAlbum() {
        return album;
    }

    public String getStatus() {
        return status;
    }

    public long getPositionMs() {
        return positionMs;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getLivePositionMs() {
        if (!isPlaying()) {
            return positionMs;
        }

        long elapsed = Math.max(0L, System.currentTimeMillis() - sampledAtMs);
        long livePosition = positionMs + elapsed;
        if (durationMs > 0L) {
            return Math.min(durationMs, livePosition);
        }
        return livePosition;
    }

    public String getAlbumArtKey() {
        return albumArtKey;
    }

    public byte[] getAlbumArtBytes() {
        return albumArtBytes;
    }

    public boolean hasAlbumArt() {
        return albumArtBytes != null && albumArtBytes.length > 0 && !albumArtKey.isEmpty();
    }

    public boolean isNativeLyricsAvailable() {
        return nativeLyricsAvailable;
    }

    public List<TimedLyrics.LyricsLine> getNativeLyricsLines() {
        return nativeLyricsLines;
    }

    public boolean isPlaying() {
        return "playing".equalsIgnoreCase(status);
    }

    public boolean isPaused() {
        return "paused".equalsIgnoreCase(status);
    }
}

