package mindless.module.impl.render;

import mindless.Raven;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Theme;
import mindless.utility.Utils;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.util.*;
import java.util.List;

public class Notifications extends Module {

    private final SliderSetting duration;
    private final ButtonSetting showEnabled;
    private final ButtonSetting showDisabled;

    private final Map<Module, Boolean> moduleStates = new IdentityHashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private static final Set<String> SUPPRESSED_SCRIPT_CHANGES = new HashSet<>();
    private static Notifications instance;
    private long lastCheck = 0L;

    public static volatile boolean pendingStartupAlert = false;
    private static final long STARTUP_SUPPRESS_MS = 4000L;
    private long startupFiredAt = 0L;

    // Width is measured per card rather than fixed. A single width has to fit the longest module
    // name, so every card was padded out to that -- which is what made a two-word alert look like
    // a mostly empty bar.
    private static final float W_MIN   = 132.0f;
    private static final float W_MAX   = 240.0f;
    private static final float H       = 32.0f;
    private static final float R       = 9.0f;
    private static final float GAP     = 4.0f;
    private static final float MARGIN  = 10.0f;
    private static final int   MAX     = 4;
    private static final long  SLIDE   = 200L;
    private static final long  FADE    = 160L;

    private static final float PAD_L   = 10.0f;
    private static final float PAD_R   = 12.0f;
    private static final float ICON    = 18.0f;
    private static final float ICON_GAP = 9.0f;
    /** Space held between the longest text line and the countdown, so they never crowd. */
    private static final float CLOCK_GAP = 16.0f;

    private static final Color ON  = new Color(72, 209, 138);
    private static final Color OFF = new Color(232, 88, 88);

    private static final class Card {
        final String   title;
        final boolean  enabled;
        final long     birthMs;
        final long     durationMs;
        float targetY;
        float y;
        float alpha;

        Card(String title, boolean enabled, long birthMs, long durationMs, float startY) {
            this.title      = title;
            this.enabled    = enabled;
            this.birthMs    = birthMs;
            this.durationMs = durationMs;
            this.targetY    = startY;
            this.y          = startY + 28;
            this.alpha      = 0.0f;
        }
    }

    public Notifications() {
        super("Notifications", category.render);
        instance = this;
        this.registerSetting(duration     = new SliderSetting("Duration", "s", 3.0, 0.5, 8.0, 0.1));
        this.registerSetting(showEnabled  = new ButtonSetting("Show enabled",  true));
        this.registerSetting(showDisabled = new ButtonSetting("Show disabled", true));
    }

    @Override public void onEnable()  {
        moduleStates.clear();
        cards.clear();
        synchronized (SUPPRESSED_SCRIPT_CHANGES) { SUPPRESSED_SCRIPT_CHANGES.clear(); }
        for (Module m : ModuleManager.modules) moduleStates.put(m, m.isEnabled());
    }
    @Override public void onDisable() {
        moduleStates.clear();
        cards.clear();
        synchronized (SUPPRESSED_SCRIPT_CHANGES) { SUPPRESSED_SCRIPT_CHANGES.clear(); }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        long now = System.currentTimeMillis();
        if (now - lastCheck < 50L) return;
        lastCheck = now;

        long dur = (long)(duration.getInput() * 1000.0);

        if (pendingStartupAlert) {
            pendingStartupAlert = false;
            startupFiredAt = now;
            push("Mindless", true, dur, now);
        }

        for (Module m : ModuleManager.modules) checkModuleState(m, now, dur);
        if (Raven.scriptManager != null) {
            for (Module m : Raven.scriptManager.scripts.values()) checkModuleState(m, now, dur);
        }

        long keep = SLIDE + FADE + 200;
        cards.removeIf(c -> now > c.birthMs + c.durationMs + keep);
    }

