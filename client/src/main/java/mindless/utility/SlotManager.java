package mindless.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C09PacketHeldItemChange;

/** Owns temporary hotbar swaps and optional server-side slot spoofing. */
public final class SlotManager {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static int originalSlot = -1;
    private static ItemStack visualStack;
    private static boolean serverSwapActive;
    private static int serverSlot = -1;

    private SlotManager() {}

    public static void swap(int slot, boolean serverSwap) {
        if (mc.thePlayer == null || slot < 0 || slot > 8) return;
        if (originalSlot == -1) {
            originalSlot = mc.thePlayer.inventory.currentItem;
            ItemStack current = mc.thePlayer.inventory.getCurrentItem();
            visualStack = current == null ? null : current.copy();
        }

        serverSwapActive = serverSwap;
        mc.thePlayer.inventory.currentItem = slot;
        if (serverSwap && serverSlot != slot) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
            serverSlot = slot;
        }
    }

    public static void swapBack() {
        if (mc.thePlayer == null || originalSlot < 0) return;
        int restore = originalSlot;
        if (serverSwapActive && serverSlot != restore) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(restore));
            serverSlot = restore;
        }
        mc.thePlayer.inventory.currentItem = restore;
        originalSlot = -1;
        visualStack = null;
        serverSwapActive = false;
    }

    public static ItemStack getVisualStack() {
        return visualStack;
    }

    public static boolean isActive() {
        return originalSlot >= 0;
    }

    public static boolean isServerSwapActive() {
        return serverSwapActive;
    }

    public static void observeServerSlot(int slot) {
        if (slot >= 0 && slot < 9) serverSlot = slot;
    }
}
