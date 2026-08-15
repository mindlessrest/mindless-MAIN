package keystrokesmod.utility;

/**
 * SafeWalk state placeholder. The original MixinEntity.injectSafeWalk
 * @ModifyVariable used to flip an internal boolean inside Entity.moveEntity
 * to keep the player from stepping off edges. JVMTI retransform cannot patch
 * a specific STORE opcode reliably. Modules can still consult this flag if
 * they want to gate behaviour without the bytecode hook — it is set true by
 * the SafeWalk / Scaffold modules on ticks where an edge safeguard is
 * wanted, and consumers can read {@link #isActive()}.
 */
public final class SafeWalkState {
    private static volatile boolean active;
    private SafeWalkState() {}
    public static void enable(boolean value) { active = value; }
    public static boolean isActive() { return active; }
}
