package mindless.script;

import mindless.Mindless;
import mindless.clickgui.components.impl.CategoryComponent;
import mindless.module.Module;
import mindless.module.setting.impl.TextSetting;
import mindless.utility.NetworkUtils;
import mindless.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;

public class ScriptManager {
    private static final char[] INVALID_SCRIPT_NAME_CHARS = new char[]{'\\', '/', ':', '*', '?', '"', '<', '>', '|'};
    private Minecraft mc = Minecraft.getMinecraft();
    public LinkedHashMap<Script, Module> scripts = new LinkedHashMap<>();
    public JavaCompiler compiler = createCompiler();
    public boolean deleteTempFiles = true;
    public File directory;
    public List<String> imports = Arrays.asList(Color.class.getName(), Collections.class.getName(), List.class.getName(), ArrayList.class.getName(), Arrays.class.getName(), Map.class.getName(), Set.class.getName(), HashMap.class.getName(), HashSet.class.getName(), ConcurrentHashMap.class.getName(), LinkedHashMap.class.getName(), LinkedHashSet.class.getName(), Iterator.class.getName(), Comparator.class.getName(), AtomicInteger.class.getName(), AtomicLong.class.getName(), AtomicBoolean.class.getName(), Random.class.getName(), Matcher.class.getName());
    public String COMPILED_DIR = Utils.getCompilerDirectory();
    public String jarPath = resolveJarPath();

    private static String resolveJarPath() {
        try {
            java.security.CodeSource cs = ScriptManager.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            String path = cs.getLocation().getPath();
            if (path == null || !path.contains(".jar")) return null;
            return path.split("\\.jar!")[0].substring(5) + ".jar";
        } catch (Exception e) {
            return null;
        }
    }
    private Map<String, String> loadedHashes = new HashMap<>();

    public ScriptManager() {
        directory = Utils.getScriptDirectory();
    }
public File mcClassesJar;
public static ClassLoader launchClassLoader() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", false,
                    ScriptManager.class.getClassLoader());
            Object value = launch.getField("classLoader").get(null);
            if (value instanceof ClassLoader) return (ClassLoader) value;
        } catch (Throwable ignored) {
        }
        return null;
    }
public static boolean isDeobfuscatedEnvironment() {
        try {
            Class<?> launch = Class.forName("net.minecraft.launchwrapper.Launch", false,
                    ScriptManager.class.getClassLoader());
            Object blackboard = launch.getField("blackboard").get(null);
            if (blackboard instanceof java.util.Map) {
                Object value = ((java.util.Map<?, ?>) blackboard).get("fml.deobfuscatedEnvironment");
                return value instanceof Boolean && (Boolean) value;
            }
        } catch (Throwable ignored) {}
        return false;
    }
public static ClassLoader scriptParentClassLoader() {
        ClassLoader launch = launchClassLoader();
        return launch != null ? launch : ScriptManager.class.getClassLoader();
    }
