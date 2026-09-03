package mindless.module.impl.movement;

import mindless.module.Module;
import mindless.module.impl.combat.KillAura;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.Utils;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.MathHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class TargetStrafe extends Module {
    private final SliderSetting radius;
    private final SliderSetting speed;
    private final ButtonSetting onlyWhileJumping;
    private final ButtonSetting reverseOnWall;

    private int direction = 1;

    public TargetStrafe() {
        super("Target Strafe", "Circles your target on its own.", category.movement);
        this.registerSetting(radius = new SliderSetting("Radius", 2.0, 0.5, 4.0, 0.1));
        this.registerSetting(speed = new SliderSetting("Speed", "x", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(onlyWhileJumping = new ButtonSetting("Only in air", false));
        this.registerSetting(reverseOnWall = new ButtonSetting("Reverse on wall", true));
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        if (!Utils.nullCheck()) return;
        EntityLivingBase target = KillAura.target;
        if (target == null || target.isDead) return;
        if (onlyWhileJumping.isToggled() && mc.thePlayer.onGround) return;

        if (reverseOnWall.isToggled() && mc.thePlayer.isCollidedHorizontally) {
            direction = -direction;
        }

        double distToTarget = mc.thePlayer.getDistanceToEntity(target);
        double rad = radius.getInput();

        float yawToTarget = getYawToEntity(target);
        float strafeAngle = yawToTarget + 90f * direction;

        if (distToTarget < rad * 0.8) {
            strafeAngle = yawToTarget + 135f * direction;
        } else if (distToTarget > rad * 1.5) {
            strafeAngle = yawToTarget + 45f * direction;
        }

        float radians = (float) Math.toRadians(strafeAngle);
        double moveSpeed = Utils.getHorizontalSpeed() * speed.getInput();
        if (moveSpeed < 0.1) moveSpeed = 0.1;

        mc.thePlayer.motionX = -Math.sin(radians) * moveSpeed;
        mc.thePlayer.motionZ = Math.cos(radians) * moveSpeed;
    }

    private float getYawToEntity(EntityLivingBase entity) {
        double dx = entity.posX - mc.thePlayer.posX;
        double dz = entity.posZ - mc.thePlayer.posZ;
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }
}
