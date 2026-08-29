package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ReflectionUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.EntityFireball;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class AntiFireball extends Module {
    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting targetCPS;
    private final SliderSetting rotationSpeed;
    private final ButtonSetting onlyIncoming;
    private final ButtonSetting onlyOnGround;
    private final ButtonSetting sneakWhileActive;

    public EntityFireball fireball;
    private final HashSet<Entity> fireballs = new HashSet<>();

    private long nextClickTimeNanos;
    private EntityFireball scheduledFireball;
    private final Random rand = new Random();

    public AntiFireball() {
        super("Anti Fireball", category.player);
        this.registerSetting(targetCPS = new SliderSetting("Target CPS", 16.0, 1.0, 20.0, 0.5));
        this.registerSetting(fov = new SliderSetting("FOV", 360.0, 30.0, 360.0, 4.0));
        this.registerSetting(range = new SliderSetting("Range", 8.0, 3.0, 15.0, 0.5));
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", 25, 1, 30, 1));
        this.registerSetting(onlyIncoming = new ButtonSetting("Only incoming", true));
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
        double predX = fireball.posX + fireball.motionX;
        double predY = fireball.posY + fireball.motionY;
        double predZ = fireball.posZ + fireball.motionZ;

        float border = fireball.getCollisionBorderSize();
        AxisAlignedBB box = fireball.getEntityBoundingBox().expand(border, border, border);
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);

        Vec3 closest = getNearestPointOnBox(eye, box.offset(fireball.motionX, fireball.motionY, fireball.motionZ));
        return RotationUtils.getRotationsToPoint(closest.xCoord, closest.yCoord, closest.zCoord, baseYaw, basePitch);
    }

    private Vec3 getNearestPointOnBox(Vec3 point, AxisAlignedBB box) {
        double x = Math.max(box.minX, Math.min(point.xCoord, box.maxX));
        double y = Math.max(box.minY, Math.min(point.yCoord, box.maxY));
        double z = Math.max(box.minZ, Math.min(point.zCoord, box.maxZ));
        return new Vec3(x, y, z);
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

    private boolean isIncoming(EntityFireball fb) {
        double currentDist = mc.thePlayer.getDistanceSqToEntity(fb);
        double nextX = fb.posX + fb.motionX;
        double nextY = fb.posY + fb.motionY;
        double nextZ = fb.posZ + fb.motionZ;
        double nextDist = mc.thePlayer.getDistanceSq(nextX, nextY, nextZ);
        return nextDist < currentDist;
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
            if (onlyIncoming.isToggled() && !isIncoming((EntityFireball) entity)) continue;
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