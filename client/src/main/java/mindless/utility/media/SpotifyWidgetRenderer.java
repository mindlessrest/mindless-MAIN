package mindless.utility.media;

import mindless.module.ModuleManager;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.render.AudioVisualizer;
import mindless.module.impl.render.HUD;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public final class SpotifyWidgetRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float COVER = 76.0F;
    private static final float CARD_WIDTH = COVER * 5.5F;
private static final float RADIUS = COVER * 0.16F;
    private static final float COVER_RADIUS = COVER * 0.125F;
    private static final float TILE_GAP = COVER * 0.18F;
private static final float PAD = COVER * 0.17F;
    private static final float PAD_X = PAD;
    private static final float PAD_Y = PAD;

    private static final float TITLE_HEIGHT = COVER * 0.26F;
    private static final float LABEL_HEIGHT = COVER * 0.155F;
    private static final float BAR_HEIGHT = COVER * 0.05F;
    private static final float BAR_GAP = COVER * 0.09F;
    private static final float VISUALIZER_HEIGHT = COVER * 0.22F;
    private static final float LYRICS_GAP = COVER * 0.08F;
private static final float SHADOW_SPREAD = 6.0F;
private static final Color TRACK = new Color(31, 31, 31);
    private static final Color FILL = new Color(255, 255, 255);
private static final String BOLD_FAMILY = "Sf-Bold";
private static final int TINT_LEFT_ALPHA = 202;
    private static final int TINT_RIGHT_ALPHA = 96;
    private static final float WASH_ALPHA = 1.0F;

    private static final float ENTRY_RISE = 14.0F;
private static final int SAVED_GL_STATE = GL11.GL_ENABLE_BIT
            | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT
            | GL11.GL_CURRENT_BIT;

    private static float visibility;
    private static long lastFrameMs;
private static String lyricKey = "";
    private static int lyricSourceIndex = -1;
    private static List<String> cachedLyricLines = java.util.Collections.emptyList();
    private static long lyricChangedAt;
    private static long cachedElapsedSecond = Long.MIN_VALUE;
    private static long cachedDurationSecond = Long.MIN_VALUE;
    private static String cachedElapsedText = "0:00";
    private static String cachedDurationText = "0:00";

    private static float cardX;
    private static float cardY;
    private static float cardWidth;
    private static float cardHeight;
    private static boolean cardVisible;

    private static float bubbleX;
    private static float bubbleY;
    private static float bubbleWidth;
    private static float bubbleHeight;
    private static boolean bubbleVisible;

    private static final Map<String, MindlessFontRenderer> FONTS = new HashMap<String, MindlessFontRenderer>();

    private SpotifyWidgetRenderer() {
    }

    public static void render() {
        drawIsolated(false);
    }

    public static float[] renderPreview() {
        drawIsolated(true);
        return getCurrentRect();
    }
