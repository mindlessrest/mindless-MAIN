package mindless.mixin.impl.render;

import mindless.runtime.ItemEffectRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderItem.class)
public class MixinRenderItem {

    /** See TransformerRenderItem for why held-item effects hook the model draw. */
    @Inject(method = "renderItemModelTransform", at = @At("HEAD"))
    private void mindless$renderHeldItemEffect(ItemStack stack, IBakedModel model,
                                               ItemCameraTransforms.TransformType transform,
                                               CallbackInfo info) {
        ItemEffectRenderer.renderHeld((RenderItem) (Object) this, stack, model, transform);
    }
}
