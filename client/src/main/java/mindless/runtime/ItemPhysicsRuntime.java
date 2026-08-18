package mindless.runtime;

import mindless.module.impl.render.ItemPhysics;
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

import java.util.Random;

public final class ItemPhysicsRuntime {
    private static final Random RANDOM = new Random();
    private static long lastNano = System.nanoTime();
    private static double rotation;
    private ItemPhysicsRuntime() {}

    public static boolean render(RenderEntityItem renderer, EntityItem entity,
                                 double x, double y, double z) {
        if (ItemPhysics.instance == null) return false;
        Minecraft mc = Minecraft.getMinecraft();
        rotation = (System.nanoTime() - lastNano) / 2500000.0D * ItemPhysics.instance.rotationSpeed.getInput();
        if (!mc.isGamePaused()) lastNano = System.nanoTime(); else rotation = 0D;
        ItemStack stack = entity.getEntityItem();
        if (stack == null || stack.getItem() == null) return true;
        RANDOM.setSeed(Item.getIdFromItem(stack.getItem()) + stack.getMetadata());
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).setBlurMipmap(false, false);
        GlStateManager.enableRescaleNormal(); GlStateManager.alphaFunc(516, .1F); GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); GlStateManager.pushMatrix();
        IBakedModel model = mc.getRenderItem().getItemModelMesher().getItemModel(stack);
        boolean is3D = model.isGui3d(); int count = count(stack);
        GlStateManager.translate((float) x, (float) y, (float) z);
        if (is3D) GlStateManager.scale(.5F, .5F, .5F);
        GL11.glRotatef(90F, 1F, 0F, 0F); GL11.glRotatef(entity.rotationYaw, 0F, 0F, 1F);
        GlStateManager.translate(0D, 0D, is3D ? -.08D : -.04D);
        if (!entity.onGround) entity.rotationPitch += (float) (rotation * 2D); else if (!is3D) entity.rotationPitch = 0F;
        if (is3D || mc.getRenderManager().options != null) GlStateManager.rotate(entity.rotationPitch, 1F, 0F, 0F);
        GlStateManager.color(1F, 1F, 1F, 1F);
        for (int i = 0; i < count; i++) {
            GlStateManager.pushMatrix();
            if (is3D && i > 0) {
                float ox = (RANDOM.nextFloat() * 2F - 1F) * .15F;
                float oy = (RANDOM.nextFloat() * 2F - 1F) * .15F;
                float oz = (RANDOM.nextFloat() * 2F - 1F) * .15F;
                boolean spread = AccessorBridge.RenderEntityItem_shouldSpreadItems(renderer);
                GlStateManager.translate(spread ? ox : 0F, spread ? oy : 0F, oz);
            }
            model = ForgeHooksClient.handleCameraTransforms(model, ItemCameraTransforms.TransformType.GROUND);
            mc.getRenderItem().renderItem(stack, model);
            GlStateManager.popMatrix();
            if (!is3D) GlStateManager.translate(0F, 0F, .05375F);
        }
        GlStateManager.popMatrix(); GlStateManager.disableRescaleNormal(); GlStateManager.disableBlend();
        mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
        mc.getTextureManager().getTexture(TextureMap.locationBlocksTexture).restoreLastBlurMipmap();
        return true;
    }

    private static int count(ItemStack stack) {
        return stack.stackSize > 48 ? 5 : stack.stackSize > 32 ? 4 : stack.stackSize > 16 ? 3 : stack.stackSize > 1 ? 2 : 1;
    }
}
