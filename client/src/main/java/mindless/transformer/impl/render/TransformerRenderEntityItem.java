package mindless.transformer.impl.render;

import mindless.module.impl.render.ItemPhysics;
import mindless.runtime.ItemPhysicsState;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.lenni0451.classtransform.annotations.injection.COverride;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.entity.RenderEntityItem;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.ForgeHooksClient;
import org.lwjgl.opengl.GL11;

@CTransformer(RenderEntityItem.class)
public abstract class TransformerRenderEntityItem {

    @CInline
    @CInject(method = "doRender(Lnet/minecraft/entity/item/EntityItem;DDDFF)V", target = @CTarget("HEAD"), cancellable = true)
    private void itemPhysics$doRender(EntityItem entity, double x, double y, double z,
                                       float entityYaw, float partialTicks, InjectionCallback ci) {
        if (ItemPhysics.instance == null) return;
        ci.setCancelled(true);

        Minecraft mc = Minecraft.getMinecraft();
        RenderEntityItem self = (RenderEntityItem) (Object) this;

        double speed = ItemPhysics.instance.rotationSpeed.getInput();
        ItemPhysicsState.rotation = (System.nanoTime() - ItemPhysicsState.lastNano) / 2500000.0 * speed;
        if (!mc.isGamePaused()) {
            ItemPhysicsState.lastNano = System.nanoTime();
        } else {
            ItemPhysicsState.rotation = 0;
        }

        ItemStack stack = entity.getEntityItem();
        if (stack == null || stack.getItem() == null) return;

        int seed = Item.getIdFromItem(stack.getItem()) + stack.getMetadata();
        ItemPhysicsState.random.setSeed(seed);

        self.bindTexture(TextureMap.locationBlocksTexture);
        self.getRenderManager().renderEngine.getTexture(TextureMap.locationBlocksTexture).setBlurMipmap(false, false);

        GlStateManager.enableRescaleNormal();
        GlStateManager.alphaFunc(516, 0.1f);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GlStateManager.pushMatrix();

        IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
        boolean is3D = model.isGui3d();
        int count = ItemPhysicsState.getModelCount(stack);

        GlStateManager.translate((float) x, (float) y, (float) z);
        if (is3D) {
            GlStateManager.scale(0.5f, 0.5f, 0.5f);
        }

        GL11.glRotatef(90.0f, 1.0f, 0.0f, 0.0f);
        GL11.glRotatef(entity.rotationYaw, 0.0f, 0.0f, 1.0f);

        if (is3D) {
            GlStateManager.translate(0.0, 0.0, -0.08);
        } else {
            GlStateManager.translate(0.0, 0.0, -0.04);
        }

        if (!entity.onGround) {
            double rot = ItemPhysicsState.rotation * 2.0;
            entity.rotationPitch += (float) rot;
        } else if (!is3D) {
            entity.rotationPitch = 0.0f;
        }

        if (is3D || mc.getRenderManager().options != null) {
            GlStateManager.rotate(entity.rotationPitch, 1.0f, 0.0f, 0.0f);
        }

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        for (int k = 0; k < count; ++k) {
            GlStateManager.pushMatrix();
            if (is3D) {
                if (k > 0) {
                    float ox = (ItemPhysicsState.random.nextFloat() * 2.0f - 1.0f) * 0.15f;
                    float oy = (ItemPhysicsState.random.nextFloat() * 2.0f - 1.0f) * 0.15f;
                    float oz = (ItemPhysicsState.random.nextFloat() * 2.0f - 1.0f) * 0.15f;
                    GlStateManager.translate(ox, oy, oz);
                }
                model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GROUND);
                mc.getRenderItem().renderItem(stack, model);
                GlStateManager.popMatrix();
            } else {
                model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GROUND);
                mc.getRenderItem().renderItem(stack, model);
                GlStateManager.popMatrix();
                GlStateManager.translate(0.0f, 0.0f, 0.05375f);
            }
        }

        GlStateManager.popMatrix();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();

        self.bindTexture(TextureMap.locationBlocksTexture);
        self.getRenderManager().renderEngine.getTexture(TextureMap.locationBlocksTexture).restoreLastBlurMipmap();
    }
}
