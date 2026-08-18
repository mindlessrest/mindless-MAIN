package mindless.script;


import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.util.Collections;
import java.util.Set;

/**
 * A ForwardingJavaFileManager that resolves class files from the game's classloader
 * when the underlying StandardJavaFileManager can't find them on disk.
 * This is needed on Lunar where Minecraft classes only exist in memory.
 */
public class ScriptClasspathFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

    public ScriptClasspathFileManager(StandardJavaFileManager delegate) {
        super(delegate);
    }

    @Override
    public JavaFileObject getJavaFileForInput(JavaFileManager.Location location, String className, JavaFileObject.Kind kind) throws IOException {
        JavaFileObject result = super.getJavaFileForInput(location, className, kind);
        if (result != null) return result;

        if (kind == JavaFileObject.Kind.CLASS) {
            byte[] bytes = loadClassBytes(className);
            if (bytes != null) {
                return new InMemoryClassFileObject(className, bytes);
            }
        }
        return null;
    }

    @Override
    public Iterable<JavaFileObject> list(JavaFileManager.Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
        Iterable<JavaFileObject> result = super.list(location, packageName, kinds, recurse);
        // We can't enumerate all classes in memory — just return what the delegate has.
        // Individual class lookups will be handled by inferBinaryName + getJavaFileForInput.
        return result;
    }

    @Override
    public String inferBinaryName(JavaFileManager.Location location, JavaFileObject file) {
        if (file instanceof InMemoryClassFileObject) {
            return ((InMemoryClassFileObject) file).getBinaryName();
        }
        return super.inferBinaryName(location, file);
    }

    @Override
    public boolean hasLocation(JavaFileManager.Location location) {
        return super.hasLocation(location) || location == StandardLocation.CLASS_PATH;
    }

    private static byte[] loadClassBytes(String className) {
        // May be null: Lunar/Genesis has no LaunchWrapper class loader.
        ClassLoader launchLoader = ScriptManager.launchClassLoader();

        try {
            // Try LaunchClassLoader.getClassBytes() — transformed bytes, not resources
            if (launchLoader != null) {
                java.lang.reflect.Method getClassBytes = launchLoader.getClass().getMethod("getClassBytes", String.class);
                byte[] bytes = (byte[]) getClassBytes.invoke(launchLoader, className.replace('/', '.'));
                if (bytes != null) return bytes;
            }
        } catch (Throwable ignored) {}

        // Fallback: try getResourceAsStream
        try {
            String resourcePath = className.replace('.', '/') + ".class";
            ClassLoader resourceLoader = launchLoader != null ? launchLoader : ScriptManager.scriptParentClassLoader();
            InputStream is = resourceLoader.getResourceAsStream(resourcePath);
            if (is != null) {
                try {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    return bos.toByteArray();
                } finally {
                    is.close();
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static class InMemoryClassFileObject extends SimpleJavaFileObject {
        private final byte[] bytes;
        private final String binaryName;

        InMemoryClassFileObject(String className, byte[] bytes) {
            super(URI.create("mem:///" + className.replace('.', '/') + ".class"), Kind.CLASS);
            this.bytes = bytes;
            this.binaryName = className;
        }

        @Override
        public InputStream openInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        public String getBinaryName() {
            return binaryName;
        }
    }
}
