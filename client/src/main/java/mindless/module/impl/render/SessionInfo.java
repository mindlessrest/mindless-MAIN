package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
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
    // The labels are all caps, so the descender space at the bottom of the last line is empty
    // and an equal bottom padding measures larger than it looks.
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

    private final SliderSetting scale;

    // Anchored on the right edge: the panel sits in the top-right by default and grows leftward,
    // so storing the left edge would make it drift as the numbers get wider.
    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private long sessionStartMs;
    private long lastResultMs;
    private String lastLine = "";
    private long lastLineMs;
    // Chat calls a nicked player by the nick, not the account, so the account name alone stops
    // matching anything the moment you nick.
    private String nickName = "";
    private int kills;
    private int deaths;
    private int wins;
    private int losses;

    public SessionInfo() {
        super("Session Info", category.render);
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.6, 1.6, 0.05));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new HudEditor.Screen())));
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
        return draw();
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

        if (isMe(victim)) deaths++;
        else if (isMe(killer)) kills++;
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
        draw();
    }

    /** A real larger face for the numbers, rather than scaling the small one up and blurring it. */
    private static RavenFontRenderer valueFont() {
        return FontManager.getHudRenderer(HUD.getSelectedFontName(),
                Math.min(2.0f, HUD.getSelectedFontScale() * 1.6f));
    }

    private String[] values() {
        return new String[] {
                Integer.toString(kills), Integer.toString(deaths),
                Integer.toString(wins), Integer.toString(losses)
        };
    }

    private static final String[] LABELS = { "KILLS", "DEATHS", "WINS", "LOSSES" };
    private static final int[] VALUE_COLORS = { COL_UP, COL_DOWN, COL_UP, COL_DOWN };

    private int[] counts() {
        return new int[] { kills, deaths, wins, losses };
    }

    /**
     * The width one stat needs, taken from the widest of the four rather than each one's own
     * label. Sizing every stat to itself put "KILLS" and "LOSSES" in columns of different
     * widths, and with equal gaps between them the four numbers came out unevenly spaced.
     */
    private float cellWidth(RavenFontRenderer small, RavenFontRenderer big) {
        String[] vals = values();
        float widest = 0.0f;
        for (int i = 0; i < 4; i++) {
            widest = Math.max(widest, Math.max(big.getStringWidth(vals[i]),
                    small.getStringWidth(LABELS[i])));
        }
        return widest;
    }

    /** Panel size for the current contents, as {width, height}, or null when it cannot be drawn. */
    private float[] measure() {
        RavenFontRenderer small = HUD.getHudFontRenderer();
        RavenFontRenderer big = valueFont();
        if (small == null || big == null) return null;

        float s = (float) scale.getInput();

        float strip = cellWidth(small, big) * 4.0f + CELL_GAP * 3.0f;
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

        float radius = 8.0f * mindless.module.impl.theme.ThemeManager.roundingScale();
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEnd(2, 2.4f, 0.85f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 120));
        // Same sheen as the alert cards, so the two overlays read as one family instead of a
        // shaded box next to a flat one.
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 17), new Color(255, 255, 255, 3));

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

        String[] vals = values();
        int[] counts = counts();

        float valueTop = textTop + small.getFontHeight() + HEADER_GAP;
        float labelTop = valueTop + big.getFontHeight() + VALUE_GAP;

        // Four equal columns across the full content width. Even columns are what make the row
        // read as a row; sizing each to its own label left the gaps visibly ragged.
        float slot = (textRight - textLeft) / 4.0f;

        for (int i = 0; i < 4; i++) {
            float center = textLeft + slot * (i + 0.5f);
            int color = counts[i] == 0 ? COL_ZERO : VALUE_COLORS[i];
            font(big, vals[i], center - big.getStringWidth(vals[i]) * 0.5f, valueTop, color);
            font(small, LABELS[i], center - small.getStringWidth(LABELS[i]) * 0.5f, labelTop, COL_LABEL);
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
