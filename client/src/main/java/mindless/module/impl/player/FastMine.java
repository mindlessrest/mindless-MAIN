package mindless.module.impl.player;

import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;

public class FastMine extends Module {
    private SliderSetting delay;
    public SliderSetting multiplier;
    private ButtonSetting creativeDisable;
    private ButtonSetting decreaseBreakDelay;
    private ButtonSetting ignoreShears;
private int passiveBlockHitLastSeen;

    public FastMine() {
        super("Fast Mine", category.player);
        this.liteModule = true;
        this.registerSetting(new DescriptionSetting("Vanilla is 250ms delay & 1x speed."));
        this.registerSetting(delay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(multiplier = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02));
        this.registerSetting(creativeDisable = new ButtonSetting("Disable in creative", true));
        this.registerSetting(decreaseBreakDelay = new ButtonSetting("Decrease break delay", false));
        this.registerSetting(ignoreShears = new ButtonSetting("Ignore shears", false));
        this.closetModule = true;
    }

    public float getBreakSpeedMultiplier() {
        if (!this.isEnabled()) {
            return 1.0f;
        }
        if (creativeDisable.isToggled() && mc.thePlayer != null && mc.thePlayer.capabilities.isCreativeMode) {
            return 1.0f;
        }
        if (ignoreShears.isToggled() && mc.thePlayer != null
                && mc.thePlayer.getHeldItem() != null
                && mc.thePlayer.getHeldItem().getItem() instanceof net.minecraft.item.ItemShears) {
            return 1.0f;
        }
        float m = (float) multiplier.getInput();
        return m > 1.0f ? m : 1.0f;
    }

    public boolean shouldPassiveDecreaseBlockHitDelay() {
        return this.isEnabled() && decreaseBreakDelay.isToggled() && Utils.nullCheck();
    }

    public void tickPassiveBlockHitDecay(Minecraft mc) {
        if (!shouldPassiveDecreaseBlockHitDelay() || mc.playerController == null) {
            return;
        }
        int delay = AccessorBridge.PlayerControllerMP_getBlockHitDelay(mc.playerController);
        if (delay > 0 && delay == this.passiveBlockHitLastSeen) {
            AccessorBridge.PlayerControllerMP_setBlockHitDelay(mc.playerController, delay - 1);
        }
        this.passiveBlockHitLastSeen = delay;
    }

    public int getBlockHitDelayOverrideOrMinusOne() {
        if (!this.isEnabled() || !Utils.nullCheck() || !mc.inGameHasFocus) {
            return -1;
        }
        if (creativeDisable.isToggled() && mc.thePlayer != null && mc.thePlayer.capabilities.isCreativeMode) {
            return -1;
        }
        int ticks = (int) (this.delay.getInput() / 50.0);
        if (ticks >= 5) {
            return -1;
        }
        return ticks;
    }

    @Override
    public String getInfo() {
        return ((int) multiplier.getInput() == multiplier.getInput() ? (int) multiplier.getInput() + "" : multiplier.getInput()) + multiplier.getSuffix();
    }
}
