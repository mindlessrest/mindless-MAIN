package mindless.module.impl.combat;

import mindless.event.PostMotionEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.ReceivePacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class JumpReset extends Module {
    private static final long COMBAT_VELOCITY_WINDOW_MS = 250L;
    private static final double MIN_HORIZONTAL_VELOCITY = 0.08D;
    private static final double MIN_VERTICAL_VELOCITY = 0.15D;
    private SliderSetting chance;
    private ButtonSetting requireMouseDown;
    private ButtonSetting requireMovingForward;
    private ButtonSetting requireAim;

    private boolean setJump;
    private boolean ignoreNext;
    private int lastHurtTime;
    private double lastFallDistance;
    private long lastCombatVelocityAtMs;

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

    @Override
    public void onEnable() {
        setJump = false;
        ignoreNext = false;
        lastHurtTime = 0;
        lastFallDistance = 0.0D;
        lastCombatVelocityAtMs = 0L;
    }

    @Override
    public void onDisable() {
        if (setJump && !Utils.jumpDown()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), false);
        }
        setJump = false;
        lastCombatVelocityAtMs = 0L;
    }

    @SubscribeEvent
    public void onReceiveVelocity(ReceivePacketEvent event) {
        if (!Utils.nullCheck() || !(event.getPacket() instanceof S12PacketEntityVelocity)) {
            return;
        }
        S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
        if (velocity.getEntityID() != mc.thePlayer.getEntityId()) {
            return;
        }
        double x = velocity.getMotionX() / 8000.0D;
        double y = velocity.getMotionY() / 8000.0D;
        double z = velocity.getMotionZ() / 8000.0D;
        if (Math.sqrt(x * x + z * z) < MIN_HORIZONTAL_VELOCITY && y < MIN_VERTICAL_VELOCITY) {
            return;
        }
        lastCombatVelocityAtMs = System.currentTimeMillis();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck()) {
            return;
        }
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

            if (!ignoreNext && !mc.thePlayer.isBurning() && onGround && aimingAt && forward && mouseDown
                    && randomization && !hasBadEffect() && fov && hasRecentCombatVelocity()) {
                KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), setJump = true);
                lastCombatVelocityAtMs = 0L;
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

    private boolean hasRecentCombatVelocity() {
        return lastCombatVelocityAtMs != 0L
                && System.currentTimeMillis() - lastCombatVelocityAtMs <= COMBAT_VELOCITY_WINDOW_MS;
    }

}
