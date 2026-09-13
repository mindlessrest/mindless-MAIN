package mindless.transformer.impl.render;

import mindless.runtime.ItemEffectRenderer;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderItem;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.item.ItemStack;

@CTransformer(RenderItem.class)
public abstract class TransformerRenderItem {

    /**
     * Held-item effects, hooked where every held item's model is drawn.
     *
     * The hook used to sit on ItemRenderer.renderItem. Swords reach that through Mindless's own
     * sword renderer, but on Lunar every other first-person item goes through Lunar's wrapped
     * first-person pass, which draws the model without passing through that method -- so a sword
     * glowed in first person and a pickaxe did not, while both glowed in third person. Every one of
     * those paths ends here. HEAD, so the silhouette is taken inside the exact state the item is
     * about to be drawn with, and the glow lands under the item rather than over it.
     */
    @CInline
    @CInject(method = "renderItemModelTransform(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/resources/model/IBakedModel;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
            target = @CTarget("HEAD"))
    private void mindless$renderHeldItemEffect(ItemStack stack, IBakedModel model,
                                               ItemCameraTransforms.TransformType transform,
                                               InjectionCallback ci) {
        ItemEffectRenderer.renderHeld((RenderItem) (Object) this, stack, model, transform);
    }
}
