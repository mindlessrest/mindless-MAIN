package mindless.runtime;

import mindless.module.ModuleManager;
import net.minecraft.entity.EntityLivingBase;

/**
 * External state for TransformerRendererLivingEntity fields.
 * Avoids adding instance fields to RendererLivingEntity via transformation.
 */
public final class RendererLivingEntityState {
    private RendererLivingEntityState() {}

    public static EntityLivingBase nameHiderRenderNameEntity;

    public static boolean shouldRender() {
        return ModuleManager.sexyESP != null && ModuleManager.sexyESP != null && ModuleManager.sexyESP.isEnabled() && ModuleManager.sexyESP.isGlowEnabled();
    }
}
