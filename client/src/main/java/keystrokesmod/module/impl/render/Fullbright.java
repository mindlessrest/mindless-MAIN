package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;

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
