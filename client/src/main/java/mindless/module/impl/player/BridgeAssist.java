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
    /**
     * How far along the line of travel to look for the drop, in blocks.
     *
     * The aim needs roughly seven ticks to swing from wherever the player is looking down to the
     * bridging pitch. Waiting until a corner of the hitbox is over the edge leaves about one tick
     * before the player is airborne, so the whole approach was spent ray-casting into open air and
     * the attempt was abandoned and restarted from the camera pitch on every pass. Two and a half
     * blocks is nine ticks at walking pace and seven at a sprint, so the aim arrives already down.
     */
    private static final double LOOKAHEAD_BLOCKS = 2.5;
    private static final double LOOKAHEAD_STEP = 0.5;
    /**
     * How close the aim must be to its target before anything is placed.
     *
     * Placement follows whatever the ray hits, so while the head is still swinging down the ray
     * sweeps across the deck and drops a block on everything it crosses -- the stray blocks either
     * side of the walk line. Nothing is placed until the turn has essentially finished.
     */
    private static final float AIM_SETTLE_DEG = 6.0f;

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
    private final ButtonSetting forceDiagonal;
    private final SliderSetting stanceAngle;
    private final ButtonSetting debug;
    private final ButtonSetting record;
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
    /** Pitch that reaches the bridge while falling, or NaN when the normal stance pitch applies. */
    private float pitchOverride = Float.NaN;

    /** Last reported trace stage, so an unchanged state does not spam chat every tick. */
    private String lastStage = "";
    private long lastStageAt;
    private java.io.BufferedWriter logWriter;
    private java.io.BufferedWriter recordWriter;
    private boolean wasRecording;
    private int recordTick;

    public BridgeAssist() {
        super("Bridge Assist", category.player);

        this.registerSetting(prePlace = new ButtonSetting("Pre place", false));
        this.registerSetting(silentRotation = new ButtonSetting("Silent rotation", false));
        this.registerSetting(forceDiagonal = new ButtonSetting("Force diagonal", false));
        this.registerSetting(stanceAngle = new SliderSetting("Stance angle", "\u00b0", 0, -45, 45, 5));
        this.registerSetting(bridgePitch = new SliderSetting("Pitch", "\u00b0", 78, 60, 88, 0.5));
        this.registerSetting(smoothness = new SliderSetting("Smoothness", "%", 42, 5, 100, 1));
        this.registerSetting(relockAngle = new SliderSetting("Relock angle", "\u00b0", 55, 20, 120, 5));
        this.registerSetting(idleRelease = new SliderSetting("Idle release", "ms", 400, 100, 1500, 50));
        this.registerSetting(debug = new ButtonSetting("Debug", false));
        this.registerSetting(record = new ButtonSetting("Record", false));

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
        pitchOverride = Float.NaN;
        lastStage = "";
        closeLog();
        closeRecord();
        wasRecording = false;
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
            if (c08.getPlacedBlockDirection() != 255) {
                capturePlacement(c08);
                if (sneakingFromModule && sneakKeyPressed.isToggled()) {
                    placed = true;
                }
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
            stage("idle: on the ground, no drop within " + LOOKAHEAD_BLOCKS + " blocks ahead");
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

        // Towering means pillaring upward, and the test for it used to be "airborne and not moving
        // horizontally" -- which is also an exact description of falling straight down. So the
        // moment the player slipped off the bridge the aim was forced to 88.5 degrees, straight
        // down, guaranteeing it could not see the block behind it and making the fall
        // unrecoverable. Rising motion is what actually distinguishes the two.
        boolean towering = !mc.thePlayer.onGround && !moving && mc.thePlayer.motionY > 0;
        float basePitch = towering ? 88.5f : (float) bridgePitch.getInput();

        if (!rotationInitialised) {
            currentYaw = mc.thePlayer.rotationYaw;
            currentPitch = mc.thePlayer.rotationPitch;
            lockedYaw = currentYaw;
            rotationInitialised = true;
        }

        // Yaw wants to face back down the line the player is walking. Snapping that to the nearest
        // 45-degree heading is the whole trick: it gives a target that does not drift as the
        // player's own view wanders.
        float awayYaw = (hasTravelYaw ? travelYaw : mc.thePlayer.rotationYaw) + 180f;

        // Re-lock only when the direction of travel has genuinely changed, and never more than
        // once every MIN_RELOCK_INTERVAL_MS. Re-picking freely is what made the head thrash: the
        // target moved as fast as the aim chased it.
        long now = System.currentTimeMillis();
        boolean drifted = Math.abs(wrap(awayYaw - lockedYaw)) > (float) relockAngle.getInput();
        boolean relock = !aimLocked || (drifted && now - lastRelockAt >= MIN_RELOCK_INTERVAL_MS);
        if (relock) {
            lastRelockAt = now;
            lockedYaw = snapHeading(awayYaw, currentYaw, forceDiagonal.isToggled());
            aimLocked = true;
            yawJitter = (float) (Math.random() * 1.6d - 0.8d);
            pitchJitter = (float) (Math.random() * 2.4d - 1.2d);
        }

        // A slow wander on top of the lock, so the aim is never perfectly static.
        float t = System.currentTimeMillis() * 0.003f;
        // Stance angle deflects the aim off the line of travel. Straight back (zero) enters the
        // support through its top face, where the block would land level with the feet and be
        // refused; coming in at an angle enters through the side face instead. The trace put every
        // successful placement around 25 degrees off, so that is the value to reach for, but which
        // side works depends on where the player walks within the block, hence a setting rather
        // than a guess.
        float targetYaw = wrap(lockedYaw + (float) stanceAngle.getInput()
                + (float) (Math.sin(t * 2.1f) * 0.45d) + yawJitter);
        float targetPitch = basePitch + (float) (Math.cos(t * 1.7f) * 0.35d) + pitchJitter;

        // Back on solid ground the normal stance applies again; any pitch found mid-fall is stale.
        if (mc.thePlayer.onGround) pitchOverride = Float.NaN;
        if (!Float.isNaN(pitchOverride)) targetPitch = pitchOverride;

        // Step toward it, quantised to the mouse GCD so the deltas look like real mouse input.
        float gcd = mouseGcd();
        currentYaw = wrap(currentYaw + quantise(wrap(targetYaw - currentYaw), gcd));
        currentPitch = RotationUtils.clampPitch(currentPitch + quantise(targetPitch - currentPitch, gcd));

        selectBlockSlot(slot);
        e.setYaw(currentYaw);
        e.setPitch(currentPitch);

        // Turn first, place second. Anything placed before the aim settles lands wherever the ray
        // happened to sweep, which is where the stray blocks beside the walk line came from.
        float yawError = Math.abs(wrap(targetYaw - currentYaw));
        float pitchError = Math.abs(targetPitch - currentPitch);
        if (yawError > AIM_SETTLE_DEG || pitchError > AIM_SETTLE_DEG) {
            stage("turning (yaw " + Math.round(currentYaw) + "/" + Math.round(targetYaw)
                    + ", pitch " + Math.round(currentPitch) + "/" + Math.round(targetPitch)
                    + ") - holding fire");
            return;
        }

        // Placement follows the rotation rather than the other way round: whatever the look
        // vector lands on becomes the support.
        double reach = mc.playerController.getBlockReachDistance();

        // The block has to land below the surface being walked on, never level with the feet.
        // Standing on a block puts the feet at exactly the next integer, so rounding up is what
        // keeps a top-face hit from dropping a block in front of the player at feet height.
        int maxPlaceY = (int) Math.ceil(mc.thePlayer.getEntityBoundingBox().minY) - 1;
        ItemStack held = mc.thePlayer.inventory.getStackInSlot(slot);

        MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, currentYaw, currentPitch);
        String reason = rejectReason(mop, maxPlaceY, held);
        if (reason == null) {
            queuePlacement(mop);
            return;
        }

        // The stance did not reach. On solid ground with the drop still ahead that is simply the
        // approach, so leave the aim alone; over the gap it means the next block is about to be
        // missed, and sweeping for a rotation that does reach is the difference between carrying
        // on and falling.
        if (mc.thePlayer.onGround && !overEdge()) {
            stage("aiming " + Math.round(currentYaw) + "/" + Math.round(currentPitch)
                    + " - approaching (" + reason + ")");
            return;
        }

        // Falling past the bridge is the one case worth chasing. The block last placed is now level
        // with the player or above them, so an aim pitched steeply down passes underneath
        // everything and reports nothing -- which is exactly how a good run ended, nine blocks in,
        // with "ray hit nothing" and no way back.
        //
        // Only the pitch is searched, and only downward from the stance. The yaw is already known
        // to be right, and it was sweeping the yaw that caused the earlier disasters: a yaw
        // candidate takes four or five ticks to reach and is stale on arrival, whereas these pitch
        // corrections are a few degrees and land in one or two. The same staleness argument that
        // condemns the yaw sweep is what permits this one.
        if (!mc.thePlayer.onGround) {
            for (float testPitch = basePitch - 4f; testPitch >= -30f; testPitch -= 4f) {
                if (rejectReason(RotationUtils.rayCastBlock(reach, currentYaw, testPitch),
                        maxPlaceY, held) != null) {
                    continue;
                }
                if (Float.isNaN(pitchOverride) || Math.abs(pitchOverride - testPitch) > 1f) {
                    stage("falling, dropping pitch to " + Math.round(testPitch) + " to reach back");
                }
                pitchOverride = testPitch;
                return;
            }
        }

        // Hold the stance and wait. There used to be a sweep here, hunting the surrounding
        // rotations for one that reached a placeable face, and it did more harm than the misses it
        // was meant to cover: candidates were judged against where the player stood at that
        // instant, while the aim needs four or five ticks to arrive and the player covers a fifth
        // of a block per tick. Every angle it chose was stale on arrival. The trace shows it
        // settling on seventy degrees off, dragging the aim away from the bridge, and the player
        // falling while facing the wrong way. A steady stance that waits for the geometry places
        // the block; a stance chasing a stale target never does.
        stage("holding stance " + Math.round(currentYaw) + "/" + Math.round(currentPitch)
                + " (" + reason + ")");
    }

    /**
     * Null when a block can be placed against whatever this ray hit, otherwise why not.
     *
     * Returning a reason rather than a bare boolean is what makes the trace worth reading: a
     * silent false covers four quite different situations, and knowing which one is happening is
     * the difference between an aim that points at nothing and an aim that points at a face the
     * game refuses.
     */
    private String rejectReason(MovingObjectPosition mop, int maxPlaceY, ItemStack held) {
        if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
            return "ray hit nothing";
        }
        if (mop.sideHit == EnumFacing.DOWN) return "hit an underside";
        BlockPos support = mop.getBlockPos();
        BlockPos placeAt = support.offset(mop.sideHit);
        if (placeAt.getY() > maxPlaceY) {
            return "hit " + mop.sideHit + " of " + support.getX() + "," + support.getY() + ","
                    + support.getZ() + ", block would land at y" + placeAt.getY()
                    + " but the deck is y" + maxPlaceY;
        }
        if (!BlockUtils.canPlaceBlockOnSide(held, support, mop.sideHit)) {
            return "game refused " + mop.sideHit + " of " + support.getX() + "," + support.getY()
                    + "," + support.getZ();
        }
        return null;
    }

    private void queuePlacement(MovingObjectPosition mop) {
        BlockPos support = mop.getBlockPos();
        BlockPos placeAt = support.offset(mop.sideHit);
        placeAtBlock = support;
        placeSide = mop.sideHit;
        placeHitVec = mop.hitVec;
        placeQueued = true;
        stage("queued " + support.getX() + "," + support.getY() + "," + support.getZ()
                + " " + mop.sideHit + " -> block at " + placeAt.getX() + "," + placeAt.getY() + ","
                + placeAt.getZ() + " (yaw " + Math.round(currentYaw) + ", pitch " + Math.round(currentPitch) + ")");
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
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= MOVING_EPSILON_SQ) return false;

        // Already at the lip, or heading at a drop that is close enough to start turning for.
        return overEdge() || dropAhead(dx, dz, Math.sqrt(lenSq));
    }

    /**
     * True when the deck runs out within LOOKAHEAD_BLOCKS along the current heading.
     *
     * Samples the floor beneath a point walked forward from the player's own feet, so it only
     * fires for a drop the player is actually about to walk off rather than for any hole nearby.
     */
    private boolean dropAhead(double dx, double dz, double len) {
        double ux = dx / len;
        double uz = dz / len;
        int y = MathHelper.floor_double(mc.thePlayer.getEntityBoundingBox().minY) - 1;
        for (double d = LOOKAHEAD_STEP; d <= LOOKAHEAD_BLOCKS; d += LOOKAHEAD_STEP) {
            BlockPos ahead = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX + ux * d), y,
                    MathHelper.floor_double(mc.thePlayer.posZ + uz * d));
            if (BlockUtils.replaceable(ahead)) return true;
        }
        return false;
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
     * Nearest 45-degree heading to the direction of travel, tie-broken toward where the player is
     * already looking so a relock is the smallest turn available.
     *
     * Including the cardinals makes the stance adaptive, and that is the whole point. Walking a
     * diagonal snaps to that diagonal, so the aim sits 45 degrees off the world axis but dead on
     * the line of travel -- the fast offset stance the reference scripts use. Walking straight
     * snaps to a cardinal and aims straight back down the line.
     *
     * Restricting the candidates to the four diagonals reproduces the reference exactly, but only
     * works if the player is genuinely walking a diagonal. On a straight walk it leaves the aim 45
     * degrees off the actual line of travel, firing the ray into open air beside the bridge, and
     * no amount of recovery sweeping compensates for an aim that never reaches. Hence a setting
     * that is off by default.
     */
    private static float snapHeading(float awayYaw, float headYaw, boolean diagonalOnly) {
        float[] headings = diagonalOnly
                ? new float[] { 45f, 135f, -135f, -45f }
                : new float[] { 0f, 45f, 90f, 135f, 180f, -135f, -90f, -45f };
        float closest = Float.MAX_VALUE;
        for (float h : headings) {
            closest = Math.min(closest, Math.abs(wrap(h - awayYaw)));
        }
        float best = headings[0];
        float bestToHead = Float.MAX_VALUE;
        for (float h : headings) {
            if (Math.abs(Math.abs(wrap(h - awayYaw)) - closest) >= 1.0f) continue;
            float toHead = Math.abs(wrap(h - headYaw));
            if (toHead < bestToHead) {
                bestToHead = toHead;
                best = h;
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
        pitchOverride = Float.NaN;
        placeQueued = false;
        restoreSlot();
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        captureTick();
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
     * Records what the player is actually doing, one line per tick, for reference.
     *
     * Inferring the intended stance from a trace of the module failing is guesswork. Recording a
     * real bridge instead gives the numbers directly: how far the head is turned off the line of
     * travel, at what pitch, where in the block the feet are when a block goes down, and which
     * face gets clicked. Turn Silent rotation off, bridge by hand, and the file describes the
     * behaviour to reproduce rather than the behaviour to avoid.
     *
     * The derived "off" column is the one that matters most: yaw measured relative to the
     * direction of travel rather than to the world, which is the thing the stance has to get
     * right and the thing a raw yaw reading hides.
     */
    private void captureTick() {
        if (record == null || !record.isToggled() || !Utils.nullCheck()) {
            if (wasRecording) {
                wasRecording = false;
                closeRecord();
                Utils.sendMessage("&7[BridgeAssist] &brecording stopped");
            }
            return;
        }
        if (!wasRecording) {
            wasRecording = true;
            recordTick = 0;
            Utils.sendMessage("&7[BridgeAssist] &brecording to logs/mindless-bridgeassist-record.log");
        }

        double dx = mc.thePlayer.posX - mc.thePlayer.prevPosX;
        double dz = mc.thePlayer.posZ - mc.thePlayer.prevPosZ;
        boolean moving = dx * dx + dz * dz > MOVING_EPSILON_SQ;
        String travel = "-";
        String off = "-";
        if (moving) {
            float heading = (float) Math.toDegrees(Math.atan2(-dx, dz));
            travel = String.valueOf(Math.round(heading));
            // How far the head is turned away from straight back down the line of travel.
            off = String.valueOf(Math.round(wrap(mc.thePlayer.rotationYaw - (heading + 180f))));
        }

        writeRecord(String.format(
                "t%-5d pos %.3f %.3f %.3f  inblk %.2f %.2f  ground %-5b mY %+.3f  yaw %-4d pitch %-3d"
                        + "  travel %-4s off %-4s  fwd %+.0f str %+.0f  sneak %-5b sprint %-5b",
                recordTick++,
                mc.thePlayer.posX, mc.thePlayer.getEntityBoundingBox().minY, mc.thePlayer.posZ,
                mc.thePlayer.posX - Math.floor(mc.thePlayer.posX),
                mc.thePlayer.posZ - Math.floor(mc.thePlayer.posZ),
                mc.thePlayer.onGround, mc.thePlayer.motionY,
                Math.round(wrap(mc.thePlayer.rotationYaw)), Math.round(mc.thePlayer.rotationPitch),
                travel, off,
                mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe,
                mc.thePlayer.movementInput.sneak, mc.thePlayer.isSprinting()));
    }

    /** Notes a real block placement against the tick stream, with everything needed to reproduce it. */
    private void capturePlacement(C08PacketPlayerBlockPlacement c08) {
        if (!wasRecording) return;
        BlockPos support = c08.getPosition();
        EnumFacing face = EnumFacing.getFront(c08.getPlacedBlockDirection());
        BlockPos placed = support.offset(face);
        writeRecord(String.format(
                "     PLACE support %d %d %d  face %-5s -> block %d %d %d  hitoff %.2f %.2f %.2f"
                        + "  feetY %.3f  yaw %d pitch %d",
                support.getX(), support.getY(), support.getZ(), face,
                placed.getX(), placed.getY(), placed.getZ(),
                c08.getPlacedBlockOffsetX(), c08.getPlacedBlockOffsetY(), c08.getPlacedBlockOffsetZ(),
                mc.thePlayer.getEntityBoundingBox().minY,
                Math.round(wrap(mc.thePlayer.rotationYaw)), Math.round(mc.thePlayer.rotationPitch)));
    }

    private void writeRecord(String line) {
        try {
            if (recordWriter == null) {
                java.io.File dir = new java.io.File(mc.mcDataDir, "logs");
                if (!dir.exists() && !dir.mkdirs()) return;
                recordWriter = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(new java.io.File(dir, "mindless-bridgeassist-record.log"), true),
                        java.nio.charset.StandardCharsets.UTF_8));
                recordWriter.write("--- recording " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(new java.util.Date()) + "  silent rotation "
                        + (silentRotation.isToggled() ? "ON" : "OFF") + " ---");
                recordWriter.newLine();
            }
            recordWriter.write(line);
            recordWriter.newLine();
            recordWriter.flush();
        } catch (Throwable ignored) {
            recordWriter = null;
        }
    }

    private void closeRecord() {
        if (recordWriter == null) return;
        try {
            recordWriter.flush();
            recordWriter.close();
        } catch (Throwable ignored) {
        }
        recordWriter = null;
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
