package obf.transform;

import obf.*;
import obf.util.NameGen;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class StringObf implements Transform, Opcodes {

    @Override
    public String name() { return "stringobf"; }

    @Override
    public void apply(ObfContext ctx) {
        if (!ctx.config().stringObf.enabled) return;

        NameGen gen = new NameGen(8, 20);
        int total = 0;

        for (ClassNode cn : ctx.classes().values()) {
            if (ctx.isExcluded(cn.name)) continue;

            List<StringEntry> strings = new ArrayList<>();

            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
                        if (s.isEmpty()) continue;
                        strings.add(new StringEntry(mn, ldc, s));
                    }
                }
            }

            if (strings.isEmpty()) continue;

            // Generate a per-class XOR key
            Random rng = new Random(cn.name.hashCode() ^ 0xDEADCAFEL);
            int key = rng.nextInt(0xFFFF) + 1;

            // Create the decryptor method
            String decryptName = gen.next();
            String decryptDesc = "(Ljava/lang/String;)Ljava/lang/String;";

            MethodNode decryptor = new MethodNode(
                ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC | ACC_BRIDGE,
                decryptName, decryptDesc, null, null);

            // Bytecode: char[] c = s.toCharArray(); for each char ^= key; return new String(c);
            InsnList il = decryptor.instructions;
            il.add(new VarInsnNode(ALOAD, 0));
            il.add(new MethodInsnNode(INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
            il.add(new VarInsnNode(ASTORE, 1));      // char[] c = ...

            il.add(new InsnNode(ICONST_0));
            il.add(new VarInsnNode(ISTORE, 2));       // int i = 0

            LabelNode loopStart = new LabelNode();
            LabelNode loopEnd = new LabelNode();

            il.add(loopStart);
            il.add(new FrameNode(F_APPEND, 2, new Object[]{"[C", INTEGER}, 0, null));
            il.add(new VarInsnNode(ILOAD, 2));
            il.add(new VarInsnNode(ALOAD, 1));
            il.add(new InsnNode(ARRAYLENGTH));
            il.add(new JumpInsnNode(IF_ICMPGE, loopEnd));

            // c[i] ^= (key + i) & 0xFFFF
            il.add(new VarInsnNode(ALOAD, 1));
            il.add(new VarInsnNode(ILOAD, 2));
            il.add(new InsnNode(DUP2));
            il.add(new InsnNode(CALOAD));
            il.add(intNode(key));
            il.add(new VarInsnNode(ILOAD, 2));
            il.add(new InsnNode(IADD));
            il.add(new InsnNode(IXOR));
            il.add(new InsnNode(I2C));
            il.add(new InsnNode(CASTORE));

            il.add(new IincInsnNode(2, 1));
            il.add(new JumpInsnNode(GOTO, loopStart));

            il.add(loopEnd);
            il.add(new FrameNode(F_SAME, 0, null, 0, null));
            il.add(new TypeInsnNode(NEW, "java/lang/String"));
            il.add(new InsnNode(DUP));
            il.add(new VarInsnNode(ALOAD, 1));
            il.add(new MethodInsnNode(INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
            il.add(new InsnNode(ARETURN));

            decryptor.maxStack = 6;
            decryptor.maxLocals = 3;
            cn.methods.add(decryptor);

            // Replace all string LDCs with encrypted + decrypt call
            for (StringEntry entry : strings) {
                String encrypted = encrypt(entry.value, key);
                entry.ldc.cst = encrypted;
                entry.method.instructions.insert(entry.ldc,
                    new MethodInsnNode(INVOKESTATIC, cn.name, decryptName, decryptDesc, false));
                total++;
            }
        }

        System.out.println("  [stringobf] " + total + " strings encrypted");
    }

    private static String encrypt(String plain, int key) {
        char[] chars = plain.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] ^= (key + i) & 0xFFFF;
        }
        return new String(chars);
    }

    private static AbstractInsnNode intNode(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            return new IntInsnNode(Opcodes.BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            return new IntInsnNode(Opcodes.SIPUSH, value);
        return new LdcInsnNode(value);
    }

    private record StringEntry(MethodNode method, LdcInsnNode ldc, String value) {}
}
