package mindless.runtime;

import net.minecraft.scoreboard.Score;

import java.util.ArrayList;
import java.util.List;

/** Render-thread scratch collections used by schema-preserving transformers. */
public final class HudRenderScratch {
    private static final ThreadLocal<Scratch> LOCAL = new ThreadLocal<Scratch>() {
        @Override
        protected Scratch initialValue() {
            return new Scratch();
        }
    };

    private HudRenderScratch() {
    }

    public static List<Score> scores() {
        Scratch scratch = LOCAL.get();
        scratch.scores.clear();
        return scratch.scores;
    }

    public static List<String> lines() {
        Scratch scratch = LOCAL.get();
        scratch.lines.clear();
        return scratch.lines;
    }

    private static final class Scratch {
        private final List<Score> scores = new ArrayList<Score>(16);
        private final List<String> lines = new ArrayList<String>(16);
    }
}
