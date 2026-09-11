package mindless.module.impl.player;

import mindless.event.ClientRotationEvent;
import mindless.event.GameTickEvent;
import mindless.event.PreAttackEvent;
import mindless.event.PrePlayerInteractEvent;
import mindless.event.PreMotionEvent;
import mindless.helper.RotationHelper;
import mindless.event.PreSlotScrollEvent;
import mindless.event.SlotUpdateEvent;
import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.combat.KillAura;
import mindless.module.impl.render.BlockOverlay;
import mindless.rotation.RotationSource;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.ProfiledButtonSetting;
import mindless.module.setting.impl.ProfiledSliderSetting;
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
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.*;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class BedAura extends Module {

    private static final String[] MODES = {"Silent", "Legit"};

    private final SliderSetting mode;
    private final SliderSetting fov;
    private final SliderSetting range;
    private final SliderSetting rate;
    private final SliderSetting aimSpeed;
    private final SliderSetting breakDelay;
    private final SliderSetting breakSpeed;
    private final ButtonSetting breakFromOutside;
    private final ButtonSetting whitelistOwnBed;
    private final ButtonSetting prioritizeKillAura;
    private final GroupSetting swapGroup;
    private final ButtonSetting autoTool;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    private final ButtonSetting spoofItem;
    private final ButtonSetting renderOutline;
    private final ColorSetting outlineColor;
    private final GroupSetting silentGroup;
    private final ProfiledSliderSetting silentRange;
    private final ProfiledSliderSetting silentSpeed;
    private final ProfiledButtonSetting silentSurroundings;
    private final ProfiledButtonSetting silentToolCheck;
    private final ProfiledButtonSetting silentWhitelist;
    private final ProfiledButtonSetting silentSwing;
    private final ProfiledSliderSetting silentMoveFix;
    private final ProfiledSliderSetting silentShowTarget;
    private final ProfiledSliderSetting silentShowProgress;
    private final SilentBedBreaker silent;

    private static final int MS_PER_TICK = 50;
    private static final double BED_FIND_EXTRA_BLOCKS = 1.0;
    private static final ItemStack BED_DISPLAY_STACK = new ItemStack(Items.bed);
private static final double AIM_FACE_INSET = 0.12;
    private final List<BlockPos[]> bedPairsCache = new ArrayList<>();
    private int scanCooldown;

    private BlockPos targetPos;
    private Vec3 targetHitVec;
    private EnumFacing targetSide;

    private BlockPos lockedPos;
    private EnumFacing lockedSide;

    private boolean miningActive;
    private boolean rotationPending;
    private boolean controlsInput;
    private int rotationAlignedTicks;
    private int retargetDelayTicks;
    private boolean lockedTargetBed;
    private int hotbarProgrammaticDepth;
    private boolean hasSwapped;
    private int swappedSlot = -1;
    private int previousSlot = -1;
    private ItemStack originalVisualItem;
    private boolean lastOutsidePolicy;
    private BlockPos pathBedFoot;
    private Vec3 pathDestination;
    private int pathInitialBlocks;
    private int activeMode = -1;


    public BedAura() {
        super("Bed Breaker", "Smoothly breaks nearby beds and their defenses.", category.player);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(breakSpeed = new SliderSetting("Break speed", "x", 1.0, 1.0, 2.0, 0.02));
        this.registerSetting(breakDelay = new SliderSetting("Break delay", "ms", 250.0, 0.0, 250.0, 50.0));
        this.registerSetting(fov = new SliderSetting("FOV", "", 180.0, 30.0, 360.0, 1.0));
        this.registerSetting(range = new SliderSetting("Range", " block", 4.5, 2.0, 6.0, 0.1));
        this.registerSetting(rate = new SliderSetting("Rate", "ms", 250.0, 50.0, 2000.0, 50.0));
        this.registerSetting(aimSpeed = new SliderSetting("Aim speed", 14, 1, 30, 1));
        // Defaulted on so behaviour is unchanged for anyone already using it: the old default
        // of off produced this same path, it was only named backwards.
        this.registerSetting(breakFromOutside = new ButtonSetting("Break from outside", true));
        this.registerSetting(whitelistOwnBed = new ButtonSetting("Whitelist own bed", true));
        this.registerSetting(prioritizeKillAura = new ButtonSetting("Prioritize KillAura", false));
        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(autoTool = new ButtonSetting(swapGroup, "Auto tool", true));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));
        this.registerSetting(spoofItem = new ButtonSetting(swapGroup, "Keep Original Item", false, "Spoof item"));
        this.registerSetting(renderOutline = new ButtonSetting("Render block outline", true));
        this.registerSetting(outlineColor = new ColorSetting("Outline color", 255, 64, 64, 229));
        this.registerSetting(silentGroup = new GroupSetting("Silent"));
        this.registerSetting(silentRange = new ProfiledSliderSetting(silentGroup, "Range", " block", 4.5, 2.0, 4.5, 0.1, "Silent.Range") {
            @Override
            public void loadProfile(com.google.gson.JsonObject data) {
                super.loadProfile(data);
                setValue(Double.isFinite(getInput()) ? getInput() : 4.5);
            }
        });
        this.registerSetting(silentSpeed = new ProfiledSliderSetting(silentGroup, "Speed", "%", 33.0, 0.0, 100.0, 1.0, "Silent.Speed"));
        this.registerSetting(silentSurroundings = new ProfiledButtonSetting(silentGroup, "Surroundings", true, "Silent.Surroundings"));
        this.registerSetting(silentToolCheck = new ProfiledButtonSetting(silentGroup, "Tool check", true, "Silent.Tool check"));
        this.registerSetting(silentWhitelist = new ProfiledButtonSetting(silentGroup, "Whitelist", true, "Silent.Whitelist"));
        this.registerSetting(silentSwing = new ProfiledButtonSetting(silentGroup, "Swing", true, "Silent.Swing"));
        this.registerSetting(silentMoveFix = new ProfiledSliderSetting(silentGroup, "Move fix", 1, new String[]{"None", "Silent", "Strict"}, "Silent.Move fix"));
        this.registerSetting(silentShowTarget = new ProfiledSliderSetting(silentGroup, "Show target", 1, new String[]{"None", "Default", "HUD"}, "Silent.Show target"));
        this.registerSetting(silentShowProgress = new ProfiledSliderSetting(silentGroup, "Show progress", 1, new String[]{"None", "Default", "HUD"}, "Silent.Show progress"));
        silent = new SilentBedBreaker(this, silentRange, silentSpeed,
                silentSurroundings, silentToolCheck, silentWhitelist, silentSwing, silentMoveFix,
                silentShowTarget, silentShowProgress);
    }

    @Override
    public void guiUpdate() {
        boolean legit = isLegitMode();
        breakSpeed.setVisible(legit, this);
        breakDelay.setVisible(legit, this);
        fov.setVisible(legit, this);
        range.setVisible(legit, this);
        rate.setVisible(legit, this);
        aimSpeed.setVisible(legit, this);
        breakFromOutside.setVisible(false, this);
        whitelistOwnBed.setVisible(legit, this);
        swapGroup.setVisible(legit, this);
        autoTool.setVisible(legit, this);
        switchBackWhenDone.setVisible(legit && autoTool.isToggled(), this);
        overrideSwapBack.setVisible(legit && autoTool.isToggled(), this);
        spoofItem.setVisible(legit && autoTool.isToggled(), this);
        renderOutline.setVisible(legit, this);
        outlineColor.setVisible(legit && renderOutline.isToggled(), this);
        silentGroup.setVisible(!legit, this);
        silentRange.setVisible(!legit, this);
        silentSpeed.setVisible(!legit, this);
        silentSurroundings.setVisible(!legit, this);
        silentToolCheck.setVisible(!legit, this);
        silentWhitelist.setVisible(!legit, this);
        silentSwing.setVisible(!legit, this);
        silentMoveFix.setVisible(!legit, this);
        silentShowTarget.setVisible(!legit, this);
        silentShowProgress.setVisible(!legit, this);
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    private boolean isLegitMode() {
        return (int) mode.getInput() == 1;
    }

    private boolean shouldBreakFromOutside() {
        return isLegitMode() || breakFromOutside.isToggled();
    }

    @Override
    public void onDisable() {
        silent.cleanup();
        resetMining();
        activeMode = -1;
        bedPairsCache.clear();
        scanCooldown = 0;
        retargetDelayTicks = 0;
    }

    public void onWorldChange() {
        silent.cleanup();
        resetMining();
        activeMode = -1;
    }

    @Override
    public void onUpdate() {
        if (Utils.nullCheck()) {
            OwnBedTracker.tick();
            int selectedMode = (int) mode.getInput();
            if (activeMode != -1 && activeMode != selectedMode) {
                silent.cleanup();
                resetMining();
            }
            activeMode = selectedMode;
            if (!isLegitMode()) {
                if (controlsInput) releaseInputControl();
                return;
            }
            applyMiningKeyState();
        }
    }

    @SubscribeEvent
    public void onGameTick(GameTickEvent event) {
        prepareSilentTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START) prepareSilentTick();
    }

    private void prepareSilentTick() {
        if (!isEnabled() || !Utils.nullCheck()) return;
        int selectedMode = (int) mode.getInput();
        if (activeMode != -1 && activeMode != selectedMode) {
            silent.cleanup();
            resetMining();
        }
        activeMode = selectedMode;
        if (!isLegitMode()) silent.prepareTick();
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            silent.cleanup();
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
            previousSlot = Math.floorMod((previousSlot >= 0 ? previousSlot : mc.thePlayer.inventory.currentItem) - slot, InventoryPlayer.getHotbarSize());
            originalVisualItem = copyStack(previousSlot);
        }
        if (!isLegitMode() && Utils.nullCheck()) {
            silent.scrollIntendedSlot(e.slot);
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
            originalVisualItem = copyStack(previousSlot);
        }
        if (!isLegitMode()) silent.recordIntendedSlot(e.slot);
        e.setCanceled(true);
    }

    private boolean shouldSuppressManualMouse() {
        return isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura()
                && (isLegitMode() ? isActivelyMining() : silent.controlsInteractions());
    }

    public void applyMiningKeyState() {
        if (!isLegitMode()) {
            if (controlsInput) releaseInputControl();
            return;
        }
        if (!canMineBlocks() || shouldYieldToKillAura() || miningActive && !hasMiningRotation()) {
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
        return isLegitMode() ? miningActive && canMineBlocks() ? targetPos : null : silent.target();
    }

    public boolean isActivelyMining() {
        return isLegitMode() ? miningActive && isEnabled() && Utils.nullCheck() && mc.currentScreen == null && canMineBlocks() && !shouldYieldToKillAura() && hasMiningRotation()
                : silent.isDigging();
    }

    public boolean isBreakingRoute() {
        if (!isLegitMode()) return isEnabled() && silent.controlsInteractions();
        return isEnabled() && Utils.nullCheck() && mc.currentScreen == null
                && canMineBlocks() && !shouldYieldToKillAura()
                && (pathDestination != null || miningActive);
    }

    public boolean isPrioritizingKillAura() {
        return prioritizeKillAura.isToggled();
    }

    public boolean shouldOverrideFastMine() {
        return isLegitMode() && isActivelyMining();
    }

    public boolean shouldSuppressControllerMining() {
        return !isLegitMode() && isEnabled() && silent.controlsInteractions();
    }

    public boolean controlsInteractions() {
        return isLegitMode() ? isActivelyMining() : silent.controlsInteractions();
    }

    public boolean shouldYieldInteractionTo(RotationSource source) {
        return !isLegitMode() && controlsInteractions() && source != null
                && source.getPriority() > RotationSource.BED_AURA.getPriority();
    }

    public boolean usesControllerMining() {
        return isLegitMode() && isActivelyMining();
    }

    public float getBreakSpeedMultiplier() {
        float multiplier = (float) breakSpeed.getInput();
        return multiplier > 1.0f ? multiplier : 1.0f;
    }

    public int getBreakDelayTicks() {
        return Math.max(0, Math.min(5, (int) (breakDelay.getInput() / 50.0)));
    }

    public float getAuraBreakProgress() {
        if (!isLegitMode()) return silent.progress();
        if (!canMineBlocks() || !miningActive || mc.playerController == null) {
            return 0f;
        }
        BlockPos currentBlock = AccessorBridge.PlayerControllerMP_getCurrentBlock(mc.playerController);
        if (targetPos == null || currentBlock == null || !targetPos.equals(currentBlock)) {
            return 0f;
        }
        return AccessorBridge.PlayerControllerMP_getCurBlockDamageMP(mc.playerController);
    }

    public float getAuraTotalProgress() {
        if (!isLegitMode()) return silent.progress();
        float blockProgress = Math.max(0.0f, Math.min(1.0f, getAuraBreakProgress()));
        if (isAuraTargetBed()) {
            return blockProgress;
        }
        if (pathDestination == null || pathInitialBlocks <= 0 || !Utils.nullCheck()) {
            return blockProgress;
        }
        int remaining = countPathBlocks(mc.thePlayer.getPositionEyes(1.0f), pathDestination);
        if (remaining == Integer.MAX_VALUE) return blockProgress;
        int completed = Math.max(0, pathInitialBlocks - remaining);
        return Math.max(0.0f, Math.min(1.0f,
                (completed + blockProgress) / (float) pathInitialBlocks));
    }

    public boolean shouldShowAuraProgress() {
        return isLegitMode() || silent.showsProgress();
    }

    public int getAuraProgressColor() {
        return isLegitMode() ? -1 : silent.progressColor();
    }

    public String getAuraToolName() {
        if (!Utils.nullCheck()) return "Bed Breaker";
        if (isAuraTargetBed()) return "Bed";
        net.minecraft.item.ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) return "Hand";
        if (stack.getItem() instanceof net.minecraft.item.ItemShears) return "Shears";
        if (stack.getItem() instanceof net.minecraft.item.ItemPickaxe) return "Pickaxe";
        if (stack.getItem() instanceof net.minecraft.item.ItemAxe) return "Axe";
        if (stack.getItem() instanceof net.minecraft.item.ItemSpade) return "Shovel";
        return "Hand";
    }

    public ItemStack getAuraToolStack() {
        if (isAuraTargetBed()) return BED_DISPLAY_STACK;
        return Utils.nullCheck() ? mc.thePlayer.getHeldItem() : null;
    }

    public boolean isAuraTargetBed() {
        BlockPos position = isLegitMode() ? targetPos : silent.target();
        return Utils.nullCheck() && position != null
                && BlockUtils.getBlock(position) instanceof BlockBed;
    }

    public boolean isSpoofingHeldItem() {
        return spoofItem.isToggled() && hasSwapped && previousSlot != -1;
    }

    public ItemStack getOriginalVisualItem() {
        return originalVisualItem;
    }
