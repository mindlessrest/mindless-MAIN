package mindless.module.impl.render;

import mindless.Mindless;
import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Theme;
import mindless.utility.Utils;
import mindless.utility.font.MindlessFontRenderer;
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
    private long lastFrameMs = 0L;

    public static volatile boolean pendingStartupAlert = false;
    private static final long STARTUP_SUPPRESS_MS = 4000L;
    private long startupFiredAt = 0L;
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
private static final float CLOCK_GAP = 16.0f;

    /** Ease-in-out, so a card does not start and stop its fade at full speed. */
    private static float smooth(float t) {
        float clamped = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }

    private static final Color ON  = new Color(72, 209, 138);
    private static final Color OFF = new Color(232, 88, 88);

    private static final class Card {
        final String   title;
        final String   customStatus;
        final boolean  enabled;
        final long     birthMs;
        final long     durationMs;
        float targetY;
        float y;
        float alpha;

        Card(String title, boolean enabled, long birthMs, long durationMs, float startY) {
            this(title, null, enabled, birthMs, durationMs, startY);
        }

        Card(String title, String customStatus, boolean enabled, long birthMs, long durationMs, float startY) {
            this.title        = title;
            this.customStatus = customStatus;
            this.enabled      = enabled;
            this.birthMs      = birthMs;
            this.durationMs   = durationMs;
            this.targetY      = startY;
            this.y            = startY + 28;
            this.alpha        = 0.0f;
        }
    }

    public Notifications() {
        super("Notifications", "Pops a toast when a module is toggled.", category.render);
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
        if (Mindless.scriptManager != null) {
            for (Module m : Mindless.scriptManager.scripts.values()) checkModuleState(m, now, dur);
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
            boolean suppressed = startupFiredAt == 0 || now - startupFiredAt < STARTUP_SUPPRESS_MS;
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
        return Mindless.scriptManager != null && Mindless.scriptManager.scripts.containsValue(module);
    }

    private void push(String title, boolean enabled, long dur, long now) {
        if (cards.size() >= MAX) cards.remove(0);
        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float startY = baseY - cards.size() * (H + GAP);
        cards.add(new Card(title, enabled, now, dur, startY));
    }
private static float cardWidth(MindlessFontRenderer font, String title, String status, String clock) {
        float text = Math.max(font.getStringWidth(title), font.getStringWidth(status));
        float w = PAD_L + ICON + ICON_GAP + text + CLOCK_GAP + font.getStringWidth(clock) + PAD_R;
        return Math.max(W_MIN, Math.min(W_MAX, w));
    }
public static void suppressScriptChange(String moduleName) {
        if (moduleName == null) return;
        synchronized (SUPPRESSED_SCRIPT_CHANGES) {
            SUPPRESSED_SCRIPT_CHANGES.add(moduleName);
        }
    }
public static void notifyScript(String title, boolean enabled) {
        Notifications notifications = instance;
        if (notifications == null || !notifications.isEnabled() || title == null || title.isEmpty()) return;
        long now = System.currentTimeMillis();
        notifications.push(title, enabled, (long) (notifications.duration.getInput() * 1000.0), now);
    }
public static void notify(String title, String status, boolean positive) {
        Notifications notifications = instance;
        if (notifications == null || !notifications.isEnabled() || title == null || title.isEmpty()) return;
        long now = System.currentTimeMillis();
        long dur = (long) (notifications.duration.getInput() * 1000.0);
        if (notifications.cards.size() >= MAX) notifications.cards.remove(0);
        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float startY = baseY - notifications.cards.size() * (H + GAP);
        notifications.cards.add(new Card(title, status, positive, now, dur, startY));
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !Utils.nullCheck() || cards.isEmpty()) return;

        MindlessFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float rightEdge = sr.getScaledWidth() - MARGIN;
        long now = System.currentTimeMillis();
        for (int i = cards.size() - 1; i >= 0; i--) {
            int slot = cards.size() - 1 - i;
            cards.get(i).targetY = baseY - slot * (H + GAP);
        }

        int[] themeGrad = Theme.getGradients((int) Settings.defaultTheme.getInput());
        int gradL = themeGrad[0];
        int gradR = themeGrad[1];

        // Seconds since the last frame, capped so a stall does not teleport the stack.
        float delta = lastFrameMs == 0L ? 1.0f / 60.0f
                : Math.max(0.0f, Math.min(0.1f, (now - lastFrameMs) / 1000.0f));
        lastFrameMs = now;

        for (Card c : cards) {
            long age = now - c.birthMs;
            // Eased against real time rather than per frame. A fixed fraction each frame
            // meant the stack slid at whatever rate the game happened to be running.
            c.y += (c.targetY - c.y) * (1.0f - (float) Math.exp(-delta * 15.0f));
            if (age < FADE) {
                c.alpha = smooth((float) age / FADE);
            } else if (age > c.durationMs) {
                c.alpha = smooth(Math.max(0.0f, 1.0f - (float)(age - c.durationMs) / FADE));
            } else {
                c.alpha = 1.0f;
            }
            if (c.alpha < 0.01f) continue;

            int a = (int)(255 * c.alpha);
            drawCard(c, rightEdge, c.y, font, c.alpha, a, gradL, gradR, now);
        }
        RenderUtils.syncGlState();
    }

    private void drawCard(Card c, float rightEdge, float y, MindlessFontRenderer font,
                          float alpha, int a, int gradL, int gradR, long now) {
        long age = now - c.birthMs;
        float progress = c.durationMs <= 0L
                ? 0.0f : Math.max(0.0f, Math.min(1.0f, 1.0f - (float) age / c.durationMs));
        float remaining = Math.max(0.0f, (c.durationMs - age) / 1000.0f);

        Color accent = c.enabled ? ON : OFF;
        String status = c.customStatus != null ? c.customStatus : (c.enabled ? "Enabled" : "Disabled");
        String clock = String.format(Locale.ROOT, "%.1fs", remaining);

        float w = cardWidth(font, c.title, status, clock);
        float x = rightEdge - w;
        float radius = R * mindless.module.impl.theme.ThemeManager.roundingScale();

        // Same surface as the Dynamic Island: the two appear together every time a module
        // is toggled, and a black card beside a lit panel looked like two different clients.
        BlurUtils.prepareBlur(x, y, w, H);
        RoundedUtils.drawRound(x, y, w, H, radius, new Color(0, 0, 0, 255));
        BlurUtils.blurEndRegion(3, 3.0f, 0.85f, x - 2.0f, y - 2.0f, w + 4.0f, H + 4.0f);
        int panel = (int) (232 * alpha);
        RoundedUtils.drawGradientVertical(x, y, w, H, radius,
                new Color(39, 38, 46, panel),
                new Color(26, 25, 31, panel));

        int edge = (int) (30 * alpha);
        if (edge > 0) {
            RoundedUtils.drawRoundOutline(x, y, w, H, radius, 0.8f,
                    new Color(0, 0, 0, 0), new Color(255, 255, 255, edge));
        }

        int sheen = (int) (24 * alpha);
        float sheenInset = Math.min(radius, w * 0.5f);
        float sheenW = w - sheenInset * 2.0f;
        if (sheen > 0 && sheenW > 2.0f) {
            Color clear = new Color(255, 255, 255, 0);
            Color peak = new Color(255, 255, 255, sheen);
            float half = sheenW * 0.5f;
            RoundedUtils.drawGradientHorizontal(x + sheenInset, y + 0.7f, half, 0.9f, 0.45f,
                    clear, peak);
            RoundedUtils.drawGradientHorizontal(x + sheenInset + half, y + 0.7f, half, 0.9f,
                    0.45f, peak, clear);
        }

        drawBadge(x + PAD_L, y + (H - ICON) * 0.5f, accent, c.enabled, progress, alpha);
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        float fontH = font.getFontHeight();
        float block = fontH * 2 + 1.0f;
        float textX = Math.round(x + PAD_L + ICON + ICON_GAP);
        float textY = Math.round(y + (H - block) * 0.5f);

        font.drawString(c.title, textX, textY, new Color(236, 236, 242, a).getRGB(), false);
        font.drawString(status, textX, Math.round(textY + fontH + 1.0f),
                new Color(accent.getRed(), accent.getGreen(), accent.getBlue(),
                        (int)(200 * alpha)).getRGB(), false);
        font.drawString(clock, Math.round(x + w - PAD_R - font.getStringWidth(clock)), textY,
                new Color(150, 150, 162, (int)(170 * alpha)).getRGB(), false);
    }
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
            polyline(new float[] { cx - s, cx - s * 0.28f, cx + s * 1.02f },
                     new float[] { cy + 0.1f, cy + s * 0.72f, cy - s * 0.78f }, 1.7f, line);
        } else {
            cross(cx, cy, s * 1.08f, 1.35f, line);
        }
    }
