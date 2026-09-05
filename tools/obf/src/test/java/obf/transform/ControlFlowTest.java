package obf.transform;

import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFlowTest {
    @Test
    void protectsOrdinaryMethodsAndPreservesBehavior() throws Exception {
        ObfContext context = contextWithValueMethod();
        MethodNode value = context.classes().get("mindless/test/Sample").methods.get(0);
        int originalSize = value.instructions.size();

        new ControlFlow().apply(context);

        assertTrue(value.instructions.size() > originalSize);
        ClassNode node = context.classes().get("mindless/test/Sample");
        new Analyzer<>(new BasicInterpreter()).analyze(node.name, value);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Class<?> sample = new ByteArrayLoader().define(writer.toByteArray());
        Method method = sample.getMethod("value");
        assertEquals(42, method.invoke(null));
    }

    @Test
    void skipsAnnotatedCallbacks() {
        ObfContext context = contextWithValueMethod();
        MethodNode value = context.classes().get("mindless/test/Sample").methods.get(0);
        value.visibleAnnotations = List.of(new AnnotationNode(
                "Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;"));
        int originalSize = value.instructions.size();

        new ControlFlow().apply(context);

        assertEquals(originalSize, value.instructions.size());
    }

    @Test
    void skipsConstructorsAndTinyMethods() {
        ObfContext context = contextWithValueMethod();
        ClassNode node = context.classes().get("mindless/test/Sample");
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(constructor);
        MethodNode tiny = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "tiny", "()V", null, null);
        tiny.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(tiny);

        new ControlFlow().apply(context);

        assertEquals(1, constructor.instructions.size());
        assertEquals(1, tiny.instructions.size());
    }

    private ObfContext contextWithValueMethod() {
        ObfContext context = new ObfContext(ObfConfig.defaults("input.jar", "output.jar"));
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "mindless/test/Sample";
        node.superName = "java/lang/Object";
        MethodNode value = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        for (int i = 0; i < 8; i++) value.instructions.add(new InsnNode(Opcodes.NOP));
        value.instructions.add(new LdcInsnNode(42));
        value.instructions.add(new InsnNode(Opcodes.IRETURN));
        value.maxStack = 1;
        node.methods.add(value);
        context.classes().put(node.name, node);
        return context;
    }

    private static class ByteArrayLoader extends ClassLoader {
        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
