package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PostMotionEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * High-IQ Scaffold with zero camera flips and zero falling off.
 *
 * Fixes:
 * 1. Uses `mc.thePlayer.posY - 0.5` for block target level so sprinting off edges never misses block placement.
 * 2. Keeps target rotations locked smoothly to movement direction + 180° (backward placement angle) without 180-degree forward/backward snaps.
 * 3. Supports Telly autojump burst mode, Godbridge, Watchdog, and Vanilla modes with sensitivity GCD patching (0.03404715d).
 */
public class Scaffold extends Module {
    private static final String[] MODE_OPTIONS = {"Watchdog", "Telly", "Godbridge", "Vanilla"};
    private static final String[] SWITCH_MODE_OPTIONS = {"Normal", "Hotbar"};

    /** Corners of the player's footprint, so a block goes down before the middle of you clears it. */
    private static final double[][] FOOTPRINT_CORNERS = {
            {-0.3, -0.3}, {0.3, -0.3}, {-0.3, 0.3}, {0.3, 0.3}
    };
    /** How far ahead along the motion vector to look, in multiples of one tick of movement. */
    private static final double[] PROJECTION = {0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 3.5};
    private static final double MAX_REACH_SQ = 20.25;
    /** Degrees of yaw and pitch combined that the snapback closes per tick. */
    private static final float SNAPBACK_STEP = 100.0f;
    /** Within this much of the target the yaw goes exactly there instead of easing. */
    private static final float YAW_SNAP_WINDOW = 45.0f;
    /** How long to keep the aim through a gap in placements before letting go. */
    private static final int RELEASE_DELAY = 10;
    /** How far ahead, in blocks, an edge counts as near enough to start bridging for. */
    private static final double EDGE_LOOKAHEAD = 1.0;
    /** How far to the side to look for the orthogonal cells a diagonal step needs under it. */
    private static final double DIAGONAL_FILL = 0.7;

    private SliderSetting modeSetting;
    private SliderSetting switchModeSetting;
    private ButtonSetting sameYSetting;
    private ButtonSetting autoJumpSetting;
    private ButtonSetting towerSetting;
    private ButtonSetting movementIntelSetting;
    private ButtonSetting diagonalSetting;
    private ButtonSetting snapMovementSetting;
    private ButtonSetting precisionHitVecSetting;

    private BlockData blockCache;
    private RaytracedRotation rotation;
    private Integer sameYPos = null;
    private int originalSlot = -1;
    private boolean placeQueued = false;
    private Vec3 placeHitVec;
    private EnumFacing placeSide;
    private BlockPos placeBlockPos;

    private float rotCurrentYaw = Float.NaN;
    private float rotCurrentPitch = Float.NaN;

    /** Telly only exists in the air; these track the current hop. */
    private boolean tellyEngaged;
    private int airborneTicks;
    /** Ticks in a row with nothing to place, before the aim is handed back. */
    private int idleTicks;

    public int blocksPlaced = 0;

    public Scaffold() {
        super("Scaffold", category.player);
        this.closetModule = true;

        this.registerSetting(modeSetting = new SliderSetting("Mode", 0, MODE_OPTIONS));
        this.registerSetting(switchModeSetting = new SliderSetting("Switch Mode", 0, SWITCH_MODE_OPTIONS));
        this.registerSetting(sameYSetting = new ButtonSetting("Same Y", true));
        this.registerSetting(autoJumpSetting = new ButtonSetting("Auto Jump", true));
        this.registerSetting(towerSetting = new ButtonSetting("Tower", false));
        this.registerSetting(new DescriptionSetting("Intelligence"));
        this.registerSetting(movementIntelSetting = new ButtonSetting("Movement Intelligence", true));
        this.registerSetting(diagonalSetting = new ButtonSetting("Diagonal Movement", true));
        this.registerSetting(snapMovementSetting = new ButtonSetting("Snap Movement", true));
        this.registerSetting(precisionHitVecSetting = new ButtonSetting("Grim Bounds Clamp", true));
    }

    @Override
    public void onEnable() {
        blockCache = null;
        rotation = null;
        placeQueued = false;
        originalSlot = -1;
        blocksPlaced = 0;
        rotCurrentYaw = Float.NaN;
        rotCurrentPitch = Float.NaN;
        tellyEngaged = false;
        airborneTicks = 0;
        idleTicks = 0;

        if (mc.thePlayer != null) {
            sameYPos = MathHelper.floor_double(mc.thePlayer.posY);
        }
    }

