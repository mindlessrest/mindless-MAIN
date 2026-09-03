package mindless.runtime;

import net.lenni0451.classtransform.TransformerManager;
import net.lenni0451.classtransform.mappings.AMapper;
import net.lenni0451.classtransform.mappings.MapperConfig;
import net.lenni0451.classtransform.mappings.impl.SrgMapper;
import net.lenni0451.classtransform.utils.FailStrategy;
import net.lenni0451.classtransform.utils.ASMUtils;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
public final class MindlessTransformerManager {
    enum RuntimeNamespace {
        MCP,
        SRG
    }

    enum RuntimeProfile {
        FORGE,
        LUNAR,
        GENERIC
    }

    private static final Object LOCK = new Object();
    private static volatile MindlessTransformerManager INSTANCE;

    private final TransformerManager delegate;
    private final AMapper mapper;
    private final RuntimeNamespace runtimeNamespace;
    private final RuntimeProfile runtimeProfile;
    private final Object delegateTransformLock = new Object();
    private final IClassProvider classProvider;
    private final Set<String> targetInternalNames;
    private final String entityPlayerSpCanonicalName;
    private final String superCallMarkerDesc;
    private final Map<String, String> transformFailures =
            Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private final TransformVerifier verifier;
private static final boolean STRICT_VERIFY =
            Boolean.getBoolean("mindless.strictTransformVerify");
private static final Set<String> REQUIRED_TARGETS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList("net.minecraft.client.Minecraft")));
static void fileLog(String message) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "MindlessNative");
            dir.mkdirs();
            try (PrintWriter w = new PrintWriter(
                    new FileOutputStream(new File(dir, "mindless-transformer.log"), true), true)) {
                w.println("[" + new java.util.Date() + "] " + message);
            }
        } catch (Throwable ignored) {}
        System.out.println(message);
    }

    private static void dumpClassBytes(String canonicalName, byte[] bytes) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "MindlessNative/classdump");
            dir.mkdirs();
            String fileName = canonicalName.replace('.', '/') + ".class";
            File out = new File(dir, fileName);
            out.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
        } catch (Throwable ignored) {}
    }

    private MindlessTransformerManager() {
        this(new LaunchClassProvider(MindlessTransformerManager.class.getClassLoader()));
    }
MindlessTransformerManager(IClassProvider provider) {
        this(provider, detectRuntimeNamespace(provider), detectRuntimeProfile(provider));
    }
