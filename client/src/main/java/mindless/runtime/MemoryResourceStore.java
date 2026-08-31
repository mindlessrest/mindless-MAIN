package mindless.runtime;

import java.util.Map;

public final class MemoryResourceStore {
    private static volatile Map<String, byte[]> entries;

    private MemoryResourceStore() {}

    public static void initialize(Map<String, byte[]> data) {
        entries = data;
    }

    public static byte[] get(String path) {
        Map<String, byte[]> e = entries;
        return e != null ? e.get(path) : null;
    }

    public static boolean contains(String path) {
        Map<String, byte[]> e = entries;
        return e != null && e.containsKey(path);
    }
}
