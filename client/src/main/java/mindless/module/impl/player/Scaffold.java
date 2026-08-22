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
    private static final String[] MODES = {"Normal", "Telly"};
    private static final String[] SPRINT_MODES = {"None", "Vanilla"};

    private final SliderSetting mode;
    private final SliderSetting sprintMode;
    private final SliderSetting rotSpeed;
    public final ButtonSetting safeWalk;
    private final ButtonSetting swing;
    public final ButtonSetting autoSwap;
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

    public Scaffold() {
        super("Scaffold", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(rotSpeed = new SliderSetting("Rotation speed", 15, 1, 30, 1));
        this.registerSetting(sprintMode = new SliderSetting("Sprint", 0, SPRINT_MODES));
        this.registerSetting(safeWalk = new ButtonSetting("Safewalk", true));
        this.registerSetting(swing = new ButtonSetting("Swing", true));
        this.registerSetting(autoSwap = new ButtonSetting("Auto swap", true));
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
    }

    @Override
    public void onDisable() {
        moduleEnabled = false;
        isEnabled = false;
        if (mc.thePlayer != null && lastSlot.get() != -1)
            mc.thePlayer.inventory.currentItem = lastSlot.get();
        lastSlot.set(-1);
        hasSwapped = false;
        active = false;
        hasTarget = false;
        fastScaffoldKeepY = false;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean safewalk() { return isEnabled && safeWalk.isToggled() && mc.thePlayer != null && mc.thePlayer.onGround; }
    public boolean canSafewalk() { return isEnabled && safeWalk.isToggled(); }
    public boolean sprint() { return !isEnabled || (sendPacket.isToggled() && !holdingBlocks()); }
    public boolean stopRotation() { return active; }
    public boolean stopFastPlace() { return isEnabled; }
    public void rotateForward() {}

    public boolean holdingBlocks() {
        if (!autoSwap.isToggled()) return isBlockItem(mc.thePlayer.getHeldItem());
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
        if (isTellyMode() && mc.thePlayer.onGround && Utils.isMoving() && holdingBlocks()) {
            e.setJump(true);
            fastScaffoldKeepY = true;
        }
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

        // Sprint control
        if ((int) sprintMode.getInput() == 0) {
            mc.thePlayer.setSprinting(false);
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
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

        // Smooth rotation (same as BridgeAssist)
        float[] sm = RotationUtils.smoothRotation(baseYaw, basePitch, targetYaw, targetPitch, (int) rotSpeed.getInput(), 20f);
        e.setYaw(sm[0]);
        e.setPitch(sm[1]);

        // Queue placement if rotation is close enough to target
        float yawDiff = Math.abs(MathHelper.wrapAngleTo180_float(sm[0] - targetYaw));
        float pitchDiff = Math.abs(sm[1] - targetPitch);
        if (yawDiff < 30f && pitchDiff < 30f) {
            MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, sm[0], sm[1]);
            if (mop != null && mop.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && !BlockUtils.isInteractable(BlockUtils.getBlock(mop.getBlockPos()))
                    && mop.sideHit != EnumFacing.DOWN) {
                BlockPos placed = mop.getBlockPos().offset(mop.sideHit);
                int feetY = MathHelper.floor_double(mc.thePlayer.posY);
                if (BlockUtils.replaceable(placed) && placed.getY() < feetY) {
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

        ItemStack held = mc.thePlayer.getHeldItem();
        if (!isBlockItem(held)) return;

        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, queuedBlock, queuedFace, queuedHitVec)) {
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
        int standY = MathHelper.floor_double(bbox.minY) - 1;
        int minX = MathHelper.floor_double(bbox.minX);
        int maxX = MathHelper.floor_double(bbox.maxX);
        int minZ = MathHelper.floor_double(bbox.minZ);
        int maxZ = MathHelper.floor_double(bbox.maxZ);

        ArrayList<FaceTarget> targets = new ArrayList<>();
        for (int y = standY; y >= standY - 1; y--) {
            for (int x = minX - 1; x <= maxX + 1; x++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
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

        // For each target face, compute the rotation to hit its center and verify via raytrace
        float bestYaw = Float.NaN, bestPitch = Float.NaN;
        float bestCost = Float.MAX_VALUE;

        for (FaceTarget t : targets) {
            // Hit point = center of the face
            double hx = t.block.getX() + 0.5;
            double hy = t.block.getY() + 0.5;
            double hz = t.block.getZ() + 0.5;
            Vec3i dir = t.face.getDirectionVec();
            hx += dir.getX() * 0.5;
            hy += dir.getY() * 0.5;
            hz += dir.getZ() * 0.5;

            double dx = hx - eye.xCoord;
            double dy = hy - eye.yCoord;
            double dz = hz - eye.zCoord;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dx * dx + dy * dy + dz * dz > reach * reach) continue;

            float calcYaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
            float calcPitch = (float) -Math.toDegrees(Math.atan2(dy, dist));
            calcPitch = MathHelper.clamp_float(calcPitch, -90f, 90f);

            // Unwrap yaw relative to base
            calcYaw = baseYaw + MathHelper.wrapAngleTo180_float(calcYaw - baseYaw);

            // Verify raytrace hits the correct block+face
            MovingObjectPosition mop = RotationUtils.rayCastBlock(reach, calcYaw, calcPitch);
            if (mop == null) continue;
            if (!mop.getBlockPos().equals(t.block) || mop.sideHit != t.face) continue;

            // Check the placed position is air AND at/below foot level
            BlockPos placed = t.block.offset(t.face);
            if (!BlockUtils.replaceable(placed)) continue;
            int feetY = MathHelper.floor_double(mc.thePlayer.posY);
            if (placed.getY() >= feetY) continue;

            // Cost: prefer closest rotation to current server rotation
            float cost = Math.abs(MathHelper.wrapAngleTo180_float(calcYaw - baseYaw)) + Math.abs(calcPitch - basePitch);
            if (cost < bestCost) {
                bestCost = cost;
                bestYaw = calcYaw;
                bestPitch = calcPitch;
            }
        }

        if (Float.isNaN(bestYaw)) return null;
        return new TargetResult(bestYaw, bestPitch);
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
        if (autoSwap.isToggled()) mc.thePlayer.inventory.currentItem = slot;
        return isBlockItem(mc.thePlayer.getHeldItem());
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
