package obf.transform;

import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringObfTest {
    @Test
    void doesNotChangeTransformerClassShape() {
        ObfContext context = new ObfContext(ObfConfig.defaults("input.jar", "output.jar"));
        ClassNode transformer = new ClassNode();
        transformer.version = Opcodes.V1_8;
        transformer.access = Opcodes.ACC_PUBLIC;
        transformer.name = "mindless/transformer/impl/render/TransformerFontRenderer";
        transformer.superName = "java/lang/Object";
        MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE, "renderStringHead", "()V", null, null);
        method.instructions.add(new LdcInsnNode("renderString"));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        transformer.methods.add(method);
        context.classes().put(transformer.name, transformer);

        new StringObf().apply(context);

        assertEquals(1, transformer.methods.size());
        assertEquals("renderStringHead", transformer.methods.get(0).name);
        assertEquals("renderString", ((LdcInsnNode) transformer.methods.get(0).instructions.getFirst()).cst);
    }
}
