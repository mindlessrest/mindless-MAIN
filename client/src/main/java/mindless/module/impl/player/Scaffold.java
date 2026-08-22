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
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 1:1 Opal v2 Scaffold Implementation.
 *
 * Key fix: RotationHelper.onGameTick() overwrites mc.thePlayer.rotationYaw with
 * the server (scaffold) yaw. Between ticks, mouse input adds delta on top of that
 * corrupted value. We recover the REAL camera yaw by tracking the mouse delta
 * (currentRotationYaw - lastServerYaw) and applying it to our own tracked camera yaw.
 */
public class Scaffold extends Module {
    private static final String[] MODE_OPTIONS = {"Watchdog", "Vanilla"};
    private static final String[] SWITCH_MODE_OPTIONS = {"Normal", "Hotbar"};

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
    private Vec2f intelligentRotation;
    private Integer sameYPos = null;
    private int originalSlot = -1;
    private boolean placeQueued = false;
    private Vec3 placeHitVec;
    private EnumFacing placeSide;
    private BlockPos placeBlockPos;

    private float lastSentYaw = Float.NaN;
    private float lastSentPitch = Float.NaN;

    /**
     * The player's REAL camera yaw, tracked independently of mc.thePlayer.rotationYaw
     * which gets corrupted by RotationHelper.onGameTick().
     *
     * Each tick we compute mouseDelta = mc.thePlayer.rotationYaw - lastOverwrittenYaw
     * and add it to playerCameraYaw. This gives us the true camera direction.
     */
    private float playerCameraYaw = 0.0f;
    private float lastOverwrittenYaw = Float.NaN;

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
        intelligentRotation = null;
        placeQueued = false;
        originalSlot = -1;
        blocksPlaced = 0;

