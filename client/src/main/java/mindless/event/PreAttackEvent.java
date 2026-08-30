package mindless.event;

import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Fired at the start of Minecraft.clickMouse() before swingItem() or attackEntity().
 * Cancel to prevent both the swing packet (C0A) and attack packet (C02) from being sent
 * for normal left-click attacks.
 * <p>
 * For direct calls to attackEntity() (e.g. KillAura, scripts), use {@link AttackEvent} instead,
 * which still fires in PlayerControllerMP.attackEntity and can cancel the attack packet only.
 */
@Cancelable
public class PreAttackEvent extends Event {
    private boolean mindlessCanceled;

    /** The current mouse-over from this tick's getMouseOver; may be null or a block/entity hit. */
    public final MovingObjectPosition objectMouseOver;

    public PreAttackEvent() {
        this(null);
    }

    public PreAttackEvent(MovingObjectPosition objectMouseOver) {
        this.objectMouseOver = objectMouseOver;
    }

    /*
     * Lunar's embedded Forge does not reliably discover @Cancelable on
     * injected event classes. Keep cancellation local so Hit Select/Bed Aura
     * can suppress the transformed clickMouse call without throwing.
     */
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
