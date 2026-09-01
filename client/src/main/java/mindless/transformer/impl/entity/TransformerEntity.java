package mindless.transformer.impl.entity;

import mindless.event.ClientLookEvent;
import mindless.event.StepHeightEvent;
import mindless.event.StrafeEvent;
import mindless.module.ModuleManager;
import mindless.module.impl.player.SafeWalk;
import mindless.utility.SafeWalkState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.MinecraftForge;

@CTransformer(Entity.class)
public abstract class TransformerEntity {
    @CShadow public double motionX;
    @CShadow public double motionZ;
    @CShadow public float rotationYaw;
    @CShadow public float stepHeight;

    @COverride
    public void moveFlying(float strafe, float forward, float friction) {
        StrafeEvent strafeEvent = new StrafeEvent(strafe, forward, friction, this.rotationYaw);
        if ((Object) this == Minecraft.getMinecraft().thePlayer) {
            MinecraftForge.EVENT_BUS.post(strafeEvent);
        }
        strafe = strafeEvent.getStrafe();
        forward = strafeEvent.getForward();
        friction = strafeEvent.getFriction();
        float yaw = strafeEvent.getYaw();

        float f = (strafe * strafe) + (forward * forward);
        if (f >= 1.0E-4F) {
            f = MathHelper.sqrt_float(f);
            if (f < 1.0F) f = 1.0F;
            f = friction / f;
            strafe *= f;
            forward *= f;
            float f1 = MathHelper.sin(yaw * (float) Math.PI / 180.0F);
            float f2 = MathHelper.cos(yaw * (float) Math.PI / 180.0F);
            this.motionX += strafe * f2 - forward * f1;
            this.motionZ += forward * f2 + strafe * f1;
        }
    }

    @CInline
    @CRedirect(method = "moveEntity",
            target = @CTarget(value = "FIELD", target = "Lnet/minecraft/entity/Entity;stepHeight:F", ordinal = 0, optional = true))
    private float redirectStepHeight(Entity instance) {
        StepHeightEvent stepHeightEvent = new StepHeightEvent(instance, instance.stepHeight);
        MinecraftForge.EVENT_BUS.post(stepHeightEvent);
        return stepHeightEvent.stepHeight;
    }
@CInline
    @CRedirect(method = "moveEntity",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/entity/Entity;isSneaking()Z", optional = true))
    private boolean redirectSafeWalkSneak(Entity instance) {
        return SafeWalkState.shouldSafeWalk(instance);
    }
}
