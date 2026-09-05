package obf.transform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import obf.ObfConfig;
import obf.ObfContext;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AnnotationNode;

import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenamerTest {
    @Test
    void renamesMixinClassesAndSynchronizesConfigWithoutRenamingMembers() {
        ObfConfig config = ObfConfig.defaults("input.jar", "output.jar");
        ObfContext context = new ObfContext(config);
        ClassNode mixin = classNode("mindless/mixin/impl/client/MixinMinecraft");
        mixin.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "shadowedField", "I", null, null));
        mixin.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "injectedMethod", "()V", null, null));
        context.classes().put(mixin.name, mixin);
        context.resources().put("mixins.mindless.json", ("{"
                + "\"package\":\"mindless.mixin.impl\","
                + "\"mixins\":[\"client.MixinMinecraft\"]"
                + "}").getBytes(StandardCharsets.UTF_8));

        new Renamer().apply(context);

        String mapped = context.classMapping().get(mixin.name);
        assertNotNull(mapped);
        assertNotEquals(mixin.name, mapped);
        assertTrue(context.classes().containsKey(mapped));
        ClassNode renamed = context.classes().get(mapped);
        assertEquals("shadowedField", renamed.fields.get(0).name);
        assertEquals("injectedMethod", renamed.methods.get(0).name);

        JsonObject json = JsonParser.parseString(new String(
                context.resources().get("mixins.mindless.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        String configured = json.get("package").getAsString().replace('.', '/') + "/"
                + json.getAsJsonArray("mixins").get(0).getAsString().replace('.', '/');
        assertEquals(mapped, configured);
    }

    @Test
    void preservesAndRemapsManifestServicesAndInnerClasses() {
        ObfConfig config = ObfConfig.defaults("input.jar", "output.jar");
        ObfContext context = new ObfContext(config);
        ClassNode outer = classNode("mindless/example/Bootstrap");
        ClassNode inner = classNode("mindless/example/Bootstrap$Worker");
        context.classes().put(inner.name, inner);
        context.classes().put(outer.name, outer);
        context.resources().put("META-INF/services/mindless.example.Bootstrap",
                "mindless.example.Bootstrap\n".getBytes(StandardCharsets.UTF_8));
        context.resources().put("META-INF/OLD.SF", new byte[]{1});
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "mindless.example.Bootstrap");
        context.manifest(manifest);

        new Renamer().apply(context);

        String mappedOuter = context.classMapping().get(outer.name);
        String mappedInner = context.classMapping().get(inner.name);
        assertTrue(mappedInner.startsWith(mappedOuter + "$"));
        assertEquals(mappedOuter.replace('/', '.'),
                context.manifest().getMainAttributes().getValue(Attributes.Name.MAIN_CLASS));
        String serviceName = "META-INF/services/" + mappedOuter.replace('/', '.');
        assertTrue(context.resources().containsKey(serviceName));
        assertEquals(mappedOuter.replace('/', '.') + "\n",
                new String(context.resources().get(serviceName), StandardCharsets.UTF_8));
        assertFalse(context.resources().containsKey("META-INF/OLD.SF"));
    }

    @Test
    void preservesExternalAndAnnotatedMembersWhileRenamingPrivateImplementation() {
        ObfConfig config = ObfConfig.defaults("input.jar", "output.jar");
        ObfContext context = new ObfContext(config);
        ClassNode owner = classNode("mindless/example/Feature");
        owner.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "publicState", "I", null, null));
        owner.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "privateState", "I", null, null));
        FieldNode annotatedField = new FieldNode(Opcodes.ACC_PRIVATE, "serializedState", "I", null, null);
        annotatedField.visibleAnnotations = java.util.List.of(new AnnotationNode("Lcom/google/gson/annotations/SerializedName;"));
        owner.fields.add(annotatedField);
        owner.methods.add(new MethodNode(Opcodes.ACC_PUBLIC, "eventHandler", "()V", null, null));
        owner.methods.add(new MethodNode(Opcodes.ACC_PRIVATE, "implementation", "()V", null, null));
        MethodNode annotatedMethod = new MethodNode(Opcodes.ACC_PRIVATE, "subscribed", "()V", null, null);
        annotatedMethod.visibleAnnotations = java.util.List.of(new AnnotationNode("Lnet/minecraftforge/fml/common/eventhandler/SubscribeEvent;"));
        owner.methods.add(annotatedMethod);
        context.classes().put(owner.name, owner);

        new Renamer().apply(context);

        ClassNode renamed = context.classes().get(context.classMapping().get(owner.name));
        assertEquals("publicState", renamed.fields.get(0).name);
        assertNotEquals("privateState", renamed.fields.get(1).name);
        assertEquals("serializedState", renamed.fields.get(2).name);
        assertEquals("eventHandler", renamed.methods.get(0).name);
        assertNotEquals("implementation", renamed.methods.get(1).name);
        assertEquals("subscribed", renamed.methods.get(2).name);
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
