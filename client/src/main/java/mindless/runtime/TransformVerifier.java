package mindless.runtime;

// The imports use the ORIGINAL org.objectweb.asm package: shadow's relocate
// rewrites them at package time to mindless.deps.org.objectweb.asm, but
// javac needs the source imports pre-relocation.
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether transformed bytecode is safe to hand to the JVM.
 *
 * <p>Until this existed, a transform counted as successful when the resulting byte array simply
 * differed from the original. That is a much weaker claim than it sounds: ClassTransform is happy
 * to weave a handler into a method whose surroundings have changed underneath it, and the result
 * is a class that only the verifier will object to. JVMTI then answers
 * {@code JVMTI_ERROR_FAILS_VERIFICATION} (62) with no indication of which method or instruction is
 * at fault, the native agent treats the partial batch as a failed startup, and
 * {@code NativeBootstrap.start} is never called -- so every transformer "succeeds" and the client
 * never initialises. That is the shape of the breakage a Lunar update causes.
 *
 * <p>Two things are checked, chosen because between them they cover what a host update actually
 * breaks:
 *
 * <ul>
 *   <li><b>References.</b> Every method and field the transform newly refers to has to resolve
 *       against the classes as they exist in this process. A renamed or re-signatured member is
 *       the ordinary consequence of a host update, and it is silent until the injected path first
 *       runs -- often long after startup, as a {@link NoSuchMethodError} in the middle of a
 *       frame.</li>
 *   <li><b>Data flow.</b> Every method the transform changed is re-analysed with the real class
 *       hierarchy behind the type checks. This is the verifier's own reasoning, run early enough
 *       to name the method and the instruction instead of a bare error code.</li>
 * </ul>
 *
 * <p>A failure here is a reason to leave one class alone, not to abandon startup: the caller
 * returns the original bytecode, that hook goes missing, and everything else still loads.
 */
final class TransformVerifier {
    /**
     * {@code Opcodes.ASM9}, written out rather than referenced.
     *
     * <p>Two ASMs are on the compile path -- Forge's bundled 5.0.3 and the 9.x the transformer
     * library needs -- and 5.0.3 wins for {@code Opcodes}, which has no ASM9 constant. Only the
     * 9.x copy is shaded into the payload, so this is the version that is actually there at run
     * time.
     */
    private static final int ASM_API = 9 << 16;

    /** Where diagnostics go. Injected so this class can be exercised without the manager. */
    interface Log {
        void warn(String message);
    }

    private final IClassProvider provider;
    private final Log log;
    private final Map<String, ClassNode> hierarchy = new HashMap<>();
    private final Set<String> unresolvable = new HashSet<>();

    TransformVerifier(IClassProvider provider, Log log) {
        this.provider = provider;
        this.log = log;
    }

    /**
     * @return a description of the first problem found, or null when the class is safe to define
     */
    String verify(byte[] originalBytes, byte[] transformedBytes) {
        ClassNode original;
        ClassNode transformed;
        try {
            original = read(originalBytes);
            transformed = read(transformedBytes);
        } catch (Throwable failure) {
            return "could not read class for verification: " + failure;
        }

        try {
            String unresolved = findUnresolvedReferences(original, transformed);
            if (unresolved != null) return unresolved;
        } catch (Throwable failure) {
            // A preflight that cannot run is not evidence of broken bytecode.
            log.warn("reference check failed for " + transformed.name + ": " + failure);
        }

        try {
            return analyzeChangedMethods(original, transformed);
        } catch (Throwable failure) {
            log.warn("dataflow check failed for " + transformed.name + ": " + failure);
            return null;
        }
    }

    // ------------------------------------------------------------------ references

