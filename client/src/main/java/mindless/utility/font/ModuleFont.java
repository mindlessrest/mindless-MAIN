package mindless.utility.font;

import mindless.module.impl.render.HUD;
import mindless.module.setting.impl.SliderSetting;

/**
 * A module's own choice of face, with the option of deferring to the HUD's.
 *
 * <p>Several things on screen used to read {@link HUD#getSelectedFontName()} directly and so had no
 * say of their own: the Spotify widget and its lyric strip, the scaffold counter, the watermark,
 * the session numbers. Changing the arraylist's font changed all of them at once, which is the
 * opposite of a per-module setting.
 *
 * <p>Index zero is "Default" and still follows the HUD, so a config that never touches one of these
 * behaves exactly as it did. Anything else applies to that one module.
 */
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

    /** The list to hand a {@code SliderSetting}, with "Default" first. */
    public static String[] options() {
        return OPTIONS.clone();
    }

    /**
     * The bold cut of a family, where there is one.
     *
     * <p>Only the SF family ships a separate bold face; everything else has to serve as its own
     * heading weight. Kept here so the one place that knows this does not have to be the ClickGUI.
     */
    public static String boldVariant(String family) {
        if (family == null || family.isEmpty()) return family;
        return "Sf-Regular".equals(family) ? "Sf-Bold" : family;
    }

    /** The family such a setting names, or the HUD's when it is left on "Default". */
    public static String nameOf(SliderSetting setting) {
        if (setting == null) return HUD.getSelectedFontName();
        int index = (int) Math.max(0, Math.min(OPTIONS.length - 1, setting.getInput()));
        return index == 0 ? HUD.getSelectedFontName() : OPTIONS[index];
    }
}
