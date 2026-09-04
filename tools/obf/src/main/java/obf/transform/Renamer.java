package obf.transform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import obf.ObfConfig;
import obf.ObfContext;
import obf.Transform;
import obf.util.NameGen;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.jar.Attributes;

public class Renamer implements Transform {
    private static final Set<String> TEXT_EXTENSIONS = Set.of(
            ".json", ".info", ".cfg", ".conf", ".properties", ".xml", ".txt", ".yml", ".yaml");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String name() {
        return "renamer";
    }

    @Override
    public void apply(ObfContext ctx) {
        ObfConfig.RenamerConfig cfg = ctx.config().renamer;
        NameGen classGen = new NameGen(cfg.minLength, cfg.maxLength, cfg.alphabet);
        Map<String, String> classMap = ctx.classMapping();
        Map<String, String> fieldMap = ctx.fieldMapping();
        Map<String, String> methodMap = ctx.methodMapping();
        Set<String> mixinClasses = findMixinClasses(ctx);
        Set<String> reflectiveNames = findStringConstants(ctx);

        if (cfg.renameClasses) buildClassMappings(ctx, cfg, classGen, classMap);
        if (cfg.renameFields) buildFieldMappings(ctx, mixinClasses, reflectiveNames, fieldMap);
        if (cfg.renameMethods) buildMethodMappings(ctx, mixinClasses, reflectiveNames, methodMap);

        Remapper remapper = createRemapper(ctx);
        remapStringConstants(ctx, classMap);
        Map<String, ClassNode> remapped = new LinkedHashMap<>();
        for (ClassNode original : ctx.classes().values()) {
            ClassNode renamed = new ClassNode();
            original.accept(new ClassRemapper(renamed, remapper));
            remapped.put(renamed.name, renamed);
        }
        ctx.classes().clear();
        ctx.classes().putAll(remapped);

        rewriteMixinConfigs(ctx, classMap);
        rewriteResources(ctx, classMap);
        rewriteManifest(ctx, classMap);

        System.out.println("  [renamer] " + classMap.size() + " classes, "
                + fieldMap.size() + " fields, " + methodMap.size() + " methods renamed");
    }

    private void buildClassMappings(ObfContext ctx, ObfConfig.RenamerConfig cfg,
                                    NameGen classGen, Map<String, String> classMap) {
        String prefix = normalizePrefix(cfg.packagePrefix);
        NameGen packageGen = new NameGen(42, cfg.minLength, cfg.maxLength, cfg.alphabet);
        Map<String, String> packageMap = new HashMap<>();
        List<String> names = new ArrayList<>(ctx.classes().keySet());
        names.sort(Comparator.comparingInt(this::innerDepth));
        for (String name : names) {
            if (ctx.isExcluded(name)) continue;
            int slash = name.lastIndexOf('/');
            String simpleName = slash < 0 ? name : name.substring(slash + 1);
            int dollar = simpleName.indexOf('$');
            if (dollar >= 0) {
                String outer = name.substring(0, slash + 1 + dollar);
                String mappedOuter = classMap.get(outer);
                if (mappedOuter != null) {
                    classMap.put(name, mappedOuter + "$" + classGen.next());
                    continue;
                }
            }
            String oldPackage = slash < 0 ? "" : name.substring(0, slash);
            String newPackage = packageMap.computeIfAbsent(oldPackage,
                    ignored -> prefix + packageGen.next() + "/" + packageGen.next());
            classMap.put(name, newPackage + "/" + classGen.next());
        }
    }

    private void buildFieldMappings(ObfContext ctx, Set<String> mixinClasses, Set<String> reflectiveNames,
                                    Map<String, String> fieldMap) {
        NameGen generator = new NameGen(10, 20, ctx.config().renamer.alphabet);
        for (ClassNode owner : ctx.classes().values()) {
            if (ctx.isExcluded(owner.name) || mixinClasses.contains(owner.name)) continue;
            for (FieldNode field : owner.fields) {
                if ((field.access & Opcodes.ACC_ENUM) != 0 || reflectiveNames.contains(field.name)) continue;
                fieldMap.put(owner.name + "." + field.name, generator.next());
            }
        }
    }

