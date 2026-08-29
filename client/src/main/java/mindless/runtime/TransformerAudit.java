package mindless.runtime;

// The imports use the ORIGINAL org.objectweb.asm package: shadow's relocate
// rewrites them at package time to mindless.deps.org.objectweb.asm, but
// javac needs the source imports pre-relocation.
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks, before anything is woven, that every transformer is still describing a method that
 * exists.
 *
 * <p>A transformer is written against one build of the game and run against whatever build the
 * user has. When the host renames a method, moves a call, or refactors a body, the transformer
 * does not become obviously wrong -- it becomes wrong in a way that only shows up as an exception
 * deep inside the weaving library, whose consequence is that the whole target class silently keeps
 * its original bytecode. Every hook in that class is then missing, and because the retransform
 * itself "succeeded" nothing says so.
 *
 * <p>This reads the transformers the same way the weaver does and answers a narrower question: for
 * each declared target -- the method being injected into, the call being redirected, the field
 * being shadowed -- does that member exist in this process? The answer is a list of exactly what
 * this game build no longer provides, by transformer and by member, which is the difference
 * between "the client does not work on the new version" and "Minecraft.runTick is now called
 * something else".
 *
 * <p>Only meaningful where the runtime uses the same names the transformers were compiled against.
 * Under Forge's obfuscated namespace the member names are rewritten by the mapper on the way in,
 * and comparing the two directly would report every single one as missing.
 */
final class TransformerAudit {
    /** Marks a finding that costs one hook rather than a whole class. */
    static final String OPTIONAL_PREFIX = "(optional) ";

    private static final String CTRANSFORMER = "Lnet/lenni0451/classtransform/annotations/CTransformer;";
    private static final String CSHADOW = "Lnet/lenni0451/classtransform/annotations/CShadow;";
    private static final String[] INJECTION_ANNOTATIONS = {
            "Lnet/lenni0451/classtransform/annotations/injection/CInject;",
            "Lnet/lenni0451/classtransform/annotations/injection/CRedirect;",
            "Lnet/lenni0451/classtransform/annotations/injection/CModifyConstant;",
            "Lnet/lenni0451/classtransform/annotations/injection/CModifyExpressionValue;",
            "Lnet/lenni0451/classtransform/annotations/injection/CWrapCondition;",
            "Lnet/lenni0451/classtransform/annotations/injection/CWrapCatch;",
            "Lnet/lenni0451/classtransform/annotations/injection/COverride;",
    };

    private final IClassProvider provider;
    private final Map<String, ClassNode> cache = new HashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();
    private final Set<String> unreadable = new LinkedHashSet<>();

    TransformerAudit(IClassProvider provider) {
        this.provider = provider;
    }

    /**
     * @return one line per declared target this game build does not provide, in registration order
     */
    List<String> audit(List<String> transformerClassNames) {
        missing.clear();
        unreadable.clear();
        for (String transformerName : transformerClassNames) {
            try {
                auditTransformer(transformerName);
            } catch (Throwable failure) {
                // Being unable to read a transformer is not the same as the game having changed.
                missing.add(transformerName + ": could not be audited (" + failure + ")");
            }
        }
        return new ArrayList<>(missing);
    }

    /** Target classes the class loader would not describe, which were therefore not checked. */
    Set<String> unreadableTargets() {
        return unreadable;
    }

    /**
     * Whether a class node carries enough to answer questions about its members.
     *
     * <p>A class with neither methods nor fields is one the provider could not read, not one the
     * game shipped empty: every class this audit looks at has a constructor at minimum.
     */
    private static boolean isReadable(ClassNode node) {
        return !node.methods.isEmpty() || !node.fields.isEmpty();
    }

