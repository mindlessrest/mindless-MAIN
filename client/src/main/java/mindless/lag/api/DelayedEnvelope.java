package mindless.lag.api;

import net.minecraft.network.Packet;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public final class DelayedEnvelope {
    public interface DeliveryRoute {
        void deliver(Packet<?> packet);
    }

    private final Packet<?> packet;
    private final EnumLagDirection direction;
    private final SessionEpoch epoch;
    private final long sequence;
    private final long capturedAtNanos;
    private final DeliveryRoute route;
    private final Map<DelayLease, Long> claims = new IdentityHashMap<>();

    public DelayedEnvelope(
            Packet<?> packet,
            EnumLagDirection direction,
            SessionEpoch epoch,
            long sequence,
            long capturedAtNanos,
            DeliveryRoute route
    ) {
        if (packet == null) throw new IllegalArgumentException("packet");
        if (direction == null) throw new IllegalArgumentException("direction");
        if (epoch == null) throw new IllegalArgumentException("epoch");
        if (route == null) throw new IllegalArgumentException("route");
        this.packet = packet;
        this.direction = direction;
        this.epoch = epoch;
        this.sequence = sequence;
        this.capturedAtNanos = capturedAtNanos;
        this.route = route;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    public EnumLagDirection getDirection() {
        return direction;
    }

    public SessionEpoch getEpoch() {
        return epoch;
    }

    public long getSequence() {
        return sequence;
    }

    public long getCapturedAtNanos() {
        return capturedAtNanos;
    }

    public DeliveryRoute getRoute() {
        return route;
    }

    public void claim(DelayLease lease, long deadlineNanos) {
        if (lease == null) throw new IllegalArgumentException("lease");
        claims.put(lease, deadlineNanos);
    }

    public boolean isClaimed() {
        return !claims.isEmpty();
    }

    public boolean isClaimedBy(DelayLease lease) {
        return claims.containsKey(lease);
    }

    public Long getDeadline(DelayLease lease) {
        return claims.get(lease);
    }

    public Map<DelayLease, Long> getClaims() {
        return Collections.unmodifiableMap(new IdentityHashMap<>(claims));
    }

    public boolean release(DelayLease lease) {
        return claims.remove(lease) != null;
    }

    public boolean releaseExpired(long nowNanos) {
        boolean changed = false;
        for (java.util.Iterator<Map.Entry<DelayLease, Long>> iterator = claims.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry<DelayLease, Long> entry = iterator.next();
            if (entry.getValue() <= nowNanos || !entry.getKey().isActive()) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    public void releaseAll() {
        claims.clear();
    }
}
