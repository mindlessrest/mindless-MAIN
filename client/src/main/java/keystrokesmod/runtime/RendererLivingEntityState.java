package keystrokesmod.runtime;

import keystrokesmod.module.ModuleManager;
import net.minecraft.entity.EntityLivingBase;

/**
 * External state for TransformerRendererLivingEntity fields.
 * Avoids adding instance fields to RendererLivingEntity via transformation.
 */
public final class RendererLivingEntityState {
    private RendererLivingEntityState() {}

    public static boolean bodyMaterialActive;
    public static EntityLivingBase nameHiderRenderNameEntity;

    public static boolean shouldRender() {
        return ModuleManager.playerESP != null && ModuleManager.playerESP.isEnabled() && ModuleManager.playerESP.outline.isToggled();
    }
}
