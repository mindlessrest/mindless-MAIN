package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;

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

    public KeepSprint() {
        super("Keep Sprint", "Retains momentum instead of losing sprint after a hit.",
                Module.category.movement, 0);
        this.registerSetting(new DescriptionSetting(
                new String("Keeps sprint while retaining vanilla-safe hit slowdown.")));
        this.registerSetting(retainFactor = new SliderSetting(
                "Retain factor", "%", 60.0D, 0.0D, 60.0D, 1.0D));
    }

    @Override
    public String getInfo() {
        return Math.round(Math.min(60.0D, retainFactor.getInput())) + "%";
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

        double factor = Math.min(0.6D, retainFactor.getInput() / 100.0D);
        mc.thePlayer.motionX *= factor;
        mc.thePlayer.motionZ *= factor;
        mc.thePlayer.setSprinting(true);
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
