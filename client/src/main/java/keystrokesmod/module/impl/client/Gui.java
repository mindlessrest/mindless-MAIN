package keystrokesmod.module.impl.client;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.font.FontManager;
import keystrokesmod.utility.font.RavenFontRenderer;

import java.awt.Color;

public class Gui extends Module {
    private static final String[] GUI_FONT_OPTIONS = FontManager.getHudFontOptions();

    public static ColorSetting enabledColor;
    public static ColorSetting disabledColor;
    public static ColorSetting themeColor;
    public static ColorSetting themeTextColor;
    public static final int UNSAVED_COLOR = new Color(114, 188, 250).getRGB();
    public static final int INVALID_COLOR = new Color(255, 80, 80).getRGB();

    public static SliderSetting guiScale;
    public static SliderSetting font;
    public static SliderSetting backgroundBlur;
    public static SliderSetting scrollSpeed;
    public static ButtonSetting removePlayerModel;
    public static ButtonSetting darkBackground;
    public static ButtonSetting removeWatermark;
    public static ButtonSetting rainBowOutlines;

    public Gui() {
        super("Gui", category.client, 54);
        this.registerSetting(guiScale = new SliderSetting("Gui scale", "x", 1.0, 0.5, 2.0, 0.01));
        this.registerSetting(font = new SliderSetting("Font", 0, GUI_FONT_OPTIONS));
        this.registerSetting(backgroundBlur = new SliderSetting("Background blur", "%", 0, 0, 100, 1));
        this.registerSetting(scrollSpeed = new SliderSetting("Scroll speed", 20, 2, 90, 1));
        this.registerSetting(themeColor = new ColorSetting("ClickGUI theme color", 159, 143, 210));
        this.registerSetting(themeTextColor = new ColorSetting("ClickGUI text color", 235, 234, 230));
        this.registerSetting(darkBackground = new ButtonSetting("Dark background", true));
        this.registerSetting(rainBowOutlines = new ButtonSetting("Rainbow outlines", false));
        this.registerSetting(removePlayerModel = new ButtonSetting("Remove player model", false));
        this.registerSetting(removeWatermark = new ButtonSetting("Remove watermark", false));
        this.registerSetting(enabledColor = new ColorSetting("Enabled color", 24, 154, 255));
        this.registerSetting(disabledColor = new ColorSetting("Disabled color", 192, 192, 192));
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck() && mc.currentScreen != Raven.clickGui) {
            mc.displayGuiScreen(Raven.clickGui);
            Raven.clickGui.initMain();
        }

        this.disable();
    }

    public static String getSelectedFontName() {
        if (font == null) {
            return "Sf-Regular";
        }

        int index = (int) Math.max(0, Math.min(font.getOptions().length - 1, font.getInput()));
        return font.getOptions()[index];
    }

    public static RavenFontRenderer getClickGuiHeaderFontRenderer() {
        return FontManager.getClickGuiHeaderRenderer(getSelectedFontName());
    }

    public static RavenFontRenderer getClickGuiSettingFontRenderer() {
        return FontManager.getClickGuiSettingRenderer(getSelectedFontName());
    }

    public static float getClickGuiScale() {
        if (guiScale == null) {
            return 1.0F;
        }

        return (float) Math.max(0.5D, Math.min(2.0D, guiScale.getInput()));
    }
}
