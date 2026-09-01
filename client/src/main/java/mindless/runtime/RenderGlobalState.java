package mindless.runtime;

import mindless.module.ModuleManager;
import mindless.module.impl.world.AntiBot;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import java.lang.reflect.Method;
public final class RenderGlobalState {
private static final Method SHOULD_RENDER_IN_PASS = findShouldRenderInPass();

    private RenderGlobalState() {}

    private static Method findShouldRenderInPass() {
        try {
            Method method = Entity.class.getMethod("shouldRenderInPass", int.class);
            method.setAccessible(true);
            return method;
        } catch (Throwable absent) {
            return null;
        }
    }
public static boolean shouldRenderInPass(Object entity, int pass) {
        if (SHOULD_RENDER_IN_PASS != null && entity != null) {
            try {
                return (Boolean) SHOULD_RENDER_IN_PASS.invoke(entity, Integer.valueOf(pass));
            } catch (Throwable ignored) {
            }
        }
        return pass == 0;
    }

    public static boolean shouldRenderOutlines() {
        return ModuleManager.sexyESP != null && ModuleManager.sexyESP != null && ModuleManager.sexyESP.isEnabled()
                && ModuleManager.sexyESP.isGlowEnabled();
    }

    public static boolean isOutlineActive(Entity entity, Minecraft minecraft) {
        if (!shouldRenderOutlines()) return true;
        Entity viewer = minecraft == null ? null : minecraft.getRenderViewEntity();
        return (entity != viewer && !AntiBot.isBot(entity))
                || (entity == viewer && ModuleManager.sexyESP.isRenderSelf());
    }
}
