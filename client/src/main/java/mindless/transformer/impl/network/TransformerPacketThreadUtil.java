package mindless.transformer.impl.network;

import com.google.common.util.concurrent.ListenableFuture;
import mindless.lag.service.PacketDelayService;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.ThreadQuickExitException;
import net.minecraft.util.IThreadListener;

@CTransformer(PacketThreadUtil.class)
public class TransformerPacketThreadUtil {
    @CInline
    @CInject(method = "checkThreadAndEnqueue", target = @CTarget("HEAD"))
    public static void checkFinalDelivery(Packet packet, INetHandler handler, IThreadListener listener, InjectionCallback ci) {
        if (!PacketDelayService.checkFinalDelivery(packet, listener.isCallingFromMinecraftThread())) {
            throw ThreadQuickExitException.INSTANCE;
        }
    }

    @CInline
    @CRedirect(method = "checkThreadAndEnqueue",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/util/IThreadListener;addScheduledTask(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;"))
    public static ListenableFuture<Object> guardScheduledTask(IThreadListener listener, Runnable task) {
        return listener.addScheduledTask(PacketDelayService.guardFinalDelivery(task));
    }
}
