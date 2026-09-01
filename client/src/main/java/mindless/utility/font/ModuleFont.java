package mindless.utility.font;

import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.SliderSetting;
public final class ModuleFont {
    private static final String DEFAULT_LABEL = "Default";
    private static final String[] OPTIONS = build();

    private ModuleFont() {
    }

    private static String[] build() {
        String[] families = FontManager.getHudFontOptions();
        String[] options = new String[families.length + 1];
        options[0] = DEFAULT_LABEL;
        System.arraycopy(families, 0, options, 1, families.length);
        return options;
    }
public static String[] options() {
        return OPTIONS.clone();
    }
public static String boldVariant(String family) {
        if (family == null || family.isEmpty()) return family;
        return "Sf-Regular".equals(family) ? "Sf-Bold" : family;
    }
public static String nameOf(SliderSetting setting) {
        if (setting == null) return HUD.getSelectedFontName();
        int index = (int) Math.max(0, Math.min(OPTIONS.length - 1, setting.getInput()));
        return index == 0 ? HUD.getSelectedFontName() : OPTIONS[index];
    }
}
