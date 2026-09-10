package mindless.agent;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LinuxAgent {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean BOOTSTRAP_STARTED = new AtomicBoolean();
    private static volatile File payload;

    private LinuxAgent() {}

    public static void premain(String arguments, Instrumentation instrumentation) {
        start(arguments, instrumentation);
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        start(arguments, instrumentation);
    }

    private static void start(String arguments, Instrumentation instrumentation) {
        Map<String, String> options = parse(arguments);
        applyProperty(options, "token", "mindless.auth.token");
        applyProperty(options, "hwid", "mindless.auth.hwid");
        applyProperty(options, "api", "mindless.auth.apiUrl");
        if (System.getProperty("mindless.auth.apiUrl") == null) {
            System.setProperty("mindless.auth.apiUrl", "https://api.mindless.rest");
        }
        payload = resolvePayload(options.get("payload"));
        if (payload == null || !payload.isFile()) {
            throw new IllegalArgumentException("Mindless payload not found; pass payload=/absolute/path/to/mindless-lunar-mcp-with-forge.jar");
        }
        instrumentation.addTransformer(new Bridge(), instrumentation.isRetransformClassesSupported());
        if (instrumentation.isRetransformClassesSupported()) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!"net.minecraft.client.Minecraft".equals(loaded.getName())) continue;
                try {
                    installPayload(loaded.getClassLoader());
                    if (instrumentation.isModifiableClass(loaded)) instrumentation.retransformClasses(loaded);
                    else beginBootstrap(loaded.getClassLoader());
                } catch (Throwable failure) {
                    throw new IllegalStateException("Could not attach Mindless to the running game", failure);
                }
                break;
            }
        }
    }

    private static final class Bridge implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (loader == null || className == null || !className.startsWith("net/minecraft/")) return null;
            try {
                installPayload(loader);
                Class<?> hooks = Class.forName("mindless.runtime.TransformerHooks", true, loader);
                Method transform = hooks.getMethod("transform", String.class, byte[].class);
                byte[] transformed = (byte[]) transform.invoke(null, className, classfileBuffer);
                if ("net/minecraft/client/Minecraft".equals(className)) beginBootstrap(loader);
                return transformed;
            } catch (Throwable failure) {
                System.err.println("[MindlessAgent] transform failed for " + className + ": " + failure);
                return null;
            }
        }
    }

    private static void installPayload(ClassLoader loader) throws Exception {
        if (INSTALLED.get()) return;
        synchronized (LinuxAgent.class) {
            if (INSTALLED.get()) return;
            Method addUrl = findMethod(loader.getClass(), "addURL", URL.class);
            addUrl.setAccessible(true);
            addUrl.invoke(loader, payload.toURI().toURL());
            INSTALLED.set(true);
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static void beginBootstrap(final ClassLoader loader) {
        if (!BOOTSTRAP_STARTED.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                Thread.currentThread().setContextClassLoader(loader);
                long deadline = System.currentTimeMillis() + 120000L;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
                        Object instance = minecraft.getMethod("getMinecraft").invoke(null);
                        if (instance != null) {
                            Class<?> bootstrap = Class.forName("mindless.runtime.NativeBootstrap", true, loader);
                            bootstrap.getMethod("start").invoke(null);
                            return;
                        }
                    } catch (Throwable failure) {
                        if (System.currentTimeMillis() + 250L >= deadline) {
                            System.err.println("[MindlessAgent] bootstrap failed: " + failure);
                        }
                    }
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                System.err.println("[MindlessAgent] timed out waiting for Minecraft");
            }
        }, "Mindless-Linux-Bootstrap");
        thread.setDaemon(true);
        thread.start();
    }

    private static Map<String, String> parse(String arguments) {
        Map<String, String> options = new HashMap<String, String>();
        if (arguments == null || arguments.trim().isEmpty()) return options;
        for (String part : arguments.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0) options.put(part.substring(0, separator).trim(), part.substring(separator + 1).trim());
        }
        return options;
    }

    private static void applyProperty(Map<String, String> options, String key, String property) {
        String value = options.get(key);
        if (value != null && !value.isEmpty()) System.setProperty(property, value);
    }

    private static File resolvePayload(String configured) {
        if (configured != null && !configured.trim().isEmpty()) return new File(configured).getAbsoluteFile();
        try {
            CodeSource source = LinuxAgent.class.getProtectionDomain().getCodeSource();
            File agent = new File(source.getLocation().toURI());
            File directory = agent.getParentFile();
            File[] candidates = directory.listFiles();
            if (candidates != null) {
                for (File candidate : candidates) {
                    if (candidate.getName().contains("lunar-mcp-with-forge") && candidate.getName().endsWith(".jar")) return candidate;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
