package mindless.module.impl.bedwars;

import mindless.event.GuiUpdateEvent;
import mindless.event.ReceivePacketEvent;
import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.HypixelLanguage;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.resources.I18n;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraft.util.StringUtils;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class ResourceDeposit extends Module {
    private static final String[] CHEST_TITLE_KEYS = {
            "container.chest",
            "container.chestDouble",
            "container.enderchest"
    };
    private static final int SINGLE_CHEST_SIZE = 27;
    private static final int LARGE_CHEST_SIZE = 54;

    private ButtonSetting depositOnOpen;

    private GuiChest boundScreen;
    private EntityPlayer boundPlayer;
    private volatile ContainerChest boundContainer;
    private volatile int boundWindowId = -1;
    private volatile boolean passActive;
    private boolean withdrawing;
    private boolean autoDeposited;

    public ResourceDeposit() {
        super("Resource Deposit", "Deposits resources when a chest opens; scroll down to withdraw them.", category.bedwars);
        this.registerSetting(depositOnOpen = new ButtonSetting("Deposit on open", true));
    }

    @Override
    public void onDisable() {
        clearState();
    }

    public void onMouseWheel(int wheelDelta) {
        if (!isEnabled() || wheelDelta == 0 || passActive) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.thePlayer.inventory == null
                || mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }

        if (!isBoundChestCurrent()) {
            clearState();
            ContainerChest container = supportedContainer(mc == null ? null : mc.currentScreen);
            if (container == null
                    || mc.thePlayer == null
                    || mc.thePlayer.inventory == null
                    || mc.thePlayer.openContainer != container) {
                return;
            }

            boundScreen = (GuiChest) mc.currentScreen;
            boundPlayer = mc.thePlayer;
            boundContainer = container;
            boundWindowId = container.windowId;
        }

        if (isBoundChestCurrent()) {
            withdrawing = wheelDelta < 0;
            passActive = true;
        }
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (boundScreen == null) {
            beginAutoDeposit();
            if (boundScreen == null) {
                return;
            }
        }
        if (!isBoundChestCurrent()) {
            clearState();
            return;
        }
        if (!passActive) {
            return;
        }
        if (mc.thePlayer.inventory.getItemStack() != null || mc.playerController == null) {
            passActive = false;
            return;
        }

        Slot source = withdrawing
                ? findNextChestSourceSlot(boundContainer, boundPlayer.inventory)
                : findNextSourceSlot(boundContainer, boundPlayer.inventory);
        if (source == null) {
            passActive = false;
            return;
        }

        mc.playerController.windowClick(boundWindowId, source.slotNumber, 0, 1, mc.thePlayer);
    }

    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (boundScreen != null && event.gui != boundScreen) {
            clearState();
        }
    }

    @SubscribeEvent
    public void onGuiUpdate(GuiUpdateEvent event) {
        if (boundScreen != null && (!event.opened || event.guiScreen != boundScreen)) {
            clearState();
        }
    }

    @SubscribeEvent
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!passActive || !(event.getPacket() instanceof S32PacketConfirmTransaction)) {
            return;
        }

        S32PacketConfirmTransaction packet = (S32PacketConfirmTransaction) event.getPacket();
        if (packet.func_148888_e() || packet.getWindowId() != boundWindowId) {
            return;
        }

        final ContainerChest container = boundContainer;
        final int windowId = boundWindowId;
        if (mc == null) {
            return;
        }
        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (passActive && boundContainer == container && boundWindowId == windowId) {
                    passActive = false;
                }
            }
        });
    }

    /**
     * Opening the chest is the trigger the module is named for: the resources go in without being
     * asked for them. Scrolling still starts a pass, and still chooses the direction, but it only
     * has to be used to pull resources back out.
     *
     * Bound once per chest. The binding lives until the screen closes, so the automatic pass
     * cannot restart itself every tick the chest stays open.
     */
    private void beginAutoDeposit() {
        if (!isEnabled() || depositOnOpen == null || !depositOnOpen.isToggled() || autoDeposited) {
            return;
        }
        if (mc == null || mc.theWorld == null || mc.thePlayer == null || mc.thePlayer.inventory == null
                || mc.thePlayer.inventory.getItemStack() != null) {
            return;
        }

        ContainerChest container = supportedContainer(mc.currentScreen);
        if (container == null || mc.thePlayer.openContainer != container) {
            return;
        }

        boundScreen = (GuiChest) mc.currentScreen;
        boundPlayer = mc.thePlayer;
        boundContainer = container;
        boundWindowId = container.windowId;
        withdrawing = false;
        autoDeposited = true;
        passActive = true;
    }

    public void onManualInventoryInteraction() {
        if (boundScreen == null) {
            beginAutoDeposit();
            if (boundScreen == null) {
                return;
            }
        }
        if (!isBoundChestCurrent()) {
            clearState();
            return;
        }
        passActive = false;
    }

    public boolean shouldYieldChestAutomation() {
        if (!isEnabled() || boundScreen == null) {
            return false;
        }
        if (!isBoundChestCurrent()) {
            clearState();
            return false;
        }
        return true;
    }

    static boolean isResourceStack(ItemStack stack) {
        if (stack == null) {
            return false;
        }
        Item item = stack.getItem();
        return item == Items.gold_ingot
                || item == Items.iron_ingot
                || item == Items.diamond
                || item == Items.emerald;
    }

    static boolean isSupportedTitle(IInventory inventory) {
        if (inventory == null) {
            return false;
        }

        String name = inventory.getName();
        for (String key : CHEST_TITLE_KEYS) {
            if (matchesTitle(name, name, key)) {
                return true;
            }
        }
        String displayName = inventory.getDisplayName() == null
                ? ""
                : StringUtils.stripControlCodes(inventory.getDisplayName().getUnformattedText());
        for (String key : CHEST_TITLE_KEYS) {
            if (matchesTitle(displayName, displayName, key)) {
                return true;
            }
        }
        return HypixelLanguage.equals(name, HypixelLanguage.Key.ENDER_CHEST)
                || HypixelLanguage.equals(displayName, HypixelLanguage.Key.ENDER_CHEST);
    }

    static ContainerChest supportedContainer(GuiScreen screen) {
        if (!(screen instanceof GuiChest)) {
            return null;
        }

        GuiChest guiChest = (GuiChest) screen;
        if (!(guiChest.inventorySlots instanceof ContainerChest)) {
            return null;
        }

        ContainerChest container = (ContainerChest) guiChest.inventorySlots;
        IInventory storage = container.getLowerChestInventory();
        if (storage == null) {
            return null;
        }
        int size = storage.getSizeInventory();
        if (size != SINGLE_CHEST_SIZE && size != LARGE_CHEST_SIZE) {
            return null;
        }
        return isSupportedTitle(storage) ? container : null;
    }

    static boolean canEnterChest(ContainerChest container, ItemStack source) {
        if (container == null) {
            return false;
        }
        IInventory storage = container.getLowerChestInventory();
        return storage != null && canEnterInventory(container, storage, storage.getSizeInventory(), source);
    }

    private static boolean canEnterInventory(ContainerChest container, IInventory inventory,
                                             int size, ItemStack source) {
        if (source == null || source.stackSize <= 0) {
            return false;
        }
        for (int index = 0; index < size; index++) {
            Slot target = container.getSlotFromInventory(inventory, index);
            if (target == null || !target.isItemValid(source)) {
                continue;
            }

            ItemStack existing = target.getStack();
            if (existing == null) {
                if (Math.min(source.getMaxStackSize(), target.getItemStackLimit(source)) > 0) {
                    return true;
                }
                continue;
            }
            if (!ItemStack.areItemsEqual(source, existing)
                    || !ItemStack.areItemStackTagsEqual(source, existing)) {
                continue;
            }

            int capacity = Math.min(source.getMaxStackSize(), target.getItemStackLimit(source));
            if (existing.stackSize < capacity) {
                return true;
            }
        }
        return false;
    }

    static Slot findNextChestSourceSlot(ContainerChest container, InventoryPlayer inventory) {
        if (container == null || inventory == null) {
            return null;
        }
        IInventory storage = container.getLowerChestInventory();
        if (storage == null) {
            return null;
        }
        for (int index = 0; index < storage.getSizeInventory(); index++) {
            Slot slot = container.getSlotFromInventory(storage, index);
            if (slot == null) {
                continue;
            }
            ItemStack source = slot.getStack();
            if (isResourceStack(source) && canEnterInventory(container, inventory, 36, source)) {
                return slot;
            }
        }
        return null;
    }

    static Slot findNextSourceSlot(ContainerChest container, InventoryPlayer inventory) {
        if (container == null || inventory == null) {
            return null;
        }

        for (int section = 0; section < 2; section++) {
            int start = section == 0 ? InventoryPlayer.getHotbarSize() : 0;
            int end = section == 0 ? 36 : InventoryPlayer.getHotbarSize();
            for (int inventoryIndex = start; inventoryIndex < end; inventoryIndex++) {
                Slot slot = container.getSlotFromInventory(inventory, inventoryIndex);
                if (slot == null) {
                    continue;
                }

                ItemStack source = slot.getStack();
                if (isResourceStack(source) && canEnterChest(container, source)) {
                    return slot;
                }
            }
        }
        return null;
    }

    private static boolean matchesTitle(String name, String displayName, String key) {
        if (key.equals(name) || key.equals(displayName)) {
            return true;
        }
        try {
            String translated = I18n.format(key);
            return translated.equals(name) || translated.equals(displayName);
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isBoundChestCurrent() {
        return boundScreen != null
                && boundContainer != null
                && boundPlayer != null
                && mc != null
                && mc.theWorld != null
                && mc.currentScreen == boundScreen
                && mc.thePlayer == boundPlayer
                && mc.thePlayer.inventory != null
                && mc.thePlayer.openContainer == boundContainer
                && boundScreen.inventorySlots == boundContainer
                && boundContainer.windowId == boundWindowId;
    }

    private void clearState() {
        passActive = false;
        autoDeposited = false;
        boundScreen = null;
        boundPlayer = null;
        boundContainer = null;
        boundWindowId = -1;
    }
}
