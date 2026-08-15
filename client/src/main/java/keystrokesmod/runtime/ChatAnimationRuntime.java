package keystrokesmod.runtime;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.MathHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** External state for GuiNewChat, whose schema cannot change after injection. */
public final class ChatAnimationRuntime {
    /** Match the original mod's relaxed, continuous chat insertion timing. */
    private static final long DURATION_NS = 320_000_000L;
    private static final long CLEANUP_NS = 1_000_000_000L;
    private static final Map<GuiNewChat, State> STATES = new WeakHashMap<>();

    private ChatAnimationRuntime() {}

    private static synchronized State state(GuiNewChat chat) {
        State value = STATES.get(chat);
        if (value == null) { value = new State(); STATES.put(chat, value); }
        return value;
    }

    public static void update(GuiNewChat chat, List<ChatLine> lines, long now) {
        State state = state(chat);
        for (ChatLine line : lines) {
            if (line == null) continue;
            LineKey key = LineKey.of(line);
            if (!state.births.containsKey(key)) state.births.put(key, now);
        }
        if (now - state.lastCleanup >= CLEANUP_NS) {
            Set<LineKey> live = new HashSet<>();
            for (ChatLine line : lines) if (line != null) live.add(LineKey.of(line));
            state.births.keySet().retainAll(live);
            state.lastCleanup = now;
        }
    }

    public static double progress(GuiNewChat chat, ChatLine line, long now) {
        Long birth = state(chat).births.get(LineKey.of(line));
        return birth == null ? 1.0D
                : MathHelper.clamp_double((now - birth) / (double) DURATION_NS, 0.0D, 1.0D);
    }

    public static double ease(double progress) {
        double remaining = 1.0D - progress;
        return 1.0D - remaining * remaining * remaining;
    }

    private static final class State {
        final Map<LineKey, Long> births = new HashMap<>();
        long lastCleanup;
    }

    /**
     * Lunar may recreate ChatLine wrappers between rendered frames.  The
     * vanilla update counter plus formatted contents identify the logical
     * line without restarting its animation when only the wrapper changes.
     */
    private static final class LineKey {
        final int updateCounter;
        final String text;

        private LineKey(int updateCounter, String text) {
            this.updateCounter = updateCounter;
            this.text = text;
        }

        static LineKey of(ChatLine line) {
            return new LineKey(line.getUpdatedCounter(), line.getChatComponent().getFormattedText());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof LineKey)) return false;
            LineKey key = (LineKey) other;
            return updateCounter == key.updateCounter && text.equals(key.text);
        }

        @Override
        public int hashCode() {
            return 31 * updateCounter + text.hashCode();
        }
    }
}
