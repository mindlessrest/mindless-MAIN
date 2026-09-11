package mindless.module.impl.player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mindless.module.impl.bedwars.ResourceDepositTest;
import mindless.module.setting.impl.ProfiledButtonSetting;
import mindless.module.setting.impl.ProfiledSliderSetting;
import mindless.rotation.RotationSource;
import mindless.helper.RotationHelper;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0APacketAnimation;
import com.mojang.authlib.GameProfile;
import java.util.UUID;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInput;
import org.junit.*;
import static org.junit.Assert.*;

public class SilentBedBreakerTest {
    private static ResourceDepositTest fixture;
    private static Minecraft mc;
    private static TestWorld world;
    private BedAura owner;
    private SilentBedBreaker silent;

    @BeforeClass public static void initialize() throws Exception {
        fixture = new ResourceDepositTest();
        fixture.setUp();
        Method make = ResourceDepositTest.class.getDeclaredMethod("fixture", int.class, String.class);
        make.setAccessible(true);
        make.invoke(fixture, 27, "container.chest");
        mc = Minecraft.getMinecraft();
        Method allocate = ResourceDepositTest.class.getDeclaredMethod("allocate", Class.class);
        allocate.setAccessible(true);
        mc.gameSettings = (net.minecraft.client.settings.GameSettings) allocate.invoke(null, net.minecraft.client.settings.GameSettings.class);
        mc.gameSettings.mouseSensitivity = 0.5F;
        world = (TestWorld) allocate.invoke(null, TestWorld.class);
        world.blocks = new HashMap<BlockPos, IBlockState>();
        mc.thePlayer = (TestPlayer) allocate.invoke(null, TestPlayer.class);
        mc.thePlayer.inventory = new net.minecraft.entity.player.InventoryPlayer(mc.thePlayer);
        mc.theWorld = world;
        mc.thePlayer.worldObj = world;
        mc.thePlayer.movementInput = new MovementInput();
        field(EntityLivingBase.class, "activePotionsMap").set(mc.thePlayer, new HashMap<Integer, net.minecraft.potion.PotionEffect>());
        mc.thePlayer.posX = mc.thePlayer.prevPosX = 0.0;
        mc.thePlayer.posY = mc.thePlayer.prevPosY = 64.0;
        mc.thePlayer.posZ = mc.thePlayer.prevPosZ = 0.5;
        mc.thePlayer.onGround = true;
        mc.thePlayer.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
    }

    @AfterClass public static void finish() throws Exception { fixture.tearDown(); }

    @Before public void reset() throws Exception {
        world.blocks.clear();
        world.bedAreaLoaded = false;
        mc.thePlayer.inventory = new net.minecraft.entity.player.InventoryPlayer(mc.thePlayer);
        mc.thePlayer.capabilities.allowEdit = true;
        mc.thePlayer.capabilities.isCreativeMode = false;
        mc.currentScreen = null;
        owner = new BedAura();
        silent = (SilentBedBreaker) field(BedAura.class, "silent").get(owner);
        ((ProfiledButtonSetting) field(BedAura.class, "silentWhitelist").get(owner)).setEnabled(false);
    }

    @Test public void defaultsMatchRequestedRangeAndSpeed() throws Exception {
        assertEquals(4.5, ((ProfiledSliderSetting) field(BedAura.class, "silentRange").get(owner)).getInput(), 0);
        assertEquals(33, ((ProfiledSliderSetting) field(BedAura.class, "silentSpeed").get(owner)).getInput(), 0);
    }

    @Test public void reachableHeadMustNotRequireReachableFoot() throws Exception {
        bed(new BlockPos(5, 65, 0), EnumFacing.WEST);
        assertEquals("Head is reachable at x=4, while foot at x=5 is not", "4,65,0", coordinates(findTarget()));
    }

    @Test public void silentReachNeverExceedsFourAndAHalfBlocks() throws Exception {
        bed(new BlockPos(6, 65, 0), EnumFacing.WEST);
        assertNull("The saved 5.5 setting is capped to the requested 4.5-block Silent reach", findTarget());
    }

    @Test public void widerDefenseMustSelectNearestBedAdjacentBlock() throws Exception {
        BlockPos foot = new BlockPos(4, 65, 0);
        bed(foot, EnumFacing.EAST);
        BlockPos head = foot.east();
        for (BlockPos half : new BlockPos[]{foot, head}) {
            for (EnumFacing face : new EnumFacing[]{EnumFacing.UP, EnumFacing.NORTH, EnumFacing.EAST, EnumFacing.SOUTH, EnumFacing.WEST}) {
                BlockPos pos = half.offset(face);
                if (!pos.equals(foot) && !pos.equals(head)) world.blocks.put(pos, Blocks.wool.getDefaultState());
            }
        }
        BlockPos outer = new BlockPos(2, 65, 0);
        world.blocks.put(outer, Blocks.wool.getDefaultState());
        assertEquals("Silent selects the bed-adjacent cover even behind an outer layer", coordinates(foot.west()), coordinates(findTarget()));
    }

