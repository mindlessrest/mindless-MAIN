package mindless.transformer.impl.render;

import mindless.Mindless;
import mindless.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;

@CTransformer(GuiChat.class)
public abstract class TransformerGuiChat {
    @CShadow
    protected GuiTextField inputField;

    @CShadow
    private boolean waitingOnAutocomplete;

    @CShadow
    public abstract void onAutocompleteResponse(String[] autocomplete);

    @CInline
    @CInject(method = "keyTyped(CI)V", target = @CTarget("RETURN"))
    private void updateLength(InjectionCallback ci) {
        if (inputField.getText().startsWith(".") && ModuleManager.canExecuteChatCommand()) {
            Mindless.commandManager.autoComplete(inputField.getText());
        } else {
            inputField.setMaxStringLength(100);
        }
    }

    @CInline
    @CInject(method = "sendAutocompleteRequest", target = @CTarget("HEAD"), cancellable = true)
    private void handleClientCommandCompletion(String full, String ignored, InjectionCallback ci) {
        if (Mindless.commandManager.autoComplete(full) && ModuleManager.canExecuteChatCommand()) {
            waitingOnAutocomplete = true;
            String[] latestAutoComplete = Mindless.commandManager.latestAutoComplete;
            if (full.toLowerCase().endsWith(
                    latestAutoComplete[latestAutoComplete.length - 1].toLowerCase())) {
                return;
            }
            this.onAutocompleteResponse(latestAutoComplete);
            ci.setCancelled(true);
        }
    }
}