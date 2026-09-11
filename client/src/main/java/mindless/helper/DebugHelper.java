package mindless.helper;

import mindless.utility.IMinecraftInstance;
import mindless.utility.Utils;

public class DebugHelper implements IMinecraftInstance {
    public static boolean MIXIN; // for debugging mixin related
    public static boolean BACKGROUND; // background processes like cache clearing and such

    public static void debugMixin(Object obj, String message) {
        if (!MIXIN) {
            return;
        }
        Utils.sendMessage("&d" + obj.getClass().getSimpleName() + "&7: " + message);
    }
}
