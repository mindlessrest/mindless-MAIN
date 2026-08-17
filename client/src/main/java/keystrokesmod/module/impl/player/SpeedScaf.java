package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.runtime.ItemRendererState;
import keystrokesmod.script.model.SimulatedPlayer;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.*;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * SpeedScaf — Bridge Assist edge-sneak + scaffold block placement.
 *
 * Two-tick placement (same as LBScaffold):
 *   Tick N  PreMotion  → find target block/face, compute rotation, send it in the packet.
 *   Tick N+1 PreUpdate → raytrace at the rotation the server now has, place if it hits.
 *
 * Bridge Assist pre-place (ClientRotation) is DISABLED when scaffold has an active target,
 * so the two rotation systems never conflict.
 */
public class SpeedScaf extends Module {

    // ── Bridge Assist settings ────────────────────────────────────────────────
    private final ButtonSetting prePlace;
    private final SliderSetting edgeOffset;
    private final SliderSetting unsneakDelayMin;
    private final SliderSetting unsneakDelayMax;
    private final SliderSetting sneakOnJump;
    private final ButtonSetting sneakKeyPressed;
    private final ButtonSetting holdingBlocks;
    private final ButtonSetting lookingDown;
    private final ButtonSetting notMovingForward;

    // ── Scaffold settings ─────────────────────────────────────────────────────
    private final SliderSetting delayMin;
    private final SliderSetting delayMax;
    private final SliderSetting minDist;
    private final ButtonSetting allowVariants;
    private final ButtonSetting sortByAmount;
    private final ButtonSetting autoBlockEnabled;
    private final SliderSetting autoBlockMode;
    private final SliderSetting safeWalkMode;
    private final SliderSetting rotationMode;
    private final SliderSetting rotationSpeed;
    private final SliderSetting rotationSpeedVertical;
    private final SliderSetting rotationTiming;
    private final SliderSetting swingMode;
    private final ButtonSetting render;
    private final ButtonSetting debugLog;

    // ── Bridge Assist state ───────────────────────────────────────────────────
    private boolean sneakingFromModule;
    private boolean placed;
    private boolean forceRelease;
    private int sneakJumpDelayTicks = -1;
    private int sneakJumpStartTick  = -1;
    private int unsneakDelayTicks   = -1;
    private int unsneakStartTick    = -1;
    private double trembleOffset    = 0;

    // ── Scaffold state ────────────────────────────────────────────────────────
    /** Rotation the server currently has (updated every PreMotion). */
    private float serverYaw;
    private float serverPitch;
    private boolean rotationInitialized;

    /** Target rotation we want to reach. */
    private float targetYaw;
    private float targetPitch;
    private boolean hasTarget;

    /** Pending placement — set in PreMotion, consumed in PreUpdate. */
    private BlockPos pendPos;
    private EnumFacing pendFacing;
    private boolean pendPlace;

    /** Latched target — kept until the block lands. */
    private BlockPos curTargetPos;
    private EnumFacing curTargetFacing;
    private float curTargetYaw;
    private float curTargetPitch;
    private boolean hasCurTarget;
    private int targetMissTicks;

    private int lastSlot = -1;
    private long nextPlaceTime;
    private int debugTick;

    private final List<BlockPos> markedPos  = new ArrayList<>();
    private final List<Long>     markedTime = new ArrayList<>();

    private PrintWriter logWriter;

    private static final float MIN_DOWN_PITCH = 75.0F;

