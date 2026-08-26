package mindless.module.impl.combat;

import mindless.utility.RenderUtils;
import mindless.Raven;
import mindless.event.AttackEvent;
import mindless.event.ClientRotationEvent;
import mindless.event.GameTickEvent;
import mindless.event.PostPlayerInputEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.RightClickMouseEvent;
import mindless.event.SendPacketEvent;
import mindless.helper.RotationHelper;
import mindless.lag.api.EnumLagDirection;
import mindless.lag.api.LagRequest;
import mindless.lag.timeout.ModuleBackedTimeout;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.CombatTargeting;
import mindless.utility.ModuleUtils;
import mindless.utility.PacketUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Displace extends Module {
    private static final int DISPLACE_WINDOW_TICKS = 10;
    private static final int VOID_SCAN_DIRECTIONS = 48;
    private static final double VOID_SCAN_STEP = 0.5D;
    private static final double VOID_COLLISION_STEP = 0.25D;
    private static final double VOID_COLLISION_INSET = 0.03D;
    private static final double VOID_REFINEMENT_STEP = 1.5D;
    private static final double VOID_SCORE_EPSILON = 1.0E-4D;
    private static final long VOID_DEBUG_DURATION_MS = 30_000L;
    private static final double VOID_DEBUG_PROBE_Y_OFFSET = 0.08D;
    private static final int DISPLACEMENT_LOCK_IDLE_TICKS = 20;
    private static final long ARROW_FADE_MS = 250L;
    private static final double ARROW_BASE_GAP = 0.3D;
    private static final long ARROW_NUDGE_MS = 320L;
    private static final double ARROW_NUDGE_DISTANCE = 0.3D;
    private static final double ARROW_NUDGE_DECAY = 6.0D;
    private static final double ARROW_NUDGE_FADE_START = 0.58D;
    private static final double ARROW_NUDGE_FADE_DECAY = 5.0D;
    private static final int OVERRIDE_MAX_FLICK_TICKS = 5;
    private static final int OVERRIDE_ATTACK_TICKS = 2;
    private static final int OVERRIDE_RESTORE_TICKS = 2;
    private static final float OVERRIDE_FLICK_TOLERANCE = 6.0F;
    private static final double OVERRIDE_TARGET_RANGE_SQ = 25.0D;

    private final SliderSetting mode;
    private final SliderSetting yawOffset;
    private final SliderSetting scanRadius;
    private final SliderSetting delay;
    private final SliderSetting direction;
    private final ButtonSetting findVoid;
    private final ButtonSetting blink;
    private final ButtonSetting overrideAttack;
    private final ButtonSetting renderArrow;
    private final ButtonSetting onlyKnockbackItems;
    private final ButtonSetting weaponOnly;

    private boolean displaceThisTick = false;
    private boolean active = false;
    private boolean hasKB = false;
    private boolean compensateNextTick = false;
    private boolean displaceLeft = false;
    private boolean wasDisplacingLastTick = false;
    private boolean releaseBlinkNextGameTick = false;
    private EntityPlayer arrowPlayer;
    private float arrowYaw;
    private float arrowFadeStartAlpha;
    private float arrowFadeEndAlpha;
    private long arrowFadeStartMs;
    private boolean arrowVisible;
    private long arrowNudgeStartMs = -1L;
    private int arrowNudgePlayerId = -1;
    private int tickCounter;
    private final Map<Integer, Integer> targetWindowStartTicks = new HashMap<>();
    private final Map<Integer, DisplacementLock> targetDisplacementLocks = new HashMap<>();
    /** Scratch positions and list for the void sweep; see getVoidPathBlockedDistance. */
    private final BlockPos.MutableBlockPos voidMinCorner = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos voidMaxCorner = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos voidScanPos = new BlockPos.MutableBlockPos();

    private LagRequest outboundBlink;
    private VoidDebugScan latestVoidDebugScan;
    private VoidDebugScan frozenVoidDebugScan;
    private long frozenVoidDebugExpiresAtMs;
    private OverrideAttackState overrideAttackState = OverrideAttackState.IDLE;
    private EntityPlayer overrideTarget;
    private float overrideTargetYaw;
    private float overrideTargetPitch;
    private float overrideFlickYaw;
    private float overrideFlickOffset;
    private boolean overrideAbsoluteFlickYaw;
    private boolean overrideAwayPacketSent;
    private boolean overrideAttackInProgress;
    private boolean overrideAttackPacketSent;
    private boolean suppressUseForOverrideAttackTick;
    private int overrideStateTicks;
    private long lastOverrideFlickMs = -1L;

    private static final String[] MODES = {"Offset", "Void"};
    private static final String[] DIRECTIONS = {"Left", "Right"};

    private enum OverrideAttackState {
        IDLE,
        FLICKING_AWAY,
        ATTACKING,
        RESTORING
    }

    private static final class DisplacementLock {
        private final float yaw;
        private int lastUseTick;
        private boolean airborne;

        private DisplacementLock(float yaw, int lastUseTick, boolean airborne) {
            this.yaw = yaw;
            this.lastUseTick = lastUseTick;
            this.airborne = airborne;
        }
    }

    public Displace() {
        super("Displace", category.combat);
        this.liteModule = true;
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(yawOffset = new SliderSetting("Yaw offset", 90, 0, 180, 1));
        this.registerSetting(scanRadius = new SliderSetting("Scan radius",  " block", 6.0D, 1.0D, 12.0D, 0.5D));
        this.registerSetting(delay = new SliderSetting("Delay", "ms", 0.0D, 0.0D, 500.0D, 50.0D));
        this.registerSetting(direction = new SliderSetting("Direction", 0, DIRECTIONS));
        this.registerSetting(findVoid = new ButtonSetting("Find void", false));
        this.registerSetting(blink = new ButtonSetting("Blink", false));
        this.registerSetting(onlyKnockbackItems = new ButtonSetting("Only knockback items", false, "Has knockback"));
        this.registerSetting(overrideAttack = new ButtonSetting("Override attack", false));
        this.registerSetting(renderArrow = new ButtonSetting("Render arrow", true));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));
    }

    @Override
    public void guiUpdate() {
        boolean offsetMode = !isVoidMode();
        direction.setVisible(offsetMode, this);
        findVoid.setVisible(offsetMode, this);
        scanRadius.setVisible(!offsetMode, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void onEnable() {
        displaceThisTick = false;
        active = false;
        hasKB = false;
        compensateNextTick = false;
        wasDisplacingLastTick = false;
        releaseBlinkNextGameTick = false;
        clearArrow();
        clearVoidDebugState();
        tickCounter = 0;
        targetWindowStartTicks.clear();
        targetDisplacementLocks.clear();
        resetOverrideAttackState();
        lastOverrideFlickMs = -1L;
        releaseBlink();
    }

    @Override
    public void onDisable() {
        active = false;
        compensateNextTick = false;
        wasDisplacingLastTick = false;
        releaseBlinkNextGameTick = false;
        clearArrow();
        clearVoidDebugState();
        targetWindowStartTicks.clear();
        targetDisplacementLocks.clear();
        resetOverrideAttackState();
    }

    @Override
    public void guiButtonToggled(ButtonSetting button) {
        if (button == overrideAttack) {
            resetOverrideAttackState();
            releaseBlinkNextGameTick = false;
        }
    }

    private static int msToTicks(double ms) {
        if (ms <= 0.0D) {
            return 0;
        }
        return (int) Math.ceil(ms / 50.0D);
    }

    private boolean anyMovementKey() {
        return mc.gameSettings.keyBindForward.isKeyDown()
                || mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown()
                || mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean isVoidMode() {
        return mode.getInput() == 1.0D;
    }

    private boolean tryFindVoidDirection(EntityPlayer target) {
        double dx = target.posX - mc.thePlayer.posX;
        double dz = target.posZ - mc.thePlayer.posZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.001) return false;

        dx /= dist;
        dz /= dist;

        double rightX = -dz;
        double rightZ = dx;

        double eyeY = target.posY + (double) target.getEyeHeight();

        int leftVoidCount = 0;
        int rightVoidCount = 0;

        for (int i = 1; i <= 12; i++) {
            double off = i * 0.5;

            double rx = target.posX + rightX * off;
            double rz = target.posZ + rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(rx, eyeY, rz), new Vec3(rx, eyeY - 10, rz)) == null) {
                rightVoidCount++;
            }

            double lx = target.posX - rightX * off;
            double lz = target.posZ - rightZ * off;
            if (mc.theWorld.rayTraceBlocks(new Vec3(lx, eyeY, lz), new Vec3(lx, eyeY - 10, lz)) == null) {
                leftVoidCount++;
            }
        }

        if (leftVoidCount == 0 && rightVoidCount == 0) return false;

        if (leftVoidCount != rightVoidCount) {
            displaceLeft = leftVoidCount > rightVoidCount;
        }
        return true;
    }

    private Float findBestVoidYaw(EntityPlayer target, float playerYaw) {
        if (target == null || mc.thePlayer == null || mc.theWorld == null) {
            return null;
        }

        float offset = (float) yawOffset.getInput();
        float preferredYawPositive = MathHelper.wrapAngleTo180_float(playerYaw + offset);
        float preferredYawNegative = MathHelper.wrapAngleTo180_float(playerYaw - offset);
        double scanAngleStep = 360.0D / (double) VOID_SCAN_DIRECTIONS;
        double scanDistance = scanRadius.getInput();
        Map<Long, Boolean> voidColumns = new HashMap<>();
        Map<Long, VoidNeighborhood> voidNeighborhoods = new HashMap<>();
        VoidDebugScan debugScan = Raven.DEBUG
                ? new VoidDebugScan(mc.theWorld, target.getEntityId(), target.posX, target.posY, target.posZ,
                scanDistance, preferredYawPositive, preferredYawNegative)
                : null;
        if (debugScan == null) {
            latestVoidDebugScan = null;
        }
        VoidYawCandidate best = null;

        for (int directionIndex = 0; directionIndex < VOID_SCAN_DIRECTIONS; directionIndex++) {
            float candidateYaw = MathHelper.wrapAngleTo180_float(preferredYawPositive
                    + (float) (directionIndex * scanAngleStep));
            best = selectBetterVoidYaw(best, candidateYaw, preferredYawPositive, preferredYawNegative,
                    target, scanDistance,
                    voidColumns, voidNeighborhoods, debugScan);
        }

        if (best == null) {
            latestVoidDebugScan = debugScan;
            return null;
        }

        float coarseYaw = best.yaw;
        for (double refinementOffset = -scanAngleStep + VOID_REFINEMENT_STEP;
             refinementOffset < scanAngleStep;
             refinementOffset += VOID_REFINEMENT_STEP) {
            if (Math.abs(refinementOffset) < VOID_SCORE_EPSILON) {
                continue;
            }
            float candidateYaw = MathHelper.wrapAngleTo180_float(coarseYaw + (float) refinementOffset);
            best = selectBetterVoidYaw(best, candidateYaw, preferredYawPositive, preferredYawNegative,
                    target, scanDistance,
                    voidColumns, voidNeighborhoods, debugScan);
        }

        if (debugScan != null) {
            debugScan.bestCandidate = best.debugCandidate;
            latestVoidDebugScan = debugScan;
        }
        return best.yaw;
    }

    private VoidYawCandidate selectBetterVoidYaw(VoidYawCandidate currentBest, float candidateYaw,
                                                  float preferredYawPositive, float preferredYawNegative,
                                                  EntityPlayer target, double scanDistance,
                                                  Map<Long, Boolean> voidColumns,
                                                  Map<Long, VoidNeighborhood> voidNeighborhoods,
                                                  VoidDebugScan debugScan) {
        double radians = Math.toRadians(candidateYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double positiveDistance = Math.abs(MathHelper.wrapAngleTo180_float(
                candidateYaw - preferredYawPositive));
        double negativeDistance = Math.abs(MathHelper.wrapAngleTo180_float(
                candidateYaw - preferredYawNegative));
        double priorityDistance = Math.min(positiveDistance, negativeDistance);
        VoidDebugCandidate debugCandidate = debugScan == null
                ? null
                : new VoidDebugCandidate(candidateYaw, forwardX, forwardZ, priorityDistance);
        VoidPathScore pathScore = scoreVoidPath(target, forwardX, forwardZ, scanDistance,
                voidColumns, voidNeighborhoods, debugCandidate);
        if (debugCandidate != null) {
            debugCandidate.pathScore = pathScore;
            debugScan.candidates.add(debugCandidate);
        }
        if (pathScore == null) {
            return currentBest;
        }

        VoidYawCandidate candidate = new VoidYawCandidate(candidateYaw, pathScore, priorityDistance,
                debugCandidate);
        if (currentBest == null || candidate.compareTo(currentBest) > 0) {
            return candidate;
        }
        return currentBest;
    }

    private VoidPathScore scoreVoidPath(EntityPlayer target, double forwardX, double forwardZ,
                                        double scanDistance, Map<Long, Boolean> voidColumns,
                                        Map<Long, VoidNeighborhood> voidNeighborhoods,
                                        VoidDebugCandidate debugCandidate) {
        double sideX = -forwardZ;
        double sideZ = forwardX;
        double sideOffset = Math.max(0.2D, (double) target.width * 0.45D);
        double checkedForward = 0.0D;
        double pathQuality = 0.0D;
        int consecutiveCenterVoid = 0;
        int longestCenterVoidRun = 0;
        int bestNeighborhoodQuality = -1;
        int bestDestinationWidth = 0;
        double bestDestinationDistance = Double.MAX_VALUE;
        double bestDestinationX = 0.0D;
        double bestDestinationZ = 0.0D;
        VoidNeighborhood bestNeighborhood = null;
        AxisAlignedBB collisionBox = target.getEntityBoundingBox().contract(
                VOID_COLLISION_INSET, 0.001D, VOID_COLLISION_INSET);

        for (double forward = VOID_SCAN_STEP;
             forward <= scanDistance + VOID_SCORE_EPSILON;
             forward += VOID_SCAN_STEP) {
            double blockedDistance = getVoidPathBlockedDistance(target, collisionBox, forwardX, forwardZ,
                    checkedForward, forward);
            if (blockedDistance >= 0.0D) {
                if (debugCandidate != null) {
                    debugCandidate.blockedDistance = blockedDistance;
                }
                break;
            }
            checkedForward = forward;
            if (debugCandidate != null) {
                debugCandidate.reachedDistance = forward;
            }

            double centerX = target.posX + forwardX * forward;
            double centerZ = target.posZ + forwardZ * forward;
            boolean centerVoid = isVoidColumn(centerX, target.posY, centerZ, voidColumns);
            boolean leftVoid = isVoidColumn(centerX - sideX * sideOffset, target.posY,
                    centerZ - sideZ * sideOffset, voidColumns);
            boolean rightVoid = isVoidColumn(centerX + sideX * sideOffset, target.posY,
                    centerZ + sideZ * sideOffset, voidColumns);
            double distanceWeight = scanDistance + VOID_SCAN_STEP - forward;

            if (debugCandidate != null) {
                debugCandidate.probes.add(new VoidDebugProbe(
                        centerX - sideX * sideOffset, centerZ - sideZ * sideOffset, leftVoid,
                        centerX, centerZ, centerVoid,
                        centerX + sideX * sideOffset, centerZ + sideZ * sideOffset, rightVoid
                ));
            }

            if (leftVoid) {
                pathQuality += distanceWeight;
            }
            if (rightVoid) {
                pathQuality += distanceWeight;
            }
            if (centerVoid) {
                consecutiveCenterVoid++;
                longestCenterVoidRun = Math.max(longestCenterVoidRun, consecutiveCenterVoid);
                pathQuality += distanceWeight * 1.6D + (double) consecutiveCenterVoid * 2.0D;

                VoidNeighborhood neighborhood = getVoidNeighborhood(centerX, target.posY, centerZ,
                        voidColumns, voidNeighborhoods);
                int neighborhoodQuality = neighborhood.quality;
                int destinationWidth = 1 + (leftVoid ? 1 : 0) + (rightVoid ? 1 : 0);
                if (neighborhoodQuality > bestNeighborhoodQuality
                        || (neighborhoodQuality == bestNeighborhoodQuality
                        && destinationWidth > bestDestinationWidth)
                        || (neighborhoodQuality == bestNeighborhoodQuality
                        && destinationWidth == bestDestinationWidth
                        && forward < bestDestinationDistance)) {
                    bestNeighborhoodQuality = neighborhoodQuality;
                    bestDestinationWidth = destinationWidth;
                    bestDestinationDistance = forward;
                    bestDestinationX = centerX;
                    bestDestinationZ = centerZ;
                    bestNeighborhood = neighborhood;
                }
            } else {
                consecutiveCenterVoid = 0;
            }
        }

        if (bestNeighborhoodQuality < 0) {
            return null;
        }

        return new VoidPathScore(bestNeighborhood.immediateVoidNeighbors,
                bestNeighborhood.extendedVoidNeighbors, bestDestinationWidth,
                longestCenterVoidRun, pathQuality, bestDestinationDistance,
                bestDestinationX, bestDestinationZ, bestNeighborhood.blockX, bestNeighborhood.blockZ,
                bestNeighborhood.voidMask);
    }

    private VoidNeighborhood getVoidNeighborhood(double x, double y, double z,
                                                  Map<Long, Boolean> voidColumns,
                                                  Map<Long, VoidNeighborhood> voidNeighborhoods) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        VoidNeighborhood cached = voidNeighborhoods.get(columnKey);
        if (cached != null) {
            return cached;
        }

        int immediateVoidNeighbors = 0;
        int extendedVoidNeighbors = 0;
        long voidMask = 0L;
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int radius = Math.max(Math.abs(offsetX), Math.abs(offsetZ));
                if (radius == 0) {
                    continue;
                }

                if (isVoidColumn(blockX + offsetX + 0.5D, y, blockZ + offsetZ + 0.5D, voidColumns)) {
                    int bitIndex = (offsetX + 2) * 5 + offsetZ + 2;
                    voidMask |= 1L << bitIndex;
                    if (radius == 1) {
                        immediateVoidNeighbors++;
                    } else {
                        extendedVoidNeighbors++;
                    }
                }
            }
        }

        int quality = immediateVoidNeighbors * 100 + extendedVoidNeighbors;
        VoidNeighborhood neighborhood = new VoidNeighborhood(blockX, blockZ, immediateVoidNeighbors,
                extendedVoidNeighbors, quality, voidMask);
        voidNeighborhoods.put(columnKey, neighborhood);
        return neighborhood;
    }

    /**
     * Whether terrain intersects the box, stopping at the first block that does.
     *
     * <p>This replaces {@code World.getCollidingBoundingBoxes}, which builds a fresh list of every
     * colliding box in range and was only ever asked whether that list was empty. The sweep runs
     * forty-eight coarse yaws plus refinement, roughly fifty-seven candidates, each stepping a
     * quarter block out to the scan radius -- about fourteen hundred of those calls per attack. At
     * fighting click rates that is fifteen to twenty thousand list allocations a second, which a
     * flight recording caught as by far the most expensive thing the client was doing.
     *
     * <p>Same block range and same per-block test as vanilla, but it returns the moment anything
     * collides and reuses one list, so a blocked step usually costs a single block lookup and an
     * open one allocates nothing at all.
     *
     * <p>One deliberate difference: vanilla's version also collects entity boxes, so another
     * player standing near the path counted as blocking it. Terrain is what decides whether there
     * is a gap to knock someone into, and a bystander moving through should not change the yaw
     * that gets picked.
     */
    private boolean isTerrainBlocking(AxisAlignedBB box) {
        int minX = MathHelper.floor_double(box.minX);
        int maxX = MathHelper.floor_double(box.maxX + 1.0D);
        int minY = MathHelper.floor_double(box.minY);
        int maxY = MathHelper.floor_double(box.maxY + 1.0D);
        int minZ = MathHelper.floor_double(box.minZ);
        int maxZ = MathHelper.floor_double(box.maxZ + 1.0D);

        for (int blockX = minX; blockX < maxX; blockX++) {
            for (int blockZ = minZ; blockZ < maxZ; blockZ++) {
                if (!mc.theWorld.isBlockLoaded(voidScanPos.set(blockX, 64, blockZ))) {
                    continue;
                }

                for (int blockY = minY - 1; blockY < maxY; blockY++) {
                    voidScanPos.set(blockX, blockY, blockZ);
                    IBlockState state = mc.theWorld.getBlockState(voidScanPos);
                    AxisAlignedBB blockBB = state.getBlock().getCollisionBoundingBox(
                            mc.theWorld, voidScanPos, state);
                    if (blockBB != null && box.intersectsWith(blockBB)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double getVoidPathBlockedDistance(EntityPlayer target, AxisAlignedBB collisionBox,
                                              double forwardX, double forwardZ,
                                              double fromForward, double toForward) {
        // Two mutable positions reused for the whole sweep.
        //
        // This is the hottest loop in the client when Find void is on: a flight recording put
        // 2,439 samples in this module against 6 in KillAura, and 426 of 451 samples inside event
        // dispatch landed in this method. It runs for every candidate yaw at a quarter-block step,
        // and it was allocating two BlockPos per step purely to ask whether a chunk was loaded.
        for (double forward = fromForward + VOID_COLLISION_STEP;
             forward <= toForward + VOID_SCORE_EPSILON;
             forward += VOID_COLLISION_STEP) {
            AxisAlignedBB checkBox = collisionBox.offset(forwardX * forward, 0.0D, forwardZ * forward);

            voidMinCorner.set(MathHelper.floor_double(checkBox.minX),
                    MathHelper.floor_double(checkBox.minY),
                    MathHelper.floor_double(checkBox.minZ));
            voidMaxCorner.set(MathHelper.floor_double(checkBox.maxX),
                    MathHelper.floor_double(checkBox.maxY),
                    MathHelper.floor_double(checkBox.maxZ));

            if (!mc.theWorld.isBlockLoaded(voidMinCorner) || !mc.theWorld.isBlockLoaded(voidMaxCorner)
                    || isTerrainBlocking(checkBox)) {
                return forward;
            }
        }
        return -1.0D;
    }

    private boolean isVoidColumn(double x, double y, double z, Map<Long, Boolean> voidColumns) {
        int blockX = MathHelper.floor_double(x);
        int blockZ = MathHelper.floor_double(z);
        long columnKey = getColumnKey(blockX, blockZ);
        Boolean cached = voidColumns.get(columnKey);
        if (cached != null) {
            return cached;
        }

        int startY = Math.min(255, MathHelper.floor_double(y - 0.01D));
        BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos(blockX, Math.max(0, startY), blockZ);
        if (!mc.theWorld.isBlockLoaded(blockPos)) {
            voidColumns.put(columnKey, false);
            return false;
        }

        for (int blockY = startY; blockY >= 0; blockY--) {
            blockPos.set(blockX, blockY, blockZ);
            if (!mc.theWorld.isAirBlock(blockPos)) {
                voidColumns.put(columnKey, false);
                return false;
            }
        }

        voidColumns.put(columnKey, true);
        return true;
    }

    private long getColumnKey(int blockX, int blockZ) {
        return ((long) blockX << 32) ^ ((long) blockZ & 0xffffffffL);
    }

    private void updateDisplaceSide(float playerYaw, float displaceYaw) {
        displaceLeft = MathHelper.wrapAngleTo180_float(displaceYaw - playerYaw) < 0.0F;
    }

    private static final class VoidYawCandidate {
        private final float yaw;
        private final VoidPathScore pathScore;
        private final double priorityDistance;
        private final VoidDebugCandidate debugCandidate;

        private VoidYawCandidate(float yaw, VoidPathScore pathScore, double priorityDistance,
                                 VoidDebugCandidate debugCandidate) {
            this.yaw = yaw;
            this.pathScore = pathScore;
            this.priorityDistance = priorityDistance;
            this.debugCandidate = debugCandidate;
        }

        private int compareTo(VoidYawCandidate other) {
            int pathComparison = pathScore.compareTo(other.pathScore);
            if (pathComparison != 0) {
                return pathComparison;
            }
            if (Math.abs(priorityDistance - other.priorityDistance) > VOID_SCORE_EPSILON) {
                return priorityDistance < other.priorityDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidPathScore {
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int destinationWidth;
        private final int longestCenterVoidRun;
        private final double pathQuality;
        private final double destinationDistance;
        private final double destinationX;
        private final double destinationZ;
        private final int destinationBlockX;
        private final int destinationBlockZ;
        private final long destinationVoidMask;

        private VoidPathScore(int immediateVoidNeighbors, int extendedVoidNeighbors, int destinationWidth,
                              int longestCenterVoidRun, double pathQuality, double destinationDistance,
                              double destinationX, double destinationZ, int destinationBlockX,
                              int destinationBlockZ, long destinationVoidMask) {
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.destinationWidth = destinationWidth;
            this.longestCenterVoidRun = longestCenterVoidRun;
            this.pathQuality = pathQuality;
            this.destinationDistance = destinationDistance;
            this.destinationX = destinationX;
            this.destinationZ = destinationZ;
            this.destinationBlockX = destinationBlockX;
            this.destinationBlockZ = destinationBlockZ;
            this.destinationVoidMask = destinationVoidMask;
        }

        private int compareTo(VoidPathScore other) {
            if (immediateVoidNeighbors != other.immediateVoidNeighbors) {
                return Integer.compare(immediateVoidNeighbors, other.immediateVoidNeighbors);
            }
            if (extendedVoidNeighbors != other.extendedVoidNeighbors) {
                return Integer.compare(extendedVoidNeighbors, other.extendedVoidNeighbors);
            }
            if (destinationWidth != other.destinationWidth) {
                return Integer.compare(destinationWidth, other.destinationWidth);
            }
            if (longestCenterVoidRun != other.longestCenterVoidRun) {
                return Integer.compare(longestCenterVoidRun, other.longestCenterVoidRun);
            }
            if (Math.abs(pathQuality - other.pathQuality) > VOID_SCORE_EPSILON) {
                return pathQuality > other.pathQuality ? 1 : -1;
            }
            if (Math.abs(destinationDistance - other.destinationDistance) > VOID_SCORE_EPSILON) {
                return destinationDistance < other.destinationDistance ? 1 : -1;
            }
            return 0;
        }
    }

    private static final class VoidNeighborhood {
        private final int blockX;
        private final int blockZ;
        private final int immediateVoidNeighbors;
        private final int extendedVoidNeighbors;
        private final int quality;
        private final long voidMask;

        private VoidNeighborhood(int blockX, int blockZ, int immediateVoidNeighbors,
                                 int extendedVoidNeighbors, int quality, long voidMask) {
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.immediateVoidNeighbors = immediateVoidNeighbors;
            this.extendedVoidNeighbors = extendedVoidNeighbors;
            this.quality = quality;
            this.voidMask = voidMask;
        }
    }

    private static final class VoidDebugProbe {
        private final double leftX;
        private final double leftZ;
        private final boolean leftVoid;
        private final double centerX;
        private final double centerZ;
        private final boolean centerVoid;
        private final double rightX;
        private final double rightZ;
        private final boolean rightVoid;

        private VoidDebugProbe(double leftX, double leftZ, boolean leftVoid,
                               double centerX, double centerZ, boolean centerVoid,
                               double rightX, double rightZ, boolean rightVoid) {
            this.leftX = leftX;
            this.leftZ = leftZ;
            this.leftVoid = leftVoid;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.centerVoid = centerVoid;
            this.rightX = rightX;
            this.rightZ = rightZ;
            this.rightVoid = rightVoid;
        }
    }

    private static final class VoidDebugCandidate {
        private final float yaw;
        private final double forwardX;
        private final double forwardZ;
        private final double priorityDistance;
        private final List<VoidDebugProbe> probes = new ArrayList<>();
        private VoidPathScore pathScore;
        private double reachedDistance;
        private double blockedDistance = -1.0D;

        private VoidDebugCandidate(float yaw, double forwardX, double forwardZ,
                                   double priorityDistance) {
            this.yaw = yaw;
            this.forwardX = forwardX;
            this.forwardZ = forwardZ;
            this.priorityDistance = priorityDistance;
        }
    }

    private static final class VoidDebugScan {
        private final Object world;
        private final int targetEntityId;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double scanDistance;
        private final float preferredYawPositive;
        private final float preferredYawNegative;
        private final List<VoidDebugCandidate> candidates = new ArrayList<>();
        private VoidDebugCandidate bestCandidate;

        private VoidDebugScan(Object world, int targetEntityId, double originX, double originY,
                              double originZ, double scanDistance, float preferredYawPositive,
                              float preferredYawNegative) {
            this.world = world;
            this.targetEntityId = targetEntityId;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.scanDistance = scanDistance;
            this.preferredYawPositive = preferredYawPositive;
            this.preferredYawNegative = preferredYawNegative;
        }
    }

    private void pruneTargetDelayStates() {
        if (mc.theWorld == null) {
            targetWindowStartTicks.clear();
            targetDisplacementLocks.clear();
            return;
        }

        Iterator<Map.Entry<Integer, Integer>> iterator = targetWindowStartTicks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Integer> entry = iterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }

        Iterator<Map.Entry<Integer, DisplacementLock>> lockIterator = targetDisplacementLocks.entrySet().iterator();
        while (lockIterator.hasNext()) {
            Map.Entry<Integer, DisplacementLock> entry = lockIterator.next();
            Entity entity = mc.theWorld.getEntityByID(entry.getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                lockIterator.remove();
                continue;
            }

            EntityPlayer player = (EntityPlayer) entity;
            DisplacementLock lock = entry.getValue();
            if (!player.onGround) {
                lock.airborne = true;
            } else if (lock.airborne || tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS) {
                lockIterator.remove();
            }
        }
    }

    private boolean shouldDisplaceInCurrentWindow(EntityPlayer target, int currentTick) {
        if (target == null) {
            return true;
        }

        int targetId = target.getEntityId();
        Integer windowStartTick = targetWindowStartTicks.get(targetId);
        if (windowStartTick == null || currentTick - windowStartTick >= DISPLACE_WINDOW_TICKS) {
            targetWindowStartTicks.put(targetId, currentTick);
            return true;
        }

        int delayTicks = msToTicks(delay.getInput());
        if (delayTicks <= 0) {
            return true;
        }

        int elapsed = currentTick - windowStartTick;
        return elapsed >= delayTicks;
    }

    private void releaseBlink() {
        if (outboundBlink != null) {
            outboundBlink.getTimeout().forceTimeOut();
            outboundBlink = null;
        }
    }

    private boolean isOverrideAttackEnabled() {
        return overrideAttack.isToggled();
    }

    private boolean isOverrideTargetValid(EntityPlayer target) {
        return target != null
                && target.worldObj == mc.theWorld
                && CombatTargeting.asValidPlayer(target, OVERRIDE_TARGET_RANGE_SQ) != null;
    }

    private boolean passesOverrideItemConditions() {
        if (onlyKnockbackItems.isToggled() && EnchantmentHelper.getKnockbackModifier(mc.thePlayer) <= 0) {
            return false;
        }
        return !weaponOnly.isToggled() || Utils.holdingWeapon();
    }

    private boolean canStartOverrideAttack(EntityPlayer target) {
        if (overrideAttackState != OverrideAttackState.IDLE || !Utils.nullCheck()
                || mc.currentScreen != null || !isOverrideTargetValid(target)
                || !passesOverrideItemConditions()) {
            return false;
        }

        long configuredDelay = (long) delay.getInput();
        return lastOverrideFlickMs < 0L
                || configuredDelay <= 0L
                || System.currentTimeMillis() - lastOverrideFlickMs >= configuredDelay;
    }

    private float[] getOverrideTargetRotations(EntityPlayer target, float baseYaw, float basePitch) {
        return RotationUtils.getRotations(target, 100.0D, 100.0D, baseYaw, basePitch);
    }

    private void startOverrideBlink() {
        releaseBlink();
        releaseBlinkNextGameTick = false;
        if (!blink.isToggled()) {
            return;
        }

        outboundBlink = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(outboundBlink);
    }

    private void releaseUseForOverrideAttack() {
        if (!Utils.nullCheck() || mc.playerController == null) {
            return;
        }

        if (mc.thePlayer.isUsingItem()) {
            mc.playerController.onStoppedUsingItem(mc.thePlayer);
        } else if (ModuleUtils.isBlocked) {
            PacketUtils.sendReleasePacket();
        }
    }

    private boolean startOverrideAttack(EntityPlayer target) {
        if (!canStartOverrideAttack(target)) {
            return false;
        }

        float baseYaw = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];
        float[] targetRotations = getOverrideTargetRotations(target, baseYaw, basePitch);
        if (targetRotations == null) {
            return false;
        }

        overrideTargetYaw = targetRotations[0];
        overrideTargetPitch = targetRotations[1];
        overrideAbsoluteFlickYaw = isVoidMode();
        if (overrideAbsoluteFlickYaw) {
            Float bestVoidYaw = findBestVoidYaw(target, overrideTargetYaw);
            if (bestVoidYaw == null) {
                return false;
            }
            overrideFlickYaw = bestVoidYaw;
            overrideFlickOffset = 0.0F;
            updateDisplaceSide(overrideTargetYaw, overrideFlickYaw);
        } else {
            if (!findVoid.isToggled() || !tryFindVoidDirection(target)) {
                displaceLeft = direction.getInput() == 0;
            }
            overrideFlickOffset = displaceLeft
                    ? -(float) yawOffset.getInput()
                    : (float) yawOffset.getInput();
            overrideFlickYaw = overrideTargetYaw + overrideFlickOffset;
        }

        overrideFlickYaw = lockDisplacementYaw(target, overrideFlickYaw);
        updateDisplaceSide(overrideTargetYaw, overrideFlickYaw);

        overrideTarget = target;
        overrideAttackState = OverrideAttackState.FLICKING_AWAY;
        overrideStateTicks = 0;
        overrideAwayPacketSent = false;
        overrideAttackInProgress = false;
        overrideAttackPacketSent = false;
        suppressUseForOverrideAttackTick = false;
        lastOverrideFlickMs = System.currentTimeMillis();
        active = true;
        displaceThisTick = true;
        hasKB = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        compensateNextTick = false;
        wasDisplacingLastTick = false;
        showArrow(target, overrideFlickYaw);
        releaseUseForOverrideAttack();
        startOverrideBlink();
        return true;
    }

    private void resetOverrideAttackState() {
        releaseBlink();
        overrideAttackState = OverrideAttackState.IDLE;
        overrideTarget = null;
        overrideTargetYaw = 0.0F;
        overrideTargetPitch = 0.0F;
        overrideFlickYaw = 0.0F;
        overrideFlickOffset = 0.0F;
        overrideAbsoluteFlickYaw = false;
        overrideAwayPacketSent = false;
        overrideAttackInProgress = false;
        overrideAttackPacketSent = false;
        suppressUseForOverrideAttackTick = false;
        overrideStateTicks = 0;
        active = false;
        displaceThisTick = false;
        compensateNextTick = false;
        wasDisplacingLastTick = false;
        hideArrow();
    }

    private void updateOverrideRotation(ClientRotationEvent event) {
        if (!isOverrideTargetValid(overrideTarget)) {
            resetOverrideAttackState();
            return;
        }

        float baseYaw = event.yaw != null ? event.yaw : RotationUtils.serverRotations[0];
        float basePitch = event.pitch != null ? event.pitch : RotationUtils.serverRotations[1];
        float[] targetRotations = getOverrideTargetRotations(overrideTarget, baseYaw, basePitch);
        if (targetRotations == null) {
            resetOverrideAttackState();
            return;
        }

        overrideTargetYaw = targetRotations[0];
        overrideTargetPitch = targetRotations[1];
        if (!overrideAbsoluteFlickYaw) {
            overrideFlickYaw = getLockedDisplacementYaw(
                    overrideTarget,
                    overrideTargetYaw + overrideFlickOffset
            );
        }
        updateDisplaceSide(overrideTargetYaw, overrideFlickYaw);

        if (overrideAttackState == OverrideAttackState.FLICKING_AWAY) {
            event.yaw = overrideFlickYaw;
            displaceThisTick = true;
        } else {
            event.yaw = overrideTargetYaw;
            displaceThisTick = false;
        }
        event.pitch = overrideTargetPitch;
        active = true;
        showArrow(overrideTarget, overrideFlickYaw);
        RotationHelper.get().forceMovementFix = true;
    }

    private void advanceOverrideAttackState() {
        if (overrideAttackState == OverrideAttackState.IDLE) {
            return;
        }
        if (!Utils.nullCheck() || mc.currentScreen != null || !isOverrideTargetValid(overrideTarget)) {
            resetOverrideAttackState();
            return;
        }

        overrideStateTicks++;
        switch (overrideAttackState) {
            case FLICKING_AWAY:
                if (overrideAwayPacketSent || overrideStateTicks >= OVERRIDE_MAX_FLICK_TICKS) {
                    overrideAttackState = OverrideAttackState.ATTACKING;
                    overrideStateTicks = 0;
                    displaceThisTick = false;
                }
                break;
            case ATTACKING:
                if (overrideStateTicks >= OVERRIDE_ATTACK_TICKS) {
                    resetOverrideAttackState();
                }
                break;
            case RESTORING:
                if (overrideStateTicks >= OVERRIDE_RESTORE_TICKS) {
                    resetOverrideAttackState();
                }
                break;
            default:
                break;
        }
    }

    private void clearVoidDebugState() {
        latestVoidDebugScan = null;
        frozenVoidDebugScan = null;
        frozenVoidDebugExpiresAtMs = 0L;
    }

    private void showArrow(EntityPlayer player, float yaw) {
        long now = System.currentTimeMillis();
        boolean playerChanged = arrowPlayer != player;
        float currentAlpha = playerChanged ? 0.0F : getArrowAlpha(now);

        if (playerChanged) {
            clearArrowNudge();
        }
        arrowPlayer = player;
        arrowYaw = yaw;
        if (!arrowVisible || playerChanged) {
            arrowFadeStartAlpha = currentAlpha;
            arrowFadeEndAlpha = 1.0F;
            arrowFadeStartMs = now;
        }
        arrowVisible = true;
    }

    private void hideArrow() {
        if (arrowPlayer == null || !arrowVisible) {
            return;
        }

        long now = System.currentTimeMillis();
        arrowFadeStartAlpha = getArrowAlpha(now);
        arrowFadeEndAlpha = 0.0F;
        arrowFadeStartMs = now;
        arrowVisible = false;
    }

    private float getArrowAlpha(long now) {
        float progress = Math.min(1.0F, Math.max(0.0F, (float) (now - arrowFadeStartMs) / (float) ARROW_FADE_MS));
        return arrowFadeStartAlpha + (arrowFadeEndAlpha - arrowFadeStartAlpha) * progress;
    }

    private void triggerArrowNudge(EntityPlayer player) {
        if (player == null || arrowPlayer != player) {
            return;
        }
        long now = System.currentTimeMillis();
        if (arrowNudgePlayerId == player.getEntityId() && now - arrowNudgeStartMs < ARROW_NUDGE_MS) {
            return;
        }
        arrowNudgePlayerId = player.getEntityId();
        arrowNudgeStartMs = now;
    }

    private double getArrowNudgeProgress(EntityPlayer player, long now) {
        if (player == null || arrowNudgePlayerId != player.getEntityId() || arrowNudgeStartMs < 0L) {
            return -1.0D;
        }
        double progress = (double) (now - arrowNudgeStartMs) / (double) ARROW_NUDGE_MS;
        return Math.min(1.0D, Math.max(0.0D, progress));
    }

    private double getArrowNudgeOffset(double progress) {
        if (progress < 0.0D) {
            return 0.0D;
        }
        double endValue = Math.exp(-ARROW_NUDGE_DECAY);
        double movement = (1.0D - Math.exp(-ARROW_NUDGE_DECAY * progress)) / (1.0D - endValue);
        return movement * ARROW_NUDGE_DISTANCE;
    }

    private float getArrowNudgeAlpha(double progress) {
        if (progress < 0.0D || progress <= ARROW_NUDGE_FADE_START) {
            return 1.0F;
        }
        double fadeProgress = (progress - ARROW_NUDGE_FADE_START) / (1.0D - ARROW_NUDGE_FADE_START);
        double endValue = Math.exp(-ARROW_NUDGE_FADE_DECAY);
        double alpha = (Math.exp(-ARROW_NUDGE_FADE_DECAY * fadeProgress) - endValue) / (1.0D - endValue);
        return (float) alpha;
    }

    private void clearArrowNudge() {
        arrowNudgeStartMs = -1L;
        arrowNudgePlayerId = -1;
    }

    private void clearArrow() {
        arrowPlayer = null;
        arrowFadeStartAlpha = 0.0F;
        arrowFadeEndAlpha = 0.0F;
        arrowFadeStartMs = 0L;
        arrowVisible = false;
        clearArrowNudge();
    }

    private float lockDisplacementYaw(EntityPlayer target, float yaw) {
        int targetId = target.getEntityId();
        DisplacementLock lock = targetDisplacementLocks.get(targetId);
        if (lock != null && ((lock.airborne && target.onGround)
                || tickCounter - lock.lastUseTick >= DISPLACEMENT_LOCK_IDLE_TICKS)) {
            targetDisplacementLocks.remove(targetId);
            lock = null;
        }
        if (lock == null) {
            lock = new DisplacementLock(yaw, tickCounter, !target.onGround);
            targetDisplacementLocks.put(targetId, lock);
        } else {
            lock.lastUseTick = tickCounter;
            if (!target.onGround) {
                lock.airborne = true;
            }
        }
        return lock.yaw;
    }

    private float getLockedDisplacementYaw(EntityPlayer target, float fallbackYaw) {
        DisplacementLock lock = targetDisplacementLocks.get(target.getEntityId());
        return lock == null ? fallbackYaw : lock.yaw;
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent e) {
        if (!Utils.nullCheck()) {
            clearArrow();
            clearVoidDebugState();
            return;
        }

        renderFrozenVoidDebug();

        if (!renderArrow.isToggled()) {
            clearArrow();
            return;
        }
        if (arrowPlayer == null || arrowPlayer.isDead || arrowPlayer.deathTime != 0) {
            clearArrow();
            return;
        }

        long now = System.currentTimeMillis();
        double nudgeProgress = getArrowNudgeProgress(arrowPlayer, now);
        float alpha = getArrowAlpha(now) * getArrowNudgeAlpha(nudgeProgress);
        if (alpha <= 0.0F) {
            if (!arrowVisible) {
                clearArrow();
            }
            return;
        }

        drawArrow(arrowPlayer, arrowYaw, e.partialTicks, alpha, getArrowNudgeOffset(nudgeProgress));
    }

    private void renderFrozenVoidDebug() {
        VoidDebugScan scan = frozenVoidDebugScan;
        if (!Raven.DEBUG || scan == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now >= frozenVoidDebugExpiresAtMs || scan.world != mc.theWorld) {
            frozenVoidDebugScan = null;
            frozenVoidDebugExpiresAtMs = 0L;
            return;
        }

        List<VoidDebugCandidate> rankedCandidates = new ArrayList<>(scan.candidates);
        Collections.sort(rankedCandidates, new Comparator<VoidDebugCandidate>() {
            @Override
            public int compare(VoidDebugCandidate first, VoidDebugCandidate second) {
                return -compareVoidDebugCandidates(first, second);
            }
        });
        if (scan.bestCandidate != null) {
            rankedCandidates.remove(scan.bestCandidate);
            rankedCandidates.add(0, scan.bestCandidate);
        }

        double renderY = scan.originY + VOID_DEBUG_PROBE_Y_OFFSET;
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        for (VoidDebugCandidate candidate : scan.candidates) {
            int rank = rankedCandidates.indexOf(candidate);
            int color = getVoidDebugRankColor(rank, rankedCandidates.size());
            double rayLength = candidate.reachedDistance;
            if (rayLength <= 0.0D && candidate.blockedDistance >= 0.0D) {
                rayLength = candidate.blockedDistance;
            }

            GL11.glLineWidth(candidate == scan.bestCandidate ? 3.5F : 1.25F);
            setVoidDebugColor(color, candidate == scan.bestCandidate ? 1.0F : 0.72F);
            GL11.glBegin(GL11.GL_LINES);
            voidDebugVertex(scan.originX, renderY, scan.originZ, viewerX, viewerY, viewerZ);
            voidDebugVertex(scan.originX + candidate.forwardX * rayLength, renderY,
                    scan.originZ + candidate.forwardZ * rayLength, viewerX, viewerY, viewerZ);
            GL11.glEnd();
        }

        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.85F, 0.2F, 1.0F, 0.9F);
        GL11.glBegin(GL11.GL_LINES);
        drawVoidDebugPreferredRay(scan, scan.preferredYawPositive, renderY,
                viewerX, viewerY, viewerZ);
        if (Math.abs(MathHelper.wrapAngleTo180_float(
                scan.preferredYawPositive - scan.preferredYawNegative)) > VOID_SCORE_EPSILON) {
            drawVoidDebugPreferredRay(scan, scan.preferredYawNegative, renderY,
                    viewerX, viewerY, viewerZ);
        }
        GL11.glEnd();

        GL11.glPointSize(3.0F);
        GL11.glBegin(GL11.GL_POINTS);
        for (VoidDebugCandidate candidate : scan.candidates) {
            for (VoidDebugProbe probe : candidate.probes) {
                setVoidProbeColor(probe.leftVoid);
                voidDebugVertex(probe.leftX, renderY, probe.leftZ, viewerX, viewerY, viewerZ);
                setVoidProbeColor(probe.centerVoid);
                voidDebugVertex(probe.centerX, renderY, probe.centerZ, viewerX, viewerY, viewerZ);
                setVoidProbeColor(probe.rightVoid);
                voidDebugVertex(probe.rightX, renderY, probe.rightZ, viewerX, viewerY, viewerZ);
            }
        }
        GL11.glEnd();

        GL11.glLineWidth(1.75F);
        GL11.glColor4f(1.0F, 0.05F, 0.05F, 0.95F);
        GL11.glBegin(GL11.GL_LINES);
        for (VoidDebugCandidate candidate : scan.candidates) {
            if (candidate.blockedDistance < 0.0D) {
                continue;
            }
            double blockedX = scan.originX + candidate.forwardX * candidate.blockedDistance;
            double blockedZ = scan.originZ + candidate.forwardZ * candidate.blockedDistance;
            drawVoidDebugCross(blockedX, renderY + 0.03D, blockedZ, 0.13D,
                    viewerX, viewerY, viewerZ);
        }
        GL11.glEnd();

        renderBestVoidDebugDetails(scan, renderY, viewerX, viewerY, viewerZ);

        RenderUtils.popAttrib();
        GL11.glPopMatrix();

        for (VoidDebugCandidate candidate : scan.candidates) {
            int rank = rankedCandidates.indexOf(candidate);
            int color = getVoidDebugRankColor(rank, rankedCandidates.size());
            double labelDistance = scan.scanDistance + 0.45D;
            double labelX = scan.originX + candidate.forwardX * labelDistance;
            double labelY = renderY + 0.32D + (double) (rank % 5) * 0.11D;
            double labelZ = scan.originZ + candidate.forwardZ * labelDistance;
            renderVoidDebugLabel(formatVoidDebugScore(candidate, rank,
                            candidate == scan.bestCandidate), labelX, labelY, labelZ, color, 0.0105F);
        }

        String legend = String.format(Locale.ROOT,
                "Score: N > E > W > Run > Path > Near > Angle | preferred %.1f° / %.1f° | scan %.1f",
                scan.preferredYawPositive, scan.preferredYawNegative, scan.scanDistance);
        renderVoidDebugLabel(legend, scan.originX, scan.originY + 2.35D, scan.originZ,
                0xFFFFFF, 0.0125F);
    }

    private void drawVoidDebugPreferredRay(VoidDebugScan scan, float preferredYaw, double renderY,
                                           double viewerX, double viewerY, double viewerZ) {
        double radians = Math.toRadians(preferredYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        voidDebugVertex(scan.originX, renderY + 0.025D, scan.originZ, viewerX, viewerY, viewerZ);
        voidDebugVertex(scan.originX + forwardX * scan.scanDistance, renderY + 0.025D,
                scan.originZ + forwardZ * scan.scanDistance, viewerX, viewerY, viewerZ);
    }

    private int compareVoidDebugCandidates(VoidDebugCandidate first, VoidDebugCandidate second) {
        if (first.pathScore == null || second.pathScore == null) {
            if (first.pathScore != null) {
                return 1;
            }
            if (second.pathScore != null) {
                return -1;
            }
        } else {
            int pathComparison = first.pathScore.compareTo(second.pathScore);
            if (pathComparison != 0) {
                return pathComparison;
            }
        }

        if (Math.abs(first.priorityDistance - second.priorityDistance) > VOID_SCORE_EPSILON) {
            return first.priorityDistance < second.priorityDistance ? 1 : -1;
        }
        return 0;
    }

    private int getVoidDebugRankColor(int rank, int candidateCount) {
        if (rank <= 0) {
            return 0x20FF45;
        }

        float progress = candidateCount <= 1
                ? 1.0F
                : Math.min(1.0F, (float) rank / (float) (candidateCount - 1));
        int red = Math.min(255, Math.round(510.0F * progress));
        int green = Math.min(255, Math.round(510.0F * (1.0F - progress)));
        int blue = Math.round(24.0F * (1.0F - progress));
        return red << 16 | green << 8 | blue;
    }

    private void setVoidDebugColor(int color, float alpha) {
        GlStateManager.color((float) (color >> 16 & 255) / 255.0F,
                (float) (color >> 8 & 255) / 255.0F,
                (float) (color & 255) / 255.0F, alpha);
    }

    private void setVoidProbeColor(boolean voidColumn) {
        if (voidColumn) {
            GlStateManager.color(0.15F, 0.9F, 1.0F, 0.82F);
        } else {
            GlStateManager.color(1.0F, 0.42F, 0.05F, 0.72F);
        }
    }

    private void renderBestVoidDebugDetails(VoidDebugScan scan, double renderY,
                                            double viewerX, double viewerY, double viewerZ) {
        VoidDebugCandidate best = scan.bestCandidate;
        if (best == null || best.pathScore == null) {
            return;
        }

        GL11.glPointSize(6.0F);
        GL11.glBegin(GL11.GL_POINTS);
        for (VoidDebugProbe probe : best.probes) {
            setVoidProbeColor(probe.leftVoid);
            voidDebugVertex(probe.leftX, renderY + 0.035D, probe.leftZ, viewerX, viewerY, viewerZ);
            setVoidProbeColor(probe.centerVoid);
            voidDebugVertex(probe.centerX, renderY + 0.035D, probe.centerZ, viewerX, viewerY, viewerZ);
            setVoidProbeColor(probe.rightVoid);
            voidDebugVertex(probe.rightX, renderY + 0.035D, probe.rightZ, viewerX, viewerY, viewerZ);
        }
        GL11.glEnd();

        VoidPathScore score = best.pathScore;
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                int bitIndex = (offsetX + 2) * 5 + offsetZ + 2;
                boolean destination = offsetX == 0 && offsetZ == 0;
                boolean voidColumn = destination || (score.destinationVoidMask & 1L << bitIndex) != 0L;
                int radius = Math.max(Math.abs(offsetX), Math.abs(offsetZ));

                if (destination) {
                    GlStateManager.color(0.1F, 1.0F, 0.25F, 1.0F);
                } else if (voidColumn && radius == 1) {
                    GlStateManager.color(0.15F, 1.0F, 0.55F, 0.9F);
                } else if (voidColumn) {
                    GlStateManager.color(0.1F, 0.75F, 1.0F, 0.78F);
                } else {
                    GlStateManager.color(1.0F, 0.12F, 0.08F, 0.68F);
                }

                GL11.glLineWidth(destination ? 3.0F : 1.25F);
                GL11.glBegin(GL11.GL_LINE_LOOP);
                drawVoidDebugSquare(score.destinationBlockX + offsetX + 0.5D,
                        renderY + 0.055D, score.destinationBlockZ + offsetZ + 0.5D,
                        0.43D, viewerX, viewerY, viewerZ);
                GL11.glEnd();
            }
        }

        GL11.glLineWidth(3.0F);
        GlStateManager.color(0.1F, 1.0F, 0.25F, 1.0F);
        GL11.glBegin(GL11.GL_LINES);
        voidDebugVertex(score.destinationX, renderY, score.destinationZ, viewerX, viewerY, viewerZ);
        voidDebugVertex(score.destinationX, renderY + 1.25D, score.destinationZ,
                viewerX, viewerY, viewerZ);
        GL11.glEnd();
    }

    private String formatVoidDebugScore(VoidDebugCandidate candidate, int rank, boolean best) {
        String prefix = best ? "BEST " : "";
        if (candidate.pathScore == null) {
            String result = candidate.blockedDistance >= 0.0D
                    ? String.format(Locale.ROOT, "blocked@%.2f", candidate.blockedDistance)
                    : String.format(Locale.ROOT, "no-void reached=%.1f", candidate.reachedDistance);
            return String.format(Locale.ROOT, "%s#%02d %.1f° INVALID %s A%.1f",
                    prefix, rank + 1, candidate.yaw, result, candidate.priorityDistance);
        }

        VoidPathScore score = candidate.pathScore;
        return String.format(Locale.ROOT,
                "%s#%02d %.1f° [N%d E%d W%d R%d P%.1f D%.1f A%.1f]",
                prefix, rank + 1, candidate.yaw, score.immediateVoidNeighbors,
                score.extendedVoidNeighbors, score.destinationWidth, score.longestCenterVoidRun,
                score.pathQuality, score.destinationDistance, candidate.priorityDistance);
    }

    private void renderVoidDebugLabel(String text, double x, double y, double z, int color, float scale) {
        RenderManager renderManager = mc.getRenderManager();
        GlStateManager.pushMatrix();
        GlStateManager.translate(x - renderManager.viewerPosX, y - renderManager.viewerPosY,
                z - renderManager.viewerPosZ);
        GlStateManager.rotate(-renderManager.playerViewY, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate((mc.gameSettings.thirdPersonView == 2 ? -1.0F : 1.0F)
                * renderManager.playerViewX, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(-scale, -scale, scale);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        int textX = -mc.fontRendererObj.getStringWidth(text) / 2;
        mc.fontRendererObj.drawString(text, textX, 0, 0xFF000000 | color, true);
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableLighting();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.popMatrix();
    }

    private void drawVoidDebugCross(double x, double y, double z, double size,
                                    double viewerX, double viewerY, double viewerZ) {
        voidDebugVertex(x - size, y, z - size, viewerX, viewerY, viewerZ);
        voidDebugVertex(x + size, y, z + size, viewerX, viewerY, viewerZ);
        voidDebugVertex(x - size, y, z + size, viewerX, viewerY, viewerZ);
        voidDebugVertex(x + size, y, z - size, viewerX, viewerY, viewerZ);
    }

    private void drawVoidDebugSquare(double x, double y, double z, double halfSize,
                                     double viewerX, double viewerY, double viewerZ) {
        voidDebugVertex(x - halfSize, y, z - halfSize, viewerX, viewerY, viewerZ);
        voidDebugVertex(x + halfSize, y, z - halfSize, viewerX, viewerY, viewerZ);
        voidDebugVertex(x + halfSize, y, z + halfSize, viewerX, viewerY, viewerZ);
        voidDebugVertex(x - halfSize, y, z + halfSize, viewerX, viewerY, viewerZ);
    }

    private void voidDebugVertex(double x, double y, double z,
                                 double viewerX, double viewerY, double viewerZ) {
        GL11.glVertex3d(x - viewerX, y - viewerY, z - viewerZ);
    }

    private void drawArrow(EntityPlayer player, float yaw, float partialTicks, float alpha, double nudgeOffset) {
        double x = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks;
        double y = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks + player.height * 0.5;
        double z = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks;
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double startDistance = player.width * 0.5 + ARROW_BASE_GAP + nudgeOffset;
        double startX = x + forwardX * startDistance;
        double startZ = z + forwardZ * startDistance;
        double bodyX = startX + forwardX * 0.74;
        double bodyZ = startZ + forwardZ * 0.74;
        double headBaseX = startX + forwardX * 0.56;
        double headBaseZ = startZ + forwardZ * 0.56;
        double tipX = bodyX + forwardX * 0.52;
        double tipZ = bodyZ + forwardZ * 0.52;
        double viewerX = mc.getRenderManager().viewerPosX;
        double viewerY = mc.getRenderManager().viewerPosY;
        double viewerZ = mc.getRenderManager().viewerPosZ;

        GL11.glPushMatrix();
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(false);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);

        GL11.glColor4f(1.0F, 1.0F, 1.0F, 0.82F * alpha);
        GL11.glBegin(GL11.GL_TRIANGLES);
        this.vertex(startX, y, startZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        this.vertex(headBaseX, y, headBaseZ, -0.3, viewerX, viewerY, viewerZ);
        this.vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        this.vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        this.vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(headBaseX, y, headBaseZ, 0.3, viewerX, viewerY, viewerZ);
        GL11.glEnd();

        GL11.glLineWidth(2.0F);
        GL11.glColor4f(0.0F, 0.0F, 0.0F, 0.95F * alpha);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        this.vertex(startX, y, startZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, -0.08, viewerX, viewerY, viewerZ);
        this.vertex(headBaseX, y, headBaseZ, -0.3, viewerX, viewerY, viewerZ);
        this.vertex(tipX, y, tipZ, 0.0, viewerX, viewerY, viewerZ);
        this.vertex(headBaseX, y, headBaseZ, 0.3, viewerX, viewerY, viewerZ);
        this.vertex(bodyX, y, bodyZ, 0.08, viewerX, viewerY, viewerZ);
        GL11.glEnd();
        RenderUtils.popAttrib();
        GL11.glPopMatrix();
    }

    private void vertex(double x, double y, double z, double yOffset, double viewerX, double viewerY, double viewerZ) {
        GL11.glVertex3d(x - viewerX, y + yOffset - viewerY, z - viewerZ);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGameTick(GameTickEvent e) {
        suppressUseForOverrideAttackTick = false;
        if (isOverrideAttackEnabled()) {
            if (releaseBlinkNextGameTick) {
                releaseBlink();
                releaseBlinkNextGameTick = false;
            }
            advanceOverrideAttackState();
            return;
        }

        if (overrideAttackState != OverrideAttackState.IDLE) {
            resetOverrideAttackState();
        }
        if (releaseBlinkNextGameTick) {
            releaseBlink();
            releaseBlinkNextGameTick = false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickMouse(RightClickMouseEvent event) {
        if (!isOverrideAttackEnabled()) {
            return;
        }

        if (overrideAttackState == OverrideAttackState.FLICKING_AWAY
                || overrideAttackState == OverrideAttackState.ATTACKING
                || suppressUseForOverrideAttackTick) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPreAttack(PreAttackEvent event) {
        if (!isOverrideAttackEnabled()) {
            return;
        }
        if (overrideAttackState != OverrideAttackState.IDLE) {
            event.setCanceled(true);
            return;
        }

        MovingObjectPosition mouseOver = event.objectMouseOver;
        EntityPlayer target = mouseOver != null && mouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                ? CombatTargeting.asValidPlayer(mouseOver.entityHit, OVERRIDE_TARGET_RANGE_SQ)
                : null;
        if (target != null && startOverrideAttack(target)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAttack(AttackEvent event) {
        if (!isOverrideAttackEnabled() || event.attacker != mc.thePlayer || overrideAttackInProgress) {
            return;
        }
        if (overrideAttackState != OverrideAttackState.IDLE) {
            event.setCanceled(true);
            return;
        }

        EntityPlayer target = CombatTargeting.asValidPlayer(
                event.target,
                OVERRIDE_TARGET_RANGE_SQ
        );
        if (target != null && startOverrideAttack(target)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!isOverrideAttackEnabled() || overrideAttackState != OverrideAttackState.ATTACKING
                || overrideAttackInProgress) {
            return;
        }
        if (!isOverrideTargetValid(overrideTarget)) {
            resetOverrideAttackState();
            return;
        }

        overrideAttackPacketSent = false;
        releaseUseForOverrideAttack();
        suppressUseForOverrideAttackTick = true;
        overrideAttackInProgress = true;
        try {
            Utils.attackEntity(overrideTarget, true, false);
        } finally {
            overrideAttackInProgress = false;
        }

        if (!overrideAttackPacketSent) {
            resetOverrideAttackState();
            return;
        }

        overrideAttackState = OverrideAttackState.RESTORING;
        overrideStateTicks = 0;
        displaceThisTick = false;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostInput(PostPlayerInputEvent e) {
        if (isOverrideAttackEnabled()) {
            compensateNextTick = false;
            return;
        }
        if (!active) {
            compensateNextTick = false;
            return;
        }

        if (compensateNextTick && !displaceThisTick) {
            compensateNextTick = false;
            if (displaceLeft) {
                mc.thePlayer.movementInput.moveStrafe = -1;
            } else {
                mc.thePlayer.movementInput.moveStrafe = 1;
            }
            return;
        }

        if (!displaceThisTick || hasKB) return;
        if (!anyMovementKey()) return;

        mc.thePlayer.movementInput.moveForward = 1;
        compensateNextTick = true;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSendPacket(SendPacketEvent e) {
        if (!isOverrideAttackEnabled() && overrideAttackState != OverrideAttackState.IDLE) {
            resetOverrideAttackState();
        }

        if (isOverrideAttackEnabled() && overrideAttackState != OverrideAttackState.IDLE) {
            if (e.isCanceled()) {
                return;
            }

            if (overrideAttackState == OverrideAttackState.FLICKING_AWAY
                    && e.getPacket() instanceof C03PacketPlayer) {
                C03PacketPlayer movementPacket = (C03PacketPlayer) e.getPacket();
                if (movementPacket.getRotating()) {
                    float yawError = Math.abs(MathHelper.wrapAngleTo180_float(
                            movementPacket.getYaw() - overrideFlickYaw
                    ));
                    if (yawError <= OVERRIDE_FLICK_TOLERANCE) {
                        overrideAwayPacketSent = true;
                    }
                }
            }

            if (overrideAttackInProgress && e.getPacket() instanceof C02PacketUseEntity
                    && ((C02PacketUseEntity) e.getPacket()).getAction() == C02PacketUseEntity.Action.ATTACK) {
                overrideAttackPacketSent = true;
                releaseBlink();
            }
            return;
        }

        if (!blink.isToggled() || !active || !displaceThisTick || releaseBlinkNextGameTick) {
            return;
        }
        if (!(e.getPacket() instanceof C03PacketPlayer)) {
            return;
        }
        if (outboundBlink != null) {
            return;
        }

        outboundBlink = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(outboundBlink);
        releaseBlinkNextGameTick = true;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onArrowNudgeAttackPacket(SendPacketEvent e) {
        if (!active || !renderArrow.isToggled() || !Utils.nullCheck() || e.isCanceled()
                || !(e.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }

        C02PacketUseEntity packet = (C02PacketUseEntity) e.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }

        Entity attackedEntity = packet.getEntityFromWorld(mc.theWorld);
        if (attackedEntity == arrowPlayer) {
            triggerArrowNudge(arrowPlayer);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onVoidDebugAttackPacket(SendPacketEvent e) {
        if (!Raven.DEBUG || !isVoidMode() || !active || !Utils.nullCheck() || e.isCanceled()
                || !(e.getPacket() instanceof C02PacketUseEntity)) {
            return;
        }

        C02PacketUseEntity packet = (C02PacketUseEntity) e.getPacket();
        if (packet.getAction() != C02PacketUseEntity.Action.ATTACK) {
            return;
        }

        Entity attackedEntity = packet.getEntityFromWorld(mc.theWorld);
        VoidDebugScan scan = latestVoidDebugScan;
        if (!(attackedEntity instanceof EntityPlayer) || scan == null || scan.bestCandidate == null
                || scan.world != mc.theWorld || attackedEntity.getEntityId() != scan.targetEntityId) {
            return;
        }

        frozenVoidDebugScan = scan;
        frozenVoidDebugExpiresAtMs = System.currentTimeMillis() + VOID_DEBUG_DURATION_MS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck()) {
            resetOverrideAttackState();
            active = false;
            compensateNextTick = false;
            wasDisplacingLastTick = false;
            clearArrow();
            clearVoidDebugState();
            return;
        }

        if (isOverrideAttackEnabled()) {
            if (overrideAttackState == OverrideAttackState.IDLE) {
                active = false;
                displaceThisTick = false;
                compensateNextTick = false;
                wasDisplacingLastTick = false;
                hideArrow();
                return;
            }
            updateOverrideRotation(e);
            return;
        }

        if (overrideAttackState != OverrideAttackState.IDLE) {
            resetOverrideAttackState();
        }

        if (!Raven.DEBUG || !isVoidMode()) {
            latestVoidDebugScan = null;
        }

        tickCounter++;
        int currentTick = tickCounter;
        pruneTargetDelayStates();

        boolean passesItemCondition = (!onlyKnockbackItems.isToggled()
                || EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0)
                && (!weaponOnly.isToggled() || Utils.holdingWeapon());
        if (!passesItemCondition) {
            active = false;
            displaceThisTick = false;
            compensateNextTick = false;
            wasDisplacingLastTick = false;
            hideArrow();
            return;
        }

        EntityPlayer target = null;
        boolean killAuraHasTarget = ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && KillAura.target != null;
        if (killAuraHasTarget) {
            target = CombatTargeting.asValidPlayer(KillAura.target, 9.0);
        } else if (Mouse.isButtonDown(0)) {
            target = CombatTargeting.findClosestTarget(9.0);
        }

        boolean hasKBEnchant = EnchantmentHelper.getKnockbackModifier(mc.thePlayer) > 0;
        active = target != null;
        if (!active) {
            displaceThisTick = false;
            compensateNextTick = false;
            wasDisplacingLastTick = false;
            hideArrow();
            return;
        }

        float playerYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float displaceYaw;
        if (isVoidMode()) {
            Float bestVoidYaw = findBestVoidYaw(target, playerYaw);
            if (bestVoidYaw == null) {
                active = false;
                displaceThisTick = false;
                compensateNextTick = false;
                wasDisplacingLastTick = false;
                hideArrow();
                return;
            }
            displaceYaw = bestVoidYaw;
            updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            if (!findVoid.isToggled() || !tryFindVoidDirection(target)) {
                displaceLeft = direction.getInput() == 0;
            }

            float offset = (float) yawOffset.getInput();
            displaceYaw = displaceLeft ? playerYaw - offset : playerYaw + offset;
        }

        hasKB = hasKBEnchant;
        displaceThisTick = !displaceThisTick;
        if (displaceThisTick && !shouldDisplaceInCurrentWindow(target, currentTick)) {
            displaceThisTick = false;
            compensateNextTick = false;
            wasDisplacingLastTick = false;
            hideArrow();
            return;
        }

        if (displaceThisTick) {
            displaceYaw = lockDisplacementYaw(target, displaceYaw);
            updateDisplaceSide(playerYaw, displaceYaw);
        } else {
            displaceYaw = getLockedDisplacementYaw(target, displaceYaw);
        }

        showArrow(target, displaceYaw);

        if (!displaceThisTick && wasDisplacingLastTick) {
            int key = mc.gameSettings.keyBindAttack.getKeyCode();
            if (key != 0) {
                KeyBinding.onTick(key);
            }
        }

        wasDisplacingLastTick = displaceThisTick;

        if (!displaceThisTick) return;

        e.yaw = displaceYaw;
        RotationHelper.get().setServerRelativeMovementInputs(true);
    }
}
