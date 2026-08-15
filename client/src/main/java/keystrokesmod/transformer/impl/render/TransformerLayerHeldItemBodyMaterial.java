package keystrokesmod.transformer.impl.render;

import keystrokesmod.module.impl.render.BodyMaterial;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;

@CTransformer(LayerHeldItem.class)
public abstract class TransformerLayerHeldItemBodyMaterial {
    private boolean bodyMaterial$itemActive;

    @CInline
    @CInject(method = "doRenderLayer", target = @CTarget("HEAD"))
    private void bodyMaterial$beginItem(EntityLivingBase entity, float limbSwing,
                                        float limbSwingAmount, float partialTicks,
                                        float ageInTicks, float netHeadYaw,
                                        float headPitch, float scale, InjectionCallback ci) {
        bodyMaterial$itemActive = BodyMaterial.begin(entity, true);
    }

    @CInline
    @CInject(method = "doRenderLayer", target = @CTarget("RETURN"))
    private void bodyMaterial$endItem(EntityLivingBase entity, float limbSwing,
                                      float limbSwingAmount, float partialTicks,
                                      float ageInTicks, float netHeadYaw,
                                      float headPitch, float scale, InjectionCallback ci) {
        BodyMaterial.end(bodyMaterial$itemActive);
        bodyMaterial$itemActive = false;
    }
}
