package mindless.module.impl.other;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class DiscordRPC extends Module {
    public static ButtonSetting showServer;

    private mindless.utility.DiscordRPC rpc;
    private long startTimestamp;
    private String lastState = null;
    private String lastDetails = null;

    public DiscordRPC() {
        super("Discord RPC", Module.category.other);
        this.registerSetting(showServer = new ButtonSetting("Show Server", true));
    }

    @Override
    public void onEnable() {
        startTimestamp = System.currentTimeMillis() / 1000L;
        if (rpc == null) {
            rpc = new mindless.utility.DiscordRPC("1541533225237749760");
            rpc.connect();
        }
        lastState = null;
        lastDetails = null;
        updatePresence(true);
    }

    @Override
    public void onDisable() {
        if (rpc != null) {
            rpc.close();
            rpc = null;
        }
    }

    @Override
    public void onUpdate() {
        updatePresence(false);
    }

    @Override
    public void guiUpdate() {
        updatePresence(false);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        updatePresence(false);
    }

    private void updatePresence(boolean force) {
        if (rpc == null) return;

        String state = "";

        if (showServer != null && showServer.isToggled()) {
            if (mc.theWorld == null || mc.currentScreen instanceof GuiMainMenu) {
                state = "Main Menu";
            } else if (mc.isSingleplayer()) {
                state = "Singleplayer";
            } else {
                ServerData serverData = mc.getCurrentServerData();
                if (serverData != null && serverData.serverIP != null) {
                    state = "Playing on " + serverData.serverIP;
                } else {
                    state = "Multiplayer";
                }
            }
        }

        if (force || !java.util.Objects.equals(state, lastState)) {
            lastState = state;

            mindless.utility.DiscordRPC.RichPresence presence = new mindless.utility.DiscordRPC.RichPresence()
                    .startTimestamp(startTimestamp)
                    .largeImage("large_image", "Mindless");

            if (!state.isEmpty()) {
                presence.details(state);
            }

            rpc.update(presence);
        }
    }
}
