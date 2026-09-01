package mindless.event;

import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;
@Cancelable
public class PreAttackEvent extends Event {
    private boolean mindlessCanceled;
public final MovingObjectPosition objectMouseOver;

    public PreAttackEvent() {
        this(null);
    }

    public PreAttackEvent(MovingObjectPosition objectMouseOver) {
        this.objectMouseOver = objectMouseOver;
    }
@Override
    public boolean isCancelable() {
        return true;
    }

    @Override
    public boolean isCanceled() {
        return mindlessCanceled;
    }

    @Override
    public void setCanceled(boolean cancel) {
        mindlessCanceled = cancel;
    }
}
