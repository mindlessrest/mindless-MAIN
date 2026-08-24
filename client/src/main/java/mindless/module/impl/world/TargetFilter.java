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

    /**
     * Whether bot detection should answer at all.
     *
     * <p>Read by AntiBot itself, so a consumer calling AntiBot.isBot directly -- the render side
     * does, for chams and ESP -- gets the same answer as one going through shouldFilter.
     */
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
        AntiBot.onUpdate();
    }

    @Override
    public void onDisable() {
        AntiBot.clear();
    }

    public static boolean shouldFilter(Entity entity) {
        if (!ModuleManager.targetFilter.isEnabled()) {
            return false;
        }
        if (entity == null || entity == mc.thePlayer) {
            return true;
        }

        // The toggle is checked inside isBot, so this reads the same either way round.
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
            String own = getTeamColorCode(mc.thePlayer);
            if (own.isEmpty()) {
                return false;
            }
            return own.equals(getTeamColorCode(entity));
        } catch (Exception ignored) {}
        return false;
    }

    private static String getTeamColorCode(EntityPlayer entity) {
        net.minecraft.scoreboard.Team team = entity.getTeam();
        if (!(team instanceof ScorePlayerTeam)) {
            return "";
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
        return color == 0 || color == 'f' ? "" : String.valueOf(color);
    }
}