MindlessTransformerManager(IClassProvider provider, RuntimeNamespace runtimeNamespace) {
        this(provider, runtimeNamespace, RuntimeProfile.GENERIC);
    }

    MindlessTransformerManager(IClassProvider provider, RuntimeNamespace runtimeNamespace,
                            RuntimeProfile runtimeProfile) {
        if (provider == null) throw new IllegalArgumentException("provider");
        if (runtimeNamespace == null) throw new IllegalArgumentException("runtimeNamespace");
        if (runtimeProfile == null) throw new IllegalArgumentException("runtimeProfile");
        this.classProvider = provider;
        this.runtimeNamespace = runtimeNamespace;
        this.runtimeProfile = runtimeProfile;
        ClassLoader loader = MindlessTransformerManager.class.getClassLoader();
        AMapper selectedMapper = null;
        if (runtimeNamespace == RuntimeNamespace.SRG) {
            InputStream mappingsStream = loader.getResourceAsStream("mindless-mappings.srg");
            if (mappingsStream == null) {
                throw new IllegalStateException(
                        "mindless-mappings.srg is required for the Forge/SRG runtime");
            }
            try {
                selectedMapper = new SrgMapper(
                        MapperConfig.create().remapTransformer(true).fillSuperMappings(true),
                        mappingsStream);
                fileLog("[MindlessTransformer] runtime namespace SRG; "
                        + "loaded mindless-mappings.srg");
            } catch (Throwable failure) {
                fileLog("[MindlessTransformer-ERR] SrgMapper init failed: " + failure);
                throw new IllegalStateException("Could not initialize Forge/SRG mappings", failure);
            }
        } else {
            fileLog("[MindlessTransformer] runtime namespace MCP "
                    + "(Lunar/deobfuscated); using identity mappings");
        }

        this.mapper = selectedMapper;
        this.delegate = selectedMapper != null
                ? new TransformerManager(provider, selectedMapper)
                : new TransformerManager(provider);
        this.delegate.setFailStrategy(FailStrategy.THROW);
        try {
            java.lang.reflect.Field treeField = TransformerManager.class.getDeclaredField("classTree");
            treeField.setAccessible(true);
            treeField.set(this.delegate,
                    new net.lenni0451.classtransform.utils.tree.ClassTree());
            fileLog("[MindlessTransformer] installed non-transforming ClassTree (reentrance guard)");
        } catch (Throwable failure) {
            fileLog("[MindlessTransformer] ClassTree swap failed: " + failure);
        }
        this.verifier = new TransformVerifier(provider, new TransformVerifier.Log() {
            public void warn(String message) {
                fileLog("[MindlessTransformer-WARN] " + message);
            }
        });
        this.targetInternalNames = new LinkedHashSet<>();
        this.entityPlayerSpCanonicalName = mapClassName(ENTITY_PLAYER_SP);
        this.superCallMarkerDesc = "(L"
                + entityPlayerSpCanonicalName.replace('.', '/') + ";)V";
        registerAll();
    }

    private static RuntimeProfile detectRuntimeProfile(IClassProvider provider) {
        String override = System.getProperty("mindless.runtimeProfile");
        if (override != null) {
            String normalized = override.trim().toLowerCase(java.util.Locale.ROOT);
            if ("lunar".equals(normalized)) return RuntimeProfile.LUNAR;
            if ("forge".equals(normalized)) return RuntimeProfile.FORGE;
            if ("generic".equals(normalized) || "deobf".equals(normalized)) {
                return RuntimeProfile.GENERIC;
            }
            throw new IllegalArgumentException(
                    "Unsupported mindless.runtimeProfile value: " + override);
        }
        try {
            byte[] marker = provider.getClass("com.lunarclient.ApiUtils");
            if (marker != null && marker.length > 0) return RuntimeProfile.LUNAR;
        } catch (Throwable ignored) {
        }
        return RuntimeProfile.GENERIC;
    }

    private static RuntimeNamespace detectRuntimeNamespace(IClassProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider");

        String override = System.getProperty("mindless.runtimeNamespace");
        if (override != null) {
            String normalized = override.trim().toLowerCase(java.util.Locale.ROOT);
            if ("mcp".equals(normalized) || "named".equals(normalized)
                    || "lunar".equals(normalized)) {
                return RuntimeNamespace.MCP;
            }
            if ("srg".equals(normalized) || "forge".equals(normalized)) {
                return RuntimeNamespace.SRG;
            }
            throw new IllegalArgumentException(
                    "Unsupported mindless.runtimeNamespace value: " + override);
        }

        try {
            byte[] minecraftBytes = provider.getClass("net.minecraft.client.Minecraft");
            ClassNode minecraft = ASMUtils.fromBytes(minecraftBytes);
            boolean hasMcpClickMouse = false;
            boolean hasSrgClickMouse = false;
            for (MethodNode method : minecraft.methods) {
                if (!"()V".equals(method.desc)) continue;
                if ("clickMouse".equals(method.name)) hasMcpClickMouse = true;
                if ("func_147116_af".equals(method.name)) hasSrgClickMouse = true;
            }
            if (hasMcpClickMouse && !hasSrgClickMouse) return RuntimeNamespace.MCP;
            if (hasSrgClickMouse && !hasMcpClickMouse) return RuntimeNamespace.SRG;
            throw new IllegalStateException("Minecraft exposes "
                    + (hasMcpClickMouse ? "both" : "neither")
                    + " MCP/SRG clickMouse names");
        } catch (Throwable failure) {
            fileLog("[MindlessTransformer] could not detect runtime namespace from "
                    + "Minecraft.clickMouse; defaulting to SRG for Forge compatibility: "
                    + failure);
            return RuntimeNamespace.SRG;
        }
    }

    private String mapClassName(String canonicalName) {
        return mapper == null ? canonicalName : mapper.mapClassName(canonicalName);
    }

    RuntimeNamespace runtimeNamespace() {
        return runtimeNamespace;
    }

    RuntimeProfile runtimeProfile() {
        return runtimeProfile;
    }

    public static MindlessTransformerManager get() {
        MindlessTransformerManager local = INSTANCE;
        if (local != null) return local;
        synchronized (LOCK) {
            if (INSTANCE == null) INSTANCE = new MindlessTransformerManager();
            return INSTANCE;
        }
    }

    public Set<String> targetInternalNames() {
        return Collections.unmodifiableSet(targetInternalNames);
    }
