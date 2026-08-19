package mindless.transformer.impl.render;

import mindless.module.impl.fun.Capes;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.util.ResourceLocation;

@CTransformer(AbstractClientPlayer.class)
public class TransformerAbstractClientPlayer {
    @CInline
    @CInject(method = "getLocationCape", target = @CTarget("RETURN"), cancellable = true)
    private void overrideCape(InjectionCallback ci) {
        AbstractClientPlayer self = (AbstractClientPlayer) (Object) this;

        if (self.equals(Minecraft.getMinecraft().thePlayer)) {
            ResourceLocation customCape = Capes.getSelectedCapeTexture();
            if (customCape != null) {
                ci.setReturnValue(customCape);
            }
        } else {
            ResourceLocation remoteCape = Capes.getCapeForPlayer(self);
            if (remoteCape != null) {
                ci.setReturnValue(remoteCape);
            }
        }
    }
}
