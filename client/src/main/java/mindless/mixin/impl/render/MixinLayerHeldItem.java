package mindless.mixin.impl.render;

import mindless.module.impl.render.SexyESP;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LayerHeldItem.class)
public class MixinLayerHeldItem {
    @Inject(method = "doRenderLayer(Lnet/minecraft/entity/EntityLivingBase;FFFFFFF)V", at = @At("HEAD"), cancellable = true)
    private void suppressHeldItemOutline(EntityLivingBase entity, float limbSwing, float limbSwingAmount,
                                         float partialTicks, float ageInTicks, float netHeadYaw,
                                         float headPitch, float scale, CallbackInfo ci) {
        if (SexyESP.renderingOutlinePass) {
            ci.cancel();
        }
    }
}
