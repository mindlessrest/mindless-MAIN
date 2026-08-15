package keystrokesmod.mixin.impl.render;

import keystrokesmod.utility.media.SpotifyMiniPlayerRenderer;
import keystrokesmod.utility.HudRenderBounds;
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
        SpotifyMiniPlayerRenderer.render();
    }
}
