package mindless.utility;

import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Tracks the round-trip latency between sending an attack packet and receiving
 * the damage-confirmation packet from the server. This gives a far more accurate
 * measure of effective combat latency than the scoreboard ping value.
 *
 * <p>Ported from VapeV4.21's {@code gg.vape.combat.AttackPacketTimingTracker}.</p>
 */
public class AttackPacketTimingTracker {

    public static final AttackPacketTimingTracker INSTANCE = new AttackPacketTimingTracker();

    private static final int MAX_SAMPLES = 20;
    private static final long MAX_VALID_DELAY_MS = 500L;
    private static final long MIN_ATTACK_INTERVAL_MS = 400L;

    private final List<Long> hitDelays = new ArrayList<>();

    private int targetId;
    private long lastHitTime;
    private long lastAttackTime;

    private AttackPacketTimingTracker() {}

    // ── Outgoing attack tracking ─────────────────────────────────────────

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.isCanceled()) return;
        if (!(e.getPacket() instanceof C02PacketUseEntity)) return;

        C02PacketUseEntity packet = (C02PacketUseEntity) e.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;

        Entity entity = packet.getEntityFromWorld(mc.theWorld);
        if (entity == null) return;

        int entityId = entity.getEntityId();
        long now = System.currentTimeMillis();

        // Only start a new measurement window if the target wasn't recently hit
        // and enough time has passed since the last attack
        if (entity.hurtResistantTime == 0 && now - lastHitTime > MIN_ATTACK_INTERVAL_MS) {
            if (now - lastAttackTime > getAverageHitDelay() * 2L) {
                lastHitTime = now;
            }
        }

        targetId = entityId;
        lastAttackTime = now;
    }

    // ── Incoming damage confirmation tracking ────────────────────────────

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.isCanceled()) return;

        Entity damaged = getDamagePacketEntity(e.getPacket());
        if (damaged != null && damaged.getEntityId() == targetId) {
            recordHitDelay();
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Returns the rolling average delay (in milliseconds) between sending an
     * attack and receiving the damage confirmation from the server.
     */
    public long getAverageHitDelay() {
        if (hitDelays.isEmpty()) return 0L;
        long total = 0L;
        for (long delay : hitDelays) {
            total += delay;
        }
        return total / hitDelays.size();
    }

    /**
     * Returns the expected hurt-time offset in ticks, derived from the average
     * hit delay.
     */
    public int getExpectedHurtTimeTicks() {
        return (int) Math.floor((double) getAverageHitDelay() / 50.0);
    }

    public long getLastHitTime() {
        return lastHitTime;
    }

    public long getLastAttackTime() {
        return lastAttackTime;
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private void recordHitDelay() {
        long delay = System.currentTimeMillis() - lastHitTime;
        if (delay >= MAX_VALID_DELAY_MS) return;

        hitDelays.add(delay);
        if (hitDelays.size() > MAX_SAMPLES) {
            hitDelays.remove(0);
        }
    }

    private static Entity getDamagePacketEntity(net.minecraft.network.Packet<?> packet) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return null;

        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            // Opcode 2 = entity hurt animation
            if (status.getOpCode() == 2) {
                return status.getEntity(mc.theWorld);
            }
        }

        if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation anim = (S0BPacketAnimation) packet;
            // Animation type 1 = hurt/damage
            if (anim.getAnimationType() == 1) {
                return mc.theWorld.getEntityByID(anim.getEntityID());
            }
        }

        return null;
    }
}
