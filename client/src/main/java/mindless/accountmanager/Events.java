package mindless.accountmanager;

import java.lang.reflect.Field;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mindless.accountmanager.AccountManager;
import mindless.accountmanager.auth.Account;
import mindless.accountmanager.auth.SessionManager;
import mindless.accountmanager.gui.GuiAccountManager;
import mindless.accountmanager.utils.TextFormatting;
import mindless.utility.HypixelLanguage;
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
    /**
     * Ban notices, matched against the plain text of the disconnect screen.
     *
     * The previous patterns were written against strings containing section-sign colour codes,
     * but they were compared to getUnformattedText(), which strips exactly those codes. Nothing
     * ever matched, so no ban was ever recorded. Matching the words instead also survives
     * Hypixel restyling the message.
     */
    private static final Pattern PERMANENT_BAN = Pattern.compile(
            "permanently banned|account has been blocked", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMPORARY_BAN = Pattern.compile(
            "temporarily (?:banned|blocked) for (.+?) from this server", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PART = Pattern.compile("(\\d+)\\s*([dhmsjt])",
            Pattern.CASE_INSENSITIVE);

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final int KEY_RSHIFT = Keyboard.KEY_RSHIFT;
    private boolean prevShiftDown = false;
    private GuiScreen lastScreen = null;
    private int screenOpenTicks = 0;

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            SessionManager.captureLaunchSession();
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
            String text = message.getUnformattedText().split("\n\n")[0].trim();

            if (PERMANENT_BAN.matcher(text).find()
                    || HypixelLanguage.contains(text, HypixelLanguage.Key.PERMANENT_BAN)
                    || HypixelLanguage.contains(text, HypixelLanguage.Key.ACCOUNT_BLOCKED)) {
                recordUnban(-1L);
                return;
            }

            Matcher temporary = TEMPORARY_BAN.matcher(text);
            if (temporary.find()) {
                long time = parseUnbanTime(temporary.group(1));
                if (time > 0L) {
                    recordUnban(time);
                }
            } else if (HypixelLanguage.contains(text, HypixelLanguage.Key.TEMPORARY_BAN)
                    || HypixelLanguage.contains(text, HypixelLanguage.Key.TEMPORARY_BLOCKED)) {
                long time = parseUnbanTime(text);
                if (time > 0L) recordUnban(time);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Turns "3d 4h 5m" into an absolute unban timestamp, or 0 if nothing parsed.
     *
     * Tolerant by design: the old loop called Long.parseLong on every whitespace-separated
     * token and one unexpected word threw, which the outer catch swallowed along with the
     * whole ban record.
     */
    private static long parseUnbanTime(String duration) {
        Matcher part = DURATION_PART.matcher(duration);
        long span = 0L;
        while (part.find()) {
            long value = Long.parseLong(part.group(1));
            switch (Character.toLowerCase(part.group(2).charAt(0))) {
                case 'd': case 'j': case 't': span += value * 86400000L; break;
                case 'h': span += value * 3600000L;  break;
                case 'm': span += value * 60000L;    break;
                case 's': span += value * 1000L;     break;
                default: break;
            }
        }
        return span == 0L ? 0L : System.currentTimeMillis() + span;
    }

    private static void recordUnban(long unban) {
        String username = mc.getSession() == null ? null : mc.getSession().getUsername();
        if (StringUtils.isBlank(username)) {
            return;
        }
        AccountManager.load();
        for (Account account : AccountManager.accounts) {
            if (!username.equals(account.getUsername())) continue;
            account.setUnban(unban);
        }
        AccountManager.save();
    }
}
