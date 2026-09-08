package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.combat.AimAssist;
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
import mindless.module.setting.impl.GroupSetting;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
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
    private GroupSetting ringGroup;
    private SliderSetting ringStyle;
    private SliderSetting ringCount;
    private SliderSetting ringSize;
    private SliderSetting ringSpeed;
    private SliderSetting ringThickness;
    private SliderSetting ringQuality;
    private ButtonSetting ringSoftEdges;
    private SliderSetting headStyle;
    private ButtonSetting hitEffects;
    private SliderSetting hitStyle;
    private SliderSetting hitParticleColor;
    private SliderSetting hitParticleAmount;
    private ColorSetting hitColor;
    private SliderSetting hitStrengthScale;
    private final ColorSetting[] ringColors = new ColorSetting[RING_COUNT];
private static final int RING_COUNT = 6;
    private static final String[] RING_COLOR_MODES = new String[] { "Theme", "Array list", "Custom" };
    private static final String[] RING_STYLES = new String[] { "Trail", "Orbit", "Pulse", "Halo", "Simple" };
    private static final int RING_STYLE_TRAIL = 0;
    private static final int RING_STYLE_ORBIT = 1;
    private static final int RING_STYLE_PULSE = 2;
    private static final int RING_STYLE_HALO = 3;
    private static final int RING_STYLE_SIMPLE = 4;
    private static final String[] HEAD_STYLES = new String[] { "3D", "Flat", "2D" };
    private static final int HEAD_STYLE_3D = 0;
    private static final int HEAD_STYLE_FLAT = 1;
    private static final int HEAD_STYLE_2D = 2;
    private static final int RING_MODE_THEME = 0;
    private static final int RING_MODE_ARRAY_LIST = 1;
    private static final int RING_MODE_CUSTOM = 2;
