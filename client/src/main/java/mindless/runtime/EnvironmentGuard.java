package mindless.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.List;

/**
 * Java-side environment integrity checks.
 * Detects debuggers, agents, instrumentation, and sandbox/analysis tools.
 */
public final class EnvironmentGuard {

    private EnvironmentGuard() {}

    public static boolean check() {
        String reason = detect();
        if (reason != null) {
            onDetected(reason);
            return false;
        }
        return true;
    }

    private static String detect() {
        String r;
        if ((r = checkJDWP()) != null) return r;
        if ((r = checkAgents()) != null) return r;
        if ((r = checkSuspiciousProperties()) != null) return r;
        if ((r = checkSuspiciousThreads()) != null) return r;
        if ((r = checkClassLoaderTampering()) != null) return r;
        if ((r = checkReflectionFrameworks()) != null) return r;
        return null;
    }

    private static String checkJDWP() {
        // JDWP agent = Java debugger attached
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        List<String> args = runtime.getInputArguments();
        for (String arg : args) {
            String lower = arg.toLowerCase();
            if (lower.contains("-agentlib:jdwp") || lower.contains("-xrunjdwp"))
                return "JDWP";
            if (lower.contains("-agentlib:") || lower.contains("-javaagent:"))
                return "JavaAgent";
            if (lower.contains("-xdebug"))
                return "XDebug";
        }
        return null;
    }

    private static String checkAgents() {
        // Check for instrumentation agents via system properties
        try {
            String agentProp = System.getProperty("jdk.attach.allowAttachSelf");
            if ("true".equals(agentProp)) return "AttachSelf";
        } catch (Exception ignored) {}

        // Check for known agent classes in classloader
        String[] agentClasses = {
            "sun.instrument.InstrumentationImpl",
            "com.sun.tools.attach.VirtualMachine",
            "net.bytebuddy.agent.ByteBuddyAgent",
            "org.objectweb.asm.ClassVisitor",
            "javassist.CtClass",
            "com.github.javaparser.JavaParser",
        };
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        for (String cls : agentClasses) {
            try {
                cl.loadClass(cls);
                return "Agent:" + cls.substring(cls.lastIndexOf('.') + 1);
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static String checkSuspiciousProperties() {
        // JRebel, DCEVM, or other hotswap tools
        String[] props = {
            "rebel.base",
            "jrebel.agent",
            "dcevm.version",
        };
        for (String p : props) {
            if (System.getProperty(p) != null)
                return "SuspiciousProp:" + p;
        }
        return null;
    }

    private static String checkSuspiciousThreads() {
        Thread[] threads = new Thread[Thread.activeCount() + 10];
        int count = Thread.enumerate(threads);
        for (int i = 0; i < count; i++) {
            if (threads[i] == null) continue;
            String name = threads[i].getName().toLowerCase();
            if (name.contains("jdwp") || name.contains("debugger"))
                return "DebugThread:" + threads[i].getName();
            if (name.contains("attach listener"))
                return "AttachListener";
        }
        return null;
    }

    private static String checkClassLoaderTampering() {
        // Check if our classes' classloader has been replaced
        ClassLoader ours = EnvironmentGuard.class.getClassLoader();
        if (ours == null) return null;

        String clName = ours.getClass().getName().toLowerCase();
        if (clName.contains("instrument") || clName.contains("transform") ||
            clName.contains("bytebuddy") || clName.contains("javassist"))
            return "TamperedClassLoader:" + ours.getClass().getName();

        return null;
    }

    private static String checkReflectionFrameworks() {
        // Detect known RE/hooking frameworks loaded into the JVM
        String[] frameworks = {
            "me.xdrop.fuzzywuzzy",        // fuzzy matching (deobfuscation)
            "org.jd.core",                 // JD-Core decompiler
            "com.strobel.decompiler",      // Procyon decompiler
            "org.benf.cfr",                // CFR decompiler
            "jadx.core",                   // JADX decompiler
        };
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        for (String fw : frameworks) {
            try {
                cl.loadClass(fw + ".Main");
                return "REFramework";
            } catch (ClassNotFoundException ignored) {}
            try {
                cl.loadClass(fw + ".Decompiler");
                return "REFramework";
            } catch (ClassNotFoundException ignored) {}
        }
        return null;
    }

    private static void onDetected(String reason) {
        NativeBootstrap.log("Environment check failed: " + reason);
        // Report via heartbeat if available
        String token = System.getProperty("mindless.auth.token");
        String apiUrl = System.getProperty("mindless.auth.apiUrl");
        if (token != null && apiUrl != null && !token.isEmpty() && !apiUrl.isEmpty()) {
            try {
                dev.authsys.AuthClient client = new dev.authsys.AuthClient(apiUrl);
                client.setToken(token);
                java.util.Map<String, String> meta = new java.util.HashMap<>();
                meta.put("reason", reason);
                meta.put("source", "java");
                client.reportEvent(dev.authsys.WebhookEventType.INTEGRITY_VIOLATION, meta);
            } catch (Throwable ignored) {}
        }

        try { TransformerHooks.untransformNative(); } catch (Throwable ignored) {}
    }
}
