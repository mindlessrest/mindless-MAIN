package mindless.lag.api;

import mindless.runtime.AccessorBridge;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S0CPacketSpawnPlayer;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S42PacketCombatEvent;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleSupplier;

public final class BacktrackPacketPolicy implements InboundClaimPolicy {
    private static final int NO_ENTITY = Integer.MIN_VALUE;
    private static final long NO_ATTACK = Long.MIN_VALUE;

    private static final class FixedPosition {
        private final int x;
        private final int y;
        private final int z;

        private FixedPosition(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final DoubleSupplier randomSource;
    private final Map<Integer, FixedPosition> baselines = new HashMap<>();
    private volatile BacktrackControlSnapshot control = BacktrackControlSnapshot.disabled();
    private volatile BacktrackPoseSnapshot pose = BacktrackPoseSnapshot.EMPTY;
    private volatile SessionEpoch stateEpoch = new SessionEpoch(0L, 0L);
    private volatile int shadowEntityId = NO_ENTITY;
    private volatile FixedPosition shadowPosition;
    private volatile boolean shadowInitialized;
    private volatile boolean windowActive;
    private volatile long windowOpenedNanos;
    private volatile long windowDeadlineNanos;
    private volatile long selectedDelayNanos;
    private volatile boolean delaySelected;
    private float lastServerHealth = Float.NaN;

    public BacktrackPacketPolicy() {
        this(Math::random);
    }

    public BacktrackPacketPolicy(DoubleSupplier randomSource) {
        if (randomSource == null) throw new IllegalArgumentException("randomSource");
        this.randomSource = randomSource;
    }

    public void setControl(BacktrackControlSnapshot next) {
        control = next == null ? BacktrackControlSnapshot.disabled() : next;
    }

    public BacktrackControlSnapshot getControl() {
        return control;
    }

    public BacktrackPoseSnapshot getPoseSnapshot() {
        return pose;
    }

    public BacktrackPoseSnapshot getPoseSnapshot(long nowNanos) {
        BacktrackPoseSnapshot current = pose;
        if (!control.isEnabled() || !windowActive || nowNanos >= windowDeadlineNanos) {
            return new BacktrackPoseSnapshot(
                    current.getEpoch(), false, false, false, current.getEntityId(),
                    current.getDisplayedX(), current.getDisplayedY(), current.getDisplayedZ(),
                    current.getShadowX(), current.getShadowY(), current.getShadowZ(),
                    current.getWidth(), current.getHeight(), current.getPublishedAtNanos());
        }
        return current;
    }

    public void releaseWindow() {
        resetWindow();
        BacktrackControlSnapshot current = control;
        double shadowX = shadowPosition == null ? current.getDisplayedX() : shadowPosition.x / 32.0D;
        double shadowY = shadowPosition == null ? current.getDisplayedY() : shadowPosition.y / 32.0D;
        double shadowZ = shadowPosition == null ? current.getDisplayedZ() : shadowPosition.z / 32.0D;
        pose = new BacktrackPoseSnapshot(
                stateEpoch, false, false, false, current.getTargetEntityId(),
                current.getDisplayedX(), current.getDisplayedY(), current.getDisplayedZ(),
                shadowX, shadowY, shadowZ, current.getWidth(), current.getHeight(), 0L);
    }

    public boolean isWindowActive(long nowNanos) {
        return windowActive && nowNanos < windowDeadlineNanos;
    }

    public long getSelectedWindowDelayNanos() {
        return delaySelected ? selectedDelayNanos : 0L;
    }

    public long chooseWindowDelayNanos() {
        if (delaySelected) return selectedDelayNanos;
        BacktrackControlSnapshot current = control;
        long min = Math.min(current.getMinDelayNanos(), current.getMaxDelayNanos());
        long max = Math.max(current.getMinDelayNanos(), current.getMaxDelayNanos());
        if (max <= min) selectedDelayNanos = max;
        else {
            double sample = randomSource.getAsDouble();
            if (Double.isNaN(sample) || Double.isInfinite(sample)) sample = 0.0D;
            sample = Math.max(0.0D, Math.min(1.0D, sample));
            double selected = min + (max - min) * sample;
            selectedDelayNanos = selected >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) selected;
        }
        delaySelected = true;
        return selectedDelayNanos;
    }

    public boolean isEntityCollisionStale(int entityId, double x, double z) {
        BacktrackPoseSnapshot current = pose;
        if (!current.isActive() || !isWindowActive(System.nanoTime()) || current.getEntityId() != entityId) return false;
        double dx = current.getShadowX() - x;
        double dz = current.getShadowZ() - z;
        return dx * dx + dz * dz > 0.0016D;
    }

