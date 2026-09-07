package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.combat.AimAssist;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.network.Backtrack;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Theme;
import mindless.utility.Timer;
import mindless.utility.Utils;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.*;

public class TargetHUD extends Module {
    private SliderSetting mode;
    private SliderSetting theme;
    private SliderSetting glowSize;
    private SliderSetting positionMode;
    private ButtonSetting renderEsp;
    private ButtonSetting showDifference;
    private ButtonSetting showStatus;
    private ButtonSetting healthColor;
    private SliderSetting ringColorMode;
    private SliderSetting headStyle;
    private ButtonSetting hitEffects;
    private ColorSetting hitColor;
    private SliderSetting hitStrengthScale;
    private final ColorSetting[] ringColors = new ColorSetting[RING_COUNT];
private static final int RING_COUNT = 6;
    private static final String[] RING_COLOR_MODES = new String[] { "Theme", "Array list", "Custom" };
    private static final String[] HEAD_STYLES = new String[] { "3D", "Flat" };
    private static final int HEAD_STYLE_3D = 0;
    private static final int HEAD_STYLE_FLAT = 1;
    private static final int RING_MODE_THEME = 0;
    private static final int RING_MODE_ARRAY_LIST = 1;
    private static final int RING_MODE_CUSTOM = 2;
private static final double RING_GRADIENT_SPREAD = 26.0;
private static final int[] DEFAULT_RING_COLORS = {
        0xFFFFFF, 0xC8E4FF, 0x9CC9FF, 0x74A8FF, 0x5A86F0, 0x4666D8
    };

    private static final long HIT_FLASH_MS = 260L;
    private static final long POP_IN_MS = 250L;
    private static final long POP_OUT_MS = 200L;
    private static final String[] POSITION_MODES = new String[] {
        "Screen", "Target Left", "Target Right", "Target Top", "Target Bottom", "Target Center"
    };

    private Timer fadeTimer;
    private Timer healthBarTimer = null;
    private EntityLivingBase target;
    private long lastAliveMS;
    private double lastHealth;
    private float lastHealthBar;
    private long popInStart = -1;
    private long hitFlashStart = -1L;
    private float hitStrength;
    private EntityLivingBase healthTrackedTarget;
    public int posX = 70;
    public int posY = 30;
    private float tweenedX = Float.NaN;
    private float tweenedY = Float.NaN;

    private String[] modes = new String[]{ "Modern", "Legacy" };

