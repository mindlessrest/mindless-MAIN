package keystrokesmod.module.impl.network;

import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.runtime.AccessorBridge;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.render.TargetHUD;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.*;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class Backtrack extends Module {
    private final SliderSetting minDistance;
    private final SliderSetting maxDistance;
    private final SliderSetting minDelay;
    private final SliderSetting maxDelay;
    private final SliderSetting maxHurtTime;
    private final SliderSetting cooldown;
    private final ButtonSetting disableAdventure;
    private final ButtonSetting flushOnDamage;
    private final ButtonSetting ignoreTeammates;
    private final ButtonSetting weaponOnly;
    private final ButtonSetting showServerPosition;
    private final ColorSetting positionColor;

    private final Queue<TimedPacket> packetQueue = new ConcurrentLinkedQueue<>();
    private Vec3 targetPos = null;
    private EntityPlayer target = null;
    private int currentDelay = 0;
    private long lastDeactivationTime = 0;
    private boolean wasActive = false;

    // Server-position interpolation
    private static final long POSITION_INTERP_MS = 80L;
    private static final double POS_EPS = 1.0e-6;
    private Vec3 positionInterpFrom;
    private Vec3 positionInterpTo;
    private long positionInterpStartMs;

    public Backtrack() {
        super("Backtrack", category.network);
        this.registerSetting(minDelay = new SliderSetting("Delay min", "ms", 150.0, 0.0, 500.0, 10.0));
        this.registerSetting(maxDelay = new SliderSetting("Delay max", "ms", 200.0, 50.0, 500.0, 10.0));
        this.registerSetting(minDistance = new SliderSetting("Distance (min)", 0.0, 0.0, 3.0, 0.1));
        this.registerSetting(maxDistance = new SliderSetting("Distance (max)", 4.0, 1.0, 6.0, 0.1));
        this.registerSetting(cooldown = new SliderSetting("Cooldown", "ms", 0.0, 0.0, 2000.0, 50.0));
        this.registerSetting(maxHurtTime = new SliderSetting("Max hurt time", "ms", 500.0, 0.0, 500.0, 10.0));
        this.registerSetting(disableAdventure = new ButtonSetting("Disable adventure", true));
        this.registerSetting(flushOnDamage = new ButtonSetting("Flush on damage", true));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(showServerPosition = new ButtonSetting("Show server position", true));
        this.registerSetting(positionColor = new ColorSetting("Position color", 0, 0, 250, 100));
    }

    @Override
    public void guiUpdate() {
        Utils.correctValue(minDistance, maxDistance);
        Utils.correctValue(minDelay, maxDelay);
    }

    @Override
    public void onEnable() {
        packetQueue.clear();
        targetPos = null;
        target = null;
        currentDelay = 0;
        lastDeactivationTime = 0;
        wasActive = false;
    }

    @Override
    public void onDisable() {
        if (mc.isSingleplayer()) resetWithoutProcessingPackets();
        else releaseAll();
        target = null;
        targetPos = null;
        currentDelay = 0;
        clearPositionInterp();
    }

    @Override
    public void onUpdate() {
        if (mc.isSingleplayer()) { resetWithoutProcessingPackets(); return; }
        if (mc.thePlayer == null || mc.theWorld == null) { releaseAll(); return; }
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) { releaseAll(); return; }
        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) { releaseAll(); return; }
        if (flushOnDamage.isToggled() && mc.thePlayer.hurtTime > 0) { releaseAll(); return; }

        if (targetPos != null && target != null) {
            double distance = RotationUtils.distanceFromEyeToClosestOnAABB(target, targetPos);
            if (distance > maxDistance.getInput() || distance < minDistance.getInput()) {
                if (wasActive) {
                    releaseAll();
                    lastDeactivationTime = System.currentTimeMillis();
        wasActive = false;
        clearPositionInterp();
                }
            }
        }

        processDelayedPackets();

        if (packetQueue.isEmpty() && target != null) targetPos = target.getPositionVector();
        wasActive = currentDelay > 0 && !packetQueue.isEmpty();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (mc.isSingleplayer() || !showServerPosition.isToggled()) return;
        if (target == null || targetPos == null || target.isDead || currentDelay <= 0) return;

        long nowMs = System.currentTimeMillis();
        if (positionInterpTo == null) {
            positionInterpFrom = targetPos;
            positionInterpTo = targetPos;
            positionInterpStartMs = nowMs;
        } else if (positionChanged(targetPos, positionInterpTo)) {
            double elapsedProgress = Math.min(1.0D,
                    (nowMs - positionInterpStartMs) / (double) POSITION_INTERP_MS);
            positionInterpFrom = lerpVec3(positionInterpFrom, positionInterpTo, elapsedProgress);
            positionInterpTo = targetPos;
            positionInterpStartMs = nowMs;
        }

        double progress = Math.min(1.0D,
                (nowMs - positionInterpStartMs) / (double) POSITION_INTERP_MS);
        Vec3 drawPos = lerpVec3(positionInterpFrom, positionInterpTo, progress);

        int color = positionColor.getColor();
        TargetHUD targetHUD = ModuleManager.targetHUD;
        if (targetHUD != null && targetHUD.isEspActiveFor(target))
            color = targetHUD.getCurrentEspColor(positionColor.getAlpha()).getRGB();

        RenderUtils.drawPlayerBoundingBox(drawPos, color);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent e) {
        if (mc.isSingleplayer()) return;
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) return;
        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) return;
        if (cooldown.getInput() > 0 && lastDeactivationTime > 0 && System.currentTimeMillis() - lastDeactivationTime < cooldown.getInput()) return;
        if (!(e.target instanceof EntityPlayer)) return;

        EntityPlayer attacked = (EntityPlayer) e.target;
        if (AntiBot.isBot(attacked) || (Utils.isTeammate(attacked) && ignoreTeammates.isToggled())) return;

        double distance = mc.thePlayer.getDistanceToEntity(attacked);
        if (distance > maxDistance.getInput() || distance < minDistance.getInput()) return;
        if (maxHurtTime.getInput() < 500 && attacked.hurtTime * 50 > maxHurtTime.getInput()) return;

        if (target == null || attacked != target) {
            releaseAll();
            targetPos = attacked.getPositionVector();
        }
        target = attacked;
        double dMin = Math.min(minDelay.getInput(), maxDelay.getInput());
        double dMax = Math.max(minDelay.getInput(), maxDelay.getInput());
        currentDelay = (int) (dMin + Math.random() * (dMax - dMin));
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (mc.isSingleplayer() || !Utils.nullCheck()) return;
        if (mc.thePlayer.ticksExisted < 20) { packetQueue.clear(); return; }
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) { releaseAll(); return; }
        if (disableAdventure.isToggled() && mc.playerController.getCurrentGameType().isAdventure()) { releaseAll(); return; }
        if (flushOnDamage.isToggled() && mc.thePlayer.hurtTime > 0) { releaseAll(); return; }
        if (target == null) { releaseAll(); return; }

        Packet packet = e.getPacket();
        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            releaseAll(); target = null; targetPos = null; return;
        }
        if (packet instanceof S13PacketDestroyEntities) {
            for (int id : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                if (target != null && id == target.getEntityId()) {
                    target = null; targetPos = null; releaseAll(); return;
                }
            }
        }

        if (currentDelay <= 0) return;

        boolean isTargetMove = false;
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity mp = (S14PacketEntity) packet;
            if (AccessorBridge.S14PacketEntity_getEntityId(mp) == target.getEntityId()) {
                isTargetMove = true;
                if (targetPos != null) targetPos = targetPos.addVector(mp.func_149062_c() / 32.0D, mp.func_149061_d() / 32.0D, mp.func_149064_e() / 32.0D);
            }
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport tp = (S18PacketEntityTeleport) packet;
            if (tp.getEntityId() == target.getEntityId()) {
                isTargetMove = true;
                targetPos = new Vec3(tp.getX() / 32.0D, tp.getY() / 32.0D, tp.getZ() / 32.0D);
            }
        }

        if (!isTargetMove) return;
        packetQueue.add(new TimedPacket(packet, System.currentTimeMillis()));
        e.setCanceled(true);
    }

    private void processDelayedPackets() {
        while (!packetQueue.isEmpty()) {
            TimedPacket tp = packetQueue.peek();
            if (tp == null) break;
            if (System.currentTimeMillis() - tp.timestamp >= currentDelay) {
                packetQueue.poll();
                processPacket(tp.packet);
            } else break;
        }
    }

    private void releaseAll() {
        while (!packetQueue.isEmpty()) {
            TimedPacket tp = packetQueue.poll();
            if (tp != null) processPacket(tp.packet);
        }
        currentDelay = 0;
    }

    private void resetWithoutProcessingPackets() {
        packetQueue.clear(); target = null; targetPos = null;
        currentDelay = 0; lastDeactivationTime = 0; wasActive = false;
        clearPositionInterp();
    }

    private void clearPositionInterp() {
        positionInterpFrom = null;
        positionInterpTo = null;
        positionInterpStartMs = 0L;
    }

    private static boolean positionChanged(Vec3 a, Vec3 b) {
        return Math.abs(a.xCoord - b.xCoord) > POS_EPS
                || Math.abs(a.yCoord - b.yCoord) > POS_EPS
                || Math.abs(a.zCoord - b.zCoord) > POS_EPS;
    }

    private static Vec3 lerpVec3(Vec3 from, Vec3 to, double t) {
        if (t <= 0.0D) return from;
        if (t >= 1.0D) return to;
        return new Vec3(
                from.xCoord + (to.xCoord - from.xCoord) * t,
                from.yCoord + (to.yCoord - from.yCoord) * t,
                from.zCoord + (to.zCoord - from.zCoord) * t
        );
    }

    @SuppressWarnings("unchecked")
    private void processPacket(Object packet) {
        if (packet == null) return;
        try {
            if (packet instanceof Packet && mc.getNetHandler() != null)
                ((Packet<INetHandlerPlayClient>) packet).processPacket(mc.getNetHandler());
        } catch (Exception ignored) {}
    }

    public boolean isRenderingServerPositionFor(EntityLivingBase entity) {
        return !mc.isSingleplayer() && showServerPosition.isToggled() && target == entity && targetPos != null && !target.isDead && currentDelay > 0;
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!showServerPosition.isToggled() || target == null || targetPos == null || currentDelay <= 0 || packetQueue.isEmpty()) return;
        if (mc.isSingleplayer()) return;

        float partialTicks = event.partialTicks;
        double x = targetPos.xCoord - mc.getRenderManager().viewerPosX;
        double y = targetPos.yCoord - mc.getRenderManager().viewerPosY;
        double z = targetPos.zCoord - mc.getRenderManager().viewerPosZ;

        float halfWidth = target.width / 2.0f;
        float height = target.height;

        int color = positionColor.getColor();
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;

        net.minecraft.util.AxisAlignedBB bb = new net.minecraft.util.AxisAlignedBB(
                x - halfWidth, y, z - halfWidth,
                x + halfWidth, y + height, z + halfWidth
        );

        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glBlendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDepthMask(false);
        org.lwjgl.opengl.GL11.glLineWidth(1.5f);
        RenderUtils.drawBoundingBox(bb, r, g, b, a);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        org.lwjgl.opengl.GL11.glDepthMask(true);
        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_TEXTURE_2D);
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_BLEND);
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    @Override
    public String getInfo() {
        if (currentDelay > 0 && !packetQueue.isEmpty()) return currentDelay + "ms";
        int lo = (int) Math.min(minDelay.getInput(), maxDelay.getInput());
        int hi = (int) Math.max(minDelay.getInput(), maxDelay.getInput());
        return lo == hi ? lo + "ms" : lo + "-" + hi + "ms";
    }

    private static class TimedPacket {
        final Object packet;
        final long timestamp;
        TimedPacket(Object packet, long timestamp) { this.packet = packet; this.timestamp = timestamp; }
    }
}
