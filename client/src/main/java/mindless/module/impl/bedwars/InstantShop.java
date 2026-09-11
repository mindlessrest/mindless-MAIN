package mindless.module.impl.bedwars;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.minigames.ShopHelper;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;

public class InstantShop extends Module {
    public InstantShop() {
        super("Instant Shop", "Turns Bed Wars shop clicks into middle-click purchases.", category.bedwars);
    }

    public boolean tryPurchase(GuiContainer gui, Slot slot, int slotId, int clickedButton, int clickType) {
        if (!isEnabled() || gui == null || slot == null || !slot.getHasStack()) return false;
        if (slotId < 0 || (clickedButton != 0 && clickedButton != 2) || clickType != 0) return false;
        if (mc.thePlayer == null || mc.playerController == null) return false;

        ShopHelper shopHelper = ModuleManager.shopHelper;
        if (shopHelper == null || !shopHelper.isShopOpen(gui)) return false;

        mc.playerController.windowClick(gui.inventorySlots.windowId, slotId, 2, 0, mc.thePlayer);
        return true;
    }
}
