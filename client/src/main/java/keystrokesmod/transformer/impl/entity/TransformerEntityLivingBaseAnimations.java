package keystrokesmod.transformer.impl.entity;

import keystrokesmod.module.impl.render.Animations;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.entity.EntityLivingBase;

@CTransformer(EntityLivingBase.class)
public abstract class TransformerEntityLivingBaseAnimations {
    @CInline
    @CInject(method = "getArmSwingAnimationEnd", target = @CTarget("HEAD"), cancellable = true)
    private void useConfiguredSwingDuration(InjectionCallback ci) {
        if (!Animations.enabled) {
            return;
        }

        int percentage = Math.max(0, Math.min(Animations.swingSpeed, 100));
        ci.setReturnValue((int) (6.0D + percentage / 100.0D * 14.0D));
    }
}
