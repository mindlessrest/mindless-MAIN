package mindless.transformer.impl.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import mindless.Mindless;
import mindless.event.DispatchPacketEvent;
import mindless.event.NoEventPacketEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.lag.service.PacketDelayService;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import mindless.runtime.SentPlayerState;
import mindless.runtime.CombatPacketState;
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
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(packet)) {
            service.bindNetworkManager((NetworkManager) (Object) this);
        }
        if (PacketUtils.consumeSendEventSkip(packet)) {
            MinecraftForge.EVENT_BUS.post(new NoEventPacketEvent(packet));
            if (!CombatPacketState.consumeReplay(packet)) {
                CombatPacketState.recordAccepted(packet);
            }
            SentPlayerState.record(packet);
            return;
        }
        SendPacketEvent event = new SendPacketEvent(packet);
        MinecraftForge.EVENT_BUS.post(event);
        if (!event.isCanceled() && service != null) service.onSendPacket(event);
        if (event.isCanceled()) {
            ci.setCancelled(true);
            return;
        }
        CombatPacketState.recordAccepted(packet);
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
    @CInject(method = "sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V", target = @CTarget("HEAD"), cancellable = true)
    public void sendPacketWithListeners(Packet packet, GenericFutureListener listener, GenericFutureListener[] listeners, InjectionCallback ci) {
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(packet)) {
            service.bindNetworkManager((NetworkManager) (Object) this);
        }
        if (PacketUtils.consumeSendEventSkip(packet)) {
            MinecraftForge.EVENT_BUS.post(new NoEventPacketEvent(packet));
            if (!CombatPacketState.consumeReplay(packet)) {
                CombatPacketState.recordAccepted(packet);
            }
            SentPlayerState.record(packet);
            return;
        }
        SendPacketEvent event = new SendPacketEvent(packet);
        MinecraftForge.EVENT_BUS.post(event);
        if (!event.isCanceled() && service != null) service.onSendPacket(event);
        if (event.isCanceled()) {
            ci.setCancelled(true);
            return;
        }
        CombatPacketState.recordAccepted(packet);
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
    @CInject(method = "dispatchPacket", target = @CTarget("HEAD"))
    public void dispatchPacket(Packet packet,
                               GenericFutureListener<? extends Future<? super Void>>[] futureListeners,
                               InjectionCallback ci) {
        MinecraftForge.EVENT_BUS.post(new DispatchPacketEvent(packet));
    }

    @CInline
    @CInject(method = "channelInactive", target = @CTarget("HEAD"))
    public void channelInactive(ChannelHandlerContext ctx, InjectionCallback ci) {
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null) service.onChannelInactive((NetworkManager) (Object) this);
    }

    @CInline
    @CInject(method = "channelRead0", target = @CTarget("HEAD"), cancellable = true)
    public void receivePacket(ChannelHandlerContext ctx, Packet packet, InjectionCallback ci) {
        if (PacketUtils.consumeReceiveEventSkip(packet)) { CombatPacketState.observeHealth(packet); return; }
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(packet)) {
            service.bindEventLoop(ctx.executor());
            service.bindNetworkManager((NetworkManager) (Object) this);
            boolean replay = PacketDelayService.consumeReplay(packet);
            if (replay && !PacketDelayService.isFinalDeliveryCurrent(packet)) {
                PacketDelayService.clearFinalDelivery(packet);
                ci.setCancelled(true);
                return;
            }
            if (!replay && service.interceptFirstInbound(ctx, packet)) {
                ci.setCancelled(true);
                return;
            }
        }
        ReceivePacketEvent event = new ReceivePacketEvent(packet);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            PacketDelayService.clearFinalDelivery(packet);
            ci.setCancelled(true);
        } else CombatPacketState.observeHealth(packet);
    }

    @CInline
    @CInject(method = "channelRead0", target = @CTarget("RETURN"))
    public void receivePacketCleanup(ChannelHandlerContext ctx, Packet packet, InjectionCallback ci) {
        PacketDelayService.clearFinalDelivery(packet);
    }
}
