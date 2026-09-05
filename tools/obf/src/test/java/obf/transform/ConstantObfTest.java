package obf.transform;

import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConstantObfTest {
    @Test
    void protectsConstantsWithoutChangingBehaviorOrAddingFrames() throws Exception {
        ObfContext context = new ObfContext(ObfConfig.defaults("in.jar", "out.jar"));
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "mindless/test/ProtectedConstants";
        node.superName = "java/lang/Object";

        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "value", "()J", null, null);
        value.instructions.add(new LdcInsnNode(0x123456789ABCDEFL));
        value.instructions.add(new InsnNode(Opcodes.LRETURN));
        node.methods.add(value);
        context.classes().put(node.name, node);

        new ConstantObf().apply(context);

        assertTrue(value.instructions.size() >= 4);
        assertTrue(java.util.Arrays.stream(value.instructions.toArray())
                .noneMatch(FrameNode.class::isInstance));

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        new ClassReader(bytes).accept(new ClassNode(), ClassReader.EXPAND_FRAMES);

        Class<?> generated = new ClassLoader() {
            Class<?> define() { return defineClass(null, bytes, 0, bytes.length); }
        }.define();
        Method method = generated.getMethod("value");
        assertEquals(0x123456789ABCDEFL, method.invoke(null));
    }
}
