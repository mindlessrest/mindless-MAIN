package keystrokesmod.transformer.impl.entity;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerMovementInputEvent;
import keystrokesmod.module.impl.client.Settings;
import keystrokesmod.utility.RotationUtils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
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

    /**
     * Lunar-side body rotation. MixinEntityLivingBase#injectUpdateDistance does
     * this on the Forge path: without it RotationUtils.setRenderYaw only moves
     * rotationYawHead, so during a silent rotation (Scaffold, KillAura, ...) the
     * head locks to the spoofed yaw while renderYawOffset keeps tracking real
     * movement input — the body and head visibly come apart.
     *
     * Reimplements vanilla updateDistance and returns from HEAD, substituting
     * RotationUtils.renderYaw for the player's real yaw while a render yaw is
     * active and "Rotate body" is on.
     */
    @CInline
    @CInject(method = "updateDistance", target = @CTarget("HEAD"), cancellable = true)
    private void injectUpdateDistance(float p_110146_1_, float p_110146_2_, InjectionCallback cir) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        float rotationYaw = self.rotationYaw;
        if (Settings.fullBody != null && Settings.rotateBody != null
                && !Settings.fullBody.isToggled() && Settings.rotateBody.isToggled()
                && self instanceof EntityPlayerSP && PreMotionEvent.setRenderYaw()) {
            if (self.swingProgress > 0F) {
                p_110146_1_ = RotationUtils.renderYaw;
            }
            rotationYaw = RotationUtils.renderYaw;
            self.rotationYawHead = RotationUtils.renderYaw;
        }

        float f = MathHelper.wrapAngleTo180_float(p_110146_1_ - self.renderYawOffset);
        self.renderYawOffset += f * 0.3F;
        float f1 = MathHelper.wrapAngleTo180_float(rotationYaw - self.renderYawOffset);
        boolean flag = f1 < -90.0F || f1 >= 90.0F;

        if (f1 < -75.0F) {
            f1 = -75.0F;
        }

        if (f1 >= 75.0F) {
            f1 = 75.0F;
        }

        self.renderYawOffset = rotationYaw - f1;

        if (f1 * f1 > 2500.0F) {
            self.renderYawOffset += f1 * 0.2F;
        }

        if (flag) {
            p_110146_2_ *= -1.0F;
        }

        cir.setReturnValue(p_110146_2_);
    }
}