    public TargetHUD() {
        super("Target HUD", "A panel for whoever you are fighting.", category.render);
        this.liteModule = true;
        this.registerSetting(new DescriptionSetting("Works with KillAura and AimAssist."));
        this.registerSetting(mode = new SliderSetting("Mode", true, 1, modes));
        this.registerSetting(theme = new SliderSetting("Theme", 0, Theme.THEMES_SETTING));
        this.registerSetting(glowSize = new SliderSetting("Glow size", 9.0, 2.0, 20.0, 0.5));
        this.registerSetting(positionMode = new SliderSetting("Position", 0, POSITION_MODES));
        this.registerSetting(renderEsp = new ButtonSetting("Render ESP", true));
        this.registerSetting(showDifference = new ButtonSetting("Show difference", true));
        this.registerSetting(showStatus = new ButtonSetting("Show win or loss", true));
        this.registerSetting(healthColor = new ButtonSetting("Traditional health color", false));
        this.registerSetting(headStyle = new SliderSetting("Head style", HEAD_STYLE_FLAT, HEAD_STYLES));
        this.registerSetting(hitEffects = new ButtonSetting("Hit effects", true));
        this.registerSetting(hitColor = new ColorSetting("Hit color", 255, 92, 92, 190));
        this.registerSetting(hitStrengthScale = new SliderSetting("Hit strength", 1.0, 0.2, 2.0, 0.05));
        this.registerSetting(ringColorMode = new SliderSetting("Ring colors", RING_MODE_THEME, RING_COLOR_MODES));
        for (int i = 0; i < RING_COUNT; i++) {
            int rgb = DEFAULT_RING_COLORS[i];
            ringColors[i] = new ColorSetting("Ring " + (i + 1) + " color",
                    (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
            this.registerSetting(ringColors[i]);
        }
    }

    @Override
    public void guiUpdate() {
        glowSize.setVisible(mode.getInput() == 0, this);

        boolean esp = renderEsp != null && renderEsp.isToggled();
        if (ringColorMode != null) {
            ringColorMode.setVisible(esp, this);
        }
        boolean custom = esp && ringColorMode != null && (int) ringColorMode.getInput() == RING_MODE_CUSTOM;
        for (ColorSetting ringColor : ringColors) {
            if (ringColor != null) {
                ringColor.setVisible(custom, this);
            }
        }
    }
private int ringColor(int ringIndex) {
        int colorMode = ringColorMode == null ? RING_MODE_THEME : (int) ringColorMode.getInput();

        if (colorMode == RING_MODE_ARRAY_LIST) {
            return HUD.getHudColor(ringIndex * RING_GRADIENT_SPREAD);
        }

        if (colorMode == RING_MODE_CUSTOM) {
            ColorSetting setting = ringIndex >= 0 && ringIndex < ringColors.length ? ringColors[ringIndex] : null;
            return setting == null ? 0xFFFFFFFF : (setting.getRGB() | 0xFF000000);
        }

        return Theme.getGradient((int) theme.getInput(), 0);
    }

    public void onDisable() {
        reset();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (!Utils.nullCheck()) {
            reset();
            return;
        }
        if (ev.phase == TickEvent.Phase.END) {
            if (mode.getInput() == -1 || mc.currentScreen != null) {
                reset();
                return;
            }
            EntityLivingBase activeTarget = getActiveTarget();
            if (activeTarget != null) {
                target = activeTarget;
                lastAliveMS = System.currentTimeMillis();
                fadeTimer = null;
                if (popInStart < 0) popInStart = System.currentTimeMillis();
            } else if (target != null) {
                if (System.currentTimeMillis() - lastAliveMS >= 400 && fadeTimer == null) {
                    (fadeTimer = new Timer((int) POP_OUT_MS)).start();
                }
            }
            else {
                return;
            }
            String playerInfo = target.getDisplayName().getFormattedText();
            double health = target.getHealth() / target.getMaxHealth();
            if (target.isDead) {
                health = 0;
            }
            if (health != lastHealth) {
                (healthBarTimer = new Timer(mode.getInput() == 0 ? 500 : 350)).start();
            }
            // Only a drop counts, and only against the same target we measured last frame.
            // Comparing across a target switch would fire the effect off whatever the previous
            // player's health happened to be.
            if (healthTrackedTarget == target && health < lastHealth - 1.0E-4) {
                hitStrength = (float) Math.max(0.25, Math.min(1.0, (lastHealth - health) * 5.0));
                hitFlashStart = System.currentTimeMillis();
            }
            healthTrackedTarget = target;
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(target, true);
            drawTargetHUD(fadeTimer, playerInfo, health);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent renderWorldLastEvent) {
        if (!renderEsp.isToggled() || !Utils.nullCheck()) {
            return;
        }
        EntityLivingBase auraTarget = getActiveTarget();

        if (auraTarget == null) {
            return;
        }

        if (ModuleManager.backtrack.isRenderingServerPositionFor(auraTarget)) {
            return;
        }

        drawPillEsp(auraTarget);
    }

    private void drawPillEsp(EntityLivingBase entity) {
        float partialTicks = mindless.runtime.AccessorBridge.Minecraft_getTimer(mc).renderPartialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - mc.getRenderManager().viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;

        float entityHeight = entity.height;
        double time = (System.currentTimeMillis() % 2000L) / 2000.0;
        float bounce = (float) (Math.sin(time * Math.PI * 2.0) * 0.5 + 0.5);
        float ringY = bounce * entityHeight;

        float radius = entity.width * 0.7f;

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GlStateManager.depthMask(false);

        int trailCount = RING_COUNT - 1;
        for (int trail = trailCount; trail >= 0; trail--) {
            float trailOffset = trail * 0.06f;
            float trailBounce = (float) (Math.sin((time - trailOffset) * Math.PI * 2.0) * 0.5 + 0.5);
            float trailY = trailBounce * entityHeight;
            float alpha = trail == 0 ? 1.0f : (1.0f - (float) trail / trailCount) * 0.35f;
            float lineWidth = trail == 0 ? 5.0f : 3.0f;

            int color = ringColor(trail);
            float r = ((color >> 16) & 0xFF) / 255.0f;
            float g = ((color >> 8) & 0xFF) / 255.0f;
            float b = (color & 0xFF) / 255.0f;

            GL11.glLineWidth(lineWidth);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GlStateManager.color(r, g, b, alpha);
            int segments = 40;
            for (int i = 0; i < segments; i++) {
                double angle = Math.PI * 2.0 * i / segments;
                float px = (float) (Math.cos(angle) * radius);
                float pz = (float) (Math.sin(angle) * radius);
                GL11.glVertex3f(px, trailY, pz);
            }
            GL11.glEnd();
        }

        GlStateManager.depthMask(true);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawTargetHUD(Timer fadeTimer, String string, double health) {
        if (showDifference.isToggled() && target != null) {
            float enemyHealth = target.isDead ? 0 : Utils.getTotalHealth(target);
            float playerHealth = Utils.getTotalHealth(mc.thePlayer);

            double diff = playerHealth - enemyHealth;
            double percent = (playerHealth / mc.thePlayer.getMaxHealth()) - (enemyHealth / target.getMaxHealth());

            diff = Utils.round(diff, 1);

            String color = percent < -0.4 ? "§c" : percent < -0.15 ? "§6" : percent <= 0.15 ? "§e" : percent <= 0.4 ? "§a" : "§2";

            if (diff > 0) {
                string += " " + color + "+" + Utils.asWholeNum(diff);
            }
            else if (diff < 0) {
                string += " " + color + "-" + Utils.asWholeNum(Math.abs(diff));
            }
        }
        if (showStatus.isToggled()) {
            string = string + " " + ((health <= Utils.getTotalHealth(mc.thePlayer) / mc.thePlayer.getMaxHealth()) ? "§aW" : "§cL");
        }
        final ScaledResolution scaledResolution = new ScaledResolution(mc);
        final int padding = 8;
        final int headSize = mc.fontRendererObj.FONT_HEIGHT + 18;
        final int targetStrWithPadding = mc.fontRendererObj.getStringWidth(string) + padding + headSize + 10;

        float desiredX = (scaledResolution.getScaledWidth() / 2 - targetStrWithPadding / 2) + posX;
        float desiredY = (scaledResolution.getScaledHeight() / 2 + 15) + posY;

        int posMode = positionMode != null ? (int) positionMode.getInput() : 0;
        if (posMode > 0 && target != null) {
            float partialTicks = mindless.runtime.AccessorBridge.Minecraft_getTimer(mc).renderPartialTicks;
            double tx = target.lastTickPosX + (target.posX - target.lastTickPosX) * partialTicks;
            double ty = target.lastTickPosY + (target.posY - target.lastTickPosY) * partialTicks;
            double tz = target.lastTickPosZ + (target.posZ - target.lastTickPosZ) * partialTicks;
            float entityH = target.height;
            float entityW = target.width;
            double camX = mc.getRenderManager().viewerPosX;
            double camY = mc.getRenderManager().viewerPosY;
            double camZ = mc.getRenderManager().viewerPosZ;
            double[] projected = new double[3];
            float sw = scaledResolution.getScaledWidth();
            float sh = scaledResolution.getScaledHeight();
            float hudW = targetStrWithPadding + padding * 2;
            float hudH = (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + padding * 2 + 13;

            if (SexyESP.projectionContext != null &&
                    mindless.utility.RenderUtils.projectTo2D(SexyESP.projectionContext, tx - camX, ty - camY + entityH / 2, tz - camZ, projected)) {
                float screenX = (float) projected[0];
                float screenY = (float) projected[1];
                switch (posMode) {
                    case 1: desiredX = screenX - hudW - 10; desiredY = screenY - hudH / 2; break;
                    case 2: desiredX = screenX + 10; desiredY = screenY - hudH / 2; break;
                    case 3: desiredX = screenX - hudW / 2; desiredY = screenY - hudH - entityH * 20; break;
                    case 4: desiredX = screenX - hudW / 2; desiredY = screenY + entityH * 10; break;
                    case 5: desiredX = screenX - hudW / 2; desiredY = screenY - hudH / 2; break;
                }
                desiredX = Math.max(2, Math.min(sw - hudW - 2, desiredX));
                desiredY = Math.max(2, Math.min(sh - hudH - 2, desiredY));
            }
        }

        if (Float.isNaN(tweenedX)) { tweenedX = desiredX; tweenedY = desiredY; }
        tweenedX += (desiredX - tweenedX) * 0.15f;
        tweenedY += (desiredY - tweenedY) * 0.15f;

        final int x = Math.round(tweenedX);
        final int y = Math.round(tweenedY);
        final int n6 = x - padding;
        final int n7 = y - padding;
        final int n8 = x + targetStrWithPadding;
        final int n9 = y + (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + padding;

        float popProgress;
        if (fadeTimer == null) {
            long elapsed = System.currentTimeMillis() - popInStart;
            popProgress = Math.min(1.0f, (float) elapsed / POP_IN_MS);
            popProgress = easeOutBack(popProgress);
        } else {
            float raw = fadeTimer.getValueFloat(0.0f, 1.0f, 1);
            popProgress = 1.0f - raw;
            popProgress = Math.max(0.0f, popProgress * popProgress);
        }

        if (popProgress <= 0.001f) {
            target = null;
            healthBarTimer = null;
            popInStart = -1;
            return;
        }

        int alpha = (int) (255 * popProgress);
        float scale = popProgress;
        float centerX = (n6 + n8) * 0.5f;
        float centerY = (n7 + n9 + 13) * 0.5f;

        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        GlStateManager.translate(-centerX, -centerY, 0.0f);

        final int maxAlphaOutline = Math.min(alpha, 110);
        final int maxAlphaBackground = Math.min(alpha, 210);
        final int[] gradientColors = Theme.getGradients((int) theme.getInput());
        switch ((int) mode.getInput()) {
            case 0: {
                float w = Math.abs((float) n6 - n8);
                float h = Math.abs((float) n7 - (n9 + 13));
                float thudRadius = 8.0f * ThemeManager.roundingScale();
                BlurUtils.prepareBlur((float) n6, (float) n7, w, h);
                RoundedUtils.drawRound((float) n6, (float) n7, w, h, thudRadius, new Color(0, 0, 0, 255));
                BlurUtils.blurEndRegion(1, 1.4f, 0.60f, (float) n6 - 2.0f, (float) n7 - 2.0f,
                        w + 4.0f, h + 4.0f);
                RoundedUtils.drawRound((float) n6, (float) n7, w, h, thudRadius, new Color(0, 0, 0, (int)(maxAlphaBackground * 0.4f)));
                break;
            }
            case 1:
                RenderUtils.drawRoundedGradientOutlinedRectangle((float) n6, (float) n7, (float) n8, (float) (n9 + 13), 10.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline), Utils.mergeAlpha(gradientColors[0], alpha), Utils.mergeAlpha(gradientColors[1], alpha));
                break;
        }
        final int n13 = n6 + 5 + headSize + 7;
        final int n14 = n8 - 6;
        final int n15 = n9;

        if (target instanceof EntityPlayer) {
            int headX = n6 + 5;
            int headY = n7 + 5;
            float hit = hitEnvelope();
            if (hit <= 0.0f) {
                drawPlayerHead((EntityPlayer) target, headX, headY, headSize, headSize, alpha);
            }
            else {
                // Punch out from the head's own centre and settle back. Scaling about the
                // panel origin instead would slide the head across the card on every hit.
                float centreX = headX + headSize * 0.5f;
                float centreY = headY + headSize * 0.5f;
                float punch = 1.0f + 0.20f * hit;
                float shake = (float) Math.sin(hit * 34.0f) * 1.7f * hit;
                GlStateManager.pushMatrix();
                GlStateManager.translate(centreX + shake, centreY, 0.0f);
                GlStateManager.scale(punch, punch, 1.0f);
                GlStateManager.translate(-centreX, -centreY, 0.0f);
                drawPlayerHead((EntityPlayer) target, headX, headY, headSize, headSize, alpha);
                drawHitFlash(headX, headY, headSize, hit, alpha);
                GlStateManager.popMatrix();
            }
        }

        RenderUtils.drawRoundedRectangle((float) n13, (float) n15, (float) n14, (float) (n15 + 5), 4.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline));
        int mergedGradientLeft = Utils.mergeAlpha(gradientColors[0], maxAlphaBackground);
        int mergedGradientRight = Utils.mergeAlpha(gradientColors[1], maxAlphaBackground);
        float healthBar = (float) (int) (n14 + (n13 - n14) * (1 - health));
        boolean smoothBack = false;
        if (healthBar != lastHealthBar && lastHealthBar - n13 >= 3 && healthBarTimer != null ) {
            int type = mode.getInput() == 0 ? 4 : 1;
            float diff = lastHealthBar - healthBar;
            if (diff > 0) {
                lastHealthBar = lastHealthBar - healthBarTimer.getValueFloat(0, diff, type);
            }
            else {
                smoothBack = true;
                lastHealthBar = healthBarTimer.getValueFloat(lastHealthBar, healthBar, type);
            }
        }
        else {
            lastHealthBar = healthBar;
        }
        if (healthColor.isToggled()) {
            mergedGradientLeft = mergedGradientRight = Utils.mergeAlpha(Utils.getColorForHealth(health), maxAlphaBackground);
        }
        if (lastHealthBar > n14) {
            lastHealthBar = n14;
        }

        switch ((int) mode.getInput()) {
            case 0:
                RenderUtils.drawRoundedRectangle((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, Utils.darkenColor(mergedGradientRight, 25));
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, smoothBack ? lastHealthBar : healthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                break;
            case 1:
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                break;
        }
        GlStateManager.enableBlend();
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        mindless.utility.font.MindlessFontRenderer hudFont = HUD.getHudFontRenderer();
        hudFont.drawString(string, (float) n13, (float) y, (new Color(220, 220, 220, 255).getRGB() & 0xFFFFFF) | Utils.clamp(alpha + 15) << 24, true);
        GlStateManager.disableBlend();

        GlStateManager.popMatrix();
    }

    /**
     * 0 when no hit is playing, otherwise a decaying 0..1 envelope for the current one.
     *
     * Scaled by how much health the target actually lost, so chip damage gives a nudge and a
     * crit gives a real punch, rather than every hit looking identical.
     */
    private float hitEnvelope() {
        if (hitEffects == null || !hitEffects.isToggled() || hitFlashStart < 0L) {
            return 0.0f;
        }
        long elapsed = System.currentTimeMillis() - hitFlashStart;
        if (elapsed < 0L || elapsed >= HIT_FLASH_MS) {
            return 0.0f;
        }
        float linear = 1.0f - (float) elapsed / (float) HIT_FLASH_MS;
        float eased = linear * linear;
        float amount = (float) (hitStrengthScale == null ? 1.0 : hitStrengthScale.getInput());
        return Math.max(0.0f, Math.min(1.0f, eased * hitStrength * amount));
    }

    private void drawHitFlash(int x, int y, int size, float hit, int alpha) {
        int base = hitColor == null ? new Color(255, 92, 92, 190).getRGB() : hitColor.getColor();
        int flashAlpha = Utils.clamp(Math.round(((base >>> 24) & 0xFF) * hit * (alpha / 255.0f)));
        if (flashAlpha <= 0) return;
        float radius = Math.max(2.0f, size * 0.14f);
        RoundedUtils.drawRound(x, y, size, size, radius,
                new Color((base & 0xFFFFFF) | (flashAlpha << 24), true));
    }

    private void drawPlayerHead(EntityPlayer player, int x, int y, int width, int height, int alpha) {
        try {
            ResourceLocation skin;
            if (player instanceof AbstractClientPlayer) {
                skin = ((AbstractClientPlayer) player).getLocationSkin();
            } else {
                NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(player.getUniqueID());
                if (playerInfo == null) return;
                skin = playerInfo.getLocationSkin();
            }
            if (skin == null) return;
            boolean depthEnabled = GL11.glIsEnabled(2929);
            boolean blendEnabled = GL11.glIsEnabled(3042);
            boolean cullEnabled = GL11.glIsEnabled(2884);
            boolean depthMask = GL11.glGetBoolean(2930);
            try {
                RenderUtils.prepareGuiTextureRenderState();
                GlStateManager.disableCull();
                mc.getTextureManager().bindTexture(skin);
                GlStateManager.color(1.0f, 1.0f, 1.0f, (float) alpha / 255.0f);
                if (headStyle == null || (int) headStyle.getInput() == HEAD_STYLE_3D) {
                    drawHeadCube(x, y, width, height, alpha);
                }
                else {
                    float cornerRadius = Math.max(2.0f, (float) Math.min(width, height) * 0.14f);
                    drawRoundedSkinLayer(x, y, width, height, cornerRadius, 8.0f, 8.0f, alpha);
                    drawRoundedSkinLayer(x, y, width, height, cornerRadius, 40.0f, 8.0f, alpha);
                }
            } finally {
                RenderUtils.restoreGuiRenderState(depthEnabled, blendEnabled, depthMask);
                if (cullEnabled) GlStateManager.enableCull();
                else GlStateManager.disableCull();
            }
        } catch (Exception ignored) {}
    }
private void drawHeadCube(float x, float y, float width, float height, int alpha) {
        float centerX = x + width * 0.5f;
        float centerY = y + height * 0.5f;
        float size = Math.min(width, height) * 0.66f;
        double seconds = (System.currentTimeMillis() % 6000L) / 6000.0;
        float yaw = (float) Math.toRadians(-26.0 + Math.sin(seconds * Math.PI * 2.0) * 8.0);
        float pitch = (float) Math.toRadians(14.0);

        drawSkinCube(centerX, centerY, size, yaw, pitch, alpha, 0.0f);
        drawSkinCube(centerX, centerY, size * 1.09f, yaw, pitch, alpha, 32.0f);
    }
private static final float[][][] HEAD_FACES = {
        {{-.5f, .5f, .5f}, {.5f, .5f, .5f}, {.5f, -.5f, .5f}, {-.5f, -.5f, .5f}},
        {{.5f, .5f, -.5f}, {-.5f, .5f, -.5f}, {-.5f, -.5f, -.5f}, {.5f, -.5f, -.5f}},
        {{-.5f, .5f, -.5f}, {-.5f, .5f, .5f}, {-.5f, -.5f, .5f}, {-.5f, -.5f, -.5f}},
        {{.5f, .5f, .5f}, {.5f, .5f, -.5f}, {.5f, -.5f, -.5f}, {.5f, -.5f, .5f}},
        {{-.5f, .5f, -.5f}, {.5f, .5f, -.5f}, {.5f, .5f, .5f}, {-.5f, .5f, .5f}},
        {{-.5f, -.5f, .5f}, {.5f, -.5f, .5f}, {.5f, -.5f, -.5f}, {-.5f, -.5f, -.5f}}
    };
private static final float[][] HEAD_NORMALS = {
        {0f, 0f, 1f}, {0f, 0f, -1f}, {-1f, 0f, 0f}, {1f, 0f, 0f}, {0f, 1f, 0f}, {0f, -1f, 0f}
    };
private static final float[][] HEAD_UVS = {
        {8f, 8f, 8f, 8f}, {24f, 8f, 8f, 8f}, {0f, 8f, 8f, 8f},
        {16f, 8f, 8f, 8f}, {8f, 0f, 8f, 8f}, {16f, 0f, 8f, 8f}
    };

    private static final float SKIN_TEXTURE_SIZE = 64.0f;

    private void drawSkinCube(float centerX, float centerY, float size, float yaw, float pitch,
                              int alpha, float textureUOffset) {
        float cosYaw = (float) Math.cos(yaw);
        float sinYaw = (float) Math.sin(yaw);
        float cosPitch = (float) Math.cos(pitch);
        float sinPitch = (float) Math.sin(pitch);

        GlStateManager.color(1.0f, 1.0f, 1.0f, alpha / 255.0f);
        GL11.glBegin(GL11.GL_QUADS);
        for (int face = 0; face < HEAD_FACES.length; face++) {
            float[] normal = rotate(HEAD_NORMALS[face], cosYaw, sinYaw, cosPitch, sinPitch);
            if (normal[2] <= 0.0f) {
                continue;
            }

            float[] uv = HEAD_UVS[face];
            float[][] corners = HEAD_FACES[face];
            for (int corner = 0; corner < corners.length; corner++) {
                float[] rotated = rotate(corners[corner], cosYaw, sinYaw, cosPitch, sinPitch);
                float u = uv[0] + textureUOffset + (corner == 1 || corner == 2 ? uv[2] : 0.0f);
                float v = uv[1] + (corner == 2 || corner == 3 ? uv[3] : 0.0f);
                GL11.glTexCoord2f(u / SKIN_TEXTURE_SIZE, v / SKIN_TEXTURE_SIZE);
                GL11.glVertex2f(centerX + rotated[0] * size, centerY - rotated[1] * size);
            }
        }
        GL11.glEnd();
    }

    private static float[] rotate(float[] point, float cosYaw, float sinYaw, float cosPitch, float sinPitch) {
        float x = point[0] * cosYaw + point[2] * sinYaw;
        float z = point[2] * cosYaw - point[0] * sinYaw;
        float y = point[1] * cosPitch - z * sinPitch;
        z = z * cosPitch + point[1] * sinPitch;
        return new float[]{x, y, z};
    }

    private void drawRoundedSkinLayer(float x, float y, float width, float height, float radius, float textureU, float textureV, int alpha) {
        float r = Math.min(radius, Math.min(width, height) * 0.5f);
        GlStateManager.color(1.0f, 1.0f, 1.0f, (float) alpha / 255.0f);
        GL11.glBegin(6); // GL_TRIANGLE_FAN
        addSkinVertex(x + width * 0.5f, y + height * 0.5f, x, y, width, height, textureU, textureV);
        addSkinCorner(x + width - r, y + r,          r, -90.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + width - r, y + height - r, r,   0.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + r,         y + height - r, r,  90.0, x, y, width, height, textureU, textureV);
        addSkinCorner(x + r,         y + r,          r, 180.0, x, y, width, height, textureU, textureV);
        addSkinVertex(x + width - r, y, x, y, width, height, textureU, textureV);
        GL11.glEnd();
    }

    private void addSkinCorner(float centerX, float centerY, float radius, double startDegrees,
                                float x, float y, float width, float height, float textureU, float textureV) {
        for (int segment = 0; segment <= 4; segment++) {
            double angle = Math.toRadians(startDegrees + segment * 22.5);
            addSkinVertex((float) (centerX + Math.cos(angle) * radius),
                          (float) (centerY + Math.sin(angle) * radius),
                          x, y, width, height, textureU, textureV);
        }
    }

    private void addSkinVertex(float vertexX, float vertexY, float x, float y, float width, float height, float textureU, float textureV) {
        float normalizedX = (vertexX - x) / width;
        float normalizedY = (vertexY - y) / height;
        GL11.glTexCoord2f((textureU + normalizedX * 8.0f) / 64.0f, (textureV + normalizedY * 8.0f) / 64.0f);
        GL11.glVertex2f(vertexX, vertexY);
    }

    private EntityLivingBase getActiveTarget() {
        if (KillAura.target != null) return KillAura.target;
        if (KillAura.attackingEntity != null) return KillAura.attackingEntity;
        if (mindless.Mindless.getModuleManager() != null) {
            for (mindless.module.Module mod : mindless.Mindless.getModuleManager().getModules()) {
                if (mod instanceof AimAssist && mod.isEnabled()) {
                    net.minecraft.entity.Entity aimTarget = ((AimAssist) mod).getAimAssistTarget();
                    if (aimTarget instanceof EntityLivingBase) return (EntityLivingBase) aimTarget;
                    break;
                }
            }
        }
        return null;
    }

    private void reset() {
        fadeTimer = null;
        target = null;
        healthBarTimer = null;
        popInStart = -1;
        hitFlashStart = -1L;
        hitStrength = 0.0f;
        healthTrackedTarget = null;
        tweenedX = Float.NaN;
        tweenedY = Float.NaN;
    }

    private static float easeOutBack(float t) {
        float c1 = 1.70158f;
        float c3 = c1 + 1.0f;
        float tm1 = t - 1.0f;
        return 1.0f + c3 * tm1 * tm1 * tm1 + c1 * tm1 * tm1;
    }

    public boolean isEspActiveFor(EntityLivingBase entity) {
        return this.isEnabled() && this.renderEsp.isToggled() && entity != null && entity == getActiveTarget();
    }

    public Color getCurrentEspColor(int alpha) {
        int color = Theme.getGradient((int) theme.getInput(), 0);
        int safeAlpha = Math.max(0, Math.min(255, alpha));

        return new Color(color >> 16 & 255, color >> 8 & 255, color & 255, safeAlpha);
    }

    public void resetPosition() {
        posX = 70;
        posY = 30;
    }

    public float[] renderPreview() {
        return renderDesignerPreview(posX, posY);
    }

    public float[] renderDesignerPreview(float left, float top) {
        posX = (int) left;
        posY = (int) top;
        String playerInfo = mc.thePlayer.getDisplayName().getFormattedText();
        double health = mc.thePlayer.getHealth() / mc.thePlayer.getMaxHealth();
        if (mc.thePlayer.isDead) {
            health = 0;
        }
        lastHealth = health;
        playerInfo += " " + Utils.getHealthStr(mc.thePlayer, true);
        drawTargetHUD(null, playerInfo, health);
        ScaledResolution res = new ScaledResolution(mc);
        int headSize = mc.fontRendererObj.FONT_HEIGHT + 18;
        int totalContentWidth = mc.fontRendererObj.getStringWidth(playerInfo) + 8 + headSize + 10;
        int padding = 8;
        float desiredX = (res.getScaledWidth() / 2 - totalContentWidth / 2) + posX;
        float desiredY = (res.getScaledHeight() / 2 + 15) + posY;
        float w = totalContentWidth + padding * 2;
        float h = (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + padding * 2 + 13;
        return new float[] { desiredX - padding, desiredY - padding, desiredX - padding + w, desiredY - padding + h };
    }
}
