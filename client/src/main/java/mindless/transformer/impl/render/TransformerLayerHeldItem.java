package mindless.transformer.impl.render;

import mindless.module.impl.render.SexyESP;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;

@CTransformer(LayerHeldItem.class)
public class TransformerLayerHeldItem {
    @CInline
    @CInject(method = "doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V", target = @CTarget("HEAD"), cancellable = true)
    private void suppressHeldItemOutline(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                         float partialTicks, float ageInTicks, float netHeadYaw,
                                         float headPitch, float scale, InjectionCallback ci) {
        if (SexyESP.renderingOutlinePass) {
            ci.setCancelled(true);
        }
    }
}
