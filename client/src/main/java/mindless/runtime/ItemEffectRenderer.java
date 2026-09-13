package mindless.runtime;

import mindless.module.ModuleManager;
import mindless.module.impl.render.ItemEffects;
import mindless.utility.RenderUtils;
import mindless.utility.shader.GlowBloomShader;
import mindless.utility.shader.GlowShader;
import mindless.utility.shader.SeparableOutlineShader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

public final class ItemEffectRenderer {
    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final GlowShader silhouetteShader = new GlowShader();
    private static final GlowBloomShader bloomShader = new GlowBloomShader();
    private static final SeparableOutlineShader outlineShader = new SeparableOutlineShader();
    private static Framebuffer silhouette;
    private static boolean capturing;

    private ItemEffectRenderer() {}

    public static void renderDropped(ItemEffects module, float partialTicks) {
        if (!available(module) || !module.dropped.isToggled() || mc.theWorld == null
                || mc.getRenderViewEntity() == null) return;
        double distanceSq = module.distance.getInput() * module.distance.getInput();
        List<EntityItem> candidates = new ArrayList<>();
        for (EntityItem entity : mc.theWorld.getEntities(EntityItem.class, e -> e != null
                && !e.isDead && e.getEntityItem() != null
                && e.getDistanceSqToEntity(mc.getRenderViewEntity()) <= distanceSq
                && module.matches(e.getEntityItem()))) {
            candidates.add(entity);
        }
        if (candidates.isEmpty()) return;
        capture(module, () -> {
            for (EntityItem entity : candidates) {
                silhouetteShader.use();
                silhouetteShader.setColorFromARGB(module.color.getColor() | 0xFF000000);
                GlStateManager.disableAlpha();
                GlStateManager.disableLighting();
                float pitch = entity.rotationPitch;
                try {
                    mc.getRenderManager().renderEntityStatic(entity, partialTicks, true);
                } finally {
                    entity.rotationPitch = pitch;
                }
            }
        });
    }

    public static void renderHeld(net.minecraft.client.renderer.entity.RenderItem renderer, ItemStack stack,
                                  net.minecraft.client.resources.model.IBakedModel model,
                                  ItemCameraTransforms.TransformType transform) {
        ItemEffects module = ModuleManager.itemEffects;
        if (capturing || !available(module) || stack == null || !module.matches(stack)) return;
        boolean firstPerson = transform == ItemCameraTransforms.TransformType.FIRST_PERSON;
        boolean thirdPerson = transform == ItemCameraTransforms.TransformType.THIRD_PERSON;
        if (firstPerson ? !module.held.isToggled() : !(thirdPerson && module.heldThirdPerson.isToggled())) {
            return;
        }
        // The same model draw again, under the same matrices: everything above this call -- the
        // first-person or third-person placement, a 3D item's scale, Lunar's own adjustments --
        // is already on the stack, so the silhouette cannot land anywhere the item does not.
        capture(module, () -> {
            try {
                mindless.runtime.AccessorBridge.RenderItem_renderItemModelTransform(renderer, stack, model, transform);
            } catch (RuntimeException unavailable) {
                // No silhouette this frame rather than a crash every frame.
            }
        });
    }

    public static void renderInventory(GuiContainer gui, int guiLeft, int guiTop) {
        ItemEffects module = ModuleManager.itemEffects;
        if (capturing || !available(module) || !module.inventory.isToggled()
                || gui == null || gui.inventorySlots == null) return;
        boolean any = false;
        for (Object object : gui.inventorySlots.inventorySlots) {
            Slot slot = (Slot) object;
            if (slot.getHasStack() && module.matches(slot.getStack())) {
                any = true;
                break;
            }
        }
        if (!any) return;
        capture(module, () -> {
            for (Object object : gui.inventorySlots.inventorySlots) {
                Slot slot = (Slot) object;
                ItemStack stack = slot.getStack();
                if (!slot.getHasStack() || !module.matches(stack)) continue;
                silhouetteShader.use();
                silhouetteShader.setColorFromARGB(module.color.getColor() | 0xFF000000);
                GlStateManager.disableAlpha();
                GlStateManager.disableLighting();
                mc.getRenderItem().renderItemAndEffectIntoGUI(stack,
                        guiLeft + slot.xDisplayPosition, guiTop + slot.yDisplayPosition);
            }
        });
    }

