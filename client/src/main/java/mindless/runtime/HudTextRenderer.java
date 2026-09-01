package mindless.runtime;

import mindless.utility.TextGlowUtils;
import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.gui.FontRenderer;
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
        vanilla.drawString(text, x, y, color, false);
    }
}
