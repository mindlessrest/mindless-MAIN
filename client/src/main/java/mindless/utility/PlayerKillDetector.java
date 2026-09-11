package mindless.utility;

import mindless.event.PlayerKillEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.server.S0BPacketAnimation;
import net.minecraft.network.play.server.S13PacketDestroyEntities;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.entity.DataWatcher;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class PlayerKillDetector {
    public static final PlayerKillDetector INSTANCE = new PlayerKillDetector();

    private static final int HEALTH_WATCHER_ID = 6;
    private static final long KILL_CREDIT_WINDOW_MS = 7000L;
    /*
     * How stale the last attack and the last hurt may be when the server removes a player and
     * we still call it our kill.
     *
     * These were under two seconds and under one, which is fine for a melee finish but wrong
     * for the way most Bedwars kills actually end: you knock someone off, they fall, and the
     * server removes them several seconds after your last hit landed. No death status and no
     * health update arrive for a void death, so the removal is the only signal there is -- and
     * it was arriving outside both windows, which is why the effect only fired now and then.
     */
    private static final long DESTROY_ATTACK_WINDOW_MS = 5000L;
    private static final long DESTROY_DAMAGE_WINDOW_MS = 3500L;
    private static final long DEDUPLICATION_WINDOW_MS = 3000L;

    private final Map<Integer, TrackedPlayer> tracked = new HashMap<Integer, TrackedPlayer>();
    private final Map<Integer, Long> emitted = new HashMap<Integer, Long>();
    /** Kills waiting to be announced. Written under the monitor, drained without it. */
    private final List<KillSnapshot> outbox = new ArrayList<KillSnapshot>();
    private WorldClient world;

    private PlayerKillDetector() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void onSendPacket(SendPacketEvent event) {
        if (event.isCanceled() || !(event.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }
        C02PacketUseEntity packet = (C02PacketUseEntity) event.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        Entity entity = packet.getEntityFromWorld(mc.theWorld);
        if (!(entity instanceof EntityPlayer) || entity == mc.thePlayer) {
            return;
        }
        EntityPlayer player = (EntityPlayer) entity;
        long now = System.currentTimeMillis();
        TrackedPlayer target = tracked.get(player.getEntityId());
        if (target == null) {
            target = new TrackedPlayer(player);
            tracked.put(player.getEntityId(), target);
        }
        target.update(player, now);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public synchronized void onReceivePacket(ReceivePacketEvent event) {
        if (event.isCanceled()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            return;
        }
        Packet<?> packet = event.getPacket();
        long now = System.currentTimeMillis();

        if (packet instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus status = (S19PacketEntityStatus) packet;
            Entity entity = status.getEntity(mc.theWorld);
            if (entity instanceof EntityPlayer) {
                TrackedPlayer target = tracked.get(entity.getEntityId());
                if (target != null) {
                    target.capture((EntityPlayer) entity);
                    if (status.getOpCode() == 3) {
                        emit(target, now);
                    }
                    else if (status.getOpCode() == 2) {
                        target.lastDamageAt = now;
                    }
                }
            }
            return;
        }

        if (packet instanceof S0BPacketAnimation) {
            S0BPacketAnimation animation = (S0BPacketAnimation) packet;
            if (animation.getAnimationType() == 1) {
                TrackedPlayer target = tracked.get(animation.getEntityID());
                if (target != null) {
                    target.lastDamageAt = now;
                }
            }
            return;
        }

        if (packet instanceof S1CPacketEntityMetadata) {
            S1CPacketEntityMetadata metadata = (S1CPacketEntityMetadata) packet;
            TrackedPlayer target = tracked.get(metadata.getEntityId());
            if (target != null) {
                List<DataWatcher.WatchableObject> values = metadata.func_149376_c();
                if (values != null) {
                    for (DataWatcher.WatchableObject value : values) {
                        if (value != null && value.getDataValueId() == HEALTH_WATCHER_ID
                                && value.getObject() instanceof Float
                                && ((Float) value.getObject()).floatValue() <= 0.0f) {
                            emit(target, now);
                            break;
                        }
                    }
                }
            }
            return;
        }

        if (packet instanceof S13PacketDestroyEntities) {
            for (int entityId : ((S13PacketDestroyEntities) packet).getEntityIDs()) {
                TrackedPlayer target = tracked.get(entityId);
                if (target != null && now - target.lastAttackAt <= DESTROY_ATTACK_WINDOW_MS
                        && now - target.lastDamageAt <= DESTROY_DAMAGE_WINDOW_MS) {
                    emit(target, now);
                }
            }
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        List<KillSnapshot> ready;
        WorldClient announceIn;
        synchronized (this) {
            announceIn = sweep();
            ready = new ArrayList<KillSnapshot>(outbox);
            outbox.clear();
        }
        announce(ready, announceIn);
    }

    /**
     * Expire stale entries and pick up deaths the packets did not describe.
     *
     * @return the world the queued kills belong to, or null when there is nothing to say.
     */
    private WorldClient sweep() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld != world) {
            tracked.clear();
            emitted.clear();
            outbox.clear();
            world = mc.theWorld;
        }
        if (mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, TrackedPlayer>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, TrackedPlayer> entry = iterator.next();
            TrackedPlayer target = entry.getValue();
            if (now - target.lastAttackAt > KILL_CREDIT_WINDOW_MS) {
                iterator.remove();
                continue;
            }
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (entity instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entity;
                target.capture(player);
                if (player.getHealth() <= 0.0f || player.deathTime > 0 || player.isDead) {
                    emit(target, now);
                }
            }
        }

        Iterator<Map.Entry<Integer, Long>> emittedIterator = emitted.entrySet().iterator();
        while (emittedIterator.hasNext()) {
            if (now - emittedIterator.next().getValue() > DEDUPLICATION_WINDOW_MS) {
                emittedIterator.remove();
            }
        }
        return mc.theWorld;
    }

    /**
     * Announce the queued kills, with no lock held.
     *
     * Subscribers run arbitrary code, and the packet thread shares this object's monitor,
     * so holding it across the post would let a slow listener stall the network read.
     */
    private void announce(List<KillSnapshot> ready, WorldClient announceIn) {
        if (announceIn == null || ready.isEmpty()) {
            return;
        }
        for (KillSnapshot snapshot : ready) {
            if (Minecraft.getMinecraft().theWorld != announceIn) {
                return;
            }
            Entity entity = announceIn.getEntityByID(snapshot.entityId);
            EntityPlayer player = entity instanceof EntityPlayer ? (EntityPlayer) entity : null;
            MinecraftForge.EVENT_BUS.post(new PlayerKillEvent(player, snapshot.entityId,
                    snapshot.name, snapshot.x, snapshot.y, snapshot.z));
        }
    }

    /**
     * Queue a kill for the next client tick.
     *
     * Every caller holds this object's monitor, so nothing here may reach for a second
     * lock. It used to call Minecraft.addScheduledTask from right here, which takes the
     * scheduled-task monitor -- and the game thread holds that one for the whole of the
     * task drain while a queued dispatch waits on this one. The packet thread and the
     * game thread took the two in opposite orders, and the client froze solid with no
     * exception and no crash report to show for it.
     *
     * Nothing is removed from the tracked map here either. Doing that used to run inside
     * the tick handler's own iteration over it.
     */
    private void emit(TrackedPlayer target, long now) {
        Long lastEmission = emitted.get(target.entityId);
        if (target.announced
                || now - target.lastAttackAt > KILL_CREDIT_WINDOW_MS
                || lastEmission != null && now - lastEmission.longValue() <= DEDUPLICATION_WINDOW_MS) {
            return;
        }
        target.announced = true;
        emitted.put(target.entityId, now);
        outbox.add(new KillSnapshot(target));
    }

    private static final class TrackedPlayer {
        final int entityId;
        String name;
        double x;
        double y;
        double z;
        long lastAttackAt;
        long lastDamageAt;
        /** Set once this death has been queued, so it is not announced twice. */
        boolean announced;

        TrackedPlayer(EntityPlayer player) {
            entityId = player.getEntityId();
            capture(player);
        }

        void update(EntityPlayer player, long now) {
            capture(player);
            lastAttackAt = now;
            // A fresh attack is a fresh engagement, so this death is worth announcing even
            // if an earlier one on the same player already was.
            announced = false;
        }

        void capture(EntityPlayer player) {
            name = player.getName();
            x = player.posX;
            y = player.posY;
            z = player.posZ;
        }
    }

    private static final class KillSnapshot {
        final int entityId;
        final String name;
        final double x;
        final double y;
        final double z;

        KillSnapshot(TrackedPlayer target) {
            entityId = target.entityId;
            name = target.name;
            x = target.x;
            y = target.y;
            z = target.z;
        }
    }
}
