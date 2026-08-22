package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.movement.LongJump;
import mindless.module.impl.combat.KillAura;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.*;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class Scaffold extends Module {

    private static final EnumFacing[] SIDES = {EnumFacing.NORTH, EnumFacing.SOUTH, EnumFacing.EAST, EnumFacing.WEST};
    private static final String[] MODES = {"Normal", "Telly", "Breezily", "God Bridge"};
    private static final String[] SPRINT_MODES = {"None", "Vanilla", "Universal", "NCP", "Legit"};
    private static final String[] SEARCH_MODES = {"Normal", "Ultra Safe", "Secondary"};
    private static final String[] RAYCAST_MODES = {"None", "Normal", "Strict"};
    private static final String[] SWAP_MODES = {"Client", "Server"};

    private final SliderSetting mode;
    private final SliderSetting sprintMode;
    private final SliderSetting rotSpeed;
    private final SliderSetting searchAlgorithm;
    private final SliderSetting rayCast;
    private final SliderSetting placeDelay;
    private final SliderSetting expand;
    private final SliderSetting swapMode;
    private final SliderSetting tellyStraightTicks;
    private final SliderSetting tellyDiagonalTicks;
    private final SliderSetting tellyJumpDownTicks;
    private final SliderSetting towerMode;
    private final ButtonSetting towerMove;
    public final ButtonSetting safeWalk;
    private final ButtonSetting swing;
    public final ButtonSetting autoSwap;
    private final ButtonSetting autoJump;
    private final ButtonSetting keepY;
    private final ButtonSetting sneak;
    private final ButtonSetting moveFix;
    public final ButtonSetting showBlockCount;
    public final ButtonSetting sendPacket;

    public Map<BlockPos, mindless.utility.Timer> highlight = new HashMap<>();
    public AtomicInteger lastSlot = new AtomicInteger(-1);
    public boolean hasSwapped;
    public boolean moduleEnabled;
    public boolean isEnabled;
    public boolean canBlockFade;
    public boolean fastScaffoldKeepY;

    private boolean active;
    private float targetYaw, targetPitch;
    private boolean hasTarget;
    private BlockPos queuedBlock;
    private EnumFacing queuedFace;
    private Vec3 queuedHitVec;
    private boolean placeQueued;
    private int ticksOnAir;
    private int blocksPlaced;
    private int startY;
    private long lastPlaceTime;
    private boolean tellyNoPlace;
    private int offGroundTicks;
    private int onGroundTicks;

    public Scaffold() {
        super("Scaffold", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(rotSpeed = new SliderSetting("Rotation speed", 15, 1, 30, 1));
        this.registerSetting(sprintMode = new SliderSetting("Sprint", 0, SPRINT_MODES));
        this.registerSetting(searchAlgorithm = new SliderSetting("Search algorithm", 0, SEARCH_MODES));
        this.registerSetting(rayCast = new SliderSetting("Ray cast", 1, RAYCAST_MODES));
        this.registerSetting(placeDelay = new SliderSetting("Place delay", " tick", 0, 0, 10, 1));
        this.registerSetting(expand = new SliderSetting("Expand", 0, 0, 4, 1));
        this.registerSetting(swapMode = new SliderSetting("Swap mode", 0, SWAP_MODES));
        this.registerSetting(tellyStraightTicks = new SliderSetting("Telly straight ticks", 6, 0, 8, 1));
        this.registerSetting(tellyDiagonalTicks = new SliderSetting("Telly diagonal ticks", 4, 0, 8, 1));
        this.registerSetting(tellyJumpDownTicks = new SliderSetting("Telly jump down ticks", 1, 0, 8, 1));
        this.registerSetting(towerMode = new SliderSetting("Tower mode", 0, new String[]{"None", "Vanilla", "Hypixel", "NCP"}));
        this.registerSetting(towerMove = new ButtonSetting("Tower move", true));
        this.registerSetting(safeWalk = new ButtonSetting("Safewalk", true));
        this.registerSetting(swing = new ButtonSetting("Swing", true));
        this.registerSetting(autoSwap = new ButtonSetting("Auto swap", true));
        this.registerSetting(autoJump = new ButtonSetting("Auto jump", false));
        this.registerSetting(keepY = new ButtonSetting("Keep Y", false));
        this.registerSetting(sneak = new ButtonSetting("Sneak", false));
        this.registerSetting(moveFix = new ButtonSetting("Move fix", true));
        this.registerSetting(showBlockCount = new ButtonSetting("Show block count", true));
        this.registerSetting(sendPacket = new ButtonSetting("Send packet", true));
        this.alwaysOn = true;
    }

    @Override
    public void onEnable() {
        moduleEnabled = true;
        isEnabled = true;
        lastSlot.set(mc.thePlayer != null ? mc.thePlayer.inventory.currentItem : -1);
        hasSwapped = false;
        active = false;
        hasTarget = false;
        fastScaffoldKeepY = false;
        ticksOnAir = 0;
        blocksPlaced = 0;
        startY = mc.thePlayer != null ? MathHelper.floor_double(mc.thePlayer.posY) : 0;
        lastPlaceTime = 0L;
        tellyNoPlace = false;
        offGroundTicks = 0;
        onGroundTicks = 0;
    }

    @Override
    public void onDisable() {
        moduleEnabled = false;
        isEnabled = false;
        if (SlotManager.isActive()) SlotManager.swapBack();
        else if (mc.thePlayer != null && lastSlot.get() != -1)
            mc.thePlayer.inventory.currentItem = lastSlot.get();
        lastSlot.set(-1);
        hasSwapped = false;
        active = false;
        hasTarget = false;
        fastScaffoldKeepY = false;
        ticksOnAir = 0;
        blocksPlaced = 0;
        placeQueued = false;
        tellyNoPlace = false;
        offGroundTicks = 0;
        onGroundTicks = 0;
        if (mc.thePlayer != null) {
            mc.thePlayer.setSprinting(false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean safewalk() { return isEnabled && safeWalk.isToggled() && mc.thePlayer != null && mc.thePlayer.onGround; }
    public boolean canSafewalk() { return isEnabled && safeWalk.isToggled(); }
    public boolean sprint() { return !isEnabled || (sendPacket.isToggled() && !holdingBlocks()); }
    public boolean stopRotation() { return active; }
    public boolean stopFastPlace() { return isEnabled; }
    public void rotateForward() {}

    public boolean holdingBlocks() {
        if (!autoSwap.isToggled()) return isBlockItem(actualHeldStack());
        return getBlockSlot() != -1;
    }

    public int totalBlocks() {
        int n = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[i];
            if (isBlockItem(s)) n += s.stackSize;
        }
        return n;
    }

    public boolean onPacketSent(C0BPacketEntityAction packet) {
        return packet.getAction() != C0BPacketEntityAction.Action.START_SPRINTING || sendPacket.isToggled();
    }

    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    // ── Mouse cancel ──────────────────────────────────────────────────────────

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMouse(MouseEvent e) {
        if (!active) return;
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), false);
        if (e.button >= 0 && e.isCancelable()) e.setCanceled(true);
    }

    // ── Telly auto-jump ───────────────────────────────────────────────────────

    @SubscribeEvent
    public void onPrePlayerInput(PrePlayerInputEvent e) {
        if (!Utils.nullCheck() || !moduleEnabled) return;
        if ((autoJump.isToggled() || isTellyMode()) && !tellyNoPlace
                && mc.thePlayer.onGround && Utils.isMoving() && holdingBlocks()) {
            e.setJump(true);
            fastScaffoldKeepY = true;
        }
        if (sneak.isToggled() && blocksPlaced > 0) e.setSneak(blocksPlaced % 2 == 0);
    }

    // ── Rotation (same event + approach as BridgeAssist pre-place) ────────────

    @SubscribeEvent
    public void onClientRotation(ClientRotationEvent e) {
        if (!Utils.nullCheck() || !moduleEnabled || mc.currentScreen != null) return;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) return;

        if (!holdingBlocks() || !setSlot()) {
            isEnabled = false;
            hasSwapped = false;
            active = false;
            hasTarget = false;
            return;
        }
        if (LongJump.stopModules || KillAura.target != null) { active = false; return; }

        isEnabled = true;
        hasSwapped = true;
        canBlockFade = true;
        updateAirTicks();
        updateTellyState();

        if (towerMode.getInput() > 0 && mc.gameSettings.keyBindJump.isKeyDown()
                && (!Utils.isMoving() || towerMove.isToggled())) {
            applyTowerMotion();
        }

        // Sprint control
        int sprint = (int) sprintMode.getInput();
        if (sprint == 0 || sprint == 3) {
            mc.thePlayer.setSprinting(false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        } else if (sprint == 1) {
            mc.thePlayer.setSprinting(Utils.isMoving());
        }

        // Only scaffold when we actually need a block under us
        BlockPos feetBelow = new BlockPos(
                MathHelper.floor_double(mc.thePlayer.posX),
                MathHelper.floor_double(mc.thePlayer.posY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        boolean needsBlock = BlockUtils.replaceable(feetBelow) || !mc.thePlayer.onGround;

        if (!needsBlock) {
            active = false;
            hasTarget = false;
            placeQueued = false;
            return;
        }

        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        double reach = mc.playerController.getBlockReachDistance();

        TargetResult target = findTarget(baseYaw, basePitch, reach);
        if (target == null) {
            active = false;
            hasTarget = false;
            return;
        }

        active = true;
        hasTarget = true;
        targetYaw = target.yaw;
        targetPitch = target.pitch;

        if ((int) mode.getInput() == 2) {
            targetYaw = breezilyYaw();
            targetPitch = 80.0f;
        } else if ((int) mode.getInput() == 3) {
            targetPitch = 78.0f;
        } else if (isTellyMode() && mc.thePlayer.onGround && Utils.isMoving() && !canPlaceNow()) {
            targetYaw = mc.thePlayer.rotationYaw;
            targetPitch = 68.0f + (float) (Math.random() * 22.0f);
        }

        // Smooth rotation (same as BridgeAssist)
        float[] sm = RotationUtils.smoothRotation(baseYaw, basePitch, targetYaw, targetPitch, (int) rotSpeed.getInput(), 20f);
        e.setYaw(sm[0]);
        e.setPitch(sm[1]);

        // Queue placement if rotation is close enough to target
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(sm[0] - targetYaw));
        float pitchDiff = Math.abs(sm[1] - targetPitch);
        if (yawDiff < 30f && pitchDiff < 30f) {
            MovingObjectPosition mop = rayCast.getInput() == 0
                    ? null : RotationUtils.rayCastBlock(reach, sm[0], sm[1]);
            if (rayCast.getInput() == 0) {
                mop = RotationUtils.rayCastBlock(reach, sm[0], sm[1]);
            }
            if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && !BlockUtils.isInteractable(BlockUtils.getBlock(mop.getBlockPos()))
                    && mop.sideHit != EnumFacing.DOWN) {
                BlockPos placed = mop.getBlockPos().offset(mop.sideHit);
                int feetY = MathHelper.floor_double(mc.thePlayer.posY);
                if (BlockUtils.replaceable(placed) && (!keepY.isToggled() || placed.getY() < startY)) {
                    queuedBlock = mop.getBlockPos();
                    queuedFace = mop.sideHit;
                    queuedHitVec = mop.hitVec;
                    placeQueued = true;
                }
            }
        }
    }

    // ── Placement (PreUpdateEvent) ───────────────────────────────────────────

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent e) {
        if (!Utils.nullCheck() || !placeQueued) return;
        placeQueued = false;

        if (queuedBlock == null || queuedFace == null || queuedHitVec == null) return;

        ItemStack held = actualHeldStack();
        if (!isBlockItem(held)) return;

        if (!canPlaceNow()) return;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, queuedBlock, queuedFace, queuedHitVec)) {
            lastPlaceTime = mc.thePlayer.ticksExisted;
            blocksPlaced++;
            highlight.put(queuedBlock.offset(queuedFace), null);
            if (swing.isToggled()) {
                mc.thePlayer.swingItem();
            } else {
                mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
            }
        }
        queuedBlock = null;
        queuedFace = null;
        queuedHitVec = null;
    }

    // ── Target finding — computes yaw+pitch to face block regardless of camera ─

    private TargetResult findTarget(float baseYaw, float basePitch, double reach) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        AxisAlignedBB bbox = mc.thePlayer.getEntityBoundingBox();
        int standY = keepY.isToggled() ? startY - 1 : MathHelper.floor_double(bbox.minY) - 1;
        int minX = MathHelper.floor_double(bbox.minX);
        int maxX = MathHelper.floor_double(bbox.maxX);
        int minZ = MathHelper.floor_double(bbox.minZ);
        int maxZ = MathHelper.floor_double(bbox.maxZ);

        ArrayList<FaceTarget> targets = new ArrayList<>();
        int radius = Math.max(1, (int) expand.getInput() + 1);
        for (int y = standY; y >= standY - 1; y--) {
            for (int x = minX - radius; x <= maxX + radius; x++) {
                for (int z = minZ - radius; z <= maxZ + radius; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (BlockUtils.replaceable(pos)) continue;
                    if (BlockUtils.isInteractable(BlockUtils.getBlock(pos))) continue;

                    BlockPos above = pos.up();
                    if (BlockUtils.replaceable(above))
                        targets.add(new FaceTarget(pos, EnumFacing.UP));
                    for (EnumFacing face : SIDES) {
                        BlockPos adjacent = pos.offset(face);
                        if (BlockUtils.replaceable(adjacent))
                            targets.add(new FaceTarget(pos, face));
                    }
                }
            }
        }
        if (targets.isEmpty()) return null;

        // Yuri-style search: choose smallest server-rotation delta among valid ray casts.
        float bestYaw = Float.NaN, bestPitch = Float.NaN;
        float bestCost = Float.MAX_VALUE;

        for (FaceTarget t : targets) {
            double hx = t.block.getX() + 0.5 + t.face.getDirectionVec().getX() * 0.5;
            double hy = t.block.getY() + 0.5 + t.face.getDirectionVec().getY() * 0.5;
            double hz = t.block.getZ() + 0.5 + t.face.getDirectionVec().getZ() * 0.5;
            double dx = hx - eye.xCoord;
            double dy = hy - eye.yCoord;
            double dz = hz - eye.zCoord;
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;

            float centerYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float centerPitch = MathHelper.clamp_float(
                    (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))), -90f, 90f);
            centerYaw = baseYaw + MathHelper.wrapAngleTo180_float(centerYaw - baseYaw);

            float[] candidate = ScaffoldUtils.computeRotations(t.block, t.face, baseYaw, basePitch,
                    reach, (int) searchAlgorithm.getInput(), (int) rayCast.getInput() == 2);
            if (candidate == null) continue;

            BlockPos placed = t.block.offset(t.face);
            if (!BlockUtils.replaceable(placed)) continue;
            int feetY = MathHelper.floor_double(mc.thePlayer.posY);
            if ((!keepY.isToggled() && placed.getY() >= feetY) || (keepY.isToggled() && placed.getY() >= startY)) continue;

            float cost = Math.abs(MathHelper.wrapAngleTo180_float(candidate[0] - baseYaw))
                    + Math.abs(candidate[1] - basePitch);
            if ((int) searchAlgorithm.getInput() == 1) cost += exposedNeighbors(t.block) * 2.5f;
            if ((int) searchAlgorithm.getInput() == 2)
                cost += Math.abs(MathHelper.wrapAngleTo180_float(candidate[0] - mc.thePlayer.rotationYaw)) * 0.25f;
            if (cost < bestCost) {
                bestCost = cost;
                bestYaw = candidate[0];
                bestPitch = candidate[1];
            }
        }

        if (Float.isNaN(bestYaw)) return null;
        return new TargetResult(bestYaw, bestPitch);
    }

    private int exposedNeighbors(BlockPos pos) {
        int exposed = 0;
        for (EnumFacing face : EnumFacing.HORIZONTALS) {
            BlockPos neighbor = pos.offset(face);
            if (BlockUtils.replaceable(neighbor)) exposed++;
        }
        return exposed;
    }

    private float[] findRotationCandidate(FaceTarget target, float centerYaw, float centerPitch,
                                           float baseYaw, float basePitch, double reach) {
        int algorithm = (int) searchAlgorithm.getInput();
        boolean strict = (int) rayCast.getInput() == 2;
        if (algorithm == 1) {
            float[] passes = {20f, 45f, 90f, 180f};
            float[] steps = {2.5f, 5f, 7.5f, 10f};
            for (int pass = 0; pass < passes.length; pass++) {
                float[] result = scanRotationGrid(target, centerYaw, centerPitch, baseYaw, basePitch,
                        passes[pass], steps[pass], strict, reach);
                if (result != null) return result;
            }
            return scanRotationGrid(target, centerYaw, centerPitch, baseYaw, basePitch, 180f, 10f, false, reach);
        }
        float yawRange = algorithm == 2 ? 15f : 180f;
        float step = algorithm == 2 ? 3f : 15f;
        return scanRotationGrid(target, centerYaw, centerPitch, baseYaw, basePitch, yawRange, step, strict, reach);
    }

    private float[] scanRotationGrid(FaceTarget target, float centerYaw, float centerPitch,
                                     float baseYaw, float basePitch, float range, float step,
                                     boolean strict, double reach) {
        float[] best = null;
        float bestCost = Float.MAX_VALUE;
        for (float yawOffset = -range; yawOffset <= range; yawOffset += step) {
            for (float pitchOffset = -range; pitchOffset <= range; pitchOffset += step) {
                float yaw = baseYaw + MathHelper.wrapAngleTo180_float(centerYaw + yawOffset - baseYaw);
                float pitch = MathHelper.clamp_float(centerPitch + pitchOffset, -90f, 90f);
                MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, yaw, pitch);
                if (mop == null || !mop.getBlockPos().equals(target.block) || mop.sideHit != target.face) continue;
                float cost = Math.abs(MathHelper.wrapAngleTo180_float(yaw - baseYaw)) + Math.abs(pitch - basePitch);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new float[]{yaw, pitch};
                }
            }
        }
        return best;
    }

    private void updateAirTicks() {
        if (mc.thePlayer.onGround) {
            onGroundTicks++;
            offGroundTicks = 0;
        } else {
            offGroundTicks++;
            onGroundTicks = 0;
        }
    }

    private void updateTellyState() {
        if (!isTellyMode()) {
            tellyNoPlace = false;
            return;
        }
        if (onGroundTicks == 1 || mc.theWorld.checkBlockCollision(
                mc.thePlayer.getEntityBoundingBox().offset(mc.thePlayer.motionX, mc.thePlayer.motionY + 0.1, mc.thePlayer.motionZ))) {
            tellyNoPlace = true;
            return;
        }
        if (mc.gameSettings.keyBindJump.isKeyDown()) {
            tellyNoPlace = offGroundTicks < (int) tellyJumpDownTicks.getInput();
        } else if (isDiagonal()) {
            tellyNoPlace = offGroundTicks < (int) tellyDiagonalTicks.getInput();
        } else {
            tellyNoPlace = offGroundTicks < (int) tellyStraightTicks.getInput();
        }
    }

    private boolean isDiagonal() {
        float yaw = mc.thePlayer.rotationYaw % 90.0f;
        if (yaw < 0.0f) yaw += 90.0f;
        return yaw > 20.0f && yaw < 70.0f;
    }

    private boolean canPlaceNow() {
        return mc.thePlayer.ticksExisted - lastPlaceTime >= (long) placeDelay.getInput()
                && (!isTellyMode() || !tellyNoPlace);
    }

    private float breezilyYaw() {
        float yaw = mc.thePlayer.rotationYaw;
        float remainder = yaw % 90.0f;
        if (remainder < 0.0f) remainder += 90.0f;
        float nearest = Math.round(yaw / 90.0f) * 90.0f;
        return nearest + (remainder < 45.0f ? 35.0f : -35.0f);
    }

    private void applyTowerMotion() {
        int tower = (int) towerMode.getInput();
        if (tower == 1) {
            mc.thePlayer.motionY = 0.42D;
        } else if (tower == 2) {
            if (mc.thePlayer.onGround) mc.thePlayer.jump();
            if (offGroundTicks == 4 && !Utils.isMoving()) mc.thePlayer.motionY -= 0.03D;
            if (offGroundTicks == 5 && !Utils.isMoving()) mc.thePlayer.motionY -= 0.5D;
        } else if (tower == 3) {
            if (mc.thePlayer.posY % 1.0D <= 0.00153598D) {
                mc.thePlayer.setPosition(mc.thePlayer.posX, Math.floor(mc.thePlayer.posY), mc.thePlayer.posZ);
                mc.thePlayer.motionY = 0.41998D;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isTellyMode() { return (int) mode.getInput() == 1; }

    private int getBlockSlot() {
        int best = -1, bestSize = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = mc.thePlayer.inventory.mainInventory[i];
            if (isBlockItem(s) && s.stackSize > bestSize) { bestSize = s.stackSize; best = i; }
        }
        return best;
    }

    private boolean setSlot() {
        int slot = getBlockSlot();
        if (slot == -1) return false;
        if (lastSlot.get() == -1) lastSlot.set(mc.thePlayer.inventory.currentItem);
        if (autoSwap.isToggled()) SlotManager.swap(slot, (int) swapMode.getInput() == 1);
        return isBlockItem(actualHeldStack());
    }

    private ItemStack actualHeldStack() {
        if (mc.thePlayer == null) return null;
        return mc.thePlayer.inventory.getStackInSlot(mc.thePlayer.inventory.currentItem);
    }

    private static boolean isBlockItem(ItemStack stack) {
        if (stack == null || stack.stackSize < 1) return false;
        if (!(stack.getItem() instanceof ItemBlock)) return false;
        return Utils.canBePlaced((ItemBlock) stack.getItem());
    }

    private static class FaceTarget {
        final BlockPos block;
        final EnumFacing face;
        FaceTarget(BlockPos block, EnumFacing face) { this.block = block; this.face = face; }
    }

    private static class TargetResult {
        final float yaw, pitch;
        TargetResult(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }
    }
}
