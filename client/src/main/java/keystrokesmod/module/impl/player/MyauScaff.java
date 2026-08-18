package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.StrafeEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.impl.render.HUD;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.util.Vec3i;
import net.minecraft.world.WorldSettings;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Port of OpenMyau-Plus' Scaffold, kept deliberately separate from {@link Scaffold}
 * so the two can be compared side by side. Everything it needs is implemented as a
 * private helper in this class rather than added to the shared utilities, so enabling
 * or disabling it cannot affect the existing Scaffold in any way.
 *
 * Framework mapping from Myau to Raven:
 *   UpdateEvent(PRE)  -> PreMotionEvent      (rotations + placement)
 *   StrafeEvent       -> StrafeEvent         (tower motion)
 *   MoveInputEvent    -> PrePlayerInputEvent (input overrides, eagle sneak)
 *   LivingUpdateEvent -> PreUpdateEvent      (motion scaling, sprint, safe-stuck)
 *   SafeWalkEvent     -> SafeWalkState.shouldSafeWalk via canSafeWalk()
 *   Render2D/3DEvent  -> RenderTickEvent / RenderWorldLastEvent
 */
public class MyauScaff extends Module {
    private static final int ROTATION_SNAP = 7;
    private static final int ROTATION_THREE_FMC = 8;
    private static final int ROTATION_SNAP2 = 9;

    private static final double[] placeOffsets = new double[]{
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375, 0.40625, 0.46875,
            0.53125, 0.59375, 0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };

    private final SliderSetting rotationMode;
    private final SliderSetting tellyStartRotationMinSpeed;
    private final SliderSetting tellyStartRotationMaxSpeed;
    private final SliderSetting tellyNormalRotationMinSpeed;
    private final SliderSetting tellyNormalRotationMaxSpeed;
    private final SliderSetting rotationApply;
    private final SliderSetting rotationSpeed;
    private final SliderSetting rotationSpeedVertical;
    private final SliderSetting moveFix;
    private final SliderSetting sprintMode;
    private final SliderSetting groundMotion;
    private final SliderSetting airMotion;
    private final SliderSetting speedMotion;
    private final SliderSetting tower;
    private final ButtonSetting hypixelTower;
    private final ButtonSetting safe;
    private final SliderSetting safeStuckDelayTicksProperty;
    private final SliderSetting keepY;
    private final ButtonSetting keepYonPress;
    private final ButtonSetting disableWhileJumpActive;
    private final ButtonSetting multiplace;
    private final ButtonSetting safeWalk;
    private final ButtonSetting swing;
    private final ButtonSetting blockCounter;
    private final ButtonSetting eagle;
    private final SliderSetting edgeDistance;
    private final SliderSetting sneakDelay;
    private final SliderSetting blocksPerSneak;
    private final ButtonSetting espOutline;
    private final SliderSetting espColor;
    private final ButtonSetting debugLog;

    private final String[] rotationModes = new String[]{
            "None", "Default", "Backwards", "Sideways", "Godbridge", "Smooth", "Hypixel", "Snap", "3FMC", "Snap2"
    };
    private final String[] rotationApplyModes = new String[]{"Silent", "Client"};
    private final String[] moveFixModes = new String[]{"None", "Silent"};
    private final String[] sprintModes = new String[]{"None", "Vanilla"};
    private final String[] towerModes = new String[]{"None", "Vanilla", "Extra", "Telly"};
    private final String[] keepYModes = new String[]{"None", "Vanilla", "Extra", "Telly", "Extra telly"};
    private final String[] espColorModes = new String[]{"Cyan", "White"};

    private int rotationTick = 0;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch = 0.0F;
    private boolean canRotate = false;
    private int towerTick = 0;
    private int towerDelay = 0;
    private int stage = 0;
    private int startY = 256;
    private boolean shouldKeepY = false;
    private boolean towering = false;
    private EnumFacing targetFacing = null;
    private int safeStuckTicks = 0;
    private int safeStuckDelayTicks = 0;
    private double safePrevMotionY = 0.0;
    private double savedMotionX;
    private double savedMotionY;
    private double savedMotionZ;
    private boolean safeStuckActive = false;
    private boolean snapRotating = false;
    private boolean placedThisTick = false;
    private int threeFmcAirTicks = 0;
    private int threeFmcGroundTicks = 0;
    private int threeFmcPlaceCooldown = 0;
    private float lastSnapPlaceYaw = Float.NaN;
    private float lastSnapPlacePitch = Float.NaN;

    private boolean eagleSneaking = false;
    private int eagleSneakTicks = 0;
    private long eagleLastSneakTime = 0L;
    private int eagleBlocksPlaced = 0;

    private final Map<BlockPos, Long> espHighlight = new HashMap<>();

    /** Set while a silent rotation is active this tick, consumed by the strafe move-fix. */
    private boolean rotatingThisTick = false;

    /**
     * The rotation actually written into this tick's C03. Not always equal to yaw/pitch —
     * the telly and tower paths send a clamped interpolation while yaw/pitch hold the
     * unclamped target — so the deferred placement must verify against these.
     */
    private float sentYaw = 0.0F;
    private float sentPitch = 0.0F;

    /**
     * Client-rotation state. In "Client" mode the player is physically turned to the scaffold
     * rotation instead of the rotation only being spoofed into the packet, so there is no
     * silent offset between what the client looks at and what the server is told. intendedYaw
     * tracks where the player actually wants to go — mouse deltas are folded into it each tick
     * so steering still works while the real rotation is held backwards.
     */
    private float intendedYaw = 0.0F;
    private float lastAppliedYaw = 0.0F;

    /** Placement staged in PreMotionEvent, committed in PreUpdateEvent on the next tick. */
    private BlockPos pendPos = null;
    private EnumFacing pendFacing = null;
    private boolean pendSnap = false;
    private boolean pendMulti = false;
    private boolean pendPlace = false;
    private int pendingSnapDelay = 0;
    private PrintWriter debugWriter;
    private int debugTick;
    private BlockPos lastPlacedBlock;

