package mindless.transformer.impl.render;

import mindless.Raven;
import mindless.module.ModuleManager;
import mindless.runtime.GuiNewChatState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.ScaledResolution;

@CTransformer(GuiChat.class)
public abstract class TransformerGuiChat {
    @CShadow
    protected GuiTextField inputField;

    @CShadow
    private boolean waitingOnAutocomplete;

    @CShadow
    public abstract void onAutocompleteResponse(String[] autocomplete);

    @CShadow
    public int width;

    @CShadow
    public int height;

    @CInline
    @CInject(method = "drawScreen", target = @CTarget("HEAD"))
    private void raven$beforeDraw(int mouseX, int mouseY, float pt, InjectionCallback ci) {
        inputField.setEnableBackgroundDrawing(false);
        GuiNewChatState.drawSurface(3, height - 15, width - 6, 13);
    }

/*    @CRedirect(method = "drawScreen", target = @CTarget(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Gui;drawRect(IIIII)V"))
    private void raven$removeVanillaBar(int left, int top, int right, int bottom, int color) {
    }*/

    @CInline
    @CInject(method = "keyTyped(CI)V", target = @CTarget("RETURN"))
    private void updateLength(InjectionCallback ci) {
        if (inputField.getText().startsWith(".") && ModuleManager.canExecuteChatCommand()) {
            Raven.commandManager.autoComplete(inputField.getText());
        } else {
            inputField.setMaxStringLength(100);
        }
    }

    @CInline
    @CInject(method = "sendAutocompleteRequest", target = @CTarget("HEAD"), cancellable = true)
    private void handleClientCommandCompletion(String full, String ignored, InjectionCallback ci) {
        if (Raven.commandManager.autoComplete(full) && ModuleManager.canExecuteChatCommand()) {
            waitingOnAutocomplete = true;
            String[] latestAutoComplete = Raven.commandManager.latestAutoComplete;
            if (full.toLowerCase().endsWith(
                    latestAutoComplete[latestAutoComplete.length - 1].toLowerCase())) {
                return;
            }
            this.onAutocompleteResponse(latestAutoComplete);
            ci.setCancelled(true);
        }
    }
}
