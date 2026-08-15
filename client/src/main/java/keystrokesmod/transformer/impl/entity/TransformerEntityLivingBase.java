package keystrokesmod.transformer.impl.entity;

import keystrokesmod.event.PrePlayerMovementInputEvent;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

@CTransformer(EntityLivingBase.class)
public abstract class TransformerEntityLivingBase {
    // NOTE: jump() override removed - mixin handles it (can't override protected methods at runtime)

    @CInline
    @CInject(method = "onLivingUpdate", target = @CTarget("HEAD"), cancellable = true)
    private void onLivingUpdateHead(InjectionCallback cir) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        LivingEvent.LivingUpdateEvent event = new LivingEvent.LivingUpdateEvent(self);
        MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) {
            cir.setCancelled(true);
        }
    }

    @CInline
    @CRedirect(method = "onLivingUpdate",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;moveEntityWithHeading(FF)V"))
    private void onMoveEntityWithHeadingRedirect(EntityLivingBase self, float originalStrafing, float originalForward) {
        if (self instanceof EntityPlayerSP) {
            PrePlayerMovementInputEvent event = new PrePlayerMovementInputEvent(originalForward, originalStrafing);
            MinecraftForge.EVENT_BUS.post(event);
            self.moveEntityWithHeading(event.strafe, event.forward);
        } else {
            self.moveEntityWithHeading(originalStrafing, originalForward);
        }
    }
}
