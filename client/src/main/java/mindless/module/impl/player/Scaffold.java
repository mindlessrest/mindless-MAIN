package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
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

public class Scaffold extends Module {
    private static final String[] MODE_OPTIONS = {"Normal", "Telly"};
    private static final String[] ROTATION_OPTIONS = {"Normal", "None"};

    private SliderSetting modeSetting;
    private ButtonSetting keepYSetting;
    private SliderSetting rotationModeSetting;
    private ButtonSetting movementFixSetting;
    private ButtonSetting precisionHitVecSetting;

    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placeQueued;
    private int originalSlot = -1;
    private Integer launchY = null;
    private float lastSentYaw = Float.NaN;
    private float lastSentPitch = Float.NaN;

    public int blocksPlaced = 0;

    public Scaffold() {
        super("Scaffold", category.player);
        this.closetModule = true;

        this.registerSetting(modeSetting = new SliderSetting("Mode", 0, MODE_OPTIONS));
        this.registerSetting(keepYSetting = new ButtonSetting("Keep Y", true));
        this.registerSetting(new DescriptionSetting("Rotations"));
        this.registerSetting(rotationModeSetting = new SliderSetting("Rotation Mode", 0, ROTATION_OPTIONS));
        this.registerSetting(movementFixSetting = new ButtonSetting("Movement Fix", true));
        this.registerSetting(precisionHitVecSetting = new ButtonSetting("Grim Bounds Clamp", true));
    }

    @Override
    public void onEnable() {
        placeQueued = false;
        originalSlot = -1;
        blocksPlaced = 0;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        if (mc.thePlayer != null) {
            launchY = MathHelper.floor_double(mc.thePlayer.posY);
        }
    }

    @Override
    public void onDisable() {
        placeQueued = false;
        launchY = null;
        if (originalSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = originalSlot;
            originalSlot = -1;
        }
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        int blockSlot = getBlockSlot();
        if (blockSlot == -1) {
            placeQueued = false;
            return;
        }

        if (mc.thePlayer.inventory.currentItem != blockSlot) {
            if (originalSlot == -1) originalSlot = mc.thePlayer.inventory.currentItem;
            mc.thePlayer.inventory.currentItem = blockSlot;
        }

        ItemStack held = mc.thePlayer.inventory.getStackInSlot(blockSlot);
        if (held == null || !(held.getItem() instanceof ItemBlock)) {
            placeQueued = false;
            return;
        }

        int rotMode = (int) rotationModeSetting.getInput();
        if (rotMode == 1) {
            placeQueued = false;
            return;
        }

        double reach = mc.playerController.getBlockReachDistance();

        Vec3 eye = new Vec3(
                mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(),
                mc.thePlayer.posZ
        );

        if (mc.thePlayer.onGround) {
            launchY = MathHelper.floor_double(mc.thePlayer.posY);
        }

        boolean keepY = keepYSetting.isToggled();
        double targetY = (keepY && launchY != null) ? (launchY - 1.0) : (mc.thePlayer.getEntityBoundingBox().minY - 0.5);

        BlockPos under = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(targetY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );

        TargetResult target = findBestTarget(under, eye, reach);
        if (target == null) {
            placeQueued = false;
            return;
        }

        // Smooth rotation toward target — no GCD here, RotationHelper handles that
        float baseYaw = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];
        if (!Float.isNaN(lastSentYaw)) {
            baseYaw = lastSentYaw;
            basePitch = lastSentPitch;
        }

        float[] smoothed = smoothRotation(baseYaw, basePitch, target.yaw, target.pitch);

        // Simulate what RotationHelper.fixRotation will produce (GCD snap)
        float[] finalRot = RotationUtils.fixRotation(smoothed[0], smoothed[1], baseYaw, basePitch);
        lastSentYaw = finalRot[0];
        lastSentPitch = finalRot[1];

        e.setYaw(smoothed[0]);
        e.setPitch(smoothed[1]);

