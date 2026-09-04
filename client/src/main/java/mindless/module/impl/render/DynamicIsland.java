package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.GroupSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.MindlessAccount;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.font.ModuleFont;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.HudGlowHelper;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

/**
 * The signed-in name, the framerate and whatever else is worth a glance, in one pill.
 *
 * The pill measures itself from its segments every frame and eases toward that width, so a segment
 * appearing or a number growing a digit slides rather than snaps. Only the geometry animates -- the
 * font size is fixed, because a moving font size means a new glyph atlas per frame.
 */
public class DynamicIsland extends Module {

    private static final String[] MODES = {"Island", "Text"};
    private static final String[] ANCHORS = {"Top centre", "Top left", "Top right", "Custom"};

    private static final float DEFAULT_TEXT_X = 5.0f;
    private static final float DEFAULT_TEXT_Y = 5.0f;
    private static final float WATERMARK_SCALE = 3.0f;

    private static final float PAD_X = 9.0f;
    private static final float PAD_Y = 5.5f;
    private static final float SEGMENT_GAP = 7.0f;
    private static final float ICON_GAP = 4.5f;
    private static final float ICON_SIZE = 7.0f;
    private static final float EDGE_MARGIN = 4.0f;
    private static final float WIDTH_EASE = 12.0f;

    private static final int MAX_SEGMENTS = 12;
    private static final float CHIP_PAD_X = 5.0f;
    private static final float CHIP_DOT = 3.5f;
    private static final float CHIP_DOT_GAP = 3.5f;
    private static final float CHIP_GAP = 4.0f;
    private static final int MAX_TOGGLES = 8;
    private static final int ACCENT_ON = 0x5BD98A;
    private static final int ACCENT_OFF = 0xE0644F;

    private final SliderSetting mode;
    private final SliderSetting font;
    private final SliderSetting anchor;

    private final GroupSetting contentGroup;
    private final ButtonSetting showAccount;
    private final ButtonSetting showFps;
    private final ButtonSetting showPing;
    private final ButtonSetting showServer;
    private final ButtonSetting showSession;
    private final ButtonSetting showClock;
    private final ButtonSetting showStatus;
    private final SliderSetting statusLimit;
    private final SliderSetting statusDuration;
    private final ButtonSetting statusValues;

    private final GroupSetting styleGroup;
    private final ButtonSetting blurBackdrop;
    private final ButtonSetting hairline;
    private final ButtonSetting sheen;
    private final ButtonSetting accentIcons;
    private final ButtonSetting dividers;
    private final SliderSetting opacity;
    private final SliderSetting scale;

    /** Reused every frame; the island lays out on the render thread only. */
    private final Segment[] segments = new Segment[MAX_SEGMENTS];
    private int segmentCount;

    /** Identity keyed: two modules can share a name, and a module is never replaced. */
    private final java.util.Map<mindless.module.Module, Boolean> toggleStates =
            new java.util.IdentityHashMap<mindless.module.Module, Boolean>();
    private final java.util.List<Toggle> recentToggles = new java.util.ArrayList<Toggle>();

    private float animatedWidth = -1.0f;
    private long lastFrameNanos;
    private long sessionStart = System.currentTimeMillis();

    public float textPosX = DEFAULT_TEXT_X;
    public float textPosY = DEFAULT_TEXT_Y;
    public float islandPosX = -1.0f;
    public float islandPosY = -1.0f;