private static final double RING_GRADIENT_SPREAD = 26.0;
private static final int[] DEFAULT_RING_COLORS = {
        0xFFFFFF, 0xC8E4FF, 0x9CC9FF, 0x74A8FF, 0x5A86F0, 0x4666D8
    };

    private static final long HIT_FLASH_MS = 260L;
    private static final String[] HIT_STYLES =
            new String[] { "Flash", "Sparks", "Orbs", "Shatter", "Ripple", "Embers" };
    private static final int HIT_STYLE_FLASH = 0;
    private static final int HIT_STYLE_SPARKS = 1;
    private static final int HIT_STYLE_ORBS = 2;
    private static final int HIT_STYLE_SHATTER = 3;
    private static final int HIT_STYLE_RIPPLE = 4;
    private static final int HIT_STYLE_EMBERS = 5;
    private static final String[] HIT_PARTICLE_COLORS =
            new String[] { "Hit color", "Theme", "Rainbow" };
    private static final int HIT_COLORS_HIT = 0;
    private static final int HIT_COLORS_THEME = 1;
    private static final int HIT_COLORS_RAINBOW = 2;
    private static final long POP_IN_MS = 140L;
    private static final long POP_OUT_MS = 160L;
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

    // Acquisition trace. Nothing already in the client records when the panel first drew
    // relative to when KillAura picked the opponent up: the profiler measures time spent in
    // code, and the GL audit only reports state. Each stage is reported once per target, with
    // the delay since that target was first seen, which is what separates "the aura took a
    // while to lock on" from "the panel was drawn all along and could not be seen".
    private int traceTargetId = -1;
    private long traceStartAt;
    private final java.util.Set<String> tracedStages = new java.util.HashSet<String>();

    private void traceStage(EntityLivingBase who, String stage, String detail) {
        if (who == null || !mindless.utility.Diagnostics.isEnabled()) {
            return;
        }
        if (who.getEntityId() != traceTargetId) {
            traceTargetId = who.getEntityId();
            traceStartAt = System.currentTimeMillis();
            tracedStages.clear();
        }
        if (!tracedStages.add(stage)) {
            return;
        }
        mindless.utility.Diagnostics.log("targethud", "+"
                + (System.currentTimeMillis() - traceStartAt) + "ms " + stage
                + " [" + who.getName() + "]" + (detail.isEmpty() ? "" : " " + detail));
    }

    private String[] modes = new String[]{ "Modern", "Legacy", "Compact" };

    public TargetHUD() {
        super("Target HUD", "A panel for whoever you are fighting.", category.render);
        this.liteModule = true;
        this.registerSetting(new DescriptionSetting("Works with KillAura and AimAssist."));
        this.registerSetting(mode = new SliderSetting("Mode", true, 1, modes));
        this.registerSetting(theme = new SliderSetting("Theme", 0, Theme.THEMES_SETTING));
        this.registerSetting(glowSize = new SliderSetting("Glow size", 9.0, 2.0, 20.0, 0.5));
        this.registerSetting(positionMode = new SliderSetting("Position", 0, POSITION_MODES));
        this.registerSetting(showDifference = new ButtonSetting("Show difference", true));
        this.registerSetting(showStatus = new ButtonSetting("Show win or loss", true));
        this.registerSetting(healthColor = new ButtonSetting("Traditional health color", false));
        this.registerSetting(headStyle = new SliderSetting("Head style", HEAD_STYLE_FLAT, HEAD_STYLES));
        this.registerSetting(hitEffects = new ButtonSetting("Hit effects", true));
        this.registerSetting(hitStyle = new SliderSetting("Hit effect", HIT_STYLE_FLASH, HIT_STYLES));
        this.registerSetting(hitParticleColor = new SliderSetting("Effect colors", HIT_COLORS_HIT, HIT_PARTICLE_COLORS));
        this.registerSetting(hitParticleAmount = new SliderSetting("Effect amount", 1.0, 0.25, 2.0, 0.05));
        this.registerSetting(hitColor = new ColorSetting("Hit color", 255, 92, 92, 190));
        this.registerSetting(hitStrengthScale = new SliderSetting("Hit strength", 1.0, 0.2, 2.0, 0.05));

        // The rings are their own thing and were scattered through the panel settings. Names
        // are unchanged, and profiles key settings by name alone, so existing configs keep
        // their values -- only where they appear in the menu has moved.
        this.registerSetting(ringGroup = new GroupSetting("Target rings"));
        this.registerSetting(renderEsp = new ButtonSetting(ringGroup, "Render ESP", true));
        this.registerSetting(ringStyle = new SliderSetting(ringGroup, "Ring style", RING_STYLE_TRAIL, RING_STYLES));
        this.registerSetting(ringCount = new SliderSetting(ringGroup, "Ring count", RING_COUNT, 1, RING_COUNT, 1));
        this.registerSetting(ringSize = new SliderSetting(ringGroup, "Ring size", 1.0, 0.4, 2.5, 0.05));
        this.registerSetting(ringSpeed = new SliderSetting(ringGroup, "Ring speed", 1.0, 0.1, 3.0, 0.05));
        this.registerSetting(ringThickness = new SliderSetting(ringGroup, "Ring thickness", 2.5, 0.5, 8.0, 0.25));
        this.registerSetting(ringQuality = new SliderSetting(ringGroup, "Ring quality", 40, 10, 64, 2));
        this.registerSetting(ringSoftEdges = new ButtonSetting(ringGroup, "Soft edges", true));
        this.registerSetting(ringColorMode = new SliderSetting(ringGroup, "Ring colors", RING_MODE_THEME, RING_COLOR_MODES));
        for (int i = 0; i < RING_COUNT; i++) {
            int rgb = DEFAULT_RING_COLORS[i];
            // Alpha is per ring now. Saved colours are "r,g,b" and load unchanged; the
            // missing fourth field simply leaves the default in place.
            ringColors[i] = new ColorSetting(ringGroup, "Ring " + (i + 1) + " color",
                    (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
            this.registerSetting(ringColors[i]);
        }
    }

    @Override
    public void guiUpdate() {
        glowSize.setVisible(mode.getInput() == 0, this);

        boolean hits = hitEffects != null && hitEffects.isToggled();
        int hitMode = hitStyle == null ? HIT_STYLE_FLASH : (int) hitStyle.getInput();
        if (hitStyle != null) hitStyle.setVisible(hits, this);
        if (hitStrengthScale != null) hitStrengthScale.setVisible(hits, this);
        // Flash is the plain overlay -- it has no particles to colour or count.
        boolean particles = hits && hitMode != HIT_STYLE_FLASH;
        if (hitParticleColor != null) hitParticleColor.setVisible(particles, this);
        if (hitParticleAmount != null) hitParticleAmount.setVisible(particles, this);
        if (hitColor != null) {
            int colorMode = hitParticleColor == null ? HIT_COLORS_HIT : (int) hitParticleColor.getInput();
            hitColor.setVisible(hits && (!particles || colorMode == HIT_COLORS_HIT), this);
        }

        boolean esp = renderEsp != null && renderEsp.isToggled();
        int style = ringStyle == null ? RING_STYLE_TRAIL : (int) ringStyle.getInput();
        if (ringStyle != null) ringStyle.setVisible(esp, this);
        // Simple is the one style that draws a single ring, so a count would mean nothing.
        if (ringCount != null) ringCount.setVisible(esp && style != RING_STYLE_SIMPLE, this);
        if (ringSize != null) ringSize.setVisible(esp, this);
        if (ringSpeed != null) {
            ringSpeed.setVisible(esp && style != RING_STYLE_HALO && style != RING_STYLE_SIMPLE, this);
        }
        if (ringThickness != null) ringThickness.setVisible(esp, this);
        if (ringQuality != null) ringQuality.setVisible(esp, this);
        if (ringSoftEdges != null) ringSoftEdges.setVisible(esp, this);
        if (ringColorMode != null) {
            ringColorMode.setVisible(esp, this);
        }
        boolean custom = esp && ringColorMode != null && (int) ringColorMode.getInput() == RING_MODE_CUSTOM;
        int shown = style == RING_STYLE_SIMPLE ? 1
                : (ringCount == null ? RING_COUNT : Math.max(1, (int) ringCount.getInput()));
        for (int i = 0; i < ringColors.length; i++) {
            if (ringColors[i] != null) {
                // Only the rings that actually draw are worth a colour picker.
                ringColors[i].setVisible(custom && i < shown, this);
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
            // Keep the picker alpha rather than forcing it opaque, so one ring can be dimmed
            // on its own instead of only by the style falloff.
            return setting == null ? 0xFFFFFFFF
                    : ((setting.getRGB() & 0x00FFFFFF) | (setting.getAlpha() << 24));
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
            if (mode.getInput() == -1) {
                reset();
                return;
            }
            // Any open screen used to reset(), which threw the target away rather than merely
            // hiding the panel. Opening chat mid-fight therefore dropped the opponent and the
            // panel had to re-acquire and pop in again afterwards, which is the intermittent
            // disappearance. Chat now keeps it on screen; other screens only suppress drawing
            // and leave the tracked target intact.
            boolean chatOpen = mc.currentScreen instanceof net.minecraft.client.gui.GuiChat;
            boolean screenHides = mc.currentScreen != null && !chatOpen;
            EntityLivingBase activeTarget = getActiveTarget();
            if (activeTarget != null) {
                traceStage(activeTarget, "hud sees aura target", "");
                if (screenHides && mindless.utility.Diagnostics.isEnabled()) {
                    traceStage(activeTarget, "panel suppressed: screen open",
                            mc.currentScreen.getClass().getSimpleName());
                }
                target = activeTarget;
                lastAliveMS = System.currentTimeMillis();
                fadeTimer = null;
                if (popInStart < 0) popInStart = System.currentTimeMillis();
            } else if (target != null) {
                // KillAura counts the chat box as a screen under "Disable in inventory", which
                // is on by default, so typing clears its target. Hold the panel while chat is
                // open instead of dropping the opponent and popping back in on send.
                if (chatOpen) {
                    lastAliveMS = System.currentTimeMillis();
                }
                if (System.currentTimeMillis() - lastAliveMS >= 100 && fadeTimer == null) {
                    (fadeTimer = new Timer((int) POP_OUT_MS)).start();
                    traceStage(target, "target released, fading out", "");
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
                // Deferred: the head box is not known until the panel lays itself out, and
                // the particles are spawned around it.
                hitSpawnPending = true;
            }
            healthTrackedTarget = target;
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(target, true);
            if (!screenHides) {
                drawTargetHUD(fadeTimer, playerInfo, health);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent renderWorldLastEvent) {
        if (!renderEsp.isToggled() || !Utils.nullCheck()) {
            return;
        }
        EntityLivingBase auraTarget = getActiveTarget();

        // The rings used to cut out the instant KillAura let go, while the panel held for a
        // moment and faded, so the two disagreed on their way off screen. They now share the
        // panel's fade and leave together.
        float espFade = 1.0f;
        if (auraTarget == null) {
            if (target == null || fadeTimer == null) {
                return;
            }
            espFade = Math.max(0.0f, 1.0f - fadeTimer.getValueFloat(0.0f, 1.0f, 1));
            espFade *= espFade;
            if (espFade <= 0.001f) {
                return;
            }
            auraTarget = target;
        }

        if (ModuleManager.backtrack.isRenderingServerPositionFor(auraTarget)) {
            return;
        }

        traceStage(auraTarget, "esp ring drawn", "");
        drawPillEsp(auraTarget, espFade);
    }

    /**
     * The rings around the target.
     *
     * Every ring in every style is the same primitive: a flat band of quads swept around a
     * circle, optionally tilted and spun, with the alpha falling to zero at both edges so it
     * reads as a soft line rather than a hard strip. That replaced GL_LINE_LOOP with
     * glLineWidth, which gave no control over softness, is capped at wildly different widths
     * from driver to driver, and cost a separate draw call per ring. Everything now lands in
     * one Tessellator batch and one draw.
     *
     * "Ring quality" is the segment count and "Soft edges" halves the geometry when off, so a
     * cheap configuration is a real option rather than a euphemism.
     */
    private void drawPillEsp(EntityLivingBase entity, float fade) {
        float partialTicks = mindless.runtime.AccessorBridge.Minecraft_getTimer(mc).renderPartialTicks;
        double x = entity.lastTickPosX + (entity.posX - entity.lastTickPosX) * partialTicks - mc.getRenderManager().viewerPosX;
        double y = entity.lastTickPosY + (entity.posY - entity.lastTickPosY) * partialTicks - mc.getRenderManager().viewerPosY;
        double z = entity.lastTickPosZ + (entity.posZ - entity.lastTickPosZ) * partialTicks - mc.getRenderManager().viewerPosZ;

        int style = ringStyle == null ? RING_STYLE_TRAIL : (int) ringStyle.getInput();
        int count = style == RING_STYLE_SIMPLE
                ? 1
                : Math.max(1, Math.min(RING_COUNT, ringCount == null ? RING_COUNT : (int) ringCount.getInput()));
        int segments = Math.max(8, ringQuality == null ? 40 : (int) ringQuality.getInput());
        boolean soft = ringSoftEdges == null || ringSoftEdges.isToggled();

        float entityHeight = entity.height;
        float baseRadius = entity.width * 0.7f * (float) (ringSize == null ? 1.0 : ringSize.getInput());
        double speed = ringSpeed == null ? 1.0 : ringSpeed.getInput();
        double time = (System.currentTimeMillis() % 86400000L) / 2000.0 * speed;

        // A band is world space, so a ring far away thins out to nothing where a screen-space
        // line would not. Widen it with distance, but only up to a point, or a target across
        // the map ends up wearing a dinner plate.
        double distance = Math.sqrt(x * x + y * y + z * z);
        float thickness = (float) ((ringThickness == null ? 2.5 : ringThickness.getInput()) * 0.012);
        thickness *= (float) Math.min(3.5, Math.max(1.0, distance / 12.0));

        ensureCircle(segments);

        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Quads have a facing and these are seen from both sides.
        GlStateManager.disableCull();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);

        for (int i = 0; i < count; i++) {
            float ringY;
            float radius;
            float strength;
            float tilt = 0.0f;
            float spin = 0.0f;

            switch (style) {
                case RING_STYLE_ORBIT: {
                    // Rings of one size through the middle of the target, each on its own axis
                    // and all turning together.
                    ringY = entityHeight * 0.5f;
                    radius = baseRadius * 1.15f;
                    tilt = (float) (Math.PI * (0.18 + 0.5 * i / (double) Math.max(1, count)));
                    spin = (float) (time * 2.0 * Math.PI + i * Math.PI * 2.0 / count);
                    strength = 0.9f - 0.09f * i;
                    break;
                }
                case RING_STYLE_PULSE: {
                    // Sonar: each ring is born at the feet, grows outward and upward, and fades
                    // as it goes, with the ring births evenly spread through the cycle.
                    double phase = ((time * 0.6) + (double) i / count) % 1.0;
                    radius = baseRadius * (float) (0.3 + 1.25 * phase);
                    ringY = (float) (phase * entityHeight * 0.55);
                    strength = (float) ((1.0 - phase) * (1.0 - phase));
                    break;
                }
                case RING_STYLE_HALO: {
                    // Still, on the ground, fading outward. Nothing animates, which makes this
                    // and Simple the two that cost the same every frame.
                    ringY = 0.02f + i * 0.003f;
                    radius = baseRadius * (1.0f + 0.16f * i);
                    strength = 1.0f - (float) i / (count + 1);
                    strength *= strength;
                    break;
                }
                case RING_STYLE_SIMPLE: {
                    ringY = 0.02f;
                    radius = baseRadius;
                    strength = 1.0f;
                    break;
                }
                case RING_STYLE_TRAIL:
                default: {
                    // The original look: one ring bouncing up the target with the rest trailing
                    // behind it in phase.
                    int trailCount = Math.max(1, count - 1);
                    float bounce = (float) (Math.sin((time - i * 0.06) * Math.PI * 2.0) * 0.5 + 0.5);
                    ringY = bounce * entityHeight;
                    radius = baseRadius;
                    strength = i == 0 ? 1.0f : (1.0f - (float) i / trailCount) * 0.35f;
                    break;
                }
            }

            int argb = ringColor(i);
            int ringAlpha = (argb >>> 24) & 0xFF;
            if (ringAlpha == 0) {
                // Theme and array-list colours arrive without an alpha channel.
                ringAlpha = 255;
            }
            int alpha = Math.round(ringAlpha * strength * fade);
            if (alpha <= 1 || radius <= 0.0f) {
                continue;
            }
            emitRing(worldRenderer, ringY, radius, thickness, segments, tilt, spin, argb,
                    Math.min(255, alpha), soft);
        }

        tessellator.draw();

        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private double[] circleCos;
    private double[] circleSin;
    private int circleSegments = -1;

    /** The unit circle for the current segment count, rebuilt only when that count changes. */
    private void ensureCircle(int segments) {
        if (circleSegments == segments && circleCos != null) {
            return;
        }
        circleCos = new double[segments];
        circleSin = new double[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2.0 * i / segments;
            circleCos[i] = Math.cos(angle);
            circleSin[i] = Math.sin(angle);
        }
        circleSegments = segments;
    }

    /**
     * One ring, as a band of quads.
     *
     * Soft draws two bands, transparent at the outside edges and full in the middle, so the
     * ring has a falloff instead of a hard border. Off draws a single flat band, which is half
     * the geometry and the cheap option.
     */
    private void emitRing(WorldRenderer worldRenderer, float ringY, float radius, float thickness,
                          int segments, float tilt, float spin, int argb, int alpha, boolean soft) {
        float cosTilt = (float) Math.cos(tilt);
        float sinTilt = (float) Math.sin(tilt);
        float cosSpin = (float) Math.cos(spin);
        float sinSpin = (float) Math.sin(spin);

        int red = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int blue = argb & 0xFF;

        float inner = Math.max(0.0f, radius - (soft ? thickness : thickness * 0.5f));
        float outer = radius + (soft ? thickness : thickness * 0.5f);

        for (int segment = 0; segment < segments; segment++) {
            int next = segment + 1 == segments ? 0 : segment + 1;
            if (soft) {
                band(worldRenderer, segment, next, inner, radius, ringY,
                        cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, 0, alpha);
                band(worldRenderer, segment, next, radius, outer, ringY,
                        cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, alpha, 0);
            }
            else {
                band(worldRenderer, segment, next, inner, outer, ringY,
                        cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, alpha, alpha);
            }
        }
    }

    private void band(WorldRenderer worldRenderer, int segment, int next, float innerRadius,
                      float outerRadius, float ringY, float cosTilt, float sinTilt,
                      float cosSpin, float sinSpin, int red, int green, int blue,
                      int innerAlpha, int outerAlpha) {
        ringVertex(worldRenderer, segment, innerRadius, ringY, cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, innerAlpha);
        ringVertex(worldRenderer, next, innerRadius, ringY, cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, innerAlpha);
        ringVertex(worldRenderer, next, outerRadius, ringY, cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, outerAlpha);
        ringVertex(worldRenderer, segment, outerRadius, ringY, cosTilt, sinTilt, cosSpin, sinSpin, red, green, blue, outerAlpha);
    }

    private void ringVertex(WorldRenderer worldRenderer, int segment, float radius, float ringY,
                            float cosTilt, float sinTilt, float cosSpin, float sinSpin,
                            int red, int green, int blue, int alpha) {
        double px = circleCos[segment] * radius;
        double pz = circleSin[segment] * radius;
        // Tilt about X, then spin about Y. The ring is flat in its own plane, so the tilt only
        // has to move the Z component into Y.
        double tiltedY = -pz * sinTilt;
        double tiltedZ = pz * cosTilt;
        double finalX = px * cosSpin + tiltedZ * sinSpin;
        double finalZ = -px * sinSpin + tiltedZ * cosSpin;
        worldRenderer.pos(finalX, ringY + tiltedY, finalZ).color(red, green, blue, alpha).endVertex();
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
        final int panelMode = (int) mode.getInput();
        final boolean compact = panelMode == 2;
        final int padding = compact ? 6 : 8;
        final int headSize = mc.fontRendererObj.FONT_HEIGHT + (compact ? 12 : 18);
        final int footerHeight = compact ? 9 : 13;
        final int barHeight = compact ? 3 : 5;
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
            float hudH = (mc.fontRendererObj.FONT_HEIGHT + 5) - 6 + padding * 2 + footerHeight;

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

        // Only a completed fade-OUT ends the panel. The pop-in starts at exactly zero --
        // easeOutBack(0) is 0, and popInStart is set in the same call that draws, so the first
        // frame of a new target usually measures no elapsed time at all. Sharing this bail with
        // the fade-out therefore read the start of the animation as the end of one: the target
        // was discarded and popInStart reset, the next frame re-acquired it and started over,
        // and the panel stayed invisible until the millisecond clock happened to tick between
        // the two. That is the second or so before the panel appears while the ESP rings are
        // already up.
        if (fadeTimer != null && popProgress <= 0.001f) {
            traceStage(target, "panel skipped: faded out", "");
            target = null;
            healthBarTimer = null;
            popInStart = -1;
            return;
        }

        int alpha = (int) (255 * popProgress);
        // Built only when diagnostics is on: this runs on every frame the panel is up, and
        // concatenating it unconditionally would allocate a string per frame for a line
        // nobody is reading.
        if (mindless.utility.Diagnostics.isEnabled()) {
            traceStage(target, "panel drawn", "alpha=" + alpha + " x=" + x + " y=" + y
                    + " w=" + targetStrWithPadding + " posMode=" + posMode
                    + " desired=" + Math.round(desiredX) + "," + Math.round(desiredY));
        }
        float scale = popProgress;
        float centerX = (n6 + n8) * 0.5f;
        float centerY = (n7 + n9 + footerHeight) * 0.5f;

        GlStateManager.pushMatrix();
        GlStateManager.translate(centerX, centerY, 0.0f);
        GlStateManager.scale(scale, scale, 1.0f);
        GlStateManager.translate(-centerX, -centerY, 0.0f);

        final int maxAlphaOutline = Math.min(alpha, 110);
        final int maxAlphaBackground = Math.min(alpha, 210);
        final int[] gradientColors = Theme.getGradients((int) theme.getInput());
        switch (panelMode) {
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
                RenderUtils.drawRoundedGradientOutlinedRectangle((float) n6, (float) n7, (float) n8, (float) (n9 + footerHeight), 10.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline), Utils.mergeAlpha(gradientColors[0], alpha), Utils.mergeAlpha(gradientColors[1], alpha));
                break;
            case 2: {
                float w = Math.abs((float) n6 - n8);
                float h = Math.abs((float) n7 - (n9 + footerHeight));
                float radius = 4.0f * ThemeManager.roundingScale();
                RoundedUtils.drawRound(n6, n7, w, h, radius,
                        new Color(7, 9, 12, Math.min(alpha, 220)));
                RenderUtils.drawRoundedGradientRect(n6, n7, n6 + 2.0f, n9 + footerHeight,
                        radius, Utils.mergeAlpha(gradientColors[0], alpha),
                        Utils.mergeAlpha(gradientColors[0], alpha),
                        Utils.mergeAlpha(gradientColors[1], alpha),
                        Utils.mergeAlpha(gradientColors[1], alpha));
                break;
            }
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
                if (hitStyleValue() == HIT_STYLE_FLASH) {
                    drawHitFlash(headX, headY, headSize, hit, alpha);
                }
                GlStateManager.popMatrix();
            }

            // Outside the punch matrix: the particles are thrown off the head, they do not
            // ride its scale. They also outlive the punch, so this is not inside the branch.
            updateAndDrawHitParticles(headX, headY, headSize, alpha);
        }

        RenderUtils.drawRoundedRectangle((float) n13, (float) n15, (float) n14,
                (float) (n15 + barHeight), 4.0f,
                Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline));
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

        switch (panelMode) {
            case 0:
                RenderUtils.drawRoundedRectangle((float) n13, (float) n15, lastHealthBar, (float) (n15 + barHeight), 4.0f, Utils.darkenColor(mergedGradientRight, 25));
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, smoothBack ? lastHealthBar : healthBar, (float) (n15 + barHeight), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                break;
            case 1:
            case 2:
                RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, lastHealthBar, (float) (n15 + barHeight), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
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
    // ---------------------------------------------------------------- hit particles

    /**
     * A pool, not a list. Hits arrive several times a second in a fight and allocating a
     * particle per spark would put a steady stream of short-lived objects through the nursery
     * for something purely decorative. The pool is allocated once and reused; running out just
     * means the oldest effect is not extended, which nobody can see.
     */
    private static final int MAX_HIT_PARTICLES = 64;
    private static final int CIRCLE_SEGMENTS = 16;
    private static final float[] CIRCLE_COS = new float[CIRCLE_SEGMENTS + 1];
    private static final float[] CIRCLE_SIN = new float[CIRCLE_SEGMENTS + 1];

    static {
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            double angle = Math.PI * 2.0 * i / CIRCLE_SEGMENTS;
            CIRCLE_COS[i] = (float) Math.cos(angle);
            CIRCLE_SIN[i] = (float) Math.sin(angle);
        }
    }

    private static final class HitParticle {
        boolean active;
        int kind;
        float x;
        float y;
        float velocityX;
        float velocityY;
        float life;
        float maxLife;
        float size;
        float rotation;
        float spin;
        int rgb;
    }

    private final HitParticle[] hitParticles = new HitParticle[MAX_HIT_PARTICLES];
    private final java.util.Random hitRandom = new java.util.Random();
    private boolean hitSpawnPending;
    private long lastParticleFrame;

    {
        for (int i = 0; i < hitParticles.length; i++) {
            hitParticles[i] = new HitParticle();
        }
    }

    private int hitStyleValue() {
        return hitStyle == null ? HIT_STYLE_FLASH : (int) hitStyle.getInput();
    }

    /** A colour for one particle, by the configured scheme. */
    private int particleColor(int index) {
        int scheme = hitParticleColor == null ? HIT_COLORS_HIT : (int) hitParticleColor.getInput();
        if (scheme == HIT_COLORS_RAINBOW) {
            // Spread over the wheel rather than random per particle, so a burst reads as a set
            // of distinct colours instead of mud.
            float hue = (index * 0.13f + hitRandom.nextFloat() * 0.08f) % 1.0f;
            return Color.HSBtoRGB(hue, 0.72f, 1.0f) & 0xFFFFFF;
        }
        if (scheme == HIT_COLORS_THEME) {
            return Theme.getGradient((int) theme.getInput(), index * 24.0) & 0xFFFFFF;
        }
        int base = hitColor == null ? new Color(255, 92, 92).getRGB() : hitColor.getColor();
        return base & 0xFFFFFF;
    }

    private HitParticle freeParticle() {
        for (HitParticle particle : hitParticles) {
            if (!particle.active) {
                return particle;
            }
        }
        return null;
    }

    private void spawnHitParticles(int style, float centerX, float centerY, float headSize) {
        float strength = Math.max(0.25f, Math.min(1.0f, hitStrength));
        float amount = (float) (hitParticleAmount == null ? 1.0 : hitParticleAmount.getInput());
        int count;
        switch (style) {
            case HIT_STYLE_SPARKS:  count = Math.round((9 + 11 * strength) * amount); break;
            case HIT_STYLE_ORBS:    count = Math.round((5 + 7 * strength) * amount); break;
            case HIT_STYLE_SHATTER: count = Math.round((7 + 9 * strength) * amount); break;
            case HIT_STYLE_EMBERS:  count = Math.round((5 + 6 * strength) * amount); break;
            case HIT_STYLE_RIPPLE:  count = 1; break;
            default: return;
        }

        for (int i = 0; i < count; i++) {
            HitParticle particle = freeParticle();
            if (particle == null) {
                return;
            }
            particle.active = true;
            particle.kind = style;
            particle.rgb = particleColor(i);
            particle.rotation = hitRandom.nextFloat() * (float) Math.PI * 2.0f;

            double angle = hitRandom.nextDouble() * Math.PI * 2.0;
            float speed;

            switch (style) {
                case HIT_STYLE_SPARKS:
                    // Thrown out from just inside the head, fast, and pulled down.
                    speed = headSize * (1.6f + hitRandom.nextFloat() * 2.4f) * strength;
                    particle.x = centerX + (float) Math.cos(angle) * headSize * 0.18f;
                    particle.y = centerY + (float) Math.sin(angle) * headSize * 0.18f;
                    particle.velocityX = (float) Math.cos(angle) * speed;
                    particle.velocityY = (float) Math.sin(angle) * speed;
                    particle.size = headSize * (0.05f + hitRandom.nextFloat() * 0.04f);
                    particle.maxLife = 0.28f + hitRandom.nextFloat() * 0.22f;
                    break;
                case HIT_STYLE_ORBS:
                    // Slower and heavier than sparks so they read as objects, not streaks.
                    speed = headSize * (0.8f + hitRandom.nextFloat() * 1.3f) * strength;
                    particle.x = centerX + (float) Math.cos(angle) * headSize * 0.12f;
                    particle.y = centerY + (float) Math.sin(angle) * headSize * 0.12f;
                    particle.velocityX = (float) Math.cos(angle) * speed;
                    particle.velocityY = (float) Math.sin(angle) * speed - headSize * 0.4f;
                    particle.size = headSize * (0.12f + hitRandom.nextFloat() * 0.09f);
                    particle.maxLife = 0.45f + hitRandom.nextFloat() * 0.35f;
                    break;
                case HIT_STYLE_SHATTER:
                    speed = headSize * (1.2f + hitRandom.nextFloat() * 1.8f) * strength;
                    particle.x = centerX + (float) Math.cos(angle) * headSize * 0.22f;
                    particle.y = centerY + (float) Math.sin(angle) * headSize * 0.22f;
                    particle.velocityX = (float) Math.cos(angle) * speed;
                    particle.velocityY = (float) Math.sin(angle) * speed;
                    particle.size = headSize * (0.09f + hitRandom.nextFloat() * 0.07f);
                    particle.spin = (hitRandom.nextFloat() - 0.5f) * 16.0f;
                    particle.maxLife = 0.35f + hitRandom.nextFloat() * 0.25f;
                    break;
                case HIT_STYLE_EMBERS:
                    // Drift up and out, slowly, with no gravity pulling them back.
                    particle.x = centerX + (hitRandom.nextFloat() - 0.5f) * headSize * 0.8f;
                    particle.y = centerY + (hitRandom.nextFloat() - 0.5f) * headSize * 0.5f;
                    particle.velocityX = (hitRandom.nextFloat() - 0.5f) * headSize * 0.7f;
                    particle.velocityY = -headSize * (0.6f + hitRandom.nextFloat() * 0.8f);
                    particle.size = headSize * (0.06f + hitRandom.nextFloat() * 0.06f);
                    particle.maxLife = 0.6f + hitRandom.nextFloat() * 0.5f;
                    break;
                case HIT_STYLE_RIPPLE:
                default:
                    particle.x = centerX;
                    particle.y = centerY;
                    particle.velocityX = 0.0f;
                    particle.velocityY = 0.0f;
                    particle.size = headSize * 0.5f;
                    particle.maxLife = 0.42f;
                    break;
            }
            particle.life = particle.maxLife;
        }
    }

    /**
     * Advance and draw the particles over the head.
     *
     * Everything is triangles in a single batch, additive except Shatter, which is solid shards
     * and wants ordinary blending. Positions are panel coordinates, so the panel's own pop-in
     * scale carries them without any extra work.
     */
    private void updateAndDrawHitParticles(int headX, int headY, int headSize, int panelAlpha) {
        int style = hitStyleValue();
        boolean enabled = hitEffects != null && hitEffects.isToggled() && style != HIT_STYLE_FLASH;

        long now = System.currentTimeMillis();
        float delta = lastParticleFrame == 0L ? 0.0f : (now - lastParticleFrame) / 1000.0f;
        lastParticleFrame = now;
        // A frame that took a second is a stall, not a second of motion.
        if (delta > 0.1f) delta = 0.1f;
        if (delta < 0.0f) delta = 0.0f;

        if (hitSpawnPending) {
            hitSpawnPending = false;
            if (enabled) {
                spawnHitParticles(style, headX + headSize * 0.5f, headY + headSize * 0.5f, headSize);
            }
        }

        boolean any = false;
        for (HitParticle particle : hitParticles) {
            if (!particle.active) continue;
            if (!enabled) {
                particle.active = false;
                continue;
            }
            particle.life -= delta;
            if (particle.life <= 0.0f) {
                particle.active = false;
                continue;
            }
            particle.x += particle.velocityX * delta;
            particle.y += particle.velocityY * delta;
            particle.rotation += particle.spin * delta;
            switch (particle.kind) {
                case HIT_STYLE_SPARKS:
                    particle.velocityY += headSize * 5.0f * delta;
                    particle.velocityX *= 1.0f - Math.min(0.9f, 2.2f * delta);
                    break;
                case HIT_STYLE_ORBS:
                    particle.velocityY += headSize * 2.4f * delta;
                    break;
                case HIT_STYLE_SHATTER:
                    particle.velocityY += headSize * 4.0f * delta;
                    break;
                case HIT_STYLE_EMBERS:
                    particle.velocityX += (float) Math.sin(particle.life * 9.0f) * headSize * 0.9f * delta;
                    break;
                default:
                    break;
            }
            any = true;
        }
        if (!any) {
            return;
        }

        boolean additive = style != HIT_STYLE_SHATTER;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableAlpha();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.depthMask(false);

        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldRenderer = tessellator.getWorldRenderer();
        worldRenderer.begin(GL11.GL_TRIANGLES, DefaultVertexFormats.POSITION_COLOR);

        float panelFade = Math.max(0, Math.min(255, panelAlpha)) / 255.0f;
        for (HitParticle particle : hitParticles) {
            if (!particle.active) continue;
            float remaining = particle.life / particle.maxLife;
            switch (particle.kind) {
                case HIT_STYLE_SPARKS:
                    emitSpark(worldRenderer, particle, remaining, panelFade);
                    break;
                case HIT_STYLE_SHATTER:
                    emitShard(worldRenderer, particle, remaining, panelFade);
                    break;
                case HIT_STYLE_RIPPLE:
                    emitRipple(worldRenderer, particle, remaining, panelFade);
                    break;
                case HIT_STYLE_EMBERS: {
                    // Flicker: an ember that holds a constant brightness looks like a dot.
                    float flicker = 0.72f + 0.28f * (float) Math.sin(particle.life * 21.0f + particle.rotation);
                    emitGlow(worldRenderer, particle.x, particle.y, particle.size * (0.6f + 0.4f * remaining),
                            particle.rgb, remaining * remaining * flicker * panelFade);
                    break;
                }
                case HIT_STYLE_ORBS:
                default: {
                    float fade = remaining * remaining;
                    emitGlow(worldRenderer, particle.x, particle.y, particle.size, particle.rgb, fade * 0.55f * panelFade);
                    // A tighter, brighter core inside the halo is what makes it read as a ball
                    // with light in it rather than a smudge.
                    emitGlow(worldRenderer, particle.x, particle.y, particle.size * 0.42f, particle.rgb, fade * panelFade);
                    break;
                }
            }
        }

        tessellator.draw();

        GlStateManager.depthMask(true);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableAlpha();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /** A radial blob: full alpha at the centre, nothing at the rim. */
    private void emitGlow(WorldRenderer worldRenderer, float centerX, float centerY,
                          float radius, int rgb, float alpha) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * 255.0f);
        if (a <= 1 || radius <= 0.0f) return;
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            worldRenderer.pos(centerX, centerY, 0.0).color(red, green, blue, a).endVertex();
            worldRenderer.pos(centerX + CIRCLE_COS[i] * radius, centerY + CIRCLE_SIN[i] * radius, 0.0)
                    .color(red, green, blue, 0).endVertex();
            worldRenderer.pos(centerX + CIRCLE_COS[i + 1] * radius, centerY + CIRCLE_SIN[i + 1] * radius, 0.0)
                    .color(red, green, blue, 0).endVertex();
        }
    }

    /** A streak along the direction of travel, bright and wide at the head, gone at the tail. */
    private void emitSpark(WorldRenderer worldRenderer, HitParticle particle, float remaining, float panelFade) {
        float speed = (float) Math.sqrt(particle.velocityX * particle.velocityX
                + particle.velocityY * particle.velocityY);
        if (speed < 0.0001f) return;
        float dirX = particle.velocityX / speed;
        float dirY = particle.velocityY / speed;
        float length = particle.size * (2.2f + speed * 0.02f);
        float halfWidth = particle.size * 0.5f;

        float tailX = particle.x - dirX * length;
        float tailY = particle.y - dirY * length;
        float normalX = -dirY * halfWidth;
        float normalY = dirX * halfWidth;

        int a = Math.round(Math.max(0.0f, Math.min(1.0f, remaining * panelFade)) * 255.0f);
        if (a <= 1) return;
        int red = (particle.rgb >> 16) & 0xFF;
        int green = (particle.rgb >> 8) & 0xFF;
        int blue = particle.rgb & 0xFF;

        // Two triangles making a wedge: full width and full alpha at the head, a point at the tail.
        worldRenderer.pos(particle.x + normalX, particle.y + normalY, 0.0).color(red, green, blue, a).endVertex();
        worldRenderer.pos(particle.x - normalX, particle.y - normalY, 0.0).color(red, green, blue, a).endVertex();
        worldRenderer.pos(tailX, tailY, 0.0).color(red, green, blue, 0).endVertex();

        worldRenderer.pos(particle.x + normalX * 0.35f, particle.y + normalY * 0.35f, 0.0)
                .color(255, 255, 255, Math.round(a * 0.7f)).endVertex();
        worldRenderer.pos(particle.x - normalX * 0.35f, particle.y - normalY * 0.35f, 0.0)
                .color(255, 255, 255, Math.round(a * 0.7f)).endVertex();
        worldRenderer.pos(tailX * 0.5f + particle.x * 0.5f, tailY * 0.5f + particle.y * 0.5f, 0.0)
                .color(red, green, blue, 0).endVertex();
    }

    /** A solid rotating shard. */
    private void emitShard(WorldRenderer worldRenderer, HitParticle particle, float remaining, float panelFade) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, remaining * panelFade)) * 255.0f);
        if (a <= 1) return;
        int red = (particle.rgb >> 16) & 0xFF;
        int green = (particle.rgb >> 8) & 0xFF;
        int blue = particle.rgb & 0xFF;
        float size = particle.size * (0.4f + 0.6f * remaining);
        for (int corner = 0; corner < 3; corner++) {
            double angle = particle.rotation + corner * Math.PI * 2.0 / 3.0;
            worldRenderer.pos(particle.x + Math.cos(angle) * size, particle.y + Math.sin(angle) * size, 0.0)
                    .color(red, green, blue, a).endVertex();
        }
    }

    /** An expanding ring with a soft edge on both sides. */
    private void emitRipple(WorldRenderer worldRenderer, HitParticle particle, float remaining, float panelFade) {
        float grown = 1.0f - remaining;
        float radius = particle.size * (0.35f + 1.25f * grown);
        float thickness = particle.size * 0.16f * (0.4f + 0.6f * remaining);
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, remaining * remaining * panelFade)) * 255.0f);
        if (a <= 1) return;
        int red = (particle.rgb >> 16) & 0xFF;
        int green = (particle.rgb >> 8) & 0xFF;
        int blue = particle.rgb & 0xFF;

        for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            float innerX0 = particle.x + CIRCLE_COS[i] * (radius - thickness);
            float innerY0 = particle.y + CIRCLE_SIN[i] * (radius - thickness);
            float innerX1 = particle.x + CIRCLE_COS[i + 1] * (radius - thickness);
            float innerY1 = particle.y + CIRCLE_SIN[i + 1] * (radius - thickness);
            float midX0 = particle.x + CIRCLE_COS[i] * radius;
            float midY0 = particle.y + CIRCLE_SIN[i] * radius;
            float midX1 = particle.x + CIRCLE_COS[i + 1] * radius;
            float midY1 = particle.y + CIRCLE_SIN[i + 1] * radius;
            float outerX0 = particle.x + CIRCLE_COS[i] * (radius + thickness);
            float outerY0 = particle.y + CIRCLE_SIN[i] * (radius + thickness);
            float outerX1 = particle.x + CIRCLE_COS[i + 1] * (radius + thickness);
            float outerY1 = particle.y + CIRCLE_SIN[i + 1] * (radius + thickness);

            rippleQuad(worldRenderer, innerX0, innerY0, innerX1, innerY1, midX1, midY1, midX0, midY0,
                    red, green, blue, 0, a);
            rippleQuad(worldRenderer, midX0, midY0, midX1, midY1, outerX1, outerY1, outerX0, outerY0,
                    red, green, blue, a, 0);
        }
    }

    private void rippleQuad(WorldRenderer worldRenderer,
                            float x0, float y0, float x1, float y1,
                            float x2, float y2, float x3, float y3,
                            int red, int green, int blue, int alphaNear, int alphaFar) {
        worldRenderer.pos(x0, y0, 0.0).color(red, green, blue, alphaNear).endVertex();
        worldRenderer.pos(x1, y1, 0.0).color(red, green, blue, alphaNear).endVertex();
        worldRenderer.pos(x2, y2, 0.0).color(red, green, blue, alphaFar).endVertex();

        worldRenderer.pos(x0, y0, 0.0).color(red, green, blue, alphaNear).endVertex();
        worldRenderer.pos(x2, y2, 0.0).color(red, green, blue, alphaFar).endVertex();
        worldRenderer.pos(x3, y3, 0.0).color(red, green, blue, alphaFar).endVertex();
    }

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
            if (skin == null || !skinIsLoaded(skin)) {
                skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(player.getUniqueID());
            }
            boolean depthEnabled = GL11.glIsEnabled(2929);
            boolean blendEnabled = GL11.glIsEnabled(3042);
            boolean cullEnabled = GL11.glIsEnabled(2884);
            boolean depthMask = GL11.glGetBoolean(2930);
            try {
                RenderUtils.prepareGuiTextureRenderState();
                GlStateManager.disableCull();
                mc.getTextureManager().bindTexture(skin);
                GlStateManager.color(1.0f, 1.0f, 1.0f, (float) alpha / 255.0f);
                int style = headStyle == null ? HEAD_STYLE_3D : (int) headStyle.getInput();
                // 2D is the rounded avatar, Flat is the square pixel-crisp one. Naming them the
                // other way round was mine and it did not match what anyone expects the words to
                // mean, so the renderers are swapped rather than the labels: a saved selection
                // now produces the look its name promises.
                if (style == HEAD_STYLE_FLAT) {
                    drawHead2D(x, y, width, height, alpha);
                }
                else if (style == HEAD_STYLE_2D) {
                    float cornerRadius = Math.max(2.0f, (float) Math.min(width, height) * 0.14f);
                    drawRoundedSkinLayer(x, y, width, height, cornerRadius, 8.0f, 8.0f, alpha);
                    // Hat sits slightly proud of the face so the two layers read apart instead
                    // of exactly overprinting each other.
                    float grow = Math.min(width, height) * 0.055f;
                    drawRoundedSkinLayer(x - grow, y - grow, width + grow * 2.0f,
                            height + grow * 2.0f, cornerRadius, 40.0f, 8.0f, alpha);
                }
                else {
                    drawHeadCube(x, y, width, height, alpha);
                }
            } finally {
                RenderUtils.restoreGuiRenderState(depthEnabled, blendEnabled, depthMask);
                if (cullEnabled) GlStateManager.enableCull();
                else GlStateManager.disableCull();
            }
        } catch (Exception ignored) {}
    }
    /**
     * Whether the skin texture actually exists yet.
     *
     * getLocationSkin returns a location for a skin that may still be downloading; the texture
     * object is only registered once it arrives.
     */
    /**
     * A flat avatar head: the 8x8 face with the hat layer over it, square and pixel-crisp.
     *
     * Distinct from Flat, which rounds its corners and grows the hat slightly. This one keeps
     * the skin's own pixel grid: nearest sampling and whole-pixel edges, so an 8x8 face scaled
     * to the head box stays sharp instead of being smeared by the linear filter the GUI is
     * otherwise left in. The filter is put back afterwards because the skin texture is shared
     * with the world renderer, which does want it smooth.
     */
    private void drawHead2D(int x, int y, int width, int height, int alpha) {
        int previousMin = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER);
        int previousMag = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        try {
            GlStateManager.color(1.0f, 1.0f, 1.0f, alpha / 255.0f);
            drawSkinQuad(x, y, width, height, 8.0f, 8.0f);
            drawSkinQuad(x, y, width, height, 40.0f, 8.0f);
        }
        finally {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, previousMin);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, previousMag);
        }
    }

    private void drawSkinQuad(float x, float y, float width, float height,
                              float textureU, float textureV) {
        float u0 = textureU / SKIN_TEXTURE_SIZE;
        float v0 = textureV / SKIN_TEXTURE_SIZE;
        float u1 = (textureU + 8.0f) / SKIN_TEXTURE_SIZE;
        float v1 = (textureV + 8.0f) / SKIN_TEXTURE_SIZE;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(u0, v0); GL11.glVertex2f(x, y);
        GL11.glTexCoord2f(u0, v1); GL11.glVertex2f(x, y + height);
        GL11.glTexCoord2f(u1, v1); GL11.glVertex2f(x + width, y + height);
        GL11.glTexCoord2f(u1, v0); GL11.glVertex2f(x + width, y);
        GL11.glEnd();
    }

    private boolean skinIsLoaded(ResourceLocation skin) {
        try {
            return mc.getTextureManager().getTexture(skin) != null;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private void drawHeadPlaceholder(int x, int y, int width, int height, int alpha) {
        float radius = Math.max(2.0f, Math.min(width, height) * 0.14f);
        int shade = Math.max(0, Math.min(255, Math.round(alpha * 0.55f)));
        RoundedUtils.drawRound(x, y, width, height, radius, new Color(26, 26, 34, shade));
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
        if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled()) {
            EntityLivingBase auraTarget = ModuleManager.killAura.getHudTarget();
            if (auraTarget != null) return auraTarget;
        }
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
        hitSpawnPending = false;
        for (HitParticle particle : hitParticles) {
            particle.active = false;
        }
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
