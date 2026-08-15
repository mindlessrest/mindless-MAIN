package keystrokesmod.accountmanager;

import java.lang.reflect.Field;
import keystrokesmod.accountmanager.AccountManager;
import keystrokesmod.accountmanager.auth.Account;
import keystrokesmod.accountmanager.auth.SessionManager;
import keystrokesmod.accountmanager.gui.GuiAccountManager;
import keystrokesmod.accountmanager.utils.TextFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiDisconnected;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.lang3.StringUtils;
import org.lwjgl.input.Keyboard;

public class Events {
    private static final Minecraft mc = Minecraft.getMinecraft();

    // Right Shift = LWJGL key 54
    private static final int KEY_RSHIFT = Keyboard.KEY_RSHIFT;
    private boolean prevShiftDown = false;
    private GuiScreen lastScreen = null;
    private int screenOpenTicks = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SessionManager.captureLaunchSession();
            // Track how long the current screen has been open
            if (mc.currentScreen != lastScreen) {
                lastScreen = mc.currentScreen;
                screenOpenTicks = 0;
            } else if (mc.currentScreen != null) {
                screenOpenTicks++;
            }
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        boolean shiftDown = Keyboard.isKeyDown(KEY_RSHIFT);
        // Only open from pre-game screens (main menu, multiplayer list, etc.)
        // mc.theWorld != null means the player is in a game — never open there.
        if (shiftDown && !prevShiftDown
                && mc.theWorld == null
                && mc.currentScreen != null
                && !(mc.currentScreen instanceof GuiAccountManager)
                && screenOpenTicks >= 10) {
            mc.displayGuiScreen(new GuiAccountManager(mc.currentScreen));
        }
        prevShiftDown = shiftDown;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        String serverIP;
        ServerData serverData = mc.getCurrentServerData();
        if (serverData != null && ((serverIP = serverData.serverIP).endsWith("hypixel.net")
                || serverIP.endsWith("hypixel.io"))) {
            AccountManager.load();
            for (Account account : AccountManager.accounts) {
                if (!mc.getSession().getUsername().equals(account.getUsername())) continue;
                account.setUnban(0L);
            }
            AccountManager.save();
        }
    }

    @SubscribeEvent
    public void onDisconnectGui(GuiScreenEvent.InitGuiEvent.Post event) {
        if (!(event.gui instanceof GuiDisconnected)) return;
        try {
            Field f = GuiDisconnected.class.getDeclaredField("message");
            f.setAccessible(true);
            IChatComponent message = (IChatComponent) f.get(event.gui);
            String text = message.getUnformattedText().split("\n\n")[0];
            if (text.equals("\u00a7r\u00a7cYou are permanently banned from this server!")
                    || text.equals("\u00a7r\u00a7cYour account has been blocked.")) {
                AccountManager.load();
                for (Account account : AccountManager.accounts) {
                    if (!mc.getSession().getUsername().equals(account.getUsername())) continue;
                    account.setUnban(-1L);
                }
                AccountManager.save();
                return;
            }
            String unban = StringUtils.substringBetween(text, "\u00a7r\u00a7f", "\u00a7r\u00a7c");
            if (unban != null && (text.matches("\u00a7r\u00a7cYou are temporarily banned for \u00a7r\u00a7f.*\u00a7r\u00a7c from this server!")
                    || text.matches("\u00a7r\u00a7cYour account is temporarily blocked for \u00a7r\u00a7f.*\u00a7r\u00a7c from this server!"))) {
                long time = System.currentTimeMillis();
                for (String duration : unban.split(" ")) {
                    String type = duration.substring(duration.length() - 1);
                    long value = Long.parseLong(duration.substring(0, duration.length() - 1));
                    switch (type) {
                        case "d": time += value * 86400000L; break;
                        case "h": time += value * 3600000L;  break;
                        case "m": time += value * 60000L;    break;
                        case "s": time += value * 1000L;     break;
                    }
                }
                AccountManager.load();
                for (Account account : AccountManager.accounts) {
                    if (!mc.getSession().getUsername().equals(account.getUsername())) continue;
                    account.setUnban(time);
                }
                AccountManager.save();
            }
        } catch (Exception ignored) {}
    }
}
