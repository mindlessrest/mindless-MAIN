package mindless.module.impl.player;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;

/**
 * Shift-click whatever the mouse is over, while the mouse is held.
 *
 * Emptying a chest by hand is shift-click, move, shift-click, move. This turns it into holding
 * shift and dragging: every slot the cursor passes over is taken at a fixed rate. It is only
 * active while both buttons are actually held, so it never fires on an ordinary click.
 *
 * Nothing happens while an item is on the cursor, because a shift-click then would put the held
 * stack somewhere unintended rather than taking the slot underneath.
 */
public class InvClicker extends Module {
    private final SliderSetting cps;
    private final ButtonSetting everyTick;
    private final ButtonSetting requireShift;

    private static Field hoveredSlotField;
    private long nextClickAt;

    public InvClicker() {
        super("Inv Clicker", "Shift-click slots by dragging across them.", category.player, 0);
        this.registerSetting(cps = new SliderSetting("CPS", 12.0, 1.0, 30.0, 1.0));
        this.registerSetting(everyTick = new ButtonSetting("Every tick", false));
        this.registerSetting(requireShift = new ButtonSetting("Require shift", true));
    }

    @Override
    public void guiUpdate() {
        if (cps != null) {
            cps.setVisible(everyTick == null || !everyTick.isToggled(), this);
        }
    }

    @Override
    public void onDisable() {
        nextClickAt = 0L;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck() || !(mc.currentScreen instanceof GuiContainer)) {
            return;
        }
        if (!Mouse.isButtonDown(0)) {
            return;
        }
        if (ModuleManager.resourceDeposit != null
                && ModuleManager.resourceDeposit.shouldYieldChestAutomation()) {
            return;
        }
        if (requireShift.isToggled()
                && !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            return;
        }
        // An item on the cursor means a shift-click would place it, not take the slot under it.
        if (mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!everyTick.isToggled()) {
            if (now < nextClickAt) {
                return;
            }
            nextClickAt = now + Math.round(1000.0 / Math.max(1.0, cps.getInput()));
        }

        Slot slot = hoveredSlot((GuiContainer) mc.currentScreen);
        if (slot == null || !slot.getHasStack()) {
            return;
        }
        // Mode 1 is quick-move, the same path a real shift-click takes.
        mc.playerController.windowClick(mc.thePlayer.openContainer.windowId,
                slot.slotNumber, 0, 1, mc.thePlayer);
    }

    /**
     * The slot under the cursor.
     *
     * GuiContainer keeps it in a private field whose name depends on whether the game is running
     * deobfuscated, so both spellings are tried once and the result cached.
     */
    private static Slot hoveredSlot(GuiContainer gui) {
        if (hoveredSlotField == null) {
            for (String name : new String[]{"theSlot", "field_147006_u"}) {
                try {
                    hoveredSlotField = GuiContainer.class.getDeclaredField(name);
                    hoveredSlotField.setAccessible(true);
                    break;
                }
                catch (NoSuchFieldException ignored) {
                    hoveredSlotField = null;
                }
            }
        }
        if (hoveredSlotField == null || gui == null) {
            return null;
        }
        try {
            Object value = hoveredSlotField.get(gui);
            return value instanceof Slot ? (Slot) value : null;
        }
        catch (Exception unreadable) {
            return null;
        }
    }
}
