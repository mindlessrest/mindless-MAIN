package mindless.module.impl.movement;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Retains a configurable portion of the player's pre-hit horizontal momentum.
 *
 * Vanilla applies a 0.6 multiplier and clears sprint at the attack-success point where
 * {@link #keepSprint(Entity)} is called by the player attack hook. Applying the configured
 * factor there is equivalent to restoring the pre-attack motion after vanilla's slowdown,
 * without relying on fragile floating-point equality checks one event later.
 */
public class KeepSprint extends Module {
    public static SliderSetting retainFactor;
    public static ButtonSetting resetSprint;

    private static final long RESET_INTERVAL_MS = 500L;
    private long lastSprintReset;
    private boolean sprintResetPending;
    private boolean cancelNextSprintEnable;

    public KeepSprint() {
        super("Keep Sprint", "Retains momentum instead of losing sprint after a hit.",
                Module.category.movement, 0);
        this.registerSetting(new DescriptionSetting(
                new String("Retains 95% movement by default after attacking.")));
        this.registerSetting(retainFactor = new SliderSetting(
                "Retain factor", "%", 95.0D, 60.0D, 100.0D, 1.0D));
        this.registerSetting(resetSprint = new ButtonSetting("Reset sprint", false));
    }

    @Override
    public void onEnable() {
        lastSprintReset = System.currentTimeMillis();
        sprintResetPending = false;
        cancelNextSprintEnable = false;
    }

    @Override
    public void onDisable() {
        sprintResetPending = false;
        cancelNextSprintEnable = false;
    }

    @Override
    public String getInfo() {
        return Math.round(retainFactor.getInput()) + "%";
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!sprintResetPending || !Utils.nullCheck()) return;

        // Match the source module's delayed reset: clear sprint on the following player tick,
        // then suppress the next automatic attempt to immediately turn it back on.
        mc.thePlayer.setSprinting(false);
        cancelNextSprintEnable = true;
        sprintResetPending = false;
    }

    /** Called by the local-player movement path before an automatic sprint enable. */
    public static boolean consumeSprintEnableCancellation() {
        KeepSprint module = ModuleManager.keepSprint;
        if (module == null || !module.isEnabled() || !module.cancelNextSprintEnable) {
            return false;
        }
        module.cancelNextSprintEnable = false;
        return true;
    }

    /**
     * Replaces vanilla's attack slowdown at its original call site.
     */
    public static void keepSprint(Entity target) {
        KeepSprint module = ModuleManager.keepSprint;
        if (module == null || !module.isEnabled() || !Utils.nullCheck()) return;

        // Vape deliberately leaves Scaffold's own sprint/placement state alone.
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isActivelyScaffolding()) {
            applyVanillaSlowdown();
            return;
        }

        // The source only retains momentum for forward combat movement.
        if (mc.thePlayer.movementInput == null
                || mc.thePlayer.movementInput.moveForward <= 0.0F
                || (!mc.thePlayer.isSprinting() && !isMovingForward())) {
            applyVanillaSlowdown();
            return;
        }

        double factor = retainFactor.getInput() / 100.0D;
        mc.thePlayer.motionX *= factor;
        mc.thePlayer.motionZ *= factor;

        if (!resetSprint.isToggled()) {
            mc.thePlayer.setSprinting(true);
            return;
        }

        // With Reset sprint enabled, normal movement input owns re-enabling sprint. Periodically
        // force one clean stop transition, matching Vape's reset timer instead of packet-spamming.
        mc.thePlayer.setSprinting(false);
        long now = System.currentTimeMillis();
        if (now - module.lastSprintReset >= RESET_INTERVAL_MS) {
            module.sprintResetPending = true;
            module.lastSprintReset = now;
        }
    }

    private static boolean isMovingForward() {
        float yaw = (float) Math.toRadians(mc.thePlayer.rotationYaw);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        return mc.thePlayer.motionX * forwardX + mc.thePlayer.motionZ * forwardZ > 0.0D;
    }

    private static void applyVanillaSlowdown() {
        mc.thePlayer.motionX *= 0.6D;
        mc.thePlayer.motionZ *= 0.6D;
        mc.thePlayer.setSprinting(false);
    }
}
