package mindless.runtime;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;

import java.util.ArrayDeque;

public final class CombatPacketState {
    private static boolean sentUseEntity;
    private static boolean sentDigging;
    private static boolean sentBlockPlacement;
    private static boolean sentHeldItemChange;
    private static boolean sentAnimation;
    private static boolean serverBlocking;
    private static long blockRevision;
    private static final ThreadLocal<Packet<?>> lastAcceptedPacket = new ThreadLocal<>();
    private static boolean pendingHurt;
    private static Float pendingHealthDelta;
    private static long session;
    private static long pendingAttackSession = -1L;
    private static int pendingAttackEntity = Integer.MIN_VALUE;
    private static boolean pendingAttackAccepted;
    private static final ThreadLocal<ArrayDeque<Packet<?>>> replayPackets =
            new ThreadLocal<ArrayDeque<Packet<?>>>() {
                @Override
                protected ArrayDeque<Packet<?>> initialValue() {
                    return new ArrayDeque<>();
                }
            };

    private CombatPacketState() {
    }

    public static synchronized void recordAccepted(Packet<?> packet) {
        lastAcceptedPacket.set(packet);
        if (packet instanceof C03PacketPlayer) {
            clearActions();
            return;
        }
        if (packet instanceof C02PacketUseEntity) {
            sentUseEntity = true;
            C02PacketUseEntity useEntity = (C02PacketUseEntity) packet;
            if (useEntity.getAction() == C02PacketUseEntity.Action.ATTACK
                    && pendingAttackSession == session
                    && AccessorBridge.C02PacketUseEntity_getEntityId(useEntity) == pendingAttackEntity) {
                pendingAttackAccepted = true;
            }
        }
        else if (packet instanceof C07PacketPlayerDigging) {
            sentDigging = true;
            if (((C07PacketPlayerDigging) packet).getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                serverBlocking = false;
                blockRevision++;
            }
        }
        else if (packet instanceof C08PacketPlayerBlockPlacement) {
            sentBlockPlacement = true;
            C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement) packet;
            if (placement.getPlacedBlockDirection() == 255 && placement.getStack() != null
                    && placement.getStack().getItem() instanceof net.minecraft.item.ItemSword) {
                serverBlocking = true;
                blockRevision++;
            }
        }
        else if (packet instanceof C09PacketHeldItemChange) {
            sentHeldItemChange = true;
            mindless.placement.PlacementRuntime.onHeldItemAccepted(((C09PacketHeldItemChange) packet).getSlotId());
        }
        else if (packet instanceof C0APacketAnimation) {
            sentAnimation = true;
        }
    }

    public static synchronized boolean sentDigging() {
        return sentDigging;
    }

    public static synchronized boolean sentBlockPlacement() {
        return sentBlockPlacement;
    }

    public static synchronized boolean sentUseEntity() {
        return sentUseEntity;
    }

    public static synchronized boolean sentHeldItemChange() {
        return sentHeldItemChange;
    }

    public static synchronized boolean sentAnimation() {
        return sentAnimation;
    }

    public static synchronized long beginSession() {
        session++;
        serverBlocking = false;
        blockRevision++;
        lastAcceptedPacket.remove();
        pendingHurt = false;
        pendingHealthDelta = null;
        clearActions();
        pendingAttackSession = -1L;
        pendingAttackEntity = Integer.MIN_VALUE;
        pendingAttackAccepted = false;
        return session;
    }

    public static synchronized void resetSession() {
        beginSession();
    }

    public static synchronized long currentSession() {
        return session;
    }

    public static synchronized boolean serverBlocking() { return serverBlocking; }
    public static synchronized long blockRevision() { return blockRevision; }
    public static synchronized boolean wasAccepted(Packet<?> packet) { return lastAcceptedPacket.get() == packet; }

    public static void observeHealth(Packet<?> packet) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        recordHealth(packet, mc.thePlayer.getEntityId(), mc.thePlayer.getHealth(), currentSession());
    }

    public static synchronized void recordHealth(Packet<?> packet, int playerId, float previous, long epoch) {
        if (epoch != session) return;
        Float health = null;
        if (packet instanceof net.minecraft.network.play.server.S06PacketUpdateHealth)
            health = ((net.minecraft.network.play.server.S06PacketUpdateHealth) packet).getHealth();
        if (packet instanceof net.minecraft.network.play.server.S1CPacketEntityMetadata) {
            net.minecraft.network.play.server.S1CPacketEntityMetadata metadata = (net.minecraft.network.play.server.S1CPacketEntityMetadata) packet;
            if (metadata.getEntityId() != playerId || metadata.func_149376_c() == null) return;
            for (net.minecraft.entity.DataWatcher.WatchableObject entry : metadata.func_149376_c()) {
                if (entry != null && entry.getDataValueId() == 6 && entry.getObject() instanceof Number)
                    health = ((Number) entry.getObject()).floatValue();
            }
        }
        if (health == null || !Float.isFinite(health) || !Float.isFinite(previous) || health == previous) return;
        pendingHurt |= health < previous;
        if (pendingHealthDelta == null) pendingHealthDelta = health - previous;
    }

    public static synchronized boolean consumeHurt() { boolean value = pendingHurt; pendingHurt = false; return value; }
    public static synchronized Float consumeHealthDelta() { Float value = pendingHealthDelta; pendingHealthDelta = null; return value; }

    public static synchronized void beginAuraAttack(int entityId) {
        pendingAttackSession = session;
        pendingAttackEntity = entityId;
        pendingAttackAccepted = false;
    }

    public static synchronized boolean consumeAuraAttackAccepted(int entityId) {
        boolean accepted = pendingAttackSession == session
                && pendingAttackEntity == entityId && pendingAttackAccepted;
        if (pendingAttackEntity == entityId) {
            pendingAttackSession = -1L;
            pendingAttackEntity = Integer.MIN_VALUE;
            pendingAttackAccepted = false;
        }
        return accepted;
    }

    public static synchronized void clearActions() {
        sentUseEntity = false;
        sentDigging = false;
        sentBlockPlacement = false;
        sentHeldItemChange = false;
        sentAnimation = false;
    }

    public static void markReplay(Packet<?> packet) {
        if (packet != null) {
            replayPackets.get().addLast(packet);
        }
    }

    public static boolean consumeReplay(Packet<?> packet) {
        if (packet == null) {
            return false;
        }
        ArrayDeque<Packet<?>> packets = replayPackets.get();
        for (java.util.Iterator<Packet<?>> iterator = packets.descendingIterator(); iterator.hasNext();) {
            if (iterator.next() == packet) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
}