    public MyauScaff() {
        super("MyauScaff", category.player);
        this.registerSetting(rotationMode = new SliderSetting("Rotations", 2, rotationModes));
        this.registerSetting(tellyStartRotationMinSpeed = new SliderSetting("Telly start rotation min speed", 90.0, 1.0, 180.0, 1.0));
        this.registerSetting(tellyStartRotationMaxSpeed = new SliderSetting("Telly start rotation max speed", 95.0, 1.0, 180.0, 1.0));
        this.registerSetting(tellyNormalRotationMinSpeed = new SliderSetting("Telly normal rotation min speed", 30.0, 1.0, 180.0, 1.0));
        this.registerSetting(tellyNormalRotationMaxSpeed = new SliderSetting("Telly normal rotation max speed", 35.0, 1.0, 180.0, 1.0));
        this.registerSetting(rotationApply = new SliderSetting("Apply rotations", 1, rotationApplyModes));
        this.registerSetting(rotationSpeed = new SliderSetting("Rotation speed", "°/tick", 60, 5, 180, 1));
        this.registerSetting(rotationSpeedVertical = new SliderSetting("Rotation speed vertical", "°/tick", 60, 5, 180, 1));
        this.registerSetting(moveFix = new SliderSetting("Move fix", 1, moveFixModes));
        this.registerSetting(sprintMode = new SliderSetting("Sprint", 0, sprintModes));
        this.registerSetting(groundMotion = new SliderSetting("Ground motion", "%", 100, 0, 100, 1));
        this.registerSetting(airMotion = new SliderSetting("Air motion", "%", 100, 0, 100, 1));
        this.registerSetting(speedMotion = new SliderSetting("Speed motion", "%", 100, 0, 100, 1));
        this.registerSetting(tower = new SliderSetting("Tower", 0, towerModes));
        this.registerSetting(hypixelTower = new ButtonSetting("Hypixel tower", false));
        this.registerSetting(safe = new ButtonSetting("Safe", false));
        this.registerSetting(safeStuckDelayTicksProperty = new SliderSetting("Safe delay ticks", 1, 1, 3, 1));
        this.registerSetting(keepY = new SliderSetting("Keep Y", 0, keepYModes));
        this.registerSetting(keepYonPress = new ButtonSetting("Keep Y on press", false));
        this.registerSetting(disableWhileJumpActive = new ButtonSetting("No keep Y on jump potion", false));
        // Off by default: placing several blocks in one tick is the loudest thing this
        // module can do. OpenMyau defaults it on; that is not viable on Hypixel.
        this.registerSetting(multiplace = new ButtonSetting("Multi place", false));
        this.registerSetting(safeWalk = new ButtonSetting("Safe walk", true));
        this.registerSetting(swing = new ButtonSetting("Swing", true));
        this.registerSetting(blockCounter = new ButtonSetting("Block counter", true));
        this.registerSetting(eagle = new ButtonSetting("Eagle", false));
        this.registerSetting(edgeDistance = new SliderSetting("Edge distance", 0.13, 0.0, 0.5, 0.01));
        this.registerSetting(sneakDelay = new SliderSetting("Sneak delay", "ms", 80, 0, 500, 5));
        this.registerSetting(blocksPerSneak = new SliderSetting("Blocks per sneak", 1, 1, 5, 1));
        this.registerSetting(espOutline = new ButtonSetting("Outline ESP", false));
        this.registerSetting(espColor = new SliderSetting("Outline color", 0, espColorModes));
        this.registerSetting(debugLog = new ButtonSetting("Debug log", true));
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    @Override
    public void onEnable() {
        // The two scaffolds fight over rotations, slot and placement, so only one runs.
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            ModuleManager.scaffold.disable();
            Utils.sendMessage("&eScaffold disabled &7(TestScaffold took over)");
        }
        if (ModuleManager.testScaffold != null && ModuleManager.testScaffold != this && ModuleManager.testScaffold.isEnabled()) {
            ModuleManager.testScaffold.disable();
            Utils.sendMessage("&eTestScaffold disabled &7(MyauScaff took over)");
        }
        this.lastSlot = mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1;
        // Seed the rotation state from where the player is actually looking, otherwise the
        // first step interpolates from 0 and sweeps the view across the world.
        this.sentYaw = mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0F;
        this.sentPitch = mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0F;
        this.intendedYaw = this.sentYaw;
        this.lastAppliedYaw = this.sentYaw;
        this.blockCount = -1;
        this.rotationTick = 3;
        this.yaw = -180.0F;
        this.pitch = 0.0F;
        this.canRotate = false;
        this.towerTick = 0;
        this.towerDelay = 0;
        this.towering = false;
        this.safeStuckTicks = 0;
        this.safeStuckDelayTicks = 0;
        this.safePrevMotionY = 0.0;
        this.safeStuckActive = false;
        this.eagleSneaking = false;
        this.eagleSneakTicks = 0;
        this.eagleBlocksPlaced = 0;
        this.eagleLastSneakTime = 0L;
        this.snapRotating = false;
        this.threeFmcAirTicks = 0;
        this.threeFmcGroundTicks = 0;
        this.threeFmcPlaceCooldown = 0;
        this.lastSnapPlaceYaw = Float.NaN;
        this.lastSnapPlacePitch = Float.NaN;
        this.clearPending();
        this.espHighlight.clear();
        this.debugTick = 0;
        this.lastPlacedBlock = null;
        this.openDebugLog();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && this.lastSlot != -1) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        // Client mode leaves the player facing backwards — hand their view back.
        if (mc.thePlayer != null && this.isClientRotation()) {
            mc.thePlayer.rotationYaw = this.intendedYaw;
        }
        if (this.safeStuckActive && mc.thePlayer != null) {
            mc.thePlayer.motionX = this.savedMotionX;
            mc.thePlayer.motionY = this.savedMotionY;
            mc.thePlayer.motionZ = this.savedMotionZ;
        }
        this.safeStuckTicks = 0;
        this.safeStuckDelayTicks = 0;
        this.safePrevMotionY = 0.0;
        this.safeStuckActive = false;
        this.eagleSneaking = false;
        this.eagleSneakTicks = 0;
        this.threeFmcAirTicks = 0;
        this.threeFmcGroundTicks = 0;
        this.threeFmcPlaceCooldown = 0;
        this.rotatingThisTick = false;
        this.clearPending();
        this.espHighlight.clear();
        this.closeDebugLog();
        this.lastPlacedBlock = null;
    }

    @Override
    public void guiButtonToggled(ButtonSetting buttonSetting) {
        if (buttonSetting == debugLog) {
            if (debugLog.isToggled()) openDebugLog();
            else closeDebugLog();
        }
    }

    @Override
    public String getInfo() {
        return rotationModes[(int) rotationMode.getInput()];
    }

    public int getSlot() {
        return this.lastSlot;
    }

    public int getBlockCount() {
        return this.blockCount;
    }

    /** Consumed by SafeWalkState so the Lunar and Forge safewalk hooks both see it. */
    public boolean canSafeWalk() {
        if (!this.isEnabled() || !safeWalk.isToggled() || !Utils.nullCheck()) {
            return false;
        }
        return mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0
                && canMove(mc.thePlayer.motionX, mc.thePlayer.motionZ, -1.0);
    }

    // ───────────────────────────── main tick ─────────────────────────────

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        this.debugTick++;
        this.debug("PRE_MOTION view=" + event.getYaw() + "," + event.getPitch() + " target=" + this.yaw + "," + this.pitch
                + " sent=" + this.sentYaw + "," + this.sentPitch + " canRotate=" + this.canRotate
                + " pending=" + this.pendPlace + " ground=" + mc.thePlayer.onGround
                + " input=" + mc.thePlayer.moveForward + "," + mc.thePlayer.moveStrafing);
        // placedThisTick is reset in onPreUpdate, which owns the placement pass.
        this.rotatingThisTick = false;
        if (this.isClientRotation()) {
            // Fold whatever the mouse did since we last forced the rotation into intendedYaw,
            // so steering keeps working while the player is held facing backwards.
            this.intendedYaw += MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - this.lastAppliedYaw);
            this.intendedYaw = MathHelper.wrapAngleTo180_float(this.intendedYaw);
        }
        this.updateThreeFmcState();
        this.quietThreeFmcMovement();

        if (this.safeStuckDelayTicks > 0) {
            this.safeStuckDelayTicks--;
            if (this.safeStuckDelayTicks <= 0) {
                this.safeStuckTicks = 1;
            }
        }
        if (this.safeStuckTicks > 0) {
            if (!this.safeStuckActive) {
                this.savedMotionX = mc.thePlayer.motionX;
                this.savedMotionY = mc.thePlayer.motionY;
                this.savedMotionZ = mc.thePlayer.motionZ;
                this.safeStuckActive = true;
            }
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionY = 0.0;
            mc.thePlayer.motionZ = 0.0;
        } else if (this.safeStuckActive) {
            mc.thePlayer.motionX = this.savedMotionX;
            mc.thePlayer.motionY = this.savedMotionY;
            mc.thePlayer.motionZ = this.savedMotionZ;
            this.safeStuckActive = false;
        }

        if (this.rotationTick > 0) {
            this.rotationTick--;
        }
        this.updateEagle();

        if (hypixelTower.isToggled() && mc.thePlayer.motionY <= 0.0
                && Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ) <= 0.02D
                && mc.thePlayer.motionY >= -0.09
                && !(Keyboard.isKeyDown(mc.gameSettings.keyBindForward.getKeyCode())
                || Keyboard.isKeyDown(mc.gameSettings.keyBindBack.getKeyCode())
                || Keyboard.isKeyDown(mc.gameSettings.keyBindLeft.getKeyCode())
                || Keyboard.isKeyDown(mc.gameSettings.keyBindRight.getKeyCode()))
                && Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            mc.thePlayer.motionY = -0.38;
        }

        if (mc.thePlayer.onGround) {
            if (this.stage > 0) {
                this.stage--;
            }
            if (this.stage < 0) {
                this.stage++;
            }
            if (this.stage == 0
                    && keepY.getInput() != 0
                    && (!keepYonPress.isToggled() || mc.thePlayer.isUsingItem())
                    && (!disableWhileJumpActive.isToggled() || !mc.thePlayer.isPotionActive(Potion.jump))
                    && !mc.gameSettings.keyBindJump.isKeyDown()) {
                this.stage = 1;
            }
            this.startY = this.shouldKeepY ? this.startY : MathHelper.floor_double(mc.thePlayer.posY);
            this.shouldKeepY = false;
            this.towering = false;
        }

        if (!this.canPlace()) {
            this.sentYaw = MathHelper.wrapAngleTo180_float(event.getYaw());
            this.sentPitch = event.getPitch();
            this.clearPending();
            return;
        }

        ItemStack stack = mc.thePlayer.getHeldItem();
        int count = isBlock(stack) ? stack.stackSize : 0;
        this.blockCount = Math.min(this.blockCount, count);
        if (this.blockCount <= 0) {
            int slot = mc.thePlayer.inventory.currentItem;
            if (this.blockCount == 0) {
                slot--;
            }
            for (int i = slot; i > slot - 9; i--) {
                int hotbarSlot = (i % 9 + 9) % 9;
                ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(hotbarSlot);
                if (isBlock(candidate)) {
                    mc.thePlayer.inventory.currentItem = hotbarSlot;
                    this.blockCount = candidate.stackSize;
                    break;
                }
            }
        }

        float currentYaw = this.getCurrentYaw();
        float yawDiffTo180 = wrapAngleDiff(currentYaw - 180.0F, event.getYaw());
        float diagonalYaw = this.isDiagonal(currentYaw)
                ? yawDiffTo180
                : wrapAngleDiff(currentYaw - 135.0F * ((currentYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F), event.getYaw());
        boolean snapMode = rotationMode.getInput() == ROTATION_SNAP || rotationMode.getInput() == ROTATION_SNAP2;
        boolean threeFmcMode = rotationMode.getInput() == ROTATION_THREE_FMC;
        boolean threeFmcTelly = this.isThreeFmcTellyMode();
        this.snapRotating = false;

        if (!this.canRotate) {
            switch ((int) rotationMode.getInput()) {
                case 1:
                case 3:
                case 6:
                    if (this.yaw == -180.0F && this.pitch == 0.0F) {
                        this.yaw = quantizeAngle(diagonalYaw);
                        this.pitch = quantizeAngle(85.0F);
                    } else {
                        this.yaw = quantizeAngle(diagonalYaw);
                    }
                    break;
                case 2:
                    if (this.yaw == -180.0F && this.pitch == 0.0F) {
                        this.yaw = quantizeAngle(yawDiffTo180);
                        this.pitch = quantizeAngle(85.0F);
                    } else {
                        this.yaw = quantizeAngle(yawDiffTo180);
                    }
                    break;
                case 4: { // Godbridge: snap yaw to nearest 45 degree diagonal
                    float roundedYaw = Math.round(currentYaw / 45.0f) * 45.0f;
                    this.yaw = quantizeAngle(roundedYaw);
                    if (this.pitch == 0.0F || !this.canRotate) {
                        this.pitch = quantizeAngle(79.3f);
                    }
                    break;
                }
                case 5:
                    if (this.yaw == -180.0F && this.pitch == 0.0F) {
                        this.yaw = quantizeAngle(diagonalYaw);
                        this.pitch = quantizeAngle(85.0F);
                    } else {
                        float targetYaw = this.isDiagonal(currentYaw) ? diagonalYaw : yawDiffTo180;
                        float yawDiff = MathHelper.wrapAngleTo180_float(targetYaw - this.yaw);
                        float pitchDiff = MathHelper.wrapAngleTo180_float(85.0F - this.pitch);
                        float yawTolerance = this.tellyTolerance();
                        float pitchTolerance = this.tellyTolerance();
                        this.yaw = quantizeAngle(this.yaw + clampAngle(yawDiff, yawTolerance));
                        this.pitch = quantizeAngle(this.pitch + clampAngle(pitchDiff, pitchTolerance));
                    }
                    break;
                case ROTATION_SNAP:
                case ROTATION_SNAP2:
                    this.yaw = quantizeAngle(yawDiffTo180);
                    this.pitch = quantizeAngle(85.0F);
                    break;
                case ROTATION_THREE_FMC:
                    if (this.yaw == -180.0F && this.pitch == 0.0F) {
                        this.yaw = quantizeAngle(event.getYaw());
                        this.pitch = quantizeAngle(event.getPitch());
                    }
                    break;
                default:
                    break;
            }
        }

        List<BlockData> candidates = this.getBlockDataCandidates();
        BlockData blockData = candidates.isEmpty() ? null : candidates.get(0);
        Vec3 hitVec = null;
        boolean validTargetRotation = false;
        TargetRotation targetRotation = null;
        for (BlockData candidate : candidates) {
            targetRotation = this.findTargetRotation(candidate, event.getYaw());
            if (targetRotation != null) {
                blockData = candidate;
                break;
            }
        }
        if (targetRotation != null) {
            this.yaw = targetRotation.yaw;
            this.pitch = targetRotation.pitch;
            hitVec = targetRotation.hitVec;
            this.canRotate = true;
            validTargetRotation = true;
        }
        else if (blockData != null) {
            this.canRotate = false;
        }
        boolean towerRotating = this.towering || this.isTowering();
        boolean snapAlreadyLooking = false;
        boolean snapCanPlace = true;
        if (snapMode && !towerRotating && blockData != null) {
            MovingObjectPosition currentMop = this.getPlacementMop(blockData, event.getYaw(), event.getPitch());
            if (currentMop != null) {
                float[] snapRotation = this.getSnapRotation(blockData, event.getYaw(), event.getPitch());
                if (snapRotation == null) {
                    snapCanPlace = false;
                    hitVec = null;
                } else {
                    this.yaw = snapRotation[0];
                    this.pitch = snapRotation[1];
                    this.canRotate = true;
                    validTargetRotation = true;
                    MovingObjectPosition snapMop = this.getPlacementMop(blockData, this.yaw, this.pitch);
                    hitVec = snapMop != null ? snapMop.hitVec : currentMop.hitVec;
                    this.snapRotating = true;
                    int snapDelay = rotationMode.getInput() == ROTATION_SNAP2 ? 0 : 1;
                    if (this.rotationTick > snapDelay) {
                        this.rotationTick = snapDelay;
                    }
                }
            } else if (hitVec != null && this.canRotate) {
                float[] snapRotation = this.getSnapRotation(blockData, this.yaw, this.pitch);
                if (snapRotation == null) {
                    snapCanPlace = false;
                    hitVec = null;
                } else {
                    this.yaw = snapRotation[0];
                    this.pitch = snapRotation[1];
                    validTargetRotation = true;
                    MovingObjectPosition snapMop = this.getPlacementMop(blockData, this.yaw, this.pitch);
                    if (snapMop != null) {
                        hitVec = snapMop.hitVec;
                    }
                    this.snapRotating = true;
                    int snapDelay = rotationMode.getInput() == ROTATION_SNAP2 ? 0 : 1;
                    if (this.rotationTick > snapDelay) {
                        this.rotationTick = snapDelay;
                    }
                }
            }
        }

        if (this.canRotate && isForwardPressed() && Math.abs(MathHelper.wrapAngleTo180_float(yawDiffTo180 - this.yaw)) < 90.0F) {
            switch ((int) rotationMode.getInput()) {
                case 2: {
                    float preferredYaw = quantizeAngle(yawDiffTo180);
                    if (blockData != null && this.getPlacementMop(blockData, preferredYaw, this.pitch) != null) {
                        this.yaw = preferredYaw;
                    }
                    break;
                }
                case 3: {
                    float preferredYaw = quantizeAngle(diagonalYaw);
                    if (blockData != null && this.getPlacementMop(blockData, preferredYaw, this.pitch) != null) {
                        this.yaw = preferredYaw;
                    }
                    break;
                }
                default:
                    break;
            }
        }

        float placeYaw = event.getYaw();
        float placePitch = event.getPitch();
        if (rotationMode.getInput() != 0 && (!snapMode || this.snapRotating || towerRotating)) {
            float targetYaw = this.yaw;
            float targetPitch = this.pitch;
            if ((!threeFmcMode || threeFmcTelly) && this.towering
                    && (mc.thePlayer.motionY > 0.0 || mc.thePlayer.posY > (double) (this.startY + 1))) {
                float yawDiff = MathHelper.wrapAngleTo180_float(this.yaw - event.getYaw());
                float tolerance = this.tellyTolerance();
                if (Math.abs(yawDiff) > tolerance) {
                    float clampedYaw = clampAngle(yawDiff, tolerance);
                    targetYaw = quantizeAngle(event.getYaw() + clampedYaw);
                    this.rotationTick = Math.max(this.rotationTick, 1);
                }
            }
            if (towerRotating && this.isTowering()) {
                if (!threeFmcMode || threeFmcTelly) {
                    float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - event.getYaw());
                    targetYaw = quantizeAngle(event.getYaw() + yawDelta * randomFloat(0.98F, 0.99F));
                    targetPitch = quantizeAngle(randomFloat(30.0F, 80.0F));
                }
                this.rotationTick = 3;
                this.towering = true;
            }
            // LiquidBounce never snaps: performAngleChange caps degrees-per-tick toward the
            // target and quantizes the delta. Instantly jumping ~180 degrees is detectable on
            // its own, regardless of whether the rotation lines up with the block.
            targetYaw = stepAngle(this.sentYaw, targetYaw, (float) rotationSpeed.getInput());
            targetPitch = stepAngle(this.sentPitch, targetPitch, (float) rotationSpeedVertical.getInput());
            placeYaw = targetYaw;
            placePitch = targetPitch;
            event.setRotations(targetYaw, targetPitch);
            this.rotatingThisTick = true;
            if (this.isClientRotation()) {
                // Turn the player for real. This is the automated version of manually spinning
                // around in first person: the rendered view, the physics yaw and the packet all
                // agree, so there is no silent offset left for the server to notice.
                mc.thePlayer.rotationYaw = targetYaw;
                mc.thePlayer.rotationPitch = targetPitch;
                this.lastAppliedYaw = targetYaw;
            }
        }

        if (blockData != null && hitVec != null && snapCanPlace) {
            // Placement must use hit data produced by the exact rotation written to C03.
            MovingObjectPosition verifiedMop = this.getPlacementMop(blockData, placeYaw, placePitch);
            hitVec = verifiedMop == null ? null : verifiedMop.hitVec;
        }
        else {
            hitVec = null;
        }

        if (blockData == null || !validTargetRotation) {
            this.canRotate = false;
            this.clearPending();
        }

        // Stage the placement instead of doing it here. Placing inside PreMotionEvent
        // sends C08 BEFORE onUpdateWalkingPlayer queues the C03 carrying this rotation,
        // so the server would evaluate the placement against the previous tick's look.
        // onPreUpdate does the actual place next tick, once this rotation is on the wire.
        if (blockData != null && hitVec != null && snapCanPlace) {
            this.stagePlacement(blockData.blockPos(), blockData.facing(), snapMode, multiplace.isToggled() && !snapMode);
        }

        if (this.targetFacing != null) {
            if (!threeFmcMode && !this.pendPlace) {
                BlockPos belowPlayer = new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX),
                        MathHelper.floor_double(mc.thePlayer.posY) - 1,
                        MathHelper.floor_double(mc.thePlayer.posZ));
                this.stagePlacementIfValid(belowPlayer, this.targetFacing, false, false, placeYaw, placePitch);
            }
            this.targetFacing = null;
        } else if ((keepY.getInput() == 2 || keepY.getInput() == 4) && this.stage > 0 && !mc.thePlayer.onGround) {
            int nextBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
            if (nextBlockY <= this.startY && mc.thePlayer.posY > (double) (this.startY + 1)) {
                this.shouldKeepY = true;
                PlacementRay keepYData = this.findCandidateForRotation(placeYaw, placePitch);
                if (keepYData != null && !this.pendPlace) {
                    this.stagePlacement(keepYData.data.blockPos(), keepYData.data.facing(), false, false);
                }
            }
        }

        // Whatever the event holds now is what onUpdateWalkingPlayer will put on the wire.
        this.sentYaw = MathHelper.wrapAngleTo180_float(placeYaw);
        this.sentPitch = placePitch;
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent event) {
        if (RotationUtils.serverRotations != null && RotationUtils.serverRotations.length >= 2) {
            this.sentYaw = MathHelper.wrapAngleTo180_float(RotationUtils.serverRotations[0]);
            this.sentPitch = RotationUtils.serverRotations[1];
        }
    }

    private void stagePlacement(BlockPos pos, EnumFacing facing, boolean snap, boolean multi) {
        this.pendPos = pos;
        this.pendFacing = facing;
        this.pendSnap = snap;
        this.pendMulti = multi;
        this.pendPlace = true;
        this.pendingSnapDelay = snap && rotationMode.getInput() == ROTATION_SNAP ? 1 : 0;
    }

    private void stagePlacementIfValid(BlockPos pos, EnumFacing facing, boolean snap, boolean multi,
                                       float yaw, float pitch) {
        BlockData data = new BlockData(pos, facing);
        MovingObjectPosition mop = this.getPlacementMop(data, yaw, pitch);
        if (mop != null) {
            this.stagePlacement(pos, facing, snap, multi);
        }
    }

    /**
     * Runs at the head of EntityPlayerSP.onUpdate, before this tick's movement and before
     * onUpdateWalkingPlayer. The player has not moved yet, so its position still matches the
     * last C03 the server received, and this.yaw/this.pitch still hold the rotation that C03
     * carried. Re-raytracing here reproduces the server's own view of the placement, so the
     * C08 that goes out is one the server can accept.
     */
    private void flushPendingPlacement() {
        if (!this.pendPlace || this.pendPos == null || this.pendFacing == null) {
            this.clearPending();
            return;
        }
        if (!this.canPlace()) {
            this.clearPending();
            return;
        }
        if (this.pendingSnapDelay > 0) {
            this.pendingSnapDelay--;
            return;
        }

        MovingObjectPosition mop = rayTrace(this.sentYaw, this.sentPitch);
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(this.pendPos)
                || mop.sideHit != this.pendFacing) {
            this.clearPending();
            return;
        }

        this.place(this.pendPos, this.pendFacing, mop.hitVec);
        if (this.pendSnap) {
            this.rememberSnapRotation(this.sentYaw, this.sentPitch);
        }

        if (this.pendMulti) {
            for (int i = 0; i < 3; i++) {
                PlacementRay next = this.findCandidateForRotation(this.sentYaw, this.sentPitch);
                if (next == null) {
                    break;
                }
                this.place(next.data.blockPos(), next.data.facing(), next.mop.hitVec);
            }
        }
        this.clearPending();
    }

    private void clearPending() {
        this.pendPos = null;
        this.pendFacing = null;
        this.pendSnap = false;
        this.pendMulti = false;
        this.pendPlace = false;
        this.pendingSnapDelay = 0;
    }

    // ───────────────────────────── movement ─────────────────────────────

    @SubscribeEvent
    public void onStrafe(StrafeEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        // Move fix: NONE lets movement follow the spoofed yaw (Myau's unfixed behaviour),
        // SILENT keeps movement on the real input yaw. Raven's rotations are packet-only,
        // so SILENT is the no-op and NONE is what has to be applied explicitly.
        if (moveFix.getInput() == 0 && this.rotatingThisTick) {
            event.setYaw(this.yaw);
        }

        if (this.safeStuckTicks > 0) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
            return;
        }
        if (this.isThreeFmcMode() && !this.isThreeFmcTellyMode()) {
            this.towerTick = 0;
            this.towerDelay = 0;
            return;
        }
        if (!mc.thePlayer.isCollidedHorizontally
                && mc.thePlayer.hurtTime <= 5
                && !mc.thePlayer.isPotionActive(Potion.jump)
                && mc.gameSettings.keyBindJump.isKeyDown()
                && isHoldingBlock()) {
            int yState = (int) (mc.thePlayer.posY % 1.0 * 100.0);
            switch ((int) tower.getInput()) {
                case 1:
                    switch (this.towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && isAirBelow()) {
                                this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                this.towerTick = 2;
                                mc.thePlayer.motionY = 0.42F;
                                if (isForwardPressed()) {
                                    setSpeed(getHorizontalSpeed(), getMoveYaw());
                                } else {
                                    setSpeed(0.0, getMoveYaw());
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                }
                            } else {
                                this.towerTick = 0;
                            }
                            return;
                        case 2:
                            this.towerTick = 3;
                            mc.thePlayer.motionY = 0.75 - mc.thePlayer.posY % 1.0;
                            return;
                        case 3:
                            this.towerTick = 1;
                            mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            return;
                        default:
                            this.towerTick = 0;
                            return;
                    }
                case 2:
                    switch (this.towerTick) {
                        case 0:
                            if (mc.thePlayer.onGround) {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = -0.0784000015258789;
                            }
                            return;
                        case 1:
                            if (yState == 0 && isAirBelow()) {
                                this.startY = MathHelper.floor_double(mc.thePlayer.posY);
                                if (!isForwardPressed()) {
                                    this.towerDelay = 2;
                                    setSpeed(0.0, getMoveYaw());
                                    event.setForward(0.0F);
                                    event.setStrafe(0.0F);
                                    EnumFacing facing = this.yawToFacing(MathHelper.wrapAngleTo180_float(this.yaw - 180.0F));
                                    double distance = this.distanceToEdge(facing);
                                    if (distance > 0.1) {
                                        if (mc.thePlayer.onGround) {
                                            Vec3i directionVec = facing.getDirectionVec();
                                            double offset = Math.min(this.getRandomOffset(), distance - 0.05);
                                            double jitter = Utils.randomizeDouble(0.02, 0.03);
                                            AxisAlignedBB nextBox = mc.thePlayer.getEntityBoundingBox()
                                                    .offset((double) directionVec.getX() * (offset - jitter), 0.0,
                                                            (double) directionVec.getZ() * (offset - jitter));
                                            if (mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, nextBox).isEmpty()) {
                                                mc.thePlayer.motionY = -0.0784000015258789;
                                                mc.thePlayer.setPosition(
                                                        nextBox.minX + (nextBox.maxX - nextBox.minX) / 2.0,
                                                        nextBox.minY,
                                                        nextBox.minZ + (nextBox.maxZ - nextBox.minZ) / 2.0);
                                            }
                                        }
                                    } else {
                                        this.towerTick = 2;
                                        this.targetFacing = facing;
                                        mc.thePlayer.motionY = 0.42F;
                                    }
                                } else {
                                    this.towerTick = 2;
                                    this.towerDelay++;
                                    mc.thePlayer.motionY = 0.42F;
                                    setSpeed(getHorizontalSpeed(), getMoveYaw());
                                }
                            } else {
                                this.towerTick = 0;
                                this.towerDelay = 0;
                            }
                            return;
                        case 2:
                            this.towerTick = 3;
                            mc.thePlayer.motionY = mc.thePlayer.motionY - Utils.randomizeDouble(0.00101, 0.00109);
                            return;
                        case 3:
                            if (this.towerDelay >= 4) {
                                this.towerTick = 4;
                                this.towerDelay = 0;
                            } else {
                                this.towerTick = 1;
                                mc.thePlayer.motionY = 1.0 - mc.thePlayer.posY % 1.0;
                            }
                            return;
                        case 4:
                            this.towerTick = 5;
                            return;
                        case 5:
                            if (!isAirBelow()) {
                                this.towerTick = 0;
                            } else {
                                this.towerTick = 1;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                                mc.thePlayer.motionY -= 0.08;
                                mc.thePlayer.motionY *= 0.98F;
                            }
                            return;
                        default:
                            this.towerTick = 0;
                            this.towerDelay = 0;
                            return;
                    }
                default:
                    this.towerTick = 0;
                    this.towerDelay = 0;
            }
        } else {
            this.towerTick = 0;
            this.towerDelay = 0;
        }
    }

    @SubscribeEvent
    public void onPlayerInput(PrePlayerInputEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (this.safeStuckTicks > 0) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
            event.setJump(false);
            event.setSneak(false);
            return;
        }
        this.quietThreeFmcMovement();
        if (this.isClientRotation()) {
            // The player is physically turned away from where they want to go, so counter-rotate
            // the movement input by the same angle. Pressing W still walks the intended
            // direction. moveFlying maps (strafe, forward) through R(yaw), so applying
            // R(intendedYaw - actualYaw) to the input leaves world-space motion unchanged.
            float d = MathHelper.wrapAngleTo180_float(this.intendedYaw - mc.thePlayer.rotationYaw);
            double rad = Math.toRadians(d);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float forward = event.getForward();
            float strafe = event.getStrafe();
            event.setStrafe(strafe * cos - forward * sin);
            event.setForward(forward * cos + strafe * sin);
        }
        if (mc.thePlayer.onGround && this.stage > 0 && isForwardPressed()) {
            event.setJump(true);
        }
        if (this.eagleSneaking && !event.isSneak()) {
            event.setSneak(true);
            event.setForward(event.getForward() * 0.3F);
            event.setStrafe(event.getStrafe() * 0.3F);
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        this.debug("PRE_UPDATE pending=" + this.pendPlace + " target=" + this.pendPos + " face=" + this.pendFacing
                + " blockCount=" + this.blockCount + " slot=" + mc.thePlayer.inventory.currentItem
                + " view=" + this.sentYaw + "," + this.sentPitch);
        this.placedThisTick = false;
        this.flushPendingPlacement();
        if (this.safeStuckTicks > 0) {
            mc.thePlayer.motionX = 0.0;
            mc.thePlayer.motionY = 0.0;
            mc.thePlayer.motionZ = 0.0;
            this.safeStuckTicks--;
        }
        this.quietThreeFmcMovement();

        float speed = this.isThreeFmcMode() && !this.isThreeFmcTellyMode() ? 1.0F : this.getSpeed();
        if (speed != 1.0F) {
            if (mc.thePlayer.movementInput.moveForward != 0.0F && mc.thePlayer.movementInput.moveStrafe != 0.0F) {
                mc.thePlayer.movementInput.moveForward *= (1.0F / (float) Math.sqrt(2.0));
                mc.thePlayer.movementInput.moveStrafe *= (1.0F / (float) Math.sqrt(2.0));
            }
            mc.thePlayer.movementInput.moveForward *= speed;
            mc.thePlayer.movementInput.moveStrafe *= speed;
        }
        if (this.shouldStopSprint()) {
            mc.thePlayer.setSprinting(false);
        }

        if (safe.isToggled() && tower.getInput() == 3 && mc.gameSettings.keyBindJump.isKeyDown()) {
            float moveYaw = this.getCurrentYaw();
            if (this.isDiagonal(moveYaw) && !mc.thePlayer.onGround) {
                double motionY = mc.thePlayer.motionY;
                if (this.safePrevMotionY > 0.0 && motionY <= 0.0) {
                    double motionXZ = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
                    if (this.safeStuckDelayTicks <= 0 && this.safeStuckTicks <= 0 && motionXZ * 20.0 >= 4.67) {
                        this.safeStuckDelayTicks = (int) safeStuckDelayTicksProperty.getInput();
                    }
                }
                this.safePrevMotionY = motionY;
            } else {
                this.safePrevMotionY = mc.thePlayer.motionY;
            }
        } else {
            this.safePrevMotionY = mc.thePlayer.motionY;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        // Only suppress the click itself, and only while actually bridging. The old Scaffold
        // force-releases the attack/use keybinds every mouse event; that is not what OpenMyau
        // does and it interferes with other modules.
        if (event.button >= 0 && isHoldingBlock()) {
            event.setCanceled(true);
        }
    }

    // ───────────────────────────── rendering ─────────────────────────────

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !blockCounter.isToggled()) {
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null) {
            return;
        }
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0) {
                Item item = stack.getItem();
                if (item instanceof ItemBlock) {
                    Block block = ((ItemBlock) item).getBlock();
                    if (!BlockUtils.isInteractable(block) && block.getMaterial().isSolid()) {
                        count += stack.stackSize;
                    }
                }
            }
        }
        ScaledResolution sr = new ScaledResolution(mc);
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawString(
                String.format("%d block%s left", count, count != 1 ? "s" : ""),
                (float) sr.getScaledWidth() / 2.0F + (float) mc.fontRendererObj.FONT_HEIGHT * 1.5F,
                (float) sr.getScaledHeight() / 2.0F - (float) mc.fontRendererObj.FONT_HEIGHT / 2.0F + 1.0F,
                (count > 0 ? Color.WHITE.getRGB() : new Color(255, 85, 85).getRGB()) | -1090519040,
                HUD.shouldDrawTextShadow());
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!espOutline.isToggled() || mc.theWorld == null || mc.thePlayer == null || this.espHighlight.isEmpty()) {
            return;
        }
        int themeColor = espColor.getInput() == 1 ? Color.WHITE.getRGB() : Color.CYAN.getRGB();
        Iterator<Map.Entry<BlockPos, Long>> iterator = this.espHighlight.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockPos, Long> entry = iterator.next();
            long time = System.currentTimeMillis() - entry.getValue();
            if (time > 750L) {
                iterator.remove();
                continue;
            }
            int currentAlpha = (int) (210 - (time / 750.0 * 210));
            if (currentAlpha <= 0) {
                iterator.remove();
                continue;
            }
            RenderUtils.renderBlock(entry.getKey(), (themeColor & 0xFFFFFF) | (currentAlpha << 24), true, false);
        }
    }

    // ───────────────────────────── placement ─────────────────────────────

    private void place(BlockPos blockPos, EnumFacing enumFacing, Vec3 vec3) {
        if (!this.canThreeFmcPlaceNow() || vec3 == null) {
            return;
        }
        if (isHoldingBlock() && this.blockCount > 0) {
            BlockPos placedBlock = blockPos.offset(enumFacing);
            if (this.lastPlacedBlock != null && !BlockUtils.replaceable(this.lastPlacedBlock)) {
                this.lastPlacedBlock = null;
            }
            if (placedBlock.equals(this.lastPlacedBlock)) {
                this.debug("PLACE_SKIP duplicate target=" + placedBlock);
                return;
            }
            boolean placed = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                    mc.thePlayer.inventory.getCurrentItem(), blockPos, enumFacing, vec3);
            this.debug("PLACE support=" + blockPos + " face=" + enumFacing + " hit=" + vec3 + " result=" + placed);
            if (placed) {
                if (mc.playerController.getCurrentGameType() != WorldSettings.GameType.CREATIVE) {
                    this.blockCount--;
                }
                this.placedThisTick = true;
                this.lastPlacedBlock = placedBlock;
                if (this.isThreeFmcMode()) {
                    this.threeFmcPlaceCooldown = 1;
                }
                this.markPlaced(blockPos.offset(enumFacing));
                this.eagleBlocksPlaced++;
                if (swing.isToggled()) {
                    mc.thePlayer.swingItem();
                } else {
                    mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
                }
            }
        }
    }

    private List<BlockData> getBlockDataCandidates() {
        int floorY = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                (this.stage != 0 && !this.shouldKeepY ? Math.min(floorY, this.startY) : floorY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtils.replaceable(targetPos)) {
            return new ArrayList<>();
        }
        ArrayList<BlockData> candidates = new ArrayList<>();
        double reach = mc.playerController.getBlockReachDistance();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = targetPos.add(x, y, z);
                    if (!BlockUtils.replaceable(pos)
                            && !BlockUtils.isInteractable(BlockUtils.getBlock(pos))
                            && !(mc.thePlayer.getDistance((double) pos.getX() + 0.5, (double) pos.getY() + 0.5, (double) pos.getZ() + 0.5) > reach)
                            && (this.stage == 0 || this.shouldKeepY || pos.getY() < this.startY)) {
                        for (EnumFacing facing : EnumFacing.VALUES) {
                            BlockPos placement = pos.offset(facing);
                            if (facing != EnumFacing.DOWN
                                    && (this.lastPlacedBlock == null || !placement.equals(this.lastPlacedBlock))
                                    && (placement.getY() <= targetPos.getY() || this.isTowering())
                                    && BlockUtils.replaceable(placement)
                                    && isPlacementSpaceClear(placement)
                                    && isBlockFaceVisible(pos, facing)) {
                                candidates.add(new BlockData(pos, facing));
                            }
                        }
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(o -> o.blockPos().offset(o.facing()).distanceSqToCenter(
                (double) targetPos.getX() + 0.5, (double) targetPos.getY() + 0.5, (double) targetPos.getZ() + 0.5)));
        return candidates;
    }

    private TargetRotation findTargetRotation(BlockData blockData, float eventYaw) {
        double[] x = placeOffsets;
        double[] y = placeOffsets;
        double[] z = placeOffsets;
        switch (blockData.facing()) {
            case NORTH: z = new double[]{0.0}; break;
            case EAST:  x = new double[]{1.0}; break;
            case SOUTH: z = new double[]{1.0}; break;
            case WEST:  x = new double[]{0.0}; break;
            case DOWN:  y = new double[]{0.0}; break;
            case UP:    y = new double[]{1.0}; break;
            default: break;
        }

        float baseYaw = wrapAngleDiff(this.yaw, eventYaw);
        float bestYaw = 0.0F;
        float bestPitch = 0.0F;
        float bestDiff = Float.MAX_VALUE;
        Vec3 bestHit = null;
        for (double dx : x) {
            for (double dy : y) {
                for (double dz : z) {
                    float[] rotations = RotationUtils.getRotationsToPoint(
                            blockData.blockPos().getX() + dx,
                            blockData.blockPos().getY() + dy,
                            blockData.blockPos().getZ() + dz,
                            baseYaw, this.pitch);
                    MovingObjectPosition mop = this.getPlacementMop(blockData, rotations[0], rotations[1]);
                    if (mop == null) continue;
                    float diff = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - baseYaw))
                            + Math.abs(rotations[1] - this.pitch);
                    if (bestHit == null || diff < bestDiff) {
                        bestYaw = rotations[0];
                        bestPitch = rotations[1];
                        bestDiff = diff;
                        bestHit = mop.hitVec;
                    }
                }
            }
        }
        return bestHit == null ? null : new TargetRotation(bestYaw, bestPitch, bestHit);
    }

    private PlacementRay findCandidateForRotation(float yaw, float pitch) {
        for (BlockData candidate : this.getBlockDataCandidates()) {
            MovingObjectPosition mop = this.getPlacementMop(candidate, yaw, pitch);
            if (mop != null) {
                return new PlacementRay(candidate, mop);
            }
        }
        return null;
    }

    private boolean isPlacementSpaceClear(BlockPos placement) {
        AxisAlignedBB bounds = new AxisAlignedBB(
                placement.getX(), placement.getY(), placement.getZ(),
                placement.getX() + 1.0D, placement.getY() + 1.0D, placement.getZ() + 1.0D);
        for (Entity entity : mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, bounds)) {
            if (entity != null && entity.canBeCollidedWith()
                    && entity.getEntityBoundingBox().intersectsWith(bounds)) {
                return false;
            }
        }
        return true;
    }

    private boolean isBlockFaceVisible(BlockPos support, EnumFacing facing) {
        AxisAlignedBB bounds = BlockUtils.getCollisionOrSelectionBox(support);
        if (bounds == null) return false;
        double eyeX = mc.thePlayer.posX;
        double eyeY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight();
        double eyeZ = mc.thePlayer.posZ;
        switch (facing) {
            case DOWN: return bounds.minY > eyeY;
            case UP: return eyeY > bounds.maxY;
            case NORTH: return bounds.minZ > eyeZ;
            case SOUTH: return eyeZ > bounds.maxZ;
            case WEST: return bounds.minX > eyeX;
            case EAST: return eyeX > bounds.maxX;
            default: return false;
        }
    }

    private MovingObjectPosition getPlacementMop(BlockData blockData, float yaw, float pitch) {
        MovingObjectPosition mop = rayTrace(yaw, pitch);
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(blockData.blockPos())
                || mop.sideHit != blockData.facing()) {
            return null;
        }
        return mop;
    }

    private MovingObjectPosition rayTrace(float yaw, float pitch) {
        return RotationUtils.rayTraceCustom(mc.playerController.getBlockReachDistance(), yaw, pitch);
    }

    private boolean isDuplicateSnapRotation(float yaw, float pitch) {
        return !Float.isNaN(this.lastSnapPlaceYaw)
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - this.lastSnapPlaceYaw)) < 0.35F
                && Math.abs(pitch - this.lastSnapPlacePitch) < 0.35F;
    }

    private float[] getSnapRotation(BlockData blockData, float yaw, float pitch) {
        float baseYaw = quantizeAngle(yaw);
        float basePitch = quantizeAngle(MathHelper.clamp_float(pitch, -90.0F, 90.0F));
        if (!this.isDuplicateSnapRotation(baseYaw, basePitch)) {
            return new float[]{baseYaw, basePitch};
        }
        for (int i = 0; i < 24; i++) {
            float yawStep = 0.35F + 0.075F * (float) (i / 2);
            float pitchStep = 0.025F + 0.01F * (float) (i / 3);
            float testYaw = quantizeAngle(baseYaw + (i % 2 == 0 ? yawStep : -yawStep));
            float testPitch = quantizeAngle(MathHelper.clamp_float(basePitch + (i % 4 < 2 ? pitchStep : -pitchStep), -90.0F, 90.0F));
            if (!this.isDuplicateSnapRotation(testYaw, testPitch) && this.getPlacementMop(blockData, testYaw, testPitch) != null) {
                return new float[]{testYaw, testPitch};
            }
        }
        return null;
    }

    private void rememberSnapRotation(float yaw, float pitch) {
        this.lastSnapPlaceYaw = yaw;
        this.lastSnapPlacePitch = pitch;
    }

    private void markPlaced(BlockPos pos) {
        if (espOutline.isToggled()) {
            this.espHighlight.put(pos, System.currentTimeMillis());
        }
    }

    // ───────────────────────────── state helpers ─────────────────────────────

    private boolean canPlace() {
        return !LongJump.function;
    }

    private boolean shouldStopSprint() {
        if (this.isThreeFmcMode() && !this.isThreeFmcTellyMode()) {
            return true;
        }
        if (this.isTowering()) {
            return false;
        }
        boolean stageMode = keepY.getInput() == 1 || keepY.getInput() == 2 || keepY.getInput() == 4;
        return (!stageMode || this.stage <= 0) && sprintMode.getInput() == 0;
    }

    /** True when rotations are applied to the player itself rather than only to the packet. */
    private boolean isClientRotation() {
        return rotationApply.getInput() == 1 && rotationMode.getInput() != 0;
    }

    private boolean isThreeFmcMode() {
        return rotationMode.getInput() == ROTATION_THREE_FMC;
    }

    private boolean isThreeFmcTellyMode() {
        return this.isThreeFmcMode() && (keepY.getInput() == 3 || keepY.getInput() == 4);
    }

    private void updateThreeFmcState() {
        if (!this.isThreeFmcMode() || mc.thePlayer == null) {
            this.threeFmcAirTicks = 0;
            this.threeFmcGroundTicks = 0;
            this.threeFmcPlaceCooldown = 0;
            return;
        }
        if (mc.thePlayer.onGround) {
            this.threeFmcGroundTicks++;
            this.threeFmcAirTicks = 0;
        } else {
            this.threeFmcAirTicks++;
            this.threeFmcGroundTicks = 0;
        }
        if (this.threeFmcPlaceCooldown > 0) {
            this.threeFmcPlaceCooldown--;
        }
    }

    private void quietThreeFmcMovement() {
        if (!this.isThreeFmcMode() || this.isThreeFmcTellyMode() || mc.thePlayer == null) {
            return;
        }
        mc.thePlayer.setSprinting(false);
        if (mc.gameSettings != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    private boolean canThreeFmcPlaceNow() {
        if (!this.isThreeFmcMode()) {
            return true;
        }
        if (mc.thePlayer == null || this.placedThisTick || this.threeFmcPlaceCooldown > 0) {
            return false;
        }
        if ((!this.isThreeFmcTellyMode() && mc.thePlayer.isSprinting())
                || mc.thePlayer.isCollidedHorizontally || mc.thePlayer.hurtTime > 0) {
            return false;
        }
        if (mc.thePlayer.onGround) {
            return Math.abs(mc.thePlayer.motionY) < 1.0E-4 && this.threeFmcGroundTicks > 0;
        }
        return this.isThreeFmcTellyMode() ? this.threeFmcAirTicks > 1 : this.threeFmcAirTicks > 2;
    }

    private boolean isTowering() {
        if (mc.thePlayer.onGround && isForwardPressed() && !isAirAbove()) {
            boolean keepYTelly = keepY.getInput() == 3 || keepY.getInput() == 4;
            boolean towerTelly = tower.getInput() == 3;
            return keepYTelly && this.stage > 0 || towerTelly && mc.gameSettings.keyBindJump.isKeyDown();
        }
        return false;
    }

    private float tellyTolerance() {
        return this.rotationTick >= 2
                ? randomFloat((float) tellyStartRotationMinSpeed.getInput(), (float) tellyStartRotationMaxSpeed.getInput())
                : randomFloat((float) tellyNormalRotationMinSpeed.getInput(), (float) tellyNormalRotationMaxSpeed.getInput());
    }

    private float getSpeed() {
        if (!mc.thePlayer.onGround) {
            return (float) airMotion.getInput() / 100.0F;
        }
        return Utils.getSpeedAmplifier() > 0
                ? (float) speedMotion.getInput() / 100.0F
                : (float) groundMotion.getInput() / 100.0F;
    }

    private double getRandomOffset() {
        return 0.2155 - Utils.randomizeDouble(1.0E-4, 9.0E-4);
    }

    private float getCurrentYaw() {
        return adjustYaw(mc.thePlayer.rotationYaw,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe);
    }

    private boolean isDiagonal(float yaw) {
        float absYaw = Math.abs(yaw % 90.0F);
        return absYaw > 20.0F && absYaw < 70.0F;
    }

    private EnumFacing yawToFacing(float yaw) {
        if (yaw < -135.0F || yaw > 135.0F) {
            return EnumFacing.NORTH;
        } else if (yaw < -45.0F) {
            return EnumFacing.EAST;
        }
        return yaw < 45.0F ? EnumFacing.SOUTH : EnumFacing.WEST;
    }

    private double distanceToEdge(EnumFacing enumFacing) {
        switch (enumFacing) {
            case NORTH: return mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
            case EAST:  return Math.ceil(mc.thePlayer.posX) - mc.thePlayer.posX;
            case SOUTH: return Math.ceil(mc.thePlayer.posZ) - mc.thePlayer.posZ;
            case WEST:
            default:    return mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        }
    }

    // ───────────────────────────── eagle ─────────────────────────────

    private boolean isNearEdge() {
        if (!mc.thePlayer.onGround) {
            return false;
        }
        double fracX = mc.thePlayer.posX - Math.floor(mc.thePlayer.posX);
        double fracZ = mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ);
        double threshold = edgeDistance.getInput();
        double minDist = Math.min(Math.min(fracX, 1.0 - fracX), Math.min(fracZ, 1.0 - fracZ));
        return minDist <= threshold;
    }

    private boolean shouldSneak() {
        if (!eagle.isToggled() || !mc.thePlayer.onGround) {
            return false;
        }
        if (this.eagleBlocksPlaced < (int) blocksPerSneak.getInput()) {
            return false;
        }
        if (System.currentTimeMillis() - this.eagleLastSneakTime < (long) sneakDelay.getInput()) {
            return false;
        }
        return this.isNearEdge();
    }

    private void updateEagle() {
        if (!eagle.isToggled()) {
            this.eagleSneaking = false;
            this.eagleSneakTicks = 0;
            return;
        }
        if (this.eagleSneakTicks > 0) {
            this.eagleSneakTicks--;
            if (this.eagleSneakTicks == 0) {
                this.eagleSneaking = false;
            }
            return;
        }
        if (this.shouldSneak()) {
            this.eagleSneaking = true;
            this.eagleSneakTicks = 2;
            this.eagleLastSneakTime = System.currentTimeMillis();
            this.eagleBlocksPlaced = 0;
        }
    }

    // ───────────────────── Myau utility equivalents ─────────────────────

    /**
     * Snaps an angle to the player's real mouse-sensitivity GCD. Vanilla can only produce
     * rotations that are multiples of this step, so anything else is trivially detectable.
     * Rounds rather than truncating — truncation is asymmetric around zero and would leave
     * negative yaws off-grid.
     */
    private static float quantizeAngle(float value) {
        return quantizeFrom(0.0F, value);
    }

    /** LiquidBounce RotationUtils.getFixedAngleDelta — (sens * 0.6 + 0.2)^3 * 1.2. */
    private static float gcd() {
        float sensitivity = mc.gameSettings != null ? mc.gameSettings.mouseSensitivity : 0.5F;
        float f = sensitivity * 0.6F + 0.2F;
        return f * f * f * 1.2F;
    }

    /**
     * LiquidBounce RotationUtils.getFixedSensitivityAngle — quantizes the DELTA from the
     * angle we last sent, not the absolute angle. The server sees deltas, so rounding the
     * absolute value leaves the deltas off-grid even though each angle looks fine alone.
     */
    private static float quantizeFrom(float start, float target) {
        float gcd = gcd();
        if (gcd <= 1.0E-6F) {
            return target;
        }
        return start + (float) (Math.round((double) (target - start) / (double) gcd) * (double) gcd);
    }

    /** Moves current toward target by at most maxStep degrees, snapped to the sensitivity grid. */
    private static float stepAngle(float current, float target, float maxStep) {
        current = MathHelper.wrapAngleTo180_float(current);
        target = MathHelper.wrapAngleTo180_float(target);
        float diff = MathHelper.wrapAngleTo180_float(target - current);
        if (maxStep > 0.0F && Math.abs(diff) > maxStep) {
            diff = maxStep * Math.signum(diff);
        }
        return MathHelper.wrapAngleTo180_float(quantizeFrom(current, current + diff));
    }

    /** Myau RotationUtil.wrapAngleDiff — expresses {@code angle} unwrapped relative to {@code base}. */
    private static float wrapAngleDiff(float angle, float base) {
        return base + MathHelper.wrapAngleTo180_float(angle - base);
    }

    /** Myau RotationUtil.clampAngle — clamps a signed delta to +/- max. */
    private static float clampAngle(float diff, float max) {
        return MathHelper.clamp_float(diff, -Math.abs(max), Math.abs(max));
    }

    private static float randomFloat(float min, float max) {
        if (max <= min) {
            return min;
        }
        return (float) Utils.randomizeDouble(min, max);
    }

    /** Myau MoveUtil.adjustYaw — yaw of the direction the player is actually moving. */
    private static float adjustYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0F) {
            yaw += 180.0F;
        }
        float strafeMod = forward < 0.0F ? -0.5F : (forward > 0.0F ? 0.5F : 1.0F);
        if (strafe > 0.0F) {
            yaw -= 90.0F * strafeMod;
        } else if (strafe < 0.0F) {
            yaw += 90.0F * strafeMod;
        }
        return MathHelper.wrapAngleTo180_float(yaw);
    }

    private static boolean isForwardPressed() {
        return mc.thePlayer != null && mc.thePlayer.movementInput != null
                && (mc.thePlayer.movementInput.moveForward != 0.0F || mc.thePlayer.movementInput.moveStrafe != 0.0F);
    }

    private static float getMoveYaw() {
        return adjustYaw(mc.thePlayer.rotationYaw,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe);
    }

    private static double getHorizontalSpeed() {
        return Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
    }

    /** Myau MoveUtil.setSpeed(speed, yaw). */
    private static void setSpeed(double speed, float yaw) {
        double rad = Math.toRadians(yaw);
        mc.thePlayer.motionX = -Math.sin(rad) * speed;
        mc.thePlayer.motionZ = Math.cos(rad) * speed;
    }

    private static boolean isAirAbove() {
        BlockPos above = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY + 2.0, mc.thePlayer.posZ);
        return BlockUtils.replaceable(above);
    }

    private static boolean isAirBelow() {
        BlockPos below = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
        return BlockUtils.replaceable(below);
    }

    /** Myau PlayerUtil.canMove — would the player still be supported after this offset. */
    private static boolean canMove(double motionX, double motionZ, double y) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(motionX, y, motionZ);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    private static boolean isBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return !BlockUtils.isInteractable(block) && block.getMaterial().isSolid()
                && Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private static boolean isHoldingBlock() {
        return isBlock(mc.thePlayer.getHeldItem());
    }

    private void openDebugLog() {
        closeDebugLog();
        if (!debugLog.isToggled()) return;
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "Mindless");
            if (!dir.exists()) dir.mkdirs();
            debugWriter = new PrintWriter(new FileWriter(new File(dir, "testscaffold-debug.log"), false), true);
            debugWriter.println("=== " + getName() + " debug " + new java.util.Date() + " ===");
        }
        catch (Exception ignored) {
            debugWriter = null;
        }
    }

    private void closeDebugLog() {
        if (debugWriter != null) {
            debugWriter.close();
            debugWriter = null;
        }
    }

    private void debug(String message) {
        if (debugWriter != null && (debugTick % 5 == 0 || message.startsWith("PLACE"))) {
            debugWriter.println(System.currentTimeMillis() + " " + message);
        }
    }

    private static final class TargetRotation {
        private final float yaw;
        private final float pitch;
        private final Vec3 hitVec;

        private TargetRotation(float yaw, float pitch, Vec3 hitVec) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.hitVec = hitVec;
        }
    }

    private static final class PlacementRay {
        private final BlockData data;
        private final MovingObjectPosition mop;

        private PlacementRay(BlockData data, MovingObjectPosition mop) {
            this.data = data;
            this.mop = mop;
        }
    }

    public static class BlockData {
        private final BlockPos blockPos;
        private final EnumFacing facing;

        public BlockData(BlockPos blockPos, EnumFacing enumFacing) {
            this.blockPos = blockPos;
            this.facing = enumFacing;
        }

        public BlockPos blockPos() {
            return this.blockPos;
        }

        public EnumFacing facing() {
            return this.facing;
        }
    }
}