    @Test public void cleanupMustDiscardCooldownFromPreviousSession() throws Exception {
        Field state = field(SilentBedBreaker.class, "state");
        for (Object candidate : state.getType().getEnumConstants()) {
            if (candidate.toString().equals("COOLDOWN")) state.set(silent, candidate);
        }
        silent.cleanup();
        assertEquals("Disable/world-change cleanup must end the old session", "IDLE", state.get(silent).toString());
    }

    @Test public void confirmedBedRemovalMustEnterSuccessCooldown() throws Exception {
        field(mindless.module.Module.class, "enabled").setBoolean(owner, true);
        mc.currentScreen = null;
        mc.thePlayer.capabilities.allowEdit = true;
        mc.thePlayer.capabilities.isCreativeMode = false;
        mc.currentScreen = null;
        field(SilentBedBreaker.class, "target").set(silent, new BlockPos(4, 65, 0));
        field(SilentBedBreaker.class, "targetIsBed").setBoolean(silent, true);
        field(SilentBedBreaker.class, "startSent").setBoolean(silent, true);
        Field state = field(SilentBedBreaker.class, "state");
        for (Object candidate : state.getType().getEnumConstants()) {
            if (candidate.toString().equals("FINISH")) state.set(silent, candidate);
        }
        silent.tick();
        assertTrue("Server-confirmed bed removal must schedule the 500 ms cooldown",
                field(SilentBedBreaker.class, "cooldownUntil").getLong(silent) > System.currentTimeMillis());
    }

    @Test public void hidingTargetMustNotHideProgressTextColor() throws Exception {
        ((ProfiledSliderSetting) field(BedAura.class, "silentShowTarget").get(owner)).setValueRaw(0);
        assertTrue(owner.shouldShowAuraProgress());
        assertNotEquals("Progress remains enabled and requires a visible color", 0, owner.getAuraProgressColor());
    }

    @Test public void preparationWaitsForThePreUpdateActionPhase() throws Exception {
        field(mindless.module.Module.class, "enabled").setBoolean(owner, true);
        bed(new BlockPos(4, 65, 0), EnumFacing.EAST);
        silent.prepareTick();
        assertEquals("START", field(SilentBedBreaker.class, "state").get(silent).toString());
        assertFalse(field(SilentBedBreaker.class, "startSent").getBoolean(silent));
        silent.actionTick();
        assertFalse("A START packet requires resolved Bed Aura rotation ownership", field(SilentBedBreaker.class, "startSent").getBoolean(silent));
    }

    @Test public void higherPriorityRotationPreventsSilentActionOwnership() throws Exception {
        field(SilentBedBreaker.class, "target").set(silent, new BlockPos(4, 65, 0));
        field(SilentBedBreaker.class, "interactionLocked").setBoolean(silent, true);
        assertTrue(owner.shouldYieldInteractionTo(RotationSource.CLUTCH));
        assertFalse(owner.shouldYieldInteractionTo(RotationSource.KILL_AURA));
        field(SilentBedBreaker.class, "rotationRequested").setBoolean(silent, true);
        field(SilentBedBreaker.class, "requiredYaw").setFloat(silent, 45.0F);
        field(SilentBedBreaker.class, "requiredPitch").setFloat(silent, 30.0F);
        RotationHelper helper = RotationHelper.get();
        field(RotationHelper.class, "serverYaw").set(helper, 45.0F);
        field(RotationHelper.class, "serverPitch").set(helper, 30.0F);
        field(RotationHelper.class, "serverYawSource").set(helper, RotationSource.CLUTCH);
        field(RotationHelper.class, "serverPitchSource").set(helper, RotationSource.CLUTCH);
        Method applicable = SilentBedBreaker.class.getDeclaredMethod("hasApplicableRotation");
        applicable.setAccessible(true);
        assertFalse((Boolean) applicable.invoke(silent));
        field(RotationHelper.class, "serverYawSource").set(helper, RotationSource.BED_AURA);
        field(RotationHelper.class, "serverPitchSource").set(helper, RotationSource.BED_AURA);
        assertTrue((Boolean) applicable.invoke(silent));
    }