/** Tracks class names currently being transformed on THIS thread.
     * ClassTransform reenters transform(...) via ClassTree.getTreePart when
     * ASM needs to resolve super classes for frame computation. That
     * reentrant call re-runs every @CInject handler with a mid-state
     * TransformerManager — injectionTargets get lost and CInjectAnnotationHandler
     * NPEs. We short-circuit reentrant hits so the outer call keeps going. */
    private static final ThreadLocal<java.util.Set<String>> IN_FLIGHT =
            new ThreadLocal<java.util.Set<String>>() {
                @Override protected java.util.Set<String> initialValue() {
                    return new java.util.HashSet<String>();
                }
            };

    private volatile boolean disabled = false;

    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }

    public byte[] transform(String internalName, byte[] originalBytes) {
        if (disabled || internalName == null || originalBytes == null) return null;
        String canonicalName = internalName.replace('/', '.');
        if (!targetInternalNames.contains(internalName)) return null;
        java.util.Set<String> inFlight = IN_FLIGHT.get();
        if (!inFlight.add(canonicalName)) {
            fileLog("[MindlessTransformer] " + canonicalName
                    + " -> SKIPPED (already transforming on this thread)");
            return null;
        }
        try {
            dumpClassBytes(canonicalName, originalBytes);
            byte[] result;
            synchronized (delegateTransformLock) {
                result = delegate.transform(canonicalName, originalBytes);
            }
            if (result != null && result != originalBytes) {
                result = rewriteRequiredSpecialCalls(canonicalName, result);
                result = restoreOriginalVisibility(originalBytes, result);
                String schemaChange = findRetransformSchemaChange(originalBytes, result);
                if (schemaChange != null) {
                    transformFailures.put(canonicalName, "schema changed: " + schemaChange);
                    fileLog("[MindlessTransformer-ERR] rejected " + canonicalName
                            + " before JVMTI: retransformation schema changed ("
                            + schemaChange + ")");
                    return null;
                }
                String suspect = verifier.verify(originalBytes, result);
                if (suspect != null) {
                    String note = "[MindlessTransformer-WARN] " + canonicalName
                            + " looks questionable but was applied anyway: " + suspect;
                    fileLog(note);
                    if (STRICT_VERIFY) {
                        transformFailures.put(canonicalName, suspect);
                        fileLog("[MindlessTransformer-ERR] rejected " + canonicalName
                                + " before JVMTI (mindless.strictTransformVerify): " + suspect);
                        return null;
                    }
                }
            }
            if (result == null || result == originalBytes || result.length == originalBytes.length) {
                if (result == null || result == originalBytes) {
                    transformFailures.put(canonicalName, "transformer returned no changed bytecode");
                    fileLog("[MindlessTransformer] " + canonicalName + " -> NO CHANGE (transformer likely inert)");
                    return null;
                }
                boolean identical = java.util.Arrays.equals(result, originalBytes);
                if (identical) {
                    transformFailures.put(canonicalName, "transformer returned identical bytecode");
                    fileLog("[MindlessTransformer] " + canonicalName + " -> NO CHANGE (equal bytes)");
                    return null;
                }
            }
            transformFailures.remove(canonicalName);
            fileLog("[MindlessTransformer] " + canonicalName
                    + " -> transformed (" + originalBytes.length + " -> " + result.length + " bytes)");
            return result;
        } catch (Throwable failure) {
            transformFailures.put(canonicalName, failure.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(sw));
            Throwable cur = failure.getCause();
            int depth = 0;
            while (cur != null && depth < 6) {
                sw.append("Caused by: ").append(cur.toString()).append('\n');
                for (StackTraceElement st : cur.getStackTrace()) {
                    sw.append("    at ").append(st.toString()).append('\n');
                    if (++depth > 6) break;
                }
                cur = cur.getCause();
            }
            fileLog("[MindlessTransformer-ERR] failed to transform " + canonicalName
                    + ":\n" + sw.toString());
            return null;
        } finally {
            inFlight.remove(canonicalName);
        }
    }