    public DynamicIsland() {
        super("Dynamic Island", "A pill with your account, FPS and client info.", category.render);

        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(anchor = new SliderSetting("Anchor", 0, ANCHORS));
        this.registerSetting(font = new SliderSetting("Font", 0, ModuleFont.options()));

        this.registerSetting(contentGroup = new GroupSetting("Content"));
        this.registerSetting(showAccount = new ButtonSetting(contentGroup, "Account", true));
        this.registerSetting(showFps = new ButtonSetting(contentGroup, "FPS", true));
        this.registerSetting(showPing = new ButtonSetting(contentGroup, "Ping", false));
        this.registerSetting(showServer = new ButtonSetting(contentGroup, "Server", false));
        this.registerSetting(showSession = new ButtonSetting(contentGroup, "Session time", false));
        this.registerSetting(showClock = new ButtonSetting(contentGroup, "Clock", false));
        this.registerSetting(showStatus = new ButtonSetting(contentGroup, "Module toggles", true));
        this.registerSetting(statusLimit = new SliderSetting(contentGroup, "Toggles shown", 3.0, 1.0, 6.0, 1.0));
        this.registerSetting(statusDuration = new SliderSetting(contentGroup, "Toggle time", "s", 2.5, 0.5, 8.0, 0.5));
        this.registerSetting(statusValues = new ButtonSetting(contentGroup, "Show disables", true));

        this.registerSetting(styleGroup = new GroupSetting("Style"));
        this.registerSetting(blurBackdrop = new ButtonSetting(styleGroup, "Blur backdrop", true));
        this.registerSetting(hairline = new ButtonSetting(styleGroup, "Edge highlight", true));
        this.registerSetting(sheen = new ButtonSetting(styleGroup, "Top sheen", true));
        this.registerSetting(accentIcons = new ButtonSetting(styleGroup, "Accent icons", true));
        this.registerSetting(dividers = new ButtonSetting(styleGroup, "Dividers", true));
        this.registerSetting(opacity = new SliderSetting(styleGroup, "Opacity", "%", 78.0, 20.0, 100.0, 1.0));
        this.registerSetting(scale = new SliderSetting(styleGroup, "Scale", 1.0, 0.7, 1.6, 0.05));
    }

    @Override
    public void guiUpdate() {
        boolean island = (int) mode.getInput() == 0;
        anchor.setVisible(island, this);
        contentGroup.setVisible(island, this);
        styleGroup.setVisible(island, this);
    }

    @Override
    public void onEnable() {
        sessionStart = System.currentTimeMillis();
        animatedWidth = -1.0f;
        lastFrameNanos = 0L;
        // Re-seeded on the next poll, so switching the island on does not announce every module
        // that happened to already be running.
        toggleStates.clear();
        recentToggles.clear();
    }

    public void resetPosition() {
        textPosX = DEFAULT_TEXT_X;
        textPosY = DEFAULT_TEXT_Y;
        islandPosX = -1.0f;
        islandPosY = -1.0f;
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;

        if ((int) mode.getInput() == 1) {
            renderTextWatermark();
        } else {
            renderIsland();
        }
    }

    // ------------------------------------------------------------------ island

    private void renderIsland() {
        MindlessFontRenderer text = islandFont();
        if (text == null) return;

        float delta = frameDelta();
        buildSegments(text);
        if (segmentCount == 0) return;

        float uiScale = (float) scale.getInput();
        float fontHeight = text.getFontHeight();
        float height = (PAD_Y * 2.0f + fontHeight) * uiScale;
        float targetWidth = measureWidth(text) * uiScale;

        if (animatedWidth < 0.0f) {
            animatedWidth = targetWidth;
        }
        animatedWidth = approach(animatedWidth, targetWidth, WIDTH_EASE, delta);
        float width = animatedWidth;

        ScaledResolution resolution = ScaledResolutionCache.get();
        float x = anchoredX(resolution, width);
        float y = anchoredY(resolution, height);

        float radius = Math.min(height * 0.5f, 12.0f * ThemeManager.roundingScale());
        int alpha = Math.round(255.0f * (float) (opacity.getInput() / 100.0));

        drawBackdrop(x, y, width, height, radius, alpha);
        drawSegments(text, x, y, width, height, uiScale, alpha);
    }

