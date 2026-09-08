package mindless.utility.combat;

import mindless.module.Module;
import mindless.module.impl.world.AntiBot;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.boss.EntityDragon;
import net.minecraft.entity.boss.EntityWither;
import net.minecraft.entity.monster.EntityGolem;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;

/**
 * The "who counts as a target" block that several combat modules need.
 *
 * Auto Block and Velocity both decide whether to act based on what is nearby, and both wanted the
 * same list of questions: which entity classes, which relationships, how far, and within what
 * angle. Written once here rather than twice and slightly differently, which is how the two ended
 * up disagreeing about what a target was.
 *
 * Settings register into the owning module, so each module keeps its own independent values --
 * this is shared code, not shared state.
 */
public final class EntityTargets {
    private final ButtonSetting players;
    private final ButtonSetting mobs;
    private final ButtonSetting animals;
    private final ButtonSetting bosses;
    private final ButtonSetting silverfish;
    private final ButtonSetting golems;
    private final ButtonSetting friends;
    private final ButtonSetting enemies;
    private final ButtonSetting teammates;
    private final ButtonSetting bots;
    private final SliderSetting range;
    private final SliderSetting fov;

    public EntityTargets(Module owner, String groupName,
                         double defaultRange, double minRange, double maxRange) {
        GroupSetting group = new GroupSetting(groupName);
        owner.registerSetting(group);
        owner.registerSetting(range = new SliderSetting(group, "Target range", " block",
                defaultRange, minRange, maxRange, 0.05));
        owner.registerSetting(fov = new SliderSetting(group, "FOV", "°", 360.0, 1.0, 360.0, 1.0));
        owner.registerSetting(players = new ButtonSetting(group, "Players", true));
        owner.registerSetting(mobs = new ButtonSetting(group, "Mobs", false));
        owner.registerSetting(animals = new ButtonSetting(group, "Animals", false));
        owner.registerSetting(bosses = new ButtonSetting(group, "Bosses", false));
        owner.registerSetting(silverfish = new ButtonSetting(group, "Silverfish", false));
        owner.registerSetting(golems = new ButtonSetting(group, "Golems", false));
        owner.registerSetting(friends = new ButtonSetting(group, "Friends", false));
        owner.registerSetting(enemies = new ButtonSetting(group, "Enemies", true));
        owner.registerSetting(teammates = new ButtonSetting(group, "Teammates", false));
        owner.registerSetting(bots = new ButtonSetting(group, "Bots", false));
    }

    public double getRange() {
        return range.getInput();
    }

    public double getFov() {
        return fov.getInput();
    }

    /**
     * Whether this entity is one the owning module should act on.
     *
     * Class first, then relationship. A player excluded by relationship is excluded whatever the
     * class toggles say, so switching Players on does not quietly re-include your own team.
     */
    public boolean matches(Entity entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer || entity.isDead) {
            return false;
        }
        if (((EntityLivingBase) entity).getHealth() <= 0.0f) {
            return false;
        }

        if (entity instanceof EntityPlayer) {
            if (!players.isToggled()) {
                return false;
            }
            EntityPlayer player = (EntityPlayer) entity;
            if (!bots.isToggled() && AntiBot.isBot(player)) {
                return false;
            }
            if (Utils.isFriended(player)) {
                return friends.isToggled();
            }
            if (Utils.isTeammate(player)) {
                return teammates.isToggled();
            }
            // Anything left is someone we have no relationship with; Enemies governs those, which
            // is the switch that decides whether the module works at all on a normal server.
            return enemies.isToggled() || Utils.isEnemy(player);
        }

        if (entity instanceof EntityDragon || entity instanceof EntityWither) {
            return bosses.isToggled();
        }
        if (entity instanceof EntitySilverfish) {
            return silverfish.isToggled();
        }
        if (entity instanceof EntityGolem) {
            return golems.isToggled();
        }
        if (entity instanceof EntityMob) {
            return mobs.isToggled();
        }
        if (entity instanceof EntityAnimal) {
            return animals.isToggled();
        }
        return false;
    }

    /** Whether the entity is inside both the range and the angle. */
    public boolean inReach(Entity entity) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || entity == null) {
            return false;
        }
        if (mc.thePlayer.getDistanceToEntity(entity) > range.getInput()) {
            return false;
        }
        double allowed = fov.getInput();
        return allowed >= 360.0 || Utils.inFov((float) allowed, entity);
    }

    /** The nearest entity that passes every filter, or null. */
    public EntityLivingBase findNearest() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return null;
        }
        EntityLivingBase best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!matches(entity) || !inReach(entity)) {
                continue;
            }
            double distance = mc.thePlayer.getDistanceSqToEntity(entity);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = (EntityLivingBase) entity;
            }
        }
        return best;
    }
}
