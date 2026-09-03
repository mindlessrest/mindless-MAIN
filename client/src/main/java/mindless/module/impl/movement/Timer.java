package mindless.module.impl.movement;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;

public class Timer extends Module {
    private SliderSetting speed;

    public Timer() {
        super("Timer", "Speeds up or slows down the whole game.", category.movement);
        this.registerSetting(speed = new SliderSetting("Speed", 1.0D, 0.0D, 2.0D, 0.1D));
    }

    @Override
    public String getInfo() {
        return Utils.asWholeNum(speed.getInput());
    }

    public float getConfiguredSpeed() {
        return (float) speed.getInput();
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck() && speed.getInput() <= 0.0D) {
            Utils.resetTimer();
        }
    }

    @Override
    public void onDisable() {
        Utils.resetTimer();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }

        float configuredSpeed = (float) speed.getInput();
        if (configuredSpeed > 0.0F) {
            AccessorBridge.Minecraft_getTimer(mc).timerSpeed = configuredSpeed;
        } else {
            Utils.resetTimer();
        }
    }

    public static int consumeExtraLocalUpdatesForBaseTick() {
        return 0;
    }

    public static boolean shouldSkipBaseLocalUpdate() {
        Timer timer = mindless.module.ModuleManager.timer;
        if (timer == null || !timer.isEnabled()) {
            return false;
        }
        if (!Utils.nullCheck()) {
            return false;
        }
        return timer.speed.getInput() <= 0.0D;
    }
}
