package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;

public class ScoreboardModule extends Module {
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();

    private static ScoreboardModule instance;

    private static SliderSetting font;
    private static SliderSetting fontScale;

    public ScoreboardModule() {
        super("Scoreboard", category.render);
        this.registerSetting(font = new SliderSetting("Font", 0, FONT_OPTIONS));
        this.registerSetting(fontScale = new SliderSetting("Font scale", 1.0, 0.5, 2.0, 0.05));
        instance = this;
    }

    @Override
    public void guiUpdate() {
        // Scale belongs to the bundled faces. The Minecraft font is drawn through the panel's own
        // fixed scale, and giving it a second one that does nothing would be a control that lies.
        if (fontScale != null) {
            fontScale.setVisible(!isMinecraftFontSelected(), this);
        }
    }

    public static boolean isCustomScoreboardEnabled() {
        return instance != null && instance.isEnabled();
    }

    /**
     * The face the scoreboard should be drawn in, or null to keep the Minecraft one.
     *
     * <p>Null rather than an adapter around the vanilla renderer on purpose: the vanilla path has
     * its own fixed panel scale and its own metrics, and routing it through the client's renderer
     * to save a branch would move every line by a pixel or two for everyone who never asked for a
     * different font.
     */
    public static RavenFontRenderer getCustomFont() {
        if (instance == null || !instance.isEnabled() || font == null || isMinecraftFontSelected()) {
            return null;
        }

        int index = (int) font.getInput();
        if (index < 0 || index >= FONT_OPTIONS.length) {
            return null;
        }

        float scale = fontScale == null ? 1.0f : (float) fontScale.getInput();
        return FontManager.getHudRenderer(FONT_OPTIONS[index], scale);
    }

    private static boolean isMinecraftFontSelected() {
        if (font == null) {
            return true;
        }
        int index = (int) font.getInput();
        return index < 0 || index >= FONT_OPTIONS.length || FontManager.isMinecraftFont(FONT_OPTIONS[index]);
    }
}
