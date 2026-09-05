package obf.transform;

import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;

import java.lang.reflect.Method;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
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
        for (var instruction : value.instructions.toArray()) {
            if (instruction instanceof FrameNode frame) {
                assertEquals(Opcodes.F_NEW, frame.type,
                        "expanded input must not be mixed with compressed frames");
            }
        }
        ClassNode node = context.classes().get("mindless/test/Sample");
        new Analyzer<>(new BasicInterpreter()).analyze(node.name, value);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] firstWrite = writer.toByteArray();

        // Lunar/Genesis reads and rewrites classes after Mindless loads. A
        // second expanded-frame round trip catches malformed StackMapTable
        // offsets that a direct JVM define alone can miss.
        ClassNode reparsed = new ClassNode();
        new ClassReader(firstWrite).accept(reparsed, ClassReader.EXPAND_FRAMES);
        ClassWriter secondWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        reparsed.accept(secondWriter);
        byte[] secondWrite = secondWriter.toByteArray();
        ClassNode verified = new ClassNode();
        new ClassReader(secondWrite).accept(verified, ClassReader.EXPAND_FRAMES);
        parseWithLegacyAsm(secondWrite);

        Class<?> sample = new ByteArrayLoader().define(secondWrite);
        Method method = sample.getMethod("value");
        assertEquals(42, method.invoke(null));
    }

    private void parseWithLegacyAsm(byte[] bytes) throws Exception {
        String classpath = System.getProperty("legacyAsmClasspath");
        assertTrue(classpath != null && !classpath.isBlank(), "legacy ASM test classpath missing");
        URL[] urls = Arrays.stream(classpath.split(File.pathSeparator))
                .map(File::new)
                .map(file -> {
                    try {
                        return file.toURI().toURL();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toArray(URL[]::new);
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            Class<?> readerClass = loader.loadClass("org.objectweb.asm.ClassReader");
            Class<?> visitorClass = loader.loadClass("org.objectweb.asm.ClassVisitor");
            Object reader = readerClass.getConstructor(byte[].class).newInstance((Object) bytes);
            Object node = loader.loadClass("org.objectweb.asm.tree.ClassNode").getConstructor().newInstance();
            readerClass.getMethod("accept", visitorClass, int.class).invoke(reader, node, 0);
        }
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
