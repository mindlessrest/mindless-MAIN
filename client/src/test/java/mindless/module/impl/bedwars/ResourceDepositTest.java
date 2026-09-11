package mindless.module.impl.bedwars;

import com.google.common.util.concurrent.ListenableFuture;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.block.Block;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Item;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.server.S32PacketConfirmTransaction;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ResourceDepositTest {
    private static final Unsafe UNSAFE = unsafe();
    private static final Field MINECRAFT_SINGLETON = field(Minecraft.class, "theMinecraft");
    private static final Field MODULE_MINECRAFT = field(mindless.module.Module.class, "mc");
    private static final Field BOOTSTRAP_REGISTERED = field(Bootstrap.class, "alreadyRegistered");

    private Minecraft previousMinecraft;
    private TestMinecraft testMinecraft;

    @Before
    public void setUp() throws Exception {
        if (!Bootstrap.isRegistered()) {
            BOOTSTRAP_REGISTERED.setBoolean(null, true);
            Block.registerBlocks();
            Item.registerItems();
        }
        previousMinecraft = Minecraft.getMinecraft();
        testMinecraft = allocate(TestMinecraft.class);
        MINECRAFT_SINGLETON.set(null, testMinecraft);
    }

    @After
    public void tearDown() throws Exception {
        MINECRAFT_SINGLETON.set(null, previousMinecraft);
        MODULE_MINECRAFT.set(null, previousMinecraft);
    }

    @Test
    public void filtersResourcesAndRecognizesSupportedChestShapes() throws Exception {
        Assert.assertTrue(ResourceDeposit.isResourceStack(new ItemStack(Items.gold_ingot)));
        Assert.assertTrue(ResourceDeposit.isResourceStack(new ItemStack(Items.iron_ingot)));
        Assert.assertTrue(ResourceDeposit.isResourceStack(new ItemStack(Items.diamond)));
        Assert.assertTrue(ResourceDeposit.isResourceStack(new ItemStack(Items.emerald)));
        Assert.assertFalse(ResourceDeposit.isResourceStack(new ItemStack(Items.stick)));
        Assert.assertFalse(ResourceDeposit.isResourceStack(null));

        Assert.assertNotNull(ResourceDeposit.supportedContainer(fixture(27, "container.chest").gui));
        Assert.assertNotNull(ResourceDeposit.supportedContainer(fixture(54, "container.chestDouble").gui));
        Assert.assertNotNull(ResourceDeposit.supportedContainer(fixture(27, "container.enderchest").gui));
        Assert.assertNotNull(ResourceDeposit.supportedContainer(fixture(27, "Ender Chest").gui));
        Assert.assertNull(ResourceDeposit.supportedContainer(fixture(9, "container.chest").gui));
        Assert.assertNull(ResourceDeposit.supportedContainer(fixture(27, "Shop").gui));
    }

    @Test
    public void walksMainInventoryBeforeHotbar() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        fixture.player.inventory.mainInventory[9] = new ItemStack(Items.gold_ingot, 2);
        fixture.player.inventory.mainInventory[0] = new ItemStack(Items.iron_ingot, 2);

        Slot source = ResourceDeposit.findNextSourceSlot(fixture.container, fixture.player.inventory);
        Assert.assertNotNull(source);
        Assert.assertTrue(source.isHere(fixture.player.inventory, 9));

        fixture.player.inventory.mainInventory[9] = null;
        source = ResourceDeposit.findNextSourceSlot(fixture.container, fixture.player.inventory);
        Assert.assertNotNull(source);
        Assert.assertTrue(source.isHere(fixture.player.inventory, 0));
    }

    @Test
    public void checksTagsAndAllowsPartialButNotFullChestStacks() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        ItemStack source = new ItemStack(Items.gold_ingot, 4);
        ItemStack existing = new ItemStack(Items.gold_ingot, 63);
        source.setTagCompound(new net.minecraft.nbt.NBTTagCompound());
        source.getTagCompound().setString("kind", "resource");
        existing.setTagCompound((net.minecraft.nbt.NBTTagCompound) source.getTagCompound().copy());
        fixture.storage.setInventorySlotContents(0, existing);
        Assert.assertTrue(ResourceDeposit.canEnterChest(fixture.container, source));

        existing.stackSize = 64;
        for (int index = 1; index < fixture.storage.getSizeInventory(); index++) {
            fixture.storage.setInventorySlotContents(index, new ItemStack(Items.iron_ingot, 64));
        }
        Assert.assertFalse(ResourceDeposit.canEnterChest(fixture.container, source));

        ItemStack untagged = new ItemStack(Items.gold_ingot, 4);
        Assert.assertFalse(ResourceDeposit.canEnterChest(fixture.container, untagged));
    }

    @Test
    public void ignoresRepeatedScrollsAndAttemptsAtMostOneSourcePerEndTick() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        fixture.player.inventory.mainInventory[9] = new ItemStack(Items.gold_ingot, 4);
        fixture.player.inventory.mainInventory[10] = new ItemStack(Items.iron_ingot, 4);
        ResourceDeposit module = enabledModule();

        module.onMouseWheel(1);
        module.onMouseWheel(1);
        module.onMouseWheel(-1);
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        Assert.assertEquals(0, fixture.controller.clicks.size());

        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertEquals(1, fixture.controller.clicks.size());
        Assert.assertEquals(fixture.container.windowId, fixture.controller.clicks.get(0).windowId);
        Assert.assertEquals(27, fixture.controller.clicks.get(0).slotId);
        Assert.assertEquals(0, fixture.controller.clicks.get(0).button);
        Assert.assertEquals(1, fixture.controller.clicks.get(0).mode);

        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
        Assert.assertEquals(1, fixture.controller.clicks.size());
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertEquals(2, fixture.controller.clicks.size());
        Assert.assertEquals(28, fixture.controller.clicks.get(1).slotId);
    }

    @Test
    public void usesVanillaShiftClickAndCapturesOneModeOnePacket() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        fixture.storage.setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 63));
        fixture.player.inventory.mainInventory[9] = new ItemStack(Items.gold_ingot, 4);
        CapturingNetHandler handler = new CapturingNetHandler();
        PlayerControllerMP controller = new PlayerControllerMP(null, handler);
        Slot source = ResourceDeposit.findNextSourceSlot(fixture.container, fixture.player.inventory);

        controller.windowClick(fixture.container.windowId, source.slotNumber, 0, 1, fixture.player);

        Assert.assertEquals(1, handler.packets.size());
        Assert.assertEquals(64, fixture.storage.getStackInSlot(0).stackSize);
        Assert.assertEquals(3, fixture.storage.getStackInSlot(1).stackSize);
        Assert.assertNull(fixture.player.inventory.mainInventory[9]);
        C0EPacketClickWindow packet = (C0EPacketClickWindow) handler.packets.get(0);
        Assert.assertEquals(fixture.container.windowId, packet.getWindowId());
        Assert.assertEquals(source.slotNumber, packet.getSlotId());
        Assert.assertEquals(0, packet.getUsedButton());
        Assert.assertEquals(1, packet.getMode());
        Assert.assertEquals(1, packet.getActionNumber());
    }

    @Test
    public void cancelsForCursorManualCloseDisableAndRejectedTransaction() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        fixture.player.inventory.mainInventory[9] = new ItemStack(Items.gold_ingot, 4);
        ResourceDeposit module = enabledModule();

        module.onMouseWheel(1);
        fixture.player.inventory.setItemStack(new ItemStack(Items.stick));
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertFalse(passActive(module));
        fixture.player.inventory.setItemStack(null);

        module.onMouseWheel(1);
        module.onManualInventoryInteraction();
        Assert.assertFalse(passActive(module));
        Assert.assertTrue(module.shouldYieldChestAutomation());

        module.onMouseWheel(1);
        module.onReceivePacket(new mindless.event.ReceivePacketEvent(
                new S32PacketConfirmTransaction(fixture.container.windowId, (short) 1, true)));
        Assert.assertTrue(passActive(module));
        module.onReceivePacket(new mindless.event.ReceivePacketEvent(
                new S32PacketConfirmTransaction(fixture.container.windowId + 1, (short) 2, false)));
        Assert.assertTrue(passActive(module));
        module.onReceivePacket(new mindless.event.ReceivePacketEvent(
                new S32PacketConfirmTransaction(fixture.container.windowId, (short) 3, false)));
        Assert.assertFalse(passActive(module));
        Assert.assertEquals(1, testMinecraft.scheduledTasks);

        module.onMouseWheel(1);
        module.onGuiOpen(new GuiOpenEvent(null));
        Assert.assertFalse(module.shouldYieldChestAutomation());

        module.onMouseWheel(1);
        module.onDisable();
        Assert.assertFalse(passActive(module));
    }

    @Test
    public void scrollDownWithdrawsOnlyResourcesOneStackPerTick() throws Exception {
        for (int size : new int[]{27, 54}) {
            Fixture fixture = fixture(size, size == 27 ? "container.enderchest" : "container.chestDouble");
            fixture.storage.setInventorySlotContents(0, new ItemStack(Items.stick, 3));
            fixture.storage.setInventorySlotContents(1, new ItemStack(Items.gold_ingot, 4));
            fixture.storage.setInventorySlotContents(size - 1, new ItemStack(Items.emerald, 2));
            ResourceDeposit module = enabledModule();
            module.onMouseWheel(0);
            Assert.assertFalse(passActive(module));
            module.onMouseWheel(-1);
            module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.START));
            Assert.assertEquals(0, fixture.controller.clicks.size());
            module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            Assert.assertEquals(1, fixture.controller.clicks.size());
            Assert.assertEquals(1, fixture.controller.clicks.get(0).slotId);
            Assert.assertNull(fixture.storage.getStackInSlot(1));
            Assert.assertEquals(4, fixture.player.inventory.mainInventory[8].stackSize);
            module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            Assert.assertEquals(2, fixture.controller.clicks.size());
            Assert.assertNull(fixture.storage.getStackInSlot(size - 1));
            Assert.assertNotNull(fixture.storage.getStackInSlot(0));
            module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            Assert.assertFalse(passActive(module));
            module.onMouseWheel(1);
            module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
            Assert.assertEquals(3, fixture.controller.clicks.size());
            Assert.assertTrue(fixture.controller.clicks.get(2).slotId >= size);
        }
    }

    @Test
    public void withdrawalSkipsResourcesThatDoNotFitAndStopsWhenInventoryIsFull() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        for (int index = 0; index < 36; index++) {
            fixture.player.inventory.mainInventory[index] = new ItemStack(Items.stick, 64);
        }
        fixture.player.inventory.mainInventory[0] = new ItemStack(Items.gold_ingot, 63);
        fixture.storage.setInventorySlotContents(0, new ItemStack(Items.emerald, 2));
        fixture.storage.setInventorySlotContents(1, new ItemStack(Items.gold_ingot, 4));
        ResourceDeposit module = enabledModule();
        module.onMouseWheel(-1);
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertEquals(1, fixture.controller.clicks.size());
        Assert.assertEquals(1, fixture.controller.clicks.get(0).slotId);
        Assert.assertEquals(64, fixture.player.inventory.mainInventory[0].stackSize);
        Assert.assertEquals(3, fixture.storage.getStackInSlot(1).stackSize);
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertEquals(1, fixture.controller.clicks.size());
        Assert.assertFalse(passActive(module));
    }

    @Test
    public void withdrawalCancelsOnManualInteractionAndRejectedTransaction() throws Exception {
        Fixture fixture = fixture(27, "container.chest");
        fixture.storage.setInventorySlotContents(0, new ItemStack(Items.diamond, 4));
        ResourceDeposit module = enabledModule();
        module.onMouseWheel(-1);
        module.onManualInventoryInteraction();
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertEquals(0, fixture.controller.clicks.size());
        module.onMouseWheel(-1);
        module.onReceivePacket(new mindless.event.ReceivePacketEvent(
                new S32PacketConfirmTransaction(fixture.container.windowId, (short) 1, false)));
        module.onTick(new TickEvent.ClientTickEvent(TickEvent.Phase.END));
        Assert.assertFalse(passActive(module));
        Assert.assertEquals(0, fixture.controller.clicks.size());
    }

    private ResourceDeposit enabledModule() {
        ResourceDeposit module = new ResourceDeposit();
        module.setEnabled(true);
        return module;
    }

    private Fixture fixture(int size, String title) throws Exception {
        EntityPlayerSP player = allocate(EntityPlayerSP.class);
        player.inventory = new InventoryPlayer(player);
        InventoryBasic storage = new InventoryBasic(title, true, size);
        ContainerChest container = new ContainerChest(player.inventory, storage, player);
        container.windowId = 17;
        player.openContainer = container;

        GuiChest gui = allocate(GuiChest.class);
        gui.inventorySlots = container;
        TestController controller = new TestController();
        testMinecraft.thePlayer = player;
        testMinecraft.theWorld = allocate(WorldClient.class);
        testMinecraft.currentScreen = gui;
        testMinecraft.playerController = controller;
        return new Fixture(player, storage, container, gui, controller);
    }

    private static boolean passActive(ResourceDeposit module) throws Exception {
        Field field = ResourceDeposit.class.getDeclaredField("passActive");
        field.setAccessible(true);
        return field.getBoolean(module);
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        }
        catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static Field field(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws InstantiationException {
        return (T) UNSAFE.allocateInstance(type);
    }

    private static class Fixture {
        private final EntityPlayerSP player;
        private final InventoryBasic storage;
        private final ContainerChest container;
        private final GuiChest gui;
        private final TestController controller;

        private Fixture(EntityPlayerSP player, InventoryBasic storage, ContainerChest container,
                        GuiChest gui, TestController controller) {
            this.player = player;
            this.storage = storage;
            this.container = container;
            this.gui = gui;
            this.controller = controller;
        }
    }

    private static class Click {
        private final int windowId;
        private final int slotId;
        private final int button;
        private final int mode;

        private Click(int windowId, int slotId, int button, int mode) {
            this.windowId = windowId;
            this.slotId = slotId;
            this.button = button;
            this.mode = mode;
        }
    }

    private static class TestController extends PlayerControllerMP {
        private final List<Click> clicks = new ArrayList<Click>();

        private TestController() {
            super(null, null);
        }

        @Override
        public ItemStack windowClick(int windowId, int slotId, int button, int mode,
                                     net.minecraft.entity.player.EntityPlayer player) {
            clicks.add(new Click(windowId, slotId, button, mode));
            return player.openContainer.slotClick(slotId, button, mode, player);
        }
    }

    private static class TestMinecraft extends Minecraft {
        private int scheduledTasks;

        private TestMinecraft() {
            super(null);
        }

        @Override
        public ListenableFuture<Object> addScheduledTask(Runnable runnable) {
            scheduledTasks++;
            runnable.run();
            return null;
        }
    }

    private static class CapturingNetHandler extends NetHandlerPlayClient {
        private final List<Packet<?>> packets = new ArrayList<Packet<?>>();

        private CapturingNetHandler() {
            super(null, null, null, new GameProfile(UUID.randomUUID(), "test"));
        }

        @Override
        public void addToSendQueue(Packet packet) {
            packets.add(packet);
        }
    }
}
