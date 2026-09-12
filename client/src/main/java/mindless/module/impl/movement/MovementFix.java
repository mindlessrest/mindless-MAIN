package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;

public class MovementFix extends Module {
    private final SliderSetting mode;

    public MovementFix() {
        super("Movement Fix", "Keeps input aligned while modules rotate you.", category.movement);
        this.registerSetting(new DescriptionSetting("Aligns input with rotations"));
        this.registerSetting(mode = new SliderSetting("Mode", 0, new String[]{"Silent", "Strict"}));
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

    public boolean isStrict() {
        return (int) mode.getInput() == 1;
    }
}
