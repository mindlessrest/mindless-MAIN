package mindless.module.impl.player;

import mindless.event.PrePlayerInteractEvent;
import mindless.event.PreSlotScrollEvent;
import mindless.event.SlotUpdateEvent;
import mindless.runtime.AccessorBridge;
import mindless.module.Module;
import mindless.module.setting.impl.BlockListSetting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.ItemListSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.RotationUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

public class AutoTool extends Module {
    private final GroupSetting timingGroup;
    private final SliderSetting activationTime;
    private final SliderSetting hoverDelay;
    private final SliderSetting nextHoverDelay;

    private final ButtonSetting ignoredHeldItemsToggle;
    private final ItemListSetting ignoredHeldItems;

    private final GroupSetting conditionsGroup;
    private final ButtonSetting onlyWhileCrouching;
    private final ButtonSetting requireLeftMouse;
    private final ButtonSetting disableInCreative;

    private final GroupSetting swapGroup;
    private final ButtonSetting switchBackWhenDone;
    private final ButtonSetting overrideSwapBack;
    public final ButtonSetting spoofItem;

    private final ButtonSetting blockWhitelistToggle;
    private final BlockListSetting blockWhitelist;
    private final ButtonSetting blockBlacklistToggle;
    private final BlockListSetting blockBlacklist;

    private boolean hasSwapped;
    private boolean manualOverride;
    public int previousSlot = -1;
    private int tickCounter;
    private int leftMouseDownSinceTick = -1;
    private int hoverStartTick = -1;
    private int nextHoverStartTick = -1;
    private int nextHoverSlot = -1;

    public AutoTool() {
        super("Auto Tool", "Switches to the best tool for the block.", category.player);
        this.liteModule = true;

        this.registerSetting(timingGroup = new GroupSetting("Timing"));
        this.registerSetting(activationTime = new SliderSetting(timingGroup, "Activation time", "ms", 0.0, 0.0, 1000.0, 25.0));
        this.registerSetting(hoverDelay = new SliderSetting(timingGroup, "Hover delay", "ms", 0.0, 0.0, 1000.0, 25.0));
        this.registerSetting(nextHoverDelay = new SliderSetting(timingGroup, "Next hover delay", "ms", 0.0, 0.0, 1000.0, 25.0));

        this.registerSetting(conditionsGroup = new GroupSetting("Conditions"));
        this.registerSetting(onlyWhileCrouching = new ButtonSetting(conditionsGroup, "Only while crouching", false));
        this.registerSetting(requireLeftMouse = new ButtonSetting(conditionsGroup, "Require Left mouse", true, "Require mouse down"));
        this.registerSetting(disableInCreative = new ButtonSetting(conditionsGroup, "Disable in creative", true));

        this.registerSetting(swapGroup = new GroupSetting("Swap"));
        this.registerSetting(switchBackWhenDone = new ButtonSetting(swapGroup, "Switch back when done", true, "Swap to previous slot"));
        this.registerSetting(overrideSwapBack = new ButtonSetting(swapGroup, "Override swap back", true));
        this.registerSetting(spoofItem = new ButtonSetting(swapGroup, "Spoof item", false));

        this.registerSetting(ignoredHeldItemsToggle = new ButtonSetting("Held item blacklist", false, "Ignore held items", "Restrict held items", "Allow while holding"));
        this.registerSetting(ignoredHeldItems = new ItemListSetting("Held items", "Items"));
        this.registerSetting(blockWhitelistToggle = new ButtonSetting("Block whitelist", false, "Restrict allowed blocks", "Blocks.Block whitelist"));
        this.registerSetting(blockWhitelist = new BlockListSetting("Whitelisted blocks", "Blocks", "Blocks.Whitelisted blocks"));
        this.registerSetting(blockBlacklistToggle = new ButtonSetting("Block blacklist", false, "Blocks.Block blacklist"));
        this.registerSetting(blockBlacklist = new BlockListSetting("Blacklisted blocks", "Block blacklist", "Blocks.Block blacklist", "Blocks.Blacklisted blocks"));
        this.closetModule = true;
    }

    @Override
    public void guiUpdate() {
        activationTime.setVisible(requireLeftMouse.isToggled(), this);
        ignoredHeldItems.setVisible(ignoredHeldItemsToggle.isToggled(), this);
        blockWhitelist.setVisible(blockWhitelistToggle.isToggled(), this);
        blockBlacklist.setVisible(blockBlacklistToggle.isToggled(), this);
    }

    @Override
    public void onEnable() {
        resetState(true);
    }

    @Override
    public void onDisable() {
        resetState(true);
    }

