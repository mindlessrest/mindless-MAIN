package mindless.transformer.impl.render;

import mindless.utility.shader.BlurUtils;
import mindless.utility.HudRenderBounds;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraftforge.client.GuiIngameForge;

@CTransformer(GuiIngameForge.class)
public abstract class TransformerGuiIngameForge {
    @CInline
    @CInject(method = "renderGameOverlay", target = @CTarget("HEAD"))
    private void clearHudRenderBounds(float partialTicks, InjectionCallback callbackInfo) {
        HudRenderBounds.clearScoreboard();
        BlurUtils.beginFrame();
    }
}
