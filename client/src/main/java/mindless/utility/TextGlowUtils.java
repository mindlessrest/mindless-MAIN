package mindless.utility;

import mindless.utility.font.GlyphBatch;
import mindless.utility.font.RavenFontRenderer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.StringUtils;

/**
 * Text glow: a tight dark halo for legibility with a soft coloured bloom around it.
 *
 * The previous implementation drew the string four times at a 0.75px offset in flat black.
 * That is a hard outline -- it has no falloff, so it reads as a sticker edge rather than as
 * light coming off the glyphs. A glow needs several rings at increasing radius with the alpha
 * decaying across them, tinted with the text's own colour so it looks emitted rather than
 * stamped on.
 */
public final class TextGlowUtils {

    /** Unit ring, eight directions. Diagonals are normalised so the ring is round, not square. */
    private static final float DIAG = 0.70710678f;
    private static final float[][] RING8 = {
            { 1f, 0f }, { -1f, 0f }, { 0f, 1f }, { 0f, -1f },
            { DIAG, DIAG }, { -DIAG, DIAG }, { DIAG, -DIAG }, { -DIAG, -DIAG }
    };
    /** Diagonals only, for the outermost pass where a full ring is not worth the draw calls. */
    private static final float[][] RING4 = {
            { DIAG, DIAG }, { -DIAG, DIAG }, { DIAG, -DIAG }, { -DIAG, -DIAG }
    };

    private static final float HALO_RADIUS = 1.1f;
    private static final float[] BLOOM_RADII = { 2.3f, 3.9f };
    private static final float[] BLOOM_ALPHA = { .17f, .075f };

    private TextGlowUtils() {
    }

    /** Draws one pass of the string; lets both font types share the ring logic below. */
    private interface Pass {
        void draw(String text, float x, float y, int color);
    }

    /**
     * Twenty-one passes over the same string, so the whole ring is held open as one batch.
     * Each pass differs only in offset and tint -- both of which travel per vertex -- so there is
     * nothing between them that would force a draw, and closing the batch once instead of
     * twenty-one times turns a glowing line of text into a single draw call.
     */
    public static void drawGlow(RavenFontRenderer font, String text, float x, float y, int color) {
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

        // Tight dark halo first. This is what keeps the text readable over a bright sky or a
        // white block; the coloured bloom on its own would wash out against them.
        int halo = Math.min(175, Math.round(sourceAlpha * .62f)) << 24;
        for (float[] d : RING8) {
            pass.draw(plain, x + d[0] * HALO_RADIUS, y + d[1] * HALO_RADIUS, halo);
        }

        // Coloured bloom. Alpha per pass is deliberately low because the rings overlap and
        // accumulate; raising it makes the text look outlined again rather than lit.
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
