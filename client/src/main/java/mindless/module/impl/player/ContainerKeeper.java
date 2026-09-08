package mindless.module.impl.player;

import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.utility.Utils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

/**
 * Keep a container on screen after the server has taken it away.
 *
 * Hypixel closes shop and chest menus out from under you constantly -- on a round starting, on
 * being hit, on walking a step too far. The window is gone before you have read it, and the
 * contents are the part you wanted.
 *
 * Two directions to stop. The client sends C0D when it closes a window itself, and the server
 * sends S2E to force one shut; both are suppressed while a container is saved, so the screen
 * stays up and stays readable.
 *
 * The container is only a picture at that point. The server has forgotten the window, so clicks
 * in it go nowhere -- this is for reading a shop or a chest you were thrown out of, not for
 * taking from one.
 */
public class ContainerKeeper extends Module {
    private final KeySetting saveKey;
    private final ButtonSetting requireShift;
    private final ButtonSetting keepOnServerClose;
    private final ButtonSetting keepOnDamage;

    /** The screen being held open, if any. */
    private GuiScreen kept;
    private boolean suppressing;

    public ContainerKeeper() {
        super("Container Keeper", "Keeps a container open after the server closes it.", category.player, 0);
        this.registerSetting(saveKey = new KeySetting("Save key", Keyboard.KEY_LSHIFT));
        this.registerSetting(requireShift = new ButtonSetting("Require key held", true));
        this.registerSetting(keepOnServerClose = new ButtonSetting("Keep on server close", true));
        this.registerSetting(keepOnDamage = new ButtonSetting("Keep on own close", false));
    }

    @Override
    public void onDisable() {
        release();
    }

    private void release() {
        kept = null;
        suppressing = false;
    }

    /** Whether the player is asking for the container in front of them to be held. */
    private boolean armed() {
        if (!requireShift.isToggled()) {
            return true;
        }
        int key = saveKey.getKey();
        return key > 0 && Keyboard.isKeyDown(key);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent event) {
        if (!keepOnDamage.isToggled() || !Utils.nullCheck()) {
            return;
        }
        if (!(event.getPacket() instanceof C0DPacketCloseWindow)) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiContainer) || !armed()) {
            return;
        }
        // Our own close, suppressed: the screen stays and the server keeps the window.
        event.setCanceled(true);
        kept = mc.currentScreen;
        suppressing = true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!keepOnServerClose.isToggled() || !Utils.nullCheck()) {
            return;
        }
        if (!(event.getPacket() instanceof S2EPacketCloseWindow)) {
            return;
        }
        if (!(mc.currentScreen instanceof GuiContainer)) {
            return;
        }
        // The server has thrown the window away regardless; swallowing the packet only stops
        // the client acting on it, which is what keeps the screen readable.
        event.setCanceled(true);
        kept = mc.currentScreen;
        suppressing = true;
    }

    @SubscribeEvent
    public void onWorldLoad(WorldEvent.Load event) {
        release();
    }

    /** Whether a held container is currently on screen. */
    public boolean isKeeping() {
        return suppressing && kept != null && mc.currentScreen == kept;
    }

    @Override
    public String getInfo() {
        return isKeeping() ? "holding" : "";
    }
}