    @Test public void switchingToLegitClearsTheSilentSession() throws Exception {
        field(mindless.module.Module.class, "enabled").setBoolean(owner, true);
        field(BedAura.class, "activeMode").setInt(owner, 0);
        field(SilentBedBreaker.class, "target").set(silent, new BlockPos(4, 65, 0));
        field(SilentBedBreaker.class, "interactionLocked").setBoolean(silent, true);
        ((mindless.module.setting.impl.SliderSetting) field(BedAura.class, "mode").get(owner)).setValueRaw(1);
        owner.onGameTick(new mindless.event.GameTickEvent());
        assertNull(field(SilentBedBreaker.class, "target").get(silent));
        assertEquals("IDLE", field(SilentBedBreaker.class, "state").get(silent).toString());
    }

    @Test public void synchronizedToolChangePrecedesSilentDiggingPackets() throws Exception {
        field(mindless.module.Module.class, "enabled").setBoolean(owner, true);
        mc.currentScreen = null;
        mc.thePlayer.capabilities.allowEdit = true;
        mc.thePlayer.capabilities.isCreativeMode = false;
        ((ProfiledButtonSetting) field(BedAura.class, "silentSwing").get(owner)).setEnabled(false);
        mc.thePlayer.inventory.currentItem = 0;
        mc.thePlayer.inventory.setInventorySlotContents(1, new ItemStack(Items.diamond_pickaxe));
        CapturingNetHandler handler = new CapturingNetHandler();
        field(net.minecraft.client.entity.EntityPlayerSP.class, "sendQueue").set(mc.thePlayer, handler);
        mc.playerController = new PlayerControllerMP(mc, handler);
        BlockPos stone = new BlockPos(4, 65, 0);
        world.blocks.put(stone, Blocks.stone.getDefaultState());
        field(SilentBedBreaker.class, "target").set(silent, stone);
        field(SilentBedBreaker.class, "interactionLocked").setBoolean(silent, true);
        Field state = field(SilentBedBreaker.class, "state");
        for (Object candidate : state.getType().getEnumConstants()) {
            if (candidate.toString().equals("START")) state.set(silent, candidate);
        }
        field(SilentBedBreaker.class, "rotationRequested").setBoolean(silent, true);
        field(SilentBedBreaker.class, "requiredYaw").setFloat(silent, 45.0F);
        field(SilentBedBreaker.class, "requiredPitch").setFloat(silent, 30.0F);
        RotationHelper helper = RotationHelper.get();
        field(RotationHelper.class, "serverYaw").set(helper, 45.0F);
        field(RotationHelper.class, "serverPitch").set(helper, 30.0F);
        field(RotationHelper.class, "serverYawSource").set(helper, RotationSource.BED_AURA);
        field(RotationHelper.class, "serverPitchSource").set(helper, RotationSource.BED_AURA);
        Method applicable = SilentBedBreaker.class.getDeclaredMethod("hasApplicableRotation");
        applicable.setAccessible(true);
        assertTrue((Boolean) applicable.invoke(silent));
        silent.actionTick();
        assertTrue(field(SilentBedBreaker.class, "startSent").getBoolean(silent));
        assertEquals(C09PacketHeldItemChange.class, handler.packets.get(0).getClass());
        assertEquals(C07PacketPlayerDigging.class, handler.packets.get(1).getClass());
        assertEquals(C0APacketAnimation.class, handler.packets.get(2).getClass());
    }

    private BlockPos findTarget() throws Exception {
        Method method = SilentBedBreaker.class.getDeclaredMethod("findTarget");
        method.setAccessible(true);
        return (BlockPos) method.invoke(silent);
    }

    @Test public void openingOneAdjacentBlockTargetsBedBehindRemainingOuterDefense() throws Exception {
        BlockPos foot = new BlockPos(4, 65, 0);
        coverBed(foot);
        world.blocks.put(foot.west(2), Blocks.wool.getDefaultState());
        world.blocks.remove(foot.west());
        assertEquals(coordinates(foot), coordinates(findTarget()));
    }

    @Test public void nearestAdjacentCoverWinsOverFasterDistantCover() throws Exception {
        BlockPos foot = new BlockPos(4, 65, 0);
        coverBed(foot);
        world.blocks.put(foot.west(), Blocks.obsidian.getDefaultState());
        mc.thePlayer.inventory.setInventorySlotContents(1, new ItemStack(Items.diamond_pickaxe));
        assertEquals(coordinates(foot.west()), coordinates(findTarget()));
    }

