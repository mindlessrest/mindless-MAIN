package mindless.runtime;

import mindless.module.ModuleManager;
import net.minecraft.entity.EntityLivingBase;
public final class RendererLivingEntityState {
    private RendererLivingEntityState() {}

    public static EntityLivingBase nameHiderRenderNameEntity;

    public static boolean shouldRender() {
        return ModuleManager.sexyESP != null && ModuleManager.sexyESP != null && ModuleManager.sexyESP.isEnabled() && ModuleManager.sexyESP.isGlowEnabled();
    }
}
