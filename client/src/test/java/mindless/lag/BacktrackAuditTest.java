package mindless.lag;

import io.netty.buffer.Unpooled;
import mindless.lag.api.BacktrackControlSnapshot;
import mindless.lag.api.BacktrackPacketPolicy;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.service.PacketDelayService;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class BacktrackAuditTest {
    private static final class Session {
        private long now;
        private final BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        private final PacketDelayService service;
        private final DelayLease lease;
        private final List<Packet<?>> delivered = new ArrayList<>();

        private Session() { this(512); }

        private Session(int capacity) {
            service = new PacketDelayService(() -> now, Runnable::run, null, capacity, 5000L);
            lease = service.acquire(DelayRequest.fixedWindow(
                    "Backtrack", EnumLagDirection.ONLY_INBOUND, policy, policy::chooseWindowDelayNanos));
        }

        private void control(BacktrackControlSnapshot.Builder builder) {
            policy.setControl(builder.build());
            service.drainExpired();
        }

        private void receive(Packet<?> packet) {
            service.handleInbound(packet, service.getCurrentEpoch(), delivered::add);
        }

        private void advance(long time) {
            now = time;
            service.drainExpired();
        }

        private void open() {
            control(settings(7));
            receive(teleport(7, 64));
            delivered.clear();
            receive(move(7, 32));
            Assert.assertEquals(1, service.getPendingCount(EnumLagDirection.INBOUND));
        }
    }

    private static BacktrackControlSnapshot.Builder settings(int target) {
        return BacktrackControlSnapshot.builder().enabled(true).targetEligible(true)
                .localEntityId(3).targetEntityId(target).attackAtNanos(0L).attackWindowNanos(1000L)
                .minDelayNanos(100L).maxDelayNanos(100L).cooldownNanos(90L)
                .eye(0.0D, 1.6D, 0.0D).displayed(2.0D, 0.0D, 0.0D).distance(0.0D, 6.0D);
    }

    private static Packet<?> teleport(int id, int x) {
        return new S18PacketEntityTeleport(id, x, 0, 0, (byte) 0, (byte) 0, true);
    }

    private static Packet<?> move(int id, int x) {
        return new S14PacketEntity.S15PacketEntityRelMove(id, (byte) x, (byte) 0, (byte) 0, true);
    }

    private static Packet<?> status(int id, int opcode) throws IOException {
        PacketBuffer buffer = new PacketBuffer(Unpooled.buffer());
        try {
            buffer.writeInt(id);
            buffer.writeByte(opcode);
            S19PacketEntityStatus packet = new S19PacketEntityStatus();
            packet.readPacketData(buffer);
            return packet;
        } finally {
            buffer.release();
        }
    }

    @Test
    public void respawnDiscardsEvenPacketsWhoseDeadlineHasJustExpired() {
        Session s = new Session();
        s.open();
        s.now = 100L;
        Packet<?> boundary = new net.minecraft.network.play.server.S07PacketRespawn();
        s.receive(boundary);
        Assert.assertEquals(1, s.delivered.size());
        Assert.assertSame(boundary, s.delivered.get(0));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void oldWorldControlCannotSeedOrHoldPacketsInTheNewWorld() {
        Session s = new Session();
        s.control(settings(7).epoch(s.service.getCurrentEpoch()).serverPosition(64, 0, 0));
        s.receive(move(7, 32));
        s.service.setEpoch(s.service.getCurrentEpoch().nextWorld());
        s.receive(move(7, 32));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        s.control(settings(7).epoch(s.service.getCurrentEpoch()).serverPosition(96, 0, 0));
        s.receive(move(7, 32));
        Assert.assertEquals(4.0D, s.policy.getPoseSnapshot().getShadowX(), 0.0D);
    }

    @Test
    public void keepAliveReleasesBacktrackBeforeBeingDelivered() {
        Session s = new Session();
        s.open();
        Packet<?> packet = new net.minecraft.network.play.server.S00PacketKeepAlive();
        s.receive(packet);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertSame(packet, s.delivered.get(1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void transactionReleasesBacktrackBeforeBeingDelivered() {
        Session s = new Session();
        s.open();
        Packet<?> packet = new net.minecraft.network.play.server.S32PacketConfirmTransaction();
        s.receive(packet);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertSame(packet, s.delivered.get(1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void correctionRequiresANewAcceptedAttack() {
        Session s = new Session();
        s.open();
        s.receive(new S08PacketPlayerPosLook());
        s.advance(100L);
        s.receive(move(7, 1));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        s.control(settings(7).attackAtNanos(100L));
        s.receive(move(7, 1));
        Assert.assertTrue(s.policy.isWindowActive(s.now));
    }

    @Test
    public void localDeathCannotBeBypassedByChangingTarget() throws IOException {
        Session s = new Session();
        s.open();
        s.receive(status(3, 3));
        s.advance(100L);
        s.control(settings(8).serverPosition(64, 0, 0).attackAtNanos(100L));
        s.receive(move(8, 32));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void healthDeathCannotBeBypassedByChangingTarget() {
        Session s = new Session();
        s.open();
        s.receive(new net.minecraft.network.play.server.S06PacketUpdateHealth(0.0F, 20, 5.0F));
        s.advance(100L);
        s.control(settings(8).serverPosition(64, 0, 0));
        s.receive(move(8, 32));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
    }

    @Test
    public void enablingWithoutAWorldDoesNotActivateTheModuleOrAcquireALease() throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        mindless.module.impl.network.Backtrack module =
                (mindless.module.impl.network.Backtrack) unsafe.allocateInstance(mindless.module.impl.network.Backtrack.class);
        module.enable();
        Assert.assertFalse(module.isEnabled());
        java.lang.reflect.Field lease = module.getClass().getDeclaredField("lease");
        lease.setAccessible(true);
        Assert.assertNull(lease.get(module));
    }

    @Test
    public void deferredLeaseInitializationCannotExtendAnAlreadyOpenedWindow() {
        long[] now = {0L};
        java.util.ArrayDeque<Runnable> work = new java.util.ArrayDeque<>();
        PacketDelayService service = new PacketDelayService(() -> now[0], work::add, null);
        service.acquire(DelayRequest.fixedWindow("Fixed", EnumLagDirection.ONLY_INBOUND,
                mindless.lag.api.InboundClaimPolicy.ALWAYS, 100L));
        List<Packet<?>> delivered = new ArrayList<>();
        service.handleInbound(move(7, 1), service.getCurrentEpoch(), delivered::add);
        while (!work.isEmpty()) work.removeFirst().run();
        now[0] = 50L;
        service.handleInbound(move(7, 1), service.getCurrentEpoch(), delivered::add);
        now[0] = 100L;
        service.drainExpired();
        while (!work.isEmpty()) work.removeFirst().run();
        Assert.assertEquals(2, delivered.size());
        Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test(timeout = 5000L)
    public void scheduledReleaseRunsWithoutTicksOrFurtherPackets() throws Exception {
        java.util.concurrent.ScheduledExecutorService loop = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
        try {
            BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
            PacketDelayService service = new PacketDelayService(System::nanoTime, loop, loop);
            loop.submit(() -> {
                long now = System.nanoTime();
                policy.setControl(settings(7).attackAtNanos(now).attackWindowNanos(2000000000L)
                        .minDelayNanos(20000000L).maxDelayNanos(20000000L).serverPosition(64, 0, 0).build());
                service.acquire(DelayRequest.fixedWindow("Backtrack", EnumLagDirection.ONLY_INBOUND,
                        policy, policy::chooseWindowDelayNanos));
                service.handleInbound(move(7, 32), service.getCurrentEpoch(), packet -> delivered.countDown());
            }).get(2L, java.util.concurrent.TimeUnit.SECONDS);
            Assert.assertTrue(delivered.await(2L, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
            Assert.assertFalse(policy.isWindowActive(System.nanoTime()));
        } finally {
            loop.shutdownNow();
            Assert.assertTrue(loop.awaitTermination(2L, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test
    public void targetDeathCannotReopenFromAQueuedMovementPacket() throws IOException {
        Session s = new Session();
        s.open();
        s.receive(status(7, 3));
        s.advance(100L);
        s.receive(move(7, 1));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void failedDeliveryIsReportedAndDoesNotStopTheRestOfTheFlush() {
        Session s = new Session();
        s.open();
        s.service.handleInbound(move(7, 1), s.service.getCurrentEpoch(), packet -> {
            throw new IllegalStateException("test delivery failure");
        });
        Packet<?> last = move(8, 1);
        s.receive(last);
        s.advance(100L);
        Assert.assertEquals(1L, s.service.getDiagnostics().getDeliveryFailures());
        Assert.assertSame(last, s.delivered.get(1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void movementBeforeTargetSelectionUsesTheLatestPosition() {
        Session s = new Session();
        s.receive(teleport(7, 32));
        s.receive(move(7, 32));
        s.control(settings(7));
        s.receive(move(7, 32));
        Assert.assertEquals(3.0D, s.policy.getPoseSnapshot().getShadowX(), 0.0D);
        Assert.assertTrue(s.policy.isWindowActive(s.now));
    }

    @Test
    public void switchingBackToATargetKeepsMovementObservedWhileUnselected() {
        Session s = new Session();
        s.open();
        s.control(settings(8));
        s.receive(move(7, 32));
        s.control(settings(7));
        s.advance(100L);
        s.receive(move(7, 32));
        Assert.assertEquals(5.0D, s.policy.getPoseSnapshot().getShadowX(), 0.0D);
    }

    @Test
    public void enablingAfterSpawnSeedsFromExactServerCoordinates() {
        Session s = new Session();
        s.control(settings(7).serverPosition(64, 0, 0));
        s.receive(move(7, 32));
        Assert.assertEquals(3.0D, s.policy.getPoseSnapshot().getShadowX(), 0.0D);
        Assert.assertTrue(s.policy.isWindowActive(s.now));
    }

    @Test
    public void publishedClientBaselineCannotRewindObservedPackets() {
        Session s = new Session();
        s.receive(teleport(7, 96));
        s.control(settings(7).serverPosition(32, 0, 0));
        s.receive(move(7, 32));
        Assert.assertEquals(4.0D, s.policy.getPoseSnapshot().getShadowX(), 0.0D);
    }

    @Test
    public void targetHurtStaysOrderedByDefault() throws IOException {
        Session s = new Session();
        s.open();
        Packet<?> hurt = status(7, 2);
        Packet<?> other = move(8, 1);
        s.receive(hurt);
        s.receive(other);
        Assert.assertTrue(s.delivered.isEmpty());
        s.advance(100L);
        Assert.assertEquals(3, s.delivered.size());
        Assert.assertSame(hurt, s.delivered.get(1));
        Assert.assertSame(other, s.delivered.get(2));
    }

    @Test
    public void optionalTargetHurtBarrierFlushesBeforeTheHurtPacket() throws IOException {
        Session s = new Session();
        s.open();
        s.control(settings(7).flushOnTargetHit(true));
        Packet<?> hurt = status(7, 2);
        s.receive(hurt);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertSame(hurt, s.delivered.get(1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
    }

    @Test
    public void selfHurtFlushesAndStartsCooldownWithoutAGameTick() throws IOException {
        Session s = new Session();
        s.open();
        s.receive(status(3, 2));
        s.receive(move(7, 1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
        s.advance(90L);
        s.receive(move(7, 1));
        Assert.assertEquals(1, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void timerClosesTheWholeWindowAndStartsCooldownWithoutNewPackets() {
        Session s = new Session();
        s.open();
        s.advance(50L);
        s.receive(move(7, 1));
        s.advance(100L);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        Assert.assertEquals(0L, s.policy.getSelectedWindowDelayNanos());
        s.advance(101L);
        s.receive(move(7, 1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
        s.advance(190L);
        s.receive(move(7, 1));
        Assert.assertEquals(1, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void expiredAttackWindowReleasesBeforeThePacketDeadline() {
        Session s = new Session();
        s.open();
        s.control(settings(7).attackWindowNanos(10L));
        s.advance(11L);
        Assert.assertEquals(1, s.delivered.size());
        Assert.assertFalse(s.policy.isWindowActive(s.now));
    }

    @Test
    public void losingUsefulDistanceFlushesWithoutWaitingForAnotherPacket() {
        Session s = new Session();
        s.open();
        s.control(settings(7).displayed(4.0D, 0.0D, 0.0D));
        Assert.assertEquals(1, s.delivered.size());
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void disabledControlFlushesWithoutIncomingTraffic() {
        Session s = new Session();
        s.open();
        s.control(BacktrackControlSnapshot.builder());
        Assert.assertEquals(1, s.delivered.size());
        Assert.assertFalse(s.policy.isWindowActive(s.now));
    }

    @Test
    public void capacityFlushAlsoEndsPolicyWindow() {
        Session s = new Session(1);
        s.open();
        s.receive(move(8, 1));
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        s.receive(move(7, 1));
        Assert.assertEquals(0, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void releaseDoesNotDiscardAnotherOwnersClaims() {
        Session s = new Session();
        s.open();
        DelayLease other = s.service.acquire(DelayRequest.perPacket("Other", EnumLagDirection.ONLY_INBOUND, 300L));
        Packet<?> shared = move(7, 1);
        s.receive(shared);
        s.lease.release();
        Assert.assertEquals(1, s.delivered.size());
        Assert.assertEquals(1, s.service.getPendingCount(EnumLagDirection.INBOUND));
        other.release();
        Assert.assertSame(shared, s.delivered.get(1));
    }

    @Test
    public void correctionFlushesInOrderAndEndsWindow() {
        Session s = new Session();
        s.open();
        Packet<?> correction = new S08PacketPlayerPosLook();
        s.receive(correction);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertSame(correction, s.delivered.get(1));
        Assert.assertFalse(s.policy.isWindowActive(s.now));
    }

    @Test
    public void destroyRemovesTheBaselineBeforeEntityIdReuse() {
        Session s = new Session();
        s.open();
        s.receive(new S13PacketDestroyEntities(7));
        s.control(settings(8));
        s.receive(move(8, 1));
        s.control(settings(7));
        s.advance(100L);
        s.receive(move(7, 1));
        Assert.assertFalse(s.policy.getPoseSnapshot().hasShadowPosition());
    }

    @Test
    public void worldResetDropsHeldPacketsAndClearsTrackedPositions() {
        Session s = new Session();
        s.open();
        s.service.setEpoch(s.service.getCurrentEpoch().nextWorld());
        Assert.assertTrue(s.delivered.isEmpty());
        Assert.assertFalse(s.policy.isWindowActive(s.now));
        s.receive(move(7, 1));
        Assert.assertFalse(s.policy.getPoseSnapshot().hasShadowPosition());
        Assert.assertEquals(1, s.delivered.size());
    }
}
