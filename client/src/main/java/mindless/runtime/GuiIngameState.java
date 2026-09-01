package mindless.runtime;

import net.minecraft.scoreboard.Score;

import java.util.ArrayList;
import java.util.List;

public final class GuiIngameState {
public static final float SCOREBOARD_SCALE = 0.90f;
    private static final float BASE_PANEL_RADIUS = 8.0f;

    public static float panelRadius() {
        return BASE_PANEL_RADIUS * mindless.module.impl.theme.ThemeManager.roundingScale();
    }
    public static final int PANEL_FILL_COLOR = 0x55000000;
    public static final float PANEL_BLUR_OPACITY = 0.85f;
    public static final float HORIZONTAL_PADDING = 4.0f;
    public static final float VERTICAL_PADDING = 4.0f;

    public static final List<Score> visibleScores = new ArrayList<Score>();
    public static final List<String> visibleLines = new ArrayList<String>();

    private GuiIngameState() {}
}