    private void auditTransformer(String transformerName) throws Exception {
        ClassNode transformer = load(transformerName.replace('.', '/'));
        if (transformer == null) return;

        List<String> targetClasses = readTransformerTargets(transformer);
        if (targetClasses.isEmpty()) return;

        for (String targetClassName : targetClasses) {
            ClassNode target = load(targetClassName);
            if (target == null) {
                // The class is simply not part of this build; the manager already skips those.
                continue;
            }
            if (!isReadable(target)) {
                // A class the loader could only describe as a name is not evidence that its
                // members are gone. The game's classes reach us through a loader that hides them
                // as resources, so what comes back is a reflective sketch that is sometimes empty;
                // reporting every member of an empty sketch as missing buries the real findings
                // under a hundred false ones.
                unreadable.add(short_(targetClassName));
                continue;
            }

            for (FieldNode field : transformer.fields) {
                AnnotationNode shadow = findAnnotation(field.invisibleAnnotations, CSHADOW);
                if (shadow == null) shadow = findAnnotation(field.visibleAnnotations, CSHADOW);
                if (shadow == null) continue;
                String declared = (String) value(shadow, "value");
                String name = declared == null || declared.isEmpty() ? field.name : declared;
                if (!hasField(target, name)) {
                    missing.add(simple(transformerName) + " @CShadow field "
                            + short_(targetClassName) + "." + name + " does not exist");
                }
            }

            for (MethodNode method : transformer.methods) {
                AnnotationNode shadow = findAnnotation(method.invisibleAnnotations, CSHADOW);
                if (shadow != null) {
                    String declared = (String) value(shadow, "value");
                    String name = declared == null || declared.isEmpty() ? method.name : declared;
                    checkMember(transformerName, targetClassName, target, name, "@CShadow method");
                }

                for (String annotationDescriptor : INJECTION_ANNOTATIONS) {
                    AnnotationNode injection = findAnnotation(method.invisibleAnnotations, annotationDescriptor);
                    if (injection == null) continue;

                    for (String declared : stringList(value(injection, "method"))) {
                        checkMember(transformerName, targetClassName, target, declared,
                                shortName(annotationDescriptor) + " target method");
                    }
                    // The instruction an injection anchors to is its own dependency on the host,
                    // and the one a refactor breaks first.
                    for (AnnotationNode targetAnnotation : targetAnnotations(injection)) {
                        checkInstructionTarget(transformerName, targetAnnotation);
                    }
                }
            }
        }
    }

    /** {@code Lowner;name(args)ret} -- the form a CTarget uses to name a call or field access. */
    private void checkInstructionTarget(String transformerName, AnnotationNode targetAnnotation) {
        Object kind = value(targetAnnotation, "value");
        if (!"INVOKE".equals(kind) && !"FIELD".equals(kind)) return;
        Object declared = value(targetAnnotation, "target");
        if (!(declared instanceof String)) return;
        String reference = (String) declared;
        int ownerEnd = reference.indexOf(';');
        if (!reference.startsWith("L") || ownerEnd <= 1) return;

        String owner = reference.substring(1, ownerEnd);
        String member = reference.substring(ownerEnd + 1);
        ClassNode ownerNode = load(owner);
        if (ownerNode == null) return;

        boolean found;
        if ("FIELD".equals(kind)) {
            int colon = member.indexOf(':');
            found = hasField(ownerNode, colon < 0 ? member : member.substring(0, colon));
        } else {
            int parenthesis = member.indexOf('(');
            String name = parenthesis < 0 ? member : member.substring(0, parenthesis);
            String desc = parenthesis < 0 ? null : member.substring(parenthesis);
            found = hasMethod(ownerNode, name, desc);
        }
        if (!found) {
            // An optional anchor that is absent is a hook quietly not applying, which is the
            // designed behaviour on a host that has moved the code. Worth saying, not worth
            // counting alongside the ones that will actually break a class.
            boolean optional = Boolean.TRUE.equals(value(targetAnnotation, "optional"));
            missing.add((optional ? OPTIONAL_PREFIX : "") + simple(transformerName)
                    + " injection point " + short_(owner) + "." + member + " does not exist");
        }
    }

    private void checkMember(String transformerName, String targetClassName, ClassNode target,
                             String declared, String what) {
        if (declared == null || declared.isEmpty()) return;
        // A target may be written as a bare name or as name+descriptor, and may carry an owner.
        String member = declared;
        int ownerEnd = member.indexOf(';');
        if (member.startsWith("L") && ownerEnd > 1) member = member.substring(ownerEnd + 1);
        int parenthesis = member.indexOf('(');
        String name = parenthesis < 0 ? member : member.substring(0, parenthesis);
        String desc = parenthesis < 0 ? null : member.substring(parenthesis);
        if ("<init>".equals(name) || "<clinit>".equals(name)) return;

        if (!hasMethod(target, name, desc)) {
            missing.add(simple(transformerName) + " " + what + " "
                    + short_(targetClassName) + "." + member + " does not exist");
        }
    }

