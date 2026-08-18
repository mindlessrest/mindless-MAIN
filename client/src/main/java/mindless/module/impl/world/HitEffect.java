package mindless.module.impl.world;

import mindless.event.AttackEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.impl.render.Slow;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import mindless.utility.shader.ShaderUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lightweight Minecraft recreations of the Juju/Roblox hit feedback effects.
 * Effects share one confirmed-hit record and never scan the entity list.
 */
public class HitEffect extends Module {
    private static HitEffect instance;
    private static final int MAX_PENDING = 8;
    private static final int MAX_VISUALS = 10;
    private static final long CONFIRM_WINDOW_MS = 450L;
    private static final long MAX_SYNC_WAIT_MS = 4000L;
    private static final Random RANDOM = new Random();
    private static int lastDamageSector = -1;
    private static final String[] SOUNDS = {"Orb", "Pling", "Bow", "Break", "Glass Shatter"};
    private static final String[] PARTICLES = {"Critical", "Magic", "Spark", "Smoke"};
    private static final String[] SOUND_IDS = {"random.orb", "note.pling", "random.successful_hit", "random.break",
            "mindless:experimental.glass"};
    private static final int[] PARTICLE_IDS = {9, 10, 3, 11};
    private static final String[] DAMAGE_POSITIONS = {"Centered", "Random above"};
    private static final String[] DAMAGE_ANIMATIONS = {"Rise", "Bounce", "Pop", "Drift", "Float"};
    private static final String[] ITEM_CHAM_STYLES = {"Tint", "Flat", "Glow", "Pulse", "Rainbow", "Ghost", "Wireframe", "Spin", "Wave", "Gradient"};
    private static final String[] GRADIENT_AXES = {"Vertical", "Horizontal"};
    private static final String[] GRADIENT_DIRECTIONS = {"Forward", "Reverse"};

    private final ButtonSetting hitSound;
    private final SliderSetting soundType;
    private final SliderSetting soundVolume;
    private final SliderSetting soundPitch;
    private final ButtonSetting hitParticles;
    private final SliderSetting particleType;
    private final SliderSetting particleCount;
    private final ButtonSetting particleRainbow;
    private final ColorSetting particleColor;
    private final ButtonSetting screenOverlay;
    private final SliderSetting overlayOpacity;
    private final ButtonSetting damageNumbers;
    private final ButtonSetting marker2d;
    private final ButtonSetting marker3d;
    private final ButtonSetting skeleton;
    private final ButtonSetting hitChams;
    private final SliderSetting damagePosition;
    private final SliderSetting damageAnimation;
    private final SliderSetting duration;
    private final SliderSetting lineWidth;
    private final ColorSetting color;
    private final ColorSetting overlayColor;
    private final ColorSetting damageColor;
    private final ColorSetting marker2dColor;
    private final ColorSetting marker3dColor;
    private final ColorSetting skeletonColor;
    private final ColorSetting hitChamsColor;
    private final ButtonSetting killColorOverride;
    private final ColorSetting killColor;
    private final ButtonSetting itemChams;
    private final SliderSetting itemChamStyle;
    private final ColorSetting itemChamColor;
    private final ColorSetting itemChamColor2;
    private final SliderSetting itemChamOpacity;
    private final SliderSetting itemChamSpeed;
    private final SliderSetting itemChamStrength;
    private final SliderSetting itemGradientAxis;
    private final SliderSetting itemGradientDirection;
    private final SliderSetting itemGradientLength;
    private final ButtonSetting itemChamOthers;
    private ShaderUtils itemGradientShader;
    private boolean itemGradientShaderActive;
    private boolean itemGradientShaderFailed;

    private final Map<Long, PendingHit> pending = new LinkedHashMap<>();
    private final List<HitVisual> visuals = new ArrayList<>();
    private long pendingSequence = Long.MIN_VALUE;

