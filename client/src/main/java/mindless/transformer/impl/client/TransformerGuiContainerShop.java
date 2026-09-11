package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
import mindless.module.impl.bedwars.InstantShop;
import mindless.module.impl.minigames.ShopHelper;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;

/**
 * The Lunar half of the shop hooks; behaviour is kept identical to MixinGuiContainerShop.
 *
 * The click handling used to live here and in TransformerGuiContainer at once, both injecting the
 * head of handleMouseClick with their own copy of the logic, so a single click could be cancelled
 * and re-sent twice. It lives here only now.
 */
@CTransformer(GuiContainer.class)
public abstract class TransformerGuiContainerShop {

    @CInline
    @CInject(method = "drawSlot", target = @CTarget("HEAD"))
    private void shopHighlight(Slot slot, InjectionCallback ci) {
        ShopHelper helper = ModuleManager.shopHelper;
        if (helper == null || !helper.isEnabled() || slot == null || !slot.getHasStack()) return;
        if (!helper.isShopOpen(Minecraft.getMinecraft().currentScreen)) return;

        int colour = helper.highlightColour(slot.getStack());
        if (colour == 0) return;

        Gui.drawRect(slot.xDisplayPosition, slot.yDisplayPosition,
                slot.xDisplayPosition + 16, slot.yDisplayPosition + 16, colour);
    }

    @CInline
    @CInject(method = "handleMouseClick", target = @CTarget("HEAD"), cancellable = true)
    private void shopClick(Slot slot, int slotId, int clickedButton, int clickType,
                           InjectionCallback ci) {
        notifyResourceDepositManualInput();
        ShopHelper helper = ModuleManager.shopHelper;
        InstantShop instantShop = ModuleManager.instantShop;
        GuiContainer self = (GuiContainer) (Object) this;
        if (instantShop != null && instantShop.isEnabled()
                && helper != null && helper.isEnabled()
                && helper.decideClick(self, slot, clickType, clickedButton) == ShopHelper.CLICK_CANCEL) {
            ci.setCancelled(true);
            return;
        }

        if (instantShop != null && instantShop.isEnabled()
                && instantShop.tryPurchase(self, slot, slotId, clickedButton, clickType)) {
            ci.setCancelled(true);
            return;
        }

        if (helper == null || !helper.isEnabled()) return;

        int decision = helper.decideClick(self, slot, clickType, clickedButton);
        if (decision == ShopHelper.CLICK_ALLOW) return;

        ci.setCancelled(true);
        if (decision != ShopHelper.CLICK_PURCHASE) return;

        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.windowClick(self.inventorySlots.windowId, slotId,
                0, 0, mc.thePlayer);
    }

    @CInline
    private static void notifyResourceDepositManualInput() {
        if (ModuleManager.resourceDeposit != null) {
            ModuleManager.resourceDeposit.onManualInventoryInteraction();
        }
    }
}
