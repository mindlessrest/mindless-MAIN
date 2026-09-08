package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.PreSlotScrollEvent;
import mindless.event.SlotUpdateEvent;
import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.render.BlockOverlay;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.OwnBedTracker;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.*;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class BedAura extends Module {

    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting rate;
    private final SliderSetting aimSpeed;
    private final SliderSetting breakDelay;
    private final SliderSetting breakSpeed;
    private final ButtonSetting breakNearBlock;
    private final ButtonSetting breakFromOutside;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting renderOutline;
    private final ColorSetting outlineColor;

    private static final int MS_PER_TICK = 50;
    private static final double BED_FIND_EXTRA_BLOCKS = 1.0;
private static final double AIM_FACE_INSET = 0.12;
    private final List<BlockPos[]> bedPairsCache = new ArrayList<>();
    private int scanCooldown;

    private BlockPos targetPos;
    private Vec3 targetHitVec;
    private EnumFacing targetSide;

    private BlockPos lockedPos;
    private EnumFacing lockedSide;

    private boolean miningActive;
    private boolean controlsInput;
    private int rotationAlignedTicks;
    private int hotbarProgrammaticDepth;
    private boolean hasSwapped;
    private int previousSlot = -1;


    public BedAura() {
        super("Bed Breaker", "Smoothly breaks nearby beds and their defenses.", category.player);
        this.registerSetting(breakSpeed = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02));
        this.registerSetting(breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 2.0, 6.0, 0.1));
        this.registerSetting(rate = new SliderSetting("Rate", "ms", 250.0, 50.0, 2000.0, 50.0));
        this.registerSetting(aimSpeed = new SliderSetting("Aim speed", 14, 1, 30, 1));
        this.registerSetting(breakNearBlock = new ButtonSetting("Break near block", true));
        // Defaulted on so behaviour is unchanged for anyone already using it: the old default
        // of off produced this same path, it was only named backwards.
        this.registerSetting(breakFromOutside = new ButtonSetting("Break from outside", true));
        this.registerSetting(whitelistOwnBed = new ButtonSetting("Whitelist own bed", true));
        this.registerSetting(prioritizeKillAura = new ButtonSetting("Prioritize KillAura", false));
        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));
        this.registerSetting(renderOutline = new ButtonSetting("Render block outline", true));
        this.registerSetting(outlineColor = new ColorSetting("Outline color", 255, 64, 64, 229));
    }

    @Override
    public void guiUpdate() {
        outlineColor.setVisible(renderOutline.isToggled(), this);
    }

    @Override
    public void onDisable() {
        resetMining();
        bedPairsCache.clear();
        scanCooldown = 0;
    }

    @Override
    public void onUpdate() {
        if (Utils.nullCheck()) {
            OwnBedTracker.tick();
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            resetSpawnTracking();
        }
    }

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        OwnBedTracker.handleChat(Utils.stripColor(event.message.getUnformattedText()));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        applyMiningKeyState();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMouse(MouseEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (e.button == 0 || e.button == 1) {
            e.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreAttack(PreAttackEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotScroll(PreSlotScrollEvent e) {
        if (!shouldSuppressManualMouse()) {
            return;
        }
        if (hasSwapped && overrideSwapBack.isToggled() && Utils.nullCheck()) {
            int slot = Integer.compare(e.slot, 0);
            previousSlot = Math.floorMod(mc.thePlayer.inventory.currentItem - slot, InventoryPlayer.getHotbarSize());
        }
        e.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onSlotUpdate(SlotUpdateEvent e) {
        if (!shouldSuppressManualMouse() || hotbarProgrammaticDepth > 0) {
            return;
        }
        if (hasSwapped && overrideSwapBack.isToggled()) {
            previousSlot = e.slot;
        }
        e.setCanceled(true);
    }

    private boolean shouldSuppressManualMouse() {
        return miningActive && isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura();
    }

    public void applyMiningKeyState() {
        if (!canMineBlocks() || shouldYieldToKillAura()) {
            if (miningActive) {
                resetMining();
            }
            return;
        }
        if (!miningActive || !isEnabled() || !Utils.nullCheck() || mc.currentScreen != null) {
            if (controlsInput) {
                releaseInputControl();
            }
            return;
        }
        int atk = mc.gameSettings.keyBindAttack.getKeyCode();
        int use = mc.gameSettings.keyBindUseItem.getKeyCode();
        controlsInput = true;
        KeyBinding.setKeyBindState(atk, false);
        KeyBinding.setKeyBindState(use, false);
        KeyBinding.setKeyBindState(atk, true);
    }
private void releaseInputControl() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), Mouse.isButtonDown(0));
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), Mouse.isButtonDown(1));
        controlsInput = false;
    }

    public BlockPos getAuraTargetPos() {
        return miningActive && canMineBlocks() ? targetPos : null;
    }

    public boolean isActivelyMining() {
        return miningActive && isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura();
    }

    public boolean isPrioritizingKillAura() {
        return prioritizeKillAura.isToggled();
    }

    public boolean shouldOverrideFastMine() {
        return isActivelyMining();
    }

    public float getBreakSpeedMultiplier() {
        float multiplier = (float) breakSpeed.getInput();
        return multiplier > 1.0f ? multiplier : 1.0f;
    }

    public int getBreakDelayTicks() {
        return Math.max(0, Math.min(5, (int) (breakDelay.getInput() / 50.0)));
    }

    public float getAuraBreakProgress() {
        if (!canMineBlocks() || !miningActive || mc.playerController == null) {
            return 0f;
        }
        BlockPos currentBlock = AccessorBridge.PlayerControllerMP_getCurrentBlock(mc.playerController);
        if (targetPos == null || currentBlock == null || !targetPos.equals(currentBlock)) {
            return 0f;
        }
        return AccessorBridge.PlayerControllerMP_getCurBlockDamageMP(mc.playerController);
    }
