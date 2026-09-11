package mindless.module.impl.combat.aura;

import com.mojang.authlib.GameProfile;
import mindless.event.PostUpdateEvent;
import mindless.module.impl.bedwars.ResourceDepositTest;
import mindless.module.impl.combat.AutoClicker;
import mindless.module.impl.combat.KillAura;
import mindless.module.ModuleManager;
import mindless.module.impl.movement.NoSlow;
import mindless.runtime.AccessorBridge;
import mindless.runtime.CombatPacketState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import net.minecraft.util.*;
import net.minecraft.world.World;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.Assert.*;

public class AuraEdgeCasesTest {
    private final ResourceDepositTest fixture = new ResourceDepositTest();
    private Minecraft mc;
    private Object previousUtilityMinecraft;

    @Before public void setUp() throws Exception {
        fixture.setUp();
        Method setup = ResourceDepositTest.class.getDeclaredMethod("fixture", int.class, String.class);
        setup.setAccessible(true);
        setup.invoke(fixture, 27, "container.chest");
        mc = Minecraft.getMinecraft();
        Field utilityMinecraft = mindless.utility.IMinecraftInstance.class.getField("mc");
        previousUtilityMinecraft = utilityMinecraft.get(null);
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        unsafe.putObject(unsafe.staticFieldBase(utilityMinecraft), unsafe.staticFieldOffset(utilityMinecraft), mc);
        mc.thePlayer.worldObj = mc.theWorld;
        field(World.class, "isRemote").setBoolean(mc.theWorld, true);
        mc.gameSettings = new GameSettings();
        mc.currentScreen = null;
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Items.diamond_sword);
        CombatPacketState.beginSession();
    }

    @After public void tearDown() throws Exception {
        CombatPacketState.beginSession();
        Field utilityMinecraft = mindless.utility.IMinecraftInstance.class.getField("mc");
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        unsafe.putObject(unsafe.staticFieldBase(utilityMinecraft), unsafe.staticFieldOffset(utilityMinecraft), previousUtilityMinecraft);
        fixture.tearDown();
    }

    @Test public void interactionsAndSlotRoundTripsDoNotInventItemUseTransitions() {
        ItemStack sword = mc.thePlayer.getHeldItem();
        CombatPacketState.recordAccepted(new C08PacketPlayerBlockPlacement(BlockPos.ORIGIN, 1, sword, .5F, 1F, .5F));
        assertFalse(CombatPacketState.serverBlocking());
        KillAura aura = new KillAura();
        aura.onPostUpdate(new PostUpdateEvent());
        assertFalse(mc.thePlayer.isUsingItem());
        CombatPacketState.recordAccepted(new C08PacketPlayerBlockPlacement(sword));
        assertTrue(CombatPacketState.serverBlocking());
        long revision = CombatPacketState.blockRevision();
        CombatPacketState.recordAccepted(new C09PacketHeldItemChange(1));
        CombatPacketState.recordAccepted(new C09PacketHeldItemChange(0));
        assertTrue(CombatPacketState.serverBlocking());
        assertEquals(revision, CombatPacketState.blockRevision());
        assertFalse(aura.isBlockingSword());
        new KillAura().onPostUpdate(new PostUpdateEvent());
        assertFalse(mc.thePlayer.isUsingItem());
        CombatPacketState.recordAccepted(new C07PacketPlayerDigging(C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        assertFalse(CombatPacketState.serverBlocking());
    }

    @Test public void rejectedSlotRestoreRetainsOwnershipUntilAcceptedOrReplaced() throws Exception {
        KillAura aura = new KillAura();
        RecordingHandler handler = handler();
        field(KillAura.class, "temporarySlot").setInt(aura, 1);
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 1);
        assertEquals(false, invoke(aura, "restoreTemporarySlot"));
        assertEquals(1, field(KillAura.class, "temporarySlot").getInt(aura));
        assertEquals(1, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
        handler.accept = true;
        assertEquals(true, invoke(aura, "restoreTemporarySlot"));
        assertEquals(-1, field(KillAura.class, "temporarySlot").getInt(aura));
        assertEquals(0, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
        field(KillAura.class, "temporarySlot").setInt(aura, 1);
        AccessorBridge.PlayerControllerMP_setCurrentPlayerItem(mc.playerController, 2);
        int attempts = handler.attempts;
        assertEquals(true, invoke(aura, "restoreTemporarySlot"));
        assertEquals(attempts, handler.attempts);
        assertEquals(2, AccessorBridge.PlayerControllerMP_getCurrentPlayerItem(mc.playerController));
    }

    @Test public void disabledCleanupRetriesAtMostOncePerTickAndStopsAtTheBound() throws Exception {
        KillAura aura = new KillAura();
        aura.onEnable();
        RecordingHandler handler = handler();
        AuraAutoBlockController block = (AuraAutoBlockController) field(KillAura.class, "block").get(aura);
        block.active = true;
        CombatPacketState.recordAccepted(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        CombatPacketState.clearActions();
        aura.onDisable();
        assertTrue(field(KillAura.class, "cleanupPending").getBoolean(aura));
        assertTrue(block.active);
        assertEquals(1, handler.attempts);
        aura.beforePlayerInteraction();
        assertEquals(1, handler.attempts);
        for (int i = 0; i < 25; i++) {
            field(KillAura.class, "cleanupTick").setLong(aura, Long.MIN_VALUE);
            aura.beforePlayerInteraction();
        }
        assertEquals(20, handler.attempts);
        assertTrue(field(KillAura.class, "cleanupPending").getBoolean(aura));
        aura.onWorldChange();
        assertFalse(field(KillAura.class, "cleanupPending").getBoolean(aura));
        assertFalse(block.active);
        assertEquals(20, handler.attempts);
    }

    @Test public void disabledCleanupCanFinishAfterARejectedRelease() throws Exception {
        KillAura aura = new KillAura();
        aura.onEnable();
        RecordingHandler handler = handler();
        AuraAutoBlockController block = (AuraAutoBlockController) field(KillAura.class, "block").get(aura);
        block.active = true;
        CombatPacketState.recordAccepted(new C08PacketPlayerBlockPlacement(mc.thePlayer.getHeldItem()));
        CombatPacketState.clearActions();
        aura.onDisable();
        handler.accept = true;
        field(KillAura.class, "cleanupTick").setLong(aura, Long.MIN_VALUE);
        aura.beforePlayerInteraction();
        assertFalse(field(KillAura.class, "cleanupPending").getBoolean(aura));
        assertFalse(block.active);
        assertFalse(CombatPacketState.serverBlocking());
    }

    @Test public void legitSelectsTheNearestColliderBeforeHealthAndRetention() throws Exception {
        TestEntity front = entity(1.2), rear = entity(2.2);
        List<Entity> entities = new ArrayList<>(Arrays.asList(rear, front));
        field(World.class, "loadedEntityList").set(mc.theWorld, entities);
        Vec3 eyes = new Vec3(0, 1.62, 0);
        assertSame(front, AuraTargeting.nearestEntity(entities, mc.thePlayer, eyes, 0, 0, 3));
        front.collidable = false;
        assertSame(rear, AuraTargeting.nearestEntity(entities, mc.thePlayer, eyes, 0, 0, 3));
        front.collidable = true;
        mc.thePlayer.setPosition(0, 0, 0);
        mc.thePlayer.rotationYaw = mc.thePlayer.rotationPitch = 0;
        KillAura aura = new KillAura();
        aura.rotationMode.setValue(3);
        AuraTarget far = candidate(rear), near = candidate(front);
        field(KillAura.class, "currentTarget").set(aura, far);
        field(KillAura.class, "selectedAt").setLong(aura, 1000);
        Method select = KillAura.class.getDeclaredMethod("selectCandidates", List.class, long.class);
        select.setAccessible(true);
        select.invoke(aura, new ArrayList<>(Arrays.asList(far, near)), 1001L);
        assertSame(near, field(KillAura.class, "currentTarget").get(aura));
        select.invoke(aura, new ArrayList<>(Collections.singletonList(far)), 1002L);
        assertNull(field(KillAura.class, "currentTarget").get(aura));
        front.setEntityBoundingBox(new AxisAlignedBB(-1, 0, -1, 1, 2, 1));
        assertSame(front, AuraTargeting.nearestEntity(entities, mc.thePlayer, eyes, 0, 0, 3));
    }

    @Test public void disablingAutoClickerReleasesSyntheticAttackWithoutAnInputDevice() {
        KeyBinding attack = mc.gameSettings.keyBindAttack;
        KeyBinding.setKeyBindState(attack.getKeyCode(), true);
        assertTrue(attack.isKeyDown());
        new AutoClicker().onDisable();
        assertFalse(attack.isKeyDown());
    }

    @Test public void legitAndHypixelBlockingKeepVanillaSlowdownWhenNoSlowIsOff() throws Exception {
        KillAura previousAura = ModuleManager.killAura;
        NoSlow previousNoSlow = ModuleManager.noSlow;
        try {
            ModuleManager.noSlow = null;
            KillAura aura = new KillAura();
            ModuleManager.killAura = aura;
            aura.autoBlockNoSlow.setEnabled(false);
            RecordingHandler network = handler();
            network.accept = true;
            Method start = KillAura.class.getDeclaredMethod("startBlocking", ItemStack.class);
            start.setAccessible(true);
            for (int mode : new int[]{AuraAutoBlockController.LEGIT, AuraAutoBlockController.HYPIXEL}) {
                aura.autoBlock.setValue(mode);
                assertEquals(true, start.invoke(aura, mc.thePlayer.getHeldItem()));
                assertTrue(mc.thePlayer.isUsingItem());
                assertTrue(CombatPacketState.serverBlocking());
                assertEquals(0.2F, NoSlow.getSlowed(), 0.0F);
                assertEquals(true, invoke(aura, "releaseBlocking"));
                assertFalse(mc.thePlayer.isUsingItem());
                assertFalse(CombatPacketState.serverBlocking());
            }
        } finally {
            ModuleManager.killAura = previousAura;
            ModuleManager.noSlow = previousNoSlow;
        }
    }

    @Test public void attacksUseVanillaClickDelayAndRespectItemUse() throws Exception {
        RecordingHandler network = handler();
        network.accept = true;
        mc.playerController = new net.minecraft.client.multiplayer.PlayerControllerMP(mc, network);
        field(net.minecraft.client.multiplayer.PlayerControllerMP.class, "currentGameType")
                .set(mc.playerController, net.minecraft.world.WorldSettings.GameType.SPECTATOR);
        field(net.minecraft.entity.EntityLivingBase.class, "activePotionsMap").set(mc.thePlayer, new HashMap<>());
        mc.thePlayer.setPosition(0, 0, 0);
        TestEntity victim = entity(1.2);
        field(World.class, "loadedEntityList").set(mc.theWorld, new ArrayList<>(Collections.singletonList(victim)));
        KillAura aura = new KillAura();
        aura.requirePress.setEnabled(false);
        aura.rotationMode.setValue(2);
        field(KillAura.class, "currentTarget").set(aura, candidate(victim));
        MovingObjectPosition previous = new MovingObjectPosition(victim);
        mc.objectMouseOver = previous;
        Method attack = KillAura.class.getDeclaredMethod("executeAttack", float.class, float.class);
        attack.setAccessible(true);
        AccessorBridge.Minecraft_setLeftClickCounter(mc, 2);
        assertEquals(false, attack.invoke(aura, 0F, 0F));
        assertEquals(0, network.attempts);
        assertSame(previous, mc.objectMouseOver);
        AccessorBridge.Minecraft_setLeftClickCounter(mc, 0);
        assertEquals(true, attack.invoke(aura, 0F, 0F));
        assertTrue(network.attempts > 0);
        assertSame(previous, mc.objectMouseOver);
        assertFalse(field(KillAura.class, "performingClick").getBoolean(aura));
        int attempts = network.attempts;
        mc.thePlayer.setItemInUse(mc.thePlayer.getHeldItem(), 72000);
        assertEquals(false, attack.invoke(aura, 0F, 0F));
        assertEquals(attempts, network.attempts);
    }

    private RecordingHandler handler() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        field(EntityPlayerSP.class, "sendQueue").set(mc.thePlayer, handler);
        return handler;
    }

    private static final class RecordingHandler extends NetHandlerPlayClient {
        boolean accept;
        int attempts;
        RecordingHandler() { super(null, null, null, new GameProfile(UUID.randomUUID(), "aura-test")); }
        @Override public void addToSendQueue(Packet packet) {
            attempts++;
            if (accept) CombatPacketState.recordAccepted(packet);
        }
    }

    public static class TestEntity extends EntityZombie {
        boolean collidable;
        public TestEntity() { super(null); }
        @Override public boolean canBeCollidedWith() { return collidable; }
    }

    private static TestEntity entity(double z) throws Exception {
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        TestEntity entity = (TestEntity) unsafe.allocateInstance(TestEntity.class);
        entity.collidable = true;
        entity.setEntityBoundingBox(new AxisAlignedBB(-.4, 0, z, .4, 1.9, z + .6));
        return entity;
    }

    private static AuraTarget candidate(TestEntity entity) {
        return new AuraTarget(entity, entity.getEntityBoundingBox(), 0, 0, 0, 0, 0,
                entity.getEntityBoundingBox().minZ, 0, true);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private static Object invoke(Object owner, String name) throws Exception {
        Method method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(owner);
    }
}
