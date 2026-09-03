package mindless.script.packet.serverbound;

import mindless.script.packet.PacketMappings;
import mindless.script.packet.clientbound.*;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.network.play.server.*;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PacketHandler {
    private static final Map<Class<?>, Object> CLIENTBOUND_CONSTRUCTORS =
            new ConcurrentHashMap<Class<?>, Object>();
    private static final Object NO_CONSTRUCTOR = new Object();

    public static CPacket convertServerBound(net.minecraft.network.Packet packet) {
        if (packet == null || packet.getClass().getSimpleName().startsWith("S")) {
            return null;
        }
        Class<? extends CPacket> asClass = PacketMappings.minecraftToScriptC.get(packet.getClass());
        CPacket newPacket;
        if (asClass != null) {
            if (packet instanceof C03PacketPlayer) {
                newPacket = new C03((C03PacketPlayer)packet, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0);
            }
            else if (packet instanceof C01PacketChatMessage) {
                newPacket = new C01((C01PacketChatMessage)packet, (byte) 0);
            }
            else if (packet instanceof C09PacketHeldItemChange) {
                newPacket = new C09(((C09PacketHeldItemChange)packet), true);
            }
            else {
                try {
                    newPacket = asClass.getConstructor(packet.getClass()).newInstance(packet);
                }
                catch (Exception e) {
                    newPacket = new CPacket(packet);
                }
            }
        }
        else {
            newPacket = new CPacket(packet);
        }
        return newPacket;
    }

    public static SPacket convertClientBound(Packet packet) {
        Class<? extends SPacket> asClass = PacketMappings.minecraftToScriptS.get(packet.getClass());
        SPacket newPacket;
        if (asClass != null) {
            if (packet instanceof S3APacketTabComplete) {
                newPacket = new S3A((S3APacketTabComplete) packet, (byte) 0);
            }
            else if (packet instanceof S23PacketBlockChange) {
                newPacket = new S23((S23PacketBlockChange) packet, (byte) 0);
            }
            else {
                Constructor<? extends SPacket> constructor =
                        clientBoundConstructor(asClass, packet.getClass());
                if (constructor == null) {
                    newPacket = new SPacket(packet);
                }
                else {
                    try {
                        newPacket = constructor.newInstance(packet);
                    }
                    catch (Exception e) {
                        newPacket = new SPacket(packet);
                    }
                }
            }
        }
        else {
            newPacket = new SPacket(packet);
        }
        return newPacket;
    }

    /**
     * Resolves the wrapper constructor once per packet type.
     *
     * Several mapped wrappers take more than the packet, so getConstructor missed and threw
     * NoSuchMethodException for every packet of that type -- a reflective lookup and a filled-in
     * stack trace per packet on the netty thread, sixteen a second in a Hypixel game.
     */
    @SuppressWarnings("unchecked")
    private static Constructor<? extends SPacket> clientBoundConstructor(
            Class<? extends SPacket> asClass, Class<?> packetClass) {
        Object cached = CLIENTBOUND_CONSTRUCTORS.get(packetClass);
        if (cached == null) {
            try {
                cached = asClass.getConstructor(packetClass);
            }
            catch (NoSuchMethodException missing) {
                cached = NO_CONSTRUCTOR;
            }
            CLIENTBOUND_CONSTRUCTORS.put(packetClass, cached);
        }
        return cached == NO_CONSTRUCTOR ? null : (Constructor<? extends SPacket>) cached;
    }

    public static Packet convertCPacket(CPacket cPacket) {
        try {
            if (cPacket instanceof C0A) {
                return new C0APacketAnimation();
            }
            else if (cPacket instanceof C0B) {
                return ((C0B) cPacket).convert();
            }
            else if (cPacket instanceof C0D) {
                return ((C0D) cPacket).convert();
            }
            else if (cPacket instanceof C09) {
                return ((C09) cPacket).convert();
            }
            else if (cPacket instanceof C0E) {
                return ((C0E) cPacket).convert();
            }
            else if (cPacket instanceof C0F) {
                return ((C0F) cPacket).convert();
            }
            else if (cPacket instanceof C08) {
                return ((C08) cPacket).convert();
            }
            else if (cPacket instanceof C07) {
                return ((C07) cPacket).convert();
            }
            else if (cPacket instanceof C01) {
                return ((C01) cPacket).convert();
            }
            else if (cPacket instanceof C02) {
                return ((C02) cPacket).convert();
            }
            else if (cPacket instanceof C03) {
                return cPacket.packet;
            }
            else if (cPacket instanceof C10) {
                return ((C10) cPacket).convert();
            }
            else if (cPacket instanceof C13) {
                return ((C13) cPacket).convert();
            }
            else if (cPacket instanceof C16) {
                return ((C16) cPacket).convert();
            }
        }
        catch (Exception e) {
            if (cPacket != null && cPacket.packet != null && !cPacket.name.startsWith("S")) {
                return cPacket.packet;
            }
            else {
                return null;
            }
        }
        if (cPacket == null && cPacket.packet == null) {
            return null;
        }
        return cPacket.packet;
    }
}