public boolean shouldOverrideMouseOver() {
        return isEnabled() && miningActive && canMineBlocks()
                && targetPos != null && targetHitVec != null && targetSide != null
                && Utils.nullCheck() && !shouldYieldToKillAura();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }
        if (mc.getRenderViewEntity() == null) {
            return;
        }

        EnumFacing side = lockedSide != null ? lockedSide : targetSide;
        MovingObjectPosition mop = new MovingObjectPosition(targetHitVec, side, targetPos);
        mc.objectMouseOver = mop;
        mc.pointedEntity = null;

        EntityRenderer renderer = mc.entityRenderer;
        AccessorBridge.EntityRenderer_setPointedEntity(renderer, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        if (!isEnabled() || !Utils.nullCheck() || mc.currentScreen != null || !canMineBlocks()) {
            resetMining();
            return;
        }
        if (shouldYieldToKillAura()) {
            resetMining();
            return;
        }
        if (e.scriptRotations) {
            resetMining();
            return;
        }
        double reach = range.getInput();
        double reachSq = reach * reach;

        if (--scanCooldown <= 0) {
            scanCooldown = Math.max(1, (int) Math.round(rate.getInput() / (double) MS_PER_TICK));
            rebuildBedPairsCache(reach + BED_FIND_EXTRA_BLOCKS);
        }

        if (bedPairsCache.isEmpty()) {
            resetMining();
            return;
        }

        if (lockedPos != null) {
            if (!isLockedTargetValid(reachSq)) {
                lockedPos = null;
                lockedSide = null;
            }
        }

        if (lockedPos != null) {
            targetPos = lockedPos;
            targetSide = lockedSide;
            targetHitVec = recalcHitVec(lockedPos, reachSq);
            if (targetHitVec == null) {
                lockedPos = null;
                lockedSide = null;
                resetMining();
                return;
            }
        } else {
            Choice best = chooseBestTarget(reachSq);
            if (best == null) {
                resetMining();
                return;
            }
            targetPos = best.pos;
            targetHitVec = best.hitVec;
            targetSide = best.side;
            lockedPos = best.pos;
            lockedSide = best.side;
            rotationAlignedTicks = 0;
        }

        equipBestHotbarTool(BlockUtils.getBlock(targetPos));

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float[] targetRotations = RotationUtils.getRotationsToPoint(
                targetHitVec.xCoord, targetHitVec.yCoord, targetHitVec.zCoord,
                baseYaw, basePitch
        );

        float[] r = RotationUtils.smoothRotationHumanized(
                baseYaw, basePitch, targetRotations[0], targetRotations[1],
                (int) aimSpeed.getInput(), 15.0F
        );
        // Snap the step to the mouse-sensitivity grid, the way Scaffold does. A real mouse can
        // only produce rotation deltas that are whole multiples of that step, so a smooth
        // arbitrary value is the one part of this no physical input could have made. It was
        // missing here and nowhere else.
        float[] fixed = RotationUtils.fixRotation(r[0], r[1],
                RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);

        // Measured against what is actually sent rather than the value before snapping,
        // otherwise mining is gated on a rotation the server never sees.
        float yawError = Math.abs(MathHelper.wrapAngleTo180_float(targetRotations[0] - fixed[0]));
        float pitchError = Math.abs(targetRotations[1] - fixed[1]);
        if (yawError <= 4.0F && pitchError <= 4.0F) {
            rotationAlignedTicks++;
        } else {
            rotationAlignedTicks = 0;
        }
        miningActive = rotationAlignedTicks >= 2;
        e.setYaw(fixed[0]);
        e.setPitch(fixed[1]);
    }


    private boolean isLockedTargetValid(double reachSq) {
        IBlockState st = mc.theWorld.getBlockState(lockedPos);
        Block block = st.getBlock();
        if (block == Blocks.air) {
            return false;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(lockedPos);
        if (bb == null) {
            return false;
        }
        Vec3 closest = RotationUtils.closestPointOnAabb(bb, eye);
        return eye.squareDistanceTo(closest) <= reachSq + 0.25;
    }

    private Vec3 recalcHitVec(BlockPos pos, double reachSq) {
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(pos);
        if (bb == null) {
            return null;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        if (eye.squareDistanceTo(RotationUtils.closestPointOnAabb(bb, eye)) > reachSq + 0.25) {
            return null;
        }
        return RotationUtils.closestPointOnAabb(bb, eye, AIM_FACE_INSET);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!isEnabled() || !renderOutline.isToggled() || !miningActive || targetPos == null || !Utils.nullCheck() || !canMineBlocks()) {
            return;
        }
        IBlockState st = mc.theWorld.getBlockState(targetPos);
        Block b = st.getBlock();
        if (b == null || b == Blocks.air) {
            return;
        }
        int c = outlineColor.getColor();
        BlockOverlay.renderBlockOutline(targetPos, c, c, 2.0f, true);
    }

    private void resetMining() {
        miningActive = false;
        rotationAlignedTicks = 0;
        lockedPos = null;
        lockedSide = null;
        if (switchBackWhenDone.isToggled() && previousSlot != -1 && Utils.nullCheck()) {
            setSlot(previousSlot);
        }
        if (controlsInput) {
            releaseInputControl();
        }
        hotbarProgrammaticDepth = 0;
        targetPos = null;
        targetHitVec = null;
        targetSide = null;
        hasSwapped = false;
        previousSlot = -1;
    }

    private void rebuildBedPairsCache(double searchRange) {
        bedPairsCache.clear();
        Set<BlockPos> seenFeet = new HashSet<>();
        int ri = (int) Math.ceil(searchRange);
        BlockPos origin = new BlockPos(mc.thePlayer);

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    BlockPos p = origin.add(dx, dy, dz);
                    BlockPos[] pair = footHeadPair(p);
                    if (pair == null) {
                        continue;
                    }
                    BlockPos foot = pair[0];
                    if (seenFeet.contains(foot)) {
                        continue;
                    }
                    if (!bedInSearchRange(pair, searchRange)) {
                        continue;
                    }
                    Vec3 center = bedCenter(pair);
                    if (!inFov(center, (float) fov.getInput())) {
                        continue;
                    }
                    seenFeet.add(foot);
                    bedPairsCache.add(pair);
                }
            }
        }

        removeOwnBedPair();
    }
