package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.Slot;

@CTransformer(GuiContainer.class)
public class TransformerGuiContainer {
    @CInline @CInject(method = "mouseClicked", target = @CTarget("HEAD"), cancellable = true)
    private void click(int x, int y, int button, InjectionCallback callback) { cancelManaged(callback); }
    @CInline @CInject(method = "mouseClickMove", target = @CTarget("HEAD"), cancellable = true)
    private void drag(int x, int y, int button, long time, InjectionCallback callback) { cancelManaged(callback); }
    @CInline @CInject(method = "mouseReleased", target = @CTarget("HEAD"), cancellable = true)
    private void release(int x, int y, int state, InjectionCallback callback) { cancelManaged(callback); }

    @CInline
    @CInject(method = "handleMouseClick", target = @CTarget("HEAD"), cancellable = true)
    private void windowClick(Slot slot, int slotId, int button, int clickType, InjectionCallback callback) {
        if (ModuleManager.invManager != null && ModuleManager.invManager.shouldCancelManualInventoryInput()) {
            callback.setCancelled(true);
            return;
        }
        if (ModuleManager.shopHelper == null || !ModuleManager.shopHelper.isEnabled()
                || !ModuleManager.shopHelper.instantBuy.isToggled() || !((Object) this instanceof GuiChest)) return;
        GuiChest chest = (GuiChest) (Object) this;
        if (!(chest.inventorySlots instanceof ContainerChest) || slot == null || !slot.getHasStack()) return;
        String name = ((ContainerChest) chest.inventorySlots).getLowerChestInventory().getDisplayName().getUnformattedText();
        if (!name.contains("Shop") && !name.contains("Item Shop") && !name.contains("Upgrades")) return;
        callback.setCancelled(true);
        Minecraft.getMinecraft().playerController.windowClick(chest.inventorySlots.windowId,
                slot.slotNumber, button, 0, Minecraft.getMinecraft().thePlayer);
    }

    @CInline
    private static void cancelManaged(InjectionCallback callback) {
        if (ModuleManager.invManager != null && ModuleManager.invManager.shouldCancelManualInventoryInput()) {
            callback.setCancelled(true);
        }
    }
}
