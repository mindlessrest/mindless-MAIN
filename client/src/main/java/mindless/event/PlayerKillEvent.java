package mindless.event;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.eventhandler.Event;

public class PlayerKillEvent extends Event {
    public final int entityId;
    public final String playerName;
    public final double x;
    public final double y;
    public final double z;
    public final EntityPlayer player;

    public PlayerKillEvent() {
        this(null, -1, "", 0.0, 0.0, 0.0);
    }

    public PlayerKillEvent(EntityPlayer player, int entityId, String playerName,
                           double x, double y, double z) {
        this.player = player;
        this.entityId = entityId;
        this.playerName = playerName;
        this.x = x;
        this.y = y;
        this.z = z;
    }
}
