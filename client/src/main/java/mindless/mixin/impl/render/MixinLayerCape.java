package mindless.mixin.impl.render;

import mindless.module.impl.fun.Capes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.layers.LayerCape;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@SideOnly(Side.CLIENT)
@Mixin(LayerCape.class)
public class MixinLayerCape {

    @Redirect(method = "doRenderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;isWearing(Lnet/minecraft/entity/player/EnumPlayerModelParts;)Z"))
    private boolean modifyIsWearing(AbstractClientPlayer player, EnumPlayerModelParts part) {
        if (player.equals(Minecraft.getMinecraft().thePlayer)) {
            if (Capes.getSelectedCapeTexture() != null) return true;
        } else {
            if (Capes.getCapeForPlayer(player) != null) return true;
        }
        return player.isWearing(part);
    }

    @Redirect(method = "doRenderLayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/entity/AbstractClientPlayer;getLocationCape()Lnet/minecraft/util/ResourceLocation;"))
    private ResourceLocation modifyGetLocationCape(AbstractClientPlayer player) {
        if (player.equals(Minecraft.getMinecraft().thePlayer)) {
            ResourceLocation cape = Capes.getSelectedCapeTexture();
            if (cape != null) return cape;
        } else {
            ResourceLocation remoteCape = Capes.getCapeForPlayer(player);
            if (remoteCape != null) return remoteCape;
        }
        return player.getLocationCape();
    }
}
