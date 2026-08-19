package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;

public class MovementFix extends Module {

    public MovementFix() {
        super("Movement Fix", category.movement);
        this.registerSetting(new DescriptionSetting("Aligns input with rotations"));
    }

    @Override
    public void disable() {
        // Movement Fix is a client invariant, not a user-toggleable module.
    }

    @Override
    public void toggle() {
        // Ignore keybind, GUI, command, and script toggle requests.
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(true);
    }
}
