package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.utility.Utils;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.settings.KeyBinding;

public class Sprint extends Module {

    private final ButtonSetting allowUsingItem;
    private final ButtonSetting allowBackwards;
    private final ButtonSetting allowSideways;
    private final ButtonSetting allowInInventory;
    private final ButtonSetting testSprint;

    public Sprint() {
        super("Sprint", "Sprints without holding the sprint key.", category.movement, 0);
        this.liteModule = true;
        this.registerSetting(new DescriptionSetting("Allow while"));
        this.registerSetting(allowUsingItem = new ButtonSetting("Using item", false));
        this.registerSetting(allowBackwards = new ButtonSetting("Backwards", false));
        this.registerSetting(allowSideways = new ButtonSetting("Sideways", false));
        this.registerSetting(allowInInventory = new ButtonSetting("In inventory", false));
        this.registerSetting(testSprint = new ButtonSetting("TestSprint", false));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        if (Utils.nullCheck()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }
        if (testSprint.isToggled()) {
            // Exact minimal sprint behavior retained as an opt-in test path. Because this branch
            // returns immediately, it fully overrides the legacy Sprint logic below.
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
            return;
        }
        boolean inGame = mc.inGameHasFocus;
        boolean inInv = allowInInventory.isToggled() && (mc.currentScreen instanceof GuiInventory || mc.currentScreen instanceof GuiChest);
        if (!inGame && !inInv) {
            return;
        }
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    public boolean allowWhileUsingItem() {
        return this.isEnabled() && allowUsingItem.isToggled();
    }

    public boolean allowWhileBackwards() {
        return this.isEnabled() && allowBackwards.isToggled();
    }

    public boolean allowWhileSideways() {
        return this.isEnabled() && allowSideways.isToggled();
    }
}
