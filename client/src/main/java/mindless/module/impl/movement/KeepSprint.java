package mindless.module.impl.movement;

import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class KeepSprint extends Module {
    public static SliderSetting mode;
    public static SliderSetting slow;
    public static ButtonSetting stopSprint;
    public static ButtonSetting disableWhileJump;
    public static ButtonSetting reduceReachHits;

    private static final String[] MODES = {"Vanilla", "Watchdog", "Blatant"};

    private int wdState;
    private int wdTicks;
    private boolean hitThisTick;

    public KeepSprint() {
        super("Keep Sprint", Module.category.movement, 0);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(new DescriptionSetting(new String("Default is 40% motion reduction.")));
        this.registerSetting(slow = new SliderSetting("Slow %", 40.0D, 0.0D, 40.0D, 1.0D));
        this.registerSetting(stopSprint = new ButtonSetting("Stop Sprint", true));
        this.registerSetting(disableWhileJump = new ButtonSetting("Disable while jumping", false));
        this.registerSetting(reduceReachHits = new ButtonSetting("Only reduce reach hits", false));
    }

    @Override
    public void onDisable() {
        wdState = 0;
        wdTicks = 0;
        hitThisTick = false;
    }

    @Override
    public void guiUpdate() {
        boolean vanilla = (int) mode.getInput() == 0;
        slow.setVisible(vanilla, this);
        stopSprint.setVisible(vanilla, this);
        disableWhileJump.setVisible(vanilla, this);
        reduceReachHits.setVisible(vanilla, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) return;
        if ((int) mode.getInput() != 1) return;

        hitThisTick = false;

        if (wdTicks > 5) {
            wdState = 0;
            wdTicks = 0;
        }

        switch (wdState) {
            case 1:
                mc.thePlayer.setSprinting(false);
                wdTicks++;
                break;
            case 2:
                if (!mc.thePlayer.isUsingItem()) {
                    mc.thePlayer.setSprinting(true);
                }
                wdState = 0;
                wdTicks = 0;
                break;
        }
    }

    public static void keepSprint(Entity en) {
        int m = (int) mode.getInput();
        if (m == 2) return; // Blatant: no slowdown at all
        if (m == 1) {
            keepSprintWatchdog(en);
            return;
        }

        boolean vanilla = false;
        if (disableWhileJump.isToggled() && !mc.thePlayer.onGround) {
            vanilla = true;
        }
        else if (reduceReachHits.isToggled() && !mc.thePlayer.capabilities.isCreativeMode) {
            double distance = -1.0;
            final Vec3 getPositionEyes = mc.thePlayer.getPositionEyes(1.0f);
            if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null) {
                distance = getPositionEyes.distanceTo(KillAura.target.getPositionEyes(1.0f));
            }
            else if (ModuleManager.reach != null && ModuleManager.reach.isEnabled()) {
                distance = getPositionEyes.distanceTo(mc.objectMouseOver.hitVec);
            }
            if (distance != -1.0 && distance <= 3.0) {
                vanilla = true;
            }
        }
        if (vanilla) {
            mc.thePlayer.motionX *= 0.6;
            mc.thePlayer.motionZ *= 0.6;
        }
        else {
            float mult = (100.0f - (float) slow.getInput()) / 100.0f;
            mc.thePlayer.motionX *= mult;
            mc.thePlayer.motionZ *= mult;
        }

        if (stopSprint.isToggled()) {
            mc.thePlayer.motionX *= 0.5;
        }
    }

    private static void keepSprintWatchdog(Entity en) {
        KeepSprint ks = ModuleManager.keepSprint;
        if (ks == null) return;

        if (!(en instanceof EntityPlayer)) {
            applySlowdown();
            return;
        }

        if (!ks.hitThisTick) {
            ks.hitThisTick = true;
            if (mc.thePlayer.isSprinting()) {
                switch (ks.wdState) {
                    case 0:
                        ks.wdState = 1;
                        ks.wdTicks = 0;
                        mc.thePlayer.motionX *= 0.6;
                        mc.thePlayer.motionZ *= 0.6;
                        mc.thePlayer.setSprinting(false);
                        break;
                    case 1:
                        mc.thePlayer.setSprinting(false);
                        ks.wdTicks = 0;
                        ks.wdState = 2;
                        break;
                    default:
                        applySlowdown();
                        break;
                }
            } else {
                ks.wdState = 2;
                ks.wdTicks = 0;
            }
        }
    }

    private static void applySlowdown() {
        float mult = (100.0f - (float) slow.getInput()) / 100.0f;
        mc.thePlayer.motionX *= mult;
        mc.thePlayer.motionZ *= mult;
    }
}
