package mindless.mixin.impl.render;

import mindless.utility.media.MediaPlayerRenderer;
import mindless.utility.HudRenderBounds;
import mindless.utility.shader.BlurUtils;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(value = GuiIngameForge.class, priority = 9999)
public abstract class MixinGuiIngameForge {
    @Inject(method = "renderGameOverlay", at = @At("HEAD"))
    private void clearHudRenderBounds(float partialTicks, CallbackInfo callbackInfo) {
        HudRenderBounds.clearScoreboard();
        // GuiIngameForge overrides renderGameOverlay without calling super, so the copy of this
        // on GuiIngame never runs here and the shared blur frame was never opened.
        BlurUtils.beginFrame();
    }

    @Inject(
        method = "renderGameOverlay",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraftforge/client/GuiIngameForge;renderPlayerList(II)V",
            shift = At.Shift.AFTER,
            remap = false
        )
    )
    private void renderPersistentCenterBar(float partialTicks, CallbackInfo callbackInfo) {
        MediaPlayerRenderer.render();
    }
}
