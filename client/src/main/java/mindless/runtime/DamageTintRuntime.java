package mindless.runtime;

import mindless.module.impl.render.DamageTint;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;

import java.nio.FloatBuffer;

public final class DamageTintRuntime {
    private static final ThreadLocal<EntityLivingBase> ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<Integer> INDEX = new ThreadLocal<>();
    private DamageTintRuntime() {}

    public static void begin(EntityLivingBase entity) { ENTITY.set(entity); INDEX.set(0); }

    public static FloatBuffer put(FloatBuffer buffer, float vanilla) {
        int index = INDEX.get() == null ? 0 : INDEX.get();
        INDEX.set(index + 1);
        if (DamageTint.instance == null) return buffer.put(vanilla);
        float value;
        switch (index) {
            case 0: value = DamageTint.instance.color.getRed() / 255F; break;
            case 1: value = DamageTint.instance.color.getGreen() / 255F; break;
            case 2: value = DamageTint.instance.color.getBlue() / 255F; break;
            case 3: value = DamageTint.computeAlpha(ENTITY.get()); break;
            default: value = vanilla;
        }
        return buffer.put(value);
    }

    public static void restore(RendererLivingEntity<?> renderer) {
        if (DamageTint.instance != null) {
            AccessorBridge.RendererLivingEntity_callUnsetBrightness(renderer);
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit); GlStateManager.enableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit); GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha(); GlStateManager.alphaFunc(516, .1F); GlStateManager.disableBlend();
            GlStateManager.enableLighting(); GlStateManager.enableDepth(); GlStateManager.depthMask(true);
            GlStateManager.color(1F, 1F, 1F, 1F);
        }
        ENTITY.remove(); INDEX.remove();
    }
}
