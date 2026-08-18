package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;

public class VClip extends Module {
    private SliderSetting distance;
    private ButtonSetting sendMessage;

    public VClip() {
        super("VClip", category.movement, 0);
        this.registerSetting(distance = new SliderSetting("Distance", 3.0, -20.0, 20.0, 0.5));
        this.registerSetting(sendMessage = new ButtonSetting("Send message", true));
    }

    public void onEnable() {
        double distance = this.distance.getInput();
        if (this.distance.getInput() != 0.0D) {
            mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + distance, mc.thePlayer.posZ);
            if (sendMessage.isToggled()) {
                Utils.sendMessage("&7Teleported you " + ((distance > 0.0) ? "upwards" : "downwards") + " by &b" + distance + " &7blocks.");
            }
        }

        this.disable();
    }
}
