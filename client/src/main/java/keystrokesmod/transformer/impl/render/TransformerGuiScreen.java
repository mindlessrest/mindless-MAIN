package keystrokesmod.transformer.impl.render;

import keystrokesmod.Raven;
import keystrokesmod.module.ModuleManager;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;

@CTransformer(GuiScreen.class)
public abstract class TransformerGuiScreen {

    @CInline
    @CInject(method = "sendChatMessage(Ljava/lang/String;Z)V",
            target = @CTarget("HEAD"), cancellable = true)
    private void messageSend(String msg, boolean addToChat, InjectionCallback ci) {
        if (msg.startsWith(".") && addToChat && ModuleManager.canExecuteChatCommand()) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.ingameGUI.getChatGUI().addToSentMessages(msg);
            Raven.commandManager.executeCommand(msg);
            ci.setCancelled(true);
        }
    }
}
