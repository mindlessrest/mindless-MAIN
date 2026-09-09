package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;

public class ScoreboardModule extends Module {
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();

    private static ScoreboardModule instance;

    private static SliderSetting font;
    private static SliderSetting scale;

    public ScoreboardModule() {
        super("Scoreboard", "Restyles the scoreboard with your own font.", category.render);
        this.registerSetting(font = new SliderSetting("Font", 0, FONT_OPTIONS));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.5, 2.0, 0.05));
        instance = this;
    }

    public static boolean isCustomScoreboardEnabled() {
        return instance != null && instance.isEnabled();
    }
public static MindlessFontRenderer getCustomFont() {
        if (instance == null || !instance.isEnabled() || font == null || isMinecraftFontSelected()) {
            return null;
        }

        int index = (int) font.getInput();
        if (index < 0 || index >= FONT_OPTIONS.length) {
            return null;
        }

        return FontManager.getHudRenderer(FONT_OPTIONS[index], 1.0f);
    }

    public static float getScale() {
        return scale == null ? 1.0f : (float) scale.getInput();
    }

    public static SliderSetting getScaleSetting() {
        return scale;
    }

    private static boolean isMinecraftFontSelected() {
        if (font == null) {
            return true;
        }
        int index = (int) font.getInput();
        return index < 0 || index >= FONT_OPTIONS.length || FontManager.isMinecraftFont(FONT_OPTIONS[index]);
    }
}
