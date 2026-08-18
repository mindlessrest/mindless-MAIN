package mindless.module.impl.render;

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
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.*;
import java.io.InputStream;
import java.util.*;
import java.util.List;

public class Notifications extends Module {

    private final SliderSetting duration;
    private final ButtonSetting showEnabled;
    private final ButtonSetting showDisabled;

    private final Map<String, Boolean> moduleStates = new HashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private static final Set<String> SUPPRESSED_SCRIPT_CHANGES = new HashSet<>();
    private static Notifications instance;
    private long lastCheck = 0L;

    public static volatile boolean pendingStartupAlert = false;
    private static final long STARTUP_SUPPRESS_MS = 4000L;
    private long startupFiredAt = 0L;

    // icon textures
    private ResourceLocation texEnabled;
    private ResourceLocation texDisabled;
    private boolean texLoaded;

    private static final float W       = 200.0f;
    private static final float H       = 34.0f;
    private static final float R       = 6.0f;
    private static final float GAP     = 5.0f;
    private static final float MARGIN  = 10.0f;
    private static final int   MAX     = 4;
    private static final long  SLIDE   = 200L;
    private static final long  FADE    = 160L;

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
        for (Module m : ModuleManager.modules) moduleStates.put(m.getName(), m.isEnabled());
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

        for (Module m : ModuleManager.modules) {
            String name = m.getName();
            boolean cur = m.isEnabled();
            Boolean prev = moduleStates.get(name);
            if (prev == null) { moduleStates.put(name, cur); continue; }
            if (cur != prev) {
                boolean suppressed = startupFiredAt > 0 && now - startupFiredAt < STARTUP_SUPPRESS_MS;
                boolean scriptChange;
                synchronized (SUPPRESSED_SCRIPT_CHANGES) {
                    scriptChange = SUPPRESSED_SCRIPT_CHANGES.remove(name);
                }
                if (!suppressed && !scriptChange && ((cur && showEnabled.isToggled()) || (!cur && showDisabled.isToggled())))
                    push(name, cur, dur, now);
                moduleStates.put(name, cur);
            } else {
                synchronized (SUPPRESSED_SCRIPT_CHANGES) {
                    SUPPRESSED_SCRIPT_CHANGES.remove(name);
                }
            }
        }

