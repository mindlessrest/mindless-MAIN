package mindless.module.impl.bedwars;

import mindless.event.GameWinEvent;
import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.setting.impl.TextSetting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoGG extends Module {
    private final TextSetting message;
    private final SliderSetting delay;

    public AutoGG() {
        super("Auto GG", "Sends your chosen message when the game ends.", category.bedwars);
        this.registerSetting(message = new TextSetting("Message", "gg", "gg", 64));
        this.registerSetting(delay = new SliderSetting("Delay (ms)", 500, 0, 3000, 100));
    }

    @SubscribeEvent
    public void onGameWin(GameWinEvent event) {
        if (mc.thePlayer == null) return;
        String msg = message.getText().trim();
        if (msg.isEmpty()) return;

        long delayMs = (long) delay.getInput();
        if (delayMs <= 0) {
            mc.thePlayer.sendChatMessage("/ac " + msg);
        } else {
            new Thread(() -> {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                mc.addScheduledTask(() -> {
                    if (mc.thePlayer != null) mc.thePlayer.sendChatMessage("/ac " + msg);
                });
            }).start();
        }
    }
}
