package mindless.lag.service;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import mindless.lag.api.DelayedEnvelope;
import mindless.lag.api.SessionEpoch;
import net.minecraft.network.Packet;

public final class PacketDelayChannelHandler extends ChannelInboundHandlerAdapter {
    private final PacketDelayService service;
    private SessionEpoch sessionEpoch;

    public PacketDelayChannelHandler(PacketDelayService service, SessionEpoch sessionEpoch) {
        this.service = service;
        this.sessionEpoch = sessionEpoch;
    }

    @Override
    public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
        if (!(message instanceof Packet)) {
            context.fireChannelRead(message);
            return;
        }
        Packet<?> packet = (Packet<?>) message;
        SessionEpoch packetEpoch = sessionEpoch;
        boolean boundary = PacketDelayService.isSessionBoundary(packet);
        SessionEpoch deliveryEpoch = boundary ? packetEpoch.nextWorld() : packetEpoch;
        DelayedEnvelope.DeliveryRoute route = delivered -> {
            PacketDelayService.markReplay(service, delivered, deliveryEpoch);
            context.fireChannelRead(delivered);
        };
        boolean intercepted = service.handleInbound(packet, packetEpoch, route);
        if (boundary && !intercepted) adoptEpoch(packetEpoch, deliveryEpoch);
    }

    void adoptEpoch(SessionEpoch previousEpoch, SessionEpoch nextEpoch) {
        if (sessionEpoch.isSameSession(previousEpoch)
                && service.getCurrentEpoch().isSameSession(nextEpoch)) {
            sessionEpoch = nextEpoch;
        }
    }
}
