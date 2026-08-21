package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.utility.ScaledResolutionCache;
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
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.text.DecimalFormat;

public class SessionInfo extends Module {
    private static final DecimalFormat RATIO_FORMAT = new DecimalFormat("0.00");

    private final ButtonSetting showKDR;
    private final ButtonSetting showFKDR;
    private final ButtonSetting showWL;

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

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;

        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return;

        ScaledResolution sr = ScaledResolutionCache.get();
        float padding = 6.0f;
        float lineHeight = font.getFontHeight() + 2.0f;

        String timePlayed = "Time: " + formatTime(System.currentTimeMillis() - sessionStartMs);
        String killsStr = "K: " + kills + " D: " + deaths;
        String kdrStr = "KDR: " + (deaths == 0 ? kills + ".00" : RATIO_FORMAT.format((double) kills / deaths));
        String fkdrStr = "FK: " + finalKills + " FD: " + finalDeaths + " FKDR: " +
                (finalDeaths == 0 ? finalKills + ".00" : RATIO_FORMAT.format((double) finalKills / finalDeaths));
        String wlStr = "W: " + wins + " L: " + losses;

        int lines = 2;
        if (showKDR.isToggled()) lines++;
        if (showFKDR.isToggled()) lines++;
        if (showWL.isToggled()) lines++;

        float maxWidth = Math.max(font.getStringWidth(timePlayed),
                Math.max(font.getStringWidth(killsStr),
                Math.max(font.getStringWidth(kdrStr),
                Math.max(font.getStringWidth(fkdrStr), font.getStringWidth(wlStr)))));

        float w = maxWidth + padding * 2;
        float h = lines * lineHeight + padding * 2;
        float x = sr.getScaledWidth() - w - 5.0f;
        float y = sr.getScaledHeight() / 2.0f - h / 2.0f;

        float radius = 8.0f * mindless.module.impl.theme.ThemeManager.roundingScale();
        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(x, y, w, h, radius, 0xFF000000);
        BlurUtils.blurEnd(2, 2.4f, 0.85f);
        RoundedUtils.drawRound(x, y, w, h, radius, 0x55000000);

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        float textX = x + padding;
        float textY = y + padding;

        font.drawString(timePlayed, textX, textY, 0xFFCCCCCC, true);
        textY += lineHeight;
        font.drawString(killsStr, textX, textY, 0xFFFFFFFF, true);
        textY += lineHeight;

        if (showKDR.isToggled()) {
            font.drawString(kdrStr, textX, textY, 0xFF55FF55, true);
            textY += lineHeight;
        }
        if (showFKDR.isToggled()) {
            font.drawString(fkdrStr, textX, textY, 0xFFFFAA00, true);
            textY += lineHeight;
        }
        if (showWL.isToggled()) {
            font.drawString(wlStr, textX, textY, 0xFF55FFFF, true);
        }
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
