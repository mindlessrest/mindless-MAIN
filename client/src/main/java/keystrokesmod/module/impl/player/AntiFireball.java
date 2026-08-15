package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.ReflectionUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class AntiFireball extends Module {
    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting targetCPS;
    private final SliderSetting rotationSpeed;
    private final ButtonSetting onlyOnGround;
    private final ButtonSetting sneakWhileActive;

    public EntityFireball fireball;
    private final HashSet<Entity> fireballs = new HashSet<>();

    private long nextClickTimeNanos;
    private EntityFireball scheduledFireball;
    private final Random rand = new Random();

    public AntiFireball() {
        super("Anti Fireball", category.player);
        this.registerSetting(targetCPS = new SliderSetting("Target CPS", 12.0, 1.0, 20.0, 0.5));
        this.registerSetting(fov = new SliderSetting("FOV", 360.0, 30.0, 360.0, 4.0));
        this.registerSetting(range = new SliderSetting("Range", 8.0, 3.0, 15.0, 0.5));
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 15, 1, 30, 1));
        this.registerSetting(onlyOnGround = new ButtonSetting("Only on ground", false));
        this.registerSetting(sneakWhileActive = new ButtonSetting("Sneak while active", false));
    }

    @Override
    public void onDisable() {
        fireballs.clear();
        fireball = null;
        resetClickScheduler();
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            fireball = null;
            resetClickScheduler();
            return;
        }

        EntityFireball selectedFireball = getFireball();
        if (selectedFireball == null || selectedFireball != fireball) {
            resetClickScheduler();
        }
        fireball = selectedFireball;
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            resetClickScheduler();
            return;
        }
        if (!isFireballValid(fireball)) {
            resetClickScheduler();
            return;
        }
        if (sneakWhileActive.isToggled() && !mc.thePlayer.isRiding()
                && !mc.thePlayer.capabilities.isFlying) {
            e.setSneak(true);
        }
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            resetClickScheduler();
            return;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            resetClickScheduler();
            return;
        }
        if (onlyOnGround.isToggled() && !mc.thePlayer.onGround) {
            resetClickScheduler();
            return;
        }
        if (!isFireballValid(fireball)) {
            resetClickScheduler();
            return;
        }

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];

        float[] target = computeAimRotations(baseYaw, basePitch);
        if (target == null) {
            resetClickScheduler();
            return;
        }

        float[] smooth = RotationUtils.smoothRotation(baseYaw, basePitch, target[0], target[1],
                (int) rotationSpeed.getInput());

        e.setYaw(smooth[0]);
        e.setPitch(smooth[1]);
    }

    private float[] computeAimRotations(float baseYaw, float basePitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        float borderSize = fireball.getCollisionBorderSize();
        AxisAlignedBB fireballBox = fireball.getEntityBoundingBox().expand(borderSize, borderSize, borderSize);
        double reach = mc.playerController.getBlockReachDistance();

        List<EntityPlayer> players = new ArrayList<>();
        for (EntityPlayer player : mc.theWorld.playerEntities) {
            if (player == mc.thePlayer || player.deathTime != 0) continue;
            if (Utils.isFriended(player)) continue;
            if (Utils.isTeammate(player)) continue;
            if (AntiBot.isBot(player)) continue;
            players.add(player);
        }
        players.sort(Comparator.comparingDouble(p -> mc.thePlayer.getDistanceSqToEntity(p)));

        for (EntityPlayer player : players) {
            float[] rot = RotationUtils.getRotationsToPoint(player.posX, player.posY, player.posZ, baseYaw, basePitch);
            if (hitsFireballBox(eye, rot[0], rot[1], fireballBox, reach)) {
                return rot;
            }
        }

        double topY = fireballBox.maxY;
        double centerX = (fireballBox.minX + fireballBox.maxX) / 2.0;
        double centerZ = (fireballBox.minZ + fireballBox.maxZ) / 2.0;
        return RotationUtils.getRotationsToPoint(centerX, topY, centerZ, baseYaw, basePitch);
    }

    private boolean hitsFireballBox(Vec3 eye, float yaw, float pitch, AxisAlignedBB box, double range) {
        Vec3 look = Utils.getLookVec(yaw, pitch);
        Vec3 end = eye.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        MovingObjectPosition intercept = box.calculateIntercept(eye, end);
        return intercept != null;
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            resetClickScheduler();
            return;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            resetClickScheduler();
            return;
        }
        if (onlyOnGround.isToggled() && !mc.thePlayer.onGround) {
            resetClickScheduler();
            return;
        }
        if (!isFireballValid(fireball)) {
            resetClickScheduler();
            return;
        }

        MovingObjectPosition mop = mc.objectMouseOver;
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY || mop.entityHit != fireball) {
            resetClickScheduler();
            return;
        }

        if (scheduledFireball != fireball) {
            resetClickScheduler();
            scheduledFireball = fireball;
        }

        long now = System.nanoTime();
        if (nextClickTimeNanos == 0L) {
            nextClickTimeNanos = now;
        }

        int key = mc.gameSettings.keyBindAttack.getKeyCode();
        if (now - nextClickTimeNanos >= 0L) {
            if (!isFireballValid(fireball)
                    || mc.objectMouseOver == null
                    || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.ENTITY
                    || mc.objectMouseOver.entityHit != fireball) {
                resetClickScheduler();
                return;
            }
            KeyBinding.onTick(key);
            ReflectionUtils.setButton(0, true);
            nextClickTimeNanos = now + nextDelayNanos();
        }
    }

    private boolean isFireballValid(EntityFireball target) {
        return target != null
                && mc.theWorld != null
                && !target.isDead
                && mc.theWorld.getEntityByID(target.getEntityId()) == target
                && mc.theWorld.loadedEntityList.contains(target);
    }

    private void resetClickScheduler() {
        nextClickTimeNanos = 0L;
        scheduledFireball = null;
    }

    private EntityFireball getFireball() {
        double rangeSq = range.getInput() * range.getInput();
        float fovVal = (float) fov.getInput();

        if (onlyOnGround.isToggled() && !mc.thePlayer.onGround) return null;

        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (!(entity instanceof EntityFireball)) continue;
            if (!fireballs.contains(entity)) continue;
            if (!isFireballValid((EntityFireball) entity)) continue;
            if (mc.thePlayer.getDistanceSqToEntity(entity) > rangeSq) continue;
            if (fovVal != 360.0f && !Utils.inFov(fovVal, entity)) continue;
            return (EntityFireball) entity;
        }
        return null;
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent e) {
        if (!Utils.nullCheck()) {
            resetClickScheduler();
            return;
        }
        if (e.entity == mc.thePlayer) {
            fireballs.clear();
            fireball = null;
            resetClickScheduler();
        } else if (e.entity instanceof EntityFireball && mc.thePlayer.getDistanceSqToEntity(e.entity) > 16.0) {
            fireballs.add(e.entity);
        }
    }

    private long nextDelayNanos() {
        int target = Math.max(1, (int) targetCPS.getInput());
        int baseDelay = 1000 / target;
        int variation = rand.nextInt(Math.max(1, baseDelay / 3 + 1)) - baseDelay / 6;
        return TimeUnit.MILLISECONDS.toNanos(Math.max(33, baseDelay + variation));
    }
}