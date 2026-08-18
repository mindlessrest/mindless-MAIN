package mindless.transformer.impl.client;

import mindless.module.impl.render.MobESP;
import mindless.module.impl.render.PlayerESP;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.item.ItemStack;

@CTransformer(ItemStack.class)
public abstract class TransformerItemStack {
    @CInline
    @CInject(method = "hasEffect", target = @CTarget("HEAD"), cancellable = true)
    private void suppressGlintDuringOutlinePass(InjectionCallback ci) {
        if (PlayerESP.renderingOutlinePass || MobESP.renderingOutlinePass) {
            ci.setReturnValue(false);
        }
    }
}
