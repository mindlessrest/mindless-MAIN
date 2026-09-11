package mindless.lag;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import mindless.event.SendPacketEvent;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.DelayedEnvelope;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.BacktrackControlSnapshot;
import mindless.lag.api.BacktrackPacketPolicy;
import mindless.lag.api.InboundClaimPolicy;
import mindless.lag.api.SessionEpoch;
import mindless.lag.service.PacketDelayService;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class PacketDelayServiceTest {
    private static final class ManualClock implements PacketDelayService.Clock {
        private long now;

        @Override
        public long nanoTime() {
            return now;
        }

        private void set(long value) {
            now = value;
        }
    }

    private static final class TestPacket implements Packet<INetHandler> {
        private final String name;

        private TestPacket(String name) {
            this.name = name;
        }

        @Override
        public void readPacketData(PacketBuffer buffer) throws IOException {
        }

        @Override
        public void writePacketData(PacketBuffer buffer) throws IOException {
        }

        @Override
        public void processPacket(INetHandler handler) {
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class CapturingNetworkManager extends NetworkManager {
        private final List<Packet<?>> sent = new ArrayList<>();

        private CapturingNetworkManager() {
            super(EnumPacketDirection.CLIENTBOUND);
        }

        @Override
        public void sendPacket(Packet packet) {
            sent.add(packet);
        }
    }

    @Test
    public void releasesOnlyTheContiguousPrefix() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        DelayLease lease = service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("a"), epoch, delivered::add);
        service.handleInbound(new TestPacket("b"), epoch, delivered::add);
        lease.releaseNext(EnumLagDirection.INBOUND);

        Assert.assertEquals(Arrays.asList("a"), names(delivered));
        Assert.assertEquals(1, service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void allOwnersMustReleaseBeforeAClaimedPacketMoves() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        DelayLease firstOwner = service.acquire(DelayRequest.fixedWindow(
                "first", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        DelayLease secondOwner = service.acquire(DelayRequest.fixedWindow(
                "second", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("shared"), epoch, delivered::add);
        secondOwner.releaseNext(EnumLagDirection.INBOUND);
        Assert.assertTrue(delivered.isEmpty());
        firstOwner.releaseNext(EnumLagDirection.INBOUND);

        Assert.assertEquals(Arrays.asList("shared"), names(delivered));
    }

    @Test
    public void unclaimedFollowerWaitsBehindAClaimedPrefix() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        final boolean[] claimFirst = {true};
        InboundClaimPolicy firstOnly = (packet, epoch, now) -> {
            if (claimFirst[0]) {
                claimFirst[0] = false;
                return InboundClaimPolicy.Decision.CLAIM;
            }
            return InboundClaimPolicy.Decision.PASS;
        };
        DelayLease lease = service.acquire(DelayRequest.fixedWindow(
                "first-only", EnumLagDirection.ONLY_INBOUND, firstOnly, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("claimed"), epoch, delivered::add);
        service.handleInbound(new TestPacket("follower"), epoch, delivered::add);
        Assert.assertTrue(delivered.isEmpty());
        lease.releaseNext(EnumLagDirection.INBOUND);

        Assert.assertEquals(Arrays.asList("claimed", "follower"), names(delivered));
    }

    @Test
    public void backtrackPreservesInboundOrderDuringTheWindow() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        BacktrackPacketPolicy policy = new BacktrackPacketPolicy(() -> 0.0D);
        policy.setControl(BacktrackControlSnapshot.builder()
                .enabled(true)
                .targetEligible(true)
                .localEntityId(3)
                .targetEntityId(7)
                .attackAtNanos(0L)
                .attackWindowNanos(1000L)
                .minDelayNanos(100L)
                .maxDelayNanos(100L)
                .eye(0.0D, 1.6D, 0.0D)
                .displayed(2.0D, 0.0D, 0.0D)
                .size(0.6D, 1.8D)
                .distance(0.0D, 4.0D)
                .build());
        service.acquire(DelayRequest.fixedWindow(
                "Backtrack", EnumLagDirection.ONLY_INBOUND, policy, policy::chooseWindowDelayNanos));
        SessionEpoch epoch = service.getCurrentEpoch();
        List<Packet<?>> delivered = new ArrayList<>();

        service.handleInbound(
                new S18PacketEntityTeleport(7, 64, 0, 0, (byte) 0, (byte) 0, true),
                epoch, delivered::add);
        delivered.clear();
        service.handleInbound(
                new S14PacketEntity.S15PacketEntityRelMove(7, (byte) 32, (byte) 0, (byte) 0, true),
                epoch, delivered::add);
        TestPacket worldPacket = new TestPacket("world-state");
        service.handleInbound(worldPacket, epoch, delivered::add);

        Assert.assertTrue(delivered.isEmpty());
        Assert.assertEquals(2, service.getPendingCount(EnumLagDirection.INBOUND));
        clock.set(100L);
        service.drainExpired();
        Assert.assertEquals(2, delivered.size());
        Assert.assertSame(worldPacket, delivered.get(1));
        Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void dropsDeferredOutboundPacketFromClosedConnection() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        PacketDelayService service = new PacketDelayService(() -> 0L, work::add, null);
        CapturingNetworkManager connection = new CapturingNetworkManager();
        service.bindNetworkManager(connection);
        DelayLease lease = service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_OUTBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        drain(work);

        SendPacketEvent event = new SendPacketEvent(new C03PacketPlayer());
        service.onSendPacket(event);
        Assert.assertTrue(event.isCanceled());
        service.onChannelInactive(connection);
        lease.release();
        drain(work);

        Assert.assertTrue(connection.sent.isEmpty());
    }

    @Test
    public void releasesDeferredOutboundPacketForActiveConnection() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        PacketDelayService service = new PacketDelayService(() -> 0L, work::add, null);
        CapturingNetworkManager connection = new CapturingNetworkManager();
        service.bindNetworkManager(connection);
        DelayLease lease = service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_OUTBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        drain(work);

        SendPacketEvent event = new SendPacketEvent(new C03PacketPlayer());
        service.onSendPacket(event);
        drain(work);
        Assert.assertTrue(connection.sent.isEmpty());
        lease.release();
        drain(work);

        Assert.assertEquals(1, connection.sent.size());
    }

    @Test
    public void fixedWindowUsesOneDeadlineForTheWholeWindow() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        DelayLease lease = service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 100L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("a"), epoch, delivered::add);
        clock.set(50L);
        service.handleInbound(new TestPacket("b"), epoch, delivered::add);
        clock.set(99L);
        service.drainExpired();
        Assert.assertTrue(delivered.isEmpty());
        clock.set(100L);
        service.drainExpired();
        Assert.assertEquals(Arrays.asList("a", "b"), names(delivered));
        Assert.assertTrue(lease.isActive());
    }

    @Test
    public void barrierFlushesBeforeItPasses() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("before"), epoch, delivered::add);
        service.handleInbound(new S08PacketPlayerPosLook(), epoch, delivered::add);

        Assert.assertEquals(Arrays.asList("before", "S08PacketPlayerPosLook"), names(delivered));
        Assert.assertEquals(1L, service.getDiagnostics().getBarrierFlushes());
    }

    @Test
    public void capacityFlushPreservesOrderAndPassesCurrentPacket() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(
                clock, Runnable::run, null, 2, 5000000000L);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("a"), epoch, delivered::add);
        service.handleInbound(new TestPacket("b"), epoch, delivered::add);
        service.handleInbound(new TestPacket("c"), epoch, delivered::add);

        Assert.assertEquals(Arrays.asList("a", "b", "c"), names(delivered));
        Assert.assertEquals(1L, service.getDiagnostics().getCapacityFlushes());
    }

    @Test
    public void maxAgeFlushesEvenALeaseWithAClosedDeadline() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null, 512, 100L);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch epoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("old"), epoch, delivered::add);
        clock.set(101L);
        service.drainExpired();

        Assert.assertEquals(Arrays.asList("old"), names(delivered));
        Assert.assertEquals(1L, service.getDiagnostics().getMaxAgeFlushes());
        Assert.assertEquals(PacketDelayService.ReleaseReason.MAX_AGE,
                service.getDiagnostics().getLastReleaseReason());
    }

    @Test
    public void finalMinecraftDeliveryRejectsAnOldEpoch() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        SessionEpoch epoch = service.getCurrentEpoch();
        TestPacket packet = new TestPacket("replay");
        AtomicInteger executions = new AtomicInteger();

        PacketDelayService.markReplay(service, packet, epoch);
        Assert.assertTrue(PacketDelayService.consumeReplay(packet));
        Runnable guarded = PacketDelayService.guardFinalDelivery(executions::incrementAndGet);
        service.setEpoch(epoch.nextWorld());
        guarded.run();

        Assert.assertEquals(0, executions.get());
    }

    @Test
    public void oldEpochPacketsAreDropped() {
        ManualClock clock = new ManualClock();
        PacketDelayService service = new PacketDelayService(clock, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, 1000000000L));
        List<Packet<?>> delivered = new ArrayList<>();
        SessionEpoch oldEpoch = service.getCurrentEpoch();

        service.handleInbound(new TestPacket("old"), oldEpoch, delivered::add);
        SessionEpoch newEpoch = oldEpoch.nextWorld();
        service.setEpoch(newEpoch);
        service.handleInbound(new TestPacket("late"), oldEpoch, delivered::add);
        service.handleInbound(new TestPacket("new"), newEpoch, delivered::add);
        service.flush(EnumLagDirection.INBOUND);

        Assert.assertEquals(Arrays.asList("new"), names(delivered));
        Assert.assertEquals(1L, service.getDiagnostics().getDroppedOldEpoch());
    }

    @Test
    public void oldConnectionPacketsStayInTheirOriginalWorldSession() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        SessionEpoch originalEpoch = service.getCurrentEpoch();
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        EmbeddedChannel channel = new EmbeddedChannel(
                new mindless.lag.service.PacketDelayChannelHandler(service, originalEpoch));

        service.advanceWorld();
        Assert.assertFalse(channel.writeInbound(new TestPacket("late")));
        Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
        Assert.assertEquals(1L, service.getDiagnostics().getDroppedOldEpoch());
        channel.finish();
    }

    @Test
    public void channelHandlerTracksTheSessionCreatedByJoinGame() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND,
                (packet, epoch, now) -> InboundClaimPolicy.Decision.PASS, Long.MAX_VALUE));
        EmbeddedChannel channel = new EmbeddedChannel(
                new mindless.lag.service.PacketDelayChannelHandler(service, service.getCurrentEpoch()));
        S01PacketJoinGame join = new S01PacketJoinGame();

        Assert.assertTrue(channel.writeInbound(join));
        Assert.assertSame(join, channel.readInbound());
        TestPacket afterJoin = new TestPacket("after-join");
        Assert.assertTrue(channel.writeInbound(afterJoin));
        Assert.assertSame(afterJoin, channel.readInbound());
        channel.finish();
    }

    @Test
    public void queuedResetCannotInvalidatePacketsAfterJoin() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        PacketDelayService service = new PacketDelayService(() -> 0L, work::add, null);
        service.advanceConnection();
        EmbeddedChannel channel = new EmbeddedChannel(
                new mindless.lag.service.PacketDelayChannelHandler(service, service.getCurrentEpoch()));
        S01PacketJoinGame join = new S01PacketJoinGame();
        Assert.assertTrue(channel.writeInbound(join));
        Assert.assertSame(join, channel.readInbound());
        SessionEpoch joinedEpoch = service.getCurrentEpoch();
        PacketDelayService.consumeReplay(join);
        AtomicInteger joinDeliveries = new AtomicInteger();
        Runnable delivery = PacketDelayService.guardFinalDelivery(joinDeliveries::incrementAndGet);

        drain(work);

        Assert.assertTrue(service.getCurrentEpoch().isSameSession(joinedEpoch));
        TestPacket update = new TestPacket("chunk-or-entity-update");
        Assert.assertTrue(channel.writeInbound(update));
        Assert.assertSame(update, channel.readInbound());
        delivery.run();
        Assert.assertEquals(1, joinDeliveries.get());
        PacketDelayService.consumeReplay(update);
        PacketDelayService.clearFinalDelivery(update);
        channel.finish();
    }

    @Test
    public void joinAndRespawnBurstReachesMinecraftInOrder() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "Backtrack", EnumLagDirection.ONLY_INBOUND, new BacktrackPacketPolicy(), Long.MAX_VALUE));
        ArrayDeque<Runnable> minecraftTasks = new ArrayDeque<>();
        List<Packet<?>> delivered = new ArrayList<>();
        EmbeddedChannel channel = deferredMinecraftChannel(service, minecraftTasks, delivered);
        Packet<?>[] burst = {new S01PacketJoinGame(), new S21PacketChunkData(),
                new S07PacketRespawn(), new S07PacketRespawn(), new S18PacketEntityTeleport()};
        try {
            for (Packet<?> packet : burst) channel.writeInbound(packet);
            Assert.assertTrue(delivered.isEmpty());
            drain(minecraftTasks);
            Assert.assertEquals(Arrays.asList(burst), delivered);
        } finally {
            channel.finish();
        }
    }

    @Test
    public void disconnectStillRejectsQueuedJoinAndRespawnBurst() {
        for (boolean replaceConnection : new boolean[]{false, true}) {
            PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
            service.acquire(DelayRequest.fixedWindow(
                    "Backtrack", EnumLagDirection.ONLY_INBOUND, new BacktrackPacketPolicy(), Long.MAX_VALUE));
            ArrayDeque<Runnable> minecraftTasks = new ArrayDeque<>();
            List<Packet<?>> delivered = new ArrayList<>();
            EmbeddedChannel channel = deferredMinecraftChannel(service, minecraftTasks, delivered);
            try {
                channel.writeInbound(new S01PacketJoinGame());
                channel.writeInbound(new S07PacketRespawn());
                if (replaceConnection) service.advanceConnection();
                else service.onClientWorldUnload();
                drain(minecraftTasks);
                Assert.assertTrue(delivered.isEmpty());
            } finally {
                channel.finish();
            }
        }
    }

    @Test
    public void respawnDiscardsHeldMovementButPreservesQueuedJoin() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND,
                (packet, epoch, now) -> packet instanceof S14PacketEntity
                        ? InboundClaimPolicy.Decision.CLAIM : InboundClaimPolicy.Decision.BYPASS,
                Long.MAX_VALUE));
        ArrayDeque<Runnable> minecraftTasks = new ArrayDeque<>();
        List<Packet<?>> delivered = new ArrayList<>();
        EmbeddedChannel channel = deferredMinecraftChannel(service, minecraftTasks, delivered);
        S01PacketJoinGame join = new S01PacketJoinGame();
        S07PacketRespawn respawn = new S07PacketRespawn();
        channel.pipeline().addFirst(PacketDelayService.HANDLER_NAME,
                new mindless.lag.service.PacketDelayChannelHandler(service, service.getCurrentEpoch()));
        try {
            channel.writeInbound(join);
            channel.writeInbound(new S14PacketEntity.S15PacketEntityRelMove(
                    7, (byte) 32, (byte) 0, (byte) 0, true));
            Assert.assertEquals(1, service.getPendingCount(EnumLagDirection.INBOUND));
            channel.writeInbound(respawn);
            Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
            drain(minecraftTasks);
            Assert.assertEquals(Arrays.asList(join, respawn), delivered);
        } finally {
            channel.finish();
        }
    }

    private static EmbeddedChannel deferredMinecraftChannel(
            PacketDelayService service, ArrayDeque<Runnable> tasks, List<Packet<?>> delivered) {
        return new EmbeddedChannel(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                Packet<?> packet = (Packet<?>) message;
                if (!PacketDelayService.consumeReplay(packet)
                        && service.interceptFirstInbound(context, packet)) return;
                if (!PacketDelayService.checkFinalDelivery(packet, false)) return;
                tasks.add(PacketDelayService.guardFinalDelivery(() -> delivered.add(packet)));
                PacketDelayService.clearFinalDelivery(packet);
            }
        });
    }

    @Test
    public void obsoleteResetDoesNotDiscardNewWorldQueue() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        PacketDelayService service = new PacketDelayService(() -> 0L, work::add, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        drain(work);
        service.advanceWorld();
        service.advanceWorld();
        SessionEpoch current = service.getCurrentEpoch();
        List<Packet<?>> delivered = new ArrayList<>();
        service.handleInbound(new TestPacket("current"), current, delivered::add);

        drain(work);

        Assert.assertTrue(service.getCurrentEpoch().isSameSession(current));
        Assert.assertEquals(1, service.getPendingCount(EnumLagDirection.INBOUND));
        Assert.assertTrue(delivered.isEmpty());
    }

    @Test
    public void lateJoinFromAnOldWorldCannotAdvanceItsChannelHandler() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND,
                (packet, epoch, now) -> InboundClaimPolicy.Decision.PASS, Long.MAX_VALUE));
        EmbeddedChannel channel = new EmbeddedChannel(
                new mindless.lag.service.PacketDelayChannelHandler(service, service.getCurrentEpoch()));

        service.advanceWorld();
        Assert.assertFalse(channel.writeInbound(new S01PacketJoinGame()));
        Assert.assertFalse(channel.writeInbound(new TestPacket("late")));
        Assert.assertEquals(2L, service.getDiagnostics().getDroppedOldEpoch());
        channel.finish();
    }

    @Test
    public void currentJoinReplayIsAcceptedBeforeTheClientWorldExists() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        S01PacketJoinGame join = new S01PacketJoinGame();
        SessionEpoch epoch = service.getCurrentEpoch();

        PacketDelayService.markReplay(service, join, epoch);
        Assert.assertTrue(PacketDelayService.consumeReplay(join));
        try {
            Assert.assertTrue(PacketDelayService.isFinalDeliveryCurrent(join));
        }
        finally {
            PacketDelayService.clearFinalDelivery(join);
        }
    }

    @Test
    public void worldUnloadDiscardsQueuedPacketsInsteadOfReplayingThem() {
        PacketDelayService service = new PacketDelayService(() -> 0L, Runnable::run, null);
        service.acquire(DelayRequest.fixedWindow(
                "test", EnumLagDirection.ONLY_INBOUND, InboundClaimPolicy.ALWAYS, Long.MAX_VALUE));
        List<Packet<?>> delivered = new ArrayList<>();

        service.handleInbound(new TestPacket("queued"), service.getCurrentEpoch(), delivered::add);
        service.onClientWorldUnload();

        Assert.assertTrue(delivered.isEmpty());
        Assert.assertEquals(0, service.getPendingCount(EnumLagDirection.INBOUND));
        Assert.assertEquals(PacketDelayService.ReleaseReason.SESSION_RESET,
                service.getDiagnostics().getLastReleaseReason());
    }

    private static List<String> names(List<Packet<?>> packets) {
        List<String> names = new ArrayList<>();
        for (Packet<?> packet : packets) names.add(packet instanceof S08PacketPlayerPosLook
                ? "S08PacketPlayerPosLook" : packet.toString());
        return names;
    }

    private static void drain(ArrayDeque<Runnable> work) {
        while (!work.isEmpty()) work.removeFirst().run();
    }
}
