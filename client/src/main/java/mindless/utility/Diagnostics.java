package mindless.utility;

import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
     * glGetError reports everything accumulated since the last call, so polling on a timer pins the
     * error on whichever stage happened to be sampled rather than the one that caused it. The check
     * runs on every call instead; it only runs at all with diagnostics switched on, and a stall per
     * call is the price of an answer that names the right stage.
     */
    public static void gl(String stage) {
        if (!isEnabled()) return;
        int error;
        while ((error = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            report("gl", stage + " -> " + describeGl(error));
        }
    }

    /**
     * Drains errors left pending by whatever ran before Mindless did this frame.
     *
     * glGetError reports everything since the last call, with no call site attached. Without a
     * drain at the top of our own rendering, the first checkpoint inside it inherits OptiFine's
     * and Lunar's errors and reports them as ours -- which is why the stage tags could not be
     * trusted. Anything reported here happened before we touched the context.
     */
    public static void glBaseline() {
        if (!isEnabled()) return;
        int error;
        while ((error = GL11.glGetError()) != GL11.GL_NO_ERROR) {
            report("gl", "(pre-existing, not mindless) -> " + describeGl(error));
        }
    }

    // ------------------------------------------------------------------ state auditing

    private static final FloatBuffer COLOUR = BufferUtils.createFloatBuffer(16);
    private static final Deque<Section> SECTIONS = new ArrayDeque<Section>();
    private static final long SLOW_SECTION_NS = 4_000_000L;

    /**
     * A snapshot of everything that decides how a draw comes out.
     *
     * Sampled rather than assumed: the point of this class is to say what the state actually is
     * at a boundary, so that a fix targets the thing that is wrong instead of the thing that
     * seemed likely.
     */
    private static final class GlState {
        int program, framebuffer, activeTexture, boundTexture, texEnvMode;
        int alphaFunc, blendSrcRgb, blendDstRgb, shadeModel, depthFunc;
        int modelviewDepth, projectionDepth, textureDepth, attribDepth;
        float alphaRef, red, green, blue, alpha;
        boolean alphaTest, blend, texture2d, depthTest, depthMask, lighting, cullFace;
        boolean scissor, vertexArray, colourArray, texCoordArray, fog;
        boolean maskR, maskG, maskB, maskA;
    }

    private static final class Section {
        final String name;
        final long startedAt;
        final GlState before;

        Section(String name, GlState before) {
            this.name = name;
            this.startedAt = System.nanoTime();
            this.before = before;
        }
    }

    private static GlState capture() {
        GlState g = new GlState();
        COLOUR.clear();
        GL11.glGetFloat(GL11.GL_CURRENT_COLOR, COLOUR);
        g.red = COLOUR.get(0);
        g.green = COLOUR.get(1);
        g.blue = COLOUR.get(2);
        g.alpha = COLOUR.get(3);

        g.program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        g.framebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        g.activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        g.boundTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        g.texEnvMode = GL11.glGetTexEnvi(GL11.GL_TEXTURE_ENV, GL11.GL_TEXTURE_ENV_MODE);

        g.alphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        g.alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        g.alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);

        g.blend = GL11.glIsEnabled(GL11.GL_BLEND);
        g.blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        g.blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);

        g.texture2d = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        g.depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        g.depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        g.depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        g.lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        g.cullFace = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        g.scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        g.fog = GL11.glIsEnabled(GL11.GL_FOG);
        g.shadeModel = GL11.glGetInteger(GL11.GL_SHADE_MODEL);

        g.vertexArray = GL11.glIsEnabled(GL11.GL_VERTEX_ARRAY);
        g.colourArray = GL11.glIsEnabled(GL11.GL_COLOR_ARRAY);
        g.texCoordArray = GL11.glIsEnabled(GL11.GL_TEXTURE_COORD_ARRAY);

        g.modelviewDepth = GL11.glGetInteger(GL11.GL_MODELVIEW_STACK_DEPTH);
        g.projectionDepth = GL11.glGetInteger(GL11.GL_PROJECTION_STACK_DEPTH);
        g.textureDepth = GL11.glGetInteger(GL11.GL_TEXTURE_STACK_DEPTH);
        g.attribDepth = GL11.glGetInteger(GL11.GL_ATTRIB_STACK_DEPTH);

        COLOUR.clear();
        GL11.glGetFloat(GL11.GL_COLOR_WRITEMASK, COLOUR);
        g.maskR = COLOUR.get(0) != 0.0F;
        g.maskG = COLOUR.get(1) != 0.0F;
        g.maskB = COLOUR.get(2) != 0.0F;
        g.maskA = COLOUR.get(3) != 0.0F;
        return g;
    }

    /**
     * Report every way the current state departs from what a 2D GUI draw needs.
     *
     * Deliberately one report per deviation rather than a single dump: report() rate-limits by
     * exact text, so distinct problems each get through instead of one long line crowding the
     * others out, and a problem that clears up stops being reported on its own.
     */
    public static void audit(String stage) {
        if (!isEnabled()) return;
        try {
            GlState g = capture();
            List<String> bad = new ArrayList<String>();

            if (g.program != 0) {
                bad.add("shader program " + g.program + " still bound (text will go through it)");
            }
            if (g.red != 1.0F || g.green != 1.0F || g.blue != 1.0F || g.alpha != 1.0F) {
                bad.add(String.format(Locale.ROOT,
                        "current colour %.2f/%.2f/%.2f/%.2f, not white (tints everything drawn)",
                        g.red, g.green, g.blue, g.alpha));
            }
            if (!g.alphaTest) {
                bad.add("alpha test disabled (transparent texels draw as solid)");
            }
            else if (g.alphaFunc != GL11.GL_GREATER || g.alphaRef < 0.05F || g.alphaRef > 0.2F) {
                bad.add(String.format(Locale.ROOT, "alpha test func %d ref %.3f, expected GREATER 0.1",
                        g.alphaFunc, g.alphaRef));
            }
            if (g.blend && (g.blendSrcRgb != GL11.GL_SRC_ALPHA
                    || g.blendDstRgb != GL11.GL_ONE_MINUS_SRC_ALPHA)) {
                bad.add("blend func " + g.blendSrcRgb + "/" + g.blendDstRgb
                        + ", expected SRC_ALPHA/ONE_MINUS_SRC_ALPHA");
            }
            if (!g.texture2d) {
                bad.add("texture 2D disabled (textured draws come out untextured)");
            }
            if (g.texEnvMode != GL11.GL_MODULATE) {
                bad.add("texture env mode " + g.texEnvMode + ", expected MODULATE"
                        + " (vertex colour stops tinting the texture)");
            }
            if (g.lighting) {
                bad.add("lighting enabled during a 2D pass (darkens everything)");
            }
            if (g.fog) {
                bad.add("fog enabled during a 2D pass (washes toward the fog colour)");
            }
            if (g.activeTexture != GL13.GL_TEXTURE0) {
                bad.add("active texture unit " + (g.activeTexture - GL13.GL_TEXTURE0) + ", expected 0");
            }
            if (!(g.maskR && g.maskG && g.maskB && g.maskA)) {
                bad.add("colour mask " + g.maskR + "/" + g.maskG + "/" + g.maskB + "/" + g.maskA
                        + " (channels are being dropped)");
            }
            if (g.scissor) {
                bad.add("scissor test still enabled (clips later draws)");
            }
            if (g.vertexArray || g.colourArray || g.texCoordArray) {
                bad.add("client arrays left enabled: vertex=" + g.vertexArray
                        + " colour=" + g.colourArray + " texcoord=" + g.texCoordArray);
            }
            if (g.framebuffer != 0 && !isGameFramebuffer(g.framebuffer)) {
                bad.add("framebuffer " + g.framebuffer
                        + " bound, not the game's (drawing into an offscreen target)");
            }
            if (g.modelviewDepth > 8 || g.projectionDepth > 4 || g.textureDepth > 4) {
                bad.add("matrix stack depth modelview=" + g.modelviewDepth
                        + " projection=" + g.projectionDepth + " texture=" + g.textureDepth
                        + " (a push is missing its pop)");
            }
            if (g.attribDepth > 0) {
                bad.add("attrib stack depth " + g.attribDepth + " (a glPushAttrib is unmatched)");
            }

            for (int i = 0; i < bad.size(); i++) {
                report("state", stage + ": " + bad.get(i));
            }
        }
        catch (Throwable ignored) {
        }
    }

    private static boolean isGameFramebuffer(int id) {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc.getFramebuffer() != null && mc.getFramebuffer().framebufferObject == id;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Open a named section. Pair with {@link #sectionEnd}.
     *
     * The point is leak detection: whatever the section changes and does not put back is
     * reported by name, which turns "something upstream broke the state" into "this module
     * broke it". Also times the section and reports it when it runs long.
     */
    public static void sectionBegin(String name) {
        if (!isEnabled()) return;
        try {
            SECTIONS.push(new Section(name, capture()));
        }
        catch (Throwable ignored) {
        }
    }

    public static void sectionEnd() {
        if (!isEnabled()) return;
        try {
            if (SECTIONS.isEmpty()) return;
            Section section = SECTIONS.pop();
            long elapsed = System.nanoTime() - section.startedAt;
            if (elapsed > SLOW_SECTION_NS) {
                report("slow", section.name + " took " + (elapsed / 1_000_000L) + "ms");
            }
            GlState before = section.before;
            GlState after = capture();
            List<String> leaks = new ArrayList<String>();

            if (before.program != after.program) {
                leaks.add("shader program " + before.program + " -> " + after.program);
            }
            // Three of these used to fire on a section putting the 2D contract BACK, which is the
            // state audit() demands two hundred lines above. Between them they were fifty thousand
            // of the seventy thousand leak lines in a one-hour recording, and a real leak in that
            // haystack may as well not be logged at all. Only departures from the contract count.
            boolean white = after.red == 1.0F && after.green == 1.0F
                    && after.blue == 1.0F && after.alpha == 1.0F;
            if (!white && (before.red != after.red || before.green != after.green
                    || before.blue != after.blue || before.alpha != after.alpha)) {
                leaks.add(String.format(Locale.ROOT, "colour %.2f/%.2f/%.2f/%.2f -> %.2f/%.2f/%.2f/%.2f",
                        before.red, before.green, before.blue, before.alpha,
                        after.red, after.green, after.blue, after.alpha));
            }
            if (before.alphaTest && !after.alphaTest) leaks.add("alpha test true -> false");
            if (before.alphaRef != after.alphaRef) {
                leaks.add(String.format(Locale.ROOT, "alpha ref %.3f -> %.3f", before.alphaRef, after.alphaRef));
            }
            if (before.blend && !after.blend) leaks.add("blend true -> false");
            if (before.blendSrcRgb != after.blendSrcRgb || before.blendDstRgb != after.blendDstRgb) {
                leaks.add("blend func " + before.blendSrcRgb + "/" + before.blendDstRgb
                        + " -> " + after.blendSrcRgb + "/" + after.blendDstRgb);
            }
            if (before.texture2d != after.texture2d) leaks.add("texture2D " + before.texture2d + " -> " + after.texture2d);
            if (before.texEnvMode != after.texEnvMode) leaks.add("tex env " + before.texEnvMode + " -> " + after.texEnvMode);
            if (before.lighting != after.lighting) leaks.add("lighting " + before.lighting + " -> " + after.lighting);
            if (before.fog != after.fog) leaks.add("fog " + before.fog + " -> " + after.fog);
            if (before.depthTest != after.depthTest) leaks.add("depth test " + before.depthTest + " -> " + after.depthTest);
            if (before.depthMask != after.depthMask) leaks.add("depth mask " + before.depthMask + " -> " + after.depthMask);
            if (before.cullFace != after.cullFace) leaks.add("cull face " + before.cullFace + " -> " + after.cullFace);
            if (before.scissor != after.scissor) leaks.add("scissor " + before.scissor + " -> " + after.scissor);
            if (before.shadeModel != after.shadeModel) leaks.add("shade model " + before.shadeModel + " -> " + after.shadeModel);
            if (before.activeTexture != after.activeTexture) {
                leaks.add("active texture " + (before.activeTexture - GL13.GL_TEXTURE0)
                        + " -> " + (after.activeTexture - GL13.GL_TEXTURE0));
            }
            if (before.framebuffer != after.framebuffer) {
                leaks.add("framebuffer " + before.framebuffer + " -> " + after.framebuffer);
            }
            if (before.maskR != after.maskR || before.maskG != after.maskG
                    || before.maskB != after.maskB || before.maskA != after.maskA) {
                leaks.add("colour mask changed");
            }
            if (before.vertexArray != after.vertexArray || before.colourArray != after.colourArray
                    || before.texCoordArray != after.texCoordArray) {
                leaks.add("client array enables changed");
            }
            if (before.modelviewDepth != after.modelviewDepth) {
                leaks.add("modelview stack " + before.modelviewDepth + " -> " + after.modelviewDepth
                        + " (unbalanced push/pop)");
            }
            if (before.projectionDepth != after.projectionDepth) {
                leaks.add("projection stack " + before.projectionDepth + " -> " + after.projectionDepth);
            }
            if (before.attribDepth != after.attribDepth) {
                leaks.add("attrib stack " + before.attribDepth + " -> " + after.attribDepth);
            }

            for (int i = 0; i < leaks.size(); i++) {
                report("leak", section.name + " left " + leaks.get(i));
            }
        }
        catch (Throwable ignored) {
        }
    }

    /** Backwards-compatible alias for the older probe name. */
    public static void glSnapshot(String stage) {
        audit(stage);
    }

    public static void log(String category, String message) {
        if (!isEnabled()) return;
        report(category, message);
    }
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
        if (!mindless.runtime.LunarEventBridge.isDirectLunar()) {
            System.err.println("[mindless] " + line);
        }
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
