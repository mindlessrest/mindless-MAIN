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
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.*;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

public class Scaffold extends Module {
    private static final String[] MODE_OPTIONS = {"Normal", "Telly"};
    private static final String[] ROTATION_OPTIONS = {"Normal", "Watchdog", "Grim", "None"};
    private static final String[] TOWER_OPTIONS = {"None", "Vanilla", "Watchdog"};
    private static final String[] SPRINT_OPTIONS = {"Normal", "Watchdog"};

    private SliderSetting modeSetting;
    private ButtonSetting keepYSetting;
    private SliderSetting rotationModeSetting;
    private SliderSetting towerSetting;
    private SliderSetting sprintSetting;
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

    // Target rotations for Watchdog/Grim modes
    private float targetYaw = Float.NaN;
    private float targetPitch = Float.NaN;

    // Watchdog Tower State
    private int towerJumpStage = 0;
    private int towerMoveTicks = 0;

    public int blocksPlaced = 0;

    public Scaffold() {
        super("Scaffold", category.player);
        this.closetModule = true;

        this.registerSetting(modeSetting = new SliderSetting("Mode", 0, MODE_OPTIONS));
        this.registerSetting(keepYSetting = new ButtonSetting("Keep Y", true));
        this.registerSetting(new DescriptionSetting("Rotations"));
        this.registerSetting(rotationModeSetting = new SliderSetting("Rotation Mode", 0, ROTATION_OPTIONS));
        this.registerSetting(towerSetting = new SliderSetting("Tower Mode", 0, TOWER_OPTIONS));
        this.registerSetting(sprintSetting = new SliderSetting("Sprint Mode", 0, SPRINT_OPTIONS));
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
        targetYaw = Float.NaN;
        targetPitch = Float.NaN;
        towerJumpStage = 0;
        towerMoveTicks = 0;

        if (mc.thePlayer != null) {
            launchY = MathHelper.floor_double(mc.thePlayer.posY);
        }
    }

    @Override
    public void onDisable() {
        placeQueued = false;
        launchY = null;
        towerJumpStage = 0;
        towerMoveTicks = 0;
        lastSentYaw = Float.NaN;
        lastSentPitch = Float.NaN;
        targetYaw = Float.NaN;
        targetPitch = Float.NaN;

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
        if (rotMode == 3) { // None
            placeQueued = false;
            return;
        }

        // Apply Watchdog Sprint speed regulation if enabled
        handleWatchdogSprint();

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

        TargetResult target = null;

        if (rotMode == 1) {
            // Watchdog raycast sweep mode
            target = findWatchdogTarget(under, eye, reach);
        } else {
            // Normal & Grim search
            target = findBestTarget(under, eye, reach);
        }

        if (target == null) {
            placeQueued = false;
            return;
        }

        this.targetYaw = target.yaw;
        this.targetPitch = target.pitch;

        if (rotMode == 2) {
            // Grim Mode: Queue placement using calculated target parameters but skip ClientRotationEvent modification
            placeAtBlock = target.support;
            hitSide = target.face;
            hitVec = target.hitVec;
            placeQueued = true;
            return;
        }

        // Smooth rotation toward target (Normal & Watchdog modes)
        float baseYaw = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];
        if (!Float.isNaN(lastSentYaw)) {
            baseYaw = lastSentYaw;
            basePitch = lastSentPitch;
        }

        float[] smoothed = RotationUtils.smoothRotation(baseYaw, basePitch, target.yaw, target.pitch, 30);

        // Simulate GCD snap
        float[] finalRot = RotationUtils.fixRotation(smoothed[0], smoothed[1], baseYaw, basePitch);
        lastSentYaw = finalRot[0];
        lastSentPitch = finalRot[1];

        e.setYaw(smoothed[0]);
        e.setPitch(smoothed[1]);

