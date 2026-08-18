package keystrokesmod.utility;

import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.player.SafeWalk;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;

/**
 * SafeWalk state shared by both injection backends. The original
 * MixinEntity.injectSafeWalk @ModifyVariable flips an internal boolean inside
 * Entity.moveEntity to keep the player from stepping off edges. JVMTI
 * retransform cannot patch a specific STORE opcode reliably, so the Lunar
 * path in TransformerEntity redirects the single Entity.isSneaking() call
 * that moveEntity uses to compute that same boolean, and routes the decision
 * through {@link #shouldSafeWalk(Entity)} here. Consumers that just want to
 * know whether an edge safeguard is active can read {@link #isActive()}.
 */
public final class SafeWalkState {
    private static volatile boolean active;
    private SafeWalkState() {}
    public static void enable(boolean value) { active = value; }
    public static boolean isActive() { return active; }

    /**
     * Vanilla sneak state without calling Entity.isSneaking. moveEntity's
     * redirected call site IS Entity.isSneaking, so the transformer body must
     * not invoke it again. isSneaking is getFlag(1), which reads bit 1 of
     * DataWatcher object 0 — read here directly.
     */
    public static boolean isSneakingRaw(Entity entity) {
        return (entity.getDataWatcher().getWatchableObjectByte(0) & 1 << 1) != 0;
    }

    /**
     * Decides the value moveEntity's edge-safeguard flag should take. Mirrors
     * MixinEntity.injectSafeWalk: only the local player, only on ground.
     */
    public static boolean shouldSafeWalk(Entity entity) {
        boolean sneaking = isSneakingRaw(entity);
        Minecraft mc = Minecraft.getMinecraft();
        if (entity != mc.thePlayer || !entity.onGround) {
            return sneaking;
        }
        boolean safeWalk = SafeWalk.canSafeWalk()
                || (ModuleManager.scaffold != null && ModuleManager.scaffold.safewalk());
        enable(safeWalk);
        return sneaking || safeWalk;
    }
}
