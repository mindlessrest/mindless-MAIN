package keystrokesmod.module.impl.player;

import keystrokesmod.event.PostMotionEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockLiquid;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Catches the player when falling into void or onto a lethal landing.
 *
 * Logic:
 *  1. Every tick, simulate where the player will land (up to N ticks ahead).
 *  2. If the landing is lethal (or void), search nearby blocks for a valid
 *     placement: support block + exposed face + within reach.
 *  3. Silently rotate toward the best placement hit point.
 *  4. Place the block once the rotation is close enough.
 *  5. After placing, optionally reset rotation and return to the previous slot.
 */
public class Clutch extends Module {

    // ── settings ────────────────────────────────────────────────────────────

    private final ButtonSetting onVoid;
    private final ButtonSetting onLethalFall;
    private final ButtonSetting onMoreThanX;
    private final SliderSetting blocksThreshold;
    private final SliderSetting rotSpeed;
    private final ButtonSetting silentAim;
    private final ButtonSetting resetAngle;
    private final SliderSetting resetAngleDelay;
    private final ButtonSetting returnSlot;
    private final SliderSetting returnDelay;
    private final ButtonSetting swing;
    private final ButtonSetting showBlockCount;

    // ── constants ────────────────────────────────────────────────────────────

    /** Max fall simulation ticks to find a landing block. */
    private static final int SIM_TICKS = 60;
    /** Block reach used for placement. */
    private static final double REACH = 4.5;
    /** Search radius around the player for support blocks. */
    private static final int SEARCH_R = 2;

    // ── state ────────────────────────────────────────────────────────────────

    private boolean active;
    private float aimYaw, aimPitch;
    private float savedYaw, savedPitch;
    private boolean hasSavedAngle;

    private BlockPos   pendingSupportPos;
    private EnumFacing pendingFace;
    private Vec3       pendingHitVec;

    private int prevSlot = -1;
    private int resetDelayTicks;
    private int returnDelayTicks;

    // ── init ─────────────────────────────────────────────────────────────────

    public Clutch() {
        super("Clutch", category.player);
        registerSetting(onVoid          = new ButtonSetting("On void",            true));
        registerSetting(onLethalFall    = new ButtonSetting("On lethal fall",     true));
        registerSetting(onMoreThanX     = new ButtonSetting("On more than X blocks", false));
        registerSetting(blocksThreshold = new SliderSetting("Blocks", 3, 1, 10, 1));
        registerSetting(rotSpeed        = new SliderSetting("Rotation speed", 16, 1, 30, 1));
        registerSetting(silentAim       = new ButtonSetting("Silent aim",         false));
        registerSetting(resetAngle      = new ButtonSetting("Reset angle",        true));
        registerSetting(resetAngleDelay = new SliderSetting("Reset angle delay", "tick", 2, 0, 6, 1));
        registerSetting(returnSlot      = new ButtonSetting("Return to last slot",true));
        registerSetting(returnDelay     = new SliderSetting("Return delay",       "tick", 2, 0, 6, 1));
        registerSetting(swing           = new ButtonSetting("Swing",              true));
        registerSetting(showBlockCount  = new ButtonSetting("Show block count",   false));
        this.alwaysOn = true;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @Override
    public String getInfo() {
        if (!showBlockCount.isToggled()) return null;
        return String.valueOf(countBlocks());
    }

    // ── tick ──────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck() || mc.currentScreen != null) return;

        tickDelays(event);

        if (!shouldTrigger()) {
            deactivate();
            return;
        }

        // Find what block to place if not already tracking one
        if (pendingSupportPos == null) {
            findTarget();
        }

        if (pendingSupportPos == null) {
            deactivate();
            return;
        }

        active = true;

        // Rotate toward the hit point
        Vec3 eye = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);

        float[] rot = RotationUtils.getRotationsFromEye(eye,
                pendingHitVec.xCoord, pendingHitVec.yCoord, pendingHitVec.zCoord);
        aimYaw   = rot[0];
        aimPitch = MathHelper.clamp_float(rot[1], -90f, 90f);

        float baseYaw   = event.getYaw();
        float basePitch = event.getPitch();

        float[] smoothed = RotationUtils.smoothRotation(baseYaw, basePitch, aimYaw, aimPitch,
                (int) rotSpeed.getInput(), 0f);

        if (!hasSavedAngle) {
            savedYaw   = baseYaw;
            savedPitch = basePitch;
            hasSavedAngle = true;
        }

        event.setRotations(smoothed[0], smoothed[1]);

