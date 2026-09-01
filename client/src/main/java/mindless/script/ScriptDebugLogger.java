package mindless.script;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
public final class ScriptDebugLogger {
    private static final long CALLBACK_LOG_INTERVAL_MS = 500L;
    private static final Map<String, Long> LAST_CALLBACKS = new HashMap<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private ScriptDebugLogger() {}

    public static synchronized void callback(String scriptName, String methodName) {
        if (!enabled()) return;
        String key = scriptName + "::" + methodName;
        long now = System.currentTimeMillis();
        Long previous = LAST_CALLBACKS.get(key);
        if (previous != null && now - previous < CALLBACK_LOG_INTERVAL_MS) return;
        LAST_CALLBACKS.put(key, now);
        write(scriptName, "callback " + methodName);
    }

    public static void event(String scriptName, String message) {
        if (enabled()) write(scriptName, message);
    }

    private static boolean enabled() {
        return Manager.debugLogging != null && Manager.debugLogging.isToggled();
    }

    private static synchronized void write(String scriptName, String message) {
        try {
            File directory = new File(Minecraft.getMinecraft().mcDataDir, "mindless" + File.separator + "script-debug");
            if (!directory.exists() && !directory.mkdirs()) return;
            File file = new File(directory, safeFileName(scriptName) + ".log");
            try (PrintWriter out = new PrintWriter(new FileWriter(file, true))) {
                out.println("[" + TIME.format(new Date()) + "] [" + scriptName + "] " + message);
            }
        }
        catch (Exception ignored) {}
    }

    private static String safeFileName(String scriptName) {
        if (scriptName == null || scriptName.trim().isEmpty()) return "unknown-script";
        String safe = scriptName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "unknown-script" : safe;
    }
}
