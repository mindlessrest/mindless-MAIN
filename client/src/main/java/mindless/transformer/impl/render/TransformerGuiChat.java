package mindless.transformer.impl.render;

import mindless.Mindless;
import mindless.module.ModuleManager;
import mindless.runtime.GuiNewChatState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.Minecraft;

@CTransformer(GuiChat.class)
public abstract class TransformerGuiChat {
    @CShadow
    protected GuiTextField inputField;

    @CShadow
    private boolean waitingOnAutocomplete;

    @CShadow
    public abstract void onAutocompleteResponse(String[] autocomplete);

    @CInline
    @CInject(method = "drawScreen", target = @CTarget("HEAD"))
    private void mindless$drawSingleInputSurface(int mouseX, int mouseY, float partialTicks,
                                                  InjectionCallback ci) {
        inputField.setEnableBackgroundDrawing(false);
        if (!GuiNewChatState.inputSurfaceDrawn()) {
            if (Minecraft.getMinecraft().currentScreen == null) return;
            int screenWidth = Minecraft.getMinecraft().currentScreen.width;
            int screenHeight = Minecraft.getMinecraft().currentScreen.height;
            int left = 3;
            int top = screenHeight - 15;
            int right = screenWidth - 3;
            int bottom = screenHeight - 2;
            GuiNewChatState.drawSurface(left, top, right - left, bottom - top);
        }
    }

    @CInline
    @CRedirect(method = "drawScreen",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/Gui;drawRect(IIIII)V",
                    optional = true))
    private void mindless$removeVanillaInputBackground(int left, int top, int right, int bottom,
                                                        int color) {
        // The custom surface above is the only input background.
    }

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
