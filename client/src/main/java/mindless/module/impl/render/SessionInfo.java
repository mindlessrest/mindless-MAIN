package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SessionInfo extends Module {
    private static final float DEFAULT_RELATIVE_X = 0.985f;
    private static final float DEFAULT_RELATIVE_Y = 0.5f;

    private static final float PAD_X = 12.0f;
    private static final float PAD_Y = 8.0f;
    private static final float PAD_BOTTOM = 6.0f;
    private static final float HEADER_GAP = 9.0f;
    private static final float VALUE_GAP = 2.0f;
    private static final float CELL_GAP = 14.0f;
    private static final float TITLE_GAP = 14.0f;

    private static final int COL_LABEL = new Color(136, 139, 150).getRGB();
    private static final int COL_TITLE = new Color(255, 255, 255).getRGB();
    private static final int COL_CLOCK = new Color(148, 151, 163).getRGB();
    private static final int COL_UP    = new Color(122, 214, 168).getRGB();
    private static final int COL_DOWN  = new Color(224, 122, 122).getRGB();
    // A zero has nothing to say yet, and four coloured zeros at the start of a session read as
    // a warning rather than an empty scoreboard.
    private static final int COL_ZERO  = new Color(118, 121, 132).getRGB();

    /** Ranks, guild tags and the like, so a name can be read off the front of a line. */
    private static final Pattern RANK_TAG = Pattern.compile("\\[[^\\]]*\\]\\s*");
    private static final Pattern KILLED_BY = Pattern.compile("\\bby ([A-Za-z0-9_]{1,16})\\b");
    private static final Pattern DUEL_WINNER = Pattern.compile("^Winner: ([A-Za-z0-9_]{1,16})\\b");
    private static final Pattern NICKED_AS = Pattern.compile("nicked as ([A-Za-z0-9_]{1,16})");

    /**
     * A death line is a username followed directly by the verb that killed them. Anchoring on
     * that shape is the whole point: searching the line for death words instead meant anything
     * carrying "by <name>" could score a kill, and bed destruction -- "Red Bed was destroyed by
     * <you>" -- did exactly that, once per bed.
     */
    private static final Pattern DEATH_LINE = Pattern.compile(
            "^([A-Za-z0-9_]{1,16}) (was|fell|died|hit|burned|drowned|suffocated"
            + "|blew|walked|went|withered|starved)\\b");

    /** Objectives, not deaths, and several of them name a player after "by". */
    private static final String[] NOT_A_DEATH = {
            "DESTRUCTION", "destroyed", "collected", "purchased", "joined", "disconnected"
    };

    /**
     * Hypixel announces a result over several lines, and older parsing counted each of them. One
     * result per window is enough; games never end twice this close together.
     */
    private static final long RESULT_COOLDOWN_MS = 20_000L;

    private static final String[] MODES = { "Modern", "Classic" };

    private final SliderSetting mode;
    private final SliderSetting scale;
    private final ButtonSetting showKills;
    private final ButtonSetting showDeaths;
    private final ButtonSetting showWins;
    private final ButtonSetting showLosses;
    private final ButtonSetting showKdr;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private long sessionStartMs;
    private long lastResultMs;
    private String lastLine = "";
    private long lastLineMs;
    private String lastVictim = "";
    private long lastVictimMs;
    private String nickName = "";
    private int kills;
    private int deaths;
    private int wins;
    private int losses;

    public SessionInfo() {
        super("Session Info", category.render);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.6, 1.6, 0.05));
        this.registerSetting(showKills = new ButtonSetting("Show kills", true));
        this.registerSetting(showDeaths = new ButtonSetting("Show deaths", true));
        this.registerSetting(showWins = new ButtonSetting("Show wins", true));
        this.registerSetting(showLosses = new ButtonSetting("Show losses", true));
        this.registerSetting(showKdr = new ButtonSetting("Show KDR", true));
        this.registerSetting(new ButtonSetting("Reset", this::resetSession));
    }

    @Override
    public void onEnable() {
        resetSession();
    }

    private void resetSession() {
        sessionStartMs = System.currentTimeMillis();
        lastResultMs = 0L;
        kills = 0;
        deaths = 0;
        wins = 0;
        losses = 0;
    }

    public SliderSetting scaleSetting() {
        return scale;
    }

    // ------------------------------------------------------------------ position

    public float getPosX() {
        syncPositionToResolution();
        return posX;
    }

    public float getPosY() {
        syncPositionToResolution();
        return posY;
    }

    public float getRelativePosX() {
        syncPositionToResolution();
        return relativePosX;
    }

    public float getRelativePosY() {
        syncPositionToResolution();
        return relativePosY;
    }

    public void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPositionToResolution();
    }

    public void setAbsolutePosition(float absoluteX, float absoluteY) {
        setAbsolutePosition(absoluteX, absoluteY, ScaledResolutionCache.get());
    }

    public void resetPosition() {
        setRelativePosition(DEFAULT_RELATIVE_X, DEFAULT_RELATIVE_Y);
    }

    private void syncPositionToResolution() {
        ScaledResolution resolution = ScaledResolutionCache.get();
        int scaledWidth = Math.max(1, resolution.getScaledWidth());
        int scaledHeight = Math.max(1, resolution.getScaledHeight());

        if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
            if (Float.isNaN(posX) || Float.isNaN(posY)) {
                relativePosX = DEFAULT_RELATIVE_X;
                relativePosY = DEFAULT_RELATIVE_Y;
            }
            else {
                relativePosX = posX / scaledWidth;
                relativePosY = posY / scaledHeight;
            }
        }

        posX = relativePosX * scaledWidth;
        posY = relativePosY * scaledHeight;
    }

    private void setAbsolutePosition(float absoluteX, float absoluteY, ScaledResolution resolution) {
        posX = absoluteX;
        posY = absoluteY;
        relativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        relativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
    }

    /** Draws the panel where it already sits and reports its bounds, for the HUD editor. */
    public float[] renderPreview() {
        return (int) mode.getInput() == 0 ? drawModern() : draw();
    }

    /** Draws the panel at a requested top-left and reports its bounds, for the HUD editor. */
    public float[] renderDesignerPreview(float absoluteLeft, float absoluteTop) {
        ScaledResolution resolution = ScaledResolutionCache.get();
        float[] size = measure();
        if (size == null) return null;
        // The stored anchor is the panel's right edge, so a left-edge drag has to be converted.
        setAbsolutePosition(absoluteLeft + size[0], absoluteTop, resolution);
        return draw();
    }

    // ------------------------------------------------------------------ tracking

    /**
     * Hypixel names the victim at the front of a death line and the killer after "by". Reading
     * both out of that one structure is what keeps a kill from also registering as a death, and
     * a final kill from registering twice.
     */
    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2 || mc.thePlayer == null) return;

        String raw = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (raw == null) return;

        String line = RANK_TAG.matcher(raw).replaceAll("").trim();
        if (line.isEmpty()) return;

        // The same line arriving twice in a frame or two is a repeat, not a second event.
        long now = System.currentTimeMillis();
        if (line.equals(lastLine) && now - lastLineMs < 500L) return;
        lastLine = line;
        lastLineMs = now;

        Matcher nick = NICKED_AS.matcher(line);
        if (nick.find()) {
            nickName = nick.group(1);
            return;
        }
        if (line.contains("no longer nicked") || line.contains("nick has been reset")
                || line.contains("You are no longer disguised")) {
            nickName = "";
            return;
        }

        Matcher duel = DUEL_WINNER.matcher(line);
        if (duel.find()) {
            recordResult(isMe(duel.group(1)));
            return;
        }
        if (line.contains("VICTORY!") || line.contains("You won")) {
            recordResult(true);
            return;
        }
        if (line.contains("GAME OVER") || line.contains("You lost")) {
            recordResult(false);
            return;
        }

        for (String objective : NOT_A_DEATH) {
            if (line.contains(objective)) return;
        }

        Matcher death = DEATH_LINE.matcher(line);
        if (!death.find()) return;

        String victim = death.group(1);
        String killer = null;
        Matcher by = KILLED_BY.matcher(line);
        while (by.find()) killer = by.group(1);

        if (victim.equals(lastVictim) && now - lastVictimMs < 2000L) return;
        lastVictim = victim;
        lastVictimMs = now;

        if (isMe(victim)) deaths++;
        else if (isMe(killer)) kills++;
    }

    @SubscribeEvent
    public void onReceivePacket(mindless.event.ReceivePacketEvent event) {
        if (!(event.getPacket() instanceof net.minecraft.network.play.server.S45PacketTitle)) return;
        net.minecraft.network.play.server.S45PacketTitle packet =
                (net.minecraft.network.play.server.S45PacketTitle) event.getPacket();
        if (packet.getType() != net.minecraft.network.play.server.S45PacketTitle.Type.TITLE) return;
        if (packet.getMessage() == null) return;
        String text = EnumChatFormatting.getTextWithoutFormattingCodes(packet.getMessage().getUnformattedText());
        if (text == null) return;
        String upper = text.toUpperCase().trim();
        if (upper.contains("WIN") || upper.contains("VICTORY")) {
            recordResult(true);
        } else if (upper.contains("LOST") || upper.contains("LOSS") || upper.contains("GAME OVER")) {
            recordResult(false);
        }
    }

    /** The account name, or the nick when one is active. */
    private boolean isMe(String name) {
        if (name == null || mc.thePlayer == null) return false;
        if (name.equalsIgnoreCase(mc.thePlayer.getName())) return true;
        return !nickName.isEmpty() && name.equalsIgnoreCase(nickName);
    }

    private void recordResult(boolean won) {
        long now = System.currentTimeMillis();
        if (now - lastResultMs < RESULT_COOLDOWN_MS) return;
        lastResultMs = now;
        if (won) wins++;
        else losses++;
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        if ((int) mode.getInput() == 0) drawModern();
        else draw();
    }

    private float[] drawModern() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        RavenFontRenderer bigFont = valueFont();
        if (font == null || bigFont == null) return null;

        syncPositionToResolution();
        float s = (float) scale.getInput();

        java.util.List<String> lines = new java.util.ArrayList<>();
        if (showKills.isToggled()) lines.add(kills + " kills");
        if (showDeaths.isToggled()) lines.add(deaths + " deaths");
        if (showWins.isToggled()) lines.add(wins + " wins");
        if (showLosses.isToggled()) lines.add(losses + " losses");
        if (showKdr.isToggled()) {
            String kdr = deaths == 0 ? String.format("%.1f", (double) kills) : String.format("%.2f", (double) kills / deaths);
            lines.add(kdr + " kdr");
        }

        String timeStr = formatTime(System.currentTimeMillis() - sessionStartMs);
        String headerLeft = "Session";
        String headerRight = " Information";

        float lineH = font.getFontHeight() + 3.0f;
        float bigH = bigFont.getFontHeight();
        float contentW = 0;
        contentW = Math.max(contentW, font.getStringWidth(headerLeft + headerRight));
        contentW = Math.max(contentW, bigFont.getStringWidth(timeStr));
        for (String line : lines) contentW = Math.max(contentW, font.getStringWidth(line));

        float padX = 10.0f;
        float padY = 8.0f;
        float headerGap = 6.0f;
        float timeGap = 6.0f;
        float totalW = (contentW + padX * 2) * s;
        float totalH = (padY + font.getFontHeight() + headerGap + bigH + timeGap + lineH * lines.size() + padY) * s;

        float left = posX - totalW;
        float top = posY;
        float radius = 9.0f * mindless.module.impl.theme.ThemeManager.roundingScale();

        BlurUtils.prepareBlur(left, top, totalW, totalH);
        RoundedUtils.drawRound(left, top, totalW, totalH, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2, top - 2, totalW + 4, totalH + 4);
        RoundedUtils.drawRound(left, top, totalW, totalH, radius, new Color(0, 0, 0, 140));

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;

        float tx = left * inv + padX;
        float ty = top * inv + padY;

        int themeColor = HUD.getHudColor(0);
        font.drawString(headerLeft, tx, ty, themeColor, false);
        font.drawString(headerRight, tx + font.getStringWidth(headerLeft), ty, 0xFFFFFFFF, false);
        ty += font.getFontHeight() + headerGap;

        bigFont.drawString(timeStr, tx, ty, 0xFFFFFFFF, false);
        ty += bigH + timeGap;

        int lineColor = new Color(190, 190, 190).getRGB();
        for (String line : lines) {
            font.drawString(line, tx, ty, lineColor, false);
            ty += lineH;
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        return new float[] { left, top, left + totalW, top + totalH };
    }

    /** A real larger face for the numbers, rather than scaling the small one up and blurring it. */
    private static RavenFontRenderer valueFont() {
        return FontManager.getHudRenderer(HUD.getSelectedFontName(),
                Math.min(2.0f, HUD.getSelectedFontScale() * 1.6f));
    }

    private java.util.List<String> activeValues() {
        java.util.List<String> vals = new java.util.ArrayList<>();
        if (showKills.isToggled()) vals.add(Integer.toString(kills));
        if (showDeaths.isToggled()) vals.add(Integer.toString(deaths));
        if (showWins.isToggled()) vals.add(Integer.toString(wins));
        if (showLosses.isToggled()) vals.add(Integer.toString(losses));
        if (showKdr.isToggled()) {
            vals.add(deaths == 0 ? String.format("%.1f", (double) kills) : String.format("%.2f", (double) kills / deaths));
        }
        return vals;
    }

    private java.util.List<String> activeLabels() {
        java.util.List<String> labels = new java.util.ArrayList<>();
        if (showKills.isToggled()) labels.add("KILLS");
        if (showDeaths.isToggled()) labels.add("DEATHS");
        if (showWins.isToggled()) labels.add("WINS");
        if (showLosses.isToggled()) labels.add("LOSSES");
        if (showKdr.isToggled()) labels.add("KDR");
        return labels;
    }

    private java.util.List<Integer> activeColors() {
        java.util.List<Integer> colors = new java.util.ArrayList<>();
        if (showKills.isToggled()) colors.add(COL_UP);
        if (showDeaths.isToggled()) colors.add(COL_DOWN);
        if (showWins.isToggled()) colors.add(COL_UP);
        if (showLosses.isToggled()) colors.add(COL_DOWN);
        if (showKdr.isToggled()) colors.add(COL_UP);
        return colors;
    }

    private java.util.List<Integer> activeCounts() {
        java.util.List<Integer> counts = new java.util.ArrayList<>();
        if (showKills.isToggled()) counts.add(kills);
        if (showDeaths.isToggled()) counts.add(deaths);
        if (showWins.isToggled()) counts.add(wins);
        if (showLosses.isToggled()) counts.add(losses);
        if (showKdr.isToggled()) counts.add(kills > 0 || deaths > 0 ? 1 : 0);
        return counts;
    }

    private float cellWidth(RavenFontRenderer small, RavenFontRenderer big, java.util.List<String> vals, java.util.List<String> labels) {
        float widest = 0.0f;
        for (int i = 0; i < vals.size(); i++) {
            widest = Math.max(widest, Math.max(big.getStringWidth(vals.get(i)),
                    small.getStringWidth(labels.get(i))));
        }
        return widest;
    }

    private float[] measure() {
        RavenFontRenderer small = HUD.getHudFontRenderer();
        RavenFontRenderer big = valueFont();
        if (small == null || big == null) return null;

        java.util.List<String> vals = activeValues();
        java.util.List<String> labels = activeLabels();
        int count = vals.size();
        if (count == 0) return null;

        float s = (float) scale.getInput();

        float strip = cellWidth(small, big, vals, labels) * count + CELL_GAP * (count - 1);
        float header = small.getStringWidth("SESSION") + TITLE_GAP + small.getStringWidth(clock());
        float content = Math.max(strip, header);

        float height = PAD_Y + small.getFontHeight() + HEADER_GAP
                + big.getFontHeight() + VALUE_GAP + small.getFontHeight() + PAD_BOTTOM;

        return new float[] { (content + PAD_X * 2.0f) * s, height * s };
    }

    /** Draws the panel and returns its bounds as {left, top, right, bottom}. */
    private float[] draw() {
        RavenFontRenderer small = HUD.getHudFontRenderer();
        RavenFontRenderer big = valueFont();
        if (small == null || big == null) return null;
        float[] size = measure();
        if (size == null) return null;

        syncPositionToResolution();
        float s = (float) scale.getInput();
        float w = size[0];
        float h = size[1];
        float left = posX - w;
        float top = posY;

        float radius = 9.0f * mindless.module.impl.theme.ThemeManager.roundingScale();

        // A blurred panel with no edge treatment has nothing separating it from the world; it
        // reads as a smudge rather than a card, and over bright terrain the sides all but
        // disappear. The shadow gives it somewhere to sit and the hairline below gives it an edge.
        //
        // The shadow goes first because everything from prepareBlur onwards is stencilled to the
        // panel shape and would paint over it.
        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 5.0f,
                new Color(0, 0, 0, 130).getRGB());

        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        // Region-limited: the full-screen variant composites the whole framebuffer twice for a
        // panel this size.
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 130));
        // Same sheen as the alert cards, so the two overlays read as one family instead of a
        // shaded box next to a flat one.
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 20), new Color(255, 255, 255, 4));

        // A real ring, drawn last.
        //
        // This used to be a translucent rounded rect a pixel proud of the panel, which is only an
        // outline for as long as something else paints over its middle -- here, the blur. Any
        // frame where the blur did not composite, the whole white rectangle showed instead of its
        // edge, which is the box turning white at random. The outline shader takes a fill and a
        // rim separately and needs nothing painted over it, so there is no frame in which it can
        // come out wrong.
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 30));

        // The rounded-rect and blur shaders leave a program bound; glyph quads drawn through it
        // come out garbled.
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;

        float textLeft = left * inv + PAD_X;
        float textRight = (left + w) * inv - PAD_X;
        float textTop = top * inv + PAD_Y;
        String clock = clock();

        font(small, "SESSION", textLeft, textTop, COL_TITLE);
        font(small, clock, textRight - small.getStringWidth(clock), textTop, COL_CLOCK);

        java.util.List<String> vals = activeValues();
        java.util.List<String> labels = activeLabels();
        java.util.List<Integer> colors = activeColors();
        java.util.List<Integer> counts = activeCounts();
        int count = vals.size();
        if (count == 0) { GlStateManager.popMatrix(); return new float[] { left, top, left + w, top + h }; }

        float valueTop = textTop + small.getFontHeight() + HEADER_GAP;
        float labelTop = valueTop + big.getFontHeight() + VALUE_GAP;
        float slot = (textRight - textLeft) / (float) count;

        for (int i = 0; i < count; i++) {
            float center = textLeft + slot * (i + 0.5f);
            int c = counts.get(i);
            int nonZero = (c | -c) >> 31;
            int color = (colors.get(i) & nonZero) | (COL_ZERO & ~nonZero);
            font(big, vals.get(i), center - big.getStringWidth(vals.get(i)) * 0.5f, valueTop, color);
            font(small, labels.get(i), center - small.getStringWidth(labels.get(i)) * 0.5f, labelTop, COL_LABEL);
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        return new float[] { left, top, left + w, top + h };
    }

    private static void font(RavenFontRenderer renderer, String text, float x, float y, int color) {
        renderer.drawString(text, Math.round(x), Math.round(y), color, false);
    }

    private String clock() {
        return formatTime(System.currentTimeMillis() - sessionStartMs);
    }

    private String formatTime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) return hours + "h " + (minutes % 60) + "m";
        if (minutes > 0) return minutes + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
}
