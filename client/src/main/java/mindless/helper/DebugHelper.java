package mindless.helper;

import mindless.Mindless;
import mindless.module.ModuleManager;
import mindless.utility.IMinecraftInstance;
import mindless.utility.RenderUtils;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class DebugHelper implements IMinecraftInstance {
    public static boolean MIXIN; // for debugging mixin related
    public static boolean BACKGROUND; // background processes like cache clearing and such

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (!Mindless.DEBUG || ev.phase != TickEvent.Phase.END || !Utils.nullCheck() || !ModuleManager.debug.debugBPS.isToggled() || mc.currentScreen != null) {
            return;
        }
        RenderUtils.renderBPS(true, true);
    }

    public static void debugMixin(Object obj, String message) {
        if (!MIXIN) {
            return;
        }
        Utils.sendMessage("&d" + obj.getClass().getSimpleName() + "&7: " + message);
    }
}
