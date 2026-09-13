package obf;

import org.objectweb.asm.tree.ClassNode;
import java.util.*;
import java.util.jar.Manifest;

public class ObfContext {
    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final ObfConfig config;
    private final Map<String, String> classMapping = new HashMap<>();
    private final Map<String, String> fieldMapping = new HashMap<>();
    private final Map<String, String> methodMapping = new HashMap<>();
    private final Map<String, String> reverseClassMapping = new HashMap<>();
    private final List<String> includes;
    private final List<String> excludes;
    private int reverseClassMappingSize = -1;
    private Manifest manifest;

    public ObfContext(ObfConfig config) {
        this.config = config;
        this.includes = normalize(config.includes);
        this.excludes = normalize(config.excludes);
    }

    public Map<String, ClassNode> classes() { return classes; }
    public Map<String, byte[]> resources() { return resources; }
    public ObfConfig config() { return config; }

    public Map<String, String> classMapping() { return classMapping; }
    public Map<String, String> fieldMapping() { return fieldMapping; }
    public Map<String, String> methodMapping() { return methodMapping; }
    public Manifest manifest() { return manifest; }
    public void manifest(Manifest manifest) { this.manifest = manifest; }

    public boolean isIncluded(String className) {
        if (includes.isEmpty()) return true;
        for (String prefix : includes) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    public boolean isExcluded(String className) {
        if (reverseClassMappingSize != classMapping.size()) {
            reverseClassMapping.clear();
            for (Map.Entry<String, String> mapping : classMapping.entrySet()) {
                reverseClassMapping.put(mapping.getValue(), mapping.getKey());
            }
            reverseClassMappingSize = classMapping.size();
        }
        String originalName = reverseClassMapping.getOrDefault(className, className);
        if (!isIncluded(originalName)) return true;
        for (String prefix : excludes) {
            if (originalName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static List<String> normalize(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) return List.of();
        return prefixes.stream().map(prefix -> prefix.replace('.', '/')).toList();
    }

    public boolean isLibrary(String className) {
        return !classes.containsKey(className);
    }
}
