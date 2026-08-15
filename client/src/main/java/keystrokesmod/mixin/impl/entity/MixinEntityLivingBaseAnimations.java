package keystrokesmod.mixin.impl.entity;

import keystrokesmod.module.impl.render.Animations;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = EntityLivingBase.class, priority = 999)
public abstract class MixinEntityLivingBaseAnimations {
    @Inject(method = "getArmSwingAnimationEnd", at = @At("HEAD"), cancellable = true)
    private void useConfiguredSwingDuration(CallbackInfoReturnable<Integer> cir) {
        if (!Animations.enabled) {
            return;
        }

        int percentage = Math.max(0, Math.min(Animations.swingSpeed, 100));
        cir.setReturnValue((int) (6.0D + percentage / 100.0D * 14.0D));
    }
}