    private static boolean available(ItemEffects module) {
        if (module == null || !module.isEnabled() || !module.hasEffects() || !silhouetteShader.isValid()) {
            return false;
        }
        return module.outline.isToggled() && outlineShader.isValid()
                || (module.glow.isToggled() || module.blur.isToggled()) && bloomShader.isValid();
    }

    private static void capture(ItemEffects module, Runnable renderer) {
        if (capturing) return;
        silhouette = RenderUtils.createFrameBuffer(silhouette, false);
        if (silhouette == null) return;
        silhouette.setFramebufferColor(0f, 0f, 0f, 0f);
        silhouette.setFramebufferFilter(GL11.GL_LINEAR);

        // Mipmaps off for the pass. The silhouette is a texture-alpha test, and a mipmapped atlas
        // sampled at a fraction of native size hands back the average of a texel and its
        // transparent neighbours -- which is an item quietly failing the test rather than an item
        // drawn slightly soft. Vanilla's own GUI item path does the same thing for the same
        // reason. Restored through the texture manager's own stack below.
        mc.getTextureManager().bindTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
        net.minecraft.client.renderer.texture.ITextureObject atlas =
                mc.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureMap.locationBlocksTexture);
        if (atlas != null) atlas.setBlurMipmap(false, false);

        int previousFramebuffer = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        int previousMatrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        try {
            silhouette.framebufferClear();
            silhouette.bindFramebuffer(true);
            capturing = true;
            // The pass owns its state rather than inheriting whatever drew last. Item rendering
            // turns lighting on for block models, leaves the depth mask off for translucent ones
            // and switches the blend function for enchanted ones; a silhouette that inherits any
            // of that is an item that draws for some stacks and not others.
            GlStateManager.disableLighting();
            GlStateManager.disableFog();
            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableCull();
            GlStateManager.disableAlpha();
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.colorMask(true, true, true, true);
            GlStateManager.color(1f, 1f, 1f, 1f);
            silhouetteShader.use();
            silhouetteShader.setColorFromARGB(module.color.getColor() | 0xFF000000);
            renderer.run();
            silhouetteShader.stop();
            capturing = false;

            mc.getFramebuffer().bindFramebuffer(true);
            mc.entityRenderer.disableLightmap();
            mc.entityRenderer.setupOverlayRendering();
            int color = module.color.getColor();
            int red = color >> 16 & 255;
            int green = color >> 8 & 255;
            int blue = color & 255;
            if (module.blur.isToggled() && bloomShader.isValid()) {
                bloomShader.render(silhouette, (float) module.blurRadius.getInput() * 4f,
                        (float) module.blurStrength.getInput(), red, green, blue, true);
            }
            if (module.glow.isToggled() && bloomShader.isValid()) {
                bloomShader.render(silhouette, (float) module.glowRadius.getInput() * 4f,
                        (float) module.glowStrength.getInput(), red, green, blue, false);
            }
            if (module.outline.isToggled() && outlineShader.isValid()) {
                outlineShader.render(silhouette, (float) module.outlineThickness.getInput());
            }
            silhouette.framebufferClear();
        } finally {
            capturing = false;
            silhouetteShader.stop();
            if (atlas != null) atlas.restoreLastBlurMipmap();
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
            // Popped through the helper so the state cache is re-read from the driver: the pass
            // changes fog, lighting, depth and the lightmap through the cache, and a bare pop left
            // it disagreeing with what the pop had just restored.
            RenderUtils.popAttrib();
            GlStateManager.matrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(GL11.GL_PROJECTION);
            GlStateManager.popMatrix();
            GlStateManager.matrixMode(previousMatrixMode);
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.color(1f, 1f, 1f, 1f);
            RenderUtils.syncGlState();
        }
    }

    public static void release() {
        if (silhouette != null) {
            silhouette.deleteFramebuffer();
            silhouette = null;
        }
        bloomShader.delete();
        outlineShader.delete();
    }
}
