package mindless.module.impl.player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import mindless.event.GameTickEvent;
import mindless.event.PostPlayerInputEvent;
import mindless.event.PrePlayerInputEvent;
import mindless.helper.RotationHelper;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.placement.PlacementCoordinator;
import mindless.placement.PlacementLease;
import mindless.rotation.RotationSource;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovementInput;
import net.minecraft.util.Vec3;
import org.junit.*;
import static org.junit.Assert.*;

public class ScaffoldMovementTest {
    private final PlacementModuleRegressionTest fixture = new PlacementModuleRegressionTest();
    private Minecraft mc;
    private Scaffold module;
    private Scaffold previousScaffold;

    @BeforeClass public static void start() throws Exception { PlacementModuleRegressionTest.initialize(); }
    @AfterClass public static void finish() throws Exception { PlacementModuleRegressionTest.finish(); }

    @Before public void reset() throws Exception {
        fixture.reset();
        mc = Minecraft.getMinecraft();
        previousScaffold = ModuleManager.scaffold;
        module = new Scaffold();
        module.setEnabled(true);
        module.onEnable();
        ModuleManager.scaffold = module;
        mc.thePlayer.movementInput = new MovementInput();
        set(mc.thePlayer, "activePotionsMap", new HashMap<>());
        net.minecraft.entity.DataWatcher watcher = new net.minecraft.entity.DataWatcher(mc.thePlayer);
        watcher.addObject(0, (byte) 0);
        set(mc.thePlayer, "dataWatcher", watcher);
        set(mc.thePlayer, "foodStats", new net.minecraft.util.FoodStats());
        mc.thePlayer.getAttributeMap().registerAttribute(net.minecraft.entity.SharedMonsterAttributes.movementSpeed);
        RotationHelper.get().onRunTick(new GameTickEvent());
    }

    @After public void clear() {
        RotationHelper.get().onRunTick(new GameTickEvent());
        ModuleManager.scaffold = previousScaffold;
        fixture.clear();
    }

    @Test public void defaultGroundDiagonalBeforeFirstAimMatchesVanilla() throws Exception {
        checkInput(true, false, 1, 1, false);
    }

    @Test public void defaultSneakingDiagonalBeforeFirstAimMatchesVanilla() throws Exception {
        checkInput(true, true, 1, 1, false);
    }

    @Test public void defaultAirborneDiagonalBeforeFirstAimMatchesVanilla() throws Exception {
        checkInput(false, false, 1, 1, false);
    }

    @Test public void straightInputBeforeFirstAimIsVanillaControl() throws Exception {
        checkInput(true, false, 1, 0, false);
    }

    @Test public void correctedDiagonalIsVanillaControl() throws Exception {
        checkInput(true, false, 1, 1, true);
    }

    @Test public void correctedSneakingDiagonalIsVanillaControl() throws Exception {
        checkInput(true, true, 1, 1, true);
    }

    @Test public void legacyMotionSettingsCannotScaleVanillaInput() throws Exception {
        mc.thePlayer.onGround = true;
        for (int percent : new int[]{0, 50, 100, 200}) {
            com.google.gson.JsonObject profile = new com.google.gson.JsonObject();
            profile.addProperty("ground-motion", percent);
            profile.addProperty("air-motion", percent);
            profile.addProperty("speed-motion", percent);
            for (mindless.module.setting.Setting setting : module.getSettings()) setting.loadProfile(profile);
            for (boolean ground : new boolean[]{false, true}) {
                mc.thePlayer.onGround = ground;
                PrePlayerInputEvent event = new PrePlayerInputEvent(1, 0, false, false, 0.3D);
                module.onPrePlayerInput(event);
                assertEquals("legacy motion settings must preserve keyboard input", 1F, event.getForward(), 0F);
            }
        }
    }

