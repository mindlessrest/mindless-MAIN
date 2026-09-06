package mindless.utility;

import mindless.Mindless;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static net.minecraft.util.EnumFacing.DOWN;

public class PacketUtils implements IMinecraftInstance {
    private static final Set<Packet<?>> skipSendEvent = Collections.newSetFromMap(
            Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>())
    );
    private static final Set<Packet<?>> skipReceiveEvent = Collections.newSetFromMap(
            Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>())
    );

    public static boolean consumeSendEventSkip(Packet<?> packet) {
        return skipSendEvent.remove(packet);
    }

    public static boolean consumeReceiveEventSkip(Packet<?> packet) {
        return skipReceiveEvent.remove(packet);
    }

    public static void sendPacketNoEvent(Packet packet) {
        if (packet == null || isClientboundPacket(packet)) {
            return;
        }
        skipSendEvent.add(packet);
        Mindless.mc.thePlayer.sendQueue.addToSendQueue(packet);
    }

    /**
     * Clientbound packet types are the ones whose class name starts with S.
     *
     * getSimpleName builds a fresh String on every call, and this runs on every packet any module
     * sends without an event -- it was the largest single allocation site in the client.
     * getName is interned on the Class, so reading a character out of it costs nothing.
     */
    private static boolean isClientboundPacket(Packet packet) {
        String className = packet.getClass().getName();
        int start = className.lastIndexOf('.') + 1;
        return start < className.length() && className.charAt(start) == 'S';
    }

    public static void receivePacketNoEvent(Packet packet) {
        try {
            packet.processPacket(Mindless.mc.getNetHandler());
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void sendReleasePacket() {
        mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, DOWN));
    }
}
