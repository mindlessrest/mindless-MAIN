package mindless.module.impl.combat;

import mindless.event.PostMotionEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class JumpReset extends Module {
    private SliderSetting chance;
    private ButtonSetting requireMouseDown;
    private ButtonSetting requireMovingForward;
    private ButtonSetting requireAim;

    private boolean setJump;
    private boolean ignoreNext;
    private int lastHurtTime;
    private double lastFallDistance;

    public JumpReset() {
        super("Jump Reset", "Jumps as you are hit to cut knockback.", category.combat);
        this.liteModule = true;
        this.registerSetting(chance = new SliderSetting("Chance", "%", 80, 0, 100, 1));
        this.registerSetting(requireMouseDown = new ButtonSetting("Require mouse down", false));
        this.registerSetting(requireMovingForward = new ButtonSetting("Require moving forward", true));
        this.registerSetting(requireAim = new ButtonSetting("Require aim", true));
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        return (int) chance.getInput() == 100 ? "" : ((int) chance.getInput()) + "%";
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        int hurtTime = mc.thePlayer.hurtTime;
        boolean onGround = mc.thePlayer.onGround;

        if (onGround && lastFallDistance > 3 && !mc.thePlayer.capabilities.allowFlying) {
            ignoreNext = true;
        }

        if (hurtTime > lastHurtTime) {
            boolean mouseDown = mc.gameSettings.keyBindAttack.isKeyDown() || !requireMouseDown.isToggled();
            boolean aimingAt = !requireAim.isToggled() || checkAim();
            boolean forward = mc.gameSettings.keyBindForward.isKeyDown() || !requireMovingForward.isToggled();
            boolean randomization = (int) chance.getInput() == 100 || Utils.randomizeDouble(0, 100) < chance.getInput();
            boolean fov = Utils.inFov(Utils.getDirection(), 330, RotationUtils.deltaAngle(mc.thePlayer.motionX, mc.thePlayer.motionZ));

            if (!ignoreNext && !mc.thePlayer.isBurning() && onGround && aimingAt && forward && mouseDown && randomization && !hasBadEffect() && fov) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), setJump = true);
            }

            ignoreNext = false;
        }

        lastHurtTime = hurtTime;
        lastFallDistance = mc.thePlayer.fallDistance;
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent e) {
        if (setJump && !Utils.jumpDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), setJump = false);
        }
    }

    private boolean hasBadEffect() {
        PotionEffect jump = mc.thePlayer.getActivePotionEffect(Potion.jump);
        PotionEffect poison = mc.thePlayer.getActivePotionEffect(Potion.poison);
        PotionEffect wither = mc.thePlayer.getActivePotionEffect(Potion.wither);
        return jump != null || poison != null || wither != null;
    }

    private boolean checkAim() {
        MovingObjectPosition result = mc.objectMouseOver;
        return result != null && result.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY && result.entityHit instanceof EntityPlayer;
    }

}
