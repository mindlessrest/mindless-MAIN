package mindless.module.impl.render;

import mindless.module.Module;

public class Fullbright extends Module {
    private float prevGamma;

    public Fullbright() {
        super("Fullbright", category.render);
    }

    @Override
    public void onEnable() {
        prevGamma = mc.gameSettings.gammaSetting;
        mc.gameSettings.gammaSetting = 1000f;
    }

    @Override
    public void onDisable() {
        mc.gameSettings.gammaSetting = prevGamma;
    }
}
