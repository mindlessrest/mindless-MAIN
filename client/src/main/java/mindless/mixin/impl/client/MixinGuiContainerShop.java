package mindless.mixin.impl.client;

import mindless.module.ModuleManager;
import mindless.module.impl.bedwars.InstantShop;
import mindless.module.impl.minigames.ShopHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(GuiContainer.class)
public abstract class MixinGuiContainerShop {

    /**
     * Lays the affordability tint down before the slot's item is drawn.
     *
     * This used to run from GuiScreenEvent.DrawScreenEvent.Post, which is after both the items and
     * the tooltip, so the tint covered the icon it was meant to mark -- and that event is never
     * posted on the Lunar path at all, so there it drew nothing whatsoever. drawSlot exists on both.
     * Slot coordinates are already inside the guiLeft/guiTop translate here, so they are used raw.
     */
    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void mindless$shopHighlight(Slot slot, CallbackInfo ci) {
        ShopHelper helper = ModuleManager.shopHelper;
        if (helper == null || !helper.isEnabled() || slot == null || !slot.getHasStack()) return;
        if (!helper.isShopOpen(Minecraft.getMinecraft().currentScreen)) return;

        int colour = helper.highlightColour(slot.getStack());
        if (colour == 0) return;

        Gui.drawRect(slot.xDisplayPosition, slot.yDisplayPosition,
                slot.xDisplayPosition + 16, slot.yDisplayPosition + 16, colour);
    }

    @Inject(method = "handleMouseClick", at = @At("HEAD"), cancellable = true)
    private void mindless$shopClick(Slot slot, int slotId, int clickedButton, int clickType,
                                    CallbackInfo ci) {
        notifyResourceDepositManualInput();
        ShopHelper helper = ModuleManager.shopHelper;
        InstantShop instantShop = ModuleManager.instantShop;
        GuiContainer self = (GuiContainer) (Object) this;
        if (instantShop != null && instantShop.isEnabled()
                && helper != null && helper.isEnabled()
                && helper.decideClick(self, slot, clickType, clickedButton) == ShopHelper.CLICK_CANCEL) {
            ci.cancel();
            return;
        }

        if (instantShop != null && instantShop.isEnabled()
                && instantShop.tryPurchase(self, slot, slotId, clickedButton, clickType)) {
            ci.cancel();
            return;
        }

        if (helper == null || !helper.isEnabled()) return;

        int decision = helper.decideClick(self, slot, clickType, clickedButton);
        if (decision == ShopHelper.CLICK_ALLOW) return;

        ci.cancel();
        if (decision != ShopHelper.CLICK_PURCHASE) return;

        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.windowClick(self.inventorySlots.windowId, slotId,
                0, 0, mc.thePlayer);
    }

    private static void notifyResourceDepositManualInput() {
        if (ModuleManager.resourceDeposit != null) {
            ModuleManager.resourceDeposit.onManualInventoryInteraction();
        }
    }
}
