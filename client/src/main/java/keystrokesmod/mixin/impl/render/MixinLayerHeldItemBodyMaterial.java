package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.BodyMaterial;
import net.minecraft.client.renderer.entity.layers.LayerHeldItem;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(LayerHeldItem.class)
public abstract class MixinLayerHeldItemBodyMaterial {
    @Unique
    private boolean bodyMaterial$itemActive;

    @Inject(method = "doRenderLayer", at = @At("HEAD"))
    private void bodyMaterial$beginItem(EntityLivingBase entity, float limbSwing,
                                        float limbSwingAmount, float partialTicks,
                                        float ageInTicks, float netHeadYaw,
                                        float headPitch, float scale, CallbackInfo ci) {
        bodyMaterial$itemActive = BodyMaterial.begin(entity, true);
    }

    @Inject(method = "doRenderLayer", at = @At("RETURN"))
    private void bodyMaterial$endItem(EntityLivingBase entity, float limbSwing,
                                      float limbSwingAmount, float partialTicks,
                                      float ageInTicks, float netHeadYaw,
                                      float headPitch, float scale, CallbackInfo ci) {
        BodyMaterial.end(bodyMaterial$itemActive);
        bodyMaterial$itemActive = false;
    }
}
