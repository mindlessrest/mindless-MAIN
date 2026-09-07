package mindless.script;

import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SecureClassLoaderTest {
    @Test
    public void loadsJarClassAfterOriginalJarIsDeleted() throws Exception {
        Path directory = Files.createTempDirectory("mindless-script-loader");
        Path source = directory.resolve("sc_MemoryScript.java");
        Files.write(source, "public class sc_MemoryScript { public String value() { return \"ok\"; } }".getBytes("UTF-8"));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, compiler.run(null, null, null, "-d", directory.toString(), source.toString()));

        Path classFile = directory.resolve("sc_MemoryScript.class");
        Path jarPath = directory.resolve("memory-script.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("sc_MemoryScript.class"));
            output.write(Files.readAllBytes(classFile));
            output.closeEntry();
        }

        Map<String, byte[]> classes = new HashMap<>();
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry("sc_MemoryScript.class");
            try (InputStream input = jar.getInputStream(entry)) {
                byte[] bytes = new byte[(int) entry.getSize()];
                int offset = 0;
                while (offset < bytes.length) {
                    int read = input.read(bytes, offset, bytes.length - offset);
                    if (read < 0) break;
                    offset += read;
                }
                classes.put("sc_MemoryScript", bytes);
            }
        }

        assertTrue(Files.deleteIfExists(jarPath));
        try (SecureClassLoader loader = new SecureClassLoader(classes, getClass().getClassLoader())) {
            Object instance = loader.loadClass("sc_MemoryScript").newInstance();
            // Java inflates reflection calls into a generated MethodAccessor after repeated use.
            // That generated accessor must still be able to resolve its JDK-owned superclass
            // through the script loader.
            for (int i = 0; i < 40; i++) {
                assertEquals("ok", instance.getClass().getMethod("value").invoke(instance));
            }
        }

        Files.deleteIfExists(source);
        Files.deleteIfExists(classFile);
        Files.deleteIfExists(directory);
    }
}
