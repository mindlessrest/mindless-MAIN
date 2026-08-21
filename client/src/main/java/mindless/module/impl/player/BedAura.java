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
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
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
    private final SliderSetting breakDelay;
    private final SliderSetting breakSpeed;
    private final ButtonSetting breakNearBlock;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting renderOutline;
    private final ColorSetting outlineColor;

    private static final int MS_PER_TICK = 50;
    private static final double BED_FIND_EXTRA_BLOCKS = 1.0;
    private static final double OWN_BED_PROTECTION_RADIUS_SQ = 800.0;
    private final List<BlockPos[]> bedPairsCache = new ArrayList<>();
    private int scanCooldown;

    private BlockPos targetPos;
    private Vec3 targetHitVec;
    private EnumFacing targetSide;

    private boolean miningActive;
    private boolean controlsInput;
    private int hotbarProgrammaticDepth;
    private boolean hasSwapped;
    private int previousSlot = -1;
    private BlockPos spawnAnchor;
    private boolean pendingSpawnAnchorCapture;
    private boolean waitingForRespawn;
    private long respawnMessageTime;

    public BedAura() {
        super("BedAura", category.player);
        this.registerSetting(breakSpeed = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02));
        this.registerSetting(breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 2.0, 6.0, 0.1));
        this.registerSetting(rate = new SliderSetting("Rate", "ms", 250.0, 50.0, 2000.0, 50.0));
        this.registerSetting(breakNearBlock = new ButtonSetting("Break near block", true));
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
        resetSpawnTracking();
        bedPairsCache.clear();
        scanCooldown = 0;
    }

    @Override
    public void onUpdate() {
        if (!Utils.nullCheck()) {
            return;
        }

        if (pendingSpawnAnchorCapture && Utils.getBedwarsStatus() == 2) {
            spawnAnchor = mc.thePlayer.getPosition();
            pendingSpawnAnchorCapture = false;
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

        String strippedMessage = Utils.stripColor(event.message.getUnformattedText());
        if (strippedMessage.startsWith(" ") && strippedMessage.contains("Protect your bed and destroy the enemy beds.")) {
            pendingSpawnAnchorCapture = true;
            waitingForRespawn = false;
        }
        else if (strippedMessage.equals("You will respawn because you still have a bed!")) {
            waitingForRespawn = true;
            respawnMessageTime = System.currentTimeMillis();
        }
        else if (strippedMessage.equals("You have respawned!") && waitingForRespawn && Utils.timeBetween(System.currentTimeMillis(), respawnMessageTime) <= 12000) {
            pendingSpawnAnchorCapture = true;
            waitingForRespawn = false;
        }
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
            return;
        }
        int atk = mc.gameSettings.keyBindAttack.getKeyCode();
        int use = mc.gameSettings.keyBindUseItem.getKeyCode();
        controlsInput = true;
        KeyBinding.setKeyBindState(atk, false);
        KeyBinding.setKeyBindState(use, false);
        KeyBinding.setKeyBindState(atk, true);
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
        return isEnabled() && miningActive && canMineBlocks() && targetPos != null && targetHitVec != null && targetSide != null && Utils.nullCheck() && !shouldYieldToKillAura();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }
        if (mc.getRenderViewEntity() == null) {
            return;
        }

        MovingObjectPosition mop = new MovingObjectPosition(targetHitVec, targetSide, targetPos);
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

        Choice best = chooseBestTarget(reachSq);
        if (best == null) {
            resetMining();
            return;
        }

        targetPos = best.pos;
        targetHitVec = best.hitVec;
        targetSide = best.side;
        miningActive = true;

        equipBestHotbarTool(BlockUtils.getBlock(targetPos));

        float baseYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        float basePitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        float[] r = RotationUtils.getRotationsToPoint(
                targetHitVec.xCoord, targetHitVec.yCoord, targetHitVec.zCoord,
                baseYaw, basePitch
        );
        e.setYaw(r[0]);
        e.setPitch(r[1]);
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
        if (switchBackWhenDone.isToggled() && previousSlot != -1 && Utils.nullCheck()) {
            setSlot(previousSlot);
        }
        if (controlsInput) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), Mouse.isButtonDown(0));
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), Mouse.isButtonDown(1));
            controlsInput = false;
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
        IBlockState st = mc.theWorld.getBlockState(at);
        if (!(st.getBlock() instanceof BlockBed)) {
            return null;
        }
        BlockBed.EnumPartType part = (BlockBed.EnumPartType) st.getValue(BlockBed.PART);
        EnumFacing facing = (EnumFacing) st.getValue(BlockBed.FACING);
        BlockPos foot = part == BlockBed.EnumPartType.FOOT ? at : at.offset(facing.getOpposite());
        IBlockState footSt = mc.theWorld.getBlockState(foot);
        if (!(footSt.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (footSt.getValue(BlockBed.PART) != BlockBed.EnumPartType.FOOT) {
            return null;
        }
        EnumFacing footFacing = (EnumFacing) footSt.getValue(BlockBed.FACING);
        BlockPos head = foot.offset(footFacing);
        IBlockState hs = mc.theWorld.getBlockState(head);
        if (!(hs.getBlock() instanceof BlockBed)) {
            return null;
        }
        if (hs.getValue(BlockBed.PART) != BlockBed.EnumPartType.HEAD) {
            return null;
        }
        if (hs.getValue(BlockBed.FACING) != footFacing) {
            return null;
        }
        return new BlockPos[]{foot, head};
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

        if (exposed) {
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
        Vec3 hit = RotationUtils.closestPointOnAabb(bb, eye);
        if (eye.squareDistanceTo(hit) > reachSq + 1e-3) {
            return;
        }

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
        spawnAnchor = null;
        pendingSpawnAnchorCapture = false;
        waitingForRespawn = false;
        respawnMessageTime = 0L;
    }

    private void removeOwnBedPair() {
        if (!shouldWhitelistOwnBed() || bedPairsCache.isEmpty()) {
            return;
        }

        BlockPos[] ownBedPair = null;
        double closestDistance = Double.POSITIVE_INFINITY;
        Vec3 spawnCenter = spawnAnchorCenter();

        for (BlockPos[] pair : bedPairsCache) {
            double distance = spawnCenter.squareDistanceTo(bedCenter(pair));
            if (distance < closestDistance) {
                closestDistance = distance;
                ownBedPair = pair;
            }
        }

        if (ownBedPair != null) {
            bedPairsCache.remove(ownBedPair);
        }
    }

    private boolean shouldWhitelistOwnBed() {
        return whitelistOwnBed.isToggled()
                && spawnAnchor != null
                && Utils.getBedwarsStatus() == 2
                && mc.thePlayer.getDistanceSq(spawnAnchor) <= OWN_BED_PROTECTION_RADIUS_SQ;
    }

    private Vec3 spawnAnchorCenter() {
        return new Vec3(spawnAnchor.getX() + 0.5, spawnAnchor.getY() + 0.5, spawnAnchor.getZ() + 0.5);
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
