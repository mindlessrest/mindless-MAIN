package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;

/**
 * How the chat box looks: its panel, its typeface, and whether messages are labelled with the
 * face of whoever sent them.
 *
 * <p>Everything here is read by the chat renderer rather than drawn from here. The renderer is a
 * mixin over vanilla's {@code drawChat}, which is the only place with access to the wrapped lines
 * and the scroll position; this module is where the choices live so they can be saved with a
 * profile and edited like any other module's.
 *
 * <p>The module being off leaves the chat exactly as it was: the panel, the animation and the
 * vanilla font are all the defaults, so switching it on and changing nothing changes nothing.
 */
public class ChatModule extends Module {
    private static final String[] FONT_OPTIONS = FontManager.getHudFontOptions();

    private static ChatModule instance;

    private static ButtonSetting background;
    private static SliderSetting backgroundOpacity;
    private static SliderSetting cornerRadius;
    private static SliderSetting font;
    private static SliderSetting fontScale;
    private static ButtonSetting textShadow;
    private static ButtonSetting playerHeads;
    private static SliderSetting headSize;
    private static SliderSetting lineSpacing;

    public ChatModule() {
        super("Chat", category.render);
        this.registerSetting(background = new ButtonSetting("Background", true));
        this.registerSetting(backgroundOpacity = new SliderSetting("Background opacity", 85.0, 0.0, 100.0, 1.0));
        this.registerSetting(cornerRadius = new SliderSetting("Corner radius", 8.0, 0.0, 14.0, 0.5));
        this.registerSetting(font = new SliderSetting("Font", 0, FONT_OPTIONS));
        this.registerSetting(fontScale = new SliderSetting("Font scale", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(textShadow = new ButtonSetting("Text shadow", true));
        this.registerSetting(playerHeads = new ButtonSetting("Player heads", false));
        this.registerSetting(headSize = new SliderSetting("Head size", 8.0, 6.0, 12.0, 0.5));
        this.registerSetting(lineSpacing = new SliderSetting("Line spacing", 0.0, -2.0, 6.0, 0.5));
        instance = this;
    }

    @Override
    public void guiUpdate() {
        boolean panel = background != null && background.isToggled();
        if (backgroundOpacity != null) {
            backgroundOpacity.setVisible(panel, this);
        }
        if (cornerRadius != null) {
            cornerRadius.setVisible(panel, this);
        }
        if (fontScale != null) {
            fontScale.setVisible(!isMinecraftFontSelected(), this);
        }
        if (headSize != null) {
            headSize.setVisible(playerHeads != null && playerHeads.isToggled(), this);
        }
    }

    private static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static boolean drawBackground() {
        return !active() || background == null || background.isToggled();
    }

    /** Panel opacity as a fraction, matching what the blur composite expects. */
    public static float backgroundOpacity() {
        if (!active() || backgroundOpacity == null) {
            return 0.85f;
        }
        return (float) Math.max(0.0, Math.min(1.0, backgroundOpacity.getInput() / 100.0));
    }

    public static float cornerRadius() {
        if (!active() || cornerRadius == null) {
            return -1.0f;
        }
        return (float) cornerRadius.getInput();
    }

    public static boolean textShadow() {
        return !active() || textShadow == null || textShadow.isToggled();
    }

    public static boolean playerHeads() {
        return active() && playerHeads != null && playerHeads.isToggled();
    }

    public static float headSize() {
        return !active() || headSize == null ? 8.0f : (float) headSize.getInput();
    }

    /** Extra pixels between lines, on top of vanilla's nine. */
    public static float lineSpacing() {
        return !active() || lineSpacing == null ? 0.0f : (float) lineSpacing.getInput();
    }

    /**
     * The face chat should be drawn in, or null to keep the Minecraft one.
     *
     * <p>Null rather than an adapter around the vanilla renderer: vanilla wraps the message text
     * into lines using its own metrics before this ever runs, so the default path has to stay
     * exactly the font those line breaks were measured with.
     */
    public static RavenFontRenderer getCustomFont() {
        if (!active() || font == null || isMinecraftFontSelected()) {
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
