package mindless.module.impl.client;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.input.Keyboard;
import net.minecraft.client.gui.inventory.GuiInventory;

public class Settings extends Module {
    public static ButtonSetting diagnostics;
    public static ButtonSetting diagnosticsChat;
    public static ButtonSetting addBracketsToDistance;
    public static ButtonSetting hideFirstPersonESP;
    public static ButtonSetting setChatAsInventory;
    public static ButtonSetting showHealthAsHearts;
    public static ButtonSetting showHeartSymbol;

    public static ButtonSetting weaponAxe;
    public static ButtonSetting weaponEnchanted;
    public static ButtonSetting weaponFist;
    public static ButtonSetting weaponHoe;
    public static ButtonSetting weaponRod;
    public static ButtonSetting weaponShovel;
    public static ButtonSetting weaponStick;

    public static ButtonSetting rotateBody;
    public static ButtonSetting fullBody;
    public static SliderSetting randomYawFactor;

    public static ButtonSetting sendMessage;
    public static ButtonSetting autoSaveProfiles;

    public static SliderSetting offset;
    public static SliderSetting timeMultiplier;
    public static SliderSetting defaultTheme;
    public Settings() {
        super("Settings", "Client options that span every module.", category.client, 0);
        this.registerSetting(new ButtonSetting("Uninject", () -> Mindless.uninject()));
        this.registerSetting(new DescriptionSetting("Diagnostics"));
        this.registerSetting(diagnostics = new ButtonSetting("Diagnostics", false));
        this.registerSetting(diagnosticsChat = new ButtonSetting("Diagnostics in chat", false));
        this.registerSetting(new ButtonSetting("Dump GL info", () -> mindless.utility.Diagnostics.dumpEnvironment()));
        this.registerSetting(new DescriptionSetting("General"));
        this.registerSetting(addBracketsToDistance = new ButtonSetting("Add brackets to distance", false));
        this.registerSetting(hideFirstPersonESP = new ButtonSetting("Hide first person self ESP", true));
        this.registerSetting(setChatAsInventory = new ButtonSetting("Set chat as inventory", false));
        this.registerSetting(showHealthAsHearts = new ButtonSetting("Show health as hearts", false));
        this.registerSetting(showHeartSymbol = new ButtonSetting("Show heart symbol", false));
        this.registerSetting(new DescriptionSetting("Extra weapons"));
        this.registerSetting(weaponAxe = new ButtonSetting("Axe", false));
        this.registerSetting(weaponEnchanted = new ButtonSetting("Enchanted", false));
        this.registerSetting(weaponFist = new ButtonSetting("Fist", false));
        this.registerSetting(weaponHoe = new ButtonSetting("Hoe", false));
        this.registerSetting(weaponRod = new ButtonSetting("Rod", false));
        this.registerSetting(weaponShovel = new ButtonSetting("Shovel", false));
        this.registerSetting(weaponStick = new ButtonSetting("Stick", true));
        this.registerSetting(new DescriptionSetting("Rotations"));
        this.registerSetting(rotateBody = new ButtonSetting("Rotate body", true));
        this.registerSetting(fullBody = new ButtonSetting("Full body", false));
        this.registerSetting(randomYawFactor = new SliderSetting("Random yaw factor", 0, 0.0, 10.0, 1.0));
        this.registerSetting(new DescriptionSetting("Profiles"));
        this.registerSetting(sendMessage = new ButtonSetting("Send message on enable", true));
        this.registerSetting(autoSaveProfiles = new ButtonSetting("Auto save profiles", false));
        this.registerSetting(new DescriptionSetting("Theme colors"));
        this.registerSetting(offset = new SliderSetting("Offset", 0.5, -3.0, 3.0, 0.1));
        this.registerSetting(timeMultiplier = new SliderSetting("Time multiplier", 0.5, 0.1, 4.0, 0.1));
        this.registerSetting(defaultTheme = new SliderSetting("Default theme", 1, mindless.utility.Theme.THEMES_STRING));
        this.canBeEnabled = false;
    }

    public static boolean inInventory() {
        if (mc.currentScreen instanceof GuiInventory) {
            return true;
        }
        if (mc.currentScreen instanceof GuiChat && setChatAsInventory.isToggled()) {
            return true;
        }
        return false;
    }

}
