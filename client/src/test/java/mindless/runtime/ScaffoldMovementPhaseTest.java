package mindless.runtime;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.jar.JarFile;
import net.lenni0451.classtransform.utils.tree.IClassProvider;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class ScaffoldMovementPhaseTest {
    @Test public void sprintIsResolvedAfterInputBeforeJumpAndAcceleration() throws Exception {
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
                MindlessTransformerManager manager = new MindlessTransformerManager(classes);
                for (String target : new String[]{"net/minecraft/client/entity/EntityPlayerSP", "net/minecraft/entity/EntityLivingBase"}) {
                    byte[] transformed = manager.transform(target, classes.getClass(target));
                    assertNotNull(transformed);
                    ClassNode node = new ClassNode();
                    new ClassReader(transformed).accept(node, 0);
                    if (target.endsWith("EntityPlayerSP")) assertInputPrecedesSuper(node);
                    else assertSprintPrecedesPhysics(node);
                }
            }
        }
        try (InputStream input = getClass().getResourceAsStream("/mindless/mixin/impl/entity/MixinEntityPlayerSP.class")) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            assertInputPrecedesSuper(node);
        }
        try (InputStream input = getClass().getResourceAsStream("/mindless/mixin/impl/entity/MixinEntityLivingBase.class")) {
            ClassNode node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            int hooks = 0;
            for (MethodNode method : node.methods) {
                if (callIndex(method, "mindless/module/impl/player/Scaffold", "beforeLivingMovement") < 0) continue;
                hooks++;
                AnnotationNode inject = method.visibleAnnotations.stream()
                        .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().get();
                assertTrue(inject.values.toString().contains("onLivingUpdate"));
                AnnotationNode at = (AnnotationNode) ((java.util.List<?>) inject.values.get(inject.values.indexOf("at") + 1)).get(0);
                assertTrue(at.values.contains("HEAD"));
            }
            assertEquals(1, hooks);
        }
    }

    private static void assertInputPrecedesSuper(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals("onLivingUpdate") && !method.name.equals("func_70636_d")) continue;
            int input = callIndex(method, null, "updatePlayerMoveState", "func_78898_a");
            int parent = callIndex(method, "net/minecraft/client/entity/AbstractClientPlayer", "onLivingUpdate", "func_70636_d");
            assertTrue(node.name + " current input before parent movement", input >= 0 && parent > input);
            return;
        }
        fail("missing player movement method");
    }

    private static void assertSprintPrecedesPhysics(ClassNode node) {
        for (MethodNode method : node.methods) {
            if (!method.name.equals("onLivingUpdate") && !method.name.equals("func_70636_d")) continue;
            int scaffold = callIndex(method, "mindless/module/impl/player/Scaffold", "beforeLivingMovement");
            int jump = callIndex(method, null, "jump", "func_70664_aZ");
            int movement = callIndex(method, null, "moveEntityWithHeading", "func_70612_e");
            assertTrue("Scaffold sprint decision before jump", scaffold >= 0 && jump > scaffold);
            assertTrue("Scaffold sprint decision before acceleration", movement > scaffold);
            return;
        }
        fail("missing base movement method");
    }

    private static int callIndex(MethodNode method, String owner, String... names) {
        for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            if (owner != null && !owner.equals(call.owner)) continue;
            for (String name : names) if (name.equals(call.name)) return method.instructions.indexOf(call);
        }
        return -1;
    }
}
