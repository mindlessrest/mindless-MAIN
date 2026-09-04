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
    private Manifest manifest;

    public ObfContext(ObfConfig config) {
        this.config = config;
    }

    public Map<String, ClassNode> classes() { return classes; }
    public Map<String, byte[]> resources() { return resources; }
    public ObfConfig config() { return config; }

    public Map<String, String> classMapping() { return classMapping; }
    public Map<String, String> fieldMapping() { return fieldMapping; }
    public Map<String, String> methodMapping() { return methodMapping; }
    public Manifest manifest() { return manifest; }
    public void manifest(Manifest manifest) { this.manifest = manifest; }

    public boolean isExcluded(String className) {
        String originalName = className;
        for (Map.Entry<String, String> mapping : classMapping.entrySet()) {
            if (mapping.getValue().equals(className)) {
                originalName = mapping.getKey();
                break;
            }
        }
        boolean included = config.includes == null || config.includes.isEmpty();
        if (!included) {
            for (String prefix : config.includes) {
                if (originalName.startsWith(prefix.replace('.', '/'))) {
                    included = true;
                    break;
                }
            }
        }
        if (!included) return true;
        for (String prefix : config.excludes) {
            String normalized = prefix.replace('.', '/');
            if (originalName.startsWith(normalized)) return true;
        }
        return false;
    }

    public boolean isLibrary(String className) {
        return !classes.containsKey(className);
    }
}
