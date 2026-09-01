package mindless.script;

import mindless.Mindless;
import mindless.utility.Utils;

import javax.tools.StandardJavaFileManager;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLClassLoader;
import java.util.*;

public class Script {
    public String name;
    public Class clazz;
    public Object instance;
    public String scriptName;
    public String codeStr;
    public boolean error = false;
    public boolean usesLoadString = false;
    public int STARTING_LINE;
    public ScriptEvents event;
    public File file;

    public Script(String name) {
        this.name = name;
        this.scriptName = "sc_" + javaIdentifier(name) + "_" + Utils.generateRandomString(5);
    }

    /**
     * A script's name turned into something Java will accept as a class name.
     *
     * The generated wrapper class is named after the script, and a script name allows
     * characters an identifier does not -- a hyphen being the easy one to hit, since
     * "keep-y" is a perfectly ordinary thing to call one. Spaces and brackets were
     * handled and nothing else was, so anything else failed to compile with a message
     * about the generated source rather than about the name. Anything not legal becomes
     * an underscore; the display name is untouched.
     */
    private static String javaIdentifier(String name) {
        if (name == null || name.isEmpty()) return "script";
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            out.append(Character.isLetterOrDigit(c) || c == '_' || c == '$' ? c : '_');
        }
        return out.toString();
    }

    public boolean run() {
        try {
            if (this.scriptName == null || this.codeStr == null) {
                return false;
            }
            final File file = new File(Mindless.scriptManager.COMPILED_DIR);
            if (!file.exists() || !file.isDirectory()) {
                file.mkdir();
            }
            if (Mindless.scriptManager.compiler == null) {
                System.err.println("[Scripts] Cannot compile " + this.name + ": no compiler available!");
                return false;
            }
            System.out.println("[Scripts] Compiling script: " + this.name + " (using " + Mindless.scriptManager.compiler.getClass().getSimpleName() + ")");
            final ScriptDiagnosticListener bp = new ScriptDiagnosticListener();
            final boolean isEcj = Mindless.scriptManager.compiler instanceof org.eclipse.jdt.internal.compiler.tool.EclipseCompiler;
            final StandardJavaFileManager stdFileManager = Mindless.scriptManager.compiler.getStandardFileManager(bp, null, null);
            // Wrap with classloader-backed file manager so ECJ can resolve MC classes from memory
            final javax.tools.JavaFileManager fileManager = isEcj ? new ScriptClasspathFileManager(stdFileManager) : stdFileManager;
            final ArrayList<String> compilationOptions = new ArrayList<>();
            compilationOptions.add("-d");
            compilationOptions.add(Mindless.scriptManager.COMPILED_DIR);
            if (!isEcj) {
                compilationOptions.add("-XDuseUnsharedTable");
            }
            if (isEcj) {
                compilationOptions.add("-source");
                compilationOptions.add("1.8");
                compilationOptions.add("-target");
                compilationOptions.add("1.8");
                // Tell ECJ where to find java.lang.Object etc on JDK 9+
                String javaHome = System.getProperty("java.home");
                File jrtFs = new File(javaHome, "lib" + File.separator + "jrt-fs.jar");
                if (jrtFs.exists()) {
                    compilationOptions.add("--system");
                    compilationOptions.add(javaHome);
                }
                compilationOptions.add("-classpath");
                String cp = buildRuntimeClasspath();
                compilationOptions.add(cp);
                System.out.println("[Scripts] ECJ classpath entries: " + cp.split(File.pathSeparator).length);
            }
            else if (!ScriptManager.isDeobfuscatedEnvironment() && Mindless.scriptManager.jarPath != null) {
                compilationOptions.add("-classpath");
                String s = Mindless.scriptManager.jarPath;
                try {
                    s = URLDecoder.decode(s, "UTF-8");
                }
                catch (UnsupportedOperationException ex2) {}
                compilationOptions.add(s);
            }

            // ECJ cannot compile from in-memory JavaSourceFromString — write to temp file
            File tempSourceFile = null;
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits;
            if (isEcj) {
                tempSourceFile = new File(Mindless.scriptManager.COMPILED_DIR, this.scriptName + ".java");
                java.nio.file.Files.write(tempSourceFile.toPath(), this.codeStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                compilationUnits = stdFileManager.getJavaFileObjects(tempSourceFile);
            } else {
                compilationUnits = Arrays.asList(new JavaSourceFromString(this.scriptName, this.codeStr, this.STARTING_LINE));
            }

            boolean success = Mindless.scriptManager.compiler.getTask(null, fileManager, bp, compilationOptions, null, compilationUnits).call();

            // Clean up temp source
            if (tempSourceFile != null && tempSourceFile.exists()) {
                tempSourceFile.delete();
            }

            if (!success) {
                System.err.println("[Scripts] Compilation FAILED for: " + this.name);
                this.error = true;
                stdFileManager.close();
                return false;
            }
            System.out.println("[Scripts] Compilation SUCCESS: " + this.name);
            try (SecureClassLoader secureClassLoader = new SecureClassLoader(new URL[]{file.toURI().toURL()}, ScriptManager.scriptParentClassLoader())) {
                this.clazz = secureClassLoader.loadClass(this.scriptName);
                this.instance = this.clazz.newInstance();
            }
            catch (Throwable e) {
                e.printStackTrace();
                Utils.sendMessage("&7Script &b" + Utils.extractFileName(this.name) + " &7blocked, &cunsafe code&7 detected!");
                this.error = true;
                return false;
            }
            finally {
                stdFileManager.close();
            }
            return true;
        }
        catch (Exception ex) {
            this.error = true;
            return !error;
        }
    }

    private static String buildRuntimeClasspath() {
        LinkedHashSet<String> entries = new LinkedHashSet<>();

        // JDK 9+: add --system-style jars for platform classes
        String bootCp = System.getProperty("sun.boot.class.path");
        if (bootCp != null && !bootCp.isEmpty()) {
            for (String entry : bootCp.split(File.pathSeparator)) {
                if (new File(entry).exists()) {
                    entries.add(entry);
                }
            }
        } else {
            File rtJar = new File(System.getProperty("java.home"), "lib" + File.separator + "rt.jar");
            if (rtJar.exists()) {
                entries.add(rtJar.getAbsolutePath());
            }
        }

        // The mod jar (contains scripting API). On Lunar it's embedded in memory —
        // extract it to a temp file so ECJ can read it.
        try {
            java.security.CodeSource cs = ScriptManager.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                URL jarUrl = cs.getLocation();
                String protocol = jarUrl.getProtocol();
                if ("file".equalsIgnoreCase(protocol)) {
                    File jarFile = new File(jarUrl.toURI());
                    if (jarFile.exists()) {
                        entries.add(jarFile.getAbsolutePath());
                        System.out.println("[Scripts] Classpath: mod jar from CodeSource: " + jarFile.getAbsolutePath());
                    }
                } else {
                    // Not a file — embedded. Dump the jar to temp.
                    File tempJar = new File(Mindless.scriptManager.COMPILED_DIR, "_mindless_classes.jar");
                    try (java.io.InputStream is = jarUrl.openStream();
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(tempJar)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                        entries.add(tempJar.getAbsolutePath());
                        System.out.println("[Scripts] Classpath: dumped embedded jar to " + tempJar.getAbsolutePath());
                    } catch (Throwable t) {
                        System.err.println("[Scripts] Failed to dump embedded jar: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[Scripts] Failed to resolve CodeSource: " + t.getMessage());
        }

        // If CodeSource didn't work, try to find the jar via the classloader's loaded class bytes.
        // Fallback: locate the jar by scanning known paths where the native injector places it.
        if (entries.stream().noneMatch(e -> e.contains("mindless") || e.contains("Mindless") || e.contains("mindless") || e.contains("Mindless"))) {
            // Try getting class file location directly
            try {
                URL classUrl = ScriptManager.class.getResource("ScriptManager.class");
                if (classUrl != null) {
                    String path = classUrl.toString();
                    // jar:file:/path/to/mod.jar!/mindless/script/ScriptManager.class
                    if (path.startsWith("jar:file:")) {
                        String jarPath = path.substring("jar:file:".length(), path.indexOf("!"));
                        File jarFile = new File(java.net.URLDecoder.decode(jarPath, "UTF-8"));
                        if (jarFile.exists()) {
                            entries.add(jarFile.getAbsolutePath());
                            System.out.println("[Scripts] Classpath: mod jar from class URL: " + jarFile.getAbsolutePath());
                        }
                    } else if (path.startsWith("file:")) {
                        // Classes are in a directory (dev env)
                        String classesPath = path.substring("file:".length(), path.indexOf("mindless"));
                        File classesDir = new File(java.net.URLDecoder.decode(classesPath, "UTF-8"));
                        if (classesDir.exists()) {
                            entries.add(classesDir.getAbsolutePath());
                            System.out.println("[Scripts] Classpath: classes dir: " + classesDir.getAbsolutePath());
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Gather URLs from the LaunchWrapper loader (absent on Lunar, hence the null check)
        ClassLoader launchLoader = ScriptManager.launchClassLoader();
        try {
            if (launchLoader instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) launchLoader).getURLs()) {
                    if ("file".equalsIgnoreCase(url.getProtocol())) {
                        entries.add(new File(url.toURI()).getAbsolutePath());
                    }
                }
            }
        }
        catch (Throwable ignored) {
        }

        // Reflective getURLs on the LaunchWrapper loader
        try {
            if (launchLoader != null) {
                java.lang.reflect.Method getURLs = launchLoader.getClass().getMethod("getURLs");
                URL[] urls = (URL[]) getURLs.invoke(launchLoader);
                if (urls != null) {
                    for (URL url : urls) {
                        if ("file".equalsIgnoreCase(url.getProtocol())) {
                            entries.add(new File(url.toURI()).getAbsolutePath());
                        }
                    }
                }
            }
        }
        catch (Throwable ignored) {
        }

        // java.class.path
        String cpProp = System.getProperty("java.class.path");
        if (cpProp != null && !cpProp.isEmpty()) {
            for (String entry : cpProp.split(File.pathSeparator)) {
                if (new File(entry).exists()) {
                    entries.add(entry);
                }
            }
        }

        // Lunar: scan the classpath dir for all jars (includes Forge, OptiFine, Minecraft classes)
        if (cpProp != null && !cpProp.isEmpty()) {
            String firstEntry = cpProp.split(File.pathSeparator)[0];
            File cpDir = new File(firstEntry).getParentFile();
            if (cpDir != null && cpDir.isDirectory()) {
                File[] allJars = cpDir.listFiles((dir, name) -> name.endsWith(".jar"));
                if (allJars != null) {
                    for (File jar : allJars) {
                        entries.add(jar.getAbsolutePath());
                    }
                }
            }
        }

        if (Mindless.scriptManager.jarPath != null && !Mindless.scriptManager.jarPath.isEmpty()) {
            File jp = new File(Mindless.scriptManager.jarPath);
            if (jp.exists()) {
                entries.add(jp.getAbsolutePath());
            }
        }

        // Add dumped MC classes jar (Lunar: MC classes only exist in memory)
        if (Mindless.scriptManager.mcClassesJar != null && Mindless.scriptManager.mcClassesJar.exists()) {
            entries.add(Mindless.scriptManager.mcClassesJar.getAbsolutePath());
        }

        System.out.println("[Scripts] Classpath has " + entries.size() + " entries");
        for (String e : entries) {
            System.out.println("[Scripts]   " + e);
        }
        return String.join(File.pathSeparator, entries);
    }

    public int getBoolean(final String s, final Object... array) {
        if (this.clazz == null || this.instance == null) {
            return -1;
        }
        Method method = null;
        for (final Method method2 : this.clazz.getDeclaredMethods()) {
            if (method2.getName().equalsIgnoreCase(s) && method2.getParameterCount() == array.length && method2.getReturnType().equals(Boolean.TYPE)) {
                method = method2;
                break;
            }
        }
        if (method != null) {
            try {
                method.setAccessible(true);
                final Object invoke = method.invoke(this.instance, array);
                if (invoke instanceof Boolean) {
                    return ((boolean)invoke) ? 1 : 0;
                }
            }
            catch (IllegalAccessException | InvocationTargetException ex) {
                ReflectiveOperationException er = ex;
                Utils.sendMessage("&7Runtime error during script &b" + this.name);
                if (er.getCause() == null) {
                    Utils.sendMessage(" &7err: &cThrowable");
                }
                else {
                    Utils.sendMessage(" &7err: &c" + er.getCause().getClass().getSimpleName());
                    final StackTraceElement[] stArr = er.getCause().getStackTrace();
                    if (stArr.length > 0) {
                        StackTraceElement st = stArr[0];
                        for (final StackTraceElement element : er.getCause().getStackTrace()) {
                            if (element.getClassName().equalsIgnoreCase(this.scriptName)) {
                                st = element;
                                break;
                            }
                        }
                        Utils.sendMessage(" &7line: &c" + (st.getLineNumber() - STARTING_LINE));
                        Utils.sendMessage(" &7src: &c" + st.getMethodName());
                    }
                }
            }
        }
        return -1;
    }

    public String getString(final String s, final Object... array) {
        if (this.clazz == null || this.instance == null) {
            return null;
        }
        Method method = null;
        for (final Method method2 : this.clazz.getDeclaredMethods()) {
            if (method2.getName().equalsIgnoreCase(s) && method2.getParameterCount() == array.length && method2.getReturnType().equals(String.class)) {
                method = method2;
                break;
            }
        }
        if (method != null) {
            try {
                method.setAccessible(true);
                final Object invoke = method.invoke(this.instance, array);
                if (invoke instanceof String) {
                    return (String) invoke;
                }
            }
            catch (IllegalAccessException | InvocationTargetException ex) {
                ReflectiveOperationException er = ex;
                Utils.sendMessage("&7Runtime error during script &b" + this.name);
                if (er.getCause() == null) {
                    Utils.sendMessage(" &7err: &cThrowable");
                }
                else {
                    Utils.sendMessage(" &7err: &c" + er.getCause().getClass().getSimpleName());
                    final StackTraceElement[] stArr = er.getCause().getStackTrace();
                    if (stArr.length > 0) {
                        StackTraceElement st = stArr[0];
                        for (final StackTraceElement element : er.getCause().getStackTrace()) {
                            if (element.getClassName().equalsIgnoreCase(this.scriptName)) {
                                st = element;
                                break;
                            }
                        }
                        Utils.sendMessage(" &7line: &c" + (st.getLineNumber() - STARTING_LINE));
                        Utils.sendMessage(" &7src: &c" + st.getMethodName());
                    }
                }
            }
        }
        return null;
    }

    public Float[] getFloatArray(String methodName, Object... args) {
        if (this.clazz == null || this.instance == null) {
            return null;
        }
        Method method = null;
        for (Method _method : this.clazz.getDeclaredMethods()) {
            if (_method.getName().equals(methodName) && _method.getReturnType().equals(Float[].class) && _method.getParameterCount() == args.length) {
                method = _method;
                break;
            }
        }
        if (method != null) {
            try {
                method.setAccessible(true);
                Object result = method.invoke(this.instance, args);
                if (result instanceof Float[]) {
                    return (Float[])result;
                }
            }
            catch (IllegalAccessException | InvocationTargetException ex) {
                ReflectiveOperationException er = ex;
                Utils.sendMessage("&7Runtime error during script &b" + this.name);
                if (er.getCause() == null) {
                    Utils.sendMessage(" &7err: &cThrowable");
                }
                else {
                    Utils.sendMessage(" &7err: &c" + er.getCause().getClass().getSimpleName());
                    StackTraceElement[] stArr = er.getCause().getStackTrace();
                    if (stArr.length > 0) {
                        StackTraceElement st = stArr[0];
                        for (StackTraceElement element : er.getCause().getStackTrace()) {
                            if (element.getClassName().equalsIgnoreCase(this.scriptName)) {
                                st = element;
                                break;
                            }
                        }
                        Utils.sendMessage(" &7line: &c" + (st.getLineNumber() - STARTING_LINE));
                        Utils.sendMessage(" &7src: &c" + st.getMethodName());
                    }
                }
            }
        }
        return null;
    }

    public void delete() {
        this.clazz = null;
        this.instance = null;
        final File file = new File(Mindless.scriptManager.COMPILED_DIR + File.separator + this.scriptName + ".class");
        if (file.exists()) {
            file.delete();
        }
    }

    public void setCode(String code) {
        STARTING_LINE = 0;
        StringBuilder fileCodeContents = new StringBuilder();
        Iterator<String> iterator = Mindless.scriptManager.imports.iterator();
        while (iterator.hasNext()) {
            STARTING_LINE++;
            fileCodeContents.append("import ").append(iterator.next()).append(";\n");
        }
        fileCodeContents.append("import mindless.script.model.*;\n");
        fileCodeContents.append("import mindless.script.packet.clientbound.*;\n");
        fileCodeContents.append("import mindless.script.packet.serverbound.*;\n");
        String name = Utils.extractFileName(this.name);
        this.codeStr = fileCodeContents + "public class " + this.scriptName + " extends " + ScriptDefaults.class.getName() + " {public static final " + ScriptDefaults.modules.class.getName().replace("$", ".") + " modules = new " + ScriptDefaults.modules.class.getName().replace("$", ".") + "(\"" + name + "\");public static final String scriptName = \"" + name + "\";\n" + code + "\n}";
        STARTING_LINE += 4;
    }

    public boolean invoke(final String s, final Object... array) {
        if (this.clazz == null || this.instance == null) {
            return false;
        }
        Method method = null;
        for (final Method method2 : this.clazz.getDeclaredMethods()) {
            if (method2.getName().equalsIgnoreCase(s) && method2.getParameterCount() == array.length && method2.getReturnType().equals(Void.TYPE)) {
                method = method2;
                break;
            }
        }
        if (method != null) {
            try {
                method.setAccessible(true);
                method.invoke(this.instance, array);
                return true;
            }
            catch (IllegalAccessException | InvocationTargetException ex) {
                ReflectiveOperationException er = ex;
                Utils.sendMessage("&7Runtime error during script &b" + this.name);
                if (er.getCause() == null) {
                    Utils.sendMessage(" &7err: &cThrowable");
                }
                else {
                    Utils.sendMessage(" &7err: &c" + er.getCause().getClass().getSimpleName());
                    final StackTraceElement[] stArr = er.getCause().getStackTrace();
                    if (stArr.length > 0) {
                        StackTraceElement st = stArr[0];
                        for (final StackTraceElement element : er.getCause().getStackTrace()) {
                            if (element.getClassName().equalsIgnoreCase(this.scriptName)) {
                                st = element;
                                break;
                            }
                        }
                        Utils.sendMessage(" &7line: &c" + (st.getLineNumber() - STARTING_LINE));
                        Utils.sendMessage(" &7src: &c" + st.getMethodName());
                    }
                }
            }
        }
        return false;
    }
}
