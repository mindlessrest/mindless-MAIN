package obf.transform;

import obf.*;
import obf.util.NameGen;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

public class ControlFlow implements Transform, Opcodes {

    @Override
    public String name() { return "cflow"; }

    @Override
    public void apply(ObfContext ctx) {
        int flattened = 0;
        int junkBranches = 0;

        for (ClassNode cn : ctx.classes().values()) {
            if (ctx.isExcluded(cn.name)) continue;

            for (MethodNode mn : cn.methods) {
                if (mn.instructions.size() < 10) continue;
                if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) continue;

                junkBranches += insertOpaquePredicates(cn, mn);
                if (mn.instructions.size() >= 20 && mn.instructions.size() <= 2000) {
                    if (flatten(cn, mn)) flattened++;
                }
            }
        }

        System.out.println("  [cflow] " + flattened + " methods flattened, "
            + junkBranches + " opaque predicates inserted");
    }

    private boolean flatten(ClassNode cn, MethodNode mn) {
        // Split method into basic blocks, dispatch via switch on a state variable
        try {
            List<Block> blocks = splitBlocks(mn);
            if (blocks.size() < 3) return false;

            // Assign random dispatch IDs
            Random rng = new Random(cn.name.hashCode() ^ mn.name.hashCode() ^ 0xCAFEL);
            int[] ids = new int[blocks.size()];
            Set<Integer> usedIds = new HashSet<>();
            for (int i = 0; i < blocks.size(); i++) {
                int id;
                do { id = rng.nextInt(900000) + 100000; } while (!usedIds.add(id));
                ids[i] = id;
            }

            // Build the dispatcher
            InsnList dispatched = new InsnList();

            // int state = <first block id>;
            int stateVar = mn.maxLocals;
            mn.maxLocals++;

            dispatched.add(intNode(ids[0]));
            dispatched.add(new VarInsnNode(ISTORE, stateVar));

            LabelNode loopLabel = new LabelNode();
            LabelNode defaultLabel = new LabelNode();
            LabelNode endLabel = new LabelNode();

            dispatched.add(loopLabel);
            dispatched.add(new VarInsnNode(ILOAD, stateVar));

            // Build lookup switch
            int[] sortedKeys = ids.clone();
            Arrays.sort(sortedKeys);
            LabelNode[] sortedLabels = new LabelNode[sortedKeys.length];
            LabelNode[] blockLabels = new LabelNode[blocks.size()];
            for (int i = 0; i < blocks.size(); i++) {
                blockLabels[i] = new LabelNode();
            }
            for (int i = 0; i < sortedKeys.length; i++) {
                for (int j = 0; j < ids.length; j++) {
                    if (ids[j] == sortedKeys[i]) {
                        sortedLabels[i] = blockLabels[j];
                        break;
                    }
                }
            }

            dispatched.add(new LookupSwitchInsnNode(defaultLabel, sortedKeys, sortedLabels));

            // Emit each block with state transition at the end
            for (int i = 0; i < blocks.size(); i++) {
                dispatched.add(blockLabels[i]);

                for (AbstractInsnNode insn : blocks.get(i).instructions) {
                    if (isReturn(insn)) {
                        dispatched.add(cloneSimple(insn));
                    } else if (isTerminal(insn) || isConditionalJump(insn)) {
                        // Skip jumps — replaced by state dispatch
                    } else {
                        dispatched.add(cloneSimple(insn));
                    }
                }

                if (!blocks.get(i).endsWithReturn) {
                    int nextIdx = (i + 1 < blocks.size()) ? i + 1 : 0;
                    dispatched.add(intNode(ids[nextIdx]));
                    dispatched.add(new VarInsnNode(ISTORE, stateVar));
                    dispatched.add(new JumpInsnNode(GOTO, loopLabel));
                }
            }

            // Default case — return (shouldn't be reached)
            dispatched.add(defaultLabel);
            dispatched.add(new InsnNode(RETURN));

            mn.instructions = dispatched;
            mn.tryCatchBlocks.clear();
            mn.maxStack = Math.max(mn.maxStack, 4) + 2;

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private int insertOpaquePredicates(ClassNode cn, MethodNode mn) {
        if ((mn.access & (ACC_ABSTRACT | ACC_NATIVE)) != 0) return 0;

        Random rng = new Random(cn.name.hashCode() ^ mn.name.hashCode() ^ 0xBEEFL);
        int count = 0;

        List<AbstractInsnNode> insertPoints = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // Insert before some regular instructions (not labels, frames, etc.)
            if (insn.getType() == AbstractInsnNode.METHOD_INSN ||
                insn.getType() == AbstractInsnNode.FIELD_INSN) {
                if (rng.nextInt(5) == 0) {
                    insertPoints.add(insn);
                }
            }
        }

        for (AbstractInsnNode point : insertPoints) {
            InsnList junk = generateOpaquePredicate(rng, mn);
            if (junk != null) {
                mn.instructions.insertBefore(point, junk);
                count++;
            }
        }

        if (count > 0) mn.maxStack = Math.max(mn.maxStack, 4) + 2;
        return count;
    }

    private InsnList generateOpaquePredicate(Random rng, MethodNode mn) {
        InsnList il = new InsnList();
        int type = rng.nextInt(4);

        LabelNode realCode = new LabelNode();

        switch (type) {
            case 0 -> {
                // (x * x) % 2 == 0 || (x * x) % 2 == 1  → always true
                // But we make the false branch go to junk
                int val = rng.nextInt(1000) + 1;
                il.add(intNode(val));
                il.add(intNode(val));
                il.add(new InsnNode(IMUL));
                il.add(intNode(2));
                il.add(new InsnNode(IREM));
                il.add(new JumpInsnNode(IFGE, realCode)); // always >= 0
                // Dead code — never reached but confuses decompilers
                il.add(intNode(0));
                il.add(new InsnNode(ATHROW)); // throws null
                il.add(realCode);
            }
            case 1 -> {
                // (x | 1) != 0 → always true
                int val = rng.nextInt(50000);
                il.add(intNode(val));
                il.add(intNode(1));
                il.add(new InsnNode(IOR));
                il.add(new JumpInsnNode(IFNE, realCode));
                il.add(new InsnNode(ACONST_NULL));
                il.add(new InsnNode(ATHROW));
                il.add(realCode);
            }
            case 2 -> {
                // x ^ x == 0 → always true
                int val = rng.nextInt(99999);
                il.add(intNode(val));
                il.add(intNode(val));
                il.add(new InsnNode(IXOR));
                il.add(new JumpInsnNode(IFEQ, realCode));
                il.add(new InsnNode(ACONST_NULL));
                il.add(new InsnNode(ATHROW));
                il.add(realCode);
            }
            case 3 -> {
                // Integer.MAX_VALUE > 0 → always true, but not obvious after obf
                il.add(new LdcInsnNode(Integer.MAX_VALUE));
                il.add(new JumpInsnNode(IFGT, realCode));
                il.add(new InsnNode(ACONST_NULL));
                il.add(new InsnNode(ATHROW));
                il.add(realCode);
            }
        }

        return il;
    }

    // ---- Basic block splitting ----

    private static class Block {
        List<AbstractInsnNode> instructions = new ArrayList<>();
        boolean endsWithReturn = false;
    }

    private List<Block> splitBlocks(MethodNode mn) {
        List<Block> blocks = new ArrayList<>();
        Block current = new Block();

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            // Skip non-real instructions
            if (insn.getType() == AbstractInsnNode.LABEL ||
                insn.getType() == AbstractInsnNode.FRAME ||
                insn.getType() == AbstractInsnNode.LINE) {
                continue;
            }

            current.instructions.add(insn);

            if (isReturn(insn)) {
                current.endsWithReturn = true;
                blocks.add(current);
                current = new Block();
            } else if (isTerminal(insn) || isConditionalJump(insn)) {
                blocks.add(current);
                current = new Block();
            }
        }

        if (!current.instructions.isEmpty()) {
            blocks.add(current);
        }

        return blocks;
    }

    private static boolean isReturn(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return op >= IRETURN && op <= RETURN;
    }

    private static boolean isTerminal(AbstractInsnNode insn) {
        return insn.getOpcode() == GOTO || isReturn(insn) || insn.getOpcode() == ATHROW;
    }

    private static boolean isConditionalJump(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        return (op >= IFEQ && op <= IF_ACMPNE) || op == IFNULL || op == IFNONNULL;
    }

    private static AbstractInsnNode cloneSimple(AbstractInsnNode insn) {
        // For non-jump instructions, a direct reconstruction is safest
        if (insn instanceof VarInsnNode v) return new VarInsnNode(v.getOpcode(), v.var);
        if (insn instanceof IntInsnNode i) return new IntInsnNode(i.getOpcode(), i.operand);
        if (insn instanceof LdcInsnNode l) return new LdcInsnNode(l.cst);
        if (insn instanceof FieldInsnNode f)
            return new FieldInsnNode(f.getOpcode(), f.owner, f.name, f.desc);
        if (insn instanceof MethodInsnNode m)
            return new MethodInsnNode(m.getOpcode(), m.owner, m.name, m.desc, m.itf);
        if (insn instanceof TypeInsnNode t) return new TypeInsnNode(t.getOpcode(), t.desc);
        if (insn instanceof InsnNode) return new InsnNode(insn.getOpcode());
        if (insn instanceof IincInsnNode ii) return new IincInsnNode(ii.var, ii.incr);
        if (insn instanceof MultiANewArrayInsnNode ma)
            return new MultiANewArrayInsnNode(ma.desc, ma.dims);
        if (insn instanceof InvokeDynamicInsnNode id)
            return new InvokeDynamicInsnNode(id.name, id.desc, id.bsm, id.bsmArgs);
        // Fallback — skip anything we can't safely clone (labels, frames, switches)
        return new InsnNode(NOP);
    }

    private static AbstractInsnNode intNode(int value) {
        if (value >= -1 && value <= 5) return new InsnNode(ICONST_0 + value);
        if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE)
            return new IntInsnNode(BIPUSH, value);
        if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE)
            return new IntInsnNode(SIPUSH, value);
        return new LdcInsnNode(value);
    }
}