private BlockPos[] footHeadPair(BlockPos at) {
        return OwnBedTracker.footHeadPair(at);
    }

    private Vec3 bedCenter(BlockPos[] pair) {
        AxisAlignedBB a = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        return new Vec3((a.minX + a.maxX) * 0.5, (a.minY + a.maxY) * 0.5, (a.minZ + a.maxZ) * 0.5);
    }

    private boolean bedInSearchRange(BlockPos[] pair, double searchRadius) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        double r2 = searchRadius * searchRadius + 1e-4;
        AxisAlignedBB u = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        Vec3 onBox = RotationUtils.closestPointOnAabb(u, eye);
        if (eye.squareDistanceTo(onBox) <= r2) {
            return true;
        }
        Vec3 mid = new Vec3((u.minX + u.maxX) * 0.5, (u.minY + u.maxY) * 0.5, (u.minZ + u.maxZ) * 0.5);
        return eye.squareDistanceTo(mid) <= r2;
    }

    private boolean inFov(Vec3 worldPoint, float fovDeg) {
        if (fovDeg >= 360) {
            return true;
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1f);
        Vec3 look = mc.thePlayer.getLook(1f);
        Vec3 to = worldPoint.subtract(eyes);
        double len = to.lengthVector();
        if (len < 1e-6) {
            return true;
        }
        to = new Vec3(to.xCoord / len, to.yCoord / len, to.zCoord / len);
        double dot = look.xCoord * to.xCoord + look.yCoord * to.yCoord + look.zCoord * to.zCoord;
        double ang = Math.acos(MathHelper.clamp_double(dot, -1.0, 1.0)) * (180.0 / Math.PI);
        return ang <= fovDeg * 0.5;
    }

    private Choice chooseBestTarget(double reachSq) {
        float curProg = AccessorBridge.PlayerControllerMP_getCurBlockDamageMP(mc.playerController);
        BlockPos breaking = AccessorBridge.PlayerControllerMP_getCurrentBlock(mc.playerController);

        List<BlockPos[]> exposed = new ArrayList<>();
        List<BlockPos[]> covered = new ArrayList<>();
        for (BlockPos[] pair : bedPairsCache) {
            if (isBedExposed(pair)) {
                exposed.add(pair);
            } else {
                covered.add(pair);
            }
        }
        sortBedsByEyeDistance(exposed);
        sortBedsByEyeDistance(covered);

        Choice c = pickBestOnClosestBedWithCandidates(exposed, reachSq, curProg, breaking);
        if (c != null) {
            return c;
        }
        return pickBestOnClosestBedWithCandidates(covered, reachSq, curProg, breaking);
    }

    private void sortBedsByEyeDistance(List<BlockPos[]> pairs) {
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        pairs.sort(Comparator.comparingDouble(p -> eye.squareDistanceTo(bedCenter(p))));
    }

    private Choice pickBestOnClosestBedWithCandidates(List<BlockPos[]> sortedPairs, double reachSq, float curProg, BlockPos breaking) {
        for (BlockPos[] pair : sortedPairs) {
            List<Choice> candidates = buildCandidates(pair, reachSq);
            if (candidates.isEmpty()) {
                continue;
            }
            Choice best = null;
            double bestScore = Double.POSITIVE_INFINITY;
            for (Choice ch : candidates) {
                double score = scoreChoice(ch, curProg, breaking);
                if (score < bestScore) {
                    bestScore = score;
                    best = ch;
                }
            }
            return best;
        }
        return null;
    }

    private double scoreChoice(Choice ch, float curProg, BlockPos breaking) {
        Block block = BlockUtils.getBlock(ch.pos);
        float bestHotbar = BlockUtils.maxDigRateAcrossSlots(block, InventoryPlayer.getHotbarSize());
        if (bestHotbar <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        double timeEst = 1.0 / bestHotbar;

        if (breaking != null && breaking.equals(ch.pos) && curProg > 0.02f) {
            timeEst -= curProg * 12.0;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        timeEst += eye.squareDistanceTo(ch.hitVec) * 0.002;
        return timeEst;
    }

    private List<Choice> buildCandidates(BlockPos[] pair, double reachSq) {
        List<Choice> out = new ArrayList<>();
        boolean exposed = isBedExposed(pair);

        // On means break it the way a player would: if the bed is buried, take the covering
        // blocks off first rather than reaching a pickaxe through them. It used to mean the
        // reverse -- switching it on made the aura hit the bed straight through whatever was
        // stacked on it, which is the opposite of breaking from outside.
        if (exposed || !breakFromOutside.isToggled()) {
            for (BlockPos bp : pair) {
                addBlockCandidate(bp, reachSq, out);
            }
        } else {
            Set<BlockPos> seen = new HashSet<>();
            for (BlockPos bp : pair) {
                for (EnumFacing f : EnumFacing.values()) {
                    if (f == EnumFacing.DOWN) {
                        continue;
                    }
                    BlockPos n = bp.offset(f);
                    if (seen.contains(n)) {
                        continue;
                    }
                    IBlockState st = mc.theWorld.getBlockState(n);
                    Block b = st.getBlock();
                    if (b == Blocks.air || b instanceof BlockBed) {
                        continue;
                    }
                    float hard = b.getBlockHardness(mc.theWorld, n);
                    if (hard < 0) {
                        continue;
                    }
                    seen.add(n);
                    addBlockCandidate(n, reachSq, out);
                }
            }
        }
        return out;
    }

    private boolean canHitThrough(Block block) {
        if (block == Blocks.air) return true;
        if (block == Blocks.iron_bars) return true;
        if (block == Blocks.water || block == Blocks.flowing_water) return true;
        if (block == Blocks.lava || block == Blocks.flowing_lava) return true;
        if (block == Blocks.tallgrass || block == Blocks.red_flower || block == Blocks.yellow_flower) return true;
        if (block == Blocks.double_plant || block == Blocks.deadbush || block == Blocks.vine) return true;
        if (block == Blocks.end_portal_frame) return true;
        if (block instanceof net.minecraft.block.BlockFence) return true;
        if (block instanceof net.minecraft.block.BlockWall) return true;
        if (block instanceof net.minecraft.block.BlockFenceGate) return true;
        if (block instanceof net.minecraft.block.BlockPane) return true;
        return false;
    }

    private boolean isBedExposed(BlockPos[] pair) {
        for (BlockPos bp : pair) {
            for (EnumFacing f : EnumFacing.values()) {
                BlockPos n = bp.offset(f);
                if (canHitThrough(mc.theWorld.getBlockState(n).getBlock())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void addBlockCandidate(BlockPos pos, double reachSq, List<Choice> out) {
        IBlockState st = mc.theWorld.getBlockState(pos);
        Block block = st.getBlock();
        if (block == Blocks.air) {
            return;
        }
        float hard = block.getBlockHardness(mc.theWorld, pos);
        if (hard < 0) {
            return;
        }
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(pos);
        if (bb == null) {
            return;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        if (eye.squareDistanceTo(RotationUtils.closestPointOnAabb(bb, eye)) > reachSq + 1e-3) {
            return;
        }
        Vec3 hit = RotationUtils.closestPointOnAabb(bb, eye, AIM_FACE_INSET);

        MovingObjectPosition trace = block.collisionRayTrace(mc.theWorld, pos, eye, hit.addVector(
                (hit.xCoord - eye.xCoord) * 0.01,
                (hit.yCoord - eye.yCoord) * 0.01,
                (hit.zCoord - eye.zCoord) * 0.01
        ));
        EnumFacing side = BlockUtils.facingFromBlockCenterToPoint(pos, hit);
        if (trace != null && trace.hitVec != null && trace.sideHit != null && pos.equals(trace.getBlockPos())) {
            hit = trace.hitVec;
            side = trace.sideHit;
        }
        if (block instanceof BlockBed && side == EnumFacing.DOWN) {
            return;
        }

        out.add(new Choice(pos, hit, side));
    }

    private void equipBestHotbarTool(Block block) {
        int slot = Utils.getTool(block);
        if (slot < 0) {
            return;
        }
        if (previousSlot == -1 && slot != mc.thePlayer.inventory.currentItem) {
            previousSlot = mc.thePlayer.inventory.currentItem;
        }
        if (slot != mc.thePlayer.inventory.currentItem) {
            setSlot(slot);
        }
    }

    private void setSlot(int slot) {
        if (slot == -1 || slot == mc.thePlayer.inventory.currentItem) {
            return;
        }
        hotbarProgrammaticDepth++;
        try {
            mc.thePlayer.inventory.currentItem = slot;
            hasSwapped = true;
            AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
        } finally {
            hotbarProgrammaticDepth--;
        }
    }

    private boolean canMineBlocks() {
        return mc.thePlayer.capabilities.allowEdit
                && !mc.thePlayer.capabilities.isCreativeMode
                && !mc.thePlayer.isSpectator();
    }

    private boolean shouldYieldToKillAura() {
        if (!prioritizeKillAura.isToggled()) {
            return false;
        }
        return ModuleManager.killAura != null
                && ModuleManager.killAura.isEnabled()
                && KillAura.target != null;
    }
private void resetSpawnTracking() {
        OwnBedTracker.reset();
    }
private void removeOwnBedPair() {
        if (!whitelistOwnBed.isToggled()) {
            return;
        }
        OwnBedTracker.removeOwnBed(bedPairsCache);
    }

    private static final class Choice {
        final BlockPos pos;
        final Vec3 hitVec;
        final EnumFacing side;

        Choice(BlockPos pos, Vec3 hitVec, EnumFacing side) {
            this.pos = pos;
            this.hitVec = hitVec;
            this.side = side;
        }
    }
}