    /**
     * Hands the slot back to the player.
     *
     * Both of these used to swallow the change outright, so a swap made while the tool was equipped
     * did nothing until the swap-back fired -- reaching for a sword mid-mine left the tool in hand
     * and looked like the module re-equipping it. With "Override swap back" on, the player's pick is
     * meant to win, so let it through and stand down until the mining action ends; otherwise keep
     * holding the tool as before.
     */
    @SubscribeEvent
    public void onScrollSlot(PreSlotScrollEvent e) {
        if (!hasSwapped) {
            return;
        }
        if (overrideSwapBack.isToggled()) {
            yieldToManualSwap();
            return;
        }
        e.setCanceled(true);
    }

    @SubscribeEvent
    public void onSlotUpdate(SlotUpdateEvent e) {
        if (!hasSwapped) {
            return;
        }
        if (overrideSwapBack.isToggled()) {
            yieldToManualSwap();
            return;
        }
        e.setCanceled(true);
    }

    private void yieldToManualSwap() {
        manualOverride = true;
        hasSwapped = false;
        previousSlot = -1;
        resetNextHover();
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck()) {
            resetState(true);
            return;
        }

        int currentTick = ++tickCounter;
        boolean leftMouseDown = Mouse.isButtonDown(0);
        updateLeftMouseState(leftMouseDown, currentTick);

        // Stay out of the way for the rest of the swing the player took the slot on.
        if (manualOverride) {
            if (leftMouseDown) {
                return;
            }
            manualOverride = false;
        }

        if (!mc.inGameHasFocus || mc.currentScreen != null || mc.thePlayer.isDead || !mc.thePlayer.capabilities.allowEdit) {
            resetState(true);
            return;
        }

        if (disableInCreative.isToggled() && mc.thePlayer.capabilities.isCreativeMode) {
            resetState(true);
            return;
        }

        MovingObjectPosition hoverResult = RotationUtils.rayTraceBlockIfNoEntityInFront(
            mc.playerController.getBlockReachDistance(),
            mc.thePlayer.rotationYaw,
            mc.thePlayer.rotationPitch
        );
        BlockPos hoverPos = hoverResult != null
            && hoverResult.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            ? hoverResult.getBlockPos()
            : null;
        updateHoverState(hoverPos, currentTick);

