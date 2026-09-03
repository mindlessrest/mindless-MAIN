package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;

public class MovementFix extends Module {

    public MovementFix() {
        super("Movement Fix", "Keeps input aligned while modules rotate you.", category.movement);
        this.registerSetting(new DescriptionSetting("Aligns input with rotations"));
    }

    @Override
    public void disable() {
    }

    @Override
    public void toggle() {
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(true);
    }
}