public boolean shouldOverrideMouseOver() {
        return isLegitMode() && isActivelyMining() && canMineBlocks()
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

        MovingObjectPosition mop = RotationUtils.rayCastBlock(range.getInput(),
                RotationHelper.get().getServerYaw(), RotationHelper.get().getServerPitch());
        if (mop == null || !targetPos.equals(mop.getBlockPos())) return;
        mc.objectMouseOver = mop;
        mc.pointedEntity = null;

        EntityRenderer renderer = mc.entityRenderer;
        AccessorBridge.EntityRenderer_setPointedEntity(renderer, null);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onClientRotation(ClientRotationEvent e) {
        rotationPending = false;
        if (!isLegitMode()) {
            silent.requestRotation(e);
            return;
        }
        if (!isEnabled() || !Utils.nullCheck() || mc.currentScreen != null || !canMineBlocks()) {
            resetMining();
            return;
        }
        if (shouldYieldToKillAura()) {
            resetMining();
            return;
        }
        double reach = range.getInput();
        double reachSq = reach * reach;

        boolean outsidePolicy = shouldBreakFromOutside();
        if (outsidePolicy != lastOutsidePolicy) {
            lockedPos = null;
            lockedSide = null;
            targetPos = null;
            targetHitVec = null;
            targetSide = null;
            rotationAlignedTicks = 0;
            lastOutsidePolicy = outsidePolicy;
        }

        if (retargetDelayTicks > 0) {
            retargetDelayTicks--;
            return;
        }

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
                boolean blockFinished = BlockUtils.getBlock(lockedPos) == Blocks.air;
                boolean finishedBed = lockedTargetBed && blockFinished;
                BlockPos continuingBed = !finishedBed && blockFinished && shouldBreakFromOutside()
                        ? pathBedFoot : null;
                Vec3 continuingDestination = continuingBed != null ? pathDestination : null;
                int continuingInitialBlocks = continuingBed != null ? pathInitialBlocks : 0;
                resetMining();
                if (continuingBed != null && continuingDestination != null) {
                    pathBedFoot = continuingBed;
                    pathDestination = continuingDestination;
                    pathInitialBlocks = continuingInitialBlocks;
                }
                if (finishedBed) retargetDelayTicks = 6;
                return;
            }
        }

        if (lockedPos != null) {
            targetPos = lockedPos;
            Choice refreshed = recalcLockedChoice(lockedPos, targetHitVec, reachSq);
            if (refreshed == null) {
                lockedPos = null;
                lockedSide = null;
                resetMining();
                return;
            }
            targetHitVec = refreshed.hitVec;
            targetSide = refreshed.side;
            lockedSide = refreshed.side;
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
            lockedTargetBed = BlockUtils.getBlock(best.pos) instanceof BlockBed;
            if (best.pathDestination != null) {
                boolean continuing = pathBedFoot != null && pathBedFoot.equals(best.bedFoot)
                        && pathDestination != null && pathDestination.equals(best.pathDestination)
                        && pathInitialBlocks > 0;
                pathBedFoot = best.bedFoot;
                pathDestination = best.pathDestination;
                if (!continuing) pathInitialBlocks = Math.max(1, best.pathBlocks);
            }
            rotationAlignedTicks = 0;
        }

        float baseYaw = e.getBaseYaw() != null ? e.getBaseYaw() : RotationUtils.serverRotations[0];
        float basePitch = e.getBasePitch() != null ? e.getBasePitch() : RotationUtils.serverRotations[1];
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

        rotationPending = true;
        e.requestRotation(mindless.rotation.RotationSource.BED_AURA, fixed[0], fixed[1]);
    }

    public void afterMotionResolved(PreMotionEvent event) {
        if (!isLegitMode()) {
            silent.actionTick(event);
            return;
        }
        boolean aligned = isEnabled() && Utils.nullCheck() && mc.currentScreen == null
                && canMineBlocks() && !shouldYieldToKillAura() && rotationPending
                && event.getYawSource() == RotationSource.BED_AURA
                && event.getPitchSource() == RotationSource.BED_AURA
                && hitsMiningTarget(event.getYaw(), event.getPitch());
        if (!aligned && miningActive) {
            resetMining();
            return;
        }
        rotationPending = false;
        rotationAlignedTicks = aligned ? Math.min(2, rotationAlignedTicks + 1) : 0;
        miningActive = rotationAlignedTicks >= 2;
        if (miningActive) updateAutoTool(BlockUtils.getBlock(targetPos));
        if (!aligned && controlsInput) releaseInputControl();
    }

    private boolean hasMiningRotation() {
        RotationHelper helper = RotationHelper.get();
        return helper.getServerYawSource() == RotationSource.BED_AURA
                && helper.getServerPitchSource() == RotationSource.BED_AURA
                && helper.getServerYaw() != null && helper.getServerPitch() != null
                && hitsMiningTarget(helper.getServerYaw(), helper.getServerPitch());
    }

    private boolean hitsMiningTarget(float yaw, float pitch) {
        if (targetPos == null || targetHitVec == null || !Utils.nullCheck()) return false;
        float[] desired = RotationUtils.getRotationsToPoint(targetHitVec.xCoord, targetHitVec.yCoord,
                targetHitVec.zCoord, yaw, pitch);
        if (Math.abs(MathHelper.wrapAngleTo180_float(desired[0] - yaw)) > 4.0F
                || Math.abs(desired[1] - pitch) > 4.0F) return false;
        MovingObjectPosition hit = RotationUtils.rayCastBlock(range.getInput(), yaw, pitch);
        return hit != null && targetPos.equals(hit.getBlockPos());
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

    private Choice recalcLockedChoice(BlockPos pos, Vec3 preferredHit, double reachSq) {
        AxisAlignedBB bb = BlockUtils.getBlockSelectionBox(pos);
        if (bb == null) {
            return null;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        if (eye.squareDistanceTo(RotationUtils.closestPointOnAabb(bb, eye)) > reachSq + 0.25) {
            return null;
        }
        if (shouldBreakFromOutside()) {
            Choice visible = traceVisibleBlock(pos, preferredHit, eye);
            if (visible != null) {
                return visible;
            }
            Vec3 center = new Vec3(
                    (bb.minX + bb.maxX) * 0.5,
                    (bb.minY + bb.maxY) * 0.5,
                    (bb.minZ + bb.maxZ) * 0.5
            );
            return traceVisibleBlock(pos, center, eye);
        }
        Vec3 hit = RotationUtils.closestPointOnAabb(bb, eye, AIM_FACE_INSET);
        return new Choice(pos, hit, BlockUtils.facingFromBlockCenterToPoint(pos, hit));
    }

    private Choice traceVisibleBlock(BlockPos expected, Vec3 point, Vec3 eye) {
        if (point == null) {
            return null;
        }
        Vec3 delta = point.subtract(eye);
        double length = delta.lengthVector();
        if (length < 1e-5) {
            return null;
        }
        Vec3 destination = point.addVector(
                delta.xCoord / length * 0.02,
                delta.yCoord / length * 0.02,
                delta.zCoord / length * 0.02
        );
        MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(eye, destination, false, true, false);
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !expected.equals(trace.getBlockPos()) || trace.hitVec == null || trace.sideHit == null) {
            return null;
        }
        return new Choice(expected, trace.hitVec, trace.sideHit);
    }

    @SubscribeEvent
    public void onRenderWorldLast(RenderWorldLastEvent e) {
        if (!isLegitMode()) {
            BlockPos silentTarget = silent.target();
            int color = silent.targetColor();
            if (isEnabled() && silentTarget != null && color != 0 && Utils.nullCheck()) {
                BlockOverlay.renderBlockOutline(silentTarget, color, color, 2.0f, true);
            }
            return;
        }
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
        rotationPending = false;
        rotationAlignedTicks = 0;
        lockedPos = null;
        lockedSide = null;
        lockedTargetBed = false;
        if (switchBackWhenDone.isToggled() && previousSlot != -1 && Utils.nullCheck()
                && mc.thePlayer.inventory.currentItem == swappedSlot) {
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
        swappedSlot = -1;
        previousSlot = -1;
        originalVisualItem = null;
        pathBedFoot = null;
        pathDestination = null;
        pathInitialBlocks = 0;
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

        if (shouldBreakFromOutside() && pathBedFoot != null && pathDestination != null) {
            Choice continuing = continuePath(reachSq);
            if (continuing != null) {
                return continuing;
            }
            pathBedFoot = null;
            pathDestination = null;
            pathInitialBlocks = 0;
        }

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

        if (ch.pathBlocks > 0) {
            timeEst += ch.pathBlocks * 1000.0;
        }

        if (breaking != null && breaking.equals(ch.pos) && curProg > 0.02f) {
            timeEst -= curProg * 12.0;
        }
        Vec3 eye = mc.thePlayer.getPositionEyes(1f);
        timeEst += eye.squareDistanceTo(ch.hitVec) * 0.002;
        return timeEst;
    }

    private List<Choice> buildCandidates(BlockPos[] pair, double reachSq) {
        if (isLegitMode()) {
            return buildLegitOutsideCandidates(pair, reachSq);
        }
        if (shouldBreakFromOutside()) {
            return buildVisibleOutsideCandidates(pair, reachSq);
        }

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

    private Choice continuePath(double reachSq) {
        BlockPos[] pair = null;
        for (BlockPos[] cached : bedPairsCache) {
            if (cached != null && cached.length >= 2 && pathBedFoot.equals(cached[0])) {
                pair = cached;
                break;
            }
        }
        if (pair == null) {
            return null;
        }

        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(
                eye, pathDestination, false, true, false);
        Choice choice = visibleTraceChoice(trace, eye, reachSq);
        if (choice == null) {
            return null;
        }
        int blocks = countPathBlocks(eye, pathDestination);
        if (blocks == Integer.MAX_VALUE) {
            return null;
        }
        return new Choice(choice.pos, choice.hitVec, choice.side,
                pathBedFoot, pathDestination, blocks);
    }

    private List<Choice> buildLegitOutsideCandidates(BlockPos[] pair, double reachSq) {
        Map<BlockPos, Choice> bestByFirstBlock = new HashMap<>();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        AxisAlignedBB bedBounds = BlockUtils.unionBlockBounds(pair[0], pair[1]);

        double[] horizontalSamples = {0.18, 0.5, 0.82};
        double[] verticalSamples = {0.25, 0.72};
        for (double xPart : horizontalSamples) {
            for (double zPart : horizontalSamples) {
                for (double yPart : verticalSamples) {
                    Vec3 destination = new Vec3(
                            bedBounds.minX + (bedBounds.maxX - bedBounds.minX) * xPart,
                            bedBounds.minY + (bedBounds.maxY - bedBounds.minY) * yPart,
                            bedBounds.minZ + (bedBounds.maxZ - bedBounds.minZ) * zPart
                    );
                    MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(
                            eye, destination, false, true, false);
                    Choice visible = visibleTraceChoice(trace, eye, reachSq);
                    if (visible == null) {
                        continue;
                    }
                    int pathBlocks = countPathBlocks(eye, destination);
                    if (pathBlocks == Integer.MAX_VALUE) {
                        continue;
                    }
                    Choice candidate = new Choice(visible.pos, visible.hitVec, visible.side,
                            pair[0], destination, pathBlocks);
                    Choice previous = bestByFirstBlock.get(candidate.pos);
                    if (previous == null || candidate.pathBlocks < previous.pathBlocks
                            || candidate.pathBlocks == previous.pathBlocks
                            && eye.squareDistanceTo(candidate.hitVec) < eye.squareDistanceTo(previous.hitVec)) {
                        bestByFirstBlock.put(candidate.pos, candidate);
                    }
                }
            }
        }
        return new ArrayList<>(bestByFirstBlock.values());
    }

    List<BlockPos> reachableBedRoute(BlockPos[] pair, double reach) {
        List<BlockPos> positions = new ArrayList<>();
        for (Choice choice : buildLegitOutsideCandidates(pair, reach * reach)) positions.add(choice.pos);
        return positions;
    }

    private int countPathBlocks(Vec3 start, Vec3 destination) {
        Vec3 delta = destination.subtract(start);
        double length = delta.lengthVector();
        if (length < 1.0E-5) {
            return 0;
        }
        int steps = Math.max(1, (int) Math.ceil(length / 0.04));
        Set<BlockPos> blocks = new HashSet<>();
        for (int i = 1; i <= steps; i++) {
            double progress = i / (double) steps;
            BlockPos pos = new BlockPos(
                    start.xCoord + delta.xCoord * progress,
                    start.yCoord + delta.yCoord * progress,
                    start.zCoord + delta.zCoord * progress);
            Block block = BlockUtils.getBlock(pos);
            if (canHitThrough(block)) {
                continue;
            }
            if (block.getBlockHardness(mc.theWorld, pos) < 0.0f) {
                return Integer.MAX_VALUE;
            }
            blocks.add(pos);
        }
        return blocks.size();
    }

    /**
     * Trace toward several points across the bed rather than assuming its immediately adjacent
     * blocks are exposed. The first collision on each ray is the layer a real player can reach;
     * after it breaks, the next scan naturally advances one layer inward.
     */
    private List<Choice> buildVisibleOutsideCandidates(BlockPos[] pair, double reachSq) {
        Map<BlockPos, Choice> bestByFirstBlock = new HashMap<>();
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        AxisAlignedBB bedBounds = BlockUtils.unionBlockBounds(pair[0], pair[1]);

        double[] horizontalSamples = {0.18, 0.5, 0.82};
        double[] verticalSamples = {0.25, 0.72};
        for (double xPart : horizontalSamples) {
            for (double zPart : horizontalSamples) {
                for (double yPart : verticalSamples) {
                    Vec3 destination = new Vec3(
                            bedBounds.minX + (bedBounds.maxX - bedBounds.minX) * xPart,
                            bedBounds.minY + (bedBounds.maxY - bedBounds.minY) * yPart,
                            bedBounds.minZ + (bedBounds.maxZ - bedBounds.minZ) * zPart
                    );
                    MovingObjectPosition trace = mc.theWorld.rayTraceBlocks(eye, destination, false, true, false);
                    Choice visible = visibleTraceChoice(trace, eye, reachSq);
                    if (visible == null) continue;
                    int pathBlocks = countPathBlocks(eye, destination);
                    if (pathBlocks == Integer.MAX_VALUE) continue;
                    Choice candidate = new Choice(visible.pos, visible.hitVec, visible.side,
                            pair[0], destination, pathBlocks);
                    Choice previous = bestByFirstBlock.get(candidate.pos);
                    if (previous == null || candidate.pathBlocks < previous.pathBlocks
                            || candidate.pathBlocks == previous.pathBlocks
                            && eye.squareDistanceTo(candidate.hitVec)
                            < eye.squareDistanceTo(previous.hitVec)) {
                        bestByFirstBlock.put(candidate.pos, candidate);
                    }
                }
            }
        }
        return new ArrayList<>(bestByFirstBlock.values());
    }

    private Choice visibleTraceChoice(MovingObjectPosition trace, Vec3 eye, double reachSq) {
        if (trace == null || trace.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || trace.hitVec == null || trace.sideHit == null) {
            return null;
        }
        BlockPos pos = trace.getBlockPos();
        if (pos == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.air || block.getBlockHardness(mc.theWorld, pos) < 0.0f) {
            return null;
        }
        AxisAlignedBB box = BlockUtils.getBlockSelectionBox(pos);
        if (box == null || eye.squareDistanceTo(RotationUtils.closestPointOnAabb(box, eye)) > reachSq + 1e-3) {
            return null;
        }
        if (block instanceof BlockBed && trace.sideHit == EnumFacing.DOWN) {
            return null;
        }
        return new Choice(pos, trace.hitVec, trace.sideHit);
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
            originalVisualItem = copyStack(previousSlot);
        }
        if (slot != mc.thePlayer.inventory.currentItem) {
            setSlot(slot);
        }
    }

    private void updateAutoTool(Block block) {
        if (autoTool.isToggled()) {
            equipBestHotbarTool(block);
            return;
        }
        if (!hasSwapped) {
            return;
        }
        if (switchBackWhenDone.isToggled() && previousSlot != -1
                && mc.thePlayer.inventory.currentItem == swappedSlot) {
            setSlot(previousSlot);
        }
        hasSwapped = false;
        swappedSlot = -1;
        previousSlot = -1;
        originalVisualItem = null;
    }

    private void setSlot(int slot) {
        if (slot == -1 || slot == mc.thePlayer.inventory.currentItem) {
            return;
        }
        hotbarProgrammaticDepth++;
        try {
            mc.thePlayer.inventory.currentItem = slot;
            swappedSlot = slot;
            hasSwapped = true;
            AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
        } finally {
            hotbarProgrammaticDepth--;
        }
    }

    private ItemStack copyStack(int slot) {
        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
        return stack == null ? null : stack.copy();
    }

    private boolean canMineBlocks() {
        return mc.thePlayer.capabilities.allowEdit
                && !mc.thePlayer.capabilities.isCreativeMode
                && !mc.thePlayer.isSpectator();
    }

    boolean shouldYieldToKillAura() {
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
        final BlockPos bedFoot;
        final Vec3 pathDestination;
        final int pathBlocks;

        Choice(BlockPos pos, Vec3 hitVec, EnumFacing side) {
            this(pos, hitVec, side, null, null, 0);
        }

        Choice(BlockPos pos, Vec3 hitVec, EnumFacing side,
               BlockPos bedFoot, Vec3 pathDestination, int pathBlocks) {
            this.pos = pos;
            this.hitVec = hitVec;
            this.side = side;
            this.bedFoot = bedFoot;
            this.pathDestination = pathDestination;
            this.pathBlocks = pathBlocks;
        }
    }
}
