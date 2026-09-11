package mindless.lag.api;

import net.minecraft.network.Packet;

@FunctionalInterface
public interface InboundClaimPolicy {
    Decision decide(Packet<?> packet, SessionEpoch epoch, long nowNanos);

    enum Decision {
        PASS,
        BYPASS,
        CLAIM,
        RELEASE_AND_PASS
    }

    InboundClaimPolicy ALWAYS = (packet, epoch, nowNanos) -> Decision.CLAIM;
}
