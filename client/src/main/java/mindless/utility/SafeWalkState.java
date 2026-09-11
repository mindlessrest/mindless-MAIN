package mindless.utility;

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
        Minecraft mc = Minecraft.getMinecraft();
        boolean sneaking = entity == mc.thePlayer && mc.thePlayer.movementInput != null
                ? mc.thePlayer.movementInput.sneak : isSneakingRaw(entity);
        if (entity != mc.thePlayer || !entity.onGround) {
            return sneaking;
        }
        boolean safeWalk = SafeWalk.canSafeWalk();
        enable(safeWalk);
        return sneaking || safeWalk;
    }
}