    private void checkModuleState(Module module, long now, long dur) {
        String name = module.getName();
        boolean cur = module.isEnabled();
        Boolean prev = moduleStates.get(module);
        if (prev == null) {
            moduleStates.put(module, cur);
            return;
        }
        if (cur != prev) {
            boolean suppressed = startupFiredAt > 0 && now - startupFiredAt < STARTUP_SUPPRESS_MS;
            boolean scriptChange;
            synchronized (SUPPRESSED_SCRIPT_CHANGES) {
                scriptChange = !isScriptModule(module) && SUPPRESSED_SCRIPT_CHANGES.remove(name);
            }
            if (!suppressed && !scriptChange && ((cur && showEnabled.isToggled()) || (!cur && showDisabled.isToggled())))
                push(name, cur, dur, now);
            moduleStates.put(module, cur);
        } else {
            if (!isScriptModule(module)) {
                synchronized (SUPPRESSED_SCRIPT_CHANGES) {
                    SUPPRESSED_SCRIPT_CHANGES.remove(name);
                }
            }
        }
    }

    private boolean isScriptModule(Module module) {
        return Raven.scriptManager != null && Raven.scriptManager.scripts.containsValue(module);
    }

    private void push(String title, boolean enabled, long dur, long now) {
        if (cards.size() >= MAX) cards.remove(0);
        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float startY = baseY - cards.size() * (H + GAP);
        cards.add(new Card(title, enabled, now, dur, startY));
    }

    /** Width that fits this card's own text, so short names get a short card. */
    private static float cardWidth(RavenFontRenderer font, String title, String status, String clock) {
        float text = Math.max(font.getStringWidth(title), font.getStringWidth(status));
        float w = PAD_L + ICON + ICON_GAP + text + CLOCK_GAP + font.getStringWidth(clock) + PAD_R;
        return Math.max(W_MIN, Math.min(W_MAX, w));
    }

    /** Suppresses one automatic alert caused by a script-controlled module toggle. */
    public static void suppressScriptChange(String moduleName) {
        if (moduleName == null) return;
        synchronized (SUPPRESSED_SCRIPT_CHANGES) {
            SUPPRESSED_SCRIPT_CHANGES.add(moduleName);
        }
    }

    /** Adds an intentional script notification without changing module state. */
    public static void notifyScript(String title, boolean enabled) {
        Notifications notifications = instance;
        if (notifications == null || !notifications.isEnabled() || title == null || title.isEmpty()) return;
        long now = System.currentTimeMillis();
        notifications.push(title, enabled, (long) (notifications.duration.getInput() * 1000.0), now);
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck() || cards.isEmpty()) return;

        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float rightEdge = sr.getScaledWidth() - MARGIN;
        long now = System.currentTimeMillis();

        // Assign target Y slots bottom-up
        for (int i = cards.size() - 1; i >= 0; i--) {
            int slot = cards.size() - 1 - i;
            cards.get(i).targetY = baseY - slot * (H + GAP);
        }

        int[] themeGrad = Theme.getGradients((int) Settings.defaultTheme.getInput());
        int gradL = themeGrad[0];
        int gradR = themeGrad[1];

        for (Card c : cards) {
            long age = now - c.birthMs;

            // Smooth slide toward target
            c.y += (c.targetY - c.y) * 0.28f;

            // Alpha
            if (age < FADE) {
                c.alpha = (float) age / FADE;
            } else if (age > c.durationMs) {
                c.alpha = Math.max(0.0f, 1.0f - (float)(age - c.durationMs) / FADE);
            } else {
                c.alpha = 1.0f;
            }
            if (c.alpha < 0.01f) continue;

            int a = (int)(255 * c.alpha);
            drawCard(c, rightEdge, c.y, font, c.alpha, a, gradL, gradR, now);
        }

