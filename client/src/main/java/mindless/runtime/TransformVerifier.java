package mindless.runtime;
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
final class TransformVerifier {
private static final int ASM_API = 9 << 16;
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
            log.warn("reference check failed for " + transformed.name + ": " + failure);
        }

        try {
            return analyzeChangedMethods(original, transformed);
        } catch (Throwable failure) {
            log.warn("dataflow check failed for " + transformed.name + ": " + failure);
            return null;
        }
    }
private String findUnresolvedReferences(ClassNode original, ClassNode transformed) {
        Set<String> existing = new HashSet<>();
        collectReferences(original, existing);

        for (MethodNode method : transformed.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) instruction;
                    String key = "M " + call.owner + "." + call.name + call.desc;
                    if (existing.contains(key)) continue;
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
                log.warn("could not analyse " + transformed.name + "."
                        + method.name + method.desc + ": " + failure);
            }
        }
        return null;
    }
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
private final class HierarchyVerifier extends SimpleVerifier {
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
            if (node == null) return true;
            if (isSubtype(node.superName, wanted, visited)) return true;
            for (String parent : node.interfaces) {
                if (isSubtype(parent, wanted, visited)) return true;
            }
            return false;
        }
    }

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
