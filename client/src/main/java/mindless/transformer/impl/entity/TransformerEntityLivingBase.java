package mindless.transformer.impl.entity;

import mindless.event.JumpEvent;
import mindless.event.PreMotionEvent;
import mindless.event.PrePlayerMovementInputEvent;
import mindless.module.impl.client.Settings;
import mindless.runtime.LunarEventBridge;
import mindless.utility.RotationUtils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingEvent;

@CTransformer(EntityLivingBase.class)
public abstract class TransformerEntityLivingBase {

    @CShadow
    protected abstract float getJumpUpwardsMotion();
    @net.lenni0451.classtransform.annotations.injection.COverride
    public void jump() {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        boolean localPlayer = self == net.minecraft.client.Minecraft.getMinecraft().thePlayer;
        JumpEvent event = new JumpEvent(self, getJumpUpwardsMotion(), self.rotationYaw, self.isSprinting());
        if (localPlayer) MinecraftForge.EVENT_BUS.post(event);
        if (event.isCanceled()) return;

        self.motionY = event.getMotionY();
        if (self.isPotionActive(Potion.jump)) {
            self.motionY += (double) ((float) (self.getActivePotionEffect(Potion.jump).getAmplifier() + 1) * 0.1F);
        }
        if (event.applySprint()) {
            float f = event.getYaw() * 0.017453292F;
            self.motionX -= (double) (MathHelper.sin(f) * 0.2F);
            self.motionZ += (double) (MathHelper.cos(f) * 0.2F);
        }
        self.isAirBorne = true;
        LunarEventBridge.onLivingJump(self);
    }

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
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/entity/EntityLivingBase;moveEntityWithHeading(FF)V", optional = true))
    private void onMoveEntityWithHeadingRedirect(EntityLivingBase self, float originalStrafing, float originalForward) {
        if (self instanceof EntityPlayerSP) {
            PrePlayerMovementInputEvent event = new PrePlayerMovementInputEvent(originalForward, originalStrafing);
            MinecraftForge.EVENT_BUS.post(event);
            self.moveEntityWithHeading(event.strafe, event.forward);
        } else {
            self.moveEntityWithHeading(originalStrafing, originalForward);
        }
    }
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
