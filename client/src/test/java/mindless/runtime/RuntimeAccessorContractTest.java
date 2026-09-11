package mindless.runtime;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertTrue;
public class RuntimeAccessorContractTest {
    @Test
    public void silentMiningDoesNotRequireForgeItemExtensions() throws Exception {
        List<String> violations = new ArrayList<>();
        try (InputStream bytes = getClass().getResourceAsStream("/mindless/module/impl/player/SilentBedBreaker.class")) {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM5) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM5) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                            if (owner.equals("net/minecraft/item/Item") && method.equals("getDigSpeed")) {
                                violations.add(name + ": " + owner + "." + method + descriptor);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue("Lunar 1.8.9 does not provide Forge's Item.getDigSpeed extension: " + violations, violations.isEmpty());
    }

    @Test
    public void productionCodeDoesNotCastToMixinAccessors() throws Exception {
        Path root = Paths.get("src", "main", "java", "mindless");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.toString().contains("mixin"))
                    .filter(path -> !path.endsWith("AccessorBridge.java"))
                    .forEach(path -> {
                        try {
                            String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                            if (source.matches("(?s).*\\(\\s*(?:IAccessor|IMixin|ISaturation)[A-Za-z0-9_]*\\s*\\).*")) {
                                violations.add(path.toString());
                            }
                        } catch (Exception failure) {
                            throw new RuntimeException(failure);
                        }
                    });
        }
        assertTrue("Mixin-only interface casts are illegal after JVMTI class loading: " + violations,
                violations.isEmpty());
    }
}
