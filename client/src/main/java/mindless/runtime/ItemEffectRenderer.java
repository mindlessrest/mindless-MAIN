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
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.EntityLivingBase;
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
                float pitch = entity.rotationPitch;
                try {
                    mc.getRenderManager().renderEntityStatic(entity, partialTicks, true);
                } finally {
                    entity.rotationPitch = pitch;
                }
            }
        });
    }

    public static void renderHeld(ItemRenderer renderer, EntityLivingBase entity, ItemStack stack,
                                  ItemCameraTransforms.TransformType transform) {
        ItemEffects module = ModuleManager.itemEffects;
        if (capturing || !available(module) || !module.held.isToggled()
                || transform != ItemCameraTransforms.TransformType.FIRST_PERSON
                || !module.matches(stack)) return;
        capture(module, () -> renderer.renderItem(entity, stack, transform));
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
            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, previousFramebuffer);
            GL11.glViewport(0, 0, mc.displayWidth, mc.displayHeight);
            GL11.glPopAttrib();
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
