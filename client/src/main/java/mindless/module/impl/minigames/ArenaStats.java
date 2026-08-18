package mindless.module.impl.minigames;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Theme;
import mindless.utility.Utils;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.*;

public class ArenaStats extends Module {
    private static final String[] THEMES = new String[]{"Cherry", "Cotton", "Flare", "Flower", "Gold", "Grayscale", "Royal", "Sky", "Vine"};

    private final ButtonSetting lowercase;
    private final ButtonSetting dropShadow;
    private final SliderSetting theme;

    // BedWars tracking
    private boolean hasSharpness = false;
    private boolean hasProtection = false;
    private boolean hasTrap = false;
    private String trapType = "";
    private int protectionLevel = 0;

    public ArenaStats() {
        super("Arena Stats", category.minigames);
        this.registerSetting(lowercase = new ButtonSetting("Lowercase", false));
        this.registerSetting(dropShadow = new ButtonSetting("Drop shadow", true));
        this.registerSetting(theme = new SliderSetting("Theme", 0, THEMES));
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        hasSharpness = false;
        hasProtection = false;
        hasTrap = false;
        trapType = "";
        protectionLevel = 0;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }

        if (mc.currentScreen != null) {
            return;
        }

        int y = 15;
        int x = 15;
        boolean shadow = dropShadow.isToggled();

        // Draw sharpness indicator
        if (hasSharpness) {
            String text = lowercase.isToggled() ? "sharpness" : "Sharpness";
            mc.fontRendererObj.drawStringWithShadow(text, x, y, getChromaColor());
            y += 10;
        }

        // Draw protection indicator
        if (hasProtection) {
            String text = lowercase.isToggled() ? "protection " + protectionLevel : "Protection " + protectionLevel;
            mc.fontRendererObj.drawStringWithShadow(text, x, y, getChromaColor());
            y += 10;
        }

        // Draw trap indicator
        if (hasTrap && !trapType.isEmpty()) {
            String text = lowercase.isToggled() ? "trap: " + trapType.toLowerCase() : "Trap: " + trapType;
            mc.fontRendererObj.drawStringWithShadow(text, x, y, getChromaColor());
        }
    }

    /**
     * Called when a chat message is received to track BedWars upgrades
     */
    public void onChatMessage(String message) {
        // Reset on new game/lobby
        if (message.contains("Reward Summary") || message.contains("joined the lobby") || message.contains("has joined (")) {
            reset();
        }

        // Clear trap when triggered
        if (message.contains("was set off!")) {
            hasTrap = false;
            trapType = "";
        }

        // Track sharpness
        if (message.contains("purchased Sharpened")) {
            hasSharpness = true;
        }

        // Track protection
        if (message.contains("purchased Reinforced Armor")) {
            hasProtection = true;
            protectionLevel++;
        }

        // Track traps
        if (message.contains("purchased Miner Fatigue Trap")) {
            hasTrap = true;
            trapType = "Mining Fatigue";
        } else if (message.contains("purchased It's a trap")) {
            hasTrap = true;
            trapType = "Blindness+Slowness";
        } else if (message.contains("purchased Counter-Offensive Trap")) {
            hasTrap = true;
            trapType = "Counter-Offensive";
        } else if (message.contains("purchased Alarm Trap")) {
            hasTrap = true;
            trapType = "Alarm";
        }
    }

    private int getChromaColor() {
        int themeIndex = (int) theme.getInput();
        double delay = 0;

        // Use Theme class for gradient colors
        if (themeIndex >= 0 && themeIndex < Theme.values().length) {
            return Theme.getGradient(themeIndex + 1, delay); // +1 because 0 is Rainbow
        }

        return Theme.getGradient(0, delay); // Rainbow as fallback
    }
}