    /**
     * Looks for members the transformed class refers to that nothing in this process provides.
     *
     * <p>Only references the transform introduced are considered. The original class is by
     * definition consistent with the rest of the runtime, so anything it already referred to is
     * someone else's business -- and a host that ships classes referring to their own internals
     * would otherwise produce a wall of false reports.
     */
    private String findUnresolvedReferences(ClassNode original, ClassNode transformed) {
        Set<String> existing = new HashSet<>();
        collectReferences(original, existing);

        for (MethodNode method : transformed.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    String key = "M " + call.owner + "." + call.name + call.desc;
                    if (existing.contains(key)) continue;
                    // Arrays inherit Object's methods and have no class file of their own.
                    if (call.owner.startsWith("[")) continue;
                    if (resolvesMethod(call.owner, call.name, call.desc)) continue;
                    if (unresolvable.contains(call.owner)) continue;
                    return "unresolved method reference in " + method.name + method.desc
                            + " -> " + call.owner + "." + call.name + call.desc;
                }
                else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode access = (FieldInsnNode) instruction;
                    String key = "F " + access.owner + "." + access.name + ":" + access.desc;
                    if (existing.contains(key)) continue;
                    if (access.owner.startsWith("[")) continue;
                    if (resolvesField(access.owner, access.name, access.desc)) continue;
                    if (unresolvable.contains(access.owner)) continue;
                    return "unresolved field reference in " + method.name + method.desc
                            + " -> " + access.owner + "." + access.name + ":" + access.desc;
                }
            }
        }
        return null;
    }

    private static void collectReferences(ClassNode node, Set<String> into) {
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    into.add("M " + call.owner + "." + call.name + call.desc);
                }
                else if (instruction instanceof FieldInsnNode) {
                    FieldInsnNode access = (FieldInsnNode) instruction;
                    into.add("F " + access.owner + "." + access.name + ":" + access.desc);
                }
            }
        }
    }

    private boolean resolvesMethod(String owner, String name, String desc) {
        return resolvesMethod(owner, name, desc, new HashSet<String>());
    }

    private boolean resolvesMethod(String owner, String name, String desc, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        ClassNode node = load(owner);
        if (node == null) return false;
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        if (node.superName != null && resolvesMethod(node.superName, name, desc, visited)) {
            return true;
        }
        for (String parent : node.interfaces) {
            if (resolvesMethod(parent, name, desc, visited)) return true;
        }
        // Every class answers to Object's methods, including interfaces.
        return !"java/lang/Object".equals(owner)
                && resolvesMethod("java/lang/Object", name, desc, visited);
    }

    private boolean resolvesField(String owner, String name, String desc) {
        return resolvesField(owner, name, desc, new HashSet<String>());
    }

    private boolean resolvesField(String owner, String name, String desc, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return false;
        ClassNode node = load(owner);
        if (node == null) return false;
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) return true;
        }
        if (node.superName != null && resolvesField(node.superName, name, desc, visited)) {
            return true;
        }
        for (String parent : node.interfaces) {
            if (resolvesField(parent, name, desc, visited)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------- data flow

    /**
     * Re-analyses the methods the transform touched, the way the verifier will.
     *
     * <p>Only changed methods, because a class like Minecraft has hundreds and the ones the
     * transform never opened are exactly as valid as they were when the JVM first accepted them.
     */
    private String analyzeChangedMethods(ClassNode original, ClassNode transformed) {
        Map<String, MethodNode> before = new HashMap<>();
        for (MethodNode method : original.methods) {
            before.put(method.name + method.desc, method);
        }

        List<Type> interfaceTypes = new ArrayList<>();
        for (String name : transformed.interfaces) interfaceTypes.add(Type.getObjectType(name));
        SimpleVerifier verifier = new HierarchyVerifier(
                Type.getObjectType(transformed.name),
                transformed.superName == null ? null : Type.getObjectType(transformed.superName),
                interfaceTypes,
                (transformed.access & Opcodes.ACC_INTERFACE) != 0);
        Analyzer<BasicValue> analyzer = new Analyzer<>(verifier);
        for (MethodNode method : transformed.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            MethodNode originalMethod = before.get(method.name + method.desc);
            if (originalMethod != null && !instructionsDiffer(originalMethod, method)) continue;

            try {
                analyzer.analyze(transformed.name, method);
            } catch (AnalyzerException failure) {
                return "verification failed in " + method.name + method.desc + ": "
                        + failure.getMessage();
            } catch (Throwable failure) {
                // The analyser itself broke rather than the bytecode; do not fail the class on it.
                log.warn("could not analyse " + transformed.name + "."
                        + method.name + method.desc + ": " + failure);
            }
        }
        return null;
    }

    /**
     * Whether two versions of a method carry different code.
     *
     * <p>Compared by opcode sequence rather than by size: labels, line numbers and frames move
     * around for reasons that have nothing to do with what the method does, and re-analysing every
     * method of every target would cost more than the whole transform.
     */
    private static boolean instructionsDiffer(MethodNode before, MethodNode after) {
        List<Integer> beforeOpcodes = opcodes(before);
        List<Integer> afterOpcodes = opcodes(after);
        return !beforeOpcodes.equals(afterOpcodes);
    }

    private static List<Integer> opcodes(MethodNode method) {
        List<Integer> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            int opcode = instruction.getOpcode();
            if (opcode >= 0) result.add(opcode);
        }
        return result;
    }

    /**
     * Type checks answered from the running process rather than from this class loader.
     *
     * <p>{@link SimpleVerifier} resolves types with {@code Class.forName}, which cannot see the
     * game's classes from here and would report every Minecraft type as unloadable. Reading the
     * hierarchy out of the class provider instead gives the analysis the same view of the world
     * the JVM's own verifier will have.
     */
    private final class HierarchyVerifier extends SimpleVerifier {
        /**
         * The API-taking constructor is the only one a subclass may use: the others assert that
         * the instance is exactly a SimpleVerifier and throw otherwise, which silently turned the
         * whole data-flow pass into a caught exception.
         */
        HierarchyVerifier(Type currentClass, Type currentSuperClass,
                          List<Type> currentClassInterfaces, boolean isInterface) {
            super(ASM_API, currentClass, currentSuperClass, currentClassInterfaces, isInterface);
        }

        @Override
        protected boolean isInterface(Type type) {
            if (type.getSort() != Type.OBJECT) return false;
            ClassNode node = load(type.getInternalName());
            return node != null && (node.access & Opcodes.ACC_INTERFACE) != 0;
        }

        @Override
        protected Type getSuperClass(Type type) {
            if (type.getSort() != Type.OBJECT) return null;
            ClassNode node = load(type.getInternalName());
            if (node == null || node.superName == null) return null;
            return Type.getObjectType(node.superName);
        }

        @Override
        protected boolean isAssignableFrom(Type target, Type value) {
            if (target.equals(value)) return true;

            if (target.getSort() == Type.ARRAY) {
                if (value.getSort() != Type.ARRAY) return false;
                // One dimension at a time, not "same number of dimensions": String[][] is an
                // Object[], because its component String[] is an Object. Comparing the fully
                // unwrapped element types instead rejected that, and rejecting valid bytecode is
                // the one thing this class must not do.
                Type targetComponent = componentOf(target);
                Type valueComponent = componentOf(value);
                if (targetComponent.getSort() == Type.OBJECT
                        || targetComponent.getSort() == Type.ARRAY) {
                    return isAssignableFrom(targetComponent, valueComponent);
                }
                return targetComponent.equals(valueComponent);
            }

            if (target.getSort() != Type.OBJECT) return false;
            if ("java/lang/Object".equals(target.getInternalName())) return true;
            if (value.getSort() == Type.ARRAY) {
                // The only named types an array answers to.
                return "java/lang/Cloneable".equals(target.getInternalName())
                        || "java/io/Serializable".equals(target.getInternalName());
            }
            if (value.getSort() != Type.OBJECT) return false;
            return isSubtype(value.getInternalName(), target.getInternalName(),
                    new HashSet<String>());
        }

        private Type componentOf(Type arrayType) {
            return Type.getType(arrayType.getDescriptor().substring(1));
        }

        private boolean isSubtype(String candidate, String wanted, Set<String> visited) {
            if (candidate == null || !visited.add(candidate)) return false;
            if (candidate.equals(wanted)) return true;
            ClassNode node = load(candidate);
            // An unknown type is treated as compatible: refusing it would reject a valid class
            // because we could not see one of its ancestors, which is the wrong way round.
            if (node == null) return true;
            if (isSubtype(node.superName, wanted, visited)) return true;
            for (String parent : node.interfaces) {
                if (isSubtype(parent, wanted, visited)) return true;
            }
            return false;
        }
    }

    // ----------------------------------------------------------------------- shared

    private ClassNode load(String internalName) {
        ClassNode cached = hierarchy.get(internalName);
        if (cached != null) return cached;
        if (unresolvable.contains(internalName)) return null;
        try {
            byte[] bytes = provider.getClass(internalName.replace('/', '.'));
            if (bytes == null || bytes.length == 0) {
                unresolvable.add(internalName);
                return null;
            }
            ClassNode node = read(bytes);
            hierarchy.put(internalName, node);
            return node;
        } catch (Throwable failure) {
            unresolvable.add(internalName);
            return null;
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return node;
    }
}