    public HitEffect() {
        super("Hit Effect", category.fun, 0);
        instance = this;

        GroupSetting audio = new GroupSetting("Hit sound");
        registerSetting(audio);
        registerSetting(hitSound = new ButtonSetting(audio, "Enabled", true));
        registerSetting(soundType = new SliderSetting(audio, "Sound", 0, SOUNDS));
        registerSetting(soundVolume = new SliderSetting(audio, "Volume", 0.45, 0.05, 1.0, 0.05));
        registerSetting(soundPitch = new SliderSetting(audio, "Pitch", 1.15, 0.5, 2.0, 0.05));

        GroupSetting particles = new GroupSetting("Particles");
        registerSetting(particles);
        registerSetting(hitParticles = new ButtonSetting(particles, "Enabled", true));
        registerSetting(particleType = new SliderSetting(particles, "Style", 0, PARTICLES));
        registerSetting(particleCount = new SliderSetting(particles, "Amount", 8, 1, 24, 1));
        registerSetting(particleRainbow = new ButtonSetting(particles, "Rainbow color", false));
        registerSetting(particleColor = new ColorSetting(particles, "Color", 110, 205, 255));

        GroupSetting feedback = new GroupSetting("Feedback");
        registerSetting(feedback);
        registerSetting(screenOverlay = new ButtonSetting(feedback, "Screen overlay", true));
        registerSetting(overlayOpacity = new SliderSetting(feedback, "Overlay opacity", "%", 12, 2, 35, 1));
        registerSetting(damageNumbers = new ButtonSetting(feedback, "Damage numbers", true));
        registerSetting(marker2d = new ButtonSetting(feedback, "2D hitmarker", true));
        registerSetting(marker3d = new ButtonSetting(feedback, "3D hitmarker", true));
        registerSetting(skeleton = new ButtonSetting(feedback, "Skeleton flash", true));
        registerSetting(hitChams = new ButtonSetting(feedback, "Hit chams", true));
        registerSetting(damagePosition = new SliderSetting(feedback, "Damage position", 0, DAMAGE_POSITIONS));
        registerSetting(damageAnimation = new SliderSetting(feedback, "Damage animation", 0, DAMAGE_ANIMATIONS));

        GroupSetting itemMaterial = new GroupSetting("Item chams");
        registerSetting(itemMaterial);
        registerSetting(itemChams = new ButtonSetting(itemMaterial, "Enabled", false));
        registerSetting(itemChamStyle = new SliderSetting(itemMaterial, "Style", 3, ITEM_CHAM_STYLES));
        registerSetting(itemChamColor = new ColorSetting(itemMaterial, "Primary color", 110, 205, 255, 210));
        registerSetting(itemChamColor2 = new ColorSetting(itemMaterial, "Secondary color", 190, 90, 255, 210));
        registerSetting(itemChamOpacity = new SliderSetting(itemMaterial, "Opacity", "%", 75, 10, 100, 5));
        registerSetting(itemChamSpeed = new SliderSetting(itemMaterial, "Animation speed", 1.0, 0.2, 3.0, 0.1));
        registerSetting(itemChamStrength = new SliderSetting(itemMaterial, "Animation strength", "%", 45, 5, 100, 5));
        registerSetting(itemGradientAxis = new SliderSetting(itemMaterial, "Gradient axis", 0, GRADIENT_AXES));
        registerSetting(itemGradientDirection = new SliderSetting(itemMaterial, "Gradient direction", 0, GRADIENT_DIRECTIONS));
        registerSetting(itemGradientLength = new SliderSetting(itemMaterial, "Gradient length", 1.0, 0.5, 5.0, 0.1));
        registerSetting(itemChamOthers = new ButtonSetting(itemMaterial, "Other players", false));

        GroupSetting appearance = new GroupSetting("Appearance");
        registerSetting(appearance);
        registerSetting(duration = new SliderSetting(appearance, "Duration", " ms", 520, 160, 1200, 20));
        registerSetting(lineWidth = new SliderSetting(appearance, "Line width", 1.6, 1.0, 3.0, 0.1));
        registerSetting(color = new ColorSetting(appearance, "Default color", 110, 205, 255));
        registerSetting(overlayColor = new ColorSetting(appearance, "Overlay color", 110, 205, 255));
        registerSetting(damageColor = new ColorSetting(appearance, "Damage text color", 110, 205, 255));
        registerSetting(marker2dColor = new ColorSetting(appearance, "2D marker color", 255, 255, 255));
        registerSetting(marker3dColor = new ColorSetting(appearance, "3D marker color", 110, 205, 255));
        registerSetting(skeletonColor = new ColorSetting(appearance, "Skeleton color", 110, 205, 255));
        registerSetting(hitChamsColor = new ColorSetting(appearance, "Hit chams color", 110, 205, 255));
        registerSetting(killColorOverride = new ButtonSetting(appearance, "Kill color override", true));
        registerSetting(killColor = new ColorSetting(appearance, "Kill color", 255, 90, 110));
    }

