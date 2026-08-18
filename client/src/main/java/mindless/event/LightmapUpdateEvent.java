package mindless.event;

import net.minecraftforge.fml.common.eventhandler.Event;

public class LightmapUpdateEvent extends Event {
    public int[] lightmapColors;

    public LightmapUpdateEvent() {
        this(null);
    }

    public LightmapUpdateEvent(int[] lightmapColors) {
        this.lightmapColors = lightmapColors;
    }
}