    @Test public void scaffoldNeverWritesVelocityPositionOrGroundState() throws Exception {
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        try (java.io.InputStream input = Scaffold.class.getResourceAsStream("Scaffold.class")) {
            new org.objectweb.asm.ClassReader(input).accept(node, 0);
        }
        for (org.objectweb.asm.tree.MethodNode method : node.methods) {
            for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode) {
                    org.objectweb.asm.tree.FieldInsnNode field = (org.objectweb.asm.tree.FieldInsnNode) instruction;
                    if (field.getOpcode() != org.objectweb.asm.Opcodes.PUTFIELD) continue;
                    assertFalse(method.name + " writes player physics: " + field.name,
                            field.name.matches("motion[XYZ]|pos[XYZ]|onGround|jumpMovementFactor|speedInAir|stepHeight"));
                }
            }
        }
    }

    @Test public void moveFixNoneStillUsesThePacketYawForMovementAndJump() throws Exception {
        ((SliderSetting) get(module, "moveFix")).setValueRaw(0);
        RotationHelper helper = new RotationHelper();
        helper.forceMovementFix = true;
        set(helper, "serverYawSource", RotationSource.SCAFFOLD);
        set(helper, "serverYaw", 90F);
        set(helper, "setRotations", true);
        mc.thePlayer.movementInput.moveForward = 1;
        helper.onPostInput(new PostPlayerInputEvent());
        assertEquals(1F, mc.thePlayer.movementInput.moveForward, 0F);
        mindless.event.StrafeEvent strafe = new mindless.event.StrafeEvent(0, 1, 0.1F, 0);
        helper.onStrafe(strafe);
        assertEquals(90F, strafe.getYaw(), 0F);
        mindless.event.JumpEvent jump = new mindless.event.JumpEvent(mc.thePlayer, 0.42F, 0, true);
        helper.onJump(jump);
        assertEquals(90F, jump.getYaw(), 0F);
        assertEquals(0.42F, jump.getMotionY(), 0F);
    }

    @Test public void safeWalkRequestsVanillaSneakInput() throws Exception {
        Scaffold safe = new Scaffold() {
            @Override public boolean wantsSafeWalk() { return true; }
        };
        safe.setEnabled(true);
        PrePlayerInputEvent event = new PrePlayerInputEvent(1, 1, false, false, 0.3D);
        safe.onPrePlayerInput(event);
        assertTrue(event.isSneak());
        assertEquals(1F, event.getForward(), 0F);
        assertEquals(1F, event.getStrafe(), 0F);
        assertEquals(0.3D, event.getSneakSlowDownMultiplier(), 0D);
    }

    @Test public void scaffoldCannotClipEdgesWithoutSneaking() {
        ModuleManager.scaffold = new Scaffold() {
            @Override public boolean wantsSafeWalk() { return true; }
        };
        ModuleManager.scaffold.setEnabled(true);
        mc.thePlayer.onGround = true;
        assertFalse(mindless.utility.SafeWalkState.shouldSafeWalk(mc.thePlayer));
        mc.thePlayer.movementInput.sneak = true;
        assertTrue(mindless.utility.SafeWalkState.shouldSafeWalk(mc.thePlayer));
    }

    @Test public void sprintRestrictionRunsOnCurrentInputAndLeavesVelocityUntouched() throws Exception {
        mc.thePlayer.motionX = 0.21D;
        mc.thePlayer.motionY = 0.33319999363422365D;
        mc.thePlayer.motionZ = -0.17D;
        mc.thePlayer.movementInput.moveForward = 1;
        mc.thePlayer.setSprinting(true);
        module.beforeLivingMovement();
        assertFalse("NONE must remain stopped after vanilla sprint-start logic", mc.thePlayer.isSprinting());
        ((SliderSetting) get(module, "sprint")).setValueRaw(1);
        mc.thePlayer.setSprinting(true);
        module.beforeLivingMovement();
        assertTrue("vanilla forward sprint remains available", mc.thePlayer.isSprinting());
        for (int keep : new int[]{0, 1}) {
            set(module, "keepYState", keep);
            for (float forward : new float[]{-1, 0}) {
                mc.thePlayer.movementInput.moveForward = forward;
                mc.thePlayer.movementInput.moveStrafe = 1;
                mc.thePlayer.setSprinting(true);
                module.beforeLivingMovement();
                assertFalse("backwards/sideways sprint must not survive input correction", mc.thePlayer.isSprinting());
            }
        }
        mc.thePlayer.movementInput.moveForward = 1;
        mc.thePlayer.movementInput.sneak = true;
        mc.thePlayer.setSprinting(true);
        module.beforeLivingMovement();
        assertFalse(mc.thePlayer.isSprinting());
        assertEquals(0.21D, mc.thePlayer.motionX, 0D);
        assertEquals(0.33319999363422365D, mc.thePlayer.motionY, 0D);
        assertEquals(-0.17D, mc.thePlayer.motionZ, 0D);
    }

    @Test public void allKeyboardDirectionsRemainVanillaAcrossYawAndSneakTransitions() throws Exception {
        for (int fixMode : new int[]{0, 1}) for (boolean sneak : new boolean[]{false, true}) {
            ((SliderSetting) get(module, "moveFix")).setValueRaw(fixMode);
            for (float yaw : new float[]{-179, -90, -23, 0, 23, 90, 179}) {
                RotationHelper helper = new RotationHelper();
                set(helper, "serverYawSource", RotationSource.SCAFFOLD);
                set(helper, "serverYaw", yaw);
                set(helper, "setRotations", true);
                for (int forward = -1; forward <= 1; forward++) for (int strafe = -1; strafe <= 1; strafe++) {
                    mc.thePlayer.rotationYaw = 0;
                    PrePlayerInputEvent input = new PrePlayerInputEvent(forward, strafe, false, sneak, 0.3D);
                    module.onPrePlayerInput(input);
                    float scale = sneak ? 0.3F : 1F;
                    mc.thePlayer.movementInput.moveForward = input.getForward() * scale;
                    mc.thePlayer.movementInput.moveStrafe = input.getStrafe() * scale;
                    mc.thePlayer.movementInput.sneak = sneak;
                    helper.onPostInput(new PostPlayerInputEvent());
                    float correctedForward = mc.thePlayer.movementInput.moveForward;
                    float correctedStrafe = mc.thePlayer.movementInput.moveStrafe;
                    assertTrue(correctedForward == 0 || Math.abs(correctedForward) == scale);
                    assertTrue(correctedStrafe == 0 || Math.abs(correctedStrafe) == scale);
                    mindless.event.StrafeEvent movement = new mindless.event.StrafeEvent(correctedStrafe, correctedForward, 0.1F, 0);
                    helper.onStrafe(movement);
                    assertEquals(yaw, movement.getYaw(), 0F);
                }
            }
        }
    }

    private void checkInput(boolean ground, boolean sneak, float forward, float strafe, boolean correction) throws Exception {
        mc.thePlayer.onGround = ground;
        mc.thePlayer.rotationYaw = 0;
        PrePlayerInputEvent event = new PrePlayerInputEvent(forward, strafe, false, sneak, 0.3D);
        module.onPrePlayerInput(event);
        float scale = sneak ? 0.3F : 1;
        mc.thePlayer.movementInput.moveForward = event.getForward() * scale;
        mc.thePlayer.movementInput.moveStrafe = event.getStrafe() * scale;
        mc.thePlayer.movementInput.sneak = sneak;
        RotationHelper helper = new RotationHelper();
        if (correction) {
            helper.forceMovementFix = true;
            set(helper, "serverYawSource", RotationSource.SCAFFOLD);
            set(helper, "serverYaw", 0F);
            set(helper, "setRotations", true);
        }
        helper.onPostInput(new PostPlayerInputEvent());
        float speed = ground ? 0.1F : 0.02F;
        double[] actual = acceleration(mc.thePlayer.movementInput.moveForward, mc.thePlayer.movementInput.moveStrafe, speed);
        double[] vanilla = acceleration(forward * scale, strafe * scale, speed);
        double error = Math.hypot(actual[0] - vanilla[0], actual[1] - vanilla[1]);
        double closestKeyboardError = Double.POSITIVE_INFINITY;
        for (int pf = -1; pf <= 1; pf++) for (int ps = -1; ps <= 1; ps++) {
            double[] possible = acceleration(pf * scale, ps * scale, speed);
            closestKeyboardError = Math.min(closestKeyboardError,
                    Math.hypot(actual[0] - possible[0], actual[1] - possible[1]));
        }
        System.out.println("ground=" + ground + " sneak=" + sneak + " correction=" + correction
                + " input=" + mc.thePlayer.movementInput.moveForward + "," + mc.thePlayer.movementInput.moveStrafe
                + " actual=" + actual[0] + "," + actual[1] + " vanilla=" + vanilla[0] + "," + vanilla[1]
                + " horizontalError=" + error + " closestKeyboardError=" + closestKeyboardError);
        assertEquals("default Scaffold must preserve vanilla acceleration before acquiring aim", 0, error, 1.0E-7);
    }

    private double[] acceleration(float forward, float strafe, float speed) {
        mc.thePlayer.motionX = mc.thePlayer.motionZ = 0;
        mc.thePlayer.moveFlying(strafe * 0.98F, forward * 0.98F, speed);
        return new double[]{mc.thePlayer.motionX, mc.thePlayer.motionZ};
    }

    @Test public void disablingScaffoldReleasesItsPendingRotation() throws Exception {
        RotationHelper helper = RotationHelper.get();
        helper.request(RotationSource.SCAFFOLD, 120F, 70F);
        assertEquals(RotationSource.SCAFFOLD, helper.getServerYawSource());
        module.setEnabled(false);
        GameSettings settings = mc.gameSettings;
        try {
            mc.gameSettings = null;
            module.onDisable();
        } finally {
            mc.gameSettings = settings;
        }
        assertNull("disabled Scaffold must not leave a pending packet rotation", helper.getServerYawSource());
    }

    @Test public void multiPlaceAllowsSecondPlacementInSameTick() throws Exception {
        ((ButtonSetting) get(module, "multiPlace")).setEnabled(true);
        Method place = preparePlacement();
        Object target = newTarget();
        assertTrue((Boolean) place.invoke(module, target));
        assertTrue("multi-place must permit another controller call in the same tick", (Boolean) place.invoke(module, target));
    }

    @Test public void placementWaitsWhenAnotherModuleOwnsRotation() throws Exception {
        preparePlacement();
        set(module, "queuedTarget", newTarget());
        set(module, "queuedHit", new Vec3(0.5, 1, 0.5));
        set(module, "placementYaw", 0F);
        set(module, "placementPitch", 80F);
        RotationHelper helper = RotationHelper.get();
        helper.request(RotationSource.SCAFFOLD, 0F, 80F);
        helper.request(RotationSource.KILL_AURA, 90F, 0F);
        assertEquals(RotationSource.KILL_AURA, helper.getServerYawSource());
        Method commit = Scaffold.class.getDeclaredMethod("placeCurrentAndAdditionalBlocks");
        commit.setAccessible(true);
        assertFalse("queued placement must wait when the resolved look faces elsewhere", (Boolean) commit.invoke(module));
    }

    private Method preparePlacement() throws Exception {
        mc.thePlayer.posX = mc.thePlayer.posZ = 0.5D;
        mc.thePlayer.posY = 2D;
        mc.thePlayer.rotationYaw = 0;
        mc.thePlayer.rotationPitch = 90;
        set(mc.theWorld, "traceGeometry", true);
        ((java.util.Map) get(mc.theWorld, "blocks")).put(BlockPos.ORIGIN, Blocks.stone.getDefaultState());
        mc.thePlayer.inventory.mainInventory[0] = new ItemStack(Blocks.stone, 64);
        set(module, "remainingStackBlocks", 64);
        long tick = Utils.getBaseClientTick();
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        coordinator.announce(module, PlacementCoordinator.Priority.SCAFFOLD, mc.thePlayer, mc.theWorld, tick + 1);
        PlacementLease lease = coordinator.acquire(module, PlacementCoordinator.Priority.SCAFFOLD, mc.thePlayer, mc.theWorld, tick);
        set(module, "placementLease", lease);
        mc.playerController = new PlayerControllerMP(mc, mc.thePlayer.sendQueue) {
            @Override public boolean onPlayerRightClick(EntityPlayerSP player, WorldClient world, ItemStack stack,
                                                       BlockPos position, EnumFacing face, Vec3 hit) {
                return true;
            }
        };
        Class<?> targetClass = Class.forName(Scaffold.class.getName() + "$BlockPlacementTarget");
        Method place = Scaffold.class.getDeclaredMethod("placeBlock", targetClass);
        place.setAccessible(true);
        return place;
    }

    private Object newTarget() throws Exception {
        Class<?> targetClass = Class.forName(Scaffold.class.getName() + "$BlockPlacementTarget");
        Constructor<?> constructor = targetClass.getDeclaredConstructor(BlockPos.class, EnumFacing.class);
        constructor.setAccessible(true);
        return constructor.newInstance(BlockPos.ORIGIN, EnumFacing.UP);
    }

    private static Object get(Object owner, String name) throws Exception { return field(owner.getClass(), name).get(owner); }
    private static void set(Object owner, String name, Object value) throws Exception { field(owner.getClass(), name).set(owner, value); }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
