package mindless.lag;

import mindless.lag.api.BacktrackControlSnapshot;
import mindless.lag.api.BacktrackPacketPolicy;
import mindless.lag.api.BacktrackPoseSnapshot;
import mindless.lag.api.InboundClaimPolicy;
import mindless.lag.api.SessionEpoch;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

public class BacktrackPacketPolicyTest {
    private static final class TestPacket implements Packet<INetHandler> {
        @Override
        public void readPacketData(PacketBuffer buffer) throws IOException {
        }

        @Override
        public void writePacketData(PacketBuffer buffer) throws IOException {
        }

        @Override
        public void processPacket(INetHandler handler) {
        }
    }

    @Test
    public void relativeMovementAccumulatesFromExactTeleportBaseline() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(control(7, 0L, 1000000000L, 100000000L));
        SessionEpoch epoch = new SessionEpoch(2L, 3L);

        Assert.assertEquals(InboundClaimPolicy.Decision.PASS,
                policy.decide(new S18PacketEntityTeleport(7, 64, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L));
        Assert.assertEquals(InboundClaimPolicy.Decision.CLAIM,
                policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) 32, (byte) 0, (byte) 0, true), epoch, 2L));

        BacktrackPoseSnapshot pose = policy.getPoseSnapshot();
        Assert.assertTrue(pose.isActive());
        Assert.assertEquals(3.0D, pose.getShadowX(), 0.000001D);
        Assert.assertEquals(2.0D, pose.getDisplayedX(), 0.000001D);
        Assert.assertEquals(InboundClaimPolicy.Decision.BYPASS,
                policy.decide(new TestPacket(), epoch, 3L));
    }

    @Test
    public void negativeRelativeMovementUsesTheSignedFixedPointDelta() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(controlWithDisplayedX(7, 1.0D));
        SessionEpoch epoch = new SessionEpoch(2L, 3L);

        policy.decide(new S18PacketEntityTeleport(7, 96, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L);
        Assert.assertEquals(InboundClaimPolicy.Decision.CLAIM,
                policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) -32, (byte) 0, (byte) 0, true), epoch, 2L));

        Assert.assertEquals(2.0D, policy.getPoseSnapshot().getShadowX(), 0.000001D);
    }

    @Test
    public void lookOnlyMovementDoesNotChangeTheShadowPosition() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(controlWithDisplayedX(7, 1.0D));
        SessionEpoch epoch = new SessionEpoch(0L, 0L);

        policy.decide(new S18PacketEntityTeleport(7, 96, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L);
        policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) -32, (byte) 0, (byte) 0, true), epoch, 2L);
        Assert.assertEquals(InboundClaimPolicy.Decision.CLAIM,
                policy.decide(new S14PacketEntity.S16PacketEntityLook(7, (byte) 10, (byte) 20, true), epoch, 3L));

        Assert.assertEquals(2.0D, policy.getPoseSnapshot().getShadowX(), 0.000001D);
    }

    @Test
    public void teleportReplacesTheTrustedBaseline() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(controlWithDisplayedX(7, 1.0D));
        SessionEpoch epoch = new SessionEpoch(0L, 0L);

        policy.decide(new S18PacketEntityTeleport(7, 96, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L);
        policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) -32, (byte) 0, (byte) 0, true), epoch, 2L);
        policy.decide(new S18PacketEntityTeleport(7, 128, 0, 0, (byte) 0, (byte) 0, true), epoch, 3L);
        policy.decide(new TestPacket(), epoch, 4L);

        Assert.assertEquals(4.0D, policy.getPoseSnapshot().getShadowX(), 0.000001D);
    }

    @Test
    public void movementWithoutTrustedBaselineDoesNotInventOne() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(control(7, 0L, 1000000000L, 100000000L));

        Assert.assertEquals(InboundClaimPolicy.Decision.PASS,
                policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) 32, (byte) 0, (byte) 0, true),
                        new SessionEpoch(0L, 0L), 1L));
        Assert.assertFalse(policy.getPoseSnapshot().hasShadowPosition());
    }

    @Test
    public void fixedWindowStopsAtItsOriginalDeadline() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(control(7, 0L, 1000000000L, 100L));
        SessionEpoch epoch = new SessionEpoch(0L, 0L);
        policy.decide(new S18PacketEntityTeleport(7, 64, 0, 0, (byte) 0, (byte) 0, true), epoch, 0L);
        Assert.assertEquals(InboundClaimPolicy.Decision.CLAIM,
                policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) 32, (byte) 0, (byte) 0, true), epoch, 1L));
        Assert.assertEquals(InboundClaimPolicy.Decision.RELEASE_AND_PASS,
                policy.decide(new TestPacket(), epoch, 101L));
        Assert.assertFalse(policy.isWindowActive(101L));
    }

    @Test
    public void attackWindowExpiryPreventsAWindowFromOpening() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(control(7, 1000L, 100L, 100000000L));
        SessionEpoch epoch = new SessionEpoch(0L, 0L);

        policy.decide(new S18PacketEntityTeleport(7, 96, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L);
        Assert.assertEquals(InboundClaimPolicy.Decision.PASS,
                policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) -32, (byte) 0, (byte) 0, true), epoch, 1101L));
        Assert.assertFalse(policy.isWindowActive(1101L));
    }

    @Test
    public void destroyingTheTargetReleasesOnlyTheBacktrackWindow() {
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(controlWithDisplayedX(7, 1.0D));
        SessionEpoch epoch = new SessionEpoch(0L, 0L);

        policy.decide(new S18PacketEntityTeleport(7, 96, 0, 0, (byte) 0, (byte) 0, true), epoch, 1L);
        policy.decide(new S14PacketEntity.S15PacketEntityRelMove(7, (byte) -32, (byte) 0, (byte) 0, true), epoch, 2L);
        Assert.assertEquals(InboundClaimPolicy.Decision.RELEASE_AND_PASS,
                policy.decide(new S13PacketDestroyEntities(7), epoch, 3L));
        Assert.assertFalse(policy.isWindowActive(3L));
    }

    private static BacktrackControlSnapshot control(int targetId, long attackAt, long attackWindow, long delay) {
        return BacktrackControlSnapshot.builder()
                .enabled(true)
                .targetEligible(true)
                .localEntityId(3)
                .targetEntityId(targetId)
                .attackAtNanos(attackAt)
                .attackWindowNanos(attackWindow)
                .minDelayNanos(delay)
                .maxDelayNanos(delay)
                .eye(0.0D, 1.6D, 0.0D)
                .displayed(2.0D, 0.0D, 0.0D)
                .size(0.6D, 1.8D)
                .distance(0.0D, 4.0D)
                .build();
    }

    private static BacktrackControlSnapshot controlWithDisplayedX(int targetId, double displayedX) {
        return BacktrackControlSnapshot.builder()
                .enabled(true)
                .targetEligible(true)
                .localEntityId(3)
                .targetEntityId(targetId)
                .attackAtNanos(0L)
                .attackWindowNanos(1000000000L)
                .minDelayNanos(100000000L)
                .maxDelayNanos(100000000L)
                .eye(0.0D, 1.6D, 0.0D)
                .displayed(displayedX, 0.0D, 0.0D)
                .size(0.6D, 1.8D)
                .distance(0.0D, 4.0D)
                .build();
    }
}
