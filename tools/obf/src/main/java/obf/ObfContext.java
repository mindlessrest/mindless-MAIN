package obf;

import org.objectweb.asm.tree.ClassNode;
import java.util.*;

public class ObfContext {
    private final Map<String, ClassNode> classes = new LinkedHashMap<>();
    private final Map<String, byte[]> resources = new LinkedHashMap<>();
    private final ObfConfig config;
    private final Map<String, String> classMapping = new HashMap<>();
    private final Map<String, String> fieldMapping = new HashMap<>();
    private final Map<String, String> methodMapping = new HashMap<>();

    public ObfContext(ObfConfig config) {
        this.config = config;
    }

    public Map<String, ClassNode> classes() { return classes; }
    public Map<String, byte[]> resources() { return resources; }
    public ObfConfig config() { return config; }

    public Map<String, String> classMapping() { return classMapping; }
    public Map<String, String> fieldMapping() { return fieldMapping; }
    public Map<String, String> methodMapping() { return methodMapping; }

    public boolean isExcluded(String className) {
        for (String prefix : config.excludes) {
            if (className.startsWith(prefix.replace('.', '/'))) return true;
        }
        return false;
    }

    public boolean isLibrary(String className) {
        return !classes.containsKey(className);
    }
}
