package mindless.transformer.impl.render;

import mindless.module.impl.render.BodyMaterial;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;

@CTransformer(LayerArmorBase.class)
public abstract class TransformerLayerArmorBaseBodyMaterial {
    private boolean bodyMaterial$armorActive;

    @CInline
    @CInject(method = "doRenderLayer", target = @CTarget("HEAD"))
    private void bodyMaterial$beginArmor(EntityLivingBase entity, float limbSwing,
                                         float limbSwingAmount, float partialTicks,
                                         float ageInTicks, float netHeadYaw,
                                         float headPitch, float scale, InjectionCallback ci) {
        bodyMaterial$armorActive = BodyMaterial.begin(entity, true);
    }

    @CInline
    @CInject(method = "doRenderLayer", target = @CTarget("RETURN"))
    private void bodyMaterial$endArmor(EntityLivingBase entity, float limbSwing,
                                       float limbSwingAmount, float partialTicks,
                                       float ageInTicks, float netHeadYaw,
                                       float headPitch, float scale, InjectionCallback ci) {
        BodyMaterial.end(bodyMaterial$armorActive);
        bodyMaterial$armorActive = false;
    }
}
