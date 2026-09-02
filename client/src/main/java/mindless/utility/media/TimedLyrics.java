package mindless.utility.media;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TimedLyrics {
    private static final TimedLyrics EMPTY = new TimedLyrics(false, false, "No synced lyrics", Collections.<LyricsLine>emptyList());
    private static final TimedLyrics LOADING = new TimedLyrics(false, true, "Finding synced lyrics...", Collections.<LyricsLine>emptyList());

    private final boolean available;
    private final boolean loading;
    private final String statusMessage;
    private final List<LyricsLine> lines;

    private TimedLyrics(boolean available, boolean loading, String statusMessage, List<LyricsLine> lines) {
        this.available = available;
        this.loading = loading;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.lines = lines;
    }

    public static TimedLyrics empty() {
        return EMPTY;
    }

    public static TimedLyrics loading() {
        return LOADING;
    }

    public static TimedLyrics unavailable(String statusMessage) {
        return new TimedLyrics(false, false, statusMessage, Collections.<LyricsLine>emptyList());
    }

    public static TimedLyrics of(List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return empty();
        }
        return new TimedLyrics(true, false, "Synced lyrics", Collections.unmodifiableList(new ArrayList<LyricsLine>(lines)));
    }

    public boolean isAvailable() {
        return available;
    }

    public boolean isLoading() {
        return loading;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public List<LyricsLine> getLines() {
        return lines;
    }

    public int findLineIndex(long positionMs) {
        if (!available || lines.isEmpty()) {
            return -1;
        }

        int activeIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (positionMs >= lines.get(i).getTimestampMs()) {
                activeIndex = i;
            }
            else {
                break;
            }
        }
        return activeIndex;
    }

    public int findVisibleLineIndex(long positionMs) {
        int activeIndex = findLineIndex(positionMs);
        if (activeIndex < 0) {
            return -1;
        }
        LyricsLine line = lines.get(activeIndex);
        return line.getText().isEmpty() ? -1 : activeIndex;
    }

    public LyricsLine getLine(int index) {
        if (index < 0 || index >= lines.size()) {
            return null;
        }
        return lines.get(index);
    }

    public static final class LyricsLine {
        private final long timestampMs;
        private final String text;

        public LyricsLine(long timestampMs, String text) {
            this.timestampMs = Math.max(0L, timestampMs);
            this.text = text == null ? "" : text.trim();
        }

        public long getTimestampMs() {
            return timestampMs;
        }

        public String getText() {
            return text;
        }
    }
}

