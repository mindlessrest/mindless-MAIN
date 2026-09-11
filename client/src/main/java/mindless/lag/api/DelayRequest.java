package mindless.lag.api;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.LongSupplier;

public final class DelayRequest {
    public enum DeadlineMode {
        PER_PACKET,
        FIXED_WINDOW
    }

    private final String ownerLabel;
    private final Set<EnumLagDirection> directions;
    private final InboundClaimPolicy inboundClaimPolicy;
    private final DeadlineMode deadlineMode;
    private final long delayNanos;
    private final LongSupplier windowDelayNanosSupplier;
    private java.util.function.BiPredicate<net.minecraft.network.Packet<?>, Boolean> outboundPolicy = (packet, queued) -> true;

    public static DelayRequest auraAutoBlock() {
        DelayRequest request = perPacketMillis("KillAuraAutoBlock", EnumSet.of(EnumLagDirection.OUTBOUND), 5000);
        request.outboundPolicy = (packet, queued) -> !(packet instanceof net.minecraft.network.play.client.C00PacketKeepAlive)
                && !(packet instanceof net.minecraft.network.play.client.C01PacketChatMessage)
                && (!(packet instanceof net.minecraft.network.play.client.C0FPacketConfirmTransaction) || queued);
        return request;
    }

    public boolean claimsOutbound(net.minecraft.network.Packet<?> packet, boolean queued) {
        return outboundPolicy.test(packet, queued);
    }

    public DelayRequest(
            String ownerLabel,
            Set<EnumLagDirection> directions,
            InboundClaimPolicy inboundClaimPolicy,
            DeadlineMode deadlineMode,
            long delayNanos
    ) {
        this(ownerLabel, directions, inboundClaimPolicy, deadlineMode, delayNanos, () -> delayNanos);
    }

    public DelayRequest(
            String ownerLabel,
            Set<EnumLagDirection> directions,
            InboundClaimPolicy inboundClaimPolicy,
            DeadlineMode deadlineMode,
            long delayNanos,
            LongSupplier windowDelayNanosSupplier
    ) {
        if (ownerLabel == null || ownerLabel.trim().isEmpty()) throw new IllegalArgumentException("ownerLabel");
        if (directions == null || directions.isEmpty()) throw new IllegalArgumentException("directions");
        if (inboundClaimPolicy == null) throw new IllegalArgumentException("inboundClaimPolicy");
        if (deadlineMode == null) throw new IllegalArgumentException("deadlineMode");
        if (delayNanos < 0L) throw new IllegalArgumentException("delayNanos");
        if (windowDelayNanosSupplier == null) throw new IllegalArgumentException("windowDelayNanosSupplier");
        this.ownerLabel = ownerLabel;
        this.directions = Collections.unmodifiableSet(EnumSet.copyOf(directions));
        this.inboundClaimPolicy = inboundClaimPolicy;
        this.deadlineMode = deadlineMode;
        this.delayNanos = delayNanos;
        this.windowDelayNanosSupplier = windowDelayNanosSupplier;
    }

    public static DelayRequest perPacket(String ownerLabel, Set<EnumLagDirection> directions, long delayNanos) {
        return new DelayRequest(ownerLabel, directions, InboundClaimPolicy.ALWAYS,
                DeadlineMode.PER_PACKET, delayNanos);
    }

    public static DelayRequest perPacketMillis(String ownerLabel, Set<EnumLagDirection> directions, long delayMs) {
        return perPacket(ownerLabel, directions, millisToNanos(delayMs));
    }

    public static DelayRequest fixedWindow(
            String ownerLabel,
            Set<EnumLagDirection> directions,
            InboundClaimPolicy inboundClaimPolicy,
            long delayNanos
    ) {
        return new DelayRequest(ownerLabel, directions, inboundClaimPolicy,
                DeadlineMode.FIXED_WINDOW, delayNanos);
    }

    public static DelayRequest fixedWindow(
            String ownerLabel,
            Set<EnumLagDirection> directions,
            InboundClaimPolicy inboundClaimPolicy,
            LongSupplier windowDelayNanosSupplier
    ) {
        return new DelayRequest(ownerLabel, directions, inboundClaimPolicy,
                DeadlineMode.FIXED_WINDOW, 0L, windowDelayNanosSupplier);
    }

    public static DelayRequest fixedWindowMillis(
            String ownerLabel,
            Set<EnumLagDirection> directions,
            InboundClaimPolicy inboundClaimPolicy,
            long delayMs
    ) {
        return fixedWindow(ownerLabel, directions, inboundClaimPolicy, millisToNanos(delayMs));
    }

    public String getOwnerLabel() {
        return ownerLabel;
    }

    public Set<EnumLagDirection> getDirections() {
        return directions;
    }

    public InboundClaimPolicy getInboundClaimPolicy() {
        return inboundClaimPolicy;
    }

    public DeadlineMode getDeadlineMode() {
        return deadlineMode;
    }

    public long getDelayNanos() {
        return delayNanos;
    }

    public long chooseWindowDelayNanos() {
        long value = windowDelayNanosSupplier.getAsLong();
        return Math.max(0L, value);
    }

    public static long millisToNanos(long millis) {
        if (millis <= 0L) return 0L;
        if (millis >= Long.MAX_VALUE / 1000000L) return Long.MAX_VALUE;
        return millis * 1000000L;
    }
}