    @Override
    public Decision decide(Packet<?> packet, SessionEpoch epoch, long nowNanos) {
        if (packet == null || epoch == null) return Decision.PASS;
        if (!stateEpoch.isSameSession(epoch)) resetForEpoch(epoch);

        if (packet instanceof S0CPacketSpawnPlayer) {
            S0CPacketSpawnPlayer spawn = (S0CPacketSpawnPlayer) packet;
            baselines.put(spawn.getEntityID(), new FixedPosition(spawn.getX(), spawn.getY(), spawn.getZ()));
            if (spawn.getEntityID() == shadowEntityId) {
                shadowPosition = baselines.get(spawn.getEntityID());
                shadowInitialized = true;
            }
        }

        BacktrackControlSnapshot current = control;
        synchronizeTarget(current);

        Decision barrier = classifyBarrier(packet, current);
        if (barrier != null) return barrier;

        boolean targetUpdate = isTargetUpdate(packet, current.getTargetEntityId());
        boolean targetMovement = applyTargetMovement(packet, current.getTargetEntityId());
        boolean eligible = current.isEnabled() && current.isTargetEligible()
                && current.getTargetEntityId() != NO_ENTITY && current.getMaxDelayNanos() > 0L;
        if (!eligible) {
            if (windowActive) {
                resetWindow();
                publishPose(current, epoch, nowNanos, false, false);
                return Decision.RELEASE_AND_PASS;
            }
            publishPose(current, epoch, nowNanos, false, false);
            return Decision.PASS;
        }

        if (nowNanos < current.getCooldownUntilNanos()) {
            publishPose(current, epoch, nowNanos, false, false);
            return Decision.PASS;
        }

        if (windowActive) {
            if (nowNanos >= windowDeadlineNanos) {
                resetWindow();
                publishPose(current, epoch, nowNanos, false, false);
                return Decision.RELEASE_AND_PASS;
            }
            if (!hasUsefulPosition(current, current.getContinueEpsilon())) {
                resetWindow();
                publishPose(current, epoch, nowNanos, false, false);
                return Decision.RELEASE_AND_PASS;
            }
            publishPose(current, epoch, nowNanos, true, hasUsefulPosition(current, 0.0D));
            return targetUpdate ? Decision.CLAIM : Decision.BYPASS;
        }

        if (targetMovement && isAttackFresh(current, nowNanos)
                && hasUsefulPosition(current, current.getStartEpsilon())) {
            windowActive = true;
            windowOpenedNanos = nowNanos;
            delaySelected = false;
            long delay = chooseWindowDelayNanos();
            windowDeadlineNanos = safeAdd(nowNanos, delay);
            windowActive = delay > 0L;
            publishPose(current, epoch, nowNanos, windowActive, windowActive);
            return windowActive ? Decision.CLAIM : Decision.PASS;
        }

        publishPose(current, epoch, nowNanos, false, false);
        return Decision.PASS;
    }

    private Decision classifyBarrier(Packet<?> packet, BacktrackControlSnapshot current) {
        if (packet instanceof S08PacketPlayerPosLook) {
            resetWindow();
            return Decision.RELEASE_AND_PASS;
        }
        if (packet instanceof S13PacketDestroyEntities) {
            for (int entityId : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                if (entityId == current.getTargetEntityId()) {
                    shadowInitialized = false;
                    resetWindow();
                    publishPose(current, stateEpoch, 0L, false, false);
                    return Decision.RELEASE_AND_PASS;
                }
            }
        }
        if (packet instanceof S06PacketUpdateHealth) {
            float health = ((S06PacketUpdateHealth) packet).getHealth();
            boolean damaged = !Float.isNaN(lastServerHealth) && health < lastServerHealth - 0.001F;
            lastServerHealth = health;
            if (health <= 0.0F || current.isFlushOnDamage() && damaged) {
                resetWindow();
                return Decision.RELEASE_AND_PASS;
            }
        }
        if (current.isFlushOnDamage() && packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == current.getLocalEntityId()) {
            resetWindow();
            return Decision.RELEASE_AND_PASS;
        }
        if (current.isFlushOnDamage() && packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            if (explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F) {
                resetWindow();
                return Decision.RELEASE_AND_PASS;
            }
        }
        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            int entityId = AccessorBridge.S19PacketEntityStatus_getEntityId(status);
            if (status.getOpCode() == 3 && (entityId == current.getLocalEntityId()
                    || entityId == current.getTargetEntityId())) {
                shadowInitialized = false;
                resetWindow();
                return Decision.RELEASE_AND_PASS;
            }
            if (status.getOpCode() == 2
                    && (current.isFlushOnDamage() && entityId == current.getLocalEntityId()
                    || entityId == current.getTargetEntityId())) {
                resetWindow();
                return Decision.RELEASE_AND_PASS;
            }
        }
        if (packet instanceof S42PacketCombatEvent) {
            S42PacketCombatEvent combat = (S42PacketCombatEvent) packet;
            if (combat.eventType == S42PacketCombatEvent.Event.ENTITY_DIED
                    && (combat.field_179774_b == current.getLocalEntityId()
                    || combat.field_179774_b == current.getTargetEntityId())) {
                shadowInitialized = false;
                resetWindow();
                return Decision.RELEASE_AND_PASS;
            }
        }
        return null;
    }

    private void synchronizeTarget(BacktrackControlSnapshot current) {
        int targetId = current.getTargetEntityId();
        if (targetId == shadowEntityId) return;
        resetWindow();
        shadowEntityId = targetId;
        shadowPosition = baselines.get(targetId);
        shadowInitialized = shadowPosition != null;
    }