private static void drawIsolated(boolean preview) {
        final int previousFramebuffer = GL11.glGetInteger(
                EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        GL11.glPushAttrib(SAVED_GL_STATE);
        GL11.glPushMatrix();
        try {
            draw(preview);
        }
        catch (Throwable ignored) {
            cardVisible = false;
        }
        finally {
            GL11.glPopMatrix();
            RenderUtils.popAttrib();
            GL20.glUseProgram(0);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glColorMask(true, true, true, true);
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
            GlStateManager.enableBlend();
            GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }
public static float[] getLyricsRect() {
        if (!bubbleVisible) {
            return null;
        }
        return new float[]{bubbleX, bubbleY, bubbleX + bubbleWidth, bubbleY + bubbleHeight};
    }
public static float[] getCurrentRect() {
        if (!cardVisible) {
            return null;
        }
        return new float[]{cardX, cardY, cardX + cardWidth, cardY + cardHeight};
    }

    private static void draw(boolean preview) {
        SystemMediaClient mediaClient = SystemMediaClient.getInstance();
        SystemMediaInfo info = mediaClient.getCurrentInfo();

        boolean wantVisible = info.isAvailable()
                && !(info.isPaused() && isOn(SpotifyMiniPlayer.hideWhenPaused));
        if (preview) {
            wantVisible = true;
        }

        visibility = approach(visibility, wantVisible ? 1.0F : 0.0F, 7.0F);
        if (visibility <= 0.01F) {
            cardVisible = false;
            return;
        }

        float uiScale = SpotifyMiniPlayer.scale == null ? 1.0F : (float) SpotifyMiniPlayer.scale.getInput();
        float cover = COVER * uiScale;
        float radius = RADIUS * uiScale;
        float gap = TILE_GAP * uiScale;
        float padX = PAD_X * uiScale;
        float width = CARD_WIDTH * uiScale;

        boolean showVisualizer = AudioVisualizer.wantsMiniPlayerSection();
        float visualizerHeight = showVisualizer ? VISUALIZER_HEIGHT * uiScale : 0.0F;
        float rowHeight = cover + visualizerHeight;

        List<String> lyricLines = collectLyrics(info);
        float lyricUiScale = uiScale * bubbleScale();
        MindlessFontRenderer lyricFont = lyricFontOfHeight(LABEL_HEIGHT * lyricUiScale * lyricScale());
        float lyricStripHeight = lyricLines.isEmpty()
                ? 0.0F
                : lyricFont.getFontHeight() * lyricLines.size()
                        + (lyricLines.size() - 1) * 3.0F * lyricUiScale + PAD_Y * lyricUiScale * 2.3F;
        float lyricGap = lyricLines.isEmpty() ? 0.0F : LYRICS_GAP * uiScale;
        boolean bubbleDetached = SpotifyMiniPlayer.hasLyricsPosition();
        float height = rowHeight;

        ScaledResolution resolution = ScaledResolutionCache.get();
        float[] position = position(resolution, width, height);
        float x = position[0];
        float y = position[1] + (1.0F - visibility) * ENTRY_RISE * uiScale;

        cardX = x;
        cardY = position[1];
        cardWidth = width;
        cardHeight = height;
        cardVisible = true;

        float alpha = visibility;
        float panelX = x + cover + gap;
        float panelWidth = Math.max(24.0F, width - cover - gap);

        ResourceLocation art = mediaClient.getAlbumArtTextureLocation();
        ResourceLocation wash = mediaClient.getAlbumArtBlurTextureLocation();

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();

        dropShadow(x, y, cover, rowHeight, radius, uiScale, alpha);
        dropShadow(panelX, y, panelWidth, rowHeight, radius, uiScale, alpha);
        float bubbleW = lyricStripHeight <= 0.0F ? 0.0F : width * bubbleScale();
        float[] bubblePos = lyricStripHeight <= 0.0F ? null
                : bubbleDetached
                        ? lyricsPosition(resolution, bubbleW, lyricStripHeight)
                        : new float[]{x, y + rowHeight + lyricGap};
        drawCover(art, wash, x, y, cover, radius, alpha);
        drawPanel(info, wash, panelX, y, panelWidth, rowHeight, radius, padX, uiScale,
                visualizerHeight, alpha);

        bubbleVisible = bubblePos != null;
        if (bubblePos != null) {
            bubbleX = bubblePos[0];
            bubbleY = bubblePos[1];
            bubbleWidth = bubbleW;
            bubbleHeight = lyricStripHeight;
            dropShadow(bubblePos[0], bubblePos[1], bubbleW, lyricStripHeight, radius,
                    lyricUiScale, alpha);
            drawLyricStrip(lyricLines, lyricFont, wash, bubblePos[0], bubblePos[1],
                    bubbleW, lyricStripHeight, radius, PAD * lyricUiScale, lyricUiScale, alpha);
        }
        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }
    /**
     * Centred, so the falloff reads as a shadow around the card. Offsetting it downwards left the
     * part that cleared the card's bottom edge as a flat slab of grey -- the card hid the half
     * that made it look like a shadow, and what was left just looked like another panel.
     */
    private static void dropShadow(float x, float y, float width, float height, float radius,
                                   float uiScale, float alpha) {
        // Original softness and opacity, centred. Only the downward offset is gone -- that was the
        // part that put a flat wedge of grey under the card. Tightening and darkening it instead
        // just turned the falloff into a hard rim, which is worse.
        float softness = Math.max(2.0F, SHADOW_SPREAD * uiScale);
        RoundedUtils.drawRoundShadow(x, y, width, height, radius,
                softness, new Color(0, 0, 0, Math.round(150 * alpha)).getRGB());
    }

    private static void drawCover(ResourceLocation art, ResourceLocation wash, float x, float y,
                                  float size, float unusedRadius, float alpha) {
        float radius = COVER_RADIUS * (size / COVER);
        RoundedUtils.drawRound(x, y, size, size, radius, new Color(0, 0, 0, Math.round(128 * alpha)));
        if (art != null) {
            drawTexturedRound(art, x, y, size, size, radius, alpha, false);
            return;
        }
        if (wash != null) {
            drawTexturedRound(wash, x, y, size, size, radius, alpha * 0.6F, true);
        }
    }

    private static void drawPanel(SystemMediaInfo info, ResourceLocation wash, float x, float y,
                                  float width, float height, float radius, float padX,
                                  float uiScale, float visualizerHeight, float alpha) {
        drawWash(wash, x, y, width, height, radius, alpha);

        float textX = x + padX;
        float textWidth = width - padX * 2.0F;
        MindlessFontRenderer titleFont = fontOfHeight(
                mindless.utility.font.ModuleFont.boldVariant(SpotifyMiniPlayer.widgetFontName()),
                TITLE_HEIGHT * uiScale);
        MindlessFontRenderer labelFont = fontOfHeight(LABEL_HEIGHT * uiScale);

        String title = clip(titleFont, valueOr(info.getTitle(), "Nothing playing"), textWidth);
        String artist = clip(labelFont, valueOr(info.getArtist(), ""), textWidth);

        float barHeight = Math.max(2.0F, BAR_HEIGHT * uiScale);
        float titleGap = 1.0F * uiScale;
        float timesGap = BAR_GAP * uiScale;
        float barGap = 3.0F * uiScale;
        float top = y + PAD_Y * uiScale;
        float bottom = y + height - PAD_Y * uiScale;

        int textAlpha = Math.round(255 * alpha);
        int primary = Utils.mergeAlpha(0xFFFFFF, textAlpha);
        int secondary = Utils.mergeAlpha(0xD2D2DC, Math.round(textAlpha * 0.86F));
        float barY = bottom - barHeight;
        float timesY = barY - barGap - labelFont.getFontHeight();

        drawShadowedString(titleFont, title, textX, top, primary, uiScale, alpha);
        float afterTitle = top + titleFont.getFontHeight() + titleGap;
        if (!artist.isEmpty() && afterTitle + labelFont.getFontHeight() <= timesY - 1.0F) {
            drawShadowedString(labelFont, artist, textX, afterTitle, secondary, uiScale, alpha);
            afterTitle += labelFont.getFontHeight();
        }

        if (visualizerHeight > 0.0F) {
            float visTop = afterTitle + timesGap * 0.4F;
            float visBottom = timesY - timesGap * 0.4F;
            if (visBottom - visTop > 3.0F) {
                VisualizerRenderer.draw(textX, visTop, textWidth, visBottom - visTop, alpha);
            }
        }

        long duration = Math.max(0L, info.getDurationMs());
        long position = Math.max(0L, Math.min(duration, info.getLivePositionMs()));
        String elapsed = elapsedText(position);
        String remaining = durationText(duration);

        drawShadowedString(labelFont, elapsed, textX, timesY, primary, uiScale, alpha);
        drawShadowedString(labelFont, remaining,
                textX + textWidth - labelFont.getStringWidth(remaining), timesY, primary, uiScale, alpha);

        float progress = duration <= 0L ? 0.0F : Math.max(0.0F, Math.min(1.0F, position / (float) duration));
        float barRadius = barHeight * 0.5F;
        RoundedUtils.drawRound(textX, barY, textWidth, barHeight, barRadius,
                new Color(255, 255, 255, Math.round(64 * alpha)));
        if (progress > 0.001F) {
            float filled = Math.max(barHeight, textWidth * progress);
            RoundedUtils.drawRound(textX, barY, filled, barHeight, barRadius,
                    withAlpha(progressColor(), alpha));
        }
    }
private static void drawLyricStrip(List<String> lines, MindlessFontRenderer font,
                                       ResourceLocation wash, float x, float y, float width,
                                       float height, float radius, float padX, float uiScale,
                                       float alpha) {
        drawWash(wash, x, y, width, height, radius, alpha);

        float duration = Math.max(1.0F, animationMs());
        float t = Math.max(0.0F, Math.min(1.0F, (nowMs() - lyricChangedAt) / duration));
        float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);

        float lineHeight = font.getFontHeight() + 3.0F * uiScale;
        float block = lineHeight * lines.size() - 3.0F * uiScale;
        float slack = Math.max(0.0F, height - block);
        float travel = Math.min(lineHeight * 0.8F, slack * 0.34F);
        float rise = (1.0F - eased) * travel;
        float cursor = y + slack * 0.5F + rise;
        float inner = width - padX * 2.0F - Math.max(0.6F, 1.1F * uiScale);

        int active = activeLyricIndex(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            float weight = i == active ? 1.0F : 0.5F;
            float fade = i == active ? (0.35F + 0.65F * eased) : eased;
            int color = Utils.mergeAlpha(0xFFFFFF, Math.round(255 * alpha * weight * fade));
            String line = clip(font, lines.get(i), inner);
            drawShadowedString(font, line, x + (width - font.getStringWidth(line)) * 0.5F,
                    cursor, color, uiScale, alpha * weight);
            cursor += lineHeight;
        }
    }
    private static void drawWash(ResourceLocation wash, float x, float y, float width,
                                 float height, float radius, float alpha) {
        RoundedUtils.drawRound(x, y, width, height, radius,
                new Color(14, 14, 18, Math.round(242 * alpha)));
        if (wash != null) {
            drawTexturedRound(wash, x, y, width, height, radius, alpha * WASH_ALPHA, true);
        }

        RoundedUtils.drawGradientCornerLR(x, y, width, height, radius,
                new Color(0, 0, 0, Math.round(TINT_LEFT_ALPHA * alpha)),
                new Color(0, 0, 0, Math.round(TINT_RIGHT_ALPHA * alpha)));

        RoundedUtils.drawGradientVertical(x, y, width, height * 0.55F, radius,
                new Color(255, 255, 255, Math.round(20 * alpha)),
                new Color(255, 255, 255, 0));
    }
private static void drawTexturedRound(ResourceLocation texture, float x, float y, float width,
                                          float height, float radius, float alpha, boolean smooth) {
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        RenderUtils.prepareGuiTextureRenderState();
        mc.getTextureManager().bindTexture(texture);
        if (smooth) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        RoundedUtils.drawRoundTextured(x, y, width, height, radius, alpha);
        GL20.glUseProgram(0);
        if (smooth) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        }
        RenderUtils.restoreGuiRenderState(depth, blend, depthMask);
    }
