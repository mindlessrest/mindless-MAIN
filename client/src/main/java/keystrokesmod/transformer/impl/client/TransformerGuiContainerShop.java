package keystrokesmod.transformer.impl.client;

import keystrokesmod.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;

@CTransformer(GuiContainer.class)
public abstract class TransformerGuiContainerShop {
    @CShadow
    private Slot theSlot;

    @CInline
    @CInject(method = "handleMouseClick", target = @CTarget("HEAD"), cancellable = true)
    private void instantShopClick(Slot slot, int slotId, int clickedButton, int clickType, InjectionCallback ci) {
        if (ModuleManager.shopHelper == null || !ModuleManager.shopHelper.isEnabled()
                || !ModuleManager.shopHelper.instantBuy.isToggled()) return;
        if (!(((Object) this) instanceof GuiChest)) return;

        GuiChest chest = (GuiChest) (Object) this;
        if (!(chest.inventorySlots instanceof ContainerChest)) return;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        String name = container.getLowerChestInventory().getDisplayName().getUnformattedText();
        if (!name.contains("Shop") && !name.contains("Item Shop") && !name.contains("Upgrades")) return;

        if (slot == null || !slot.getHasStack()) return;

        ci.setCancelled(true);

        Minecraft.getMinecraft().playerController.windowClick(
                chest.inventorySlots.windowId,
                slot.slotNumber,
                clickedButton,
                0,
                Minecraft.getMinecraft().thePlayer
        );
    }
}
