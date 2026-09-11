package mindless.module.impl.bedwars;

import com.mojang.authlib.GameProfile;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.minigames.ShopHelper;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class InstantShopTest {
    private Minecraft previousMinecraft;
    private ShopHelper previousHelper;
    private Minecraft minecraft;
    private GuiChest gui;
    private Slot slot;
    private InstantShop module;
    private CapturingNetHandler handler;

    @Before
    public void setUp() throws Exception {
        if (!Bootstrap.isRegistered()) {
            field(Bootstrap.class, "alreadyRegistered").setBoolean(null, true);
            Block.registerBlocks();
            Item.registerItems();
        }
        previousMinecraft = Minecraft.getMinecraft();
        previousHelper = ModuleManager.shopHelper;
        minecraft = allocate(Minecraft.class);
        field(Minecraft.class, "theMinecraft").set(null, minecraft);
        field(Module.class, "mc").set(null, minecraft);
        EntityPlayerSP player = allocate(EntityPlayerSP.class);
        player.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
        player.inventory = new InventoryPlayer(player);
        InventoryBasic storage = new InventoryBasic("Quick Buy", true, 54);
        storage.setInventorySlotContents(10, new ItemStack(Items.stick));
        ContainerChest container = new ContainerChest(player.inventory, storage, player);
        container.windowId = 17;
        player.openContainer = container;
        gui = allocate(GuiChest.class);
        gui.inventorySlots = container;
        slot = container.getSlot(10);
        handler = new CapturingNetHandler();
        minecraft.thePlayer = player;
        minecraft.currentScreen = gui;
        minecraft.playerController = new PlayerControllerMP(minecraft, handler);
        ModuleManager.shopHelper = new ShopHelper();
        module = new InstantShop();
        module.setEnabled(true);
    }

    @After
    public void tearDown() throws Exception {
        ModuleManager.shopHelper = previousHelper;
        field(Minecraft.class, "theMinecraft").set(null, previousMinecraft);
        field(Module.class, "mc").set(null, previousMinecraft);
    }

    @Test
    public void consecutiveLeftClicksSendMiddleClickPacketsImmediately() {
        for (int i = 0; i < 3; i++) {
            assertTrue(module.tryPurchase(gui, slot, 10, 0, 0));
            assertEquals(i + 1, handler.packets.size());
            C0EPacketClickWindow packet = handler.packets.get(i);
            assertEquals(17, packet.getWindowId());
            assertEquals(10, packet.getSlotId());
            assertEquals(2, packet.getUsedButton());
            assertEquals(3, packet.getMode());
            assertEquals(i + 1, packet.getActionNumber());
            assertNull(minecraft.thePlayer.inventory.getItemStack());
            assertTrue(slot.getHasStack());
        }
    }

    @Test
    public void acceptsNativeMiddleClickMode() {
        assertTrue(module.tryPurchase(gui, slot, 10, 2, 3));
        assertEquals(1, handler.packets.size());
        assertEquals(3, handler.packets.get(0).getMode());
    }

    @Test
    public void leavesOtherInventoryActionsUntouched() {
        assertFalse(module.tryPurchase(gui, slot, 10, 1, 0));
        assertFalse(module.tryPurchase(gui, slot, 10, 0, 1));
        assertFalse(module.tryPurchase(gui, slot, 10, 0, 6));
        assertFalse(module.tryPurchase(gui, slot, 10, 0, 3));
        module.setEnabled(false);
        assertFalse(module.tryPurchase(gui, slot, 10, 0, 0));
        assertTrue(handler.packets.isEmpty());
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        return type.cast(unsafe.allocateInstance(type));
    }

    private static class CapturingNetHandler extends NetHandlerPlayClient {
        private final List<C0EPacketClickWindow> packets = new ArrayList<C0EPacketClickWindow>();

        private CapturingNetHandler() {
            super(null, null, null, new GameProfile(UUID.randomUUID(), "test"));
        }

        @Override
        public void addToSendQueue(Packet packet) {
            packets.add((C0EPacketClickWindow) packet);
        }
    }
}
