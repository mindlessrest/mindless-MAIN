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
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.Assert.*;

public class AuraMovementPhaseTest {
    @Test
    public void combatRunsInTheInputPhaseBeforePlayerMovement() throws Exception {
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
                for (String target : new String[]{"net/minecraft/client/Minecraft", "net/minecraft/client/entity/EntityPlayerSP"}) {
                    byte[] transformed = new MindlessTransformerManager(classes).transform(target, classes.getClass(target));
                    assertNotNull(namespace, transformed);
                    ClassNode node = new ClassNode();
                    new ClassReader(transformed).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    if (target.endsWith("/Minecraft")) assertInputCombat(namespace, node, false);
                    else assertNoPlayerCombat(namespace, node);
                }
            }
        }
        for (String type : new String[]{"client/MixinMinecraft", "entity/MixinEntityPlayerSP"}) {
            try (InputStream stream = getClass().getResourceAsStream("/mindless/mixin/impl/" + type + ".class")) {
                assertNotNull(stream);
                ClassNode node = new ClassNode();
                new ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (type.endsWith("/MixinMinecraft")) assertInputCombat("forge", node, true);
                else assertNoPlayerCombat("forge", node);
            }
        }
    }

    private static void assertNoPlayerCombat(String backend, ClassNode node) {
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode)) continue;
            MethodInsnNode call = (MethodInsnNode) instruction;
            assertFalse(backend + " combat in player update: " + method.name,
                    call.owner.equals("mindless/module/impl/combat/KillAura")
                            && (call.name.equals("beforePlayerInteraction") || call.name.equals("afterMotionResolved")));
        }
    }

    private static void assertInputCombat(String backend, ClassNode node, boolean mixin) {
        int total = 0;
        for (MethodNode method : node.methods) {
            int combat = -1, pick = -1, rotations = -1, interaction = -1, movement = -1;
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                int index = method.instructions.indexOf(call);
                if (call.owner.equals("mindless/module/impl/combat/KillAura") && call.name.equals("beforePlayerInteraction")) {
                    combat = index;
                    total++;
                }
                if (call.owner.equals("mindless/helper/RotationHelper") && call.name.equals("updateServerRotations")) rotations = index;
                if (call.name.equals("getMouseOver") || call.name.equals("func_78473_a")) pick = index;
                if (call.owner.equals("mindless/event/PrePlayerInteractEvent") && call.name.equals("<init>")) interaction = index;
                if (call.name.equals("updateEntities") || call.name.equals("func_72939_s")) movement = index;
            }
            if (combat < 0) continue;
            assertTrue(backend + " interaction handlers precede combat", interaction >= 0 && interaction < combat);
            if (mixin) {
                assertEquals("beforePlayerInteraction", method.name);
                org.objectweb.asm.tree.AnnotationNode inject = method.visibleAnnotations.stream()
                        .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/injection/Inject;")).findFirst().get();
                assertTrue(inject.values.toString().contains("runTick"));
                int at = inject.values.indexOf("at");
                org.objectweb.asm.tree.AnnotationNode location = (org.objectweb.asm.tree.AnnotationNode)
                        ((java.util.List<?>) inject.values.get(at + 1)).get(0);
                assertTrue(location.values.toString().contains("chatVisibility"));
            } else {
                assertTrue(method.name.equals("runTick") || method.name.equals("func_71407_l"));
                assertTrue(backend + " resolve rotations before tick pick", rotations >= 0 && rotations < pick);
                assertTrue(backend + " pick before combat", pick < combat);
                assertTrue(backend + " combat before world movement", movement > combat);
            }
        }
        assertEquals(backend + " exactly one input combat call", 1, total);
    }
}
