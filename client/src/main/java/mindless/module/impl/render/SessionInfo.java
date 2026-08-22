package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.client.Settings;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Theme;
import mindless.utility.Utils;
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
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class SessionInfo extends Module {
    private static final DecimalFormat RATIO_FORMAT = new DecimalFormat("0.00");

    private static final float DEFAULT_RELATIVE_X = 0.985f;
    private static final float DEFAULT_RELATIVE_Y = 0.5f;

    private static final float PAD_X = 8.0f;
    private static final float PAD_Y = 7.0f;
    private static final float COL_GAP = 16.0f;
    private static final float ROW_GAP = 2.0f;
    private static final float RULE_GAP = 5.0f;

    private static final int COL_LABEL  = new Color(150, 152, 163).getRGB();
    private static final int COL_VALUE  = new Color(238, 239, 245).getRGB();
    private static final int COL_TITLE  = new Color(255, 255, 255).getRGB();
    private static final int COL_CLOCK  = new Color(160, 162, 174).getRGB();
    private static final int COL_GOOD   = new Color(88, 214, 141).getRGB();
    private static final int COL_BAD    = new Color(232, 104, 104).getRGB();

    private final ButtonSetting showKDR;
    private final ButtonSetting showFKDR;
    private final ButtonSetting showWL;
    private final ButtonSetting colorRatios;
    private final SliderSetting scale;

    // Anchored on the right edge: the panel sits in the top-right by default and grows leftward,
    // so storing the left edge would make it drift as rows are toggled on and off.
    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    private long sessionStartMs;
    private int kills;
    private int deaths;
    private int finalKills;
    private int finalDeaths;
    private int wins;
    private int losses;

    public SessionInfo() {
        super("Session Info", category.render);
        this.registerSetting(showKDR = new ButtonSetting("Show KDR", true));
        this.registerSetting(showFKDR = new ButtonSetting("Show FKDR", true));
        this.registerSetting(showWL = new ButtonSetting("Show W/L", true));
        this.registerSetting(colorRatios = new ButtonSetting("Color ratios", true));
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
        kills = 0;
        deaths = 0;
        finalKills = 0;
        finalDeaths = 0;
        wins = 0;
        losses = 0;
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

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return;
        String msg = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (msg == null) return;

        String playerName = mc.thePlayer != null ? mc.thePlayer.getName() : "";

        if (msg.contains("killed by " + playerName) || msg.contains("was killed by " + playerName)
                || msg.contains("by " + playerName + ".") || msg.contains("by " + playerName + " ")) {
            kills++;
        }

        if (msg.contains(playerName + " was killed") || msg.contains(playerName + " was shot")
                || msg.contains(playerName + " was thrown") || msg.contains(playerName + " fell")
                || msg.contains(playerName + " was knocked") || msg.contains(playerName + " burned")
                || msg.contains(playerName + " drowned") || msg.contains(playerName + " died")
                || msg.contains(playerName + " walked into") || msg.contains(playerName + " suffocated")
                || msg.contains(playerName + " was slain")) {
            deaths++;
        }

        if (msg.contains("FINAL KILL!") && msg.contains(playerName)) {
            finalKills++;
        }

        if (msg.contains("You died!") || (msg.contains(playerName) && msg.contains("FINAL DEATH!"))) {
            finalDeaths++;
        }

        if (msg.contains("VICTORY!") || msg.contains("Winner") || msg.contains("1st Place")
                || msg.contains(" won the game!") || msg.contains("You won!")) {
            wins++;
        }

        if (msg.contains("GAME OVER!") || msg.contains("You lost!") || msg.contains(" has won")) {
            if (!msg.contains("VICTORY!") && !msg.contains("You won!")) {
                losses++;
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw();
    }

    private static final class Row {
        final String label;
        final String value;
        final int color;

        Row(String label, String value, int color) {
            this.label = label;
            this.value = value;
            this.color = color;
        }
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<Row>();
        rows.add(new Row("K / D", kills + " / " + deaths, COL_VALUE));
        if (showKDR.isToggled()) {
            double kdr = deaths == 0 ? kills : (double) kills / deaths;
            rows.add(new Row("KDR", RATIO_FORMAT.format(kdr), ratioColor(kdr)));
        }
        if (showFKDR.isToggled()) {
            double fkdr = finalDeaths == 0 ? finalKills : (double) finalKills / finalDeaths;
            rows.add(new Row("FK / FD", finalKills + " / " + finalDeaths, COL_VALUE));
            rows.add(new Row("FKDR", RATIO_FORMAT.format(fkdr), ratioColor(fkdr)));
        }
        if (showWL.isToggled()) {
            double wl = losses == 0 ? wins : (double) wins / losses;
            rows.add(new Row("W / L", wins + " / " + losses, ratioColor(wl)));
        }
        return rows;
    }

    private int ratioColor(double ratio) {
        if (!colorRatios.isToggled()) return COL_VALUE;
        return ratio >= 1.0 ? COL_GOOD : COL_BAD;
    }

    /** Panel size for the current contents, as {width, height}, or null when it cannot be drawn. */
    private float[] measure() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;

        float s = (float) scale.getInput();
        float lineHeight = font.getFontHeight() * s;
        List<Row> rows = buildRows();

        float labelWidth = 0.0f;
        float valueWidth = 0.0f;
        for (Row row : rows) {
            labelWidth = Math.max(labelWidth, font.getStringWidth(row.label) * s);
            valueWidth = Math.max(valueWidth, font.getStringWidth(row.value) * s);
        }
        float headerWidth = (font.getStringWidth("SESSION") + 6 + font.getStringWidth(clock())) * s;
        float content = Math.max(headerWidth, labelWidth + COL_GAP + valueWidth);

        float height = PAD_Y * 2 + lineHeight + RULE_GAP * 2 + 1.0f
                + rows.size() * lineHeight + (rows.size() - 1) * ROW_GAP;
        return new float[] { content + PAD_X * 2, height };
    }

    /** Draws the panel and returns its bounds as {left, top, right, bottom}. */
    private float[] draw() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;
        float[] size = measure();
        if (size == null) return null;

        syncPositionToResolution();
        float w = size[0];
        float h = size[1];
        float left = posX - w;
        float top = posY;

        float s = (float) scale.getInput();
        float lineHeight = font.getFontHeight() * s;
        List<Row> rows = buildRows();

        float radius = 8.0f * mindless.module.impl.theme.ThemeManager.roundingScale();
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEnd(2, 2.4f, 0.85f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 120));
        // Same sheen as the alert cards, so the two overlays read as one family instead of a
        // shaded box next to a flat one.
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 17), new Color(255, 255, 255, 3));

        // Hairline under the header, in the profile's theme gradient. It is what separates the
        // title row from the numbers; without it every line carried equal weight and the panel
        // read as an undifferentiated list.
        int[] gradient = Theme.getGradients((int) Settings.defaultTheme.getInput());
        float ruleY = top + PAD_Y + lineHeight + RULE_GAP;
        RoundedUtils.drawGradientHorizontal(left + PAD_X, ruleY, w - PAD_X * 2, 1.0f, 0.5f,
                new Color((gradient[0] >> 16) & 0xFF, (gradient[0] >> 8) & 0xFF, gradient[0] & 0xFF, 210),
                new Color((gradient[1] >> 16) & 0xFF, (gradient[1] >> 8) & 0xFF, gradient[1] & 0xFF, 40));

        // The rounded-rect and blur shaders leave a program bound; glyph quads drawn through it
        // come out garbled.
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;

        float textLeft = left + PAD_X;
        float textRight = left + w - PAD_X;
        String clock = clock();

        font.drawString("SESSION", Math.round(textLeft * inv), Math.round((top + PAD_Y) * inv), COL_TITLE, false);
        font.drawString(clock, Math.round(textRight * inv) - font.getStringWidth(clock),
                Math.round((top + PAD_Y) * inv), COL_CLOCK, false);

        float rowY = ruleY + 1.0f + RULE_GAP;
        for (Row row : rows) {
            font.drawString(row.label, Math.round(textLeft * inv), Math.round(rowY * inv), COL_LABEL, false);
            // Values are right-aligned into a column. Left-aligning them next to the labels was
            // what made the old panel look ragged, since "4 / 2" and "12.00" are different widths.
            font.drawString(row.value, Math.round(textRight * inv) - font.getStringWidth(row.value),
                    Math.round(rowY * inv), row.color, false);
            rowY += lineHeight + ROW_GAP;
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        return new float[] { left, top, left + w, top + h };
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
