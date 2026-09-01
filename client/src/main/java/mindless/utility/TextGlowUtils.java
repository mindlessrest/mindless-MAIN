package mindless.utility;

import mindless.utility.font.GlyphBatch;
import mindless.utility.font.MindlessFontRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StringUtils;
public final class TextGlowUtils {
private static final float DIAG = 0.70710678f;
    private static final float[][] RING8 = {
            { 1f, 0f }, { -1f, 0f }, { 0f, 1f }, { 0f, -1f },
            { DIAG, DIAG }, { -DIAG, DIAG }, { DIAG, -DIAG }, { -DIAG, -DIAG }
    };
private static final float[][] RING4 = {
            { DIAG, DIAG }, { -DIAG, DIAG }, { DIAG, -DIAG }, { -DIAG, -DIAG }
    };

    private static final float HALO_RADIUS = 1.1f;
    private static final float[] BLOOM_RADII = { 2.3f, 3.9f };
    private static final float[] BLOOM_ALPHA = { .17f, .075f };

    private TextGlowUtils() {
    }
private interface Pass {
        void draw(String text, float x, float y, int color);
    }
public static void drawGlow(MindlessFontRenderer font, String text, float x, float y, int color) {
        if (font == null) return;
        GlyphBatch.begin();
        try {
            draw((s, px, py, c) -> font.drawString(s, px, py, c, false), text, x, y, color);
        }
        finally {
            GlyphBatch.end();
        }
    }

    public static void drawGlow(FontRenderer font, String text, float x, float y, int color) {
        if (font == null) return;
        draw((s, px, py, c) -> font.drawString(s, px, py, c, false), text, x, y, color);
    }

    private static void draw(Pass pass, String text, float x, float y, int color) {
        if (text == null || text.isEmpty()) return;
        String plain = StringUtils.stripControlCodes(text);
        if (plain.isEmpty()) return;

        int sourceAlpha = (color >>> 24) & 0xFF;
        if (sourceAlpha == 0) sourceAlpha = 0xFF;
        int rgb = color & 0xFFFFFF;
        int halo = Math.min(175, Math.round(sourceAlpha * .62f)) << 24;
        for (float[] d : RING8) {
            pass.draw(plain, x + d[0] * HALO_RADIUS, y + d[1] * HALO_RADIUS, halo);
        }
        for (int ring = 0; ring < BLOOM_RADII.length; ring++) {
            float radius = BLOOM_RADII[ring];
            int alpha = Math.max(5, Math.round(sourceAlpha * BLOOM_ALPHA[ring]));
            int tint = (alpha << 24) | rgb;
            for (float[] d : (ring == 0 ? RING8 : RING4)) {
                pass.draw(plain, x + d[0] * radius, y + d[1] * radius, tint);
            }
        }
    }
}
