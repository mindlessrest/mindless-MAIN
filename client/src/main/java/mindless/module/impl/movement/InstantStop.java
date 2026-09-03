package mindless.module.impl.movement;

import mindless.event.PrePlayerInputEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class InstantStop extends Module {

    private final ButtonSetting onlyOnGround;
    private final ButtonSetting disableWhenFlying;

    private float lastForward;
    private float lastStrafe;

    public InstantStop() {
        super("Instant Stop", "Stops you dead when you release your keys.", category.movement, 0);
        this.registerSetting(onlyOnGround = new ButtonSetting("Only on ground", true));
        this.registerSetting(disableWhenFlying = new ButtonSetting("Disable when flying", true));
    }

    @Override
    public void onDisable() {
        this.lastForward = 0.0F;
        this.lastStrafe = 0.0F;
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!this.isEnabled() || !Utils.nullCheck()) {
            return;
        }
        if (mc.currentScreen != null) {
            return;
        }
        if (onlyOnGround.isToggled() && !mc.thePlayer.onGround) {
            this.lastForward = e.getForward();
            this.lastStrafe = e.getStrafe();
            return;
        }
        if (disableWhenFlying.isToggled() && mc.thePlayer.capabilities.isFlying) {
            this.lastForward = e.getForward();
            this.lastStrafe = e.getStrafe();
            return;
        }

        float rawF = e.getForward();
        float rawS = e.getStrafe();

        if (rawF == 0.0F && rawS == 0.0F && (this.lastForward != 0.0F || this.lastStrafe != 0.0F)) {
            e.setForward(Math.signum(-this.lastForward));
            e.setStrafe(Math.signum(-this.lastStrafe));
        }

        this.lastForward = rawF;
        this.lastStrafe = rawS;
    }
}