        // Raytrace with actual GCD-snapped rotation to decide if we can place
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
            // Fallback placement close to target
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

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!this.isEnabled()) return;
        if (!Utils.nullCheck()) return;

        // Handle Tower Logic
        handleTower();

        if (!placeQueued) return;
        placeQueued = false;

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        int rotMode = (int) rotationModeSetting.getInput();

        if (rotMode == 2) {
            // Grim Mode Packet Spoof Placement
            placeBlockGrim(held);
            return;
        }

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

    /**
     * Grim anticheat placement bypass:
     * Sends C06PosLook with targeted pitch/yaw -> places block -> sends C06PosLook back to player angles + jitter.
     */
    private void placeBlockGrim(ItemStack held) {
        if (placeAtBlock == null || hitSide == null || hitVec == null || Float.isNaN(targetYaw) || Float.isNaN(targetPitch)) {
            return;
        }

        float fX = precisionHitVecSetting.isToggled() ? MathHelper.clamp_float((float) (hitVec.xCoord - placeAtBlock.getX()), 0.001f, 0.999f) : (float) (hitVec.xCoord - placeAtBlock.getX());
        float fY = precisionHitVecSetting.isToggled() ? MathHelper.clamp_float((float) (hitVec.yCoord - placeAtBlock.getY()), 0.001f, 0.999f) : (float) (hitVec.yCoord - placeAtBlock.getY());
        float fZ = precisionHitVecSetting.isToggled() ? MathHelper.clamp_float((float) (hitVec.zCoord - placeAtBlock.getZ()), 0.001f, 0.999f) : (float) (hitVec.zCoord - placeAtBlock.getZ());

        // Spoof rotation packet before placement
        mc.getNetHandler().addToSendQueue(new C06PacketPlayerPosLook(
                mc.thePlayer.posX,
                mc.thePlayer.posY,
                mc.thePlayer.posZ,
                targetYaw,
                targetPitch,
                mc.thePlayer.onGround
        ));

        // Place block directly
        mc.getNetHandler().addToSendQueue(new C08PacketPlayerBlockPlacement(
                placeAtBlock,
                hitSide.getIndex(),
                held,
                fX, fY, fZ
        ));

        mc.getNetHandler().addToSendQueue(new C0APacketAnimation());

        // Spoof rotation packet after placement with slight jitter
        float jitterYaw = (float) (mc.thePlayer.rotationYaw + Math.random() * 0.03);
        float jitterPitch = (float) MathHelper.clamp_float((float) (mc.thePlayer.rotationPitch - Math.random()), -90.0f, 90.0f);

        mc.getNetHandler().addToSendQueue(new C06PacketPlayerPosLook(
                mc.thePlayer.posX,
                mc.thePlayer.posY,
                mc.thePlayer.posZ,
                jitterYaw,
                jitterPitch,
                mc.thePlayer.onGround
        ));

        blocksPlaced++;
    }

    /**
     * Watchdog Raycast Sweep rotation search algorithm ported from Rise 6.9.5 D() method.
     */
    private TargetResult findWatchdogTarget(BlockPos centerUnder, Vec3 eye, double reach) {
        if (!BlockUtils.replaceable(centerUnder)) return null;

        List<TargetResult> validCandidates = new ArrayList<>();
        double targetBlockY = centerUnder.getY();
        double eyeDeltaY = mc.thePlayer.posY + mc.thePlayer.getEyeHeight() - targetBlockY - 0.5 - (Math.random() - 0.5) * 0.1;

        // Raycast sweep in 45-degree steps around player yaw
        for (int yawOffset = -180; yawOffset <= 180; yawOffset += 45) {
            float testYaw = mc.thePlayer.rotationYaw + yawOffset;
            Vec3 testLook = getVectorForRotation(0.0f, testYaw);
            Vec3 testRayEnd = eye.addVector(testLook.xCoord * reach, testLook.yCoord * reach, testLook.zCoord * reach);

            // Temporarily evaluate raytrace at virtual target elevation
            Vec3 virtualEye = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY - eyeDeltaY, mc.thePlayer.posZ);
            Vec3 virtualEnd = virtualEye.addVector(testLook.xCoord * reach, testLook.yCoord * reach, testLook.zCoord * reach);

            MovingObjectPosition mop = mc.theWorld.rayTraceBlocks(virtualEye, virtualEnd, false, false, true);
            if (mop != null && mop.hitVec != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                float[] rots = getRotations(eye, mop.hitVec);
                validCandidates.add(new TargetResult(rots[0], rots[1], mop.getBlockPos(), mop.sideHit, mop.hitVec));
            }
        }

        if (!validCandidates.isEmpty()) {
            // Find candidate requiring minimal rotation delta from server rotations
            float serverYaw = RotationUtils.serverRotations[0];
            float serverPitch = RotationUtils.serverRotations[1];

            TargetResult best = validCandidates.get(0);
            float bestDist = Math.abs(MathHelper.wrapAngleTo180_float(best.yaw - serverYaw)) + Math.abs(best.pitch - serverPitch);

            for (TargetResult candidate : validCandidates) {
                float dist = Math.abs(MathHelper.wrapAngleTo180_float(candidate.yaw - serverYaw)) + Math.abs(candidate.pitch - serverPitch);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
            return best;
        }

        // Fallback to geometric search if raycast sweep yields no hits
        return findBestTarget(centerUnder, eye, reach);
    }

    /**
     * Handles Tower mechanics (Vanilla / Watchdog).
     */
    private void handleTower() {
        int towerMode = (int) towerSetting.getInput();
        if (towerMode == 0) return; // None

        if (!Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            towerJumpStage = 0;
            towerMoveTicks = 0;
            return;
        }

        if (towerMode == 1) {
            // Vanilla Tower
            if (isBlockBelowPlayer()) {
                mc.thePlayer.motionY = 0.42;
            }
        } else if (towerMode == 2) {
            // Watchdog Multi-stage Tower
            if (mc.thePlayer.onGround) {
                towerJumpStage = 0;
                towerMoveTicks = 0;
            } else {
                towerMoveTicks++;
            }

            if (towerMoveTicks >= 23) {
                towerMoveTicks = 0;
                towerJumpStage = 0;
            }

            if (isBlockBelowPlayer()) {
                switch (towerJumpStage) {
                    case 0:
                        mc.thePlayer.motionY = 0.42;
                        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                            double amp = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1;
                            double mult = (amp >= 2) ? 1.045 : 1.035;
                            mc.thePlayer.motionX *= mult;
                            mc.thePlayer.motionZ *= mult;
                        }
                        towerJumpStage = 1;
                        break;
                    case 1:
                        mc.thePlayer.motionY = 0.33;
                        if (mc.thePlayer.isPotionActive(Potion.moveSpeed)) {
                            double amp = mc.thePlayer.getActivePotionEffect(Potion.moveSpeed).getAmplifier() + 1;
                            double mult = (amp >= 2) ? 1.015 : 1.005;
                            mc.thePlayer.motionX *= mult;
                            mc.thePlayer.motionZ *= mult;
                        }
                        towerJumpStage = 2;
                        break;
                    case 2:
                        mc.thePlayer.motionY = 1.0 - (mc.thePlayer.posY % 1.0);
                        towerJumpStage = 0;
                        break;
                }
            }
        }
    }

    /**
     * Handles Watchdog Sprint speed capping.
     */
    private void handleWatchdogSprint() {
        int sprintMode = (int) sprintSetting.getInput();
        if (sprintMode != 1) return; // Not Watchdog

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
        mc.thePlayer.setSprinting(true);

        double targetSpeed = mc.thePlayer.isPotionActive(Potion.moveSpeed) ? 0.118 : 0.083;
        double currentSpeed = Math.sqrt(mc.thePlayer.motionX * mc.thePlayer.motionX + mc.thePlayer.motionZ * mc.thePlayer.motionZ);

        if (mc.thePlayer.onGround) {
            float moveYaw = getDirection();
            double rad = Math.toRadians(moveYaw);
            mc.thePlayer.motionX = -Math.sin(rad) * targetSpeed;
            mc.thePlayer.motionZ = Math.cos(rad) * targetSpeed;
        } else if (currentSpeed > targetSpeed && !Keyboard.isKeyDown(mc.gameSettings.keyBindJump.getKeyCode())) {
            mc.thePlayer.motionX *= 0.98;
            mc.thePlayer.motionZ *= 0.98;
        }
    }

    private boolean isBlockBelowPlayer() {
        BlockPos pos = new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
        return !mc.theWorld.isAirBlock(pos);
    }

    private Vec3 getVectorForRotation(float pitch, float yaw) {
        float f = MathHelper.cos(-yaw * 0.017453292F - (float) Math.PI);
        float f1 = MathHelper.sin(-yaw * 0.017453292F - (float) Math.PI);
        float f2 = -MathHelper.cos(-pitch * 0.017453292F);
        float f3 = MathHelper.sin(-pitch * 0.017453292F);
        return new Vec3(f1 * f2, f3, f * f2);
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
