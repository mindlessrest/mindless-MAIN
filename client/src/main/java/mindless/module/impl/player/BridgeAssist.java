package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.SendPacketEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.script.model.SimulatedPlayer;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

public class BridgeAssist extends Module {
    /** DOWN is never useful: the block would land above the support, on top of the player. */
    private static final EnumFacing[] FACES = {
            EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST
    };

    private static final double GRID_INSET = 0.05;
    private static final double GRID_STEP = 0.25;
    private static final int GRID_N = (int) Math.round(1.0 / GRID_STEP);
    /** Cap on how many (support, face) pairs get the full ray-cast sweep in one acquisition. */
    /**
     * Ticks the last aim is held after the target disappears.
     *
     * Bridging spends most of its ticks with nothing to place -- the square underfoot is still
     * solid until the player steps off it. Dropping the aim on those ticks made the rotation
     * snap back and forth and never settle, so it is held briefly instead, which is what keeps
     * the view pointed down the way a scaffold does.
     */
    /** Half the player's hitbox width, for testing whether a corner hangs over the edge. */
    private static final double FOOTPRINT_HALF = 0.3;
    /** Squared per-tick displacement below which the player counts as standing still. */
    private static final double MOVING_EPSILON_SQ = 1.0E-5;
    /** Floor on how often the locked diagonal may change, as insurance against chatter. */
    private static final long MIN_RELOCK_INTERVAL_MS = 250L;

    private final SliderSetting edgeOffset;
    private final SliderSetting unsneakDelayMin;
    private final SliderSetting unsneakDelayMax;
    private final SliderSetting sneakOnJump;
    private final ButtonSetting sneakKeyPressed;
    private final ButtonSetting holdingBlocks;
    private final ButtonSetting lookingDown;
    private final ButtonSetting notMovingForward;

    private final ButtonSetting prePlace;
    private final ButtonSetting silentRotation;
    private final ButtonSetting debug;
    private final SliderSetting bridgePitch;
    private final SliderSetting smoothness;
    private final SliderSetting relockAngle;
    private final SliderSetting idleRelease;

    private boolean sneakingFromModule;
    private boolean placed;
    private boolean forceRelease;
    private int sneakJumpDelayTicks = -1;
    private int sneakJumpStartTick = -1;
    private int unsneakDelayTicks = -1;
    private int unsneakStartTick = -1;
    private boolean placeQueued;
    private BlockPos placeAtBlock;
    private EnumFacing placeSide;
    private Vec3 placeHitVec;
    private int previousSlot = -1;

    // Rotation state. The aim is driven by where the player is going, not by hunting for a
    // block: yaw locks onto the diagonal opposite the direction of travel and stays there until
    // the movement direction genuinely changes, and the placement is whatever the resulting look
    // vector happens to hit.
    private boolean aimLocked;
    private boolean rotationInitialised;
    private float currentYaw, currentPitch;
    private float lockedYaw;
    private float yawJitter, pitchJitter;
    private long lastPlaceAt;
    private long lastRelockAt;
    /** Last known heading of actual travel, latched so a momentary stall does not drop the lock. */
    private float travelYaw;
    private boolean hasTravelYaw;

    /** Last reported trace stage, so an unchanged state does not spam chat every tick. */
    private String lastStage = "";
    private long lastStageAt;
    private java.io.BufferedWriter logWriter;