        // Suppress left-click while clutching
        if (mc.gameSettings.keyBindAttack.isKeyDown()) {
            net.minecraft.client.settings.KeyBinding.setKeyBindState(
                    mc.gameSettings.keyBindAttack.getKeyCode(), false);
        }
    }

    @SubscribeEvent
    public void onPostMotion(PostMotionEvent event) {
        if (!Utils.nullCheck() || !active || pendingSupportPos == null) return;

        // After this tick's C03 is on the wire, place using the rotation we just sent
        float sentYaw   = RotationUtils.serverRotations[0];
        float sentPitch = RotationUtils.serverRotations[1];

        // Verify ray-trace still hits the intended support face
        MovingObjectPosition mop = RotationUtils.rayTraceCustom(REACH, sentYaw, sentPitch);
        if (mop == null
                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !mop.getBlockPos().equals(pendingSupportPos)
                || mop.sideHit != pendingFace) {
            // Rotation not there yet — keep trying next tick
            return;
        }

        // Check we still have a block item
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!isValidBlockItem(held)) {
            // Try to grab one from hotbar
            int slot = findBlockSlot();
            if (slot == -1) { deactivate(); return; }
            if (prevSlot == -1) prevSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
            held = mc.thePlayer.getHeldItem();
        }

        if (!isValidBlockItem(held)) { deactivate(); return; }

        // Place
        boolean placed = mc.playerController.onPlayerRightClick(
                mc.thePlayer, mc.theWorld, held,
                pendingSupportPos, pendingFace, mop.hitVec);

        if (placed) {
            if (swing.isToggled()) mc.thePlayer.swingItem();
            else mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());

            // Schedule post-placement cleanup
            resetDelayTicks  = (int) resetAngleDelay.getInput();
            returnDelayTicks = (int) returnDelay.getInput();
            pendingSupportPos = null;
            pendingFace       = null;
            pendingHitVec     = null;
            active = false;
        } else {
            // Target became invalid (block placed by server, entity collision, etc.)
            pendingSupportPos = null;
            pendingFace       = null;
            pendingHitVec     = null;
        }
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        // Re-check target every tick so we always aim at the freshest candidate
        if (!Utils.nullCheck() || mc.currentScreen != null) return;
        if (active && pendingSupportPos != null) {
            // Re-validate: target might have been filled since last tick
            if (!BlockUtils.replaceable(pendingSupportPos.offset(pendingFace))) {
                // Block already exists — find a new target
                pendingSupportPos = null;
                pendingFace       = null;
                pendingHitVec     = null;
            }
        }
    }

    // ── trigger ───────────────────────────────────────────────────────────────

    private boolean shouldTrigger() {
        if (mc.thePlayer.onGround && mc.thePlayer.motionY >= 0) return false;
        if (mc.thePlayer.isInWater() || mc.thePlayer.isInLava()) return false;
        if (mc.thePlayer.capabilities.isFlying || mc.thePlayer.capabilities.isCreativeMode) return false;
        if (countBlocks() == 0) return false;

        if (!onVoid.isToggled() && !onLethalFall.isToggled() && !onMoreThanX.isToggled()) return false;

        // Simulate fall
        LandingResult landing = simulateLanding();

        if (landing == null) {
            // No landing found → void
            return onVoid.isToggled();
        }

        if (onLethalFall.isToggled()) {
            double fallDist = mc.thePlayer.posY - landing.landY - 1;
            float maxFallDist = mc.thePlayer.capabilities.isCreativeMode ? 0 : getFallDamageTolerance();
            if (fallDist > maxFallDist) return true;
        }

        if (onMoreThanX.isToggled()) {
            double fallBlocks = mc.thePlayer.posY - landing.landY - 1;
            if (fallBlocks >= blocksThreshold.getInput()) return true;
        }

        return false;
    }

    /** Returns the maximum fall distance the player can survive. */
    private float getFallDamageTolerance() {
        // Base: 3 blocks safe (fall damage starts at 4+)
        float base = 3f;
        // Feather falling, jump boost etc. could add more — keep simple
        net.minecraft.potion.PotionEffect jump = mc.thePlayer.getActivePotionEffect(
                net.minecraft.potion.Potion.jump);
        if (jump != null) base += (jump.getAmplifier() + 1) * 2f;
        return base;
    }

    // ── simulation ────────────────────────────────────────────────────────────

    private static final class LandingResult {
        final double landY;
        LandingResult(double y) { this.landY = y; }
    }

    /**
     * Simulate the player falling with current velocity for up to SIM_TICKS
     * ticks to find where they land.
     */
    private LandingResult simulateLanding() {
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;
        double vx = mc.thePlayer.motionX;
        double vy = mc.thePlayer.motionY;
        double vz = mc.thePlayer.motionZ;

        for (int t = 0; t < SIM_TICKS; t++) {
            vy = (vy - 0.08) * 0.98;
            double nx = x + vx;
            double ny = y + vy;
            double nz = z + vz;
            vx *= 0.91;
            vz *= 0.91;

            if (ny < -64) return null; // void

            // Check floor at simulated Y
            int bx = MathHelper.floor_double(nx);
            int by = MathHelper.floor_double(ny - 0.001);
            int bz = MathHelper.floor_double(nz);
            BlockPos bp = new BlockPos(bx, by, bz);
            Block block = BlockUtils.getBlock(bp);
            if (block != null && !(block instanceof BlockAir) && !(block instanceof BlockLiquid)
                    && block.getCollisionBoundingBox(mc.theWorld, bp,
                            mc.theWorld.getBlockState(bp)) != null) {
                return new LandingResult(by + 1.0);
            }
            x = nx; y = ny; z = nz;
        }
        return null;
    }

    // ── target search ─────────────────────────────────────────────────────────

    /**
     * Search nearby blocks for the best placement: a solid block with an
     * exposed face that faces a replaceable cell, within reach, visible from
     * the player's eye position.
     */
    private void findTarget() {
        Vec3 eye = new Vec3(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ);
        double reachSq = REACH * REACH;

        int px = MathHelper.floor_double(mc.thePlayer.posX);
        int py = MathHelper.floor_double(mc.thePlayer.posY);
        int pz = MathHelper.floor_double(mc.thePlayer.posZ);

        // Collect candidates: [support block, face] pairs
        List<Candidate> candidates = new ArrayList<>();

        for (int dx = -SEARCH_R; dx <= SEARCH_R; dx++) {
            for (int dy = -SEARCH_R; dy <= SEARCH_R + 1; dy++) {
                for (int dz = -SEARCH_R; dz <= SEARCH_R; dz++) {
                    BlockPos support = new BlockPos(px + dx, py + dy, pz + dz);

                    // Support must be solid, non-interactable
                    Block supportBlock = BlockUtils.getBlock(support);
                    if (supportBlock instanceof BlockAir) continue;
                    if (BlockUtils.replaceable(support)) continue;
                    if (BlockUtils.isInteractable(supportBlock)) continue;

                    // Check each face
                    for (EnumFacing face : EnumFacing.VALUES) {
                        BlockPos placed = support.offset(face);

                        // Placed position must be replaceable and clear of entities
                        if (!BlockUtils.replaceable(placed)) continue;
                        if (!isSpaceClear(placed)) continue;

                        // Face must be visible from eye (eye on correct side)
                        if (!isFaceVisible(eye, support, face)) continue;

                        // Compute best hit point on this face
                        Vec3 hit = bestHitPoint(eye, support, face);
                        if (hit == null) continue;

                        // Must be within reach
                        double distSq = eye.squareDistanceTo(hit);
                        if (distSq > reachSq) continue;

                        // Verify actual ray-trace hits this face (no obstruction)
                        float[] rot = RotationUtils.getRotationsFromEye(eye,
                                hit.xCoord, hit.yCoord, hit.zCoord);
                        MovingObjectPosition mop = RotationUtils.rayTraceCustom(
                                REACH, rot[0], rot[1]);
                        if (mop == null
                                || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                                || !mop.getBlockPos().equals(support)
                                || mop.sideHit != face) continue;

                        // Score: prefer blocks directly below the player, then by distance
                        double score = distSq + Math.abs(placed.getX() - px) * 50
                                + Math.abs(placed.getZ() - pz) * 50
                                + Math.abs(placed.getY() - (py - 1)) * 20;
                        // Strongly prefer placing directly underfoot
                        if (placed.getX() == px && placed.getZ() == pz) score -= 10000;

                        candidates.add(new Candidate(support, face, hit, score));
                    }
                }
            }
        }

        if (candidates.isEmpty()) return;

        // Pick best
        candidates.sort((a, b) -> Double.compare(a.score, b.score));
        Candidate best = candidates.get(0);

        pendingSupportPos = best.support;
        pendingFace       = best.face;
        pendingHitVec     = best.hit;

        // Switch slot if needed
        if (!isValidBlockItem(mc.thePlayer.getHeldItem())) {
            int slot = findBlockSlot();
            if (slot == -1) {
                pendingSupportPos = null; pendingFace = null; pendingHitVec = null;
                return;
            }
            if (prevSlot == -1) prevSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = slot;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Whether the eye is on the correct side of the face. */
    private boolean isFaceVisible(Vec3 eye, BlockPos support, EnumFacing face) {
        AxisAlignedBB bb = BlockUtils.getBlock(support)
                .getCollisionBoundingBox(mc.theWorld, support, mc.theWorld.getBlockState(support));
        if (bb == null) bb = new AxisAlignedBB(support.getX(), support.getY(), support.getZ(),
                support.getX() + 1, support.getY() + 1, support.getZ() + 1);
        // getCollisionBoundingBox already returns world-space coords — do NOT re-offset
        switch (face) {
            case UP:    return eye.yCoord > bb.maxY;
            case DOWN:  return eye.yCoord < bb.minY;
            case NORTH: return eye.zCoord < bb.minZ;
            case SOUTH: return eye.zCoord > bb.maxZ;
            case WEST:  return eye.xCoord < bb.minX;
            case EAST:  return eye.xCoord > bb.maxX;
            default:    return false;
        }
    }

    /** Pick the face point that is closest to the current look angle. */
    private Vec3 bestHitPoint(Vec3 eye, BlockPos support, EnumFacing face) {
        double sx = support.getX(), sy = support.getY(), sz = support.getZ();
        // Sample a 3×3 grid on the face, pick the one with smallest rotation cost
        float baseYaw   = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];

        Vec3 best = null;
        float bestCost = Float.MAX_VALUE;

        for (double u = 0.15; u <= 0.85; u += 0.35) {
            for (double v = 0.15; v <= 0.85; v += 0.35) {
                Vec3 pt = facePoint(sx, sy, sz, face, u, v);
                if (eye.distanceTo(pt) > REACH + 0.1) continue;
                float[] rot = RotationUtils.getRotationsFromEye(eye,
                        pt.xCoord, pt.yCoord, pt.zCoord);
                float cost = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - baseYaw))
                        + Math.abs(rot[1] - basePitch);
                if (cost < bestCost) { bestCost = cost; best = pt; }
            }
        }
        return best;
    }

    /** Get a point on a block face with normalized (u, v) ∈ [0,1]×[0,1]. */
    private static Vec3 facePoint(double bx, double by, double bz,
                                   EnumFacing face, double u, double v) {
        switch (face) {
            case UP:    return new Vec3(bx + u, by + 1.0, bz + v);
            case DOWN:  return new Vec3(bx + u, by,       bz + v);
            case NORTH: return new Vec3(bx + u, by + v,   bz);
            case SOUTH: return new Vec3(bx + u, by + v,   bz + 1.0);
            case WEST:  return new Vec3(bx,     by + v,   bz + u);
            case EAST:  return new Vec3(bx + 1, by + v,   bz + u);
            default:    return new Vec3(bx + 0.5, by + 0.5, bz + 0.5);
        }
    }

    private boolean isSpaceClear(BlockPos pos) {
        AxisAlignedBB bb = new AxisAlignedBB(pos.getX(), pos.getY(), pos.getZ(),
                pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        List<Entity> entities = mc.theWorld.getEntitiesWithinAABBExcludingEntity(mc.thePlayer, bb);
        for (Entity e : entities) {
            if (e != null && e.canBeCollidedWith()
                    && e.getEntityBoundingBox().intersectsWith(bb)) return false;
        }
        return true;
    }

    private boolean isValidBlockItem(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) return false;
        if (!(stack.getItem() instanceof ItemBlock)) return false;
        Block b = ((ItemBlock) stack.getItem()).getBlock();
        return b != null && !BlockUtils.isInteractable(b)
                && b.getMaterial().isSolid()
                && Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private int findBlockSlot() {
        for (int i = 0; i < 9; i++) {
            if (isValidBlockItem(mc.thePlayer.inventory.getStackInSlot(i))) return i;
        }
        return -1;
    }

    private int countBlocks() {
        int total = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (isValidBlockItem(stack)) total += stack.stackSize;
        }
        return total;
    }

    // ── delay / cleanup ───────────────────────────────────────────────────────

    private void tickDelays(PreMotionEvent event) {
        if (resetDelayTicks > 0) {
            resetDelayTicks--;
            if (resetDelayTicks == 0 && hasSavedAngle
                    && resetAngle.isToggled() && !silentAim.isToggled()) {
                event.setRotations(savedYaw, savedPitch);
                hasSavedAngle = false;
            }
        }
        if (returnDelayTicks > 0) {
            returnDelayTicks--;
            if (returnDelayTicks == 0 && returnSlot.isToggled() && prevSlot != -1) {
                mc.thePlayer.inventory.currentItem = prevSlot;
                prevSlot = -1;
            }
        }
    }

    private void deactivate() {
        active = false;
        pendingSupportPos = null;
        pendingFace       = null;
        pendingHitVec     = null;
    }

    private void resetState() {
        active           = false;
        pendingSupportPos = null;
        pendingFace      = null;
        pendingHitVec    = null;
        hasSavedAngle    = false;
        resetDelayTicks  = 0;
        returnDelayTicks = 0;
        if (returnSlot != null && returnSlot.isToggled() && prevSlot != -1
                && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = prevSlot;
        }
        prevSlot = -1;
    }

    // ── inner ─────────────────────────────────────────────────────────────────

    private static final class Candidate {
        final BlockPos   support;
        final EnumFacing face;
        final Vec3       hit;
        final double     score;
        Candidate(BlockPos s, EnumFacing f, Vec3 h, double sc) {
            support = s; face = f; hit = h; score = sc;
        }
    }
}
