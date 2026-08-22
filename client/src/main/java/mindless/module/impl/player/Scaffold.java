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
    /** How long to keep the aim through a gap in placements before letting go. */
    private static final int RELEASE_DELAY = 10;
    /** How far ahead, in blocks, an edge counts as near enough to start bridging for. */
    private static final double EDGE_LOOKAHEAD = 1.0;

    /**
     * Yaw offsets from straight-back, in the order they are tried.
     *
     * Every one is a whole 45 degree step, and that is the point of them. The movement fix
     * reproduces your intended direction by picking the best of eight -- forward, back, left,
     * right and the four corners -- read against the yaw being sent. Those eight are exact
     * matches only while the sent yaw is a 45 degree step from the camera; at any other angle the
     * closest of them is up to 22 degrees out and you get shoved sideways. So the aim may choose
     * an angle, but only from these.
     */
    private static final float[] YAW_OFFSETS = {0f, -45f, 45f, -90f, 90f, -135f, 135f, 180f};
    /** Faces worth clicking. UP is absent: clicking a ceiling is not how anyone bridges. */
    private static final EnumFacing[] SUPPORT_FACES = {
            EnumFacing.DOWN, EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST
    };
    /** Heights up a side face to aim at, as a fraction of the block. */
    private static final double[] FACE_HEIGHTS = {0.5, 0.8, 0.2};
    /** How far in from the rim of a face an aim point has to stay. */
    private static final double FACE_MARGIN = 0.12;
    /** A step is diagonal once the smaller motion axis is at least this much of the larger. */
    private static final double DIAGONAL_RATIO = 0.3;

    private SliderSetting modeSetting;
    private SliderSetting switchModeSetting;
    private ButtonSetting sameYSetting;
    private ButtonSetting autoJumpSetting;
    private ButtonSetting towerSetting;
    private ButtonSetting movementIntelSetting;
    private ButtonSetting diagonalSetting;
    private ButtonSetting snapMovementSetting;
    private ButtonSetting precisionHitVecSetting;

    private Aim aim;
    /** The yaw offset that worked last, tried first so the head does not hop between angles. */
    private float lastYawOffset = 0.0f;
    private Integer sameYPos = null;
    private int originalSlot = -1;
    private boolean placeQueued = false;

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
        aim = null;
        lastYawOffset = 0.0f;
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
        aim = null;
        lastYawOffset = 0.0f;
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

        // Towering means you want to go up, so let the level follow your feet for the hop
        // instead of pinning the bridge to where you took off -- otherwise the block goes under
        // the level you just left and you walk straight back down onto it.
        //
        // Gated on Tower rather than on the jump key. Releasing the level for any jump turns an
        // ordinary hop across flat ground into a block placed under your feet, which is towering
        // whether you asked for it or not. Telly's autojump sets the input rather than the key,
        // so it keeps its level either way, which is the whole point of it.
        if (isTowering()) {
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
            aim = null;
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
                aim = null;
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

    /**
     * The layer blocks are placed into.
     *
     * The two branches used to disagree by one. sameYPos is the cell the feet are in, so the
     * layer under it is one below -- but floor(posY - 0.5) already IS that layer, and taking one
     * off it as well aimed a block beneath the bridge. It only showed while the lock was off,
     * which is exactly while the jump key is held: so every towered block and every manual jump
     * dropped the bridge a level instead of carrying it up, and you walked straight back down.
     */
    private int targetY() {
        return sameYPos != null
                ? sameYPos - 1
                : MathHelper.floor_double(mc.thePlayer.posY - 0.5);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        int blockSlot = getPlaceableBlockSlot();
        if (blockSlot == -1) {
            aim = null;
            placeQueued = false;
            return;
        }

        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            if (originalSlot == -1) originalSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        ItemStack held = mc.thePlayer.inventory.getStackInSlot(blockSlot);
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            aim = null;
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
            aim = null;
            placeQueued = false;
            holdOrRelease(e);
            return;
        }

        aim = solveAim(collectTargets());

        if (aim != null) {
            // Yaw goes straight to the solved value instead of easing into it.
            //
            // Every angle the solver can return is a whole 45 degree step from the camera, and
            // those are the only angles the movement fix can reproduce exactly. Easing between
            // two of them spends ticks on angles that are not, and each of those ticks is the fix
            // picking the wrong one of its eight directions and throwing you sideways -- which is
            // the left-right-left-right, and the wobble for the first few blocks of a bridge.
            float targetPitch = aim.pitch;
            float nextPitch;
            if (Float.isNaN(rotCurrentPitch)) {
                nextPitch = MathHelper.clamp_float(targetPitch, -89.0f, 89.0f);
            } else {
                float speed = telly ? 80.0f
                        : (diagonalSetting.isToggled() && isMovingDiagonal() ? 70.0f : 35.0f);
                nextPitch = MathHelper.clamp_float(
                        rotCurrentPitch + MathHelper.clamp_float(
                                targetPitch - rotCurrentPitch, -speed, speed),
                        -89.0f, 89.0f);
            }

            float[] finalRots = RotationUtils.fixRotation(
                    quantizeAngle(aim.yaw), quantizeAngle(nextPitch),
                    RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

            idleTicks = 0;
            rotCurrentYaw = finalRots[0];
            rotCurrentPitch = finalRots[1];

            e.setYaw(finalRots[0]);
            e.setPitch(finalRots[1]);

            RotationHelper.get().setRotations(finalRots[0], finalRots[1]);
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
        if (aim == null) return;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;
        if (mc.playerController == null) return;

        // Prefer the face the rotation that just went out actually hits.
        //
        // The look packet for this tick has already been sent, carrying the position the player
        // moved to, so this ray is the same one the server will trace. Picking a block and then
        // clicking a face that some other angle would have hit is the mismatch every anticheat
        // watches for, and it is why blocks quietly failed to stick going diagonally while
        // holding right click by hand was fine -- by hand, the aim and the click are one ray.
        //
        // It is a preference and not a requirement, though. The aim is worked out a tick early,
        // from a predicted position, and stepping just past an edge leaves a band a fraction of a
        // degree wide -- so the re-trace does miss sometimes, and refusing to place at all when
        // it does was worse than the mismatch it was avoiding: one miss puts you in the air, and
        // from the air the next tick misses too. When it misses, the face the aim was solved for
        // is used instead, which the sent rotation is still within a whisker of.
        double reach = mc.playerController.getBlockReachDistance();
        Vec3 eye = getEyePos();
        BlockPos intended = aim.support.offset(aim.side);

        BlockPos support = null;
        EnumFacing side = null;
        Vec3 hitVec = null;

        MovingObjectPosition mop = rayCast(eye,
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1], reach);
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            BlockPos hitSupport = mop.getBlockPos();
            BlockPos hitCell = hitSupport.offset(mop.sideHit);
            // Sideways drift over the tick of movement is fine -- that is the point of tracing it
            // again -- but a different layer is not, that would leave blocks hanging under the
            // bridge.
            if (hitCell.getY() == intended.getY()
                    && BlockUtils.replaceable(hitCell)
                    && isUsableSupport(hitSupport)
                    && BlockUtils.canPlaceBlockOnSide(held, hitSupport, mop.sideHit)) {
                support = hitSupport;
                side = mop.sideHit;
                hitVec = mop.hitVec;
            }
        }

        if (support == null) {
            if (!BlockUtils.replaceable(intended)) return;
            if (!isUsableSupport(aim.support)) return;
            if (!BlockUtils.canPlaceBlockOnSide(held, aim.support, aim.side)) return;
            if (eye.squareDistanceTo(aim.hit) > MAX_REACH_SQ) return;
            support = aim.support;
            side = aim.side;
            hitVec = aim.hit;
        }

        if (precisionHitVecSetting.isToggled()) {
            hitVec = new Vec3(
                    support.getX() + MathHelper.clamp_double(
                            hitVec.xCoord - support.getX(), 0.001, 0.999),
                    support.getY() + MathHelper.clamp_double(
                            hitVec.yCoord - support.getY(), 0.001, 0.999),
                    support.getZ() + MathHelper.clamp_double(
                            hitVec.zCoord - support.getZ(), 0.001, 0.999));
        }

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
                support, side, hitVec)) {
            mc.thePlayer.swingItem();
            blocksPlaced++;
        }
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

    /**
     * The cells worth filling this tick, best first.
     *
     * Nothing here works out how to reach them -- that is the aim solver's job -- so a cell with
     * no face to click simply loses to the next one down the list.
     */
    private List<BlockPos> collectTargets() {
        List<BlockPos> targets = new ArrayList<>();
        double[] travel = travelDirection();
        int px = MathHelper.floor_double(mc.thePlayer.posX);
        int pz = MathHelper.floor_double(mc.thePlayer.posZ);

        // Where the body will be when the click is sent, not where it is now. A cell the player
        // is standing in cannot be filled -- the game refuses it -- and every tick spent asking
        // is a tick not spent on a cell that would have worked.
        //
        // Downward motion is deliberately not carried into it. A tick of falling drops the box a
        // twelfth of a block into the layer being bridged, so every target read as blocked the
        // moment the player left the ground -- which is the whole time it matters. Falling is
        // also the case where the block is the thing that stops the fall, so it is not a reason
        // to hold off. Only rising is, and that is the towering case this guards.
        AxisAlignedBB body = mc.thePlayer.getEntityBoundingBox().offset(
                mc.thePlayer.motionX, Math.max(0.0, mc.thePlayer.motionY), mc.thePlayer.motionZ);

        // Whether this is a genuinely diagonal step, and which way it leans.
        //
        // Read off the motion rather than the keys, so it still works when the diagonal comes
        // from looking at 45 degrees and holding nothing but forward, which is how most people do
        // it. And off a ratio rather than an epsilon: motion is never exactly square, so an
        // epsilon called nearly every straight step diagonal and laid a second lane the whole way.
        int sx = 0;
        int sz = 0;
        if (travel != null) {
            double ax = Math.abs(travel[0]);
            double az = Math.abs(travel[1]);
            double major = Math.max(ax, az);
            if (major > 1.0E-6 && Math.min(ax, az) >= major * DIAGONAL_RATIO) {
                sx = travel[0] >= 0.0 ? 1 : -1;
                sz = travel[1] >= 0.0 ? 1 : -1;
            }
        }

        // A tower is the block straight under you, and it comes first: holding jump means up.
        // Taken from where the jump will have carried you by the time the click goes out, since
        // reading it from the position the tick started at spends the first tick of every hop
        // looking at the block already under your feet.
        if (isTowering()) {
            addTarget(targets, body, new BlockPos(px, MathHelper.floor_double(
                    mc.thePlayer.posY + mc.thePlayer.motionY) - 1, pz));
        }

        int targetY = targetY();
        addBridgeTarget(targets, body, new BlockPos(px, targetY, pz), sx, sz);

        // Corners of the hitbox, but only the ones you are walking towards. All four meant a
        // corner hanging off the side of the bridge asked for a block beside you, and you do not
        // fall off the side of a block you are walking along -- that was a second lane the whole
        // way. Standing on nothing at all is already the centre cell above.
        if (travel != null) {
            for (double[] corner : FOOTPRINT_CORNERS) {
                if (corner[0] * travel[0] + corner[1] * travel[1] <= 0.0) continue;
                addBridgeTarget(targets, body, new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX + corner[0]), targetY,
                        MathHelper.floor_double(mc.thePlayer.posZ + corner[1])), sx, sz);
            }
        }

        if (travel != null && movementIntelSetting.isToggled()) {
            int lastX = px;
            int lastZ = pz;
            for (double multiplier : PROJECTION) {
                int x = MathHelper.floor_double(mc.thePlayer.posX + travel[0] * multiplier);
                int z = MathHelper.floor_double(mc.thePlayer.posZ + travel[1] * multiplier);
                if (x == lastX && z == lastZ) continue;
                lastX = x;
                lastZ = z;
                addBridgeTarget(targets, body, new BlockPos(x, targetY, z), sx, sz);
            }
        }

        return targets;
    }

    /**
     * A cell, followed straight away by the two cells a diagonal step needs under it.
     *
     * A cell entered diagonally has no solid face anywhere on it: the block you came from touches
     * it at a corner, and a corner is not something you can right click. The two orthogonal cells
     * either side are what a person fills first, and then the diagonal has something to build
     * off. They sit directly behind the cell they serve rather than at the end of the list,
     * because a diagonal step only leaves about two ticks to get both blocks down and trying
     * every cell further along first spends them.
     */
    private void addBridgeTarget(List<BlockPos> targets, AxisAlignedBB body, BlockPos pos,
                                 int sx, int sz) {
        addTarget(targets, body, pos);
        if (sx == 0 || sz == 0) return;
        addTarget(targets, body, pos.add(-sx, 0, 0));
        addTarget(targets, body, pos.add(0, 0, -sz));
    }

    private void addTarget(List<BlockPos> targets, AxisAlignedBB body, BlockPos pos) {
        if (targets.contains(pos)) return;
        if (!BlockUtils.replaceable(pos)) return;
        if (intersectsBody(pos, body)) return;
        targets.add(pos);
    }

    /**
     * Whether a cell is one the player is standing in rather than one they can fill.
     *
     * The slack matters: standing on a block puts the top of that cell exactly at the sole of the
     * foot, and a bare overlap test calls exactly-touching an overlap the moment a float lands a
     * hair the wrong side of the boundary. Anything less than a fifth of a block of genuine
     * overlap is the player resting on the cell, not occupying it.
     */
    private boolean intersectsBody(BlockPos pos, AxisAlignedBB body) {
        final double slack = 0.02;
        return pos.getX() + 1.0 > body.minX + slack && pos.getX() < body.maxX - slack
                && pos.getY() + 1.0 > body.minY + slack && pos.getY() < body.maxY - slack
                && pos.getZ() + 1.0 > body.minZ + slack && pos.getZ() < body.maxZ - slack;
    }

    /**
     * Finds a rotation that genuinely reaches one of the wanted cells.
     *
     * Two things pin it down. The yaw has to sit on a 45 degree step from the camera, or the
     * movement fix cannot reproduce your intended direction and shoves you sideways. And the
     * rotation has to be one that really does hit the face, because what the server checks the
     * placement against is the rotation the client sent, not the rotation the block would have
     * needed.
     *
     * So it walks those 45 degree steps outward from straight back, and for each one works out
     * where that yaw's vertical plane crosses the face. That gives an exact pitch rather than a
     * sampled sweep, which matters: a step just past an edge leaves a visible band well under a
     * degree wide, and a sweep walks straight over it.
     */
    private Aim solveAim(List<BlockPos> targets) {
        if (targets.isEmpty()) return null;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return null;
        if (mc.playerController == null) return null;

        Vec3 eye = predictedEyePos();
        double reach = mc.playerController.getBlockReachDistance();
        float pinYaw = MathHelper.wrapAngleTo180_float(getDirection() + 180.0f);

        for (BlockPos target : targets) {
            for (int i = -1; i < YAW_OFFSETS.length; i++) {
                float offset = i < 0 ? lastYawOffset : YAW_OFFSETS[i];
                if (i >= 0 && offset == lastYawOffset) continue;
                float yaw = MathHelper.wrapAngleTo180_float(pinYaw + offset);

                for (EnumFacing facing : SUPPORT_FACES) {
                    BlockPos support = target.offset(facing);
                    if (BlockUtils.replaceable(support)) continue;
                    if (!isUsableSupport(support)) continue;

                    EnumFacing side = facing.getOpposite();
                    // The same question the game asks before a real right click. Without it the
                    // search can settle on a face the block cannot legally go on, the click is
                    // refused, and the tick is spent for nothing.
                    if (!BlockUtils.canPlaceBlockOnSide(held, support, side)) continue;

                    MovingObjectPosition hit = traceOntoFace(support, side, eye, yaw, reach);
                    if (hit == null) continue;

                    lastYawOffset = offset;
                    return new Aim(yaw, pitchTo(eye, hit.hitVec), support, side, hit.hitVec);
                }
            }
        }

        return null;
    }

    /**
     * The pitch that puts a ray at exactly this yaw onto a face, or null when the vertical plane
     * that yaw sweeps misses the face, the face is out of reach, or something is in the way.
     */
    private MovingObjectPosition traceOntoFace(BlockPos support, EnumFacing side, Vec3 eye,
                                               float yaw, double reach) {
        double dirX = -Math.sin(Math.toRadians(yaw));
        double dirZ = Math.cos(Math.toRadians(yaw));
        double limit = Math.min(MAX_REACH_SQ, reach * reach);

        // A top or bottom face has no height to choose between; a side face does, and the middle
        // of it is the point with the most room either side before the ray slides off.
        double[] heights = side.getAxis() == EnumFacing.Axis.Y
                ? new double[]{0.5} : FACE_HEIGHTS;

        for (double height : heights) {
            Vec3 point = facePoint(support, side, eye, dirX, dirZ, height);
            if (point == null) continue;

            double dx = point.xCoord - eye.xCoord;
            double dy = point.yCoord - eye.yCoord;
            double dz = point.zCoord - eye.zCoord;
            if (dx * dx + dy * dy + dz * dz > limit) continue;

            MovingObjectPosition mop = rayCast(eye, quantizeAngle(yaw), pitchTo(eye, point), reach);
            if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK) {
                continue;
            }
            if (!mop.getBlockPos().equals(support) || mop.sideHit != side) continue;
            return mop;
        }

        return null;
    }

    /** The pitch that looks from one point to another, quantized and clamped as sent. */
    private float pitchTo(Vec3 from, Vec3 to) {
        return quantizeAngle(MathHelper.clamp_float(
                getRotationFromPosition(from, to).y, -89.0f, 89.0f));
    }

    /** Where the vertical plane swept by a fixed yaw crosses a face, or null if it misses it. */
    private Vec3 facePoint(BlockPos pos, EnumFacing side, Vec3 eye,
                           double dirX, double dirZ, double height) {
        double minX = pos.getX();
        double maxX = minX + 1.0;
        double minY = pos.getY();
        double maxY = minY + 1.0;
        double minZ = pos.getZ();
        double maxZ = minZ + 1.0;

        switch (side.getAxis()) {
            case X: {
                if (Math.abs(dirX) < 1.0E-6) return null;
                double planeX = side == EnumFacing.EAST ? maxX : minX;
                double distance = (planeX - eye.xCoord) / dirX;
                if (distance <= 0.0) return null;
                double z = eye.zCoord + dirZ * distance;
                if (z < minZ + FACE_MARGIN || z > maxZ - FACE_MARGIN) return null;
                return new Vec3(planeX, minY + height, z);
            }
            case Z: {
                if (Math.abs(dirZ) < 1.0E-6) return null;
                double planeZ = side == EnumFacing.SOUTH ? maxZ : minZ;
                double distance = (planeZ - eye.zCoord) / dirZ;
                if (distance <= 0.0) return null;
                double x = eye.xCoord + dirX * distance;
                if (x < minX + FACE_MARGIN || x > maxX - FACE_MARGIN) return null;
                return new Vec3(x, minY + height, planeZ);
            }
            default: {
                // Horizontal faces: the plane runs across the footprint rather than through one
                // edge of it, so take the middle of the stretch that is over the block.
                double planeY = side == EnumFacing.UP ? maxY : minY;
                double enter = 0.0;
                double exit = Double.MAX_VALUE;
                double[][] spans = {
                        {eye.xCoord, dirX, minX + FACE_MARGIN, maxX - FACE_MARGIN},
                        {eye.zCoord, dirZ, minZ + FACE_MARGIN, maxZ - FACE_MARGIN}
                };
                for (double[] span : spans) {
                    if (Math.abs(span[1]) < 1.0E-6) {
                        if (span[0] < span[2] || span[0] > span[3]) return null;
                        continue;
                    }
                    double a = (span[2] - span[0]) / span[1];
                    double b = (span[3] - span[0]) / span[1];
                    enter = Math.max(enter, Math.min(a, b));
                    exit = Math.min(exit, Math.max(a, b));
                }
                if (exit <= enter) return null;
                double distance = (enter + exit) * 0.5;
                return new Vec3(eye.xCoord + dirX * distance, planeY,
                        eye.zCoord + dirZ * distance);
            }
        }
    }

    private MovingObjectPosition rayCast(Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 look = getVectorForRotation(pitch, yaw);
        return mc.theWorld.rayTraceBlocks(eye, eye.addVector(
                look.xCoord * reach, look.yCoord * reach, look.zCoord * reach), false, false, true);
    }

    /**
     * Where the eyes will be when the packet goes out, not where they are now.
     *
     * A tick's rotation is decided before the player moves, but the position the server checks it
     * against is the one after. Stepping just past an edge, that is the difference between a face
     * being visible and not -- the band is a fraction of a degree wide there -- so the aim is
     * worked out from where the step lands rather than from where it started.
     */
    private Vec3 predictedEyePos() {
        return new Vec3(
                mc.thePlayer.posX + mc.thePlayer.motionX,
                mc.thePlayer.posY + mc.thePlayer.motionY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ + mc.thePlayer.motionZ);
    }

    /**
     * Whether a block is worth clicking as support.
     *
     * Right-clicking a chest, a workbench or a fence gate opens it rather than placing anything,
     * which costs the tick and pops a screen mid-bridge. Slabs, fences and the rest have hit
     * boxes that do not fill the cell, so the face is not where the arithmetic says it is.
     */
    private boolean isUsableSupport(BlockPos pos) {
        net.minecraft.block.Block block = BlockUtils.getBlock(pos);
        if (block == null) return false;
        return !BlockUtils.isInteractable(block) && !BlockUtils.notFull(block);
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

    /** A rotation that has been proven to reach a face, and the face it reaches. */
    private static class Aim {
        final float yaw;
        final float pitch;
        final BlockPos support;
        final EnumFacing side;
        final Vec3 hit;

        Aim(float yaw, float pitch, BlockPos support, EnumFacing side, Vec3 hit) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.support = support;
            this.side = side;
            this.hit = hit;
        }
    }
}
