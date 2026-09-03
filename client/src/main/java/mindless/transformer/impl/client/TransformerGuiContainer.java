package mindless.transformer.impl.client;

import mindless.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.inventory.GuiContainer;
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
        // Shop handling lives in TransformerGuiContainerShop; having it here as well meant one
        // click went through two independent cancel-and-resend paths.
        if (ModuleManager.invManager != null && ModuleManager.invManager.shouldCancelManualInventoryInput()) {
            callback.setCancelled(true);
        }
    }

    @CInline
    private static void cancelManaged(InjectionCallback callback) {
        if (ModuleManager.invManager != null && ModuleManager.invManager.shouldCancelManualInventoryInput()) {
            callback.setCancelled(true);
        }
    }
}
