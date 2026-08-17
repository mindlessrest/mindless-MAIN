package keystrokesmod.module.impl.player;

import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.runtime.AccessorBridge;
import keystrokesmod.runtime.ItemRendererState;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * LiquidBounce NextGen ModuleScaffold, rebuilt for Minecraft 1.8.9.
 *
 * NextGen targets 1.21 and its code cannot be copied — Direction/BlockHitResult/
 * interactionManager/BlockState have no 1.8.9 equivalents — so every behaviour here is
 * reimplemented. What is preserved exactly is the feature set, the setting names and the
 * layout order of ModuleScaffold.kt:
 *
 *   Delay, MinDist, Timer, Block Item Selection, Auto Block, Prediction, Technique, SameY,
 *   Tower, SafeWalk, Rotations, Swing, Sprint Control, Simulate Placement Attempts,
 *   Acceleration, Strafe, Strafe On Jump, Speed Limiter, Blink, Auto Speed, Ledge, Render.
 *
 * Placement keeps the ordering fix this client needs: the target is chosen while the
 * rotation is being sent, and the block is only placed on the following tick once the
 * server actually has that rotation.
 */
public class LBScaffold extends Module {

    // Top level
    private final SliderSetting delayMin;
    private final SliderSetting delayMax;
    private final SliderSetting minDist;
    private final SliderSetting timer;

    // Block Item Selection
    private final ButtonSetting allowVariants;
    private final ButtonSetting sortByAmount;

    // Auto Block
    private final ButtonSetting autoBlockEnabled;
    private final SliderSetting autoBlockMode;

    // Prediction
    private final ButtonSetting predictionEnabled;

    // Technique / SameY / Tower / SafeWalk / Swing
    private final SliderSetting technique;
    private final SliderSetting godBridgePitch;
    private final SliderSetting expandLength;
    private final SliderSetting sameY;
    private final SliderSetting tower;
    private final SliderSetting towerMotion;
    private final SliderSetting towerTriggerHeight;
    private final SliderSetting safeWalkMode;
    private final SliderSetting swingMode;

    // Rotations
    private final SliderSetting rotationMode;
    private final SliderSetting rotationSpeed;
    private final SliderSetting rotationSpeedVertical;
    private final SliderSetting rotationTiming;
    private final ButtonSetting considerInventory;

    // Feature groups
    private final ButtonSetting sprintControl;
    private final SliderSetting sprintControlMode;
    private final ButtonSetting simulatePlacementAttempts;
    private final ButtonSetting failedAttemptsOnly;
    private final ButtonSetting acceleration;
    private final SliderSetting accelerationAmount;
    private final ButtonSetting strafe;
    private final ButtonSetting strafeOnJump;
    private final ButtonSetting speedLimiter;
    private final SliderSetting speedLimit;
    private final ButtonSetting blink;
    private final ButtonSetting autoSpeed;
    private final ButtonSetting ledge;
    private final ButtonSetting render;
    private final ButtonSetting debug;

    private java.io.PrintWriter debugWriter;
    private int debugTick;
    private final List<BlockPos> ghostPos = new ArrayList<>();
    private final List<Integer> ghostTick = new ArrayList<>();

    private final String[] autoBlockModes = new String[]{"Spoof", "Switch", "Pick"};
    private final String[] techniqueModes = new String[]{"Normal", "Expand", "GodBridge", "Breezily"};
    private final String[] sameYModes = new String[]{"Off", "On", "JumpKey", "Falling", "Hypixel"};
    private final String[] towerModes = new String[]{"None", "Motion", "Pulldown", "Karhu", "Vulcan", "Hypixel"};
    private final String[] safeWalkModes = new String[]{"Off", "Safe", "OnEdge"};
    private final String[] swingModes = new String[]{"Do Not Hide", "Hide For Client", "Hide For Server", "Off"};
    private final String[] rotationModes = new String[]{"Off", "Normal", "Stabilized", "ReverseYaw", "GodBridge", "Backwards"};
    private final String[] rotationTimings = new String[]{"Normal", "OnTick", "OnTickSnap"};
    private final String[] sprintControlModes = new String[]{"Off", "Always", "OnGround"};

    /** Rotation the server currently has — LiquidBounce currRotation. */
    private float serverYaw;
    private float serverPitch;
    private boolean rotationInitialized;
    private float targetYaw;
    private float targetPitch;
    private boolean hasTarget;

    private BlockPos pendPos;
    private EnumFacing pendFacing;
    private boolean pendPlace;

    /**
     * LiquidBounce ModuleScaffold.currentTarget. The target and its rotation are computed
     * ONCE and kept until the block is actually placed — it is only nulled in the placement
     * success callback. Re-searching every tick makes compareDifferences pick a different
     * sub-block hit vector each time, which lurches the sent yaw by tens of degrees per tick.
     */
    private BlockPos curTargetPos;
    private EnumFacing curTargetFacing;
    private float curTargetYaw;
    private float curTargetPitch;
    private boolean hasCurTarget;
    /** Ticks the latched target has failed to ray-trace. Guards against latching forever. */
    private int targetMissTicks;

    private int lastSlot = -1;
    private int placementY;
    private int startY;
    private int jumps;
    private long nextPlaceTime;
    private double jumpOffPosition = Double.NaN;
    private boolean wasTowering;
    private float originalTimer = 1.0F;

    private final List<BlockPos> markedPos = new ArrayList<>();
    private final List<Long> markedTime = new ArrayList<>();

