package mindless.runtime;

import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.junit.Assume;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Contract test for the bytecode emitted by the runtime transformer pipeline.
 *
 * <p>The native agent retransforms classes that are already loaded. JVMTI
 * permits method-body changes in that situation, but it rejects structural
 * changes such as adding/removing members or changing inheritance. This test
 * feeds the same SRG bytecode used by the 1.8.9 Forge runtime through every
 * registered transformer and verifies that each result obeys that contract.</p>
 */
public class TransformerCompatibilityTest {
    private static final String LOOM_VERSION_DIR = "1.8.9";
    private static final String FORGE_VERSION = System.getProperty(
            "mindless.testForgeVersion", "1.8.9-11.15.1.1764");
    private static final String EXPECTED_CACHE_FRAGMENT =
            "mcp_stable.1_8_9.22-1.8.9-forge-" + FORGE_VERSION;
    private static final List<String> REQUIRED_SRG_TARGETS = Collections.unmodifiableList(
            Arrays.asList(
                    "net/minecraft/client/Minecraft",
                    "net/minecraft/block/Block",
                    "net/minecraft/client/settings/GameSettings",
                    "net/minecraft/item/ItemStack",
                    "net/minecraft/util/MovementInputFromOptions",
                    "net/minecraft/client/gui/inventory/GuiContainer",
                    "net/minecraft/client/multiplayer/PlayerControllerMP",
                    "net/minecraft/world/World",
                    "net/minecraft/world/storage/WorldInfo",
                    "net/minecraft/entity/Entity",
                    "net/minecraft/entity/EntityLiving",
                    "net/minecraft/entity/EntityLivingBase",
                    "net/minecraft/entity/player/EntityPlayer",
                    "net/minecraft/client/entity/EntityPlayerSP",
                    "net/minecraftforge/fml/common/network/handshake/FMLHandshakeMessage$ModList",
                    "net/minecraft/client/network/NetHandlerPlayClient",
                    "net/minecraft/network/NetworkManager",
                    "net/minecraft/client/renderer/EntityRenderer",
                    "net/minecraft/client/gui/FontRenderer",
                    "net/minecraft/client/gui/GuiChat",
                    "net/minecraft/client/gui/GuiNewChat",
                    "net/minecraft/client/gui/GuiIngame",
                    "net/minecraftforge/client/GuiIngameForge",
                    "net/minecraft/client/gui/GuiPlayerTabOverlay",
                    "net/minecraft/client/gui/GuiScreen",
                    "net/minecraft/client/renderer/ItemRenderer",
                    "net/minecraft/client/renderer/entity/layers/LayerArmorBase",
                    "net/minecraft/client/renderer/entity/layers/LayerHeldItem",
                    "net/minecraft/client/renderer/RenderGlobal",
                    "net/minecraft/client/renderer/entity/RenderEntityItem",
                    "net/minecraft/client/renderer/entity/RenderManager",
                    "net/minecraft/client/renderer/entity/RenderPlayer",
                    "net/minecraft/client/renderer/entity/RendererLivingEntity",
                    "net/minecraft/client/renderer/tileentity/TileEntityChestRenderer",
                    "net/minecraft/client/renderer/tileentity/TileEntityEnderChestRenderer"));

