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

    /**
     * Jump, reimplemented so the sprint boost can be aimed somewhere other than where the head is
     * pointing.
     *
     * <p>Vanilla adds the boost along {@code rotationYaw}. That is fine until something is holding
     * that yaw somewhere the player is not walking -- an aura on a client-side rotation mode is the
     * usual case -- and then a sprint jump accelerates toward the target rather than along the
     * direction of travel, and the server sees movement that does not follow from the input. It
     * flags, and it flags only on jumps, because ground movement is already corrected: StrafeEvent
     * is posted from TransformerEntity, and RotationHelper substitutes the server yaw there.
     *
     * <p>The matching JumpEvent was posted only from MixinEntityLivingBase, and the mixin tree is
     * the one Forge loads -- this client runs the transformers. So RotationHelper's jump correction
     * had never once run here, and neither had InvMove's jump block or Jump45. The note that used
     * to stand in this place said the mixin handled it because a protected method cannot be
     * overridden at runtime; that is true of overriding, but an injection replaces the body of the
     * method that is already there and needs no new one.
     *
     * <p>Posted for every living entity, as the mixin did: the listeners that care filter on the
     * local player themselves.
     */
    @CInline
    @CInject(method = "jump", target = @CTarget("HEAD"), cancellable = true)
    private void injectJump(InjectionCallback ci) {
        EntityLivingBase self = (EntityLivingBase) (Object) this;
        JumpEvent event = new JumpEvent(self, getJumpUpwardsMotion(), self.rotationYaw, self.isSprinting());
        MinecraftForge.EVENT_BUS.post(event);
        // Taken over whatever the outcome, so a cancelled jump is a jump that does not happen.
        ci.setCancelled(true);
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
        // Not ForgeHooks.onLivingJump: its static initialiser touches Block.setHarvestLevel, which
        // Lunar's bake does not have. The bridge posts the same event without that.
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