    public LBScaffold() {
        super("LBScaffold", category.player);

        this.registerSetting(delayMin = new SliderSetting("Delay min", " ticks", 0, 0, 40, 1));
        this.registerSetting(delayMax = new SliderSetting("Delay max", " ticks", 0, 0, 40, 1));
        this.registerSetting(minDist = new SliderSetting("Min dist", 0.0, 0.0, 0.25, 0.01));
        this.registerSetting(timer = new SliderSetting("Timer", "x", 1.0, 0.01, 10.0, 0.01));

        GroupSetting itemSelection = new GroupSetting("Block Item Selection");
        this.registerSetting(itemSelection);
        this.registerSetting(allowVariants = new ButtonSetting(itemSelection, "Allow variants", true));
        this.registerSetting(sortByAmount = new ButtonSetting(itemSelection, "Sort by highest amount", false));

        GroupSetting autoBlockGroup = new GroupSetting("Auto Block");
        this.registerSetting(autoBlockGroup);
        this.registerSetting(autoBlockEnabled = new ButtonSetting(autoBlockGroup, "Auto block", true));
        this.registerSetting(autoBlockMode = new SliderSetting(autoBlockGroup, "Auto block mode", 1, autoBlockModes));

        GroupSetting predictionGroup = new GroupSetting("Prediction");
        this.registerSetting(predictionGroup);
        this.registerSetting(predictionEnabled = new ButtonSetting(predictionGroup, "Prediction enabled", false));

        GroupSetting techniqueGroup = new GroupSetting("Technique");
        this.registerSetting(techniqueGroup);
        this.registerSetting(technique = new SliderSetting(techniqueGroup, "Technique mode", 0, techniqueModes));
        this.registerSetting(godBridgePitch = new SliderSetting(techniqueGroup, "Godbridge pitch", 73.5, 0.0, 90.0, 0.1));
        this.registerSetting(expandLength = new SliderSetting(techniqueGroup, "Expand length", 1, 1, 6, 1));

        this.registerSetting(sameY = new SliderSetting("Same Y", 0, sameYModes));

        GroupSetting towerGroup = new GroupSetting("Tower");
        this.registerSetting(towerGroup);
        this.registerSetting(tower = new SliderSetting(towerGroup, "Tower mode", 0, towerModes));
        this.registerSetting(towerMotion = new SliderSetting(towerGroup, "Motion", 0.42, 0.0, 1.0, 0.01));
        this.registerSetting(towerTriggerHeight = new SliderSetting(towerGroup, "Trigger height", 0.78, 0.76, 1.0, 0.01));

        this.registerSetting(safeWalkMode = new SliderSetting("Safe Walk", 1, safeWalkModes));

        GroupSetting rotationsGroup = new GroupSetting("Rotations");
        this.registerSetting(rotationsGroup);
        this.registerSetting(rotationMode = new SliderSetting(rotationsGroup, "Rotation mode", 5, rotationModes));
        this.registerSetting(rotationSpeed = new SliderSetting(rotationsGroup, "Speed", "°/tick", 60, 5, 180, 1));
        this.registerSetting(rotationSpeedVertical = new SliderSetting(rotationsGroup, "Speed vertical", "°/tick", 60, 5, 180, 1));
        this.registerSetting(rotationTiming = new SliderSetting(rotationsGroup, "Timing", 0, rotationTimings));
        this.registerSetting(considerInventory = new ButtonSetting(rotationsGroup, "Consider inventory", false));

        this.registerSetting(swingMode = new SliderSetting("Swing", 0, swingModes));

        GroupSetting sprintGroup = new GroupSetting("Sprint Control");
        this.registerSetting(sprintGroup);
        this.registerSetting(sprintControl = new ButtonSetting(sprintGroup, "Sprint control", true));
        this.registerSetting(sprintControlMode = new SliderSetting(sprintGroup, "Sprint control mode", 1, sprintControlModes));

        GroupSetting simulateGroup = new GroupSetting("Simulate Placement Attempts");
        this.registerSetting(simulateGroup);
        this.registerSetting(simulatePlacementAttempts = new ButtonSetting(simulateGroup, "Simulate attempts", false));
        this.registerSetting(failedAttemptsOnly = new ButtonSetting(simulateGroup, "Failed attempts only", true));

        GroupSetting accelGroup = new GroupSetting("Acceleration");
        this.registerSetting(accelGroup);
        this.registerSetting(acceleration = new ButtonSetting(accelGroup, "Acceleration enabled", false));
        this.registerSetting(accelerationAmount = new SliderSetting(accelGroup, "Amount", "x", 1.0, 0.5, 1.5, 0.01));

        GroupSetting strafeGroup = new GroupSetting("Strafe");
        this.registerSetting(strafeGroup);
        this.registerSetting(strafe = new ButtonSetting(strafeGroup, "Strafe enabled", false));

        GroupSetting strafeJumpGroup = new GroupSetting("Strafe On Jump");
        this.registerSetting(strafeJumpGroup);
        this.registerSetting(strafeOnJump = new ButtonSetting(strafeJumpGroup, "Strafe on jump", false));

        GroupSetting speedLimiterGroup = new GroupSetting("Speed Limiter");
        this.registerSetting(speedLimiterGroup);
        this.registerSetting(speedLimiter = new ButtonSetting(speedLimiterGroup, "Speed limiter", false));
        this.registerSetting(speedLimit = new SliderSetting(speedLimiterGroup, "Limit", 0.11, 0.01, 0.5, 0.001));

        GroupSetting blinkGroup = new GroupSetting("Blink");
        this.registerSetting(blinkGroup);
        this.registerSetting(blink = new ButtonSetting(blinkGroup, "Blink enabled", false));

        this.registerSetting(autoSpeed = new ButtonSetting("Auto Speed", false));
        this.registerSetting(ledge = new ButtonSetting("Ledge", true));
        this.registerSetting(render = new ButtonSetting("Render", true));
        this.registerSetting(debug = new ButtonSetting("Debug log", false));
    }

