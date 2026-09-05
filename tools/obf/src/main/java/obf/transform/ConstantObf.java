package obf.transform;

import obf.ObfContext;
import obf.Transform;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Random;

/** Protects integral constants without adding branches or changing stack-map frames. */
public final class ConstantObf implements Transform, Opcodes {
    @Override
    public String name() {
        return "constobf";
    }

    @Override
    public void apply(ObfContext context) {
        int protectedConstants = 0;
        for (var classNode : context.classes().values()) {
            if (context.isExcluded(classNode.name)) continue;
            Random random = new Random(classNode.name.hashCode() ^ 0x434F4E53544F4246L);
            for (var method : classNode.methods) {
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    Number value = integralValue(instruction);
                    if (value == null) continue;

                    InsnList replacement = new InsnList();
                    if (value instanceof Long) {
                        long key = nonZeroLong(random);
                        replacement.add(new LdcInsnNode(value.longValue() ^ key));
                        replacement.add(new LdcInsnNode(key));
                        replacement.add(new InsnNode(LXOR));
                    } else {
                        int key = nonZeroInt(random);
                        replacement.add(intNode(value.intValue() ^ key));
                        replacement.add(intNode(key));
                        replacement.add(new InsnNode(IXOR));
                    }
                    method.instructions.insertBefore(instruction, replacement);
                    method.instructions.remove(instruction);
                    protectedConstants++;
                }
            }
        }
        System.out.println("  [constobf] " + protectedConstants + " integral constants protected");
    }

    private static Number integralValue(AbstractInsnNode instruction) {
        int opcode = instruction.getOpcode();
        if (opcode >= ICONST_M1 && opcode <= ICONST_5) return opcode - ICONST_0;
        if (opcode == LCONST_0 || opcode == LCONST_1) return (long) (opcode - LCONST_0);
        if (instruction instanceof IntInsnNode intInstruction
                && (opcode == BIPUSH || opcode == SIPUSH)) {
            return intInstruction.operand;
        }
        if (instruction instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Integer || ldc.cst instanceof Long) return (Number) ldc.cst;
        }
        return null;
    }

    private static int nonZeroInt(Random random) {
        int value;
        do value = random.nextInt(); while (value == 0);
        return value;
    }

    private static long nonZeroLong(Random random) {
        long value;
        do value = random.nextLong(); while (value == 0L);
        return value;
    }

    private static AbstractInsnNode intNode(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }
}