    public BridgeAssist() {
        super("Bridge Assist", category.player);

        this.registerSetting(prePlace = new ButtonSetting("Pre place", false));
        this.registerSetting(silentRotation = new ButtonSetting("Silent rotation", false));
        this.registerSetting(bridgePitch = new SliderSetting("Pitch", "\u00b0", 78, 60, 88, 0.5));
        this.registerSetting(smoothness = new SliderSetting("Smoothness", "%", 42, 5, 100, 1));
        this.registerSetting(relockAngle = new SliderSetting("Relock angle", "\u00b0", 55, 20, 120, 5));
        this.registerSetting(idleRelease = new SliderSetting("Idle release", "ms", 400, 100, 1500, 50));
        this.registerSetting(debug = new ButtonSetting("Debug", false));

        GroupSetting sneakingGroup = new GroupSetting("Sneaking");
        this.registerSetting(sneakingGroup);
        this.registerSetting(edgeOffset = new SliderSetting(sneakingGroup, "Edge offset", " blocks", 0, 0, 0.3, 0.01));
        this.registerSetting(unsneakDelayMin = new SliderSetting(sneakingGroup, "Sneak delay min", "ms", 50, 50, 300, 5));
        this.registerSetting(unsneakDelayMax = new SliderSetting(sneakingGroup, "Sneak delay max", "ms", 100, 50, 300, 5));
        this.registerSetting(sneakOnJump = new SliderSetting(sneakingGroup, "Sneak on jump", "ms", 0, 0, 500, 5));

        GroupSetting conditionsGroup = new GroupSetting("Conditions");
        this.registerSetting(conditionsGroup);
        this.registerSetting(sneakKeyPressed = new ButtonSetting(conditionsGroup, "Sneak key pressed", false));
        this.registerSetting(holdingBlocks = new ButtonSetting(conditionsGroup, "Holding blocks", false));
        this.registerSetting(lookingDown = new ButtonSetting(conditionsGroup, "Looking down", false));
        this.registerSetting(notMovingForward = new ButtonSetting(conditionsGroup, "Not moving forward", false));

        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        double offset = edgeOffset.getInput();
        return offset == Math.rint(offset) ? Integer.toString((int) offset) : Double.toString(Utils.round(offset, 2));
    }

    @Override
    public void onDisable() {
        sneakingFromModule = false;
        resetUnsneak();
        placeQueued = false;
        aimLocked = false;
        rotationInitialised = false;
        hasTravelYaw = false;
        lastStage = "";
        closeLog();
        restoreSlot();
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;

        boolean manualSneak = isManualSneak();
        boolean requireSneak = sneakKeyPressed.isToggled();

        if (manualSneak && !requireSneak) {
            resetUnsneak();
            return;
        }

        if (requireSneak && (!manualSneak || (e.getForward() == 0 && e.getStrafe() == 0))) {
            if (!manualSneak) resetUnsneak();
            repressSneak(e);
            return;
        }

        if (notMovingForward.isToggled() && e.getForward() > 0) {
            clearSneak(e);
            return;
        }
        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70) {
            clearSneak(e);
            return;
        }
        if (holdingBlocks.isToggled()) {
            ItemStack held = mc.thePlayer.getHeldItem();
            if (held == null || !(held.getItem() instanceof ItemBlock)) {
                clearSneak(e);
                return;
            }
        }