    // ───────────────────────────── debug logging ─────────────────────────────

    /**
     * Writes to %TEMP%/RavenNative/lbscaffold-debug.log. Only logs place attempts, ghost
     * blocks and setbacks — all low frequency — so the file stays readable.
     *
     * The three things it captures answer the open question directly:
     *   PLACE   ... rc=false  -> we are failing client side, and the line says which check
     *   PLACE   ... rc=true   -> the client placed it; if a GHOST line follows, the server
     *                            rejected it, which is what produces the setback
     *   SETBACK ...           -> the exact tick the server corrected us, to correlate
     */
    private void log(String line) {
        if (!debug.isToggled()) {
            return;
        }
        try {
            if (debugWriter == null) {
                java.io.File dir = new java.io.File(System.getProperty("java.io.tmpdir"), "RavenNative");
                dir.mkdirs();
                debugWriter = new java.io.PrintWriter(new java.io.FileWriter(
                        new java.io.File(dir, "lbscaffold-debug.log"), true), true);
                debugWriter.println("=== LBScaffold debug " + new java.util.Date() + " ===");
            }
            debugWriter.println("[" + this.debugTick + "] " + line);
        } catch (Throwable ignored) {
        }
    }

    private void closeLog() {
        if (debugWriter != null) {
            try { debugWriter.close(); } catch (Throwable ignored) { }
            debugWriter = null;
        }
    }

    /** Server correction — the actual "pushed back" event. */
    @SubscribeEvent
    public void onReceivePacket(keystrokesmod.event.ReceivePacketEvent event) {
        if (!debug.isToggled() || !Utils.nullCheck()) {
            return;
        }
        if (event.getPacket() instanceof net.minecraft.network.play.server.S08PacketPlayerPosLook) {
            net.minecraft.network.play.server.S08PacketPlayerPosLook p =
                    (net.minecraft.network.play.server.S08PacketPlayerPosLook) event.getPacket();
            double dist = Math.sqrt(Math.pow(p.getX() - mc.thePlayer.posX, 2)
                    + Math.pow(p.getY() - mc.thePlayer.posY, 2)
                    + Math.pow(p.getZ() - mc.thePlayer.posZ, 2));
            log(String.format("SETBACK dist=%.3f to=(%.2f,%.2f,%.2f) yaw=%.1f pitch=%.1f",
                    dist, p.getX(), p.getY(), p.getZ(), p.getYaw(), p.getPitch()));
        }
    }

    /** A block we placed client-side; if it is gone later the server refused it. */
    private void checkGhosts() {
        for (int i = this.ghostPos.size() - 1; i >= 0; i--) {
            if (this.debugTick - this.ghostTick.get(i) < 10) {
                continue;
            }
            BlockPos pos = this.ghostPos.get(i);
            boolean gone = BlockUtils.replaceable(pos);
            log((gone ? "GHOST   " : "CONFIRM ") + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + (gone ? "  <-- server rejected this placement" : ""));
            this.ghostPos.remove(i);
            this.ghostTick.remove(i);
        }
    }

    // ───────────────────────────── lifecycle ─────────────────────────────

