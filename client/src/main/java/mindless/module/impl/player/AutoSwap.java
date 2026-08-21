package mindless.module.impl.player;

import mindless.event.PrePlayerInteractEvent;
import mindless.event.ReceivePacketEvent;
import mindless.event.SendPacketEvent;
import mindless.mixin.impl.accessor.IAccessorPlayerControllerMP;
import mindless.module.Module;
import mindless.module.setting.impl.BlockListSetting;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.BlockUtils;
import mindless.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class AutoSwap extends Module {
    private static final String[] ALLOWED_TYPES = { "Exact", "Variants", "Full", "Any" };
    private static final int EXACT = 0;
    private static final int VARIANTS = 1;
    private static final int FULL = 2;
    private static final int ANY = 3;

    private final SliderSetting allowedTypes;
    private final ButtonSetting swapOnRmb;
    private final ButtonSetting syncStackSize;
    private final ButtonSetting useBlockWhitelist;
    private final BlockListSetting blockWhitelist;

    private ItemStack trackedStack;
    private volatile int lastPlaceSlot = -1;
    private int lastSwapSlot = -1;
    private volatile int serverStackSize = -1;
    private long lastSwapTime;

    public AutoSwap() {
        super("Auto Swap", category.player);
        this.liteModule = true;
        this.registerSetting(allowedTypes = new SliderSetting("Allowed types", EXACT, ALLOWED_TYPES));
        this.registerSetting(swapOnRmb = new ButtonSetting("Swap on RMB", true));
        this.registerSetting(syncStackSize = new ButtonSetting("Sync stack size", true));
        this.registerSetting(useBlockWhitelist = new ButtonSetting("Use block whitelist", false));
        this.registerSetting(blockWhitelist = new BlockListSetting("Whitelisted blocks", "Blocks", "Blocks.Block whitelist", "Blocks.Whitelisted blocks"));
        blockWhitelist.visible = false;
        this.closetModule = true;
    }

    @Override
    public void guiUpdate() {
        blockWhitelist.setVisible(useBlockWhitelist.isToggled(), this);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent e) {
        if (!Utils.nullCheck() || !(e.getPacket() instanceof C08PacketPlayerBlockPlacement)) {
            return;
        }

        C08PacketPlayerBlockPlacement packet = (C08PacketPlayerBlockPlacement) e.getPacket();
        if (packet.getPlacedBlockDirection() == 255) {
            return;
        }

        ItemStack stack = packet.getStack();
        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return;
        }

        int currentSlot = mc.thePlayer.inventory.currentItem;
        boolean continuingStack = lastPlaceSlot == currentSlot && isSameStack(stack, trackedStack);
        if (!continuingStack) {
            serverStackSize = -1;
        }

        trackedStack = stack.copy();
        trackedStack.stackSize = 1;
        lastPlaceSlot = currentSlot;
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent e) {
        int hotbarSlot = lastPlaceSlot;
        if (hotbarSlot == -1) {
            return;
        }

        int inventorySlot = 36 + hotbarSlot;
        if (e.getPacket() instanceof S2FPacketSetSlot) {
            S2FPacketSetSlot packet = (S2FPacketSetSlot) e.getPacket();
            if (packet.func_149175_c() == 0 && packet.func_149173_d() == inventorySlot) {
                updateServerStackSize(packet.func_149174_e());
            }
        }
        else if (e.getPacket() instanceof S30PacketWindowItems) {
            S30PacketWindowItems packet = (S30PacketWindowItems) e.getPacket();
            ItemStack[] stacks = packet.getItemStacks();
            if (packet.func_148911_c() == 0 && inventorySlot < stacks.length) {
                updateServerStackSize(stacks[inventorySlot]);
            }
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        if (!Utils.nullCheck()) {
            resetState();
            return;
        }

        if (!mc.inGameHasFocus || mc.currentScreen != null
            || (swapOnRmb.isToggled() && !Utils.isBindDown(mc.gameSettings.keyBindUseItem))) {
            return;
        }

        if (trackedStack == null || lastPlaceSlot == -1 || mc.thePlayer.inventory.currentItem != lastPlaceSlot) {
            return;
        }

        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && !isSameStack(held, trackedStack)) {
            return;
        }

        if (syncStackSize.isToggled() && !mc.playerController.isInCreativeMode()) {
            if (serverStackSize != 0) {
                return;
            }
        }
        else if (held != null && held.stackSize > 0) {
            return;
        }

        if (!isWhitelistedBlock(trackedStack)) {
            return;
        }

        long now = System.currentTimeMillis();
        for (int slot = 8; slot >= 0; --slot) {
            if (slot == lastPlaceSlot) {
                continue;
            }
            if (slot == lastSwapSlot && now - lastSwapTime < 300L) {
                continue;
            }

            ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(slot);
            if (!matchesTrackedStack(candidate)) {
                continue;
            }

            swapToSlot(slot);
            lastSwapSlot = slot;
            lastSwapTime = now;
            break;
        }
    }

    private boolean isWhitelistedBlock(ItemStack stack) {
        if (!useBlockWhitelist.isToggled()) {
            return true;
        }

        if (stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }

        Block block = ((ItemBlock) stack.getItem()).getBlock();
        Object registryName = Block.blockRegistry.getNameForObject(block);
        if (block == null || registryName == null) {
            return false;
        }

        String registryId = registryName.toString();
        int meta = stack.getMetadata();
        String storageId = meta != 0 ? registryId + ":" + meta : registryId;
        return blockWhitelist.contains(storageId) || blockWhitelist.contains(registryId);
    }

    private boolean isSameStack(ItemStack first, ItemStack second) {
        return first != null && second != null
            && first.getItem() == second.getItem()
            && first.getMetadata() == second.getMetadata()
            && ItemStack.areItemStackTagsEqual(first, second);
    }

    private void updateServerStackSize(ItemStack stack) {
        serverStackSize = stack == null ? 0 : stack.stackSize;
    }

    private boolean matchesTrackedStack(ItemStack stack) {
        if (trackedStack == null || stack == null || !(stack.getItem() instanceof ItemBlock)) {
            return false;
        }

        int allowedType = (int) allowedTypes.getInput();
        if (allowedType == EXACT || allowedType == VARIANTS) {
            if (stack.getItem() != trackedStack.getItem()) {
                return false;
            }
            if (allowedType == EXACT && stack.getMetadata() != trackedStack.getMetadata()) {
                return false;
            }
            return ItemStack.areItemStackTagsEqual(stack, trackedStack);
        }

        Block block = ((ItemBlock) stack.getItem()).getBlock();
        if (block == null) {
            return false;
        }

        if (allowedType == FULL) {
            return block.isFullBlock();
        }

        return allowedType == ANY && !BlockUtils.isInteractable(block);
    }

    private void swapToSlot(int slot) {
        if (slot == -1 || slot == mc.thePlayer.inventory.currentItem) {
            return;
        }

        mc.thePlayer.inventory.currentItem = slot;
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
    }

    private void resetState() {
        trackedStack = null;
        lastPlaceSlot = -1;
        lastSwapSlot = -1;
        serverStackSize = -1;
        lastSwapTime = 0L;
    }
}
