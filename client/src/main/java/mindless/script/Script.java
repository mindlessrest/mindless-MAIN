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
    /** Held so the classes it defined can still resolve their lazy references. */
    public SecureClassLoader loader;
    public Object instance;
    public String scriptName;
    public String codeStr;
    public boolean error = false;
    public boolean usesLoadString = false;
    public int STARTING_LINE;
    public ScriptEvents event;
    public File file;
    private final Map<String, Method> callbackMethods = new HashMap<>();

    public Script(String name) {
        this.name = name;
        this.scriptName = "sc_" + javaIdentifier(name) + "_" + Utils.generateRandomString(5);
    }
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
            // Deliberately not try-with-resources. Closing a URLClassLoader releases the jar it
            // reads from, and the JVM resolves classes lazily -- anything referenced only inside a
            // method body is loaded the first time that method runs, long after this returns. The
            // old version closed it here, so those loads failed later with NoClassDefFoundError.
            // The loader lives as long as the classes it defined.
            SecureClassLoader secureClassLoader = null;
            try {
                secureClassLoader = new SecureClassLoader(new URL[]{file.toURI().toURL()},
                        ScriptManager.scriptParentClassLoader());
                this.loader = secureClassLoader;
                this.clazz = secureClassLoader.loadClass(this.scriptName);
                this.instance = this.clazz.newInstance();
                cacheCallbackMethods();
            }
            catch (Throwable e) {
                // Only a sandbox refusal is unsafe code. Everything else is an ordinary compile or
                // link failure, and reporting those as "unsafe" made real script bugs impossible
                // to diagnose.
                String rejected = secureClassLoader == null ? null : secureClassLoader.getRejectedClass();
                if (rejected != null) {
                    Utils.sendMessage("&7Script &b" + Utils.extractFileName(this.name)
                            + " &7blocked: &cnot allowed to use " + rejected);
                }
                else {
                    e.printStackTrace();
                    Utils.sendMessage("&7Script &b" + Utils.extractFileName(this.name)
                            + " &7failed to load: &c" + e.getClass().getSimpleName());
                }
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
        if (entries.stream().noneMatch(e -> e.contains("mindless") || e.contains("Mindless") || e.contains("mindless") || e.contains("Mindless"))) {
            try {
                URL classUrl = ScriptManager.class.getResource("ScriptManager.class");
                if (classUrl != null) {
                    String path = classUrl.toString();
                    if (path.startsWith("jar:file:")) {
                        String jarPath = path.substring("jar:file:".length(), path.indexOf("!"));
                        File jarFile = new File(java.net.URLDecoder.decode(jarPath, "UTF-8"));
                        if (jarFile.exists()) {
                            entries.add(jarFile.getAbsolutePath());
                            System.out.println("[Scripts] Classpath: mod jar from class URL: " + jarFile.getAbsolutePath());
                        }
                    } else if (path.startsWith("file:")) {
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
        String cpProp = System.getProperty("java.class.path");
        if (cpProp != null && !cpProp.isEmpty()) {
            for (String entry : cpProp.split(File.pathSeparator)) {
                if (new File(entry).exists()) {
                    entries.add(entry);
                }
            }
        }
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
        Method method = findCallback(s, array.length, Boolean.TYPE);
        if (method != null) {
            try {
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
        Method method = findCallback(s, array.length, String.class);
        if (method != null) {
            try {
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
        Method method = findCallback(methodName, args.length, Float[].class);
        if (method != null) {
            try {
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
        callbackMethods.clear();
        if (this.loader != null) {
            try {
                this.loader.close();
            }
            catch (Exception ignored) {
            }
            this.loader = null;
        }
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
        Method method = findCallback(s, array.length, Void.TYPE);
        if (method != null) {
            try {
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

    /**
     * Build the callback lookup after either source compilation or a precompiled JAR load.
     * Package-private so ScriptManager can finish binding JAR scripts through the exact same
     * runtime path as source scripts.
     */
    void cacheCallbackMethods() {
        callbackMethods.clear();
        if (clazz == null) return;
        for (Method method : clazz.getDeclaredMethods()) {
            String key = callbackKey(method.getName(), method.getParameterCount(), method.getReturnType());
            if (callbackMethods.containsKey(key)) continue;
            try {
                method.setAccessible(true);
                callbackMethods.put(key, method);
            }
            catch (RuntimeException ignored) {
                // Preserve the previous behavior: inaccessible callbacks are treated as absent.
            }
        }
    }

    private Method findCallback(String name, int parameterCount, Class<?> returnType) {
        if (name == null) return null;
        return callbackMethods.get(callbackKey(name, parameterCount, returnType));
    }

    private static String callbackKey(String name, int parameterCount, Class<?> returnType) {
        return name.toLowerCase(Locale.ROOT) + '#' + parameterCount + ':' + returnType.getName();
    }
}