private static Color progressColor() {
        return FILL;
    }
private static List<String> collectLyrics(SystemMediaInfo info) {
        List<String> empty = java.util.Collections.emptyList();
        if (!isOn(SpotifyMiniPlayer.showLyrics) || !info.isAvailable()) {
            lyricSourceIndex = -1;
            cachedLyricLines = empty;
            return empty;
        }
        TimedLyrics lyrics = SystemMediaClient.getInstance().getTimedLyrics();
        if (lyrics == null || !lyrics.isAvailable() || lyrics.getLines().isEmpty()) {
            lyricSourceIndex = -1;
            cachedLyricLines = empty;
            return empty;
        }

        long position = info.getLivePositionMs()
                + (SpotifyMiniPlayer.lyricSyncOffset == null
                        ? 0L : (long) SpotifyMiniPlayer.lyricSyncOffset.getInput());
        int active = lyrics.findVisibleLineIndex(position);
        if (active < 0) {
            lyricSourceIndex = -1;
            cachedLyricLines = empty;
            return empty;
        }

        List<TimedLyrics.LyricsLine> all = lyrics.getLines();
        activeLyricIndex = 0;
        String text = all.get(active).getText();
        if (text == null || text.trim().isEmpty()) return empty;
        text = text.trim();
        if (active != lyricSourceIndex || !text.equals(lyricKey)) {
            lyricSourceIndex = active;
            lyricKey = text;
            cachedLyricLines = java.util.Collections.singletonList(text);
            lyricChangedAt = nowMs();
        }
        return cachedLyricLines;
    }

    private static int activeLyricIndex;

    private static long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static float animationMs() {
        if (SpotifyMiniPlayer.animateLyrics != null && !SpotifyMiniPlayer.animateLyrics.isToggled()) {
            return 1.0F;
        }
        return SpotifyMiniPlayer.lyricAnimationSpeed == null
                ? 260.0F : (float) SpotifyMiniPlayer.lyricAnimationSpeed.getInput();
    }

    private static int activeLyricIndex(int lineCount) {
        return Math.max(0, Math.min(lineCount - 1, activeLyricIndex));
    }

    private static float bubbleScale() {
        return SpotifyMiniPlayer.lyricsScale == null
                ? 1.0F : (float) SpotifyMiniPlayer.lyricsScale.getInput();
    }

    private static float[] lyricsPosition(ScaledResolution resolution, float width, float height) {
        float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
        float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
        return new float[]{maxX * SpotifyMiniPlayer.getLyricsNormalizedX(),
                maxY * SpotifyMiniPlayer.getLyricsNormalizedY()};
    }

    private static float lyricScale() {
        return SpotifyMiniPlayer.lyricTextScale == null
                ? 1.0F : (float) SpotifyMiniPlayer.lyricTextScale.getInput();
    }

    private static float[] position(ScaledResolution resolution, float width, float height) {
        float margin = 8.0F;
        if (SpotifyMiniPlayer.hasCustomPosition()) {
            float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
            float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
            return new float[]{maxX * SpotifyMiniPlayer.getCustomNormalizedX(),
                    maxY * SpotifyMiniPlayer.getCustomNormalizedY()};
        }
        return new float[]{resolution.getScaledWidth() - width - margin, margin};
    }
