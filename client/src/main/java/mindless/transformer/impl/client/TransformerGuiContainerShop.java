package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
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
        ShopHelper helper = ModuleManager.shopHelper;
        if (helper == null || !helper.isEnabled()) return;

        GuiContainer self = (GuiContainer) (Object) this;
        int decision = helper.decideClick(self, slot, clickType, clickedButton);
        if (decision == ShopHelper.CLICK_ALLOW) return;

        ci.setCancelled(true);
        if (decision != ShopHelper.CLICK_QUICK_MOVE) return;

        Minecraft mc = Minecraft.getMinecraft();
        mc.playerController.windowClick(self.inventorySlots.windowId, slot.slotNumber,
                0, 1, mc.thePlayer);
    }
}