        long keep = SLIDE + FADE + 200;
        cards.removeIf(c -> now > c.birthMs + c.durationMs + keep);
    }

    private void push(String title, boolean enabled, long dur, long now) {
        if (cards.size() >= MAX) cards.remove(0);
        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float startY = baseY - cards.size() * (H + GAP);
        cards.add(new Card(title, enabled, now, dur, startY));
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

        ensureTex();
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        ScaledResolution sr = ScaledResolutionCache.get();
        float baseY = sr.getScaledHeight() - MARGIN - H;
        float x = sr.getScaledWidth() - W - MARGIN;
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
            drawCard(c, x, c.y, font, c.alpha, a, gradL, gradR, now);
        }
    }

    private void drawCard(Card c, float x, float y, RavenFontRenderer font,
                          float alpha, int a, int gradL, int gradR, long now) {
        // Blur snapshot clipped to card bounds
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, W, H, R, new Color(0, 0, 0, (int)(80 * alpha)));
        BlurUtils.blurEnd(2, 2.8f, 0.45f);

        // Panel fill + border via SDF outline shader (no corner artifact)
        Color fill    = new Color(12, 12, 16, (int)(205 * alpha));
        Color border  = new Color(255, 255, 255, (int)(22 * alpha));
        RoundedUtils.drawRoundOutline(x, y, W, H, R, 0.8f, fill, border);

        // Icon
        float iconSz = 14.0f;
        float iconX  = x + 9.0f;
        float iconY  = y + (H - iconSz) * 0.5f;
        ResourceLocation tex = c.enabled ? texEnabled : texDisabled;
        if (tex != null) {
            GlStateManager.enableBlend();
            GlStateManager.color(1, 1, 1, alpha);
            Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
            net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(
                    (int) iconX, (int) iconY, 0, 0, (int) iconSz, (int) iconSz, iconSz, iconSz);
            GlStateManager.color(1, 1, 1, 1);
        } else {
            int dotCol = c.enabled ? new Color(80, 200, 100, a).getRGB() : new Color(200, 70, 70, a).getRGB();
            RoundedUtils.drawRound(iconX + 2, iconY + 2, iconSz - 4, iconSz - 4,
                    (iconSz - 4) * 0.5f, new Color(dotCol, true));
        }

        // The card is drawn through the Kawase blur and the SDF rounded-rect shaders, and
        // neither unbinds its program on the way out. Any shader still bound here would be
        // applied to every glyph quad, which garbles the text. Drop back to fixed-function
        // and a known colour before drawing.
        net.minecraft.client.renderer.OpenGlHelper.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(org.lwjgl.opengl.GL11.GL_SRC_ALPHA, org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        // Text. Snap to whole pixels — the card slides in on a fractional X and the vertical
        // centering lands on a half pixel, which samples the glyph atlas between texels and
        // renders the text doubled/smeared. Rounding both axes keeps glyphs on the grid.
        float fontH = font.getFontHeight();
        float block = fontH * 2 + 2.0f;
        float textX = Math.round(iconX + iconSz + 6.0f);
        float textY = Math.round(y + (H - block) * 0.5f);

        font.drawString(c.title, textX, textY, new Color(230, 230, 235, a).getRGB(), false);

        String statusStr = c.enabled ? "Enabled" : "Disabled";
        int statusCol = c.enabled
                ? new Color(80, 200, 100, (int)(175 * alpha)).getRGB()
                : new Color(200, 70, 70, (int)(175 * alpha)).getRGB();
        font.drawString(statusStr, textX, Math.round(textY + fontH + 2.0f), statusCol, false);

        // Progress bar - fully rounded, theme gradient
        long age = now - c.birthMs;
        float progress = Math.max(0.0f, Math.min(1.0f, 1.0f - (float) age / c.durationMs));
        if (progress > 0.0f) {
            float bx = x + 9.0f;
            float bw = W - 18.0f;
            float by = y + H - 5.5f;
            float bh = 2.5f;
            float br = bh * 0.5f;
            // Track
            RoundedUtils.drawRound(bx, by, bw, bh, br,
                    new Color(255, 255, 255, (int)(18 * alpha)));
            // Fill gradient
            RenderUtils.drawRoundedGradientRect(bx, by, bx + bw * progress, by + bh, br,
                    new Color((gradL >> 16) & 0xFF, (gradL >> 8) & 0xFF, gradL & 0xFF, (int)(190 * alpha)).getRGB(),
                    new Color((gradL >> 16) & 0xFF, (gradL >> 8) & 0xFF, gradL & 0xFF, (int)(190 * alpha)).getRGB(),
                    new Color((gradR >> 16) & 0xFF, (gradR >> 8) & 0xFF, gradR & 0xFF, (int)(190 * alpha)).getRGB(),
                    new Color((gradR >> 16) & 0xFF, (gradR >> 8) & 0xFF, gradR & 0xFF, (int)(190 * alpha)).getRGB());
        }
    }

    private void ensureTex() {
        if (texLoaded) return;
        texLoaded = true;
        texEnabled  = loadTex("/assets/mindless/textures/notification/green.png",  "notif_on");
        texDisabled = loadTex("/assets/mindless/textures/notification/red.png",    "notif_off");
    }

    private static ResourceLocation loadTex(String path, String name) {
        try (InputStream is = Minecraft.class.getResourceAsStream(path)) {
            if (is == null) return null;
            return Minecraft.getMinecraft().getTextureManager()
                    .getDynamicTextureLocation(name, new DynamicTexture(TextureUtil.readBufferedImage(is)));
        } catch (Exception ignored) { return null; }
    }
}
