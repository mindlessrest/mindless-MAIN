package mindless.transformer.impl.render;

import mindless.module.impl.render.Chams;
import mindless.utility.BlockAnimationUtils;
import mindless.utility.Utils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.CRedirect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

@CTransformer(RenderPlayer.class)
public class TransformerRenderPlayer {
    @CInline
    @CInject(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V", target = @CTarget("HEAD"), cancellable = true)
    private void onDoRenderHead(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        if (Chams.onRenderPlayerPre(entity)) {
            ci.setCancelled(true);
            return;
        }
        BlockAnimationUtils.beginRender(entity);
    }

    @CInline
    @CInject(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V", target = @CTarget("RETURN"))
    private void onDoRenderReturn(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        BlockAnimationUtils.endRender(entity);
        Chams.onRenderPlayerPost(entity);
    }

    @CInline
    @CRedirect(method = "setModelVisibilities(Lnet/minecraft/client/entity/AbstractClientPlayer;)V",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/entity/player/InventoryPlayer;getCurrentItem()Lnet/minecraft/item/ItemStack;", optional = true))
    private ItemStack redirectGetCurrentItem(InventoryPlayer inventory) {
        if (Minecraft.getMinecraft().gameSettings.thirdPersonView == 0 && inventory.player == Minecraft.getMinecraft().thePlayer) {
            return Utils.getSpoofedItem(inventory.getCurrentItem());
        }
        else {
            return inventory.getCurrentItem();
        }
    }

    @CInline
    @CRedirect(method = "setModelVisibilities(Lnet/minecraft/client/entity/AbstractClientPlayer;)V",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getItemInUseCount()I", optional = true))
    private int redirectGetItemInUseCount(AbstractClientPlayer clientPlayer) {
        int actualCount = clientPlayer.getItemInUseCount();
        if (actualCount > 0) {
            return actualCount;
        }

        ItemStack itemStack = Minecraft.getMinecraft().gameSettings.thirdPersonView == 0
            ? Utils.getSpoofedItem(clientPlayer.inventory.getCurrentItem())
            : clientPlayer.inventory.getCurrentItem();

        return BlockAnimationUtils.shouldForceBlockAnimation(clientPlayer, itemStack) ? 1 : actualCount;
    }
}
