package mindless.mixin.impl.network;

import com.google.common.util.concurrent.ListenableFuture;
import mindless.lag.service.PacketDelayService;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketThreadUtil;
import net.minecraft.network.ThreadQuickExitException;
import net.minecraft.util.IThreadListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketThreadUtil.class)
public class MixinPacketThreadUtil {
    @Inject(method = "checkThreadAndEnqueue", at = @At("HEAD"))
    public static void checkFinalDelivery(Packet packet, INetHandler handler, IThreadListener listener, CallbackInfo ci) {
        if (!PacketDelayService.checkFinalDelivery(packet, listener.isCallingFromMinecraftThread())) {
            throw ThreadQuickExitException.INSTANCE;
        }
    }

    @Redirect(method = "checkThreadAndEnqueue",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/IThreadListener;addScheduledTask(Ljava/lang/Runnable;)Lcom/google/common/util/concurrent/ListenableFuture;"))
    private static ListenableFuture<Object> guardScheduledTask(IThreadListener listener, Runnable task) {
        return listener.addScheduledTask(PacketDelayService.guardFinalDelivery(task));
    }
}
