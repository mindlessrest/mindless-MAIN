package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreUpdateEvent;
import mindless.rotation.RotationSource;
import mindless.helper.RotationHelper;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ItemListSetting;
import mindless.module.setting.impl.KeySetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.placement.PlacementRuntime;
import mindless.utility.*;
import mindless.utility.Timer;
import net.minecraft.block.*;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.*;

public class AutoBlockin extends Module {

    private static final EnumFacing[] HORIZONTALS = {
            EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST, EnumFacing.NORTH
    };

    private static final SupportOffset[] SUPPORTS = {
            new SupportOffset(0, 1, 0, EnumFacing.DOWN),
            new SupportOffset(0, -1, 0, EnumFacing.UP),
            new SupportOffset(0, 0, -1, EnumFacing.SOUTH),
            new SupportOffset(0, 0, 1, EnumFacing.NORTH),
            new SupportOffset(1, 0, 0, EnumFacing.WEST),
            new SupportOffset(-1, 0, 0, EnumFacing.EAST)
    };

    private static final double REACH = 4.5;
    private static final double GRID_INSET = 0.05;
    private static final double GRID_STEP = 0.2;
    private static final int GRID_N = (int) Math.round(1.0 / GRID_STEP);

    private final SliderSetting speed;
    private final SliderSetting randomization;
    private final SliderSetting rotationTol;
    private final SliderSetting showProgress;
    private final ButtonSetting disableInCreative;
    private final ButtonSetting skipNearBed;
    private final KeySetting activationKey;
    private final ButtonSetting ignoreBlocksToggle;
    private final ItemListSetting ignoredBlocks;

    private boolean placing;
    private PlacementLease placementLease;
    private int plannedSlot = -1;
    private boolean placeQueued;

    private BlockPos targetHitPos;
    private EnumFacing targetSide;
    private float aimYaw;
    private float aimPitch;

    private BlockPos hitAt;
    private EnumFacing hitSide;
    private Vec3 placeAt;

    private float fillCount;
    private float lastFillCount = -1;
    private float circleProgress;
    private float animStartProgress;
    private float animTargetProgress;
    private long animStartTime;

    private Timer progressFadeTimer;
    private Timer progressFadeInTimer;
    private float previousProgressAlpha;

    private boolean lastTargetAdjacent;
    private float fillTargetCount;
    private float lastFillTargetCount = -1;

    public AutoBlockin() {
        super("Auto Block In", "Boxes you in on its own, no bind held.", category.player);
        this.liteModule = true;
        this.registerSetting(speed = new SliderSetting("Speed", 10, 1, 30, 1));
        this.registerSetting(randomization = new SliderSetting("Randomization", "%", 10, 0, 100, 1));
        this.registerSetting(rotationTol = new SliderSetting("Rotation tolerance", "\u00B0", 25, 20, 100, 1));
        this.registerSetting(showProgress = new SliderSetting("Show progress", 0, new String[]{"Off", "Circle", "Percentage"}));
        this.registerSetting(disableInCreative = new ButtonSetting("Disable in creative", true));
        this.registerSetting(skipNearBed = new ButtonSetting("Skip near bed", true));
        this.registerSetting(activationKey = new KeySetting("Activation key", 0));
        this.registerSetting(ignoreBlocksToggle = new ButtonSetting("Ignore blocks", false));
        this.registerSetting(ignoredBlocks = new ItemListSetting("Items"));
        this.closetModule = true;
    }

    @Override
    public void onDisable() {
        PlacementCoordinator.get().cancel(this);
        disablePlacing();
        placeQueued = false;
        fillCount = 0;
        fillTargetCount = 0;
        lastFillCount = -1;
        lastFillTargetCount = -1;
        circleProgress = 0;
        resetProgressFade();
    }

