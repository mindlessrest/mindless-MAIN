package mindless.module.impl.bedwars;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashSet;
import java.util.Set;

/**
 * Says when an enemy team buys a team upgrade.
 *
 * <p>Hypixel does not announce these, so the first you usually know about Sharpened Swords is
 * losing a fight you should have won. There is no packet for it either -- the only evidence is
 * that everyone on that team is suddenly holding enchanted gear, so that is what this watches.
 *
 * <p>Once per team per upgrade. The upgrade is bought for the whole team, so the second and third
 * player carrying it is the same piece of news.
 */
public class UpgradeAlerts extends Module {
    private static final String SHARPNESS = "Sharpened Swords";
    private static final String PROTECTION = "Reinforced Armour";
    /** A second between sweeps. Armour does not change hands fast enough to want more. */
    private static final int SCAN_INTERVAL = 20;

    private final ButtonSetting pingSound;

    private final Set<String> announced = new HashSet<String>();
    private int ticks;

    public UpgradeAlerts() {
        super("Upgrade Alerts", category.bedwars);
        this.registerSetting(pingSound = new ButtonSetting("Ping sound", true));
    }

    @Override
    public void onDisable() {
        announced.clear();
        ticks = 0;
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || !Utils.nullCheck()) return;

        if (Utils.getBedwarsStatus() != 2) {
            // Between games the slate has to be clean, or the next game reports nothing.
            if (!announced.isEmpty()) announced.clear();
            return;
        }

        if (++ticks < SCAN_INTERVAL) return;
        ticks = 0;

        for (EntityPlayer player : mc.theWorld.playerEntities) {
            check(player);
        }
    }

    private void check(EntityPlayer player) {
        if (player == null || player == mc.thePlayer) return;
        if (Utils.isTeammate(player)) return;

        ItemStack held = player.getHeldItem();
        if (held != null && held.getItem() instanceof ItemSword && held.isItemEnchanted()) {
            announce(player, SHARPNESS);
        }

        ItemStack chest = player.inventory.armorInventory[2];
        if (chest != null && chest.getItem() instanceof ItemArmor && chest.isItemEnchanted()) {
            announce(player, PROTECTION);
        }
    }

    private void announce(EntityPlayer player, String upgrade) {
        String team = teamName(player);
        if (team == null) return;

        String key = team + "/" + upgrade;
        if (!announced.add(key)) return;

        Utils.sendMessage(team + " &7purchased &3" + upgrade);
        if (pingSound.isToggled()) {
            mc.thePlayer.playSound("note.pling", 1.0f, 1.2f);
        }
    }

    /** The player's bedwars team, as a coloured label, or null when they are not on one. */
    private String teamName(EntityPlayer player) {
        if (mc.theWorld == null) return null;
        ScorePlayerTeam team = mc.theWorld.getScoreboard().getPlayersTeam(player.getName());
        if (team == null) return null;

        switch (colourOf(team.getColorPrefix())) {
            case RED: return "&cRed";
            case BLUE: return "&9Blue";
            case GREEN: return "&aGreen";
            case YELLOW: return "&eYellow";
            case AQUA: return "&bAqua";
            case WHITE: return "&fWhite";
            case LIGHT_PURPLE: return "&dPink";
            case DARK_GRAY: return "&8Grey";
            default: return null;
        }
    }

    /** The first colour code in a team prefix, which is the team's colour. */
    private EnumChatFormatting colourOf(String prefix) {
        if (prefix == null) return EnumChatFormatting.RESET;
        for (int i = 0; i < prefix.length() - 1; i++) {
            if (prefix.charAt(i) != '§') continue;
            String code = prefix.substring(i, i + 2);
            for (EnumChatFormatting format : EnumChatFormatting.values()) {
                if (format.isColor() && format.toString().equals(code)) return format;
            }
        }
        return EnumChatFormatting.RESET;
    }
}
