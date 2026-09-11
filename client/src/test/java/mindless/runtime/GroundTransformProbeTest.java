package mindless.runtime;

import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.junit.Assert;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class GroundTransformProbeTest {
    @Test
    public void emittedPlayerPacketGroundComesFromFinalEvent() throws Exception {
        Class<?> fixture = TransformerCompatibilityTest.class;
        Method findJar = fixture.getDeclaredMethod("findEssentialLoomJar", String.class);
        findJar.setAccessible(true);
        Method readClass = fixture.getDeclaredMethod("readClass", String.class, JarFile[].class);
        readClass.setAccessible(true);
        Class<?> providerType = Class.forName(
                "mindless.runtime.TransformerCompatibilityTest$SrgFirstClassProvider");
        Constructor<?> providerConstructor = providerType.getDeclaredConstructor(
                ClassLoader.class, JarFile[].class);
        providerConstructor.setAccessible(true);

        for (String namespace : new String[]{"srg", "mapped"}) {
            Path gamePath = (Path) findJar.invoke(null, "minecraft-" + namespace + ".jar");
            Path forgePath = (Path) findJar.invoke(null, "forge-" + namespace + ".jar");
            try (JarFile game = new JarFile(gamePath.toFile());
                 JarFile forge = new JarFile(forgePath.toFile())) {
                JarFile[] jars = new JarFile[]{game, forge};
                IClassProvider provider = (IClassProvider) providerConstructor.newInstance(
                        fixture.getClassLoader(), jars);
                MindlessTransformerManager manager = new MindlessTransformerManager(provider);
                String target = "net/minecraft/client/entity/EntityPlayerSP";
                byte[] original = (byte[]) readClass.invoke(null, target, jars);
                byte[] transformed = manager.transform(target, original);
                Assert.assertNotNull(namespace + " transform", transformed);

                ClassNode node = new ClassNode();
                new ClassReader(transformed).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                MethodNode walking = null;
                for (MethodNode candidate : node.methods) {
                    if (("onUpdateWalkingPlayer".equals(candidate.name)
                            || "func_175161_p".equals(candidate.name))
                            && "()V".equals(candidate.desc)) {
                        walking = candidate;
                        break;
                    }
                }
                Assert.assertNotNull(namespace + " walking method", walking);

                int eventGroundGetters = 0;
                List<String> packetGroundSources = new ArrayList<>();
                for (AbstractInsnNode instruction : walking.instructions.toArray()) {
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if ("mindless/event/PreMotionEvent".equals(call.owner)
                                && "isOnGround".equals(call.name)) {
                            eventGroundGetters++;
                        }
                        if (call.getOpcode() == Opcodes.INVOKESPECIAL
                                && "<init>".equals(call.name)
                                && call.owner.contains("C03PacketPlayer")) {
                            AbstractInsnNode source = previousReal(instruction);
                            packetGroundSources.add(describe(source));
                        }
                    }
                }
                System.out.println("namespace=" + namespace
                        + " method=" + walking.name
                        + " packetConstructors=" + packetGroundSources.size()
                        + " eventGroundGetterCalls=" + eventGroundGetters
                        + " immediateGroundSources=" + packetGroundSources);
                Assert.assertEquals(namespace + " packet variants", 5, packetGroundSources.size());
                Assert.assertEquals(namespace + " every packet consumes final event ground", 5, eventGroundGetters);
                for (String source : packetGroundSources) {
                    Assert.assertTrue(namespace + " packet ground source: " + source,
                            source.equals("CALL mindless/event/PreMotionEvent.isOnGround()Z"));
                }
            }
        }
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode instruction) {
        AbstractInsnNode cursor = instruction.getPrevious();
        while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
        return cursor;
    }

    private static String describe(AbstractInsnNode instruction) {
        if (instruction instanceof FieldInsnNode) {
            FieldInsnNode field = (FieldInsnNode) instruction;
            return (field.getOpcode() == Opcodes.GETFIELD ? "GETFIELD " : "FIELD_OPCODE_")
                    + field.owner + "." + field.name + ":" + field.desc;
        }
        if (instruction instanceof MethodInsnNode) {
            MethodInsnNode method = (MethodInsnNode) instruction;
            return "CALL " + method.owner + "." + method.name + method.desc;
        }
        return instruction == null ? "null" : "opcode=" + instruction.getOpcode();
    }
}
