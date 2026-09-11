package mindless.module.impl.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import mindless.event.ClientRotationEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.event.RightClickMouseEvent;
import mindless.event.SlotUpdateEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.rotation.RotationSource;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldSettings.GameType;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Scaffold extends Module {
    private static final int ROTATIONS_NONE = 0;
    private static final int ROTATIONS_DEFAULT = 1;
    private static final int ROTATIONS_BACKWARDS = 2;
    private static final int ROTATIONS_SIDEWAYS = 3;
    private static final int MOVE_FIX_SILENT = 1;
    private static final int SPRINT_NONE = 0;
    private static final int TOWER_NONE = 0;
    private static final int TOWER_TELLY = 2;
    private static final int KEEP_Y_NONE = 0;
    private static final int KEEP_Y_EXTRA = 2;
    private static final int KEEP_Y_TELLY = 3;
    private static final double[] FACE_SAMPLE_OFFSETS = {
            0.03125D, 0.09375D, 0.15625D, 0.21875D,
            0.28125D, 0.34375D, 0.40625D, 0.46875D,
            0.53125D, 0.59375D, 0.65625D, 0.71875D,
            0.78125D, 0.84375D, 0.90625D, 0.96875D
    };

    private final SliderSetting rotations;
    private final SliderSetting moveFix;
    private final SliderSetting sprint;
    private final SliderSetting tower;
    private final SliderSetting keepY;
    private final ButtonSetting keepYOnPress;
    private final ButtonSetting multiPlace;
    private final ButtonSetting safeWalk;
    private final ButtonSetting swing;
    private final ButtonSetting itemSpoof;
    private final ButtonSetting blockCounter;

    private int placementDelayTicks;
    private int previousHotbarSlot = -1;
    private int remainingStackBlocks = -1;
    private float placementYaw = -180.0F;
    private float placementPitch;
    private boolean hasPlacementRotation;
    private int keepYState;
    private int keepYLevel = 256;
    private boolean keepYRecoveryPlacement;
    private boolean tellyRotationActive;
    private boolean wasOnGround;
    private BlockPlacementTarget queuedTarget;
    private Vec3 queuedHit;
    private PlacementLease placementLease;

    public Scaffold() {
        super("Scaffold", "Bridges by placing blocks under your feet.", category.player, 33);
        this.closetModule = true;
        this.registerSetting(rotations = new SliderSetting("rotations", ROTATIONS_DEFAULT,
                new String[]{"NONE", "DEFAULT", "BACKWARDS", "SIDEWAYS"}));
        this.registerSetting(moveFix = new SliderSetting("move-fix", MOVE_FIX_SILENT,
                new String[]{"NONE", "SILENT"}));
        this.registerSetting(sprint = new SliderSetting("sprint", SPRINT_NONE,
                new String[]{"NONE", "VANILLA"}));
        this.registerSetting(tower = new SliderSetting("tower", TOWER_NONE,
                new String[]{"NONE", "VANILLA", "TELLY"}));
        this.registerSetting(keepY = new SliderSetting("keep-y", KEEP_Y_TELLY,
                new String[]{"NONE", "VANILLA", "EXTRA", "TELLY"}));
        this.registerSetting(keepYOnPress = new ButtonSetting("keep-y-on-press", true));
        this.registerSetting(multiPlace = new ButtonSetting("multi-place", false));
        this.registerSetting(safeWalk = new ButtonSetting("safe-walk", false));
        this.registerSetting(swing = new ButtonSetting("swing", true));
        this.registerSetting(itemSpoof = new ButtonSetting("item-spoof", true));
        this.registerSetting(blockCounter = new ButtonSetting("block-counter", true));
    }

    @Override
    public void guiUpdate() {
        keepYOnPress.setVisible((int) keepY.getInput() != KEEP_Y_NONE, this);
    }

    @Override
    public String getInfo() {
        return rotations.getSelectedOption();
    }

    @Override
    public void onEnable() {
        if (Utils.nullCheck() && mc.playerController != null) {
            mc.playerController.resetBlockRemoving();
        }
        previousHotbarSlot = Utils.nullCheck() ? mc.thePlayer.inventory.currentItem : -1;
        remainingStackBlocks = -1;
        placementDelayTicks = 0;
        placementYaw = -180.0F;
        placementPitch = 0.0F;
        hasPlacementRotation = false;
        keepYState = 0;
        keepYLevel = 256;
        keepYRecoveryPlacement = false;
        tellyRotationActive = false;
        wasOnGround = Utils.nullCheck() && mc.thePlayer.onGround;
        queuedTarget = null;
        queuedHit = null;
    }

    @Override
    public void onDisable() {
        RotationHelper.get().release(RotationSource.SCAFFOLD);
        releasePlacement(false);
        PlacementCoordinator.get().cancel(this);
        if (Utils.nullCheck() && itemSpoof.isToggled() && previousHotbarSlot >= 0) {
            Utils.switchSlot(previousHotbarSlot, false);
        }
        queuedTarget = null;
        queuedHit = null;
        placementDelayTicks = 0;
        hasPlacementRotation = false;
        keepYState = 0;
        keepYRecoveryPlacement = false;
        tellyRotationActive = false;
        remainingStackBlocks = -1;
        previousHotbarSlot = -1;
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent event) {
        if (!isEnabled() || !Utils.nullCheck()) {
            return;
        }
        if (placementDelayTicks > 0) {
            placementDelayTicks--;
        }
        updateKeepYState();
        updateExtraKeepYRecovery();

        float previousYaw = event.getBaseYaw() != null ? event.getBaseYaw() : RotationUtils.serverRotations[0];
        float previousPitch = event.getBasePitch() != null ? event.getBasePitch() : RotationUtils.serverRotations[1];
        float movementYaw = getMovementYaw();
        float backwardYaw = movementYaw - 180.0F;
        float sidewaysYaw = getSidewaysYaw(movementYaw);
        if (!hasPlacementRotation) {
            placementPitch = quantize(placementYaw, RandomRange.floatBetween(65.0F, 85.0F),
                    previousYaw, previousPitch)[1];
            if ((int) rotations.getInput() == ROTATIONS_BACKWARDS) {
                placementYaw = quantize(backwardYaw, placementPitch, previousYaw, previousPitch)[0];
            }
            else if ((int) rotations.getInput() == ROTATIONS_SIDEWAYS) {
                placementYaw = quantize(sidewaysYaw, placementPitch, previousYaw, previousPitch)[0];
            }
        }

        BlockPlacementTarget target = findPlacementTarget();
        if (target == null || !acquirePlacement()) {
            queuedTarget = null;
            queuedHit = null;
            applyPlacementRotation(event, previousYaw, previousPitch, backwardYaw, sidewaysYaw);
            return;
        }
        selectPlacementHotbarSlot();
        if (!isHoldingPlaceableBlock()) {
            queuedTarget = null;
            queuedHit = null;
            applyPlacementRotation(event, previousYaw, previousPitch, backwardYaw, sidewaysYaw);
            return;
        }
        Vec3 hit = findBestPlacementHit(target, previousYaw, previousPitch);
        applyPlacementRotation(event, previousYaw, previousPitch, backwardYaw, sidewaysYaw);
        if (hit == null) {
            queuedTarget = null;
            queuedHit = null;
            return;
        }
        queuedTarget = target;
        queuedHit = hit;
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!isEnabled() || !Utils.nullCheck()) {
            return;
        }
        placeCurrentAndAdditionalBlocks();
    }

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!isEnabled() || !Utils.nullCheck()) {
            return;
        }
        if (wantsSafeWalk()) {
            event.setSneak(true);
        }
        if (mc.thePlayer.onGround && keepYState > 0 && hasMovementInput(event)
                && !event.isSneak() && isHoldingPlaceableBlock()
                && !mc.thePlayer.capabilities.isFlying && !mc.thePlayer.isRiding()) {
            event.setJump(true);
        }
    }

    public void beforeLivingMovement() {
        if (!isEnabled() || !Utils.nullCheck() || mc.thePlayer.movementInput == null) {
            return;
        }
        if (shouldDisableSprint() || mc.thePlayer.movementInput.moveForward < 0.8F
                || mc.thePlayer.movementInput.sneak || mc.thePlayer.isCollidedHorizontally
                || mc.thePlayer.isUsingItem() || mc.thePlayer.isPotionActive(Potion.blindness)
                || mc.thePlayer.getFoodStats().getFoodLevel() <= 6 && !mc.thePlayer.capabilities.allowFlying) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent
    public void onBlockAttack(PreAttackEvent event) {
        if (blocksMining() && event.objectMouseOver != null
                && event.objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClick(RightClickMouseEvent event) {
        if (isEnabled()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onSlotUpdate(SlotUpdateEvent event) {
        if (!isEnabled()) {
            return;
        }
        previousHotbarSlot = event.slot;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (!isEnabled() || !blockCounter.isToggled()
                || event.type != RenderGameOverlayEvent.ElementType.CROSSHAIRS || !Utils.nullCheck()) {
            return;
        }
        drawBlockCounter(event.resolution, countPlaceableHotbarBlocks());
    }

    public boolean wantsSafeWalk() {
        return isEnabled() && safeWalk.isToggled() && Utils.nullCheck()
                && !mc.thePlayer.capabilities.isFlying && !mc.thePlayer.isRiding()
                && mc.thePlayer.onGround && mc.thePlayer.motionY <= 0.0D
                && hasNoSupportAt(mc.thePlayer.motionX, mc.thePlayer.motionZ);
    }

    public boolean isActivelyScaffolding() {
        return isEnabled() && queuedTarget != null;
    }

    public boolean blocksMining() {
        return isEnabled();
    }

    public boolean usesSilentMoveFix() {
        return isEnabled() && (int) moveFix.getInput() == MOVE_FIX_SILENT;
    }

    private void updateKeepYState() {
        boolean onGround = mc.thePlayer.onGround;
        if (!onGround) {
            wasOnGround = false;
            return;
        }
        if (keepYState > 0) {
            keepYState--;
        }
        if (keepYState < 0) {
            keepYState++;
        }
        boolean keepYEnabled = (int) keepY.getInput() != KEEP_Y_NONE;
        boolean useKeyDown = Utils.isBindDown(mc.gameSettings.keyBindUseItem);
        boolean jumpKeyDown = Utils.isBindDown(mc.gameSettings.keyBindJump);
        if (keepYState == 0 && keepYEnabled && (!keepYOnPress.isToggled() || useKeyDown)
                && !jumpKeyDown && !mc.thePlayer.isPotionActive(Potion.jump)) {
            keepYState = 1;
        }
        if (!keepYRecoveryPlacement) {
            keepYLevel = MathHelper.floor_double(mc.thePlayer.posY);
        }
        keepYRecoveryPlacement = false;
        if (!wasOnGround) {
            tellyRotationActive = false;
        }
        wasOnGround = true;
    }

    private void updateExtraKeepYRecovery() {
        if ((int) keepY.getInput() != KEEP_Y_EXTRA || keepYState <= 0 || mc.thePlayer.onGround) {
            return;
        }
        int predictedBlockY = MathHelper.floor_double(mc.thePlayer.posY + mc.thePlayer.motionY);
        if (predictedBlockY > keepYLevel || mc.thePlayer.posY <= keepYLevel + 1.0D) {
            return;
        }
        keepYRecoveryPlacement = true;
    }

    private BlockPlacementTarget findPlacementTarget() {
        int playerBlockY = MathHelper.floor_double(mc.thePlayer.posY);
        int placementY = keepYState != 0 && !keepYRecoveryPlacement
                ? Math.min(playerBlockY, keepYLevel) : playerBlockY;
        BlockPos desired = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX), placementY - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!BlockUtils.replaceable(desired)) {
            return null;
        }

        double reach = mc.playerController.getBlockReachDistance();
        ArrayList<BlockPos> supports = new ArrayList<BlockPos>();
        for (int offsetX = -4; offsetX <= 4; offsetX++) {
            for (int offsetY = -4; offsetY <= 0; offsetY++) {
                for (int offsetZ = -4; offsetZ <= 4; offsetZ++) {
                    BlockPos candidate = desired.add(offsetX, offsetY, offsetZ);
                    Block block = BlockUtils.getBlock(candidate);
                    if (BlockUtils.replaceable(candidate) || BlockUtils.isInteractable(block)) {
                        continue;
                    }
                    double dx = candidate.getX() + 0.5D - mc.thePlayer.posX;
                    double dy = candidate.getY() + 0.5D - mc.thePlayer.posY;
                    double dz = candidate.getZ() + 0.5D - mc.thePlayer.posZ;
                    if (dx * dx + dy * dy + dz * dz > reach * reach) {
                        continue;
                    }
                    if (keepYState != 0 && !keepYRecoveryPlacement && candidate.getY() >= keepYLevel) {
                        continue;
                    }
                    for (EnumFacing face : EnumFacing.VALUES) {
                        if (face != EnumFacing.DOWN && BlockUtils.replaceable(candidate.offset(face))) {
                            supports.add(candidate);
                            break;
                        }
                    }
                }
            }
        }
        if (supports.isEmpty()) {
            return null;
        }
        Collections.sort(supports, new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos left, BlockPos right) {
                return Double.compare(distanceSquared(desired, left), distanceSquared(desired, right));
            }
        });
        BlockPos support = supports.get(0);
        EnumFacing face = findConnectingFace(support, desired);
        return face == null ? null : new BlockPlacementTarget(support, face);
    }

    private EnumFacing findConnectingFace(BlockPos support, BlockPos desired) {
        EnumFacing best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnumFacing face : EnumFacing.VALUES) {
            if (face == EnumFacing.DOWN) {
                continue;
            }
            BlockPos neighbor = support.offset(face);
            if (neighbor.getY() > desired.getY() || !BlockUtils.replaceable(neighbor)) {
                continue;
            }
            double distance = distanceSquared(desired, neighbor);
            if (best == null || distance < bestDistance
                    || distance == bestDistance && face == EnumFacing.UP) {
                best = face;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Vec3 findBestPlacementHit(BlockPlacementTarget target, float previousYaw, float previousPitch) {
        if (keepYState == 0 && (int) rotations.getInput() == ROTATIONS_DEFAULT
                && usesSilentMoveFix() && hasMovementInput()) {
            Vec3 alignedHit = findBestPlacementHit(target, previousYaw, previousPitch, true);
            if (alignedHit != null) {
                return alignedHit;
            }
        }
        return findBestPlacementHit(target, previousYaw, previousPitch, false);
    }

    private Vec3 findBestPlacementHit(BlockPlacementTarget target, float previousYaw, float previousPitch,
                                     boolean alignMovement) {
        double[] offsetsX = FACE_SAMPLE_OFFSETS;
        double[] offsetsY = FACE_SAMPLE_OFFSETS;
        double[] offsetsZ = FACE_SAMPLE_OFFSETS;
        switch (target.face) {
            case NORTH:
                offsetsZ = new double[]{0.0D};
                break;
            case EAST:
                offsetsX = new double[]{1.0D};
                break;
            case SOUTH:
                offsetsZ = new double[]{1.0D};
                break;
            case WEST:
                offsetsX = new double[]{0.0D};
                break;
            case DOWN:
                offsetsY = new double[]{0.0D};
                break;
            case UP:
                offsetsY = new double[]{1.0D};
                break;
            default:
                break;
        }
        Vec3 bestHit = null;
        float bestYaw = placementYaw;
        float bestPitch = placementPitch;
        float bestCost = Float.MAX_VALUE;
        for (double offsetX : offsetsX) {
            for (double offsetY : offsetsY) {
                for (double offsetZ : offsetsZ) {
                    double deltaX = target.position.getX() + offsetX - mc.thePlayer.posX;
                    double deltaY = target.position.getY() + offsetY
                            - mc.thePlayer.posY - mc.thePlayer.getEyeHeight();
                    double deltaZ = target.position.getZ() + offsetZ - mc.thePlayer.posZ;
                    float[] raw = rotationsFromDelta(deltaX, deltaY, deltaZ);
                    if (alignMovement) {
                        float relativeYaw = MathHelper.wrapAngleTo180_float(raw[0] - mc.thePlayer.rotationYaw);
                        raw[0] = mc.thePlayer.rotationYaw + Math.round(relativeYaw / 45.0F) * 45.0F;
                    }
                    float[] candidate = quantize(raw[0], raw[1], previousYaw, previousPitch);
                    MovingObjectPosition hit = RotationUtils.rayCastBlock(
                            mc.playerController.getBlockReachDistance(), candidate[0], candidate[1]);
                    if (hit == null || hit.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                            || !target.position.equals(hit.getBlockPos()) || target.face != hit.sideHit) {
                        continue;
                    }
                    float cost = Math.abs(MathHelper.wrapAngleTo180_float(candidate[0] - placementYaw))
                            + Math.abs(candidate[1] - placementPitch);
                    if (cost < bestCost) {
                        bestCost = cost;
                        bestYaw = candidate[0];
                        bestPitch = candidate[1];
                        bestHit = hit.hitVec;
                    }
                }
            }
        }
        if (bestHit != null) {
            placementYaw = bestYaw;
            placementPitch = bestPitch;
            hasPlacementRotation = true;
        }
        return bestHit;
    }

    private void applyPlacementRotation(ClientRotationEvent event, float previousYaw, float previousPitch,
                                        float backwardYaw, float sidewaysYaw) {
        if ((int) rotations.getInput() == ROTATIONS_NONE || !hasPlacementRotation) {
            return;
        }
        if (hasMovementInput()) {
            float backwardDifference = Math.abs(MathHelper.wrapAngleTo180_float(backwardYaw - placementYaw));
            if (backwardDifference < 90.0F) {
                if ((int) rotations.getInput() == ROTATIONS_BACKWARDS) {
                    placementYaw = quantize(backwardYaw, placementPitch, previousYaw, previousPitch)[0];
                }
                else if ((int) rotations.getInput() == ROTATIONS_SIDEWAYS) {
                    placementYaw = quantize(sidewaysYaw, placementPitch, previousYaw, previousPitch)[0];
                }
            }
        }
        float requestedYaw = placementYaw;
        float requestedPitch = placementPitch;
        if (isTellyTakeoff() && !tellyRotationActive) {
            float playerYawDifference = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - previousYaw);
            requestedYaw = quantize(previousYaw + playerYawDifference * RandomRange.floatBetween(0.98F, 0.99F),
                    placementPitch, previousYaw, previousPitch)[0];
            requestedPitch = quantize(previousYaw, RandomRange.floatBetween(30.0F, 80.0F),
                    previousYaw, previousPitch)[1];
            placementDelayTicks = 3;
            tellyRotationActive = true;
        }
        else {
            float difference = MathHelper.wrapAngleTo180_float(placementYaw - previousYaw);
            float maxChange = RandomRange.floatBetween(80.0F, 90.0F);
            if (tellyRotationActive && mc.thePlayer.motionY > 0.0D && placementDelayTicks <= 1) {
                maxChange = RandomRange.floatBetween(30.0F, 35.0F);
            }
            if (Math.abs(difference) > maxChange) {
                requestedYaw = quantize(previousYaw + Math.copySign(maxChange, difference), placementPitch,
                        previousYaw, previousPitch)[0];
                placementDelayTicks = Math.max(placementDelayTicks, 1);
            }
        }
        event.requestRotation(RotationSource.SCAFFOLD, requestedYaw, requestedPitch);
    }

    private boolean placeCurrentAndAdditionalBlocks() {
        if (queuedTarget == null || queuedHit == null || placementDelayTicks > 0) {
            return false;
        }
        boolean placed = placeBlock(queuedTarget);
        queuedTarget = null;
        queuedHit = null;
        if (!placed || !multiPlace.isToggled()) {
            return placed;
        }
        for (int index = 0; index < 3; index++) {
            BlockPlacementTarget target = findPlacementTarget();
            if (target == null) {
                break;
            }
            if (!placeBlock(target)) {
                break;
            }
        }
        return true;
    }

    private boolean placeBlock(BlockPlacementTarget target) {
        if (!isHoldingPlaceableBlock() || remainingStackBlocks <= 0 || placementLease == null
                || !PlacementRuntime.isHotbarSynchronized()) {
            return false;
        }
        RotationHelper helper = RotationHelper.get();
        float yaw = helper.getServerYaw() == null ? mc.thePlayer.rotationYaw : helper.getServerYaw();
        float pitch = helper.getServerPitch() == null ? mc.thePlayer.rotationPitch : helper.getServerPitch();
        MovingObjectPosition hit = RotationUtils.rayCastBlock(mc.playerController.getBlockReachDistance(), yaw, pitch);
        if (hit == null || !target.position.equals(hit.getBlockPos()) || target.face != hit.sideHit) {
            return false;
        }
        final Vec3 placementHit = hit.hitVec;
        final ItemStack stack = mc.thePlayer.inventory.getCurrentItem();
        if (stack == null) {
            return false;
        }
        boolean placed = placementLease.tryControllerAction(Utils.getBaseClientTick(), multiPlace.isToggled() ? 4 : 1,
                new PlacementLease.ControllerAction() {
                    @Override
                    public boolean run() {
                        return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack,
                                target.position, target.face, placementHit);
                    }
                });
        if (!placed) {
            return false;
        }
        if (mc.playerController.getCurrentGameType() != GameType.CREATIVE) {
            remainingStackBlocks--;
        }
        if (swing.isToggled()) {
            mc.thePlayer.swingItem();
        }
        else if (mc.getNetHandler() != null) {
            mc.getNetHandler().addToSendQueue(new C0APacketAnimation());
        }
        return true;
    }

    private void selectPlacementHotbarSlot() {
        ItemStack held = mc.thePlayer.getHeldItem();
        int heldCount = isPlaceableBlockStack(held) ? held.stackSize : 0;
        remainingStackBlocks = Math.min(remainingStackBlocks, heldCount);
        if (remainingStackBlocks > 0) {
            return;
        }
        int current = mc.thePlayer.inventory.currentItem;
        int start = remainingStackBlocks == 0 ? current - 1 : current;
        for (int cursor = start; cursor > start - 9; cursor--) {
            int slot = (cursor % 9 + 9) % 9;
            ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(slot);
            if (!isPlaceableBlockStack(candidate)) {
                continue;
            }
            if (placementLease.claimHotbar(PlacementRuntime.hotbar(), slot)) {
                remainingStackBlocks = candidate.stackSize;
            }
            return;
        }
    }

    private boolean isTellyTakeoff() {
        if (!mc.thePlayer.onGround || !hasMovementInput() || hasCollisionAbove()) {
            return false;
        }
        if ((int) keepY.getInput() == KEEP_Y_TELLY && keepYState > 0) {
            return true;
        }
        return (int) tower.getInput() == TOWER_TELLY && Utils.isBindDown(mc.gameSettings.keyBindJump);
    }

    private float getMovementYaw() {
        float yaw = mc.thePlayer.rotationYaw;
        float forward = mc.thePlayer.movementInput == null ? 0.0F : mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput == null ? 0.0F : mc.thePlayer.movementInput.moveStrafe;
        if (forward < 0.0F) {
            yaw += 180.0F;
        }
        float factor = forward < 0.0F ? -0.5F : forward > 0.0F ? 0.5F : 1.0F;
        if (strafe > 0.0F) {
            yaw -= 90.0F * factor;
        }
        else if (strafe < 0.0F) {
            yaw += 90.0F * factor;
        }
        return yaw;
    }

    private float getSidewaysYaw(float yaw) {
        if (isDiagonalYaw(yaw)) {
            return yaw - 180.0F;
        }
        float factor = (yaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F;
        return yaw - 135.0F * factor;
    }

    private static boolean isDiagonalYaw(float angle) {
        float remainder = Math.abs(angle % 90.0F);
        return remainder > 20.0F && remainder < 70.0F;
    }

    private boolean acquirePlacement() {
        long tick = Utils.getBaseClientTick();
        PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.SCAFFOLD,
                mc.thePlayer, mc.theWorld, tick + 1L);
        placementLease = PlacementCoordinator.get().acquire(this, PlacementCoordinator.Priority.SCAFFOLD,
                mc.thePlayer, mc.theWorld, tick);
        return placementLease != null && placementLease.isActive();
    }

    private void releasePlacement(boolean restoreHotbar) {
        if (placementLease == null) {
            return;
        }
        placementLease.releaseHotbar(restoreHotbar);
        placementLease.release();
        placementLease = null;
    }

    private boolean isHoldingPlaceableBlock() {
        return isPlaceableBlockStack(mc.thePlayer.getHeldItem());
    }

    private static boolean isPlaceableBlockStack(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock
                && Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private boolean shouldDisableSprint() {
        if (keepYState > 0 || (int) tower.getInput() == TOWER_TELLY
                && mc.thePlayer.movementInput.jump) {
            return false;
        }
        return (int) sprint.getInput() == SPRINT_NONE;
    }

    private boolean hasMovementInput() {
        return mc.thePlayer.movementInput != null && (mc.thePlayer.movementInput.moveForward != 0.0F
                || mc.thePlayer.movementInput.moveStrafe != 0.0F);
    }

    private static boolean hasMovementInput(PrePlayerInputEvent event) {
        return event.getForward() != 0.0F || event.getStrafe() != 0.0F;
    }

    private boolean hasCollisionAbove() {
        AxisAlignedBB above = mc.thePlayer.getEntityBoundingBox().offset(0.0D, 0.1D, 0.0D);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, above).isEmpty();
    }

    private boolean hasNoSupportAt(double motionX, double motionZ) {
        AxisAlignedBB next = mc.thePlayer.getEntityBoundingBox().offset(motionX, -1.0D, motionZ);
        return mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, next).isEmpty();
    }

    private int countPlaceableHotbarBlocks() {
        int count = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isPlaceableBlockStack(stack)) {
                count += stack.stackSize;
            }
        }
        return count;
    }

    private void drawBlockCounter(ScaledResolution resolution, int count) {
        FontRenderer font = mc.fontRendererObj;
        String text = count + (count == 1 ? " block left" : " blocks left");
        float x = resolution.getScaledWidth() / 2.0F + font.FONT_HEIGHT * 1.5F;
        float y = resolution.getScaledHeight() / 2.0F - font.FONT_HEIGHT / 2.0F + 1.0F;
        GlStateManager.pushMatrix();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(770, 771);
        font.drawString(text, x, y, count > 0 ? 0xFFFFFFFF : 0xFFFF5555, true);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private static double distanceSquared(BlockPos from, BlockPos to) {
        double x = to.getX() - from.getX();
        double y = to.getY() - from.getY();
        double z = to.getZ() - from.getZ();
        return x * x + y * y + z * z;
    }

    private static float[] rotationsFromDelta(double x, double y, double z) {
        double distance = Math.sqrt(x * x + z * z);
        return new float[]{(float) Math.toDegrees(Math.atan2(-x, z)),
                MathHelper.clamp_float((float) -Math.toDegrees(Math.atan2(y, distance)), -90.0F, 90.0F)};
    }

    private static float[] quantize(float yaw, float pitch, float previousYaw, float previousPitch) {
        return RotationUtils.fixRotation(yaw, MathHelper.clamp_float(pitch, -90.0F, 90.0F),
                previousYaw, previousPitch);
    }

    private static final class BlockPlacementTarget {
        private final BlockPos position;
        private final EnumFacing face;

        private BlockPlacementTarget(BlockPos position, EnumFacing face) {
            this.position = position;
            this.face = face;
        }
    }

    private static final class RandomRange {
        private RandomRange() {
        }

        private static float floatBetween(float min, float max) {
            return min + (float) Math.random() * (max - min);
        }
    }
}
