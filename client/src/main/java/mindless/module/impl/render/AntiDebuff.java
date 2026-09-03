package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import net.minecraft.potion.Potion;

public class AntiDebuff extends Module {
    public ButtonSetting removeBlindness;
    public ButtonSetting removeNausea;
    public ButtonSetting removeSideEffects;

    public AntiDebuff() {
        super("Anti Debuff", "Strips blindness, nausea and screen effects.", category.render);
        this.liteModule = true;
        this.registerSetting(removeBlindness = new ButtonSetting("Remove blindness", true));
        this.registerSetting(removeNausea = new ButtonSetting("Remove nausea", true));
        this.registerSetting(removeSideEffects = new ButtonSetting("Remove side effects", false));
    }

    public boolean canRemoveBlindness(Potion potion) {
        return this.isEnabled() && potion == Potion.blindness && this.removeBlindness.isToggled();
    }

    public boolean canRemoveNausea(Potion potion) {
        return this.isEnabled() && potion == Potion.confusion && this.removeNausea.isToggled();
    }
}
