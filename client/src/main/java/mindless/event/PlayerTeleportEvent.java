package mindless.event;

import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class PlayerTeleportEvent extends Event {
    public PlayerTeleportEvent() { this(null); }
    public S08PacketPlayerPosLook packet;

    public PlayerTeleportEvent(S08PacketPlayerPosLook packet) {
        this.packet = packet;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
