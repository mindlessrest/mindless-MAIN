package mindless.module.impl.player;

import java.util.ArrayList;
import java.util.List;
import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.SliderSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.script.model.Simulation;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class LadderClutch extends Module {
    private static final int LOOK_AHEAD_TICKS = 40;
    private static final int GUIDE_TIMEOUT_TICKS = 26;
    private static final double MAX_HORIZONTAL_DISTANCE = 4.5D;
    private static final double MAX_PLACE_BELOW_FEET = 3.0D;
    private static final double[] HIT_OFFSETS = {0.5D, 0.35D, 0.65D, 0.2D, 0.8D};

    private final SliderSetting minimumDropDistance;
    private final SliderSetting rotationSpeed;
    private final List<Vec3> fallPath = new ArrayList<>();

    private PlacementLease placementLease;
    private PlacementPlan activePlan;
    private Stage stage = Stage.IDLE;
    private boolean fallQualified;
    private boolean placedThisTick;
    private int lastPlacementAttemptTick = Integer.MIN_VALUE;
    private int stageStartedTick = Integer.MIN_VALUE;
    private int guideStartedTick = Integer.MIN_VALUE;
    private String lastInvalidationReason = "idle";

    public LadderClutch() {
        super("Ladder Clutch", "Places a ladder catch during a dangerous fall.", category.player);
        minimumDropDistance = new SliderSetting(
                "Minimum height", 4.0D, 0.0D, 20.0D, 0.5D, "Minimum drop distance");
        minimumDropDistance.setSuffix(" blocks");
        this.registerSetting(minimumDropDistance);
        this.registerSetting(rotationSpeed = new SliderSetting(
                "Rotation speed", " degrees/tick", 90.0D, 10.0D, 180.0D, 5.0D));
        this.closetModule = true;
    }

    @Override
    public void onEnable() {
        fallQualified = false;
        placedThisTick = false;
        resetExecution("enabled");
    }

    @Override
    public void onDisable() {
        fallQualified = false;
        resetExecution("disabled");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreUpdate(PreUpdateEvent event) {
        placedThisTick = false;

        if (!isReady()) {
            fallQualified = false;
            resetExecution("module-not-ready");
            return;
        }
        if (shouldYieldToAutoClutch()) {
            resetExecution("auto-clutch-active");
            return;
        }
        if (!isFalling()) {
            fallQualified = false;
            resetExecution("fall-ended");
            return;
        }

        if (!fallQualified && (minimumDropDistance.getInput() == 0.0D
                || heightAboveGround() >= minimumDropDistance.getInput())) {
            fallQualified = true;
        }
        if (!fallQualified) {
            return;
        }

        if (activePlan == null) {
            PlacementPlan plan = buildPlan();
            if (plan != null) {
                activatePlan(plan);
            }
            return;
        }

        if (!isPlanInBounds(activePlan)) {
            resetExecution("plan-out-of-bounds");
            return;
        }
        if (stage == Stage.PLACE_SUPPORT && isSolidLadderSupport(activePlan.supportPos)) {
            advanceToLadderStage();
        }
        if (stage == Stage.PLACE_LADDER && isLadder(activePlan.ladderPos)) {
            enterGuideStage();
        }
        if (stage == Stage.GUIDE_ENTRY) {
            if (mc.thePlayer.isOnLadder() || occupies(activePlan.ladderPos)) {
                resetExecution("ladder-caught");
            } else if (mc.thePlayer.onGround || mc.thePlayer.ticksExisted - guideStartedTick > GUIDE_TIMEOUT_TICKS) {
                resetExecution("guide-ended");
            }
            return;
        }

        if (!isStageValid()) {
            resetExecution("stage-invalid");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isReady() || activePlan == null || stage == Stage.GUIDE_ENTRY || stage == Stage.IDLE) {
            return;
        }
        if (shouldYieldToAutoClutch()) {
            resetExecution("auto-clutch-active");
            return;
        }
        if (!isStageValid()) {
            resetExecution("stage-invalid");
            return;
        }

        BlockPos anchor = getCurrentAnchor();
        EnumFacing face = getCurrentFace();
        BlockPos placePos = getCurrentPlacePos();
        if (anchor == null || face == null || placePos == null) {
            resetExecution("missing-placement-target");
            return;
        }

        float baseYaw = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];
        RotationTarget target = findRotationTarget(anchor, face, placePos, baseYaw, basePitch);
        if (target == null) {
            resetExecution("no-placement-ray");
            return;
        }

        float[] rotated = stepTowards(baseYaw, basePitch, target.yaw, target.pitch);
        event.requestRotation(mindless.rotation.RotationSource.LADDER_CLUTCH, rotated[0], rotated[1]);
        RotationHelper.get().forceMovementFix = true;

        if (!isWithinPlacementBounds(placePos) || !acquirePlacement()) {
            return;
        }
        int slot = stage == Stage.PLACE_SUPPORT ? findSupportSlot() : findLadderSlot();
        if (slot == -1 || !placementLease.claimHotbar(PlacementRuntime.hotbar(), slot)) {
            resetExecution("missing-placement-item");
            return;
        }
        placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindAttack), false);
        placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindUseItem), false);

        MovingObjectPosition hit = RotationUtils.rayCastBlock(getPlacementReach(), rotated[0], rotated[1]);
        if (!isValidPlacementRay(hit, anchor, face, placePos)
                || placedThisTick || lastPlacementAttemptTick == mc.thePlayer.ticksExisted) {
            return;
        }

        final MovingObjectPosition placementHit = hit;
        lastPlacementAttemptTick = mc.thePlayer.ticksExisted;
        boolean accepted = placementLease.tryControllerAction(Utils.getBaseClientTick(),
                new PlacementLease.ControllerAction() {
                    @Override
                    public boolean run() {
                        return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                                mc.thePlayer.getHeldItem(), placementHit.getBlockPos(),
                                placementHit.sideHit, placementHit.hitVec);
                    }
                });
        if (!accepted) {
            return;
        }

        placedThisTick = true;
        mc.thePlayer.swingItem();
        mc.getItemRenderer().resetEquippedProgress();
        if (stage == Stage.PLACE_SUPPORT && isSolidLadderSupport(activePlan.supportPos)) {
            advanceToLadderStage();
        } else if (stage == Stage.PLACE_LADDER && isLadder(activePlan.ladderPos)) {
            enterGuideStage();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPrePlayerInput(PrePlayerInputEvent event) {
        if (!isReady() || activePlan == null || stage == Stage.IDLE || !isFalling()
                || shouldYieldToAutoClutch()) {
            return;
        }

        float yaw = RotationHelper.get().getServerYaw() != null
                ? RotationHelper.get().getServerYaw() : RotationUtils.serverRotations[0];
        float[] input = chooseSteeringInput(activePlan.ladderPos, yaw);
        event.setForward(input[0]);
        event.setStrafe(input[1]);
        event.setJump(false);
        event.setSneak(false);
        RotationHelper.get().setServerRelativeMovementInputs(true);
    }

    private void activatePlan(PlacementPlan plan) {
        activePlan = plan;
        stage = plan.requiresSupportPlacement ? Stage.PLACE_SUPPORT : Stage.PLACE_LADDER;
        stageStartedTick = mc.thePlayer.ticksExisted;
        guideStartedTick = Integer.MIN_VALUE;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
        lastInvalidationReason = "plan-ready";
    }

    private void advanceToLadderStage() {
        stage = Stage.PLACE_LADDER;
        stageStartedTick = mc.thePlayer.ticksExisted;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
    }

    private void enterGuideStage() {
        stage = Stage.GUIDE_ENTRY;
        guideStartedTick = mc.thePlayer.ticksExisted;
        releasePlacement();
    }

    private PlacementPlan buildPlan() {
        if (findLadderSlot() == -1) {
            return null;
        }

        Landing landing = predictLanding();
        if (landing == null) {
            return null;
        }

        PlacementPlan direct = findDirectPlan(landing);
        if (direct != null) {
            return direct;
        }
        return findSupportSlot() == -1 ? null : findGroundPlan(landing);
    }

    private Landing predictLanding() {
        fallPath.clear();
        Simulation simulation;
        try {
            simulation = Simulation.create();
        } catch (IllegalStateException ignored) {
            return null;
        }
        simulation.setJump(false);
        for (int tick = 0; tick <= LOOK_AHEAD_TICKS; tick++) {
            mindless.script.model.Vec3 simulatedPosition = simulation.getPosition();
            Vec3 position = new Vec3(simulatedPosition.x, simulatedPosition.y, simulatedPosition.z);
            fallPath.add(position);
            if (simulation.onGround()) {
                return new Landing(position, tick);
            }
            simulation.tick();
        }
        return null;
    }

    private PlacementPlan findDirectPlan(Landing landing) {
        PlacementPlan best = null;
        for (Vec3 point : fallPath) {
            BlockPos ladderPos = new BlockPos(point.xCoord, point.yCoord, point.zCoord);
            if (!fallPassesCell(ladderPos) || !isReplaceableOrLadder(ladderPos)) {
                continue;
            }
            for (EnumFacing face : EnumFacing.Plane.HORIZONTAL) {
                BlockPos supportPos = ladderPos.offset(face.getOpposite());
                if (!canUseAsLadderSupport(ladderPos, face, supportPos)) {
                    continue;
                }
                best = chooseBetterPlan(best, new PlacementPlan(ladderPos, supportPos, face,
                        false, planCost(ladderPos, landing, false)));
            }
        }
        return best;
    }

    private PlacementPlan findGroundPlan(Landing landing) {
        BlockPos center = new BlockPos(landing.position.xCoord,
                Math.ceil(landing.position.yCoord - 1.0E-7D), landing.position.zCoord);
        PlacementPlan best = null;
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                BlockPos ladderPos = center.add(x, 0, z);
                if (!BlockUtils.replaceable(ladderPos)) {
                    continue;
                }
                for (EnumFacing face : EnumFacing.Plane.HORIZONTAL) {
                    BlockPos supportPos = ladderPos.offset(face.getOpposite());
                    if (!BlockUtils.replaceable(supportPos)
                            || !isSolidLadderSupport(supportPos.down())
                            || playerWouldIntersect(supportPos)) {
                        continue;
                    }
                    best = chooseBetterPlan(best, new PlacementPlan(ladderPos, supportPos, face,
                            true, planCost(ladderPos, landing, true)));
                }
            }
        }
        return best;
    }

    private PlacementPlan chooseBetterPlan(PlacementPlan current, PlacementPlan candidate) {
        if (current == null || candidate.cost < current.cost
                || candidate.cost == current.cost && candidate.ladderPos.toLong() < current.ladderPos.toLong()) {
            return candidate;
        }
        return current;
    }

    private double planCost(BlockPos ladderPos, Landing landing, boolean requiresSupport) {
        double dx = ladderPos.getX() + 0.5D - landing.position.xCoord;
        double dz = ladderPos.getZ() + 0.5D - landing.position.zCoord;
        return dx * dx + dz * dz + (requiresSupport ? 0.25D : 0.0D) + landing.tick * 0.0001D;
    }

    private boolean fallPassesCell(BlockPos cell) {
        for (Vec3 point : fallPath) {
            if (point.yCoord < cell.getY()) {
                break;
            }
            if (point.yCoord < cell.getY() + 1.0D
                    && point.xCoord >= cell.getX() && point.xCoord < cell.getX() + 1.0D
                    && point.zCoord >= cell.getZ() && point.zCoord < cell.getZ() + 1.0D) {
                return true;
            }
        }
        return false;
    }

    private RotationTarget findRotationTarget(BlockPos anchor, EnumFacing face, BlockPos placePos,
                                              float baseYaw, float basePitch) {
        RotationTarget best = null;
        RotationTarget fallback = null;
        for (double u : HIT_OFFSETS) {
            for (double v : HIT_OFFSETS) {
                Vec3 hitVec = faceHitVec(anchor, face, u, v);
                float[] rotation = rotationsTo(hitVec, baseYaw, basePitch);
                double score = Math.abs(MathHelper.wrapAngleTo180_float(rotation[0] - baseYaw))
                        + Math.abs(rotation[1] - basePitch);
                if (fallback == null || score < fallback.score) {
                    fallback = new RotationTarget(rotation[0], rotation[1], score);
                }
                MovingObjectPosition hit = RotationUtils.rayCastBlock(getPlacementReach(), rotation[0], rotation[1]);
                if (!isValidPlacementRay(hit, anchor, face, placePos)) {
                    continue;
                }
                if (best == null || score < best.score) {
                    best = new RotationTarget(rotation[0], rotation[1], score);
                }
            }
        }
        return best != null ? best : fallback;
    }

    private float[] rotationsTo(Vec3 target, float baseYaw, float basePitch) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0F);
        double dx = target.xCoord - eye.xCoord;
        double dy = target.yCoord - eye.yCoord;
        double dz = target.zCoord - eye.zCoord;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = horizontal < 1.0E-6D ? baseYaw : (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        yaw = baseYaw + MathHelper.wrapAngleTo180_float(yaw - baseYaw);
        pitch = MathHelper.clamp_float(pitch, -90.0F, 90.0F);
        return RotationUtils.fixRotation(yaw, pitch, baseYaw, basePitch);
    }

    private float[] stepTowards(float baseYaw, float basePitch, float targetYaw, float targetPitch) {
        float limit = (float) rotationSpeed.getInput();
        float yaw = baseYaw + MathHelper.clamp_float(
                MathHelper.wrapAngleTo180_float(targetYaw - baseYaw), -limit, limit);
        float pitch = basePitch + MathHelper.clamp_float(targetPitch - basePitch, -limit, limit);
        return RotationUtils.fixRotation(yaw, pitch, baseYaw, basePitch);
    }

    private boolean isValidPlacementRay(MovingObjectPosition hit, BlockPos anchor, EnumFacing face,
                                        BlockPos placePos) {
        return hit != null && hit.getBlockPos() != null && hit.sideHit == face
                && anchor.equals(hit.getBlockPos()) && placePos.equals(anchor.offset(face));
    }

    private boolean acquirePlacement() {
        if (isPlacementActive()) {
            return true;
        }
        long tick = Utils.getBaseClientTick();
        PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.CLUTCH,
                mc.thePlayer, mc.theWorld, tick + 1L);
        placementLease = PlacementCoordinator.get().acquire(this, PlacementCoordinator.Priority.CLUTCH,
                mc.thePlayer, mc.theWorld, tick);
        return isPlacementActive();
    }

    private void releasePlacement() {
        PlacementCoordinator.get().cancel(this);
        placementLease = null;
    }

    private boolean isPlacementActive() {
        return placementLease != null && placementLease.isActive();
    }

    private boolean isStageValid() {
        if (activePlan == null || !isPlanInBounds(activePlan)) {
            return false;
        }
        if (stage == Stage.PLACE_SUPPORT) {
            return findLadderSlot() != -1 && findSupportSlot() != -1
                    && BlockUtils.replaceable(activePlan.supportPos)
                    && isSolidLadderSupport(activePlan.supportPos.down())
                    && !playerWouldIntersect(activePlan.supportPos);
        }
        if (stage == Stage.PLACE_LADDER) {
            return findLadderSlot() != -1 && isReplaceableOrLadder(activePlan.ladderPos)
                    && canUseAsLadderSupport(activePlan.ladderPos, activePlan.ladderFace, activePlan.supportPos);
        }
        return stage == Stage.GUIDE_ENTRY && isLadder(activePlan.ladderPos);
    }

    private boolean isPlanInBounds(PlacementPlan plan) {
        if (plan == null) {
            return false;
        }
        double dx = plan.ladderPos.getX() + 0.5D - mc.thePlayer.posX;
        double dz = plan.ladderPos.getZ() + 0.5D - mc.thePlayer.posZ;
        if (dx * dx + dz * dz > MAX_HORIZONTAL_DISTANCE * MAX_HORIZONTAL_DISTANCE) {
            return false;
        }
        if (stage == Stage.GUIDE_ENTRY) {
            double delta = getFeetY() - plan.ladderPos.getY();
            return delta >= -1.0D && delta <= MAX_PLACE_BELOW_FEET;
        }
        return getFeetY() >= plan.ladderPos.getY() - 1.0D;
    }

    private boolean isWithinPlacementBounds(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        double dx = Math.abs(pos.getX() + 0.5D - mc.thePlayer.posX);
        double dz = Math.abs(pos.getZ() + 0.5D - mc.thePlayer.posZ);
        double below = getFeetY() - pos.getY();
        return dx <= MAX_HORIZONTAL_DISTANCE && dz <= MAX_HORIZONTAL_DISTANCE
                && below > -1.0D && below <= MAX_PLACE_BELOW_FEET;
    }

    private BlockPos getCurrentAnchor() {
        if (activePlan == null) {
            return null;
        }
        return stage == Stage.PLACE_SUPPORT ? activePlan.supportPos.down() : activePlan.supportPos;
    }

    private EnumFacing getCurrentFace() {
        if (activePlan == null) {
            return null;
        }
        return stage == Stage.PLACE_SUPPORT ? EnumFacing.UP : activePlan.ladderFace;
    }

    private BlockPos getCurrentPlacePos() {
        if (activePlan == null) {
            return null;
        }
        return stage == Stage.PLACE_SUPPORT ? activePlan.supportPos : activePlan.ladderPos;
    }

    private boolean canUseAsLadderSupport(BlockPos ladderPos, EnumFacing face, BlockPos supportPos) {
        return ladderPos != null && face != null && supportPos != null
                && isSolidLadderSupport(supportPos) && isReplaceableOrLadder(ladderPos)
                && Blocks.ladder.canPlaceBlockOnSide(mc.theWorld, ladderPos, face);
    }

    private boolean isSolidLadderSupport(BlockPos pos) {
        if (pos == null || BlockUtils.replaceable(pos) || BlockUtils.isInteractable(BlockUtils.getBlock(pos))) {
            return false;
        }
        Block block = BlockUtils.getBlock(pos);
        return block != null && block != Blocks.air && block.isFullCube();
    }

    private boolean isReplaceableOrLadder(BlockPos pos) {
        return pos != null && (BlockUtils.replaceable(pos) || isLadder(pos));
    }

    private boolean isLadder(BlockPos pos) {
        return pos != null && BlockUtils.getBlock(pos) == Blocks.ladder;
    }

    private boolean playerWouldIntersect(BlockPos pos) {
        AxisAlignedBB placement = new AxisAlignedBB(pos, pos.add(1, 1, 1));
        AxisAlignedBB body = mc.thePlayer.getEntityBoundingBox();
        return body.intersectsWith(placement)
                || body.offset(mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ)
                        .intersectsWith(placement);
    }

    private boolean occupies(BlockPos ladderPos) {
        return ladderPos != null && mc.thePlayer.isOnLadder()
                && MathHelper.floor_double(mc.thePlayer.posX) == ladderPos.getX()
                && MathHelper.floor_double(getFeetY()) == ladderPos.getY()
                && MathHelper.floor_double(mc.thePlayer.posZ) == ladderPos.getZ();
    }

    private int findLadderSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isLadderStack(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private int findSupportSlot() {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isSupportStack(stack)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean isLadderStack(ItemStack stack) {
        return stack != null && stack.stackSize > 0 && stack.getItem() instanceof ItemBlock
                && ((ItemBlock) stack.getItem()).getBlock() == Blocks.ladder;
    }

    private boolean isSupportStack(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0 || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }
        ItemBlock itemBlock = (ItemBlock) stack.getItem();
        Block block = itemBlock.getBlock();
        return block != null && block != Blocks.ladder && block.isFullCube() && Utils.canBePlaced(itemBlock);
    }

    private float[] chooseSteeringInput(BlockPos target, float yaw) {
        double desiredX = target.getX() + 0.5D - mc.thePlayer.posX;
        double desiredZ = target.getZ() + 0.5D - mc.thePlayer.posZ;
        double length = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        if (length < 0.05D) {
            return new float[]{0.0F, 0.0F};
        }
        desiredX /= length;
        desiredZ /= length;
        double yawRadians = Math.toRadians(yaw);
        double cos = Math.cos(yawRadians);
        double sin = Math.sin(yawRadians);
        float bestForward = 0.0F;
        float bestStrafe = 0.0F;
        double bestScore = -Double.MAX_VALUE;
        for (float forward = -1.0F; forward <= 1.0F; forward += 1.0F) {
            for (float strafe = -1.0F; strafe <= 1.0F; strafe += 1.0F) {
                if (forward == 0.0F && strafe == 0.0F) {
                    continue;
                }
                double moveX = strafe * cos - forward * sin;
                double moveZ = forward * cos + strafe * sin;
                double moveLength = Math.sqrt(moveX * moveX + moveZ * moveZ);
                double score = (moveX * desiredX + moveZ * desiredZ) / moveLength;
                if (score > bestScore) {
                    bestScore = score;
                    bestForward = forward;
                    bestStrafe = strafe;
                }
            }
        }
        return new float[]{bestForward, bestStrafe};
    }

    private double heightAboveGround() {
        AxisAlignedBB feet = mc.thePlayer.getEntityBoundingBox();
        AxisAlignedBB column = new AxisAlignedBB(feet.minX + 1.0E-7D, -1.0D, feet.minZ + 1.0E-7D,
                feet.maxX - 1.0E-7D, feet.minY + 1.0E-7D, feet.maxZ - 1.0E-7D);
        double surface = Double.NEGATIVE_INFINITY;
        List<AxisAlignedBB> boxes = new ArrayList<>();
        for (int x = MathHelper.floor_double(column.minX); x <= MathHelper.floor_double(column.maxX); x++) {
            for (int z = MathHelper.floor_double(column.minZ); z <= MathHelper.floor_double(column.maxZ); z++) {
                for (int y = MathHelper.floor_double(feet.minY); y >= 0 && y + 1.5D > surface; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    net.minecraft.block.state.IBlockState state = mc.theWorld.getBlockState(pos);
                    boxes.clear();
                    state.getBlock().addCollisionBoxesToList(mc.theWorld, pos, state, column, boxes, mc.thePlayer);
                    for (AxisAlignedBB box : boxes) {
                        if (box.maxY <= feet.minY + 1.0E-7D) {
                            surface = Math.max(surface, box.maxY);
                        }
                    }
                }
            }
        }
        return feet.minY - surface;
    }

    private boolean isFalling() {
        EntityPlayerSP player = mc.thePlayer;
        return player != null && !player.onGround && !player.isOnLadder() && player.motionY < 0.0D
                && !player.isInWater() && !player.isInLava() && !player.capabilities.isFlying
                && !player.isSpectator() && !player.noClip && player.isEntityAlive()
                && !player.isRiding() && !player.isUsingItem();
    }

    private boolean isReady() {
        return isEnabled() && Utils.nullCheck() && mc.playerController != null && !mc.isGamePaused()
                && mc.currentScreen == null && mc.thePlayer.movementInput != null;
    }

    private boolean shouldYieldToAutoClutch() {
        return ModuleManager.clutch != null && ModuleManager.clutch.isActiveWindow();
    }

    private double getFeetY() {
        return mc.thePlayer.getEntityBoundingBox().minY;
    }

    private double getPlacementReach() {
        return mc.playerController.getBlockReachDistance();
    }

    private Vec3 faceHitVec(BlockPos block, EnumFacing face, double u, double v) {
        double first = MathHelper.clamp_double(u, 0.01D, 0.99D);
        double second = MathHelper.clamp_double(v, 0.01D, 0.99D);
        switch (face) {
            case DOWN:
                return new Vec3(block.getX() + first, block.getY() + 0.01D, block.getZ() + second);
            case UP:
                return new Vec3(block.getX() + first, block.getY() + 0.99D, block.getZ() + second);
            case NORTH:
                return new Vec3(block.getX() + first, block.getY() + second, block.getZ() + 0.01D);
            case SOUTH:
                return new Vec3(block.getX() + first, block.getY() + second, block.getZ() + 0.99D);
            case WEST:
                return new Vec3(block.getX() + 0.01D, block.getY() + second, block.getZ() + first);
            case EAST:
                return new Vec3(block.getX() + 0.99D, block.getY() + second, block.getZ() + first);
            default:
                return null;
        }
    }

    private void resetExecution(String reason) {
        releasePlacement();
        activePlan = null;
        stage = Stage.IDLE;
        placedThisTick = false;
        lastPlacementAttemptTick = Integer.MIN_VALUE;
        stageStartedTick = Integer.MIN_VALUE;
        guideStartedTick = Integer.MIN_VALUE;
        lastInvalidationReason = reason;
    }

    private enum Stage {
        IDLE,
        PLACE_SUPPORT,
        PLACE_LADDER,
        GUIDE_ENTRY
    }

    private static final class Landing {
        private final Vec3 position;
        private final int tick;

        private Landing(Vec3 position, int tick) {
            this.position = position;
            this.tick = tick;
        }
    }

    private static final class PlacementPlan {
        private final BlockPos ladderPos;
        private final BlockPos supportPos;
        private final EnumFacing ladderFace;
        private final boolean requiresSupportPlacement;
        private final double cost;

        private PlacementPlan(BlockPos ladderPos, BlockPos supportPos, EnumFacing ladderFace,
                              boolean requiresSupportPlacement, double cost) {
            this.ladderPos = ladderPos;
            this.supportPos = supportPos;
            this.ladderFace = ladderFace;
            this.requiresSupportPlacement = requiresSupportPlacement;
            this.cost = cost;
        }
    }

    private static final class RotationTarget {
        private final float yaw;
        private final float pitch;
        private final double score;

        private RotationTarget(float yaw, float pitch, double score) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.score = score;
        }
    }
}
