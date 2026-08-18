package mindless.module.impl.network;

import mindless.module.Module;
import mindless.utility.Utils;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Limbo extends Module {
    private boolean waitingForDisconnectSpam;

    public Limbo() {
        super("Limbo", category.network);
    }

    @Override
    public void onEnable() {
        if (!Utils.isHypixel()) {
            Utils.sendMessage("&cYou are not on hypixel.");
            this.disable();
            return;
        }
        waitingForDisconnectSpam = false;
        for (int i = 0; i < 12; i++) mc.thePlayer.sendChatMessage("/");
    }

    @Override
    public void onDisable() {
        waitingForDisconnectSpam = false;
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        String message = StringUtils.stripControlCodes(event.message.getUnformattedText());
        if (message.startsWith("Unknown command. Type \"help\" for help. (")) {
            event.setCanceled(true);
            waitingForDisconnectSpam = true;
            return;
        }
        if (waitingForDisconnectSpam && message.contains("disconnect.spam")) {
            waitingForDisconnectSpam = false;
            event.setCanceled(true);
            this.disable();
        }
    }
}
