package mindless.event;

import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.Cancelable;

/**
 * MouseEvent subclass that forces cancelability on Lunar's embedded Forge,
 * which does not reliably discover @Cancelable via annotation scanning.
 */
@Cancelable
public class CancelableMouseEvent extends MouseEvent {
    private boolean ravenCanceled;

    @Override
    public boolean isCancelable() {
        return true;
    }

    @Override
    public boolean isCanceled() {
        return ravenCanceled;
    }

    @Override
    public void setCanceled(boolean cancel) {
        ravenCanceled = cancel;
    }
}
