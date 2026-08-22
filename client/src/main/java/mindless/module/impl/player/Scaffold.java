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
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
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

    private SliderSetting modeSetting;
    private SliderSetting switchModeSetting;
    private ButtonSetting sameYSetting;
    private ButtonSetting autoJumpSetting;
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

    public int blocksPlaced = 0;

    public Scaffold() {
        super("Scaffold", category.player);
        this.closetModule = true;

        this.registerSetting(modeSetting = new SliderSetting("Mode", 0, MODE_OPTIONS));
        this.registerSetting(switchModeSetting = new SliderSetting("Switch Mode", 0, SWITCH_MODE_OPTIONS));
        this.registerSetting(sameYSetting = new ButtonSetting("Same Y", true));
        this.registerSetting(autoJumpSetting = new ButtonSetting("Auto Jump", true));
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
        if (sameYPos != null
                && Math.abs(MathHelper.floor_double(mc.thePlayer.posY) - sameYPos) > 3) {
            sameYPos = null;
        }
        if (sameYPos == null && mc.thePlayer.onGround) {
            sameYPos = MathHelper.floor_double(mc.thePlayer.posY);
        }
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

        // Telly burst condition: place when falling, on ground, or near peak
        boolean canPlaceTelly = !telly || mc.thePlayer.onGround || mc.thePlayer.fallDistance > 0.3f || mc.thePlayer.motionY < 0.0;

        if (!canPlaceTelly) {
            blockCache = null;
            rotation = null;
            placeQueued = false;
            return;
        }

        // 2. Search placement block & calculation
        boolean dataFound = updateData();

        if (dataFound && blockCache != null && rotation != null) {
            float targetYaw = rotation.rotation.x;
            float targetPitch = rotation.rotation.y;

            // Smooth rotation with Scaffold.jar speed scaling (35° straight, 70° diagonal, 80° telly)
            float fromYaw = Float.isNaN(rotCurrentYaw) ? RotationUtils.serverRotations[0] : rotCurrentYaw;
            float fromPitch = Float.isNaN(rotCurrentPitch) ? RotationUtils.serverRotations[1] : rotCurrentPitch;
            float[] smoothed = getRotationsSmoothed(fromYaw, fromPitch, targetYaw, targetPitch, telly);

            // GCD quantize angle (sensitivity patch)
            float finalYaw = quantizeAngle(smoothed[0]);
            float finalPitch = quantizeAngle(smoothed[1]);

            float[] finalRots = RotationUtils.fixRotation(finalYaw, finalPitch, RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

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
            // Keep rotation locked smoothly facing backward even if blockCache is null for 1 tick while moving
            if (!Float.isNaN(rotCurrentYaw)) {
                e.setYaw(rotCurrentYaw);
                e.setPitch(rotCurrentPitch);
                RotationHelper.get().setRotations(rotCurrentYaw, rotCurrentPitch);
            }
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

        if (placeBlockPos != null && placeSide != null && placeHitVec != null) {
            // A tick has passed since the target was picked; something else may have filled it.
            if (!BlockUtils.replaceable(placeBlockPos.offset(placeSide))) return;

            float fX, fY, fZ;
            if (precisionHitVecSetting.isToggled()) {
                fX = MathHelper.clamp_float((float) (placeHitVec.xCoord - placeBlockPos.getX()), 0.001f, 0.999f);
                fY = MathHelper.clamp_float((float) (placeHitVec.yCoord - placeBlockPos.getY()), 0.001f, 0.999f);
                fZ = MathHelper.clamp_float((float) (placeHitVec.zCoord - placeBlockPos.getZ()), 0.001f, 0.999f);
            } else {
                fX = (float) (placeHitVec.xCoord - placeBlockPos.getX());
                fY = (float) (placeHitVec.yCoord - placeBlockPos.getY());
                fZ = (float) (placeHitVec.zCoord - placeBlockPos.getZ());
            }

            mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(
                    placeBlockPos,
                    placeSide.getIndex(),
                    held,
                    fX, fY, fZ
            ));
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

        float nextYaw = currentYaw + MathHelper.clamp_float(deltaYaw, -speed, speed);
        float nextPitch = currentPitch + MathHelper.clamp_float(deltaPitch, -speed, speed);

        return new float[]{nextYaw, MathHelper.clamp_float(nextPitch, -89.0f, 89.0f)};
    }

    private boolean isMovingDiagonal() {
        return mc.thePlayer.moveForward != 0.0f && mc.thePlayer.moveStrafing != 0.0f;
    }

    private boolean isMoving() {
        return mc.thePlayer.moveForward != 0.0f || mc.thePlayer.moveStrafing != 0.0f;
    }

    private boolean updateData() {
        blockCache = null;
        rotation = null;

        Vec3 eyePos = getEyePos();
        int targetY = targetY();

        if (accept(getBlockData(new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                targetY,
                MathHelper.floor_double(mc.thePlayer.posZ)), eyePos))) {
            return true;
        }

        // Any corner of the hitbox over a gap counts, not just the block the middle of you is
        // above. Waiting for the centre to cross means the block only goes down once you are
        // already dropping off the edge, which is most of why bridging felt like walk, stop,
        // place, walk.
        for (double[] corner : FOOTPRINT_CORNERS) {
            if (accept(getBlockData(new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX + corner[0]),
                    targetY,
                    MathHelper.floor_double(mc.thePlayer.posZ + corner[1])), eyePos))) {
                return true;
            }
        }

        if (!movementIntelSetting.isToggled()) return false;

        // Where you are about to be. This used to step one tick of motion at a time up to three,
        // which at sprint speed is well under a block, so the next block along was only ever
        // found at the last possible moment.
        double px = mc.thePlayer.motionX;
        double pz = mc.thePlayer.motionZ;
        if (px * px + pz * pz <= 1.0E-6) {
            // Standing still against a wall, or the first tick off a ledge: aim where the keys
            // point instead, or there is no direction to project along at all.
            float forward = mc.thePlayer.moveForward;
            float strafe = mc.thePlayer.moveStrafing;
            double length = Math.sqrt(forward * forward + strafe * strafe);
            if (length <= 0.01) return false;
            double yaw = Math.toRadians(mc.thePlayer.rotationYaw);
            px = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) / length * 0.75;
            pz = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) / length * 0.75;
        }

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
        float direction = mc.thePlayer.rotationYaw;
        float forward = 1.0F;

        if (mc.thePlayer.moveForward < 0.0F) {
            direction += 180.0F;
            forward = -0.5F;
        } else if (mc.thePlayer.moveForward > 0.0F) {
            forward = 0.5F;
        }

        if (mc.thePlayer.moveStrafing > 0.0F) {
            direction -= 90.0F * forward;
        } else if (mc.thePlayer.moveStrafing < 0.0F) {
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
