package mindless.runtime;
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
final class TransformerAudit {
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
List<String> audit(List<String> transformerClassNames) {
        missing.clear();
        unreadable.clear();
        for (String transformerName : transformerClassNames) {
            try {
                auditTransformer(transformerName);
            } catch (Throwable failure) {
                missing.add(transformerName + ": could not be audited (" + failure + ")");
            }
        }
        return new ArrayList<>(missing);
    }
Set<String> unreadableTargets() {
        return unreadable;
    }
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
                continue;
            }
            if (!isReadable(target)) {
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
                    for (AnnotationNode targetAnnotation : targetAnnotations(injection)) {
                        checkInstructionTarget(transformerName, targetAnnotation);
                    }
                }
            }
        }
    }
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
            boolean optional = Boolean.TRUE.equals(value(targetAnnotation, "optional"));
            missing.add((optional ? OPTIONAL_PREFIX : "") + simple(transformerName)
                    + " injection point " + short_(owner) + "." + member + " does not exist");
        }
    }

    private void checkMember(String transformerName, String targetClassName, ClassNode target,
                             String declared, String what) {
        if (declared == null || declared.isEmpty()) return;
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