    @Override
    public void guiUpdate() {
        ignoredBlocks.setVisible(ignoreBlocksToggle.isToggled(), this);
    }

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!canPlace()) {
            disablePlacing();
            return;
        }
        placeQueued = false;

        runTargetSelection();

        if (mc.currentScreen != null) disablePlacing();
        if (!isPlacementActive() || targetHitPos == null) return;

        float baseYaw = e.getBaseYaw() != null ? e.getBaseYaw() : RotationUtils.serverRotations[0];
        float basePitch = e.getBasePitch() != null ? e.getBasePitch() : RotationUtils.serverRotations[1];
        float[] sm = RotationUtils.smoothRotation(baseYaw, basePitch, aimYaw, aimPitch,
                (int) speed.getInput(), (float) randomization.getInput());
        double r = REACH;
        MovingObjectPosition mop = RotationUtils.rayCastBlock(r, sm[0], sm[1]);

        if (mop != null) {
            BlockPos hitBlock = mop.getBlockPos();
            EnumFacing side = mop.sideHit;
            if (hitBlock.equals(targetHitPos) && side == targetSide) {
                double tol = rotationTol.getInput();
                if (Math.abs(MathHelper.wrapAngleTo180_float(sm[0] - baseYaw)) <= tol
                        && Math.abs(sm[1] - basePitch) <= tol) {
                    hitAt = hitBlock;
                    hitSide = side;
                    placeAt = mop.hitVec;
                    placeQueued = true;
                }
            }
        }

        e.requestRotation(mindless.rotation.RotationSource.AUTO_BLOCKIN, sm[0], sm[1]);
    }

    private MovingObjectPosition validatedPlacementHit() {
        mindless.runtime.SentPlayerState.Snapshot sent = mindless.runtime.SentPlayerState.snapshot();
        if (sent == null || hitAt == null || hitSide == null || BlockUtils.replaceable(hitAt)
                || mc.theWorld.getBlockState(hitAt).getBlock().getMaterial().isLiquid()
                || !BlockUtils.replaceable(hitAt.offset(hitSide))) return null;
        Vec3 eyes = new Vec3(sent.x, sent.y + mc.thePlayer.getEyeHeight(), sent.z);
        Vec3 look = Utils.getLookVec(sent.yaw, sent.pitch);
        MovingObjectPosition hit = mc.theWorld.rayTraceBlocks(eyes,
                eyes.addVector(look.xCoord * REACH, look.yCoord * REACH, look.zCoord * REACH), false, false, false);
        return hit != null && hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                && hitAt.equals(hit.getBlockPos()) && hitSide == hit.sideHit ? hit : null;
    }

    private boolean canPlace() {
        return Utils.nullCheck() && mc.currentScreen == null
                && isActivationPressed()
                && (!disableInCreative.isToggled() || !mc.thePlayer.capabilities.isCreativeMode)
                && (ModuleManager.bedAura == null || !ModuleManager.bedAura.controlsInteractions());
    }

    private boolean isActivationPressed() {
        return activationKey.getKey() == 0 || activationKey.isPressed();
    }

    private void runTargetSelection() {
        clearAim();

        if (!isActivationPressed() || mc.currentScreen != null) {
            disablePlacing();
            circleProgress = 0f;
            return;
        }

        int strongSlot = pickBlockSlot(true);
        int weakSlot = pickBlockSlot(false);
        if (strongSlot == -1 && weakSlot == -1) {
            disablePlacing();
            return;
        }

        plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);

        if (!getTarget()) {
            disablePlacing();
            return;
        }

        if (lastTargetAdjacent) plannedSlot = (strongSlot != -1 ? strongSlot : weakSlot);
        else plannedSlot = (weakSlot != -1 ? weakSlot : strongSlot);

        if (!isPlacementActive()) enablePlacing();
        if (!isPlacementActive()) return;
        placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindAttack), false);
        placementLease.claimInput(PlacementRuntime.input(mc.gameSettings.keyBindUseItem), false);
        equipPlannedSlot();
    }


    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!canPlace()) {
            disablePlacing();
            return;
        }

        if (placeQueued) {
            placeQueued = false;
            if (hitAt != null && hitSide != null && placeAt != null
                    && mc.thePlayer.inventory.currentItem == plannedSlot
                    && mindless.runtime.AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController) == plannedSlot
                    && mc.thePlayer.getHeldItem() != null && mc.thePlayer.getHeldItem().getItem() instanceof ItemBlock) {
                BlockPos placementPos = hitAt.offset(hitSide);
                final MovingObjectPosition hit = validatedPlacementHit();

                if (hit != null && !isNearBed(placementPos) && isPlacementActive()
                        && placementLease.tryControllerAction(Utils.getBaseClientTick(), new PlacementLease.ControllerAction() {
                    @Override
                    public boolean run() {
                        return mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld,
                                mc.thePlayer.getHeldItem(), hitAt, hitSide, hit.hitVec);
                    }
                })) {
                    mc.thePlayer.swingItem();
                }
            }
        }

        fillCount = 0;
        fillTargetCount = 0;

        if (isActivationPressed() && mc.currentScreen == null) {
            BlockPos feet = new BlockPos(
                    MathHelper.floor_double(mc.thePlayer.posX),
                    MathHelper.floor_double(mc.thePlayer.posY),
                    MathHelper.floor_double(mc.thePlayer.posZ)
            );

            countProgressPosition(feet.up().up());

            for (EnumFacing dir : HORIZONTALS) {
                BlockPos lowerSide = feet.offset(dir);

                countProgressPosition(lowerSide);
                countProgressPosition(lowerSide.up());
            }

            if (fillCount != lastFillCount
                    || fillTargetCount != lastFillTargetCount) {
                animStartProgress = circleProgress;

                if (fillTargetCount <= 0) {
                    animTargetProgress = 0f;
                } else {
                    animTargetProgress = Math.max(
                            0f,
                            Math.min(1f, fillCount / fillTargetCount)
                    );
                }

                animStartTime = System.currentTimeMillis();
                lastFillCount = fillCount;
                lastFillTargetCount = fillTargetCount;
            }
        }
        else {
            lastFillCount = -1;
            lastFillTargetCount = -1;
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) {
            return;
        }

        int progressMode = (int) showProgress.getInput() - 1;

        if (mc.currentScreen != null || progressMode == -1) {
            resetProgressFade();
            return;
        }

        if (disableInCreative.isToggled()
                && mc.thePlayer.capabilities.isCreativeMode) {
            resetProgressFade();
            return;
        }

        long elapsed = System.currentTimeMillis() - animStartTime;

        if (elapsed < 50L) {
            float t = (float) elapsed / 50f;
            circleProgress = lerp(
                    animStartProgress,
                    animTargetProgress,
                    quadInOutEasing(t)
            );
        } else {
            circleProgress = animTargetProgress;
        }

        boolean hasValidProgress = fillTargetCount > 0 && fillCount > 0;
        int alpha = updateProgressAlpha(hasValidProgress);
        if (alpha <= 10) {
            return;
        }

        float ratio = Math.max(0f, Math.min(1f, circleProgress));

        if (progressMode == 1) {
            renderPercentage(ratio, alpha);
        } else {
            renderCircleProgress(ratio, alpha);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (isPlacementActive() && e.button > -1) {
            e.setCanceled(true);
        }
    }

    private void enablePlacing() {
        if (isPlacementActive()) return;
        if (placing) disablePlacing();
        long tick = Utils.getBaseClientTick();
        PlacementCoordinator.get().announce(this, PlacementCoordinator.Priority.AUTO_BLOCK_IN,
                mc.thePlayer, mc.theWorld, tick + 1L);
        PlacementLease lease = PlacementCoordinator.get().acquire(
                this, PlacementCoordinator.Priority.AUTO_BLOCK_IN, mc.thePlayer, mc.theWorld, tick);
        if (lease == null) return;
        placementLease = lease;
        placing = true;
    }

    private int updateProgressAlpha(boolean shouldShow) {
        if (shouldShow) {
            if (progressFadeTimer != null) {
                progressFadeTimer = null;
                progressFadeInTimer = new Timer(150);
                progressFadeInTimer.start();
            } else if (progressFadeInTimer == null
                    && previousProgressAlpha < 255f) {
                progressFadeInTimer = new Timer(150);
                progressFadeInTimer.start();
            }

            float alpha;

            if (progressFadeInTimer != null) {
                alpha = progressFadeInTimer.getValueFloat(10, 255, 1);

                if (alpha >= 255f) {
                    alpha = 255f;
                    progressFadeInTimer = null;
                }
            } else {
                alpha = 255f;
            }

            previousProgressAlpha = alpha;
            return (int) alpha;
        }

        if (previousProgressAlpha <= 10f) {
            resetProgressFade();
            return 0;
        }

        if (progressFadeTimer == null) {
            progressFadeTimer = new Timer(150);
            progressFadeTimer.start();
            progressFadeInTimer = null;
        }

        float alpha = 255f - progressFadeTimer.getValueInt(0, 255, 1);

        if (alpha <= 10f) {
            resetProgressFade();
            return 0;
        }

        previousProgressAlpha = alpha;
        return (int) alpha;
    }

    private void renderCircleProgress(float ratio, int alpha) {
        ScaledResolution resolution = new ScaledResolution(mc);

        float centerX = resolution.getScaledWidth() / 2f + 0.5f;
        float centerY = resolution.getScaledHeight() / 2f + 0.5f;
        float radius = 10f;
        float thickness = 3f;
        float alphaFloat = alpha / 255f;

        RenderUtils.draw2DCircle(
                centerX,
                centerY,
                radius,
                100,
                thickness,
                0f,
                0f,
                0f,
                alphaFloat * 0.5f
        );

        if (ratio >= 0.999f) {
            RenderUtils.draw2DCircle(
                    centerX,
                    centerY,
                    radius,
                    100,
                    thickness,
                    0f,
                    1f,
                    0f,
                    alphaFloat
            );
            return;
        }

        int red = (int) ((1f - ratio) * 255f + 0.5f);
        int green = (int) (ratio * 255f + 0.5f);

        int color = ((alpha & 0xFF) << 24)
                | ((red & 0xFF) << 16)
                | ((green & 0xFF) << 8);

        float startAngle = 90f;
        float endAngle = startAngle + ratio * 360f + 0.5f;

        RenderUtils.draw2DCircleArc(
                centerX,
                centerY,
                radius,
                startAngle,
                endAngle,
                thickness,
                color
        );
    }

    private void renderPercentage(float ratio, int alpha) {
        ScaledResolution resolution = new ScaledResolution(mc);

        String text = Math.round(ratio * 100f) + "%";

        float x = resolution.getScaledWidth() / 2f
                - mc.fontRendererObj.getStringWidth(text) / 2f;
        float y = resolution.getScaledHeight() / 2f + 12f;

        int color = Utils.mergeAlpha(ratio >= 0.999f ? 0x00FF00 : 0xFFFFFF, alpha);

        GL11.glPushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(
                GL11.GL_SRC_ALPHA,
                GL11.GL_ONE_MINUS_SRC_ALPHA
        );

        mc.fontRendererObj.drawStringWithShadow(
                text,
                x,
                y,
                color
        );

        GlStateManager.disableBlend();
        GL11.glPopMatrix();
    }

    private void resetProgressFade() {
        progressFadeTimer = null;
        progressFadeInTimer = null;
        previousProgressAlpha = 0f;
    }

    private void disablePlacing() {
        placeQueued = false;
        hitAt = null;
        hitSide = null;
        placeAt = null;
        clearAim();
        RotationHelper.get().release(RotationSource.AUTO_BLOCKIN);
        PlacementCoordinator.get().cancel(this);
        placing = false;
        releasePlacement();
        plannedSlot = -1;
    }

    private void clearAim() {
        targetHitPos = null;
        targetSide = null;
    }

    private void equipPlannedSlot() {
        if (plannedSlot != -1 && placementLease != null) {
            placementLease.claimHotbar(PlacementRuntime.hotbar(), plannedSlot);
        }
    }

    private boolean isPlacementActive() {
        return placing && placementLease != null && placementLease.isActive();
    }

    private void releasePlacement() {
        if (placementLease != null) {
            placementLease.release();
            placementLease = null;
        }
    }


    private int pickBlockSlot(boolean preferStrong) {
        int best = -1;
        float bestScore = preferStrong ? -1 : Float.MAX_VALUE;

        for (int slot = 8; slot >= 0; --slot) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[slot];
            if (s == null || s.stackSize == 0) continue;
            if (!(s.getItem() instanceof ItemBlock)) continue;
            if (ignoreBlocksToggle.isToggled() && ignoredBlocks.matches(s)) continue;

            Block block = ((ItemBlock) s.getItem()).getBlock();
            if (block instanceof BlockLadder) continue;

            float score = BlockUtils.getFistBreakTicks(block);

            if (preferStrong ? score > bestScore : score < bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }


    private boolean getTarget() {
        AimResult result = roofAim();
        if (result == null) result = sidesAim();
        if (result == null) return false;

        BlockPos placed = result.supportBlock.offset(result.face);
        lastTargetAdjacent = isDirectAdjacentPlacement(placed);

        targetHitPos = result.supportBlock;
        targetSide = result.face;
        aimYaw = result.yaw;
        aimPitch = result.pitch;
        return true;
    }

    private AimResult roofAim() {
        Vec3 pos = new Vec3(mc.thePlayer.posX, mc.thePlayer.posY, mc.thePlayer.posZ);
        BlockPos aboveHead = new BlockPos(
                MathHelper.floor_double(pos.xCoord),
                MathHelper.floor_double(pos.yCoord) + 2,
                MathHelper.floor_double(pos.zCoord)
        );
        if (!BlockUtils.replaceable(aboveHead)) return null;

        if (plannedSlot < 0 || plannedSlot > 8) return null;
        ItemStack held = mc.thePlayer.inventory.mainInventory[plannedSlot];
        double r = REACH;
        Vec3 eye = new Vec3(pos.xCoord, pos.yCoord + mc.thePlayer.getEyeHeight(), pos.zCoord);
        double r2 = r * r;
        double rp12 = (r + 1) * (r + 1);

        int minY = MathHelper.floor_double(eye.yCoord) + 1;
        int maxY = MathHelper.floor_double(eye.yCoord + r);
        int minX = MathHelper.floor_double(eye.xCoord - r);
        int maxX = MathHelper.floor_double(eye.xCoord + r);
        int minZ = MathHelper.floor_double(eye.zCoord - r);
        int maxZ = MathHelper.floor_double(eye.zCoord + r);

        ArrayList<BlockCandidate> cands = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    double dx = (x + 0.5) - eye.xCoord;
                    double dy = (y + 0.5) - eye.yCoord;
                    double dz = (z + 0.5) - eye.zCoord;
                    if (dx * dx + dy * dy + dz * dz > rp12) continue;

                    BlockPos bp = new BlockPos(x, y, z);
                    if (BlockUtils.replaceable(bp)) continue;
                    Block block = BlockUtils.getBlock(bp);
                    if (BlockUtils.isInteractable(block) || block instanceof BlockFence || block instanceof BlockWall) continue;

                    double d2 = BlockUtils.dist2PointAABB(eye, bp);
                    if (d2 > r2) continue;

                    cands.add(new BlockCandidate(d2, bp));
                }
            }
        }

        cands.sort((a, b) -> Double.compare(a.dist, b.dist));

        for (BlockCandidate cand : cands) {
            AimResult res = getBestRotationsToBlock(held, cand.pos, eye, r, minY);
            if (res != null) return res;
        }
        return null;
    }

    private AimResult getBestRotationsToBlock(ItemStack held, BlockPos targetCell, Vec3 eye, double reachVal, int minY) {
        float baseYaw = RotationUtils.serverRotations[0];
        float basePitch = RotationUtils.serverRotations[1];

        boolean faceUp = Math.abs(eye.yCoord - (targetCell.getY() + 1)) < Math.abs(eye.yCoord - targetCell.getY());
        boolean faceSouth = Math.abs(eye.zCoord - (targetCell.getZ() + 1)) < Math.abs(eye.zCoord - targetCell.getZ());
        boolean faceEast = Math.abs(eye.xCoord - (targetCell.getX() + 1)) < Math.abs(eye.xCoord - targetCell.getX());

        double bx = targetCell.getX(), by = targetCell.getY(), bz = targetCell.getZ();
        double jit = GRID_STEP * 0.1;

        ArrayList<RotationCandidate> cands = new ArrayList<>((GRID_N + 1) * (GRID_N + 1) * 3 + 1);
        cands.add(new RotationCandidate(0, baseYaw, basePitch));

        for (int row = 0; row <= GRID_N; row++) {
            double v = clamp01(row * GRID_STEP + jitter(jit));
            for (int col = 0; col <= GRID_N; col++) {
                double u = clamp01(col * GRID_STEP + jitter(jit));

                float[] rY = RotationUtils.getRotationsFromEye(eye,
                        bx + u, faceUp ? by + 1 - GRID_INSET : by + GRID_INSET, bz + v);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rY[0] - baseYaw)) + Math.abs(rY[1] - basePitch),
                        rY[0], rY[1]));

                float[] rZ = RotationUtils.getRotationsFromEye(eye,
                        bx + u, by + v, faceSouth ? bz + 1 - GRID_INSET : bz + GRID_INSET);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rZ[0] - baseYaw)) + Math.abs(rZ[1] - basePitch),
                        rZ[0], rZ[1]));

                float[] rX = RotationUtils.getRotationsFromEye(eye,
                        faceEast ? bx + 1 - GRID_INSET : bx + GRID_INSET, by + v, bz + u);
                cands.add(new RotationCandidate(
                        Math.abs(MathHelper.wrapAngleTo180_float(rX[0] - baseYaw)) + Math.abs(rX[1] - basePitch),
                        rX[0], rX[1]));
            }
        }

        cands.sort((a, b) -> Double.compare(a.cost, b.cost));

        int byY = targetCell.getY();
        for (RotationCandidate c : cands) {
            MovingObjectPosition mop = RotationUtils.rayCastBlock(reachVal, c.yaw, c.pitch);
            if (mop == null) continue;
            BlockPos hitBlock = mop.getBlockPos();
            EnumFacing face = mop.sideHit;
            if (hitBlock.equals(targetCell) && hitBlock.getY() >= minY
                    && !(face == EnumFacing.DOWN && byY == minY)
                    && BlockUtils.canPlaceBlockOnSide(held, hitBlock, face)) {
                BlockPos placementPos = hitBlock.offset(face);

                if (isNearBed(placementPos)) {
                    continue;
                }

                return new AimResult(hitBlock, face, c.yaw, c.pitch);
            }
        }
        return null;
    }

    private void countProgressPosition(BlockPos pos) {
        if (isNearBed(pos)) {
            return;
        }

        fillTargetCount++;

        if (!BlockUtils.replaceable(pos)) {
            fillCount++;
        }
    }

    private AimResult sidesAim() {
        BlockPos feet = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        BlockPos head = feet.up();
        double r = REACH;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);

        ArrayList<BlockPos> baseline = new ArrayList<>(8);
        for (EnumFacing dir : HORIZONTALS) {
            baseline.add(feet.offset(dir));
            baseline.add(head.offset(dir));
        }

        ArrayList<BlockPos> primaryGoals = new ArrayList<>(baseline.size());
        for (BlockPos pos : baseline) {
            if (!BlockUtils.replaceable(pos)) continue;
            if (!hasReplaceableNeighbor(pos, feet, head)) continue;
            primaryGoals.add(pos);
        }
        if (primaryGoals.isEmpty()) return null;

        Vec3 enemyPos = Utils.getClosestPlayerPos(100);
        if (enemyPos != null) {
            baseline.sort((a, b) -> {
                double da = sq(a.getX() + 0.5 - enemyPos.xCoord)
                        + sq(a.getY() + 0.5 - enemyPos.yCoord)
                        + sq(a.getZ() + 0.5 - enemyPos.zCoord);
                double db = sq(b.getX() + 0.5 - enemyPos.xCoord)
                        + sq(b.getY() + 0.5 - enemyPos.yCoord)
                        + sq(b.getZ() + 0.5 - enemyPos.zCoord);
                return Double.compare(da, db);
            });
            int picked = 0;
            for (int i = 0; i < baseline.size() && picked < 3; i++) {
                BlockPos pos = baseline.get(i);
                if (!BlockUtils.replaceable(pos)) continue;
                if (!hasReplaceableNeighbor(pos, feet, head)) continue;
                AimResult rEnemy = findBestForGoals(Collections.singletonList(pos), r, eye);
                if (rEnemy != null) return rEnemy;
                picked++;
            }
        }

        AimResult result = findBestForGoals(primaryGoals, r, eye);
        if (result != null) return result;

        ArrayList<BlockPos> frontier = new ArrayList<>(primaryGoals);
        HashSet<Long> seen = new HashSet<>(frontier.size() * 8);
        for (BlockPos g : frontier) seen.add(g.toLong());

        for (int iter = 0; iter < 5; iter++) {
            if (frontier.isEmpty()) break;

            ArrayList<BlockPos> layer = new ArrayList<>(frontier.size() * 3);
            for (BlockPos g : frontier) {
                for (EnumFacing f : EnumFacing.values()) {
                    BlockPos s = g.offset(f);
                    if (!BlockUtils.replaceable(s)) continue;
                    if (!seen.add(s.toLong())) continue;
                    layer.add(s);
                }
            }

            if (!layer.isEmpty()) {
                AimResult rLayer = findBestForGoals(layer, r, eye);
                if (rLayer != null) return rLayer;
            }
            frontier = layer;
        }
        return null;
    }

    private boolean hasReplaceableNeighbor(BlockPos pos, BlockPos... exclude) {
        for (EnumFacing facing : EnumFacing.values()) {
            BlockPos neighbor = pos.offset(facing);
            if (!BlockUtils.replaceable(neighbor)) continue;

            boolean excluded = false;
            for (BlockPos excludedPos : exclude) {
                if (neighbor.equals(excludedPos)) {
                    excluded = true;
                    break;
                }
            }
            if (!excluded) return true;
        }
        return false;
    }

    private AimResult findBestForGoals(List<BlockPos> goals, double reachVal, Vec3 eye) {
        if (goals == null || goals.isEmpty()) return null;
        if (plannedSlot < 0 || plannedSlot > 8) return null;

        ItemStack held = mc.thePlayer.inventory.mainInventory[plannedSlot];
        float curYaw = RotationUtils.serverRotations[0];
        float curPitch = RotationUtils.serverRotations[1];

        MovingObjectPosition now = RotationUtils.rayCastBlock(reachVal, curYaw, curPitch);
        if (now != null) {
            BlockPos support = now.getBlockPos();
            EnumFacing faceHit = now.sideHit;

            if (!BlockUtils.replaceable(support) && BlockUtils.canPlaceBlockOnSide(held, support, faceHit)) {
                for (BlockPos goal : goals) {
                    AimResult ok = tryPlacement(reachVal, RotationUtils.serverRotations[0],
                            RotationUtils.serverRotations[1], support, faceHit, goal);
                    if (ok != null) return ok;
                }
            }
        }

        double jit = GRID_STEP * 0.1;
        double insetTop = 1 - GRID_INSET - 1e-3;
        double insetBot = GRID_INSET + 1e-3;

        ArrayList<PlacementCandidate> cands = new ArrayList<>(Math.max(16, goals.size() * 6 * (GRID_N + 1) * (GRID_N + 1)));

        for (BlockPos g : goals) {
            for (SupportOffset s : SUPPORTS) {
                BlockPos support = new BlockPos(g.getX() + s.dx, g.getY() + s.dy, g.getZ() + s.dz);
                if (BlockUtils.replaceable(support) || !BlockUtils.canPlaceBlockOnSide(held, support, s.face)) continue;

                double sx = support.getX(), sy = support.getY(), sz = support.getZ();

                for (int row = 0; row <= GRID_N; row++) {
                    boolean ltr = (row & 1) == 0;
                    double v = clamp01(row * GRID_STEP + jitter(jit));

                    for (int col = 0; col <= GRID_N; col++) {
                        double cu = clamp01(col * GRID_STEP + jitter(jit));
                        double u = ltr ? cu : 1.0 - cu;

                        double px, py, pz;
                        if (s.dy != 0) {
                            px = sx + u; pz = sz + v;
                            py = sy + (s.dy < 0 ? insetTop : insetBot);
                        } else if (s.dz != 0) {
                            px = sx + u; py = sy + v;
                            pz = sz + (s.dz < 0 ? insetTop : insetBot);
                        } else {
                            pz = sz + u; py = sy + v;
                            px = sx + (s.dx < 0 ? insetTop : insetBot);
                        }

                        float[] rot = RotationUtils.getRotationsFromEye(eye, px, py, pz);
                        float dYaw = Math.abs(MathHelper.wrapAngleTo180_float(rot[0] - curYaw));
                        float dPit = Math.abs(rot[1] - curPitch);
                        if (dYaw < 0.1f && dPit < 0.1f) continue;

                        cands.add(new PlacementCandidate(dYaw + dPit, rot[0], rot[1], support, s.face, g));
                    }
                }
            }
        }

        if (cands.isEmpty()) return null;

        cands.sort((a, b) -> Double.compare(a.cost, b.cost));

        for (PlacementCandidate c : cands) {
            AimResult ok = tryPlacement(reachVal, c.yaw, c.pitch, c.support, c.face, c.goal);
            if (ok != null) return ok;
        }
        return null;
    }


    private AimResult tryPlacement(double reachVal, float yaw, float pit,
                                   BlockPos expectedSupport, EnumFacing expectedFace,
                                   BlockPos goal) {
        MovingObjectPosition mop = RotationUtils.rayCastBlock(reachVal, yaw, pit);
        if (mop == null) return null;

        BlockPos hitBlock = mop.getBlockPos();
        EnumFacing faceHit = mop.sideHit;

        if (!hitBlock.equals(expectedSupport)) return null;
        if (faceHit != expectedFace) return null;

        BlockPos placed = hitBlock.offset(faceHit);

        if (!placed.equals(goal)) return null;
        if (isNearBed(placed)) return null;

        return new AimResult(hitBlock, faceHit, yaw, pit);
    }

    private boolean isDirectAdjacentPlacement(BlockPos p) {
        BlockPos feet = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY),
                MathHelper.floor_double(mc.thePlayer.posZ)
        );
        int dx = p.getX() - feet.getX();
        int dy = p.getY() - feet.getY();
        int dz = p.getZ() - feet.getZ();
        if (dx == 0 && dz == 0 && dy == 2) return true;
        return (dy == 0 || dy == 1)
                && ((Math.abs(dx) == 1 && dz == 0) || (Math.abs(dz) == 1 && dx == 0));
    }

    private boolean isNearBed(BlockPos placementPos) {
        if (!skipNearBed.isToggled()) return false;

        if (BlockUtils.getBlock(placementPos) instanceof BlockBed) {
            return true;
        }

        for (EnumFacing facing : EnumFacing.values()) {
            if (BlockUtils.getBlock(placementPos.offset(facing)) instanceof BlockBed) {
                return true;
            }
        }

        return false;
    }

    private static float lerp(float start, float end, float t) {
        return start + (end - start) * t;
    }

    private static float quadInOutEasing(float t) {
        if (t < 0.5f) return 2f * t * t;
        return -1f + (4f - 2f * t) * t;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    private static double jitter(double range) {
        return range > 0 ? (Math.random() * 2 - 1) * range : 0;
    }


    private static class SupportOffset {
        final int dx, dy, dz;
        final EnumFacing face;
        SupportOffset(int dx, int dy, int dz, EnumFacing face) {
            this.dx = dx; this.dy = dy; this.dz = dz; this.face = face;
        }
    }

    private static class BlockCandidate {
        final double dist;
        final BlockPos pos;
        BlockCandidate(double dist, BlockPos pos) { this.dist = dist; this.pos = pos; }
    }

    private static class RotationCandidate {
        final double cost;
        final float yaw, pitch;
        RotationCandidate(double cost, float yaw, float pitch) {
            this.cost = cost; this.yaw = yaw; this.pitch = pitch;
        }
    }

    private static class PlacementCandidate {
        final double cost;
        final float yaw, pitch;
        final BlockPos support, goal;
        final EnumFacing face;
        PlacementCandidate(double cost, float yaw, float pitch, BlockPos support, EnumFacing face, BlockPos goal) {
            this.cost = cost; this.yaw = yaw; this.pitch = pitch;
            this.support = support; this.face = face; this.goal = goal;
        }
    }

    private static class AimResult {
        final BlockPos supportBlock;
        final EnumFacing face;
        final float yaw, pitch;
        AimResult(BlockPos supportBlock, EnumFacing face, float yaw, float pitch) {
            this.supportBlock = supportBlock; this.face = face; this.yaw = yaw; this.pitch = pitch;
        }
    }
}