    private void drawBackdrop(float x, float y, float width, float height, float radius, int alpha) {
        if (blurBackdrop.isToggled()) {
            BlurUtils.prepareBlur(x, y, width, height);
            RoundedUtils.drawRound(x, y, width, height, radius, 0xFF000000);
            // Composited over exactly the pill, not two pixels past it. The inflated region let
            // the blur smear past the rounded mask, which is the pale square-shouldered halo that
            // sat around the pill and made its edge look like a second, badly drawn border.
            BlurUtils.blurEndRegion(2, 2.6f, alpha / 255.0f, x, y, width, height);
        }

        RoundedUtils.drawRound(x, y, width, height, radius, withAlpha(0x0A0D12, alpha));

        if (sheen.isToggled()) {
            // A thin lit band along the top edge; it is what stops the pill reading as a flat
            // rectangle without adding another blur pass.
            float bandHeight = Math.min(height * 0.45f, 9.0f);
            RoundedUtils.drawGradientVertical(x + 1.0f, y + 1.0f, width - 2.0f, bandHeight,
                    Math.max(0.0f, radius - 1.0f),
                    new java.awt.Color(255, 255, 255, Math.min(26, alpha / 8)),
                    new java.awt.Color(255, 255, 255, 0));
        }

        if (hairline.isToggled()) {
            // Half a GUI unit, not a whole one. drawRoundOutline multiplies the thickness by the
            // GUI scale factor, so 1.0 was a full Minecraft pixel -- three physical pixels at the
            // usual scale -- and read as a heavy grey border drawn around the pill rather than as
            // a lit edge on it.
            RoundedUtils.drawRoundOutline(x, y, width, height, radius, 0.5f,
                    new java.awt.Color(0, 0, 0, 0),
                    new java.awt.Color(255, 255, 255, Math.min(30, alpha / 7)));
        }
    }

    private void drawSegments(MindlessFontRenderer text, float x, float y, float width, float height,
                              float uiScale, int alpha) {
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);

        float contentWidth = measureWidth(text) * uiScale;
        // While the pill is still easing, keep the content centred in what it has so far rather
        // than letting it spill past the rounded edge.
        float cursor = x + Math.max(PAD_X * uiScale, (width - contentWidth) * 0.5f + PAD_X * uiScale);
        float fontHeight = text.getFontHeight();
        float textY = y + (height - fontHeight * uiScale) * 0.5f;
        float iconSize = ICON_SIZE * uiScale;

        int textColor = withAlpha(0xE8ECF2, alpha);
        int mutedColor = withAlpha(0xA6AEBC, alpha);