        if (e.isJump() && mc.thePlayer.onGround && (e.getForward() != 0 || e.getStrafe() != 0) && sneakOnJump.getInput() > 0) {
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

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (e.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            C08PacketPlayerBlockPlacement c08 = (C08PacketPlayerBlockPlacement) e.getPacket();
            if (c08.getPlacedBlockDirection() != 255 && sneakingFromModule && sneakKeyPressed.isToggled()) {
                placed = true;
            }
        }
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        // Hard gate: with Silent rotation off this handler does nothing at all -- no aim, no slot
        // change, and above all no setYaw/setPitch. Bridge Assist's own sneak behaviour is
        // untouched by any of the code below.
        if (!silentRotation.isToggled()) {
            releaseAim();
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) {
            releaseAim();
            return;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            stage("held by BedAura");
            return;
        }
        if (lookingDown.isToggled() && mc.thePlayer.rotationPitch < 70f) {
            stage("blocked by 'Looking down' condition (pitch " + Math.round(mc.thePlayer.rotationPitch) + ")");
            releaseAim();
            return;
        }
        if (notMovingForward.isToggled() && mc.thePlayer.movementInput.moveForward > 0f) {
            stage("blocked by 'Not moving forward' condition");
            releaseAim();
            return;
        }

        int slot = blockSlot();
        if (slot == -1) {
            stage("no placeable block in the hotbar");
            releaseAim();
            return;
        }

        if (!shouldBridge()) {
            stage("idle: on the ground, not near an edge");
            releaseAim();
            return;
        }

        // Direction of travel, measured from the distance actually covered last tick.
        //
        // This used to be read off movementInput combined with the player's own rotationYaw, and
        // that was the bug behind the whole thing. Movement Fix rewrites moveForward/moveStrafe to
        // be relative to the *spoofed* yaw whenever a silent rotation is active
        // (RotationHelper.onPostInput), while rotationYaw is still the real camera. Mixing the two
        // frames produced a bogus heading, which relocked the diagonal, which swung the spoofed
        // yaw, which made Movement Fix pick a different input combination -- a closed loop that
        // flipped the target between two diagonals every few ticks and left the aim sweeping
        // sideways through open air. Displacement belongs to no frame at all, so it cannot feed
        // back. It also keeps working mid-air, where momentum carries the heading through.
        double dx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double dz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        boolean moving = dx * dx + dz * dz > MOVING_EPSILON_SQ;
        if (moving) {
            travelYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            hasTravelYaw = true;
        }

        boolean towering = !mc.thePlayer.onGround && !moving;
        float basePitch = towering ? 88.5f : (float) bridgePitch.getInput();

        if (!rotationInitialised) {
            currentYaw = mc.thePlayer.rotationYaw;
            currentPitch = mc.thePlayer.rotationPitch;
            lockedYaw = currentYaw;
            rotationInitialised = true;
        }

        // Yaw wants to face back down the line the player is walking, which is what a god bridge
        // looks like. Snapping that to the nearest 45-degree diagonal is the whole trick: it
        // gives a target that does not drift as the player's own view wanders.
        float awayYaw = (hasTravelYaw ? travelYaw : mc.thePlayer.rotationYaw) + 180f;

        // Re-lock only when the direction of travel has genuinely changed, and never more than
        // once every MIN_RELOCK_INTERVAL_MS. Re-picking freely is what made the head thrash: the
        // target moved as fast as the aim chased it.
        long now = System.currentTimeMillis();
        boolean drifted = Math.abs(wrap(awayYaw - lockedYaw)) > (float) relockAngle.getInput();
        boolean relock = !aimLocked || (drifted && now - lastRelockAt >= MIN_RELOCK_INTERVAL_MS);
        if (relock) {
            lastRelockAt = now;
            lockedYaw = bestDiagonal(awayYaw, currentYaw);
            aimLocked = true;
            yawJitter = (float) (Math.random() * 1.6d - 0.8d);
            pitchJitter = (float) (Math.random() * 2.4d - 1.2d);
        }

        // A slow wander on top of the lock, so the aim is never perfectly static.
        float t = System.currentTimeMillis() * 0.003f;
        float targetYaw = wrap(lockedYaw + (float) (Math.sin(t * 2.1f) * 0.45d) + yawJitter);
        float targetPitch = basePitch + (float) (Math.cos(t * 1.7f) * 0.35d) + pitchJitter;

        // Step toward it, quantised to the mouse GCD so the deltas look like real mouse input.
        float gcd = mouseGcd();
        currentYaw = wrap(currentYaw + quantise(wrap(targetYaw - currentYaw), gcd));
        currentPitch = RotationUtils.clampPitch(currentPitch + quantise(targetPitch - currentPitch, gcd));

        selectBlockSlot(slot);
        e.setYaw(currentYaw);
        e.setPitch(currentPitch);

        // Placement follows the rotation rather than the other way round. Whatever the look
        // vector lands on is the support -- so there is no such thing as "no rotation reaches the
        // target" any more, which is what the block-first search kept failing on.
        double reach = mc.playerController.getBlockReachDistance();
        MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, currentYaw, currentPitch);
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            stage("aiming " + Math.round(currentYaw) + "/" + Math.round(currentPitch) + " - ray hit nothing");
            return;
        }
        if (mop.sideHit == EnumFacing.DOWN) {
            stage("ray hit the underside of a block, skipping");
            return;
        }
        BlockPos support = mop.getBlockPos();
        if (support.offset(mop.sideHit).getY() > MathHelper.floor_double(mc.thePlayer.posY)) {
            stage("ray would place above the feet, skipping");
            return;
        }
        ItemStack held = mc.thePlayer.inventory.getStackInSlot(slot);
        if (!BlockUtils.canPlaceBlockOnSide(held, support, mop.sideHit)) {
            stage("cannot place on " + support.getX() + "," + support.getY() + "," + support.getZ()
                    + " " + mop.sideHit);
            return;
        }

        placeAtBlock = support;
        placeSide = mop.sideHit;
        placeHitVec = mop.hitVec;
        placeQueued = true;
        stage("queued " + support.getX() + "," + support.getY() + "," + support.getZ()
                + " " + mop.sideHit + " (yaw " + Math.round(currentYaw) + ", pitch " + Math.round(currentPitch) + ")");
    }

    /**
     * Whether the player is in a state where blocks should be going down.
     *
     * Waiting for the square underfoot to be air meant waiting until the player was already
     * falling, which was far too late. Standing at the lip of the deck counts, and so does the
     * moment just after a placement, so the aim stays put between blocks.
     */
    private boolean shouldBridge() {
        if (!mc.thePlayer.onGround) return true;
        if (System.currentTimeMillis() - lastPlaceAt < (long) idleRelease.getInput()) return true;

        // Displacement again rather than movementInput, for the same reason as above: with
        // Movement Fix on, those fields no longer describe the keys the player is holding.
        double dx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double dz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        if (dx * dx + dz * dz <= MOVING_EPSILON_SQ) return false;

        // At the lip: any corner of the hitbox hanging over open space.
        return overEdge();
    }

    /** True when a corner of the player's footprint is over air. */
    private boolean overEdge() {
        int y = MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1;
        for (double dx = -FOOTPRINT_HALF; dx <= FOOTPRINT_HALF; dx += FOOTPRINT_HALF * 2) {
            for (double dz = -FOOTPRINT_HALF; dz <= FOOTPRINT_HALF; dz += FOOTPRINT_HALF * 2) {
                BlockPos corner = new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX + dx), y,
                        MathHelper.floor_double(mc.thePlayer.posZ + dz));
                if (BlockUtils.replaceable(corner)) return true;
            }
        }
        return false;
    }

    /**
     * Nearest 45-degree diagonal to the direction of travel, tie-broken toward where the player
     * is already looking so a relock is the smallest turn available.
     */
    private static float bestDiagonal(float awayYaw, float headYaw) {
        float[] diagonals = { 45f, 135f, -135f, -45f };
        float closest = Float.MAX_VALUE;
        for (float d : diagonals) {
            closest = Math.min(closest, Math.abs(wrap(d - awayYaw)));
        }
        float best = diagonals[0];
        float bestToHead = Float.MAX_VALUE;
        for (float d : diagonals) {
            if (Math.abs(Math.abs(wrap(d - awayYaw)) - closest) >= 1.0f) continue;
            float toHead = Math.abs(wrap(d - headYaw));
            if (toHead < bestToHead) {
                bestToHead = toHead;
                best = d;
            }
        }
        return best;
    }

    /**
     * One smoothing step, rounded to the mouse GCD.
     *
     * Vanilla can only deliver rotation deltas that are multiples of this value, so anything
     * else is a giveaway. The floor of one whole unit stops the step rounding to zero and
     * stalling the aim short of its target.
     */
    private float quantise(float delta, float gcd) {
        float step = delta * (float) (smoothness.getInput() / 100d);
        step = Math.round(step / gcd) * gcd;
        if (step == 0f && Math.abs(delta) >= gcd) step = Math.signum(delta) * gcd;
        return step;
    }

    private float mouseGcd() {
        float f = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        return f * f * f * 1.2f;
    }

    private static float wrap(float angle) {
        return MathHelper.wrapAngleTo180_float(angle);
    }

    private void releaseAim() {
        if (rotationInitialised || aimLocked) {
            stage("released");
        }
        aimLocked = false;
        rotationInitialised = false;
        hasTravelYaw = false;
        placeQueued = false;
        restoreSlot();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!placeQueued) return;
        placeQueued = false;
        if (!Utils.nullCheck() || mc.currentScreen != null || !silentRotation.isToggled()
                || placeAtBlock == null || placeSide == null || placeHitVec == null) {
            return;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            stage("place skipped: held item is not a block");
            return;
        }
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, placeAtBlock, placeSide, placeHitVec)) {
            mc.thePlayer.swingItem();
            lastPlaceAt = System.currentTimeMillis();
            stage("placed at " + placeAtBlock.getX() + "," + placeAtBlock.getY() + "," + placeAtBlock.getZ()
                    + " " + placeSide);
        } else {
            stage("place REJECTED by the controller at " + placeAtBlock.getX() + ","
                    + placeAtBlock.getY() + "," + placeAtBlock.getZ() + " " + placeSide);
        }
    }

    private void pressSneak(PrePlayerInputEvent e, boolean resetDelay) {
        e.setSneak(true);
        sneakingFromModule = true;
        if (resetDelay) unsneakStartTick = -1;
        repressSneak(e);
    }

    private void tryReleaseSneak(PrePlayerInputEvent e, boolean resetDelay) {
        if (!mc.thePlayer.onGround) {
            pressSneak(e, false);
            return;
        }

        int existed = mc.thePlayer.ticksExisted;
        if (unsneakStartTick == -1 && sneakJumpStartTick == -1) {
            unsneakStartTick = existed;
            double fromMs = Math.min(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            double toMs = Math.max(unsneakDelayMin.getInput(), unsneakDelayMax.getInput());
            if (toMs <= fromMs) toMs = fromMs + 1;
            unsneakDelayTicks = Math.max(1, (int) Math.floor((fromMs + Math.random() * (toMs - fromMs)) / 50.0));
        }

        if (sneakJumpStartTick != -1 && existed - sneakJumpStartTick < sneakJumpDelayTicks) {
            pressSneak(e, false);
            return;
        }
        if (unsneakStartTick != -1 && existed - unsneakStartTick < unsneakDelayTicks) {
            pressSneak(e, false);
            return;
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
        unsneakStartTick = -1;
        sneakJumpStartTick = -1;
        sneakJumpDelayTicks = -1;
        unsneakDelayTicks = -1;
    }

    private boolean isManualSneak() {
        return Utils.isBindDown(mc.gameSettings.keyBindSneak);
    }

    private double computeEdgeOffset(AxisAlignedBB simBox) {
        AxisAlignedBB groundCheck = new AxisAlignedBB(
                simBox.minX, simBox.minY - 0.01, simBox.minZ,
                simBox.maxX, simBox.minY, simBox.maxZ
        );

        List<AxisAlignedBB> groundBoxes = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, groundCheck);
        if (groundBoxes.isEmpty()) return Double.NaN;

        double feetX = (simBox.minX + simBox.maxX) / 2.0;
        double feetZ = (simBox.minZ + simBox.maxZ) / 2.0;

        double minDist = Double.MAX_VALUE;
        for (AxisAlignedBB box : groundBoxes) {
            double closestX = Math.max(box.minX, Math.min(feetX, box.maxX));
            double closestZ = Math.max(box.minZ, Math.min(feetZ, box.maxZ));
            double dx = Math.abs(feetX - closestX);
            double dz = Math.abs(feetZ - closestZ);
            double dist = Math.max(dx, dz);
            minDist = Math.min(minDist, dist);
        }

        return minDist;
    }

    /**
     * Reports where the silent-rotation pipeline got to this tick.
     *
     * Only fires when the stage text changes, or every two seconds while it is unchanged, so a
     * steady state reads as one line rather than twenty a second. Every early return in
     * onClientRotation names itself, so the first thing that stops working is visible directly
     * instead of having to be inferred from the module doing nothing.
     */
    private void stage(String text) {
        if (debug == null || !debug.isToggled()) return;
        long now = System.currentTimeMillis();
        if (text.equals(lastStage) && now - lastStageAt < 2000L) return;
        lastStage = text;
        lastStageAt = now;
        Utils.sendMessage("&7[BridgeAssist] &b" + text);
        writeLog(text);
    }

    /**
     * Mirrors the trace into logs/mindless-bridgeassist-debug.log next to Minecraft's own logs.
     *
     * The writer is opened once and kept, rather than reopened per line: this runs on the game
     * thread, and open-append-close per entry is exactly the kind of synchronous file I/O that
     * costs frames. Rate limiting upstream keeps this to roughly a line a second.
     */
    private void writeLog(String text) {
        try {
            if (logWriter == null) {
                java.io.File dir = new java.io.File(mc.mcDataDir, "logs");
                if (!dir.exists() && !dir.mkdirs()) return;
                java.io.File file = new java.io.File(dir, "mindless-bridgeassist-debug.log");
                logWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(file, true), java.nio.charset.StandardCharsets.UTF_8));
                logWriter.write("--- session start " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date()) + " ---");
                logWriter.newLine();
            }
            logWriter.write(new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date())
                    + "  " + text);
            logWriter.newLine();
            logWriter.flush();
        } catch (Throwable ignored) {
            logWriter = null;
        }
    }

    private void closeLog() {
        if (logWriter == null) return;
        try {
            logWriter.flush();
            logWriter.close();
        } catch (Throwable ignored) {
        }
        logWriter = null;
    }

    /** Hotbar slot holding a placeable block: the held one if it already is, else the first found. */
    private int blockSlot() {
        ItemStack current = mc.thePlayer.getHeldItem();
        if (current != null && current.stackSize > 0 && current.getItem() instanceof ItemBlock) {
            return mc.thePlayer.inventory.currentItem;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock) return i;
        }
        return -1;
    }

    private void selectBlockSlot(int slot) {
        if (mc.thePlayer.inventory.currentItem == slot) return;
        if (previousSlot == -1) previousSlot = mc.thePlayer.inventory.currentItem;
        mc.thePlayer.inventory.currentItem = slot;
    }

    private void restoreSlot() {
        if (previousSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
    }


}
