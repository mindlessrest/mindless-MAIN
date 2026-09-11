package mindless.lag;

import mindless.lag.api.BacktrackControlSnapshot;
import mindless.lag.api.BacktrackPacketPolicy;
import mindless.lag.api.DelayLease;
import mindless.lag.api.DelayRequest;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.KnockbackPacketPolicy;
import mindless.lag.service.PacketDelayService;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S00PacketKeepAlive;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S14PacketEntity;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class KnockbackPacketPolicyTest {
    private static final long MS = 1000000L;

    private static final class Session {
        private long now;
        private final KnockbackPacketPolicy policy = new KnockbackPacketPolicy();
        private final BacktrackPacketPolicy backtrack = new BacktrackPacketPolicy(() -> 0.0D);
        private final PacketDelayService service;
        private final List<Packet<?>> delivered = new ArrayList<>();
        private final DelayLease lease;

        private Session() { this(false, false, true, 512); }

        private Session(boolean withBacktrack, boolean backtrackFirst, boolean flushOnDamage, int capacity) {
            service = new PacketDelayService(() -> now, Runnable::run, null, capacity, 5000 * MS);
            policy.configure(service.getCurrentEpoch(), true, 3, 200, 100);
            backtrack.setControl(BacktrackControlSnapshot.builder().enabled(true).targetEligible(true)
                    .localEntityId(3).targetEntityId(7).attackAtNanos(0).attackWindowNanos(1000 * MS)
                    .minDelayNanos(300 * MS).maxDelayNanos(300 * MS).flushOnDamage(flushOnDamage)
                    .eye(0, 1.6, 0).displayed(2, 0, 0).serverPosition(64, 0, 0).distance(0, 6).build());
            if (withBacktrack && backtrackFirst) acquireBacktrack();
            lease = service.acquire(DelayRequest.fixedWindow("Knockback Delay", EnumLagDirection.ONLY_INBOUND,
                    policy, policy::chooseDelayNanos));
            if (withBacktrack && !backtrackFirst) acquireBacktrack();
        }

        private void acquireBacktrack() {
            service.acquire(DelayRequest.fixedWindow("Backtrack", EnumLagDirection.ONLY_INBOUND,
                    backtrack, backtrack::chooseWindowDelayNanos));
        }

        private void receive(Packet<?> packet) {
            service.handleInbound(packet, service.getCurrentEpoch(), delivered::add);
        }

        private void advance(long millis) {
            now = millis * MS;
            service.drainExpired();
        }
    }

    private static Packet<?> velocity(int entity) {
        return new S12PacketEntityVelocity(entity, 0.2D, 0.4D, 0.2D);
    }

    private static Packet<?> move() {
        return new S14PacketEntity.S15PacketEntityRelMove(7, (byte) 32, (byte) 0, (byte) 0, true);
    }

    @Test
    public void localVelocityAndFollowingPacketsShareOneDeadline() {
        Session s = new Session();
        Packet<?> velocity = velocity(3);
        Packet<?> following = move();
        s.receive(velocity);
        s.advance(150);
        s.receive(following);
        s.advance(199);
        Assert.assertTrue(s.delivered.isEmpty());
        s.advance(200);
        Assert.assertEquals(Arrays.asList(velocity, following), s.delivered);
    }

    @Test
    public void otherPlayersAndFailedConditionsDoNotStartDelay() {
        Session s = new Session();
        Packet<?> other = velocity(7);
        s.receive(other);
        s.policy.configure(s.service.getCurrentEpoch(), false, 3, 200, 100);
        Packet<?> ineligible = velocity(3);
        s.receive(ineligible);
        s.policy.configure(s.service.getCurrentEpoch(), true, 3, 200, 0);
        Packet<?> chanceZero = velocity(3);
        s.receive(chanceZero);
        Assert.assertEquals(Arrays.asList(other, ineligible, chanceZero), s.delivered);
    }

    @Test
    public void backtrackDamageFlushPreservesKnockbackDelayInBothEnableOrders() {
        for (boolean backtrackFirst : new boolean[]{true, false}) {
            Session s = new Session(true, backtrackFirst, true, 512);
            Packet<?> movement = move();
            s.receive(movement);
            s.advance(50);
            Packet<?> velocity = velocity(3);
            s.receive(velocity);
            Assert.assertEquals(Arrays.asList(movement), s.delivered);
            s.advance(249);
            Assert.assertEquals(1, s.delivered.size());
            s.advance(250);
            Assert.assertEquals(Arrays.asList(movement, velocity), s.delivered);
        }
    }

    @Test
    public void longerBacktrackHoldStillOwnsVelocityWhenDamageFlushIsOff() {
        for (boolean backtrackFirst : new boolean[]{true, false}) {
            Session s = new Session(true, backtrackFirst, false, 512);
            Packet<?> movement = move();
            Packet<?> velocity = velocity(3);
            s.receive(movement);
            s.advance(50);
            s.receive(velocity);
            s.advance(250);
            Assert.assertTrue(s.delivered.isEmpty());
            s.advance(300);
            Assert.assertEquals(Arrays.asList(movement, velocity), s.delivered);
        }
    }

    @Test
    public void explicitFlushDoesNotStartAnotherWindowWithoutVelocity() {
        Session s = new Session();
        s.receive(velocity(3));
        s.advance(50);
        s.service.flush(EnumLagDirection.INBOUND);
        Packet<?> next = move();
        s.receive(next);
        Assert.assertEquals(2, s.delivered.size());
        Assert.assertSame(next, s.delivered.get(1));
    }

    @Test
    public void capacityFlushDoesNotRestartDelayOnUnrelatedPackets() {
        Session s = new Session(false, false, true, 1);
        s.receive(velocity(3));
        s.receive(move());
        Packet<?> next = new S00PacketKeepAlive();
        s.receive(next);
        Assert.assertEquals(3, s.delivered.size());
        Assert.assertSame(next, s.delivered.get(2));
    }

    @Test
    public void releasingKnockbackDoesNotReleaseBacktracksClaim() {
        Session s = new Session(true, true, false, 512);
        s.receive(move());
        s.receive(velocity(3));
        s.lease.release();
        Assert.assertTrue(s.delivered.isEmpty());
        s.advance(300);
        Assert.assertEquals(2, s.delivered.size());
    }

    @Test
    public void correctionFlushesBothOwnersAndDoesNotDelayNextMovement() {
        Session s = new Session(true, true, false, 512);
        s.receive(move());
        s.receive(velocity(3));
        s.receive(new S08PacketPlayerPosLook());
        s.receive(move());
        Assert.assertEquals(4, s.delivered.size());
    }

    @Test
    public void newVelocityReleasesPreviousImpulseAndFollowingPacketsInOrder() {
        Session s = new Session();
        Packet<?> first = velocity(3);
        Packet<?> movement = move();
        Packet<?> second = velocity(3);
        s.receive(first);
        s.receive(movement);
        s.advance(50);
        s.receive(second);
        Assert.assertEquals(Arrays.asList(first, movement, second), s.delivered);
        s.receive(move());
        Assert.assertEquals(4, s.delivered.size());
    }

    @Test
    public void keepAliveAndHealthBypassWithoutReleasingVelocity() {
        Session s = new Session();
        Packet<?> velocity = velocity(3);
        Packet<?> keepAlive = new S00PacketKeepAlive();
        Packet<?> health = new net.minecraft.network.play.server.S06PacketUpdateHealth(19, 20, 5);
        s.receive(velocity);
        s.receive(keepAlive);
        s.receive(health);
        Assert.assertEquals(Arrays.asList(keepAlive, health), s.delivered);
        s.advance(200);
        Assert.assertEquals(Arrays.asList(keepAlive, health, velocity), s.delivered);
    }

    @Test
    public void backtrackKeepAliveBarrierDoesNotReleaseKnockbackClaim() {
        for (boolean backtrackFirst : new boolean[]{true, false}) {
            Session s = new Session(true, backtrackFirst, false, 512);
            Packet<?> movement = move();
            Packet<?> velocity = velocity(3);
            Packet<?> keepAlive = new S00PacketKeepAlive();
            s.receive(movement);
            s.receive(velocity);
            s.receive(keepAlive);
            Assert.assertEquals(Arrays.asList(movement, keepAlive), s.delivered);
            s.advance(200);
            Assert.assertEquals(Arrays.asList(movement, keepAlive, velocity), s.delivered);
        }
    }

    @Test
    public void oldWorldEligibilityCannotDelayPacketsAfterRespawn() {
        Session s = new Session();
        s.receive(velocity(3));
        Packet<?> respawn = new net.minecraft.network.play.server.S07PacketRespawn();
        s.receive(respawn);
        Packet<?> next = velocity(3);
        s.receive(next);
        Assert.assertEquals(Arrays.asList(respawn, next), s.delivered);
        s.policy.configure(s.service.getCurrentEpoch(), true, 3, 200, 100);
        s.receive(velocity(3));
        Assert.assertEquals(1, s.service.getPendingCount(EnumLagDirection.INBOUND));
    }

    @Test
    public void disabledConditionsReleaseTheWindowWithoutAnotherPacket() {
        Session s = new Session();
        Packet<?> velocity = velocity(3);
        s.receive(velocity);
        s.policy.configure(s.service.getCurrentEpoch(), false, 3, 200, 100);
        s.advance(50);
        Assert.assertEquals(Arrays.asList(velocity), s.delivered);
        s.policy.configure(s.service.getCurrentEpoch(), true, 3, 200, 100);
        s.receive(move());
        Assert.assertEquals(2, s.delivered.size());
    }

    @Test
    public void maximumAgeFlushClearsTheWindow() {
        long[] now = {0};
        PacketDelayService service = new PacketDelayService(() -> now[0], Runnable::run, null, 512, 50 * MS);
        KnockbackPacketPolicy policy = new KnockbackPacketPolicy();
        policy.configure(service.getCurrentEpoch(), true, 3, 200, 100);
        service.acquire(DelayRequest.fixedWindow("Knockback Delay", EnumLagDirection.ONLY_INBOUND,
                policy, policy::chooseDelayNanos));
        List<Packet<?>> delivered = new ArrayList<>();
        service.handleInbound(velocity(3), service.getCurrentEpoch(), delivered::add);
        now[0] = 50 * MS;
        service.drainExpired();
        service.handleInbound(move(), service.getCurrentEpoch(), delivered::add);
        Assert.assertEquals(2, delivered.size());
    }
}
