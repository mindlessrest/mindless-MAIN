package mindless.lag.service;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import mindless.event.SendPacketEvent;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.DelayedEnvelope;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.InboundClaimPolicy;
import mindless.lag.api.SessionEpoch;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import mindless.utility.PacketUtils;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PacketDelayService {
    public static final String HANDLER_NAME = "mindless_packet_delay";
    public static final int DEFAULT_MAX_PACKETS = 512;
    public static final long DEFAULT_MAX_AGE_NANOS = 5000000000L;

    public interface Clock {
        long nanoTime();
    }

    public enum ReleaseReason {
        DEADLINE,
        LEASE_RELEASE,
        EXPLICIT,
        BARRIER,
        CAPACITY,
        MAX_AGE,
        SESSION_RESET,
        OLD_EPOCH,
        DISABLED,
        INSTALL_FAILURE,
        DELIVERY_FAILURE
    }

    public static final class DiagnosticsSnapshot {
        private final int inboundQueued;
        private final int outboundQueued;
        private final long delivered;
        private final long droppedOldEpoch;
        private final long capacityFlushes;
        private final long maxAgeFlushes;
        private final long barrierFlushes;
        private final long installFailures;
        private final long deliveryFailures;
        private final ReleaseReason lastReleaseReason;

        private DiagnosticsSnapshot(
                int inboundQueued,
                int outboundQueued,
                long delivered,
                long droppedOldEpoch,
                long capacityFlushes,
                long maxAgeFlushes,
                long barrierFlushes,
                long installFailures,
                long deliveryFailures,
                ReleaseReason lastReleaseReason
        ) {
            this.inboundQueued = inboundQueued;
            this.outboundQueued = outboundQueued;
            this.delivered = delivered;
            this.droppedOldEpoch = droppedOldEpoch;
            this.capacityFlushes = capacityFlushes;
            this.maxAgeFlushes = maxAgeFlushes;
            this.barrierFlushes = barrierFlushes;
            this.installFailures = installFailures;
            this.deliveryFailures = deliveryFailures;
            this.lastReleaseReason = lastReleaseReason;
        }

        public int getInboundQueued() { return inboundQueued; }
        public int getOutboundQueued() { return outboundQueued; }
        public long getDelivered() { return delivered; }
        public long getDroppedOldEpoch() { return droppedOldEpoch; }
        public long getCapacityFlushes() { return capacityFlushes; }
        public long getMaxAgeFlushes() { return maxAgeFlushes; }
        public long getBarrierFlushes() { return barrierFlushes; }
        public long getDeliveryFailures() { return deliveryFailures; }
        public long getInstallFailures() { return installFailures; }
        public ReleaseReason getLastReleaseReason() { return lastReleaseReason; }
    }

    private static final class ReplayMarker {
        private final PacketDelayService service;
        private final Packet<?> packet;
        private final SessionEpoch epoch;

        private ReplayMarker(PacketDelayService service, Packet<?> packet, SessionEpoch epoch) {
            this.service = service;
            this.packet = packet;
            this.epoch = epoch;
        }
    }

    private static final ThreadLocal<ArrayDeque<ReplayMarker>> REPLAY_MARKERS =
            new ThreadLocal<ArrayDeque<ReplayMarker>>() {
                @Override
                protected ArrayDeque<ReplayMarker> initialValue() {
                    return new ArrayDeque<>();
                }
            };

    private static final ThreadLocal<ArrayDeque<ReplayMarker>> FINAL_REPLAY_MARKERS =
            new ThreadLocal<ArrayDeque<ReplayMarker>>() {
                @Override
                protected ArrayDeque<ReplayMarker> initialValue() {
                    return new ArrayDeque<>();
                }
            };

    private final Clock clock;
    private final int maxPackets;
    private final long maxAgeNanos;
    private final AtomicLong nextLeaseId = new AtomicLong(1L);
    private final AtomicLong nextSequence = new AtomicLong(1L);
    private final ConcurrentMap<DelayLease, Boolean> leases = new ConcurrentHashMap<>();
    private final EnumMap<EnumLagDirection, ArrayDeque<DelayedEnvelope>> queues =
            new EnumMap<>(EnumLagDirection.class);
    private final IdentityHashMap<DelayLease, EnumMap<EnumLagDirection, Long>> fixedDeadlines =
            new IdentityHashMap<>();
    private final EnumMap<EnumLagDirection, ScheduledFuture<?>> scheduled =
            new EnumMap<>(EnumLagDirection.class);
    private final EnumMap<EnumLagDirection, AtomicInteger> pendingCounts =
            new EnumMap<>(EnumLagDirection.class);
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong droppedOldEpoch = new AtomicLong();
    private final AtomicLong capacityFlushes = new AtomicLong();
    private final AtomicLong maxAgeFlushes = new AtomicLong();
    private final AtomicLong barrierFlushes = new AtomicLong();
    private final AtomicLong installFailures = new AtomicLong();
    private final AtomicLong deliveryFailures = new AtomicLong();
    private volatile Executor eventLoop = Runnable::run;
    private volatile ScheduledExecutorService scheduler;
    private final ThreadLocal<Boolean> onEventLoop = new ThreadLocal<>();
    private volatile SessionEpoch currentEpoch = new SessionEpoch(0L, 0L);
    private volatile SessionEpoch minimumDeliveryEpoch = currentEpoch;
    private SessionEpoch appliedEpoch = currentEpoch;
    private volatile boolean valid = true;
    private volatile NetworkManager boundNetworkManager;
    private volatile net.minecraft.util.Vec3 lastReleasedServerPosition;
    private volatile ReleaseReason lastReleaseReason;

    public PacketDelayService() {
        this(System::nanoTime, Runnable::run, null, DEFAULT_MAX_PACKETS, DEFAULT_MAX_AGE_NANOS);
    }

    public PacketDelayService(Clock clock, Executor eventLoop, ScheduledExecutorService scheduler) {
        this(clock, eventLoop, scheduler, DEFAULT_MAX_PACKETS, DEFAULT_MAX_AGE_NANOS);
    }

    public PacketDelayService(
            Clock clock,
            Executor eventLoop,
            ScheduledExecutorService scheduler,
            int maxPackets,
            long maxAgeNanos
    ) {
        if (clock == null) throw new IllegalArgumentException("clock");
        if (eventLoop == null) throw new IllegalArgumentException("eventLoop");
        if (maxPackets <= 0) throw new IllegalArgumentException("maxPackets");
        if (maxAgeNanos < 0L) throw new IllegalArgumentException("maxAgeNanos");
        this.clock = clock;
        this.eventLoop = eventLoop;
        this.scheduler = scheduler;
        this.maxPackets = maxPackets;
        this.maxAgeNanos = maxAgeNanos;
        queues.put(EnumLagDirection.INBOUND, new ArrayDeque<DelayedEnvelope>());
        queues.put(EnumLagDirection.OUTBOUND, new ArrayDeque<DelayedEnvelope>());
        pendingCounts.put(EnumLagDirection.INBOUND, new AtomicInteger());
        pendingCounts.put(EnumLagDirection.OUTBOUND, new AtomicInteger());
    }

    public DelayLease acquire(DelayRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        DelayLease lease = new DelayLease(this, request, nextLeaseId.getAndIncrement(), currentEpoch);
        leases.put(lease, Boolean.TRUE);
        executeOnEventLoop(() -> drainAllExpired(clock.nanoTime()));
        return lease;
    }

    public void release(DelayLease lease) {
        if (lease == null) return;
        leases.remove(lease);
        executeOnEventLoop(() -> {
            releaseClaimsInternal(lease, null, ReleaseReason.LEASE_RELEASE);
            fixedDeadlines.remove(lease);
            drainAllExpired(clock.nanoTime());
        });
    }

    public void releaseClaims(DelayLease lease) {
        if (lease == null) return;
        executeOnEventLoop(() -> {
            releaseClaimsInternal(lease, null, ReleaseReason.EXPLICIT);
            drainAllExpired(clock.nanoTime());
        });
    }

    public void releaseNext(DelayLease lease, EnumLagDirection direction) {
        if (lease == null || direction == null) return;
        executeOnEventLoop(() -> {
            ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
            for (DelayedEnvelope envelope : queue) {
                if (envelope.isClaimedBy(lease)) {
                    envelope.release(lease);
                    lastReleaseReason = ReleaseReason.EXPLICIT;
                    break;
                }
            }
            drainPrefix(direction);
            cleanupFixedDeadline(lease, direction);
            scheduleNext(direction);
        });
    }

    public void releaseExpired(DelayLease lease, EnumLagDirection direction) {
        if (lease == null || direction == null) return;
        executeOnEventLoop(() -> {
            long now = clock.nanoTime();
            ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
            for (DelayedEnvelope envelope : queue) {
                Long deadline = envelope.getDeadline(lease);
                if (deadline != null && deadline <= now) envelope.release(lease);
            }
            drainPrefix(direction);
            cleanupFixedDeadline(lease, direction);
            scheduleNext(direction);
        });
    }

    public void flush(EnumLagDirection direction) {
        if (direction == null) return;
        executeOnEventLoop(() -> flushDirection(direction, ReleaseReason.EXPLICIT));
    }

    public void drainExpired() {
        executeOnEventLoop(() -> drainAllExpired(clock.nanoTime()));
    }

    public int getPendingCount(EnumLagDirection direction) {
        if (direction == null) return 0;
        return pendingCounts.get(direction).get();
    }

    public boolean hasOtherOutboundOwner(String owner) {
        for (DelayLease lease : activeLeases(EnumLagDirection.OUTBOUND)) {
            if (!owner.equals(lease.getOwnerLabel())) return true;
        }
        return false;
    }

    public boolean hasActiveLease(EnumLagDirection direction) {
        if (!valid || direction == null) return false;
        for (DelayLease lease : leases.keySet()) {
            if (lease.isActive() && lease.getRequest().getDirections().contains(direction)) return true;
        }
        return false;
    }

    public SessionEpoch getCurrentEpoch() {
        return currentEpoch;
    }

    public SessionEpoch epochForConnection(long connectionGeneration) {
        return new SessionEpoch(connectionGeneration, currentEpoch.getWorldGeneration());
    }

    public boolean isValid() {
        return valid;
    }

    public static boolean isPlayPacket(Packet<?> packet) {
        return packet != null && isPlayPacketClassName(packet.getClass().getName());
    }

    private static boolean isPlayPacketClassName(String className) {
        return className != null && className.startsWith("net.minecraft.network.play.");
    }

    public void bindEventLoop(Executor executor) {
        if (executor == null) return;
        eventLoop = executor;
        if (executor instanceof ScheduledExecutorService) {
            scheduler = (ScheduledExecutorService) executor;
        }
    }

    public void bindNetworkManager(NetworkManager networkManager) {
        if (networkManager == null) return;
        NetworkManager previous = boundNetworkManager;
        boundNetworkManager = networkManager;
        if (previous != null && previous != networkManager) advanceConnection();
        Channel channel = AccessorBridge.NetworkManager_getChannel(networkManager);
        if (channel != null) bindEventLoop(channel.eventLoop());
    }

    public void advanceConnection() {
        setEpoch(currentEpoch.nextConnection());
    }

    public void advanceWorld() {
        setEpoch(currentEpoch.nextWorld());
    }

    public void onClientWorldUnload() {
        advanceWorld();
    }

    public void onChannelInactive(NetworkManager networkManager) {
        if (networkManager == null || boundNetworkManager == networkManager) {
            if (boundNetworkManager == networkManager) boundNetworkManager = null;
            advanceConnection();
        }
    }

    public void setEpoch(SessionEpoch epoch) {
        if (epoch == null) throw new IllegalArgumentException("epoch");
        minimumDeliveryEpoch = epoch;
        currentEpoch = epoch;
        executeOnEventLoop(() -> {
            if (currentEpoch.isSameSession(epoch) && !appliedEpoch.isSameSession(epoch)) {
                resetForEpoch(epoch, ReleaseReason.SESSION_RESET);
            }
        });
    }

    public void invalidate() {
        valid = false;
        for (DelayLease lease : leases.keySet()) lease.markInactive();
        leases.clear();
        NetworkManager managerToDetach = boundNetworkManager;
        SessionEpoch invalidationEpoch = currentEpoch.nextConnection();
        minimumDeliveryEpoch = invalidationEpoch;
        currentEpoch = invalidationEpoch;
        executeOnEventLoop(() -> {
            try {
                Channel channel = managerToDetach == null
                        ? null : AccessorBridge.NetworkManager_getChannel(managerToDetach);
                if (channel != null && channel.pipeline().get(HANDLER_NAME) != null) {
                    channel.pipeline().remove(HANDLER_NAME);
                }
            }
            catch (RuntimeException ignored) {
            }
            clearQueues();
            appliedEpoch = currentEpoch;
            lastReleasedServerPosition = null;
            fixedDeadlines.clear();
            cancelSchedules();
            lastReleaseReason = ReleaseReason.DISABLED;
        });
    }

    public void activate() {
        valid = true;
    }

    public net.minecraft.util.Vec3 getLastReleasedServerPosition() {
        return lastReleasedServerPosition;
    }

    public DiagnosticsSnapshot getDiagnostics() {
        return new DiagnosticsSnapshot(
                getPendingCount(EnumLagDirection.INBOUND),
                getPendingCount(EnumLagDirection.OUTBOUND),
                delivered.get(),
                droppedOldEpoch.get(),
                capacityFlushes.get(),
                maxAgeFlushes.get(),
                barrierFlushes.get(),
                installFailures.get(),
                deliveryFailures.get(),
                lastReleaseReason
        );
    }

    public boolean interceptFirstInbound(ChannelHandlerContext context, Packet<?> packet) {
        if (context == null || !isPlayPacket(packet) || !valid
                || !hasActiveLease(EnumLagDirection.INBOUND)) return false;
        bindEventLoop(context.executor());
        if (context.pipeline().get(HANDLER_NAME) != null) return false;
        SessionEpoch epoch = currentEpoch;
        try {
            context.pipeline().addBefore(context.name(), HANDLER_NAME,
                    new PacketDelayChannelHandler(this, epoch));
        }
        catch (RuntimeException failure) {
            installFailures.incrementAndGet();
            lastReleaseReason = ReleaseReason.INSTALL_FAILURE;
            return false;
        }
        ChannelHandlerContext delayContext = context.pipeline().context(HANDLER_NAME);
        if (delayContext == null) {
            installFailures.incrementAndGet();
            lastReleaseReason = ReleaseReason.INSTALL_FAILURE;
            return false;
        }
        SessionEpoch deliveryEpoch = isSessionBoundary(packet) ? epoch.nextWorld() : epoch;
        boolean intercepted = handleInbound(packet, epoch, deliveredPacket -> {
            markReplay(this, deliveredPacket, deliveryEpoch);
            delayContext.fireChannelRead(deliveredPacket);
        });
        PacketDelayChannelHandler handler = (PacketDelayChannelHandler) context.pipeline().get(HANDLER_NAME);
        if (!intercepted && handler != null && isSessionBoundary(packet)) {
            handler.adoptEpoch(epoch, deliveryEpoch);
        }
        return true;
    }

    public boolean handleInbound(Packet<?> packet, SessionEpoch epoch, DelayedEnvelope.DeliveryRoute route) {
        return handle(packet, EnumLagDirection.INBOUND, epoch, route);
    }

    public boolean handleOutbound(Packet<?> packet, SessionEpoch epoch, DelayedEnvelope.DeliveryRoute route) {
        if (packet != null && route != null && valid && (epoch == null || currentEpoch.isSameSession(epoch)))
            CombatPacketState.recordAccepted(packet);
        return handle(packet, EnumLagDirection.OUTBOUND, epoch, route);
    }

    public static void markReplay(Packet<?> packet, SessionEpoch epoch) {
        markReplay(null, packet, epoch);
    }

    public static void markReplay(PacketDelayService service, Packet<?> packet, SessionEpoch epoch) {
        if (packet == null) return;
        REPLAY_MARKERS.get().addLast(new ReplayMarker(service, packet, epoch));
    }

    public static boolean consumeReplay(Packet<?> packet) {
        ReplayMarker marker = takeMarker(REPLAY_MARKERS.get(), packet);
        if (marker == null) return false;
        if (marker.service != null) FINAL_REPLAY_MARKERS.get().addLast(marker);
        return true;
    }

    public static boolean checkFinalDelivery(Packet<?> packet, boolean minecraftThread) {
        ReplayMarker marker = findMarker(FINAL_REPLAY_MARKERS.get(), packet);
        if (marker == null || marker.service == null) return true;
        if (!marker.service.acceptsFinalEpoch(marker.epoch)) {
            takeMarker(FINAL_REPLAY_MARKERS.get(), packet);
            return false;
        }
        if (minecraftThread) takeMarker(FINAL_REPLAY_MARKERS.get(), packet);
        return true;
    }

    public static boolean isFinalDeliveryCurrent(Packet<?> packet) {
        ReplayMarker marker = findMarker(FINAL_REPLAY_MARKERS.get(), packet);
        return marker == null || marker.service == null || marker.service.acceptsFinalEpoch(marker.epoch);
    }

    public static Runnable guardFinalDelivery(Runnable task) {
        if (task == null) return null;
        ArrayDeque<ReplayMarker> markers = FINAL_REPLAY_MARKERS.get();
        ReplayMarker marker = markers.isEmpty() ? null : markers.removeLast();
        if (marker == null || marker.service == null) return task;
        return () -> {
            if (marker.service.acceptsFinalEpoch(marker.epoch)) task.run();
        };
    }

    public static void clearFinalDelivery(Packet<?> packet) {
        if (packet == null) return;
        takeMarker(FINAL_REPLAY_MARKERS.get(), packet);
    }

    public void onSendPacket(SendPacketEvent event) {
        if (event == null || event.isCanceled() || !isPlayPacket(event.getPacket()) || !valid
                || !hasActiveLease(EnumLagDirection.OUTBOUND)) return;
        NetworkManager networkManager = boundNetworkManager;
        if (networkManager == null) return;
        NetworkManager selected = networkManager;
        SessionEpoch packetEpoch = currentEpoch;
        Packet<?> packet = event.getPacket();
        event.setCanceled(true);
        CombatPacketState.recordAccepted(packet);
        Channel channel = AccessorBridge.NetworkManager_getChannel(selected);
        if (channel != null) bindEventLoop(channel.eventLoop());
        Executor executor = channel == null ? eventLoop : channel.eventLoop();
        executor.execute(() -> runOnEventLoop(() -> {
            if (!acceptsOutboundSession(selected, packetEpoch)) return;
            handle(packet, EnumLagDirection.OUTBOUND, packetEpoch, deliveredPacket -> {
                if (acceptsOutboundSession(selected, packetEpoch)) {
                    PacketUtils.sendPacketNoEvent(selected, deliveredPacket);
                }
            });
        }));
    }

    private boolean handle(
            Packet<?> packet,
            EnumLagDirection direction,
            SessionEpoch packetEpoch,
            DelayedEnvelope.DeliveryRoute route
    ) {
        if (packet == null || route == null) return false;
        if (!valid) {
            route.deliver(packet);
            return false;
        }
        long now = clock.nanoTime();
        if (!appliedEpoch.isSameSession(currentEpoch)) {
            resetForEpoch(currentEpoch, ReleaseReason.SESSION_RESET);
        }
        SessionEpoch epoch = packetEpoch == null ? currentEpoch : packetEpoch;
        if (!currentEpoch.isSameSession(epoch)) {
            if (isNewer(epoch, currentEpoch)) {
                currentEpoch = epoch;
                resetForEpoch(epoch, ReleaseReason.SESSION_RESET);
            }
            else {
                droppedOldEpoch.incrementAndGet();
                lastReleaseReason = ReleaseReason.OLD_EPOCH;
                return true;
            }
        }

        if (direction == EnumLagDirection.INBOUND && isSessionBoundary(packet)) {
            SessionEpoch boundaryEpoch = currentEpoch.nextWorld();
            currentEpoch = boundaryEpoch;
            resetForEpoch(boundaryEpoch, ReleaseReason.SESSION_RESET);
            deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
            return false;
        }
        drainExpired(direction, now);
        if (direction == EnumLagDirection.INBOUND && packet instanceof S08PacketPlayerPosLook) {
            for (DelayLease lease : activeLeases(direction)) {
                lease.getRequest().getInboundClaimPolicy().decide(packet, currentEpoch, now);
            }
            flushDirection(direction, ReleaseReason.BARRIER);
            deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
            return false;
        }

        DelayedEnvelope envelope = new DelayedEnvelope(
                packet, direction, currentEpoch, nextSequence.getAndIncrement(), now, route);
        List<DelayLease> active = activeLeases(direction);
        Set<DelayLease> bypassing = new HashSet<>();
        for (DelayLease lease : active) {
            DelayRequest request = lease.getRequest();
            InboundClaimPolicy.Decision decision = direction == EnumLagDirection.INBOUND
                    ? request.getInboundClaimPolicy().decide(packet, currentEpoch, now)
                    : request.claimsOutbound(packet, !queues.get(direction).isEmpty())
                    ? InboundClaimPolicy.Decision.CLAIM : InboundClaimPolicy.Decision.BYPASS;
            if (decision == InboundClaimPolicy.Decision.RELEASE_AND_PASS) {
                releaseClaimsInternal(lease, direction, ReleaseReason.BARRIER);
                continue;
            }
            if (decision == InboundClaimPolicy.Decision.CLAIM) {
                long deadline = deadlineFor(lease, direction, now);
                if (deadline > now) envelope.claim(lease, deadline);
            }
            else if (decision == InboundClaimPolicy.Decision.BYPASS) {
                bypassing.add(lease);
            }
        }

        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        if (!envelope.isClaimed() && canBypassQueuedPrefix(queue, bypassing)) {
            deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
            return false;
        }
        if (!envelope.isClaimed() && !queue.isEmpty()) {
            if (queue.size() >= maxPackets || isTooOld(queue.peek(), now)) {
                if (queue.size() >= maxPackets) capacityFlushes.incrementAndGet();
                else maxAgeFlushes.incrementAndGet();
                flushDirection(direction, queue.size() >= maxPackets ? ReleaseReason.CAPACITY : ReleaseReason.MAX_AGE);
                deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
                return false;
            }
            queue.addLast(envelope);
            pendingCounts.get(direction).incrementAndGet();
            scheduleNext(direction);
            return true;
        }
        if (!envelope.isClaimed()) {
            deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
            return false;
        }

        if (queue.size() >= maxPackets || isTooOld(queue.peek(), now)) {
            if (queue.size() >= maxPackets) capacityFlushes.incrementAndGet();
            else maxAgeFlushes.incrementAndGet();
            flushDirection(direction, queue.size() >= maxPackets ? ReleaseReason.CAPACITY : ReleaseReason.MAX_AGE);
            deliverDirect(packet, route, direction == EnumLagDirection.OUTBOUND);
            return false;
        }
        queue.addLast(envelope);
        pendingCounts.get(direction).incrementAndGet();
        scheduleNext(direction);
        return true;
    }

    private boolean canBypassQueuedPrefix(
            ArrayDeque<DelayedEnvelope> queue,
            Set<DelayLease> bypassing
    ) {
        if (queue.isEmpty() || bypassing.isEmpty()) return false;
        for (DelayedEnvelope queued : queue) {
            if (queued.getClaims().isEmpty()) return false;
            for (DelayLease owner : queued.getClaims().keySet()) {
                if (!bypassing.contains(owner)) return false;
            }
        }
        return true;
    }

    private void deliverDirect(Packet<?> packet, DelayedEnvelope.DeliveryRoute route, boolean outbound) {
        observeReleased(packet, EnumLagDirection.OUTBOUND);
        if (outbound && packet != null && route != null) {
            CombatPacketState.markReplay(packet);
        }
        try {
            route.deliver(packet);
            delivered.incrementAndGet();
        }
        catch (RuntimeException failure) {
            lastReleaseReason = ReleaseReason.DELIVERY_FAILURE;
            long failures = deliveryFailures.incrementAndGet();
            if ((failures & (failures - 1L)) == 0L) {
                System.err.println("[mindless] [packet-delay] delivery failed count=" + failures
                        + " packet=" + packet.getClass().getSimpleName());
                failure.printStackTrace();
            }
        } finally {
            if (outbound) CombatPacketState.consumeReplay(packet);
        }
    }

    private boolean acceptsOutboundSession(NetworkManager networkManager, SessionEpoch epoch) {
        return valid && networkManager != null && boundNetworkManager == networkManager
                && currentEpoch.isSameSession(epoch);
    }

    private boolean acceptsFinalEpoch(SessionEpoch epoch) {
        SessionEpoch current = currentEpoch;
        SessionEpoch minimum = minimumDeliveryEpoch;
        return valid && epoch != null
                && epoch.getConnectionGeneration() == current.getConnectionGeneration()
                && epoch.getConnectionGeneration() == minimum.getConnectionGeneration()
                && epoch.getWorldGeneration() >= minimum.getWorldGeneration()
                && epoch.getWorldGeneration() <= current.getWorldGeneration();
    }

    private void drainExpired(EnumLagDirection direction, long now) {
        if (direction == EnumLagDirection.INBOUND) {
            for (DelayLease lease : activeLeases(direction)) {
                if (lease.getRequest().getInboundClaimPolicy().shouldRelease(currentEpoch, now)) {
                    releaseClaimsInternal(lease, direction, ReleaseReason.BARRIER);
                }
            }
        }
        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        if (isTooOld(queue.peek(), now)) {
            maxAgeFlushes.incrementAndGet();
            flushDirection(direction, ReleaseReason.MAX_AGE);
            return;
        }
        boolean released = false;
        for (DelayedEnvelope envelope : queue) {
            if (envelope.releaseExpired(now)) released = true;
        }
        if (released) lastReleaseReason = ReleaseReason.DEADLINE;
        drainPrefix(direction);
        for (DelayLease lease : new ArrayList<>(leases.keySet())) cleanupFixedDeadline(lease, direction);
        scheduleNext(direction);
    }

    private void drainAllExpired(long now) {
        drainExpired(EnumLagDirection.INBOUND, now);
        drainExpired(EnumLagDirection.OUTBOUND, now);
    }

    private void drainPrefix(EnumLagDirection direction) {
        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        while (!queue.isEmpty()) {
            DelayedEnvelope envelope = queue.peekFirst();
            if (envelope.isClaimed()) break;
            queue.removeFirst();
            pendingCounts.get(direction).decrementAndGet();
            if (!currentEpoch.isSameSession(envelope.getEpoch())) {
                droppedOldEpoch.incrementAndGet();
                lastReleaseReason = ReleaseReason.OLD_EPOCH;
                continue;
            }
            deliverDirect(envelope.getPacket(), envelope.getRoute(),
                    envelope.getDirection() == EnumLagDirection.OUTBOUND);
        }
    }

    private void flushDirection(EnumLagDirection direction, ReleaseReason reason) {
        if (direction == EnumLagDirection.INBOUND) {
            for (DelayLease lease : activeLeases(direction)) {
                lease.getRequest().getInboundClaimPolicy().onRelease(currentEpoch, clock.nanoTime());
            }
        }
        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        for (DelayedEnvelope envelope : queue) envelope.releaseAll();
        lastReleaseReason = reason;
        drainPrefix(direction);
        for (DelayLease lease : new ArrayList<>(leases.keySet())) cleanupFixedDeadline(lease, direction);
        scheduleNext(direction);
        if (reason == ReleaseReason.BARRIER) barrierFlushes.incrementAndGet();
    }

    private void releaseClaimsInternal(DelayLease lease, EnumLagDirection direction, ReleaseReason reason) {
        if (direction == null) {
            for (EnumLagDirection value : EnumLagDirection.values()) releaseClaimsInternal(lease, value, reason);
            return;
        }
        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        if (direction == EnumLagDirection.INBOUND) {
            lease.getRequest().getInboundClaimPolicy().onRelease(currentEpoch, clock.nanoTime());
        }
        for (DelayedEnvelope envelope : queue) envelope.release(lease);
        lastReleaseReason = reason;
        drainPrefix(direction);
        cleanupFixedDeadline(lease, direction);
        scheduleNext(direction);
    }

    private List<DelayLease> activeLeases(EnumLagDirection direction) {
        List<DelayLease> active = new ArrayList<>();
        for (DelayLease lease : leases.keySet()) {
            if (lease.isActive() && lease.getRequest().getDirections().contains(direction)) active.add(lease);
        }
        active.sort((left, right) -> Long.compare(left.getId(), right.getId()));
        return active;
    }

    private long deadlineFor(DelayLease lease, EnumLagDirection direction, long now) {
        DelayRequest request = lease.getRequest();
        long delay;
        if (request.getDeadlineMode() == DelayRequest.DeadlineMode.FIXED_WINDOW) {
            EnumMap<EnumLagDirection, Long> byDirection = fixedDeadlines.get(lease);
            if (byDirection == null) {
                byDirection = new EnumMap<>(EnumLagDirection.class);
                fixedDeadlines.put(lease, byDirection);
            }
            Long existing = byDirection.get(direction);
            if (existing != null && existing > now) return existing;
            delay = request.chooseWindowDelayNanos();
            long deadline = safeAdd(now, delay);
            byDirection.put(direction, deadline);
            return deadline;
        }
        delay = request.getDelayNanos();
        return safeAdd(now, delay);
    }

    private void cleanupFixedDeadline(DelayLease lease, EnumLagDirection direction) {
        if (lease.getRequest().getDeadlineMode() != DelayRequest.DeadlineMode.FIXED_WINDOW) return;
        ArrayDeque<DelayedEnvelope> queue = queues.get(direction);
        for (DelayedEnvelope envelope : queue) {
            if (envelope.isClaimedBy(lease)) return;
        }
        EnumMap<EnumLagDirection, Long> byDirection = fixedDeadlines.get(lease);
        if (byDirection != null) {
            byDirection.remove(direction);
            if (byDirection.isEmpty()) fixedDeadlines.remove(lease);
        }
    }

    private void scheduleNext(EnumLagDirection direction) {
        ScheduledFuture<?> previous = scheduled.remove(direction);
        if (previous != null) previous.cancel(false);
        ScheduledExecutorService timer = scheduler;
        if (timer == null) return;
        long now = clock.nanoTime();
        long earliest = Long.MAX_VALUE;
        for (DelayedEnvelope envelope : queues.get(direction)) {
            if (maxAgeNanos > 0L) earliest = Math.min(earliest, safeAdd(envelope.getCapturedAtNanos(), maxAgeNanos));
            for (Long deadline : envelope.getClaims().values()) earliest = Math.min(earliest, deadline);
        }
        if (earliest == Long.MAX_VALUE) return;
        long delay = earliest <= now ? 0L : earliest - now;
        scheduled.put(direction, timer.schedule(() -> executeOnEventLoop(() -> {
            scheduled.remove(direction);
            drainExpired(direction, clock.nanoTime());
        }), delay, TimeUnit.NANOSECONDS));
    }

    private void resetForEpoch(SessionEpoch epoch, ReleaseReason reason) {
        appliedEpoch = epoch;
        for (DelayLease lease : activeLeases(EnumLagDirection.INBOUND)) {
            lease.getRequest().getInboundClaimPolicy().onRelease(epoch, clock.nanoTime());
        }
        clearQueues();
        fixedDeadlines.clear();
        cancelSchedules();
        lastReleasedServerPosition = null;
        lastReleaseReason = reason;
    }

    private void clearQueues() {
        queues.get(EnumLagDirection.INBOUND).clear();
        queues.get(EnumLagDirection.OUTBOUND).clear();
        pendingCounts.get(EnumLagDirection.INBOUND).set(0);
        pendingCounts.get(EnumLagDirection.OUTBOUND).set(0);
    }

    private void cancelSchedules() {
        for (ScheduledFuture<?> future : scheduled.values()) future.cancel(false);
        scheduled.clear();
    }

    private boolean isTooOld(DelayedEnvelope envelope, long now) {
        return envelope != null && maxAgeNanos > 0L
                && now - envelope.getCapturedAtNanos() >= maxAgeNanos;
    }

    static boolean isSessionBoundary(Packet<?> packet) {
        return packet instanceof S01PacketJoinGame
                || packet instanceof S07PacketRespawn
                || packet instanceof S40PacketDisconnect;
    }

    private static boolean isNewer(SessionEpoch candidate, SessionEpoch current) {
        if (candidate.getConnectionGeneration() != current.getConnectionGeneration()) {
            return candidate.getConnectionGeneration() > current.getConnectionGeneration();
        }
        return candidate.getWorldGeneration() > current.getWorldGeneration();
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        if (left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private void observeReleased(Packet<?> packet, EnumLagDirection direction) {
        if (direction != EnumLagDirection.OUTBOUND || !(packet instanceof C03PacketPlayer)) return;
        C03PacketPlayer movement = (C03PacketPlayer) packet;
        if (movement.isMoving()) {
            lastReleasedServerPosition = new net.minecraft.util.Vec3(
                    movement.getPositionX(), movement.getPositionY(), movement.getPositionZ());
        }
    }

    private void executeOnEventLoop(Runnable action) {
        if (Boolean.TRUE.equals(onEventLoop.get())) {
            action.run();
            return;
        }
        Executor executor = eventLoop;
        executor.execute(() -> runOnEventLoop(action));
    }

    private void runOnEventLoop(Runnable action) {
        onEventLoop.set(Boolean.TRUE);
        try {
            action.run();
        }
        finally {
            onEventLoop.remove();
        }
    }

    private static ReplayMarker findMarker(ArrayDeque<ReplayMarker> markers, Packet<?> packet) {
        if (packet == null || markers.isEmpty()) return null;
        ReplayMarker last = markers.peekLast();
        if (last != null && last.packet == packet) return last;
        for (java.util.Iterator<ReplayMarker> iterator = markers.descendingIterator(); iterator.hasNext();) {
            ReplayMarker marker = iterator.next();
            if (marker.packet == packet) return marker;
        }
        return null;
    }

    private static ReplayMarker takeMarker(ArrayDeque<ReplayMarker> markers, Packet<?> packet) {
        ReplayMarker marker = findMarker(markers, packet);
        if (marker == null) return null;
        markers.remove(marker);
        return marker;
    }
}