        if (mc.thePlayer != null) {
            sameYPos = MathHelper.floor_double(mc.thePlayer.posY);
            // On enable, rotationYaw hasn't been corrupted yet — it IS the real camera yaw
            playerCameraYaw = mc.thePlayer.rotationYaw;
            lastOverwrittenYaw = Float.NaN;
            lastSentYaw = Float.NaN;
            lastSentPitch = Float.NaN;
        } else {
            lastSentYaw = Float.NaN;
            lastSentPitch = Float.NaN;
            lastOverwrittenYaw = Float.NaN;
        }
    }

    @Override
    public void onDisable() {
        blockCache = null;
        rotation = null;
        intelligentRotation = null;
                sameYPos = null;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        lastOverwrittenYaw = Float.NaN;

        if (originalSlot != -1 && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = originalSlot;
        }
        originalSlot = -1;

        // Watchdog sprint holds the key down by hand. Left as-is it stays held after the module
        // stops, so hand it back to whatever the physical key is actually doing.
        if (mc.gameSettings != null && mc.gameSettings.keyBindSprint != null) {
            int sprintKey = mc.gameSettings.keyBindSprint.getKeyCode();
            KeyBinding.setKeyBindState(sprintKey, Keyboard.isKeyDown(sprintKey));
        }
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck() || mc.currentScreen != null || mc.thePlayer.capabilities.isFlying) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        // === RECOVER REAL CAMERA YAW ===
        // Tick order: GameTickEvent → RotationHelper.onGameTick() sets rotationYaw = serverYaw
        //             → mouse input adds delta → ClientRotationEvent (we are here)
        // So: mc.thePlayer.rotationYaw = lastServerYaw + mouseDelta
        // We extract mouseDelta and apply it to our own tracked camera yaw.
        if (!Float.isNaN(lastOverwrittenYaw)) {
            float mouseDelta = mc.thePlayer.rotationYaw - lastOverwrittenYaw;
            playerCameraYaw += mouseDelta;
        } else {
            // First tick after enable, or RotationHelper wasn't active — rotationYaw is real
            playerCameraYaw = mc.thePlayer.rotationYaw;
        }

        int blockSlot = getPlaceableBlockSlot();
        if (blockSlot == -1) {
            blockCache = null;
            rotation = null;
            placeQueued = false;
            // No scaffold rotation this tick — RotationHelper won't overwrite
            lastOverwrittenYaw = Float.NaN;
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
            lastOverwrittenYaw = Float.NaN;
            return;
        }

        // 1. Same Y handling
        boolean sameY = sameYSetting.isToggled();
        boolean autoJump = autoJumpSetting.isToggled();

        boolean updateY = !sameY
                || (autoJump && Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode()))
                || mc.thePlayer.onGround
                || (sameYPos != null && Math.abs(MathHelper.floor_double(mc.thePlayer.posY) - sameYPos) > 3);

        if (updateY) {
            sameYPos = MathHelper.floor_double(mc.thePlayer.posY);
        }

        intelligentRotation = null;

        // 2. Update Movement Intelligence (uses playerCameraYaw for direction)
        updateMovementIntelligence();

        // 3. Update Block Data & Fast Rotation Search
        boolean dataFound = updateData();

        if (dataFound && blockCache != null && rotation != null) {
            Vec2f targetRot = rotation.rotation;

            // Apply HypixelRotationModel if Watchdog mode selected
            if ((int) modeSetting.getInput() == 0) { // Watchdog
                Vec2f currentRot = new Vec2f(
                        Float.isNaN(lastSentYaw) ? MathHelper.wrapAngleTo180_float(playerCameraYaw + 180.0f) : lastSentYaw,
                        Float.isNaN(lastSentPitch) ? 80.0f : lastSentPitch
                );
                targetRot = tickHypixelRotationModel(currentRot, targetRot, 1.0f);
            }

            // Apply sensitivity patching / GCD snapping
            Vec2f sensitivityRot = getVanillaRotation(targetRot, new Vec2f(RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]));
            float[] finalRots = RotationUtils.fixRotation(sensitivityRot.x, sensitivityRot.y, RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

            lastSentYaw = finalRots[0];
            lastSentPitch = finalRots[1];

            e.setYaw(finalRots[0]);
            e.setPitch(finalRots[1]);

            RotationHelper.get().setRotations(finalRots[0], finalRots[1]);

            // Track what RotationHelper will overwrite rotationYaw to next tick
            lastOverwrittenYaw = finalRots[0];

            // Queue block placement
            placeBlockPos = blockCache.blockWithDirection.blockPos;
            placeSide = blockCache.blockWithDirection.direction;
            placeHitVec = rotation.hitResult != null && rotation.hitResult.hitVec != null ? rotation.hitResult.hitVec : getCenterHitVec(placeBlockPos, placeSide);
            placeQueued = true;
        } else {
            placeQueued = false;
            lastOverwrittenYaw = Float.NaN;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck()) return;

        // Opal Auto-jump logic
        if (sameYSetting.isToggled() && autoJumpSetting.isToggled() && mc.thePlayer.onGround) {
            if (isNearingVoidEdge()) {
                e.setJump(true);
            }
        }

        if (!placeQueued) return;
        placeQueued = false;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        if (placeBlockPos != null && placeSide != null && placeHitVec != null) {
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

    private void updateMovementIntelligence() {
        if (movementIntelSetting.isToggled()) {
            Vec2f currentRotation = new Vec2f(
                    Float.isNaN(lastSentYaw) ? MathHelper.wrapAngleTo180_float(playerCameraYaw + 180.0f) : lastSentYaw,
                    Float.isNaN(lastSentPitch) ? 80.0f : lastSentPitch
            );
            intelligentRotation = getPriorityAngle(currentRotation, 3.0f, snapMovementSetting.isToggled(), diagonalSetting.isToggled());
        }
    }

    private boolean updateData() {
        blockCache = getBlockData();
        if (blockCache != null) {
            this.rotation = blockCache.rotation;
            return true;
        }

        // Fast 2-step lookahead if moving
        double vx = mc.thePlayer.motionX;
        double vz = mc.thePlayer.motionZ;
        if (Math.abs(vx) > 0.01 || Math.abs(vz) > 0.01) {
            Vec3 eyePos = getEyePos();
            for (int i = 1; i <= 2; i++) {
                BlockPos simPos = new BlockPos(
                        MathHelper.floor_double(mc.thePlayer.posX + vx * i),
                        (sameYPos != null ? sameYPos - 1 : MathHelper.floor_double(mc.thePlayer.posY) - 1),
                        MathHelper.floor_double(mc.thePlayer.posZ + vz * i)
                );
                BlockData simulatedData = getBlockData(simPos, eyePos);
                if (simulatedData != null) {
                    rotation = simulatedData.rotation;
                    blockCache = simulatedData;
                    break;
                }
            }
        }

        return blockCache != null;
    }

    private BlockData getBlockData() {
        int targetY = (sameYPos != null ? sameYPos - 1 : MathHelper.floor_double(mc.thePlayer.posY) - 1);
        BlockPos targetPos = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                targetY,
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        return getBlockData(targetPos, getEyePos());
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

            blockList.sort(Comparator.comparingDouble(data -> data.blockPos.offset(data.direction).distanceSq(targetBlockPos)));

            for (BlockWithDirection block : blockList) {
                RaytracedRotation rRot = getRotation(block, eyePos);
                if (rRot != null) {
                    return new BlockData(block, rRot);
                }
            }
        }
        return null;
    }

    private RaytracedRotation getRotation(BlockWithDirection data, Vec3 eyePos) {
        Vec2f sortingAngle = (intelligentRotation != null) ? intelligentRotation :
                new Vec2f(Float.isNaN(lastSentYaw) ? MathHelper.wrapAngleTo180_float(playerCameraYaw + 180.0f) : lastSentYaw, 80.0f);

        return getRotationFromRaycastedBlock(data.blockPos, data.direction, sortingAngle, eyePos);
    }

    private RaytracedRotation getRotationFromRaycastedBlock(BlockPos blockPos, EnumFacing side, Vec2f priorityRotations, Vec3 eyePos) {
        double reach = mc.playerController.getBlockReachDistance();
        List<RaytracedRotation> rotations = new ArrayList<>();

        Vec3 centerVec = getCenterHitVec(blockPos, side);

        double[][] faceOffsets;
        if (side.getAxis() == EnumFacing.Axis.X) {
            faceOffsets = new double[][]{{0, 0, 0}, {0, -0.35, -0.35}, {0, 0.35, -0.35}, {0, -0.35, 0.35}, {0, 0.35, 0.35}};
        } else if (side.getAxis() == EnumFacing.Axis.Y) {
            faceOffsets = new double[][]{{0, 0, 0}, {-0.35, 0, -0.35}, {0.35, 0, -0.35}, {-0.35, 0, 0.35}, {0.35, 0, 0.35}};
        } else {
            faceOffsets = new double[][]{{0, 0, 0}, {-0.35, -0.35, 0}, {0.35, -0.35, 0}, {-0.35, 0.35, 0}, {0.35, 0.35, 0}};
        }

        for (double[] off : faceOffsets) {
            Vec3 testPoint = centerVec.addVector(off[0], off[1], off[2]);
            Vec2f rawRot = getRotationFromPosition(eyePos, testPoint);
            Vec2f raytraceRotation = getVanillaRotation(rawRot, priorityRotations);

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

    /**
     * Opal getPriorityAngle — uses playerCameraYaw (real camera direction) to compute
     * the backward placement yaw. This prevents the 180-degree flip-flop caused by
     * RotationHelper overwriting mc.thePlayer.rotationYaw.
     */
    private Vec2f getPriorityAngle(Vec2f currentRotation, float steps, boolean snap, boolean diagonal) {
        // Use playerCameraYaw (real camera) instead of mc.thePlayer.rotationYaw (corrupted)
        float moveDir = getDirection();
        // Target backward: we place blocks BEHIND us as we walk forward
        float targetYaw = MathHelper.wrapAngleTo180_float(moveDir + 180.0f);

        List<Float> yaws = new ArrayList<>();
        yaws.add(targetYaw);
        if (diagonal) {
            yaws.add(MathHelper.wrapAngleTo180_float(targetYaw + 45.0f));
            yaws.add(MathHelper.wrapAngleTo180_float(targetYaw - 45.0f));
        }

        yaws.sort(Comparator.comparingDouble(y -> Math.abs(MathHelper.wrapAngleTo180_float(y - currentRotation.x))));
        return new Vec2f(yaws.get(0), currentRotation.y);
    }

    private Vec2f tickHypixelRotationModel(Vec2f from, Vec2f to, float timeDelta) {
        float deltaYaw = MathHelper.wrapAngleTo180_float(to.x - from.x) * timeDelta;
        float deltaPitch = (to.y - from.y) * timeDelta;

        double distance = Math.sqrt(deltaYaw * deltaYaw + deltaPitch * deltaPitch);
        if (distance == 0.0) {
            return new Vec2f(from.x + deltaYaw, from.y + deltaPitch);
        }

        float speed = isYawDiagonal() ? (mc.thePlayer.fallDistance > 0 ? 65.0f : 36.0f) : 35.0f;

        double distributionYaw = Math.abs(deltaYaw / distance);
        double distributionPitch = Math.abs(deltaPitch / distance);

        double maxYaw = speed * distributionYaw;
        double maxPitch = speed * distributionPitch;

        float moveYaw = (float) Math.max(Math.min(deltaYaw, maxYaw), -maxYaw);
        float movePitch = (float) Math.max(Math.min(deltaPitch, maxPitch), -maxPitch);

        return new Vec2f(from.x + moveYaw, from.y + movePitch);
    }

    private boolean isYawDiagonal() {
        float direction = Math.abs(getDirection() % 90.0f);
        int range = 30;
        return direction > 45 - range && direction < 45 + range;
    }

    private Vec2f patchConstantRotation(Vec2f rotation, Vec2f prevRotation) {
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6f + 0.2f;
        float multiplier = sensitivity * sensitivity * sensitivity * 8.0f;
        float divisor = multiplier * 0.15f;

        float yawDelta = rotation.x - prevRotation.x;
        float pitchDelta = rotation.y - prevRotation.y;
        float yaw = prevRotation.x + (Math.round(yawDelta / divisor) * divisor);
        float pitch = prevRotation.y + (Math.round(pitchDelta / divisor) * divisor);
        return new Vec2f(yaw, pitch);
    }

    private Vec2f getVanillaRotation(Vec2f original, Vec2f current) {
        Vec2f patched = patchConstantRotation(original, current);
        float wrappedYaw = current.x + MathHelper.wrapAngleTo180_float(patched.x - current.x);
        return new Vec2f(wrappedYaw, patched.y);
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

    /**
     * Computes the player's ACTUAL movement direction using playerCameraYaw (real camera yaw)
     * instead of mc.thePlayer.rotationYaw (which RotationHelper corrupts with server scaffold yaw).
     */
    public float getDirection() {
        float direction = playerCameraYaw; // USE REAL CAMERA YAW, NOT mc.thePlayer.rotationYaw
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