        // Raytrace with the actual GCD-snapped rotation to decide if we can place
        Vec3 lookVec = getVectorForRotation(finalRot[1], finalRot[0]);
        Vec3 rayEnd = eye.addVector(lookVec.xCoord * reach, lookVec.yCoord * reach, lookVec.zCoord * reach);
        MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(eye, rayEnd, false, false, true);

        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(target.support) && mop.sideHit == target.face) {
            placeAtBlock = mop.getBlockPos();
            hitSide = mop.sideHit;
            hitVec = mop.hitVec;
            placeQueued = true;
        } else {
            // Fallback: if we're close to target, place using geometry
            float yawOff = Math.abs(MathHelper.wrapAngleTo180_float(finalRot[0] - target.yaw));
            float pitchOff = Math.abs(finalRot[1] - target.pitch);
            if (yawOff < 3.0f && pitchOff < 3.0f) {
                placeAtBlock = target.support;
                hitSide = target.face;
                hitVec = target.hitVec;
                placeQueued = true;
            } else {
                placeQueued = false;
            }
        }

        if (movementFixSetting.isToggled()) {
            RotationHelper.get().setRotations(smoothed[0], smoothed[1]);
        }
    }

    private Vec3 getVectorForRotation(float pitch, float yaw) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck()) return;

        if (!placeQueued) return;
        placeQueued = false;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (placeAtBlock != null && hitSide != null && hitVec != null) {
            float fX, fY, fZ;
            if (precisionHitVecSetting.isToggled()) {
                fX = MathHelper.clamp_float((float) (hitVec.xCoord - placeAtBlock.getX()), 0.001f, 0.999f);
                fY = MathHelper.clamp_float((float) (hitVec.yCoord - placeAtBlock.getY()), 0.001f, 0.999f);
                fZ = MathHelper.clamp_float((float) (hitVec.zCoord - placeAtBlock.getZ()), 0.001f, 0.999f);
            } else {
                fX = (float) (hitVec.xCoord - placeAtBlock.getX());
                fY = (float) (hitVec.yCoord - placeAtBlock.getY());
                fZ = (float) (hitVec.zCoord - placeAtBlock.getZ());
            }

            mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(
                    placeAtBlock,
                    hitSide.getIndex(),
                    held,
                    fX, fY, fZ
            ));
            mc.thePlayer.swingItem();
            blocksPlaced++;
        }
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

    public int getTotalBlocksCount() {
        int totalCount = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(i);
            if (stack != null && stack.getItem() instanceof ItemBlock && stack.stackSize > 0) {
                totalCount += stack.stackSize;
            }
        }
        return totalCount;
    }

    private TargetResult findBestTarget(BlockPos centerUnder, Vec3 eye, double reach) {
        if (!BlockUtils.replaceable(centerUnder)) return null;

        double reachSq = reach * reach;
        EnumFacing[] horizontalOrder = getMovementOrderedFacings();

        EnumFacing[] allFacings = new EnumFacing[] {
                EnumFacing.DOWN,
                horizontalOrder[0], horizontalOrder[1], horizontalOrder[2], horizontalOrder[3],
                EnumFacing.UP
        };

        // Primary search: direct neighbor
        TargetResult best = null;
        double bestScore = Double.MAX_VALUE;

        for (EnumFacing facing : allFacings) {
            BlockPos neighbor = centerUnder.offset(facing);
            if (BlockUtils.replaceable(neighbor)) continue;

            EnumFacing opp = facing.getOpposite();
            if (isFaceHidden(neighbor, opp, eye)) continue;

            double hitX = neighbor.getX() + 0.5 + opp.getFrontOffsetX() * 0.5;
            double hitY = neighbor.getY() + 0.5 + opp.getFrontOffsetY() * 0.5;
            double hitZ = neighbor.getZ() + 0.5 + opp.getFrontOffsetZ() * 0.5;

            Vec3 hit = new Vec3(hitX, hitY, hitZ);
            if (eye.squareDistanceTo(hit) > reachSq) continue;

            float[] rots = getRotations(eye, hit);
            double score = scorePlacement(rots[0], eye, hit);
            if (score < bestScore) {
                bestScore = score;
                best = new TargetResult(rots[0], rots[1], neighbor, opp, hit);
            }
        }

        if (best != null) return best;

        // Secondary search: 2-block extend
        for (EnumFacing facing : allFacings) {
            BlockPos neighbor = centerUnder.offset(facing);
            if (!BlockUtils.replaceable(neighbor)) continue;

            for (EnumFacing secondFacing : allFacings) {
                BlockPos secondNeighbor = neighbor.offset(secondFacing);
                if (BlockUtils.replaceable(secondNeighbor)) continue;

                EnumFacing opp = secondFacing.getOpposite();
                if (isFaceHidden(secondNeighbor, opp, eye)) continue;

                double hitX = secondNeighbor.getX() + 0.5 + opp.getFrontOffsetX() * 0.5;
                double hitY = secondNeighbor.getY() + 0.5 + opp.getFrontOffsetY() * 0.5;
                double hitZ = secondNeighbor.getZ() + 0.5 + opp.getFrontOffsetZ() * 0.5;

                Vec3 hit = new Vec3(hitX, hitY, hitZ);
                if (eye.squareDistanceTo(hit) > reachSq) continue;

                float[] rots = getRotations(eye, hit);
                double score = scorePlacement(rots[0], eye, hit);
                if (score < bestScore) {
                    bestScore = score;
                    best = new TargetResult(rots[0], rots[1], secondNeighbor, opp, hit);
                }
            }
        }

        return best;
    }

    private double scorePlacement(float targetYaw, Vec3 eye, Vec3 hit) {
        float currentYaw = RotationUtils.serverRotations[0];
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - currentYaw));

        double score = yawDiff;

        float moveYaw = getDirection();
        float behindYaw = MathHelper.wrapAngleTo180_float(moveYaw + 180.0f);
        float alignDiff = Math.abs(MathHelper.wrapAngleTo180_float(targetYaw - behindYaw));
        score += alignDiff * 0.5;

        score += eye.squareDistanceTo(hit) * 0.1;

        return score;
    }

    private EnumFacing[] getMovementOrderedFacings() {
        float moveYaw = getDirection();
        float behindYaw = moveYaw + 180.0f;

        EnumFacing[] horizontal = { EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.EAST };
        float[] scores = new float[4];
        float[] facingYaws = { 180.0f, 0.0f, 90.0f, -90.0f };

        for (int i = 0; i < 4; i++) {
            scores[i] = Math.abs(MathHelper.wrapAngleTo180_float(facingYaws[i] - behindYaw));
        }

        for (int i = 1; i < 4; i++) {
            float key = scores[i];
            EnumFacing keyFacing = horizontal[i];
            int j = i - 1;
            while (j >= 0 && scores[j] > key) {
                scores[j + 1] = scores[j];
                horizontal[j + 1] = horizontal[j];
                j--;
            }
            scores[j + 1] = key;
            horizontal[j + 1] = keyFacing;
        }

        return horizontal;
    }

    private boolean isFaceHidden(BlockPos pos, EnumFacing face, Vec3 eye) {
        switch (face) {
            case NORTH: return eye.zCoord >= pos.getZ();
            case SOUTH: return eye.zCoord <= pos.getZ() + 1.0;
            case WEST:  return eye.xCoord >= pos.getX();
            case EAST:  return eye.xCoord <= pos.getX() + 1.0;
            case UP:    return eye.yCoord <= pos.getY() + 1.0;
            case DOWN:  return eye.yCoord >= pos.getY();
            default:    return false;
        }
    }

    private int getBlockSlot() {
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

    private float[] getRotations(Vec3 eye, Vec3 target) {
        double dx = target.xCoord - eye.xCoord;
        double dy = target.yCoord - eye.yCoord;
        double dz = target.zCoord - eye.zCoord;
        double dist = MathHelper.sqrt_double(dx * dx + dz * dz);

        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
        float pitch = (float) (-(Math.atan2(dy, dist) * 180.0 / Math.PI));

        return new float[]{ MathHelper.wrapAngleTo180_float(yaw), RotationUtils.clampPitch(pitch) };
    }

    private static class TargetResult {
        final float yaw;
        final float pitch;
        final BlockPos support;
        final EnumFacing face;
        final Vec3 hitVec;

        public TargetResult(float yaw, float pitch, BlockPos support, EnumFacing face, Vec3 hitVec) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.support = support;
            this.face = face;
            this.hitVec = hitVec;
        }
    }
}