void assertNoTransformFailures() {
        Map<String, String> failures;
        synchronized (transformFailures) {
            if (transformFailures.isEmpty()) return;
            failures = new LinkedHashMap<String, String>(transformFailures);
            transformFailures.clear();
        }

        Map<String, String> required = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> failure : failures.entrySet()) {
            String target = failure.getKey();
            boolean isRequired = REQUIRED_TARGETS.contains(target)
                    || REQUIRED_TARGETS.contains(stripRegistrationPrefix(target));
            String line = (isRequired ? "[MindlessTransformer-ERR] REQUIRED " : "[MindlessTransformer-WARN] skipped ")
                    + target + ": " + failure.getValue();
            fileLog(line);
            System.out.println(line);
            if (isRequired) required.put(target, failure.getValue());
        }

        if (!required.isEmpty()) {
            String message = "Required transformer targets did not apply to this game build: "
                    + required + " -- the client will start but its hooks into those classes are "
                    + "missing";
            fileLog("[MindlessTransformer-ERR] " + message);
            System.out.println("[MindlessTransformer-ERR] " + message);
        }
    }

    private static String stripRegistrationPrefix(String key) {
        return key.startsWith("registration:") ? key.substring("registration:".length()) : key;
    }
static String findRetransformSchemaChange(byte[] originalBytes, byte[] transformedBytes) {
        try {
            ClassNode before = ASMUtils.fromBytes(originalBytes);
            ClassNode after = ASMUtils.fromBytes(transformedBytes);
            if (!safeEquals(before.name, after.name)) return "class name";
            if (!safeEquals(before.superName, after.superName)) return "super class";
            if (!before.interfaces.equals(after.interfaces)) return "interfaces";
            if ((before.access & CLASS_SCHEMA_ACCESS) != (after.access & CLASS_SCHEMA_ACCESS)) {
                return "class modifiers";
            }

            String fields = compareFields(before.fields, after.fields);
            if (fields != null) return fields;
            String methods = compareMethods(before.methods, after.methods);
            if (methods != null) return methods;
            return null;
        } catch (Throwable failure) {
            return "schema preflight failed: " + failure;
        }
    }
static byte[] restoreOriginalVisibility(byte[] originalBytes,
                                            byte[] transformedBytes) {
        ClassReader originalReader = new ClassReader(originalBytes);
        ClassReader transformedReader = new ClassReader(transformedBytes);
        ClassNode before = new ClassNode();
        ClassNode after = new ClassNode();
        originalReader.accept(before, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG
                | ClassReader.SKIP_FRAMES);
        transformedReader.accept(after, 0);

        after.access = restoreAccess(after.access, before.access, VISIBILITY_ACCESS);
        Map<String, FieldNode> fields = new LinkedHashMap<>();
        for (FieldNode field : before.fields) fields.put(field.name + field.desc, field);
        for (FieldNode field : after.fields) {
            FieldNode original = fields.get(field.name + field.desc);
            if (original != null) {
                field.access = restoreAccess(field.access, original.access,
                        VISIBILITY_ACCESS);
            }
        }

        Map<String, MethodNode> methods = new LinkedHashMap<>();
        for (MethodNode method : before.methods) methods.put(method.name + method.desc, method);
        for (MethodNode method : after.methods) {
            MethodNode original = methods.get(method.name + method.desc);
            if (original != null) {
                method.access = restoreAccess(method.access, original.access,
                        VISIBILITY_ACCESS);
            }
        }

        ClassWriter writer = new ClassWriter(transformedReader, 0);
        after.accept(writer);
        return writer.toByteArray();
    }

    private static int restoreAccess(int transformed, int original, int mask) {
        return (transformed & ~mask) | (original & mask);
    }