    private void buildMethodMappings(ObfContext ctx, Set<String> mixinClasses, Set<String> reflectiveNames,
                                     Map<String, String> methodMap) {
        NameGen generator = new NameGen(10, 25, ctx.config().renamer.alphabet);
        Set<String> fixedNames = new HashSet<>(Arrays.asList(
                "main", "<init>", "<clinit>", "values", "valueOf", "readResolve", "writeReplace"));
        Map<String, List<MethodNode>> methodsBySignature = new LinkedHashMap<>();
        Map<MethodNode, ClassNode> owners = new HashMap<>();
        for (ClassNode owner : ctx.classes().values()) {
            for (MethodNode method : owner.methods) {
                methodsBySignature.computeIfAbsent(method.name + method.desc, ignored -> new ArrayList<>()).add(method);
                owners.put(method, owner);
            }
        }
        for (List<MethodNode> candidates : methodsBySignature.values()) {
            Set<MethodNode> remaining = new LinkedHashSet<>(candidates);
            while (!remaining.isEmpty()) {
                MethodNode seed = remaining.iterator().next();
                Set<MethodNode> family = new LinkedHashSet<>();
                Queue<MethodNode> queue = new ArrayDeque<>();
                queue.add(seed);
                while (!queue.isEmpty()) {
                    MethodNode current = queue.remove();
                    if (!family.add(current)) continue;
                    ClassNode currentOwner = owners.get(current);
                    for (MethodNode other : candidates) {
                        if (!family.contains(other)
                                && isRelated(ctx, currentOwner.name, owners.get(other).name)) queue.add(other);
                    }
                }
                remaining.removeAll(family);
                if (!canRenameMethodFamily(ctx, mixinClasses, reflectiveNames, fixedNames, family, owners)) continue;
                String mappedName = generator.next();
                for (MethodNode method : family) {
                    ClassNode owner = owners.get(method);
                    methodMap.put(owner.name + "." + method.name + method.desc, mappedName);
                }
            }
        }
    }

    private boolean canRenameMethodFamily(ObfContext ctx, Set<String> mixinClasses, Set<String> reflectiveNames,
                                          Set<String> fixedNames, Set<MethodNode> family,
                                          Map<MethodNode, ClassNode> owners) {
        for (MethodNode method : family) {
            ClassNode owner = owners.get(method);
            if (fixedNames.contains(method.name) || reflectiveNames.contains(method.name)
                    || ctx.isExcluded(owner.name) || mixinClasses.contains(owner.name)) {
                return false;
            }
            boolean virtual = (method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0;
            if (virtual && hasUnknownParent(ctx, owner)) return false;
        }
        return true;
    }

    private boolean hasUnknownParent(ObfContext ctx, ClassNode owner) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        if (owner.superName != null && !"java/lang/Object".equals(owner.superName)) queue.add(owner.superName);
        if (owner.interfaces != null) queue.addAll(owner.interfaces);
        while (!queue.isEmpty()) {
            String name = queue.remove();
            if (!visited.add(name)) continue;
            ClassNode parent = ctx.classes().get(name);
            if (parent == null) return true;
            if (parent.superName != null && !"java/lang/Object".equals(parent.superName)) queue.add(parent.superName);
            if (parent.interfaces != null) queue.addAll(parent.interfaces);
        }
        return false;
    }

    private boolean isRelated(ObfContext ctx, String first, String second) {
        return first.equals(second) || isAncestor(ctx, first, second) || isAncestor(ctx, second, first);
    }

