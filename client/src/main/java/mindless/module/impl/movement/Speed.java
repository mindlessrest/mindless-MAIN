package mindless.module.impl.movement;

import mindless.event.PostPlayerInputEvent;
import mindless.event.PreMotionEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.runtime.AccessorBridge;
import mindless.utility.PacketUtils;
import mindless.utility.Utils;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Speed extends Module {
    private static final String[] MODES = {
            "Strafe", "NCP", "Vulcan 2.8.6", "Vulcan 2.8.8", "Vulcan Ground",
            "Verus B3882", "BlocksMC", "Matrix 7", "Intave 14", "Intave 14 Fast",
            "Spartan", "Spartan Fast Fall", "Hylex Low Hop", "Hylex Ground"
    };
    private static final int STRAFE = 0;
    private static final int NCP = 1;
    private static final int VULCAN_286 = 2;
    private static final int VULCAN_288 = 3;
    private static final int VULCAN_GROUND = 4;
    private static final int VERUS = 5;
    private static final int BLOCKS_MC = 6;
    private static final int MATRIX_7 = 7;
    private static final int INTAVE_14 = 8;
    private static final int INTAVE_14_FAST = 9;
    private static final int SPARTAN = 10;
    private static final int SPARTAN_FAST = 11;
    private static final int HYLEX_LOW_HOP = 12;
    private static final int HYLEX_GROUND = 13;

    public SliderSetting speed;
    public static SliderSetting multiplier;
    public static SliderSetting speedSetting;
    private final ButtonSetting liquidDisable;
    private final ButtonSetting sneakDisable;
    private final ButtonSetting jumpMoving;
    private final ButtonSetting ncpTimer;
    private final ButtonSetting ncpLowHop;
    private final ButtonSetting ncpAirStrafe;
    private final ButtonSetting ncpPullDown;
    private final SliderSetting ncpPullTick;
    public ButtonSetting rotateYawOption;
    public GroupSetting damageBoostGroup;
    public ButtonSetting damageBoost;
    public ButtonSetting damageBoostRequireKey;
    public KeySetting damageBoostKey;
    public boolean hopping;
    public boolean lowhop;
    public boolean didMove;
    public boolean setRotation;
    private int airTicks;
    private int groundTicks;
    private int blocksState;
    private int blocksFlagDelay;
    private int lastMode = -1;
    private boolean timerApplied;

    public Speed() {
        super("Speed", "Applies server-specific movement profiles.", category.movement, 0);
        this.registerSetting(speed = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(multiplier = new SliderSetting("Strafe speed", " b/t", 0.33D, 0.2D, 1.0D, 0.005D));
        this.registerSetting(speedSetting = new SliderSetting("NCP boost", "x", 1.0D, 0.1D, 3.0D, 0.05D));
        this.registerSetting(liquidDisable = new ButtonSetting("Disable in liquid", true));
        this.registerSetting(sneakDisable = new ButtonSetting("Disable while sneaking", true));
        this.registerSetting(jumpMoving = new ButtonSetting("Only jump when moving", true));
        this.registerSetting(ncpTimer = new ButtonSetting("NCP timer", true));
        this.registerSetting(ncpLowHop = new ButtonSetting("NCP low hop", true));
        this.registerSetting(ncpAirStrafe = new ButtonSetting("NCP air strafe", true));
        this.registerSetting(ncpPullDown = new ButtonSetting("NCP pull down", true));
        this.registerSetting(ncpPullTick = new SliderSetting("NCP pull tick", 5, 1, 9, 1));
        this.registerSetting(rotateYawOption = new ButtonSetting("Rotate yaw", false));
        this.registerSetting(damageBoostGroup = new GroupSetting("Damage boost"));
        this.registerSetting(damageBoost = new ButtonSetting(damageBoostGroup, "Enable", true));
        this.registerSetting(damageBoostRequireKey = new ButtonSetting(damageBoostGroup, "Require key", false));
        this.registerSetting(damageBoostKey = new KeySetting(damageBoostGroup, "Enable key", 51));
    }

    @Override
    public void guiUpdate() {
        int mode = mode();
        multiplier.setVisible(mode == STRAFE, this);
        speedSetting.setVisible(mode == NCP, this);
        ncpTimer.setVisible(mode == NCP, this);
        ncpLowHop.setVisible(mode == NCP, this);
        ncpAirStrafe.setVisible(mode == NCP, this);
        ncpPullDown.setVisible(mode == NCP, this);
        ncpPullTick.setVisible(mode == NCP && ncpPullDown.isToggled(), this);
        rotateYawOption.setVisible(false, this);
        damageBoostGroup.setVisible(mode == NCP, this);
        damageBoostKey.setVisible(mode == NCP && damageBoostRequireKey.isToggled(), this);
    }

    @Override
    public String getInfo() {
        return MODES[mode()];
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetTimer();
        hopping = false;
        airTicks = 0;
        groundTicks = 0;
        blocksState = 0;
    }

    @SubscribeEvent
    public void onPostPlayerInput(PostPlayerInputEvent event) {
        if (!Utils.nullCheck() || !hopping || !mc.thePlayer.onGround) return;
        mc.thePlayer.movementInput.jump = false;
    }

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck()) return;
        resetTimer();
        updateTicks();
        int mode = mode();
        if (mode != lastMode) {
            airTicks = mc.thePlayer.onGround ? 0 : 1;
            groundTicks = mc.thePlayer.onGround ? 1 : 0;
            blocksState = 0;
            blocksFlagDelay = 0;
            hopping = false;
            lastMode = mode;
        }
        if (!canRun()) {
            hopping = false;
            return;
        }

        switch (mode) {
            case STRAFE:
                tickStrafe();
                break;
            case NCP:
                tickNcp();
                break;
            case VULCAN_286:
                tickVulcan286();
                break;
            case VULCAN_288:
                tickVulcan288();
                break;
            case VULCAN_GROUND:
                tickVulcanGround(event);
                break;
            case VERUS:
                tickVerus();
                break;
            case BLOCKS_MC:
                tickBlocksMc();
                break;
            case MATRIX_7:
                tickMatrix();
                break;
            case INTAVE_14:
                tickIntave(false);
                break;
            case INTAVE_14_FAST:
                tickIntave(true);
                break;
            case SPARTAN:
                tickSpartan(false);
                break;
            case SPARTAN_FAST:
                tickSpartan(true);
                break;
            case HYLEX_LOW_HOP:
                tickHylexLowHop();
                break;
            case HYLEX_GROUND:
                tickHylexGround();
                break;
            default:
                break;
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mode() == BLOCKS_MC && event.getPacket() instanceof S08PacketPlayerPosLook) {
            blocksFlagDelay = 20;
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (mode() != VULCAN_288 || !Utils.nullCheck() || mc.thePlayer.motionY >= 0.0D
                || !(event.getPacket() instanceof C03PacketPlayer)) return;
        C03PacketPlayer packet = (C03PacketPlayer) event.getPacket();
        PacketUtils.sendPacketNoEvent(copyMovement(packet, true));
        event.setCanceled(true);
    }

    private void tickStrafe() {
        jumpIfGround();
        mc.thePlayer.setSprinting(true);
        Utils.setSpeed(Math.max(Utils.getHorizontalSpeed(), multiplier.getInput()));
        hopping = true;
        didMove = true;
    }

    private void tickNcp() {
        int potion = Math.max(0, Utils.getSpeedAmplifier() - 1);
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            if (ncpLowHop.isToggled()) mc.thePlayer.motionY = 0.4D;
            Utils.setSpeed(Math.max(Utils.getHorizontalSpeed(), 0.281D + 0.2D * potion));
        } else {
            mc.thePlayer.motionX *= 1.0D + 0.00718D * speedSetting.getInput();
            mc.thePlayer.motionZ *= 1.0D + 0.00718D * speedSetting.getInput();
            if (ncpAirStrafe.isToggled()) {
                strafe(Math.max(Utils.getHorizontalSpeed(), 0.2D + 0.2D * potion), 0.7D);
            }
            if (ncpPullDown.isToggled() && airTicks == (int) ncpPullTick.getInput()) {
                mc.thePlayer.motionY -= 0.1523351824467155D;
            }
            if (mc.thePlayer.hurtTime >= 5 && mc.thePlayer.motionY >= 0.0D) {
                mc.thePlayer.motionY -= 0.1D;
            }
        }
        if (ncpTimer.isToggled()) applyTimer(1.08F);
        if (damageBoost.isToggled() && mc.thePlayer.hurtTime > 0) {
            Utils.setSpeed(Math.max(Utils.getHorizontalSpeed(), 0.5D));
        }
        hopping = true;
        didMove = true;
    }

    private void tickVulcan286() {
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            hopping = true;
            return;
        }
        int potion = Math.max(0, Utils.getSpeedAmplifier() - 1);
        boolean sideways = mc.thePlayer.moveStrafing != 0.0F;
        if (airTicks == 1) {
            Utils.setSpeed(sideways ? 0.3345D : 0.3355D * (1.0D + potion * 0.3819D));
        } else if (airTicks == 2 && mc.thePlayer.isSprinting()) {
            Utils.setSpeed(sideways ? 0.3235D : 0.3284D * (1.0D + potion * 0.355D));
        } else if (airTicks == 4) {
            mc.thePlayer.motionY = -0.376D;
        } else if (airTicks == 6 && Utils.getHorizontalSpeed() > 0.298D) {
            Utils.setSpeed(0.298D);
        }
        didMove = true;
    }

    private void tickVulcan288() {
        boolean hasSpeed = Utils.getSpeedAmplifier() > 1;
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            Utils.setSpeed(hasSpeed ? 0.771D : 0.5D);
            hopping = true;
        } else if (airTicks == 1) {
            Utils.setSpeed(hasSpeed ? 0.605D : 0.31D);
        } else if (airTicks == 2) {
            Utils.setSpeed(hasSpeed ? 0.57D : 0.29D);
            mc.thePlayer.motionY = hasSpeed ? -0.5D : -0.37D;
        } else if (airTicks == 3) {
            Utils.setSpeed(hasSpeed ? 0.595D : 0.27D);
        } else if (airTicks == 4) {
            Utils.setSpeed(hasSpeed ? 0.595D : 0.28D);
        }
        if (!mc.thePlayer.onGround && mc.thePlayer.fallDistance > 0.0F && hasSpeed) {
            mc.thePlayer.motionX *= 1.055D;
            mc.thePlayer.motionZ *= 1.055D;
        }
        didMove = true;
    }

    private void tickVulcanGround(PreMotionEvent event) {
        if (!groundCollision() || Utils.jumpDown()) return;
        double moveSpeed = Utils.getSpeedAmplifier() > 1
                ? 0.59D : mc.thePlayer.moveStrafing != 0.0F ? 0.41D : 0.42D;
        Utils.setSpeed(moveSpeed);
        mc.thePlayer.motionY = 0.005D;
        event.setPosY(event.getPosY() + 0.005D);
        didMove = true;
    }

    private void tickVerus() {
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            mc.thePlayer.motionX *= 1.1D;
            mc.thePlayer.motionZ *= 1.1D;
            hopping = true;
        }
        strafe(Utils.getHorizontalSpeed(), 1.0D);
        if (mc.thePlayer.ticksExisted % 102 == 0) applyTimer(2.0F);
        didMove = true;
    }

    private void tickBlocksMc() {
        if (mc.thePlayer.onGround) blocksState = 1;
        double moveSpeed = 0.06D + (mc.thePlayer.onGround ? 0.12D : 0.21D) + mc.thePlayer.motionY / 20.0D;
        if (Utils.getSpeedAmplifier() == 2) moveSpeed += 0.1D;
        if (blocksFlagDelay > 0) {
            moveSpeed -= 0.007D * blocksFlagDelay;
            blocksFlagDelay--;
        }
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            hopping = true;
        } else if (blocksState != 0) {
            if (airTicks == 4) mc.thePlayer.motionY = -0.09800000190734863D;
            Utils.setSpeed(Math.max(0.0D, moveSpeed));
        }
        didMove = true;
    }

    private void tickMatrix() {
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            mc.thePlayer.motionY = 0.419652D;
            strafe(Utils.getHorizontalSpeed(), 1.0D);
            hopping = true;
        } else if (Utils.getHorizontalSpeed() < 0.2D) {
            strafe(Utils.getHorizontalSpeed(), 1.0D);
        }
        didMove = true;
    }

    private void tickIntave(boolean fast) {
        if (mc.thePlayer.onGround) {
            jumpIfGround();
            strafe(Utils.getHorizontalSpeed(), 0.27D);
            hopping = true;
        } else {
            if (fast) {
                double factor = airTicks == 1 ? 1.04D : airTicks >= 2 && airTicks <= 4 ? 1.02D : 1.0D;
                mc.thePlayer.motionX *= factor;
                mc.thePlayer.motionZ *= factor;
                applyTimer(1.002F);
            } else {
                if (airTicks == 11) strafe(Utils.getHorizontalSpeed(), 0.27D);
                if (mc.thePlayer.motionY > 0.003D && mc.thePlayer.isSprinting()) {
                    mc.thePlayer.motionX *= 1.00075D;
                    mc.thePlayer.motionZ *= 1.00075D;
                }
            }
        }
        didMove = true;
    }

    private void tickSpartan(boolean fastFall) {
        if (!Utils.isBindDown(mc.gameSettings.keyBindForward)) return;
        boolean leatherBoots = false;
        ItemStack boots = mc.thePlayer.getCurrentArmor(0);
        if (boots != null) leatherBoots = boots.getItem() == Items.leather_boots;
        if (mc.thePlayer.onGround) {
            double factor = fastFall ? leatherBoots ? 1.2D : 1.05D : leatherBoots ? 1.8D : 1.3D;
            mc.thePlayer.motionX *= factor;
            mc.thePlayer.motionZ *= factor;
            jumpIfGround();
            hopping = true;
        } else if (fastFall && airTicks == 1) {
            applyTimer(0.5F);
            PacketUtils.sendPacketNoEvent(new C03PacketPlayer(true));
            mc.thePlayer.motionY = -0.0784D;
        }
        didMove = true;
    }

    private void tickHylexLowHop() {
        if (mc.thePlayer.onGround) {
            if (Utils.getHorizontalSpeed() < 0.32D) multiplyHorizontal(1.1D);
            jumpIfGround();
            mc.thePlayer.motionY = 0.33D;
            hopping = true;
        } else {
            if (airTicks == 9 && Utils.getHorizontalSpeed() < 0.29D) multiplyHorizontal(1.007D);
            if (airTicks == 1 && Utils.getHorizontalSpeed() < 0.2D) multiplyHorizontal(1.01D);
            if (mc.thePlayer.motionY > 0.0D && airTicks <= 2 && Utils.getHorizontalSpeed() < 0.2D) {
                multiplyHorizontal(1.02D);
            }
        }
        didMove = true;
    }

    private void tickHylexGround() {
        if (!mc.thePlayer.onGround) return;
        if (groundTicks <= 5 || mc.thePlayer.hurtTime > 0 || Utils.getSpeedAmplifier() > 1) return;
        multiplyHorizontal(mc.thePlayer.moveStrafing == 0.0F ? 1.2174D : 1.214D);
        didMove = true;
    }

    private boolean canRun() {
        if (!Utils.isMoving()) return false;
        if (ModuleManager.longJump != null && ModuleManager.longJump.function) return false;
        if (liquidDisable.isToggled() && (mc.thePlayer.isInWater() || mc.thePlayer.isInLava())) return false;
        return !sneakDisable.isToggled() || !mc.thePlayer.isSneaking();
    }

    private void jumpIfGround() {
        if (mc.thePlayer.onGround && (!jumpMoving.isToggled() || Utils.isMoving())) {
            mc.thePlayer.jump();
        }
    }

    private void updateTicks() {
        if (mc.thePlayer.onGround) {
            airTicks = 0;
            groundTicks++;
        } else {
            airTicks++;
            groundTicks = 0;
        }
    }

    private void strafe(double targetSpeed, double strength) {
        double oldX = mc.thePlayer.motionX;
        double oldZ = mc.thePlayer.motionZ;
        Utils.setSpeed(targetSpeed);
        mc.thePlayer.motionX = oldX + (mc.thePlayer.motionX - oldX) * strength;
        mc.thePlayer.motionZ = oldZ + (mc.thePlayer.motionZ - oldZ) * strength;
    }

    private void multiplyHorizontal(double factor) {
        mc.thePlayer.motionX *= factor;
        mc.thePlayer.motionZ *= factor;
    }

    private boolean groundCollision() {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(0.0D, -0.005D, 0.0D);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    private void applyTimer(float value) {
        AccessorBridge.Minecraft_getTimer(mc).timerSpeed = value;
        timerApplied = true;
    }

    private void resetTimer() {
        if (!timerApplied || mc == null) return;
        AccessorBridge.Minecraft_getTimer(mc).timerSpeed = 1.0F;
        timerApplied = false;
    }

    private void resetState() {
        hopping = false;
        lowhop = false;
        didMove = false;
        setRotation = false;
        airTicks = 0;
        groundTicks = 0;
        blocksState = 0;
        blocksFlagDelay = 0;
        lastMode = -1;
        timerApplied = false;
    }

    private int mode() {
        return Math.max(0, Math.min(MODES.length - 1, (int) speed.getInput()));
    }

    private C03PacketPlayer copyMovement(C03PacketPlayer packet, boolean onGround) {
        if (packet.isMoving() && packet.getRotating()) {
            return new C03PacketPlayer.C06PacketPlayerPosLook(packet.getPositionX(), packet.getPositionY(),
                    packet.getPositionZ(), packet.getYaw(), packet.getPitch(), onGround);
        }
        if (packet.isMoving()) {
            return new C03PacketPlayer.C04PacketPlayerPosition(
                    packet.getPositionX(), packet.getPositionY(), packet.getPositionZ(), onGround);
        }
        if (packet.getRotating()) {
            return new C03PacketPlayer.C05PacketPlayerLook(packet.getYaw(), packet.getPitch(), onGround);
        }
        return new C03PacketPlayer(onGround);
    }
}
