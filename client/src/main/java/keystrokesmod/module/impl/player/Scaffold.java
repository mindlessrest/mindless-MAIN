package keystrokesmod.module.impl.player;

import keystrokesmod.event.*;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.*;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.*;

public class Scaffold extends Module {

    // ── Constants ──────────────────────────────────────────────────────────────
    private static final int ROT_NONE      = 0;
    private static final int ROT_DEFAULT   = 1;
    private static final int ROT_BACKWARDS = 2;
    private static final int ROT_SIDEWAYS  = 3;
    private static final int ROT_GODBRIDGE = 4;
    private static final int ROT_SMOOTH    = 5;
    private static final int ROT_HYPIXEL   = 6;
    private static final int ROT_SNAP      = 7;
    private static final int ROT_3FMC      = 8;
    private static final int ROT_SNAP2     = 9;

    private static final double[] PLACE_OFFSETS = {
        0.03125, 0.09375, 0.15625, 0.21875, 0.28125,
        0.34375, 0.40625, 0.46875, 0.53125, 0.59375,
        0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };

    // ── Settings ────────────────────────────────────────────────────────────────
    private final SliderSetting rotationMode;
    private final SliderSetting tellyStartRotMinSpeed;
    private final SliderSetting tellyStartRotMaxSpeed;
    private final SliderSetting tellyNormalRotMinSpeed;
    private final SliderSetting tellyNormalRotMaxSpeed;
    private final ButtonSetting moveFix;
    private final ButtonSetting stopSprint;
    private final SliderSetting groundMotion;
    private final SliderSetting airMotion;
    private final SliderSetting speedMotion;
    private final SliderSetting tower;
    private final ButtonSetting hypixelTower;
    private final ButtonSetting safeMode;
    private final SliderSetting safeModeDelay;
    private final SliderSetting keepY;
    private final ButtonSetting keepYonPress;
    private final ButtonSetting noKeepYonJumpPotion;
    private final ButtonSetting multiPlace;
    private final ButtonSetting safeWalk;
    private final ButtonSetting swing;
    private final ButtonSetting blockCounter;
    private final ButtonSetting eagle;
    private final SliderSetting edgeDistance;
    private final SliderSetting sneakDelay;
    private final SliderSetting blocksPerSneak;
    private final ButtonSetting espOutline;
    private final SliderSetting espColor;

    // ── State ───────────────────────────────────────────────────────────────────
    private int    rotationTick        = 0;
    private int    lastSlot            = -1;
    private int    blockCount          = -1;
    private float  yaw                 = -180f;
    private float  pitch               = 0f;
    private boolean canRotate          = false;
    private int    towerTick           = 0;
    private int    towerDelay          = 0;
    private int    stage               = 0;
    private int    startY              = 256;
    private boolean shouldKeepY        = false;
    private boolean towering           = false;
    private EnumFacing targetFacing    = null;
    private int    safeStuckTicks      = 0;
    private int    safeStuckDelayTicks = 0;
    private double safePrevMotionY     = 0;
    private double savedMotionX, savedMotionY, savedMotionZ;
    private boolean safeStuckActive    = false;
    private boolean snapRotating       = false;
    private boolean placedThisTick     = false;
    private int    threeFmcAirTicks    = 0;
    private int    threeFmcGroundTicks = 0;
    private int    threeFmcPlaceCooldown = 0;
    private float  lastSnapPlaceYaw    = Float.NaN;
    private float  lastSnapPlacePitch  = Float.NaN;
    private boolean eagleSneaking      = false;
    private int    eagleSneakTicks     = 0;
    private long   eagleLastSneakTime  = 0L;
    private int    eagleBlocksPlaced   = 0;
    private final Map<BlockPos, Long> espHighlight = new HashMap<>();

    public Scaffold() {
        super("Scaffold", category.player);
        this.closetModule = true;
        this.registerSetting(rotationMode = new SliderSetting("Rotations", 2,
                new String[]{"None","Default","Backwards","Sideways","Godbridge","Smooth","Hypixel","Snap","3FMC","Snap2"}));
        this.registerSetting(tellyStartRotMinSpeed = new SliderSetting("Telly start min", 90f, 1f, 180f, 1f));
        this.registerSetting(tellyStartRotMaxSpeed = new SliderSetting("Telly start max", 95f, 1f, 180f, 1f));
        this.registerSetting(tellyNormalRotMinSpeed = new SliderSetting("Telly normal min", 30f, 1f, 180f, 1f));
        this.registerSetting(tellyNormalRotMaxSpeed = new SliderSetting("Telly normal max", 35f, 1f, 180f, 1f));
        this.registerSetting(moveFix   = new ButtonSetting("Move fix (silent)", true));
        this.registerSetting(stopSprint = new ButtonSetting("Stop sprint", false));
        this.registerSetting(groundMotion = new SliderSetting("Ground motion", "%", 100, 0, 100, 5));
        this.registerSetting(airMotion    = new SliderSetting("Air motion",    "%", 100, 0, 100, 5));
        this.registerSetting(speedMotion  = new SliderSetting("Speed motion",  "%", 100, 0, 100, 5));
        this.registerSetting(tower = new SliderSetting("Tower", 0,
                new String[]{"None","Vanilla","Extra","Telly"}));
        this.registerSetting(hypixelTower  = new ButtonSetting("Hypixel tower",  false));
        this.registerSetting(safeMode      = new ButtonSetting("Safe",           false));
        this.registerSetting(safeModeDelay = new SliderSetting("Safe delay ticks", 1, 1, 3, 1));
        this.registerSetting(keepY = new SliderSetting("Keep Y", 0,
                new String[]{"None","Vanilla","Extra","Telly","ExtraTelly"}));
        this.registerSetting(keepYonPress        = new ButtonSetting("Keep Y on item use", false));
        this.registerSetting(noKeepYonJumpPotion = new ButtonSetting("No keep Y on jump potion", false));
        this.registerSetting(multiPlace = new ButtonSetting("Multi-place", true));
        this.registerSetting(safeWalk   = new ButtonSetting("Safe walk",   true));
        this.registerSetting(swing      = new ButtonSetting("Swing",       true));
        this.registerSetting(blockCounter = new ButtonSetting("Block counter", true));
        this.registerSetting(eagle          = new ButtonSetting("Eagle",        false));
        this.registerSetting(edgeDistance   = new SliderSetting("Edge distance", 0.13, 0.0, 0.5, 0.01));
        this.registerSetting(sneakDelay     = new SliderSetting("Sneak delay",   "ms", 80, 0, 500, 10));
        this.registerSetting(blocksPerSneak = new SliderSetting("Blocks per sneak", 1, 1, 5, 1));
        this.registerSetting(espOutline = new ButtonSetting("ESP outline", false));
        this.registerSetting(espColor   = new SliderSetting("ESP color", 0, new String[]{"Default","HUD"}));
    }

