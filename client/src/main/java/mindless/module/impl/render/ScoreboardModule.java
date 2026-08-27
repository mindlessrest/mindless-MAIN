package mindless.module.impl.render;

import mindless.module.Module;

public class ScoreboardModule extends Module {
    private static ScoreboardModule instance;

    public ScoreboardModule() {
        super("Scoreboard", category.render);
        instance = this;
    }

    public static boolean isCustomScoreboardEnabled() {
        return instance != null && instance.isEnabled();
    }
}
