package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreMotionEvent;
import mindless.helper.RotationHelper;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.rotation.RotationSource;
import mindless.runtime.AccessorBridge;
import mindless.utility.BlockUtils;
import mindless.utility.OwnBedTracker;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legit mode. The digging is Silent's: START/STOP packets from the motion tick, tool swapped in and
 * put back. What differs is what gets dug and how it is aimed at.
 *
 * Timing is exactly what a vanilla dig takes, because that is what anti-cheats hold it to: STOP
 * goes out ceil(1 / best damage per tick) ticks after START (Grim's FastBreak prediction, which
 * takes the best per-tick damage seen during the dig), once the 1.8 server would accept it, and
 * the next START waits vanilla's hit delay after a STOP.
 *
 * Targets come from sight lines from the eye to the bed. Each line lists the blocks it passes
 * through in order, and the cheapest line by break time wins. That line is then the path: its
 * blocks are dug in order down to the bed, wherever the player moves meanwhile, so moving never
 * switches to a different set of blocks halfway. A new line is only traced when the path's next
 * block stays out of sight or reach for PATH_GIVE_UP_TICKS, or something unbreakable took its place.
 *
 * Aim is smoothed toward a point on the target that is visible from the eye, and a tick only
 * counts toward the break when the rotation actually sent that tick lands on the target.
 */
final class LegitBedBreaker {
    private enum State { IDLE, MINING, FINISH, COOLDOWN }

    private static final double MAX_REACH = 4.5;
    /** A 1.8 server accepts STOP once relative hardness times ticks since START reaches this. */
    private static final float SERVER_BREAK_RATIO = 0.7F;
    private static final int MISALIGNED_ABORT_TICKS = 4;
    private static final int VANILLA_HIT_DELAY_TICKS = 5;
    /** How long the path's next block may stay out of sight or reach before a new path is traced. */
    private static final int PATH_GIVE_UP_TICKS = 20;
    private static final int FINISH_TIMEOUT_TICKS = 20;
    private static final int MAX_BLOCK_TICKS = 600;
    private static final long BED_COOLDOWN_MS = 500L;
    private static final double[] AIM_FRACTIONS = {0.2, 0.5, 0.8};
    private static final double[] SHORT_FRACTIONS = {0.15, 0.5, 0.85};
    private static final double[] LONG_FRACTIONS = {0.1, 0.3, 0.5, 0.7, 0.9};
    private static final double[] BED_HEIGHT_FRACTIONS = {0.3, 0.7};
    private static final EnumFacing[] BED_FACES = {EnumFacing.UP, EnumFacing.NORTH, EnumFacing.SOUTH,
            EnumFacing.WEST, EnumFacing.EAST};

    private final BedAura owner;
    private final SliderSetting range;
    private final SliderSetting breakDelay;
    private final SliderSetting fov;
    private final SliderSetting aimSpeed;
    private final SliderSetting moveFix;
    private final ButtonSetting toolCheck;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting autoTool;
    private final ButtonSetting switchBack;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting spoofItem;

    private State state = State.IDLE;
    private BlockPos target;
    private boolean targetIsBed;
    private Vec3 aimPoint;
    private Vec3 routeAim;
    private EnumFacing face = EnumFacing.UP;
    private boolean digging;
    private int breakTicks;
    private float maxRelative;
    private int misalignedTicks;
    private int lostSightTicks;
    private int finishTicks;
    private int delayTicks;
    private long cooldownUntil;
    private int toolSlot = -1;
    private int intendedSlot = -1;
    private ItemStack miningItem;
    private BlockPos routeFoot;
    private int routeBroken;
    private int routeRemaining;
    /** The committed sight line: blocks to dig in order, the bed last. */
    private List<BlockPos> routePath;
    /** The point on the bed the committed line runs to. */
    private Vec3 routeEnd;
    /** Last block the server sent back unchanged (network thread), read while waiting on a STOP. */
    private volatile BlockPos serverRestored;

