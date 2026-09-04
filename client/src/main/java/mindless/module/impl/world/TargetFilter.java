package mindless.module.impl.world;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.Utils;
import mindless.utility.BedwarsTeam;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TargetFilter extends Module {
    private static ButtonSetting antiBot;
    private static ButtonSetting serverTeamCheck;
    private static ButtonSetting colorTeamCheck;
    private static ButtonSetting friends;
    private static boolean preGameLobby;

    public TargetFilter() {
        super("Target Filter", "Decides who counts as an enemy.", Module.category.world, 0);
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
        return BedwarsTeam.isSameColorTeam(mc.thePlayer, entity);
    }
}
