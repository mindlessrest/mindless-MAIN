package mindless.module.impl.world;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;

public class Ambience extends Module {
    public static SliderSetting timeMode;
    public static ButtonSetting darkMode;

    private static float savedGamma = 0.0f;

    private static final String[] timeNames = { "Normal", "Day", "Night", "Sunset" };

    public Ambience() {
        super("Ambience", "Locks the time of day, with a dark mode.", category.world);
        this.registerSetting(timeMode = new SliderSetting("Time", 0, timeNames));
        this.registerSetting(darkMode = new ButtonSetting("Dark mode", false));
    }

    @Override
    public void onEnable() {
        savedGamma = mc.gameSettings.gammaSetting;
    }

    @Override
    public void onDisable() {
        mc.gameSettings.gammaSetting = savedGamma;
    }

    @Override
    public void onUpdate() {
        if (darkMode.isToggled()) {
            mc.gameSettings.gammaSetting = -0.5f;
        } else if (mc.gameSettings.gammaSetting < 0.0f) {
            mc.gameSettings.gammaSetting = savedGamma;
        }
    }

    public static long getTimeOverride(long original) {
        if (timeMode == null) return original;
        int mode = (int) timeMode.getInput();
        switch (mode) {
            case 1: return 6000;   // Day
            case 2: return 18000;  // Night
            case 3: return 13000;  // Sunset
            default: return original;
        }
    }
}