/**
     * Trims text to a width it genuinely fits in.
     *
     * <p>Adding up per-character widths does not give the width of the string. Every advance comes
     * back as a whole number, so each character is measured up to half a pixel short, and across a
     * line of lyrics the error adds up to more than the padding is wide -- the sum says it fits
     * while the string as a whole does not. Since the line is then centred on its real width, what
     * it overruns by is split between the two sides and it leaves the bubble at both ends. The
     * running total is still the quick way to get close, so it is kept as a first guess and then
     * checked against the real thing, which is one or two characters' work.
     */
    private static String clip(MindlessFontRenderer font, String text, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        float budget = maxWidth - 1.0F;
        if (budget <= 0.0F) {
            return "";
        }
        if (font.getStringWidth(text) <= budget) {
            return text;
        }

        String ellipsis = "...";
        if (font.getStringWidth(ellipsis) >= budget) {
            return "";
        }
        int low = 0;
        int high = text.length() - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (font.getStringWidth(text.substring(0, mid) + ellipsis) <= budget) {
                low = mid;
            }
            else {
                high = mid - 1;
            }
        }

        return low == 0 ? "" : text.substring(0, low) + ellipsis;
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long seconds = totalSeconds % 60L;
        return (totalSeconds / 60L) + ":" + (seconds < 10L ? "0" : "") + seconds;
    }

    private static String elapsedText(long millis) {
        long second = Math.max(0L, millis / 1000L);
        if (second != cachedElapsedSecond) {
            cachedElapsedSecond = second;
            cachedElapsedText = formatTime(millis);
        }
        return cachedElapsedText;
    }

    private static String durationText(long millis) {
        long second = Math.max(0L, millis / 1000L);
        if (second != cachedDurationSecond) {
            cachedDurationSecond = second;
            cachedDurationText = formatTime(millis);
        }
        return cachedDurationText;
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isOn(mindless.module.setting.impl.ButtonSetting setting) {
        return setting != null && setting.isToggled();
    }
private static void drawShadowedString(MindlessFontRenderer font, String text, float x, float y,
                                           int color, float uiScale, float alpha) {
        float offset = Math.max(0.6F, 1.1F * uiScale);
        int shadow = Utils.mergeAlpha(0x000000, Math.round(120 * alpha));
        font.drawString(text, x + offset, y + offset, shadow, false);
        font.drawString(text, x, y, color, false);
    }

    private static Color withAlpha(Color color, float alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.max(0, Math.min(255, Math.round(255 * alpha))));
    }
private static MindlessFontRenderer fontOfHeight(String family, float pixelHeight) {
        float height = Math.max(6.0F, pixelHeight);
        String key = family + "#" + Math.round(height * 2.0F);
        MindlessFontRenderer cached = FONTS.get(key);
        if (cached != null) {
            return cached;
        }
        MindlessFontRenderer font = FontManager.getClickGuiRenderer(family, height);
        if (FONTS.size() > 32) {
            FONTS.clear();
        }
        FONTS.put(key, font);
        return font;
    }

    private static MindlessFontRenderer fontOfHeight(float pixelHeight) {
        return fontOfHeight(SpotifyMiniPlayer.widgetFontName(), pixelHeight);
    }
private static MindlessFontRenderer lyricFontOfHeight(float pixelHeight) {
        return fontOfHeight(SpotifyMiniPlayer.lyricsFontName(), pixelHeight);
    }

    private static float approach(float current, float target, float rate) {
        long now = System.nanoTime() / 1_000_000L;
        float delta = lastFrameMs == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrameMs) / 1000.0F);
        lastFrameMs = now;
        float factor = 1.0F - (float) Math.exp(-delta * rate);
        return current + (target - current) * factor;
    }
}
