package mindless.mixin.impl.render;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.module.impl.client.Settings;
import mindless.utility.HudRenderBounds;
import mindless.utility.TextGlowUtils;
import mindless.runtime.GuiIngameState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@SideOnly(Side.CLIENT)
@Mixin(GuiIngame.class)
public abstract class MixinGuiIngame {
    private static final float SCOREBOARD_SCALE = GuiIngameState.SCOREBOARD_SCALE;

    @Unique
    private final List<Score> raven$visibleScores = new ArrayList<Score>();

    @Unique
    private final List<String> raven$visibleLines = new ArrayList<String>();

    @Shadow
    public abstract FontRenderer getFontRenderer();

    @Inject(method = "renderGameOverlay", at = @At("HEAD"))
    private void raven$beginHudBlurFrame(float partialTicks, CallbackInfo callbackInfo) {
        BlurUtils.beginFrame();
    }

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void raven$renderUnifiedScoreboard(ScoreObjective objective, ScaledResolution resolution,
                                               CallbackInfo callbackInfo) {
        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> sortedScores = scoreboard.getSortedScores(objective);
        raven$visibleScores.clear();
        raven$visibleLines.clear();

        for (Score score : sortedScores) {
            String playerName = score.getPlayerName();
            if (playerName != null && !playerName.startsWith("#")) {
                raven$visibleScores.add(score);
            }
        }

        if (raven$visibleScores.size() > 15) {
            raven$visibleScores.subList(0, raven$visibleScores.size() - 15).clear();
        }
        if (raven$visibleScores.isEmpty()) {
            callbackInfo.cancel();
            return;
        }

        FontRenderer font = getFontRenderer();
        int contentWidth = font.getStringWidth(objective.getDisplayName());
        for (Score score : raven$visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            raven$visibleLines.add(line);
            contentWidth = Math.max(contentWidth, font.getStringWidth(line));
        }

        int lineHeight = font.FONT_HEIGHT;
        float scaledLineHeight = lineHeight * SCOREBOARD_SCALE;
        float rowsHeight = raven$visibleScores.size() * scaledLineHeight;
        float panelWidth = contentWidth * SCOREBOARD_SCALE + GuiIngameState.HORIZONTAL_PADDING * 2.0f;
        float panelHeight = (raven$visibleScores.size() + 1) * scaledLineHeight
                + GuiIngameState.VERTICAL_PADDING * 2.0f + 2.0f;
        float defaultBottom = resolution.getScaledHeight() / 2.0f + rowsHeight / 3.0f + 5.0f;
        float defaultLeft = resolution.getScaledWidth() - 3.0f - panelWidth;
        float defaultTop = defaultBottom - panelHeight;
        float left = Settings.getScoreboardX(panelWidth, resolution, defaultLeft);
        float top = Settings.getScoreboardY(panelHeight, resolution, defaultTop);
        float right = left + panelWidth;
        float bottom = top + panelHeight;
        HudRenderBounds.setScoreboard(left, top, right, bottom);

        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
        GL11.glColorMask(true, true, true, true);

        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.PANEL_RADIUS, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                left - 2.0f, top - 2.0f, right - left + 4.0f, bottom - top + 4.0f);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.PANEL_RADIUS, GuiIngameState.PANEL_FILL_COLOR);

        // The scoreboard is rendered after several optional HUD modules. Give
        // its text a clean baseline so a preceding gradient/shader cannot tint
        // the title or team-formatted lines.
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.scale(SCOREBOARD_SCALE, SCOREBOARD_SCALE, 1.0f);

        float titleVisualWidth = font.getStringWidth(objective.getDisplayName()) * SCOREBOARD_SCALE;
        int titleX = Math.round((left + (right - left - titleVisualWidth) / 2.0f) / SCOREBOARD_SCALE);
        int titleY = Math.round((top + GuiIngameState.VERTICAL_PADDING) / SCOREBOARD_SCALE);
        if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
            TextGlowUtils.drawGlow(font, objective.getDisplayName(), titleX, titleY, 0xFFFFFFFF);
        }
        font.drawString(objective.getDisplayName(), titleX, titleY, 0xFFFFFFFF);

        for (int i = 0; i < raven$visibleScores.size(); i++) {
            String playerText = raven$visibleLines.get(i);
            int y = Math.round((bottom - GuiIngameState.VERTICAL_PADDING
                    - (i + 1) * scaledLineHeight) / SCOREBOARD_SCALE);
            int lineX = Math.round((left + GuiIngameState.HORIZONTAL_PADDING) / SCOREBOARD_SCALE);
            if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
                TextGlowUtils.drawGlow(font, playerText, lineX, y, 0xFFFFFFFF);
            }
            font.drawString(playerText, lineX, y, 0xFFFFFFFF);
        }

        GlStateManager.popMatrix();

        callbackInfo.cancel();
    }
}
