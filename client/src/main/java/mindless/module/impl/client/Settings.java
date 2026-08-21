package mindless.module.impl.client;

import mindless.Raven;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import net.minecraft.client.gui.GuiChat;
import org.lwjgl.input.Keyboard;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;

public class Settings extends Module {
    public static KeySetting reinjectKey;
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

    public static SliderSetting customCapes;

    public static SliderSetting offset;
    public static SliderSetting timeMultiplier;
    public static SliderSetting defaultTheme;
    public static SliderSetting scoreboardPosX;
    public static SliderSetting scoreboardPosY;
    public static ButtonSetting arrayListGlow;
    public static ButtonSetting scoreboardGlow;
    public static ButtonSetting chatGlow;

    public Settings() {
        super("Settings", category.client, 0);
        mindless.utility.CapeManager.reloadCustomCapes();
        this.registerSetting(new ButtonSetting("Uninject", () -> Raven.uninject()));
        this.registerSetting(reinjectKey = new KeySetting("Reinject key", Keyboard.KEY_INSERT));
        this.registerSetting(customCapes = new SliderSetting("Custom cape", 0, mindless.utility.CapeManager.getCapeOptions()));
        this.registerSetting(new DescriptionSetting("HUD layout"));
        this.registerSetting(new ButtonSetting("Edit HUD elements", () -> mc.displayGuiScreen(new HudEditor.Screen())));
        this.registerSetting(arrayListGlow = new ButtonSetting("Array List text glow", false));
        this.registerSetting(scoreboardGlow = new ButtonSetting("Scoreboard text glow", false));
        this.registerSetting(chatGlow = new ButtonSetting("Chat text glow", false));
        this.registerSetting(scoreboardPosX = new SliderSetting("Scoreboard position X", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(scoreboardPosY = new SliderSetting("Scoreboard position Y", 0.0, -1.0, 1.0, 0.001));
        scoreboardPosX.visible = false;
        scoreboardPosY.visible = false;
        scoreboardPosX.setValueRaw(-1.0D);
        scoreboardPosY.setValueRaw(-1.0D);
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

    public static boolean hasCustomScoreboardPosition() {
        return scoreboardPosX != null && scoreboardPosY != null
                && scoreboardPosX.getInput() >= 0.0D && scoreboardPosY.getInput() >= 0.0D;
    }

    public static float getScoreboardX(float width, ScaledResolution resolution, float defaultX) {
        if (!hasCustomScoreboardPosition()) return defaultX;
        return (float) (Math.max(0.0F, resolution.getScaledWidth() - width) * scoreboardPosX.getInput());
    }

    public static float getScoreboardY(float height, ScaledResolution resolution, float defaultY) {
        if (!hasCustomScoreboardPosition()) return defaultY;
        return (float) (Math.max(0.0F, resolution.getScaledHeight() - height) * scoreboardPosY.getInput());
    }

    public static void setScoreboardPosition(float x, float y, float width, float height, ScaledResolution resolution) {
        if (scoreboardPosX == null || scoreboardPosY == null || resolution == null) return;
        float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
        float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
        float clampedX = Math.max(0.0F, Math.min(maxX, x));
        float clampedY = Math.max(0.0F, Math.min(maxY, y));
        scoreboardPosX.setValueRaw(maxX <= 0.0F ? 0.0D : clampedX / maxX);
        scoreboardPosY.setValueRaw(maxY <= 0.0F ? 0.0D : clampedY / maxY);
    }

    public static void resetScoreboardPosition() {
        if (scoreboardPosX != null) scoreboardPosX.setValueRaw(-1.0D);
        if (scoreboardPosY != null) scoreboardPosY.setValueRaw(-1.0D);
    }
}
