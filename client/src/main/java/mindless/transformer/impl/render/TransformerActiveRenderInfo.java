package mindless.transformer.impl.render;

import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.ActiveRenderInfo;

@CTransformer(ActiveRenderInfo.class)
public class TransformerActiveRenderInfo {
    @CInline
    @CInject(method = "updateRenderInfo", target = @CTarget("RETURN"))
    private static void onUpdateRenderInfoReturn(InjectionCallback ci) {
    }
}