    public SpeedScaf() {
        super("SpeedScaf", category.player);

        // ── Bridge Assist ─────────────────────────────────────────────────────
        this.registerSetting(prePlace = new ButtonSetting("Pre place", true));

        GroupSetting sneakingGroup = new GroupSetting("Sneaking");
        this.registerSetting(sneakingGroup);
        this.registerSetting(edgeOffset      = new SliderSetting(sneakingGroup, "Edge offset",   " blocks", 0.4, 0.0, 0.5, 0.01));
        this.registerSetting(unsneakDelayMin = new SliderSetting(sneakingGroup, "Delay min",     "ms",      0,   0,   300, 5));
        this.registerSetting(unsneakDelayMax = new SliderSetting(sneakingGroup, "Delay max",     "ms",      0,   0,   300, 5));
        this.registerSetting(sneakOnJump     = new SliderSetting(sneakingGroup, "Sneak on jump", "ms",      0,   0,   500, 5));

        GroupSetting conditionsGroup = new GroupSetting("Conditions");
        this.registerSetting(conditionsGroup);
        this.registerSetting(sneakKeyPressed  = new ButtonSetting(conditionsGroup, "Sneak key pressed",  true));
        this.registerSetting(holdingBlocks    = new ButtonSetting(conditionsGroup, "Holding blocks",     true));
        this.registerSetting(lookingDown      = new ButtonSetting(conditionsGroup, "Looking down",       false));
        this.registerSetting(notMovingForward = new ButtonSetting(conditionsGroup, "Not moving forward", false));

        // ── Scaffold ──────────────────────────────────────────────────────────
        this.registerSetting(delayMin = new SliderSetting("Delay min", " ticks", 0, 0, 40, 1));
        this.registerSetting(delayMax = new SliderSetting("Delay max", " ticks", 0, 0, 40, 1));
        this.registerSetting(minDist  = new SliderSetting("Min dist",  0.0, 0.0, 0.25, 0.01));

        GroupSetting itemGroup = new GroupSetting("Block Item Selection");
        this.registerSetting(itemGroup);
        this.registerSetting(allowVariants = new ButtonSetting(itemGroup, "Allow variants",         true));
        this.registerSetting(sortByAmount  = new ButtonSetting(itemGroup, "Sort by highest amount", false));

        GroupSetting abGroup = new GroupSetting("Auto Block");
        this.registerSetting(abGroup);
        this.registerSetting(autoBlockEnabled = new ButtonSetting(abGroup, "Auto block",      true));
        this.registerSetting(autoBlockMode    = new SliderSetting(abGroup, "Auto block mode", 1,
                new String[]{"Spoof", "Switch", "Pick"}));

        this.registerSetting(safeWalkMode = new SliderSetting("Safe Walk", 1,
                new String[]{"Off", "Safe", "OnEdge"}));

        GroupSetting rotGroup = new GroupSetting("Rotations");
        this.registerSetting(rotGroup);
        this.registerSetting(rotationMode          = new SliderSetting(rotGroup, "Rotation mode",  5,
                new String[]{"Off", "Normal", "Stabilized", "ReverseYaw", "GodBridge", "Backwards"}));
        this.registerSetting(rotationSpeed         = new SliderSetting(rotGroup, "Speed",          "°/tick", 60, 5, 180, 1));
        this.registerSetting(rotationSpeedVertical = new SliderSetting(rotGroup, "Speed vertical", "°/tick", 60, 5, 180, 1));
        this.registerSetting(rotationTiming        = new SliderSetting(rotGroup, "Timing",         0,
                new String[]{"Normal", "OnTick", "OnTickSnap"}));

        this.registerSetting(swingMode = new SliderSetting("Swing", 0,
                new String[]{"Do Not Hide", "Hide For Client", "Hide For Server", "Off"}));
        this.registerSetting(render   = new ButtonSetting("Render",    true));
        this.registerSetting(debugLog = new ButtonSetting("Debug log", false));
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        if (ModuleManager.lbScaffold != null && ModuleManager.lbScaffold.isEnabled()) {
            ModuleManager.lbScaffold.disable();
            Utils.sendMessage("&eLBScaffold disabled &7(SpeedScaf took over)");
        }
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            ModuleManager.scaffold.disable();
            Utils.sendMessage("&eScaffold disabled &7(SpeedScaf took over)");
        }
        if (mc.thePlayer != null) {
            this.lastSlot    = mc.thePlayer.inventory.currentItem;
            this.serverYaw   = mc.thePlayer.rotationYaw;
            this.serverPitch = mc.thePlayer.rotationPitch;
        }
        this.rotationInitialized = mc.thePlayer != null;
        this.hasTarget     = false;
        this.nextPlaceTime = 0L;
        this.debugTick     = 0;
        this.resetTarget();
        this.markedPos.clear();
        this.markedTime.clear();
        this.sneakingFromModule = false;
        this.placed      = false;
        this.forceRelease = false;
        this.trembleOffset = 0;
        this.resetUnsneak();
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null && this.lastSlot != -1 && autoBlockEnabled.isToggled()) {
            mc.thePlayer.inventory.currentItem = this.lastSlot;
        }
        ItemRendererState.setCancelUpdate(false);
        ItemRendererState.setCancelReset(false);
        this.hasTarget = false;
        this.rotationInitialized = false;
        this.resetTarget();
        this.markedPos.clear();
        this.markedTime.clear();
        this.sneakingFromModule = false;
        this.trembleOffset = 0;
        this.resetUnsneak();
        closeLog();
    }

    public boolean canSafeWalk() {
        if (!this.isEnabled() || safeWalkMode.getInput() == 0 || !Utils.nullCheck()) return false;
        if (safeWalkMode.getInput() == 2 && !Utils.isEdgeOfBlock()) return false;
        return mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0;
    }

    // ── Debug ─────────────────────────────────────────────────────────────────

    private void log(String msg) {
        if (!debugLog.isToggled()) return;
        try {
            if (logWriter == null) {
                File dir = new File(System.getProperty("java.io.tmpdir"), "RavenNative");
                dir.mkdirs();
                logWriter = new PrintWriter(new FileWriter(new File(dir, "speedscaf-debug.log"), true), true);
                logWriter.println("=== SpeedScaf debug " + new Date() + " ===");
            }
            logWriter.println("[" + debugTick + "] " + msg);
        } catch (Throwable ignored) {}
    }

    private void closeLog() {
        if (logWriter != null) { try { logWriter.close(); } catch (Throwable ignored) {} logWriter = null; }
    }

    // ── Tick N+1: place block (server now has the rotation) ───────────────────

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!Utils.nullCheck()) return;
        debugTick++;

        if (!this.pendPlace || this.pendPos == null || this.pendFacing == null) {
            this.clearPending(); return;
        }
        if (System.currentTimeMillis() < this.nextPlaceTime) {
            this.clearPending(); return;
        }

        MovingObjectPosition mop = raytrace(this.serverYaw, this.serverPitch);
        boolean hit = mop != null
                && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(this.pendPos)
                && mop.sideHit == this.pendFacing;

        if (!hit) {
            this.targetMissTicks++;
            log(String.format("NOHIT want=%d,%d,%d/%s got=%s rot=(%.2f,%.2f)",
                    this.pendPos.getX(), this.pendPos.getY(), this.pendPos.getZ(), this.pendFacing,
                    mop == null || mop.getBlockPos() == null ? "null"
                            : mop.getBlockPos().getX() + "," + mop.getBlockPos().getY() + ","
                            + mop.getBlockPos().getZ() + "/" + mop.sideHit,
                    this.serverYaw, this.serverPitch));
            this.clearPending(); return;
        }

        this.targetMissTicks = 0;
        this.doPlace(this.pendPos, this.pendFacing, mop.hitVec);
        this.clearPending();
    }

    // ── Tick N: find target + set rotation in packet ──────────────────────────

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck()) return;
        if (!this.rotationInitialized) {
            this.serverYaw   = event.getYaw();
            this.serverPitch = event.getPitch();
            this.rotationInitialized = true;
        }

        int placementY = (int) Math.floor(mc.thePlayer.posY) - 1;
        this.hasTarget = false;

        if (this.reuseCurrentTarget()) {
            this.hasTarget   = true;
            this.targetYaw   = this.curTargetYaw;
            this.targetPitch = this.curTargetPitch;
            this.pendPos     = this.curTargetPos;
            this.pendFacing  = this.curTargetFacing;
            this.pendPlace   = true;
        } else {
            this.findBlock(placementY);
        }

        if (rotationMode.getInput() != 0 && this.hasTarget) {
            boolean instant = rotationTiming.getInput() != 0;
            float hSpeed = instant ? 180.0F : (float) rotationSpeed.getInput();
            float vSpeed = instant ? 180.0F : (float) rotationSpeedVertical.getInput();
            this.serverYaw   = stepAngle(this.serverYaw,   this.targetYaw,   hSpeed);
            this.serverPitch = MathHelper.clamp_float(
                    stepAngle(this.serverPitch, this.targetPitch, vSpeed), -90.0F, 90.0F);
            event.setRotations(this.serverYaw, this.serverPitch);
        } else {
            this.serverYaw   = event.getYaw();
            this.serverPitch = event.getPitch();
        }
    }

    // ── Bridge Assist: sneak at edge ──────────────────────────────────────────

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        handleSneak(e);
    }

    // ── Bridge Assist: track placements ──────────────────────────────────────

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement c08 = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (c08.getPlacedBlockDirection() != 255 && sneakingFromModule && sneakKeyPressed.isToggled()) {
                placed = true;
            }
        }
    }

    // ── Bridge Assist: pre-place rotation ────────────────────────────────────
    // Only fires when scaffold has NO active target.

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!prePlace.isToggled()) return;
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;
        // Don't fight scaffold rotation when it has a target
        if (this.hasTarget) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;
        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70f) return;
        if (notMovingForward.isToggled() && mc.thePlayer.movementInput.moveForward > 0f) return;

        float basePitch = e.pitch != null ? e.pitch : this.serverPitch;
        double reach    = mc.playerController.getBlockReachDistance();

        TargetResult target = findPrePlaceTarget(basePitch, reach);
        if (target == null) return;

        float baseYaw = e.yaw != null ? e.yaw : this.serverYaw;
        float[] sm    = RotationUtils.smoothRotation(baseYaw, basePitch, target.yaw, target.pitch, 15, 20f);

        trembleOffset += (Math.random() - 0.5) * 0.8 - trembleOffset * 0.1;
        if (trembleOffset >  1.5) trembleOffset =  1.5;
        if (trembleOffset < -1.5) trembleOffset = -1.5;

        e.setYaw(sm[0]);
        e.setPitch(RotationUtils.clampPitch(sm[1] + (float) trembleOffset));
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!render.isToggled() || mc.theWorld == null || mc.thePlayer == null || this.markedPos.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (int i = this.markedPos.size() - 1; i >= 0; i--) {
            long age   = now - this.markedTime.get(i);
            int  alpha = (int) (210 - (age / 750.0 * 210));
            if (age > 750L || alpha <= 0) {
                this.markedPos.remove(i);
                this.markedTime.remove(i);
                continue;
            }
            RenderUtils.renderBlock(this.markedPos.get(i),
                    (Color.CYAN.getRGB() & 0xFFFFFF) | (alpha << 24), true, false);
        }
    }

    // ── Scaffold: target search ───────────────────────────────────────────────

    private void findBlock(int placementY) {
        BlockPos base = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                placementY,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (this.search(base)) return;

        List<BlockPos> box = new ArrayList<>();
        for (int x = -3; x <= 3; x++)
            for (int y = -2; y <= 0; y++)
                for (int z = -3; z <= 3; z++)
                    box.add(base.add(x, y, z));
        box.sort((a, b) -> Double.compare(centerDistance(a), centerDistance(b)));
        for (BlockPos pos : box) {
            if (canBeClicked(pos) || this.search(pos)) return;
        }
    }

    private boolean reuseCurrentTarget() {
        if (!this.hasCurTarget || this.curTargetPos == null || this.curTargetFacing == null) return false;
        BlockPos placeAt = this.curTargetPos.offset(this.curTargetFacing);
        if (!BlockUtils.replaceable(placeAt) || !canBeClicked(this.curTargetPos)) {
            this.hasCurTarget = false; return false;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double reach = eyes.distanceTo(new Vec3(
                this.curTargetPos.getX() + 0.5,
                this.curTargetPos.getY() + 0.5,
                this.curTargetPos.getZ() + 0.5));
        if (reach > mc.playerController.getBlockReachDistance()) {
            this.hasCurTarget = false; return false;
        }
        if (this.targetMissTicks > 3) {
            this.hasCurTarget = false; this.targetMissTicks = 0; return false;
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
        if (!BlockUtils.replaceable(blockPosition)) return false;
        float maxReach = mc.playerController.getBlockReachDistance();
        Vec3  eyes     = mc.thePlayer.getPositionEyes(1.0F);

        PlaceRotation best = null;
        for (EnumFacing side : EnumFacing.values()) {
            BlockPos neighbor = blockPosition.offset(side);
            if (!canBeClicked(neighbor)) continue;
            EnumFacing clickFace = side.getOpposite();
            float projYaw   = rotationMode.getInput() == 5 ? backwardsYaw() : this.serverYaw;
            float projPitch = (clickFace == EnumFacing.UP && this.serverPitch < MIN_DOWN_PITCH)
                    ? MIN_DOWN_PITCH : this.serverPitch;
            Vec3 aim = faceAimPoint(neighbor, clickFace, eyes, projYaw, projPitch);
            if (aim == null) continue;
            PlaceRotation candidate = buildPlaceRotation(neighbor, clickFace, aim, eyes, maxReach);
            best = compareDifferences(candidate, best);
        }
        if (best == null) return false;

        this.targetYaw   = best.yaw;
        this.targetPitch = best.pitch;
        this.hasTarget   = true;
        this.pendPos     = best.blockPos;
        this.pendFacing  = best.facing;
        this.pendPlace   = true;
        this.curTargetPos    = best.blockPos;
        this.curTargetFacing = best.facing;
        this.curTargetYaw    = best.yaw;
        this.curTargetPitch  = best.pitch;
        this.hasCurTarget    = true;
        return true;
    }

    private Vec3 faceAimPoint(BlockPos block, EnumFacing face, Vec3 eyes, float fromYaw, float fromPitch) {
        final double trim = 0.15;
        double minX = block.getX() + trim, maxX = block.getX() + 1 - trim;
        double minY = block.getY() + trim, maxY = block.getY() + 1 - trim;
        double minZ = block.getZ() + trim, maxZ = block.getZ() + 1 - trim;
        switch (face) {
            case DOWN:  minY = maxY = block.getY();     break;
            case UP:    minY = maxY = block.getY() + 1; break;
            case NORTH: minZ = maxZ = block.getZ();     break;
            case SOUTH: minZ = maxZ = block.getZ() + 1; break;
            case WEST:  minX = maxX = block.getX();     break;
            case EAST:  minX = maxX = block.getX() + 1; break;
            default: return null;
        }
        Vec3   dir = lookVector(fromYaw, fromPitch);
        double planeVal, eyeVal, dirVal;
        switch (face.getAxis()) {
            case X: planeVal = minX; eyeVal = eyes.xCoord; dirVal = dir.xCoord; break;
            case Y: planeVal = minY; eyeVal = eyes.yCoord; dirVal = dir.yCoord; break;
            default: planeVal = minZ; eyeVal = eyes.zCoord; dirVal = dir.zCoord; break;
        }
        double x, y, z;
        if (Math.abs(dirVal) < 1.0E-6) {
            x = (minX + maxX) * 0.5; y = (minY + maxY) * 0.5; z = (minZ + maxZ) * 0.5;
        } else {
            double t = (planeVal - eyeVal) / dirVal;
            if (t < 0.0) t = 0.0;
            x = eyes.xCoord + dir.xCoord * t;
            y = eyes.yCoord + dir.yCoord * t;
            z = eyes.zCoord + dir.zCoord * t;
        }
        return new Vec3(
                MathHelper.clamp_double(x, minX, maxX),
                MathHelper.clamp_double(y, minY, maxY),
                MathHelper.clamp_double(z, minZ, maxZ));
    }

    private PlaceRotation buildPlaceRotation(BlockPos block, EnumFacing face, Vec3 aim,
                                             Vec3 eyes, float maxReach) {
        if (eyes.distanceTo(aim) > maxReach) return null;
        Vec3 diff = aim.subtract(eyes);
        if (face.getAxis() != EnumFacing.Axis.Y) {
            double dist = Math.abs(face.getAxis() == EnumFacing.Axis.Z ? diff.zCoord : diff.xCoord);
            if (dist < minDist.getInput()) return null;
        }
        float[] rot   = toRotation(aim, eyes);
        float   yaw   = rot[0];
        float   pitch = rot[1];

        int mode = (int) rotationMode.getInput();
        if (mode == 2) {
            yaw = Math.round(yaw / 45.0F) * 45.0F;
        } else if (mode == 3) {
            yaw = isLookingDiagonally()
                    ? Math.round(yaw / 45.0F) * 45.0F
                    : Math.round(yaw / 90.0F) * 90.0F;
        } else if (mode == 4) {
            yaw   = Math.round(yaw / 45.0F) * 45.0F;
            pitch = 79.3F;
        } else if (mode == 5) {
            yaw   = backwardsYaw();
            pitch = 85.0F;
        }

        if (mode != 5 && face == EnumFacing.UP && pitch < MIN_DOWN_PITCH) {
            pitch = MIN_DOWN_PITCH;
        }

        yaw   = quantizeFrom(this.serverYaw,   yaw);
        pitch = quantizeFrom(this.serverPitch, MathHelper.clamp_float(pitch, -90.0F, 90.0F));

        // Already looking at it — reuse current rotation
        MovingObjectPosition current = raytrace(this.serverYaw, this.serverPitch);
        if (current != null && current.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && current.getBlockPos().equals(block) && current.sideHit == face) {
            return new PlaceRotation(block, face, this.serverYaw, this.serverPitch);
        }

        MovingObjectPosition trace = raytrace(yaw, pitch);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) return null;
        if (trace.getBlockPos().equals(block) && trace.sideHit == face) {
            return new PlaceRotation(block, face, yaw, pitch);
        }
        return null;
    }

    private PlaceRotation compareDifferences(PlaceRotation candidate, PlaceRotation old) {
        if (candidate == null) return old;
        if (old == null) return candidate;
        return rotationDifference(candidate.yaw, candidate.pitch)
                < rotationDifference(old.yaw, old.pitch) ? candidate : old;
    }

    private float rotationDifference(float yaw, float pitch) {
        float dy = MathHelper.wrapAngleTo180_float(yaw - this.serverYaw);
        float dp = pitch - this.serverPitch;
        return (float) Math.sqrt(dy * dy + dp * dp);
    }

    private boolean isLookingDiagonally() {
        float yaw = Math.abs(MathHelper.wrapAngleTo180_float(this.serverYaw) % 90.0F);
        return yaw > 20.0F && yaw < 70.0F;
    }

    private float backwardsYaw() {
        float moveYaw = adjustYaw(mc.thePlayer.rotationYaw,
                mc.thePlayer.movementInput.moveForward,
                mc.thePlayer.movementInput.moveStrafe);
        return MathHelper.wrapAngleTo180_float(moveYaw - 180.0F);
    }

    private static float adjustYaw(float yaw, float forward, float strafe) {
        if (forward < 0.0F) yaw += 180.0F;
        float strafeMod = forward < 0.0F ? -0.5F : (forward > 0.0F ? 0.5F : 1.0F);
        if (strafe > 0.0F) yaw -= 90.0F * strafeMod;
        else if (strafe < 0.0F) yaw += 90.0F * strafeMod;
        return MathHelper.wrapAngleTo180_float(yaw);
    }

    // ── Scaffold: placement ───────────────────────────────────────────────────

    private void doPlace(BlockPos pos, EnumFacing facing, Vec3 hitVec) {
        int slot = this.findBlockSlot();
        if (slot == -1) { log("SKIP no block slot"); return; }
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) return;
        if (!((ItemBlock) stack.getItem()).canPlaceBlockOnSide(mc.theWorld, pos, facing, mc.thePlayer, stack)) {
            log("SKIP canPlaceBlockOnSide=false"); return;
        }

        int previous = mc.thePlayer.inventory.currentItem;
        boolean switched = false;
        if (autoBlockEnabled.isToggled() && previous != slot) {
            mc.thePlayer.inventory.currentItem = slot;
            switched = true;
            if (autoBlockMode.getInput() == 0) {
                ItemRendererState.setCancelUpdate(true);
                ItemRendererState.setCancelReset(true);
            }
        }

        boolean rc = mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                mc.thePlayer.inventory.getCurrentItem(), pos, facing, hitVec);

        log(String.format("PLACE rc=%s pos=%d,%d,%d/%s rot=(%.2f,%.2f) ground=%s",
                rc, pos.getX(), pos.getY(), pos.getZ(), facing,
                this.serverYaw, this.serverPitch, mc.thePlayer.onGround));

        if (rc) {
            this.hasCurTarget = false;
            doSwing();
            markPlaced(pos.offset(facing));
            int min   = (int) delayMin.getInput();
            int max   = (int) delayMax.getInput();
            int ticks = max > min ? min + (int) (Math.random() * (max - min)) : min;
            this.nextPlaceTime = System.currentTimeMillis() + ticks * 50L;
        }

        if (switched && autoBlockMode.getInput() == 1) {
            mc.thePlayer.inventory.currentItem = previous;
        }
    }

    private void doSwing() {
        switch ((int) swingMode.getInput()) {
            case 0: mc.thePlayer.swingItem(); break;
            case 1: mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation()); break;
            case 2:
                ItemRendererState.setCancelReset(false);
                mc.getItemRenderer().resetEquippedProgress();
                break;
            default: break;
        }
    }

    private int findBlockSlot() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!sortByAmount.isToggled() && isValidBlock(held)) return mc.thePlayer.inventory.currentItem;
        int slot = -1, bestCount = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (!isValidBlock(stack)) continue;
            if (!allowVariants.isToggled() && held != null && isValidBlock(held)
                    && !stack.getItem().getClass().equals(held.getItem().getClass())) continue;
            if (!sortByAmount.isToggled()) return i;
            if (stack.stackSize > bestCount) { bestCount = stack.stackSize; slot = i; }
        }
        return slot;
    }

    private static boolean isValidBlock(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) return false;
        Block block = ((ItemBlock) stack.getItem()).getBlock();
        return !(block instanceof BlockBush)
                && !BlockUtils.isInteractable(block)
                && block.getMaterial().isSolid()
                && Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private void clearPending() {
        this.pendPos = null; this.pendFacing = null; this.pendPlace = false;
    }

    private void resetTarget() {
        this.hasCurTarget = false; this.targetMissTicks = 0;
        this.curTargetPos = null; this.curTargetFacing = null;
        this.clearPending();
    }

    private static boolean canBeClicked(BlockPos pos) {
        return !BlockUtils.replaceable(pos) && !BlockUtils.isInteractable(BlockUtils.getBlock(pos));
    }

    private MovingObjectPosition raytrace(float yaw, float pitch) {
        Vec3  eyes = mc.thePlayer.getPositionEyes(1.0F);
        float f    = -yaw   * 0.017453292F;
        float f1   = -pitch * 0.017453292F;
        float cos  = MathHelper.cos(f - (float) Math.PI);
        float sin  = MathHelper.sin(f - (float) Math.PI);
        float nc   = -MathHelper.cos(f1);
        Vec3  look = new Vec3(sin * nc, MathHelper.sin(f1), cos * nc);
        double reach = mc.playerController.getBlockReachDistance();
        Vec3   end   = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);
        return mc.theWorld.rayTraceBlocks(eyes, end, false, false, true);
    }

    private static float[] toRotation(Vec3 target, Vec3 eyes) {
        double dx = target.xCoord - eyes.xCoord;
        double dy = target.yCoord - eyes.yCoord;
        double dz = target.zCoord - eyes.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw   = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        return new float[]{yaw, pitch};
    }

    private static Vec3 lookVector(float yaw, float pitch) {
        float f   = -yaw   * 0.017453292F;
        float f1  = -pitch * 0.017453292F;
        float cos = MathHelper.cos(f - (float) Math.PI);
        float sin = MathHelper.sin(f - (float) Math.PI);
        float nc  = -MathHelper.cos(f1);
        return new Vec3(sin * nc, MathHelper.sin(f1), cos * nc);
    }

    private static float gcd() {
        float sensitivity = mc.gameSettings != null ? mc.gameSettings.mouseSensitivity : 0.5F;
        float f = sensitivity * 0.6F + 0.2F;
        return f * f * f * 1.2F;
    }

    private static float quantizeFrom(float start, float target) {
        float gcd = gcd();
        if (gcd <= 1.0E-6F) return target;
        return start + (float) (Math.round((double) (target - start) / (double) gcd) * (double) gcd);
    }

    private static float stepAngle(float current, float target, float maxStep) {
        float diff = MathHelper.wrapAngleTo180_float(target - current);
        if (maxStep > 0.0F && Math.abs(diff) > maxStep) diff = maxStep * Math.signum(diff);
        return quantizeFrom(current, current + diff);
    }

    private void markPlaced(BlockPos pos) {
        if (render.isToggled()) {
            this.markedPos.add(pos);
            this.markedTime.add(System.currentTimeMillis());
        }
    }

    // ── Bridge Assist: pre-place target scan ─────────────────────────────────

    private static final EnumFacing[] SIDES = {
            EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private TargetResult findPrePlaceTarget(float currentPitch, double reach) {
        float yaw = mc.thePlayer.rotationYaw;
        AxisAlignedBB bbox = mc.thePlayer.getEntityBoundingBox();
        int standY = MathHelper.floor_double(bbox.minY) - 1;
        int minX   = MathHelper.floor_double(bbox.minX);
        int maxX   = MathHelper.floor_double(bbox.maxX);
        int minZ   = MathHelper.floor_double(bbox.minZ);
        int maxZ   = MathHelper.floor_double(bbox.maxZ);

        ArrayList<FaceTarget> targets = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos standBlock = new BlockPos(x, standY, z);
                if (BlockUtils.replaceable(standBlock)) continue;
                for (EnumFacing face : SIDES) {
                    BlockPos adj = standBlock.offset(face);
                    if (!BlockUtils.replaceable(adj)) continue;
                    targets.add(new FaceTarget(standBlock, face));
                }
            }
        }
        if (targets.isEmpty()) return null;

        float bestDelta = Float.MAX_VALUE;
        float bestPitch = Float.NaN;
        BlockPos bestSupport = null;
        EnumFacing bestFace  = null;

        for (float pitch = 60f; pitch <= 90f; ) {
            float step = 1.0f + (float) (Math.random() * 2 - 1) * 0.5f;
            if (step < 0.4f) step = 0.4f;
            if (step > 1.8f) step = 1.8f;
            pitch += step;
            float samplePitch = Math.min(pitch, 90f);
            MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, yaw, samplePitch);
            if (mop == null) { if (pitch >= 90f) break; continue; }
            EnumFacing hitFace = mop.sideHit;
            if (hitFace == EnumFacing.UP || hitFace == EnumFacing.DOWN) { if (pitch >= 90f) break; continue; }
            BlockPos hitBlock = mop.getBlockPos();
            for (FaceTarget t : targets) {
                if (hitBlock.equals(t.block) && hitFace == t.face) {
                    float delta = Math.abs(samplePitch - currentPitch);
                    if (delta < bestDelta) {
                        bestDelta = delta; bestPitch = samplePitch;
                        bestSupport = t.block; bestFace = t.face;
                    }
                    break;
                }
            }
            if (pitch >= 90f) break;
        }

        if (bestSupport == null || bestFace == null || Float.isNaN(bestPitch)) return null;
        return new TargetResult(yaw, bestPitch, bestSupport, bestFace);
    }

    // ── Bridge Assist sneak ───────────────────────────────────────────────────

    private void handleSneak(PrePlayerInputEvent e) {
        boolean manualSneak  = isManualSneak();
        boolean requireSneak = sneakKeyPressed.isToggled();

        if (manualSneak && !requireSneak) { resetUnsneak(); return; }

        if (requireSneak && (!manualSneak || (e.getForward() == 0 && e.getStrafe() == 0))) {
            if (!manualSneak) resetUnsneak();
            repressSneak(e);
            return;
        }

        if (notMovingForward.isToggled() && e.getForward() > 0) { clearSneak(e); return; }
        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70)  { clearSneak(e); return; }
        if (holdingBlocks.isToggled()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !(held.getItem() instanceof ItemBlock)) { clearSneak(e); return; }
        }

        if (e.isJump() && mc.thePlayer.onGround
                && (e.getForward() != 0 || e.getStrafe() != 0)
                && sneakOnJump.getInput() > 0) {
            if (!requireSneak || forceRelease) {
                sneakJumpStartTick = mc.thePlayer.ticksExisted;
                double raw = sneakOnJump.getInput() / 50.0;
                int base = (int) raw;
                sneakJumpDelayTicks = base + (Math.random() < (raw - base) ? 1 : 0);
                pressSneak(e, true);
                return;
            }
        }

        SimulatedPlayer sim = SimulatedPlayer.fromClientPlayer(mc.thePlayer.movementInput);
        sim.movementInput.sneak = false;
        sim.tick();
        double offset = computeEdgeOffset(sim.getEntityBoundingBox());

        if (Double.isNaN(offset)) {
            if (e.isJump() && (sneakOnJump.getInput() <= 0 || (e.getForward() == 0 && e.getStrafe() == 0))) {
                if (sneakingFromModule) tryReleaseSneak(e, true);
            } else if (mc.thePlayer.onGround) {
                pressSneak(e, true);
            } else if (sneakingFromModule) {
                tryReleaseSneak(e, true);
            }
            return;
        }

        if (offset > edgeOffset.getInput()) {
            pressSneak(e, true);
        } else if (sneakingFromModule) {
            tryReleaseSneak(e, true);
        }
    }

    private void pressSneak(PrePlayerInputEvent e, boolean resetDelay) {
        e.setSneak(true);
        sneakingFromModule = true;
        if (resetDelay) unsneakStartTick = -1;
        repressSneak(e);
    }

    private void tryReleaseSneak(PrePlayerInputEvent e, boolean resetDelay) {
        int existed = mc.thePlayer.ticksExisted;
        if (unsneakStartTick == -1 && sneakJumpStartTick == -1) {
            unsneakStartTick = existed;
            double fromMs = Math.min(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            double toMs   = Math.max(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            if (toMs <= fromMs) toMs = fromMs + 1;
            unsneakDelayTicks = (int) Math.floor((fromMs + Math.random() * (toMs - fromMs)) / 50.0);
        }
        if (sneakJumpStartTick != -1 && existed - sneakJumpStartTick < sneakJumpDelayTicks) {
            pressSneak(e, false); return;
        }
        if (unsneakStartTick != -1 && existed - unsneakStartTick < unsneakDelayTicks) {
            pressSneak(e, false); return;
        }
        releaseSneak(e, resetDelay);
    }

    private void releaseSneak(PrePlayerInputEvent e, boolean resetDelay) {
        if (!sneakKeyPressed.isToggled()) {
            e.setSneak(false);
        } else if (sneakingFromModule && isManualSneak() && (placed || !mc.thePlayer.onGround)) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), false);
            e.setSneak(false);
            forceRelease = true;
        } else if (forceRelease) {
            e.setSneak(false);
        }
        sneakingFromModule = false;
        placed = false;
        if (resetDelay) resetUnsneak();
    }

    private void repressSneak(PrePlayerInputEvent e) {
        if (forceRelease && isManualSneak()) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.getKeyCode(), true);
            e.setSneak(true);
        }
        forceRelease = false;
    }

    private void clearSneak(PrePlayerInputEvent e) {
        sneakingFromModule = false;
        resetUnsneak();
        if (sneakKeyPressed.isToggled()) repressSneak(e);
    }

    private void resetUnsneak() {
        unsneakStartTick = -1; sneakJumpStartTick = -1;
        sneakJumpDelayTicks = -1; unsneakDelayTicks = -1;
    }

    private boolean isManualSneak() {
        return Utils.isBindDown(mc.gameSettings.keyBindSneak);
    }

    private double computeEdgeOffset(AxisAlignedBB simBox) {
        AxisAlignedBB groundCheck = new AxisAlignedBB(
                simBox.minX, simBox.minY - 0.01, simBox.minZ,
                simBox.maxX, simBox.minY,         simBox.maxZ);
        List<AxisAlignedBB> groundBoxes = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, groundCheck);
        if (groundBoxes.isEmpty()) return Double.NaN;
        double feetX = (simBox.minX + simBox.maxX) / 2.0;
        double feetZ = (simBox.minZ + simBox.maxZ) / 2.0;
        double minDist = Double.MAX_VALUE;
        for (AxisAlignedBB box : groundBoxes) {
            double closestX = Math.max(box.minX, Math.min(feetX, box.maxX));
            double closestZ = Math.max(box.minZ, Math.min(feetZ, box.maxZ));
            minDist = Math.min(minDist, Math.max(Math.abs(feetX - closestX), Math.abs(feetZ - closestZ)));
        }
        return minDist;
    }

    // ── Inner classes ─────────────────────────────────────────────────────────

    private static class PlaceRotation {
        final BlockPos blockPos; final EnumFacing facing; final float yaw, pitch;
        PlaceRotation(BlockPos b, EnumFacing f, float y, float p) {
            blockPos = b; facing = f; yaw = y; pitch = p;
        }
    }

    private static class FaceTarget {
        final BlockPos block; final EnumFacing face;
        FaceTarget(BlockPos b, EnumFacing f) { block = b; face = f; }
    }

    private static class TargetResult {
        final float yaw, pitch; final BlockPos support; final EnumFacing face;
        TargetResult(float y, float p, BlockPos s, EnumFacing f) {
            yaw = y; pitch = p; support = s; face = f;
        }
    }
}
