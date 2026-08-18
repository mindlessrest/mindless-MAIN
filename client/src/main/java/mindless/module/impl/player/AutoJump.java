package mindless.module.impl.player;

import mindless.event.PrePlayerInputEvent;
import mindless.module.Module;
import mindless.script.model.SimulatedPlayer;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoJump extends Module {
    public AutoJump() {
        super("Auto Jump", category.player);
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (!mc.thePlayer.onGround) return;

        SimulatedPlayer sim = SimulatedPlayer.fromClientPlayer(mc.thePlayer.movementInput);
        sim.tick();

        if (!sim.onGround && !e.isJump()) {
            e.setJump(true);
        }
    }
}
