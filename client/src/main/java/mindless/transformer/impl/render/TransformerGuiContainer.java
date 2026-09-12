package mindless.transformer.impl.render;

import mindless.module.ModuleManager;
import mindless.runtime.ItemEffectRenderer;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;

@CTransformer(GuiContainer.class)
public class TransformerGuiContainer {
    @CShadow private int guiLeft;
    @CShadow private int guiTop;

    @CInline
    @CInject(method = "drawScreen", target = @CTarget("RETURN"))
    private void mindless$renderInventoryItemEffects(int mouseX, int mouseY, float partialTicks,
                                                      InjectionCallback callbackInfo) {
        ItemEffectRenderer.renderInventory((GuiContainer) (Object) this, guiLeft, guiTop);
    }

    @CInline
    @CInject(method = "mouseClicked", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$cancelManagedInventoryMouseClick(int mouseX, int mouseY, int mouseButton, InjectionCallback callbackInfo) {
        if (shouldCancelManualInventoryInput()) {
            callbackInfo.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "mouseClickMove", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$cancelManagedInventoryMouseDrag(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick, InjectionCallback callbackInfo) {
        if (shouldCancelManualInventoryInput()) {
            callbackInfo.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "mouseReleased", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$cancelManagedInventoryMouseRelease(int mouseX, int mouseY, int state, InjectionCallback callbackInfo) {
        if (shouldCancelManualInventoryInput()) {
            callbackInfo.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "handleMouseClick", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$cancelManagedInventoryWindowClick(Slot slotIn, int slotId, int clickedButton, int clickType, InjectionCallback callbackInfo) {
        if (shouldCancelManualInventoryInput()) {
            callbackInfo.setCancelled(true);
        }
    }

    private static boolean shouldCancelManualInventoryInput() {
        return ModuleManager.invManager != null && ModuleManager.invManager.shouldCancelManualInventoryInput();
    }
}
