package mindless.module.impl.network;

import mindless.Mindless;
import mindless.event.DispatchPacketEvent;
import mindless.lag.api.BacktrackControlSnapshot;
import mindless.lag.api.BacktrackPacketPolicy;
import mindless.lag.api.BacktrackPoseSnapshot;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.render.TargetHUD;
import mindless.module.impl.world.TargetFilter;
import mindless.runtime.AccessorBridge;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Backtrack extends Module {
    private final SliderSetting minDistance;
    private final SliderSetting maxDistance;
    private final SliderSetting minDelay;
    private final SliderSetting maxDelay;
    private final SliderSetting attackWindow;
    private final SliderSetting maxHurtTime;
    private final SliderSetting cooldown;
    private final ButtonSetting disableAdventure;
    private final ButtonSetting flushOnDamage;
    private final ButtonSetting weaponOnly;
    private final ButtonSetting showServerPosition;
    private final ColorSetting positionColor;
    private final BacktrackPacketPolicy policy = new BacktrackPacketPolicy();

    private DelayLease lease;
    private volatile EntityPlayer target;
    private volatile EntityPlayer pendingAttackTarget;
    private volatile long acceptedAttackAtNanos = Long.MIN_VALUE;
    private volatile long cooldownUntilNanos;
    private boolean wasWindowActive;
    private Vec3 positionInterpFrom;
    private Vec3 positionInterpTo;
    private long positionInterpStartNanos;

    public Backtrack() {
        super("Backtrack", "Holds packets so you hit where they were.", category.combat);
        this.liteModule = true;
        this.registerSetting(minDelay = new SliderSetting("Delay min", "ms", 150.0, 0.0, 500.0, 10.0));
        this.registerSetting(maxDelay = new SliderSetting("Delay max", "ms", 200.0, 50.0, 500.0, 10.0));
        this.registerSetting(minDistance = new SliderSetting("Distance (min)", 0.0, 0.0, 3.0, 0.1));
        this.registerSetting(maxDistance = new SliderSetting("Distance (max)", 4.0, 1.0, 6.0, 0.1));
        this.registerSetting(cooldown = new SliderSetting("Cooldown", "ms", 0.0, 0.0, 2000.0, 50.0));
        this.registerSetting(attackWindow = new SliderSetting("Attack window", "ms", 1000.0, 100.0, 5000.0, 50.0));
        this.registerSetting(maxHurtTime = new SliderSetting("Max hurt time", "ms", 500.0, 0.0, 500.0, 10.0));
        this.registerSetting(disableAdventure = new ButtonSetting("Disable adventure", true));
        this.registerSetting(flushOnDamage = new ButtonSetting("Flush on damage", true));
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
        if (Mindless.packetDelayService == null) return;
        Mindless.packetDelayService.activate();
        resetRuntimeState();
        ensureLease();
    }

    @Override
    public void onDisable() {
        resetRuntimeState();
    }

    @Override
    public void onProfileLoad() {
        resetRuntimeState();
    }

    public void onWorldUnload() {
        resetRuntimeState();
    }

    @Override
    public void onUpdate() {
        long now = System.nanoTime();
        boolean active = policy.isWindowActive(now);
        if (wasWindowActive && !active && cooldown.getInput() > 0.0D) {
            cooldownUntilNanos = now + DelayRequest.millisToNanos((long) cooldown.getInput());
        }
        wasWindowActive = active;

        if (!isRuntimeReady()) {
            invalidateLease();
            policy.setControl(BacktrackControlSnapshot.disabled());
            return;
        }
        if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            invalidateLease();
            policy.setControl(BacktrackControlSnapshot.disabled());
            return;
        }
        if (disableAdventure.isToggled() && mc.playerController != null
                && mc.playerController.getCurrentGameType().isAdventure()) {
            invalidateLease();
            policy.setControl(BacktrackControlSnapshot.disabled());
            return;
        }
        if (flushOnDamage.isToggled() && mc.thePlayer.hurtTime > 0) {
            releaseClaims();
            acceptedAttackAtNanos = Long.MIN_VALUE;
            policy.setControl(BacktrackControlSnapshot.disabled());
            return;
        }

        EntityPlayer currentTarget = target;
        if (!isEligibleTarget(currentTarget)) {
            releaseClaims();
            policy.setControl(BacktrackControlSnapshot.disabled());
            return;
        }

        ensureLease();

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 displayed = currentTarget.getPositionVector();
        long minDelayNanos = DelayRequest.millisToNanos((long) Math.min(minDelay.getInput(), maxDelay.getInput()));
        long maxDelayNanos = DelayRequest.millisToNanos((long) Math.max(minDelay.getInput(), maxDelay.getInput()));
        long attackWindowNanos = DelayRequest.millisToNanos((long) attackWindow.getInput());
        BacktrackControlSnapshot next = BacktrackControlSnapshot.builder()
                .enabled(true)
                .targetEligible(true)
                .flushOnDamage(flushOnDamage.isToggled())
                .localEntityId(mc.thePlayer.getEntityId())
                .targetEntityId(currentTarget.getEntityId())
                .attackAtNanos(acceptedAttackAtNanos)
                .attackWindowNanos(attackWindowNanos)
                .cooldownUntilNanos(cooldownUntilNanos)
                .minDelayNanos(minDelayNanos)
                .maxDelayNanos(maxDelayNanos)
                .eye(eye.xCoord, eye.yCoord, eye.zCoord)
                .displayed(displayed.xCoord, displayed.yCoord, displayed.zCoord)
                .size(currentTarget.width, currentTarget.height)
                .distance(minDistance.getInput(), maxDistance.getInput())
                .thresholds(0.025D, 0.01D)
                .build();
        policy.setControl(next);
    }

    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!isEnabled() || mc.isSingleplayer() || !isRuntimeReady()) return;
        if (event.entityPlayer != mc.thePlayer || !(event.target instanceof EntityPlayer)) return;
        EntityPlayer attacked = (EntityPlayer) event.target;
        if (isEligibleTarget(attacked)) {
            if (target != attacked) releaseClaims();
            target = attacked;
            pendingAttackTarget = attacked;
        }
    }

    @SubscribeEvent
    public void onDispatchPacket(DispatchPacketEvent event) {
        if (!isEnabled() || !(event.getPacket() instanceof C02PacketUseEntity)) return;
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) return;
        BacktrackControlSnapshot current = policy.getControl();
        int dispatchedEntityId = AccessorBridge.C02PacketUseEntity_getEntityId(packet);
        EntityPlayer pending = pendingAttackTarget;
        boolean hasPendingMetadata = pending != null;
        if (hasPendingMetadata && pending.getEntityId() != dispatchedEntityId) return;
        if (!hasPendingMetadata && (!current.isEnabled() || !current.isTargetEligible()
                || current.getTargetEntityId() != dispatchedEntityId)) return;
        EntityPlayer dispatchedTarget = target;
        if (dispatchedTarget == null && mc.isCallingFromMinecraftThread()
                && mc.theWorld != null && mc.theWorld.getEntityByID(dispatchedEntityId) instanceof EntityPlayer) {
            dispatchedTarget = (EntityPlayer) mc.theWorld.getEntityByID(dispatchedEntityId);
        }
        if (dispatchedTarget == null || dispatchedTarget.getEntityId() != dispatchedEntityId) return;
        if (pendingAttackTarget != null && pendingAttackTarget.getEntityId() != dispatchedEntityId) return;
        if (target != dispatchedTarget) releaseClaims();
        target = dispatchedTarget;
        pendingAttackTarget = null;
        acceptedAttackAtNanos = System.nanoTime();
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent event) {
        if (Mindless.packetDelayService == null || mc.isSingleplayer() || !showServerPosition.isToggled() || target == null) return;
        if (Mindless.packetDelayService.getPendingCount(EnumLagDirection.INBOUND) <= 0) return;
        BacktrackPoseSnapshot snapshot = policy.getPoseSnapshot(System.nanoTime());
        if (!snapshot.isActive() || !snapshot.hasShadowPosition()) return;
        Vec3 drawPosition = interpolatedPosition(snapshot, System.nanoTime());
        int color = positionColor.getColor();
        TargetHUD targetHUD = ModuleManager.targetHUD;
        if (targetHUD != null && targetHUD.isEspActiveFor(target)) {
            color = targetHUD.getCurrentEspColor(positionColor.getAlpha()).getRGB();
        }
        RenderUtils.drawPlayerBoundingBox(drawPosition, color);
    }

    public boolean isRenderingServerPositionFor(EntityLivingBase entity) {
        if (Mindless.packetDelayService == null || mc.isSingleplayer() || !showServerPosition.isToggled() || entity != target) return false;
        if (Mindless.packetDelayService.getPendingCount(EnumLagDirection.INBOUND) <= 0) return false;
        return policy.getPoseSnapshot(System.nanoTime()).isActive();
    }

    public Vec3 getBacktrackPosition() {
        if (Mindless.packetDelayService == null || mc.isSingleplayer() || target == null
                || Mindless.packetDelayService.getPendingCount(EnumLagDirection.INBOUND) <= 0) return null;
        BacktrackPoseSnapshot snapshot = policy.getPoseSnapshot(System.nanoTime());
        if (!snapshot.isActive()) return null;
        return new Vec3(snapshot.getShadowX(), snapshot.getShadowY(), snapshot.getShadowZ());
    }

    public EntityPlayer getBacktrackTarget() {
        return getBacktrackPosition() == null ? null : target;
    }

    public boolean isEntityCollisionStale(int entityId, double x, double z) {
        if (!isEnabled() || target == null || entityId != target.getEntityId()) return false;
        return policy.isEntityCollisionStale(entityId, x, z);
    }

    @Override
    public String getInfo() {
        long selected = policy.getSelectedWindowDelayNanos();
        if (selected > 0L && Mindless.packetDelayService != null
                && Mindless.packetDelayService.getPendingCount(EnumLagDirection.INBOUND) > 0) {
            return (selected / 1000000L) + "ms";
        }
        int lo = (int) Math.min(minDelay.getInput(), maxDelay.getInput());
        int hi = (int) Math.max(minDelay.getInput(), maxDelay.getInput());
        return lo == hi ? lo + "ms" : lo + "-" + hi + "ms";
    }

    private boolean isRuntimeReady() {
        return !mc.isSingleplayer() && Utils.nullCheck() && mc.thePlayer instanceof EntityPlayerSP
                && mc.theWorld != null && Mindless.packetDelayService != null;
    }

    private boolean isEligibleTarget(EntityPlayer candidate) {
        if (candidate == null || candidate == mc.thePlayer || candidate.isDead) return false;
        if (TargetFilter.shouldFilter(candidate)) return false;
        double distance = mc.thePlayer.getDistanceToEntity(candidate);
        if (distance < minDistance.getInput() || distance > maxDistance.getInput()) return false;
        return maxHurtTime.getInput() >= 500.0D || candidate.hurtTime * 50.0D <= maxHurtTime.getInput();
    }

    private void releaseClaims() {
        policy.releaseWindow();
        if (lease != null) lease.releaseClaims();
    }

    private void invalidateLease() {
        policy.releaseWindow();
        if (lease != null) {
            lease.release();
            lease = null;
        }
    }

    private void ensureLease() {
        if (lease == null && Mindless.packetDelayService != null) {
            lease = Mindless.packetDelayService.acquire(DelayRequest.fixedWindow(
                    "Backtrack", EnumLagDirection.ONLY_INBOUND, policy, policy::chooseWindowDelayNanos));
        }
    }

    private void resetRuntimeState() {
        policy.setControl(BacktrackControlSnapshot.disabled());
        invalidateLease();
        target = null;
        pendingAttackTarget = null;
        acceptedAttackAtNanos = Long.MIN_VALUE;
        cooldownUntilNanos = 0L;
        wasWindowActive = false;
        clearPositionInterp();
    }

    private Vec3 interpolatedPosition(BacktrackPoseSnapshot snapshot, long nowNanos) {
        Vec3 next = new Vec3(snapshot.getShadowX(), snapshot.getShadowY(), snapshot.getShadowZ());
        if (positionInterpTo == null) {
            positionInterpFrom = next;
            positionInterpTo = next;
            positionInterpStartNanos = nowNanos;
            return next;
        }
        if (!samePosition(positionInterpTo, next)) {
            double progress = Math.min(1.0D, Math.max(0.0D,
                    (nowNanos - positionInterpStartNanos) / 80000000.0D));
            positionInterpFrom = lerp(positionInterpFrom, positionInterpTo, progress);
            positionInterpTo = next;
            positionInterpStartNanos = nowNanos;
        }
        double progress = Math.min(1.0D, Math.max(0.0D,
                (nowNanos - positionInterpStartNanos) / 80000000.0D));
        return lerp(positionInterpFrom, positionInterpTo, progress);
    }

    private void clearPositionInterp() {
        positionInterpFrom = null;
        positionInterpTo = null;
        positionInterpStartNanos = 0L;
    }

    private static boolean samePosition(Vec3 left, Vec3 right) {
        return Math.abs(left.xCoord - right.xCoord) <= 1.0e-6D
                && Math.abs(left.yCoord - right.yCoord) <= 1.0e-6D
                && Math.abs(left.zCoord - right.zCoord) <= 1.0e-6D;
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, double progress) {
        if (progress <= 0.0D) return from;
        if (progress >= 1.0D) return to;
        return new Vec3(
                from.xCoord + (to.xCoord - from.xCoord) * progress,
                from.yCoord + (to.yCoord - from.yCoord) * progress,
                from.zCoord + (to.zCoord - from.zCoord) * progress);
    }
}