    @Override
    public void onDisable() {
        blockCache = null;
        rotation = null;
        placeQueued = false;
        sameYPos = null;
        rotCurrentYaw = Float.NaN;
        rotCurrentPitch = Float.NaN;
        tellyEngaged = false;
        airborneTicks = 0;
        idleTicks = 0;

        if (originalSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = originalSlot;
        }
        originalSlot = -1;
    }

    private boolean isTellyMode() {
        return (int) modeSetting.getInput() == 1; // 1 = Telly
    }

    /**
     * Latches the bridging level once, rather than re-reading it every tick.
     *
     * The old condition refreshed the locked Y whenever onGround was true -- which while
     * bridging is nearly every tick -- so Same Y held nothing at all. It also never released,
     * so once the module had a level it carried it around on ordinary ground too. Now it is
     * taken the first time you are stood on something and only given up if you end up more
     * than a few blocks off it, which is a clutch or a staircase rather than a bridge.
     */
    private void updateYLock(boolean telly) {
        if (!sameYSetting.isToggled() && !telly) {
            sameYPos = null;
            return;
        }

        // A jump you pressed yourself means you want to go up, so let the level follow your feet
        // for that hop instead of pinning the bridge to where you took off. Telly's autojump
        // sets the input directly rather than the key, so it still keeps its level, which is
        // the whole point of it.
        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            sameYPos = null;
            return;
        }