private int lastResolveSource = 0;
private byte[] resolveClassBytes(String className, java.util.Set<String> referencedOut) {
        String resourcePath = className.replace('.', '/') + ".class";
        java.util.List<ClassLoader> loaders = new java.util.ArrayList<>();
        loaders.add(ScriptManager.class.getClassLoader());
        loaders.add(Thread.currentThread().getContextClassLoader());
        ClassLoader launch = launchClassLoader();
        if (launch != null) loaders.add(launch);

        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try (java.io.InputStream is = cl.getResourceAsStream(resourcePath)) {
                if (is != null) {
                    byte[] bytes = readAllBytes(is);
                    if (bytes.length > 0) {
                        lastResolveSource = 0;
                        return bytes;
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (launch != null) {
            try {
                java.lang.reflect.Method getClassBytes =
                        launch.getClass().getMethod("getClassBytes", String.class);
                byte[] bytes = (byte[]) getClassBytes.invoke(launch, className);
                if (bytes != null && bytes.length > 0) {
                    lastResolveSource = 1;
                    return bytes;
                }
            } catch (Throwable ignored) {}
        }
        for (ClassLoader cl : loaders) {
            if (cl == null) continue;
            try {
                Class<?> klass = Class.forName(className, false, cl);
                byte[] stub = synthesizeClassStub(klass, referencedOut);
                if (stub != null) {
                    lastResolveSource = 2;
                    return stub;
                }
            } catch (Throwable ignored) {}
        }

        return null;
    }
private static byte[] synthesizeClassStub(Class<?> klass, java.util.Set<String> referencedOut) {
        try {
            org.objectweb.asm.ClassWriter writer = new org.objectweb.asm.ClassWriter(0);

            String internal = klass.getName().replace('.', '/');
            Class<?> superClass = klass.getSuperclass();
            String superInternal = (klass.isInterface() || superClass == null)
                    ? "java/lang/Object" : superClass.getName().replace('.', '/');
            if (superClass != null) collectType(superClass, referencedOut);

            Class<?>[] ifaces = klass.getInterfaces();
            String[] interfaceInternals = new String[ifaces.length];
            for (int i = 0; i < ifaces.length; i++) {
                interfaceInternals[i] = ifaces[i].getName().replace('.', '/');
                collectType(ifaces[i], referencedOut);
            }

            int access = klass.getModifiers() & 0xFFFF;
            if (klass.isInterface()) access |= org.objectweb.asm.Opcodes.ACC_INTERFACE;
            writer.visit(org.objectweb.asm.Opcodes.V1_8, access, internal, null,
                    superInternal, interfaceInternals);

            for (java.lang.reflect.Field f : klass.getDeclaredFields()) {
                collectType(f.getType(), referencedOut);
                writer.visitField(f.getModifiers() & 0xFFFF, f.getName(),
                        typeDescriptor(f.getType()), null, null).visitEnd();
            }
            for (java.lang.reflect.Constructor<?> c : klass.getDeclaredConstructors()) {
                for (Class<?> pt : c.getParameterTypes()) collectType(pt, referencedOut);
                writer.visitMethod(c.getModifiers() & 0xFFFF, "<init>",
                        methodDescriptor(c.getParameterTypes(), void.class), null, null).visitEnd();
            }
            for (java.lang.reflect.Method m : klass.getDeclaredMethods()) {
                for (Class<?> pt : m.getParameterTypes()) collectType(pt, referencedOut);
                collectType(m.getReturnType(), referencedOut);
                writer.visitMethod(m.getModifiers() & 0xFFFF, m.getName(),
                        methodDescriptor(m.getParameterTypes(), m.getReturnType()), null, null).visitEnd();
            }

            writer.visitEnd();
            return writer.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }
private static void collectType(Class<?> type, java.util.Set<String> out) {
        if (type == null || out == null) return;
        while (type.isArray()) type = type.getComponentType();
        if (type.isPrimitive()) return;
        String name = type.getName();
        if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")) out.add(name);
    }

    private static String typeDescriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + typeDescriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    private static String methodDescriptor(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(typeDescriptor(p));
        return sb.append(')').append(typeDescriptor(ret)).toString();
    }
private static boolean isUsableMcClassesJar(File jar) {
        if (jar == null || !jar.exists() || jar.length() < 1024) return false;
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(jar)) {
            return zf.getEntry("net/minecraft/util/Vec3.class") != null
                    && zf.getEntry("net/minecraft/util/BlockPos.class") != null
                    && zf.getEntry("net/minecraft/client/Minecraft.class") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static byte[] readAllBytes(java.io.InputStream is) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
private static void scanConstantPoolForClasses(byte[] classBytes, java.util.Set<String> out) {
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(classBytes));
            int magic = dis.readInt();
            if (magic != 0xCAFEBABE) return;
            dis.readUnsignedShort(); // minor
            dis.readUnsignedShort(); // major
            int cpCount = dis.readUnsignedShort();
            String[] utf8s = new String[cpCount];
            int[] classRefs = new int[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = dis.readUnsignedByte();
                switch (tag) {
                    case 1: // UTF8
                        utf8s[i] = dis.readUTF();
                        break;
                    case 7: // Class
                        classRefs[i] = dis.readUnsignedShort();
                        break;
                    case 8: // String
                        dis.readUnsignedShort();
                        break;
                    case 3: case 4: // Int, Float
                        dis.readInt();
                        break;
                    case 5: case 6: // Long, Double
                        dis.readLong();
                        i++; // takes two slots
                        break;
                    case 9: case 10: case 11: case 12: // Field, Method, InterfaceMethod, NameAndType
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                        break;
                    case 15: // MethodHandle
                        dis.readUnsignedByte();
                        dis.readUnsignedShort();
                        break;
                    case 16: // MethodType
                        dis.readUnsignedShort();
                        break;
                    case 18: // InvokeDynamic
                        dis.readUnsignedShort();
                        dis.readUnsignedShort();
                        break;
                    default:
                        return; // unknown tag, bail
                }
            }
            for (int i = 1; i < cpCount; i++) {
                if (classRefs[i] != 0 && classRefs[i] < cpCount && utf8s[classRefs[i]] != null) {
                    String name = utf8s[classRefs[i]];
                    if ((name.startsWith("net/minecraft/") || name.startsWith("net/minecraftforge/")) && !name.contains("[")) {
                        out.add(name.replace('/', '.'));
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static JavaCompiler createCompiler() {
        System.out.println("[Scripts] Searching for Java compiler...");
        System.out.println("[Scripts] Running on: " + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ")");
        JavaCompiler systemCompiler = ToolProvider.getSystemJavaCompiler();
        if (systemCompiler != null) {
            System.out.println("[Scripts] Found system JavaCompiler (JDK detected).");
            return systemCompiler;
        }
        System.out.println("[Scripts] No system compiler (running on JRE/modular runtime).");
        int javaVersion = getJavaMajorVersion();
        if (javaVersion <= 8) {
            System.out.println("[Scripts] Java 8 runtime detected. Searching for external JDK tools.jar...");
            JavaCompiler found = findJdkCompiler();
            if (found != null) {
                System.out.println("[Scripts] Found JDK compiler from tools.jar.");
                return found;
            }
        } else {
            System.out.println("[Scripts] Java " + javaVersion + " runtime — skipping tools.jar search (incompatible).");
        }

        System.out.println("[Scripts] Falling back to bundled Eclipse ECJ compiler...");
        try {
            JavaCompiler ecj = new org.eclipse.jdt.internal.compiler.tool.EclipseCompiler();
            System.out.println("[Scripts] ECJ compiler loaded successfully.");
            return ecj;
        }
        catch (Throwable t) {
            System.err.println("[Scripts] FAILED to load ECJ compiler: " + t.getMessage());
            t.printStackTrace();
            return null;
        }
    }

    private static int getJavaMajorVersion() {
        String version = System.getProperty("java.specification.version", "1.8");
        if (version.startsWith("1.")) {
            return Integer.parseInt(version.substring(2));
        }
        try {
            return Integer.parseInt(version.split("\\.")[0]);
        } catch (NumberFormatException e) {
            return 8;
        }
    }

    private static JavaCompiler findJdkCompiler() {
        String userHome = System.getProperty("user.home");
        String[] searchRoots = {
                System.getenv("JAVA_HOME"),
                "C:\\Program Files\\Java",
                "C:\\Program Files\\Eclipse Adoptium",
                "C:\\Program Files\\AdoptOpenJDK",
                "C:\\Program Files\\Zulu",
                "C:\\Program Files\\Microsoft\\jdk",
                "C:\\Program Files\\Amazon Corretto",
                userHome != null ? userHome + "\\.jdks" : null,
                userHome != null ? userHome + "\\scoop\\apps\\temurin21-jdk\\current" : null,
                userHome != null ? userHome + "\\scoop\\apps\\temurin25-jdk\\current" : null,
                userHome != null ? userHome + "\\scoop\\apps\\temurin17-jdk\\current" : null
        };
        for (String root : searchRoots) {
            if (root == null || root.isEmpty()) continue;
            java.io.File rootDir = new java.io.File(root);
            JavaCompiler c = tryLoadFromJdk(rootDir);
            if (c != null) return c;
            if (rootDir.isDirectory()) {
                java.io.File[] children = rootDir.listFiles();
                if (children != null) {
                    for (java.io.File child : children) {
                        if (!child.isDirectory()) continue;
                        c = tryLoadFromJdk(child);
                        if (c != null) return c;
                    }
                }
            }
        }
        return null;
    }

    private static JavaCompiler tryLoadFromJdk(java.io.File jdkDir) {
        java.io.File toolsJar = new java.io.File(jdkDir, "lib" + java.io.File.separator + "tools.jar");
        if (toolsJar.exists()) {
            JavaCompiler c = loadCompilerFromToolsJar(toolsJar);
            if (c != null) return c;
        }
        java.io.File javacBin = new java.io.File(jdkDir, "bin" + java.io.File.separator + "javac.exe");
        if (!javacBin.exists()) {
            javacBin = new java.io.File(jdkDir, "bin" + java.io.File.separator + "javac");
        }
        if (javacBin.exists()) {
            java.io.File compilerModule = new java.io.File(jdkDir, "lib" + java.io.File.separator + "jrt-fs.jar");
            if (compilerModule.exists()) {
                return null;
            }
        }
        return null;
    }

    private static JavaCompiler loadCompilerFromToolsJar(java.io.File toolsJar) {
        try {
            java.net.URLClassLoader cl = new java.net.URLClassLoader(
                    new java.net.URL[]{toolsJar.toURI().toURL()},
                    ScriptManager.class.getClassLoader()
            );
            Class<?> javacToolClass = cl.loadClass("com.sun.tools.javac.api.JavacTool");
            return (JavaCompiler) javacToolClass.getMethod("create").invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public void onEnable(Script script) {
        ScriptDebugLogger.event(script.name, "enabled");
        if (script.event == null) {
            script.event = new ScriptEvents(getModule(script));
            MinecraftForge.EVENT_BUS.register(script.event);
        }
        script.invoke("onEnable");
    }

    public Module getModule(Script script) {
        for (Map.Entry<Script, Module> entry : this.scripts.entrySet()) {
            if (entry.getKey().equals(script)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public String createScript(String requestedName) {
        String scriptName = normalizeScriptName(requestedName);
        String validationError = validateScriptName(scriptName, null);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return null;
        }

        try {
            if (!directory.exists() && !directory.mkdirs()) {
                Utils.sendMessage("&cFailed to create scripts folder.");
                return null;
            }

            Files.write(new File(directory, scriptName + ".java").toPath(), buildDefaultScriptTemplate().getBytes(StandardCharsets.UTF_8));
            loadScripts();
            return scriptName;
        }
        catch (Exception e) {
            Utils.sendMessage("&cFailed to create script: &b" + scriptName);
            e.printStackTrace();
            return null;
        }
    }

    public void loadScripts() {
        for (Module module : this.scripts.values()) {
            module.disable();
        }

        if (deleteTempFiles) {
            deleteTempFiles = false;
            File tempDirectory = new File(COMPILED_DIR);
            if (tempDirectory.exists() && tempDirectory.isDirectory()) {
                File[] tempFiles = tempDirectory.listFiles();
                if (tempFiles != null) {
                    for (File tempFile : tempFiles) {
                        if (mcClassesJar != null && tempFile.equals(mcClassesJar)) {
                            continue;
                        }
                        if (!tempFile.delete()) {
                            System.err.println("Failed to delete temp file: " + tempFile.getAbsolutePath());
                        }
                    }
                }
            }
        }
        else {
            if (!this.scripts.isEmpty()) {
                Iterator<Map.Entry<Script, Module>> iterator = scripts.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Script, Module> entry = iterator.next();
                    String fileName = entry.getKey().file.getName();
                    String hash = calculateHash(entry.getKey().file);

                    String cachedHash = loadedHashes.get(fileName);
                    if (cachedHash != null && cachedHash.equals(hash) && !entry.getKey().error) {
                        continue; // no changes detected, skip loading
                    }
                    entry.getKey().delete();
                    iterator.remove();
                    loadedHashes.remove(fileName);
                }
            }
            else {
                loadedHashes.clear();
            }
        }

        File scriptDirectory = directory;
        if (scriptDirectory.exists() && scriptDirectory.isDirectory()) {
            File[] scriptFiles = scriptDirectory.listFiles();
            if (scriptFiles != null) {
                for (File scriptFile : scriptFiles) {
                    if (!scriptFile.isFile()) continue;
                    String fileName = scriptFile.getName();
                    if (!fileName.endsWith(".jar") && !fileName.endsWith(".java")) continue;
                    String hash = calculateHash(scriptFile);

                    String cachedHash = loadedHashes.get(fileName);
                    if (cachedHash != null && cachedHash.equals(hash)) {
                        continue;
                    }
                    boolean loaded = fileName.endsWith(".jar")
                            ? loadJarScript(scriptFile)
                            : parseFile(scriptFile);
                    if (loaded) {
                        loadedHashes.put(fileName, hash);
                    }
                }
            }
        }
        else {
            if (scriptDirectory.mkdirs()) {
                System.out.println("Created script directory: " + scriptDirectory.getAbsolutePath());
            }
            else {
                System.err.println("Failed to create script directory: " + scriptDirectory.getAbsolutePath());
            }
        }

        for (Module module : this.scripts.values()) {
            module.disable();
        }

        refreshScriptModules();

        ScriptDefaults.reloadModules();

        File tempDirectory = new File(COMPILED_DIR);
        if (tempDirectory.exists() && tempDirectory.isDirectory()) {
            File[] tempFiles = tempDirectory.listFiles();
            if (tempFiles != null) {
                for (File tempFile : tempFiles) {
                    if (mcClassesJar != null && tempFile.equals(mcClassesJar)) {
                        continue;
                    }
                    if (!tempFile.delete()) {
                        System.err.println("Failed to delete temp file: " + tempFile.getAbsolutePath());
                    }
                }
            }
        }
    }

    private boolean loadJarScript(File jarFile) {
        String scriptName = jarFile.getName().replace(".jar", "");
        if (scriptName.isEmpty() || scriptName.startsWith("_")) return false;
        try {
            String className = "sc_" + scriptName;
            String[] candidates;
            Map<String, byte[]> classes = new HashMap<>();
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jarFile)) {
                candidates = jf.stream()
                        .filter(e -> e.getName().endsWith(".class") && e.getName().startsWith("sc_")
                                && !e.getName().contains("$"))
                        .map(e -> e.getName().replace("/", ".").replace(".class", ""))
                        .toArray(String[]::new);
                java.util.Enumeration<java.util.jar.JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    java.util.jar.JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) continue;
                    try (java.io.InputStream input = jf.getInputStream(entry)) {
                        classes.put(entry.getName().replace("/", ".").replace(".class", ""), readAllBytes(input));
                    }
                }
            }

            SecureClassLoader jarLoader = new SecureClassLoader(classes, scriptParentClassLoader());

            Class<?> scriptClass = null;
            if (candidates != null && candidates.length > 0) {
                scriptClass = jarLoader.loadClass(candidates[0]);
                className = candidates[0];
            } else {
                scriptClass = jarLoader.loadClass(className);
            }

            Script script = new Script(scriptName);
            script.file = jarFile;
            script.loader = jarLoader;
            script.clazz = scriptClass;
            script.instance = scriptClass.newInstance();

            Module module = new Module(script);
            attachManagerSettings(script, module);
            Mindless.scriptManager.scripts.put(script, module);
            ScriptDefaults.reloadModules();
            Mindless.scriptManager.invoke("onLoad", module);
            return true;
        } catch (Throwable t) {
            System.err.println("[Scripts] Failed to load jar script " + scriptName + ": " + t.getMessage());
            t.printStackTrace();
            return false;
        }
    }

    private boolean parseFile(File file) {
        if (file.getName().startsWith("_") || !file.getName().endsWith(".java")) {
            return false;
        }
        String scriptName = file.getName().replace(".java", "");
        if (scriptName.isEmpty()) {
            return false;
        }
        StringBuilder scriptContents = new StringBuilder();
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                scriptContents.append(line).append("\n");
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        if (scriptContents.length() == 0) {
            return false;
        }

        String source = normalizeLineSeparators(scriptContents.toString());
        List<String> topLevelLines = Utils.getTopLevelLines(source);
        boolean usesLoadString = false;
        for (String line : topLevelLines) {
            if (line.startsWith("load - \"") && line.endsWith("\"")) {
                usesLoadString = true;
                int loadIndex = source.indexOf(line);
                if (loadIndex != -1) {
                    source = source.substring(0, loadIndex)
                            + source.substring(loadIndex + line.length());
                }

                if (!Manager.enableHttpRequests.isToggled()) {
                    Utils.sendMessage("&7Blocked &cload string&7 in &b" + scriptName + "&7, http requests are not enabled.");
                    continue;
                }
                String url = line.substring("load - \"".length(), line.length() - 1);
                String externalContents = normalizeLineSeparators(
                        NetworkUtils.getTextFromURL(url, true, true)
                );
                if (externalContents.isEmpty()) {
                    break;
                }

                if (loadIndex != -1) {
                    source = source.substring(0, loadIndex)
                            + externalContents
                            + source.substring(loadIndex);
                }
            }
        }

        Script script = new Script(scriptName);
        script.file = file;
        script.usesLoadString = usesLoadString;
        script.setCode(source);
        script.run();
        Module module = new Module(script);
        attachManagerSettings(script, module);
        Mindless.scriptManager.scripts.put(script, module);
        ScriptDefaults.reloadModules();
        Mindless.scriptManager.invoke("onLoad", module);
        return !script.error;
    }


    private String normalizeLineSeparators(String source) {
        if (source == null) {
            return "";
        }
        return source.replace('\u2028', '\n').replace('\u2029', '\n').replace('\u0085', '\n');
    }

    public void onDisable(Script script) {
        ScriptDebugLogger.event(script.name, "disabled");
        if (script.event != null) {
            MinecraftForge.EVENT_BUS.unregister(script.event);
            script.event = null;
        }
        script.invoke("onDisable");
    }

    public void invoke(String methodName, Module module, Object... args) {
        for (Map.Entry<Script, Module> entry : this.scripts.entrySet()) {
            if (((entry.getValue().canBeEnabled() && entry.getValue().isEnabled()) || methodName.equals("onLoad")) && entry.getValue().equals(module)) {
                ScriptDebugLogger.callback(entry.getKey().name, methodName);
                entry.getKey().invoke(methodName, args);
            }
        }
    }

    public boolean hasEnabledScript(Module module) {
        for (Map.Entry<Script, Module> entry : this.scripts.entrySet()) {
            Module owner = entry.getValue();
            if (owner.canBeEnabled() && owner.isEnabled() && owner.equals(module)) {
                return true;
            }
        }
        return false;
    }

    public int invokeBoolean(String methodName, Module module, Object... args) {
        for (Map.Entry<Script, Module> entry : this.scripts.entrySet()) {
            if (entry.getValue().canBeEnabled() && entry.getValue().isEnabled() && entry.getValue().equals(module)) {
                int c = entry.getKey().getBoolean(methodName, args);
                if (c != -1) {
                    return c;
                }
            }
        }
        return -1;
    }


    public Float[] invokeFloatArray(String method, Module module, Object... args) {
        for (Map.Entry<Script, Module> entry : this.scripts.entrySet()) {
            if (entry.getValue().canBeEnabled() && entry.getValue().isEnabled() && entry.getValue().equals(module)) {
                Float[] val = entry.getKey().getFloatArray(method, args);
                if (val != null) {
                    return val;
                }
            }
        }
        return null;
    }

    private String calculateHash(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(Paths.get(file.getPath()));
            byte[] hashBytes = digest.digest(fileBytes);

            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        }
        catch (Exception e) {
            return "";
        }
    }

    public boolean renameScript(Script script, String requestedName) {
        if (script == null || script.file == null) {
            Utils.sendMessage("&cFailed to rename script.");
            return false;
        }

        String oldName = script.name;
        String newName = normalizeScriptName(requestedName);
        String validationError = validateScriptName(newName, oldName);
        if (validationError != null) {
            Utils.sendMessage("&c" + validationError);
            return false;
        }

        if (oldName.equals(newName)) {
            return true;
        }

        File newFile = new File(directory, newName + ".java");
        try {
            Files.move(script.file.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            loadScripts();
            return true;
        }
        catch (Exception e) {
            Utils.sendMessage("&cFailed to rename script: &b" + oldName);
            e.printStackTrace();
            return false;
        }
    }

    private void refreshScriptModules() {
        for (CategoryComponent categoryComponent : Mindless.clickGui.categories) {
            if (categoryComponent.category == Module.category.scripts) {
                categoryComponent.reloadModules(false);
                break;
            }
        }
    }

    private void attachManagerSettings(Script script, Module module) {
        final TextSetting[] scriptNameSetting = new TextSetting[1];
        scriptNameSetting[0] = new TextSetting("Script name", script.name, "Type a new script name...", 32, () -> renameScript(script, module, scriptNameSetting[0].getText()));
        module.registerSetting(scriptNameSetting[0]);
    }

    private void renameScript(Script script, Module module, String requestedName) {
        String oldName = module.getName();
        if (renameScript(script, requestedName)) {
            if (!oldName.equals(requestedName.trim())) {
                Utils.sendMessage("&7Renamed script: &b" + oldName + " &7to &b" + requestedName.trim());
            }
        }
    }

    private String validateScriptName(String scriptName, String currentName) {
        if (scriptName.isEmpty()) {
            return "Script name cannot be empty.";
        }
        if (scriptName.endsWith(".") || scriptName.endsWith(" ")) {
            return "Script name cannot end with a space or period.";
        }
        for (char c : scriptName.toCharArray()) {
            if (c < 32 || containsInvalidScriptChar(c)) {
                return "Script name contains invalid characters.";
            }
        }
        File scriptFile = new File(directory, scriptName + ".java");
        if ((currentName == null || !currentName.equalsIgnoreCase(scriptName)) && scriptFile.exists()) {
            return "Script already exists: " + scriptName;
        }
        return null;
    }

    private boolean containsInvalidScriptChar(char c) {
        for (char invalidChar : INVALID_SCRIPT_NAME_CHARS) {
            if (invalidChar == c) {
                return true;
            }
        }
        return false;
    }

    private String normalizeScriptName(String name) {
        return name == null ? "" : name.trim();
    }

    private String buildDefaultScriptTemplate() {
        return "// New Mindless script\n"
            + "void onLoad() {\n"
            + "}\n\n"
            + "void onEnable() {\n"
            + "}\n\n"
            + "void onDisable() {\n"
            + "}\n";
    }
}
