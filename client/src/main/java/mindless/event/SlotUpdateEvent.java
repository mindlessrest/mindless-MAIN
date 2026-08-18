package mindless.event;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class SlotUpdateEvent extends Event {
    public SlotUpdateEvent() {
        this(0);
    }

    public int slot;

    public SlotUpdateEvent(int slot) {
        this.slot = slot;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
