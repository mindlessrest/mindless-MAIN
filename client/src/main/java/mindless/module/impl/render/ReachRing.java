package mindless.module.impl.render;

import mindless.effect.EffectRenderer;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * A ring on the ground showing how close is too close.
 *
 * Drawn at whatever the radius setting says rather than at a number pretending to be authoritative.
 * Real reach depends on the server's lag compensation and on both players' latency, so a ring that
 * claimed to know it would be lying; this one is a ruler you set yourself.
 *
 * Inside the ring you are inside their reach, which also means they are inside yours.
 */
public class ReachRing extends Module {

    private static final String[] TARGETS = new String[]{"Nearest", "All players", "Looking at"};
    private static final int TARGET_NEAREST = 0;
    private static final int TARGET_ALL = 1;
    private static final int TARGET_LOOKING = 2;

    private final SliderSetting target;
    private final SliderSetting radius;
    private final SliderSetting thickness;
    private final SliderSetting range;
    private final ButtonSetting pulse;
    private final ButtonSetting warn;
    private final ColorSetting color;
    private final ColorSetting insideColor;
    private final SliderSetting opacity;
    private final ButtonSetting throughWalls;

    public ReachRing() {
        super("Reach Ring", "Draws the enemy's reach on the ground around them.", category.render, 0);
        this.registerSetting(target = new SliderSetting("Target", TARGET_NEAREST, TARGETS));
        this.registerSetting(radius = new SliderSetting("Radius", "blocks", 3.0, 1.0, 6.0, 0.05));
        this.registerSetting(thickness = new SliderSetting("Thickness", "blocks", 0.08, 0.02, 0.5, 0.01));
        this.registerSetting(range = new SliderSetting("Draw within", "blocks", 24.0, 4.0, 64.0, 1.0));
        this.registerSetting(pulse = new ButtonSetting("Pulse", true));
        this.registerSetting(warn = new ButtonSetting("Change inside", true));
        this.registerSetting(color = new ColorSetting("Color", 255, 120, 60, 255));
        this.registerSetting(insideColor = new ColorSetting("Inside color", 255, 60, 60, 255));
        this.registerSetting(opacity = new SliderSetting("Opacity", "%", 55, 5, 100, 5));
        this.registerSetting(throughWalls = new ButtonSetting("Through walls", true));
        this.liteModule = true;
    }

    @Override
    public void guiUpdate() {
        if (insideColor != null) {
            insideColor.setVisible(warn != null && warn.isToggled(), this);
        }
    }

    @Override
    public String getInfo() {
        return String.format(java.util.Locale.ROOT, "%.2f", radius.getInput());
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || mc.getRenderManager() == null) {
            return;
        }

        int mode = (int) target.getInput();
        double maxRange = range.getInput();
        double maxRangeSq = maxRange * maxRange;

        EntityPlayer nearest = null;
        double nearestSq = Double.MAX_VALUE;
        java.util.List<EntityPlayer> chosen = new java.util.ArrayList<EntityPlayer>();

        for (int i = 0; i < mc.theWorld.playerEntities.size(); i++) {
            EntityPlayer player = mc.theWorld.playerEntities.get(i);
            if (player == mc.thePlayer || player.isDead || player.isInvisible()) {
                continue;
            }
            double distSq = player.getDistanceSqToEntity(mc.thePlayer);
            if (distSq > maxRangeSq) {
                continue;
            }
            if (mode == TARGET_ALL) {
                chosen.add(player);
            }
            else if (distSq < nearestSq) {
                nearestSq = distSq;
                nearest = player;
            }
        }

        if (mode == TARGET_LOOKING) {
            chosen.clear();
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit instanceof EntityPlayer
                    && mc.objectMouseOver.entityHit != mc.thePlayer) {
                chosen.add((EntityPlayer) mc.objectMouseOver.entityHit);
            }
        }
        else if (mode == TARGET_NEAREST && nearest != null) {
            chosen.add(nearest);
        }

        if (chosen.isEmpty()) {
            return;
        }

        RenderManager rm = mc.getRenderManager();
        EffectRenderer.begin();
        EffectRenderer.seeThrough(throughWalls.isToggled());
        GlStateManager.translate(-rm.viewerPosX, -rm.viewerPosY, -rm.viewerPosZ);
        try {
            for (int i = 0; i < chosen.size(); i++) {
                drawFor(chosen.get(i), event.partialTicks);
            }
        }
        finally {
            EffectRenderer.seeThrough(false);
            EffectRenderer.end();
        }
    }

    private void drawFor(EntityPlayer player, float partialTicks) {
        double px = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double pz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;

        double r = radius.getInput();
        // Measured between the two players' feet, which is how a melee hit is judged: the check
        // is on the boxes, and drawing it eye to eye would put the ring in the wrong place on
        // anyone standing above or below you.
        double dx = mc.thePlayer.posX - player.posX;
        double dz = mc.thePlayer.posZ - player.posZ;
        boolean inside = Math.sqrt(dx * dx + dz * dz) <= r;

        int rgb = (warn.isToggled() && inside ? insideColor : color).getRGB() & 0xFFFFFF;
        float alpha = (float) (opacity.getInput() / 100.0);
        if (pulse.isToggled()) {
            alpha *= 0.75f + 0.25f * (float) Math.sin(System.currentTimeMillis() / 260.0);
        }
        if (inside) {
            alpha = Math.min(1.0f, alpha * 1.35f);
        }

        double half = thickness.getInput() * 0.5;
        double footY = player.getEntityBoundingBox().minY + 0.02;
        EffectRenderer.groundRing(px, footY, pz, Math.max(0.0, r - half), r + half, rgb, alpha);

        // A faint wash inside the ring is what makes it read as an area rather than as a hoop.
        EffectRenderer.groundRing(px, footY, pz, 0.0, Math.max(0.0, r - half), rgb, alpha * 0.10f);
    }
}