private static void cross(float cx, float cy, float arm, float thickness, int color) {
        float t = thickness * 0.5f;
        float[][] plus = {
                { t, t }, { t, arm }, { -t, arm }, { -t, t },
                { -arm, t }, { -arm, -t }, { -t, -t }, { -t, -arm },
                { t, -arm }, { t, -t }, { arm, -t }, { arm, t }
        };
        float k = 0.70710678f;
        float[] xs = new float[plus.length];
        float[] ys = new float[plus.length];
        for (int i = 0; i < plus.length; i++) {
            xs[i] = cx + (plus[i][0] - plus[i][1]) * k;
            ys[i] = cy + (plus[i][0] + plus[i][1]) * k;
        }
        polygon(cx, cy, xs, ys, color);
    }
private static void polygon(float centerX, float centerY, float[] xs, float[] ys, int color) {
        int n = xs.length;
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int a = (color >>> 24) & 0xFF;
        if (a <= 0) return;

        WorldRenderer wr = begin2D(GL11.GL_TRIANGLE_FAN);
        wr.pos(centerX, centerY, 0.0D).color(r, g, b, a).endVertex();
        for (int i = 0; i <= n; i++) {
            int j = i % n;
            wr.pos(xs[j], ys[j], 0.0D).color(r, g, b, a).endVertex();
        }
        end2D();

        float[] mx = new float[n], my = new float[n];
        for (int i = 0; i < n; i++) {
            int prev = (i + n - 1) % n;
            int next = (i + 1) % n;
            float ax = normalX(xs[prev], ys[prev], xs[i], ys[i]);
            float ay = normalY(xs[prev], ys[prev], xs[i], ys[i]);
            float bx = normalX(xs[i], ys[i], xs[next], ys[next]);
            float by = normalY(xs[i], ys[i], xs[next], ys[next]);
            float vx = ax + bx, vy = ay + by;
            float len = (float) Math.sqrt(vx * vx + vy * vy);
            if (len < 1.0e-5f) { vx = bx; vy = by; len = 1.0f; }
            vx /= len; vy /= len;
            if (vx * (xs[i] - centerX) + vy * (ys[i] - centerY) < 0.0f) { vx = -vx; vy = -vy; }
            float d = Math.abs(vx * bx + vy * by);
            float scale = d > 0.35f ? 1.0f / d : 1.0f / 0.35f;
            mx[i] = vx * scale;
            my[i] = vy * scale;
        }

        wr = begin2D(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= n; i++) {
            int j = i % n;
            wr.pos(xs[j], ys[j], 0.0D).color(r, g, b, a).endVertex();
            wr.pos(xs[j] + mx[j] * FEATHER, ys[j] + my[j] * FEATHER, 0.0D).color(r, g, b, 0).endVertex();
        }
        end2D();
    }

    private static float normalX(float x1, float y1, float x2, float y2) {
        float dy = y2 - y1;
        float len = (float) Math.sqrt((x2 - x1) * (x2 - x1) + dy * dy);
        return len < 1.0e-5f ? 0.0f : -dy / len;
    }

    private static float normalY(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float len = (float) Math.sqrt(dx * dx + (y2 - y1) * (y2 - y1));
        return len < 1.0e-5f ? 0.0f : dx / len;
    }
private static void polyline(float[] xs, float[] ys, float thickness, int color) {
        int n = xs.length;
        if (n < 2) return;
        float half = thickness * 0.5f;
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
private static WorldRenderer begin2D(int mode) {
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
private static final float FEATHER = 0.55f;
    private static final int CIRCLE_STEPS = 48;
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
