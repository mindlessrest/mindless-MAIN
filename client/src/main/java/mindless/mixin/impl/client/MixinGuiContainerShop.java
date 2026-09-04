package mindless.mixin.impl.client;

import mindless.module.ModuleManager;
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
        ShopHelper helper = ModuleManager.shopHelper;
        if (helper == null || !helper.isEnabled()) return;

        GuiContainer self = (GuiContainer) (Object) this;
        int decision = helper.decideClick(self, slot, clickType, clickedButton);
        if (decision == ShopHelper.CLICK_ALLOW) return;

        ci.cancel();
        if (decision != ShopHelper.CLICK_QUICK_MOVE) return;

        Minecraft mc = Minecraft.getMinecraft();
        // Button 2, mode 3 -- a clone click. Mode 1 is a quick move, which is a shift click: the
        // server only treats that as a buy on the category pages, and the stack visibly travels
        // to the cursor first. A clone click reads as a plain click on every shop page and moves
        // nothing client side, so there is no animation to hide.
        mc.playerController.windowClick(self.inventorySlots.windowId, slot.slotNumber,
                2, 3, mc.thePlayer);
    }
}