    @Test public void uncoveredUndersideDoesNotSkipDefense() throws Exception {
        BlockPos foot = new BlockPos(4, 65, 0);
        coverBed(foot);
        assertEquals(coordinates(foot.west()), coordinates(findTarget()));
    }

    @Test public void cancellationUsesDownFaceAfterSideDigging() throws Exception {
        BlockPos position = new BlockPos(3, 65, 0);
        world.blocks.put(position, Blocks.wool.getDefaultState());
        CapturingNetHandler handler = new CapturingNetHandler();
        field(net.minecraft.client.entity.EntityPlayerSP.class, "sendQueue").set(mc.thePlayer, handler);
        field(SilentBedBreaker.class, "target").set(silent, position);
        field(SilentBedBreaker.class, "face").set(silent, EnumFacing.WEST);
        field(SilentBedBreaker.class, "digging").setBoolean(silent, true);
        silent.cleanup();
        assertEquals(1, handler.packets.size());
        C07PacketPlayerDigging packet = (C07PacketPlayerDigging) handler.packets.get(0);
        assertEquals(C07PacketPlayerDigging.Action.ABORT_DESTROY_BLOCK, packet.getStatus());
        assertEquals(EnumFacing.DOWN, packet.getFacing());
        assertEquals(coordinates(position), coordinates(packet.getPosition()));
    }

    @Test public void packetSequenceBreaksOneCoverThenBedBehindOuterWall() throws Exception {
        BlockPos foot = new BlockPos(4, 65, 0);
        coverBed(foot);
        for (int y = 64; y <= 67; y++) for (int z = -2; z <= 2; z++) {
            world.blocks.put(new BlockPos(2, y, z), Blocks.wool.getDefaultState());
        }
        field(mindless.module.Module.class, "enabled").setBoolean(owner, true);
        ((ProfiledButtonSetting) field(BedAura.class, "silentSwing").get(owner)).setEnabled(false);
        CapturingNetHandler handler = new CapturingNetHandler();
        field(net.minecraft.client.entity.EntityPlayerSP.class, "sendQueue").set(mc.thePlayer, handler);
        mc.playerController = new PlayerControllerMP(mc, handler);
        silent.prepareTick();
        assertEquals(coordinates(foot.west()), coordinates(silent.target()));
        for (int i = 0; i < 60 && !field(SilentBedBreaker.class, "stopSent").getBoolean(silent); i++) {
            resolvedAction();
        }
        assertTrue(field(SilentBedBreaker.class, "stopSent").getBoolean(silent));
        silent.prepareTick();
        assertEquals("Wait for server confirmation before targeting bed", coordinates(foot.west()), coordinates(silent.target()));
        world.blocks.remove(foot.west());
        silent.prepareTick();
        silent.prepareTick();
        assertEquals(coordinates(foot), coordinates(silent.target()));
        for (int i = 0; i < 60 && !field(SilentBedBreaker.class, "stopSent").getBoolean(silent); i++) {
            resolvedAction();
        }
        assertTrue(field(SilentBedBreaker.class, "stopSent").getBoolean(silent));
        List<C07PacketPlayerDigging> digging = new ArrayList<C07PacketPlayerDigging>();
        for (Packet<?> packet : handler.packets) {
            if (packet instanceof C07PacketPlayerDigging) digging.add((C07PacketPlayerDigging) packet);
        }
        assertEquals(4, digging.size());
        for (int i = 0; i < digging.size(); i++) {
            assertEquals(i % 2 == 0 ? C07PacketPlayerDigging.Action.START_DESTROY_BLOCK
                    : C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK, digging.get(i).getStatus());
            assertEquals(coordinates(i < 2 ? foot.west() : foot), coordinates(digging.get(i).getPosition()));
            assertEquals(EnumFacing.WEST, digging.get(i).getFacing());
        }
    }

