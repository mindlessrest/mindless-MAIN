package mindless.module.impl.player;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class DelayRemover extends Module {
    public ButtonSetting oldReg, removeJumpTicks;

    public DelayRemover() {
        super("Delay Remover", category.player, 0);
        this.liteModule = true;
        this.registerSetting(oldReg = new ButtonSetting("1.7 hitreg", true));
        this.registerSetting(removeJumpTicks = new ButtonSetting("Remove jump ticks", false));
        this.closetModule = true;
    }

    @SubscribeEvent
    public void onTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !mc.inGameHasFocus || !Utils.nullCheck()) {
            return;
        }
        if (oldReg.isToggled()) {
            AccessorBridge.Minecraft_setLeftClickCounter(mc, 0);
        }
        if (removeJumpTicks.isToggled()) {
            AccessorBridge.EntityLivingBase_setJumpTicks(mc.thePlayer, 0);
        }
    }

    public static boolean shouldRemoveHitDelay() {
        return mindless.module.ModuleManager.getModule(DelayRemover.class) != null
                && ((DelayRemover) mindless.module.ModuleManager.getModule(DelayRemover.class)).isEnabled()
                && ((DelayRemover) mindless.module.ModuleManager.getModule(DelayRemover.class)).oldReg.isToggled();
    }

    public static boolean shouldRemoveBreakDelay() {
        return shouldRemoveHitDelay();
    }
}