byte[] rewriteRequiredSpecialCalls(String canonicalName, byte[] classBytes) {
        if (!entityPlayerSpCanonicalName.equals(canonicalName)) return classBytes;

        ClassReader reader = new ClassReader(classBytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        if (node.superName == null) {
            throw new IllegalStateException("EntityPlayerSP has no direct superclass");
        }

        int replacements = 0;
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode invocation = (MethodInsnNode) instruction;
                if (!SUPER_CALL_MARKER_OWNER.equals(invocation.owner)
                        || !SUPER_CALL_MARKER_NAME.equals(invocation.name)) {
                    continue;
                }
                if (invocation.getOpcode() != Opcodes.INVOKESTATIC
                        || !superCallMarkerDesc.equals(invocation.desc)
                        || !"()V".equals(method.desc)) {
                    throw new IllegalStateException("invalid super-call marker in "
                            + method.name + method.desc);
                }

                invocation.setOpcode(Opcodes.INVOKESPECIAL);
                invocation.owner = node.superName;
                invocation.name = method.name;
                invocation.desc = method.desc;
                invocation.itf = false;
                replacements++;
            }
        }
        if (replacements > 1) {
            throw new IllegalStateException("expected at most one EntityPlayerSP super-call "
                    + "marker, found " + replacements);
        }

        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return writer.toByteArray();
    }
