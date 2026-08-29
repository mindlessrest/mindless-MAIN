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

/**
 * Runtime replacement for Sponge Mixin. Registers ClassTransform transformers
 * that reproduce every Raven mixin, then re-transforms the target classes via
 * a native JVMTI ClassFileLoadHook installed by RavenNative.dll.
 *
 * IMPORTANT: JVMTI RetransformClasses cannot add methods, fields, or
 * interfaces to already-loaded classes. Accessor mixins (@Accessor / @Invoker)
 * therefore cannot be reproduced by porting them; callers must go through
 * {@link AccessorBridge} instead.
 */
public final class RavenTransformerManager {
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
    private static volatile RavenTransformerManager INSTANCE;

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

    /**
     * Targets the client cannot run without.
     *
     * <p>Minecraft carries the tick and input bridge: without it no module ever updates, no
     * keybind is read and no event is posted, so a client that started anyway would look exactly
     * like one that had not. Every other target is one feature. Losing a feature to a host update
     * is worth reporting; losing the whole client to it is not, which is what an all-or-nothing
     * startup did.
     */
    private static final Set<String> REQUIRED_TARGETS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList("net.minecraft.client.Minecraft")));

    /** File log survives the DLL unload and MC process; grep here to diagnose. */
    static void fileLog(String message) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "RavenNative");
            dir.mkdirs();
            try (PrintWriter w = new PrintWriter(
                    new FileOutputStream(new File(dir, "raven-transformer.log"), true), true)) {
                w.println("[" + new java.util.Date() + "] " + message);
            }
        } catch (Throwable ignored) {}
        System.out.println(message);
    }

    private static void dumpClassBytes(String canonicalName, byte[] bytes) {
        try {
            File dir = new File(System.getProperty("java.io.tmpdir"), "RavenNative/classdump");
            dir.mkdirs();
            String fileName = canonicalName.replace('.', '/') + ".class";
            File out = new File(dir, fileName);
            out.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
        } catch (Throwable ignored) {}
    }

    private RavenTransformerManager() {
        this(new LaunchClassProvider(RavenTransformerManager.class.getClassLoader()));
    }

    /**
     * Test seam for supplying bytecode in the same namespace as the classes
     * being transformed. Production always enters through {@link #get()} and
     * therefore keeps using {@link LaunchClassProvider}.
     */
    RavenTransformerManager(IClassProvider provider) {
        this(provider, detectRuntimeNamespace(provider), detectRuntimeProfile(provider));
    }

    /** Explicit namespace seam used by the Forge and Lunar compatibility tests. */
    RavenTransformerManager(IClassProvider provider, RuntimeNamespace runtimeNamespace) {
        this(provider, runtimeNamespace, RuntimeProfile.GENERIC);
    }

    RavenTransformerManager(IClassProvider provider, RuntimeNamespace runtimeNamespace,
                            RuntimeProfile runtimeProfile) {
        if (provider == null) throw new IllegalArgumentException("provider");
        if (runtimeNamespace == null) throw new IllegalArgumentException("runtimeNamespace");
        if (runtimeProfile == null) throw new IllegalArgumentException("runtimeProfile");
        this.classProvider = provider;
        this.runtimeNamespace = runtimeNamespace;
        this.runtimeProfile = runtimeProfile;
        ClassLoader loader = RavenTransformerManager.class.getClassLoader();

        // Forge production bytecode needs the bundled MCP -> SRG table.
        // Lunar's baked 1.8.9 classes already expose MCP/named members, so the
        // correct Lunar mapping operation is identity/pass-through.
        AMapper selectedMapper = null;
        if (runtimeNamespace == RuntimeNamespace.SRG) {
            InputStream mappingsStream = loader.getResourceAsStream("raven-mappings.srg");
            if (mappingsStream == null) {
                throw new IllegalStateException(
                        "raven-mappings.srg is required for the Forge/SRG runtime");
            }
            try {
                selectedMapper = new SrgMapper(
                        MapperConfig.create().remapTransformer(true).fillSuperMappings(true),
                        mappingsStream);
                fileLog("[RavenTransformer] runtime namespace SRG; "
                        + "loaded raven-mappings.srg");
            } catch (Throwable failure) {
                fileLog("[RavenTransformer-ERR] SrgMapper init failed: " + failure);
                throw new IllegalStateException("Could not initialize Forge/SRG mappings", failure);
            }
        } else {
            fileLog("[RavenTransformer] runtime namespace MCP "
                    + "(Lunar/deobfuscated); using identity mappings");
        }

        this.mapper = selectedMapper;
        this.delegate = selectedMapper != null
                ? new TransformerManager(provider, selectedMapper)
                : new TransformerManager(provider);
        // Use THROW so failures inside transform() propagate as exceptions
        // caught by our transform(...) wrapper, giving us a real stack trace
        // instead of the silent "CANCEL" fallback.
        this.delegate.setFailStrategy(FailStrategy.THROW);

        // CRITICAL: swap the internal ClassTree for a non-transforming one.
        // When ASM asks getCommonSuperClass(A, B) mid-transform, the default
        // ClassTree re-runs the transformer pipeline for A/B — that reentrant
        // call NPEs because the CInject/CRedirect handlers hold state that
        // is only valid for the top-level target. A ClassTree constructed
        // WITHOUT a TransformerManager reference just walks raw bytecode
        // from the class provider (returning the stub or real bytes) without
        // re-applying any @CInject.
        try {
            java.lang.reflect.Field treeField = TransformerManager.class.getDeclaredField("classTree");
            treeField.setAccessible(true);
            treeField.set(this.delegate,
                    new net.lenni0451.classtransform.utils.tree.ClassTree());
            fileLog("[RavenTransformer] installed non-transforming ClassTree (reentrance guard)");
        } catch (Throwable failure) {
            fileLog("[RavenTransformer] ClassTree swap failed: " + failure);
        }
        this.verifier = new TransformVerifier(provider, new TransformVerifier.Log() {
            public void warn(String message) {
                fileLog("[RavenTransformer-WARN] " + message);
            }
        });
        this.targetInternalNames = new LinkedHashSet<>();
        this.entityPlayerSpCanonicalName = mapClassName(ENTITY_PLAYER_SP);
        this.superCallMarkerDesc = "(L"
                + entityPlayerSpCanonicalName.replace('.', '/') + ";)V";
        registerAll();
    }

    private static RuntimeProfile detectRuntimeProfile(IClassProvider provider) {
        String override = System.getProperty("raven.runtimeProfile");
        if (override != null) {
            String normalized = override.trim().toLowerCase(java.util.Locale.ROOT);
            if ("lunar".equals(normalized)) return RuntimeProfile.LUNAR;
            if ("forge".equals(normalized)) return RuntimeProfile.FORGE;
            if ("generic".equals(normalized) || "deobf".equals(normalized)) {
                return RuntimeProfile.GENERIC;
            }
            throw new IllegalArgumentException(
                    "Unsupported raven.runtimeProfile value: " + override);
        }

        // Test fixtures and non-native launches do not receive the native
        // property. Detect Lunar from a stable class baked into its platform.
        try {
            byte[] marker = provider.getClass("com.lunarclient.ApiUtils");
            if (marker != null && marker.length > 0) return RuntimeProfile.LUNAR;
        } catch (Throwable ignored) {
            // Not Lunar.
        }
        return RuntimeProfile.GENERIC;
    }

    private static RuntimeNamespace detectRuntimeNamespace(IClassProvider provider) {
        if (provider == null) throw new IllegalArgumentException("provider");

        String override = System.getProperty("raven.runtimeNamespace");
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
                    "Unsupported raven.runtimeNamespace value: " + override);
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
            fileLog("[RavenTransformer] could not detect runtime namespace from "
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

    public static RavenTransformerManager get() {
        RavenTransformerManager local = INSTANCE;
        if (local != null) return local;
        synchronized (LOCK) {
            if (INSTANCE == null) INSTANCE = new RavenTransformerManager();
            return INSTANCE;
        }
    }

    public Set<String> targetInternalNames() {
        return Collections.unmodifiableSet(targetInternalNames);
    }

    /**
     * Called by TransformerHooks from the JVMTI ClassFileLoadHook. The name is
     * in internal form (e.g. "net/minecraft/client/renderer/EntityRenderer").
     * Returns the transformed bytecode or null when the class is not managed.
     */
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
            fileLog("[RavenTransformer] " + canonicalName
                    + " -> SKIPPED (already transforming on this thread)");
            return null;
        }
        try {
            dumpClassBytes(canonicalName, originalBytes);
            byte[] result;
            synchronized (delegateTransformLock) {
                // ClassTransform keeps mutable handler state while applying a
                // transformer. JVMTI class-load hooks may arrive concurrently
                // from the client, Netty, and other loader threads.
                result = delegate.transform(canonicalName, originalBytes);
            }
            if (result != null && result != originalBytes) {
                result = rewriteRequiredSpecialCalls(canonicalName, result);
                result = restoreOriginalVisibility(originalBytes, result);
                String schemaChange = findRetransformSchemaChange(originalBytes, result);
                if (schemaChange != null) {
                    transformFailures.put(canonicalName, "schema changed: " + schemaChange);
                    fileLog("[RavenTransformer-ERR] rejected " + canonicalName
                            + " before JVMTI: retransformation schema changed ("
                            + schemaChange + ")");
                    return null;
                }
                // The last gate before the JVM sees this. Everything above compares shapes;
                // this one reads the code. A class that gets past a shape check and fails here
                // is the one that would come back from JVMTI as a bare error 62 and take the
                // whole startup with it.
                String invalid = verifier.verify(originalBytes, result);
                if (invalid != null) {
                    transformFailures.put(canonicalName, invalid);
                    fileLog("[RavenTransformer-ERR] rejected " + canonicalName
                            + " before JVMTI: " + invalid);
                    return null;
                }
            }
            if (result == null || result == originalBytes || result.length == originalBytes.length) {
                if (result == null || result == originalBytes) {
                    transformFailures.put(canonicalName, "transformer returned no changed bytecode");
                    fileLog("[RavenTransformer] " + canonicalName + " -> NO CHANGE (transformer likely inert)");
                    return null;
                }
                // same length but potentially different content
                boolean identical = java.util.Arrays.equals(result, originalBytes);
                if (identical) {
                    transformFailures.put(canonicalName, "transformer returned identical bytecode");
                    fileLog("[RavenTransformer] " + canonicalName + " -> NO CHANGE (equal bytes)");
                    return null;
                }
            }
            transformFailures.remove(canonicalName);
            fileLog("[RavenTransformer] " + canonicalName
                    + " -> transformed (" + originalBytes.length + " -> " + result.length + " bytes)");
            return result;
        } catch (Throwable failure) {
            transformFailures.put(canonicalName, failure.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(sw));
            // Walk cause chain in case the real reason is buried under wrappers
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
            fileLog("[RavenTransformer-ERR] failed to transform " + canonicalName
                    + ":\n" + sw.toString());
            return null;
        } finally {
            inFlight.remove(canonicalName);
        }
    }

    /**
     * Reports what did not apply, and stops startup only when it has to.
     *
     * <p>A successful {@code RetransformClasses} is not evidence that anything was hooked: JVMTI
     * also answers success when the hook returns null and the original bytes are kept. This is
     * where that difference is turned into something readable -- one line per target that did not
     * take, naming the class and the reason, which on a host update is the renamed method itself.
     *
     * <p>Only a {@link #REQUIRED_TARGETS required} target stops the client. This used to throw for
     * any failure at all, and combined with the agent abandoning startup on a partial batch it
     * meant one class the host had refactored kept the client from ever initialising.
     */
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
            String line = (isRequired ? "[RavenTransformer-ERR] REQUIRED " : "[RavenTransformer-WARN] skipped ")
                    + target + ": " + failure.getValue();
            fileLog(line);
            System.out.println(line);
            if (isRequired) required.put(target, failure.getValue());
        }

        if (!required.isEmpty()) {
            throw new IllegalStateException(
                    "Required transformer targets could not be applied to this game build: "
                            + required);
        }
    }

    private static String stripRegistrationPrefix(String key) {
        return key.startsWith("registration:") ? key.substring("registration:".length()) : key;
    }

    /**
     * HotSpot retransformation may alter method bodies but may not add/remove
     * fields or methods, change inheritance, or change member modifiers. Keep
     * this preflight next to the transform boundary so a future transformer
     * cannot silently produce JVMTI error 63/64 again.
     */
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

    /**
     * ClassTransform copies an override method's visibility from the template.
     * Lunar exposes a few vanilla methods more broadly than Forge does, so a
     * single template visibility cannot match both runtimes. Restore only the
     * original visibility; changes to static/final/native/abstract and other
     * structural flags must remain visible to the schema check and be rejected.
     */
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

    /**
     * Java reflection cannot express a non-virtual super call. The
     * EntityPlayerSP override therefore carries a harmless static marker while
     * it is processed by ClassTransform; replace that marker only after the
     * final target method name (MCP or SRG) is known.
     */
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

    /**
     * ClassTransform may remove an {@code @CInline} helper while leaving an
     * invocation to it in a copied handler. The class still has a legal JVMTI
     * schema, but the JVM throws {@link NoSuchMethodError} only when that path
     * first executes. Resolve every call whose symbolic owner is the
     * transformed class before returning its bytecode to the native agent.
     */
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

    // Note: `inFlight` is thread-local and the set removes the name in the
    // finally block above; that is what makes reentrant calls from ASM's
    // getCommonSuperClass short-circuit safely without leaving stale entries.

    private void registerAll() {
        // Try to register the Optifine CustomSky transformer conditionally.
        // It only makes sense if net.optifine.CustomSky is present at runtime.
        boolean customSkyPresent = false;
        try {
            Class.forName("net.optifine.CustomSky", false,
                    RavenTransformerManager.class.getClassLoader());
            customSkyPresent = true;
        } catch (Throwable ignored) {
            // OptiFine not present — skip.
        }
        if (customSkyPresent) {
            registerTransformer("mindless.transformer.impl.render.TransformerCustomSky");
        }

        String[] transformers = {
                // client
                "mindless.transformer.impl.client.TransformerMinecraft",
                "mindless.transformer.impl.client.TransformerBlock",
                "mindless.transformer.impl.client.TransformerGameSettings",
                "mindless.transformer.impl.client.TransformerGuiContainer",
                "mindless.transformer.impl.client.TransformerItemStack",
                "mindless.transformer.impl.client.TransformerMovementInputFromOptions",
                "mindless.transformer.impl.client.TransformerPlayerControllerMP",
                "mindless.transformer.impl.client.TransformerWorld",
                "mindless.transformer.impl.client.TransformerWorldInfo",
                // entity
                "mindless.transformer.impl.entity.TransformerEntity",
                "mindless.transformer.impl.entity.TransformerEntityLiving",
                "mindless.transformer.impl.entity.TransformerEntityLivingBase",
                "mindless.transformer.impl.entity.TransformerEntityLivingBaseAnimations",
                "mindless.transformer.impl.entity.TransformerEntityPlayer",
                "mindless.transformer.impl.entity.TransformerEntityPlayerSP",
                // network
                "mindless.transformer.impl.network.TransformerModList",
                "mindless.transformer.impl.network.TransformerNetHandlerPlayClient",
                "mindless.transformer.impl.network.TransformerNetworkManager",
                // render
                "mindless.transformer.impl.render.TransformerEntityRenderer",
                "mindless.transformer.impl.render.TransformerAbstractClientPlayer",
                "mindless.transformer.impl.render.TransformerFontRenderer",
                "mindless.transformer.impl.render.TransformerGuiChat",
                "mindless.transformer.impl.render.TransformerGuiNewChat",
                "mindless.transformer.impl.render.TransformerGuiIngame",
                "mindless.transformer.impl.render.TransformerGuiIngameForge",
                "mindless.transformer.impl.render.TransformerGuiScreen",
                "mindless.transformer.impl.render.TransformerGuiPlayerTabOverlay",
                "mindless.transformer.impl.render.TransformerItemRenderer",
                "mindless.transformer.impl.render.TransformerLayerCape",
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
        fileLog("[RavenTransformer] registered " + targetInternalNames.size()
                + " transformers successfully for profile " + runtimeProfile);
    }

    /**
     * Says up front which declared targets this game build no longer has.
     *
     * <p>Runs before any weaving so the report survives whatever the weaving does next. Nothing is
     * refused on the strength of it: a target the audit cannot find is usually a target the weaver
     * will also fail to find, and the weaver's failure is already handled per class. What this
     * adds is the name of the member that moved, which is the only part a bare
     * "transformation failed" never tells you and the only part that says what to change.
     *
     * <p>Skipped in the obfuscated namespace, where the transformers are written in named form and
     * the mapper rewrites them on the way in; comparing the two directly would flag every member.
     */
    private void auditDeclaredTargets(java.util.List<String> transformerClassNames) {
        if (runtimeNamespace != RuntimeNamespace.MCP) {
            fileLog("[RavenTransformer] target audit skipped: obfuscated runtime namespace");
            return;
        }
        try {
            java.util.List<String> problems =
                    new TransformerAudit(classProvider).audit(transformerClassNames);
            int breaking = 0;
            for (String problem : problems) {
                if (!problem.startsWith(TransformerAudit.OPTIONAL_PREFIX)) breaking++;
            }
            if (problems.isEmpty()) {
                fileLog("[RavenTransformer] target audit clean: every declared target exists "
                        + "in this game build");
                return;
            }
            fileLog("[RavenTransformer] target audit: " + breaking + " declared target(s) missing"
                    + ", " + (problems.size() - breaking) + " optional hook(s) inactive"
                    + " on this game build:");
            for (String problem : problems) {
                boolean optional = problem.startsWith(TransformerAudit.OPTIONAL_PREFIX);
                fileLog((optional ? "[RavenTransformer-WARN]   " : "[RavenTransformer-ERR]   ")
                        + problem);
            }
        } catch (Throwable failure) {
            fileLog("[RavenTransformer-WARN] target audit could not run: " + failure);
        }
    }

    private void registerTransformer(String transformerClassName) {
        try {
            delegate.addTransformer(transformerClassName);
            // ClassTransform owns the authoritative, already-mapped target
            // set. Deriving from it keeps the native JVMTI filter in the same
            // namespace as the transformer delegate.
            for (String target : delegate.getTransformedClasses()) {
                targetInternalNames.add(target.replace('.', '/'));
            }
        } catch (Throwable failure) {
            String key = "registration:" + transformerClassName;
            transformFailures.put(key, failure.toString());
            fileLog("[RavenTransformer-ERR] could not register "
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
