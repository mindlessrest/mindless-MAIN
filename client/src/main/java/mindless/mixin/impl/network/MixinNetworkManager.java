package mindless.mixin.impl.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import mindless.Mindless;
import mindless.event.DispatchPacketEvent;
import mindless.event.NoEventPacketEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.lag.service.PacketDelayService;
import mindless.runtime.CombatPacketState;
import mindless.runtime.SentPlayerState;
import mindless.utility.PacketUtils;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraftforge.common.MinecraftForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public class MixinNetworkManager {
    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"), cancellable = true)
    public void sendPacket(Packet p_sendPacket_1_, CallbackInfo ci) {
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(p_sendPacket_1_)) {
            service.bindNetworkManager((NetworkManager) (Object) this);
        }
        if (PacketUtils.consumeSendEventSkip(p_sendPacket_1_)) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new NoEventPacketEvent(p_sendPacket_1_));
            if (!CombatPacketState.consumeReplay(p_sendPacket_1_)) {
                CombatPacketState.recordAccepted(p_sendPacket_1_);
            }
            SentPlayerState.record(p_sendPacket_1_);
            return;
        }

        SendPacketEvent sendPacketEvent = new SendPacketEvent(p_sendPacket_1_);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(sendPacketEvent);

        if (!sendPacketEvent.isCanceled() && service != null) service.onSendPacket(sendPacketEvent);
        if (sendPacketEvent.isCanceled()) {
            ci.cancel();
            return;
        }
        CombatPacketState.recordAccepted(p_sendPacket_1_);
        SentPlayerState.record(p_sendPacket_1_);
    }
    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V", at = @At("HEAD"), cancellable = true)
    public void sendPacketWithListeners(Packet p_sendPacket_1_, GenericFutureListener listener, GenericFutureListener[] listeners, CallbackInfo ci) {
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(p_sendPacket_1_)) {
            service.bindNetworkManager((NetworkManager) (Object) this);
        }
        if (PacketUtils.consumeSendEventSkip(p_sendPacket_1_)) {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new NoEventPacketEvent(p_sendPacket_1_));
            if (!CombatPacketState.consumeReplay(p_sendPacket_1_)) {
                CombatPacketState.recordAccepted(p_sendPacket_1_);
            }
            SentPlayerState.record(p_sendPacket_1_);
            return;
        }

        SendPacketEvent sendPacketEvent = new SendPacketEvent(p_sendPacket_1_);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(sendPacketEvent);

        if (!sendPacketEvent.isCanceled() && service != null) service.onSendPacket(sendPacketEvent);
        if (sendPacketEvent.isCanceled()) {
            ci.cancel();
            return;
        }
        CombatPacketState.recordAccepted(p_sendPacket_1_);
        SentPlayerState.record(p_sendPacket_1_);
    }

    @Inject(method = "dispatchPacket", at = @At("HEAD"))
    public void dispatchPacket(Packet inPacket, GenericFutureListener<? extends Future<? super Void>>[] futureListeners, CallbackInfo ci) {
        DispatchPacketEvent dispatchPacketEvent = new DispatchPacketEvent(inPacket);
        MinecraftForge.EVENT_BUS.post(dispatchPacketEvent);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    public void channelInactive(ChannelHandlerContext context, CallbackInfo ci) {
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null) service.onChannelInactive((NetworkManager) (Object) this);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V", at = @At("HEAD"), cancellable = true)
    public void receivePacket(ChannelHandlerContext p_channelRead0_1_, Packet p_channelRead0_2_, CallbackInfo ci) {
        if (PacketUtils.consumeReceiveEventSkip(p_channelRead0_2_)) {
            CombatPacketState.observeHealth(p_channelRead0_2_);
            return;
        }
        PacketDelayService service = Mindless.packetDelayService;
        if (service != null && PacketDelayService.isPlayPacket(p_channelRead0_2_)) {
            service.bindEventLoop(p_channelRead0_1_.executor());
            service.bindNetworkManager((NetworkManager) (Object) this);
            boolean replay = PacketDelayService.consumeReplay(p_channelRead0_2_);
            if (replay && !PacketDelayService.isFinalDeliveryCurrent(p_channelRead0_2_)) {
                PacketDelayService.clearFinalDelivery(p_channelRead0_2_);
                ci.cancel();
                return;
            }
            if (!replay && service.interceptFirstInbound(p_channelRead0_1_, p_channelRead0_2_)) {
                ci.cancel();
                return;
            }
        }
        ReceivePacketEvent receivePacketEvent = new ReceivePacketEvent(p_channelRead0_2_);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(receivePacketEvent);

        if (receivePacketEvent.isCanceled()) {
            PacketDelayService.clearFinalDelivery(p_channelRead0_2_);
            ci.cancel();
        } else CombatPacketState.observeHealth(p_channelRead0_2_);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V", at = @At("RETURN"))
    public void receivePacketCleanup(ChannelHandlerContext context, Packet packet, CallbackInfo ci) {
        PacketDelayService.clearFinalDelivery(packet);
    }
}