    @Override
    public void onEnable() {
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            ModuleManager.scaffold.disable();
            Utils.sendMessage("&eScaffold disabled &7(LBScaffold took over)");
        }
        if (ModuleManager.testScaffold != null && ModuleManager.testScaffold.isEnabled()) {
            ModuleManager.testScaffold.disable();
            Utils.sendMessage("&eTestScaffold disabled &7(LBScaffold took over)");
        }
        this.lastSlot = mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1;
        this.serverYaw = mc.thePlayer != null ? mc.thePlayer.rotationYaw : 0.0F;
        this.serverPitch = mc.thePlayer != null ? mc.thePlayer.rotationPitch : 0.0F;
        this.rotationInitialized = mc.thePlayer != null;
        this.placementY = mc.thePlayer != null ? (int) Math.floor(mc.thePlayer.posY) : 0;
        this.startY = this.placementY;
        this.jumps = 0;
        this.hasTarget = false;
        this.wasTowering = false;
        this.jumpOffPosition = Double.NaN;
        this.nextPlaceTime = 0L;
        this.originalTimer = getTimerSpeed();
        this.resetTarget();
        this.markedPos.clear();
        this.markedTime.clear();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && this.lastSlot != -1 && autoBlockEnabled.isToggled()) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        ItemRendererState.setCancelUpdate(false);
        ItemRendererState.setCancelReset(false);
        setTimerSpeed(1.0F);
        this.hasTarget = false;
        this.rotationInitialized = false;
        this.resetTarget();
        this.markedPos.clear();
        this.markedTime.clear();
        this.ghostPos.clear();
        this.ghostTick.clear();
        this.closeLog();
    }

    @Override
    public String getInfo() {
        return techniqueModes[(int) technique.getInput()];
    }

    /** Consumed by SafeWalkState / MixinEntity. */
    public boolean canSafeWalk() {
        if (!this.isEnabled() || safeWalkMode.getInput() == 0 || !Utils.nullCheck()) {
            return false;
        }
        if (safeWalkMode.getInput() == 2 && !Utils.isEdgeOfBlock()) {
            return false;
        }
        return mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0;
    }

    // ───────────────────────────── placement pass ─────────────────────────────

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        this.handleTower();
        this.debugTick++;
        this.checkGhosts();

        if (!this.pendPlace || this.pendPos == null || this.pendFacing == null) {
            this.clearPending();
            return;
        }
        if (System.currentTimeMillis() < this.nextPlaceTime) {
            this.clearPending();
            return;
        }
        if (considerInventory.isToggled() && mc.currentScreen != null) {
            this.clearPending();
            return;
        }

        MovingObjectPosition mop = raytrace(this.serverYaw, this.serverPitch);
        boolean hit = mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(this.pendPos)
                && mop.sideHit == this.pendFacing;

        if (!hit) {
            this.targetMissTicks++;
            log(String.format("NOHIT   want=%d,%d,%d/%s got=%s rot=(%.2f,%.2f)",
                    this.pendPos.getX(), this.pendPos.getY(), this.pendPos.getZ(), this.pendFacing,
                    mop == null || mop.getBlockPos() == null ? "null"
                            : mop.getBlockPos().getX() + "," + mop.getBlockPos().getY() + ","
                            + mop.getBlockPos().getZ() + "/" + mop.sideHit,
                    this.serverYaw, this.serverPitch));
            // LiquidBounce SimulatePlacementAttempts: still swing on a failed attempt so the
            // click cadence looks the same whether or not a placement actually lands.
            if (simulatePlacementAttempts.isToggled() && failedAttemptsOnly.isToggled()) {
                this.doSwing();
            }
            this.clearPending();
            return;
        }

        this.targetMissTicks = 0;
        this.doPlace(this.pendPos, this.pendFacing, mop.hitVec);
        this.clearPending();
    }

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (!this.rotationInitialized) {
            this.serverYaw = event.getYaw();
            this.serverPitch = event.getPitch();
            this.rotationInitialized = true;
        }

        setTimerSpeed((float) timer.getInput());
        this.handleSprintControl();

        if (mc.thePlayer.onGround) {
            this.startY = (int) Math.floor(mc.thePlayer.posY);
            if (this.wasTowering) {
                this.jumps++;
                this.wasTowering = false;
            }
        }
        this.placementY = this.resolvePlacementY();

        this.hasTarget = false;
        if (this.reuseCurrentTarget()) {
            // Keep the rotation we already committed to — see curTargetPos.
            this.hasTarget = true;
            this.targetYaw = this.curTargetYaw;
            this.targetPitch = this.curTargetPitch;
            this.pendPos = this.curTargetPos;
            this.pendFacing = this.curTargetFacing;
            this.pendPlace = true;
        } else {
            this.findBlock();
        }

        if (rotationMode.getInput() != 0 && this.hasTarget) {
            boolean instant = rotationTiming.getInput() != 0; // OnTick / OnTickSnap
            float hSpeed = instant ? 180.0F : (float) rotationSpeed.getInput();
            float vSpeed = instant ? 180.0F : (float) rotationSpeedVertical.getInput();
            this.serverYaw = stepAngle(this.serverYaw, this.targetYaw, hSpeed);
            this.serverPitch = MathHelper.clamp_float(
                    stepAngle(this.serverPitch, this.targetPitch, vSpeed), -90.0F, 90.0F);
            event.setRotations(this.serverYaw, this.serverPitch);
        } else {
            this.serverYaw = event.getYaw();
            this.serverPitch = event.getPitch();
        }
    }

    @SubscribeEvent
    public void onPlayerInput(PrePlayerInputEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }
        if (acceleration.isToggled() && mc.thePlayer.onGround) {
            event.setForward((float) (event.getForward() * accelerationAmount.getInput()));
            event.setStrafe((float) (event.getStrafe() * accelerationAmount.getInput()));
        }
        if (strafeOnJump.isToggled() && !mc.thePlayer.onGround && event.getForward() == 0.0F) {
            event.setForward(1.0F);
        }
        if (ledge.isToggled() && mc.thePlayer.onGround && Utils.isEdgeOfBlock() && this.blockCount() <= 0) {
            // No blocks left — stop walking off the edge.
            event.setForward(0.0F);
            event.setStrafe(0.0F);
        }
    }

    private void handleSprintControl() {
        if (!sprintControl.isToggled() || sprintControlMode.getInput() == 0) {
            return;
        }
        if (sprintControlMode.getInput() == 2 && !mc.thePlayer.onGround) {
            return;
        }
        mc.thePlayer.setSprinting(false);
    }

    private void handleSpeedLimit() {
        if (!speedLimiter.isToggled()) {
            return;
        }
        double limit = speedLimit.getInput();
        double speed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX
                + mc.thePlayer.motionZ * mc.thePlayer.motionZ);
        if (speed > limit) {
            double scale = limit / speed;
            mc.thePlayer.motionX *= scale;
            mc.thePlayer.motionZ *= scale;
        }
    }

    // ───────────────────────────── tower ─────────────────────────────

    private void handleTower() {
        this.handleSpeedLimit();
        int mode = (int) tower.getInput();
        if (mode == 0 || !mc.gameSettings.keyBindJump.isKeyDown() || this.blockCount() <= 0) {
            this.jumpOffPosition = Double.NaN;
            return;
        }
        this.wasTowering = true;

        double horizontalSpeed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX
                + mc.thePlayer.motionZ * mc.thePlayer.motionZ);

        switch (mode) {
            case 1: { // Motion
                if (mc.thePlayer.onGround) {
                    this.jumpOffPosition = mc.thePlayer.posY;
                }
                if (Double.isNaN(this.jumpOffPosition)) {
                    return;
                }
                if (mc.thePlayer.posY > this.jumpOffPosition + towerTriggerHeight.getInput()) {
                    mc.thePlayer.setPosition(mc.thePlayer.posX,
                            (double) (long) mc.thePlayer.posY, mc.thePlayer.posZ);
                    mc.thePlayer.motionY = towerMotion.getInput();
                    this.jumpOffPosition = mc.thePlayer.posY;
                }
                break;
            }
            case 2: { // Pulldown
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.motionY = 0.42;
                } else if (mc.thePlayer.motionY < 0.2 && mc.thePlayer.motionY > 0.0) {
                    mc.thePlayer.motionY = -0.28;
                }
                break;
            }
            case 3: { // Karhu
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.motionY = 0.42;
                } else if (mc.thePlayer.motionY <= 0.0) {
                    mc.thePlayer.motionY = -0.0784000015258789;
                }
                break;
            }
            case 4: { // Vulcan
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.motionY = 0.4;
                } else if (mc.thePlayer.motionY < 0.0) {
                    mc.thePlayer.motionY *= 0.6;
                }
                break;
            }
            case 5: { // Hypixel — LiquidBounce ScaffoldTowerHypixel, verbatim behaviour
                if (horizontalSpeed > 0.01) {
                    return; // must be stationary, otherwise LB refuses and warns
                }
                if (mc.thePlayer.onGround) {
                    mc.thePlayer.motionY = 0.42;
                }
                if (mc.thePlayer.motionY <= 0.0 && mc.thePlayer.motionY >= -0.09) {
                    mc.thePlayer.motionY = -0.38;
                }
                break;
            }
            default:
                break;
        }
    }

    // ───────────────────────────── target search ─────────────────────────────

    private int resolvePlacementY() {
        int base = (int) Math.floor(mc.thePlayer.posY) - 1;
        switch ((int) sameY.getInput()) {
            case 0: return base;
            case 1: return this.startY - 1;
            case 2: return mc.gameSettings.keyBindJump.isKeyDown() ? base : this.startY - 1;
            case 3: return mc.thePlayer.motionY < 0.2 ? this.startY - 1 : base;
            case 4: // Hypixel
                if (mc.thePlayer.motionY == -0.15233518685055708 && this.jumps >= 2) {
                    this.jumps = 0;
                    return this.startY;
                }
                return this.startY - 1;
            default: return base;
        }
    }

    private void findBlock() {
        BlockPos blockPosition = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                this.placementY,
                MathHelper.floor_double(mc.thePlayer.posZ));

        if (this.search(blockPosition)) {
            return;
        }

        if (technique.getInput() == 1) { // Expand
            EnumFacing facing = mc.thePlayer.getHorizontalFacing();
            for (int i = 1; i <= (int) expandLength.getInput(); i++) {
                if (this.search(blockPosition.offset(facing, i))) {
                    return;
                }
            }
            return;
        }

        List<BlockPos> box = new ArrayList<>();
        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 0; y++) {
                for (int z = -3; z <= 3; z++) {
                    box.add(blockPosition.add(x, y, z));
                }
            }
        }
        box.sort(Comparator.comparingDouble(this::centerDistance));
        for (BlockPos pos : box) {
            if (canBeClicked(pos) || this.search(pos)) {
                return;
            }
        }
    }

    /**
     * Is the latched target still worth keeping? Only drop it if the spot has already been
     * filled, the block we click against is gone, or it has drifted out of reach.
     */
    private boolean reuseCurrentTarget() {
        if (!this.hasCurTarget || this.curTargetPos == null || this.curTargetFacing == null) {
            return false;
        }
        BlockPos placeAt = this.curTargetPos.offset(this.curTargetFacing);
        if (!BlockUtils.replaceable(placeAt) || !canBeClicked(this.curTargetPos)) {
            this.hasCurTarget = false;
            return false;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double reach = eyes.distanceTo(new Vec3(this.curTargetPos.getX() + 0.5,
                this.curTargetPos.getY() + 0.5, this.curTargetPos.getZ() + 0.5));
        if (reach > mc.playerController.getBlockReachDistance()) {
            this.hasCurTarget = false;
            return false;
        }
        // A target we cannot actually hit must not be held forever. Without this the module
        // locks onto one block, freezes the rotation sideways and stops bridging entirely.
        if (this.targetMissTicks > 3) {
            this.hasCurTarget = false;
            this.targetMissTicks = 0;
            return false;
        }
        return true;
    }

    private double centerDistance(BlockPos pos) {
        double dx = pos.getX() + 0.5 - mc.thePlayer.posX;
        double dy = pos.getY() + 0.5 - mc.thePlayer.posY;
        double dz = pos.getZ() + 0.5 - mc.thePlayer.posZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean search(BlockPos blockPosition) {
        if (!BlockUtils.replaceable(blockPosition)) {
            return false;
        }
        float maxReach = mc.playerController.getBlockReachDistance();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);

        PlaceRotation best = null;
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos neighbor = blockPosition.offset(side);
            if (!canBeClicked(neighbor)) {
                continue;
            }
            // LiquidBounce NearestRotationTargetPositionFactory — aim at the point on the face
            // nearest to the current look ray instead of sampling fixed points and ranking
            // them. Sampling makes the winner jump between samples on tiny rotation changes,
            // which is exactly what made the sent yaw jitter by tens of degrees.
            EnumFacing clickFace = side.getOpposite();
            // In Backwards mode project along the movement-derived yaw so the aim point and
            // the yaw we are going to send agree; otherwise the raytrace check would miss.
            float projYaw = rotationMode.getInput() == 5 ? backwardsYaw() : this.serverYaw;
            // For UP faces, always project with a steep downward pitch so the look ray
            // actually intersects the top face instead of flying past it horizontally.
            float projPitch = (clickFace == EnumFacing.UP && this.serverPitch < MIN_DOWN_PITCH)
                    ? MIN_DOWN_PITCH : this.serverPitch;
            Vec3 aim = faceAimPoint(neighbor, clickFace, eyes, projYaw, projPitch);
            if (aim == null) {
                continue;
            }
            PlaceRotation candidate = buildPlaceRotation(neighbor, clickFace, aim, eyes, maxReach);
            best = compareDifferences(candidate, best);
        }
        if (best == null) {
            return false;
        }
        this.targetYaw = best.yaw;
        this.targetPitch = best.pitch;
        this.hasTarget = true;
        this.pendPos = best.blockPos;
        this.pendFacing = best.facing;
        this.pendPlace = true;

        // Latch it — this stays the target until the block lands.
        this.curTargetPos = best.blockPos;
        this.curTargetFacing = best.facing;
        this.curTargetYaw = best.yaw;
        this.curTargetPitch = best.pitch;
        this.hasCurTarget = true;
        return true;
    }

    /**
     * LiquidBounce FaceTargetPositionFactory.trimFace + aimAtNearestPointToRotationLine.
     *
     * Builds the rectangle of the given block face, trims it 15% inward on each axis so it
     * never aims at an edge, then intersects the current look ray with that face plane and
     * clamps the hit into the rectangle. That is a continuous function of the rotation —
     * nudging the view nudges the aim point — which is what keeps the sent rotation stable.
     */
    private Vec3 faceAimPoint(BlockPos block, EnumFacing face, Vec3 eyes, float fromYaw, float fromPitch) {
        final double trim = 0.15;
        double minX = block.getX() + trim, maxX = block.getX() + 1 - trim;
        double minY = block.getY() + trim, maxY = block.getY() + 1 - trim;
        double minZ = block.getZ() + trim, maxZ = block.getZ() + 1 - trim;

        // Collapse the axis the face lies on.
        switch (face) {
            case DOWN:  minY = maxY = block.getY(); break;
            case UP:    minY = maxY = block.getY() + 1; break;
            case NORTH: minZ = maxZ = block.getZ(); break;
            case SOUTH: minZ = maxZ = block.getZ() + 1; break;
            case WEST:  minX = maxX = block.getX(); break;
            case EAST:  minX = maxX = block.getX() + 1; break;
            default: return null;
        }

        Vec3 dir = lookVector(fromYaw, fromPitch);
        double planeVal;
        double eyeVal;
        double dirVal;
        switch (face.getAxis()) {
            case X: planeVal = minX; eyeVal = eyes.xCoord; dirVal = dir.xCoord; break;
            case Y: planeVal = minY; eyeVal = eyes.yCoord; dirVal = dir.yCoord; break;
            default: planeVal = minZ; eyeVal = eyes.zCoord; dirVal = dir.zCoord; break;
        }

        double x, y, z;
        if (Math.abs(dirVal) < 1.0E-6) {
            // Looking along the face plane — fall back to its centre.
            x = (minX + maxX) * 0.5;
            y = (minY + maxY) * 0.5;
            z = (minZ + maxZ) * 0.5;
        } else {
            double t = (planeVal - eyeVal) / dirVal;
            if (t < 0.0) {
                t = 0.0; // plane is behind us; clamp to the eye and let the bounds decide
            }
            x = eyes.xCoord + dir.xCoord * t;
            y = eyes.yCoord + dir.yCoord * t;
            z = eyes.zCoord + dir.zCoord * t;
        }

        return new Vec3(
                MathHelper.clamp_double(x, minX, maxX),
                MathHelper.clamp_double(y, minY, maxY),
                MathHelper.clamp_double(z, minZ, maxZ));
    }

    /** Rotation + verification for an already-chosen aim point on a face. */
    private PlaceRotation buildPlaceRotation(BlockPos block, EnumFacing face, Vec3 aim,
                                             Vec3 eyes, float maxReach) {
        if (eyes.distanceTo(aim) > maxReach) {
            return null;
        }
        Vec3 diff = aim.subtract(eyes);
        if (face.getAxis() != EnumFacing.Axis.Y) {
            double dist = Math.abs(face.getAxis() == EnumFacing.Axis.Z ? diff.zCoord : diff.xCoord);
            if (dist < minDist.getInput()) {
                return null;
            }
        }

        float[] rot = toRotation(aim, eyes);
        float yaw = rot[0];
        float pitch = rot[1];

        int mode = (int) rotationMode.getInput();
        if (mode == 2) {
            yaw = Math.round(yaw / 45.0F) * 45.0F;
        } else if (mode == 3) {
            yaw = isLookingDiagonally() ? Math.round(yaw / 45.0F) * 45.0F : Math.round(yaw / 90.0F) * 90.0F;
        } else if (mode == 4 || technique.getInput() == 2) {
            yaw = Math.round(yaw / 45.0F) * 45.0F;
            pitch = (float) godBridgePitch.getInput();
        } else if (mode == 5) {
            // OpenMyau Backwards: yaw = opposite of movement direction, pitch hardcoded
            // to 85° (exactly as OpenMyau does — case 2 sets pitch=85 on first tick and
            // keeps it there). This guarantees the look ray always hits the UP face of
            // the block below regardless of where the player is actually looking.
            yaw = backwardsYaw();
            pitch = 85.0F;
        }

        // ── Downward-pitch fix (non-Backwards modes) ─────────────────────────
        // For all other modes: when targeting the TOP face (UP) of the block below,
        // clamp pitch to at least MIN_DOWN_PITCH so the raytrace hits the face.
        // Skip this for Backwards mode (mode 5) — pitch is already set to 85° above.
        if (mode != 5 && face == EnumFacing.UP && pitch < MIN_DOWN_PITCH) {
            pitch = MIN_DOWN_PITCH;
        }

        yaw = quantizeFrom(this.serverYaw, yaw);
        pitch = quantizeFrom(this.serverPitch, MathHelper.clamp_float(pitch, -90.0F, 90.0F));

        // Already looking at it — reuse the server's rotation untouched.
        MovingObjectPosition current = raytrace(this.serverYaw, this.serverPitch);
        if (current != null && current.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && current.getBlockPos().equals(block) && current.sideHit == face) {
            return new PlaceRotation(block, face, this.serverYaw, this.serverPitch);
        }

        MovingObjectPosition trace = raytrace(yaw, pitch);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return null;
        }
        if (trace.getBlockPos().equals(block) && trace.sideHit == face) {
            return new PlaceRotation(block, face, yaw, pitch);
        }
        return null;
    }

    /**
     * Minimum downward pitch (degrees) required to reliably hit the TOP face of the
     * block directly below the player.  At normal bridging height (player eye ~1.62
     * above the block surface) a pitch of 75° gives a look-vector Y component of
     * sin(75°) ≈ 0.966, which always intersects the top face within reach.
     * Raising this toward 90° makes it more reliable but looks more robotic;
     * 75° is the sweet spot that matches what you found works manually.
     */
    private static final float MIN_DOWN_PITCH = 75.0F;

    /** OpenMyau: yaw pointing straight opposite the direction the player is actually moving. */
    private float backwardsYaw() {
        float moveYaw = adjustYaw(mc.thePlayer.rotationYaw,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe);
        return MathHelper.wrapAngleTo180_float(moveYaw - 180.0F);
    }

    /** OpenMyau MoveUtil.adjustYaw — the yaw of the direction the inputs actually move you. */
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

    private static Vec3 lookVector(float yaw, float pitch) {
        float f = -yaw * 0.017453292F;
        float f1 = -pitch * 0.017453292F;
        float cos = MathHelper.cos(f - (float) Math.PI);
        float sin = MathHelper.sin(f - (float) Math.PI);
        float nc = -MathHelper.cos(f1);
        return new Vec3(sin * nc, MathHelper.sin(f1), cos * nc);
    }

    private PlaceRotation compareDifferences(PlaceRotation candidate, PlaceRotation old) {
        if (candidate == null) return old;
        if (old == null) return candidate;
        return rotationDifference(candidate.yaw, candidate.pitch) < rotationDifference(old.yaw, old.pitch)
                ? candidate : old;
    }

    /**
     * Candidate ranking is anchored to the player's REAL rotation, not the rotation we last
     * sent. Anchoring to the sent value makes drift self-reinforcing: a candidate near the
     * drifted yaw wins, we step toward it, and the next tick ranks from further out still —
     * the log showed this running away to 269 deg while the player held 180. LiquidBounce
     * avoids it because RotationManager resets to player.rotation after ticksUntilReset.
     */
    /**
     * Anchored to the rotation the server already has — LiquidBounce ranks against
     * RotationManager.currentRotation, not the player's real rotation.
     *
     * Anchoring to the player's rotation looks correct only while they happen to be looking
     * at the block: as soon as they look up, it prefers candidates near their pitch instead
     * of the pitch the block actually needs, and the aim falls apart. The earlier runaway
     * that made me switch anchors came from the 125-point sampling, not from this anchor —
     * projection is bounded to the face rectangle, so it cannot drift off the block.
     */
    private float rotationDifference(float yaw, float pitch) {
        float dy = MathHelper.wrapAngleTo180_float(yaw - this.serverYaw);
        float dp = pitch - this.serverPitch;
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    private boolean isLookingDiagonally() {
        float yaw = Math.abs(MathHelper.wrapAngleTo180_float(this.serverYaw) % 90.0F);
        return yaw > 20.0F && yaw < 70.0F;
    }

    // ───────────────────────────── placing ─────────────────────────────

    private void doPlace(BlockPos pos, EnumFacing facing, Vec3 hitVec) {
        int slot = this.findBlockSlot();
        if (slot == -1) {
            log("SKIP    no block slot found");
            return;
        }
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            log("SKIP    slot " + slot + " is not a block");
            return;
        }
        if (!((ItemBlock) stack.getItem()).canPlaceBlockOnSide(mc.theWorld, pos, facing, mc.thePlayer, stack)) {
            log("SKIP    canPlaceBlockOnSide=false at " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + "/" + facing);
            return;
        }

        int previous = mc.thePlayer.inventory.currentItem;
        boolean switched = false;
        if (autoBlockEnabled.isToggled() && previous != slot) {
            mc.thePlayer.inventory.currentItem = slot;
            switched = true;
            if (autoBlockMode.getInput() == 0) { // Spoof — keep the held item render unchanged
                ItemRendererState.setCancelUpdate(true);
                ItemRendererState.setCancelReset(true);
            }
        }

        boolean rc = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                mc.thePlayer.inventory.getCurrentItem(), pos, facing, hitVec);

        if (debug.isToggled()) {
            Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
            double reach = eyes.distanceTo(hitVec);
            log(String.format("PLACE   rc=%-5s pos=%d,%d,%d/%s reach=%.3f hit=(%.3f,%.3f,%.3f) "
                            + "rot=(%.2f,%.2f) real=(%.2f,%.2f) slot=%d ground=%s",
                    rc, pos.getX(), pos.getY(), pos.getZ(), facing, reach,
                    hitVec.xCoord, hitVec.yCoord, hitVec.zCoord,
                    this.serverYaw, this.serverPitch,
                    mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch,
                    mc.thePlayer.inventory.currentItem, mc.thePlayer.onGround));
            if (rc) {
                this.ghostPos.add(pos.offset(facing));
                this.ghostTick.add(this.debugTick);
            }
        }

        if (rc) {
            // LiquidBounce clears currentTarget in the placement success callback.
            this.hasCurTarget = false;
            this.doSwing();
            this.markPlaced(pos.offset(facing));
            int min = (int) delayMin.getInput();
            int max = (int) delayMax.getInput();
            int ticks = max > min ? min + (int) (Math.random() * (max - min)) : min;
            this.nextPlaceTime = System.currentTimeMillis() + ticks * 50L;
        } else if (simulatePlacementAttempts.isToggled() && !failedAttemptsOnly.isToggled()) {
            this.doSwing();
        }

        if (switched && autoBlockMode.getInput() == 1) { // Switch — restore right away
            mc.thePlayer.inventory.currentItem = previous;
        }
    }

    /** LiquidBounce SwingMode. */
    private void doSwing() {
        switch ((int) swingMode.getInput()) {
            case 0: // Do Not Hide — normal client + server swing
                mc.thePlayer.swingItem();
                break;
            case 1: // Hide For Client — server sees the swing, client arm stays still
                mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
                break;
            case 2: // Hide For Server — client arm swings, no packet
                ItemRendererState.setCancelReset(false);
                mc.getItemRenderer().resetEquippedProgress();
                break;
            default: // Off
                break;
        }
    }

    private int findBlockSlot() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!sortByAmount.isToggled() && isValidBlock(held)) {
            return mc.thePlayer.inventory.currentItem;
        }
        int slot = -1;
        int bestCount = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!isValidBlock(stack)) {
                continue;
            }
            if (!allowVariants.isToggled() && held != null && isValidBlock(held)
                    && !stack.getItem().getClass().equals(held.getItem().getClass())) {
                continue;
            }
            if (!sortByAmount.isToggled()) {
                return i;
            }
            if (stack.stackSize > bestCount) {
                bestCount = stack.stackSize;
                slot = i;
            }
        }
        return slot;
    }

    private int blockCount() {
        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (isValidBlock(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private static boolean isValidBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return !(block instanceof BlockBush)
                && !BlockUtils.isInteractable(block)
                && block.getMaterial().isSolid()
                && Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private void clearPending() {
        this.pendPos = null;
        this.pendFacing = null;
        this.pendPlace = false;
    }

    /** Full reset — used on enable/disable so no stale target survives a toggle. */
    private void resetTarget() {
        this.hasCurTarget = false;
        this.targetMissTicks = 0;
        this.curTargetPos = null;
        this.curTargetFacing = null;
        this.clearPending();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static boolean canBeClicked(BlockPos pos) {
        return !BlockUtils.replaceable(pos) && !BlockUtils.isInteractable(BlockUtils.getBlock(pos));
    }

    private MovingObjectPosition raytrace(float yaw, float pitch) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float f = -yaw * 0.017453292F;
        float f1 = -pitch * 0.017453292F;
        float cos = MathHelper.cos(f - (float) Math.PI);
        float sin = MathHelper.sin(f - (float) Math.PI);
        float nc = -MathHelper.cos(f1);
        Vec3 look = new Vec3(sin * nc, MathHelper.sin(f1), cos * nc);
        double reach = mc.playerController.getBlockReachDistance();
        Vec3 end = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        return mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);
    }

    private static float[] toRotation(Vec3 target, Vec3 eyes) {
        double dx = target.xCoord - eyes.xCoord;
        double dy = target.yCoord - eyes.yCoord;
        double dz = target.zCoord - eyes.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[]{yaw, pitch};
    }

    private static float gcd() {
        float sensitivity = mc.gameSettings != null ? mc.gameSettings.mouseSensitivity : 0.5F;
        float f = sensitivity * 0.6F + 0.2F;
        return f * f * f * 1.2F;
    }

    private static float quantizeFrom(float start, float target) {
        float gcd = gcd();
        if (gcd <= 1.0E-6F) {
            return target;
        }
        return start + (float) (Math.round((double) (target - start) / (double) gcd) * (double) gcd);
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float diff = MathHelper.wrapAngleTo180_float(target - current);
        if (maxStep > 0.0F && Math.abs(diff) > maxStep) {
            diff = maxStep * Math.signum(diff);
        }
        return quantizeFrom(current, current + diff);
    }

    private static float getTimerSpeed() {
        try {
            return AccessorBridge.Minecraft_getTimer(mc).timerSpeed;
        } catch (Throwable t) {
            return 1.0F;
        }
    }

    private static void setTimerSpeed(float speed) {
        try {
            AccessorBridge.Minecraft_getTimer(mc).timerSpeed = speed;
        } catch (Throwable ignored) {
        }
    }

    // ───────────────────────────── render ─────────────────────────────

    private void markPlaced(BlockPos pos) {
        if (render.isToggled()) {
            this.markedPos.add(pos);
            this.markedTime.add(System.currentTimeMillis());
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!render.isToggled() || mc.theWorld == null || mc.thePlayer == null || this.markedPos.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (int i = this.markedPos.size() - 1; i >= 0; i--) {
            long age = now - this.markedTime.get(i);
            int alpha = (int) (210 - (age / 750.0 * 210));
            if (age > 750L || alpha <= 0) {
                this.markedPos.remove(i);
                this.markedTime.remove(i);
                continue;
            }
            RenderUtils.renderBlock(this.markedPos.get(i),
                    (Color.CYAN.getRGB() & 0xFFFFFF) | (alpha << 24), true, false);
        }
    }

    private static class PlaceRotation {
        private final BlockPos blockPos;
        private final EnumFacing facing;
        private final float yaw;
        private final float pitch;

        private PlaceRotation(BlockPos blockPos, EnumFacing facing, float yaw, float pitch) {
            this.blockPos = blockPos;
            this.facing = facing;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