        // Cards draw at the very end of the frame, so anything left dirty here lands on the next
        // frame's hotbar rather than on the card itself. Hand back a known-clean state.
        RenderUtils.syncGlState();
    }

    private void drawCard(Card c, float rightEdge, float y, RavenFontRenderer font,
                          float alpha, int a, int gradL, int gradR, long now) {
        long age = now - c.birthMs;
        float progress = c.durationMs <= 0L
                ? 0.0f : Math.max(0.0f, Math.min(1.0f, 1.0f - (float) age / c.durationMs));
        float remaining = Math.max(0.0f, (c.durationMs - age) / 1000.0f);

        Color accent = c.enabled ? ON : OFF;
        String status = c.enabled ? "Enabled" : "Disabled";
        String clock = String.format(Locale.ROOT, "%.1fs", remaining);

        float w = cardWidth(font, c.title, status, clock);
        float x = rightEdge - w;
        float radius = R * mindless.module.impl.theme.ThemeManager.roundingScale();

        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, w, H, radius, new Color(0, 0, 0, 255));
        BlurUtils.blurEnd(3, 3.0f, 0.85f);
        RoundedUtils.drawRound(x, y, w, H, radius, new Color(0, 0, 0, (int)(120 * alpha)));
        // A faint top-down sheen. Cheaper than a border and it stops the card reading as a plain
        // flat slab, which was most of what made it feel unfinished.
        RoundedUtils.drawGradientVertical(x, y, w, H, radius,
                new Color(255, 255, 255, (int)(17 * alpha)),
                new Color(255, 255, 255, (int)(3 * alpha)));

        drawBadge(x + PAD_L, y + (H - ICON) * 0.5f, accent, c.enabled, progress, alpha);

        // The card is drawn through the Kawase blur and the SDF rounded-rect shaders, and
        // neither unbinds its program on the way out. Any shader still bound here would be
        // applied to every glyph quad, which garbles the text. Drop back to fixed-function
        // and a known colour before drawing.
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        // Text. Snap to whole pixels — the card slides in on a fractional X and the vertical
        // centering lands on a half pixel, which samples the glyph atlas between texels and
        // renders the text doubled/smeared. Rounding both axes keeps glyphs on the grid.
        float fontH = font.getFontHeight();
        float block = fontH * 2 + 1.0f;
        float textX = Math.round(x + PAD_L + ICON + ICON_GAP);
        float textY = Math.round(y + (H - block) * 0.5f);

        font.drawString(c.title, textX, textY, new Color(236, 236, 242, a).getRGB(), false);
        font.drawString(status, textX, Math.round(textY + fontH + 1.0f),
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                        (int)(200 * alpha)).getRGB(), false);

        // Countdown, right-aligned against the title so it reads as one row rather than floating.
        font.drawString(clock, Math.round(x + w - PAD_R - font.getStringWidth(clock)), textY,
                new Color(150, 150, 162, (int)(170 * alpha)).getRGB(), false);
    }

    /**
     * The state badge: a ring that drains as the card ages, wrapped around a tick or a cross.
     *
     * This replaces two flat PNGs. They could not carry the countdown, could not follow the
     * card's colour, and at 14 pixels a bitmap on a scaled GUI lands between texels and blurs.
     */
    private void drawBadge(float x, float y, Color accent, boolean enabled, float progress, float alpha) {
        float cx = x + ICON * 0.5f;
        float cy = y + ICON * 0.5f;
        float ring = ICON * 0.5f - 1.0f;

        int fill = argb(accent, (int)(38 * alpha));
        int track = argb(Color.WHITE, (int)(28 * alpha));
        int line = argb(accent, (int)(235 * alpha));

        disc(cx, cy, ring - 1.6f, fill);
        arc(cx, cy, ring, 1.6f, 0.0f, 360.0f, track);
        if (progress > 0.0f) {
            arc(cx, cy, ring, 1.6f, 0.0f, 360.0f * progress, argb(accent, (int)(215 * alpha)));
        }

        float s = 2.5f;
        if (enabled) {
            // One mitred polyline, not two strokes butted together. Two overlapping strokes
            // double-blend down the join and need a cap disc to hide the notch, and at five
            // pixels across that cap is most of the tick.
            polyline(new float[] { cx - s, cx - s * 0.28f, cx + s * 1.02f },
                     new float[] { cy + 0.1f, cy + s * 0.72f, cy - s * 0.78f }, 1.7f, line);
        } else {
            polyline(new float[] { cx - s * 0.72f, cx + s * 0.72f },
                     new float[] { cy - s * 0.72f, cy + s * 0.72f }, 1.7f, line);
            polyline(new float[] { cx - s * 0.72f, cx + s * 0.72f },
                     new float[] { cy + s * 0.72f, cy - s * 0.72f }, 1.7f, line);
        }
    }

    /**
     * A polyline stroked at a fixed width, mitred at the joins and feathered along both sides.
     *
     * The previous stroke drew a core quad, two fringe quads and a cap disc at each end, all
     * overlapping. Everything here is drawn at less than full alpha, so each overlap blended
     * twice and the glyph came out blotchy with a heavy blob at the elbow. This emits three
     * non-overlapping strips instead -- core, and one fringe down each side -- so every pixel is
     * covered exactly once.
     */
    private static void polyline(float[] xs, float[] ys, float thickness, int color) {
        int n = xs.length;
        if (n < 2) return;
        float half = thickness * 0.5f;

        // Offset direction at each point: the segment normal at the ends, and the mitre of the
        // two adjoining normals in between, lengthened so the join keeps its width through
        // the corner.
        float[] mx = new float[n], my = new float[n];
        for (int i = 0; i < n; i++) {
            float ax = 0, ay = 0, bx = 0, by = 0;
            if (i > 0) {
                float dx = xs[i] - xs[i - 1], dy = ys[i] - ys[i - 1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1.0e-5f) { ax = -dy / len; ay = dx / len; }
            }
            if (i < n - 1) {
                float dx = xs[i + 1] - xs[i], dy = ys[i + 1] - ys[i];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1.0e-5f) { bx = -dy / len; by = dx / len; }
            }
            float vx = (i == 0) ? bx : (i == n - 1) ? ax : ax + bx;
            float vy = (i == 0) ? by : (i == n - 1) ? ay : ay + by;
            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 1.0e-5f) { vx = ax; vy = ay; len = 1.0f; }
            vx /= len; vy /= len;
            // Mitre length. Clamped so a sharp corner cannot shoot off into a spike.
            float scale = 1.0f;
            if (i > 0 && i < n - 1) {
                float d = vx * bx + vy * by;
                scale = d > 0.35f ? 1.0f / d : 1.0f / 0.35f;
            }
            mx[i] = vx * scale;
            my[i] = vy * scale;
        }

        strip(xs, ys, mx, my, half, -half, 1.0f, 1.0f, color);
        strip(xs, ys, mx, my, half + FEATHER, half, 0.0f, 1.0f, color);
        strip(xs, ys, mx, my, -half, -half - FEATHER, 1.0f, 0.0f, color);
    }

    /** Triangle strip between two parallel offsets of a polyline, with an alpha at each. */
    private static void strip(float[] xs, float[] ys, float[] mx, float[] my,
                              float offsetA, float offsetB, float alphaA, float alphaB, int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int alpha = (color >>> 24) & 0xFF;
        int ca = Math.round(alpha * alphaA), cb = Math.round(alpha * alphaB);
        if (ca <= 0 && cb <= 0) return;

        WorldRenderer wr = begin2D(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i < xs.length; i++) {
            wr.pos(xs[i] + mx[i] * offsetA, ys[i] + my[i] * offsetA, 0.0D).color(r, g, b, ca).endVertex();
            wr.pos(xs[i] + mx[i] * offsetB, ys[i] + my[i] * offsetB, 0.0D).color(r, g, b, cb).endVertex();
        }
        end2D();
    }

    private static int argb(Color base, int alpha) {
        return new Color(base.getRed(), base.getGreen(), base.getBlue(),
                Math.max(0, Math.min(255, alpha))).getRGB();
    }

    /** Begins an untextured, smooth-shaded 2D batch with the inherited GL state corrected. */
    private static WorldRenderer begin2D(int mode) {
        // Three pieces of inherited state have to be corrected here, and every one of them is
        // invisible until geometry like this is drawn through it.
        //
        // shadeModel is the important one. The gradient helpers in RenderUtils set GL_SMOOTH,
        // draw, and set GL_FLAT back, and syncGlState leaves GL_FLAT too. Under GL_FLAT a
        // triangle takes one vertex's colour for all of it, so the alpha ramps that feather
        // every edge below collapsed into solid blocks -- the anti-aliasing was being written
        // and then thrown away by the rasteriser.
        //
        // The alpha test is Minecraft's usual GL_GREATER 0.1, which chops the tail off a fade
        // and turns the soft edge back into a hard one.
        //
        // Culling depends on winding, and winding here depends on which way a stroke runs, so a
        // shape could vanish entirely based on the direction it was drawn in.
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
        wr.begin(mode, DefaultVertexFormats.POSITION_COLOR);
        return wr;
    }

    private static void end2D() {
        Tessellator.getInstance().draw();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
    }

    /**
     * Width of the translucent fringe added to every edge, in GUI pixels.
     *
     * None of this geometry gets anti-aliased by the pipeline -- the badge was raw triangles with
     * hard edges, which is why the ring and the tick came out stepped. Every shape below is drawn
     * with a band of its own colour fading to zero alpha along the boundary. That is
     * anti-aliasing done by hand, and it costs one extra strip per edge.
     */
    private static final float FEATHER = 0.55f;
    private static final int CIRCLE_STEPS = 48;

    /**
     * One radial band as a triangle strip: alpha {@code a0} at radius {@code r0} fading to
     * {@code a1} at {@code r1}. Discs, rings and their fringes are all this same shape.
     */
    private static void band(float cx, float cy, float r0, float a0, float r1, float a1,
                             float startDeg, float sweepDeg, int color) {
        if (sweepDeg <= 0.0f) return;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int alpha = (color >>> 24) & 0xFF;
        int c0 = Math.round(alpha * a0), c1 = Math.round(alpha * a1);
        if (c0 <= 0 && c1 <= 0) return;

        int steps = Math.max(2, Math.round(CIRCLE_STEPS * sweepDeg / 360.0f));
        WorldRenderer wr = begin2D(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= steps; i++) {
            double t = Math.toRadians(startDeg + sweepDeg * i / (double) steps);
            double sin = Math.sin(t), cos = Math.cos(t);
            wr.pos(cx + sin * r1, cy - cos * r1, 0.0D).color(r, g, b, c1).endVertex();
            wr.pos(cx + sin * r0, cy - cos * r0, 0.0D).color(r, g, b, c0).endVertex();
        }
        end2D();
    }

    private static void disc(float cx, float cy, float radius, int color) {
        if (radius <= 0.0f) return;
        band(cx, cy, 0.0f, 1.0f, radius, 1.0f, 0.0f, 360.0f, color);
        band(cx, cy, radius, 1.0f, radius + FEATHER, 0.0f, 0.0f, 360.0f, color);
    }

    /**
     * A ring segment. Angles are degrees, zero at the top, sweeping clockwise so the countdown
     * unwinds the way a clock hand would.
     */
    private static void arc(float cx, float cy, float radius, float thickness,
                            float startDeg, float sweepDeg, int color) {
        if (sweepDeg <= 0.0f || radius <= 0.0f) return;
        float inner = radius - thickness * 0.5f;
        float outer = radius + thickness * 0.5f;
        band(cx, cy, inner, 1.0f, outer, 1.0f, startDeg, sweepDeg, color);
        band(cx, cy, Math.max(0.0f, inner - FEATHER), 0.0f, inner, 1.0f, startDeg, sweepDeg, color);
        band(cx, cy, outer, 1.0f, outer + FEATHER, 0.0f, startDeg, sweepDeg, color);
    }
}
