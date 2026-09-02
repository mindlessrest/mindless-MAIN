package obf.transform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import obf.*;
import obf.util.NameGen;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Renamer implements Transform {

    @Override
    public String name() { return "renamer"; }

    @Override
    public void apply(ObfContext ctx) {
        ObfConfig.RenamerConfig cfg = ctx.config().renamer;
        NameGen gen = new NameGen(cfg.minLength, cfg.maxLength);
        String prefix = cfg.packagePrefix;

        Map<String, String> classMap = ctx.classMapping();
        Map<String, String> fieldMap = ctx.fieldMapping();
        Map<String, String> methodMap = ctx.methodMapping();
        Set<String> protectedClasses = findMixinClasses(ctx);

        // Phase 1: build class rename mapping
        if (cfg.renameClasses) {
            // Group classes by package to create fake package hierarchy
            NameGen pkgGen = new NameGen(42, cfg.minLength, cfg.maxLength);
            Map<String, String> pkgMap = new HashMap<>();

            for (String name : ctx.classes().keySet()) {
                if (ctx.isExcluded(name) || protectedClasses.contains(name)) continue;

                // Map old package to a new obfuscated sub-package under prefix
                int lastSlash = name.lastIndexOf('/');
                String oldPkg = lastSlash >= 0 ? name.substring(0, lastSlash) : "";

                String newPkg = pkgMap.computeIfAbsent(oldPkg, k -> {
                    // 2-3 levels deep under prefix
                    return prefix + pkgGen.next() + "/" + pkgGen.next();
                });

                // Handle inner classes — keep the relationship
                String simpleName = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
                int dollar = simpleName.indexOf('$');
                if (dollar >= 0) {
                    String outerOld = name.substring(0, lastSlash + 1 + dollar);
                    String outerNew = classMap.get(outerOld);
                    if (outerNew != null) {
                        classMap.put(name, outerNew + "$" + gen.next());
                        continue;
                    }
                }
                classMap.put(name, newPkg + "/" + gen.next());
            }
        }

        // Phase 2: build field/method rename mappings
        if (cfg.renameFields) {
            NameGen fieldGen = new NameGen(10, 20);
            for (ClassNode cn : ctx.classes().values()) {
                if (ctx.isExcluded(cn.name) || protectedClasses.contains(cn.name)) continue;
                for (FieldNode fn : cn.fields) {
                    String key = cn.name + "." + fn.name;
                    fieldMap.put(key, fieldGen.next());
                }
            }
        }

        if (cfg.renameMethods) {
            NameGen methodGen = new NameGen(10, 25);
            Set<String> noRename = new HashSet<>(Arrays.asList(
                "main", "<init>", "<clinit>", "values", "valueOf"
            ));
            for (ClassNode cn : ctx.classes().values()) {
                if (ctx.isExcluded(cn.name) || protectedClasses.contains(cn.name)) continue;
                for (MethodNode mn : cn.methods) {
                    if (noRename.contains(mn.name)) continue;
                    // Don't rename overrides of library methods
                    if (isLibraryOverride(ctx, cn, mn)) continue;
                    String key = cn.name + "." + mn.name + mn.desc;
                    methodMap.put(key, methodGen.next());
                }
            }
        }

        // Phase 3: apply remapping
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                return classMap.getOrDefault(internalName, internalName);
            }

            @Override
            public String mapFieldName(String owner, String name, String descriptor) {
                String key = owner + "." + name;
                // Check owner hierarchy
                String result = fieldMap.get(key);
                if (result != null) return result;
                // Check if the original owner (before remapping) had this field
                for (Map.Entry<String, String> e : classMap.entrySet()) {
                    if (e.getValue().equals(owner)) {
                        result = fieldMap.get(e.getKey() + "." + name);
                        if (result != null) return result;
                    }
                }
                return name;
            }

            @Override
            public String mapMethodName(String owner, String name, String descriptor) {
                if (name.startsWith("<")) return name;
                String key = owner + "." + name + descriptor;
                String result = methodMap.get(key);
                if (result != null) return result;
                for (Map.Entry<String, String> e : classMap.entrySet()) {
                    if (e.getValue().equals(owner)) {
                        result = methodMap.get(e.getKey() + "." + name + descriptor);
                        if (result != null) return result;
                    }
                }
                return name;
            }
        };

        Map<String, ClassNode> remapped = new LinkedHashMap<>();
        for (Map.Entry<String, ClassNode> entry : ctx.classes().entrySet()) {
            ClassNode cn = entry.getValue();
            ClassNode newCn = new ClassNode();
            cn.accept(new ClassRemapper(newCn, remapper));
            remapped.put(newCn.name, newCn);
        }

        ctx.classes().clear();
        ctx.classes().putAll(remapped);

        System.out.println("  [renamer] " + classMap.size() + " classes, "
            + fieldMap.size() + " fields, " + methodMap.size() + " methods renamed");
    }

    private Set<String> findMixinClasses(ObfContext ctx) {
        Set<String> result = new HashSet<>();
        for (ClassNode cn : ctx.classes().values()) {
            if (hasMixinAnnotation(cn.visibleAnnotations) || hasMixinAnnotation(cn.invisibleAnnotations)) {
                result.add(cn.name);
            }
        }
        for (Map.Entry<String, byte[]> entry : ctx.resources().entrySet()) {
            if (!entry.getKey().endsWith(".json")) continue;
            try {
                JsonElement parsed = JsonParser.parseString(new String(entry.getValue(), StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) continue;
                JsonObject object = parsed.getAsJsonObject();
                if (!object.has("package")) continue;
                String packageName = object.get("package").getAsString().replace('.', '/');
                addMixinEntries(result, object, packageName, "mixins");
                addMixinEntries(result, object, packageName, "client");
                addMixinEntries(result, object, packageName, "server");
                if (object.has("plugin") && !object.get("plugin").isJsonNull()) {
                    result.add(object.get("plugin").getAsString().replace('.', '/'));
                }
            } catch (RuntimeException ignored) {
            }
        }
        boolean changed;
        do {
            changed = false;
            for (String name : ctx.classes().keySet()) {
                int inner = name.indexOf('$');
                if (inner > 0 && result.contains(name.substring(0, inner))) {
                    changed |= result.add(name);
                }
            }
        } while (changed);
        return result;
    }

    private void addMixinEntries(Set<String> result, JsonObject object, String packageName, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) return;
        JsonArray entries = object.getAsJsonArray(key);
        for (JsonElement entry : entries) {
            String relativeName = entry.getAsString().replace('.', '/');
            result.add(packageName.isEmpty() ? relativeName : packageName + "/" + relativeName);
        }
    }

    private boolean hasMixinAnnotation(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if ("Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) return true;
        }
        return false;
    }

    private boolean isLibraryOverride(ObfContext ctx, ClassNode cn, MethodNode mn) {
        // Walk the hierarchy — if we encounter a library class, this method
        // might be an override of something we can't rename, so skip it.
        // If the entire chain is within our jar, we're safe.
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        if (cn.superName != null && !cn.superName.equals("java/lang/Object"))
            queue.add(cn.superName);
        if (cn.interfaces != null) queue.addAll(cn.interfaces);

        while (!queue.isEmpty()) {
            String parent = queue.poll();
            if (!visited.add(parent)) continue;

            ClassNode parentNode = ctx.classes().get(parent);
            if (parentNode == null) {
                // Library class — check if it could have this method via reflection
                try {
                    Class<?> clazz = Class.forName(parent.replace('/', '.'), false,
                        ClassLoader.getSystemClassLoader());
                    for (java.lang.reflect.Method m : clazz.getMethods()) {
                        if (m.getName().equals(mn.name)) return true;
                    }
                    for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                        if (m.getName().equals(mn.name)) return true;
                    }
                } catch (Exception e) {
                    // Can't load — be conservative only for java.* classes
                    if (parent.startsWith("java/") || parent.startsWith("javax/"))
                        return true;
                }
            } else {
                if (parentNode.superName != null && !parentNode.superName.equals("java/lang/Object"))
                    queue.add(parentNode.superName);
                if (parentNode.interfaces != null) queue.addAll(parentNode.interfaces);
            }
        }
        return false;
    }
}
