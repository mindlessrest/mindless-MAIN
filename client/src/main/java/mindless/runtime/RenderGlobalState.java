package mindless.runtime;

import mindless.module.ModuleManager;
import mindless.module.impl.world.AntiBot;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

import java.lang.reflect.Method;

/** Render-outline decisions kept outside the already-loaded RenderGlobal class. */
public final class RenderGlobalState {
    /**
     * Forge's {@code Entity.shouldRenderInPass(int)}, or null where it does not exist.
     *
     * <p>It is a Forge addition to a vanilla class, so Lunar's Minecraft has never had it.
     * Compiling a call to it is fine -- the transformer is built against Forge -- and injecting
     * that call into Lunar's RenderGlobal is also fine right up until an entity is drawn, at which
     * point the frame dies on {@link NoSuchMethodError}. Resolved once, here, rather than assumed.
     */
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

    /**
     * Whether an entity draws in the given render pass.
     *
     * <p>Falls back to what vanilla does when Forge is not there to be asked: everything renders
     * in pass 0 and nothing in any other. Entities that override the method on Forge keep their
     * answer.
     */
    public static boolean shouldRenderInPass(Object entity, int pass) {
        if (SHOULD_RENDER_IN_PASS != null && entity != null) {
            try {
                return (Boolean) SHOULD_RENDER_IN_PASS.invoke(entity, Integer.valueOf(pass));
            } catch (Throwable ignored) {
                // Fall through to the vanilla answer.
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
