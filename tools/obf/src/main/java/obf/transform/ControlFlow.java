package obf.transform;

import obf.ObfContext;
import obf.Transform;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ControlFlow implements Transform, Opcodes {
    @Override
    public String name() {
        return "cflow";
    }

    @Override
    public void apply(ObfContext ctx) {
        int inserted = 0;
        for (ClassNode owner : ctx.classes().values()) {
            if (ctx.isExcluded(owner.name)) continue;
            for (MethodNode method : owner.methods) {
                if ((method.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;
                Random random = new Random(owner.name.hashCode() * 31L + method.name.hashCode());
                List<AbstractInsnNode> points = new ArrayList<>();
                for (AbstractInsnNode instruction : method.instructions) {
                    int type = instruction.getType();
                    if ((type == AbstractInsnNode.METHOD_INSN || type == AbstractInsnNode.FIELD_INSN)
                            && random.nextInt(5) == 0) points.add(instruction);
                }
                for (AbstractInsnNode point : points) {
                    int value = random.nextInt();
                    InsnList noise = new InsnList();
                    noise.add(new LdcInsnNode(value));
                    noise.add(new LdcInsnNode(value ^ 0x5A5A5A5A));
                    noise.add(new InsnNode(IXOR));
                    noise.add(new InsnNode(POP));
                    method.instructions.insertBefore(point, noise);
                    inserted++;
                }
                if (!points.isEmpty()) method.maxStack = Math.max(method.maxStack, 2);
            }
        }
        System.out.println("  [cflow] " + inserted + " opaque operations inserted");
    }
}