String findDanglingSelfMethodReference(byte[] transformedBytes) {
        return findDanglingSelfMethodReference(transformedBytes, null);
    }

    String findDanglingSelfMethodReference(byte[] transformedBytes, byte[] originalBytes) {
        try {
            ClassNode node = ASMUtils.fromBytes(transformedBytes);
            Set<String> preExistingCalls = new HashSet<>();
            if (originalBytes != null) {
                ClassNode original = ASMUtils.fromBytes(originalBytes);
                for (MethodNode caller : original.methods) {
                    for (AbstractInsnNode instruction : caller.instructions.toArray()) {
                        if (!(instruction instanceof MethodInsnNode)) continue;
                        MethodInsnNode inv = (MethodInsnNode) instruction;
                        if (original.name.equals(inv.owner)) {
                            preExistingCalls.add(inv.name + inv.desc);
                        }
                    }
                }
            }
            for (MethodNode caller : node.methods) {
                for (AbstractInsnNode instruction : caller.instructions.toArray()) {
                    if (!(instruction instanceof MethodInsnNode)) continue;
                    MethodInsnNode invocation = (MethodInsnNode) instruction;
                    if (!node.name.equals(invocation.owner)) continue;
                    if (preExistingCalls.contains(invocation.name + invocation.desc)) continue;
                    if (!methodExistsInHierarchy(node, invocation.name, invocation.desc,
                            new HashSet<String>())) {
                        return caller.name + caller.desc + " -> " + invocation.owner + "."
                                + invocation.name + invocation.desc;
                    }
                }
            }
            return null;
        } catch (Throwable failure) {
            return "reference preflight failed: " + failure;
        }
    }

    private boolean methodExistsInHierarchy(ClassNode node, String name, String desc,
                                             Set<String> visited)
            throws ClassNotFoundException {
        if (node == null || !visited.add(node.name)) return false;
        for (MethodNode method : node.methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) return true;
        }

        if (node.superName != null) {
            ClassNode parent = loadClassNode(node.superName);
            if (methodExistsInHierarchy(parent, name, desc, visited)) return true;
        }
        for (String interfaceName : node.interfaces) {
            ClassNode parent = loadClassNode(interfaceName);
            if (methodExistsInHierarchy(parent, name, desc, visited)) return true;
        }
        return false;
    }

    private ClassNode loadClassNode(String internalName) throws ClassNotFoundException {
        return ASMUtils.fromBytes(classProvider.getClass(internalName.replace('/', '.')));
    }

    private static String compareFields(List<FieldNode> before, List<FieldNode> after) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (FieldNode field : before) {
            expected.put(field.name + field.desc, field.access & FIELD_SCHEMA_ACCESS);
        }
        for (FieldNode field : after) {
            actual.put(field.name + field.desc, field.access & FIELD_SCHEMA_ACCESS);
        }
        if (!expected.keySet().equals(actual.keySet())) {
            Set<String> added = new LinkedHashSet<>(actual.keySet());
            added.removeAll(expected.keySet());
            Set<String> removed = new LinkedHashSet<>(expected.keySet());
            removed.removeAll(actual.keySet());
            return "fields added=" + added + ", removed=" + removed;
        }
        for (String key : expected.keySet()) {
            if (!expected.get(key).equals(actual.get(key))) return "field modifiers: " + key;
        }
        return null;
    }

    private static String compareMethods(List<MethodNode> before, List<MethodNode> after) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (MethodNode method : before) {
            expected.put(method.name + method.desc, method.access & METHOD_SCHEMA_ACCESS);
        }
        for (MethodNode method : after) {
            actual.put(method.name + method.desc, method.access & METHOD_SCHEMA_ACCESS);
        }
        if (!expected.keySet().equals(actual.keySet())) {
            Set<String> added = new LinkedHashSet<>(actual.keySet());
            added.removeAll(expected.keySet());
            Set<String> removed = new LinkedHashSet<>(expected.keySet());
            removed.removeAll(actual.keySet());
            return "methods added=" + added + ", removed=" + removed;
        }
        for (String key : expected.keySet()) {
            if (!expected.get(key).equals(actual.get(key))) return "method modifiers: " + key;
        }
        return null;
    }

    private static boolean safeEquals(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final int CLASS_SCHEMA_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC
            | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM;
    private static final int FIELD_SCHEMA_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT | Opcodes.ACC_SYNTHETIC
            | Opcodes.ACC_ENUM;
    private static final int METHOD_SCHEMA_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE
            | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL
            | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_BRIDGE | Opcodes.ACC_VARARGS
            | Opcodes.ACC_NATIVE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STRICT
            | Opcodes.ACC_SYNTHETIC;
    private static final int VISIBILITY_ACCESS = Opcodes.ACC_PUBLIC
            | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED;
    private static final String ENTITY_PLAYER_SP =
            "net.minecraft.client.entity.EntityPlayerSP";
    private static final String SUPER_CALL_MARKER_OWNER =
            "mindless/runtime/EntityPlayerSPReflection";
    private static final String SUPER_CALL_MARKER_NAME =
            "callSuperOnLivingUpdateMarker";

    private void registerAll() {
        boolean customSkyPresent = false;
        try {
            Class.forName("net.optifine.CustomSky", false,
                    MindlessTransformerManager.class.getClassLoader());
            customSkyPresent = true;
        } catch (Throwable ignored) {
        }
        if (customSkyPresent) {
            registerTransformer("mindless.transformer.impl.render.TransformerCustomSky");
        }

        String[] transformers = {
                "mindless.transformer.impl.client.TransformerMinecraft",
                "mindless.transformer.impl.client.TransformerBlock",
                "mindless.transformer.impl.client.TransformerGameSettings",
                "mindless.transformer.impl.client.TransformerGuiContainer",
                "mindless.transformer.impl.client.TransformerGuiContainerShop",
                "mindless.transformer.impl.client.TransformerItemStack",
                "mindless.transformer.impl.client.TransformerMovementInputFromOptions",
                "mindless.transformer.impl.client.TransformerPlayerControllerMP",
                "mindless.transformer.impl.client.TransformerWorld",
                "mindless.transformer.impl.client.TransformerWorldInfo",
                "mindless.transformer.impl.entity.TransformerEntity",
                "mindless.transformer.impl.entity.TransformerEntityLiving",
                "mindless.transformer.impl.entity.TransformerEntityLivingBase",
                "mindless.transformer.impl.entity.TransformerEntityLivingBaseAnimations",
                "mindless.transformer.impl.entity.TransformerEntityPlayer",
                "mindless.transformer.impl.entity.TransformerEntityPlayerSP",
                "mindless.transformer.impl.network.TransformerModList",
                "mindless.transformer.impl.network.TransformerNetHandlerPlayClient",
                "mindless.transformer.impl.network.TransformerNetworkManager",
                "mindless.transformer.impl.render.TransformerEntityRenderer",
                "mindless.transformer.impl.render.TransformerFontRenderer",
                "mindless.transformer.impl.render.TransformerGuiChat",
                "mindless.transformer.impl.render.TransformerGuiNewChat",
                "mindless.transformer.impl.render.TransformerGuiIngame",
                "mindless.transformer.impl.render.TransformerGuiIngameForge",
                "mindless.transformer.impl.render.TransformerGuiScreen",
                "mindless.transformer.impl.render.TransformerGuiPlayerTabOverlay",
                "mindless.transformer.impl.render.TransformerItemRenderer",
                "mindless.transformer.impl.render.TransformerLayerHeldItem",
                "mindless.transformer.impl.render.TransformerRenderGlobal",
                "mindless.transformer.impl.render.TransformerRenderEntityItem",
                "mindless.transformer.impl.render.TransformerRenderManager",
                "mindless.transformer.impl.render.TransformerRenderPlayer",
                "mindless.transformer.impl.render.TransformerRendererLivingEntity",
                "mindless.transformer.impl.render.TransformerTileEntityChestRenderer",
                "mindless.transformer.impl.render.TransformerTileEntityEnderChestRenderer",
        };
        java.util.List<String> registrationOrder = new java.util.ArrayList<>();
        if (customSkyPresent) {
            registrationOrder.add("mindless.transformer.impl.render.TransformerCustomSky");
        }
        Collections.addAll(registrationOrder, transformers);

        auditDeclaredTargets(registrationOrder);

        for (String transformer : transformers) {
            registerTransformer(transformer);
        }
        fileLog("[MindlessTransformer] registered " + targetInternalNames.size()
                + " transformers successfully for profile " + runtimeProfile);
    }
private void auditDeclaredTargets(java.util.List<String> transformerClassNames) {
        if (runtimeNamespace != RuntimeNamespace.MCP) {
            fileLog("[MindlessTransformer] target audit skipped: obfuscated runtime namespace");
            return;
        }
        try {
            TransformerAudit audit = new TransformerAudit(classProvider);
            java.util.List<String> problems = audit.audit(transformerClassNames);
            if (!audit.unreadableTargets().isEmpty()) {
                fileLog("[MindlessTransformer-WARN] target audit could not read "
                        + audit.unreadableTargets().size() + " target class(es) and skipped them: "
                        + audit.unreadableTargets());
            }
            int breaking = 0;
            for (String problem : problems) {
                if (!problem.startsWith(TransformerAudit.OPTIONAL_PREFIX)) breaking++;
            }
            if (problems.isEmpty()) {
                fileLog("[MindlessTransformer] target audit clean: every declared target exists "
                        + "in this game build");
                return;
            }
            fileLog("[MindlessTransformer] target audit: " + breaking + " declared target(s) missing"
                    + ", " + (problems.size() - breaking) + " optional hook(s) inactive"
                    + " on this game build:");
            for (String problem : problems) {
                boolean optional = problem.startsWith(TransformerAudit.OPTIONAL_PREFIX);
                fileLog((optional ? "[MindlessTransformer-WARN]   " : "[MindlessTransformer-ERR]   ")
                        + problem);
            }
        } catch (Throwable failure) {
            fileLog("[MindlessTransformer-WARN] target audit could not run: " + failure);
        }
    }

    private void registerTransformer(String transformerClassName) {
        try {
            delegate.addTransformer(transformerClassName);
            for (String target : delegate.getTransformedClasses()) {
                targetInternalNames.add(target.replace('.', '/'));
            }
        } catch (Throwable failure) {
            String key = "registration:" + transformerClassName;
            transformFailures.put(key, failure.toString());
            fileLog("[MindlessTransformer-ERR] could not register "
                    + transformerClassName + ": " + failure);
            throw new IllegalStateException(
                    "Could not register runtime transformer " + transformerClassName,
                    failure);
        }
    }

    public int registeredCount() {
        return targetInternalNames.size();
    }
}
