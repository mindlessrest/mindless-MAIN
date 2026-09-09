package mindless.transformer.impl.render;

import mindless.module.impl.render.Animations;
import mindless.module.impl.render.Slow;
import mindless.runtime.ItemAnimationRuntime;
import mindless.runtime.ItemRendererState;
import mindless.utility.Utils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;
@CTransformer(ItemRenderer.class)
public abstract class TransformerItemRenderer {
    @CShadow private ItemStack itemToRender;
    @CShadow private float equippedProgress;
    @CShadow private float prevEquippedProgress;

    @CInline
    @CInject(method = "renderItemInFirstPerson", target = @CTarget("HEAD"), cancellable = true)
    private void modifyRenderItemPre(float partial, InjectionCallback ci) {
        ItemStack original = this.itemToRender;
        ItemRendererState.rememberOriginalRenderedItem(original);
        ItemStack forcedSword = ItemRendererState.getForcedSwordRenderItem();
        if (forcedSword != null) {
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            this.itemToRender = forcedSword;
        } else {
            this.itemToRender = Utils.getSpoofedItem(original);
        }
        if (ItemAnimationRuntime.renderSwordOverride((ItemRenderer)(Object)this, this.itemToRender, partial)) {
            ci.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "renderItemInFirstPerson", target = @CTarget("RETURN"))
    private void modifyRenderItemPost(float partial, InjectionCallback ci) {
        this.itemToRender = ItemRendererState.takeOriginalRenderedItem();
    }

    @CInline
    @CRedirect(method = "renderItemInFirstPerson",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getItemInUseCount()I",
                    optional = true))
    private int forceSwordUseCount(AbstractClientPlayer player) {
        return ItemRendererState.shouldRenderForcedSwordBlock(this.itemToRender)
                ? 71999 : player.getItemInUseCount();
    }

    @CInline
    @CRedirect(method = "renderItemInFirstPerson",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/item/ItemStack;getItemUseAction()Lnet/minecraft/item/EnumAction;",
                    optional = true))
    private EnumAction forceSwordUseAction(ItemStack stack) {
        return ItemRendererState.shouldRenderForcedSwordBlock(stack)
                ? EnumAction.BLOCK : stack.getItemUseAction();
    }

    @CInline
    @CRedirect(method = "renderItemInFirstPerson",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getSwingProgress(F)F",
                    optional = true))
    private float useVisualSwingProgress(AbstractClientPlayer player, float partialTicks) {
        float vanilla = player.getSwingProgress(partialTicks);
        return Slow.getVisualSwingProgress(player, vanilla);
    }

    @CInline
    @CInject(method = "renderItemInFirstPerson",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;doBlockTransformations()V",
                    shift = CTarget.Shift.BEFORE,
                    optional = true))
    private void applyBlockingAnimation(float partialTicks, InjectionCallback ci) {
        if (Animations.enabled && ItemRendererState.isRenderedSword(this.itemToRender)) {
            ItemRendererState.applyAnimationTransform(this.equippedProgress, this.prevEquippedProgress, partialTicks);
        }
    }

    @CInline
    @CInject(method = "renderItemInFirstPerson",
            target = @CTarget(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItem(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;)V",
                    shift = CTarget.Shift.BEFORE,
                    optional = true))
    private void applyScale(float partialTicks, InjectionCallback ci) {
        if (!Animations.enabled || !ItemRendererState.isRenderedSword(this.itemToRender)) return;
        double s = Animations.scale / 100.0 * (1.0 + Animations.itemSize);
        s = Math.max(0.1, Math.min(s, 2.0));
        GL11.glScaled(s, s, s);
    }

    @CInline
    @CInject(method = "updateEquippedItem", target = @CTarget("HEAD"), cancellable = true)
    private void onUpdateEquippedItem(InjectionCallback ci) {
        if (ItemRendererState.isForceSwordBlockAnimationActive()
                || ItemRendererState.isHeldItemSpoofActive()) {
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
            return;
        }
        if (ItemRendererState.isCancelUpdate()) {
            ItemRendererState.setCancelUpdate(false);
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "resetEquippedProgress", target = @CTarget("HEAD"), cancellable = true)
    private void injectResetEquippedProgress(InjectionCallback ci) {
        if (ItemRendererState.isForceSwordBlockAnimationActive()
                || ItemRendererState.isHeldItemSpoofActive()) {
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
            return;
        }
        if (ItemRendererState.isCancelReset()) {
            ItemRendererState.setCancelReset(false);
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "resetEquippedProgress2", target = @CTarget("HEAD"), cancellable = true)
    private void injectResetEquippedProgress2(InjectionCallback ci) {
        if (ItemRendererState.isForceSwordBlockAnimationActive()
                || ItemRendererState.isHeldItemSpoofActive()) {
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
            return;
        }
        if (ItemRendererState.isCancelReset()) {
            ItemRendererState.setCancelReset(false);
            this.equippedProgress = 1.0F;
            this.prevEquippedProgress = 1.0F;
            ci.setCancelled(true);
        }
    }
}
