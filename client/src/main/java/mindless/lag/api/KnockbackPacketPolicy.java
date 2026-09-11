package mindless.lag.api;

import mindless.runtime.AccessorBridge;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;

public final class KnockbackPacketPolicy implements InboundClaimPolicy {
    private static final class Control {
        private final SessionEpoch epoch;
        private final boolean eligible;
        private final int localEntityId;
        private final long delayNanos;
        private final double chancePercent;

        private Control(SessionEpoch epoch, boolean eligible, int localEntityId, long delayMs, double chancePercent) {
            this.epoch = epoch;
            this.eligible = eligible;
            this.localEntityId = localEntityId;
            this.delayNanos = DelayRequest.millisToNanos(delayMs);
            this.chancePercent = chancePercent;
        }
    }

    private volatile Control control = new Control(null, false, Integer.MIN_VALUE, 0, 0);
    private SessionEpoch stateEpoch = new SessionEpoch(0L, 0L);
    private boolean holding;
    private long deadlineNanos;
    private long selectedDelayNanos;

    public void configure(SessionEpoch epoch, boolean eligible, int localEntityId, long delayMs, double chancePercent) {
        control = new Control(epoch, eligible, localEntityId, delayMs, chancePercent);
    }

    public void disable() {
        control = new Control(null, false, Integer.MIN_VALUE, 0, 0);
    }

    public long chooseDelayNanos() {
        return selectedDelayNanos;
    }

    @Override
    public void onRelease(SessionEpoch epoch, long nowNanos) {
        stateEpoch = epoch;
        holding = false;
        deadlineNanos = 0L;
        selectedDelayNanos = 0L;
    }

    @Override
    public boolean shouldRelease(SessionEpoch epoch, long nowNanos) {
        Control current = control;
        return holding && (!stateEpoch.isSameSession(epoch) || !isEligible(current, epoch)
                || nowNanos >= deadlineNanos);
    }

    @Override
    public Decision decide(Packet<?> packet, SessionEpoch epoch, long nowNanos) {
        if (!stateEpoch.isSameSession(epoch)) onRelease(epoch, nowNanos);
        Control current = control;
        if (packet instanceof S08PacketPlayerPosLook) return Decision.RELEASE_AND_PASS;
        if (!isEligible(current, epoch) || holding && nowNanos >= deadlineNanos) {
            return holding ? Decision.RELEASE_AND_PASS : Decision.PASS;
        }
        if (packet instanceof S00PacketKeepAlive || packet instanceof S32PacketConfirmTransaction) {
            return Decision.BYPASS;
        }
        if (packet instanceof S06PacketUpdateHealth) {
            return ((S06PacketUpdateHealth) packet).getHealth() <= 0.0F
                    ? Decision.RELEASE_AND_PASS : Decision.BYPASS;
        }
        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            if (status.getOpCode() == 3 && AccessorBridge.S19PacketEntityStatus_getEntityId(status) == current.localEntityId) {
                return Decision.RELEASE_AND_PASS;
            }
            if (status.getOpCode() == 2 || status.getOpCode() == 3) return Decision.BYPASS;
        }
        if (packet instanceof S27PacketExplosion) {
            S27PacketExplosion explosion = (S27PacketExplosion) packet;
            if (explosion.func_149149_c() != 0.0F || explosion.func_149144_d() != 0.0F
                    || explosion.func_149147_e() != 0.0F) return Decision.RELEASE_AND_PASS;
        }
        boolean localVelocity = packet instanceof S12PacketEntityVelocity
                && ((S12PacketEntityVelocity) packet).getEntityID() == current.localEntityId;
        if (holding) return localVelocity ? Decision.RELEASE_AND_PASS : Decision.CLAIM;
        if (!localVelocity || current.chancePercent <= 0.0D
                || current.chancePercent < 100.0D && Math.random() * 100.0D >= current.chancePercent) return Decision.PASS;
        selectedDelayNanos = current.delayNanos;
        deadlineNanos = safeAdd(nowNanos, selectedDelayNanos);
        holding = selectedDelayNanos > 0L;
        return holding ? Decision.CLAIM : Decision.PASS;
    }

    private boolean isEligible(Control current, SessionEpoch epoch) {
        return current.eligible && current.epoch != null && current.epoch.isSameSession(epoch);
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
