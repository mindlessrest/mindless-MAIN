package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.SpotifyMiniPlayer;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.media.SystemMediaClient;
import mindless.utility.media.SystemMediaInfo;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class DynamicIsland extends Module {
    private static final String[] ANCHORS = {"Top centre", "Top left", "Top right", "Custom"};
    private static final String LOGO_RESOURCE = "/assets/mindless/textures/gui/mindless_mark.png";
    private static final float LOGO_ASPECT = 32.0f / 22.0f;
    private static final float EDGE_MARGIN = 3.0f;
    private static final float PAD_X = 5.0f;
    private static final float BADGE_SIZE = 11.5f;
    private static final float BADGE_GAP = 3.5f;
    private static final float VALUE_GAP = 5.0f;
    private static final float HEIGHT = 17.5f;
    // Longer than it was: the pill resizing in a tenth of a second reads as a snap rather
    // than a move, and the content crossfade underneath it takes about as long.
    private static final float WIDTH_SMOOTH_TIME = 0.14f;
    /** How far new content starts below its resting place, before easing up into it. */
    private static final float CONTENT_RISE = 2.2f;
    private static final float CONTENT_RATE = 11.0f;
    /** Faster than the arrival: waiting on the old content is what feels like lag. */
    private static final float SWAP_RATE = 26.0f;
    private static final int STATE_IDLE = 0;
    private static final int STATE_NOTIFICATION = 1;
    private static final int STATE_BREAKER = 2;
    private static final int STATE_SCAFFOLD = 3;
    private static final int STATE_SPOTIFY = 4;
    private static final int STATE_HIDDEN = 5;
    private static final int MAX_TOGGLES = 8;
    private static final float EQUALIZER_WIDTH = 9.0f;
    /** Cover size at 100%, which is the pill's height less its padding. */
    private static final float SPOTIFY_ART_MAX = HEIGHT - 4.0f;
    /**
     * Height reserved along the bottom edge for the progress bar while a track is showing.
     *
     * The bar used to be positioned from the bottom edge while the title stayed centred on the
     * whole pill, which left them about a tenth of a pixel apart and read as one smeared block.
     * The title is centred in what is left above this strip instead, so the gap is whatever is
     * actually free rather than whatever happened to be left over.
     */
    private static final float SPOTIFY_BAR_ZONE = 5.2f;

    private final SliderSetting font;
    private final SliderSetting anchor;
    private final SliderSetting notificationDuration;
    private final GroupSetting contentGroup;
    private final ButtonSetting showNotifications;
    private final ButtonSetting showBedAura;
    private final ButtonSetting showScaffold;
    private final ButtonSetting showSpotify;
    private final ButtonSetting showIdle;
    private final GroupSetting spotifyGroup;
    private final SliderSetting spotifyTextScale;
    private final SliderSetting spotifyTextWidth;
    private final SliderSetting spotifyArtSize;
    private final SliderSetting spotifyArtRounding;
    private final ButtonSetting spotifyArtist;
    private final ButtonSetting spotifyProgressBar;
    private final SliderSetting spotifyProgressThickness;
    private final ButtonSetting spotifyEqualizer;
    private final GroupSetting styleGroup;
    private final ButtonSetting blurBackdrop;
    private final ButtonSetting dropShadow;
    private final SliderSetting opacity;
    private final SliderSetting scale;
    private final SliderSetting roundness;
    private final java.util.Map<Module, Boolean> toggleStates =
            new java.util.IdentityHashMap<Module, Boolean>();
    private final java.util.List<Toggle> recentToggles = new java.util.ArrayList<Toggle>();

    private ResourceLocation logoTexture;
    private boolean logoLoadAttempted;
    private float animatedWidth = -1.0f;
    private float widthVelocity;
    private float contentFade = 1.0f;
    private float contentSlide;
    private boolean swapping;
    private String pendingKey = "idle";
    private String pendingLabel = "Mindless";
    private String pendingValue = "";
    private int pendingState = STATE_IDLE;
    private ItemStack pendingIcon;
    private ResourceLocation pendingArtwork;
    private boolean pendingMediaPlaying;
    private float scaffoldProgress;
    private float breakerProgress;
    private int scaffoldPeak;
    private int islandState = STATE_IDLE;
    private long lastFrameNanos;
    private long lastPollAt;
    private String stateKey = "idle";
    private String stateLabel = "Mindless";
    private String stateValue = "";
    private ItemStack stateIcon;
    private ResourceLocation stateArtwork;
    private boolean mediaPlaying;
    private float spotifyProgress;
    private String stateSubtitle = "";
    private String pendingSubtitle = "";
    private int displayedBlockCount = Integer.MIN_VALUE;
    private int pendingBlockCount = Integer.MIN_VALUE;
    private float blockValueBlend = 1.0f;
    private boolean blockValueSwapping;

    public float islandPosX = -1.0f;
    public float islandPosY = -1.0f;

    public DynamicIsland() {
        super("Dynamic Island", "Shows notifications, combat, movement and media activity.", category.render);
        this.registerSetting(anchor = new SliderSetting("Anchor", 0, ANCHORS));
        this.registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));
        this.registerSetting(contentGroup = new GroupSetting("Content"));
        this.registerSetting(showNotifications = new ButtonSetting(contentGroup, "Notifications", true));
        this.registerSetting(showBedAura = new ButtonSetting(contentGroup, "Bed Aura", true));
        this.registerSetting(showScaffold = new ButtonSetting(contentGroup, "Scaffold", true));
        this.registerSetting(showSpotify = new ButtonSetting(contentGroup, "Spotify", true));
        this.registerSetting(showIdle = new ButtonSetting(contentGroup, "Idle logo", true));
        this.registerSetting(spotifyGroup = new GroupSetting("Spotify"));
        this.registerSetting(spotifyArtist = new ButtonSetting(spotifyGroup, "Show artist", false));
        // Under one: the title sat at the same size as a module toggle, which is far too loud for
        // something that is on screen for the length of a song rather than two seconds.
        this.registerSetting(spotifyTextScale = new SliderSetting(
                spotifyGroup, "Text size", "x", 0.92, 0.6, 1.2, 0.02));
        this.registerSetting(spotifyTextWidth = new SliderSetting(
                spotifyGroup, "Text width", "px", 92.0, 40.0, 190.0, 2.0));
        this.registerSetting(spotifyArtSize = new SliderSetting(
                spotifyGroup, "Art size", "%", 78.0, 45.0, 100.0, 2.0));
        this.registerSetting(spotifyArtRounding = new SliderSetting(
                spotifyGroup, "Art rounding", "%", 32.0, 0.0, 100.0, 2.0));
        this.registerSetting(spotifyProgressBar = new ButtonSetting(spotifyGroup, "Progress bar", true));
        this.registerSetting(spotifyProgressThickness = new SliderSetting(
                spotifyGroup, "Progress thickness", "px", 1.65, 1.0, 4.0, 0.05));
        this.registerSetting(spotifyEqualizer = new ButtonSetting(spotifyGroup, "Equalizer", true));
        notificationDuration = new SliderSetting(
                "Notification time", 2.5, 0.5, 8.0, 0.5,
                "Toggle time", "Content.Toggle time");
        notificationDuration.setSuffix("s");
        this.registerSetting(notificationDuration);
        this.registerSetting(styleGroup = new GroupSetting("Style"));
        this.registerSetting(blurBackdrop = new ButtonSetting(styleGroup, "Blur backdrop", true));
        this.registerSetting(dropShadow = new ButtonSetting(styleGroup, "Drop shadow", true));
        this.registerSetting(opacity = new SliderSetting(
                styleGroup, "Opacity", "%", 88.0, 20.0, 100.0, 1.0));
        this.registerSetting(scale = new SliderSetting(styleGroup, "Scale", 1.0, 0.7, 1.6, 0.05));
        // 100% is the full capsule it used to be. The default sits just under that: at this
        // height a true pill reads as stubby, and shaving the corners back turns it into a
        // squircle that holds its shape as the width animates.
        this.registerSetting(roundness = new SliderSetting(
                styleGroup, "Roundness", "%", 86.0, 40.0, 100.0, 1.0));
    }

    @Override
    public void guiUpdate() {
        anchor.setVisible(true, this);
        notificationDuration.setVisible(showNotifications.isToggled(), this);
        contentGroup.setVisible(true, this);
        boolean spotify = showSpotify.isToggled();
        spotifyGroup.setVisible(spotify, this);
        spotifyArtist.setVisible(spotify, this);
        spotifyTextScale.setVisible(spotify, this);
        spotifyTextWidth.setVisible(spotify, this);
        spotifyArtSize.setVisible(spotify, this);
        spotifyArtRounding.setVisible(spotify, this);
        spotifyProgressBar.setVisible(spotify, this);
        spotifyProgressThickness.setVisible(spotify && spotifyProgressBar.isToggled(), this);
        spotifyEqualizer.setVisible(spotify, this);
        styleGroup.setVisible(true, this);
    }

    @Override
    public void onEnable() {
        animatedWidth = -1.0f;
        widthVelocity = 0.0f;
        contentFade = 1.0f;
        contentSlide = 0.0f;
        swapping = false;
        pendingKey = "idle";
        pendingLabel = "Mindless";
        pendingValue = "";
        pendingState = STATE_IDLE;
        pendingIcon = null;
        pendingArtwork = null;
        pendingMediaPlaying = false;
        scaffoldProgress = 0.0f;
        breakerProgress = 0.0f;
        scaffoldPeak = 0;
        islandState = STATE_IDLE;
        lastFrameNanos = 0L;
        lastPollAt = 0L;
        stateKey = "idle";
        stateLabel = "Mindless";
        stateValue = "";
        stateIcon = null;
        stateArtwork = null;
        mediaPlaying = false;
        spotifyProgress = 0.0f;
        stateSubtitle = "";
        pendingSubtitle = "";
        resetBlockValueTransition();
        toggleStates.clear();
        recentToggles.clear();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        renderIsland();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        pollToggles(System.currentTimeMillis());
    }

    private void renderIsland() {
        MindlessFontRenderer text = islandFont();
        if (text == null) return;
        float delta = frameDelta();
        resolveState(delta);
        if (islandState == STATE_HIDDEN) return;
        float uiScale = (float) scale.getInput();
        float targetWidth = stateWidth(text) * uiScale;
        float height = HEIGHT * uiScale;
        if (animatedWidth < 0.0f) {
            animatedWidth = targetWidth;
            widthVelocity = 0.0f;
        }
        animatedWidth = spring(animatedWidth, targetWidth, WIDTH_SMOOTH_TIME, delta);
        ScaledResolution resolution = ScaledResolutionCache.get();
        float x = anchoredX(resolution, animatedWidth);
        float y = anchoredY(resolution, height);
        int alpha = Math.round(255.0f * (float) (opacity.getInput() / 100.0));
        float radius = height * 0.5f * (float) (roundness.getInput() / 100.0);
        drawBackdrop(x, y, animatedWidth, height, radius, alpha);
        if (swapping) {
            // The old content leaves before the new arrives. Cutting straight to invisible
            // and fading back up is what read as a flash: for a frame the island was an
            // empty pill with nothing in it.
            contentFade = approach(contentFade, 0.0f, SWAP_RATE, delta);
            contentSlide = approach(contentSlide, -CONTENT_RISE, SWAP_RATE, delta);
            if (contentFade <= 0.03f) {
                stateKey = pendingKey;
                stateLabel = pendingLabel;
                stateValue = pendingValue;
                islandState = pendingState;
                stateIcon = pendingIcon;
                stateArtwork = pendingArtwork;
                stateSubtitle = pendingSubtitle;
                mediaPlaying = pendingMediaPlaying;
                swapping = false;
                contentFade = 0.0f;
                contentSlide = CONTENT_RISE;
            }
        }
        else {
            contentFade = approach(contentFade, 1.0f, CONTENT_RATE, delta);
            contentSlide = approach(contentSlide, 0.0f, CONTENT_RATE, delta);
        }
        drawContent(text, x, y, animatedWidth, height, uiScale, alpha,
                contentFade, contentSlide * uiScale);
    }

    private void resolveState(float delta) {
        updateBlockValueTransition(delta);
        int nextState = STATE_IDLE;
        String nextLabel = "Mindless";
        String nextValue = "";
        String nextKey = "idle";
        ItemStack nextIcon = null;
        ResourceLocation nextArtwork = null;
        String nextSubtitle = "";
        boolean nextMediaPlaying = false;
        long now = System.currentTimeMillis();
        ItemStack islandBlock = ModuleManager.blockCounter == null
                ? null : ModuleManager.blockCounter.islandBlock();
        Notifications.IslandNotification notification = showNotifications.isToggled()
                ? Notifications.latestForIsland(now) : null;
        Toggle toggle = showNotifications.isToggled() ? latestToggle(now) : null;
        SystemMediaInfo mediaInfo = currentSpotifyInfo();
        if (notification != null) {
            nextState = STATE_NOTIFICATION;
            nextLabel = notification.title;
            nextValue = notification.status;
            nextKey = "notification:" + notification.bornAt;
        } else if (toggle != null) {
            nextState = STATE_NOTIFICATION;
            nextLabel = toggle.name;
            nextValue = toggle.enabled ? "ON" : "OFF";
            nextKey = "notification:" + toggle.name + ':' + toggle.enabled + ':' + toggle.bornAt;
        } else if (showBedAura.isToggled()
                && ModuleManager.bedAura != null && ModuleManager.bedAura.isBreakingRoute()) {
            nextState = STATE_BREAKER;
            nextLabel = ModuleManager.bedAura.getAuraToolName();
            float target = Math.max(0.0f, Math.min(1.0f,
                    ModuleManager.bedAura.getAuraTotalProgress()));
            breakerProgress = approach(breakerProgress, target, 12.0f, delta);
            nextValue = Math.round(breakerProgress * 100.0f) + "%";
            nextIcon = ModuleManager.bedAura.getAuraToolStack();
            nextKey = "breaker:" + nextLabel;
        } else if (showScaffold.isToggled() && (islandBlock != null
                || ModuleManager.scaffold != null && ModuleManager.scaffold.isActivelyScaffolding())) {
            nextState = STATE_SCAFFOLD;
            nextLabel = "Blocks";
            int blocks = ModuleManager.blockCounter == null
                    ? scaffoldBlockCount() : ModuleManager.blockCounter.islandCount();
            if (displayedBlockCount == Integer.MIN_VALUE) {
                displayedBlockCount = blocks;
                pendingBlockCount = blocks;
            } else if (blocks != (blockValueSwapping ? pendingBlockCount : displayedBlockCount)) {
                pendingBlockCount = blocks;
                if (!blockValueSwapping) {
                    blockValueBlend = 0.0f;
                    blockValueSwapping = true;
                }
            }
            nextValue = Integer.toString(displayedBlockCount);
            nextIcon = islandBlock;
            nextKey = "scaffold:" + blockKey(islandBlock);
            if (scaffoldPeak == 0) {
                scaffoldPeak = Math.max(1, blocks);
                scaffoldProgress = blocks / (float) scaffoldPeak;
            } else {
                scaffoldPeak = Math.max(scaffoldPeak, blocks);
                float target = blocks / (float) Math.max(1, scaffoldPeak);
                scaffoldProgress = approach(scaffoldProgress, target, 9.0f, delta);
            }
        } else if (mediaInfo != null) {
            nextState = STATE_SPOTIFY;
            nextLabel = mediaInfo.getTitle();
            nextSubtitle = mediaInfo.getArtist() == null ? "" : mediaInfo.getArtist().trim();
            nextKey = "spotify:" + mediaInfo.getTitle() + ':' + mediaInfo.getArtist();
            nextArtwork = SystemMediaClient.getInstance().getAlbumArtTextureLocation();
            nextMediaPlaying = mediaInfo.isPlaying();
            spotifyProgress = mediaInfo.getDurationMs() <= 0L ? 0.0f
                    : Math.max(0.0f, Math.min(1.0f,
                    mediaInfo.getLivePositionMs() / (float) mediaInfo.getDurationMs()));
        } else if (!showIdle.isToggled()) {
            nextState = STATE_HIDDEN;
            nextLabel = "";
            nextKey = "hidden";
        } else {
            breakerProgress = 0.0f;
            scaffoldPeak = 0;
            scaffoldProgress = 0.0f;
            resetBlockValueTransition();
        }
        // Compared against whatever is already on its way in, so a second toggle during a
        // swap replaces the queued content instead of starting the animation over.
        String showing = swapping ? pendingKey : stateKey;
        if (nextState == STATE_HIDDEN || islandState == STATE_HIDDEN) {
            stateKey = nextKey;
            stateLabel = nextLabel;
            stateValue = nextValue;
            islandState = nextState;
            stateIcon = nextIcon;
            stateArtwork = nextArtwork;
            stateSubtitle = nextSubtitle;
            mediaPlaying = nextMediaPlaying;
            pendingKey = nextKey;
            pendingLabel = nextLabel;
            pendingValue = nextValue;
            pendingState = nextState;
            pendingIcon = nextIcon;
            pendingArtwork = nextArtwork;
            pendingSubtitle = nextSubtitle;
            pendingMediaPlaying = nextMediaPlaying;
            swapping = false;
            contentFade = 1.0f;
            contentSlide = 0.0f;
            animatedWidth = -1.0f;
            return;
        }
        if (!nextKey.equals(showing)) {
            pendingKey = nextKey;
            pendingLabel = nextLabel;
            pendingValue = nextValue;
            pendingState = nextState;
            pendingIcon = nextIcon;
            pendingArtwork = nextArtwork;
            pendingSubtitle = nextSubtitle;
            pendingMediaPlaying = nextMediaPlaying;
            swapping = true;
            return;
        }
        if (!swapping) {
            // Same panel, live numbers. The block count and the break percentage move in
            // place with no transition, because nothing about the panel has changed.
            islandState = nextState;
            stateLabel = nextLabel;
            stateValue = nextValue;
            stateIcon = nextIcon;
            stateArtwork = nextArtwork;
            stateSubtitle = nextSubtitle;
            mediaPlaying = nextMediaPlaying;
        }
    }

    private void drawBackdrop(float x, float y, float width, float height, float radius, int alpha) {
        // Depth comes from the shadow and the gradient, never from a drawn edge. A soft
        // wide falloff lifts the panel off whatever is behind it; a line just traces it.
        if (dropShadow.isToggled()) {
            RoundedUtils.drawRoundShadow(x, y + 2.0f, width, height, radius,
                    7.0f, withAlpha(0x000000, Math.round(alpha * 0.14f)));
        }
        if (blurBackdrop.isToggled()) {
            BlurUtils.prepareBlur(x, y, width, height);
            RoundedUtils.drawRound(x, y, width, height, radius, 0xFF000000);
            BlurUtils.blurEndRegion(2, 1.8f, alpha / 255.0f * 0.72f, x, y, width, height);
        }
        // A wider tonal range than before, so the top reads as lit and the bottom as
        // shadowed on their own. That is the whole shape now: no outline, no sheen line,
        // nothing with a hard edge anywhere on it.
        RoundedUtils.drawGradientVertical(x, y, width, height, radius,
                new java.awt.Color(46, 45, 55, alpha),
                new java.awt.Color(22, 21, 27, alpha));
    }

    private void drawContent(MindlessFontRenderer text, float x, float y, float width,
                             float height, float uiScale, int alpha, float fade,
                             float slide) {
        // The mark is the one thing that never changes, so it keeps full opacity and holds
        // still. Fading it with the rest made it blink on every module toggle.
        int contentAlpha = Math.round(alpha * fade);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        boolean cover = islandState == STATE_SPOTIFY && stateArtwork != null;
        float badge = (cover ? spotifyArt() : BADGE_SIZE) * uiScale;
        float badgeX = x + PAD_X * uiScale;
        float badgeY = y + (height - badge) * 0.5f;
        int accent = ThemeManager.getWatermarkColor(0.0) & 0xFFFFFF;
        float markHeight = 7.8f * uiScale;
        float markWidth = markHeight * LOGO_ASPECT;
        if (cover) {
            drawRoundedTexture(stateArtwork, badgeX, badgeY, badge,
                    badge * 0.5f * (float) (spotifyArtRounding.getInput() / 100.0),
                    contentAlpha / 255.0f);
        } else if ((islandState == STATE_BREAKER || islandState == STATE_SCAFFOLD) && stateIcon != null) {
            drawItemIcon(stateIcon, badgeX, badgeY, badge, contentAlpha);
        } else {
            drawLogo(badgeX + (badge - markWidth) * 0.5f,
                    badgeY + (badge - markHeight) * 0.5f,
                    markWidth, markHeight, withAlpha(0xDCD5F3, alpha));
        }
        float labelX = badgeX + badge + BADGE_GAP * uiScale;
        float textY = y + (height - text.getFontHeight() * uiScale) * 0.5f + slide;
        if (islandState == STATE_BREAKER || islandState == STATE_SCAFFOLD) {
            textY -= 1.15f * uiScale;
        }
        String visibleLabel = stateLabel;
        float labelScale = uiScale;
        if (islandState == STATE_SPOTIFY) {
            labelScale = uiScale * spotifyTextScale();
            visibleLabel = fitText(text, spotifyLabel(), spotifyTextLimit());
            float barZone = spotifyBarZone() * uiScale;
            textY = y + (height - barZone - text.getFontHeight() * labelScale) * 0.5f
                    + 1.1f * uiScale + slide;
        }
        drawScaled(text, visibleLabel, labelX, textY, labelScale,
                withAlpha(0xF1F1F5, contentAlpha));
        if (!stateValue.isEmpty()) {
            // On takes the theme colour, off goes quiet. The state is then readable from
            // the corner of the eye without reading the word.
            int valueRgb;
            if (islandState == STATE_NOTIFICATION) {
                valueRgb = "OFF".equals(stateValue) ? 0x8E8D96 : accent;
            }
            else {
                valueRgb = 0xF1F1F5;
            }
            float valueWidth = text.getStringWidth(stateValue) * uiScale;
            float valueX = x + width - PAD_X * uiScale - valueWidth;
            if (islandState == STATE_SCAFFOLD && blockValueSwapping) {
                float blend = smoothStep(blockValueBlend);
                String incoming = Integer.toString(pendingBlockCount);
                float incomingWidth = text.getStringWidth(incoming) * uiScale;
                float incomingX = x + width - PAD_X * uiScale - incomingWidth;
                float right = x + width - PAD_X * uiScale + 1.0f;
                float left = right - Math.max(valueWidth, incomingWidth) - 2.0f;
                float boundary = left + (right - left) * blend;
                float clipY = textY - 1.0f;
                float clipHeight = text.getFontHeight() * uiScale + 2.0f;
                if (boundary < right - 0.01f) {
                    RenderUtils.scissorPushGui(boundary, clipY, right - boundary, clipHeight);
                    drawScaled(text, stateValue, valueX, textY, uiScale,
                            withAlpha(valueRgb, contentAlpha));
                    RenderUtils.scissorPop();
                }
                if (boundary > left + 0.01f) {
                    RenderUtils.scissorPushGui(left, clipY, boundary - left, clipHeight);
                    drawScaled(text, incoming, incomingX, textY, uiScale,
                            withAlpha(valueRgb, contentAlpha));
                    RenderUtils.scissorPop();
                }
            } else {
                drawScaled(text, stateValue, valueX, textY, uiScale,
                        withAlpha(valueRgb, contentAlpha));
            }
        }
        boolean spotifyBar = islandState == STATE_SPOTIFY && spotifyProgressBar.isToggled();
        if (islandState == STATE_BREAKER || islandState == STATE_SCAFFOLD || spotifyBar) {
            float barX = labelX;
            float barHeight = Math.max(1.0f, (islandState == STATE_SPOTIFY
                    ? (float) spotifyProgressThickness.getInput() : 1.65f) * uiScale);
            // Centred in the strip reserved for it rather than measured off the bottom edge, so
            // thickening the bar eats into the padding on both sides instead of only the top.
            float barY = islandState == STATE_SPOTIFY
                    ? y + height - (spotifyBarZone() * uiScale + barHeight) * 0.5f + slide
                    : y + height - 4.1f * uiScale + slide;
            float barWidth = Math.max(10.0f * uiScale,
                    width - (labelX - x) - PAD_X * uiScale
                            - (islandState == STATE_SPOTIFY && spotifyEqualizer.isToggled()
                            ? (EQUALIZER_WIDTH + VALUE_GAP) * uiScale : 0.0f));
            RoundedUtils.drawRound(barX, barY, barWidth, barHeight, barHeight * 0.5f,
                    withAlpha(0xFFFFFF, Math.min(contentAlpha, 36)));
            float progress = islandState == STATE_BREAKER ? breakerProgress
                    : islandState == STATE_SCAFFOLD ? scaffoldProgress : spotifyProgress;
            float fill = barWidth * Math.max(0.0f, Math.min(1.0f, progress));
            if (fill > 0.5f) {
                RoundedUtils.drawRound(barX, barY, fill, barHeight, barHeight * 0.5f,
                        withAlpha(0xF1F1F5, contentAlpha));
            }
        }
        if (islandState == STATE_SPOTIFY && spotifyEqualizer.isToggled()) {
            // Centred on the pill, not on the text block. The bar stops short of it horizontally,
            // so there is nothing under here for it to collide with, and a widget's meter reads as
            // part of the pill rather than as something stuck to the title.
            drawEqualizer(x + width - (PAD_X + EQUALIZER_WIDTH) * uiScale,
                    y + height * 0.5f + slide, uiScale, contentAlpha, accent, mediaPlaying);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float stateWidth(MindlessFontRenderer text) {
        float width = PAD_X * 2.0f + BADGE_SIZE + BADGE_GAP + text.getStringWidth(stateLabel);
        if (islandState == STATE_SPOTIFY) {
            width = PAD_X * 2.0f + (stateArtwork != null ? spotifyArt() : BADGE_SIZE) + BADGE_GAP
                    + Math.min(spotifyTextLimit(), text.getStringWidth(spotifyLabel())) * spotifyTextScale()
                    + (spotifyEqualizer.isToggled() ? VALUE_GAP + EQUALIZER_WIDTH : 0.0f);
        }
        if (!stateValue.isEmpty()) {
            float valueWidth = text.getStringWidth(stateValue);
            if (islandState == STATE_SCAFFOLD) {
                valueWidth = Math.max(valueWidth, text.getStringWidth("888"));
            }
            width += VALUE_GAP + valueWidth;
        }
        return Math.max(42.0f, width);
    }

    private float spotifyTextScale() {
        return (float) spotifyTextScale.getInput();
    }

    /** Title width in font units, so the setting stays a screen measurement at any text size. */
    private float spotifyTextLimit() {
        return (float) (spotifyTextWidth.getInput() / Math.max(0.01, spotifyTextScale.getInput()));
    }

    private float spotifyArt() {
        return SPOTIFY_ART_MAX * (float) (spotifyArtSize.getInput() / 100.0);
    }

    private float spotifyBarZone() {
        return spotifyProgressBar.isToggled() ? SPOTIFY_BAR_ZONE : 0.0f;
    }

    private String spotifyLabel() {
        if (!spotifyArtist.isToggled() || stateSubtitle == null || stateSubtitle.isEmpty()) {
            return stateLabel;
        }
        return stateLabel + " \u00b7 " + stateSubtitle;
    }

    /**
     * The cover, with the pill's own corner treatment.
     *
     * Album art arrives as a dynamic texture, which Minecraft uploads with nearest filtering; at
     * roughly a seventh of its native size that turns a cover into aliased confetti, so the two
     * filters are swapped for the draw and put back. The shader is what rounds it -- a scissor
     * cannot cut a corner, and a stencil pass for one small square costs more than it saves.
     */
    private void drawRoundedTexture(ResourceLocation texture, float x, float y, float size,
                                    float radius, float alpha) {
        if (texture == null || alpha <= 0.0f) return;
        if (radius < 0.35f) {
            drawTexture(texture, x, y, size, size, withAlpha(0xFFFFFF, Math.round(alpha * 255.0f)));
            return;
        }
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        mc.getTextureManager().bindTexture(texture);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
        RoundedUtils.drawRoundTextured(x, y, size, size, radius, alpha);
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private SystemMediaInfo currentSpotifyInfo() {
        if (!showSpotify.isToggled() || ModuleManager.spotifyMiniPlayer == null
                || !ModuleManager.spotifyMiniPlayer.isEnabled()) return null;
        SystemMediaInfo info = SystemMediaClient.getInstance().getCurrentInfo();
        if (info == null || !info.isAvailable() || info.getTitle().trim().isEmpty()) return null;
        if (SpotifyMiniPlayer.hideWhenPaused != null
                && SpotifyMiniPlayer.hideWhenPaused.isToggled() && info.isPaused()) return null;
        return info;
    }

    public boolean handlesNotifications() {
        return isEnabled() && showNotifications.isToggled();
    }

    public boolean handlesSpotify() {
        return isEnabled() && showSpotify.isToggled();
    }

    private String fitText(MindlessFontRenderer text, String value, float maxWidth) {
        if (text.getStringWidth(value) <= maxWidth) return value;
        String suffix = "...";
        float available = maxWidth - text.getStringWidth(suffix);
        int length = value.length();
        while (length > 0 && text.getStringWidth(value.substring(0, length)) > available) length--;
        return value.substring(0, length) + suffix;
    }

    private void drawEqualizer(float x, float centerY, float uiScale, int alpha,
                               int accent, boolean playing) {
        float barWidth = Math.max(1.0f, 1.25f * uiScale);
        float gap = 1.25f * uiScale;
        double time = System.currentTimeMillis() / 155.0;
        for (int i = 0; i < 3; i++) {
            float height = playing
                    ? (2.5f + 3.0f * (float) Math.abs(Math.sin(time + i * 1.35))) * uiScale
                    : 2.0f * uiScale;
            float barX = x + i * (barWidth + gap);
            RoundedUtils.drawRound(barX, centerY - height * 0.5f, barWidth, height,
                    barWidth * 0.5f, withAlpha(accent, alpha));
        }
    }

    private int scaffoldBlockCount() {
        if (!Utils.nullCheck()) return 0;
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[i];
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private void updateBlockValueTransition(float delta) {
        if (blockValueSwapping) {
            blockValueBlend = approach(blockValueBlend, 1.0f, 28.0f, delta);
            if (blockValueBlend >= 0.985f) {
                displayedBlockCount = pendingBlockCount;
                blockValueSwapping = false;
                blockValueBlend = 1.0f;
            }
        }
    }

    private void resetBlockValueTransition() {
        displayedBlockCount = Integer.MIN_VALUE;
        pendingBlockCount = Integer.MIN_VALUE;
        blockValueBlend = 1.0f;
        blockValueSwapping = false;
    }

    private static float smoothStep(float value) {
        float clamped = Math.max(0.0f, Math.min(1.0f, value));
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    private static String blockKey(ItemStack stack) {
        if (stack == null || stack.getItem() == null) return "none";
        return net.minecraft.item.Item.getIdFromItem(stack.getItem()) + ":" + stack.getMetadata();
    }

    private void drawItemIcon(ItemStack stack, float x, float y, float size, int alpha) {
        if (stack == null || alpha <= 0) return;
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        float iconScale = size / 16.0f;
        float oldZ = mc.getRenderItem().zLevel;
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(iconScale, iconScale, 1.0f);
        GlStateManager.enableDepth();
        net.minecraft.client.renderer.RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha / 255.0f);
        mc.getRenderItem().zLevel = 0.0f;
        mc.getRenderItem().renderItemAndEffectIntoGUI(stack, 0, 0);
        mc.getRenderItem().zLevel = oldZ;
        net.minecraft.client.renderer.RenderHelper.disableStandardItemLighting();
        GlStateManager.disableDepth();
        GlStateManager.popMatrix();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void pollToggles(long now) {
        long life = Math.max(1L, (long) (notificationDuration.getInput() * 1000.0));
        long elapsed = lastPollAt == 0L ? 0L : Math.max(0L, now - lastPollAt);
        lastPollAt = now;
        if (elapsed > 0L && (mc.currentScreen != null || mc.gameSettings.showDebugInfo)) {
            for (int i = 0; i < recentToggles.size(); i++) recentToggles.get(i).bornAt += elapsed;
        }
        java.util.List<Module> modules = ModuleManager.modules;
        if (modules != null) {
            synchronized (modules) {
                for (int i = 0; i < modules.size(); i++) trackToggle(modules.get(i), now);
            }
        }
        if (mindless.Mindless.scriptManager != null) {
            for (Module module : mindless.Mindless.scriptManager.scripts.values()) {
                trackToggle(module, now);
            }
        }
        for (int i = recentToggles.size() - 1; i >= 0; i--) {
            if (now - recentToggles.get(i).bornAt >= life) recentToggles.remove(i);
        }
    }

    private void trackToggle(Module module, long now) {
        if (module == null || module.isHidden()) return;
        boolean enabled = module.isEnabled();
        Boolean previous = toggleStates.put(module, enabled);
        if (previous == null || previous.booleanValue() == enabled) return;
        String name = module.getNameInHud();
        if (name == null || name.isEmpty()) return;
        for (int i = 0; i < recentToggles.size(); i++) {
            Toggle existing = recentToggles.get(i);
            if (existing.name.equals(name)) {
                existing.enabled = enabled;
                existing.bornAt = now;
                recentToggles.remove(i);
                recentToggles.add(existing);
                return;
            }
        }
        while (recentToggles.size() >= MAX_TOGGLES) recentToggles.remove(0);
        recentToggles.add(new Toggle(name, enabled, now));
    }

    private Toggle latestToggle(long now) {
        long life = Math.max(1L, (long) (notificationDuration.getInput() * 1000.0));
        for (int i = recentToggles.size() - 1; i >= 0; i--) {
            Toggle toggle = recentToggles.get(i);
            if (now - toggle.bornAt < life) return toggle;
        }
        return null;
    }

    private void drawLogo(float x, float y, float width, float height, int colour) {
        ResourceLocation texture = logoTexture();
        if (texture == null) return;
        drawTexture(texture, x, y, width, height, colour);
    }

    private void drawTexture(ResourceLocation texture, float x, float y, float width,
                             float height, int colour) {
        if (texture == null || ((colour >>> 24) & 0xFF) <= 0) return;
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.color(((colour >> 16) & 0xFF) / 255.0f,
                ((colour >> 8) & 0xFF) / 255.0f,
                (colour & 0xFF) / 255.0f,
                ((colour >>> 24) & 0xFF) / 255.0f);
        mc.getTextureManager().bindTexture(texture);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(0.0f, 1.0f);
        GL11.glVertex2f(x, y + height);
        GL11.glTexCoord2f(1.0f, 1.0f);
        GL11.glVertex2f(x + width, y + height);
        GL11.glTexCoord2f(1.0f, 0.0f);
        GL11.glVertex2f(x + width, y);
        GL11.glEnd();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private ResourceLocation logoTexture() {
        if (logoTexture == null && !logoLoadAttempted) {
            logoLoadAttempted = true;
            try (java.io.InputStream stream = DynamicIsland.class.getResourceAsStream(LOGO_RESOURCE)) {
                if (stream != null) {
                    java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(stream);
                    if (image != null) {
                        DynamicTexture dynamic = new DynamicTexture(image);
                        dynamic.setBlurMipmap(true, false);
                        logoTexture = mc.getTextureManager()
                                .getDynamicTextureLocation("mindless_island_mark", dynamic);
                    }
                }
            } catch (Exception ignored) {
                logoTexture = null;
            }
        }
        return logoTexture;
    }

    private void drawScaled(MindlessFontRenderer text, String value, float x, float y,
                            float uiScale, int colour) {
        if (uiScale == 1.0f) {
            text.drawString(value, x, y, colour, false);
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(uiScale, uiScale, 1.0f);
        text.drawString(value, 0.0f, 0.0f, colour, false);
        GlStateManager.popMatrix();
    }

    private MindlessFontRenderer islandFont() {
        return FontManager.getHudRenderer(ModuleFont.nameOf(font), HUD.getSelectedFontScale());
    }

    public float[] getIslandBounds() {
        MindlessFontRenderer text = islandFont();
        if (text == null) return null;
        float uiScale = (float) scale.getInput();
        float width = stateWidth(text) * uiScale;
        float height = HEIGHT * uiScale;
        ScaledResolution resolution = ScaledResolutionCache.get();
        float x = anchoredX(resolution, width);
        float y = anchoredY(resolution, height);
        return new float[]{x, y, x + width, y + height};
    }

    public void resetPosition() {
        islandPosX = -1.0f;
        islandPosY = -1.0f;
    }

    public void moveIslandTo(float left, float top) {
        if ((int) anchor.getInput() != 3) anchor.setValueWithEvent(3.0);
        islandPosX = left;
        islandPosY = top;
    }

    public boolean isCustomAnchored() {
        return (int) anchor.getInput() == 3;
    }

    private float anchoredX(ScaledResolution resolution, float width) {
        switch ((int) anchor.getInput()) {
            case 1:
                return EDGE_MARGIN;
            case 2:
                return resolution.getScaledWidth() - width - EDGE_MARGIN;
            case 3:
                if (islandPosX < 0.0f) islandPosX = (resolution.getScaledWidth() - width) * 0.5f;
                return Math.max(0.0f, Math.min(resolution.getScaledWidth() - width, islandPosX));
            default:
                return (resolution.getScaledWidth() - width) * 0.5f;
        }
    }

    private float anchoredY(ScaledResolution resolution, float height) {
        if ((int) anchor.getInput() == 3) {
            if (islandPosY < 0.0f) islandPosY = EDGE_MARGIN;
            return Math.max(0.0f, Math.min(resolution.getScaledHeight() - height, islandPosY));
        }
        return EDGE_MARGIN;
    }

    private float frameDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0f / 60.0f;
        }
        float delta = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        return Math.max(0.0f, Math.min(0.1f, delta));
    }

    private float spring(float current, float target, float smoothTime, float delta) {
        if (delta <= 0.0f) return current;
        float omega = 2.0f / Math.max(0.0001f, smoothTime);
        float scaled = omega * delta;
        float decay = 1.0f / (1.0f + scaled + 0.48f * scaled * scaled
                + 0.235f * scaled * scaled * scaled);
        float change = current - target;
        float temp = (widthVelocity + omega * change) * delta;
        widthVelocity = (widthVelocity - omega * temp) * decay;
        float result = target + (change + temp) * decay;
        if (Math.abs(target - result) < 0.05f) {
            widthVelocity = 0.0f;
            return target;
        }
        return result;
    }

    private static float approach(float current, float target, float rate, float delta) {
        float factor = 1.0f - (float) Math.exp(-delta * rate);
        return current + (target - current) * factor;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static final class Toggle {
        private final String name;
        private boolean enabled;
        private long bornAt;

        private Toggle(String name, boolean enabled, long bornAt) {
            this.name = name;
            this.enabled = enabled;
            this.bornAt = bornAt;
        }
    }
}
