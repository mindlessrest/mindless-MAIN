package mindless.mixin.impl.render;

import mindless.module.impl.fun.Capes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@SideOnly(Side.CLIENT)
@Mixin(AbstractClientPlayer.class)
public class MixinAbstractClientPlayer {

    @Inject(method = "getLocationCape", at = @At("RETURN"), cancellable = true)
    private void overrideCape(CallbackInfoReturnable<ResourceLocation> cir) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        if (self.equals(Minecraft.getMinecraft().thePlayer)) {
            // Local player — use selected cape
            ResourceLocation customCape = Capes.getSelectedCapeTexture();
            if (customCape != null) {
                cir.setReturnValue(customCape);
            }
        } else {
            // Other player — check if they're a Mindless user with a cape
            ResourceLocation remoteCape = Capes.getCapeForPlayer(self);
            if (remoteCape != null) {
                cir.setReturnValue(remoteCape);
            }
        }
    }
}
