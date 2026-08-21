package mindless.utility;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * One place to find out why something is not working.
 *
 * The game reports "OpenGL Error: 1282" and nothing else -- not which draw call raised it, not
 * which pass it belongs to -- which leaves the cause to be guessed at. Shader build failures were
 * worse than that: they produced no output at all, so a broken shader and a switched-off setting
 * looked identical from the outside.
 *
 * Everything here is inert unless the Diagnostics toggle is on, because {@link #gl} forces the
 * driver to flush the pipeline and would cost frames if it ran all the time.
 */
public final class Diagnostics {
    private static final Map<String, Long> lastReported = new HashMap<String, Long>();
    private static final long REPEAT_INTERVAL_MS = 3000L;

    private static PrintWriter writer;

    private Diagnostics() {}

    public static boolean isEnabled() {
        try {
            return mindless.module.impl.client.Settings.diagnostics.isToggled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean toChat() {
        try {
            return mindless.module.impl.client.Settings.diagnosticsChat.isToggled();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Drains the GL error queue and attributes anything in it to {@code stage}.
     *
     * Errors are sticky and reported at the next query rather than at the offending call, so a
     * marker after each step is what turns "something in this frame is wrong" into a named line.
     */
    public static void gl(String stage) {
        if (!isEnabled()) return;
        int error;
        while ((error = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            report("gl", stage + " -> " + describeGl(error));
        }
    }

    public static void log(String category, String message) {
        if (!isEnabled()) return;
        report(category, message);
    }

    /** Logged whether or not diagnostics are on: a shader that will not build is always worth saying. */
    public static void always(String category, String message) {
        report(category, message);
    }

    private static void report(String category, String message) {
        String line = "[" + category + "] " + message;
        synchronized (lastReported) {
            long now = System.currentTimeMillis();
            Long seen = lastReported.get(line);
            if (seen != null && now - seen.longValue() < REPEAT_INTERVAL_MS) return;
            lastReported.put(line, Long.valueOf(now));
        }

        write(line);
        System.err.println("[mindless] " + line);
        if (toChat()) {
            try {
                Utils.sendMessage("&8[&cdiag&8] &7" + message);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void write(String line) {
        try {
            if (writer == null) {
                File dir = new File(Minecraft.getMinecraft().mcDataDir, "logs");
                if (!dir.isDirectory() && !dir.mkdirs()) return;
                writer = new PrintWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(dir, "mindless-debug.log"), true),
                        StandardCharsets.UTF_8), true);
                writer.println("--- session " + stamp() + " ---");
            }
            writer.println(stamp() + "  " + line);
        } catch (Throwable ignored) {
            writer = null;
        }
    }

    private static String stamp() {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
    }

    private static String describeGl(int error) {
        switch (error) {
            case GL11.GL_INVALID_ENUM: return "1280 invalid enum";
            case GL11.GL_INVALID_VALUE: return "1281 invalid value";
            case GL11.GL_INVALID_OPERATION: return "1282 invalid operation";
            case GL11.GL_STACK_OVERFLOW: return "1283 stack overflow";
            case GL11.GL_STACK_UNDERFLOW: return "1284 stack underflow";
            case GL11.GL_OUT_OF_MEMORY: return "1285 out of memory";
            case 1286: return "1286 invalid framebuffer operation";
            default: return String.valueOf(error);
        }
    }

    /**
     * Writes what the driver actually supports, plus the state of every shader we build.
     *
     * The limits matter more than they look: a sampler pointed past GL_MAX_TEXTURE_IMAGE_UNITS is
     * a silent invalid-operation on every draw, and the number varies by driver.
     */
    public static void dumpEnvironment() {
        always("env", "GL_VERSION   " + GL11.glGetString(GL11.GL_VERSION));
        always("env", "GL_RENDERER  " + GL11.glGetString(GL11.GL_RENDERER));
        always("env", "GL_VENDOR    " + GL11.glGetString(GL11.GL_VENDOR));
        try {
            always("env", "GLSL         " + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));
        } catch (Throwable ignored) {
        }
        always("env", "max texture image units      " + GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS));
        always("env", "max combined texture units   " + GL11.glGetInteger(GL20.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS));
        always("env", "framebuffer " + Minecraft.getMinecraft().displayWidth
                + "x" + Minecraft.getMinecraft().displayHeight);
        always("env", "log file: .minecraft/logs/mindless-debug.log");
    }
}