    @Override
    public void guiUpdate() {
        boolean isTelly = (int)keepY.getInput() == 3 || (int)keepY.getInput() == 4;
        tellyStartRotMinSpeed.setVisible(isTelly, this);
        tellyStartRotMaxSpeed.setVisible(isTelly, this);
        tellyNormalRotMinSpeed.setVisible(isTelly, this);
        tellyNormalRotMaxSpeed.setVisible(isTelly, this);
        boolean isTellyTower = (int)tower.getInput() == 3;
        hypixelTower.setVisible(isTellyTower, this);
        safeMode.setVisible(isTellyTower, this);
        safeModeDelay.setVisible(isTellyTower && safeMode.isToggled(), this);
        keepYonPress.setVisible((int)keepY.getInput() != 0, this);
        noKeepYonJumpPotion.setVisible((int)keepY.getInput() != 0, this);
        edgeDistance.setVisible(eagle.isToggled(), this);
        sneakDelay.setVisible(eagle.isToggled(), this);
        blocksPerSneak.setVisible(eagle.isToggled(), this);
        espColor.setVisible(espOutline.isToggled(), this);
    }

    @Override
    public void onEnable() {
        if (mc.thePlayer != null) lastSlot = mc.thePlayer.inventory.currentItem;
        else lastSlot = -1;
        blockCount        = -1;
        rotationTick      = 3;
        yaw               = -180f;
        pitch             = 0f;
        canRotate         = false;
        towerTick         = 0;
        towerDelay        = 0;
        towering          = false;
        safeStuckTicks    = 0;
        safeStuckDelayTicks = 0;
        safePrevMotionY   = 0;
        safeStuckActive   = false;
        eagleSneaking     = false;
        eagleSneakTicks   = 0;
        eagleBlocksPlaced = 0;
        eagleLastSneakTime = 0L;
        snapRotating      = false;
        threeFmcAirTicks  = 0;
        threeFmcGroundTicks = 0;
        threeFmcPlaceCooldown = 0;
        lastSnapPlaceYaw  = Float.NaN;
        lastSnapPlacePitch = Float.NaN;
        espHighlight.clear();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && lastSlot != -1)
            mc.thePlayer.inventory.currentItem = lastSlot;
        if (safeStuckActive && mc.thePlayer != null) {
            mc.thePlayer.motionX = savedMotionX;
            mc.thePlayer.motionY = savedMotionY;
            mc.thePlayer.motionZ = savedMotionZ;
        }
        safeStuckTicks      = 0;
        safeStuckDelayTicks = 0;
        safeStuckActive     = false;
        eagleSneaking       = false;
        eagleSneakTicks     = 0;
        threeFmcAirTicks    = 0;
        threeFmcGroundTicks = 0;
        threeFmcPlaceCooldown = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Main event — runs rotation computation + block placement
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;

        placedThisTick = false;
        updateThreeFmcState();
        quietThreeFmcMovement();

        // safe-stuck timer
        if (safeStuckDelayTicks > 0) {
            safeStuckDelayTicks--;
            if (safeStuckDelayTicks <= 0) safeStuckTicks = 1;
        }
        if (safeStuckTicks > 0) {
            if (!safeStuckActive) {
                savedMotionX = mc.thePlayer.motionX;
                savedMotionY = mc.thePlayer.motionY;
                savedMotionZ = mc.thePlayer.motionZ;
                safeStuckActive = true;
            }
            mc.thePlayer.motionX = 0;
            mc.thePlayer.motionY = 0;
            mc.thePlayer.motionZ = 0;
        } else if (safeStuckActive) {
            mc.thePlayer.motionX = savedMotionX;
            mc.thePlayer.motionY = savedMotionY;
            mc.thePlayer.motionZ = savedMotionZ;
            safeStuckActive = false;
        }

        if (rotationTick > 0) rotationTick--;
        updateEagle();

