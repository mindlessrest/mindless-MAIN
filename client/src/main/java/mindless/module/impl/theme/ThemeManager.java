package mindless.module.impl.theme;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Gui;
import mindless.module.impl.client.Settings;
import mindless.module.impl.render.HUD;
import mindless.utility.font.FontManager;
import mindless.module.setting.Setting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Theme;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class ThemeManager extends Module {
public enum ClientTheme {
        Lavender  (0x9F8FD2, 0xEBEAE6, 0xA893FF, 0x6E6980, 4, 0xC9B8FF, 0x6A5AA8, 0x0E0D14),
        Orchid    (0xC77DFF, 0xF1E9F7, 0xD08BFF, 0x7A6A85, 4, 0xE4A9FF, 0x8A3FD0, 0x120C16),
        Rose      (0xFF8FB1, 0xFBEAF0, 0xFF7CA8, 0x8A6B76, 1, 0xFFC2D4, 0xE0517E, 0x150C10),
        Crimson   (0xE5484D, 0xF7E9EA, 0xF0575C, 0x8A6467, 1, 0xFF7A7E, 0xA31E22, 0x150A0B),
        Ember     (0xFF7A3C, 0xF9ECE4, 0xFF8A4C, 0x8A6E5E, 3, 0xFFB067, 0xD1401A, 0x150E09),
        Amber     (0xF5C242, 0xF8F1DF, 0xFFCE55, 0x8A7F5E, 5, 0xFFE08A, 0xD69A16, 0x14110A),
        Emerald   (0x2ECC71, 0xE7F5EC, 0x3BDD80, 0x5E7F6B, 9, 0x6BE8A0, 0x14874A, 0x09140E),
        Mint      (0x74E8C3, 0xE8F7F2, 0x86F0D0, 0x63847A, 9, 0xA8F5DD, 0x35B894, 0x0A1512),
        Ice       (0x7FD8F7, 0xE9F4F9, 0x8FE3FF, 0x62787F, 8, 0xB6ECFF, 0x3AA8CE, 0x0A1216),
        Ocean     (0x4B8BF5, 0xE8EEF9, 0x5C9BFF, 0x5F6C82, 7, 0x86B4FF, 0x1F5AC0, 0x090E17),
        Midnight  (0x5566C7, 0xE4E6F2, 0x6577DD, 0x5B6076, 7, 0x8290E8, 0x2C3782, 0x0A0C16),
        Monochrome(0xD6D6D6, 0xF0F0F0, 0xE4E4E4, 0x6E6E6E, 6, 0xFFFFFF, 0x8A8A8A, 0x111111);

        public final Color accent, text, enabled, disabled, gradFrom, gradTo, surface;
        public final int hudGradient;

        ClientTheme(int accent, int text, int enabled, int disabled,
                    int hudGradient, int gradFrom, int gradTo, int surface) {
            this.accent = new Color(accent);
            this.text = new Color(text);
            this.enabled = new Color(enabled);
            this.disabled = new Color(disabled);
            this.hudGradient = hudGradient;
            this.gradFrom = new Color(gradFrom);
            this.gradTo = new Color(gradTo);
            this.surface = new Color(surface);
        }
    }

    private static final ClientTheme[] THEMES = ClientTheme.values();
public static final int CUSTOM_INDEX = THEMES.length;
    private static final String[] THEME_NAMES = names();

    public static ButtonSetting create;
    public static ColorSetting customAccent, customText, customEnabled, customDisabled;
    public static ColorSetting customGradFrom, customGradTo, customSurface;
    public static SliderSetting customHudGradient;

    public static SliderSetting theme;
    public static ButtonSetting gradientAccent;
    public static SliderSetting gradientSpeed;
    public static ButtonSetting applyOnSelect, applyText, applyToggleColors, applyHudGradient;
    public static ButtonSetting applySurfaces;
    public static SliderSetting font;
    public static SliderSetting blurSize;
    public static SliderSetting rounding;
    public static ButtonSetting customizeHud;
    public static ColorSetting hudArrayListColor1, hudArrayListColor2;
    public static ButtonSetting hudArrayListGradient;
    public static ColorSetting hudWatermarkColor1, hudWatermarkColor2;
    public static ButtonSetting hudWatermarkGradient;
    public static ColorSetting hudStatsLabel, hudStatsValue;
    public static ButtonSetting customizeColors;
    public static ColorSetting colPanel, colPanelAlt, colRow, colRowHover;
    public static ColorSetting colControl, colControlHover, colBorder, colDivider;
    public static ColorSetting colDropdown, colDropdownBorder, colDropdownSelected;
private static final List<Setting> COLOURS = new ArrayList<>();

    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();
    private static int appliedFont = -1;
    private static double appliedBlur = -1;
private static boolean appearanceAdopted = false;
private static final List<Setting> EDITOR = new ArrayList<>();
private static int appliedIndex = -1;

    public ThemeManager() {
        super("Theme Manager", "Builds, saves and applies colour themes.", category.theme);
        this.registerSetting(create = new ButtonSetting("Create custom theme", false));

        GroupSetting maker = new GroupSetting("Custom theme");
        this.registerSetting(maker);
        this.registerSetting(customAccent = new ColorSetting(maker, "Accent", 159, 143, 210));
        this.registerSetting(customText = new ColorSetting(maker, "Text", 235, 234, 230));
        this.registerSetting(customEnabled = new ColorSetting(maker, "Enabled", 168, 147, 255));
        this.registerSetting(customDisabled = new ColorSetting(maker, "Disabled", 110, 105, 128));
        this.registerSetting(customGradFrom = new ColorSetting(maker, "Gradient from", 201, 184, 255));
        this.registerSetting(customGradTo = new ColorSetting(maker, "Gradient to", 106, 90, 168));
        this.registerSetting(customSurface = new ColorSetting(maker, "Background", 14, 13, 20));
        this.registerSetting(customHudGradient = new SliderSetting(maker, "HUD gradient", 0, Theme.THEMES_STRING));
        ButtonSetting copyPreset = new ButtonSetting("Copy selected preset", ThemeManager::copyPreset);
        ButtonSetting saveApply = new ButtonSetting("Save & apply", ThemeManager::saveCustom);
        this.registerSetting(copyPreset);
        this.registerSetting(saveApply);

        Collections.addAll(EDITOR, maker, customAccent, customText, customEnabled, customDisabled,
                customGradFrom, customGradTo, customSurface, customHudGradient, copyPreset, saveApply);
        for (Setting setting : EDITOR) setting.visible = false;
        this.registerSetting(customizeHud = new ButtonSetting("Customize HUD colors", false));
        GroupSetting hudColors = new GroupSetting("HUD Colors");
        this.registerSetting(hudColors);
        this.registerSetting(hudArrayListColor1 = new ColorSetting(hudColors, "Array List color 1", 201, 184, 255));
        this.registerSetting(hudArrayListColor2 = new ColorSetting(hudColors, "Array List color 2", 106, 90, 168));
        this.registerSetting(hudArrayListGradient = new ButtonSetting(hudColors, "Array List gradient", true));
        this.registerSetting(hudWatermarkColor1 = new ColorSetting(hudColors, "Watermark color 1", 201, 184, 255));
        this.registerSetting(hudWatermarkColor2 = new ColorSetting(hudColors, "Watermark color 2", 106, 90, 168));
        this.registerSetting(hudWatermarkGradient = new ButtonSetting(hudColors, "Watermark gradient", true));
        this.registerSetting(hudStatsLabel = new ColorSetting(hudColors, "HUD label color", 201, 184, 255));
        this.registerSetting(hudStatsValue = new ColorSetting(hudColors, "HUD value color", 255, 255, 255));
        this.registerSetting(customizeColors = new ButtonSetting("Customize colors", false));
        GroupSetting colours = new GroupSetting("Colors");
        this.registerSetting(colours);
        this.registerSetting(colPanel = new ColorSetting(colours, "Panel", 13, 16, 18, 232));
        this.registerSetting(colPanelAlt = new ColorSetting(colours, "Panel alt", 15, 18, 20, 236));
        this.registerSetting(colRow = new ColorSetting(colours, "Row", 24, 27, 28, 224));
        this.registerSetting(colRowHover = new ColorSetting(colours, "Row hover", 31, 34, 35, 236));
        this.registerSetting(colControl = new ColorSetting(colours, "Control", 7, 9, 10, 118));
        this.registerSetting(colControlHover = new ColorSetting(colours, "Control hover", 28, 30, 31, 150));
        this.registerSetting(colBorder = new ColorSetting(colours, "Border", 210, 210, 204, 52));
        this.registerSetting(colDivider = new ColorSetting(colours, "Divider", 210, 210, 204, 45));
        this.registerSetting(colDropdown = new ColorSetting(colours, "Dropdown background", 15, 18, 20, 236));
        this.registerSetting(colDropdownBorder = new ColorSetting(colours, "Dropdown border", 210, 210, 204, 52));
        this.registerSetting(colDropdownSelected = new ColorSetting(colours, "Dropdown selected", 159, 143, 210, 80));
        ButtonSetting seedColours = new ButtonSetting("Seed from theme", ThemeManager::seedColours);
        this.registerSetting(seedColours);

        Collections.addAll(COLOURS, colours, colPanel, colPanelAlt, colRow, colRowHover,
                colControl, colControlHover, colBorder, colDivider,
                colDropdown, colDropdownBorder, colDropdownSelected, seedColours);
        for (Setting setting : COLOURS) setting.visible = false;
        GroupSetting appearance = new GroupSetting("Appearance");
        this.registerSetting(appearance);
        this.registerSetting(font = new SliderSetting(appearance, "Font", defaultFontIndex(), FONT_OPTIONS));
        this.registerSetting(blurSize = new SliderSetting(appearance, "Blur size", "%", 0, 0, 100, 1));
        this.registerSetting(rounding = new SliderSetting(appearance, "Rounding", "%", 100, 0, 200, 5));

        this.registerSetting(theme = new SliderSetting("Theme", 0, THEME_NAMES));
        this.registerSetting(gradientAccent = new ButtonSetting("Gradient accent", false));
        this.registerSetting(gradientSpeed = new SliderSetting("Gradient speed", 1.0, 0.1, 5.0, 0.1));
        this.registerSetting(applyOnSelect = new ButtonSetting("Apply on select", true));
        this.registerSetting(applyText = new ButtonSetting("Apply text color", true));
        this.registerSetting(applySurfaces = new ButtonSetting("Apply GUI surfaces", true));
        this.registerSetting(applyToggleColors = new ButtonSetting("Apply toggle colors", true));
        this.registerSetting(applyHudGradient = new ButtonSetting("Apply HUD gradient", true));
        this.registerSetting(new ButtonSetting("Apply now", ThemeManager::applyNow));
        this.canBeEnabled = false;
    }

    private static String[] names() {
        String[] out = new String[THEMES.length + 1];
        for (int i = 0; i < THEMES.length; i++) out[i] = THEMES[i].name();
        out[THEMES.length] = "Custom";
        return out;
    }

    private static int index() {
        if (theme == null) return 0;
        int index = (int) theme.getInput();
        return (index < 0 || index > CUSTOM_INDEX) ? 0 : index;
    }

    private static boolean custom() {
        return index() == CUSTOM_INDEX;
    }

    private static ClientTheme preset() {
        int index = index();
        return THEMES[index >= THEMES.length ? 0 : index];
    }

    private static Color color(ColorSetting setting, Color fallback) {
        if (setting == null) return fallback;
        return new Color(setting.getRed(), setting.getGreen(), setting.getBlue());
    }

    private static Color accent()   { return custom() ? color(customAccent, preset().accent) : preset().accent; }
    private static Color text()     { return custom() ? color(customText, preset().text) : preset().text; }
    private static Color enabled()  { return custom() ? color(customEnabled, preset().enabled) : preset().enabled; }
    private static Color disabled() { return custom() ? color(customDisabled, preset().disabled) : preset().disabled; }
    private static Color gradFrom() { return custom() ? color(customGradFrom, preset().gradFrom) : preset().gradFrom; }
    private static Color gradTo()   { return custom() ? color(customGradTo, preset().gradTo) : preset().gradTo; }
public static boolean surfacesEnabled() {
        return applySurfaces != null && applySurfaces.isToggled();
    }
public static Color surface() {
        return custom() ? color(customSurface, preset().surface) : preset().surface;
    }

    private static int hudGradient() {
        if (custom()) return customHudGradient == null ? 0 : (int) customHudGradient.getInput();
        return preset().hudGradient;
    }
public static void poll() {
        syncEditorVisibility();
        syncAppearance();

        if (theme != null && applyOnSelect != null && applyOnSelect.isToggled() && index() != appliedIndex) {
            applyNow();
        }
        if (gradientAccent != null && gradientAccent.isToggled() && Gui.themeColor != null) {
            double speed = gradientSpeed == null ? 1.0 : Math.max(0.1, gradientSpeed.getInput());
            double phase = (Math.sin(System.currentTimeMillis() / (2400.0 / speed)) + 1.0) * 0.5;
            set(Gui.themeColor, Theme.convert(gradFrom(), gradTo(), phase));
        }
    }
private static void syncEditorVisibility() {
        Module module = ModuleManager.themeManager;
        if (module == null) return;
        if (create != null) {
            boolean open = create.isToggled();
            for (Setting setting : EDITOR) setting.setVisible(open, module);
        }
        if (customizeHud != null) {
            boolean open = customizeHud.isToggled();
            if (hudArrayListColor1 != null) hudArrayListColor1.setVisible(open, module);
            if (hudArrayListColor2 != null) hudArrayListColor2.setVisible(open, module);
            if (hudArrayListGradient != null) hudArrayListGradient.setVisible(open, module);
            if (hudWatermarkColor1 != null) hudWatermarkColor1.setVisible(open, module);
            if (hudWatermarkColor2 != null) hudWatermarkColor2.setVisible(open, module);
            if (hudWatermarkGradient != null) hudWatermarkGradient.setVisible(open, module);
            if (hudStatsLabel != null) hudStatsLabel.setVisible(open, module);
            if (hudStatsValue != null) hudStatsValue.setVisible(open, module);
        }
        if (customizeColors != null) {
            boolean open = customizeColors.isToggled();
            for (Setting setting : COLOURS) setting.setVisible(open, module);
        }
    }
private static void syncAppearance() {
        if (!appearanceAdopted) {
            appearanceAdopted = true;
            if (font != null && Gui.font != null) font.setValueRaw(Gui.font.getInput());
            if (blurSize != null && Gui.backgroundBlur != null) blurSize.setValueRaw(Gui.backgroundBlur.getInput());
            appliedFont = font == null ? -1 : (int) font.getInput();
            appliedBlur = blurSize == null ? -1 : blurSize.getInput();
            return;
        }

        if (font != null && Gui.font != null) {
            int index = (int) font.getInput();
            if (index != appliedFont) {
                appliedFont = index;
                Gui.font.setValueRaw(index);
                if (HUD.font != null) HUD.font.setValueRaw(index);
            }
        }
        if (blurSize != null && Gui.backgroundBlur != null) {
            double value = blurSize.getInput();
            if (value != appliedBlur) {
                appliedBlur = value;
                Gui.backgroundBlur.setValueRaw(value);
            }
        }
    }
private static int defaultFontIndex() {
        for (int i = 0; i < FONT_OPTIONS.length; i++) {
            if (!"Minecraft".equalsIgnoreCase(FONT_OPTIONS[i])) return i;
        }
        return 0;
    }

    public static boolean isHudCustomized() {
        return customizeHud != null && customizeHud.isToggled();
    }

    public static int getArrayListColor(double offset) {
        if (!isHudCustomized()) return Theme.getGradient(10, offset) | 0xFF000000;
        if (hudArrayListGradient != null && hudArrayListGradient.isToggled() && hudArrayListColor1 != null && hudArrayListColor2 != null) {
            Color c1 = new Color(hudArrayListColor1.getRed(), hudArrayListColor1.getGreen(), hudArrayListColor1.getBlue());
            Color c2 = new Color(hudArrayListColor2.getRed(), hudArrayListColor2.getGreen(), hudArrayListColor2.getBlue());
            double phase = (Math.sin(System.currentTimeMillis() / 2000.0 + offset * 0.1) + 1.0) * 0.5;
            return Theme.convert(c1, c2, phase).getRGB() | 0xFF000000;
        }
        return hudArrayListColor1 != null ? (hudArrayListColor1.getRGB() | 0xFF000000) : 0xFFFFFFFF;
    }

    public static int getWatermarkColor(double offset) {
        if (!isHudCustomized()) return Theme.getGradient(10, offset) | 0xFF000000;
        if (hudWatermarkGradient != null && hudWatermarkGradient.isToggled() && hudWatermarkColor1 != null && hudWatermarkColor2 != null) {
            Color c1 = new Color(hudWatermarkColor1.getRed(), hudWatermarkColor1.getGreen(), hudWatermarkColor1.getBlue());
            Color c2 = new Color(hudWatermarkColor2.getRed(), hudWatermarkColor2.getGreen(), hudWatermarkColor2.getBlue());
            double phase = (Math.sin(System.currentTimeMillis() / 2000.0 + offset * 0.1) + 1.0) * 0.5;
            return Theme.convert(c1, c2, phase).getRGB() | 0xFF000000;
        }
        return hudWatermarkColor1 != null ? (hudWatermarkColor1.getRGB() | 0xFF000000) : 0xFFFFFFFF;
    }

    public static int getStatsLabelColor() {
        if (!isHudCustomized() || hudStatsLabel == null) return Theme.getGradient(10, 0) | 0xFF000000;
        return hudStatsLabel.getRGB() | 0xFF000000;
    }

    public static int getStatsValueColor() {
        if (!isHudCustomized() || hudStatsValue == null) return 0xFFFFFFFF;
        return hudStatsValue.getRGB() | 0xFF000000;
    }
public static float roundingScale() {
        return rounding == null ? 1f : (float) Math.max(0d, rounding.getInput() / 100d);
    }
public static boolean colorsOverridden() {
        return customizeColors != null && customizeColors.isToggled();
    }

    private static int argb(ColorSetting setting, int fallback) {
        return setting == null ? fallback : setting.getColor();
    }

    public static int panel()            { return argb(colPanel, 0); }
    public static int panelAlt()         { return argb(colPanelAlt, 0); }
    public static int row()              { return argb(colRow, 0); }
    public static int rowHover()         { return argb(colRowHover, 0); }
    public static int control()          { return argb(colControl, 0); }
    public static int controlHover()     { return argb(colControlHover, 0); }
    public static int border()           { return argb(colBorder, 0); }
    public static int divider()          { return argb(colDivider, 0); }
    public static int dropdown()         { return argb(colDropdown, 0); }
    public static int dropdownBorder()   { return argb(colDropdownBorder, 0); }
    public static int dropdownSelected() { return argb(colDropdownSelected, 0); }
public static int themeCount() {
        return THEMES.length + 1;
    }

    public static String themeName(int i) {
        return (i < 0 || i >= THEMES.length) ? "Custom" : THEMES[i].name();
    }

    private static boolean isCustom(int i) {
        return i < 0 || i >= THEMES.length;
    }

    public static Color themeAccent(int i) {
        return isCustom(i) ? color(customAccent, ClientTheme.Lavender.accent) : THEMES[i].accent;
    }

    public static Color themeText(int i) {
        return isCustom(i) ? color(customText, ClientTheme.Lavender.text) : THEMES[i].text;
    }

    public static Color themeEnabled(int i) {
        return isCustom(i) ? color(customEnabled, ClientTheme.Lavender.enabled) : THEMES[i].enabled;
    }

    public static Color themeGradFrom(int i) {
        return isCustom(i) ? color(customGradFrom, ClientTheme.Lavender.gradFrom) : THEMES[i].gradFrom;
    }

    public static Color themeGradTo(int i) {
        return isCustom(i) ? color(customGradTo, ClientTheme.Lavender.gradTo) : THEMES[i].gradTo;
    }

    public static Color themeSurface(int i) {
        return isCustom(i) ? color(customSurface, ClientTheme.Lavender.surface) : THEMES[i].surface;
    }

    public static int selectedIndex() {
        return index();
    }
public static void select(int i) {
        if (theme == null) return;
        theme.setValueRaw(Math.max(0, Math.min(CUSTOM_INDEX, i)));
        applyNow();
    }
public static void seedColours() {
        Color base = surface();
        Color accent = accent();
        int r = base.getRed(), g = base.getGreen(), b = base.getBlue();
        setRgba(colPanel, r, g, b, 232);
        setRgba(colPanelAlt, scale(r, 1.18f), scale(g, 1.18f), scale(b, 1.18f), 236);
        setRgba(colRow, scale(r, 2.05f), scale(g, 2.05f), scale(b, 2.05f), 224);
        setRgba(colRowHover, scale(r, 2.70f), scale(g, 2.70f), scale(b, 2.70f), 236);
        setRgba(colControl, scale(r, .55f), scale(g, .55f), scale(b, .55f), 118);
        setRgba(colControlHover, scale(r, 2.40f), scale(g, 2.40f), scale(b, 2.40f), 150);
        setRgba(colBorder, accent.getRed(), accent.getGreen(), accent.getBlue(), 62);
        setRgba(colDivider, accent.getRed(), accent.getGreen(), accent.getBlue(), 50);
        setRgba(colDropdown, scale(r, 1.18f), scale(g, 1.18f), scale(b, 1.18f), 240);
        setRgba(colDropdownBorder, accent.getRed(), accent.getGreen(), accent.getBlue(), 62);
        setRgba(colDropdownSelected, accent.getRed(), accent.getGreen(), accent.getBlue(), 80);
    }

    private static int scale(int channel, float factor) {
        return Math.min(255, Math.round(channel * factor) + 3);
    }

    private static void setRgba(ColorSetting setting, int r, int g, int b, int a) {
        if (setting != null) setting.setColor(r, g, b, a);
    }
public static void applyNow() {
        if (theme == null) return;
        appliedIndex = index();

        set(Gui.themeColor, accent());
        if (applyText == null || applyText.isToggled()) set(Gui.themeTextColor, text());
        if (applyToggleColors == null || applyToggleColors.isToggled()) {
            set(Gui.enabledColor, enabled());
            set(Gui.disabledColor, disabled());
        }
        if ((applyHudGradient == null || applyHudGradient.isToggled()) && Settings.defaultTheme != null) {
            Settings.defaultTheme.setValueRaw(hudGradient());
        }
    }
public static void copyPreset() {
        ClientTheme source = preset();
        set(customAccent, source.accent);
        set(customText, source.text);
        set(customEnabled, source.enabled);
        set(customDisabled, source.disabled);
        set(customGradFrom, source.gradFrom);
        set(customGradTo, source.gradTo);
        set(customSurface, source.surface);
        if (customHudGradient != null) customHudGradient.setValueRaw(source.hudGradient);
    }
public static void saveCustom() {
        if (theme != null) theme.setValueRaw(CUSTOM_INDEX);
        applyNow();
    }

    private static void set(ColorSetting setting, Color color) {
        if (setting == null || color == null) return;
        setting.setColor(color.getRed(), color.getGreen(), color.getBlue());
    }
}