    // ------------------------------------------------------------------ lookups

    private boolean hasMethod(ClassNode node, String name, String desc) {
        return hasMethod(node, name, desc, new HashSet<String>());
    }

    private boolean hasMethod(ClassNode node, String name, String desc, Set<String> visited) {
        if (node == null || !visited.add(node.name)) return false;
        for (MethodNode method : node.methods) {
            if (!method.name.equals(name)) continue;
            if (desc == null || method.desc.equals(desc)) return true;
        }
        if (node.superName != null && hasMethod(load(node.superName), name, desc, visited)) {
            return true;
        }
        for (String parent : node.interfaces) {
            if (hasMethod(load(parent), name, desc, visited)) return true;
        }
        return false;
    }

    private boolean hasField(ClassNode node, String name) {
        return hasField(node, name, new HashSet<String>());
    }

    private boolean hasField(ClassNode node, String name, Set<String> visited) {
        if (node == null || !visited.add(node.name)) return false;
        for (FieldNode field : node.fields) {
            if (field.name.equals(name)) return true;
        }
        if (node.superName != null && hasField(load(node.superName), name, visited)) return true;
        for (String parent : node.interfaces) {
            if (hasField(load(parent), name, visited)) return true;
        }
        return false;
    }

    private ClassNode load(String internalName) {
        if (cache.containsKey(internalName)) return cache.get(internalName);
        ClassNode node = null;
        try {
            byte[] bytes = provider.getClass(internalName.replace('/', '.'));
            if (bytes != null && bytes.length > 0) {
                node = new ClassNode();
                new ClassReader(bytes).accept(node,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        } catch (Throwable ignored) {
        }
        cache.put(internalName, node);
        return node;
    }

    // -------------------------------------------------------------- annotations

    private static List<String> readTransformerTargets(ClassNode transformer) {
        AnnotationNode annotation = findAnnotation(transformer.invisibleAnnotations, CTRANSFORMER);
        if (annotation == null) {
            annotation = findAnnotation(transformer.visibleAnnotations, CTRANSFORMER);
        }
        List<String> targets = new ArrayList<>();
        if (annotation == null) return targets;

        Object classes = value(annotation, "value");
        if (classes instanceof List) {
            for (Object entry : (List<?>) classes) {
                if (entry instanceof Type) targets.add(((Type) entry).getInternalName());
            }
        }
        for (String name : stringList(value(annotation, "name"))) {
            targets.add(name.replace('.', '/'));
        }
        return targets;
    }

    private static List<AnnotationNode> targetAnnotations(AnnotationNode injection) {
        List<AnnotationNode> result = new ArrayList<>();
        Object target = value(injection, "target");
        if (target instanceof AnnotationNode) {
            result.add((AnnotationNode) target);
        } else if (target instanceof List) {
            for (Object entry : (List<?>) target) {
                if (entry instanceof AnnotationNode) result.add((AnnotationNode) entry);
            }
        }
        return result;
    }

    private static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return null;
        for (AnnotationNode annotation : annotations) {
            if (descriptor.equals(annotation.desc)) return annotation;
        }
        return null;
    }

    /** ASM stores annotation members as a flat name/value list. */
    private static Object value(AnnotationNode annotation, String name) {
        if (annotation == null || annotation.values == null) return null;
        for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object entry : (List<?>) value) {
                if (entry instanceof String) result.add((String) entry);
            }
        } else if (value instanceof String) {
            result.add((String) value);
        }
        return result;
    }

    private static String simple(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? className : className.substring(dot + 1);
    }

    private static String short_(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? internalName : internalName.substring(slash + 1);
    }

    private static String shortName(String annotationDescriptor) {
        int slash = annotationDescriptor.lastIndexOf('/');
        String name = annotationDescriptor.substring(slash + 1);
        return "@" + name.substring(0, name.length() - 1);
    }
}
