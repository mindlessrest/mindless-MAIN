package keystrokesmod.event;

import net.minecraft.network.Packet;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class ReceivePacketEvent extends Event {
    public ReceivePacketEvent() {
        this(null);
    }

    private Packet<?> packet;

    public ReceivePacketEvent(Packet<?> packet) {
        this.packet = packet;
    }

    public Packet<?> getPacket() {
        return packet;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
