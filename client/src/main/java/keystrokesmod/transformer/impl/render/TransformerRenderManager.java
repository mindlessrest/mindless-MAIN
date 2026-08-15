package keystrokesmod.transformer.impl.render;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.render.Freelook;
import keystrokesmod.runtime.RenderManagerState;
import keystrokesmod.utility.RotationUtils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;

@CTransformer(RenderManager.class)
public class TransformerRenderManager {

    @CInline
    @CInject(method = "cacheActiveRenderInfo",
            target = @CTarget("RETURN"))
    private void applyFreelookViewAngles(InjectionCallback ci) {
        if (ModuleManager.freelook != null && ModuleManager.freelook.isEnabled()
                && Freelook.perspectiveToggled) {
            RenderManager rm = (RenderManager) (Object) this;
            rm.playerViewY = Freelook.cameraYaw;
            rm.playerViewX = Freelook.cameraPitch;
        }
    }

    @CInline
    @CInject(method = "renderEntityStatic", target = @CTarget("HEAD"))
    public void renderEntityStaticPre(Entity entity, float n, boolean b, InjectionCallback ci) {
        if (entity instanceof EntityPlayerSP && PreMotionEvent.setRenderYaw()) {
            EntityPlayerSP player = (EntityPlayerSP) entity;
            RenderManagerState.capture(player);
            player.prevRotationPitch = RotationUtils.prevRenderPitch;
            player.rotationPitch = RotationUtils.renderPitch;
        }
    }

    @CInline
    @CInject(method = "renderEntityStatic", target = @CTarget("RETURN"))
    public void renderEntityStaticPost(Entity entity, float n, boolean b, InjectionCallback ci) {
        if (entity instanceof EntityPlayerSP && RenderManagerState.hasCapturedState()) {
            EntityPlayerSP player = (EntityPlayerSP) entity;
            RenderManagerState.restore(player);
        }
    }
}