    private boolean isAncestor(ObfContext ctx, String ancestor, String child) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(child);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) continue;
            if (ancestor.equals(current)) return true;
            ClassNode node = ctx.classes().get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return false;
    }

    private Remapper createRemapper(ObfContext ctx) {
        Map<String, String> classMap = ctx.classMapping();
        return new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String mapped = findFieldMapping(ctx, owner, name);
                return mapped == null ? name : mapped;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) return name;
                String mapped = findMethodMapping(ctx, owner, name, descriptor);
                return mapped == null ? name : mapped;
            }
        };
    }

    private String findFieldMapping(ObfContext ctx, String owner, String name) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(unmapClass(ctx, owner));
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) continue;
            String mapped = ctx.fieldMapping().get(current + "." + name);
            if (mapped != null) return mapped;
            ClassNode node = ctx.classes().get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return null;
    }

    private String findMethodMapping(ObfContext ctx, String owner, String name, String descriptor) {
        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(unmapClass(ctx, owner));
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) continue;
            String mapped = ctx.methodMapping().get(current + "." + name + descriptor);
            if (mapped != null) return mapped;
            ClassNode node = ctx.classes().get(current);
            if (node == null) continue;
            if (node.superName != null) queue.add(node.superName);
            if (node.interfaces != null) queue.addAll(node.interfaces);
        }
        return null;
    }

    private String unmapClass(ObfContext ctx, String name) {
        for (Map.Entry<String, String> entry : ctx.classMapping().entrySet()) {
            if (entry.getValue().equals(name)) return entry.getKey();
        }
        return name;
    }

    private void remapStringConstants(ObfContext ctx, Map<String, String> classMap) {
        for (ClassNode owner : ctx.classes().values()) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                        ldc.cst = replaceClassNames(value, classMap);
                    }
                }
            }
        }
    }

    private Set<String> findStringConstants(ObfContext ctx) {
        Set<String> values = new HashSet<>();
        for (ClassNode owner : ctx.classes().values()) {
            for (MethodNode method : owner.methods) {
                for (AbstractInsnNode instruction : method.instructions) {
                    if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String value) values.add(value);
                }
            }
        }
        return values;
    }

    private void rewriteMixinConfigs(ObfContext ctx, Map<String, String> classMap) {
        for (Map.Entry<String, byte[]> resource : ctx.resources().entrySet()) {
            if (!resource.getKey().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            try {
                JsonElement parsed = JsonParser.parseString(new String(resource.getValue(), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) continue;
                JsonObject object = parsed.getAsJsonObject();
                if (!object.has("package") || !isMixinConfig(object)) continue;
                String oldPackage = object.get("package").getAsString().replace('.', '/');
                List<String> keys = List.of("mixins", "client", "server");
                List<String> originalNames = new ArrayList<>();
                boolean allMapped = true;
                for (String key : keys) {
                    if (!object.has(key) || !object.get(key).isJsonArray()) continue;
                    for (JsonElement element : object.getAsJsonArray(key)) {
                        String fullName = joinClass(oldPackage, element.getAsString());
                        originalNames.add(fullName);
                        allMapped &= classMap.containsKey(fullName);
                    }
                }
                if (originalNames.isEmpty() || !allMapped) continue;
                String newPackage = commonPackage(originalNames.stream().map(classMap::get).toList());
                object.addProperty("package", newPackage.replace('/', '.'));
                for (String key : keys) {
                    if (!object.has(key) || !object.get(key).isJsonArray()) continue;
                    JsonArray rewritten = new JsonArray();
                    for (JsonElement element : object.getAsJsonArray(key)) {
                        String mapped = classMap.get(joinClass(oldPackage, element.getAsString()));
                        rewritten.add(mapped.substring(newPackage.length() + 1).replace('/', '.'));
                    }
                    object.add(key, rewritten);
                }
                if (object.has("plugin") && !object.get("plugin").isJsonNull()) {
                    String plugin = object.get("plugin").getAsString().replace('.', '/');
                    String mappedPlugin = classMap.get(plugin);
                    if (mappedPlugin != null) object.addProperty("plugin", mappedPlugin.replace('/', '.'));
                }
                resource.setValue((JSON.toJson(object) + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void rewriteResources(ObfContext ctx, Map<String, String> classMap) {
        Map<String, byte[]> rewritten = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> resource : ctx.resources().entrySet()) {
            String name = resource.getKey();
            if (isSignature(name)) continue;
            byte[] bytes = resource.getValue();
            if (isTextResource(name)) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                bytes = replaceClassNames(text, classMap).getBytes(StandardCharsets.UTF_8);
            }
            if (name.startsWith("META-INF/services/")) {
                String service = name.substring("META-INF/services/".length()).replace('.', '/');
                String mapped = classMap.get(service);
                if (mapped != null) name = "META-INF/services/" + mapped.replace('/', '.');
            }
            rewritten.put(name, bytes);
        }
        ctx.resources().clear();
        ctx.resources().putAll(rewritten);
    }

    private void rewriteManifest(ObfContext ctx, Map<String, String> classMap) {
        if (ctx.manifest() == null) return;
        rewriteAttributes(ctx.manifest().getMainAttributes(), classMap);
        for (Attributes attributes : ctx.manifest().getEntries().values()) rewriteAttributes(attributes, classMap);
        ctx.manifest().getMainAttributes().remove(new Attributes.Name("Signature-Version"));
    }

    private void rewriteAttributes(Attributes attributes, Map<String, String> classMap) {
        for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
            if (attribute.getValue() instanceof String value) {
                attribute.setValue(replaceClassNames(value, classMap));
            }
        }
    }

    private String replaceClassNames(String value, Map<String, String> classMap) {
        String result = value;
        List<Map.Entry<String, String>> mappings = new ArrayList<>(classMap.entrySet());
        mappings.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> mapping : mappings) {
            result = result.replace(mapping.getKey(), mapping.getValue());
            result = result.replace(mapping.getKey().replace('/', '.'), mapping.getValue().replace('/', '.'));
        }
        return result;
    }

    private Set<String> findMixinClasses(ObfContext ctx) {
        Set<String> result = new HashSet<>();
        for (ClassNode owner : ctx.classes().values()) {
            if (hasMixinAnnotation(owner.visibleAnnotations) || hasMixinAnnotation(owner.invisibleAnnotations)) {
                result.add(owner.name);
            }
        }
        for (byte[] resource : ctx.resources().values()) {
            try {
                JsonElement parsed = JsonParser.parseString(new String(resource, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) continue;
                JsonObject object = parsed.getAsJsonObject();
                if (!object.has("package") || !isMixinConfig(object)) continue;
                String packageName = object.get("package").getAsString().replace('.', '/');
                addMixinEntries(result, object, packageName, "mixins");
                addMixinEntries(result, object, packageName, "client");
                addMixinEntries(result, object, packageName, "server");
            } catch (RuntimeException ignored) {
            }
        }
        for (String name : new ArrayList<>(ctx.classes().keySet())) {
            int inner = name.indexOf('$');
            if (inner > 0 && result.contains(name.substring(0, inner))) result.add(name);
        }
        return result;
    }

    private void addMixinEntries(Set<String> result, JsonObject object, String packageName, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return;
        for (JsonElement entry : object.getAsJsonArray(key)) result.add(joinClass(packageName, entry.getAsString()));
    }

    private boolean hasMixinAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) return true;
        }
        return false;
    }

    private boolean isMixinConfig(JsonObject object) {
        return object.has("mixins") || object.has("client") || object.has("server");
    }

    private String joinClass(String packageName, String relativeName) {
        String relative = relativeName.replace('.', '/');
        return packageName.isEmpty() ? relative : packageName + "/" + relative;
    }

    private String commonPackage(List<String> names) {
        String[] parts = names.get(0).split("/");
        int count = parts.length - 1;
        for (int i = 1; i < names.size(); i++) {
            String[] other = names.get(i).split("/");
            count = Math.min(count, other.length - 1);
            int matching = 0;
            while (matching < count && parts[matching].equals(other[matching])) matching++;
            count = matching;
        }
        return String.join("/", Arrays.copyOf(parts, count));
    }

    private String normalizePrefix(String prefix) {
        String normalized = prefix.replace('.', '/');
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private int innerDepth(String name) {
        int depth = 0;
        for (int i = 0; i < name.length(); i++) if (name.charAt(i) == '$') depth++;
        return depth;
    }

    private boolean isTextResource(String name) {
        if (name.startsWith("META-INF/services/")) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : TEXT_EXTENSIONS) if (lower.endsWith(extension)) return true;
        return false;
    }

    private boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                || upper.endsWith(".DSA") || upper.endsWith(".EC"));
    }
}
