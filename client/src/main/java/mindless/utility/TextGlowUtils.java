package mindless.utility;

import mindless.utility.font.RavenFontRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StringUtils;

/** Lightweight, crisp text outline matching the HUD editor's selected-text look. */
public final class TextGlowUtils {
    private static final float OFFSET = 0.75F;

    private TextGlowUtils() {
    }

    public static void drawGlow(RavenFontRenderer font, String text, float x, float y, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        String outlineText = StringUtils.stripControlCodes(text);
        int outlineColor = outlineColor(color);
        font.drawString(outlineText, x - OFFSET, y, outlineColor, false);
        font.drawString(outlineText, x + OFFSET, y, outlineColor, false);
        font.drawString(outlineText, x, y - OFFSET, outlineColor, false);
        font.drawString(outlineText, x, y + OFFSET, outlineColor, false);
    }

    public static void drawGlow(FontRenderer font, String text, float x, float y, int color) {
        if (font == null || text == null || text.isEmpty()) return;
        String outlineText = StringUtils.stripControlCodes(text);
        int outlineColor = outlineColor(color);
        font.drawString(outlineText, x - OFFSET, y, outlineColor, false);
        font.drawString(outlineText, x + OFFSET, y, outlineColor, false);
        font.drawString(outlineText, x, y - OFFSET, outlineColor, false);
        font.drawString(outlineText, x, y + OFFSET, outlineColor, false);
    }

    private static int outlineColor(int color) {
        int sourceAlpha = (color >>> 24) & 0xFF;
        if (sourceAlpha == 0) sourceAlpha = 0xFF;
        int alpha = Math.min(190, Math.max(52, Math.round(sourceAlpha * 0.75F)));
        return alpha << 24;
    }
}
