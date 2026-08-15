package keystrokesmod.transformer.impl.render;

import keystrokesmod.runtime.BodyMaterialRuntime;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;

@CTransformer(LayerHeldItem.class)
public class TransformerLayerHeldItem {
    @CInline @CInject(method = "doRenderLayer", target = @CTarget("HEAD"))
    private void begin(EntityLivingBase entity, float a, float b, float c, float d,
                       float e, float f, float g, InjectionCallback callback) {
        BodyMaterialRuntime.begin(entity, true);
    }
    @CInline @CInject(method = "doRenderLayer", target = @CTarget("RETURN"))
    private void end(EntityLivingBase entity, float a, float b, float c, float d,
                     float e, float f, float g, InjectionCallback callback) {
        BodyMaterialRuntime.end();
    }
}
