package keystrokesmod.runtime;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/** Render-outline decisions kept outside the already-loaded RenderGlobal class. */
public final class RenderGlobalState {
    private RenderGlobalState() {}

    public static boolean shouldRenderOutlines() {
        return ModuleManager.playerESP != null && ModuleManager.playerESP.isEnabled()
                && ModuleManager.playerESP.outline.isToggled();
    }

    public static boolean isOutlineActive(Entity entity, Minecraft minecraft) {
        if (!shouldRenderOutlines()) return true;
        Entity viewer = minecraft == null ? null : minecraft.getRenderViewEntity();
        return (entity != viewer && !AntiBot.isBot(entity))
                || (entity == viewer && ModuleManager.playerESP.renderSelf.isToggled());
    }
}
