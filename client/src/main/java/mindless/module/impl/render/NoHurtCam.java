package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;

public class NoHurtCam extends Module {
    public SliderSetting multiplier;
    public NoHurtCam() {
        super("No Hurt Cam", "Cuts the camera shake when you take damage.", category.render);
        this.registerSetting(new DescriptionSetting("Default is 14x multiplier."));
        this.registerSetting(multiplier = new SliderSetting("Multiplier", 14, -40, 40, 1));
    }
}
