package mindless.utility.font;

import mindless.utility.ScaledResolutionCache;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class GlyphFontRenderer implements MindlessFontRenderer {
    private static final int FIRST_GLYPH = 0;
    private static final int LAST_GLYPH = 255;
    private static final int CHANNEL_MASK = 0xFF;
    private static final int GLYPH_MARGIN = 4;
    private static final float MIN_RENDER_SCALE = 2.0f;
    private static final float QUALITY_MULTIPLIER = 2.0f;
    /** Ceiling on a boosted atlas, in pixels of rasterised font size. */
    private static final float MAX_RASTERISED_GLYPH_SIZE = 64.0f;
    private static final String ALPHABET = "ABCDEFGHOKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final String COLOR_CODES = "0123456789abcdefklmnor";
    private static final GlyphData EMPTY_GLYPH = new GlyphData(null, 0.0f, 0.0f, 0.0f, 0.0f, 0, 0);

    private final Font renderFont;
    private final boolean antiAlias;
    private final FontRenderContext fontRenderContext;
    private final GlyphAtlas atlas;
    private final GlyphData[] defaultGlyphs = new GlyphData[LAST_GLYPH + 1];
    private final Map<Character, GlyphData> extendedGlyphs = new ConcurrentHashMap<Character, GlyphData>();
    private final float drawScale;
    private final float rawScale;
    private final float rawTextTop;
    private final float rawTextBottom;
    private final float fontHeight;
    private final float lineHeight;
    private boolean destroyed;

    public GlyphFontRenderer(Font sourceFont, boolean antiAlias) {
        this(sourceFont, antiAlias, 1.0f);
    }

    /**
     * @param qualityBoost extra atlas resolution, on top of what the UI scale already asks for.
     *                     Everything this renderer reports -- advances, heights, the quads it
     *                     draws -- is divided back down by the same factor, so a boost changes
     *                     nothing about layout. It buys headroom for text that gets magnified
     *                     after it is drawn, which is every nametag: they are drawn at a fixed
     *                     nine units and then scaled up in world space, so at a few blocks away
     *                     a glyph covers two or three times the pixels it was rasterised at.
     */
    public GlyphFontRenderer(Font sourceFont, boolean antiAlias, float qualityBoost) {
        float renderScale = boostedRenderScale(sourceFont, qualityBoost);
        this.drawScale = 1.0f / renderScale;
        this.rawScale = renderScale;
        this.renderFont = sourceFont.deriveFont(sourceFont.getStyle(), Math.max(1.0f, sourceFont.getSize2D() * renderScale));
        this.antiAlias = antiAlias;
        this.fontRenderContext = new FontRenderContext(new AffineTransform(), antiAlias, true);

        // Rasterise the whole set before anything is packed: the atlas has to know the total area
        // up front to pick a page size that holds every glyph in one texture.
        Raster[] rasters = new Raster[LAST_GLYPH + 1];
        List<int[]> sizes = new ArrayList<int[]>(LAST_GLYPH + 1);

        for (int codePoint = FIRST_GLYPH; codePoint <= LAST_GLYPH; codePoint++) {
            Raster raster = rasterise((char) codePoint);
            rasters[codePoint] = raster;
            if (raster != null && raster.visibleBottom > raster.visibleTop) {
                sizes.add(new int[]{raster.image.getWidth(), raster.image.getHeight()});
            }
        }

        this.atlas = new GlyphAtlas(GlyphAtlas.chooseSize(sizes));

        for (int codePoint = FIRST_GLYPH; codePoint <= LAST_GLYPH; codePoint++) {
            defaultGlyphs[codePoint] = commit(rasters[codePoint]);
        }

        atlas.finish();

        this.rawTextTop = computeRawTextTop();
        this.rawTextBottom = computeRawTextBottom();
        this.fontHeight = Math.max(1.0f, (rawTextBottom - rawTextTop) * drawScale);
        this.lineHeight = computeLineHeight();
    }

    @Override
    public int drawString(String text, float x, float y, int color, boolean shadow) {
        if (destroyed || text == null || text.isEmpty()) {
            return 0;
        }

        GlyphBatch.begin();
        try {
            int width = 0;
            if (shadow) {
                width = drawInternal(text, x + 1.0f, y + 1.0f, color, true);
            }

            return Math.max(width, drawInternal(text, x, y, color, false));
        }
        finally {
            GlyphBatch.end();
        }
    }

    @Override
    public int drawGlyphString(String text, float x, float y, GlyphColorProvider colorProvider, boolean shadow) {
        if (destroyed || text == null || text.isEmpty()) {
            return 0;
        }

        GlyphBatch.begin();
        try {
            int width = 0;
            if (shadow) {
                width = drawGlyphInternal(text, x + 1.0f, y + 1.0f, colorProvider, true);
            }

            return Math.max(width, drawGlyphInternal(text, x, y, colorProvider, false));
        }
        finally {
            GlyphBatch.end();
        }
    }

    @Override
    public int getStringWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        float width = 0.0f;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (isMalformedSectionPrefix(text, i)) {
                continue;
            }

            if (character == '\u00a7' && i + 1 < text.length()) {
                i++;
                continue;
            }

            if (character == '\n') {
                continue;
            }

            width += getGlyph(character).advance;
        }

        return Math.round(width);
    }

    @Override
    public int getFontHeight() {
        return Math.round(fontHeight);
    }

    @Override
    public int getLineHeight() {
        return Math.round(lineHeight);
    }

    @Override
    public int getTextTopOffset() {
        return 0;
    }

    @Override
    public int getTextBottomOffset() {
        return Math.round(fontHeight);
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }

        destroyed = true;
        extendedGlyphs.clear();
        atlas.delete();
    }

    private int drawInternal(String text, float x, float y, int color, boolean shadowPass) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }

        int activeColor = shadowPass ? applyShadowColor(color) : withAlpha(color, alpha);
        float startX = x * rawScale;
        float drawX = startX;
        float drawY = (y * rawScale) - rawTextTop;

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (isMalformedSectionPrefix(text, i)) {
                continue;
            }

            if (character == '\u00a7' && i + 1 < text.length()) {
                char formatCode = Character.toLowerCase(text.charAt(++i));
                int colorIndex = COLOR_CODES.indexOf(formatCode);

                if (colorIndex >= 0) {
                    if (colorIndex < 16) {
                        activeColor = getMinecraftColor(colorIndex, alpha, shadowPass);
                    }
                    else if (formatCode == 'r') {
                        activeColor = shadowPass ? applyShadowColor(color) : withAlpha(color, alpha);
                    }
                }

                continue;
            }

            if (character == '\n') {
                drawX = startX;
                drawY += lineHeight * rawScale;
                continue;
            }

            GlyphData glyph = getGlyph(character);
            renderGlyph(glyph, drawX - GLYPH_MARGIN, drawY, activeColor);
            drawX += glyph.rawAdvance;
        }

        return Math.round((drawX - startX) * drawScale);
    }

    private int drawGlyphInternal(String text, float x, float y, GlyphColorProvider colorProvider, boolean shadowPass) {
        float startX = x * rawScale;
        float drawX = startX;
        float drawY = (y * rawScale) - rawTextTop;
        Integer formattingColor = null;

        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (isMalformedSectionPrefix(text, i)) {
                continue;
            }

            if (character == '\u00a7' && i + 1 < text.length()) {
                char formatCode = Character.toLowerCase(text.charAt(++i));
                int colorIndex = COLOR_CODES.indexOf(formatCode);

                if (colorIndex >= 0 && colorIndex < 16) {
                    formattingColor = getMinecraftColor(colorIndex, 0xFF, false);
                }
                else if (formatCode == 'r') {
                    formattingColor = null;
                }

                continue;
            }

            if (character == '\n') {
                drawX = startX;
                drawY += lineHeight * rawScale;
                continue;
            }

            GlyphData glyph = getGlyph(character);
            int glyphColor = colorProvider.colorForGlyph(character, (drawX - startX) * drawScale, glyph.advance, formattingColor);
            int alpha = (glyphColor >>> 24) & 0xFF;
            if (alpha == 0) {
                alpha = 0xFF;
            }
            glyphColor = withAlpha(glyphColor, alpha);
            if (shadowPass) {
                glyphColor = applyShadowColor(glyphColor);
            }

            renderGlyph(glyph, drawX - GLYPH_MARGIN, drawY, glyphColor);
            drawX += glyph.rawAdvance;
        }

        return Math.round((drawX - startX) * drawScale);
    }

    /**
     * Positions arrive already divided down by the atlas scale rather than being left to a scaled
     * modelview. A matrix push has to be paired with a pop around every string, and a batch cannot
     * outlive the matrix its vertices were measured in -- flattening the scale here is what lets
     * quads from separate drawString calls share a single draw.
     */
    private void renderGlyph(GlyphData glyph, float rawX, float rawY, int color) {
        GlyphAtlas.Region region = glyph.region;
        if (region == null) {
            return;
        }

        float alpha = ((color >>> 24) & CHANNEL_MASK) / 255.0f;
        float red = ((color >>> 16) & CHANNEL_MASK) / 255.0f;
        float green = ((color >>> 8) & CHANNEL_MASK) / 255.0f;
        float blue = (color & CHANNEL_MASK) / 255.0f;

        GlyphBatch.quad(region.textureId,
                rawX * drawScale, rawY * drawScale,
                glyph.width * drawScale, glyph.height * drawScale,
                region.u0, region.v0, region.u1, region.v1,
                red, green, blue, alpha);
    }

    private GlyphData getGlyph(char character) {
        if (character >= FIRST_GLYPH && character <= LAST_GLYPH) {
            GlyphData glyph = defaultGlyphs[character];
            if (glyph != null) {
                return glyph;
            }
        }

        return extendedGlyphs.computeIfAbsent(character, this::createExtendedGlyph);
    }

    private GlyphData createExtendedGlyph(char character) {
        if (destroyed) {
            return EMPTY_GLYPH;
        }

        return commit(rasterise(character));
    }

    /** Gives a rasterised glyph a home in the atlas. Glyphs with nothing visible are not packed. */
    private GlyphData commit(Raster raster) {
        if (raster == null) {
            return EMPTY_GLYPH;
        }

        if (raster.visibleBottom <= raster.visibleTop) {
            return new GlyphData(null, 0.0f, 0.0f, raster.rawAdvance, raster.rawAdvance * drawScale,
                    raster.visibleTop, raster.visibleBottom);
        }

        GlyphAtlas.Region region = atlas.add(raster.image);
        return new GlyphData(region, raster.image.getWidth(), raster.image.getHeight(),
                raster.rawAdvance, raster.rawAdvance * drawScale, raster.visibleTop, raster.visibleBottom);
    }

    private Raster rasterise(char character) {
        if (destroyed) {
            return null;
        }

        if (Character.isISOControl(character) && character != '\n') {
            return null;
        }

        String glyphText = String.valueOf(character);
        Font glyphFont = renderFont;
        if (!renderFont.canDisplay(character)) {
            glyphFont = new Font(Font.SANS_SERIF, renderFont.getStyle(), renderFont.getSize());
        }

        BufferedImage metricsImage = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metricsGraphics = metricsImage.createGraphics();
        try {
            metricsGraphics.setFont(glyphFont);
            applyRenderHints(metricsGraphics);
            FontMetrics metrics = metricsGraphics.getFontMetrics();
            Rectangle2D bounds = metrics.getStringBounds(glyphText, metricsGraphics);
            float rawAdvance = Math.max(1.0f, (float) bounds.getWidth());
            int imageWidth = Math.max(1, (int) Math.ceil(rawAdvance) + GLYPH_MARGIN * 2);
            int imageHeight = Math.max(1, metrics.getHeight());
            BufferedImage glyphImage = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);

            Graphics2D glyphGraphics = glyphImage.createGraphics();
            try {
                glyphGraphics.setFont(glyphFont);
                glyphGraphics.setBackground(new Color(255, 255, 255, 0));
                glyphGraphics.clearRect(0, 0, imageWidth, imageHeight);
                glyphGraphics.setColor(Color.WHITE);
                applyRenderHints(glyphGraphics);
                glyphGraphics.drawString(glyphText, GLYPH_MARGIN, metrics.getAscent());
            }
            finally {
                glyphGraphics.dispose();
            }

            int[] visibleBounds = findVisibleRowBounds(glyphImage);
            return new Raster(glyphImage, rawAdvance, visibleBounds[0], visibleBounds[1]);
        }
        finally {
            metricsGraphics.dispose();
        }
    }

    private float computeRawTextTop() {
        float minTop = Float.MAX_VALUE;

        for (int i = 0; i < ALPHABET.length(); i++) {
            GlyphData glyph = getGlyph(ALPHABET.charAt(i));
            if (!glyph.hasVisiblePixels()) {
                continue;
            }
            minTop = Math.min(minTop, glyph.visibleTop);
        }

        return minTop == Float.MAX_VALUE ? 0.0f : minTop;
    }

    private float computeRawTextBottom() {
        float maxBottom = 0.0f;

        for (int i = 0; i < ALPHABET.length(); i++) {
            GlyphData glyph = getGlyph(ALPHABET.charAt(i));
            if (!glyph.hasVisiblePixels()) {
                continue;
            }
            maxBottom = Math.max(maxBottom, glyph.visibleBottom);
        }

        if (maxBottom <= 0.0f) {
            return Math.max(1.0f, renderFont.getSize2D());
        }

        return maxBottom;
    }

    private float computeLineHeight() {
        return Math.max(fontHeight, (float) renderFont.getStringBounds(ALPHABET, fontRenderContext).getHeight() * drawScale);
    }

    private static int[] findVisibleRowBounds(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] pixels = image.getRGB(0, 0, width, height, new int[width * height], 0, width);
        int top = -1;
        int bottom = -1;

        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            for (int x = 0; x < width; x++) {
                if (((pixels[rowStart + x] >>> 24) & CHANNEL_MASK) != 0) {
                    if (top == -1) {
                        top = y;
                    }
                    bottom = y + 1;
                    break;
                }
            }
        }

        if (top == -1) {
            return new int[]{0, 0};
        }

        return new int[]{top, bottom};
    }

    /**
     * The boosted scale, with a ceiling on how big a glyph is actually rasterised.
     *
     * <p>The whole glyph set shares one atlas page, so a boost costs its square in page area. Past
     * around sixty pixels there is nothing more to be had for text that is only ever magnified two
     * or three times, so the boost is taken up to that point and no further -- and never below what
     * the UI scale asked for on its own.
     */
    private static float boostedRenderScale(Font sourceFont, float qualityBoost) {
        float base = resolveRenderScale();
        float boosted = base * Math.max(1.0f, qualityBoost);
        float requestedSize = Math.max(1.0f, sourceFont.getSize2D());
        return Math.min(boosted, Math.max(base, MAX_RASTERISED_GLYPH_SIZE / requestedSize));
    }

    private static float resolveRenderScale() {
        int uiScale = 1;

        try {
            uiScale = Math.max(1, ScaledResolutionCache.get().getScaleFactor());
        }
        catch (Exception ignored) {
        }

        return Math.max(MIN_RENDER_SCALE, uiScale * QUALITY_MULTIPLIER);
    }

    private void applyRenderHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, antiAlias ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, antiAlias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        graphics.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    private static int getMinecraftColor(int colorIndex, int alpha, boolean shadow) {
        int offset = (colorIndex >> 3 & 1) * 85;
        int red = (colorIndex >> 2 & 1) * 170 + offset;
        int green = (colorIndex >> 1 & 1) * 170 + offset;
        int blue = (colorIndex & 1) * 170 + offset;

        if (colorIndex == 6) {
            red += 85;
        }

        if (shadow) {
            red /= 4;
            green /= 4;
            blue /= 4;
        }

        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int applyShadowColor(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) {
            alpha = 0xFF;
        }

        int red = ((color >>> 16) & 0xFF) / 4;
        int green = ((color >>> 8) & 0xFF) / 4;
        int blue = (color & 0xFF) / 4;
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    private static boolean isMalformedSectionPrefix(String text, int index) {
        if (!isFormattingArtifact(text.charAt(index))) {
            return false;
        }

        for (int i = index + 1; i < text.length(); i++) {
            char next = text.charAt(i);
            if (next == '\u00a7') {
                return true;
            }
            if (!isFormattingArtifact(next)) {
                return false;
            }
        }

        return false;
    }

    private static boolean isFormattingArtifact(char character) {
        return character == '\u00c2' || character == '\u00c3' || character == '\u0082' || character == '\u201a';
    }

    /** A glyph as the font produced it, before it has been given a home in the atlas. */
    private static final class Raster {
        private final BufferedImage image;
        private final float rawAdvance;
        private final int visibleTop;
        private final int visibleBottom;

        private Raster(BufferedImage image, float rawAdvance, int visibleTop, int visibleBottom) {
            this.image = image;
            this.rawAdvance = rawAdvance;
            this.visibleTop = visibleTop;
            this.visibleBottom = visibleBottom;
        }
    }

    private static final class GlyphData {
        private final GlyphAtlas.Region region;
        private final float width;
        private final float height;
        private final float rawAdvance;
        private final float advance;
        private final int visibleTop;
        private final int visibleBottom;

        private GlyphData(GlyphAtlas.Region region, float width, float height, float rawAdvance, float advance, int visibleTop, int visibleBottom) {
            this.region = region;
            this.width = width;
            this.height = height;
            this.rawAdvance = rawAdvance;
            this.advance = advance;
            this.visibleTop = visibleTop;
            this.visibleBottom = visibleBottom;
        }

        private boolean hasVisiblePixels() {
            return visibleBottom > visibleTop;
        }
    }
}