    @Test
    public void everyAvailableSrgTargetIsRetransformCompatible() throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        List<String> failures = new ArrayList<>();
        int transformedCount = 0;

        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(), minecraft, forge);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            Set<String> targets = manager.targetInternalNames();

            List<String> missingTargets = new ArrayList<>(REQUIRED_SRG_TARGETS);
            missingTargets.removeAll(targets);
            Assert.assertTrue("Runtime transformer registry is missing target(s) "
                    + missingTargets + "; registered targets=" + targets,
                    missingTargets.isEmpty());
            Assert.assertEquals("registeredCount disagrees with targetInternalNames",
                    targets.size(), manager.registeredCount());

            for (String target : targets) {
                byte[] original = readClass(target, minecraft, forge);
                if (original == null) {
                    failures.add(target + ": class is missing from both SRG cache jars");
                    continue;
                }

                final byte[] transformed;
                try {
                    transformed = manager.transform(target, original);
                } catch (Throwable failure) {
                    failures.add(target + ": transform threw " + describe(failure));
                    continue;
                }

                if (transformed == null) {
                    failures.add(target + ": transform returned null (failed or unexpectedly inert)");
                    continue;
                }
                if (Arrays.equals(original, transformed)) {
                    failures.add(target + ": transform returned unchanged bytecode");
                    continue;
                }

                String schemaChange = MindlessTransformerManager.findRetransformSchemaChange(
                        original, transformed);
                if (schemaChange != null) {
                    failures.add(target + ": illegal retransformation schema change: " + schemaChange);
                    continue;
                }
                String danglingReference = manager.findDanglingSelfMethodReference(transformed);
                if (danglingReference != null) {
                    failures.add(target + ": dangling self method reference: "
                            + danglingReference);
                    continue;
                }
                if ("net/minecraft/client/entity/EntityPlayerSP".equals(target)) {
                    String invalidSuperCall = findInvalidLivingUpdateSuperCall(transformed);
                    if (invalidSuperCall != null) {
                        failures.add(target + ": " + invalidSuperCall);
                        continue;
                    }
                }
                transformedCount++;
            }
        }

        if (transformedCount == 0) {
            failures.add("No registered SRG target was transformed");
        }
        Assert.assertTrue(buildFailureMessage(failures, minecraftSrg, forgeSrg),
                failures.isEmpty());
    }

    @Test
    public void everyAvailableMcpTargetIsRetransformCompatibleForGenericNamedNamespace()
            throws Exception {
        Path minecraftMcp = findEssentialLoomJar("minecraft-mapped.jar");
        Path forgeMcp = findEssentialLoomJar("forge-mapped.jar");
        List<String> failures = new ArrayList<>();
        int transformedCount = 0;

        try (JarFile minecraft = new JarFile(minecraftMcp.toFile());
             JarFile forge = new JarFile(forgeMcp.toFile())) {
            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(), minecraft, forge);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            Assert.assertEquals("MCP bytecode was not detected as the named namespace",
                    MindlessTransformerManager.RuntimeNamespace.MCP,
                    manager.runtimeNamespace());
            Assert.assertEquals("Generic MCP bytecode was incorrectly classified as Lunar",
                    MindlessTransformerManager.RuntimeProfile.GENERIC,
                    manager.runtimeProfile());

            Set<String> targets = manager.targetInternalNames();
            List<String> requiredMcpTargets = new ArrayList<>(REQUIRED_SRG_TARGETS);
            List<String> missingTargets = new ArrayList<>(requiredMcpTargets);
            missingTargets.removeAll(targets);
            Assert.assertTrue("Runtime transformer registry is missing MCP target(s) "
                    + missingTargets + "; registered targets=" + targets,
                    missingTargets.isEmpty());

            for (String target : targets) {
                byte[] original = readClass(target, minecraft, forge);
                if (original == null) {
                    failures.add(target + ": class is missing from both MCP cache jars");
                    continue;
                }
                byte[] transformed = manager.transform(target, original);
                if (transformed == null) {
                    failures.add(target + ": transform returned null");
                    continue;
                }
                if (Arrays.equals(original, transformed)) {
                    failures.add(target + ": transform returned unchanged bytecode");
                    continue;
                }
                String schemaChange = MindlessTransformerManager.findRetransformSchemaChange(
                        original, transformed);
                if (schemaChange != null) {
                    failures.add(target + ": illegal retransformation schema change: "
                            + schemaChange);
                    continue;
                }
                String danglingReference = manager.findDanglingSelfMethodReference(transformed);
                if (danglingReference != null) {
                    failures.add(target + ": dangling self method reference: "
                            + danglingReference);
                    continue;
                }
                transformedCount++;
            }
        }

        if (transformedCount == 0) failures.add("No registered MCP target was transformed");
        Assert.assertTrue(buildFailureMessage(failures, minecraftMcp, forgeMcp),
                failures.isEmpty());
    }

    @Test
    public void entityLivingBaseJumpUsesLocalPlayerGuardAndDirectSrgPotionCalls()
            throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(), minecraft, forge);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            byte[] original = readClass("net/minecraft/entity/EntityLivingBase",
                    minecraft, forge);
            Assert.assertNotNull("SRG EntityLivingBase is missing", original);

            byte[] transformed = manager.transform(
                    "net/minecraft/entity/EntityLivingBase", original);
            Assert.assertNotNull("EntityLivingBase transformer returned null", transformed);
            Assert.assertNull("EntityLivingBase changed retransformation schema",
                    MindlessTransformerManager.findRetransformSchemaChange(original, transformed));
            Assert.assertNull("EntityLivingBase contains a dangling self method reference",
                    manager.findDanglingSelfMethodReference(transformed));

            ClassNode node = new ClassNode();
            new ClassReader(transformed).accept(node,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MethodNode jump = null;
            for (MethodNode method : node.methods) {
                if ("func_70664_aZ".equals(method.name) && "()V".equals(method.desc)) {
                    jump = method;
                    break;
                }
            }
            Assert.assertNotNull("Transformed SRG EntityLivingBase.jump is missing", jump);

            boolean getsMinecraftSingleton = false;
            boolean callsIsPotionActive = false;
            boolean callsGetActivePotionEffect = false;
            boolean callsGetAmplifier = false;
            boolean routesLivingJumpThroughBridge = false;
            boolean callsForgeHooks = false;
            boolean usesReflection = false;
            int localPlayerFieldIndex = -1;
            int localPlayerGuardIndex = -1;
            int jumpEventIndex = -1;
            int jumpEventPostGuardIndex = -1;
            int jumpEventPostIndex = -1;
            AbstractInsnNode[] instructions = jump.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                AbstractInsnNode instruction = instructions[i];
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode invoke = (MethodInsnNode) instruction;
                    if (instruction.getOpcode() == Opcodes.INVOKESTATIC
                            && "net/minecraft/client/Minecraft".equals(invoke.owner)
                            && "func_71410_x".equals(invoke.name)
                            && "()Lnet/minecraft/client/Minecraft;".equals(invoke.desc)) {
                        getsMinecraftSingleton = true;
                    }
                    if ("net/minecraft/entity/EntityLivingBase".equals(invoke.owner)
                            && "func_70644_a".equals(invoke.name)
                            && "(Lnet/minecraft/potion/Potion;)Z".equals(invoke.desc)) {
                        callsIsPotionActive = true;
                    }
                    if ("net/minecraft/entity/EntityLivingBase".equals(invoke.owner)
                            && "func_70660_b".equals(invoke.name)
                            && "(Lnet/minecraft/potion/Potion;)Lnet/minecraft/potion/PotionEffect;"
                            .equals(invoke.desc)) {
                        callsGetActivePotionEffect = true;
                    }
                    if ("net/minecraft/potion/PotionEffect".equals(invoke.owner)
                            && "func_76458_c".equals(invoke.name)
                            && "()I".equals(invoke.desc)) {
                        callsGetAmplifier = true;
                    }
                    if ("net/minecraftforge/fml/common/eventhandler/EventBus"
                            .equals(invoke.owner)
                            && "post".equals(invoke.name)
                            && "(Lnet/minecraftforge/fml/common/eventhandler/Event;)Z"
                            .equals(invoke.desc)) {
                        jumpEventPostIndex = i;
                    }
                    if ("mindless/runtime/LunarEventBridge".equals(invoke.owner)
                            && "onLivingJump".equals(invoke.name)
                            && "(Lnet/minecraft/entity/EntityLivingBase;)V".equals(invoke.desc)) {
                        routesLivingJumpThroughBridge = true;
                    }
                    if ("net/minecraftforge/common/ForgeHooks".equals(invoke.owner)) {
                        callsForgeHooks = true;
                    }
                    if (("java/lang/Class".equals(invoke.owner)
                            && ("getMethod".equals(invoke.name)
                            || "getDeclaredMethod".equals(invoke.name)))
                            || ("java/lang/reflect/Method".equals(invoke.owner)
                            && "invoke".equals(invoke.name))) {
                        usesReflection = true;
                    }
                } else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode field = (FieldInsnNode) instruction;
                    if (instruction.getOpcode() == Opcodes.GETFIELD
                            && "net/minecraft/client/Minecraft".equals(field.owner)
                            && "field_71439_g".equals(field.name)
                            && "Lnet/minecraft/client/entity/EntityPlayerSP;".equals(field.desc)) {
                        localPlayerFieldIndex = i;
                    }
                } else if (instruction instanceof TypeInsnNode) {
                    TypeInsnNode type = (TypeInsnNode) instruction;
                    if (instruction.getOpcode() == Opcodes.NEW
                            && "mindless/event/JumpEvent".equals(type.desc)) {
                        jumpEventIndex = i;
                    }
                }
                if (localPlayerFieldIndex >= 0 && localPlayerGuardIndex < 0
                        && (instruction.getOpcode() == Opcodes.IF_ACMPEQ
                        || instruction.getOpcode() == Opcodes.IF_ACMPNE)) {
                    localPlayerGuardIndex = i;
                }
                if (jumpEventIndex >= 0 && jumpEventPostGuardIndex < 0
                        && (instruction.getOpcode() == Opcodes.IFEQ
                        || instruction.getOpcode() == Opcodes.IFNE)) {
                    jumpEventPostGuardIndex = i;
                }
            }

            Assert.assertTrue("SRG jump does not call Minecraft.func_71410_x",
                    getsMinecraftSingleton);
            Assert.assertTrue("SRG jump does not read Minecraft.field_71439_g",
                    localPlayerFieldIndex >= 0);
            Assert.assertTrue("SRG jump does not reference-guard the local player",
                    localPlayerGuardIndex > localPlayerFieldIndex);
            Assert.assertTrue("SRG jump does not construct JumpEvent", jumpEventIndex >= 0);
            Assert.assertTrue("JumpEvent post guard does not follow the local-player comparison",
                    jumpEventPostGuardIndex > localPlayerGuardIndex);
            Assert.assertTrue("JumpEvent post is not behind a boolean local-player guard",
                    jumpEventPostGuardIndex > jumpEventIndex);
            Assert.assertTrue("Guarded jump path does not post JumpEvent to Forge",
                    jumpEventPostIndex > jumpEventPostGuardIndex);
            Assert.assertTrue("SRG jump does not call EntityLivingBase.func_70644_a",
                    callsIsPotionActive);
            Assert.assertTrue("SRG jump does not call EntityLivingBase.func_70660_b",
                    callsGetActivePotionEffect);
            Assert.assertTrue("SRG jump does not call PotionEffect.func_76458_c",
                    callsGetAmplifier);
            Assert.assertTrue("SRG jump does not route LivingJumpEvent through the safe bridge",
                    routesLivingJumpThroughBridge);
            Assert.assertFalse("SRG jump still invokes ForgeHooks directly", callsForgeHooks);
            Assert.assertFalse("SRG jump still uses reflective potion lookup", usesReflection);
            manager.assertNoTransformFailures();
        }
    }

    @Test
    public void entityPlayerAttackRoutesForgeHookThroughRuntimeBridge() throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(), minecraft, forge);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            byte[] original = readClass("net/minecraft/entity/player/EntityPlayer",
                    minecraft, forge);
            Assert.assertNotNull("SRG EntityPlayer is missing", original);

            byte[] transformed = manager.transform(
                    "net/minecraft/entity/player/EntityPlayer", original);
            Assert.assertNotNull("EntityPlayer transformer returned null", transformed);
            Assert.assertNull("EntityPlayer changed retransformation schema",
                    MindlessTransformerManager.findRetransformSchemaChange(original, transformed));
            Assert.assertNull("EntityPlayer contains a dangling self method reference",
                    manager.findDanglingSelfMethodReference(transformed));

            ClassNode node = new ClassNode();
            new ClassReader(transformed).accept(node,
                    ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            MethodNode bridgedAttack = null;
            for (MethodNode method : node.methods) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode invoke = (MethodInsnNode) instruction;
                    if ("mindless/runtime/LunarEventBridge".equals(invoke.owner)
                            && "onPlayerAttackTarget".equals(invoke.name)
                            && "(Lnet/minecraft/entity/player/EntityPlayer;"
                            .concat("Lnet/minecraft/entity/Entity;)Z").equals(invoke.desc)) {
                        bridgedAttack = method;
                    }
                }
            }

            Assert.assertTrue("SRG attack does not route through the safe bridge",
                    bridgedAttack != null);
            for (AbstractInsnNode instruction : bridgedAttack.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode invoke = (MethodInsnNode) instruction;
                Assert.assertFalse("Transformed SRG attack still invokes ForgeHooks directly",
                        "net/minecraftforge/common/ForgeHooks".equals(invoke.owner));
            }
            manager.assertNoTransformFailures();
        }
    }

    @Test
    public void renderGlobalOptiFineM5RedirectIsRetransformCompatible() throws Exception {
        Path optiFinePath = findInstalledOptiFineM5();
        Assume.assumeTrue("OptiFine M5 is not installed", optiFinePath != null);

        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile());
             JarFile optiFine = new JarFile(optiFinePath.toFile())) {
            byte[] installedRenderGlobal = readClass("bfr", optiFine);
            Assert.assertNotNull("OptiFine M5 RenderGlobal (bfr.class) is missing",
                    installedRenderGlobal);
            Assert.assertTrue("Installed OptiFine M5 no longer uses Reflector.callBoolean",
                    countReflectorCallBoolean(installedRenderGlobal) >= 3);

            byte[] vanilla = readClass("net/minecraft/client/renderer/RenderGlobal",
                    minecraft, forge);
            Assert.assertNotNull(vanilla);
            byte[] optiFineShape = replaceRenderPassCallsWithOptiFineShape(vanilla);

            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(),
                    minecraft, forge, optiFine);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            byte[] transformed = manager.transform(
                    "net/minecraft/client/renderer/RenderGlobal", optiFineShape);

            Assert.assertNotNull("OptiFine RenderGlobal redirect returned null", transformed);
            Assert.assertNull("OptiFine RenderGlobal changed retransformation schema",
                    MindlessTransformerManager.findRetransformSchemaChange(
                            optiFineShape, transformed));
            Assert.assertNull("OptiFine RenderGlobal contains a dangling self method reference",
                    manager.findDanglingSelfMethodReference(transformed));
            manager.assertNoTransformFailures();
        }
    }

    @Test
    public void renderWorldLastBridgeRunsBeforeFirstPersonHand() throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            MindlessTransformerManager manager = new MindlessTransformerManager(
                    new SrgFirstClassProvider(
                            TransformerCompatibilityTest.class.getClassLoader(),
                            minecraft, forge));
            byte[] original = readClass(
                    "net/minecraft/client/renderer/EntityRenderer",
                    minecraft, forge);
            Assert.assertNotNull("SRG EntityRenderer is missing", original);
            byte[] transformed = manager.transform(
                    "net/minecraft/client/renderer/EntityRenderer", original);
            Assert.assertNotNull("EntityRenderer transformer returned null", transformed);
            assertWorldLastBeforeHand(transformed,
                    "func_175068_a", "field_175074_C");
        }

        Path minecraftMcp = findEssentialLoomJar("minecraft-mapped.jar");
        Path forgeMcp = findEssentialLoomJar("forge-mapped.jar");
        try (JarFile minecraft = new JarFile(minecraftMcp.toFile());
             JarFile forge = new JarFile(forgeMcp.toFile())) {
            MindlessTransformerManager manager = new MindlessTransformerManager(
                    new SrgFirstClassProvider(
                            TransformerCompatibilityTest.class.getClassLoader(),
                            minecraft, forge));
            byte[] original = readClass(
                    "net/minecraft/client/renderer/EntityRenderer",
                    minecraft, forge);
            Assert.assertNotNull("MCP EntityRenderer is missing", original);
            byte[] transformed = manager.transform(
                    "net/minecraft/client/renderer/EntityRenderer", original);
            Assert.assertNotNull("EntityRenderer transformer returned null", transformed);
            assertWorldLastBeforeHand(transformed,
                    "renderWorldPass", "renderHand");
        }
    }

    @Test
    @Ignore("Mindless does not include Pigeon's ephemeral alt-session subsystem")
    public void minecraftSessionGetterRoutesThroughEphemeralAltOverride()
            throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            MindlessTransformerManager manager = new MindlessTransformerManager(
                    new SrgFirstClassProvider(
                            TransformerCompatibilityTest.class.getClassLoader(),
                            minecraft, forge));
            byte[] original = readClass("net/minecraft/client/Minecraft",
                    minecraft, forge);
            Assert.assertNotNull("SRG Minecraft is missing", original);
            assertSessionOverrideCall(manager.transform(
                    "net/minecraft/client/Minecraft", original),
                    "func_110432_I");
        }

        Path minecraftMcp = findEssentialLoomJar("minecraft-mapped.jar");
        Path forgeMcp = findEssentialLoomJar("forge-mapped.jar");
        try (JarFile minecraft = new JarFile(minecraftMcp.toFile());
             JarFile forge = new JarFile(forgeMcp.toFile())) {
            MindlessTransformerManager manager = new MindlessTransformerManager(
                    new SrgFirstClassProvider(
                            TransformerCompatibilityTest.class.getClassLoader(),
                            minecraft, forge));
            byte[] original = readClass("net/minecraft/client/Minecraft",
                    minecraft, forge);
            Assert.assertNotNull("MCP Minecraft is missing", original);
            assertSessionOverrideCall(manager.transform(
                    "net/minecraft/client/Minecraft", original),
                    "getSession");
        }
    }

    private static void assertSessionOverrideCall(byte[] transformed,
                                                  String getterName) {
        Assert.assertNotNull("Minecraft transformer returned null", transformed);
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int calls = 0;
        for (MethodNode method : node.methods) {
            if (!getterName.equals(method.name)
                    || !"()Lnet/minecraft/util/Session;".equals(method.desc)) {
                continue;
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode invoke = (MethodInsnNode) instruction;
                if ("mindless/alt/AltSessionController".equals(invoke.owner)
                        && "resolveSession".equals(invoke.name)
                        && "(Lnet/minecraft/util/Session;)Lnet/minecraft/util/Session;"
                        .equals(invoke.desc)) {
                    calls++;
                }
            }
        }
        Assert.assertEquals("getSession must route through one alt override hook",
                1, calls);
    }

    private static void assertWorldLastBeforeHand(byte[] transformed,
                                                   String methodName,
                                                   String handFieldName) {
        ClassNode node = new ClassNode();
        new ClassReader(transformed).accept(node,
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode renderWorldPass = null;
        for (MethodNode method : node.methods) {
            if (methodName.equals(method.name)
                    && "(IFJ)V".equals(method.desc)) {
                renderWorldPass = method;
                break;
            }
        }
        Assert.assertNotNull("EntityRenderer." + methodName + " is missing",
                renderWorldPass);

        int bridgeIndex = -1;
        int bridgeCount = 0;
        int handFieldIndex = -1;
        AbstractInsnNode[] instructions = renderWorldPass.instructions.toArray();
        for (int i = 0; i < instructions.length; i++) {
            AbstractInsnNode instruction = instructions[i];
            if (instruction instanceof MethodInsnNode) {
                MethodInsnNode invoke = (MethodInsnNode) instruction;
                if ("mindless/runtime/LunarEventBridge".equals(invoke.owner)
                        && "postRenderWorld".equals(invoke.name)
                        && "(F)V".equals(invoke.desc)) {
                    bridgeIndex = i;
                    bridgeCount++;
                }
            }
            else if (instruction instanceof FieldInsnNode) {
                FieldInsnNode field = (FieldInsnNode) instruction;
                if (instruction.getOpcode() == Opcodes.GETFIELD
                        && "net/minecraft/client/renderer/EntityRenderer"
                        .equals(field.owner)
                        && handFieldName.equals(field.name)
                        && "Z".equals(field.desc)) {
                    handFieldIndex = i;
                }
            }
        }
        Assert.assertEquals("RenderWorldLast must be emitted exactly once",
                1, bridgeCount);
        Assert.assertTrue("renderHand field read is missing",
                handFieldIndex >= 0);
        Assert.assertTrue("RenderWorldLast runs after first-person hand setup",
                bridgeIndex >= 0 && bridgeIndex < handFieldIndex);
    }

    @Test
    public void danglingSelfMethodReferenceIsDetectedBeforeJvmti() throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        Path forgeSrg = findEssentialLoomJar("forge-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile());
             JarFile forge = new JarFile(forgeSrg.toFile())) {
            IClassProvider provider = new SrgFirstClassProvider(
                    TransformerCompatibilityTest.class.getClassLoader(), minecraft, forge);
            MindlessTransformerManager manager = new MindlessTransformerManager(provider);
            byte[] original = readClass("net/minecraft/client/renderer/ItemRenderer",
                    minecraft, forge);
            Assert.assertNotNull(original);

            ClassNode node = new ClassNode();
            new ClassReader(original).accept(node, 0);
            MethodNode renderHand = null;
            for (MethodNode method : node.methods) {
                if ("func_78440_a".equals(method.name) && "(F)V".equals(method.desc)) {
                    renderHand = method;
                    break;
                }
            }
            Assert.assertNotNull("SRG ItemRenderer.renderItemInFirstPerson is missing",
                    renderHand);

            InsnList invalidCall = new InsnList();
            invalidCall.add(new VarInsnNode(Opcodes.ALOAD, 0));
            invalidCall.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name,
                    "mindless$missingHelper", "()V", false));
            renderHand.instructions.insert(invalidCall);
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);

            String failure = manager.findDanglingSelfMethodReference(writer.toByteArray());
            Assert.assertNotNull("Dangling helper invocation was not detected", failure);
            Assert.assertTrue(failure, failure.contains("mindless$missingHelper()V"));
        }
    }

    @Test
    public void entityPlayerReflectionTargetsExistInSrgRuntime() throws Exception {
        Path minecraftSrg = findEssentialLoomJar("minecraft-srg.jar");
        try (JarFile minecraft = new JarFile(minecraftSrg.toFile())) {
            ClassNode player = readClassNode("net/minecraft/entity/player/EntityPlayer",
                    minecraft);
            ClassNode entity = readClassNode("net/minecraft/entity/Entity", minecraft);

            Assert.assertTrue("Entity.inPortal SRG field is missing",
                    hasField(entity, "field_71087_bX", "Z"));
            Assert.assertTrue("EntityPlayer.flyToggleTimer SRG field is missing",
                    hasField(player, "field_71101_bC", "I"));
            Assert.assertTrue("Entity.pushOutOfBlocks SRG method is missing",
                    hasMethod(entity, "func_145771_j", "(DDD)Z"));
        }
    }

    private static int countReflectorCallBoolean(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        int count = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode invoke = (MethodInsnNode) instruction;
                if ("net/optifine/reflect/Reflector".equals(invoke.owner)
                        && "callBoolean".equals(invoke.name)
                        && "(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;[Ljava/lang/Object;)Z"
                        .equals(invoke.desc)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static ClassNode readClassNode(String internalName, JarFile... jars)
            throws IOException {
        byte[] bytes = readClass(internalName, jars);
        Assert.assertNotNull(internalName + " is missing", bytes);
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }

    private static boolean hasField(ClassNode node, String name, String desc) {
        for (FieldNode field : node.fields) {
            if (name.equals(field.name) && desc.equals(field.desc)) return true;
        }
        return false;
    }

    private static boolean hasMethod(ClassNode node, String name, String desc) {
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return true;
        }
        return false;
    }

    private static String findInvalidLivingUpdateSuperCall(byte[] classBytes) {
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        MethodNode livingUpdate = null;
        for (MethodNode method : node.methods) {
            if (("func_70636_d".equals(method.name) || "onLivingUpdate".equals(method.name))
                    && "()V".equals(method.desc)) {
                livingUpdate = method;
                break;
            }
        }
        if (livingUpdate == null) return "onLivingUpdate override is missing";

        int directSuperCalls = 0;
        List<String> specialCalls = new ArrayList<>();
        for (AbstractInsnNode instruction : livingUpdate.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode invocation = (MethodInsnNode) instruction;
            if (instruction.getOpcode() == Opcodes.INVOKESPECIAL) {
                specialCalls.add(invocation.owner + "." + invocation.name + invocation.desc);
            }
            if (instruction.getOpcode() == Opcodes.INVOKESPECIAL
                    && node.superName.equals(invocation.owner)
                    && livingUpdate.name.equals(invocation.name)
                    && livingUpdate.desc.equals(invocation.desc)) {
                directSuperCalls++;
            }
            if ("java/lang/reflect/Method".equals(invocation.owner)
                    && "invoke".equals(invocation.name)) {
                return "onLivingUpdate still uses virtual reflective dispatch";
            }
        }
        return directSuperCalls == 1 ? null
                : "onLivingUpdate expected exactly one direct super call, found "
                + directSuperCalls + "; invokespecial calls=" + specialCalls;
    }

    private static byte[] replaceRenderPassCallsWithOptiFineShape(byte[] vanilla) {
        ClassNode node = new ClassNode();
        new ClassReader(vanilla).accept(node, 0);
        MethodNode renderEntities = null;
        for (MethodNode method : node.methods) {
            if ("func_180446_a".equals(method.name)
                    && "(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;F)V"
                    .equals(method.desc)) {
                renderEntities = method;
                break;
            }
        }
        Assert.assertNotNull("SRG RenderGlobal.renderEntities is missing", renderEntities);

        for (AbstractInsnNode instruction : renderEntities.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode invoke = (MethodInsnNode) instruction;
            if ("net/minecraft/entity/Entity".equals(invoke.owner)
                    && "shouldRenderInPass".equals(invoke.name)
                    && "(I)Z".equals(invoke.desc)) {
                InsnList replacement = new InsnList();
                replacement.add(new InsnNode(Opcodes.POP2));
                replacement.add(new InsnNode(Opcodes.ICONST_0));
                renderEntities.instructions.insertBefore(invoke, replacement);
                renderEntities.instructions.remove(invoke);
            }
        }

        InsnList optiFineCalls = new InsnList();
        for (int i = 0; i < 3; i++) {
            optiFineCalls.add(new InsnNode(Opcodes.ACONST_NULL));
            optiFineCalls.add(new InsnNode(Opcodes.ACONST_NULL));
            optiFineCalls.add(new InsnNode(Opcodes.ICONST_0));
            optiFineCalls.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
            optiFineCalls.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "net/optifine/reflect/Reflector", "callBoolean",
                    "(Ljava/lang/Object;Lnet/optifine/reflect/ReflectorMethod;[Ljava/lang/Object;)Z",
                    false));
            optiFineCalls.add(new InsnNode(Opcodes.POP));
        }
        renderEntities.instructions.insert(optiFineCalls);

        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Path findInstalledOptiFineM5() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isEmpty()) return null;
        Path minecraft = Paths.get(appData, ".minecraft");
        Path[] candidates = {
                minecraft.resolve("mods/OptiFine-OptiFine-1.8.9_HD_U_M5.jar"),
                minecraft.resolve("libraries/optifine/OptiFine/OptiFine-1.8.9_HD_U_M5/OptiFine-OptiFine-1.8.9_HD_U_M5.jar")
        };
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static Path findEssentialLoomJar(String fileName) throws IOException {
        Path root = Paths.get(System.getProperty("user.home"), ".gradle", "caches",
                "essential-loom", LOOM_VERSION_DIR);
        if (!Files.isDirectory(root)) {
            throw new IOException("Essential Loom cache directory does not exist: " + root);
        }

        List<Path> candidates = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(Files::isRegularFile)
                    .filter(path -> fileName.equals(path.getFileName().toString()))
                    .filter(path -> path.toString().contains(EXPECTED_CACHE_FRAGMENT))
                    .forEach(candidates::add);
        }
        Collections.sort(candidates);
        if (candidates.isEmpty()) {
            throw new IOException("Could not find " + fileName + " for MCP stable 22 / Forge "
                    + FORGE_VERSION + " under " + root
                    + ". Run a Loom setup/build once to populate the SRG cache.");
        }
        return candidates.get(0);
    }

    private static byte[] readClass(String internalName, JarFile... jars) throws IOException {
        String entryName = internalName + ".class";
        for (JarFile jar : jars) {
            JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) continue;
            try (InputStream input = jar.getInputStream(entry)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        entry.getSize() > 0 && entry.getSize() <= Integer.MAX_VALUE
                                ? (int) entry.getSize() : 4096);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        }
        return null;
    }

    /**
     * Supplies Minecraft/Forge classes from the SRG jars before consulting the
     * ordinary test classpath. The ordering matters: SrgMapper's InfoFiller
     * must inspect members in the same namespace as the target bytecode.
     */
    private static final class SrgFirstClassProvider implements IClassProvider {
        private final JarFile[] srgJars;
        private final LaunchClassProvider classpathFallback;
        private final Map<String, byte[]> cache = new HashMap<>();

        private SrgFirstClassProvider(ClassLoader loader, JarFile... srgJars) {
            this.srgJars = srgJars.clone();
            this.classpathFallback = new LaunchClassProvider(loader);
        }

        @Override
        public byte[] getClass(String name) throws ClassNotFoundException {
            String binaryName = name.replace('/', '.');
            synchronized (cache) {
                byte[] cached = cache.get(binaryName);
                if (cached != null) return cached;
            }

            try {
                byte[] srgBytes = readClass(binaryName.replace('.', '/'), srgJars);
                if (srgBytes != null) {
                    synchronized (cache) {
                        cache.put(binaryName, srgBytes);
                    }
                    return srgBytes;
                }
            } catch (IOException failure) {
                throw new ClassNotFoundException("Could not read SRG bytecode for " + binaryName,
                        failure);
            }
            return classpathFallback.getClass(binaryName);
        }

        @Override
        public Map<String, Supplier<byte[]>> getAllClasses() {
            Map<String, Supplier<byte[]>> classes = new LinkedHashMap<>(
                    classpathFallback.getAllClasses());
            for (JarFile jar : srgJars) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();
                    if (entry.isDirectory() || !entryName.endsWith(".class")) continue;
                    final String binaryName = entryName.substring(0, entryName.length() - 6)
                            .replace('/', '.');
                    classes.put(binaryName, new Supplier<byte[]>() {
                        @Override
                        public byte[] get() {
                            try {
                                return SrgFirstClassProvider.this.getClass(binaryName);
                            } catch (ClassNotFoundException failure) {
                                throw new IllegalStateException(failure);
                            }
                        }
                    });
                }
            }
            return classes;
        }
    }

    private static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 6) {
            if (result.length() > 0) result.append(" -> ");
            result.append(current.getClass().getName());
            if (current.getMessage() != null) result.append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return result.toString();
    }

    private static String buildFailureMessage(List<String> failures,
                                              Path minecraftSrg,
                                              Path forgeSrg) {
        StringBuilder message = new StringBuilder("Transformer compatibility check failed")
                .append("\nMinecraft SRG: ").append(minecraftSrg)
                .append("\nForge SRG: ").append(forgeSrg);
        for (String failure : failures) {
            message.append("\n - ").append(failure);
        }
        return message.toString();
    }
}
