package mindless.module.impl.world;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TargetFilter extends Module {
    private static ButtonSetting antiBot;
    private static ButtonSetting serverTeamCheck;
    private static ButtonSetting colorTeamCheck;
    private static ButtonSetting friends;
    private static boolean preGameLobby;

    public TargetFilter() {
        super("Target Filter", Module.category.world, 0);
        this.registerSetting(serverTeamCheck = new ButtonSetting("Server team check", true));
        this.registerSetting(colorTeamCheck = new ButtonSetting("Color team check", true));
        this.registerSetting(friends = new ButtonSetting("Friends", true));
        this.registerSetting(antiBot = new ButtonSetting("Anti Bot", true));
        AntiBot.registerSettings(this);
        this.closetModule = true;
        this.liteModule = true;
    }
public static boolean isAntiBotActive() {
        return ModuleManager.targetFilter != null
                && ModuleManager.targetFilter.isEnabled()
                && antiBot != null
                && antiBot.isToggled();
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        AntiBot.onEntityJoin(event);
    }

    @Override
    public void onUpdate() {
        preGameLobby = Utils.getBedwarsStatus() == 1 || Utils.getSkyWarsStatus() == 1;
        AntiBot.onUpdate();
    }

    @Override
    public void onDisable() {
        preGameLobby = false;
        AntiBot.clear();
    }

    public static boolean shouldFilter(Entity entity) {
        if (ModuleManager.targetFilter == null || !ModuleManager.targetFilter.isEnabled()) {
            return false;
        }
        if (entity == null || entity == mc.thePlayer) {
            return true;
        }
        if (preGameLobby) {
            return true;
        }
        if (AntiBot.isBot(entity)) {
            return true;
        }

        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;

            if (friends.isToggled() && Utils.isFriended(player)) {
                return true;
            }

            if (serverTeamCheck.isToggled() && isServerTeammate(player)) {
                return true;
            }

            if (colorTeamCheck.isToggled() && isColorTeammate(player)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isServerTeammate(EntityPlayer entity) {
        if (mc.thePlayer == null || entity == mc.thePlayer) {
            return false;
        }
        try {
            return mc.thePlayer.isOnSameTeam(entity);
        } catch (Exception ignored) {}
        return false;
    }

    private static boolean isColorTeammate(EntityPlayer entity) {
        if (mc.thePlayer == null || entity == mc.thePlayer) {
            return false;
        }
        try {
            char own = detectColorCode(mc.thePlayer);
            if (own == 0) {
                return false;
            }
            return own == detectColorCode(entity);
        } catch (Exception ignored) {}
        return false;
    }

    private static char detectColorCode(EntityPlayer player) {
        char color = getDisplayNameColorCode(player);
        if (color == 0) {
            color = getTeamPrefixColorCode(player);
        }
        return color;
    }

    private static char getDisplayNameColorCode(EntityPlayer player) {
        String name = player.getName();
        String formatted = player.getDisplayName().getFormattedText();
        if (formatted == null || !formatted.contains("§")) {
            return 0;
        }
        int nameIndex = formatted.indexOf(name);
        if (nameIndex <= 0) {
            return 0;
        }
        for (int i = nameIndex - 1; i >= 0; i--) {
            if (formatted.charAt(i) == '§' && i + 1 < formatted.length()) {
                char c = Character.toLowerCase(formatted.charAt(i + 1));
                if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                    return c == 'f' ? 0 : c;
                }
            }
        }
        return 0;
    }

    private static char getTeamPrefixColorCode(EntityPlayer entity) {
        net.minecraft.scoreboard.Team team = entity.getTeam();
        if (!(team instanceof ScorePlayerTeam)) {
            return 0;
        }
        String prefix = ((ScorePlayerTeam) team).getColorPrefix();
        char color = 0;
        for (int i = 0; i + 1 < prefix.length(); i++) {
            if (prefix.charAt(i) != '§') {
                continue;
            }
            char c = Character.toLowerCase(prefix.charAt(i + 1));
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')) {
                color = c;
            }
        }
        return color == 'f' ? 0 : color;
    }
}
