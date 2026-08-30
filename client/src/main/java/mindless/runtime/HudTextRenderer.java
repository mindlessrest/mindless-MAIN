package mindless.runtime;

import mindless.utility.TextGlowUtils;
import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.gui.FontRenderer;

/**
 * Draws HUD text through whichever face a module selected, falling back to Minecraft's.
 *
 * <p>The font settings on chat and the scoreboard did nothing, and had never done anything on this
 * client. Both of them were read in the mixin copies of these screens, which is the tree Forge
 * loads; what actually runs here is the transformer copy, and those had been written against
 * {@code mc.fontRendererObj} directly. A setting wired to code that is not on the path is
 * indistinguishable from a setting that is ignored.
 *
 * <p>Kept here rather than in either screen's state class because both screens need the same three
 * questions answered, and because the transformers that call it are inlined into their targets --
 * whatever they call has to be an ordinary static method on an ordinary class.
 *
 * <p>A null {@code custom} is the Minecraft font, not a missing one. Chat in particular depends on
 * that distinction: vanilla wraps message text into lines using its own metrics before any of this
 * runs, so the default path has to stay the exact font those breaks were measured with.
 */
public final class HudTextRenderer {
    private HudTextRenderer() {
    }

    public static int width(MindlessFontRenderer custom, FontRenderer vanilla, String text) {
        return custom != null ? custom.getStringWidth(text) : vanilla.getStringWidth(text);
    }

    public static int lineHeight(MindlessFontRenderer custom, FontRenderer vanilla) {
        return custom != null ? custom.getLineHeight() : vanilla.FONT_HEIGHT;
    }

    public static void draw(MindlessFontRenderer custom, FontRenderer vanilla, String text,
                            float x, float y, int color, boolean shadow, boolean glow) {
        if (custom != null) {
            if (glow) TextGlowUtils.drawGlow(custom, text, x, y, color);
            custom.drawString(text, x, y, color, shadow);
            return;
        }
        if (glow) TextGlowUtils.drawGlow(vanilla, text, x, y, color);
        if (shadow) {
            vanilla.drawStringWithShadow(text, x, y, color);
            return;
        }
        // The float overload, since callers place text on fractional offsets.
        vanilla.drawString(text, x, y, color, false);
    }
}
