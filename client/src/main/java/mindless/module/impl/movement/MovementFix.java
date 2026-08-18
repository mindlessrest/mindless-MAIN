package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;

public class MovementFix extends Module {

    public MovementFix() {
        super("Movement Fix", category.movement);
        this.registerSetting(new DescriptionSetting("Aligns input with rotations"));
    }
}
