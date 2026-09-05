package obf.transform;

import obf.ObfContext;
import obf.Transform;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Random;

public class ControlFlow implements Transform, Opcodes {
    private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

    @Override
    public String name() {
        return "cflow";
    }

    @Override
    public void apply(ObfContext ctx) {
        int transformed = 0;
        for (ClassNode owner : ctx.classes().values()) {
            if (ctx.isExcluded(owner.name) || hasAnnotation(owner.visibleAnnotations, MIXIN)
                    || hasAnnotation(owner.invisibleAnnotations, MIXIN)) continue;
            for (MethodNode method : owner.methods) {
                if (!canTransform(method)) continue;
                long seed = 31L * owner.name.hashCode() + method.name.hashCode() * 17L + method.desc.hashCode();
                Random random = new Random(seed);
                if (random.nextInt(100) >= 45) continue;
                method.instructions.insert(createDiamond(random));
                method.maxStack = Math.max(method.maxStack, 2);
                transformed++;
            }
        }
        System.out.println("  [cflow] " + transformed + " methods protected with opaque branches");
    }

    private boolean canTransform(MethodNode method) {
        return (method.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0
                && !method.name.startsWith("<")
                && method.instructions.size() >= 8
                && (method.visibleAnnotations == null || method.visibleAnnotations.isEmpty())
                && (method.invisibleAnnotations == null || method.invisibleAnnotations.isEmpty());
    }

    private InsnList createDiamond(Random random) {
        int salt = random.nextInt();
        LabelNode alternate = new LabelNode();
        LabelNode merge = new LabelNode();
        InsnList code = new InsnList();
        code.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        code.add(new MethodInsnNode(INVOKESTATIC, "java/lang/System", "identityHashCode", "(Ljava/lang/Object;)I", false));
        code.add(new LdcInsnNode(salt));
        code.add(new InsnNode(IXOR));
        code.add(new InsnNode(ICONST_1));
        code.add(new InsnNode(IAND));
        code.add(new JumpInsnNode(IFEQ, alternate));
        addBalancedNoise(code, random);
        code.add(new JumpInsnNode(GOTO, merge));
        code.add(alternate);
        code.add(new FrameNode(F_SAME, 0, null, 0, null));
        addBalancedNoise(code, random);
        code.add(merge);
        code.add(new FrameNode(F_SAME, 0, null, 0, null));
        return code;
    }

    private void addBalancedNoise(InsnList code, Random random) {
        int left = random.nextInt();
        int right = random.nextInt();
        code.add(new LdcInsnNode(left));
        code.add(new LdcInsnNode(right));
        code.add(new InsnNode(IXOR));
        code.add(new LdcInsnNode(left ^ right));
        code.add(new InsnNode(ISUB));
        code.add(new InsnNode(POP));
    }

    private boolean hasAnnotation(List<AnnotationNode> annotations, String descriptor) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) if (descriptor.equals(annotation.desc)) return true;
        return false;
    }
}
