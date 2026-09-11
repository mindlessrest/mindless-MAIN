package mindless.runtime;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.jar.JarFile;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import static org.junit.Assert.*;

public class PlacementTransformerRegressionTest {
    @Test public void emittedControllerExemptsApprovedBatchAndRescueFromManualSuppression() throws Exception {
        Method find = TransformerCompatibilityTest.class.getDeclaredMethod("findEssentialLoomJar", String.class);
        find.setAccessible(true);
        Constructor<?> provider = Class.forName(TransformerCompatibilityTest.class.getName() + "$SrgFirstClassProvider")
                .getDeclaredConstructor(ClassLoader.class, JarFile[].class);
        provider.setAccessible(true);
        for (String namespace : new String[]{"srg", "mapped"}) {
            Path game = (Path) find.invoke(null, "minecraft-" + namespace + ".jar");
            Path forgePath = (Path) find.invoke(null, "forge-" + namespace + ".jar");
            try (JarFile minecraft = new JarFile(game.toFile()); JarFile forge = new JarFile(forgePath.toFile())) {
                IClassProvider classes = (IClassProvider) provider.newInstance(getClass().getClassLoader(), new JarFile[]{minecraft, forge});
                String target = "net/minecraft/client/multiplayer/PlayerControllerMP";
                byte[] transformed = new MindlessTransformerManager(classes).transform(target, classes.getClass(target));
                assertNotNull(namespace, transformed);
                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                int gates = 0;
                for (MethodNode method : node.methods) {
                    MethodInsnNode authorization = null;
                    JumpInsnNode exemption = null;
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        if (!(instruction instanceof MethodInsnNode)) continue;
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.owner.equals("mindless/placement/PlacementCoordinator") && call.name.equals("shouldBlockControllerAction")) {
                            authorization = call;
                        }
                        if (call.owner.equals("mindless/placement/PlacementCoordinator") && call.name.equals("isControllerAction") && exemption == null) {
                            AbstractInsnNode next = call.getNext();
                            while (next != null && next.getOpcode() < 0) next = next.getNext();
                            assertTrue(namespace, next instanceof JumpInsnNode);
                            exemption = (JumpInsnNode) next;
                            assertEquals(namespace, Opcodes.IFNE, exemption.getOpcode());
                        }
                        if (call.owner.equals("mindless/utility/Utils") && call.name.equals("shouldSuppressManualClicksForModulePlacementTick")) {
                            assertNotNull(namespace, authorization);
                            assertNotNull(namespace, exemption);
                            assertTrue(namespace, method.instructions.indexOf(authorization) < method.instructions.indexOf(exemption));
                            assertTrue(namespace, method.instructions.indexOf(exemption.label) > method.instructions.indexOf(call));
                            gates++;
                        }
                    }
                }
                assertEquals(namespace, 1, gates);
            }
        }
    }
}