    LegitBedBreaker(BedAura owner, SliderSetting range, SliderSetting breakDelay,
                    SliderSetting fov, SliderSetting aimSpeed, SliderSetting moveFix,
                    ButtonSetting toolCheck, ButtonSetting whitelistOwnBed, ButtonSetting autoTool,
                    ButtonSetting switchBack, ButtonSetting overrideSwapBack, ButtonSetting spoofItem) {
        this.owner = owner;
        this.range = range;
        this.breakDelay = breakDelay;
        this.fov = fov;
        this.aimSpeed = aimSpeed;
        this.moveFix = moveFix;
        this.toolCheck = toolCheck;
        this.whitelistOwnBed = whitelistOwnBed;
        this.autoTool = autoTool;
        this.switchBack = switchBack;
        this.overrideSwapBack = overrideSwapBack;
        this.spoofItem = spoofItem;
    }

    /** Runs from the rotation event: keeps the route current, then aims along it. */
    void requestRotation(ClientRotationEvent event) {
        if (!ready()) {
            if (target != null || intendedSlot >= 0) cleanup();
            return;
        }
        if (delayTicks > 0) delayTicks--;
        if (state == State.COOLDOWN) {
            if (System.currentTimeMillis() < cooldownUntil) return;
            state = State.IDLE;
        }
        if (state == State.FINISH) {
            if (mc().theWorld.isAirBlock(target)) {
                onTargetRemoved();
                if (state == State.COOLDOWN) return;
            }
            else if (target.equals(serverRestored) || ++finishTicks >= FINISH_TIMEOUT_TICKS) {
                // The server kept the block (sent it back, or never confirmed): dig it again right
                // away, still after vanilla's hit delay from the STOP.
                int sinceStop = finishTicks;
                dropTarget();
                delayTicks = Math.max(0, breakDelayTicks() - sinceStop);
            }
        }
        else if (target != null) {
            if (mc().theWorld.isAirBlock(target)) {
                onTargetRemoved();
                if (state == State.COOLDOWN) return;
            }
            else if (!targetStillValid()) {
                dropTarget();
            }
        }

        Vec3 eye = predictedEye();
        if (target == null && !followPath() && !adopt(plan(eye))) {
            endSession();
            return;
        }

        Vec3 visible = visibleAimPoint(eye);
        if (visible == null) {
            if (++lostSightTicks >= PATH_GIVE_UP_TICKS) {
                routePath = null;
                routeEnd = null;
                dropTarget();
                return;
            }
            // Keep turning toward the path's block while it is hidden or out of reach.
            AxisAlignedBB box = BlockUtils.getBlockSelectionBox(target);
            if (aimPoint == null && box != null) {
                aimPoint = new Vec3((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
            }
        }
        else {
            lostSightTicks = 0;
            aimPoint = visible;
        }
        if (aimPoint == null) return;

        float baseYaw = event.getBaseYaw() != null ? event.getBaseYaw() : RotationUtils.serverRotations[0];
        float basePitch = event.getBasePitch() != null ? event.getBasePitch() : RotationUtils.serverRotations[1];
        Vec3 shift = eye.subtract(mc().thePlayer.getPositionEyes(1.0F));
        float[] desired = RotationUtils.getRotationsToPoint(aimPoint.xCoord - shift.xCoord,
                aimPoint.yCoord - shift.yCoord, aimPoint.zCoord - shift.zCoord, baseYaw, basePitch);
        float[] smoothed = RotationUtils.smoothRotationHumanized(baseYaw, basePitch, desired[0], desired[1],
                (int) aimSpeed.getInput(), 15.0F);
        if (event.requestRotation(RotationSource.BED_AURA, smoothed[0], RotationUtils.clampPitch(smoothed[1]))
                && (int) moveFix.getInput() != 0) {
            RotationHelper.get().forceMovementFix = true;
        }
    }

    /** Runs once the tick's rotation is final, just before it is sent. */
    void actionTick(PreMotionEvent event) {
        if (target == null || !ready() || (state != State.IDLE && state != State.MINING)) return;
        if (delayTicks > 0 || mc().theWorld.isAirBlock(target)) return;

        MovingObjectPosition hit = event.getYawSource() == RotationSource.BED_AURA
                && event.getPitchSource() == RotationSource.BED_AURA
                ? RotationUtils.rayTraceCustom(reach(), event.getYaw(), event.getPitch()) : null;
        if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !target.equals(hit.getBlockPos())) {
            // Smoothing and rotation noise can clip an edge for a tick. Hold the progress through
            // that, and only give the dig up when the aim has really left the block.
            if (digging && ++misalignedTicks >= MISALIGNED_ABORT_TICKS) abortDig();
            return;
        }
        misalignedTicks = 0;
        face = hit.sideHit;
        Block block = mc().theWorld.getBlockState(target).getBlock();

        if (!digging) {
            if (mc().thePlayer.isUsingItem()) return;
            if (mc().playerController != null) mc().playerController.resetBlockRemoving();
            toolSlot = autoTool.isToggled() ? bestTool(block) : mc().thePlayer.inventory.currentItem;
            equipTool();
            if (!synchronizeHeldItem()) return;
            miningItem = ItemStack.copyItemStack(mc().thePlayer.getHeldItem());
            float relative = currentRelativeHardness(block);
            send(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK);
            mc().thePlayer.swingItem();
            breakTicks = 0;
            maxRelative = relative;
            if (relative >= 1.0F) {
                // Breaks on START alone, the way the vanilla client does it: no STOP follows.
                finishTicks = 0;
                state = State.FINISH;
                return;
            }
            digging = true;
            state = State.MINING;
            return;
        }

        if (!synchronizeHeldItem()) return;
        if (!sameMiningItem() || toolSlot != mc().thePlayer.inventory.currentItem) {
            abortDig();
            return;
        }
        breakTicks++;
        float relative = currentRelativeHardness(block);
        maxRelative = Math.max(maxRelative, relative);
        mc().thePlayer.swingItem();
        if (mc().effectRenderer != null) mc().effectRenderer.addBlockHitEffects(target, face);
        mc().theWorld.sendBlockBreakProgress(mc().thePlayer.getEntityId(), target, (int) (progress() * 10.0F) - 1);
        // Both have to agree. Grim predicts the dig from the best per-tick damage seen since START,
        // and one tick short of that is already a FastBreak violation; the 1.8 server judges the
        // whole dig by the state at STOP, so in the air it waits for the landing.
        if (breakTicks >= requiredTicks() && relative * (breakTicks + 1) >= SERVER_BREAK_RATIO) {
            serverRestored = null;
            send(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK);
            mc().thePlayer.swingItem();
            digging = false;
            finishTicks = 0;
            state = State.FINISH;
        }
    }

    void cleanup() {
        endSession();
        state = State.IDLE;
        cooldownUntil = 0L;
    }

    boolean controlsInteractions() {
        return target != null;
    }

    boolean isDigging() {
        return digging && target != null;
    }

    BlockPos target() {
        return target;
    }

    float progress() {
        if (target == null) return 0.0F;
        if (state == State.FINISH) return 1.0F;
        return Math.max(0.0F, Math.min(1.0F, breakTicks / (float) requiredTicks()));
    }

    /** Called with every single-block update from the server. */
    void onServerBlockChange(BlockPos pos, IBlockState state) {
        if (state.getBlock().getMaterial() != Material.air) serverRestored = pos;
    }

    float totalProgress() {
        float current = progress();
        if (routeRemaining <= 0) return current;
        return Math.max(0.0F, Math.min(1.0F, (routeBroken + current) / (float) (routeBroken + routeRemaining)));
    }

    boolean isSpoofingHeldItem() {
        return spoofItem.isToggled() && intendedSlot >= 0 && Utils.nullCheck()
                && mc().thePlayer.inventory.currentItem != intendedSlot;
    }

    ItemStack originalVisualItem() {
        return intendedSlot >= 0 && Utils.nullCheck() ? mc().thePlayer.inventory.getStackInSlot(intendedSlot) : null;
    }

    void recordIntendedSlot(int slot) {
        if (overrideSwapBack.isToggled() && intendedSlot >= 0 && slot >= 0 && slot < 9) intendedSlot = slot;
    }

    void scrollIntendedSlot(int delta) {
        if (intendedSlot >= 0) recordIntendedSlot(Math.floorMod(intendedSlot - Integer.compare(delta, 0), 9));
    }

    private boolean adopt(Route route) {
        if (route == null) return false;
        if (target != null && !route.target.equals(target)) dropTarget();
        if (routeFoot == null || !routeFoot.equals(route.foot)) {
            routeFoot = route.foot;
            routeBroken = 0;
        }
        target = route.target;
        targetIsBed = route.blocks == 0;
        // Picked fresh from the inset points on the block, not the sight line's hit, which can sit
        // right on an edge where a tick of movement puts the neighbour under the crosshair.
        aimPoint = null;
        routeAim = route.aim;
        routePath = route.path;
        routeEnd = route.end;
        routeRemaining = route.blocks + 1;
        lostSightTicks = 0;
        return true;
    }

    /** Targets the committed path's next standing block, wherever the player has moved since. */
    private boolean followPath() {
        if (routePath == null) return false;
        for (int i = 0; i < routePath.size(); i++) {
            BlockPos cell = routePath.get(i);
            Block block = mc().theWorld.getBlockState(cell).getBlock();
            if (block.getMaterial() == Material.air) continue;
            boolean bed = i == routePath.size() - 1;
            if (bed != (block instanceof BlockBed) || block.getBlockHardness(mc().theWorld, cell) < 0.0F
                    || toolCheck.isToggled() && !SilentBedBreaker.hasRequiredTool(block)) {
                break;
            }
            target = cell;
            targetIsBed = bed;
            aimPoint = null;
            routeAim = null;
            routeRemaining = routePath.size() - i;
            lostSightTicks = 0;
            return true;
        }
        routePath = null;
        return false;
    }

    private void onTargetRemoved() {
        boolean bed = targetIsBed;
        int sinceStop = state == State.FINISH ? finishTicks : 0;
        dropTarget();
        if (bed) {
            endSession();
            cooldownUntil = System.currentTimeMillis() + BED_COOLDOWN_MS;
            state = State.COOLDOWN;
            return;
        }
        routeBroken++;
        // Counted from STOP rather than from the server confirming it, so ping is not added on top.
        delayTicks = Math.max(0, breakDelayTicks() - sinceStop);
    }

    private boolean targetStillValid() {
        Block block = mc().theWorld.getBlockState(target).getBlock();
        if (block.getBlockHardness(mc().theWorld, target) < 0.0F) return false;
        if (toolCheck.isToggled() && !SilentBedBreaker.hasRequiredTool(block)) return false;
        // Out of reach is not a reason to leave the path: that is the out-of-sight wait's job.
        return BlockUtils.getBlockSelectionBox(target) != null;
    }

    private void abortDig() {
        if (digging && target != null && Utils.nullCheck() && !mc().theWorld.isAirBlock(target)) {
            send(C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK);
        }
        if (target != null && Utils.nullCheck()) {
            mc().theWorld.sendBlockBreakProgress(mc().thePlayer.getEntityId(), target, -1);
        }
        digging = false;
        breakTicks = 0;
        maxRelative = 0.0F;
        misalignedTicks = 0;
        miningItem = null;
        if (state == State.MINING) state = State.IDLE;
    }

    private void dropTarget() {
        abortDig();
        target = null;
        targetIsBed = false;
        aimPoint = null;
        routeAim = null;
        face = EnumFacing.UP;
        finishTicks = 0;
        lostSightTicks = 0;
        if (state == State.FINISH || state == State.MINING) state = State.IDLE;
    }

    private void endSession() {
        dropTarget();
        restoreSlot();
        routePath = null;
        routeEnd = null;
        routeFoot = null;
        routeBroken = 0;
        routeRemaining = 0;
        delayTicks = 0;
    }

    private Route plan(Vec3 eye) {
        double reach = reach();
        int radius = (int) Math.ceil(reach) + 1;
        BlockPos origin = new BlockPos(eye);
        Set<BlockPos> seen = new HashSet<BlockPos>();
        Map<Block, Integer> ticksByBlock = new HashMap<Block, Integer>();
        Route best = null;
        Route committed = null;
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos[] pair = OwnBedTracker.footHeadPair(origin.add(x, y, z));
                    if (pair == null || !seen.add(pair[0])) continue;
                    if (whitelistOwnBed.isToggled() && OwnBedTracker.isOwnBed(pair)) continue;
                    boolean routeBed = pair[0].equals(routeFoot);
                    AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pair[0]).union(BlockUtils.getBlockSelectionBox(pair[1]));
                    if (!routeBed && !inFov(box)) continue;
                    Route route = bestRoute(pair, box, eye, reach, ticksByBlock);
                    if (route == null) continue;
                    if (routeBed) committed = route;
                    if (best == null || route.betterThan(best)) best = route;
                }
            }
        }
        // Stay on the bed already being dug while it can still be reached, even if another is cheaper.
        return committed != null ? committed : best;
    }

    private Route bestRoute(BlockPos[] pair, AxisAlignedBB box, Vec3 eye, double reach,
                            Map<Block, Integer> ticksByBlock) {
        Route best = null;
        for (EnumFacing side : BED_FACES) {
            if (!facesEye(side, box, eye)) continue;
            double[] first;
            double[] second;
            if (side == EnumFacing.UP) {
                first = box.maxX - box.minX > 1.5 ? LONG_FRACTIONS : SHORT_FRACTIONS;
                second = box.maxZ - box.minZ > 1.5 ? LONG_FRACTIONS : SHORT_FRACTIONS;
            }
            else {
                double width = side.getAxis() == EnumFacing.Axis.Z ? box.maxX - box.minX : box.maxZ - box.minZ;
                first = width > 1.5 ? LONG_FRACTIONS : SHORT_FRACTIONS;
                second = BED_HEIGHT_FRACTIONS;
            }
            for (double a : first) {
                for (double b : second) {
                    Route route = trace(eye, facePoint(box, side, a, b), pair, reach, ticksByBlock);
                    if (route != null && (best == null || route.betterThan(best))) best = route;
                }
            }
        }
        return best;
    }

    /** Walks the sight line from the eye to a point on the bed, costing each block it must break. */
    private Route trace(Vec3 eye, Vec3 point, BlockPos[] pair, double reach, Map<Block, Integer> ticksByBlock) {
        Vec3 direction = point.subtract(eye);
        double length = direction.lengthVector();
        if (length < 1.0E-4) return null;
        Vec3 end = point.addVector(direction.xCoord / length * 0.05, direction.yCoord / length * 0.05,
                direction.zCoord / length * 0.05);
        double reachSq = reach * reach;
        BlockPos first = null;
        Vec3 firstHit = null;
        List<BlockPos> path = new ArrayList<BlockPos>();
        int blocks = 0;
        int cost = 0;
        for (BlockPos cell : cells(eye, end)) {
            IBlockState state = mc().theWorld.getBlockState(cell);
            Block block = state.getBlock();
            if (!block.canCollideCheck(state, false)) continue;
            MovingObjectPosition hit = block.collisionRayTrace(mc().theWorld, cell, eye, end);
            if (hit == null || hit.hitVec == null || hit.sideHit == null) continue;
            if (eye.squareDistanceTo(hit.hitVec) > reachSq) return null;
            boolean bed = cell.equals(pair[0]) || cell.equals(pair[1]);
            // Never dig through a different bed to reach this one: it may be the player's own.
            if (!bed && block instanceof BlockBed) return null;
            int ticks = breakTicks(block, cell, ticksByBlock);
            if (ticks < 0) return null;
            if (first == null) {
                first = cell;
                firstHit = hit.hitVec;
            }
            cost += ticks;
            path.add(cell);
            if (bed) {
                if (hit.sideHit == EnumFacing.DOWN) return null;
                return new Route(pair[0], first, firstHit, path, end, blocks, cost, angleTo(firstHit));
            }
            blocks++;
            cost += breakDelayTicks();
        }
        return null;
    }

    private int breakTicks(Block block, BlockPos pos, Map<Block, Integer> ticksByBlock) {
        Integer cached = ticksByBlock.get(block);
        if (cached != null) return cached;
        int ticks;
        if (block.getBlockHardness(mc().theWorld, pos) < 0.0F
                || toolCheck.isToggled() && !SilentBedBreaker.hasRequiredTool(block)) {
            ticks = -1;
        }
        else {
            float relative = plannedRelativeHardness(block);
            if (relative <= 0.0F) ticks = -1;
            else if (relative >= 1.0F) ticks = 1;
            else {
                ticks = 1 + (int) Math.ceil(1.0D / relative);
                if (ticks > MAX_BLOCK_TICKS) ticks = -1;
            }
        }
        ticksByBlock.put(block, ticks);
        return ticks;
    }

    /** Blocks entered by the segment, in the order it enters them. */
    private static List<BlockPos> cells(Vec3 from, Vec3 to) {
        List<BlockPos> out = new ArrayList<BlockPos>();
        int x = MathHelper.floor_double(from.xCoord);
        int y = MathHelper.floor_double(from.yCoord);
        int z = MathHelper.floor_double(from.zCoord);
        int endX = MathHelper.floor_double(to.xCoord);
        int endY = MathHelper.floor_double(to.yCoord);
        int endZ = MathHelper.floor_double(to.zCoord);
        double dx = to.xCoord - from.xCoord;
        double dy = to.yCoord - from.yCoord;
        double dz = to.zCoord - from.zCoord;
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
        double deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
        double maxX = stepX > 0 ? (x + 1 - from.xCoord) / dx : stepX < 0 ? (from.xCoord - x) / -dx : Double.POSITIVE_INFINITY;
        double maxY = stepY > 0 ? (y + 1 - from.yCoord) / dy : stepY < 0 ? (from.yCoord - y) / -dy : Double.POSITIVE_INFINITY;
        double maxZ = stepZ > 0 ? (z + 1 - from.zCoord) / dz : stepZ < 0 ? (from.zCoord - z) / -dz : Double.POSITIVE_INFINITY;
        out.add(new BlockPos(x, y, z));
        for (int guard = 0; guard < 64 && (x != endX || y != endY || z != endZ); guard++) {
            if (maxX < maxY && maxX < maxZ) {
                if (maxX > 1.0) break;
                x += stepX;
                maxX += deltaX;
            }
            else if (maxY < maxZ) {
                if (maxY > 1.0) break;
                y += stepY;
                maxY += deltaY;
            }
            else {
                if (maxZ > 1.0) break;
                z += stepZ;
                maxZ += deltaZ;
            }
            out.add(new BlockPos(x, y, z));
        }
        return out;
    }

    /** A point on the target the eye can see, preferring the one already aimed at. */
    private Vec3 visibleAimPoint(Vec3 eye) {
        double reachSq = reach() * reach();
        if (aimPoint != null && seesTarget(eye, aimPoint, reachSq)) return aimPoint;
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(target);
        if (box == null) return null;
        Vec3 best = null;
        float bestAngle = Float.MAX_VALUE;
        for (EnumFacing side : EnumFacing.values()) {
            if (!facesEye(side, box, eye) || targetIsBed && side == EnumFacing.DOWN) continue;
            for (double a : AIM_FRACTIONS) {
                for (double b : AIM_FRACTIONS) {
                    Vec3 point = facePoint(box, side, a, b);
                    if (!seesTarget(eye, point, reachSq)) continue;
                    float angle = angleTo(point);
                    if (angle < bestAngle) {
                        bestAngle = angle;
                        best = point;
                    }
                }
            }
        }
        if (best != null) return best;
        // A target seen only through a narrow gap may show no inset point at all; the sight line
        // that found it still reaches it, and so does the path's line from where the eye is now,
        // which runs through the hole already dug.
        if (routeAim != null && seesTarget(eye, routeAim, reachSq)) return routeAim;
        if (routeEnd == null) return null;
        IBlockState state = mc().theWorld.getBlockState(target);
        MovingObjectPosition hit = state.getBlock().collisionRayTrace(mc().theWorld, target, eye, routeEnd);
        return hit != null && hit.hitVec != null && seesTarget(eye, hit.hitVec, reachSq) ? hit.hitVec : null;
    }

    private boolean seesTarget(Vec3 eye, Vec3 point, double reachSq) {
        Vec3 direction = point.subtract(eye);
        double length = direction.lengthVector();
        if (length < 1.0E-4) return false;
        Vec3 end = point.addVector(direction.xCoord / length * 0.05, direction.yCoord / length * 0.05,
                direction.zCoord / length * 0.05);
        MovingObjectPosition hit = mc().theWorld.rayTraceBlocks(eye, end, false, false, true);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && target.equals(hit.getBlockPos()) && eye.squareDistanceTo(hit.hitVec) <= reachSq;
    }

    private static boolean facesEye(EnumFacing side, AxisAlignedBB box, Vec3 eye) {
        switch (side) {
            case UP: return eye.yCoord > box.maxY;
            case DOWN: return eye.yCoord < box.minY;
            case NORTH: return eye.zCoord < box.minZ;
            case SOUTH: return eye.zCoord > box.maxZ;
            case WEST: return eye.xCoord < box.minX;
            default: return eye.xCoord > box.maxX;
        }
    }

    /** a runs along the face's width, b along its depth (top and bottom) or height (sides). */
    private static Vec3 facePoint(AxisAlignedBB box, EnumFacing side, double a, double b) {
        double x = box.minX + (box.maxX - box.minX) * a;
        double y = box.minY + (box.maxY - box.minY) * b;
        double z = box.minZ + (box.maxZ - box.minZ) * b;
        switch (side) {
            case UP: return new Vec3(x, box.maxY, z);
            case DOWN: return new Vec3(x, box.minY, z);
            case NORTH: return new Vec3(x, y, box.minZ);
            case SOUTH: return new Vec3(x, y, box.maxZ);
            case WEST: return new Vec3(box.minX, y, box.minZ + (box.maxZ - box.minZ) * a);
            default: return new Vec3(box.maxX, y, box.minZ + (box.maxZ - box.minZ) * a);
        }
    }

    private float angleTo(Vec3 point) {
        float yaw = RotationUtils.serverRotations[0];
        float pitch = RotationUtils.serverRotations[1];
        float[] rotation = RotationUtils.getRotationsToPoint(point.xCoord, point.yCoord, point.zCoord, yaw, pitch);
        return Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - yaw)) + Math.abs(rotation[1] - pitch);
    }

    private boolean inFov(AxisAlignedBB box) {
        float fovDegrees = (float) fov.getInput();
        if (fovDegrees >= 360.0F) return true;
        Vec3 eye = mc().thePlayer.getPositionEyes(1.0F);
        Vec3 look = mc().thePlayer.getLook(1.0F);
        Vec3 to = new Vec3((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5)
                .subtract(eye);
        double length = to.lengthVector();
        if (length < 1.0E-6) return true;
        double dot = (look.xCoord * to.xCoord + look.yCoord * to.yCoord + look.zCoord * to.zCoord) / length;
        return Math.toDegrees(Math.acos(MathHelper.clamp_double(dot, -1.0, 1.0))) <= fovDegrees * 0.5;
    }

    /** Where the eye will be once this tick's movement is applied, which is where the aim is judged. */
    private Vec3 predictedEye() {
        Vec3 eye = mc().thePlayer.getPositionEyes(1.0F);
        return eye.addVector(mc().thePlayer.motionX, mc().thePlayer.onGround ? 0.0 : mc().thePlayer.motionY,
                mc().thePlayer.motionZ);
    }

    private float plannedRelativeHardness(Block block) {
        if (!autoTool.isToggled()) {
            return BlockUtils.getBlockHardness(block, mc().thePlayer.getHeldItem(), false, true);
        }
        float best = BlockUtils.getBlockHardness(block, null, false, true);
        for (int slot = 0; slot < 9; slot++) {
            best = Math.max(best, BlockUtils.getBlockHardness(block, mc().thePlayer.inventory.getStackInSlot(slot), false, true));
        }
        return best;
    }

    private float currentRelativeHardness(Block block) {
        return BlockUtils.getBlockHardness(block, mc().thePlayer.getHeldItem(), false, false);
    }

    private int bestTool(Block block) {
        int slot = Utils.getTool(block);
        return slot >= 0 ? slot : mc().thePlayer.inventory.currentItem;
    }

    private void equipTool() {
        if (toolSlot == mc().thePlayer.inventory.currentItem) return;
        if (intendedSlot == -1) intendedSlot = mc().thePlayer.inventory.currentItem;
        mc().thePlayer.inventory.currentItem = toolSlot;
        if (mc().playerController != null) AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
    }

    private void restoreSlot() {
        if (intendedSlot < 0) return;
        if (switchBack.isToggled() && Utils.nullCheck() && mc().thePlayer.inventory.currentItem == toolSlot) {
            mc().thePlayer.inventory.currentItem = intendedSlot;
            if (mc().playerController != null) AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
        }
        intendedSlot = -1;
        toolSlot = -1;
    }

    private boolean synchronizeHeldItem() {
        if (mc().playerController == null) return false;
        AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
        return AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc().playerController)
                == mc().thePlayer.inventory.currentItem;
    }

    private boolean sameMiningItem() {
        ItemStack held = mc().thePlayer.getHeldItem();
        return held == null ? miningItem == null : miningItem != null
                && held.getItem() == miningItem.getItem()
                && ItemStack.areItemStackTagsEqual(held, miningItem)
                && (held.isItemStackDamageable() || held.getMetadata() == miningItem.getMetadata());
    }

    private void send(C07PacketPlayerDigging.Action action) {
        if (Utils.nullCheck() && target != null) {
            EnumFacing packetFace = action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK ? EnumFacing.DOWN : face;
            mc().thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(action, target, packetFace));
        }
    }

    private boolean ready() {
        return owner.isEnabled() && Utils.nullCheck() && mc().currentScreen == null
                && mc().thePlayer.capabilities.allowEdit && !mc().thePlayer.capabilities.isCreativeMode
                && !mc().thePlayer.isSpectator() && !mc().thePlayer.isDead && !owner.shouldYieldToKillAura();
    }

    private double reach() {
        return Math.min(MAX_REACH, range.getInput());
    }

    /** Vanilla's break time for the best per-tick damage seen this dig (Grim FastBreak's prediction). */
    private int requiredTicks() {
        return maxRelative > 0.0F ? (int) Math.ceil(1.0D / maxRelative) : Integer.MAX_VALUE;
    }

    private int breakDelayTicks() {
        // Never under vanilla's 5-tick blockHitDelay: Grim's FastBreak flags a START that follows a break sooner.
        return Math.max(VANILLA_HIT_DELAY_TICKS, (int) Math.round(breakDelay.getInput() / 50.0));
    }

    private static Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    private static final class Route {
        final BlockPos foot;
        final BlockPos target;
        final Vec3 aim;
        /** Blocks to dig in order, the bed last. */
        final List<BlockPos> path;
        final Vec3 end;
        final int blocks;
        final int cost;
        final float angle;

        Route(BlockPos foot, BlockPos target, Vec3 aim, List<BlockPos> path, Vec3 end, int blocks, int cost, float angle) {
            this.foot = foot;
            this.target = target;
            this.aim = aim;
            this.path = path;
            this.end = end;
            this.blocks = blocks;
            this.cost = cost;
            this.angle = angle;
        }

        /** Cheapest break time first; among equals, fewer blocks, then the one nearest the current aim. */
        boolean betterThan(Route other) {
            if (cost != other.cost) return cost < other.cost;
            if (blocks != other.blocks) return blocks < other.blocks;
            return angle < other.angle;
        }
    }
}