    private boolean applyTargetMovement(Packet<?> packet, int targetId) {
        if (targetId == NO_ENTITY || shadowEntityId != targetId) return false;
        if (packet instanceof S14PacketEntity) {
            S14PacketEntity movement = (S14PacketEntity) packet;
            if (AccessorBridge.S14PacketEntity_getEntityId(movement) != targetId
                    || !(movement instanceof S14PacketEntity.S15PacketEntityRelMove)
                    && !(movement instanceof S14PacketEntity.S17PacketEntityLookMove)) return false;
            if (!shadowInitialized || shadowPosition == null) return false;
            shadowPosition = new FixedPosition(
                    shadowPosition.x + AccessorBridge.S14PacketEntity_getDeltaX(movement),
                    shadowPosition.y + AccessorBridge.S14PacketEntity_getDeltaY(movement),
                    shadowPosition.z + AccessorBridge.S14PacketEntity_getDeltaZ(movement));
            return true;
        }
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) packet;
            if (teleport.getEntityId() != targetId) return false;
            shadowPosition = new FixedPosition(teleport.getX(), teleport.getY(), teleport.getZ());
            shadowInitialized = true;
            return true;
        }
        return false;
    }

    private boolean isTargetUpdate(Packet<?> packet, int targetId) {
        if (targetId == NO_ENTITY) return false;
        if (packet instanceof S14PacketEntity) {
            return AccessorBridge.S14PacketEntity_getEntityId((S14PacketEntity) packet) == targetId;
        }
        return packet instanceof S18PacketEntityTeleport
                && ((S18PacketEntityTeleport) packet).getEntityId() == targetId;
    }

    private boolean isAttackFresh(BacktrackControlSnapshot current, long nowNanos) {
        long attackAt = current.getAttackAtNanos();
        if (attackAt == NO_ATTACK || current.getAttackWindowNanos() <= 0L || nowNanos < attackAt) return false;
        return nowNanos - attackAt <= current.getAttackWindowNanos();
    }

    private boolean hasUsefulPosition(BacktrackControlSnapshot current, double epsilon) {
        if (!shadowInitialized || shadowPosition == null || shadowEntityId != current.getTargetEntityId()) return false;
        double renderedDistance = distanceToBox(
                current.getEyeX(), current.getEyeY(), current.getEyeZ(),
                current.getDisplayedX(), current.getDisplayedY(), current.getDisplayedZ(),
                current.getWidth(), current.getHeight());
        double shadowDistance = distanceToBox(
                current.getEyeX(), current.getEyeY(), current.getEyeZ(),
                shadowPosition.x / 32.0D, shadowPosition.y / 32.0D, shadowPosition.z / 32.0D,
                current.getWidth(), current.getHeight());
        return renderedDistance >= current.getMinDistance()
                && renderedDistance <= current.getMaxDistance()
                && shadowDistance >= current.getMinDistance()
                && shadowDistance <= current.getMaxDistance()
                && shadowDistance > renderedDistance + epsilon;
    }

    private void publishPose(
            BacktrackControlSnapshot current,
            SessionEpoch epoch,
            long nowNanos,
            boolean pending,
            boolean useful
    ) {
        double shadowX = shadowPosition == null ? current.getDisplayedX() : shadowPosition.x / 32.0D;
        double shadowY = shadowPosition == null ? current.getDisplayedY() : shadowPosition.y / 32.0D;
        double shadowZ = shadowPosition == null ? current.getDisplayedZ() : shadowPosition.z / 32.0D;
        pose = new BacktrackPoseSnapshot(
                epoch, pending, windowActive, useful, current.getTargetEntityId(),
                current.getDisplayedX(), current.getDisplayedY(), current.getDisplayedZ(),
                shadowX, shadowY, shadowZ, current.getWidth(), current.getHeight(), nowNanos);
    }

    private void resetForEpoch(SessionEpoch epoch) {
        stateEpoch = epoch;
        baselines.clear();
        shadowEntityId = NO_ENTITY;
        shadowPosition = null;
        shadowInitialized = false;
        lastServerHealth = Float.NaN;
        resetWindow();
        pose = BacktrackPoseSnapshot.EMPTY;
    }

    private void resetWindow() {
        windowActive = false;
        windowOpenedNanos = 0L;
        windowDeadlineNanos = 0L;
        selectedDelayNanos = 0L;
        delaySelected = false;
    }

    private static double distanceToBox(
            double eyeX,
            double eyeY,
            double eyeZ,
            double x,
            double y,
            double z,
            double width,
            double height
    ) {
        double halfWidth = width * 0.5D + 0.1D;
        double closestX = clamp(eyeX, x - halfWidth, x + halfWidth);
        double closestY = clamp(eyeY, y, y + height);
        double closestZ = clamp(eyeZ, z - halfWidth, z + halfWidth);
        double dx = eyeX - closestX;
        double dy = eyeY - closestY;
        double dz = eyeZ - closestZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
