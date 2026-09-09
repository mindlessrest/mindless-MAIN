package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.HudGlowHelper;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

public class DynamicIsland extends Module {
    private static final String[] MODES = {"Island", "Text"};
    private static final String[] ANCHORS = {"Top centre", "Top left", "Top right", "Custom"};
    private static final String LOGO_RESOURCE = "/assets/mindless/textures/gui/mindless_mark.png";
    private static final float LOGO_ASPECT = 32.0f / 22.0f;
    private static final float DEFAULT_TEXT_X = 5.0f;
    private static final float DEFAULT_TEXT_Y = 5.0f;
    private static final float WATERMARK_SCALE = 3.0f;
    private static final float EDGE_MARGIN = 4.0f;
    private static final float PAD_X = 6.0f;
    private static final float BADGE_SIZE = 12.0f;
    private static final float BADGE_GAP = 5.0f;
    private static final float VALUE_GAP = 10.0f;
    private static final float HEIGHT = 21.0f;
    private static final float WIDTH_SMOOTH_TIME = 0.105f;
    private static final int STATE_IDLE = 0;
    private static final int STATE_NOTIFICATION = 1;
    private static final int STATE_SCAFFOLD = 2;
    private static final int MAX_TOGGLES = 8;

    private final SliderSetting mode;
    private final SliderSetting font;
    private final SliderSetting anchor;
    private final SliderSetting notificationDuration;
    private final GroupSetting styleGroup;
    private final ButtonSetting blurBackdrop;
    private final ButtonSetting dropShadow;
    private final SliderSetting opacity;
    private final SliderSetting scale;
    private final java.util.Map<Module, Boolean> toggleStates =
            new java.util.IdentityHashMap<Module, Boolean>();
    private final java.util.List<Toggle> recentToggles = new java.util.ArrayList<Toggle>();

    private ResourceLocation logoTexture;
    private boolean logoLoadAttempted;
    private float animatedWidth = -1.0f;
    private float widthVelocity;
    private float contentFade = 1.0f;
    private float scaffoldProgress;
    private int scaffoldPeak;
    private int islandState = STATE_IDLE;
    private long lastFrameNanos;
    private long lastPollAt;
    private String stateKey = "idle";
    private String stateLabel = "Mindless";
    private String stateValue = "";

    public float textPosX = DEFAULT_TEXT_X;
    public float textPosY = DEFAULT_TEXT_Y;
    public float islandPosX = -1.0f;
    public float islandPosY = -1.0f;

