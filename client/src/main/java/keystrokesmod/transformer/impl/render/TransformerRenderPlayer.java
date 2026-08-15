package keystrokesmod.transformer.impl.render;

import keystrokesmod.utility.BlockAnimationUtils;
import keystrokesmod.utility.Utils;
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
    @CInject(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V", target = @CTarget("HEAD"))
    private void onDoRenderHead(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        BlockAnimationUtils.beginRender(entity);
    }

    @CInline
    @CInject(method = "doRender(Lnet/minecraft/client/entity/AbstractClientPlayer;DDDFF)V", target = @CTarget("RETURN"))
    private void onDoRenderReturn(AbstractClientPlayer entity, double x, double y, double z, float entityYaw, float partialTicks, InjectionCallback ci) {
        BlockAnimationUtils.endRender(entity);
    }

    @CInline
    @CRedirect(method = "setModelVisibilities(Lnet/minecraft/client/entity/AbstractClientPlayer;)V",
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/entity/player/InventoryPlayer;getCurrentItem()Lnet/minecraft/item/ItemStack;"))
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
            target = @CTarget(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getItemInUseCount()I"))
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
