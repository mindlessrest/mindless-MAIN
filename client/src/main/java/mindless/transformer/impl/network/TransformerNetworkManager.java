package mindless.transformer.impl.network;

import io.netty.channel.ChannelHandlerContext;
import mindless.event.NoEventPacketEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import mindless.runtime.SentPlayerState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.item.ItemBlock;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraftforge.common.MinecraftForge;

@CTransformer(NetworkManager.class)
public class TransformerNetworkManager {
    @CInline
    @CInject(method = "sendPacket(Lnet/minecraft/network/Packet;)V",
            target = @CTarget("HEAD"), cancellable = true)
    public void sendPacket(Packet packet, InjectionCallback ci) {
        if (PacketUtils.consumeSendEventSkip(packet)) {
            MinecraftForge.EVENT_BUS.post(new NoEventPacketEvent(packet));
            SentPlayerState.record(packet);
            return;
        }
        SendPacketEvent event = new SendPacketEvent(packet);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            ci.setCancelled(true);
            return;
        }
        SentPlayerState.record(packet);
        if (packet instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement placement = (C08PacketPlayerBlockPlacement) packet;
            if (placement.getPlacedBlockDirection() != 255
                    && placement.getStack() != null
                    && placement.getStack().getItem() instanceof ItemBlock) {
                Utils.markCommittedRealBlockPlacementPacketSend();
            }
        }
    }

    @CInline
    @CInject(method = "channelRead0", target = @CTarget("HEAD"), cancellable = true)
    public void receivePacket(ChannelHandlerContext ctx, Packet packet, InjectionCallback ci) {
        if (PacketUtils.consumeReceiveEventSkip(packet)) return;
        ReceivePacketEvent event = new ReceivePacketEvent(packet);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) ci.setCancelled(true);
    }
}
