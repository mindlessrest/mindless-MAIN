package keystrokesmod.mixin.impl.render;

import keystrokesmod.module.impl.render.BodyMaterial;
import net.minecraft.client.renderer.entity.layers.LayerArmorBase;
import net.minecraft.entity.EntityLivingBase;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(LayerArmorBase.class)
public abstract class MixinLayerArmorBaseBodyMaterial {
    @Unique
    private boolean bodyMaterial$armorActive;

    @Inject(method = "doRenderLayer", at = @At("HEAD"))
    private void bodyMaterial$beginArmor(EntityLivingBase entity, float limbSwing,
                                         float limbSwingAmount, float partialTicks,
                                         float ageInTicks, float netHeadYaw,
                                         float headPitch, float scale, CallbackInfo ci) {
        bodyMaterial$armorActive = BodyMaterial.begin(entity, true);
    }

    @Inject(method = "doRenderLayer", at = @At("RETURN"))
    private void bodyMaterial$endArmor(EntityLivingBase entity, float limbSwing,
                                       float limbSwingAmount, float partialTicks,
                                       float ageInTicks, float netHeadYaw,
                                       float headPitch, float scale, CallbackInfo ci) {
        BodyMaterial.end(bodyMaterial$armorActive);
        bodyMaterial$armorActive = false;
    }
}