        if (hoverPos == null || isUnsupportedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        if (onlyWhileCrouching.isToggled() && !mc.thePlayer.isSneaking()) {
            resetSlot();
            return;
        }

        if (requireLeftMouse.isToggled()) {
            if (!leftMouseDown) {
                resetSlot();
                return;
            }
            if (!hasElapsed(leftMouseDownSinceTick, activationTime.getInput(), currentTick)) {
                resetSlot();
                return;
            }
        }

        if (!hasElapsed(hoverStartTick, hoverDelay.getInput(), currentTick)) {
            resetSlot();
            return;
        }

        if (isUseBlocked()) {
            resetSlot();
            return;
        }

        if (isBlockedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        if (blockWhitelistToggle.isToggled() && !isWhitelistedBlock(hoverPos)) {
            resetSlot();
            return;
        }

        MovingObjectPosition swapResult = mc.objectMouseOver;
        BlockPos swapPos = swapResult != null
            && swapResult.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
            ? swapResult.getBlockPos()
            : null;
        if (swapPos == null || isUnsupportedBlock(swapPos)) {
            resetSlot();
            return;
        }

        int slot = Utils.getTool(BlockUtils.getBlock(swapPos));
        if (slot == -1) {
            resetNextHover();
            return;
        }

        if (previousSlot == -1 && slot != mc.thePlayer.inventory.currentItem) {
            previousSlot = mc.thePlayer.inventory.currentItem;
        }

        if (!hasSwapped) {
            setSlot(slot);
            resetNextHover();
            return;
        }

        if (slot == mc.thePlayer.inventory.currentItem) {
            resetNextHover();
            return;
        }

        if (nextHoverSlot != slot) {
            nextHoverSlot = slot;
            nextHoverStartTick = currentTick;
        }
        if (hasElapsed(nextHoverStartTick, nextHoverDelay.getInput(), currentTick)) {
            setSlot(slot);
            resetNextHover();
        }
    }

    private void updateLeftMouseState(boolean leftMouseDown, int currentTick) {
        if (leftMouseDown) {
            if (leftMouseDownSinceTick == -1) {
                leftMouseDownSinceTick = currentTick;
            }
        }
        else {
            leftMouseDownSinceTick = -1;
        }
    }

    private void updateHoverState(BlockPos hoverPos, int currentTick) {
        if (hoverPos == null) {
        hoverStartTick = -1;
        nextHoverStartTick = -1;
        nextHoverSlot = -1;
            return;
        }

        if (hoverStartTick == -1) {
            hoverStartTick = currentTick;
        }
    }

    private boolean isUseBlocked() {
        boolean useActive = Utils.isBindDown(mc.gameSettings.keyBindUseItem) || mc.thePlayer.isUsingItem();
        if (ignoredHeldItemsToggle.isToggled()) {
            int heldItemSlot = hasSwapped && previousSlot != -1
                    ? previousSlot
                    : mc.thePlayer.inventory.currentItem;
            if (ignoredHeldItems.matches(mc.thePlayer.inventory.getStackInSlot(heldItemSlot))) {
                return true;
            }
        }
        return useActive;
    }

    private boolean isUnsupportedBlock(BlockPos blockPos) {
        Block b = BlockUtils.getBlock(blockPos);
        return b == Blocks.bedrock || b == Blocks.barrier;
    }

    private boolean isBlockedBlock(BlockPos blockPos) {
        if (!blockBlacklistToggle.isToggled()) {
            return false;
        }
        return matchesBlockList(blockPos, blockBlacklist);
    }

    private boolean isWhitelistedBlock(BlockPos blockPos) {
        if (blockWhitelist.getBlocks().isEmpty()) {
            return false;
        }
        return matchesBlockList(blockPos, blockWhitelist);
    }

    private boolean matchesBlockList(BlockPos blockPos, BlockListSetting blockList) {
        IBlockState state = BlockUtils.getBlockState(blockPos);
        Block hoveredBlock = state.getBlock();
        if (hoveredBlock == null || Block.blockRegistry.getNameForObject(hoveredBlock) == null) {
            return false;
        }

        String registryId = Block.blockRegistry.getNameForObject(hoveredBlock).toString();
        int meta = hoveredBlock.getMetaFromState(state);
        String storageId = meta != 0 ? registryId + ":" + meta : registryId;
        return blockList.contains(storageId) || blockList.contains(registryId);
    }

    private boolean hasElapsed(int startTick, double requiredMs, int currentTick) {
        int requiredTicks = getRequiredTicks(requiredMs);
        if (requiredTicks <= 0) {
            return true;
        }
        return startTick != -1 && currentTick - startTick >= requiredTicks;
    }

    private int getRequiredTicks(double requiredMs) {
        if (requiredMs <= 0.0) {
            return 0;
        }
        return (int) Math.ceil(requiredMs / 50.0);
    }

    private void resetState(boolean resetTimers) {
        if (resetTimers) {
            tickCounter = 0;
            leftMouseDownSinceTick = -1;
            hoverStartTick = -1;
        }
        manualOverride = false;
        resetSlot();
    }

    private void resetSlot() {
        if (previousSlot != -1 && switchBackWhenDone.isToggled()) {
            setSlot(previousSlot);
        }
        previousSlot = -1;
        hasSwapped = false;
        resetNextHover();
    }

    private void setSlot(int currentItem) {
        if (currentItem == -1 || currentItem == mc.thePlayer.inventory.currentItem) {
            return;
        }
        mc.thePlayer.inventory.currentItem = currentItem;
        hasSwapped = true;
        AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
    }

    private void resetNextHover() {
        nextHoverStartTick = -1;
        nextHoverSlot = -1;
    }

    /**
     * Whether the hand should still be showing what the player chose rather than the tool.
     *
     * Asked every frame by the item renderers, so it is a condition and not a flag they
     * consume. The previous arming was a one-shot set once per client tick and cleared by
     * the first renderer to read it, which left most frames unsuppressed at any sensible
     * frame rate -- and it was evaluated before the swap it was meant to hide, so the tick
     * that actually changed slots was never covered at all. Between the two, the equip
     * animation played and the tool appeared.
     */
    public boolean isSpoofingHeldItem() {
        boolean active = spoofItem.isToggled() && hasSwapped && previousSlot != -1;
        if (active != reportedSpoofActive) {
            reportedSpoofActive = active;
            mindless.utility.Diagnostics.log("autotool", active
                    ? "spoof on, showing slot " + previousSlot
                    : "spoof off (toggled=" + spoofItem.isToggled()
                            + " swapped=" + hasSwapped + " previous=" + previousSlot + ")");
        }
        return active;
    }

    /** Last reported spoof state, so the log records the change and not every frame. */
    private boolean reportedSpoofActive;

    /** The slot the player had selected before Auto Tool took it. */
    public int getSpoofSlot() {
        return previousSlot;
    }
}
