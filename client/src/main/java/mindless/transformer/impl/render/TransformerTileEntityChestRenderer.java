package mindless.transformer.impl.render;

import mindless.module.impl.render.ChestESP;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.tileentity.TileEntityChestRenderer;
import net.minecraft.tileentity.TileEntityChest;

@CTransformer(TileEntityChestRenderer.class)
public class TransformerTileEntityChestRenderer {
    @CInline
    @CInject(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntityChest;DDDFI)V",
            target = @CTarget("HEAD")
    )
    private void raven$chestChamsPre(TileEntityChest te, double x, double y, double z, float partialTicks, int destroyStage, InjectionCallback ci) {
        ChestESP.onRenderChestPre(te);
    }

    @CInline
    @CInject(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntityChest;DDDFI)V",
            target = @CTarget("RETURN")
    )
    private void raven$chestChamsPost(TileEntityChest te, double x, double y, double z, float partialTicks, int destroyStage, InjectionCallback ci) {
        ChestESP.onRenderChestPost();
    }
}
