package mindless.runtime;

import mindless.Raven;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/**
 * The one listener that survives an uninject, so the client can be brought back.
 *
 * Uninject takes every handler off the buses, which necessarily includes the ones that would let
 * anything be clicked or bound afterwards -- the ClickGUI is gone, so there is nothing left to
 * press. Re-attaching from the loader is not an option either: the DLL is still resident and the
 * classes are still defined, so it refuses with "already attached" and would re-run against the
 * same bytecode even if it did not.
 *
 * This handler is therefore registered outside the tracked set and never removed. It does nothing
 * at all while the client is live, and once uninjected it watches a single key.
 */
public final class ReinjectHotkey {
    /** Guards against a held key firing on every tick of the press. */
    private boolean wasDown;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        int key = Raven.getReinjectKey();
        boolean down = key > 0 && Keyboard.isKeyDown(key);
        if (down && !wasDown && Raven.isUnloaded()) {
            Raven.reinject();
        }
        wasDown = down;
    }
}
