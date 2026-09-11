package mindless.mixin.impl.render;

import mindless.Mindless;
import mindless.event.KeyPressEvent;
import mindless.module.ModuleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.MinecraftForge;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {
    @Shadow
    public Minecraft mc;

    @Shadow
    public int width;

    @Shadow
    public int height;

    @Inject(method = "sendChatMessage(Ljava/lang/String;Z)V", at = @At("HEAD"), cancellable = true)
    private void messageSend(String msg, boolean addToChat, CallbackInfo callbackInfo) {
        if (addToChat && Mindless.commandManager != null && Mindless.commandManager.handleChatMessage(msg)) {
            this.mc.ingameGUI.getChatGUI().addToSentMessages(msg);
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleKeyboardInput", at = @At("HEAD"), cancellable = true)
    private void injectHandleKeyboardInput(CallbackInfo callbackInfo) {
        KeyPressEvent event = new KeyPressEvent(Keyboard.getEventCharacter(), Keyboard.getEventKey());
        MinecraftForge.EVENT_BUS.post(event);

        if (event.isCanceled()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "handleMouseInput", at = @At("HEAD"))
    private void mindless$resourceDepositMouseWheel(CallbackInfo callbackInfo) {
        if (ModuleManager.resourceDeposit != null) {
            ModuleManager.resourceDeposit.onMouseWheel(Mouse.getEventDWheel());
        }
    }
}
