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
        if (entity.hurtResistantTime == 0 && now - lastHitTime > MIN_ATTACK_INTERVAL_MS) {
            if (now - lastAttackTime > getAverageHitDelay() * 2L) {
                lastHitTime = now;
            }
        }

        targetId = entityId;
        lastAttackTime = now;
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        if (e.isCanceled()) return;

        Entity damaged = getDamagePacketEntity(e.getPacket());
        if (damaged != null && damaged.getEntityId() == targetId) {
            recordHitDelay();
        }
    }
public long getAverageHitDelay() {
        if (hitDelays.isEmpty()) return 0L;
        long total = 0L;
        for (long delay : hitDelays) {
            total += delay;
        }
        return total / hitDelays.size();
    }
public int getExpectedHurtTimeTicks() {
        return (int) Math.floor((double) getAverageHitDelay() / 50.0);
    }

    public long getLastHitTime() {
        return lastHitTime;
    }

    public long getLastAttackTime() {
        return lastAttackTime;
    }

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
            if (status.getOpCode() == 2) {
                return status.getEntity(mc.theWorld);
            }
        }

        if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation anim = (S0BPacketAnimation) packet;
            if (anim.getAnimationType() == 1) {
                return mc.theWorld.getEntityByID(anim.getEntityID());
            }
        }

        return null;
    }
}
