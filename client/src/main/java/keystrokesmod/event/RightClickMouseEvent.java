package keystrokesmod.event;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

@Cancelable
public class RightClickMouseEvent extends Event {

    @Override
    public boolean isCancelable() {
        return true;
    }
}
