package mindless.module.impl.player;

import com.mojang.authlib.GameProfile;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mindless.event.ClientRotationEvent;
import mindless.event.PreMotionEvent;
import mindless.event.PostMotionEvent;
import mindless.event.PreUpdateEvent;
import mindless.module.impl.bedwars.ResourceDepositTest;
import mindless.module.ModuleManager;
import mindless.placement.PlacementRuntime;
import net.minecraft.client.settings.KeyBinding;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.rotation.RotationSource;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import mindless.utility.IMinecraftInstance;
import mindless.utility.Utils;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraft.util.MovingObjectPosition;
import org.junit.*;
import sun.misc.Unsafe;
import static org.junit.Assert.*;

public class PlacementModuleRegressionTest {
    private static ResourceDepositTest fixture;
    private static Minecraft mc;
    private static Object previousUtilityMinecraft;
    private static Unsafe unsafe;
    private TestWorld world;
    private RecordingNetwork network;

    @BeforeClass public static void initialize() throws Exception {
        unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        fixture = new ResourceDepositTest();
        fixture.setUp();
        mc = Minecraft.getMinecraft();
        Field utilityMinecraft = IMinecraftInstance.class.getField("mc");
        previousUtilityMinecraft = utilityMinecraft.get(null);
        unsafe.putObject(unsafe.staticFieldBase(utilityMinecraft), unsafe.staticFieldOffset(utilityMinecraft), mc);
        mc.gameSettings = new GameSettings(mc, new File("build/tmp/placement-regression"));
    }

    @AfterClass public static void finish() throws Exception {
        Field utilityMinecraft = IMinecraftInstance.class.getField("mc");
        unsafe.putObject(unsafe.staticFieldBase(utilityMinecraft), unsafe.staticFieldOffset(utilityMinecraft), previousUtilityMinecraft);
        fixture.tearDown();
    }

