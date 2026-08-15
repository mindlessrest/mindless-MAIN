package keystrokesmod.mixin.impl.client;

import keystrokesmod.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(GuiContainer.class)
public abstract class MixinGuiContainerShop {

    @Shadow
    private Slot theSlot;

    @Inject(method = "handleMouseClick", at = @At("HEAD"), cancellable = true)
    private void shopHelperClick(Slot slot, int slotId, int clickedButton, int clickType, CallbackInfo ci) {
        if (ModuleManager.shopHelper == null || !ModuleManager.shopHelper.isEnabled()) return;
        if (!(((Object) this) instanceof GuiChest)) return;

        GuiChest chest = (GuiChest) (Object) this;
        if (!(chest.inventorySlots instanceof ContainerChest)) return;
        ContainerChest container = (ContainerChest) chest.inventorySlots;
        String name = container.getLowerChestInventory().getDisplayName().getUnformattedText();
        if (!name.contains("Shop") && !name.contains("Item Shop") && !name.contains("Upgrades")) return;

        if (slot == null || !slot.getHasStack()) return;

        // Delegate to ShopHelper module for decision
        int result = ModuleManager.shopHelper.onShopClick(slot, clickedButton, clickType);

        if (result == 1) {
            // Cancel (prevent duplicate)
            ci.cancel();
            return;
        }

        if (result == 2) {
            // Replace with middle click (quick buy)
            ci.cancel();
            Minecraft.getMinecraft().playerController.windowClick(
                    chest.inventorySlots.windowId,
                    slot.slotNumber,
                    2, // middle click button
                    0, // normal click type
                    Minecraft.getMinecraft().thePlayer
            );
            return;
        }

        // Instant buy: force click type to 0 (normal click, bypass shift-click etc.)
        if (ModuleManager.shopHelper.instantBuy.isToggled()) {
            ci.cancel();
            Minecraft.getMinecraft().playerController.windowClick(
                    chest.inventorySlots.windowId,
                    slot.slotNumber,
                    clickedButton,
                    0,
                    Minecraft.getMinecraft().thePlayer
            );
        }
    }
}
