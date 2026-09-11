package mindless.utility;

import mindless.module.ModuleManager;
import mindless.module.impl.player.SafeWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
public final class SafeWalkState {
    private static volatile boolean active;
    private SafeWalkState() {}
    public static void enable(boolean value) { active = value; }
    public static boolean isActive() { return active; }
public static boolean isSneakingRaw(Entity entity) {
        return (entity.getDataWatcher().getWatchableObjectByte(0) & 1 << 1) != 0;
    }
public static boolean shouldSafeWalk(Entity entity) {
        boolean sneaking = isSneakingRaw(entity);
        Minecraft mc = Minecraft.getMinecraft();
        if (entity != mc.thePlayer || !entity.onGround) {
            return sneaking;
        }
        boolean safeWalk = SafeWalk.canSafeWalk()
                || ModuleManager.scaffold != null && ModuleManager.scaffold.wantsSafeWalk();
        enable(safeWalk);
        return sneaking || safeWalk;
    }
}