    @Test public void whitelistWaitsForSpawnAndChoosesNearestBed() throws Exception {
        mindless.utility.OwnBedTracker.reset();
        try {
            bed(new BlockPos(-3, 62, -3), EnumFacing.WEST);
            BlockPos own = new BlockPos(5, 65, 0);
            bed(own, EnumFacing.EAST);
            mindless.utility.OwnBedTracker.handleChat("Protect your bed and destroy the enemy beds.");
            assertEquals(0L, field(mindless.utility.OwnBedTracker.class, "scanAt").getLong(null));
            mindless.utility.OwnBedTracker.handleSpawnTeleport();
            assertTrue(field(mindless.utility.OwnBedTracker.class, "scanAt").getLong(null) > 0);
            Method scan = mindless.utility.OwnBedTracker.class.getDeclaredMethod("findNearestBed");
            scan.setAccessible(true);
            BlockPos[] pair = (BlockPos[]) scan.invoke(null);
            assertEquals(own, pair[0]);
            field(mindless.utility.OwnBedTracker.class, "ownBedFoot").set(null, pair[0]);
            assertTrue(mindless.utility.OwnBedTracker.isOwnBed(
                    mindless.utility.OwnBedTracker.footHeadPair(own.east())));
            field(mindless.utility.OwnBedTracker.class, "scanAt").setLong(null, 0L);
            world.blocks.clear();
            mindless.utility.OwnBedTracker.tick();
            assertTrue(mindless.utility.OwnBedTracker.isKnown());
            assertFalse(mindless.utility.OwnBedTracker.isDestroyed());
            world.bedAreaLoaded = true;
            mindless.utility.OwnBedTracker.tick();
            assertTrue(mindless.utility.OwnBedTracker.isDestroyed());
            assertFalse(mindless.utility.OwnBedTracker.isKnown());
            mindless.utility.OwnBedTracker.handleChat("You have respawned!");
            assertEquals(0L, field(mindless.utility.OwnBedTracker.class, "scanAt").getLong(null));
            mindless.utility.OwnBedTracker.reset();
            mindless.utility.OwnBedTracker.handleSpawnTeleport();
            assertEquals(0L, field(mindless.utility.OwnBedTracker.class, "scanAt").getLong(null));
        } finally {
            mindless.utility.OwnBedTracker.reset();
        }
    }

    private void resolvedAction() throws Exception {
        mindless.event.ClientRotationEvent rotation = new mindless.event.ClientRotationEvent(0.0F, 0.0F);
        silent.requestRotation(rotation);
        assertTrue(rotation.hasRotationRequest());
        RotationHelper helper = RotationHelper.get();
        field(RotationHelper.class, "serverYaw").set(helper, rotation.getYaw());
        field(RotationHelper.class, "serverPitch").set(helper, rotation.getPitch());
        field(RotationHelper.class, "serverYawSource").set(helper, RotationSource.BED_AURA);
        field(RotationHelper.class, "serverPitchSource").set(helper, RotationSource.BED_AURA);
        mindless.event.PreMotionEvent motion = new mindless.event.PreMotionEvent();
        motion.requestRotation(RotationSource.BED_AURA, rotation.getYaw(), rotation.getPitch());
        silent.actionTick(motion);
    }

    private void coverBed(BlockPos foot) {
        bed(foot, EnumFacing.EAST);
        BlockPos head = foot.east();
        for (BlockPos half : new BlockPos[]{foot, head}) {
            for (EnumFacing direction : EnumFacing.values()) {
                if (direction == EnumFacing.DOWN) continue;
                BlockPos position = half.offset(direction);
                if (!position.equals(foot) && !position.equals(head)) {
                    world.blocks.put(position, Blocks.wool.getDefaultState());
                }
            }
        }
    }

    private String coordinates(BlockPos pos) {
        return pos == null ? "none" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private void bed(BlockPos foot, EnumFacing facing) {
        world.blocks.put(foot, Blocks.bed.getDefaultState().withProperty(BlockBed.FACING, facing).withProperty(BlockBed.PART, BlockBed.EnumPartType.FOOT));
        world.blocks.put(foot.offset(facing), Blocks.bed.getDefaultState().withProperty(BlockBed.FACING, facing).withProperty(BlockBed.PART, BlockBed.EnumPartType.HEAD));
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static class TestWorld extends WorldClient {
        Map<BlockPos, IBlockState> blocks;
        boolean bedAreaLoaded;
        private TestWorld() { super(null, null, 0, null, null); }
        @Override public boolean isAreaLoaded(BlockPos from, BlockPos to) { return bedAreaLoaded; }
        @Override public IBlockState getBlockState(BlockPos position) {
            IBlockState state = blocks.get(position);
            return state == null ? Blocks.air.getDefaultState() : state;
        }
    }

    private static class TestPlayer extends net.minecraft.client.entity.EntityPlayerSP {
        private TestPlayer() { super(null, null, null, null); }
        @Override public boolean isSpectator() { return false; }
        @Override public boolean isInsideOfMaterial(net.minecraft.block.material.Material material) { return false; }
    }

    private static class CapturingNetHandler extends NetHandlerPlayClient {
        private final List<Packet<?>> packets = new ArrayList<Packet<?>>();
        private CapturingNetHandler() { super(null, null, null, new GameProfile(UUID.randomUUID(), "test")); }
        @Override public void addToSendQueue(Packet packet) { packets.add(packet); }
    }
}
