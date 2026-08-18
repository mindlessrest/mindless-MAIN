package mindless.script;

import mindless.Raven;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

public class SecureClassLoader extends URLClassLoader {
    private static final List<String> WHITELISTED_PACKAGES = Arrays.asList("sun.reflect", "mindless", "java.lang", "java.util", "java.awt");

    private static final List<String> BLOCKED_CLASSES = Arrays.asList(
            "java.lang.Runtime",
            "java.lang.Process",
            "java.lang.ProcessBuilder",
            "java.lang.ProcessHandle",
            "java.lang.System",
            "java.lang.Class",
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.SecurityManager",
            "java.lang.Compiler",
            "java.lang.Package",
            "java.lang.Module",
            "java.awt.Desktop",
            "java.awt.Robot",
            "java.awt.Toolkit"
    );

    public SecureClassLoader(URL[] urls, ClassLoader parent) {
        super(urls, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isClassSafe(name)) {
            throw new ClassNotFoundException("Unsafe class detected: " + name);
        }
        return super.loadClass(name, resolve);
    }

    private boolean isClassSafe(String name) {
        if (matchesBlockedClassOrNested(name)) {
            return false;
        }

        boolean hasAllowedSuffix = name.endsWith("Exception") || name.endsWith("Throwable");

        boolean isAllowedImport = Raven.scriptManager.imports.stream().anyMatch(prefix -> name.toLowerCase().startsWith(prefix));
        boolean isScriptClass = name.startsWith("sc_") && !name.contains(".");

        boolean isWhitelistedPackage = WHITELISTED_PACKAGES.stream().anyMatch(name::startsWith);

        return hasAllowedSuffix || isAllowedImport || isScriptClass || isWhitelistedPackage;
    }

    private static boolean matchesBlockedClassOrNested(String name) {
        for (String blocked : BLOCKED_CLASSES) {
            if (name.equals(blocked) || name.startsWith(blocked + "$")) {
                return true;
            }
        }
        return false;
    }
}