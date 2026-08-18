package mindless.event;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class PreSlotScrollEvent extends Event {
    public PreSlotScrollEvent() {
        this(0, 0);
    }

    public int slot;
    public int previousSlot;

    public PreSlotScrollEvent(int slot, int previousSlot) {
        this.slot = slot;
        this.previousSlot = previousSlot;
    }

    @Override
    public boolean isCancelable() {
        return true;
    }
}
