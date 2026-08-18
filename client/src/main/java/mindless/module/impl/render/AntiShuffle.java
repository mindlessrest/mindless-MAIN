package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.DescriptionSetting;

public class AntiShuffle extends Module {
    private static String shuffleStr = "§k";

    public AntiShuffle() {
        super("Anti Shuffle", Module.category.render, 0);
        this.registerSetting(new DescriptionSetting("Removes obfuscation (" + shuffleStr + "hey" + "§" + "r)."));
    }

    public static String removeObfuscation(String s) {
        return s.replace(shuffleStr, "");
    }
}