        for (int i = 0; i < segmentCount; i++) {
            Segment segment = segments[i];
            boolean chip = segment.icon == Segment.ICON_CHIP;
            float labelWidth = text.getStringWidth(segment.label) * uiScale;

            if (chip) {
                // Chips carry their own pill, so a divider beside one would read as a second edge.
                int chipAlpha = Math.round(alpha * Math.max(0.0f, Math.min(1.0f, segment.fade)));
                float chipHeight = height - PAD_Y * uiScale;
                float chipWidth = segmentWidth(text, segment) * uiScale;
                float chipY = y + (height - chipHeight) * 0.5f;
                RoundedUtils.drawRound(cursor, chipY, chipWidth, chipHeight, chipHeight * 0.5f,
                        withAlpha(0xFFFFFF, Math.min(24, chipAlpha / 8)));

                float dot = CHIP_DOT * uiScale;
                RoundedUtils.drawRound(cursor + CHIP_PAD_X * uiScale, y + (height - dot) * 0.5f,
                        dot, dot, dot * 0.5f, withAlpha(segment.accent, chipAlpha));

                float labelX = cursor + (CHIP_PAD_X + CHIP_DOT + CHIP_DOT_GAP) * uiScale;
                drawScaled(text, segment.label, labelX, textY, uiScale,
                        withAlpha(0xE8ECF2, chipAlpha));
                cursor += chipWidth;
            }
            else {
                if (i > 0 && dividers.isToggled() && segments[i - 1].icon != Segment.ICON_CHIP) {
                    float dividerX = cursor - SEGMENT_GAP * uiScale * 0.5f;
                    float inset = height * 0.28f;
                    RoundedUtils.drawRound(dividerX, y + inset, Math.max(1.0f, uiScale),
                            height - inset * 2.0f, 0.5f, withAlpha(0xFFFFFF, Math.min(38, alpha / 5)));
                }

                int iconColor = accentIcons.isToggled()
                        ? withAlpha(ThemeManager.getWatermarkColor(i * 1.5) & 0xFFFFFF, alpha)
                        : mutedColor;

                float iconY = y + (height - iconSize) * 0.5f;
                drawIcon(segment.icon, cursor, iconY, iconSize, iconColor);
                cursor += iconSize + ICON_GAP * uiScale;

                int colour = segment.emphasised ? textColor : mutedColor;
                drawScaled(text, segment.label, cursor, textY, uiScale, colour);
                cursor += labelWidth;
            }

            if (i < segmentCount - 1) {
                cursor += (segments[i + 1].icon == Segment.ICON_CHIP ? CHIP_GAP : SEGMENT_GAP) * uiScale;
            }
        }

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
    }

    private void drawScaled(MindlessFontRenderer text, String value, float x, float y,
                            float uiScale, int colour) {
        if (uiScale == 1.0f) {
            text.drawString(value, x, y, colour, false);
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0.0f);
        GlStateManager.scale(uiScale, uiScale, 1.0f);
        text.drawString(value, 0.0f, 0.0f, colour, false);
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ icons

    /**
     * Icons are drawn from rounded rects rather than a texture atlas or a font glyph.
     *
     * A glyph would depend on the chosen font actually having it, and a texture would need loading
     * and filtering; primitives stay crisp at any scale and cost nothing to ship.
     */
    private void drawIcon(int icon, float x, float y, float size, int colour) {
        switch (icon) {
            case Segment.ICON_ACCOUNT: {
                float headSize = size * 0.42f;
                RoundedUtils.drawRound(x + (size - headSize) * 0.5f, y + size * 0.04f,
                        headSize, headSize, headSize * 0.5f, colour);
                float bodyWidth = size * 0.82f;
                float bodyHeight = size * 0.40f;
                RoundedUtils.drawRound(x + (size - bodyWidth) * 0.5f, y + size * 0.58f,
                        bodyWidth, bodyHeight, bodyHeight * 0.75f, colour);
                break;
            }
            case Segment.ICON_FPS: {
                // A display on a stand.
                //
                // This was a ring with a stub of a needle across it. At seven pixels the ring's
                // stroke and the needle were both one pixel and sat on top of each other, so it
                // read as a smudge rather than as a gauge. A screen outline has nothing inside it
                // to collide with, and it does not repeat the ping icon's bars.
                float screenHeight = size * 0.72f;
                RoundedUtils.drawRoundOutline(x, y, size, screenHeight, size * 0.18f,
                        Math.max(1.0f, size * 0.15f),
                        new java.awt.Color(0, 0, 0, 0), toColor(colour));
                float standWidth = size * 0.40f;
                float standHeight = Math.max(1.0f, size * 0.16f);
                RoundedUtils.drawRound(x + (size - standWidth) * 0.5f, y + size - standHeight,
                        standWidth, standHeight, standHeight * 0.5f, colour);
                break;
            }
            case Segment.ICON_PING: {
                // Three rising bars.
                float barWidth = size * 0.2f;
                for (int i = 0; i < 3; i++) {
                    float barHeight = size * (0.34f + i * 0.22f);
                    RoundedUtils.drawRound(x + i * (barWidth + size * 0.13f),
                            y + size - barHeight, barWidth, barHeight, barWidth * 0.45f, colour);
                }
                break;
            }
            case Segment.ICON_SERVER: {
                float slotHeight = size * 0.26f;
                RoundedUtils.drawRound(x, y + size * 0.10f, size, slotHeight, slotHeight * 0.35f, colour);
                RoundedUtils.drawRound(x, y + size * 0.62f, size, slotHeight, slotHeight * 0.35f, colour);
                break;
            }
            case Segment.ICON_CLOCK:
            default: {
                RoundedUtils.drawRoundOutline(x, y, size, size, size * 0.5f, Math.max(1.0f, size * 0.16f),
                        new java.awt.Color(0, 0, 0, 0), toColor(colour));
                float handThickness = Math.max(1.0f, size * 0.13f);
                RoundedUtils.drawRound(x + size * 0.47f, y + size * 0.28f,
                        handThickness, size * 0.26f, handThickness * 0.5f, colour);
                RoundedUtils.drawRound(x + size * 0.47f, y + size * 0.47f,
                        size * 0.26f, handThickness, handThickness * 0.5f, colour);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ content

    private void buildSegments(MindlessFontRenderer text) {
        segmentCount = 0;

        if (showAccount.isToggled()) {
            add(Segment.ICON_ACCOUNT, MindlessAccount.displayName(), true);
        }
        if (showFps.isToggled()) {
            add(Segment.ICON_FPS, currentFps() + " fps", false);
        }
        if (showPing.isToggled()) {
            add(Segment.ICON_PING, pingText(), false);
        }
        if (showServer.isToggled()) {
            add(Segment.ICON_SERVER, serverText(), false);
        }
        if (showSession.isToggled()) {
            add(Segment.ICON_CLOCK, sessionText(), false);
        }
        if (showClock.isToggled()) {
            add(Segment.ICON_CLOCK, clockText(), false);
        }
        if (showStatus.isToggled()) {
            long now = System.currentTimeMillis();
            pollToggles(now);
            addToggleChips(now);
        }
    }

    /**
     * Watches every module for a toggle and remembers the recent ones.
     *
     * Listing everything that happened to be enabled filled the pill with modules that are simply
     * always on -- Discord RPC, the knockback settings -- and never changed, which is the opposite
     * of useful. A chip is an event now: it appears when you turn something on or off, and leaves
     * on its own, so the pill only ever carries what just happened.
     *
     * Toggles are polled rather than hooked. Module.enable and disable are on the path every
     * keybind and every script takes, and nothing here is worth putting a callback in that path
     * for.
     */
    private void pollToggles(long now) {
        long life = (long) (statusDuration.getInput() * 1000.0);
        boolean announceOff = statusValues.isToggled();

        java.util.List<mindless.module.Module> modules = mindless.module.ModuleManager.modules;
        if (modules != null) {
            synchronized (modules) {
                for (int i = 0; i < modules.size(); i++) {
                    trackToggle(modules.get(i), now, life, announceOff);
                }
            }
        }

        // Dropped as soon as they expire, so the list never outgrows what is on screen.
        for (int i = recentToggles.size() - 1; i >= 0; i--) {
            if (now - recentToggles.get(i).bornAt > life) {
                recentToggles.remove(i);
            }
        }
    }

    private void trackToggle(mindless.module.Module module, long now, long life, boolean announceOff) {
        if (module == null || module.isHidden()) return;

        boolean enabled = module.isEnabled();
        Boolean previous = toggleStates.put(module, enabled);
        // First sighting is the current state, not a toggle: without this every module would
        // announce itself the first frame the island drew.
        if (previous == null || previous.booleanValue() == enabled) return;
        if (!enabled && !announceOff) return;

        String name = module.getNameInHud();
        if (name == null || name.isEmpty()) return;

        while (recentToggles.size() >= MAX_TOGGLES) {
            recentToggles.remove(0);
        }
        recentToggles.add(new Toggle(name, enabled, now));
    }

    /** The toggles still inside their lifetime, newest last, capped at what the user asked for. */
    private void addToggleChips(long now) {
        int limit = Math.min((int) statusLimit.getInput(), MAX_SEGMENTS - segmentCount);
        if (limit <= 0 || recentToggles.isEmpty()) return;

        long life = Math.max(1L, (long) (statusDuration.getInput() * 1000.0));
        int from = Math.max(0, recentToggles.size() - limit);
        for (int i = from; i < recentToggles.size(); i++) {
            Toggle toggle = recentToggles.get(i);
            float age = (now - toggle.bornAt) / (float) life;
            if (age >= 1.0f) continue;

            // Fades over the last third of its life, so it leaves rather than blinking out.
            float fade = age < 0.66f ? 1.0f : Math.max(0.0f, (1.0f - age) / 0.34f);
            addChip(toggle.name + "  " + (toggle.enabled ? "ON" : "OFF"),
                    toggle.enabled ? ACCENT_ON : ACCENT_OFF, fade);
        }
    }

    private void add(int icon, String label, boolean emphasised) {
        addSegment(icon, label, emphasised, 0, 1.0f);
    }

    private void addChip(String label, int accent, float fade) {
        addSegment(Segment.ICON_CHIP, label, true, accent, fade);
    }

    private void addSegment(int icon, String label, boolean emphasised, int accent, float fade) {
        if (segmentCount >= MAX_SEGMENTS || label == null || label.isEmpty()) return;
        Segment segment = segments[segmentCount];
        if (segment == null) {
            segment = new Segment();
            segments[segmentCount] = segment;
        }
        segment.icon = icon;
        segment.label = label;
        segment.emphasised = emphasised;
        segment.accent = accent;
        segment.fade = fade;
        segmentCount++;
    }

    /** Width of one segment's contents, excluding the gap that follows it. */
    private float segmentWidth(MindlessFontRenderer text, Segment segment) {
        if (segment.icon == Segment.ICON_CHIP) {
            return CHIP_PAD_X * 2.0f + CHIP_DOT + CHIP_DOT_GAP + text.getStringWidth(segment.label);
        }
        return ICON_SIZE + ICON_GAP + text.getStringWidth(segment.label);
    }

    private float measureWidth(MindlessFontRenderer text) {
        float width = PAD_X * 2.0f;
        for (int i = 0; i < segmentCount; i++) {
            width += segmentWidth(text, segments[i]);
            if (i < segmentCount - 1) {
                width += segments[i + 1].icon == Segment.ICON_CHIP ? CHIP_GAP : SEGMENT_GAP;
            }
        }
        return width;
    }

    /**
     * The real framerate, unsmoothed.
     *
     * This used to ease toward the counter. Minecraft only republishes it once a second, so easing
     * toward a value that holds still for a second meant the island spent most of every second
     * displaying a number the machine was not running at, and disagreeing with every other FPS
     * readout on screen. There is nothing here to smooth: the source is already a one second
     * average.
     */
    private int currentFps() {
        return Math.max(0, Minecraft.getDebugFPS());
    }

    private String pingText() {
        if (mc.isSingleplayer()) return "0 ms";
        NetHandlerPlayClient handler = mc.getNetHandler();
        if (handler == null || mc.thePlayer == null) return "-- ms";
        NetworkPlayerInfo info = handler.getPlayerInfo(mc.thePlayer.getUniqueID());
        return info == null ? "-- ms" : info.getResponseTime() + " ms";
    }

    private String serverText() {
        if (mc.isSingleplayer()) return "singleplayer";
        ServerData server = mc.getCurrentServerData();
        if (server == null || server.serverIP == null || server.serverIP.isEmpty()) return "unknown";
        String ip = server.serverIP;
        int port = ip.indexOf(':');
        return port > 0 ? ip.substring(0, port) : ip;
    }

    private String sessionText() {
        long seconds = Math.max(0L, (System.currentTimeMillis() - sessionStart) / 1000L);
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (hours > 0L) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m " + (seconds % 60L) + "s";
    }

    private String clockText() {
        java.util.Calendar calendar = java.util.Calendar.getInstance();
        int hour = calendar.get(java.util.Calendar.HOUR_OF_DAY);
        int minute = calendar.get(java.util.Calendar.MINUTE);
        return (hour < 10 ? "0" : "") + hour + ":" + (minute < 10 ? "0" : "") + minute;
    }

    // ------------------------------------------------------------------ placement

    private float anchoredX(ScaledResolution resolution, float width) {
        switch ((int) anchor.getInput()) {
            case 1: return EDGE_MARGIN;
            case 2: return resolution.getScaledWidth() - width - EDGE_MARGIN;
            case 3: {
                if (islandPosX < 0.0f) {
                    islandPosX = (resolution.getScaledWidth() - width) * 0.5f;
                }
                return Math.max(0.0f, Math.min(resolution.getScaledWidth() - width, islandPosX));
            }
            default: return (resolution.getScaledWidth() - width) * 0.5f;
        }
    }

    private float anchoredY(ScaledResolution resolution, float height) {
        if ((int) anchor.getInput() == 3) {
            if (islandPosY < 0.0f) {
                islandPosY = EDGE_MARGIN;
            }
            return Math.max(0.0f, Math.min(resolution.getScaledHeight() - height, islandPosY));
        }
        return EDGE_MARGIN;
    }

    /** Bounds of whatever the module is currently drawing, for the HUD editor. */
    public float[] getIslandBounds() {
        MindlessFontRenderer text = islandFont();
        if (text == null) return null;
        buildSegments(text);
        if (segmentCount == 0) return null;

        float uiScale = (float) scale.getInput();
        float width = measureWidth(text) * uiScale;
        float height = (PAD_Y * 2.0f + text.getFontHeight()) * uiScale;
        ScaledResolution resolution = ScaledResolutionCache.get();
        float x = anchoredX(resolution, width);
        float y = anchoredY(resolution, height);
        return new float[]{x, y, x + width, y + height};
    }

    public boolean isCustomAnchored() {
        return (int) mode.getInput() == 0 && (int) anchor.getInput() == 3;
    }

    public boolean isIslandMode() {
        return (int) mode.getInput() == 0;
    }

    // ------------------------------------------------------------------ text mode

    private MindlessFontRenderer islandFont() {
        return FontManager.getHudRenderer(ModuleFont.nameOf(font), HUD.getSelectedFontScale());
    }

    private MindlessFontRenderer getWatermarkFont() {
        return FontManager.getHudRenderer(ModuleFont.nameOf(font),
                HUD.getSelectedFontScale() * WATERMARK_SCALE);
    }

    private void renderTextWatermark() {
        MindlessFontRenderer watermarkFont = getWatermarkFont();
        if (watermarkFont == null) return;

        String value = "Mindless";
        int baseColor = ThemeManager.getWatermarkColor(0.0);
        int r = (baseColor >> 16) & 0xFF;
        int g = (baseColor >> 8) & 0xFF;
        int b = baseColor & 0xFF;

        if (HudGlowHelper.isAvailable()) {
            HudGlowHelper.beginMask();
            watermarkFont.drawGlyphString(value, textPosX, textPosY,
                    (character, xOffset, width, formattingColor)
                            -> ThemeManager.getWatermarkColor(xOffset * 0.1), false);
            HudGlowHelper.endAndComposite(8.0f, 1.2f, r, g, b);
        }

        watermarkFont.drawGlyphString(value, textPosX, textPosY,
                (character, xOffset, width, formattingColor)
                        -> ThemeManager.getWatermarkColor(xOffset * 0.1), false);
    }

    public float[] getTextBounds() {
        MindlessFontRenderer watermarkFont = getWatermarkFont();
        if (watermarkFont == null) return null;
        String value = "Mindless";
        return new float[]{textPosX, textPosY,
                textPosX + watermarkFont.getStringWidth(value),
                textPosY + watermarkFont.getFontHeight()};
    }

    // ------------------------------------------------------------------ helpers

    private float frameDelta() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0f / 60.0f;
        }
        float delta = (now - lastFrameNanos) / 1_000_000_000.0f;
        lastFrameNanos = now;
        // A pause or an alt-tab must not teleport the easing.
        return Math.max(0.0f, Math.min(0.1f, delta));
    }


    private static float approach(float current, float target, float rate, float delta) {
        float factor = 1.0f - (float) Math.exp(-delta * rate);
        return current + (target - current) * factor;
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static java.awt.Color toColor(int argb) {
        return new java.awt.Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF,
                (argb >>> 24) & 0xFF);
    }

    private static final class Segment {
        private static final int ICON_ACCOUNT = 0;
        private static final int ICON_FPS = 1;
        private static final int ICON_PING = 2;
        private static final int ICON_SERVER = 3;
        private static final int ICON_CLOCK = 4;

        /** A chip carries its own pill and accent dot instead of an icon and a divider. */
        private static final int ICON_CHIP = 5;

        private int icon;
        private String label;
        private boolean emphasised;
        private int accent;
        private float fade;
    }

    /** One module toggle, alive until its lifetime runs out. */
    private static final class Toggle {
        private final String name;
        private final boolean enabled;
        private final long bornAt;

        private Toggle(String name, boolean enabled, long bornAt) {
            this.name = name;
            this.enabled = enabled;
            this.bornAt = bornAt;
        }
    }
}
