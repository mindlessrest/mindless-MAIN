package mindless.utility.media;

import mindless.Raven;
import mindless.module.ModuleManager;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.render.HUD;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Future;

public final class SpotifyMiniPlayerRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final float PLAYER_WIDTH_WITH_ART = 384.0F;
    private static final float PLAYER_WIDTH_NO_ART = 284.0F;
    private static final float PLAYER_HEIGHT = 164.0F;
    private static final float IDLE_WIDTH = 186.0F;
    private static final float IDLE_HEIGHT = 48.0F;
    private static final float ENTRY_OFFSET = 18.0F;
    // SMTC reports Spotify position with ~200-400ms latency vs audio.
    // On high-latency outputs (Bluetooth, USB DAC) the user hears audio
    // 150-300ms after the SMTC position advances, making lyrics appear early.
    // A small negative lead delays the visual to better match perceived audio.
    // Users can fine-tune with the "Lyrics sync" slider.
    private static final long LYRIC_RENDER_LEAD_MS = -200L;
    private static final long LYRIC_SEEK_RESET_MS = 2400L;
    // Increased from 320 to 700: SMTC can report positions 300-500ms behind
    // the running interpolated clock when Spotify updates infrequently. A tight
    // tolerance caused false-positive rewind detections that reset the scroll
    // animation every few seconds, producing a visible flash/flicker.
    private static final long LYRIC_REWIND_TOLERANCE_MS = 700L;
    private static final long LYRIC_TRANSITION_MS = 420L;
    private static final int SAVED_GL_STATE = GL11.GL_ENABLE_BIT
            | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT
            | GL11.GL_TEXTURE_BIT | GL11.GL_TRANSFORM_BIT
            | GL11.GL_VIEWPORT_BIT | GL11.GL_SCISSOR_BIT
            | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT;

    private static float visibility;
    private static float visibilityStart;
    private static float visibilityTarget;
    private static long visibilityAnimationStartedAt = -1L;
    private static SystemMediaInfo lastVisibleInfo = SystemMediaInfo.unavailable();
    private static String lastLyricsTrackKey = "";
    private static int lastLyricsIndex = -1;
    private static long lastLyricsPositionMs = -1L;
    private static float lyricScrollOffset;
    private static float lyricScrollStartOffset;
    private static float lyricScrollTargetOffset;
    private static long lyricScrollStartedAt;
    private static boolean lyricScrollInitialized;
    private static TimedLyrics cachedLyricsSource;
    private static String cachedLyricsLayoutKey = "";
    private static List<WrappedLyric> cachedWrappedLyrics = Collections.emptyList();
    private static volatile List<WrappedLyric> asyncWrappedLyrics;
    private static volatile String asyncWrappedLayoutKey;
    private static volatile TimedLyrics asyncWrappedSource;
    private static Future<?> asyncWrappedTask;
    private static String cachedAdaptiveFontKey = "";
    private static RavenFontRenderer cachedAdaptiveLyricFont;
    private static volatile String asyncAdaptiveFontKey;
    private static volatile RavenFontRenderer asyncAdaptiveLyricFont;
    private static Future<?> asyncAdaptiveFontTask;
    private static String marqueeTitle = "";
    private static long marqueeStartedAt;
    private static float animatedPanelHeight = -1.0F;
    private static float panelHeightStart;
    private static float panelHeightTarget;
    private static long panelHeightAnimationStartedAt = -1L;

    private static boolean panelVisible;
    private static boolean progressActive;
    private static boolean previousButtonActive;
    private static boolean playPauseButtonActive;
    private static boolean nextButtonActive;
    private static long lastDurationMs;
    private static float panelX;
    private static float panelY;
    private static float panelWidth;
    private static float panelHeight;
    private static float progressX;
    private static float progressY;
    private static float progressWidth;
    private static float progressHeight;
    private static float previousX;
    private static float previousY;
    private static float previousWidth;
    private static float previousHeight;
    private static float playPauseX;
    private static float playPauseY;
    private static float playPauseWidth;
    private static float playPauseHeight;
    private static float nextX;
    private static float nextY;
    private static float nextWidth;
    private static float nextHeight;
    private static long lastRenderedHudFrame = Long.MIN_VALUE;

    private SpotifyMiniPlayerRenderer() {
    }

    public static void render() {
        renderIsolated(false);
    }

    public static float[] renderPreview() {
        renderIsolated(true);
        return getCurrentRect();
    }

    private static void renderIsolated(boolean previewMode) {
        final int previousFramebuffer = GL11.glGetInteger(
                EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        // GL_ALL_ATTRIB_BITS forces old drivers to snapshot a large amount of
        // unrelated world state every HUD frame. Save only the state touched
        // by this renderer while still restoring Lunar's exact viewport.
        GL11.glPushAttrib(SAVED_GL_STATE);
        GL11.glPushMatrix();
        try {
            renderInternal(previewMode);
        }
        finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();

            // The old 1.8 render stack mixes cached GlStateManager calls with
            // direct OpenGL calls. Restore the actual fixed-function baseline
            // as well as the saved attributes so the next HUD/world pass can
            // never inherit the player's animated colors.
            GL20.glUseProgram(0);
            GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
            EXTFramebufferObject.glBindFramebufferEXT(
                    EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glColorMask(true, true, true, true);
            GL11.glShadeModel(GL11.GL_FLAT);
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

    private static void renderInternal(boolean previewMode) {
        if (mc == null || mc.fontRendererObj == null || mc.gameSettings == null) {
            reset();
            return;
        }

        if (!previewMode && mc.gameSettings.showDebugInfo) {
            reset();
            return;
        }

        if (!previewMode && (ModuleManager.spotifyMiniPlayer == null || !ModuleManager.spotifyMiniPlayer.isEnabled())) {
            reset();
            return;
        }

        SystemMediaClient mediaClient = SystemMediaClient.getInstance();
        SystemMediaInfo mediaInfo = previewMode ? createPreviewInfo() : mediaClient.getCurrentInfo();
        boolean hideWhenPaused = !previewMode && SpotifyMiniPlayer.hideWhenPaused != null && SpotifyMiniPlayer.hideWhenPaused.isToggled();
        boolean mediaVisible = mediaInfo.isAvailable() && (!mediaInfo.isPaused() || !hideWhenPaused);
        // Only show the idle card when a media session IS detected (isAvailable=true) but
        // not currently visible (e.g. paused). When nothing is detected at all, hide
        // completely — don't show a "No media found" placeholder.
        boolean showIdleCard = previewMode || (mediaInfo.isAvailable() && !mediaVisible
                && SpotifyMiniPlayer.showIdleCard != null && SpotifyMiniPlayer.showIdleCard.isToggled());
        boolean shouldShow = previewMode || mediaVisible || showIdleCard;

        float renderVisibility;
        if (previewMode) {
            renderVisibility = 1.0F;
        }
        else {
            updateAnimation(shouldShow);
            renderVisibility = visibility;
            if (mediaVisible) {
                lastVisibleInfo = mediaInfo;
            }
            else if (lastVisibleInfo.isAvailable()) {
                mediaInfo = lastVisibleInfo;
            }
        }

        if (renderVisibility <= 0.02F) {
            clearInteractiveRegions();
            return;
        }

        boolean showAlbumArt = mediaVisible && (SpotifyMiniPlayer.showAlbumArt == null || SpotifyMiniPlayer.showAlbumArt.isToggled());
        boolean showHeader = SpotifyMiniPlayer.showHeader == null || SpotifyMiniPlayer.showHeader.isToggled();
        boolean showDetails = SpotifyMiniPlayer.showDetails == null || SpotifyMiniPlayer.showDetails.isToggled();
        boolean showSourceApp = SpotifyMiniPlayer.showSourceApp == null || SpotifyMiniPlayer.showSourceApp.isToggled();
        boolean showStatusBadge = SpotifyMiniPlayer.showStatusBadge == null || SpotifyMiniPlayer.showStatusBadge.isToggled();
        boolean showLyrics = mediaVisible && (SpotifyMiniPlayer.showLyrics == null || SpotifyMiniPlayer.showLyrics.isToggled());
        boolean showProgress = mediaVisible && (SpotifyMiniPlayer.showProgressBar == null || SpotifyMiniPlayer.showProgressBar.isToggled()) && mediaInfo.getDurationMs() > 0L;
        float uiScale = SpotifyMiniPlayer.scale == null ? 0.5F : (float) Math.max(0.4D, Math.min(1.75D, SpotifyMiniPlayer.scale.getInput()));
        boolean lowScaleLayout = uiScale <= 0.74F;
        boolean ultraLowScaleLayout = uiScale <= 0.58F;
        boolean effectiveShowHeader = showHeader && !ultraLowScaleLayout;
        boolean effectiveShowTimeLabels = showProgress;
        boolean renderFooterLine = showDetails && !ultraLowScaleLayout;
        float compactRelief = Math.max(0.0F, 0.95F - uiScale);
        float textScale = lowScaleLayout
                ? Math.max(0.82F, Math.min(0.98F, 0.56F + uiScale * 0.62F))
                : Math.max(0.78F, Math.min(1.02F, 0.84F + uiScale * 0.18F));
        RavenFontRenderer uiFont = getUiFontRenderer(textScale);
        RavenFontRenderer timeFont = uiFont;
        TimedLyrics timedLyrics = mediaVisible ? mediaClient.getTimedLyrics() : TimedLyrics.empty();
        long livePositionMs = mediaInfo.getLivePositionMs();
        boolean renderLyrics = showLyrics && timedLyrics.isAvailable() && !timedLyrics.getLines().isEmpty();
        // Keep the lyrics area reserved while a fetch is in flight. Dropping it the instant the
        // lyrics went unavailable made the panel shrink and grow again on every track change,
        // which is most of what read as flicker.
        boolean lyricsArea = showLyrics && (renderLyrics || timedLyrics.isLoading());
        LyricsTimeline lyricsTimeline = renderLyrics ? buildLyricsTimeline(mediaInfo, timedLyrics, livePositionMs) : null;
        RavenFontRenderer lyricFont = uiFont;
        float lowScaleBreathingRoom = lowScaleLayout ? 8.0F : 0.0F;
        float width = getPanelWidth(mediaVisible, showAlbumArt) * uiScale + lowScaleBreathingRoom;
        float desiredHeight = getPanelHeight(mediaVisible, effectiveShowHeader, showDetails, showProgress, lyricsArea) * uiScale
                + lowScaleBreathingRoom;
        float height = previewMode ? desiredHeight : updateAnimatedPanelHeight(desiredHeight);
        float radius = 2.5F;

        ScaledResolution scaledResolution = ScaledResolutionCache.get();
        float[] position = getPanelPosition(scaledResolution, width, height);
        float x = position[0];
        float y = position[1];
        float animationOffset = (1.0F - renderVisibility) * ENTRY_OFFSET * uiScale;
        boolean rightAligned = x + width * 0.5F >= scaledResolution.getScaledWidth() * 0.5F;
        boolean bottomAligned = y + height * 0.5F >= scaledResolution.getScaledHeight() * 0.5F;
        x += rightAligned ? animationOffset : -animationOffset;
        y += bottomAligned ? animationOffset * 0.45F : -animationOffset * 0.25F;

        drawMindlessGlassPanel(x, y, width, height, radius, renderVisibility);

        panelVisible = true;
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = height;
        clearButtonRegions();

        int textAlpha = Math.max(86, Math.min(255, Math.round(255.0F * renderVisibility)));
        int primaryColor = Utils.mergeAlpha(0xF4F8FC, textAlpha);
        int secondaryColor = Utils.mergeAlpha(0xD2DCE7, Math.max(68, textAlpha - 32));
        int tertiaryColor = Utils.mergeAlpha(0xAEB9C5, Math.max(56, textAlpha - 60));
        int accentColor = Utils.mergeAlpha(0xC7F779, Math.max(88, textAlpha - 22));
        float padding = (11.0F * uiScale) + compactRelief * (lowScaleLayout ? 4.2F : 2.5F);
        float headerHeight = effectiveShowHeader ? Math.max((lowScaleLayout ? 11.0F : 13.0F) * uiScale, uiFont.getFontHeight() + (lowScaleLayout ? 1.0F : 2.0F) * uiScale) : 0.0F;
        float lineAdvance = getLineAdvance(uiFont, uiScale, compactRelief, lowScaleLayout);
        float sectionGap = effectiveShowHeader ? (lowScaleLayout ? 5.0F : 8.0F) * uiScale : 0.0F;
        float contentTop = y + padding + headerHeight + sectionGap;
        float progressBarHeight = Math.max(1.75F, 2.35F * uiScale);
        float timeLabelGap = effectiveShowTimeLabels ? Math.max(1.5F, 2.0F * uiScale) : 0.0F;
        float progressBottomReserve = effectiveShowTimeLabels ? timeFont.getFontHeight() + timeLabelGap : 0.0F;
        float progressBarY = y + height - padding - progressBottomReserve - progressBarHeight;
        float progressReserve = showProgress
                ? progressBarHeight + progressBottomReserve + Math.max(3.0F, 4.0F * uiScale)
                : 0.0F;
        float contentBottom = y + height - padding - progressReserve;
        float infoHeight = Math.max(20.0F * uiScale, contentBottom - contentTop);
        float contentHeight = Math.max(24.0F * uiScale, infoHeight);
        float totalTextHeight = uiFont.getFontHeight();
        if (showDetails) {
            totalTextHeight += lineAdvance;
            if (renderFooterLine) {
                totalTextHeight += lineAdvance;
            }
        }
        float titleY = contentTop + Math.max(0.0F, Math.min(4.5F * uiScale, (contentHeight - totalTextHeight) * 0.24F));
        float artBottom = showProgress ? progressBarY : y + height - padding;
        float artSize = showAlbumArt ? Math.max(28.0F * uiScale, artBottom - titleY) : 0.0F;

        float textX = x + padding;

        if (effectiveShowHeader) {
            drawHeaderRow(mediaInfo, mediaVisible, showStatusBadge, x, y, padding, uiScale, uiFont, primaryColor, secondaryColor, accentColor, lowScaleLayout);
        }

        if (showAlbumArt) {
            drawAlbumArt(mediaClient, x + padding, titleY, artSize, textAlpha);
            textX += artSize + (lowScaleLayout ? 10.0F : 13.0F) * uiScale;
        }
        else if (!mediaVisible) {
            float badgeSize = Math.max(26.0F * uiScale, Math.min(42.0F * uiScale, contentHeight - 2.0F * uiScale));
            drawOfflineBadge(x + padding, contentTop + Math.max(0.0F, (contentHeight - badgeSize) * 0.15F), badgeSize, textAlpha);
            textX += badgeSize + (lowScaleLayout ? 10.0F : 13.0F) * uiScale;
        }

        float textWidth = Math.max(lowScaleLayout ? 68.0F : 44.0F, x + width - padding - textX);
        if (renderLyrics) {
            lyricFont = getAdaptiveLyricFont(textScale * getLyricTextScaleMultiplier(), timedLyrics, textWidth);
        }
        float lyricLineAdvance = lyricsArea ? lyricFont.getFontHeight() + Math.max(lowScaleLayout ? 1.0F : 1.5F, uiScale * (lowScaleLayout ? 1.4F : 1.8F)) : 0.0F;
        String title = mediaVisible
                ? safeText(mediaInfo.getTitle(), "Nothing playing")
                : buildHeaderLabel(mediaInfo, mediaVisible);
        drawScrollingTitle(uiFont, title, textX, titleY, textWidth, primaryColor);

        if (showDetails) {
            String subtitle = mediaVisible
                    ? trimToWidth(uiFont, buildSubtitle(mediaInfo), Math.max(18, Math.round(textWidth)))
                    : trimToWidth(uiFont, mediaClient.getStatusMessage(), Math.max(18, Math.round(textWidth)));
            drawMiniText(uiFont, subtitle, textX, titleY + lineAdvance, secondaryColor, true);
            if (renderFooterLine) {
                String footer = mediaVisible
                        ? trimToWidth(uiFont, buildFooter(mediaInfo, showSourceApp), Math.max(18, Math.round(textWidth)))
                        : trimToWidth(uiFont, "Waiting for media", Math.max(18, Math.round(textWidth)));
                drawMiniText(uiFont, footer, textX, titleY + lineAdvance * 2.0F, tertiaryColor, true);
            }
        }

        if (lyricsArea) {
            float separatorY = titleY + totalTextHeight + Math.max(1.5F, 2.0F * uiScale);
            // One even rule. The second, taller bar over the first 28% made the separator look
            // thicker on the left than on the right.
            int separatorColor = Utils.mergeAlpha(0xFFFFFF, Math.max(22, textAlpha / 6));
            RenderUtils.drawRect(textX, separatorY, textX + textWidth, separatorY + 0.5F, separatorColor);
            GL20.glUseProgram(0);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            float lyricY = separatorY + Math.max(3.5F, 4.5F * uiScale);
            float lyricBottom = showProgress
                    ? progressBarY - Math.max(4.0F, 4.75F * uiScale)
                    : y + height - padding;
            float lyricViewportHeight = Math.max(lyricLineAdvance, lyricBottom - lyricY);
            if (renderLyrics) {
                renderLyricsTimeline(lyricFont, lyricsTimeline, textX, lyricY, textWidth,
                        lyricLineAdvance, lyricViewportHeight, primaryColor, secondaryColor);
            } else {
                String status = timedLyrics.getStatusMessage();
                if (status == null || status.isEmpty()) status = "Loading lyrics";
                lyricFont.drawString(status, textX, lyricY, secondaryColor, false);
            }
        }

        if (showProgress) {
            progressX = x + padding;
            progressWidth = Math.max(28.0F, width - padding * 2.0F);
            if (effectiveShowTimeLabels) {
                String currentTime = formatTime(livePositionMs);
                String duration = formatTime(mediaInfo.getDurationMs());
                int durationWidth = timeFont.getStringWidth(duration);
                float timeLabelY = progressBarY + progressBarHeight + timeLabelGap;
                int currentTimeColor = Utils.mergeAlpha(HUD.getHudColor(0.0D), Math.max(110, textAlpha - 22));
                int durationColor = Utils.mergeAlpha(HUD.getHudColor(90.0D), Math.max(90, textAlpha - 48));
                drawMiniText(timeFont, currentTime, progressX, timeLabelY, currentTimeColor, true);
                drawMiniText(timeFont, duration, progressX + progressWidth - durationWidth, timeLabelY, durationColor, true);
            }

            progressY = progressBarY;
            progressHeight = progressBarHeight;
            progressActive = true;
            lastDurationMs = mediaInfo.getDurationMs();
            float progress = Math.max(0.0F, Math.min(1.0F, livePositionMs / (float) Math.max(1L, mediaInfo.getDurationMs())));
            RoundedUtils.drawRound(progressX, progressY, progressWidth, progressHeight, progressHeight * 0.5F,
                    false, new Color(255, 255, 255, Math.max(20, textAlpha / 7)));
            float filledWidth = progressWidth * progress;
            if (filledWidth > 0.01F) {
                if (SpotifyMiniPlayer.progressBarColorMode != null
                        && (int) SpotifyMiniPlayer.progressBarColorMode.getInput() == 1) {
                    Color accentLeft = new Color(HUD.getHudColor(0.0D), true);
                    int waveAlpha = Math.max(120, textAlpha - 16);
                    int leftColor = new Color(accentLeft.getRed(), accentLeft.getGreen(),
                            accentLeft.getBlue(), waveAlpha).getRGB();
                    int rightColor = new Color(accentLeft.getRed(), accentLeft.getGreen(),
                            accentLeft.getBlue(), waveAlpha).getRGB();
                    RenderUtils.drawHorizontalGradientRect(progressX, progressY,
                            progressX + filledWidth, progressY + progressHeight, leftColor, rightColor);
                } else {
                    Color waveLeft = new Color(HUD.getHudColor(0.0D), true);
                    Color waveRight = new Color(HUD.getHudColor(90.0D), true);
                    int waveAlpha = Math.max(120, textAlpha - 16);
                    int leftColor = new Color(waveLeft.getRed(), waveLeft.getGreen(), waveLeft.getBlue(), waveAlpha).getRGB();
                    int rightColor = new Color(waveRight.getRed(), waveRight.getGreen(), waveRight.getBlue(), waveAlpha).getRGB();
                    RenderUtils.drawHorizontalGradientRect(progressX, progressY,
                            progressX + filledWidth, progressY + progressHeight, leftColor, rightColor);
                }
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            }
            float knobX = progressX + progressWidth * progress;
            float knobCenterY = progressY + progressHeight * 0.5F;
            Color knobAccent = new Color(HUD.getHudColor(progress * 90.0D), true);
            drawProgressCircle(knobX, knobCenterY, Math.max(1.45F, progressHeight * 0.72F),
                    new Color(knobAccent.getRed(), knobAccent.getGreen(), knobAccent.getBlue(), textAlpha));
        }
        else {
            progressActive = false;
            lastDurationMs = 0L;
        }

        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        GL11.glColorMask(true, true, true, true);
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static void drawMindlessGlassPanel(float x, float y, float width, float height,
                                               float radius, float visibility) {
        if (SpotifyMiniPlayer.noBackground != null && SpotifyMiniPlayer.noBackground.isToggled()) return;

        int blurMaskAlpha = Math.max(1, Math.min(255, Math.round(255.0F * visibility)));
        int shadowAlpha = Math.max(0, Math.min(0x72, Math.round(0x72 * visibility)));
        int surfaceAlpha = Math.max(0, Math.min(0xA8, Math.round(0xA8 * visibility)));

        BlurUtils.prepareBlur(x, y, width, height);
        RoundedUtils.drawRound(x, y, width, height, radius,
                new Color(0, 0, 0, blurMaskAlpha));
        // Only composite the player rectangle. The blurred scene itself is
        // shared with chat/scoreboard for this frame, while avoiding a second
        // full-screen blend just for this small HUD element.
        BlurUtils.blurEndRegion(1, 1.4F, 0.72F, x, y, width, height);

        RoundedUtils.drawRoundShadow(x, y, width, height, radius, 4.5F,
                shadowAlpha << 24);
        RoundedUtils.drawRound(x, y, width, height, radius,
                new Color(0, 0, 0, surfaceAlpha));

        if (SpotifyMiniPlayer.dynamicIslandStyle == null || SpotifyMiniPlayer.dynamicIslandStyle.isToggled()) {
            Color left = new Color(HUD.getHudColor(0.0D), true);
            Color right = new Color(HUD.getHudColor(90.0D), true);
            int tintAlpha = Math.max(0, Math.min(12, Math.round(12.0F * visibility)));
            float accentInset = Math.max(1.0F, radius * 0.72F);
            RenderUtils.drawHorizontalGradientRect(
                    x + accentInset, y + 0.75F,
                    x + width - accentInset, y + 1.35F,
                    new Color(left.getRed(), left.getGreen(), left.getBlue(), tintAlpha).getRGB(),
                    new Color(right.getRed(), right.getGreen(), right.getBlue(), tintAlpha).getRGB());
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        }

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    public static boolean handleMouseClick(int button) {
        return false;
    }

    private static LyricsTimeline buildLyricsTimeline(SystemMediaInfo mediaInfo, TimedLyrics timedLyrics, long livePositionMs) {
        String trackKey = buildLyricsTrackKey(mediaInfo);
        boolean trackChanged = !trackKey.equals(lastLyricsTrackKey);
        if (trackChanged) {
            lastLyricsTrackKey = trackKey;
            lastLyricsIndex = -1;
            lastLyricsPositionMs = -1L;
            clearLyricsLayout();
        }

        if (timedLyrics == null) {
            return LyricsTimeline.status("Finding synced lyrics...");
        }
        if (timedLyrics.isLoading()) {
            return LyricsTimeline.status("Finding synced lyrics...");
        }
        if (!timedLyrics.isAvailable()) {
            return LyricsTimeline.status(safeText(timedLyrics.getStatusMessage(), "No synced lyrics"));
        }

        long lyricOffsetMs = SpotifyMiniPlayer.lyricSyncOffset == null
                ? 0L : Math.round(SpotifyMiniPlayer.lyricSyncOffset.getInput());
        long lyricLookupPositionMs = Math.max(0L, livePositionMs + LYRIC_RENDER_LEAD_MS + lyricOffsetMs);
        int activeIndex = timedLyrics.findLineIndex(lyricLookupPositionMs);
        boolean rewound = lastLyricsPositionMs >= 0L
                && livePositionMs + LYRIC_REWIND_TOLERANCE_MS < lastLyricsPositionMs;
        boolean seeked = trackChanged || rewound
                || (lastLyricsPositionMs >= 0L && Math.abs(livePositionMs - lastLyricsPositionMs) > LYRIC_SEEK_RESET_MS);
        if (!seeked && lastLyricsIndex >= 0 && activeIndex >= 0) {
            if (activeIndex < lastLyricsIndex) {
                activeIndex = lastLyricsIndex;
            }
        }

        lastLyricsIndex = activeIndex;
        lastLyricsPositionMs = livePositionMs;
        return new LyricsTimeline(timedLyrics, activeIndex, "", seeked, lyricLookupPositionMs);
    }

    private static void renderLyricsTimeline(RavenFontRenderer uiFont, LyricsTimeline timeline, float textX, float lyricY,
                                             float textWidth, float lineAdvance, float viewportHeight,
                                             int primaryColor, int secondaryColor) {
        if (!timeline.statusMessage.isEmpty() || timeline.timedLyrics == null) {
            List<String> statusLines = wrapText(uiFont, timeline.statusMessage, Math.max(18, Math.round(textWidth)));
            for (int i = 0; i < statusLines.size(); i++) {
                drawMiniText(uiFont, statusLines.get(i), textX, lyricY + i * lineAdvance, secondaryColor, true);
            }
            return;
        }

        List<WrappedLyric> lyrics = getWrappedLyrics(uiFont, timeline.timedLyrics, textWidth, lineAdvance);
        if (lyrics.isEmpty()) {
            return;
        }

        int activeIndex = Math.max(-1, Math.min(timeline.activeIndex, lyrics.size() - 1));
        boolean fullLyricsView = SpotifyMiniPlayer.fullLyricsView != null
                && SpotifyMiniPlayer.fullLyricsView.isToggled();
        if (activeIndex < 0 && !fullLyricsView) {
            return;
        }
        int focusIndex = activeIndex < 0 ? 0 : activeIndex;
        WrappedLyric focus = lyrics.get(focusIndex);
        float totalHeight = lyrics.get(lyrics.size() - 1).bottom;

        // Center active lyric in the viewport; prev/next lines visible at edges
        float targetOffset;
        if (fullLyricsView) {
            targetOffset = focus.top - viewportHeight * 0.34F;
        } else {
            float focusCenter = focus.top + focus.height * 0.5F;
            targetOffset = focusCenter - viewportHeight * 0.5F;
        }
        if (focus.height > viewportHeight) {
            targetOffset = focus.top;
        }
        targetOffset = Math.max(0.0F, Math.min(Math.max(0.0F, totalHeight - viewportHeight), targetOffset));
        updateLyricScroll(targetOffset, timeline.seeked);

        long now = animationTimeMs();
        float scrollOffset = getLyricScrollOffset(now);
        // Show one line before and after active so prev/next peek into view
        int firstIndex = fullLyricsView ? 0 : Math.max(0, activeIndex - 1);
        int lastIndex  = fullLyricsView ? lyrics.size() - 1 : Math.min(lyrics.size() - 1, activeIndex + 1);
        RenderUtils.scissorPushGui(textX - 2.0F, lyricY - 1.0F, textWidth + 4.0F, viewportHeight);
        try {
            for (int i = firstIndex; i <= lastIndex; i++) {
                WrappedLyric lyric = lyrics.get(i);
                float drawY = lyricY + lyric.top - scrollOffset;
                if (drawY > lyricY + viewportHeight || drawY + lyric.height < lyricY) {
                    continue;
                }
                if (i == activeIndex) {
                    drawKaraokeLyric(uiFont, lyric, timeline, textX, drawY, lineAdvance,
                            primaryColor, secondaryColor);
                }
                else {
                    int color = i < activeIndex
                            ? applyTextAlpha(secondaryColor, 0.34F)
                            : applyTextAlpha(secondaryColor, 0.62F);
                    for (int line = 0; line < lyric.lines.size(); line++) {
                        drawMiniText(uiFont, lyric.lines.get(line), textX,
                                drawY + line * lineAdvance, color, true);
                    }
                }
            }
        }
        finally {
            RenderUtils.scissorPop();
        }
    }

    private static void drawScrollingTitle(RavenFontRenderer font, String title, float x, float y,
                                           float availableWidth, int color) {
        String safeTitle = safeText(title, "Nothing playing");
        int titleWidth = font.getStringWidth(safeTitle);
        if (!safeTitle.equals(marqueeTitle)) {
            marqueeTitle = safeTitle;
            marqueeStartedAt = animationTimeMs();
        }
        if (titleWidth <= availableWidth) {
            drawMiniText(font, safeTitle, x, y, color, true);
            return;
        }

        long elapsed = Math.max(0L, animationTimeMs() - marqueeStartedAt);
        long holdMs = 3000L;
        // Keep moving after the end first becomes visible. Reset only once the
        // title's trailing edge reaches the viewport's starting (left) edge.
        float maxOffset = titleWidth + 2.0F;
        float pixelsPerSecond = 18.0F;
        long scrollMs = Math.max(1L, Math.round(maxOffset / pixelsPerSecond * 1000.0F));
        long cycleMs = holdMs + scrollMs;
        long cycleElapsed = elapsed % cycleMs;
        float offset = cycleElapsed < holdMs ? 0.0F
                : Math.min(maxOffset, (cycleElapsed - holdMs) / (float) scrollMs * maxOffset);

        RenderUtils.scissorPushGui(x - 1.0F, y - 1.0F, availableWidth + 2.0F,
                font.getFontHeight() + 2.0F);
        try {
            drawMiniText(font, safeTitle, x - offset, y, color, true);
        }
        finally {
            RenderUtils.scissorPop();
        }
    }

    private static void drawKaraokeLyric(RavenFontRenderer font, WrappedLyric lyric,
                                          LyricsTimeline timeline, float textX, float drawY,
                                          float lineAdvance, int primaryColor, int secondaryColor) {
        boolean karaokeEnabled = SpotifyMiniPlayer.karaokeLyrics != null
                && SpotifyMiniPlayer.karaokeLyrics.isToggled();
        if (!karaokeEnabled || timeline.activeIndex < 0 || timeline.timedLyrics == null) {
            for (int line = 0; line < lyric.lines.size(); line++) {
                drawMiniText(font, lyric.lines.get(line), textX, drawY + line * lineAdvance,
                        primaryColor, true);
            }
            return;
        }

        TimedLyrics.LyricsLine activeLine = timeline.timedLyrics.getLine(timeline.activeIndex);
        TimedLyrics.LyricsLine nextLine = timeline.timedLyrics.getLine(timeline.activeIndex + 1);
        long startMs = activeLine == null ? timeline.positionMs : activeLine.getTimestampMs();
        long endMs = nextLine == null ? startMs + 4000L : nextLine.getTimestampMs();
        long rawDurationMs = Math.max(500L, endMs - startMs);
        long trailingHoldMs = Math.min(350L, Math.max(80L, Math.round(rawDurationMs * 0.10F)));
        long visualEndMs = Math.max(startMs + 500L, endMs - trailingHoldMs);
        long durationMs = Math.max(500L, visualEndMs - startMs);
        float spokenProgress = Math.max(0.0F, Math.min(1.0F,
                (timeline.positionMs - startMs) / (float) durationMs));

        float totalTextWidth = 0.0F;
        for (String line : lyric.lines) {
            totalTextWidth += Math.max(1, font.getStringWidth(line));
        }
        float spokenWidth = totalTextWidth * spokenProgress;
        float consumedWidth = 0.0F;
        int mutedColor = applyTextAlpha(primaryColor, 0.86F);
        int gradientAlpha = (primaryColor >>> 24) & 0xFF;

        for (int lineIndex = 0; lineIndex < lyric.lines.size(); lineIndex++) {
            String line = lyric.lines.get(lineIndex);
            float lineY = drawY + lineIndex * lineAdvance;
            float lineWidth = Math.max(1, font.getStringWidth(line));
            drawMiniText(font, line, textX, lineY, mutedColor, false);

            float fillWidth = Math.max(0.0F, Math.min(lineWidth, spokenWidth - consumedWidth));
            if (fillWidth > 0.01F) {
                RenderUtils.scissorPushGui(textX, lineY - 1.0F, fillWidth + 0.5F,
                        font.getFontHeight() + 2.0F);
                try {
                    drawGradientLyricString(font, line, textX, lineY, gradientAlpha,
                            lyric.top * 2.2D + lineIndex * 34.0D);
                }
                finally {
                    RenderUtils.scissorPop();
                }
            }
            consumedWidth += lineWidth;
        }
    }

    private static void drawGradientLyricString(RavenFontRenderer font, String text, float x,
                                                 float y, int alpha, double phaseOffset) {
        // One font pass keeps karaoke animation cheap. The previous per-letter
        // draw calls repeatedly pushed matrices and rebound state, which could
        // make the entire player feel as though it rendered at a low frame rate.
        font.drawGlyphString(text, x, y,
                (character, glyphOffset, glyphWidth, formattingColor) ->
                        Utils.mergeAlpha(HUD.getHudColor(
                                phaseOffset + glyphOffset * 3.6D), alpha), false);
    }

    private static void drawProgressCircle(float centerX, float centerY, float radius, Color color) {
        boolean textureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.disableTexture2D();
        GL11.glColor4f(color.getRed() / 255.0F, color.getGreen() / 255.0F,
                color.getBlue() / 255.0F, color.getAlpha() / 255.0F);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(centerX, centerY);
        for (int i = 0; i <= 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            GL11.glVertex2d(centerX + Math.cos(angle) * radius,
                    centerY + Math.sin(angle) * radius);
        }
        GL11.glEnd();
        if (textureEnabled) {
            GlStateManager.enableTexture2D();
        }
        if (!blendEnabled) {
            GlStateManager.disableBlend();
        }
        // The circle color was set through raw OpenGL, so invalidate
        // GlStateManager's cached value before restoring white.
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.resetColor();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static List<WrappedLyric> getWrappedLyrics(RavenFontRenderer font, TimedLyrics timedLyrics,
                                                        float textWidth, float lineAdvance) {
        String layoutKey = HUD.getSelectedFontName() + ":" + Math.round(HUD.getSelectedFontScale() * 1000.0F)
                + ":" + Math.round(textWidth) + ":" + Math.round(lineAdvance * 100.0F);
        if (timedLyrics == cachedLyricsSource && layoutKey.equals(cachedLyricsLayoutKey)) {
            return cachedWrappedLyrics;
        }

        if (asyncWrappedLyrics != null && asyncWrappedLayoutKey != null
                && asyncWrappedLayoutKey.equals(layoutKey) && asyncWrappedSource == timedLyrics) {
            cachedLyricsSource = timedLyrics;
            cachedLyricsLayoutKey = layoutKey;
            cachedWrappedLyrics = asyncWrappedLyrics;
            asyncWrappedLyrics = null;
            asyncWrappedLayoutKey = null;
            asyncWrappedSource = null;
            return cachedWrappedLyrics;
        }

        int lineCount = timedLyrics.getLines().size();
        if (lineCount <= 30) {
            List<WrappedLyric> wrapped = computeWrappedLyrics(font, timedLyrics, textWidth, lineAdvance);
            cachedLyricsSource = timedLyrics;
            cachedLyricsLayoutKey = layoutKey;
            cachedWrappedLyrics = Collections.unmodifiableList(wrapped);
            return cachedWrappedLyrics;
        }

        if (asyncWrappedTask == null || asyncWrappedTask.isDone()) {
            final TimedLyrics lyricsRef = timedLyrics;
            final String keyRef = layoutKey;
            final RavenFontRenderer fontRef = font;
            final float tw = textWidth;
            final float la = lineAdvance;
            asyncWrappedTask = Raven.getCachedExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    List<WrappedLyric> result = computeWrappedLyrics(fontRef, lyricsRef, tw, la);
                    asyncWrappedLyrics = Collections.unmodifiableList(result);
                    asyncWrappedLayoutKey = keyRef;
                    asyncWrappedSource = lyricsRef;
                }
            });
        }

        // Hold the previous layout while the new one is computed instead of returning nothing.
        // Returning an empty list here blanks the lyrics for however many frames the wrap takes,
        // which is a visible flash. A stale layout for a frame or two is far less noticeable --
        // and during an actual track change the manager reports "loading", so the lyrics block
        // is not drawn at all and no stale text can appear.
        if (layoutKey.equals(cachedLyricsLayoutKey) && !cachedWrappedLyrics.isEmpty()) {
            return cachedWrappedLyrics;
        }
        return Collections.emptyList();
    }

    private static List<WrappedLyric> computeWrappedLyrics(RavenFontRenderer font, TimedLyrics timedLyrics,
                                                            float textWidth, float lineAdvance) {
        List<WrappedLyric> wrapped = new ArrayList<WrappedLyric>();
        float top = 0.0F;
        float entryGap = Math.max(1.5F, lineAdvance * 0.28F);
        int maxWidth = Math.max(18, Math.round(textWidth));
        for (int i = 0; i < timedLyrics.getLines().size(); i++) {
            String text = safeText(timedLyrics.getLines().get(i).getText(), "Instrumental");
            List<String> lines = wrapText(font, text, maxWidth);
            float height = Math.max(lineAdvance, lines.size() * lineAdvance);
            wrapped.add(new WrappedLyric(lines, top, top + height));
            top += height + entryGap;
        }
        return wrapped;
    }

    private static RavenFontRenderer getAdaptiveLyricFont(float requestedScale, TimedLyrics timedLyrics,
                                                           float textWidth) {
        String key = lastLyricsTrackKey + ":" + Math.round(textWidth)
                + ":" + Math.round(requestedScale * 1000.0F)
                + ":" + HUD.getSelectedFontName() + ":" + Math.round(HUD.getSelectedFontScale() * 1000.0F);
        if (cachedAdaptiveLyricFont != null && key.equals(cachedAdaptiveFontKey)) {
            return cachedAdaptiveLyricFont;
        }

        if (asyncAdaptiveLyricFont != null && key.equals(asyncAdaptiveFontKey)) {
            cachedAdaptiveFontKey = key;
            cachedAdaptiveLyricFont = asyncAdaptiveLyricFont;
            asyncAdaptiveFontKey = null;
            asyncAdaptiveLyricFont = null;
            return cachedAdaptiveLyricFont;
        }

        if (asyncAdaptiveFontTask == null || asyncAdaptiveFontTask.isDone()) {
            final float rs = requestedScale;
            final TimedLyrics lyricsRef = timedLyrics;
            final float tw = textWidth;
            final String keyRef = key;
            asyncAdaptiveFontTask = Raven.getCachedExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    float fitMultiplier = 1.0F;
                    RavenFontRenderer candidate = getUiFontRenderer(rs);
                    if (lyricsRef != null && lyricsRef.isAvailable()) {
                        int maxWidth = Math.max(18, Math.round(tw));
                        while (fitMultiplier > 0.78F && hasLyricsWiderThanTwoLines(candidate, lyricsRef, maxWidth)) {
                            fitMultiplier = Math.max(0.78F, fitMultiplier - 0.055F);
                            candidate = getUiFontRenderer(rs * fitMultiplier);
                        }
                    }
                    asyncAdaptiveFontKey = keyRef;
                    asyncAdaptiveLyricFont = candidate;
                }
            });
        }

        return cachedAdaptiveLyricFont != null ? cachedAdaptiveLyricFont : getUiFontRenderer(requestedScale);
    }

    private static boolean hasLyricsWiderThanTwoLines(RavenFontRenderer font, TimedLyrics timedLyrics,
                                                       int maxWidth) {
        for (TimedLyrics.LyricsLine lyric : timedLyrics.getLines()) {
            if (wrapText(font, safeText(lyric.getText(), "Instrumental"), maxWidth).size() > 2) {
                return true;
            }
        }
        return false;
    }

    private static List<String> wrapText(RavenFontRenderer font, String text, int maxWidth) {
        if (text == null || text.trim().isEmpty()) {
            return Collections.singletonList("Instrumental");
        }

        List<String> lines = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        String[] words = text.trim().split("\\s+");
        for (String word : words) {
            String candidate = current.length() == 0 ? word : current.toString() + " " + word;
            if (font.getStringWidth(candidate) <= maxWidth) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }

            if (current.length() > 0) {
                lines.add(current.toString());
                current.setLength(0);
            }
            appendWrappedWord(font, word, maxWidth, lines, current);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines.isEmpty() ? Collections.singletonList("Instrumental") : lines;
    }

    private static void appendWrappedWord(RavenFontRenderer font, String word, int maxWidth,
                                          List<String> lines, StringBuilder current) {
        if (font.getStringWidth(word) <= maxWidth) {
            current.append(word);
            return;
        }

        StringBuilder part = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            char character = word.charAt(i);
            if (part.length() > 0 && font.getStringWidth(part.toString() + character) > maxWidth) {
                lines.add(part.toString());
                part.setLength(0);
            }
            part.append(character);
        }
        current.append(part);
    }

    private static void updateLyricScroll(float targetOffset, boolean force) {
        long now = animationTimeMs();
        float currentOffset = getLyricScrollOffset(now);
        if (!lyricScrollInitialized || !shouldAnimateLyrics()) {
            lyricScrollOffset = targetOffset;
            lyricScrollStartOffset = targetOffset;
            lyricScrollTargetOffset = targetOffset;
            lyricScrollStartedAt = 0L;
            lyricScrollInitialized = true;
            return;
        }
        if (Math.abs(targetOffset - lyricScrollTargetOffset) < 0.01F) {
            lyricScrollOffset = currentOffset;
            return;
        }
        lyricScrollOffset = currentOffset;
        lyricScrollStartOffset = currentOffset;
        lyricScrollTargetOffset = targetOffset;
        lyricScrollStartedAt = now;
    }

    private static float getLyricScrollOffset(long now) {
        if (!lyricScrollInitialized || lyricScrollStartedAt <= 0L) {
            return lyricScrollTargetOffset;
        }
        long duration = getLyricTransitionDurationMs();
        float progress = Math.max(0.0F, Math.min(1.0F, (now - lyricScrollStartedAt) / (float) Math.max(1L, duration)));
        float eased = easeSmootherStep(progress);
        lyricScrollOffset = lyricScrollStartOffset + (lyricScrollTargetOffset - lyricScrollStartOffset) * eased;
        if (progress >= 0.999F) {
            lyricScrollOffset = lyricScrollTargetOffset;
            lyricScrollStartedAt = 0L;
        }
        return lyricScrollOffset;
    }

    private static void clearLyricsLayout() {
        cachedLyricsSource = null;
        cachedLyricsLayoutKey = "";
        cachedWrappedLyrics = Collections.emptyList();
        cachedAdaptiveFontKey = "";
        cachedAdaptiveLyricFont = null;
        cachedUiFontKey = "";
        cachedUiFont = null;
        asyncAdaptiveFontKey = null;
        asyncAdaptiveLyricFont = null;
        if (asyncAdaptiveFontTask != null) {
            asyncAdaptiveFontTask.cancel(true);
            asyncAdaptiveFontTask = null;
        }
        asyncWrappedLyrics = null;
        asyncWrappedLayoutKey = null;
        asyncWrappedSource = null;
        if (asyncWrappedTask != null) {
            asyncWrappedTask.cancel(true);
            asyncWrappedTask = null;
        }
        lyricScrollOffset = 0.0F;
        lyricScrollStartOffset = 0.0F;
        lyricScrollTargetOffset = 0.0F;
        lyricScrollStartedAt = 0L;
        lyricScrollInitialized = false;
    }

    private static int applyTextAlpha(int argbColor, float alphaScale) {
        int alpha = (argbColor >>> 24) & 0xFF;
        int scaledAlpha = Math.max(0, Math.min(255, Math.round(alpha * Math.max(0.0F, Math.min(1.0F, alphaScale)))));
        return (argbColor & 0x00FFFFFF) | (scaledAlpha << 24);
    }

    private static float easeSmootherStep(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        return clamped * clamped * clamped * (clamped * (clamped * 6.0F - 15.0F) + 10.0F);
    }

    private static void drawHeaderRow(SystemMediaInfo mediaInfo, boolean mediaVisible, boolean showStatusBadge, float x, float y, float padding, float uiScale, RavenFontRenderer uiFont, int primaryColor, int secondaryColor, int accentColor, boolean lowScaleLayout) {
        float dotSize = (lowScaleLayout ? 5.0F : 6.0F) * uiScale;
        float dotX = x + padding;
        float dotY = y + padding + (lowScaleLayout ? 2.8F : 3.5F) * uiScale;
        RoundedUtils.drawRound(dotX, dotY, dotSize, dotSize, dotSize * 0.5F, false, new Color(90, 224, 112, 220));
        float textX = dotX + dotSize + (lowScaleLayout ? 4.0F : 5.0F) * uiScale;
        float textY = y + padding + (lowScaleLayout ? 1.4F : 2.0F) * uiScale;
        String label = buildHeaderLabel(mediaInfo, mediaVisible);
        drawMiniText(uiFont, label, textX, textY, primaryColor, true);
        if (!showStatusBadge) {
            return;
        }

        String dots = getAnimatedDots();
        if (!dots.isEmpty()) {
            drawMiniText(uiFont, dots, textX + uiFont.getStringWidth(label) + (lowScaleLayout ? 3.0F : 4.0F) * uiScale, textY, mediaVisible ? accentColor : secondaryColor, true);
        }
    }

    private static String buildHeaderLabel(SystemMediaInfo mediaInfo, boolean mediaVisible) {
        String platform = "";
        if (mediaInfo != null) {
            platform = stripExecutableSuffix(safeText(mediaInfo.getSourceApp(), ""));
        }

        if (platform.isEmpty()) {
            return "Now playing";
        }

        return "Now playing " + platform;
    }

    private static float getPanelWidth(boolean mediaVisible, boolean showAlbumArt) {
        if (!mediaVisible) {
            return IDLE_WIDTH;
        }
        return showAlbumArt ? PLAYER_WIDTH_WITH_ART : PLAYER_WIDTH_NO_ART;
    }

    private static float getPanelHeight(boolean mediaVisible, boolean showHeader, boolean showDetails,
                                        boolean showProgress, boolean showLyrics) {
        if (!mediaVisible) {
            return IDLE_HEIGHT;
        }

        float height = PLAYER_HEIGHT;
        if (!showHeader) {
            height -= 14.0F;
        }
        if (!showDetails) {
            height -= 14.0F;
        }
        if (!showProgress) {
            height -= 12.0F;
        }
        if (!showLyrics) {
            height -= 62.0F;
        }
        return Math.max(70.0F, height);
    }

    private static void clearButtonRegions() {
        progressActive = false;
        previousButtonActive = false;
        playPauseButtonActive = false;
        nextButtonActive = false;
        lastDurationMs = 0L;
    }

    private static void clearInteractiveRegions() {
        panelVisible = false;
        panelX = 0.0F;
        panelY = 0.0F;
        panelWidth = 0.0F;
        panelHeight = 0.0F;
        clearButtonRegions();
    }

    private static void drawAlbumArt(SystemMediaClient mediaClient, float x, float y, float size, int alpha) {
        ResourceLocation albumArt = mediaClient.getAlbumArtTextureLocation();
        if (albumArt == null) {
            String glyph = "\u266A";
            float glyphScale = Math.max(0.8F, Math.min(1.25F, size / 24.0F));
            RavenFontRenderer glyphFont = getUiFontRenderer(glyphScale);
            float glyphX = x + (size - glyphFont.getStringWidth(glyph)) * 0.5F;
            float glyphY = y + (size - glyphFont.getFontHeight()) * 0.5F - 1.0F;
            drawMiniText(glyphFont, glyph, glyphX, glyphY, Utils.mergeAlpha(0xF5FBFF, Math.max(72, alpha - 52)), true);
            return;
        }

        boolean depthEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blendEnabled = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);

        RenderUtils.prepareGuiTextureRenderState();
        mc.getTextureManager().bindTexture(albumArt);
        GlStateManager.color(1.0F, 1.0F, 1.0F, Math.max(0.0F, Math.min(1.0F, alpha / 255.0F)));
        Gui.drawModalRectWithCustomSizedTexture(Math.round(x), Math.round(y), 0.0F, 0.0F, Math.round(size), Math.round(size), size, size);
        RenderUtils.restoreGuiRenderState(depthEnabled, blendEnabled, depthMask);
    }

    private static void drawOfflineBadge(float x, float y, float size, int alpha) {
        RoundedUtils.drawRound(x - 1.0F, y - 1.0F, size + 2.0F, size + 2.0F, Math.max(7.0F, size * 0.24F), false, new Color(16, 20, 28, Math.max(26, alpha / 5)));
        RoundedUtils.drawRound(x, y, size, size, Math.max(6.0F, size * 0.22F), false, new Color(255, 255, 255, Math.max(18, alpha / 8)));
        String glyph = "\u266B";
        float glyphScale = Math.max(0.8F, Math.min(1.25F, size / 24.0F));
        RavenFontRenderer glyphFont = getUiFontRenderer(glyphScale);
        float glyphX = x + (size - glyphFont.getStringWidth(glyph)) * 0.5F;
        float glyphY = y + (size - glyphFont.getFontHeight()) * 0.5F - 1.0F;
        drawMiniText(glyphFont, glyph, glyphX, glyphY, Utils.mergeAlpha(0xF5FBFF, Math.max(72, alpha - 52)), true);
    }

    private static void updateAnimation(boolean shouldShow) {
        long now = animationTimeMs();
        float target = shouldShow ? 1.0F : 0.0F;
        if (visibilityAnimationStartedAt < 0L) {
            visibilityStart = visibility;
            visibilityTarget = target;
            visibilityAnimationStartedAt = now;
        }
        else if (target != visibilityTarget) {
            visibility = getVisibilityAt(now);
            visibilityStart = visibility;
            visibilityTarget = target;
            visibilityAnimationStartedAt = now;
        }
        visibility = getVisibilityAt(now);
    }

    private static float getVisibilityAt(long now) {
        if (visibilityAnimationStartedAt < 0L) {
            return visibilityTarget;
        }
        long duration = visibilityTarget > visibilityStart ? 280L : 220L;
        float progress = Math.max(0.0F, Math.min(1.0F,
                (now - visibilityAnimationStartedAt) / (float) duration));
        float eased = easeSmootherStep(progress);
        return visibilityStart + (visibilityTarget - visibilityStart) * eased;
    }

    private static float updateAnimatedPanelHeight(float desiredHeight) {
        long now = animationTimeMs();
        if (animatedPanelHeight < 0.0F) {
            animatedPanelHeight = desiredHeight;
            panelHeightStart = desiredHeight;
            panelHeightTarget = desiredHeight;
            panelHeightAnimationStartedAt = -1L;
            return desiredHeight;
        }

        if (Math.abs(desiredHeight - panelHeightTarget) > 0.1F) {
            animatedPanelHeight = getAnimatedPanelHeightAt(now);
            panelHeightStart = animatedPanelHeight;
            panelHeightTarget = desiredHeight;
            panelHeightAnimationStartedAt = now;
        }

        animatedPanelHeight = getAnimatedPanelHeightAt(now);
        return animatedPanelHeight;
    }

    private static float getAnimatedPanelHeightAt(long now) {
        if (panelHeightAnimationStartedAt < 0L) {
            return panelHeightTarget;
        }
        long duration = panelHeightTarget > panelHeightStart ? 340L : 280L;
        float progress = Math.max(0.0F, Math.min(1.0F,
                (now - panelHeightAnimationStartedAt) / (float) duration));
        float value = panelHeightStart
                + (panelHeightTarget - panelHeightStart) * easeSmootherStep(progress);
        if (progress >= 1.0F) {
            panelHeightAnimationStartedAt = -1L;
            panelHeightStart = panelHeightTarget;
            return panelHeightTarget;
        }
        return value;
    }

    private static void reset() {
        visibility = 0.0F;
        visibilityStart = 0.0F;
        visibilityTarget = 0.0F;
        visibilityAnimationStartedAt = -1L;
        lastVisibleInfo = SystemMediaInfo.unavailable();
        marqueeTitle = "";
        marqueeStartedAt = 0L;
        animatedPanelHeight = -1.0F;
        panelHeightStart = 0.0F;
        panelHeightTarget = 0.0F;
        panelHeightAnimationStartedAt = -1L;
        clearLyricsLayout();
        clearInteractiveRegions();
    }

    private static SystemMediaInfo createPreviewInfo() {
        return new SystemMediaInfo(
                true,
                "Spotify",
                "Mindless Nights",
                "Moonlight Avenue",
                "Afterglow",
                "Playing",
                71250L,
                201000L,
                "",
                null
        );
    }

    private static float[] getPanelPosition(ScaledResolution scaledResolution, float width, float height) {
        float margin = (SpotifyMiniPlayer.noBackground != null && SpotifyMiniPlayer.noBackground.isToggled()) ? 2.0F : 8.0F;
        float x;
        float y;
        if (SpotifyMiniPlayer.hasCustomPosition()) {
            float maxX = Math.max(0.0F, scaledResolution.getScaledWidth() - width);
            float maxY = Math.max(0.0F, scaledResolution.getScaledHeight() - height);
            x = maxX * SpotifyMiniPlayer.getCustomNormalizedX();
            y = maxY * SpotifyMiniPlayer.getCustomNormalizedY();
            return clampAwayFromDynamicIsland(scaledResolution, x, y, width, height, margin);
        }
        x = scaledResolution.getScaledWidth() - width - margin;
        y = margin;
        return clampAwayFromDynamicIsland(scaledResolution, x, y, width, height, margin);
    }

    private static float[] clampAwayFromDynamicIsland(ScaledResolution scaledResolution, float x, float y, float width, float height, float margin) {
        float maxX = Math.max(0.0F, scaledResolution.getScaledWidth() - width - margin);
        float maxY = Math.max(0.0F, scaledResolution.getScaledHeight() - height - margin);
        float clampedX = Math.max(margin, Math.min(maxX, x));
        float clampedY = Math.max(margin, Math.min(maxY, y));
        return new float[] { clampedX, clampedY };
    }

    private static String buildSubtitle(SystemMediaInfo mediaInfo) {
        String artist = safeText(mediaInfo.getArtist(), "");
        String album = safeText(mediaInfo.getAlbum(), "");
        if (!artist.isEmpty() && !album.isEmpty()) {
            return artist + " \u2022 " + album;
        }
        if (!artist.isEmpty()) {
            return artist;
        }
        if (!album.isEmpty()) {
            return album;
        }
        return "System media session";
    }

    private static String buildFooter(SystemMediaInfo mediaInfo, boolean includeSource) {
        String status = safeText(mediaInfo.getStatus(), "Idle");
        String source = includeSource ? stripExecutableSuffix(safeText(mediaInfo.getSourceApp(), "Unknown source")) : "";
        if (!source.isEmpty()) {
            return status + " \u2022 " + source;
        }
        return status;
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10L ? "0" : "") + seconds;
    }

    private static String safeText(String text, String fallback) {
        return text == null || text.trim().isEmpty() ? fallback : text.trim();
    }

    private static String trimToWidth(RavenFontRenderer uiFont, String text, int width) {
        if (text == null) {
            return "";
        }
        if (uiFont.getStringWidth(text) <= width) {
            return text;
        }

        String ellipsis = "...";
        int ellipsisWidth = uiFont.getStringWidth(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String next = builder.toString() + text.charAt(i);
            if (uiFont.getStringWidth(next) + ellipsisWidth > width) {
                break;
            }
            builder.append(text.charAt(i));
        }
        return builder.length() == 0 ? ellipsis : builder.append(ellipsis).toString();
    }

    private static String stripExecutableSuffix(String sourceApp) {
        if (sourceApp == null) {
            return "";
        }
        return sourceApp.endsWith(".exe") ? sourceApp.substring(0, sourceApp.length() - 4) : sourceApp;
    }

    private static String buildLyricsTrackKey(SystemMediaInfo mediaInfo) {
        if (mediaInfo == null) {
            return "";
        }
        return safeText(mediaInfo.getTitle(), "") + "|" + safeText(mediaInfo.getArtist(), "");
    }

    private static boolean shouldAnimateLyrics() {
        return SpotifyMiniPlayer.animateLyrics == null || SpotifyMiniPlayer.animateLyrics.isToggled();
    }

    private static long getLyricTransitionDurationMs() {
        return SpotifyMiniPlayer.lyricAnimationSpeed == null ? LYRIC_TRANSITION_MS : Math.max(60L, Math.round(SpotifyMiniPlayer.lyricAnimationSpeed.getInput()));
    }

    private static float getLyricTextScaleMultiplier() {
        return SpotifyMiniPlayer.lyricTextScale == null ? 1.0F : (float) Math.max(0.75D, Math.min(1.5D, SpotifyMiniPlayer.lyricTextScale.getInput()));
    }

    public static float[] getCurrentBounds() {
        if (!panelVisible) {
            return null;
        }
        return new float[] { panelX, panelY, panelWidth, panelHeight };
    }

    public static float[] getCurrentRect() {
        if (!panelVisible) {
            return null;
        }
        return new float[] { panelX, panelY, panelX + panelWidth, panelY + panelHeight };
    }

    private static String getAnimatedDots() {
        int phase = (int) ((animationTimeMs() / 1250L) % 4L);
        switch (phase) {
            case 0:
                return "";
            case 1:
                return ".";
            case 2:
                return ". .";
            default:
                return ". . .";
        }
    }

    private static float getLineAdvance(RavenFontRenderer uiFont, float uiScale, float compactRelief, boolean lowScaleLayout) {
        return uiFont.getFontHeight() + Math.max(lowScaleLayout ? 1.6F : 1.5F, (lowScaleLayout ? 2.2F : 2.1F) * uiScale) + compactRelief * (lowScaleLayout ? 1.0F : 1.2F);
    }

    private static String cachedUiFontKey = "";
    private static RavenFontRenderer cachedUiFont;

    private static RavenFontRenderer getUiFontRenderer(float textScale) {
        String fontName = HUD.getSelectedFontName();
        float baseScale = HUD.getSelectedFontScale();
        float effectiveScale = baseScale * Math.max(0.6F, textScale);
        String key = fontName + ":" + Math.round(effectiveScale * 1000.0F);
        if (cachedUiFont != null && key.equals(cachedUiFontKey)) {
            return cachedUiFont;
        }
        cachedUiFontKey = key;
        cachedUiFont = FontManager.getHudRenderer(fontName, effectiveScale);
        return cachedUiFont;
    }

    private static void drawMiniText(RavenFontRenderer font, String text, float x, float y, int color, boolean shadow) {
        font.drawString(text, x, y, color, shadow);
    }

    /** Monotonic clock for visual interpolation; immune to wall-clock jumps. */
    private static long animationTimeMs() {
        return System.nanoTime() / 1_000_000L;
    }

    private static final class LyricsTimeline {
        private final TimedLyrics timedLyrics;
        private final int activeIndex;
        private final String statusMessage;
        private final boolean seeked;
        private final long positionMs;

        private LyricsTimeline(TimedLyrics timedLyrics, int activeIndex, String statusMessage,
                               boolean seeked, long positionMs) {
            this.timedLyrics = timedLyrics;
            this.activeIndex = activeIndex;
            this.statusMessage = statusMessage == null ? "" : statusMessage;
            this.seeked = seeked;
            this.positionMs = Math.max(0L, positionMs);
        }

        private static LyricsTimeline status(String message) {
            return new LyricsTimeline(null, -1, message, true, 0L);
        }
    }

    private static final class WrappedLyric {
        private final List<String> lines;
        private final float top;
        private final float bottom;
        private final float height;

        private WrappedLyric(List<String> lines, float top, float bottom) {
            this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
            this.top = top;
            this.bottom = bottom;
            this.height = bottom - top;
        }
    }
}
