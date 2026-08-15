package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class Clutch extends Module {
    private static final Map<String, Integer> BLOCK_SCORE = new HashMap<>();
    private static final double[] PLACE_OFFSETS = {
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125,
            0.34375, 0.40625, 0.46875, 0.53125, 0.59375,
            0.65625, 0.71875, 0.78125, 0.84375, 0.90625, 0.96875
    };

    static {
        BLOCK_SCORE.put("obsidian", 0);
        BLOCK_SCORE.put("end_stone", 1);
        BLOCK_SCORE.put("planks", 2);
        BLOCK_SCORE.put("log", 2);
        BLOCK_SCORE.put("log2", 2);
        BLOCK_SCORE.put("glass", 3);
        BLOCK_SCORE.put("stained_glass", 3);
        BLOCK_SCORE.put("hardened_clay", 4);
        BLOCK_SCORE.put("stained_hardened_clay", 4);
        BLOCK_SCORE.put("stone", 5);
        BLOCK_SCORE.put("wool", 5);
    }

    private final SliderSetting reach;
    private final SliderSetting speed;
    private final SliderSetting snapbackSpeed;
    private final SliderSetting maxDistance;
    private final SliderSetting rotationTolerance;
    private final ButtonSetting simulateFuturePosition;
    private final ButtonSetting autoClutch;
    private final ButtonSetting requireVoid;
    private final SliderSetting minimumFallDistance;
    private final KeySetting selectKeybind;
    private final ButtonSetting multiplace;
    private final keystrokesmod.module.setting.impl.ItemListSetting itemBlacklist;

    private BlockPos placeAtBlock;
    private EnumFacing hitSide;
    private Vec3 hitVec;
    private boolean placeQueued;
    private boolean placing;
    private boolean slotWasSwapped;
    private boolean autoClickerWasOn;
    private int prevSlot = -1;
    private int plannedSlot = -1;
    private float aimYaw;
    private float aimPitch;
    private boolean hasAim;
    private boolean resetting;
    private BlockPos lastPlaced;
    private int clutchBlocksPlaced;
    private boolean autoClutchActive;
    private boolean autoClutchChecking;
    private int autoClutchCheckCounter;
    private boolean autoClutchLandedGuard;
    private int autoClutchLandedTick;
    private float lastPlaceYaw = Float.NaN;
    private float lastPlacePitch = Float.NaN;

    public Clutch() {
        super("Clutch", category.player);
        this.registerSetting(reach = new SliderSetting("Reach", " blocks", 4.5, 0.5, 4.5, 0.1));
        this.registerSetting(speed = new SliderSetting("Speed", 12, 0, 100, 1));
        this.registerSetting(snapbackSpeed = new SliderSetting("Snapback Speed", 12, 0, 100, 1));
        this.registerSetting(maxDistance = new SliderSetting("Max distance", " blocks", 10, 0, 20, 1));
        this.registerSetting(rotationTolerance = new SliderSetting("Rotation Tolerance", "\u00B0", 25, 20, 100, 1));
        this.registerSetting(simulateFuturePosition = new ButtonSetting("Simulate future position", true));
        this.registerSetting(autoClutch = new ButtonSetting("Auto Clutch", false));
        this.registerSetting(requireVoid = new ButtonSetting("Require void", false));
        this.registerSetting(minimumFallDistance = new SliderSetting("Minimum fall distance", " blocks", 10, 3, 20, 1));
        this.registerSetting(selectKeybind = new KeySetting("Select Keybind", 0));
        this.registerSetting(multiplace = new ButtonSetting("Multiplace", true));
        this.registerSetting(itemBlacklist = new keystrokesmod.module.setting.impl.ItemListSetting("Item blacklist"));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        hasAim = false;
        resetting = false;
        clutchBlocksPlaced = 0;
        autoClutchActive = false;
        autoClutchChecking = false;
        autoClutchCheckCounter = 0;
        autoClutchLandedGuard = false;
        autoClutchLandedTick = 0;
        lastPlaceYaw = Float.NaN;
        lastPlacePitch = Float.NaN;
    }

    @Override
    public void onDisable() {
        clearAim(false);
        disablePlacing(true);
        placeQueued = false;
        autoClutchActive = false;
        autoClutchChecking = false;
        autoClutchLandedGuard = false;
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        runPrePlayerInteract();

        if (mc.currentScreen != null) {
            clearAim(true);
            disablePlacing(true);
        }

        if (resetting) {
            float[] smoothed = smoothRotate(e, mc.thePlayer.rotationYaw, mc.thePlayer.rotationPitch, true);
            if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - mc.thePlayer.rotationYaw)) < 0.5f
                    && Math.abs(smoothed[1] - mc.thePlayer.rotationPitch) < 0.5f) {
                resetting = false;
                hasAim = false;
                restoreInputsAndAutoClicker();
                return;
            }
            e.setYaw(smoothed[0]);
            e.setPitch(smoothed[1]);
            return;
        }

        if (!hasAim) return;

        float[] smoothed = smoothRotate(e, aimYaw, aimPitch, false);

        if (placing) {
            double tolerance = rotationTolerance.getInput();
            if (Math.abs(MathHelper.wrapAngleTo180_float(smoothed[0] - RotationUtils.serverRotations[0])) <= tolerance
                    && Math.abs(smoothed[1] - RotationUtils.serverRotations[1]) <= tolerance) {
                int maxBlocks = (int) maxDistance.getInput();
                if (maxBlocks == 0 || clutchBlocksPlaced < maxBlocks) {
                    placeQueued = true;
                }
            }
        }

        e.setYaw(smoothed[0]);
        e.setPitch(smoothed[1]);
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck() || !placeQueued) return;

        placeQueued = false;
        if (!hasAim || plannedSlot == -1) return;

        BlockData data = findBlockToPlace();
        if (data == null) return;

        tryPlace(data);
        if (multiplace.isToggled()) {
            for (int i = 0; i < 3; i++) {
                BlockData extra = findBlockToPlace();
                if (extra == null) break;
                tryPlaceExtra(extra);
            }
        }
    }

    private void tryPlace(BlockData data) {
        ItemStack held = plannedSlot >= 0 && plannedSlot <= 8 ? mc.thePlayer.inventory.mainInventory[plannedSlot] : null;
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        float[] yaws = {aimYaw};
        float[] pitches = {aimPitch};
        double[] x = getOffsets(data.facing, 0);
        double[] y = getOffsets(data.facing, 1);
        double[] z = getOffsets(data.facing, 2);

        for (double dx : x) {
            for (double dy : y) {
                for (double dz : z) {
                    double rx = data.blockPos.getX() + dx - mc.thePlayer.posX;
                    double ry = data.blockPos.getY() + dy - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                    double rz = data.blockPos.getZ() + dz - mc.thePlayer.posZ;
                    float[] rots = getRotations(rx, ry, rz);
                    float qYaw = quantizeAngle(rots[0]);
                    float qPitch = quantizeAngle(MathHelper.clamp_float(rots[1], -90f, 90f));

                    if (isDuplicateSnap(qYaw, qPitch)) continue;

                    MovingObjectPosition mop = rayCast(qYaw, qPitch, reach.getInput());
                    if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !mop.getBlockPos().equals(data.blockPos)
                            || mop.sideHit != data.facing) continue;

                    if (BlockUtils.canPlaceBlockOnSide(held, mop.getBlockPos(), mop.sideHit)) {
                        equipPlannedSlot();
                        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
                            clutchBlocksPlaced++;
                            mc.thePlayer.swingItem();
                            rememberPlace(qYaw, qPitch);
                        }
                        return;
                    }
                }
            }
        }
    }

    private void tryPlaceExtra(BlockData data) {
        ItemStack held = plannedSlot >= 0 && plannedSlot <= 8 ? mc.thePlayer.inventory.mainInventory[plannedSlot] : null;
        if (held == null || !(held.getItem() instanceof ItemBlock)) return;

        MovingObjectPosition mop = rayCast(aimYaw, aimPitch, reach.getInput());
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(data.blockPos)
                && mop.sideHit == data.facing
                && BlockUtils.canPlaceBlockOnSide(held, mop.getBlockPos(), mop.sideHit)) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
                clutchBlocksPlaced++;
                mc.thePlayer.swingItem();
            }
            return;
        }

        Vec3 cv = getClickVec(data.blockPos, data.facing);
        double dx = cv.xCoord - mc.thePlayer.posX;
        double dy = cv.yCoord - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
        double dz = cv.zCoord - mc.thePlayer.posZ;
        float[] rots = getRotations(dx, dy, dz);
        mop = rayCast(rots[0], rots[1], reach.getInput());
        if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && mop.getBlockPos().equals(data.blockPos)
                && mop.sideHit == data.facing
                && BlockUtils.canPlaceBlockOnSide(held, mop.getBlockPos(), mop.sideHit)) {
            if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, mop.getBlockPos(), mop.sideHit, mop.hitVec)) {
                clutchBlocksPlaced++;
                mc.thePlayer.swingItem();
            }
        }
    }

    private BlockData findBlockToPlace() {
        int feetX = MathHelper.floor_double(mc.thePlayer.posX);
        int feetY = MathHelper.floor_double(mc.thePlayer.posY);
        int feetZ = MathHelper.floor_double(mc.thePlayer.posZ);
        BlockPos targetPos = new BlockPos(feetX, feetY - 1, feetZ);

        ArrayList<BlockCandidate> candidates = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = targetPos.add(x, y, z);
                    if (canPlaceThrough(pos)) continue;
                    if (BlockUtils.isInteractable(mc.theWorld.getBlockState(pos).getBlock())) continue;
                    if (mc.thePlayer.getDistance(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > reach.getInput()) continue;

                    for (EnumFacing facing : EnumFacing.VALUES) {
                        if (facing == EnumFacing.DOWN) continue;
                        BlockPos adj = pos.offset(facing);
                        if (canPlaceThrough(adj) && adj.getY() <= feetY) {
                            candidates.add(new BlockCandidate(pos, facing, pos.distanceSqToCenter(feetX + 0.5, feetY + 0.5, feetZ + 0.5)));
                        }
                    }
                }
            }
        }

        candidates.sort(Comparator.comparingDouble(a -> a.score));
        for (BlockCandidate c : candidates) {
            if (canPlaceOn(c)) return new BlockData(c.pos, c.facing);
        }
        return null;
    }

    private boolean canPlaceOn(BlockCandidate c) {
        Block block = BlockUtils.getBlock(c.pos);
        Material mat = block.getMaterial();
        return mat != Material.air && mat != Material.water && mat != Material.lava
                && block != Blocks.fire && !BlockUtils.isInteractable(mc.theWorld.getBlockState(c.pos).getBlock());
    }

    private double[] getOffsets(EnumFacing facing, int axis) {
        switch (facing) {
            case NORTH: return axis == 2 ? new double[]{0.0} : PLACE_OFFSETS;
            case EAST:  return axis == 0 ? new double[]{1.0} : PLACE_OFFSETS;
            case SOUTH: return axis == 2 ? new double[]{1.0} : PLACE_OFFSETS;
            case WEST:  return axis == 0 ? new double[]{0.0} : PLACE_OFFSETS;
            case DOWN:  return axis == 1 ? new double[]{0.0} : PLACE_OFFSETS;
            case UP:    return axis == 1 ? new double[]{1.0} : PLACE_OFFSETS;
            default:    return PLACE_OFFSETS;
        }
    }

    private Vec3 getClickVec(BlockPos pos, EnumFacing facing) {
        double rx = Math.random();
        double ry = Math.random();
        double rz = Math.random();
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        double cx = pos.getX() + Math.min(Math.max(rx, block.getBlockBoundsMinX()), block.getBlockBoundsMaxX());
        double cy = pos.getY() + Math.min(Math.max(ry, block.getBlockBoundsMinY()), block.getBlockBoundsMaxY());
        double cz = pos.getZ() + Math.min(Math.max(rz, block.getBlockBoundsMinZ()), block.getBlockBoundsMaxZ());
        switch (facing) {
            default:  return new Vec3(cx, pos.getY() + block.getBlockBoundsMinY(), cz);
            case UP:    return new Vec3(cx, pos.getY() + block.getBlockBoundsMaxY(), cz);
            case NORTH: return new Vec3(cx, cy, pos.getZ() + block.getBlockBoundsMinZ());
            case EAST:  return new Vec3(pos.getX() + block.getBlockBoundsMaxX(), cy, cz);
            case SOUTH: return new Vec3(cx, cy, pos.getZ() + block.getBlockBoundsMaxZ());
            case WEST:  return new Vec3(pos.getX() + block.getBlockBoundsMinX(), cy, cz);
        }
    }

    private static float quantizeAngle(float angle) {
        return (float) (angle - angle % 0.0096);
    }

    private boolean isDuplicateSnap(float yaw, float pitch) {
        return !Float.isNaN(lastPlaceYaw)
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - lastPlaceYaw)) < 0.35F;
    }

    private void rememberPlace(float yaw, float pitch) {
        lastPlaceYaw = yaw;
        lastPlacePitch = pitch;
    }

    private MovingObjectPosition rayCast(float yaw, float pitch, double dist) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        float cosPitch = MathHelper.cos(-pitch * 0.017453292F - 3.1415927F);
        float sinPitch = MathHelper.sin(-pitch * 0.017453292F - 3.1415927F);
        float cosYaw = MathHelper.cos(-yaw * 0.017453292F - 3.1415927F);
        float sinYaw = MathHelper.sin(-yaw * 0.017453292F - 3.1415927F);
        double dx = sinYaw * cosPitch * dist;
        double dy = sinPitch * dist;
        double dz = cosYaw * cosPitch * dist;
        return mc.theWorld.rayTraceBlocks(eye, eye.addVector(dx, dy, dz));
    }

    private static float[] getRotations(double dx, double dy, double dz) {
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontalDistance));
        return new float[]{normYaw(yaw), RotationUtils.clampPitch(pitch)};
    }

    private static float normYaw(float yaw) {
        yaw = ((yaw % 360f) + 360f) % 360f;
        return yaw > 180f ? yaw - 360f : yaw;
    }

    private float[] smoothRotate(ClientRotationEvent e, float targetYaw, float targetPitch, boolean snapback) {
        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float dYaw = MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
        float dPitch = targetPitch - basePitch;

        if (Math.abs(dYaw) < 0.1f) baseYaw = targetYaw;
        if (Math.abs(dPitch) < 0.1f) basePitch = targetPitch;
        if (baseYaw == targetYaw && basePitch == targetPitch)
            return new float[]{baseYaw, RotationUtils.clampPitch(basePitch)};

        float max = (float) (snapback ? snapbackSpeed.getInput() : speed.getInput());
        float f = 1f - (float) (Math.random() * 0.2);
        max *= f;
        float total = Math.abs(dYaw) + Math.abs(dPitch);
        if (total <= max) {
            baseYaw = targetYaw;
            basePitch = targetPitch;
        } else if (max > 0) {
            float s = max / total;
            baseYaw += dYaw * s;
            basePitch += dPitch * s;
        }
        return new float[]{baseYaw, RotationUtils.clampPitch(basePitch)};
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if ((placing || resetting || hasAim) && e.button > -1) {
            e.setCanceled(true);
        }
    }

    private void runPrePlayerInteract() {
        if (mc.thePlayer.onGround) clutchBlocksPlaced = 0;
        int ticksExisted = mc.thePlayer.ticksExisted;

        updateAutoClutch(ticksExisted);

        boolean active = selectKeybind.isPressed() || autoClutchActive;
        if (mc.currentScreen != null || !active) {
            clearAim(true);
            disablePlacing(false);
            return;
        }

        if (requireVoid.isToggled() && !Utils.overVoid(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ)) {
            clearAim(true);
            disablePlacing(false);
            return;
        }

        BlockPos below = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        if (!canPlaceThrough(below)) {
            clearAim(true);
            return;
        }

        int weakSlot = pickBlockSlot();
        if (weakSlot == -1) {
            clearAim(true);
            return;
        }

        plannedSlot = weakSlot;
        BlockData target = findBlockToPlace();
        if (target == null) {
            clearAim(true);
            return;
        }

        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        double bestDist = Double.MAX_VALUE;
        float bestYaw = 0, bestPitch = 0;
        double[] x = getOffsets(target.facing, 0);
        double[] y = getOffsets(target.facing, 1);
        double[] z = getOffsets(target.facing, 2);

        for (double dx : x) {
            for (double dy : y) {
                for (double dz : z) {
                    double rx = target.blockPos.getX() + dx - mc.thePlayer.posX;
                    double ry = target.blockPos.getY() + dy - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                    double rz = target.blockPos.getZ() + dz - mc.thePlayer.posZ;
                    float[] rots = getRotations(rx, ry, rz);
                    float qYaw = quantizeAngle(rots[0]);
                    float qPitch = quantizeAngle(MathHelper.clamp_float(rots[1], -90f, 90f));

                    if (isDuplicateSnap(qYaw, qPitch)) continue;

                    MovingObjectPosition mop = rayCast(qYaw, qPitch, reach.getInput());
                    if (mop == null || mop.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !mop.getBlockPos().equals(target.blockPos)
                            || mop.sideHit != target.facing) continue;

                    double diff = Math.abs(MathHelper.wrapAngleTo180_float(qYaw - RotationUtils.serverRotations[0]))
                            + Math.abs(qPitch - RotationUtils.serverRotations[1]);
                    if (diff < bestDist) {
                        bestDist = diff;
                        bestYaw = qYaw;
                        bestPitch = qPitch;
                    }
                }
            }
        }

        if (bestDist == Double.MAX_VALUE) {
            clearAim(true);
            return;
        }

        aimYaw = bestYaw;
        aimPitch = bestPitch;
        hasAim = true;
        resetting = false;

        if (hasAim && !placing) enablePlacing();

        if (placing || resetting || hasAim) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
            equipPlannedSlot();
        }
    }

    private void updateAutoClutch(int ticksExisted) {
        if (autoClutch.isToggled()) {
            if (!autoClutchChecking && !autoClutchActive && !autoClutchLandedGuard) {
                autoClutchChecking = true;
                autoClutchCheckCounter = 0;
            }
            if (autoClutchChecking && !autoClutchActive && !autoClutchLandedGuard) {
                if (autoClutchCheckCounter == 0 || autoClutchCheckCounter % 3 == 0) {
                    if (willFallFar(minimumFallDistance.getInput())) {
                        autoClutchActive = true;
                    }
                }
                autoClutchCheckCounter++;
            }
            if (autoClutchLandedGuard) {
                boolean expired = ticksExisted - autoClutchLandedTick >= 10;
                boolean jumped = mc.gameSettings.keyBindJump.isKeyDown();
                boolean airborneUp = !mc.thePlayer.onGround && mc.thePlayer.motionY > 0;
                if (expired || jumped || airborneUp) {
                    autoClutchActive = false;
                    autoClutchChecking = false;
                    autoClutchLandedGuard = false;
                }
            }
            if (autoClutchActive && mc.thePlayer.onGround && mc.thePlayer.hurtTime < mc.thePlayer.maxHurtTime - 2) {
                if (!autoClutchLandedGuard) {
                    autoClutchLandedGuard = true;
                    autoClutchLandedTick = ticksExisted;
                    if (!willFallSoon()) {
                        autoClutchActive = false;
                        autoClutchChecking = false;
                        autoClutchLandedGuard = false;
                    }
                }
            }
            if (!autoClutchActive && !autoClutchLandedGuard && mc.thePlayer.onGround && mc.thePlayer.hurtTime == 0) {
                autoClutchChecking = false;
                autoClutchCheckCounter = 0;
            }
        } else {
            autoClutchActive = false;
            autoClutchChecking = false;
            autoClutchLandedGuard = false;
        }
    }

    private void enablePlacing() {
        if (placing) return;
        placing = true;
        if (!slotWasSwapped) prevSlot = mc.thePlayer.inventory.currentItem;
        autoClickerWasOn = autoClickerWasOn || (ModuleManager.autoClicker != null && ModuleManager.autoClicker.isEnabled());
        if (autoClickerWasOn && ModuleManager.autoClicker != null) {
            ModuleManager.autoClicker.disable();
        }
    }

    private void disablePlacing(boolean forceRestore) {
        if (!placing && !forceRestore) return;
        placing = false;
        plannedSlot = -1;
        if ((forceRestore || !hasAim) && slotWasSwapped && prevSlot != -1 && prevSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = prevSlot;
            slotWasSwapped = false;
        }
        if (forceRestore) {
            prevSlot = -1;
            restoreInputsAndAutoClicker();
        }
    }

    private void clearAim(boolean allowSnapback) {
        if (slotWasSwapped && prevSlot != -1 && prevSlot != mc.thePlayer.inventory.currentItem) {
            mc.thePlayer.inventory.currentItem = prevSlot;
            slotWasSwapped = false;
        }
        lastPlaced = null;
        clutchBlocksPlaced = 0;
        if (allowSnapback && hasAim) resetting = true;
        hasAim = false;
        prevSlot = -1;
    }

    private void restoreInputsAndAutoClicker() {
        if (mc.currentScreen == null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), Mouse.isButtonDown(0));
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), Mouse.isButtonDown(1));
        }
        if (autoClickerWasOn && ModuleManager.autoClicker != null) {
            ModuleManager.autoClicker.enable();
            autoClickerWasOn = false;
        }
    }

    private boolean willFallFar(double minFall) {
        double startY = mc.thePlayer.posY;
        PredictionState p = PredictionState.fromPlayer();
        for (int t = 0; t < 60; t++) {
            p.tick(false);
            if (p.onGround) return false;
            if (startY - p.posY > minFall) return true;
        }
        return false;
    }

    private boolean willFallSoon() {
        PredictionState p = PredictionState.fromPlayer();
        for (int t = 0; t < 10; t++) {
            p.tick(true);
            if (!p.onGround && p.motionY < 0) return true;
        }
        return false;
    }

    private int pickBlockSlot() {
        boolean playingBedwars = Utils.getBedwarsStatus() == 2;
        if (!playingBedwars) {
            int current = mc.thePlayer.inventory.currentItem;
            if (isBlockSlot(current)) return current;
            for (int slot = 8; slot >= 0; --slot) {
                if (isBlockSlot(slot)) return slot;
            }
            return -1;
        }
        int best = -1;
        int bestScore = Integer.MIN_VALUE;
        for (int slot = 8; slot >= 0; --slot) {
            ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
            if (stack == null || stack.stackSize == 0 || !(stack.getItem() instanceof ItemBlock)) continue;
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            ResourceLocation id = Block.blockRegistry.getNameForObject(block);
            if (id == null) continue;
            Integer score = BLOCK_SCORE.get(id.getResourcePath());
            if (score == null) continue;
            if (score > bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    private boolean isBlockSlot(int slot) {
        if (slot < 0 || slot > 8) return false;
        ItemStack stack = mc.thePlayer.inventory.mainInventory[slot];
        if (stack != null && itemBlacklist.matches(stack)) return false;
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock;
    }

    private void equipPlannedSlot() {
        int current = mc.thePlayer.inventory.currentItem;
        if (plannedSlot != -1 && plannedSlot != current) {
            mc.thePlayer.inventory.currentItem = plannedSlot;
            slotWasSwapped = true;
        }
    }

    private boolean canPlaceThrough(BlockPos pos) {
        Block block = BlockUtils.getBlock(pos);
        Material material = block.getMaterial();
        return material == Material.air || material == Material.water || material == Material.lava || block == Blocks.fire;
    }

    private static class BlockCandidate {
        final BlockPos pos;
        final EnumFacing facing;
        final double score;
        BlockCandidate(BlockPos pos, EnumFacing facing, double score) { this.pos = pos; this.facing = facing; this.score = score; }
    }

    private static class BlockData {
        final BlockPos blockPos;
        final EnumFacing facing;
        BlockData(BlockPos blockPos, EnumFacing facing) { this.blockPos = blockPos; this.facing = facing; }
    }

    private static class PredictionState {
        private AxisAlignedBB box;
        private double motionX, motionY, motionZ, posY;
        private boolean onGround;

        static PredictionState fromPlayer() {
            PredictionState s = new PredictionState();
            s.box = mc.thePlayer.getEntityBoundingBox();
            s.motionX = mc.thePlayer.motionX;
            s.motionY = mc.thePlayer.motionY;
            s.motionZ = mc.thePlayer.motionZ;
            s.posY = mc.thePlayer.posY;
            s.onGround = mc.thePlayer.onGround;
            return s;
        }

        void tick(boolean stopHorizontal) {
            if (stopHorizontal) { motionX = 0.0; motionZ = 0.0; }
            motionY -= 0.08;
            move(motionX, motionY, motionZ);
            motionY *= 0.9800000190734863;
            motionX *= 0.91;
            motionZ *= 0.91;
        }

        private void move(double x, double y, double z) {
            double ox = x, oy = y, oz = z;
            List<AxisAlignedBB> collisions = mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box.addCoord(x, y, z));
            for (AxisAlignedBB c : collisions) { y = c.calculateYOffset(box, y); }
            box = box.offset(0.0, y, 0.0);
            for (AxisAlignedBB c : collisions) { x = c.calculateXOffset(box, x); }
            box = box.offset(x, 0.0, 0.0);
            for (AxisAlignedBB c : collisions) { z = c.calculateZOffset(box, z); }
            box = box.offset(0.0, 0.0, z);
            onGround = oy != y && oy < 0.0;
            posY = box.minY;
            if (ox != x) motionX = 0.0;
            if (oy != y) motionY = 0.0;
            if (oz != z) motionZ = 0.0;
        }
    }
}