    @Override
    public void onDisable() {
        pending.clear();
        visuals.clear();
        pendingSequence = Long.MIN_VALUE;
        if (itemGradientShaderActive && itemGradientShader != null) itemGradientShader.unload();
        itemGradientShaderActive = false;
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        if (!isEnabled() || !Utils.nullCheck() || event.isCanceled()
                || event.attacker != mc.thePlayer || !(event.target instanceof EntityLivingBase)) {
            return;
        }
        EntityLivingBase target = (EntityLivingBase) event.target;
        long now = System.currentTimeMillis();
        long syncCycle = Slow.getHitFeedbackCycle();
        long feedbackAt = syncCycle >= 0L ? 0L : now + Slow.getHitFeedbackDelayMillis();
        PendingHit hit = new PendingHit(target, totalHealth(target), target.hurtTime, now, feedbackAt, syncCycle);
        if (syncCycle < 0L && feedbackAt <= now) {
            hit.visual = createHitFeedback(target, now);
        }
        // Per-swing mode deliberately replaces additional clicks assigned to
        // the same slowed visual cycle. Every-hit mode uses unique keys.
        long key = syncCycle >= 0L ? syncCycle : pendingSequence++;
        pending.put(key, hit);
        while (pending.size() > MAX_PENDING) {
            pending.remove(pending.keySet().iterator().next());
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !isEnabled()) return;
        if (!Utils.nullCheck()) {
            pending.clear();
            visuals.clear();
            return;
        }

        long now = System.currentTimeMillis();
        flushDueFeedback(now);
        Iterator<Map.Entry<Long, PendingHit>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingHit hit = iterator.next().getValue();
            long expiryBase = hit.visual != null ? hit.visual.createdAt
                    : (hit.syncCycle >= 0L ? hit.createdAt : Math.max(hit.createdAt, hit.feedbackAt));
            long expiryWindow = hit.visual == null && hit.syncCycle >= 0L ? MAX_SYNC_WAIT_MS : CONFIRM_WINDOW_MS;
            if (now - expiryBase > expiryWindow) {
                iterator.remove();
                continue;
            }

            float currentHealth = totalHealth(hit.target);
            boolean healthChanged = currentHealth < hit.health - 0.01F;
            boolean hurtStarted = hit.target.hurtTime > hit.hurtTime && hit.target.hurtTime > 0;
            if (healthChanged || hurtStarted) {
                hit.damage = Math.max(0.0F, hit.health - currentHealth);
                hit.killed = hit.target.isDead || hit.target.getHealth() <= 0.0F;
                if (hit.visual != null) {
                    hit.visual.damage = hit.damage;
                    hit.visual.killed = hit.killed;
                    iterator.remove();
                }
            }
        }
        pruneVisuals(now);
    }

    private HitVisual createHitFeedback(EntityLivingBase target, long now) {
        AxisAlignedBB box = target.getEntityBoundingBox();
        HitVisual visual = new HitVisual(target.posX, box.minY, target.posZ,
                Math.max(0.35F, target.width), Math.max(0.8F, target.height),
                target.rotationYaw, 0.0F, false, now);
        visuals.add(visual);
        while (visuals.size() > MAX_VISUALS) visuals.remove(0);

        if (hitSound.isToggled()) {
            int index = clampIndex((int) soundType.getInput(), SOUND_IDS.length);
            mc.thePlayer.playSound(SOUND_IDS[index], (float) soundVolume.getInput(), (float) soundPitch.getInput());
        }
        if (hitParticles.isToggled()) spawnParticles(target);
        return visual;
    }

    private void spawnParticles(EntityLivingBase target) {
        int id = PARTICLE_IDS[clampIndex((int) particleType.getInput(), PARTICLE_IDS.length)];
        int count = (int) particleCount.getInput();
        AxisAlignedBB box = target.getEntityBoundingBox();
        for (int i = 0; i < count; i++) {
            double ox = (RANDOM.nextDouble() - 0.5D) * target.width;
            double oy = RANDOM.nextDouble() * target.height;
            double oz = (RANDOM.nextDouble() - 0.5D) * target.width;
            EntityFX particle = mc.effectRenderer.spawnEffectParticle(id, target.posX + ox, box.minY + oy, target.posZ + oz,
                    ox * 0.08D, 0.025D + RANDOM.nextDouble() * 0.035D, oz * 0.08D);
            if (particle != null) {
                int rgb = particleRainbow.isToggled()
                        ? java.awt.Color.HSBtoRGB(RANDOM.nextFloat(), 0.72F, 1.0F)
                        : particleColor.getRGB();
                particle.setRBGColorF((rgb >> 16 & 255) / 255.0F,
                        (rgb >> 8 & 255) / 255.0F, (rgb & 255) / 255.0F);
            }
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL || visuals.isEmpty()) return;
        HitVisual hit = visuals.get(visuals.size() - 1);
        float fade = fade(hit, System.currentTimeMillis());
        if (fade <= 0.0F) return;
        ScaledResolution sr = event.resolution;

        if (screenOverlay.isToggled()) {
            int rgb = resolveColor(hit, overlayColor);
            int alpha = (int) (255.0D * overlayOpacity.getInput() / 100.0D * fade);
            RenderUtils.drawRect(0, 0, sr.getScaledWidth(), sr.getScaledHeight(), (alpha << 24) | rgb);
        }
        if (marker2d.isToggled()) {
            int rgb = resolveColor(hit, marker2dColor);
            double cx = sr.getScaledWidth() * 0.5D;
            double cy = sr.getScaledHeight() * 0.5D;
            int argb = ((int) (255 * fade) << 24) | rgb;
            double gap = 3.0D;
            double size = 7.0D;
            drawScreenLine(cx - gap, cy - gap, cx - size, cy - size, argb);
            drawScreenLine(cx + gap, cy - gap, cx + size, cy - size, argb);
            drawScreenLine(cx - gap, cy + gap, cx - size, cy + size, argb);
            drawScreenLine(cx + gap, cy + gap, cx + size, cy + size, argb);
        }
    }

    private void drawScreenLine(double x1, double y1, double x2, double y2, int argb) {
        float a = (argb >>> 24) / 255.0F;
        float r = (argb >> 16 & 255) / 255.0F;
        float g = (argb >> 8 & 255) / 255.0F;
        float b = (argb & 255) / 255.0F;
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GL11.glLineWidth((float) lineWidth.getInput());
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2d(x1, y1);
        GL11.glVertex2d(x2, y2);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }

    @SubscribeEvent
    public void onWorldRender(RenderWorldLastEvent event) {
        if (!Utils.nullCheck()) return;
        long now = System.currentTimeMillis();
        // RenderWorldLast runs every rendered frame, avoiding the up-to-50 ms
        // timing jitter caused by releasing synchronized feedback on ticks.
        flushDueFeedback(now);
        if (visuals.isEmpty()) return;
        RenderManager rm = mc.getRenderManager();
        for (HitVisual hit : visuals) {
            float fade = fade(hit, now);
            if (fade <= 0.0F) continue;
            if (hitChams.isToggled()) renderChams(hit, rm, resolveColor(hit, hitChamsColor), fade);
            if (skeleton.isToggled()) renderSkeleton(hit, rm, resolveColor(hit, skeletonColor), fade);
            if (marker3d.isToggled()) renderWorldMarker(hit, rm, resolveColor(hit, marker3dColor), fade);
            if (damageNumbers.isToggled()) renderDamage(hit, rm, resolveColor(hit, damageColor), fade);
        }
    }

    private void flushDueFeedback(long now) {
        for (PendingHit hit : pending.values()) {
            if (hit.visual != null) continue;
            boolean due = hit.syncCycle >= 0L
                    ? Slow.hasReachedHitFeedbackCycle(hit.syncCycle)
                    : now >= hit.feedbackAt;
            if (!due) continue;
            hit.visual = createHitFeedback(hit.target, now);
            hit.visual.damage = hit.damage;
            hit.visual.killed = hit.killed;
        }
    }

    private void renderChams(HitVisual h, RenderManager rm, int rgb, float fade) {
        double x = h.x - rm.viewerPosX;
        double y = h.y - rm.viewerPosY;
        double z = h.z - rm.viewerPosZ;
        float r = (rgb >> 16 & 255) / 255.0F;
        float g = (rgb >> 8 & 255) / 255.0F;
        float b = (rgb & 255) / 255.0F;
        float w = h.width;
        float height = h.height;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        float alpha = 0.13F * fade;
        drawPart(x, y + height * 0.72, z, w * 0.55, height * 0.28, w * 0.55, r, g, b, alpha); // head
        drawPart(x, y + height * 0.35, z, w * 0.72, height * 0.38, w * 0.38, r, g, b, alpha); // torso
        drawPart(x - w * 0.46, y + height * 0.34, z, w * 0.18, height * 0.4, w * 0.2, r, g, b, alpha); // arms
        drawPart(x + w * 0.46, y + height * 0.34, z, w * 0.18, height * 0.4, w * 0.2, r, g, b, alpha);
        drawPart(x - w * 0.2, y, z, w * 0.25, height * 0.36, w * 0.28, r, g, b, alpha); // legs
        drawPart(x + w * 0.2, y, z, w * 0.25, height * 0.36, w * 0.28, r, g, b, alpha);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.popMatrix();
    }

    private void drawPart(double cx, double minY, double cz, double width, double height, double depth,
                          float r, float g, float b, float alpha) {
        RenderUtils.drawBoundingBox(
            cx - width / 2.0,  minY,          cz - depth / 2.0,
            cx + width / 2.0,  minY + height, cz + depth / 2.0,
            r, g, b, alpha);
    }

    private void renderSkeleton(HitVisual h, RenderManager rm, int rgb, float fade) {
        double x = h.x - rm.viewerPosX;
        double y = h.y - rm.viewerPosY;
        double z = h.z - rm.viewerPosZ;
        double half = h.width * 0.48D;
        double rad = Math.toRadians(-h.yaw);
        double rx = Math.cos(rad), rz = Math.sin(rad);
        double shoulder = y + h.height * 0.72D;
        double hip = y + h.height * 0.38D;

        beginWorldLines(rgb, 0.9F * fade);
        line(x, y + h.height * 0.96, z, x, shoulder, z);
        line(x, shoulder, z, x, hip, z);
        line(x - rx * half, shoulder, z - rz * half, x + rx * half, shoulder, z + rz * half);
        line(x - rx * half, shoulder, z - rz * half, x - rx * half * 1.25, hip, z - rz * half * 1.25);
        line(x + rx * half, shoulder, z + rz * half, x + rx * half * 1.25, hip, z + rz * half * 1.25);
        line(x, hip, z, x - rx * half * 0.35, y, z - rz * half * 0.35);
        line(x, hip, z, x + rx * half * 0.35, y, z + rz * half * 0.35);
        endWorldLines();
    }

    private void renderWorldMarker(HitVisual h, RenderManager rm, int rgb, float fade) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(h.x - rm.viewerPosX, h.y + h.height * 0.58 - rm.viewerPosY, h.z - rm.viewerPosZ);
        GlStateManager.rotate(-rm.playerViewY, 0, 1, 0);
        GlStateManager.rotate(rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1), 1, 0, 0);
        beginWorldLines(rgb, fade);
        double gap = 0.055D, size = 0.17D;
        line(-gap, -gap, 0, -size, -size, 0);
        line(gap, -gap, 0, size, -size, 0);
        line(-gap, gap, 0, -size, size, 0);
        line(gap, gap, 0, size, size, 0);
        endWorldLines();
        GlStateManager.popMatrix();
    }

    private void renderDamage(HitVisual h, RenderManager rm, int rgb, float fade) {
        String text = h.damage > 0.01F ? "-" + formatDamage(h.damage) : "HIT";
        float progress = 1.0F - fade;
        int motion = clampIndex((int) damageAnimation.getInput(), DAMAGE_ANIMATIONS.length);
        double motionX = 0.0D;
        double motionY;
        float scaleX = 1.0F;
        float scaleY = 1.0F;
        float tilt = 0.0F;
        switch (motion) {
            case 1: // Drop, contact, a full bounce, then a smaller settling bounce.
                if (progress < 0.46F) {
                    float t = progress / 0.46F;
                    motionY = 0.62D * (1.0D - t * t);
                } else if (progress < 0.76F) {
                    float t = (progress - 0.46F) / 0.30F;
                    motionY = 0.27D * 4.0D * t * (1.0D - t);
                } else if (progress < 0.94F) {
                    float t = (progress - 0.76F) / 0.18F;
                    motionY = 0.105D * 4.0D * t * (1.0D - t);
                } else {
                    motionY = 0.0D;
                }
                float firstImpact = Math.max(0.0F, 1.0F - Math.abs(progress - 0.46F) / 0.055F);
                float secondImpact = Math.max(0.0F, 1.0F - Math.abs(progress - 0.76F) / 0.04F);
                float squash = Math.max(firstImpact, secondImpact * 0.65F);
                scaleX = 1.0F + squash * 0.13F;
                scaleY = 1.0F - squash * 0.20F;
                tilt = (float) (Math.sin(progress * Math.PI * 3.0D) * (1.0F - progress)
                        * 7.0F * h.driftX);
                break;
            case 2:
                motionY = 0.08D + progress * 0.10D;
                scaleX = scaleY = 0.62F + 0.38F * (float) Math.sin(
                        Math.min(1.0F, progress / 0.28F) * Math.PI / 2.0D);
                break;
            case 3:
                motionX = progress * h.driftX * 0.45D;
                motionY = progress * 0.26D;
                tilt = (float) (h.driftX * progress * 5.0D);
                break;
            case 4:
                motionY = Math.sin(progress * Math.PI) * 0.32D;
                break;
            default:
                motionY = progress * 0.42D;
                break;
        }
        double randomX = (int) damagePosition.getInput() == 1 ? h.damageOffsetX : 0.0D;
        double randomY = (int) damagePosition.getInput() == 1 ? h.damageOffsetY : 0.0D;
        float scale = 0.018F;
        GlStateManager.pushMatrix();
        GlStateManager.translate(h.x - rm.viewerPosX,
                h.y + h.height + 0.15D + motionY - rm.viewerPosY,
                h.z - rm.viewerPosZ);
        GlStateManager.rotate(-rm.playerViewY, 0, 1, 0);
        GlStateManager.rotate(rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1), 1, 0, 0);
        // Camera-local placement produces visibly different positions instead
        // of different world points that project onto the same screen pixels.
        GlStateManager.translate(randomX + motionX, randomY, 0.0D);
        GlStateManager.rotate(tilt, 0.0F, 0.0F, 1.0F);
        GlStateManager.scale(-scale * scaleX, -scale * scaleY, scale);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        int alpha = (int) (255 * fade);
        int argb = (alpha << 24) | rgb;
        int shadow = (Math.min(alpha, 210) << 24);
        int half = mc.fontRendererObj.getStringWidth(text) / 2;
        mc.fontRendererObj.drawString(text, -half + 1, 1, shadow, false);
        mc.fontRendererObj.drawString(text, -half, 0, argb, false);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.popMatrix();
    }

    private void beginWorldLines(int rgb, float alpha) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GL11.glLineWidth((float) lineWidth.getInput());
        GL11.glColor4f((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F,
                (rgb & 255) / 255.0F, Math.max(0, Math.min(1, alpha)));
        GL11.glBegin(GL11.GL_LINES);
    }

    private void line(double x1, double y1, double z1, double x2, double y2, double z2) {
        GL11.glVertex3d(x1, y1, z1);
        GL11.glVertex3d(x2, y2, z2);
    }

    private void endWorldLines() {
        GL11.glEnd();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
    }

    private void pruneVisuals(long now) {
        long lifetime = (long) duration.getInput();
        visuals.removeIf(hit -> now - hit.createdAt >= lifetime);
    }

    private float fade(HitVisual hit, long now) {
        return Math.max(0.0F, 1.0F - (now - hit.createdAt) / (float) duration.getInput());
    }

    private int resolveColor(HitVisual hit, ColorSetting componentColor) {
        if (hit.killed && killColorOverride.isToggled()) return killColor.getRGB();
        return componentColor == null ? color.getRGB() : componentColor.getRGB();
    }

    /** Applies a lightweight material/animation around the held-item draw only. */
    public static boolean beginItemChams(EntityLivingBase entity, ItemStack stack,
                                         ItemCameraTransforms.TransformType transform) {
        HitEffect mod = instance;
        if (mod == null || !mod.isEnabled() || !mod.itemChams.isToggled()
                || entity == null || stack == null) {
            return false;
        }
        boolean heldTransform = transform == ItemCameraTransforms.TransformType.FIRST_PERSON
                || transform == ItemCameraTransforms.TransformType.THIRD_PERSON;
        if (!heldTransform || (entity != mc.thePlayer && !mod.itemChamOthers.isToggled())) {
            return false;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);

        int style = clampIndex((int) mod.itemChamStyle.getInput(), ITEM_CHAM_STYLES.length);
        double time = System.currentTimeMillis() / 1000.0D * mod.itemChamSpeed.getInput();
        float wave = (float) ((Math.sin(time * Math.PI * 2.0D) + 1.0D) * 0.5D);
        int rgb = mixColors(mod.itemChamColor.getRGB(), mod.itemChamColor2.getRGB(), wave);
        if (style == 4) rgb = Utils.getChroma(2L, 0L);
        float alpha = (float) (mod.itemChamOpacity.getInput() / 100.0D);
        float strength = (float) (mod.itemChamStrength.getInput() / 100.0D);

        switch (style) {
            case 1: // Flat material
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                break;
            case 2: // Additive glow without a framebuffer pass
                GlStateManager.disableLighting();
                GlStateManager.disableTexture2D();
                GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                alpha *= 0.72F;
                break;
            case 3: { // Pulse
                float scale = 1.0F + (wave - 0.5F) * 0.10F * strength;
                GlStateManager.scale(scale, scale, scale);
                break;
            }
            case 4: // Rainbow tint
                break;
            case 5: { // Ghost
                alpha *= 0.38F;
                float ghostScale = 1.0F + wave * 0.055F * strength;
                GlStateManager.scale(ghostScale, ghostScale, ghostScale);
                GlStateManager.disableAlpha();
                break;
            }
            case 6: // Wireframe
                GlStateManager.disableTexture2D();
                GlStateManager.disableLighting();
                GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
                GL11.glLineWidth(1.0F + strength * 2.0F);
                break;
            case 7: // Spin
                GlStateManager.rotate((float) ((time * 90.0D) % 360.0D), 0.0F, 1.0F, 0.0F);
                break;
            case 8: // Wave
                GlStateManager.translate(Math.sin(time * 4.0D) * 0.025D * strength,
                        Math.cos(time * 3.0D) * 0.018D * strength, 0.0D);
                GlStateManager.rotate((float) (Math.sin(time * 3.0D) * 7.0D * strength), 0.0F, 0.0F, 1.0F);
                break;
            case 9: // Two-color HUD-style gradient wave
                mod.beginItemGradientShader(alpha, strength);
                break;
            default: // Tint
                break;
        }

        GlStateManager.color((rgb >> 16 & 255) / 255.0F, (rgb >> 8 & 255) / 255.0F,
                (rgb & 255) / 255.0F, Math.max(0.05F, Math.min(1.0F, alpha)));
        return true;
    }

    public static void endItemChams(boolean active) {
        if (!active) return;
        HitEffect mod = instance;
        if (mod != null && mod.itemGradientShaderActive && mod.itemGradientShader != null) {
            mod.itemGradientShader.unload();
            mod.itemGradientShaderActive = false;
        }
        GlStateManager.popMatrix();
        GL11.glPopAttrib();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private static int mixColors(int first, int second, float amount) {
        float t = Math.max(0.0F, Math.min(1.0F, amount));
        int r = (int) ((first >> 16 & 255) + ((second >> 16 & 255) - (first >> 16 & 255)) * t);
        int g = (int) ((first >> 8 & 255) + ((second >> 8 & 255) - (first >> 8 & 255)) * t);
        int b = (int) ((first & 255) + ((second & 255) - (first & 255)) * t);
        return r << 16 | g << 8 | b;
    }

    private void beginItemGradientShader(float opacity, float strength) {
        if (itemGradientShaderFailed) return;
        try {
            if (itemGradientShader == null) {
                itemGradientShader = new ShaderUtils("mindless:shaders/item_gradient.frag");
            }
            itemGradientShader.init();
            itemGradientShader.setUniformi("textureIn", 0);
            itemGradientShader.setUniformf("color1", itemChamColor.getRed() / 255.0F,
                    itemChamColor.getGreen() / 255.0F, itemChamColor.getBlue() / 255.0F);
            itemGradientShader.setUniformf("color2", itemChamColor2.getRed() / 255.0F,
                    itemChamColor2.getGreen() / 255.0F, itemChamColor2.getBlue() / 255.0F);
            float speed = (float) itemChamSpeed.getInput();
            float timeAngle = (float) (System.currentTimeMillis() / 7500.0D * Math.PI * 2.0D * speed);
            boolean vertical = (int) itemGradientAxis.getInput() == 0;
            float length = (float) Math.max(0.5D, itemGradientLength.getInput());
            float spatialScale = (vertical ? 0.16F : 0.042F) / length;
            float direction = (int) itemGradientDirection.getInput() == 0 ? 1.0F : -1.0F;
            itemGradientShader.setUniformf("timeAngle", timeAngle);
            itemGradientShader.setUniformf("spatialScale", spatialScale);
            itemGradientShader.setUniformf("direction", direction);
            itemGradientShader.setUniformf("axis", vertical ? 0.0F : 1.0F);
            itemGradientShader.setUniformf("opacity", Math.max(0.05F, Math.min(1.0F, opacity)));
            itemGradientShader.setUniformf("strength", Math.max(0.0F, Math.min(1.0F, strength)));
            itemGradientShaderActive = true;
        } catch (Throwable ignored) {
            itemGradientShaderFailed = true;
            itemGradientShaderActive = false;
        }
    }

    private static float totalHealth(EntityLivingBase entity) {
        return entity.getHealth() + entity.getAbsorptionAmount();
    }

    private static int clampIndex(int index, int length) {
        return Math.max(0, Math.min(length - 1, index));
    }

    private static String formatDamage(float damage) {
        float rounded = Math.round(damage * 10.0F) / 10.0F;
        return rounded == Math.round(rounded) ? Integer.toString(Math.round(rounded)) : Float.toString(rounded);
    }

    private static final class PendingHit {
        private final EntityLivingBase target;
        private final float health;
        private final int hurtTime;
        private final long createdAt;
        private final long feedbackAt;
        private final long syncCycle;
        private HitVisual visual;
        private float damage;
        private boolean killed;

        private PendingHit(EntityLivingBase target, float health, int hurtTime, long createdAt,
                           long feedbackAt, long syncCycle) {
            this.target = target;
            this.health = health;
            this.hurtTime = hurtTime;
            this.createdAt = createdAt;
            this.feedbackAt = feedbackAt;
            this.syncCycle = syncCycle;
        }
    }

    private static final class HitVisual {
        private final double x, y, z;
        private final float width, height, yaw;
        private final double damageOffsetX, damageOffsetY;
        private final double driftX;
        private float damage;
        private boolean killed;
        private final long createdAt;

        private HitVisual(double x, double y, double z, float width, float height, float yaw,
                          float damage, boolean killed, long createdAt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.width = width;
            this.height = height;
            this.yaw = yaw;
            int sector = nextDamageSector();
            int column = sector % 4;
            int row = sector / 4;
            this.damageOffsetX = (column - 1.5D) * 0.31D + (RANDOM.nextDouble() - 0.5D) * 0.12D;
            this.damageOffsetY = row * 0.20D + RANDOM.nextDouble() * 0.12D;
            this.driftX = RANDOM.nextBoolean() ? 1.0D : -1.0D;
            this.damage = damage;
            this.killed = killed;
            this.createdAt = createdAt;
        }
    }

    private static int nextDamageSector() {
        int sector = RANDOM.nextInt(12);
        if (sector == lastDamageSector || Math.abs(sector - lastDamageSector) == 1) {
            sector = (sector + 4 + RANDOM.nextInt(5)) % 12;
        }
        lastDamageSector = sector;
        return sector;
    }
}
