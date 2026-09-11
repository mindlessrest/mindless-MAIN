package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.PreMotionEvent;
import mindless.helper.RotationHelper;
import mindless.module.setting.impl.ProfiledButtonSetting;
import mindless.module.setting.impl.ProfiledSliderSetting;
import mindless.rotation.RotationSource;
import mindless.runtime.AccessorBridge;
import mindless.utility.BlockUtils;
import mindless.utility.Diagnostics;
import mindless.utility.OwnBedTracker;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockBed;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.potion.Potion;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SilentBedBreaker {
    private enum State { IDLE, START, MINING, FINISH, RESTORE, COOLDOWN }

    private final BedAura owner;
    private final ProfiledSliderSetting range;
    private final ProfiledSliderSetting speed;
    private final ProfiledButtonSetting surroundings;
    private final ProfiledButtonSetting toolCheck;
    private final ProfiledButtonSetting whitelist;
    private final ProfiledButtonSetting swing;
    private final ProfiledSliderSetting moveFix;
    private final ProfiledSliderSetting showTarget;
    private final ProfiledSliderSetting showProgress;

    private State state = State.IDLE;
    private BlockPos target;
    private EnumFacing face = EnumFacing.UP;
    private boolean targetIsBed;
    private boolean digging;
    private boolean interactionLocked;
    private boolean startSent;
    private boolean stopSent;
    private int intendedSlot = -1;
    private int toolSlot = -1;
    private ItemStack miningItem;
    private int breakTicks;
    private int finishTicks;
    private long cooldownUntil;
    private float accumulatedDamage;
    private float requiredYaw;
    private float requiredPitch;
    private boolean rotationRequested;

    SilentBedBreaker(BedAura owner,
                     ProfiledSliderSetting range, ProfiledSliderSetting speed,
                     ProfiledButtonSetting surroundings, ProfiledButtonSetting toolCheck,
                     ProfiledButtonSetting whitelist, ProfiledButtonSetting swing,
                     ProfiledSliderSetting moveFix, ProfiledSliderSetting showTarget,
                     ProfiledSliderSetting showProgress) {
        this.owner = owner;
        this.range = range;
        this.speed = speed;
        this.surroundings = surroundings;
        this.toolCheck = toolCheck;
        this.whitelist = whitelist;
        this.swing = swing;
        this.moveFix = moveFix;
        this.showTarget = showTarget;
        this.showProgress = showProgress;
    }

    void prepareTick() {
        if (!ready()) {
            debug("prepare blocked: " + unavailableReason());
            cleanup();
            return;
        }
        if (state == State.COOLDOWN) {
            if (System.currentTimeMillis() >= cooldownUntil) transition(State.IDLE, "cooldown elapsed");
            else debug("prepare cooldown remainingMs=" + (cooldownUntil - System.currentTimeMillis()));
            return;
        }
        if (target != null && mc().theWorld.isAirBlock(target)) {
            if (targetIsBed) {
                debug("prepare confirmed removal target=" + describeTarget());
                completeSuccess();
            }
            else {
                debug("prepare defense removed target=" + describeTarget());
                cleanup();
            }
            return;
        }
        if (target != null && !targetIsValid()) {
            debug("prepare invalid target=" + describeTarget());
            cleanup();
            return;
        }
        if (target == null) {
            debug("prepare target scan configuredRange=" + range.getInput() + " effectiveRange=" + effectiveReach());
            target = findTarget();
            if (target == null) {
                debug("prepare target scan found none");
                return;
            }
            targetIsBed = block(target) instanceof BlockBed;
            face = breakFace(target);
            transition(State.START, "target acquired " + describeTarget());
            interactionLocked = true;
        }
        face = breakFace(target);
        debug("prepare ready state=" + state + " " + describeTarget());
    }

    void tick() {
        prepareTick();
        if (state == State.FINISH) {
            finishTick();
        }
    }

    void actionTick(PreMotionEvent event) {
        if (target != null && (event.getYawSource() != RotationSource.BED_AURA
                || event.getPitchSource() != RotationSource.BED_AURA
                || Math.abs(MathHelper.wrapAngleTo180_float(event.getYaw() - requiredYaw)) >= 0.001F
                || Math.abs(event.getPitch() - requiredPitch) >= 0.001F)) {
            cleanup();
            return;
        }
        if (target != null && ready() && !mc().theWorld.isAirBlock(target)) {
            Vec3 eye = mc().thePlayer.getPositionEyes(1.0F);
            Vec3 direction = RotationUtils.getVectorForRotation(event.getPitch(), event.getYaw());
            MovingObjectPosition hit = block(target).collisionRayTrace(mc().theWorld, target, eye,
                    eye.addVector(direction.xCoord * effectiveReach(), direction.yCoord * effectiveReach(),
                            direction.zCoord * effectiveReach()));
            if (hit == null || hit.sideHit == null || !target.equals(hit.getBlockPos())) {
                cleanup();
                return;
            }
            face = hit.sideHit;
        }
        actionTick();
    }

    void actionTick() {
        if (!ready()) {
            debug("action blocked: " + unavailableReason());
            cleanup();
            return;
        }
        if (state == State.COOLDOWN || target == null) {
            debug("action skipped state=" + state + " target=" + target);
            return;
        }
        if (state == State.FINISH) {
            debug("action finish poll " + describeTarget());
            finishTick();
            return;
        }
        if (!targetIsValid()) {
            debug("action invalid target=" + describeTarget());
            cleanup();
            return;
        }
        if (!hasApplicableRotation()) {
            debug("action waiting rotation " + rotationStatus());
            return;
        }
        IBlockState stateAtTarget = mc().theWorld.getBlockState(target);
        Block targetBlock = stateAtTarget.getBlock();
        if (toolCheck.isToggled() && !hasRequiredTool(targetBlock)) {
            debug("action missing required tool target=" + describeTarget() + " material=" + targetBlock.getMaterial());
            cleanup();
            return;
        }
        switch (state) {
            case START:
                if (mc().thePlayer.isUsingItem()) {
                    debug("action waiting item use target=" + describeTarget());
                    return;
                }
                if (mc().playerController != null) mc().playerController.resetBlockRemoving();
                toolSlot = bestTool(targetBlock);
                equipTool();
                if (!synchronizeHeldItem()) return;
                miningItem = ItemStack.copyItemStack(mc().thePlayer.getHeldItem());
                send(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK);
                animate();
                startSent = true;
                digging = true;
                transition(State.MINING, "START sent " + describeTarget() + " toolSlot=" + toolSlot);
                break;
            case MINING:
                if (!synchronizeHeldItem()) return;
                if (!sameMiningItem() || toolSlot != mc().thePlayer.inventory.currentItem) {
                    send(C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK);
                    toolSlot = mc().thePlayer.inventory.currentItem;
                    miningItem = ItemStack.copyItemStack(mc().thePlayer.getHeldItem());
                    intendedSlot = -1;
                    accumulatedDamage = 0.0F;
                    breakTicks = 0;
                    send(C07PacketPlayerDigging.Action.START_DESTROY_BLOCK);
                    return;
                }
                breakTicks++;
                animate();
                if (mc().effectRenderer != null) mc().effectRenderer.addBlockHitEffects(target, face);
                accumulatedDamage += relativeHardness(stateAtTarget, target, toolSlot, mc().thePlayer.onGround);
                debug("action mining tick=" + breakTicks + " progress=" + progress() + " damage=" + accumulatedDamage
                        + " threshold=" + threshold() + " " + describeTarget());
                if (accumulatedDamage >= threshold()) {
                    send(C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK);
                    animate();
                    digging = false;
                    stopSent = true;
                    interactionLocked = true;
                    finishTicks = 0;
                    transition(State.FINISH, "STOP sent " + describeTarget());
                }
                break;
            default:
                break;
        }
    }

    private boolean sameMiningItem() {
        ItemStack held = mc().thePlayer.getHeldItem();
        return held == null ? miningItem == null : miningItem != null
                && held.getItem() == miningItem.getItem()
                && ItemStack.areItemStackTagsEqual(held, miningItem)
                && (held.isItemStackDamageable() || held.getMetadata() == miningItem.getMetadata());
    }

    private boolean synchronizeHeldItem() {
        if (mc().playerController == null) return false;
        AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
        return AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc().playerController)
                == mc().thePlayer.inventory.currentItem;
    }

    private void finishTick() {
        if (target == null) return;
        if (mc().theWorld.isAirBlock(target)) {
            debug("finish confirmed removal " + describeTarget());
            completeSuccess();
            return;
        }
        finishTicks++;
        debug("finish waiting tick=" + finishTicks + " " + describeTarget());
        if (finishTicks >= 10) {
            debug("finish timeout " + describeTarget());
            cleanup();
        }
    }

    void requestRotation(ClientRotationEvent event) {
        rotationRequested = false;
        if (!ready() || target == null || !interactionLocked) return;
        Vec3 eye = mc().thePlayer.getPositionEyes(1.0F);
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(target);
        if (box == null) return;
        Vec3 point = RotationUtils.closestPointOnAabb(box, eye, 0.12);
        float baseYaw = event.getBaseYaw() != null ? event.getBaseYaw() : RotationUtils.serverRotations[0];
        float basePitch = event.getBasePitch() != null ? event.getBasePitch() : RotationUtils.serverRotations[1];
        float[] rotation = RotationUtils.getRotationsToPoint(point.xCoord, point.yCoord, point.zCoord, baseYaw, basePitch);
        float[] fixed = RotationUtils.fixRotation(rotation[0], rotation[1], RotationUtils.serverRotations[0], RotationUtils.serverRotations[1]);
        requiredYaw = fixed[0];
        requiredPitch = MathHelper.clamp_float(fixed[1], -90.0F, 90.0F);
        rotationRequested = event.requestRotation(RotationSource.BED_AURA, requiredYaw, requiredPitch);
        debug("rotation request accepted=" + rotationRequested + " yaw=" + requiredYaw + " pitch=" + requiredPitch
                + " face=" + face + " " + describeTarget());
        if (rotationRequested
                && usesMoveFix()) {
            RotationHelper.get().forceMovementFix = true;
        }
    }

    private boolean hasApplicableRotation() {
        if (!rotationRequested || RotationHelper.get().getServerYawSource() != RotationSource.BED_AURA
                || RotationHelper.get().getServerPitchSource() != RotationSource.BED_AURA) {
            return false;
        }
        Float yaw = RotationHelper.get().getServerYaw();
        Float pitch = RotationHelper.get().getServerPitch();
        return yaw != null && pitch != null
                && Math.abs(MathHelper.wrapAngleTo180_float(yaw - requiredYaw)) < 0.001F
                && Math.abs(pitch - requiredPitch) < 0.001F;
    }

    void cleanup() {
        debug("cleanup state=" + state + " startSent=" + startSent + " stopSent=" + stopSent
                + " digging=" + digging + " " + describeTarget());
        if (digging && !stopSent && target != null && Utils.nullCheck() && !mc().theWorld.isAirBlock(target)) {
            send(C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK);
        }
        restoreSlot();
        target = null;
        face = EnumFacing.UP;
        targetIsBed = false;
        digging = false;
        interactionLocked = false;
        startSent = false;
        stopSent = false;
        breakTicks = 0;
        finishTicks = 0;
        accumulatedDamage = 0.0F;
        miningItem = null;
        rotationRequested = false;
        transition(State.IDLE, "session cleared");
    }

    private void completeSuccess() {
        boolean wasBed = targetIsBed;
        cleanup();
        if (wasBed) {
            cooldownUntil = System.currentTimeMillis() + 500L;
            transition(State.COOLDOWN, "bed success cooldownUntil=" + cooldownUntil);
        }
    }

    boolean controlsInteractions() {
        return interactionLocked && target != null;
    }

    boolean isDigging() {
        return digging && target != null;
    }

    boolean usesControllerMining() {
        return false;
    }

    BlockPos target() {
        return target;
    }

    float progress() {
        if (target == null) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, accumulatedDamage / threshold()));
    }

    boolean showsProgress() {
        return (int) showProgress.getInput() != 0 && !(targetIsBed && surroundings.isToggled());
    }

    int targetColor() {
        if ((int) showTarget.getInput() == 0) return 0;
        float progress = progress();
        int red;
        int green;
        if (progress <= 0.5F) {
            red = 255;
            green = Math.round(progress * 510.0F);
        }
        else {
            red = Math.round((1.0F - progress) * 510.0F);
            green = 255;
        }
        return 0xE0000000 | (Math.max(0, Math.min(255, red)) << 16) | (Math.max(0, Math.min(255, green)) << 8);
    }

    int progressColor() {
        if ((int) showProgress.getInput() == 0) return 0;
        float progress = progress();
        int red = progress <= 0.5F ? 255 : Math.round((1.0F - progress) * 510.0F);
        int green = progress <= 0.5F ? Math.round(progress * 510.0F) : 255;
        return 0xE0000000 | (Math.max(0, Math.min(255, red)) << 16) | (Math.max(0, Math.min(255, green)) << 8);
    }

    boolean usesMoveFix() {
        return controlsInteractions() && (int) moveFix.getInput() != 0;
    }

    private boolean ready() {
        return owner.isEnabled() && Utils.nullCheck() && mc().currentScreen == null
                && mc().thePlayer.capabilities.allowEdit && !mc().thePlayer.capabilities.isCreativeMode
                && !mc().thePlayer.isSpectator() && !mc().thePlayer.isDead && !owner.shouldYieldToKillAura();
    }

    private String unavailableReason() {
        if (!owner.isEnabled()) return "module disabled";
        if (!Utils.nullCheck()) return "world or player unavailable";
        if (mc().currentScreen != null) return "screen=" + mc().currentScreen.getClass().getSimpleName();
        if (!mc().thePlayer.capabilities.allowEdit) return "editing not allowed";
        if (mc().thePlayer.capabilities.isCreativeMode) return "creative";
        if (mc().thePlayer.isSpectator()) return "spectator";
        if (mc().thePlayer.isDead) return "dead";
        if (owner.shouldYieldToKillAura()) return "Kill Aura priority";
        return "unknown";
    }

    private boolean targetIsValid() {
        if (mc().theWorld.isAirBlock(target) || !breakable(target) || !withinReach(target)) return false;
        if (!targetIsBed) {
            BlockPos next = findTarget();
            return next == null || !(block(next) instanceof BlockBed);
        }
        return true;
    }

    private BlockPos findTarget() {
        int radius = (int) Math.ceil(effectiveReach());
        BlockPos origin = new BlockPos(mc().thePlayer.getPositionEyes(1.0F));
        List<BlockPos[]> beds = new ArrayList<BlockPos[]>();
        Set<BlockPos> seen = new HashSet<BlockPos>();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos[] pair = OwnBedTracker.footHeadPair(origin.add(x, y, z));
                    if (pair != null && seen.add(pair[0]) && breakable(pair[0])
                            && (withinReach(pair[0]) || withinReach(pair[1]))) {
                        beds.add(pair);
                        debug("target candidate foot=" + pair[0] + " head=" + pair[1]
                                + " footDistance=" + distanceSquared(pair[0]) + " headDistance=" + distanceSquared(pair[1]));
                    }
                }
            }
        }
        beds.sort(new Comparator<BlockPos[]>() {
            @Override
            public int compare(BlockPos[] first, BlockPos[] second) {
                return Double.compare(closestBedDistance(first), closestBedDistance(second));
            }
        });
        for (BlockPos[] pair : beds) {
            if (whitelist.isToggled() && OwnBedTracker.isOwnBed(pair)) {
                debug("target skipped own bed foot=" + pair[0]);
                continue;
            }
            if (!surroundings.isToggled()) {
                BlockPos selected = closerBedHalf(pair);
                debug("target selected bed surroundings=false target=" + selected);
                return selected;
            }
            BlockPos cover = findSurroundingBlock(pair);
            if (cover == null) continue;
            if (!toolCheck.isToggled() || hasRequiredTool(block(cover))) {
                debug("target selected defense target=" + cover + " exposed=" + isExposed(cover)
                        + " distance=" + distanceSquared(cover));
                return cover;
            }
            debug("target skipped defense lacking tool target=" + cover);
        }
        return null;
    }

    private BlockPos findSurroundingBlock(BlockPos[] pair) {
        if (isExposed(pair[0]) || isExposed(pair[1])) return closerBedHalf(pair);
        List<BlockPos> candidates = new ArrayList<BlockPos>();
        for (BlockPos half : pair) for (EnumFacing direction : EnumFacing.values()) {
            if (direction == EnumFacing.DOWN) continue;
            BlockPos position = half.offset(direction);
            if (breakable(position) && !(block(position) instanceof BlockBed) && withinReach(position)
                    && (!toolCheck.isToggled() || hasRequiredTool(block(position)))
                    && !candidates.contains(position)) candidates.add(position);
        }
        if (candidates.isEmpty()) return null;
        candidates.sort(new Comparator<BlockPos>() {
            @Override
            public int compare(BlockPos first, BlockPos second) {
                return Double.compare(distanceSquared(first), distanceSquared(second));
            }
        });
        return candidates.get(0);
    }

    private BlockPos closerBedHalf(BlockPos[] pair) {
        boolean firstReachable = withinReach(pair[0]);
        boolean secondReachable = withinReach(pair[1]);
        if (!firstReachable) return secondReachable ? pair[1] : null;
        if (!secondReachable) return pair[0];
        return distanceSquared(pair[0]) <= distanceSquared(pair[1]) ? pair[0] : pair[1];
    }

    private double closestBedDistance(BlockPos[] pair) {
        return Math.min(withinReach(pair[0]) ? distanceSquared(pair[0]) : Double.MAX_VALUE,
                withinReach(pair[1]) ? distanceSquared(pair[1]) : Double.MAX_VALUE);
    }

    private boolean isExposed(BlockPos position) {
        for (EnumFacing direction : EnumFacing.values()) {
            if (direction == EnumFacing.DOWN) continue;
            BlockPos neighbor = position.offset(direction);
            if (block(neighbor).isReplaceable(mc().theWorld, neighbor)) return true;
        }
        return false;
    }

    private boolean withinReach(BlockPos pos) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null) return false;
        double distanceSquared = mc().thePlayer.getPositionEyes(1.0F).squareDistanceTo(
                RotationUtils.closestPointOnAabb(box, mc().thePlayer.getPositionEyes(1.0F)));
        boolean reachable = distanceSquared <= effectiveReach() * effectiveReach();
        if (!reachable) debug("reach rejected target=" + pos + " distance=" + Math.sqrt(distanceSquared)
                + " effectiveRange=" + effectiveReach() + " configuredRange=" + range.getInput());
        return reachable;
    }

    private double distanceSquared(BlockPos pos) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null) return Double.MAX_VALUE;
        Vec3 eye = mc().thePlayer.getPositionEyes(1.0F);
        return eye.squareDistanceTo(RotationUtils.closestPointOnAabb(box, eye));
    }

    private Block block(BlockPos pos) {
        return mc().theWorld.getBlockState(pos).getBlock();
    }

    private boolean breakable(BlockPos pos) {
        Block block = block(pos);
        return block != Blocks.air && block.getBlockHardness(mc().theWorld, pos) > 0.0F;
    }

    private int bestTool(Block block) {
        int slot = Utils.getTool(block);
        return slot >= 0 ? slot : mc().thePlayer.inventory.currentItem;
    }

    private boolean hasRequiredTool(Block block) {
        Material material = block.getMaterial();
        if (material != Material.iron && material != Material.anvil && material != Material.rock) return true;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc().thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.getItem() instanceof ItemPickaxe) return true;
        }
        return false;
    }

    private float relativeHardness(IBlockState state, BlockPos position, int slot, boolean onGround) {
        Block block = state.getBlock();
        float hardness = block.getBlockHardness(mc().theWorld, position);
        if (hardness <= 0.0F) return 0.0F;
        return digSpeed(state, slot, onGround) / hardness / (canHarvest(block, slot) ? 30.0F : 100.0F);
    }

    private float digSpeed(IBlockState state, int slot, boolean onGround) {
        ItemStack stack = mc().thePlayer.inventory.getStackInSlot(slot);
        float speed = stack == null ? 1.0F : stack.getStrVsBlock(state.getBlock());
        if (speed > 1.0F && stack != null) {
            int efficiency = EnchantmentHelper.getEnchantmentLevel(net.minecraft.enchantment.Enchantment.efficiency.effectId, stack);
            if (efficiency > 0) speed += efficiency * efficiency + 1;
        }
        if (mc().thePlayer.isPotionActive(Potion.digSpeed)) {
            speed *= 1.0F + (mc().thePlayer.getActivePotionEffect(Potion.digSpeed).getAmplifier() + 1) * 0.2F;
        }
        if (mc().thePlayer.isPotionActive(Potion.digSlowdown)) {
            switch (mc().thePlayer.getActivePotionEffect(Potion.digSlowdown).getAmplifier()) {
                case 0: speed *= 0.3F; break;
                case 1: speed *= 0.09F; break;
                case 2: speed *= 0.0027F; break;
                default: speed *= 0.00081F; break;
            }
        }
        if (mc().thePlayer.isInsideOfMaterial(Material.water) && !EnchantmentHelper.getAquaAffinityModifier(mc().thePlayer)) speed /= 5.0F;
        if (!onGround) speed /= 5.0F;
        return speed;
    }

    private boolean canHarvest(Block block, int slot) {
        if (block.getMaterial().isToolNotRequired()) return true;
        ItemStack stack = mc().thePlayer.inventory.getStackInSlot(slot);
        return stack != null && stack.canHarvestBlock(block);
    }

    private float threshold() {
        return 1.0F - 0.3F * (float) (speed.getInput() / 100.0D);
    }

    private EnumFacing breakFace(BlockPos position) {
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(position);
        if (box == null) return EnumFacing.UP;
        Vec3 point = RotationUtils.closestPointOnAabb(box, mc().thePlayer.getPositionEyes(1.0F), 0.12);
        return BlockUtils.facingFromBlockCenterToPoint(position, point);
    }

    private void equipTool() {
        if (toolSlot == mc().thePlayer.inventory.currentItem) return;
        if (intendedSlot == -1) intendedSlot = mc().thePlayer.inventory.currentItem;
        debug("tool switch from=" + mc().thePlayer.inventory.currentItem + " to=" + toolSlot + " restore=" + intendedSlot);
        mc().thePlayer.inventory.currentItem = toolSlot;
        if (mc().playerController != null) AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
    }

    private void restoreSlot() {
        if (intendedSlot < 0) return;
        if (!Utils.nullCheck() || mc().thePlayer.inventory.currentItem != toolSlot) {
            debug("tool restore discarded slot=" + intendedSlot + " because world or player unavailable");
            intendedSlot = -1;
            return;
        }
        debug("tool restore from=" + mc().thePlayer.inventory.currentItem + " to=" + intendedSlot);
        mc().thePlayer.inventory.currentItem = intendedSlot;
        if (mc().playerController != null) AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc().playerController);
        intendedSlot = -1;
    }

    void recordIntendedSlot(int slot) {
        if (slot >= 0 && slot < 9) {
            intendedSlot = slot;
            debug("tool requested restore slot=" + slot);
        }
    }

    void scrollIntendedSlot(int delta) {
        int slot = intendedSlot >= 0 ? intendedSlot : mc().thePlayer.inventory.currentItem;
        recordIntendedSlot(Math.floorMod(slot - Integer.compare(delta, 0), 9));
    }

    private void send(C07PacketPlayerDigging.Action action) {
        if (Utils.nullCheck() && target != null) {
            EnumFacing packetFace = action == C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK ? EnumFacing.DOWN : face;
            debug("packet digging action=" + action + " target=" + target + " face=" + packetFace + " " + rotationStatus());
            mc().thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(action, target, packetFace));
        }
    }

    private void animate() {
        if (swing.isToggled()) {
            debug("packet animation local swing target=" + target);
            mc().thePlayer.swingItem();
        }
        else {
            debug("packet animation C0A target=" + target);
            mc().thePlayer.sendQueue.addToSendQueue(new net.minecraft.network.play.client.C0APacketAnimation());
        }
    }

    private net.minecraft.client.Minecraft mc() {
        return net.minecraft.client.Minecraft.getMinecraft();
    }

    private double effectiveReach() {
        return Math.min(4.5, range.getInput());
    }

    private String describeTarget() {
        if (target == null) return "target=null face=" + face + " bed=" + targetIsBed;
        if (!Utils.nullCheck()) return "target=" + target + " distance=unavailable face=" + face + " bed=" + targetIsBed;
        return "target=" + target + " distance=" + Math.sqrt(distanceSquared(target)) + " face=" + face + " bed=" + targetIsBed;
    }

    private String rotationStatus() {
        return "requested=" + rotationRequested + " required=" + requiredYaw + "/" + requiredPitch
                + " applied=" + RotationHelper.get().getServerYaw() + "/" + RotationHelper.get().getServerPitch()
                + " source=" + RotationHelper.get().getServerYawSource() + "/" + RotationHelper.get().getServerPitchSource();
    }

    private void transition(State next, String reason) {
        if (state != next) debug("state " + state + " -> " + next + " reason=" + reason);
        state = next;
    }

    private void debug(String message) {
        Diagnostics.log("silent-bed", message);
    }
}
