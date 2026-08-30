package mindless.transformer.impl.render;

import mindless.module.impl.render.ChestESP;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.renderer.tileentity.TileEntityEnderChestRenderer;
import net.minecraft.tileentity.TileEntityEnderChest;

@CTransformer(TileEntityEnderChestRenderer.class)
public class TransformerTileEntityEnderChestRenderer {
    @CInline
    @CInject(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntityEnderChest;DDDFI)V",
            target = @CTarget("HEAD")
    )
    private void mindless$enderChestChamsPre(TileEntityEnderChest te, double x, double y, double z, float partialTicks, int destroyStage, InjectionCallback ci) {
        ChestESP.onRenderChestPre(te);
    }

    @CInline
    @CInject(
            method = "renderTileEntityAt(Lnet/minecraft/tileentity/TileEntityEnderChest;DDDFI)V",
            target = @CTarget("RETURN")
    )
    private void mindless$enderChestChamsPost(TileEntityEnderChest te, double x, double y, double z, float partialTicks, int destroyStage, InjectionCallback ci) {
        ChestESP.onRenderChestPost();
    }
}
