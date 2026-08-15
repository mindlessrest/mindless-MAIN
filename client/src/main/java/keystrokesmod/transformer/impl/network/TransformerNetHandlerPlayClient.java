package keystrokesmod.transformer.impl.network;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.runtime.LunarEventBridge;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S02PacketChat;

@CTransformer(NetHandlerPlayClient.class)
public class TransformerNetHandlerPlayClient {
    @CInline
    @CInject(method = "handleChat(Lnet/minecraft/network/play/server/S02PacketChat;)V",
            target = @CTarget("HEAD"), cancellable = true)
    public void handleChat(S02PacketChat packetIn, InjectionCallback ci) {
        if (LunarEventBridge.postChat(packetIn)) ci.setCancelled(true);
    }

    @CInline
    @CInject(method = "handlePlayerPosLook(Lnet/minecraft/network/play/server/S08PacketPlayerPosLook;)V",
            target = @CTarget("HEAD"))
    public void handlePlayerPosLookPre(S08PacketPlayerPosLook packetIn, InjectionCallback ci) {
        if (ModuleManager.noRotate != null) ModuleManager.noRotate.handlePlayerPosLookPre();
    }

    @CInline
    @CInject(method = "handlePlayerPosLook(Lnet/minecraft/network/play/server/S08PacketPlayerPosLook;)V",
            target = @CTarget("RETURN"))
    public void handlePlayerPosLook(S08PacketPlayerPosLook packetIn, InjectionCallback ci) {
        if (ModuleManager.noRotate != null) ModuleManager.noRotate.handlePlayerPosLook(packetIn);
    }
}
