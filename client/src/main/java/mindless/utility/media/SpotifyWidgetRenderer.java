package mindless.utility.media;

import mindless.module.ModuleManager;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.render.AudioVisualizer;
import mindless.module.impl.render.HUD;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
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

/**
 * The widget layout: a square cover beside a panel of its own blurred colours.
 *
 * <p>This is a port of the OBS Spotify widget rather than a variation on the client's own player,
 * which is the point of it -- the two look nothing alike and both are kept. Its proportions are
 * held against the cover size the way the original holds everything against
 * {@code --album-art-size}, so the whole thing scales as one piece and none of it drifts.
 *
 * <p>What the original gets its character from, and what is reproduced here: the cover is a
 * separate tile rather than an inset, the panel's background is the cover blurred out to a wash of
 * colour with black at half opacity over it, elapsed and remaining sit on one line above the
 * progress bar rather than beside it, and the whole card is dropped onto a hard offset shadow.
 *
 * <p>Two things the original has no answer for, because it never had them. Lyrics get their own
 * strip beneath, built from the same wash and the same corner, so it reads as part of the card
 * instead of something bolted under it. The visualiser is a row inside the panel above the times,
 * in the same white as the progress fill.
 */
public final class SpotifyWidgetRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Everything is a fraction of the cover, exactly as the stylesheet has it: 100px of cover to
    // 500px of card, 10px corners, 8px between the tiles, 20px of padding inside the panel.
    private static final float COVER = 76.0F;
    private static final float CARD_WIDTH = COVER * 5.0F;
    private static final float RADIUS = COVER * 0.10F;
    private static final float TILE_GAP = COVER * 0.08F;
    private static final float PAD_X = COVER * 0.20F + 4.0F;
    /** Breathing room above and below the text block, which the original gets from centring. */
    private static final float PAD_Y = 4.0F;

    private static final float TITLE_HEIGHT = COVER * 0.20F;
    private static final float LABEL_HEIGHT = COVER * 0.16F;
    private static final float BAR_HEIGHT = COVER * 0.05F;
    private static final float BAR_GAP = COVER * 0.13F;
    private static final float VISUALIZER_HEIGHT = COVER * 0.26F;
    private static final float LYRICS_GAP = COVER * 0.08F;

    /** filter: drop-shadow(15px 15px 7px rgba(0,0,0,1)), pulled in so it stays behind the card. */
    private static final float SHADOW_OFFSET = 3.0F;
    private static final float SHADOW_SPREAD = 3.0F;

    /** #1F1F1F behind the progress fill, white in front of it. */
    private static final Color TRACK = new Color(31, 31, 31);
    private static final Color FILL = new Color(255, 255, 255);
    /**
     * How dark the panel sits over its wash.
     *
     * <p>The original's rgba(0, 0, 0, 0.5) sits over art blurred across a much larger area, where
     * twenty pixels of blur average a whole cover down to something close to grey. Twelve pixels
     * stretched over a panel keeps far more of the record's colour than that, and a straight half
     * alpha over it produced a flat slab of whatever colour the sleeve happened to be. Darker
     * here, weaker below: the panel reads as dark with the record showing through, which is what
     * the original actually looks like.
     */
    private static final int PANEL_TINT_ALPHA = 172;
    private static final float WASH_ALPHA = 0.5F;

    private static final float ENTRY_RISE = 14.0F;

    /**
     * The state this renderer disturbs, saved so the rest of the HUD does not inherit it.
     *
     * <p>Deliberately not GL_ALL_ATTRIB_BITS: that makes old drivers snapshot a great deal of
     * unrelated world state every frame, for no benefit here.
     */
    private static final int SAVED_GL_STATE = GL11.GL_ENABLE_BIT
            | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT
            | GL11.GL_CURRENT_BIT;

    private static float visibility;
    private static long lastFrameMs;

    /** What the lyric strip was showing, so a change can be animated away from it. */
    private static String lyricKey = "";
    private static long lyricChangedAt;

    private static float cardX;
    private static float cardY;
    private static float cardWidth;
    private static float cardHeight;
    private static boolean cardVisible;

    private static final Map<String, RavenFontRenderer> FONTS = new HashMap<String, RavenFontRenderer>();

    private SpotifyWidgetRenderer() {
    }

    public static void render() {
        drawIsolated(false);
    }

    public static float[] renderPreview() {
        drawIsolated(true);
        return getCurrentRect();
    }

    /**
     * Runs the draw with the GL state fenced off.
     *
     * <p>Rounded rectangles here are shader draws and the wash changes texture filtering on a
     * texture the rest of the game also uses. Both would otherwise escape into whatever the HUD
     * draws next, which is the sort of fault that shows up as some unrelated element being the
     * wrong colour.
     */
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
            GL11.glPopAttrib();

            // The 1.8 stack mixes GlStateManager's cache with direct GL calls, so the saved
            // attributes are not enough on their own; the baseline is restored explicitly too.
            GL20.glUseProgram(0);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glColorMask(true, true, true, true);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0.1F);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.resetColor();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }
    }

    /** Where the card sits, for the drag-to-move screen. Null until something has been drawn. */
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
        // The cover and the panel are one flex row at a shared height in the original, so the
        // cover grows with the panel rather than the panel growing away from a fixed square.
        float rowHeight = cover + visualizerHeight;

        List<String> lyricLines = collectLyrics(info);
        RavenFontRenderer lyricFont = fontOfHeight(LABEL_HEIGHT * uiScale * lyricScale());
        float lyricStripHeight = lyricLines.isEmpty()
                ? 0.0F
                : lyricFont.getFontHeight() * lyricLines.size()
                        + (lyricLines.size() - 1) * 2.0F * uiScale + padX * 0.6F;
        float lyricGap = lyricLines.isEmpty() ? 0.0F : LYRICS_GAP * uiScale;

        float height = rowHeight + lyricGap + lyricStripHeight;

        ScaledResolution resolution = ScaledResolutionCache.get();
        float[] position = position(resolution, width, height);
        float x = position[0];
        // Rises into place, so appearing is a movement rather than a thing simply being there.
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
        if (lyricStripHeight > 0.0F) {
            dropShadow(x, y + rowHeight + lyricGap, width, lyricStripHeight, radius, uiScale, alpha);
        }

        drawCover(art, wash, x, y, cover, radius, alpha);
        drawPanel(info, wash, panelX, y, panelWidth, rowHeight, radius, padX, uiScale,
                visualizerHeight, alpha);

        if (lyricStripHeight > 0.0F) {
            drawLyricStrip(lyricLines, lyricFont, wash, x, y + rowHeight + lyricGap,
                    width, lyricStripHeight, radius, padX, uiScale, alpha);
        }

        // The rounded shaders leave a program bound, and anything drawn afterwards without
        // clearing it comes out wrong.
        GL20.glUseProgram(0);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // -------------------------------------------------------------------------------- the pieces

    /**
     * The offset shadow the original drops the whole card onto.
     *
     * <p>Stacked rounded rects rather than a real blur, which is close enough at this size. The
     * spread has to be scaled with the card: left as fixed pixels it stayed full size while the
     * card halved, and a shadow wider than the thing casting it does not read as a shadow at all,
     * it reads as a grey box behind the player.
     */
    private static void dropShadow(float x, float y, float width, float height, float radius,
                                   float uiScale, float alpha) {
        float offset = SHADOW_OFFSET * uiScale;
        int steps = 3;
        for (int i = steps; i >= 1; i--) {
            float spread = SHADOW_SPREAD * uiScale * i / steps;
            int a = Math.round(58.0F * alpha * (1.0F - (i - 1) / (float) steps));
            if (a <= 0) {
                continue;
            }
            RoundedUtils.drawRound(x + offset - spread, y + offset - spread,
                    width + spread * 2.0F, height + spread * 2.0F, radius + spread,
                    new Color(0, 0, 0, a));
        }
    }

    private static void drawCover(ResourceLocation art, ResourceLocation wash, float x, float y,
                                  float size, float radius, float alpha) {
        // background: rgba(0, 0, 0, 0.5) shows through wherever there is no cover to show.
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

        RavenFontRenderer titleFont = fontOfHeight(TITLE_HEIGHT * uiScale);
        RavenFontRenderer labelFont = fontOfHeight(LABEL_HEIGHT * uiScale);

        String title = clip(titleFont, valueOr(info.getTitle(), "Nothing playing"), textWidth);
        String artist = clip(labelFont, valueOr(info.getArtist(), ""), textWidth);

        float barHeight = BAR_HEIGHT * uiScale;
        float titleGap = 3.0F * uiScale;
        float blockHeight = titleFont.getFontHeight() + titleGap
                + labelFont.getFontHeight()
                + visualizerHeight
                + BAR_GAP * uiScale + labelFont.getFontHeight()
                + 3.0F * uiScale + barHeight;
        // #IAmRunningOutOfNamesForTheseBoxes is centred in the panel, whatever it ends up holding.
        float cursor = y + (height - blockHeight) * 0.5F;

        int textAlpha = Math.round(255 * alpha);
        int primary = Utils.mergeAlpha(0xFFFFFF, textAlpha);
        int secondary = Utils.mergeAlpha(0xFFFFFF, Math.round(textAlpha * 0.82F));

        drawShadowedString(titleFont, title, textX, cursor, primary, uiScale, alpha);
        cursor += titleFont.getFontHeight() + titleGap;
        if (!artist.isEmpty()) {
            drawShadowedString(labelFont, artist, textX, cursor, secondary, uiScale, alpha);
        }
        cursor += labelFont.getFontHeight();

        if (visualizerHeight > 0.0F) {
            VisualizerRenderer.draw(textX, cursor + 1.0F * uiScale, textWidth,
                    visualizerHeight - 2.0F * uiScale, alpha);
            cursor += visualizerHeight;
        }

        cursor += BAR_GAP * uiScale;

        long duration = Math.max(0L, info.getDurationMs());
        long position = Math.max(0L, Math.min(duration, info.getLivePositionMs()));
        String elapsed = formatTime(position);
        String remaining = formatTime(duration);

        drawShadowedString(labelFont, elapsed, textX, cursor, primary, uiScale, alpha);
        drawShadowedString(labelFont, remaining,
                textX + textWidth - labelFont.getStringWidth(remaining), cursor, primary, uiScale, alpha);
        // margin: 10px 0 on the bar, against a 16px times line.
        cursor += labelFont.getFontHeight() + 3.0F * uiScale;

        float progress = duration <= 0L ? 0.0F : Math.max(0.0F, Math.min(1.0F, position / (float) duration));
        float barRadius = barHeight * 0.5F;
        RoundedUtils.drawRound(textX, cursor, textWidth, barHeight, barRadius,
                withAlpha(TRACK, alpha));
        if (progress > 0.001F) {
            float filled = Math.max(barHeight, textWidth * progress);
            RoundedUtils.drawRound(textX, cursor, filled, barHeight, barRadius,
                    withAlpha(progressColor(), alpha));
        }
    }

    /**
     * The lyric strip, with the change between lines animated.
     *
     * <p>Without it the block simply becomes different words between one frame and the next, which
     * at three lines is a flicker rather than a transition. Lines rise into place and fade up over
     * the configured lyric animation time, so the eye follows the line moving into the middle
     * instead of being handed a new block to re-read.
     */
    private static void drawLyricStrip(List<String> lines, RavenFontRenderer font,
                                       ResourceLocation wash, float x, float y, float width,
                                       float height, float radius, float padX, float uiScale,
                                       float alpha) {
        drawWash(wash, x, y, width, height, radius, alpha);

        float duration = Math.max(1.0F, animationMs());
        float t = Math.max(0.0F, Math.min(1.0F, (nowMs() - lyricChangedAt) / duration));
        // Eased out, so it arrives softly rather than stopping dead.
        float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);

        float lineHeight = font.getFontHeight() + 2.0F * uiScale;
        float rise = (1.0F - eased) * lineHeight * 0.8F;
        float cursor = y + (height - (lineHeight * lines.size() - 2.0F * uiScale)) * 0.5F + rise;
        float inner = width - padX * 2.0F;

        int active = activeLyricIndex(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            // The line being sung is white; the rest sit back, the way the original's secondary
            // text does against its title. The active one also fades up rather than appearing.
            float weight = i == active ? 1.0F : 0.5F;
            float fade = i == active ? (0.35F + 0.65F * eased) : eased;
            int color = Utils.mergeAlpha(0xFFFFFF, Math.round(255 * alpha * weight * fade));
            String line = clip(font, lines.get(i), inner);
            drawShadowedString(font, line, x + (width - font.getStringWidth(line)) * 0.5F,
                    cursor, color, uiScale, alpha * weight);
            cursor += lineHeight;
        }
    }

    /** #backgroundArt: the cover blurred out, with black at half opacity over it. */
    private static void drawWash(ResourceLocation wash, float x, float y, float width,
                                 float height, float radius, float alpha) {
        if (wash != null) {
            drawTexturedRound(wash, x, y, width, height, radius, alpha * WASH_ALPHA, true);
        }
        RoundedUtils.drawRound(x, y, width, height, radius,
                new Color(0, 0, 0, Math.round(PANEL_TINT_ALPHA * alpha)));
    }

    /**
     * Draws a texture into a rounded rectangle.
     *
     * @param smooth whether to ask for linear filtering, which is what turns the twelve pixels of
     *               the softened cover back into a gradient instead of twelve visible squares
     */
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
            // Without clamping, the filter reaches around to the opposite edge and the wash picks
            // up a seam down whichever side it wrapped from.
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

    // ------------------------------------------------------------------------------------ detail

    /**
     * The progress fill.
     *
     * <p>White, as the original has it, and not the accent the other player uses. Tinting it to
     * the sleeve put a red bar on a red panel, which is both harder to read and not the widget.
     */
    private static Color progressColor() {
        return FILL;
    }

    /**
     * The line being sung and its neighbours.
     *
     * <p>Three lines rather than a scrolling column: the widget is a card with a fixed shape, and
     * a block that grows and shrinks with the song would fight that. {@link TimedLyrics} already
     * knows which line is current, so the only work here is choosing what sits around it.
     */
    private static List<String> collectLyrics(SystemMediaInfo info) {
        List<String> empty = java.util.Collections.emptyList();
        if (!isOn(SpotifyMiniPlayer.showLyrics) || !info.isAvailable()) {
            return empty;
        }
        TimedLyrics lyrics = SystemMediaClient.getInstance().getTimedLyrics();
        if (lyrics == null || !lyrics.isAvailable() || lyrics.getLines().isEmpty()) {
            return empty;
        }

        long position = info.getLivePositionMs()
                + (SpotifyMiniPlayer.lyricSyncOffset == null
                        ? 0L : (long) SpotifyMiniPlayer.lyricSyncOffset.getInput());
        int active = lyrics.findLineIndex(position);
        if (active < 0) {
            active = 0;
        }

        List<TimedLyrics.LyricsLine> all = lyrics.getLines();
        java.util.List<String> out = new java.util.ArrayList<String>(3);
        activeLyricIndex = 0;
        for (int i = active - 1; i <= active + 1; i++) {
            if (i < 0 || i >= all.size()) {
                continue;
            }
            String text = all.get(i).getText();
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            if (i == active) {
                activeLyricIndex = out.size();
            }
            out.add(text.trim());
        }
        String key = out.toString() + "@" + activeLyricIndex;
        if (!key.equals(lyricKey)) {
            lyricKey = key;
            lyricChangedAt = nowMs();
        }
        return out;
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
     * Trims to fit with an ellipsis, as {@code text-overflow: ellipsis} does.
     *
     * <p>Measured per character rather than by average width, because the fonts here are
     * proportional and an average overshoots on a title full of narrow letters.
     */
    private static String clip(RavenFontRenderer font, String text, float maxWidth) {
        if (text == null || text.isEmpty() || font.getStringWidth(text) <= maxWidth) {
            return text == null ? "" : text;
        }
        String ellipsis = "...";
        float room = maxWidth - font.getStringWidth(ellipsis);
        if (room <= 0.0F) {
            return ellipsis;
        }
        StringBuilder out = new StringBuilder(text.length());
        float used = 0.0F;
        for (int i = 0; i < text.length(); i++) {
            float advance = font.getStringWidth(String.valueOf(text.charAt(i)));
            if (used + advance > room) {
                break;
            }
            used += advance;
            out.append(text.charAt(i));
        }
        return out.append(ellipsis).toString();
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return (totalSeconds / 60L) + ":" + String.format("%02d", totalSeconds % 60L);
    }

    private static String valueOr(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static boolean isOn(mindless.module.setting.impl.ButtonSetting setting) {
        return setting != null && setting.isToggled();
    }

    /**
     * text-shadow: 2px 2px 2px rgba(0, 0, 0, 0.5)
     *
     * <p>Drawn as a second pass rather than with the font's own shadow flag. That one is a hard
     * black copy at a fixed offset, which at these sizes reads as the text having been printed
     * twice rather than as depth.
     */
    private static void drawShadowedString(RavenFontRenderer font, String text, float x, float y,
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

    /**
     * A font at roughly the requested pixel height.
     *
     * <p>The renderer takes a scale rather than a size, so one probe at scale one gives the ratio
     * and everything else follows from it. Results are cached per height because the panel asks
     * for three different sizes every frame, and a single-entry cache would rebuild all three.
     */
    private static RavenFontRenderer fontOfHeight(float targetHeight) {
        String family = HUD.getSelectedFontName();
        float base = HUD.getSelectedFontScale();
        String key = family + ":" + Math.round(targetHeight * 4.0F);
        RavenFontRenderer cached = FONTS.get(key);
        if (cached != null) {
            return cached;
        }

        RavenFontRenderer probe = FONTS.get(family + ":probe");
        if (probe == null) {
            probe = FontManager.getHudRenderer(family, base);
            FONTS.put(family + ":probe", probe);
        }
        float probeHeight = Math.max(1.0F, probe.getFontHeight());
        float scale = Math.max(0.45F, Math.min(4.0F, base * (targetHeight / probeHeight)));

        RavenFontRenderer font = FontManager.getHudRenderer(family, scale);
        if (FONTS.size() > 24) {
            FONTS.clear();
        }
        FONTS.put(key, font);
        return font;
    }

    private static float approach(float current, float target, float rate) {
        long now = System.nanoTime() / 1_000_000L;
        float delta = lastFrameMs == 0L ? 1.0F / 60.0F : Math.min(0.1F, (now - lastFrameMs) / 1000.0F);
        lastFrameMs = now;
        float factor = 1.0F - (float) Math.exp(-delta * rate);
        return current + (target - current) * factor;
    }
}
