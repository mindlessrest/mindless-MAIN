package mindless.module.impl.combat;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.helper.RotationHelper;
import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.render.Notifications;
import mindless.module.impl.world.AntiBot;
import mindless.module.impl.world.TargetFilter;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityGiantZombie;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class KillAura extends Module {
    private SliderSetting targetCPS;
    private SliderSetting fov;
    private SliderSetting attackRange;
    private SliderSetting swingRange;
    private SliderSetting aimRange;
    public SliderSetting rotationMode;
    private SliderSetting speed;
    private SliderSetting sortMode;
    private SliderSetting switchDelay;
    private SliderSetting targets;
    private ButtonSetting attackMobs;
    private ButtonSetting targetInvis;
    private ButtonSetting disableInInventory;
    private ButtonSetting disableWhileMining;
    private ButtonSetting aimThroughBlocks;
    private ButtonSetting aimThroughEntities;
    private ButtonSetting prioritizeEnemies;
    private ButtonSetting notUsingItem;
    private ButtonSetting requireMouseDown;
    private ButtonSetting weaponOnly;
    private ButtonSetting autoBlock;
    private ButtonSetting humanize;
    private ButtonSetting killNotification;
    private boolean blocking;

    private String[] rotationModes = new String[]{"Silent", "Lock view", "None"};
    private String[] sortModes = new String[]{"Distance", "Health", "Hurt time", "Yaw"};

    public static EntityLivingBase target;
    public static EntityLivingBase attackingEntity;

    public boolean isRequireMouseDown() {
        return requireMouseDown.isToggled();
    }

    private HashMap<Integer, Integer> hitMap = new HashMap<>();
    private List<Entity> hostileMobs = new ArrayList<>();
    private Set<Integer> hostileMobIds = new HashSet<>();
    private Map<Integer, Boolean> golems = new HashMap<>();
    private Set<Integer> monsterClassCache = new HashSet<>();
    private Set<Integer> nonMonsterClassCache = new HashSet<>();

    private long nextClickTime;
    private Random rand;
    private double targetDistance = Double.MAX_VALUE;
    private int lastAttackedEntityId = -1;
    private long lastAttackTimeMs;
    private long lastKillNotifyMs;
    private float lastAttackedHealth = -1f;

    public KillAura() {
        super("Kill Aura", category.combat);
        this.liteModule = true;
        this.registerSetting(targetCPS = new SliderSetting("Target CPS", 10.0, 1.0, 20.0, 0.5));
        this.registerSetting(fov = new SliderSetting("FOV", "°", 360.0, 30.0, 360.0, 4.0));
        this.registerSetting(attackRange = new SliderSetting("Range (attack)", 3.0, 3.0, 6.0, 0.05));
        this.registerSetting(swingRange = new SliderSetting("Range (swing)", 4.5, 3.0, 8.0, 0.05));
        this.registerSetting(aimRange = new SliderSetting("Range (aim)", 4.5, 3.0, 8.0, 0.05));
        this.registerSetting(rotationMode = new SliderSetting("Rotation mode", 0, rotationModes));
        this.registerSetting(speed = new SliderSetting("Speed", 10, 1, 30, 1));
        this.registerSetting(sortMode = new SliderSetting("Sort mode", 0, sortModes));
        this.registerSetting(switchDelay = new SliderSetting("Switch delay", "ms", 200.0, 50.0, 1000.0, 25.0));
        this.registerSetting(targets = new SliderSetting("Targets", 3.0, 1.0, 10.0, 1.0));
        this.registerSetting(targetInvis = new ButtonSetting("Target invis", true));
        this.registerSetting(attackMobs = new ButtonSetting("Attack mobs", false));
        this.registerSetting(aimThroughBlocks = new ButtonSetting("Hit through walls", false));
        this.registerSetting(aimThroughEntities = new ButtonSetting("Hit through entities", false));
        this.registerSetting(disableInInventory = new ButtonSetting("Disable in inventory", true));
        this.registerSetting(disableWhileMining = new ButtonSetting("Disable while mining", false));
        this.registerSetting(notUsingItem = new ButtonSetting("Not using item", false));
        this.registerSetting(prioritizeEnemies = new ButtonSetting("Prioritize enemies", false));
        this.registerSetting(requireMouseDown = new ButtonSetting("Require mouse down", false));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
        this.registerSetting(autoBlock = new ButtonSetting("Auto block", false));
        this.registerSetting(humanize = new ButtonSetting("Humanize", false));
        this.registerSetting(killNotification = new ButtonSetting("Kill notification", false));
    }

    @Override
    public String getInfo() {
        if (rotationMode.getInput() == 2) {
            return (int) this.fov.getInput() + fov.getSuffix();
        }
        return rotationModes[(int) rotationMode.getInput()];
    }

    @Override
    public void onEnable() {
        rand = new Random();
        nextClickTime = 0L;
    }

    @Override
    public void onDisable() {
        hitMap.clear();
        setTarget(null);
        nextClickTime = 0L;
        lastAttackedEntityId = -1;
        monsterClassCache.clear();
        nonMonsterClassCache.clear();
        stopBlocking();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onClientRotation(ClientRotationEvent e) {
        if (!basicCondition() || !settingCondition()) {
            setTarget(null);
            return;
        }

        boolean bedAuraHasPriority = ModuleManager.bedAura != null
                && ModuleManager.bedAura.shouldOverrideMouseOver()
                && !ModuleManager.bedAura.isPrioritizingKillAura();
        if (bedAuraHasPriority) {
            setTarget(null);
            return;
        }

        handleTarget();
        if (target == null) {
            return;
        }
        targetDistance = RotationUtils.distanceFromEyeToClosestOnAABB(target);
        attackingEntity = targetDistance <= attackRange.getInput() ? target : null;
        if (rotationMode.getInput() == 0) {
            double aimRangeVal = aimRange.getInput();
            if (targetDistance <= aimRangeVal) {
                int speedVal = (int) speed.getInput();
                boolean useBackup = !aimThroughBlocks.isToggled() || !aimThroughEntities.isToggled();
                float[] rot = humanize.isToggled()
                        ? RotationHelper.get().getHumanizedRotationsToTarget(target, e, speedVal, 100, 100, 0f, useBackup, aimRangeVal, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled(), true)
                        : RotationHelper.get().getRotationsToTarget(target, e, speedVal, 100, 100, 0f, useBackup, aimRangeVal, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled(), true);
                if (rot != null) {
                    e.yaw = rot[0];
                    e.pitch = rot[1];
                }
            }
        }
    }

    @Override
    public void onUpdate() {
        if (mc.thePlayer != null) {
            int ticks = mc.thePlayer.ticksExisted;
            if ((ticks % 6000 == 0 || hitMap.size() > 64) && !hitMap.isEmpty()) {
                hitMap.keySet().removeIf(id -> {
                    Entity e = mc.theWorld.getEntityByID(id);
                    return e == null || e.isDead;
                });
            }
        }

        if (killNotification.isToggled() && lastAttackedEntityId != -1) {
            long now = System.currentTimeMillis();
            Entity attacked = mc.theWorld.getEntityByID(lastAttackedEntityId);
            if (attacked instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) attacked;
                float hp = living.getHealth();
                boolean dead = hp <= 0 || living.deathTime > 0 || living.isDead;
                if (!dead && lastAttackedHealth > 0 && hp <= 1.0f && hp < lastAttackedHealth * 0.25f) {
                    dead = true;
                }
                lastAttackedHealth = hp;
                if (dead) {
                    if (now - lastKillNotifyMs > 2000L) {
                        lastKillNotifyMs = now;
                        Notifications.notify(living.getName(), "Target neutralized.", true);
                    }
                    lastAttackedEntityId = -1;
                    lastAttackedHealth = -1f;
                }
            } else if (attacked == null && now - lastAttackTimeMs < 5000L) {
                if (now - lastKillNotifyMs > 2000L) {
                    lastKillNotifyMs = now;
                    Notifications.notify("Target", "Target neutralized.", true);
                }
                lastAttackedEntityId = -1;
                lastAttackedHealth = -1f;
            } else if (attacked == null) {
                lastAttackedEntityId = -1;
                lastAttackedHealth = -1f;
            }
        }

        if (rotationMode.getInput() == 1 && target != null) {
            double aimRangeVal = aimRange.getInput();
            if (targetDistance <= aimRangeVal) {
                int speedVal = (int) speed.getInput();
                boolean useBackup = !aimThroughBlocks.isToggled() || !aimThroughEntities.isToggled();
                float[] rot = humanize.isToggled()
                        ? RotationHelper.get().getHumanizedRotationsToTarget(target, speedVal, 100, 100, 0f, useBackup, aimRangeVal, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled(), true)
                        : RotationHelper.get().getRotationsToTarget(target, speedVal, 100, 100, 0f, useBackup, aimRangeVal, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled(), true);
                if (rot != null) {
                    mc.thePlayer.rotationYaw = rot[0];
                    mc.thePlayer.rotationPitch = rot[1];
                }
            }
        }

        if (target != null && targetDistance <= attackRange.getInput()) {
            attackingEntity = target;
        } else {
            attackingEntity = null;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck() || target == null || targetDistance > swingRange.getInput()
                || !basicCondition() || !settingCondition()
                || (notUsingItem.isToggled() && !autoBlock.isToggled() && mc.thePlayer.isUsingItem())) {
            nextClickTime = 0L;
            if (autoBlock.isToggled() && blocking) stopBlocking();
            return;
        }

        int key = mc.gameSettings.keyBindAttack.getKeyCode();
        long now = System.currentTimeMillis();
        if (nextClickTime == 0) {
            nextClickTime = now;
        }
        int clicks = 0;
        while (nextClickTime <= now) {
            clicks++;
            nextClickTime += nextDelay();
        }

        if (clicks > 0 && autoBlock.isToggled() && blocking) {
            stopBlocking();
        }

        for (int i = 0; i < clicks; i++) {
            KeyBinding.onTick(key);
        }
        if (clicks > 0 && target != null && targetDistance <= attackRange.getInput()) {
            lastAttackedEntityId = target.getEntityId();
            lastAttackTimeMs = System.currentTimeMillis();
            lastAttackedHealth = target.getHealth();
        }

        if (autoBlock.isToggled() && target != null && targetDistance <= swingRange.getInput() && Utils.holdingSword()) {
            startBlocking();
        }
    }

    @SubscribeEvent
    public void onSetAttackTarget(LivingSetAttackTargetEvent e) {
        if (e.entity != null && !hostileMobs.contains(e.entity)) {
            if (!(e.target instanceof EntityPlayer) || !e.target.getName().equals(mc.thePlayer.getName())) {
                return;
            }
            if (Utils.getBedwarsStatus() == 2 && e.entity instanceof EntityPigZombie) {
                return;
            }
            hostileMobs.add(e.entity);
            hostileMobIds.add(e.entity.getEntityId());
        }
        if (e.target == null && hostileMobs.contains(e.entity)) {
            hostileMobs.remove(e.entity);
            hostileMobIds.remove(e.entity.getEntityId());
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            hitMap.clear();
            hostileMobs.clear();
            hostileMobIds.clear();
            golems.clear();
            monsterClassCache.clear();
            nonMonsterClassCache.clear();
            lastAttackedEntityId = -1;
        }
    }

    private void setTarget(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            target = null;
            attackingEntity = null;
            targetDistance = Double.MAX_VALUE;
            nextClickTime = 0L;
        } else {
            target = (EntityLivingBase) entity;
        }
    }

    private void handleTarget() {
        double maxRange = Math.max(attackRange.getInput(), aimRange.getInput());
        float fovValue = (float) fov.getInput();
        int maxTargets = Math.max(8, (int) targets.getInput() * 3);

        List<Candidate> rawCandidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            Candidate candidate = getCandidateTarget(entity, maxRange, fovValue);
            if (candidate == null) continue;
            rawCandidates.add(candidate);
        }

        rawCandidates.sort(Comparator.comparingDouble(c -> c.distance));
        if (rawCandidates.size() > maxTargets) {
            rawCandidates = rawCandidates.subList(0, maxTargets);
        }

        List<KillAuraTarget> candidates = new ArrayList<>();
        for (Candidate candidate : rawCandidates) {
            KillAuraTarget auraTarget = buildKillAuraTarget(candidate.entity, candidate.distance, maxRange);
            if (auraTarget != null) {
                candidates.add(auraTarget);
            }
        }

        if (prioritizeEnemies.isToggled()) {
            List<KillAuraTarget> enemies = new ArrayList<>();
            for (KillAuraTarget candidate : candidates) {
                if (candidate.isEnemy) {
                    enemies.add(candidate);
                }
            }
            if (!enemies.isEmpty()) {
                candidates = enemies;
            }
        }

        candidates.sort(getTargetComparator().thenComparingDouble(c -> c.distance));

        double attackRangeValue = attackRange.getInput();
        List<KillAuraTarget> attackTargets = new ArrayList<>();
        for (KillAuraTarget candidate : candidates) {
            if (candidate.distance <= attackRangeValue) {
                attackTargets.add(candidate);
            }
        }

        if (!attackTargets.isEmpty()) {
            KillAuraTarget selectedAttackTarget = selectAttackTarget(attackTargets);
            if (selectedAttackTarget != null) {
                setTarget(selectedAttackTarget.entity);
                return;
            }
            return;
        }

        if (!candidates.isEmpty()) {
            setTarget(candidates.get(0).entity);
            return;
        }

        setTarget(null);
    }

    private Candidate getCandidateTarget(Entity entity, double maxRange, float fovValue) {
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer || entity.isDead) {
            return null;
        }
        double dx = entity.posX - mc.thePlayer.posX;
        double dy = entity.posY - mc.thePlayer.posY;
        double dz = entity.posZ - mc.thePlayer.posZ;
        double quickDistSq = dx * dx + dy * dy + dz * dz;
        double rangeWithSlop = maxRange + 2.0;
        if (quickDistSq > rangeWithSlop * rangeWithSlop) {
            return null;
        }

        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            if (player.deathTime != 0) {
                return null;
            }
            if (TargetFilter.shouldFilter(entity)) {
                return null;
            }
        } else if (entity instanceof EntityCreature && attackMobs.isToggled()) {
            EntityCreature creature = (EntityCreature) entity;
            if (creature.tasks == null || creature.isAIDisabled() || creature.deathTime != 0) {
                return null;
            }

            int classId = System.identityHashCode(entity.getClass());
            if (nonMonsterClassCache.contains(classId)) {
                return null;
            }
            if (!monsterClassCache.contains(classId)) {
                String canonicalName = entity.getClass().getCanonicalName();
                if (canonicalName == null || !canonicalName.startsWith("net.minecraft.entity.monster.")) {
                    nonMonsterClassCache.add(classId);
                    return null;
                }
                monsterClassCache.add(classId);
            }
        } else {
            return null;
        }

        if (entity.isInvisible() && !targetInvis.isToggled()) {
            return null;
        }

        if (fovValue != 360.0f && !Utils.inFov(fovValue, entity)) {
            return null;
        }

        double distance = RotationUtils.distanceFromEyeToClosestOnAABB(entity);
        if (distance > maxRange) {
            return null;
        }

        return new Candidate((EntityLivingBase) entity, distance);
    }

    private KillAuraTarget buildKillAuraTarget(EntityLivingBase entity, double distanceToBoundingBox, double maxRange) {
        if (entity instanceof EntityCreature && attackMobs.isToggled() && !isHostile((EntityCreature) entity)) {
            return null;
        }

        double multipointH = 100;
        double multipointV = 100;
        if (!RotationUtils.hasValidAimPoint(entity, multipointH, multipointV, maxRange, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled(), true)) {
            return null;
        }

        boolean isEnemyPlayer = entity instanceof EntityPlayer && Utils.isEnemy((EntityPlayer) entity);
        return new KillAuraTarget(
                entity,
                distanceToBoundingBox,
                entity.getHealth(),
                entity.hurtTime,
                RotationUtils.distanceFromYaw(entity, false),
                entity.getEntityId(),
                isEnemyPlayer
        );
    }

    private Comparator<KillAuraTarget> getTargetComparator() {
        switch ((int) sortMode.getInput()) {
            case 1:
                return Comparator.comparingDouble(target -> target.health);
            case 2:
                return Comparator.comparingInt(target -> target.hurttime);
            case 3:
                return Comparator.comparingDouble(target -> target.yawDelta);
            case 0:
            default:
                return Comparator.comparingDouble(target -> target.distance);
        }
    }

    private KillAuraTarget selectAttackTarget(List<KillAuraTarget> attackTargets) {
        int ticksExisted = mc.thePlayer.ticksExisted;
        int switchDelayTicks = (int) (switchDelay.getInput() / 50);
        long noHitTicks = (long) Math.min(attackTargets.size(), targets.getInput()) * switchDelayTicks;

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(candidate.entityId);
            if (firstHitTick == null || ticksExisted - firstHitTick >= switchDelayTicks) {
                continue;
            }
            return candidate;
        }

        for (KillAuraTarget candidate : attackTargets) {
            Integer firstHitTick = hitMap.get(candidate.entityId);
            if (firstHitTick == null || ticksExisted >= firstHitTick + noHitTicks) {
                hitMap.put(candidate.entityId, ticksExisted);
                return candidate;
            }
        }
        KillAuraTarget mostRecent = null;
        int mostRecentTick = Integer.MIN_VALUE;
        for (KillAuraTarget candidate : attackTargets) {
            Integer hitTick = hitMap.get(candidate.entityId);
            if (hitTick != null && hitTick > mostRecentTick) {
                mostRecentTick = hitTick;
                mostRecent = candidate;
            }
        }
        if (mostRecent != null) {
            return mostRecent;
        }

        KillAuraTarget fallback = attackTargets.get(0);
        hitMap.put(fallback.entityId, ticksExisted);
        return fallback;
    }

    private boolean isHostile(EntityCreature entityCreature) {
        if (entityCreature instanceof EntitySilverfish) {
            String teamColor = Utils.getFirstColorCode(entityCreature.getCustomNameTag());
            String teamColorSelf = Utils.getFirstColorCode(mc.thePlayer.getDisplayName().getFormattedText());
            return teamColor.isEmpty() || (!teamColorSelf.equals(teamColor) && !Utils.isTeammate(entityCreature));
        } else if (entityCreature instanceof EntityIronGolem) {
            if (Utils.getBedwarsStatus() != 2) {
                return true;
            }
            if (!golems.containsKey(entityCreature.getEntityId())) {
                double nearestDistance = -1;
                EntityArmorStand nearestArmorStand = null;
                for (Entity entity : mc.theWorld.loadedEntityList) {
                    if (!(entity instanceof EntityArmorStand)) {
                        continue;
                    }
                    String stripped = Utils.stripString(entity.getDisplayName().getFormattedText());
                    if (stripped.contains("[") && stripped.endsWith("]")) {
                        double distanceSq = entity.getDistanceSq(entityCreature.posX, entityCreature.posY, entityCreature.posZ);
                        if (distanceSq < nearestDistance || nearestDistance == -1) {
                            nearestDistance = distanceSq;
                            nearestArmorStand = (EntityArmorStand) entity;
                        }
                    }
                }
                if (nearestArmorStand != null) {
                    String teamColor = Utils.getFirstColorCode(nearestArmorStand.getDisplayName().getFormattedText());
                    String teamColorSelf = Utils.getFirstColorCode(mc.thePlayer.getDisplayName().getFormattedText());
                    boolean isTeam = !teamColor.isEmpty() && (teamColorSelf.equals(teamColor) || Utils.isTeammate(nearestArmorStand));
                    golems.put(entityCreature.getEntityId(), isTeam);
                    return !isTeam;
                }
                return !ModuleManager.bedwars.spawnedMobs.contains(entityCreature.getEntityId());
            } else {
                return !golems.getOrDefault(entityCreature.getEntityId(), false);
            }
        } else if (entityCreature instanceof EntityPigZombie && Utils.getBedwarsStatus() != 2) {
            return false;
        }
        return hostileMobIds.contains(entityCreature.getEntityId());
    }

    private boolean basicCondition() {
        if (!Utils.nullCheck()) {
            return false;
        }
        return !mc.thePlayer.isDead;
    }

    private boolean settingCondition() {
        if (requireMouseDown.isToggled() && !Mouse.isButtonDown(0)) {
            return false;
        } else if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            return false;
        } else if (disableWhileMining.isToggled() && Utils.isMining()) {
            return false;
        } else if (disableInInventory.isToggled() && mc.currentScreen != null) {
            return false;
        }
        return true;
    }

    private long nextDelay() {
        int cps = Math.max(1, (int) targetCPS.getInput());
        int baseDelay = 1000 / cps;
        int finalDelay = baseDelay + (rand.nextInt(21) - 10);
        return Math.max(33, Math.min(180, finalDelay));
    }

    public SliderSetting getAttackRangeSetting() {
        return attackRange;
    }

    public SliderSetting getSwingRangeSetting() {
        return swingRange;
    }

    public SliderSetting getAimRangeSetting() {
        return aimRange;
    }

    public boolean shouldOverrideMouseOver() {
        return this.isEnabled()
                && Utils.nullCheck()
                && attackingEntity != null
                && target == attackingEntity
                && basicCondition()
                && targetDistance <= swingRange.getInput();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }

        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = attackRange.getInput();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = attackingEntity.getCollisionBorderSize();
        AxisAlignedBB bb = attackingEntity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = bb.calculateIntercept(eyes, rayEnd);
        boolean inside = bb.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        if (!aimThroughBlocks.isToggled()) {
            MovingObjectPosition blockHit = RotationUtils.rayTraceBlocksIgnoringOpenFenceGates(eyes, hitVec, false, false, true);
            if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                return;
            }
        }
        if (!aimThroughEntities.isToggled() && RotationUtils.isPathBlockedByEntity(eyes, hitVec, attackingEntity)) {
            return;
        }

        mc.objectMouseOver = new MovingObjectPosition(attackingEntity, hitVec);
        mc.pointedEntity = attackingEntity;

        EntityRenderer renderer = mc.entityRenderer;
        AccessorBridge.EntityRenderer_setPointedEntity(renderer, attackingEntity);
    }

    private static final class Candidate {
        final EntityLivingBase entity;
        final double distance;

        Candidate(EntityLivingBase entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }

    private void startBlocking() {
        if (blocking || !Utils.holdingSword()) return;
        mc.thePlayer.sendQueue.addToSendQueue(
                new net.minecraft.network.play.client.C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        blocking = true;
    }

    private void stopBlocking() {
        if (!blocking) return;
        mc.thePlayer.sendQueue.addToSendQueue(
                new net.minecraft.network.play.client.C07PacketPlayerDigging(
                        net.minecraft.network.play.client.C07PacketPlayerDigging.Action.RELEASE_USE_ITEM,
                        net.minecraft.util.BlockPos.ORIGIN, net.minecraft.util.EnumFacing.DOWN));
        blocking = false;
    }

    static class KillAuraTarget {
        final EntityLivingBase entity;
        final double distance;
        final float health;
        final int hurttime;
        final double yawDelta;
        final int entityId;
        final boolean isEnemy;

        public KillAuraTarget(EntityLivingBase entity, double distance, float health, int hurttime, double yawDelta, int entityId, boolean isEnemy) {
            this.entity = entity;
            this.distance = distance;
            this.health = health;
            this.hurttime = hurttime;
            this.yawDelta = yawDelta;
            this.entityId = entityId;
            this.isEnemy = isEnemy;
        }
    }
}