    public DynamicIsland() {
        super("Dynamic Island", "Shows Mindless, notifications and Scaffold blocks.", category.render);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(anchor = new SliderSetting("Anchor", 0, ANCHORS));
        this.registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));
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
    }

    @Override
    public void guiUpdate() {
        boolean island = isIslandMode();
        anchor.setVisible(island, this);
        notificationDuration.setVisible(island, this);
        styleGroup.setVisible(island, this);
    }

    @Override
    public void onEnable() {
        animatedWidth = -1.0f;
        widthVelocity = 0.0f;
        contentFade = 1.0f;
        scaffoldProgress = 0.0f;
        scaffoldPeak = 0;
        islandState = STATE_IDLE;
        lastFrameNanos = 0L;
        lastPollAt = 0L;
        stateKey = "idle";
        stateLabel = "Mindless";
        stateValue = "";
        toggleStates.clear();
        recentToggles.clear();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        if (isIslandMode()) renderIsland();
        else renderTextWatermark();
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
        drawBackdrop(x, y, animatedWidth, height, height * 0.5f, alpha);
        contentFade = approach(contentFade, 1.0f, 14.0f, delta);
        drawContent(text, x, y, animatedWidth, height, uiScale,
                Math.round(alpha * contentFade));
    }

    private void resolveState(float delta) {
        int nextState = STATE_IDLE;
        String nextLabel = "Mindless";
        String nextValue = "";
        String nextKey = "idle";
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            nextState = STATE_SCAFFOLD;
            nextLabel = "Blocks";
            BlockCounter counter = ModuleManager.blockCounter;
            int blocks = counter == null ? 0 : Math.max(0, counter.islandCount());
            nextValue = Integer.toString(blocks);
            nextKey = "scaffold";
            if (scaffoldPeak == 0) {
                scaffoldPeak = Math.max(1, blocks);
                scaffoldProgress = blocks / (float) scaffoldPeak;
            } else {
                scaffoldPeak = Math.max(scaffoldPeak, blocks);
                float target = blocks / (float) Math.max(1, scaffoldPeak);
                scaffoldProgress = approach(scaffoldProgress, target, 9.0f, delta);
            }
        } else {
            scaffoldPeak = 0;
            scaffoldProgress = 0.0f;
            Toggle toggle = latestToggle(System.currentTimeMillis());
            if (toggle != null) {
                nextState = STATE_NOTIFICATION;
                nextLabel = toggle.name;
                nextValue = toggle.enabled ? "ON" : "OFF";
                nextKey = "notification:" + toggle.name + ':' + toggle.enabled;
            }
        }
        if (!nextKey.equals(stateKey)) {
            stateKey = nextKey;
            contentFade = 0.0f;
        }
        islandState = nextState;
        stateLabel = nextLabel;
        stateValue = nextValue;
    }

    private void drawBackdrop(float x, float y, float width, float height, float radius, int alpha) {
        if (dropShadow.isToggled()) {
            RoundedUtils.drawRoundShadow(x, y + 1.0f, width, height, radius,
                    3.0f, withAlpha(0x000000, Math.round(alpha * 0.42f)));
        }
        if (blurBackdrop.isToggled()) {
            BlurUtils.prepareBlur(x, y, width, height);
            RoundedUtils.drawRound(x, y, width, height, radius, 0xFF000000);
            BlurUtils.blurEndRegion(2, 2.2f, alpha / 255.0f, x, y, width, height);
        }
        RoundedUtils.drawGradientVertical(x, y, width, height, radius,
                new java.awt.Color(45, 43, 52, alpha),
                new java.awt.Color(25, 24, 30, alpha));
        RoundedUtils.drawRoundOutline(x + 0.25f, y + 0.25f, width - 0.5f, height - 0.5f,
                Math.max(0.0f, radius - 0.25f), 0.35f,
                new java.awt.Color(0, 0, 0, 0),
                new java.awt.Color(255, 255, 255, Math.min(22, alpha / 9)));
    }

    private void drawContent(MindlessFontRenderer text, float x, float y, float width,
                             float height, float uiScale, int alpha) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        float badge = BADGE_SIZE * uiScale;
        float badgeX = x + PAD_X * uiScale;
        float badgeY = y + (height - badge) * 0.5f;
        int accent = ThemeManager.getWatermarkColor(0.0) & 0xFFFFFF;
        RoundedUtils.drawRound(badgeX, badgeY, badge, badge, badge * 0.5f,
                withAlpha(accent, Math.min(alpha, 105)));
        float markHeight = 5.7f * uiScale;
        float markWidth = markHeight * LOGO_ASPECT;
        drawLogo(badgeX + (badge - markWidth) * 0.5f,
                badgeY + (badge - markHeight) * 0.5f,
                markWidth, markHeight, withAlpha(0xF6F2FF, alpha));
        float labelX = badgeX + badge + BADGE_GAP * uiScale;
        float textY = y + (height - text.getFontHeight() * uiScale) * 0.5f;
        if (islandState == STATE_SCAFFOLD) textY -= 1.15f * uiScale;
        drawScaled(text, stateLabel, labelX, textY, uiScale, withAlpha(0xF1F1F5, alpha));
        if (!stateValue.isEmpty()) {
            float valueWidth = text.getStringWidth(stateValue) * uiScale;
            float valueX = x + width - PAD_X * uiScale - valueWidth;
            int valueRgb = islandState == STATE_NOTIFICATION && "OFF".equals(stateValue)
                    ? 0xA7A6AE : 0xF1F1F5;
            drawScaled(text, stateValue, valueX, textY, uiScale, withAlpha(valueRgb, alpha));
        }
        if (islandState == STATE_SCAFFOLD) {
            float barX = labelX;
            float barY = y + height - 4.1f * uiScale;
            float barWidth = Math.max(10.0f * uiScale,
                    width - (labelX - x) - PAD_X * uiScale);
            float barHeight = Math.max(1.0f, 1.65f * uiScale);
            RoundedUtils.drawRound(barX, barY, barWidth, barHeight, barHeight * 0.5f,
                    withAlpha(0xFFFFFF, Math.min(alpha, 36)));
            float fill = barWidth * Math.max(0.0f, Math.min(1.0f, scaffoldProgress));
            if (fill > 0.5f) {
                RoundedUtils.drawRound(barX, barY, fill, barHeight, barHeight * 0.5f,
                        withAlpha(accent, alpha));
            }
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private float stateWidth(MindlessFontRenderer text) {
        float width = PAD_X * 2.0f + BADGE_SIZE + BADGE_GAP + text.getStringWidth(stateLabel);
        if (!stateValue.isEmpty()) width += VALUE_GAP + text.getStringWidth(stateValue);
        return Math.max(48.0f, width);
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

    private MindlessFontRenderer watermarkFont() {
        return FontManager.getHudRenderer(ModuleFont.nameOf(font),
                HUD.getSelectedFontScale() * WATERMARK_SCALE);
    }

    private void renderTextWatermark() {
        MindlessFontRenderer text = watermarkFont();
        if (text == null) return;
        String value = "Mindless";
        int baseColor = ThemeManager.getWatermarkColor(0.0);
        int r = (baseColor >> 16) & 0xFF;
        int g = (baseColor >> 8) & 0xFF;
        int b = baseColor & 0xFF;
        if (HudGlowHelper.isAvailable()) {
            HudGlowHelper.beginMask();
            drawWatermark(text, value);
            HudGlowHelper.endAndComposite(8.0f, 1.2f, r, g, b);
        }
        drawWatermark(text, value);
    }

    private void drawWatermark(MindlessFontRenderer text, String value) {
        text.drawGlyphString(value, textPosX, textPosY,
                (character, xOffset, width, formattingColor)
                        -> ThemeManager.getWatermarkColor(xOffset * 0.1), false);
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

    public float[] getTextBounds() {
        MindlessFontRenderer text = watermarkFont();
        if (text == null) return null;
        String value = "Mindless";
        return new float[]{textPosX, textPosY,
                textPosX + text.getStringWidth(value), textPosY + text.getFontHeight()};
    }

    public void resetPosition() {
        textPosX = DEFAULT_TEXT_X;
        textPosY = DEFAULT_TEXT_Y;
        islandPosX = -1.0f;
        islandPosY = -1.0f;
    }

    public void moveIslandTo(float left, float top) {
        if ((int) anchor.getInput() != 3) anchor.setValueWithEvent(3.0);
        islandPosX = left;
        islandPosY = top;
    }

    public boolean isCustomAnchored() {
        return isIslandMode() && (int) anchor.getInput() == 3;
    }

    public boolean isIslandMode() {
        return (int) mode.getInput() == 0;
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