    @Before public void reset() throws Exception {
        PlacementCoordinator.get().clear();
        CombatPacketState.resetSession();
        mindless.runtime.SentPlayerState.reset();
        Utils.advanceBaseClientTick();
        mc.thePlayer = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
        mc.thePlayer.inventory = new InventoryPlayer(mc.thePlayer);
        field(net.minecraft.entity.player.EntityPlayer.class, "capabilities").set(mc.thePlayer, new PlayerCapabilities());
        mc.thePlayer.fallDistance = 4;
        mc.thePlayer.posY = 3;
        network = new RecordingNetwork();
        field(EntityPlayerSP.class, "sendQueue").set(mc.thePlayer, network);
        mc.playerController = new PlayerControllerMP(mc, network);
        world = (TestWorld) unsafe.allocateInstance(TestWorld.class);
        world.blocks = new HashMap<>();
        field(net.minecraft.world.World.class, "loadedEntityList").set(world, new ArrayList<>());
        mc.theWorld = world;
        mc.currentScreen = null;
        world.hit = new MovingObjectPosition(new Vec3(0.5, 1, 0.5), EnumFacing.UP, BlockPos.ORIGIN);
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Items.ender_pearl);
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 0);
    }

    @After public void clear() { PlacementCoordinator.get().clear(); }

    @Test public void unrelatedHeldItemCannotBeUsedAsWaterOrEmptyBucket() throws Exception {
        WaterBucket module = new WaterBucket();
        set(module, "bucketSlot", 0);
        Method use = method(WaterBucket.class, "useCurrentItem", Item.class);
        assertFalse((Boolean) use.invoke(module, Items.water_bucket));
        assertFalse((Boolean) use.invoke(module, Items.bucket));
        assertTrue(network.packets.isEmpty());
    }

    @Test public void missingBucketAndDisabledSwitchLeaveFallUntouched() throws Exception {
        WaterBucket module = new WaterBucket();
        module.onClientRotation(new ClientRotationEvent());
        assertEquals("IDLE", get(module, "stage").toString());
        mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Items.water_bucket);
        module.switchToItem.setEnabled(false);
        module.onClientRotation(new ClientRotationEvent());
        assertEquals("IDLE", get(module, "stage").toString());
        assertEquals(0, mc.thePlayer.inventory.currentItem);
        assertTrue(network.packets.isEmpty());
    }

    @Test public void confirmedSingleBucketPickupKeepsAimAndRestoresSlot() throws Exception {
        WaterBucket module = startWater();
        commit(module, RotationSource.WATER_BUCKET);
        assertEquals("WAIT_WATER", get(module, "stage").toString());
        assertEquals(1, usePackets());
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals("WAIT_WATER", get(module, "stage").toString());
        confirmWater(module);
        ClientRotationEvent rotation = new ClientRotationEvent();
        module.onClientRotation(rotation);
        assertEquals(RotationSource.WATER_BUCKET, rotation.getPitchSource());
        assertEquals("PICKUP", get(module, "stage").toString());
        Utils.advanceBaseClientTick();
        PlacementCoordinator.get().beginTick(mc.thePlayer, mc.theWorld, Utils.getBaseClientTick());
        world.hit = new MovingObjectPosition(new Vec3(0.5, 1.5, 0.5), EnumFacing.UP, BlockPos.ORIGIN.up());
        commit(module, RotationSource.WATER_BUCKET);
        assertEquals("WAIT_PICKUP", get(module, "stage").toString());
        assertEquals(2, usePackets());
        mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Items.water_bucket);
        world.blocks.clear();
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals("IDLE", get(module, "stage").toString());
        assertEquals(0, mc.thePlayer.inventory.currentItem);
    }

    @Test public void unconfirmedPlacementTimesOutAndRestoresSlot() throws Exception {
        WaterBucket module = startWater();
        commit(module, RotationSource.WATER_BUCKET);
        mc.thePlayer.ticksExisted = 41;
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals("IDLE", get(module, "stage").toString());
        assertEquals(0, mc.thePlayer.inventory.currentItem);
        assertEquals(1, usePackets());
    }

    @Test public void noPickupAndDisableBothRestoreOriginalSlot() throws Exception {
        WaterBucket module = startWater();
        module.pickupWater.setEnabled(false);
        commit(module, RotationSource.WATER_BUCKET);
        confirmWater(module);
        assertEquals(0, mc.thePlayer.inventory.currentItem);
        module = startWater();
        module.onDisable();
        assertEquals(0, mc.thePlayer.inventory.currentItem);
    }

    @Test public void laterRotationWinnerPreventsWaterUse() throws Exception {
        WaterBucket module = startWater();
        commit(module, RotationSource.LONG_JUMP);
        assertEquals(0, usePackets());
        assertEquals(0, mc.thePlayer.inventory.currentItem);
    }

    @Test public void defaultAutoBlockinActivationIsOptionalAndCreativeCancelsQueue() throws Exception {
        AutoBlockin module = new AutoBlockin();
        assertTrue((Boolean) method(AutoBlockin.class, "isActivationPressed").invoke(module));
        method(AutoBlockin.class, "enablePlacing").invoke(module);
        set(module, "placeQueued", true);
        PlacementLease lease = (PlacementLease) get(module, "placementLease");
        mc.thePlayer.capabilities.isCreativeMode = true;
        module.onClientRotation(new ClientRotationEvent());
        assertFalse((Boolean) get(module, "placing"));
        assertFalse((Boolean) get(module, "placeQueued"));
        assertFalse(lease.isActive());
    }

    @Test public void creativeAtCommitAlsoCancelsAutoBlockinQueue() throws Exception {
        AutoBlockin module = new AutoBlockin();
        method(AutoBlockin.class, "enablePlacing").invoke(module);
        set(module, "placeQueued", true);
        mc.thePlayer.capabilities.isCreativeMode = true;
        module.onPreUpdate(new PreUpdateEvent());
        assertFalse((Boolean) get(module, "placing"));
        assertFalse((Boolean) get(module, "placeQueued"));
    }

    @Test public void autoBlockinPlacesBeforeMovementWithoutLateSlotRestoration() throws Exception {
        AutoBlockin module = new AutoBlockin();
        world.blocks.put(BlockPos.ORIGIN, Blocks.stone.getDefaultState());
        mindless.runtime.SentPlayerState.record(new net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook(0.5, 3, 0.5, 0, 90, false));
        mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Blocks.stone);
        method(AutoBlockin.class, "enablePlacing").invoke(module);
        set(module, "plannedSlot", 1);
        method(AutoBlockin.class, "equipPlannedSlot").invoke(module);
        set(module, "hitAt", BlockPos.ORIGIN);
        set(module, "hitSide", EnumFacing.UP);
        set(module, "placeAt", new Vec3(0.5, 1, 0.5));
        set(module, "placeQueued", true);
        int[] attempts = {0};
        mc.playerController = new PlayerControllerMP(mc, network) {
            @Override public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient world,
                    ItemStack stack, BlockPos pos, EnumFacing side, Vec3 hit) {
                attempts[0]++;
                player.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(pos, side.getIndex(), stack, .5F, 1F, .5F));
                return true;
            }
        };
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 0);
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals(0, attempts[0]);
        assertEquals(1, network.packets.size());
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 1);
        set(module, "placeQueued", true);
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals(1, attempts[0]);
        assertFalse((Boolean) get(module, "placeQueued"));
        assertEquals(1, mc.thePlayer.inventory.currentItem);
        assertEquals(2, network.packets.size());
        assertTrue(network.packets.get(0) instanceof net.minecraft.network.play.client.C09PacketHeldItemChange);
        assertTrue(network.packets.get(1) instanceof C08PacketPlayerBlockPlacement);
        for (Method handler : AutoBlockin.class.getDeclaredMethods()) {
            for (Class<?> parameter : handler.getParameterTypes()) assertNotEquals(PostMotionEvent.class, parameter);
        }
    }

    @Test public void autoBlockinWaitsForSentLookAndRejectsChangedSupport() throws Exception {
        AutoBlockin module = new AutoBlockin();
        field(net.minecraft.entity.player.EntityPlayer.class, "eyeHeight").setFloat(mc.thePlayer, 1.62F);
        BlockPos support = new BlockPos(0, 4, 2);
        set(module, "hitAt", support);
        set(module, "hitSide", EnumFacing.NORTH);
        world.blocks.put(support, Blocks.stone.getDefaultState());
        world.traceGeometry = true;
        Method validate = method(AutoBlockin.class, "validatedPlacementHit");
        assertNull(validate.invoke(module));
        mindless.runtime.SentPlayerState.record(new net.minecraft.network.play.client.C03PacketPlayer.C06PacketPlayerPosLook(.5, 3, .5, 90, 0, false));
        mc.thePlayer.rotationYaw = 0;
        assertNull("local high-speed turn has not been sent", validate.invoke(module));
        mindless.runtime.SentPlayerState.record(new net.minecraft.network.play.client.C03PacketPlayer.C05PacketPlayerLook(0, 0, false));
        MovingObjectPosition hit = (MovingObjectPosition) validate.invoke(module);
        assertNotNull(hit);
        assertEquals(support, hit.getBlockPos());
        assertEquals(EnumFacing.NORTH, hit.sideHit);
        world.blocks.put(support, Blocks.air.getDefaultState());
        assertNull(validate.invoke(module));
        world.blocks.put(support, Blocks.water.getDefaultState());
        assertNull(validate.invoke(module));
        world.blocks.put(support, Blocks.stone.getDefaultState());
        world.blocks.put(support.north(), Blocks.stone.getDefaultState());
        assertNull("occupied destination must not be placed again", validate.invoke(module));
    }

    @Test public void clutchResetReleasesOwnedWorkAndGuiClearsLinger() throws Exception {
        Clutch module = new Clutch();
        set(module, "placing", true);
        set(module, "placeQueued", true);
        set(module, "autoClickerWasOn", true);
        module.onEnable();
        assertFalse((Boolean) get(module, "placing"));
        assertFalse((Boolean) get(module, "placeQueued"));
        assertFalse((Boolean) get(module, "autoClickerWasOn"));
        set(module, "hasAim", true);
        set(module, "aimLingerTicks", 5);
        set(module, "placeQueued", true);
        mc.currentScreen = new GuiScreen() { };
        module.onClientRotation(new ClientRotationEvent());
        assertFalse((Boolean) get(module, "hasAim"));
        assertFalse((Boolean) get(module, "placeQueued"));
        assertEquals(0, get(module, "aimLingerTicks"));
    }

    @Test public void clutchCommitsQueuedPlacementDuringPreUpdate() throws Exception {
        Clutch module = new Clutch();
        module.onEnable();
        method(Clutch.class, "enablePlacing").invoke(module);
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        set(module, "placeAtBlock", BlockPos.ORIGIN);
        set(module, "hitSide", EnumFacing.UP);
        set(module, "hitVec", new Vec3(0.5, 1, 0.5));
        set(module, "placeQueued", true);
        int[] attempts = {0};
        mc.playerController = new PlayerControllerMP(mc, network) {
            @Override public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient world,
                    ItemStack stack, BlockPos pos, EnumFacing side, Vec3 hit) {
                attempts[0]++;
                return false;
            }
        };
        module.onPreUpdate(new PreUpdateEvent());
        assertEquals(1, attempts[0]);
        assertFalse((Boolean) get(module, "placeQueued"));
        for (Method handler : Clutch.class.getDeclaredMethods()) {
            for (Class<?> parameter : handler.getParameterTypes()) assertNotEquals(PostMotionEvent.class, parameter);
        }
    }

    @Test public void topFaceRescueCountsForLandingButNotBridgeDistance() throws Exception {
        Clutch module = new Clutch();
        world.blocks.put(new BlockPos(0, 2, 0), Blocks.stone.getDefaultState());
        method(Clutch.class, "onBlockPlaced", BlockPos.class, EnumFacing.class, float.class)
                .invoke(module, new BlockPos(0, 1, 0), EnumFacing.UP, 2.0F);
        assertTrue((Boolean) get(module, "placedDuringRescue"));
        assertEquals(0, get(module, "clutchBlocksPlaced"));
        mc.thePlayer.onGround = true;
        set(module, "safeLandingTicks", 2);
        method(Clutch.class, "updateDamageAndLandingState").invoke(module);
        assertTrue((Long) get(module, "cooldownUntil") > System.currentTimeMillis());
        assertTrue((Integer) get(module, "antiSlipTicks") > 0);
    }

    @Test public void headHitterAutoJumpOffDoesNotClaimOrTickJump() throws Exception {
        AutoHeadHitter module = new AutoHeadHitter();
        module.setAutoJumpEnabled(false);
        PlacementLease lease = PlacementCoordinator.get().acquire(module,
                PlacementCoordinator.Priority.HEAD_HITTER, mc.thePlayer, mc.theWorld);
        set(module, "placementLease", lease);
        Map<KeyBinding, PlacementLease.InputState> inputs = (Map<KeyBinding, PlacementLease.InputState>)
                field(PlacementRuntime.class, "INPUTS").get(null);
        FakeInput fake = new FakeInput();
        PlacementLease.InputState previous = inputs.put(mc.gameSettings.keyBindJump, fake);
        try {
            method(AutoHeadHitter.class, "tickJumpKey").invoke(module);
            assertEquals(0, fake.writes);
        } finally {
            lease.release();
            if (previous == null) inputs.remove(mc.gameSettings.keyBindJump);
            else inputs.put(mc.gameSettings.keyBindJump, previous);
        }
    }

    @Test public void bedInteractionTakeoverRevokesPlacementQueues() throws Exception {
        BedAura previous = ModuleManager.bedAura;
        try {
            ModuleManager.bedAura = (BlockingBedAura) unsafe.allocateInstance(BlockingBedAura.class);
            Object[] modules = {new Clutch(), new AutoBlockin()};
            for (Object module : modules) {
                method(module.getClass(), "enablePlacing").invoke(module);
                set(module, "placeQueued", true);
                PlacementLease lease = (PlacementLease) get(module, "placementLease");
                method(module.getClass(), "onClientRotation", ClientRotationEvent.class)
                        .invoke(module, new ClientRotationEvent());
                assertFalse((Boolean) get(module, "placing"));
                assertFalse((Boolean) get(module, "placeQueued"));
                assertFalse(lease.isActive());
            }
        } finally {
            ModuleManager.bedAura = previous;
        }
    }

    @Test public void playerReplacementCannotRestorePreviousPlayersSlot() throws Exception {
        Clutch module = new Clutch();
        module.onEnable();
        method(Clutch.class, "enablePlacing").invoke(module);
        PlacementLease lease = (PlacementLease) get(module, "placementLease");
        lease.claimHotbar(PlacementRuntime.hotbar(), 1);
        set(module, "placeQueued", true);
        TestPlayer nextPlayer = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
        nextPlayer.inventory = new InventoryPlayer(nextPlayer);
        nextPlayer.inventory.currentItem = 3;
        mc.thePlayer = nextPlayer;
        module.onEnable();
        assertEquals(3, nextPlayer.inventory.currentItem);
        assertFalse(lease.isActive());
        assertFalse((Boolean) get(module, "placeQueued"));
    }

    @Test public void scaffoldRetainsPreRepairInputOwnership() throws Exception {
        Scaffold module = new Scaffold();
        field(mindless.module.Module.class, "enabled").setBoolean(module, true);
        module.onEnable();
        mindless.event.RightClickMouseEvent click = new mindless.event.RightClickMouseEvent();
        mindless.event.SlotUpdateEvent slot = new mindless.event.SlotUpdateEvent(2);
        module.onRightClick(click);
        module.onSlotUpdate(slot);
        assertTrue(click.isCanceled());
        assertTrue(slot.isCanceled());
        assertEquals(2, get(module, "previousHotbarSlot"));
    }

    @Test public void scaffoldDisableLeavesSlotPacketsToVanillaSynchronization() throws Exception {
        for (boolean spoof : new boolean[]{true, false}) {
            mc.thePlayer.inventory.currentItem = 0;
            AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 0);
            Scaffold module = new Scaffold();
            field(mindless.module.Module.class, "enabled").setBoolean(module, true);
            ((mindless.module.setting.impl.ButtonSetting) get(module, "itemSpoof")).setEnabled(spoof);
            module.onEnable();
            PlacementLease lease = PlacementCoordinator.get().acquire(module,
                    PlacementCoordinator.Priority.SCAFFOLD, mc.thePlayer, mc.theWorld);
            assertTrue(lease.claimHotbar(PlacementRuntime.hotbar(), 1));
            set(module, "placementLease", lease);
            module.onSlotUpdate(new mindless.event.SlotUpdateEvent(2));
            network.packets.clear();
            GameSettings settings = mc.gameSettings;
            try {
                mc.gameSettings = null;
                module.onDisable();
            } finally {
                mc.gameSettings = settings;
            }
            assertFalse(lease.isActive());
            assertNull(get(module, "placementLease"));
            assertEquals(spoof ? 2 : 1, mc.thePlayer.inventory.currentItem);
            assertEquals(1, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
            assertTrue("disable must not send a held-item packet", network.packets.isEmpty());
            AccessorBridge.PlayerControllerMP_callSyncCurrentPlayItem(mc.playerController);
            assertEquals(spoof ? 1 : 0, network.packets.size());
            if (spoof) assertEquals(2, ((net.minecraft.network.play.client.C09PacketHeldItemChange)
                    network.packets.get(0)).getSlotId());
        }
    }

    @Test public void antiSlipReleasePreservesOnlyCurrentPhysicalSneak() throws Exception {
        Clutch module = new Clutch();
        Map<KeyBinding, PlacementLease.InputState> inputs = (Map<KeyBinding, PlacementLease.InputState>)
                field(PlacementRuntime.class, "INPUTS").get(null);
        FakeInput fake = new FakeInput();
        PlacementLease.InputState previous = inputs.put(mc.gameSettings.keyBindSneak, fake);
        Method sneak = method(Clutch.class, "setAntiSlipSneaking", boolean.class);
        try {
            sneak.invoke(module, true);
            fake.physical = true;
            sneak.invoke(module, false);
            assertTrue(fake.pressed);
            sneak.invoke(module, true);
            fake.physical = false;
            sneak.invoke(module, false);
            assertFalse(fake.pressed);
        } finally {
            if (previous == null) inputs.remove(mc.gameSettings.keyBindSneak);
            else inputs.put(mc.gameSettings.keyBindSneak, previous);
        }
    }

    @Test public void rejectedRestoreSurvivesDisableUntilNextAcceptedRetry() {
        WaterBucket module = startWater();
        network.accept = false;
        module.onDisable();
        assertEquals(0, mc.thePlayer.inventory.currentItem);
        assertEquals(1, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
        assertFalse(PlacementRuntime.isHotbarSynchronized());
        network.accept = true;
        Utils.advanceBaseClientTick();
        PlacementRuntime.retryHotbarSync();
        assertTrue(PlacementRuntime.isHotbarSynchronized());
        assertEquals(0, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
    }

    @Test public void pendingSwitchCannotUseEvenIfVanillaControllerOptimisticallyChangesSlot() {
        network.accept = false;
        WaterBucket module = startWater();
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 1);
        commit(module, RotationSource.WATER_BUCKET);
        assertEquals(0, usePackets());
        module.onDisable();
    }

    @Test public void repeatedSameSlotClaimsKeepRetryBudgetAndFailClosed() {
        network.accept = false;
        PlacementLease.HotbarState hotbar = PlacementRuntime.hotbar();
        for (int tick = 0; tick < 25; tick++) {
            Utils.advanceBaseClientTick();
            hotbar.setSelectedSlot(1);
            hotbar.setSelectedSlot(1);
            PlacementRuntime.retryHotbarSync();
        }
        assertEquals(20, network.packets.size());
        assertFalse(PlacementRuntime.isHotbarSynchronized());
        mc.thePlayer.inventory.currentItem = 2;
        PlacementRuntime.retryHotbarSync();
        assertTrue(PlacementRuntime.isHotbarSynchronized());
        assertEquals(20, network.packets.size());
    }

    @Test public void newSlotAndNewPlayerInvalidateRejectedRestoration() throws Exception {
        WaterBucket module = startWater();
        network.accept = false;
        module.onDisable();
        network.accept = true;
        PlacementRuntime.hotbar().setSelectedSlot(2);
        Utils.advanceBaseClientTick();
        PlacementRuntime.retryHotbarSync();
        assertEquals(2, mc.thePlayer.inventory.currentItem);
        assertEquals(2, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
        assertTrue(PlacementRuntime.isHotbarSynchronized());
        network.accept = false;
        PlacementRuntime.hotbar().setSelectedSlot(1);
        int attempts = network.packets.size();
        TestPlayer next = (TestPlayer) unsafe.allocateInstance(TestPlayer.class);
        next.inventory = new InventoryPlayer(next);
        next.inventory.currentItem = 3;
        mc.thePlayer = next;
        Utils.advanceBaseClientTick();
        PlacementRuntime.retryHotbarSync();
        assertEquals(attempts, network.packets.size());
        assertEquals(3, next.inventory.currentItem);
        assertTrue(PlacementRuntime.isHotbarSynchronized());
    }

    @Test public void acceptedOrdinarySlotSyncResolvesAnExhaustedRestore() {
        network.accept = false;
        PlacementRuntime.hotbar().setSelectedSlot(1);
        for (int tick = 0; tick < 25; tick++) {
            Utils.advanceBaseClientTick();
            PlacementRuntime.retryHotbarSync();
        }
        assertFalse(PlacementRuntime.isHotbarSynchronized());
        CombatPacketState.recordAccepted(new net.minecraft.network.play.client.C09PacketHeldItemChange(1));
        assertTrue(PlacementRuntime.isHotbarSynchronized());
    }

    private WaterBucket startWater() {
        mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Items.water_bucket);
        WaterBucket module = new WaterBucket();
        module.onClientRotation(new ClientRotationEvent());
        assertEquals(1, mc.thePlayer.inventory.currentItem);
        return module;
    }

    private void confirmWater(WaterBucket module) throws Exception {
        mc.thePlayer.inventory.mainInventory[1] = new ItemStack(Items.bucket);
        world.blocks.put(BlockPos.ORIGIN.up(), Blocks.water.getDefaultState());
        set(module, "lastPlace", System.currentTimeMillis() - 200L);
        module.onPreUpdate(new PreUpdateEvent());
    }

    private void commit(WaterBucket module, RotationSource source) {
        PreMotionEvent motion = new PreMotionEvent();
        module.onPreMotion(motion);
        motion.requestRotation(source, 0, 90);
        module.onPostMotion(new PostMotionEvent());
    }

    private int usePackets() {
        int count = 0;
        for (Packet<?> packet : network.packets) if (packet instanceof C08PacketPlayerBlockPlacement) count++;
        return count;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method method = type.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }
    private static Object get(Object owner, String name) throws Exception { return field(owner.getClass(), name).get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner.getClass(), name).set(owner, value); }

    private static class BlockingBedAura extends BedAura {
        @Override public boolean controlsInteractions() { return true; }
        @Override public boolean shouldYieldInteractionTo(RotationSource source) { return false; }
    }
    private static class FakeInput implements PlacementLease.InputState {
        private int writes;
        private boolean pressed;
        private boolean physical;
        @Override public boolean isPressed() { return pressed; }
        @Override public boolean isPhysicallyPressed() { return physical; }
        @Override public void setPressed(boolean value) { pressed = value; writes++; }
    }
    private static class TestPlayer extends EntityPlayerSP {
        private TestPlayer() { super(null, null, null, null); }
        @Override public void swingItem() { }
    }
    private static class TestWorld extends WorldClient {
        private Map<BlockPos, IBlockState> blocks;
        private MovingObjectPosition hit;
        private boolean traceGeometry;
        private TestWorld() { super(null, null, 0, null, null); }
        @Override public IBlockState getBlockState(BlockPos pos) {
            IBlockState state = blocks.get(pos);
            return state == null ? Blocks.air.getDefaultState() : state;
        }
        @Override public MovingObjectPosition rayTraceBlocks(Vec3 start, Vec3 end, boolean liquids, boolean ignore, boolean last) {
            if (!traceGeometry) return hit;
            MovingObjectPosition nearest = null;
            for (Map.Entry<BlockPos, IBlockState> entry : blocks.entrySet()) {
                MovingObjectPosition candidate = entry.getValue().getBlock().collisionRayTrace(this, entry.getKey(), start, end);
                if (candidate != null && (nearest == null || start.squareDistanceTo(candidate.hitVec) < start.squareDistanceTo(nearest.hitVec))) nearest = candidate;
            }
            return nearest;
        }
    }
    private static class RecordingNetwork extends NetHandlerPlayClient {
        private final List<Packet<?>> packets = new ArrayList<>();
        private boolean accept = true;
        private RecordingNetwork() { super(null, null, null, new GameProfile(UUID.randomUUID(), "placement-test")); }
        @Override public void addToSendQueue(Packet packet) {
            packets.add(packet);
            if (accept) CombatPacketState.recordAccepted(packet);
        }
    }
}
