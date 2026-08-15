package keystrokesmod.transformer.impl.render;

import keystrokesmod.module.impl.fun.Capes;
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
        if (((Object) this).equals(Minecraft.getMinecraft().thePlayer)) {
            ResourceLocation customCape = Capes.getSelectedCapeTexture();
            if (customCape != null) {
                ci.setReturnValue(customCape);
            }
        }
    }
}