        // Re-taken every time you are stood on something, so landing a step higher carries the
        // bridge up with you. Held while you are in the air, which is what Same Y is for.
        if (mc.thePlayer.onGround) {
            sameYPos = MathHelper.floor_double(mc.thePlayer.posY);
            return;
        }
        if (sameYPos != null
                && Math.abs(MathHelper.floor_double(mc.thePlayer.posY) - sameYPos) > 3) {
            sameYPos = null;
        }
    }

    /**
     * Telly, as the name has always meant: sprint, hop, look down for the arc, land looking
     * normal, hop again.
     *
     * The old condition placed while standing on the ground and only skipped the top of the
     * jump, so the rotation sat pinned down the bridge the entire time you were bridging. A
     * rotation that never comes back up while you sprint in a straight line is the thing that
     * gets picked up -- there is no legitimate way to play looking backwards and down for
     * thirty seconds straight.
     *
     * Grounded now means grounded: no aim, no placement, the rotation handed back. Everything
     * happens inside the hop, which is where a person doing this by hand does it too.
     *
     * @return true when the module should go on to search and place this tick.
     */
    private boolean updateTelly(ClientRotationEvent e) {
        if (mc.thePlayer.onGround) {
            airborneTicks = 0;
            tellyEngaged = false;
            blockCache = null;
            rotation = null;
            placeQueued = false;
            releaseRotation(e);
            return false;
        }

        airborneTicks++;

        if (!tellyEngaged) {
            // A hop you asked for, or the first tick of walking off an edge. Anything else --
            // knockback, a fall from somewhere else -- is left alone.
            if (mc.gameSettings.keyBindJump.isKeyDown() || airborneTicks == 1) {
                tellyEngaged = true;
            } else {
                blockCache = null;
                rotation = null;
                placeQueued = false;
                releaseRotation(e);
                return false;
            }
        }

        return true;
    }

    /**
     * Keeps the current aim through a short gap in placements, and only then gives it back.
     *
     * Letting go the instant there is nothing to place, and taking the target back the instant
     * there is, turns an ordinary one or two tick gap into the head flicking down and up again.
     * Bridging is full of those gaps: the block just placed fills the cell it was aimed at, and
     * the next one is not due until you have moved far enough to need it. So hold through them,
     * and let go only once you have actually stopped.
     */
    private void holdOrRelease(ClientRotationEvent e) {
        if (Float.isNaN(rotCurrentYaw) || Float.isNaN(rotCurrentPitch)) return;

        if (++idleTicks < RELEASE_DELAY) {
            e.setYaw(rotCurrentYaw);
            e.setPitch(rotCurrentPitch);
            RotationHelper.get().setRotations(rotCurrentYaw, rotCurrentPitch);
            return;
        }

        releaseRotation(e);
    }

    /**
     * Walks the rotation back to where the player is actually looking, then stops driving it.
     *
     * Dropping the override outright would put a hard jump in the rotation stream on every
     * landing, which is its own signature. This closes the gap quickly -- a landing is not a
     * moment to be leisurely about -- and then lets go entirely, so between hops nothing is
     * being sent but the player's own aim.
     */
    private void releaseRotation(ClientRotationEvent e) {
        if (Float.isNaN(rotCurrentYaw) || Float.isNaN(rotCurrentPitch)) return;

        float targetYaw = mc.thePlayer.rotationYaw;
        float targetPitch = mc.thePlayer.rotationPitch;

        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - rotCurrentYaw);
        float deltaPitch = targetPitch - rotCurrentPitch;

        if (Math.abs(deltaYaw) + Math.abs(deltaPitch) <= SNAPBACK_STEP) {
            rotCurrentYaw = Float.NaN;
            rotCurrentPitch = Float.NaN;
            return;
        }

        float scale = SNAPBACK_STEP / (Math.abs(deltaYaw) + Math.abs(deltaPitch));
        float nextYaw = quantizeAngle(rotCurrentYaw + deltaYaw * scale);
        float nextPitch = MathHelper.clamp_float(
                quantizeAngle(rotCurrentPitch + deltaPitch * scale), -89.0f, 89.0f);

        float[] fixed = RotationUtils.fixRotation(nextYaw, nextPitch,
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

        rotCurrentYaw = fixed[0];
        rotCurrentPitch = fixed[1];
        e.setYaw(fixed[0]);
        e.setPitch(fixed[1]);
        RotationHelper.get().setRotations(fixed[0], fixed[1]);
    }

    /**
     * Holding jump with Tower on builds straight up under you instead of bridging outward.
     *
     * Nothing else is needed to make it work: holding jump already releases the Y lock, so the
     * layer follows your feet, and once you rise clear of the block you took off from the cell
     * you were stood in reads as empty and gets filled.
     */
    private boolean isTowering() {
        return towerSetting.isToggled()
                && mc.gameSettings.keyBindJump.isKeyDown()
                && !mc.thePlayer.capabilities.isFlying;
    }

    /** Where the player is heading: their motion, or the keys when they are not moving yet. */
    private double[] travelDirection() {
        double px = mc.thePlayer.motionX;
        double pz = mc.thePlayer.motionZ;
        if (px * px + pz * pz > 1.0E-6) return new double[]{px, pz};

        float forward = rawForward();
        float strafe = rawStrafe();
        double length = Math.sqrt(forward * forward + strafe * strafe);
        if (length <= 0.01) return null;

        double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
        return new double[]{
                (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) / length * 0.75,
                (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) / length * 0.75
        };
    }

    /**
     * Whether there is actually a gap worth bridging: under your feet, under any corner of
     * them, or one block along the way you are heading.
     */
    private boolean isNearEdge() {
        int targetY = targetY();

        if (BlockUtils.replaceable(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX), targetY,
                MathHelper.floor_double(mc.thePlayer.posZ)))) {
            return true;
        }

        for (double[] corner : FOOTPRINT_CORNERS) {
            if (BlockUtils.replaceable(new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX + corner[0]), targetY,
                    MathHelper.floor_double(mc.thePlayer.posZ + corner[1])))) {
                return true;
            }
        }

        double[] travel = travelDirection();
        if (travel == null) return false;

        double length = Math.sqrt(travel[0] * travel[0] + travel[1] * travel[1]);
        if (length <= 1.0E-6) return false;

        return BlockUtils.replaceable(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX + travel[0] / length * EDGE_LOOKAHEAD),
                targetY,
                MathHelper.floor_double(mc.thePlayer.posZ + travel[1] / length * EDGE_LOOKAHEAD)));
    }

    /** The layer blocks are placed into. */
    private int targetY() {
        return (sameYPos != null
                ? sameYPos
                : MathHelper.floor_double(mc.thePlayer.posY - 0.5)) - 1;
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        int blockSlot = getPlaceableBlockSlot();
        if (blockSlot == -1) {
            blockCache = null;
            rotation = null;
            placeQueued = false;
            return;
        }

        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            if (originalSlot == -1) originalSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        ItemStack held = mc.thePlayer.inventory.getStackInSlot(blockSlot);
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            blockCache = null;
            rotation = null;
            placeQueued = false;
            return;
        }

        boolean telly = isTellyMode();
        updateYLock(telly);

        if (telly && !updateTelly(e)) return;

        // Nothing to bridge means nothing to do. Without this it kept a bridging rotation and
        // hunted for placements while walking across a solid floor, because the forward search
        // reaches far enough to find a hole several blocks away and start aiming at it.
        if (!isTowering() && !isNearEdge()) {
            blockCache = null;
            rotation = null;
            placeQueued = false;
            holdOrRelease(e);
            return;
        }

        // 2. Search placement block & calculation
        boolean dataFound = updateData();

        if (dataFound && blockCache != null && rotation != null) {
            // Yaw comes from where you are walking, not from the block.
            //
            // It used to be the yaw that points at the chosen hit point, and that point is one
            // of five offsets across the support's face. The support sits about half a block
            // behind your feet, so a 0.35 offset across it is tens of degrees of yaw, and which
            // offset wins changes as you move. That jitter lands on the rotation we send, and
            // the movement fix reads that rotation to decide which of eight directions your
            // keys mean -- so a few degrees of wobble at a sector boundary throws your movement
            // 45 degrees sideways, every tick, which is the left-right-left-right.
            //
            // Pinning it to the movement direction makes the sent yaw exactly camera + 180,
            // which is an exact match in that eight-way choice: holding W resolves to straight
            // forward with no strafe at all. The pitch still comes from the block, and the
            // placement still carries its own hit vector, so nothing about aiming is lost.
            float targetYaw = MathHelper.wrapAngleTo180_float(getDirection() + 180.0f);
            float targetPitch = rotation.rotation.y;

            // First tick of a bridge takes the target outright rather than easing into it.
            //
            // Easing in from wherever you were looking is up to 180 degrees, which at 35 a tick
            // is five or six ticks spent part-way round. The movement fix reads that part-way
            // yaw to decide which of eight directions your keys mean, so the whole way round it
            // keeps landing in the wrong sector -- that is the wobble at the start that settles
            // after a few blocks. One turn and it is over.
            float[] smoothed;
            if (Float.isNaN(rotCurrentYaw) || Float.isNaN(rotCurrentPitch)) {
                smoothed = new float[]{targetYaw, MathHelper.clamp_float(targetPitch, -89.0f, 89.0f)};
            } else {
                smoothed = getRotationsSmoothed(rotCurrentYaw, rotCurrentPitch,
                        targetYaw, targetPitch, telly);
            }

            // GCD quantize angle (sensitivity patch)
            float finalYaw = quantizeAngle(smoothed[0]);
            float finalPitch = quantizeAngle(smoothed[1]);

            float[] finalRots = RotationUtils.fixRotation(finalYaw, finalPitch, RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

            idleTicks = 0;
            rotCurrentYaw = finalRots[0];
            rotCurrentPitch = finalRots[1];

            e.setYaw(finalRots[0]);
            e.setPitch(finalRots[1]);

            RotationHelper.get().setRotations(finalRots[0], finalRots[1]);

            // Queue block placement
            placeBlockPos = blockCache.blockWithDirection.blockPos;
            placeSide = blockCache.blockWithDirection.direction;
            placeHitVec = rotation.hitResult != null && rotation.hitResult.hitVec != null ? rotation.hitResult.hitVec : getCenterHitVec(placeBlockPos, placeSide);
            placeQueued = true;
        } else {
            holdOrRelease(e);
            placeQueued = false;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck()) return;

        // Autojump handling
        if (isTellyMode() && mc.thePlayer.onGround && isMoving()) {
            e.setJump(true);
        } else if (sameYSetting.isToggled() && autoJumpSetting.isToggled() && mc.thePlayer.onGround) {
            if (isNearingVoidEdge()) {
                e.setJump(true);
            }
        }
    }

    /**
     * Sends the placement, after the look packet rather than before it.
     *
     * <p>This used to sit in the movement-input event. Both events run in the same tick, but the
     * input one runs from onLivingUpdate, which is before onUpdateWalkingPlayer sends the C03
     * carrying the rotation this placement was aimed with. So the order on the wire was place,
     * then look -- the server checked every placement against the rotation from the tick before,
     * which is exactly the mismatch Watchdog and Grim look for, and is why blocks would quietly
     * fail to appear when turning. PostMotionEvent fires immediately after that C03 goes out, so
     * the server now has the right rotation before the placement arrives.
     */
    @SubscribeEvent
    public void onPostMotion(PostMotionEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck()) return;

        if (!placeQueued) return;
        placeQueued = false;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (placeBlockPos == null || placeSide == null || placeHitVec == null) return;
        if (mc.playerController == null) return;

        // A tick has passed since the target was picked; something else may have filled it.
        if (!BlockUtils.replaceable(placeBlockPos.offset(placeSide))) return;

        double hitX = placeHitVec.xCoord - placeBlockPos.getX();
        double hitY = placeHitVec.yCoord - placeBlockPos.getY();
        double hitZ = placeHitVec.zCoord - placeBlockPos.getZ();
        if (precisionHitVecSetting.isToggled()) {
            hitX = MathHelper.clamp_double(hitX, 0.001, 0.999);
            hitY = MathHelper.clamp_double(hitY, 0.001, 0.999);
            hitZ = MathHelper.clamp_double(hitZ, 0.001, 0.999);
        }
        Vec3 hitVec = new Vec3(placeBlockPos.getX() + hitX,
                placeBlockPos.getY() + hitY,
                placeBlockPos.getZ() + hitZ);

        // Go through the same call a real right click makes instead of putting the packet on the
        // wire by hand.
        //
        // Sending only C08 meant the block never existed on this client until the server echoed
        // it back a few ticks later. So you walked out over a hole the client still thought was
        // air, started falling, got corrected when the block arrived, and the search picked the
        // same cell again in the meantime because it still read as empty. That is the place,
        // stop, go, place, stop -- and it is why holding right click by hand felt fine, because
        // that path runs onItemUse and the block is simply there. This also syncs the held slot,
        // which the hand-rolled version never did.
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held,
                placeBlockPos, placeSide, hitVec)) {
            mc.thePlayer.swingItem();
            blocksPlaced++;
        }
    }

    private float[] getRotationsSmoothed(float currentYaw, float currentPitch, float targetYaw, float targetPitch, boolean tellyActive) {
        float deltaYaw = MathHelper.wrapAngleTo180_float(targetYaw - currentYaw);
        float deltaPitch = targetPitch - currentPitch;

        float speed = 35.0f;
        if (tellyActive) {
            speed = 80.0f;
        } else if (diagonalSetting.isToggled() && isMovingDiagonal()) {
            speed = 70.0f;
        }

        // Land exactly on the target once it is close, rather than easing in forever.
        //
        // The yaw we send is what the movement fix reads to decide which of eight directions
        // your keys mean. Sitting a few degrees short of the target is enough to fall on the
        // wrong side of a sector boundary, and that is a 45 degree sideways shove. Landing
        // exactly on camera + 180 makes one of those eight an exact match, so there is no
        // sideways component at all -- in every mode, not just when the easing happened to have
        // caught up.
        float nextYaw = Math.abs(deltaYaw) <= YAW_SNAP_WINDOW
                ? targetYaw
                : currentYaw + MathHelper.clamp_float(deltaYaw, -speed, speed);
        float nextPitch = currentPitch + MathHelper.clamp_float(deltaPitch, -speed, speed);

        return new float[]{nextYaw, MathHelper.clamp_float(nextPitch, -89.0f, 89.0f)};
    }

    /**
     * The keys you are actually holding, not thePlayer's move fields.
     *
     * Those fields hold what the movement fix rewrote them to last tick: it re-picks
     * forward/strafe so that, read against the rotation we are sending, you travel where the
     * camera points. Holding W with a rotation 180 degrees behind you comes back out of that as
     * moveForward = -1. Feeding it to getDirection, which reads it against the camera instead,
     * turned the bridge direction around, which moved the rotation, which changed what the fix
     * picked next tick. That loop is the veering left and right -- the module was steering off
     * its own output. Reading the keybinds breaks it.
     */
    private float rawForward() {
        float forward = 0.0f;
        if (mc.gameSettings.keyBindForward.isKeyDown()) forward += 1.0f;
        if (mc.gameSettings.keyBindBack.isKeyDown()) forward -= 1.0f;
        return forward;
    }

    private float rawStrafe() {
        float strafe = 0.0f;
        if (mc.gameSettings.keyBindLeft.isKeyDown()) strafe += 1.0f;
        if (mc.gameSettings.keyBindRight.isKeyDown()) strafe -= 1.0f;
        return strafe;
    }

    private boolean isMovingDiagonal() {
        return rawForward() != 0.0f && rawStrafe() != 0.0f;
    }

    private boolean isMoving() {
        return rawForward() != 0.0f || rawStrafe() != 0.0f;
    }

    private boolean updateData() {
        blockCache = null;
        rotation = null;

        Vec3 eyePos = getEyePos();
        double[] travel = travelDirection();

        // A tower is the block directly under you, and it takes priority: if you are holding
        // jump you want to go up. It no longer returns outright though -- towering while walking
        // is a normal thing to do, and the bridge underneath you still has to keep up.
        if (isTowering() && accept(getBlockData(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)), eyePos))) {
            return true;
        }

        int targetY = targetY();

        if (accept(getBlockData(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                targetY,
                MathHelper.floor_double(mc.thePlayer.posZ)), eyePos))) {
            return true;
        }

        // Corners of the hitbox, but only the ones you are walking towards.
        //
        // All four meant a corner hanging off the side of the bridge asked for a block beside
        // you, and you are not going to fall off the side of a block you are walking along -- so
        // that was just a second lane being laid the whole way. Standing on nothing at all is
        // already covered by the centre check above.
        if (travel != null) {
            for (double[] corner : FOOTPRINT_CORNERS) {
                if (corner[0] * travel[0] + corner[1] * travel[1] <= 0.0) continue;
                if (accept(getBlockData(new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX + corner[0]),
                        targetY,
                        MathHelper.floor_double(mc.thePlayer.posZ + corner[1])), eyePos))) {
                    return true;
                }
            }
        }

        // Going diagonally you step into a cell whose only face-adjacent neighbours are the two
        // orthogonal cells either side of it. With neither filled there is no face to click,
        // nothing goes down, and you drop through the corner.
        //
        // Keyed off the keys rather than the motion vector: motion is never exactly square, so
        // testing it against a small epsilon called almost every step diagonal and laid both
        // orthogonal blocks every time. That is the other half of the double-wide bridge.
        if (travel != null && rawForward() != 0.0f && rawStrafe() != 0.0f) {
            int aheadX = MathHelper.floor_double(
                    mc.thePlayer.posX + Math.signum(travel[0]) * DIAGONAL_FILL);
            int aheadZ = MathHelper.floor_double(
                    mc.thePlayer.posZ + Math.signum(travel[1]) * DIAGONAL_FILL);

            if (accept(getBlockData(new BlockPos(
                    aheadX, targetY, MathHelper.floor_double(mc.thePlayer.posZ)), eyePos))) {
                return true;
            }
            if (accept(getBlockData(new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX), targetY, aheadZ), eyePos))) {
                return true;
            }
        }

        if (!movementIntelSetting.isToggled()) return false;

        // Where you are about to be. This used to step one tick of motion at a time up to three,
        // which at sprint speed is well under a block, so the next block along was only ever
        // found at the last possible moment.
        if (travel == null) return false;
        double px = travel[0];
        double pz = travel[1];

        int lastX = MathHelper.floor_double(mc.thePlayer.posX);
        int lastZ = MathHelper.floor_double(mc.thePlayer.posZ);
        for (double multiplier : PROJECTION) {
            int projectedX = MathHelper.floor_double(mc.thePlayer.posX + px * multiplier);
            int projectedZ = MathHelper.floor_double(mc.thePlayer.posZ + pz * multiplier);
            if (projectedX == lastX && projectedZ == lastZ) continue;
            lastX = projectedX;
            lastZ = projectedZ;
            if (accept(getBlockData(new BlockPos(projectedX, targetY, projectedZ), eyePos))) {
                return true;
            }
        }

        return false;
    }

    private boolean accept(BlockData data) {
        if (data == null) return false;
        blockCache = data;
        rotation = data.rotation;
        return true;
    }

    private BlockData getBlockData(BlockPos targetBlockPos, Vec3 eyePos) {
        if (BlockUtils.replaceable(targetBlockPos)) {
            List<BlockWithDirection> blockList = new ArrayList<>();

            for (EnumFacing facing : EnumFacing.values()) {
                BlockPos neighbor = targetBlockPos.offset(facing);
                if (!BlockUtils.replaceable(neighbor)) {
                    blockList.add(new BlockWithDirection(neighbor, facing.getOpposite()));
                }
            }

            // Fallback 2-block extend neighbors
            if (blockList.isEmpty()) {
                for (EnumFacing facing : EnumFacing.values()) {
                    BlockPos neighbor = targetBlockPos.offset(facing);
                    for (EnumFacing secondFacing : EnumFacing.values()) {
                        BlockPos secondNeighbor = neighbor.offset(secondFacing);
                        if (!BlockUtils.replaceable(secondNeighbor)) {
                            blockList.add(new BlockWithDirection(secondNeighbor, secondFacing.getOpposite()));
                        }
                    }
                }
            }

            if (blockList.isEmpty()) return null;

            // Nearest and best lined up with where we are already looking.
            //
            // The old comparator measured blockPos.offset(direction) against the target, but by
            // construction that IS the target, so every candidate scored zero and the order was
            // whatever EnumFacing.values() happened to be. Picking a support on the far side of
            // the gap costs a longer reach and a bigger turn for the same block.
            blockList.sort(Comparator.comparingDouble(data -> supportScore(data, eyePos)));

            for (BlockWithDirection block : blockList) {
                RaytracedRotation rRot = getRotation(block, eyePos);
                if (rRot != null) {
                    return new BlockData(block, rRot);
                }
            }
        }
        return null;
    }

    private double supportScore(BlockWithDirection data, Vec3 eyePos) {
        Vec3 hit = getCenterHitVec(data.blockPos, data.direction);
        double dx = hit.xCoord - eyePos.xCoord;
        double dy = hit.yCoord - eyePos.yCoord;
        double dz = hit.zCoord - eyePos.zCoord;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > MAX_REACH_SQ) return Double.MAX_VALUE;

        double length = Math.sqrt(distanceSq);
        Vec3 look = getVectorForRotation(
                Float.isNaN(rotCurrentPitch) ? mc.thePlayer.rotationPitch : rotCurrentPitch,
                Float.isNaN(rotCurrentYaw) ? mc.thePlayer.rotationYaw : rotCurrentYaw);
        double alignment = length > 0.0
                ? (look.xCoord * dx + look.yCoord * dy + look.zCoord * dz) / length
                : -1.0;
        return distanceSq + (1.0 - alignment) * 0.25;
    }

    private RaytracedRotation getRotation(BlockWithDirection data, Vec3 eyePos) {
        float moveDir = getDirection();
        float baseYaw = MathHelper.wrapAngleTo180_float(moveDir + 180.0f);
        Vec2f sortingAngle = new Vec2f(baseYaw, 85.0f);

        return getRotationFromRaycastedBlock(data.blockPos, data.direction, sortingAngle, eyePos);
    }

    private RaytracedRotation getRotationFromRaycastedBlock(BlockPos blockPos, EnumFacing side, Vec2f priorityRotations, Vec3 eyePos) {
        double reach = mc.playerController.getBlockReachDistance();
        List<RaytracedRotation> rotations = new ArrayList<>();

        Vec3 centerVec = getCenterHitVec(blockPos, side);

        // Offsets across the face, in the face's own plane.
        //
        // These used to be world-space XY offsets applied to every face. On a face whose normal
        // is X or Y -- four of the six -- that pushes the point along the normal, off the face
        // and into the block, so only the centre point was ever a real candidate there. Four
        // fewer angles to choose from means more ticks where nothing lines up and no block goes
        // down. Deriving the two in-plane axes from the normal makes all five usable on any face.
        Vec3 axisU;
        Vec3 axisV;
        switch (side.getAxis()) {
            case Y:
                axisU = new Vec3(1.0, 0.0, 0.0);
                axisV = new Vec3(0.0, 0.0, 1.0);
                break;
            case X:
                axisU = new Vec3(0.0, 0.0, 1.0);
                axisV = new Vec3(0.0, 1.0, 0.0);
                break;
            default:
                axisU = new Vec3(1.0, 0.0, 0.0);
                axisV = new Vec3(0.0, 1.0, 0.0);
                break;
        }

        double[][] faceOffsets = new double[][]{{0, 0}, {-0.35, -0.35}, {0.35, -0.35}, {-0.35, 0.35}, {0.35, 0.35}};

        for (double[] off : faceOffsets) {
            Vec3 testPoint = centerVec.addVector(
                    axisU.xCoord * off[0] + axisV.xCoord * off[1],
                    axisU.yCoord * off[0] + axisV.yCoord * off[1],
                    axisU.zCoord * off[0] + axisV.zCoord * off[1]);
            Vec2f rawRot = getRotationFromPosition(eyePos, testPoint);
            Vec2f raytraceRotation = new Vec2f(quantizeAngle(rawRot.x), quantizeAngle(rawRot.y));

            Vec3 lookVec = getVectorForRotation(raytraceRotation.y, raytraceRotation.x);
            Vec3 rayEnd = eyePos.addVector(lookVec.xCoord * reach, lookVec.yCoord * reach, lookVec.zCoord * reach);

            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eyePos, rayEnd, false, false, true);

            if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                if (mop.getBlockPos().equals(blockPos) && mop.sideHit == side) {
                    rotations.add(new RaytracedRotation(raytraceRotation, mop));
                }
            }
        }

        if (rotations.isEmpty()) return null;

        rotations.sort(Comparator.comparingDouble(r -> getRotationDifference(r.rotation, priorityRotations)));
        return rotations.get(0);
    }

    private float quantizeAngle(float angle) {
        double gcd = 0.03404715d;
        return (float) (Math.round(angle / gcd) * gcd);
    }

    private float getRotationDifference(Vec2f a, Vec2f b) {
        return Math.abs(MathHelper.wrapAngleTo180_float(a.x - b.x)) + Math.abs(a.y - b.y);
    }

    private Vec2f getRotationFromPosition(Vec3 from, Vec3 to) {
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) Math.toDegrees(-Math.atan2(dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, dist));

        return new Vec2f(yaw, pitch);
    }

    private Vec3 getVectorForRotation(float pitch, float yaw) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    private Vec3 getEyePos() {
        return new Vec3(mc.thePlayer.posX, mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
    }

    private Vec3 getCenterHitVec(BlockPos pos, EnumFacing side) {
        return new Vec3(
                pos.getX() + 0.5 + side.getFrontOffsetX() * 0.5,
                pos.getY() + 0.5 + side.getFrontOffsetY() * 0.5,
                pos.getZ() + 0.5 + side.getFrontOffsetZ() * 0.5
        );
    }

    private boolean isNearingVoidEdge() {
        double vx = mc.thePlayer.motionX * 2.0;
        double vz = mc.thePlayer.motionZ * 2.0;
        BlockPos futurePos = new BlockPos(mc.thePlayer.posX + vx, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ + vz);
        return mc.theWorld.isAirBlock(futurePos);
    }

    public float getDirection() {
        float moveForward = rawForward();
        float moveStrafing = rawStrafe();

        float direction = mc.thePlayer.rotationYaw;
        float forward = 1.0F;

        if (moveForward < 0.0F) {
            direction += 180.0F;
            forward = -0.5F;
        } else if (moveForward > 0.0F) {
            forward = 0.5F;
        }

        if (moveStrafing > 0.0F) {
            direction -= 90.0F * forward;
        } else if (moveStrafing < 0.0F) {
            direction += 90.0F * forward;
        }

        return direction;
    }

    private int getPlaceableBlockSlot() {
        if (mc.thePlayer.getHeldItem() != null && mc.thePlayer.getHeldItem().getItem() instanceof ItemBlock) {
            return mc.thePlayer.inventory.currentItem;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                return i;
            }
        }
        return -1;
    }

    private static class Vec2f {
        final float x;
        final float y;

        public Vec2f(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private static class BlockWithDirection {
        final BlockPos blockPos;
        final EnumFacing direction;

        public BlockWithDirection(BlockPos blockPos, EnumFacing direction) {
            this.blockPos = blockPos;
            this.direction = direction;
        }
    }

    private static class RaytracedRotation {
        final Vec2f rotation;
        final MovingObjectPosition hitResult;

        public RaytracedRotation(Vec2f rotation, MovingObjectPosition hitResult) {
            this.rotation = rotation;
            this.hitResult = hitResult;
        }
    }

    private static class BlockData {
        final BlockWithDirection blockWithDirection;
        final RaytracedRotation rotation;

        public BlockData(BlockWithDirection blockWithDirection, RaytracedRotation rotation) {
            this.blockWithDirection = blockWithDirection;
            this.rotation = rotation;
        }
    }
}