        // Hypixel tower: press jump while stationary mid-air → pull down
        if (hypixelTower.isToggled() && mc.thePlayer.motionY <= 0
                && Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) <= 0.02
                && mc.thePlayer.motionY >= -0.09
                && !isAnyMovementKeyDown()
                && mc.gameSettings.keyBindJump.isKeyDown()) {
            mc.thePlayer.motionY = -0.38;
        }

        // Ground stage management
        if (mc.thePlayer.onGround) {
            if (stage > 0) stage--;
            if (stage < 0) stage++;
            if (stage == 0 && (int)keepY.getInput() != 0
                    && (!keepYonPress.isToggled() || mc.thePlayer.isUsingItem())
                    && (!noKeepYonJumpPotion.isToggled() || !mc.thePlayer.isPotionActive(Potion.jump))
                    && !mc.gameSettings.keyBindJump.isKeyDown()) {
                stage = 1;
            }
            startY = shouldKeepY ? startY : MathHelper.floor_double(mc.thePlayer.posY);
            shouldKeepY = false;
            towering = false;
        }

        float baseYaw   = e.yaw   != null ? e.yaw   : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];

        // select block slot
        ItemStack heldStack = mc.thePlayer.getHeldItem();
        int count = isBlock(heldStack) ? heldStack.stackSize : 0;
        blockCount = Math.min(blockCount, count);
        if (blockCount <= 0) {
            int slot = mc.thePlayer.inventory.currentItem;
            if (blockCount == 0) slot--;
            for (int i = slot; i > slot - 9; i--) {
                int hotbar = ((i % 9) + 9) % 9;
                ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbar);
                if (isBlock(candidate)) {
                    mc.thePlayer.inventory.currentItem = hotbar;
                    blockCount = candidate.stackSize;
                    break;
                }
            }
        }

        float currentYaw = getCurrentYaw(baseYaw);
        float yawDiffTo180 = wrapAngleDiff(currentYaw - 180f, baseYaw);
        float diagonalYaw  = isDiagonal(currentYaw)
                ? yawDiffTo180
                : wrapAngleDiff(currentYaw - 135f * ((currentYaw + 180f) % 90f < 45f ? 1f : -1f), baseYaw);

        boolean snapMode    = (int)rotationMode.getInput() == ROT_SNAP || (int)rotationMode.getInput() == ROT_SNAP2;
        boolean threeFmcMode = isThreeFmcMode();
        boolean threeFmcTelly = isThreeFmcTellyMode();
        snapRotating = false;

        // compute target rotation
        if (!canRotate) {
            switch ((int)rotationMode.getInput()) {
                case ROT_DEFAULT:
                    if (yaw == -180f && pitch == 0f) { yaw = quantize(diagonalYaw); pitch = quantize(85f); }
                    else yaw = quantize(diagonalYaw);
                    break;
                case ROT_BACKWARDS:
                    if (yaw == -180f && pitch == 0f) { yaw = quantize(yawDiffTo180); pitch = quantize(85f); }
                    else yaw = quantize(yawDiffTo180);
                    break;
                case ROT_SIDEWAYS:
                    if (yaw == -180f && pitch == 0f) { yaw = quantize(diagonalYaw); pitch = quantize(85f); }
                    else yaw = quantize(diagonalYaw);
                    break;
                case ROT_GODBRIDGE: {
                    float rounded = Math.round(currentYaw / 45f) * 45f;
                    yaw = quantize(rounded);
                    if (pitch == 0f || !canRotate) pitch = quantize(79.3f);
                    break;
                }
                case ROT_SMOOTH:
                    if (yaw == -180f && pitch == 0f) { yaw = quantize(diagonalYaw); pitch = quantize(85f); }
                    else {
                        float target = isDiagonal(currentYaw) ? diagonalYaw : yawDiffTo180;
                        float yawDiff   = MathHelper.wrapAngleTo180_float(target - yaw);
                        float pitchDiff = MathHelper.wrapAngleTo180_float(85f - pitch);
                        float yt = rotationTick >= 2
                                ? randFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                                : randFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
                        float pt = rotationTick >= 2
                                ? randFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                                : randFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
                        yaw   = quantize(yaw   + clampAngle(yawDiff,   yt));
                        pitch = quantize(pitch + clampAngle(pitchDiff, pt));
                    }
                    break;
                case ROT_HYPIXEL:
                    if (yaw == -180f && pitch == 0f) { yaw = quantize(diagonalYaw); pitch = quantize(85f); }
                    else yaw = quantize(diagonalYaw);
                    break;
                case ROT_SNAP:
                case ROT_SNAP2:
                    yaw   = quantize(yawDiffTo180);
                    pitch = quantize(85f);
                    break;
                case ROT_3FMC:
                    if (yaw == -180f && pitch == 0f) {
                        yaw   = quantize(baseYaw);
                        pitch = quantize(basePitch);
                    }
                    break;
            }
        }

        BlockData blockData = getBlockData();
        Vec3 hitVec = null;

        if (blockData != null) {
            double[] xs = PLACE_OFFSETS, ys = PLACE_OFFSETS, zs = PLACE_OFFSETS;
            switch (blockData.facing) {
                case NORTH: zs = new double[]{0.0};   break;
                case EAST:  xs = new double[]{1.0};   break;
                case SOUTH: zs = new double[]{1.0};   break;
                case WEST:  xs = new double[]{0.0};   break;
                case DOWN:  ys = new double[]{0.0};   break;
                case UP:    ys = new double[]{1.0};   break;
            }
            float bestYaw = -180f, bestPitch = 0f, bestDiff = 0f;
            for (double dx : xs) {
                for (double dy : ys) {
                    for (double dz : zs) {
                        float bYaw = wrapAngleDiff(yaw, baseYaw);
                        float[] rots = RotationUtils.getRotationsToPoint(
                                blockData.pos.getX() + dx,
                                blockData.pos.getY() + dy,
                                blockData.pos.getZ() + dz,
                                bYaw, pitch);
                        MovingObjectPosition mop = RotationUtils.rayTraceCustom(
                                mc.playerController.getBlockReachDistance(), rots[0], rots[1]);
                        if (mop != null
                                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                                && mop.getBlockPos().equals(blockData.pos)
                                && mop.sideHit == blockData.facing) {
                            float diff = Math.abs(rots[0] - bYaw) + Math.abs(rots[1] - pitch);
                            if (bestYaw == -180f || diff < bestDiff) {
                                bestYaw = rots[0]; bestPitch = rots[1];
                                bestDiff = diff; hitVec = mop.hitVec;
                            }
                        }
                    }
                }
            }
            if (bestYaw != -180f || bestPitch != 0f) {
                yaw = bestYaw; pitch = bestPitch; canRotate = true;
            } else if (threeFmcMode) {
                canRotate = false;
            }
        }

        boolean towerRotating = towering || isTowering();
        boolean snapCanPlace  = true;

        if (snapMode && !towerRotating && blockData != null) {
            MovingObjectPosition curMop = RotationUtils.rayTraceCustom(
                    mc.playerController.getBlockReachDistance(), baseYaw, basePitch);
            boolean curHit = curMop != null
                    && curMop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && curMop.getBlockPos().equals(blockData.pos)
                    && curMop.sideHit == blockData.facing;
            if (curHit) {
                float[] snap = getSnapRotation(blockData, baseYaw, basePitch);
                if (snap == null) { snapCanPlace = false; hitVec = null; }
                else {
                    yaw = snap[0]; pitch = snap[1]; canRotate = true;
                    MovingObjectPosition sm = RotationUtils.rayTraceCustom(
                            mc.playerController.getBlockReachDistance(), yaw, pitch);
                    hitVec = sm != null ? sm.hitVec : curMop.hitVec;
                    snapRotating = true;
                    int delay = (int)rotationMode.getInput() == ROT_SNAP2 ? 0 : 1;
                    if (rotationTick > delay) rotationTick = delay;
                }
            } else if (hitVec != null && canRotate) {
                float[] snap = getSnapRotation(blockData, yaw, pitch);
                if (snap == null) { snapCanPlace = false; hitVec = null; }
                else {
                    yaw = snap[0]; pitch = snap[1];
                    MovingObjectPosition sm = RotationUtils.rayTraceCustom(
                            mc.playerController.getBlockReachDistance(), yaw, pitch);
                    if (sm != null) hitVec = sm.hitVec;
                    snapRotating = true;
                    int delay = (int)rotationMode.getInput() == ROT_SNAP2 ? 0 : 1;
                    if (rotationTick > delay) rotationTick = delay;
                }
            }
        }

        // lock yaw to movement direction when forward-walking (non-3FMC, non-snap)
        if (canRotate && isForwardPressed()
                && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - yaw)) < 90f) {
            switch ((int)rotationMode.getInput()) {
                case ROT_BACKWARDS: yaw = quantize(yawDiffTo180); break;
                case ROT_SIDEWAYS:  yaw = quantize(diagonalYaw);  break;
            }
        }

        float placeYaw = yaw, placePitch = pitch;
        if ((int)rotationMode.getInput() != ROT_NONE
                && (!snapMode || snapRotating || towerRotating)) {
            float targetYaw   = yaw;
            float targetPitch = pitch;

            // smooth approach during telly towering
            if ((!threeFmcMode || threeFmcTelly) && towering
                    && (mc.thePlayer.motionY > 0 || mc.thePlayer.posY > startY + 1)) {
                float diff = MathHelper.wrapAngleTo180_float(yaw - baseYaw);
                float tolerance = rotationTick >= 2
                        ? randFloat(tellyStartRotMinSpeed, tellyStartRotMaxSpeed)
                        : randFloat(tellyNormalRotMinSpeed, tellyNormalRotMaxSpeed);
                if (Math.abs(diff) > tolerance) {
                    targetYaw = quantize(baseYaw + clampAngle(diff, tolerance));
                    rotationTick = Math.max(rotationTick, 1);
                }
            }

            if (towerRotating && isTowering()) {
                if (!threeFmcMode || threeFmcTelly) {
                    float delta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - baseYaw);
                    targetYaw   = quantize(baseYaw + delta * randFloat(0.98f, 0.99f));
                    targetPitch = quantize(randFloat(30f, 80f));
                }
                rotationTick = 3;
                towering = true;
            }

            placeYaw   = targetYaw;
            placePitch = targetPitch;

            e.setYaw(targetYaw);
            e.setPitch(targetPitch);

            if (moveFix.isToggled()) {
                RotationHelper.get().setServerRelativeMovementInputs(true);
            }
        }

        // verify 3FMC MOP at final rotation
        if (threeFmcMode && blockData != null && hitVec != null) {
            MovingObjectPosition vm = RotationUtils.rayTraceCustom(
                    mc.playerController.getBlockReachDistance(), placeYaw, placePitch);
            if (vm == null) hitVec = null;
            else hitVec = vm.hitVec;
        }

        // place
        if (blockData != null && hitVec != null && snapCanPlace
                && (rotationTick <= 0 || false /* snapAlreadyLooking — covered above */)) {
            place(blockData.pos, blockData.facing, hitVec);
            if (snapMode) rememberSnapRotation();

            if (multiPlace.isToggled() && !snapMode) {
                for (int i = 0; i < 3; i++) {
                    blockData = getBlockData();
                    if (blockData == null) break;
                    MovingObjectPosition mop2 = RotationUtils.rayTraceCustom(
                            mc.playerController.getBlockReachDistance(), yaw, pitch);
                    if (mop2 != null
                            && mop2.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                            && mop2.getBlockPos().equals(blockData.pos)
                            && mop2.sideHit == blockData.facing) {
                        place(blockData.pos, blockData.facing, mop2.hitVec);
                    } else {
                        Vec3 hv = getClickVec(blockData.pos, blockData.facing);
                        double dx = hv.xCoord - mc.thePlayer.posX;
                        double dy = hv.yCoord - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                        double dz = hv.zCoord - mc.thePlayer.posZ;
                        float[] rots = RotationUtils.getRotationsToPoint(
                                mc.thePlayer.posX + dx,
                                mc.thePlayer.posY + mc.thePlayer.getEyeHeight() + dy,
                                mc.thePlayer.posZ + dz, baseYaw, basePitch);
                        if (Math.abs(rots[0] - yaw) >= 120f || Math.abs(rots[1] - pitch) >= 60f) break;
                        MovingObjectPosition mop3 = RotationUtils.rayTraceCustom(
                                mc.playerController.getBlockReachDistance(), rots[0], rots[1]);
                        if (mop3 == null
                                || mop3.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                                || !mop3.getBlockPos().equals(blockData.pos)
                                || mop3.sideHit != blockData.facing) break;
                        place(blockData.pos, blockData.facing, mop3.hitVec);
                    }
                }
            }
        }

        // extra keep-Y placement
        if (targetFacing != null) {
            if (threeFmcMode) {
                targetFacing = null;
            } else if (rotationTick <= 0 && !placedThisTick) {
                int bx = MathHelper.floor_double(mc.thePlayer.posX);
                int by = MathHelper.floor_double(mc.thePlayer.posY);
                int bz = MathHelper.floor_double(mc.thePlayer.posZ);
                BlockPos below = new BlockPos(bx, by - 1, bz);
                Vec3 hv = getHitVec(below, targetFacing, yaw, pitch);
                place(below, targetFacing, hv);
            }
            targetFacing = null;
        } else if (((int)keepY.getInput() == 2 || (int)keepY.getInput() == 4)
                && stage > 0 && !mc.thePlayer.onGround) {
            int nextY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
            if (nextY <= startY && mc.thePlayer.posY > startY + 1) {
                shouldKeepY = true;
                blockData = getBlockData();
                if (blockData != null && rotationTick <= 0 && !placedThisTick) {
                    MovingObjectPosition mop = RotationUtils.rayTraceCustom(
                            mc.playerController.getBlockReachDistance(), yaw, pitch);
                    if (mop != null
                            && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                            && mop.getBlockPos().equals(blockData.pos)
                            && mop.sideHit == blockData.facing) {
                        place(blockData.pos, blockData.facing, mop.hitVec);
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Strafe event — tower jump + speed scaling
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onStrafe(StrafeEvent e) {
        if (!isEnabled()) return;
        if (safeStuckTicks > 0) { e.setForward(0); e.setStrafe(0); return; }
        if (isThreeFmcMode() && !isThreeFmcTellyMode()) { towerTick = 0; towerDelay = 0; return; }

        if (!mc.thePlayer.isCollidedHorizontally
                && mc.thePlayer.hurtTime <= 5
                && !mc.thePlayer.isPotionActive(Potion.jump)
                && mc.gameSettings.keyBindJump.isKeyDown()
                && isHoldingBlock()) {

            int ys = (int)(mc.thePlayer.posY % 1.0 * 100.0);
            switch ((int)tower.getInput()) {
                case 1: // VANILLA
                    switch (towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) { mc.thePlayer.motionY = -0.0784000015258789; towerTick = 1; } return;
                        case 1:
                            if (ys == 0 && isAirBelow()) {
                                startY = MathHelper.floor_double(mc.thePlayer.posY); towerTick = 2;
                                mc.thePlayer.motionY = 0.42f;
                                if (isForwardPressed()) setSpeed(Utils.getHorizontalSpeed(), (float)Math.toDegrees(Utils.gd()));
                                else { setSpeed(0); e.setForward(0); e.setStrafe(0); }
                            } else towerTick = 0;
                            return;
                        case 2: mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0; towerTick = 3; return;
                        case 3: mc.thePlayer.motionY = 1.0  - mc.thePlayer.posY % 1.0; towerTick = 1; return;
                        default: towerTick = 0;
                    }
                    break;
                case 2: // EXTRA
                    switch (towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) { mc.thePlayer.motionY = -0.0784000015258789; towerTick = 1; } return;
                        case 1:
                            if (ys == 0 && isAirBelow()) {
                                startY = MathHelper.floor_double(mc.thePlayer.posY);
                                if (!isForwardPressed()) {
                                    towerDelay = 2; setSpeed(0); e.setForward(0); e.setStrafe(0);
                                    EnumFacing facing = yawToFacing(MathHelper.wrapAngleTo180_float(yaw - 180f));
                                    double dist = distanceToEdge(facing);
                                    if (dist > 0.1 && mc.thePlayer.onGround) {
                                        Vec3i dir = facing.getDirectionVec();
                                        double offset = Math.min(randDouble(0.2155 - 9e-4, 0.2155 - 1e-4), dist - 0.05);
                                        double jitter = randDouble(0.02, 0.03);
                                        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().offset(
                                                dir.getX() * (offset - jitter), 0, dir.getZ() * (offset - jitter));
                                        if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty()) {
                                            mc.thePlayer.motionY = -0.0784000015258789;
                                            mc.thePlayer.setPosition(
                                                    next.minX + (next.maxX - next.minX) / 2,
                                                    next.minY,
                                                    next.minZ + (next.maxZ - next.minZ) / 2);
                                        }
                                    } else {
                                        towerTick = 2; targetFacing = facing; mc.thePlayer.motionY = 0.42f;
                                    }
                                } else {
                                    towerTick = 2; towerDelay++; mc.thePlayer.motionY = 0.42f;
                                    setSpeed(Utils.getHorizontalSpeed(), (float)Math.toDegrees(Utils.gd()));
                                }
                            } else { towerTick = 0; towerDelay = 0; }
                            return;
                        case 2: mc.thePlayer.motionY -= randDouble(0.00101, 0.00109); towerTick = 3; return;
                        case 3:
                            if (towerDelay >= 4) { towerTick = 4; towerDelay = 0; }
                            else { mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0; towerTick = 1; }
                            return;
                        case 4: towerTick = 5; return;
                        case 5:
                            if (!isAirBelow()) towerTick = 0;
                            else { mc.thePlayer.motionY -= 0.08; mc.thePlayer.motionY *= 0.98f; mc.thePlayer.motionY -= 0.08; mc.thePlayer.motionY *= 0.98f; towerTick = 1; }
                            return;
                        default: towerTick = 0; towerDelay = 0;
                    }
                    break;
                default: towerTick = 0; towerDelay = 0;
            }
        } else {
            towerTick = 0; towerDelay = 0;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Living update — motion scaling, sprint, safe mode
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent e) {
        if (e.entityLiving != mc.thePlayer || !isEnabled()) return;
        if (safeStuckTicks > 0) {
            mc.thePlayer.motionX = 0; mc.thePlayer.motionY = 0; mc.thePlayer.motionZ = 0;
            safeStuckTicks--;
        }
        quietThreeFmcMovement();

        float speed = (isThreeFmcMode() && !isThreeFmcTellyMode()) ? 1f : getMotionScale();
        if (speed != 1f) {
            if (mc.thePlayer.movementInput.moveForward != 0 && mc.thePlayer.movementInput.moveStrafe != 0) {
                float invSqrt2 = 1f / (float)Math.sqrt(2);
                mc.thePlayer.movementInput.moveForward *= invSqrt2;
                mc.thePlayer.movementInput.moveStrafe  *= invSqrt2;
            }
            mc.thePlayer.movementInput.moveForward *= speed;
            mc.thePlayer.movementInput.moveStrafe  *= speed;
        }

        if (shouldStopSprint()) mc.thePlayer.setSprinting(false);

        // safe mode detection (telly tower)
        if (safeMode.isToggled() && (int)tower.getInput() == 3 && mc.gameSettings.keyBindJump.isKeyDown()) {
            float mYaw = getCurrentYaw(mc.thePlayer.rotationYaw);
            if (isDiagonal(mYaw) && !mc.thePlayer.onGround) {
                double motY = mc.thePlayer.motionY;
                if (safePrevMotionY > 0 && motY <= 0) {
                    double xzSpeed = Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ) * 20;
                    if (safeStuckDelayTicks <= 0 && safeStuckTicks <= 0 && xzSpeed >= 4.67) {
                        safeStuckDelayTicks = (int)safeModeDelay.getInput();
                    }
                }
                safePrevMotionY = motY;
            } else {
                safePrevMotionY = mc.thePlayer.motionY;
            }
        } else {
            safePrevMotionY = mc.thePlayer.motionY;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Player input event — safe walk, eagle, keep-Y jump, move fix
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!isEnabled()) return;

        if (safeStuckTicks > 0) {
            e.setForward(0); e.setStrafe(0); e.setJump(false); e.setSneak(false); return;
        }

        quietThreeFmcMovement();

        // move fix (strafe relative to server rotations)
        if (moveFix.isToggled() && RotationUtils.serverRotations[0] != mc.thePlayer.rotationYaw
                && isForwardPressed()) {
            fixStrafe(RotationUtils.serverRotations[0]);
        }

        // keep-Y: force jump while on ground
        if (mc.thePlayer.onGround && stage > 0 && isForwardPressed()) {
            e.setJump(true);
        }

        // eagle sneak
        if (eagleSneaking && !e.isSneak()) {
            e.setSneak(true);
            e.setForward(e.getForward() * 0.3f);
            e.setStrafe(e.getStrafe() * 0.3f);
        }

        // safe walk
        if (safeWalk.isToggled() && mc.thePlayer.onGround && mc.thePlayer.motionY <= 0
                && canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0)) {
            e.setSneak(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Cancel left/right clicks + slot scroll while active
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClickMouse(ClickMouseEvent e) {
        if (isEnabled()) e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickMouse(RightClickMouseEvent e) {
        if (isEnabled()) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onSlotScroll(PreSlotScrollEvent e) {
        if (!isEnabled()) return;
        lastSlot = e.slot;
        e.setCanceled(true);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Block counter HUD
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !isEnabled() || !blockCounter.isToggled()) return;
        if (!Utils.nullCheck()) return;

        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) {
                Block b = ((ItemBlock)stack.getItem()).getBlock();
                if (!BlockUtils.isInteractable(b) && isSolid(b)) count += stack.stackSize;
            }
        }

        ScaledResolution sr = new ScaledResolution(mc);
        String text = count + " block" + (count != 1 ? "s" : "") + " left";
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        int color = count > 0 ? 0xCCFFFFFF : 0xCCFF5555;
        mc.fontRendererObj.drawString(text,
                sr.getScaledWidth() / 2f + mc.fontRendererObj.FONT_HEIGHT * 1.5f,
                sr.getScaledHeight() / 2f - mc.fontRendererObj.FONT_HEIGHT / 2f + 1f,
                color, true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ESP outline (recently placed blocks fade over 750ms)
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!espOutline.isToggled() || mc.theWorld == null || mc.thePlayer == null) return;
        if (espHighlight.isEmpty()) return;

        int rgb = (int)espColor.getInput() == 0 ? 0x00FFFF : 0x5555FF;

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1.5f);
        GL11.glTranslated(-mc.getRenderManager().viewerPosX,
                          -mc.getRenderManager().viewerPosY,
                          -mc.getRenderManager().viewerPosZ);

        Iterator<Map.Entry<BlockPos, Long>> iter = espHighlight.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iter.next();
            long elapsed = System.currentTimeMillis() - entry.getValue();
            if (elapsed > 750L) { iter.remove(); continue; }
            int alpha = (int)(210 - elapsed / 750.0 * 210);
            if (alpha <= 0) { iter.remove(); continue; }
            int color = (rgb & 0xFFFFFF) | (alpha << 24);
            BlockPos p = entry.getKey();
            AxisAlignedBB box = new AxisAlignedBB(p.getX(), p.getY(), p.getZ(),
                                                   p.getX()+1, p.getY()+1, p.getZ()+1);
            float r = ((color >> 16) & 0xFF) / 255f;
            float g = ((color >>  8) & 0xFF) / 255f;
            float b = ( color        & 0xFF) / 255f;
            float a = ((color >> 24) & 0xFF) / 255f;
            GL11.glColor4f(r, g, b, a);
            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glVertex3d(box.minX, box.minY, box.minZ);
            GL11.glVertex3d(box.maxX, box.minY, box.minZ);
            GL11.glVertex3d(box.maxX, box.minY, box.maxZ);
            GL11.glVertex3d(box.minX, box.minY, box.maxZ);
            GL11.glVertex3d(box.minX, box.minY, box.minZ);
            GL11.glEnd();
            GL11.glBegin(GL11.GL_LINE_STRIP);
            GL11.glVertex3d(box.minX, box.maxY, box.minZ);
            GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
            GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
            GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
            GL11.glVertex3d(box.minX, box.maxY, box.minZ);
            GL11.glEnd();
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex3d(box.minX, box.minY, box.minZ); GL11.glVertex3d(box.minX, box.maxY, box.minZ);
            GL11.glVertex3d(box.maxX, box.minY, box.minZ); GL11.glVertex3d(box.maxX, box.maxY, box.minZ);
            GL11.glVertex3d(box.maxX, box.minY, box.maxZ); GL11.glVertex3d(box.maxX, box.maxY, box.maxZ);
            GL11.glVertex3d(box.minX, box.minY, box.maxZ); GL11.glVertex3d(box.minX, box.maxY, box.maxZ);
            GL11.glEnd();
        }

        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safe-mode: drop C03 packets while frozen
    // ═══════════════════════════════════════════════════════════════════════════

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onSendPacket(SendPacketEvent e) {
        if (safeStuckTicks > 0 && e.getPacket() instanceof C03PacketPlayer) {
            e.setCanceled(true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Private helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isThreeFmcMode()      { return (int)rotationMode.getInput() == ROT_3FMC; }
    private boolean isThreeFmcTellyMode() { return isThreeFmcMode() && ((int)keepY.getInput() == 3 || (int)keepY.getInput() == 4); }

    private void updateThreeFmcState() {
        if (!isThreeFmcMode() || mc.thePlayer == null) {
            threeFmcAirTicks = threeFmcGroundTicks = threeFmcPlaceCooldown = 0; return;
        }
        if (mc.thePlayer.onGround) { threeFmcGroundTicks++; threeFmcAirTicks = 0; }
        else { threeFmcAirTicks++; threeFmcGroundTicks = 0; }
        if (threeFmcPlaceCooldown > 0) threeFmcPlaceCooldown--;
    }

    private void quietThreeFmcMovement() {
        if (!isThreeFmcMode() || isThreeFmcTellyMode() || mc.thePlayer == null) return;
        mc.thePlayer.setSprinting(false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
    }

    private boolean canThreeFmcPlaceNow() {
        if (!isThreeFmcMode()) return true;
        if (mc.thePlayer == null || placedThisTick || threeFmcPlaceCooldown > 0) return false;
        if ((!isThreeFmcTellyMode() && mc.thePlayer.isSprinting())
                || mc.thePlayer.isCollidedHorizontally || mc.thePlayer.hurtTime > 0) return false;
        if (mc.thePlayer.onGround)
            return Math.abs(mc.thePlayer.motionY) < 1e-4 && threeFmcGroundTicks > 0;
        return isThreeFmcTellyMode() ? threeFmcAirTicks > 1 : threeFmcAirTicks > 2;
    }

    private boolean shouldStopSprint() {
        if (isThreeFmcMode() && !isThreeFmcTellyMode()) return true;
        if (isTowering()) return false;
        int ky = (int)keepY.getInput();
        boolean inStage = (ky == 1 || ky == 2 || ky == 4);
        return (!inStage || stage <= 0) && stopSprint.isToggled();
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && isForwardPressed() && !Utils.blockAbove()) {
            int ky = (int)keepY.getInput(), tw = (int)tower.getInput();
            return (ky == 3 || ky == 4) && stage > 0 || tw == 3 && mc.gameSettings.keyBindJump.isKeyDown();
        }
        return false;
    }

    private void updateEagle() {
        if (!eagle.isToggled()) { eagleSneaking = false; eagleSneakTicks = 0; return; }
        if (eagleSneakTicks > 0) {
            eagleSneakTicks--;
            if (eagleSneakTicks == 0) eagleSneaking = false;
            return;
        }
        if (shouldSneak()) {
            eagleSneaking = true; eagleSneakTicks = 2;
            eagleLastSneakTime = System.currentTimeMillis();
            eagleBlocksPlaced = 0;
        }
    }

    private boolean shouldSneak() {
        if (!eagle.isToggled() || !mc.thePlayer.onGround) return false;
        if (eagleBlocksPlaced < (int)blocksPerSneak.getInput()) return false;
        if (System.currentTimeMillis() - eagleLastSneakTime < sneakDelay.getInput()) return false;
        return isNearEdge();
    }

    private boolean isNearEdge() {
        if (!mc.thePlayer.onGround) return false;
        double fx = mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        double fz = mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
        double thr = edgeDistance.getInput();
        return Math.min(Math.min(fx, 1 - fx), Math.min(fz, 1 - fz)) <= thr;
    }

    private float getMotionScale() {
        if (!mc.thePlayer.onGround) return (float)airMotion.getInput() / 100f;
        return getSpeedLevel() > 0 ? (float)speedMotion.getInput() / 100f : (float)groundMotion.getInput() / 100f;
    }

    private BlockData getBlockData() {
        int sy = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos target = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                (stage != 0 && !shouldKeepY ? Math.min(sy, startY) : sy) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtils.replaceable(target)) return null;

        List<BlockPos> candidates = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = target.add(x, y, z);
                    if (!BlockUtils.replaceable(pos)
                            && !BlockUtils.isInteractable(BlockUtils.getBlock(pos))
                            && mc.thePlayer.getDistance(pos.getX()+.5, pos.getY()+.5, pos.getZ()+.5)
                                <= mc.playerController.getBlockReachDistance()
                            && (stage == 0 || shouldKeepY || pos.getY() < startY)) {
                        for (EnumFacing face : EnumFacing.VALUES) {
                            if (face != EnumFacing.DOWN && BlockUtils.replaceable(pos.offset(face))) {
                                candidates.add(pos); break;
                            }
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(Comparator.comparingDouble(
                p -> p.distanceSqToCenter(target.getX()+.5, target.getY()+.5, target.getZ()+.5)));
        BlockPos best = candidates.get(0);
        EnumFacing face = getBestFacing(best, target);
        return face == null ? null : new BlockData(best, face);
    }

    private EnumFacing getBestFacing(BlockPos from, BlockPos to) {
        double best = Double.MAX_VALUE;
        EnumFacing result = null;
        for (EnumFacing f : EnumFacing.VALUES) {
            if (f == EnumFacing.DOWN) continue;
            BlockPos off = from.offset(f);
            if (off.getY() > to.getY()) continue;
            double d = off.distanceSqToCenter(to.getX()+.5, to.getY()+.5, to.getZ()+.5);
            if (result == null || d < best || (d == best && f == EnumFacing.UP)) {
                best = d; result = f;
            }
        }
        return result;
    }

    private void place(BlockPos pos, EnumFacing face, Vec3 hitVec) {
        if (!canThreeFmcPlaceNow()) return;
        if (!isHoldingBlock() || blockCount <= 0) return;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                mc.thePlayer.inventory.getCurrentItem(), pos, face, hitVec)) {
            if (mc.playerController.getCurrentGameType() != net.minecraft.world.WorldSettings.GameType.CREATIVE)
                blockCount--;
            placedThisTick = true;
            if (isThreeFmcMode()) threeFmcPlaceCooldown = 1;
            if (espOutline.isToggled()) espHighlight.put(pos.offset(face), System.currentTimeMillis());
            eagleBlocksPlaced++;
            if (swing.isToggled()) mc.thePlayer.swingItem();
            else mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
        }
    }

    private MovingObjectPosition getPlacementMop(BlockData bd, float y, float p) {
        MovingObjectPosition mop = RotationUtils.rayTraceCustom(mc.playerController.getBlockReachDistance(), y, p);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(bd.pos) || mop.sideHit != bd.facing) return null;
        return mop;
    }

    private boolean isDuplicateSnapRotation(float y, float p) {
        return !Float.isNaN(lastSnapPlaceYaw)
                && Math.abs(MathHelper.wrapAngleTo180_float(y - lastSnapPlaceYaw)) < 0.35f;
    }

    private float[] getSnapRotation(BlockData bd, float baseYaw, float basePitch) {
        float bY = quantize(baseYaw), bP = quantize(MathHelper.clamp_float(basePitch, -90f, 90f));
        if (!isDuplicateSnapRotation(bY, bP)) return new float[]{bY, bP};
        for (int i = 0; i < 24; i++) {
            float ys = 0.35f + 0.075f * (i / 2f), ps = 0.025f + 0.01f * (i / 3f);
            float ty = quantize(bY + (i % 2 == 0 ? ys : -ys));
            float tp = quantize(MathHelper.clamp_float(bP + (i % 4 < 2 ? ps : -ps), -90f, 90f));
            if (!isDuplicateSnapRotation(ty, tp) && getPlacementMop(bd, ty, tp) != null)
                return new float[]{ty, tp};
        }
        return null;
    }

    private void rememberSnapRotation() {
        lastSnapPlaceYaw = yaw; lastSnapPlacePitch = pitch;
    }

    // ── Utility helpers ────────────────────────────────────────────────────────

    private float getCurrentYaw(float baseYaw) {
        return adjustYaw(baseYaw,
                mc.thePlayer.movementInput.moveForward,
                mc.thePlayer.movementInput.moveStrafe);
    }

    private boolean isDiagonal(float y) { float a = Math.abs(y % 90f); return a > 20f && a < 70f; }

    private EnumFacing yawToFacing(float y) {
        if (y < -135f || y > 135f) return EnumFacing.NORTH;
        if (y < -45f) return EnumFacing.EAST;
        return y < 45f ? EnumFacing.SOUTH : EnumFacing.WEST;
    }

    private double distanceToEdge(EnumFacing f) {
        switch (f) {
            case NORTH: return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:  return Math.ceil(mc.thePlayer.posX)  - mc.thePlayer.posX;
            case SOUTH: return Math.ceil(mc.thePlayer.posZ)  - mc.thePlayer.posZ;
            default:    return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    private boolean isForwardPressed() {
        return mc.gameSettings.keyBindForward.isKeyDown() != mc.gameSettings.keyBindBack.isKeyDown()
            || mc.gameSettings.keyBindLeft.isKeyDown()   != mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean isAnyMovementKeyDown() {
        return mc.gameSettings.keyBindForward.isKeyDown() || mc.gameSettings.keyBindBack.isKeyDown()
            || mc.gameSettings.keyBindLeft.isKeyDown()    || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean isAirBelow() {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(0, -1, 0);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    private boolean canMove(double mx, double mz, double my) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(mx, my, mz);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    private boolean isHoldingBlock() {
        ItemStack s = mc.thePlayer.getHeldItem();
        if (s == null || s.stackSize < 1 || !(s.getItem() instanceof ItemBlock)) return false;
        Block b = ((ItemBlock)s.getItem()).getBlock();
        return !BlockUtils.isInteractable(b) && isSolid(b);
    }

    private static boolean isBlock(ItemStack s) {
        if (s == null || s.stackSize < 1 || !(s.getItem() instanceof ItemBlock)) return false;
        Block b = ((ItemBlock)s.getItem()).getBlock();
        return !BlockUtils.isInteractable(b) && isSolid(b);
    }

    private static boolean isSolid(Block b) {
        return !(b instanceof BlockStairs || b instanceof BlockSlab
              || b instanceof BlockEndPortalFrame || b instanceof BlockVine
              || b instanceof BlockPumpkin || b instanceof BlockCactus
              || b instanceof BlockBush || b instanceof BlockFalling
              || b instanceof BlockWeb || b instanceof BlockPane
              || b instanceof BlockCarpet || b instanceof BlockSnow
              || b instanceof BlockFence || b instanceof BlockFenceGate
              || b instanceof BlockWall || b instanceof BlockLadder
              || b instanceof BlockTorch || b instanceof BlockRedstoneWire
              || b instanceof BlockRedstoneDiode || b instanceof BlockBasePressurePlate
              || b instanceof BlockTripWire || b instanceof BlockTripWireHook
              || b instanceof BlockRailBase || b instanceof BlockSlime
              || b instanceof BlockTNT || b instanceof BlockAir);
    }

    private static int getSpeedLevel() {
        return mc.thePlayer.isPotionActive(Potion.moveSpeed)
                ? mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1 : 0;
    }

    // rotate movement inputs to match target yaw
    private static void fixStrafe(float targetYaw) {
        float angle = MathHelper.wrapAngleTo180_float(
            adjustYaw(mc.thePlayer.rotationYaw,
                      mc.thePlayer.movementInput.moveForward,
                      mc.thePlayer.movementInput.moveStrafe) - targetYaw + 22.5f);
        switch ((int)((angle + 180f) / 45f) % 8) {
            case 0: mc.thePlayer.movementInput.moveForward=-1; mc.thePlayer.movementInput.moveStrafe= 0; break;
            case 1: mc.thePlayer.movementInput.moveForward=-1; mc.thePlayer.movementInput.moveStrafe= 1; break;
            case 2: mc.thePlayer.movementInput.moveForward= 0; mc.thePlayer.movementInput.moveStrafe= 1; break;
            case 3: mc.thePlayer.movementInput.moveForward= 1; mc.thePlayer.movementInput.moveStrafe= 1; break;
            case 4: mc.thePlayer.movementInput.moveForward= 1; mc.thePlayer.movementInput.moveStrafe= 0; break;
            case 5: mc.thePlayer.movementInput.moveForward= 1; mc.thePlayer.movementInput.moveStrafe=-1; break;
            case 6: mc.thePlayer.movementInput.moveForward= 0; mc.thePlayer.movementInput.moveStrafe=-1; break;
            case 7: mc.thePlayer.movementInput.moveForward=-1; mc.thePlayer.movementInput.moveStrafe=-1; break;
        }
        if (mc.thePlayer.movementInput.sneak) {
            mc.thePlayer.movementInput.moveForward *= 0.3f;
            mc.thePlayer.movementInput.moveStrafe  *= 0.3f;
        }
    }

    // degrees of movement direction, accounting for diagonal
    private static float adjustYaw(float yaw, float fwd, float left) {
        float rad = Utils.ae(yaw, fwd, left);
        return MathHelper.wrapAngleTo180_float((float)Math.toDegrees(rad));
    }

    private static void setSpeed(double speed, float yawDeg) {
        mc.thePlayer.motionX = -Math.sin(Math.toRadians(yawDeg)) * speed;
        mc.thePlayer.motionZ =  Math.cos(Math.toRadians(yawDeg)) * speed;
    }

    private static void setSpeed(double speed) {
        Utils.setSpeed(speed);
    }

    private static float wrapAngleDiff(float angle, float target) {
        return target + MathHelper.wrapAngleTo180_float(angle - target);
    }

    private static float quantize(float angle) {
        return (float)((double)angle - (double)angle % 0.0096f);
    }

    private static float clampAngle(float diff, float max) {
        max = Math.max(0f, Math.min(180f, max));
        return MathHelper.clamp_float(diff, -max, max);
    }

    private static Vec3 getClickVec(BlockPos pos, EnumFacing face) {
        return BlockUtils.getFaceCenter(pos, face);
    }

    private static Vec3 getHitVec(BlockPos pos, EnumFacing face, float yaw, float pitch) {
        MovingObjectPosition mop = RotationUtils.rayTraceCustom(
                mc.playerController.getBlockReachDistance(), yaw, pitch);
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(pos) && mop.sideHit == face)
            return mop.hitVec;
        return getClickVec(pos, face);
    }

    private static float randFloat(double min, double max) {
        return (float)Utils.randomizeDouble(min, max);
    }

    private static float randFloat(SliderSetting min, SliderSetting max) {
        return (float)Utils.randomizeDouble(min.getInput(), max.getInput());
    }

    private static double randDouble(double min, double max) {
        return Utils.randomizeDouble(min, max);
    }

    // ── Inner class ────────────────────────────────────────────────────────────

    private static class BlockData {
        final BlockPos pos;
        final EnumFacing facing;
        BlockData(BlockPos pos, EnumFacing facing) { this.pos = pos; this.facing = facing; }
    }
}
