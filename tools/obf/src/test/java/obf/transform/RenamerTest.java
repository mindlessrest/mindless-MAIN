package obf.transform;

import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RenamerTest {
    @Test
    void preservesConfiguredMixinClassesAndMembers() {
        ObfConfig config = ObfConfig.defaults("input.jar", "output.jar");
        ObfContext context = new ObfContext(config);

        ClassNode mixin = classNode("mindless/mixin/impl/client/MixinMinecraft");
        mixin.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "shadowedField", "I", null, null));
        mixin.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "injectedMethod", "()V", null, null));
        ClassNode regular = classNode("mindless/module/RegularModule");

        context.classes().put(mixin.name, mixin);
        context.classes().put(regular.name, regular);
        context.resources().put("mixins.mindless.json", ("{"
                + "\"package\":\"mindless.mixin.impl\","
                + "\"mixins\":[\"client.MixinMinecraft\"]"
                + "}").getBytes(StandardCharsets.UTF_8));

        new Renamer().apply(context);

        assertTrue(context.classes().containsKey("mindless/mixin/impl/client/MixinMinecraft"));
        assertFalse(context.classes().containsKey("mindless/module/RegularModule"));
        ClassNode preserved = context.classes().get("mindless/mixin/impl/client/MixinMinecraft");
        assertEquals("shadowedField", preserved.fields.get(0).name);
        assertEquals("injectedMethod", preserved.methods.get(0).name);
    }

    private ClassNode classNode(String name) {
        ClassNode node = new ClassNode();
        node.version = Opcodes.V1_8;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = name;
        node.superName = "java/lang/Object";
        return node;
    }
}
