package mindless.transformer.impl.render;

import mindless.module.impl.render.HUD;
import mindless.module.impl.render.CustomHotbar;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.runtime.GuiIngameState;
import mindless.runtime.HudTextRenderer;
import mindless.utility.font.MindlessFontRenderer;
import mindless.utility.HudRenderBounds;
import mindless.utility.RenderUtils;
import net.lenni0451.classtransform.InjectionCallback;
import net.lenni0451.classtransform.annotations.CInline;
import net.lenni0451.classtransform.annotations.CShadow;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CInject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.util.Collection;
import java.util.List;

@CTransformer(GuiIngame.class)
public abstract class TransformerGuiIngame {

    @CShadow
    public abstract FontRenderer getFontRenderer();
@CInline
    @CInject(method = "renderGameOverlay", target = @CTarget("HEAD"))
    private void mindless$beginHudBlurFrame(float partialTicks, InjectionCallback callbackInfo) {
        RenderUtils.syncGlStateFromDriver();
        HudRenderBounds.clearScoreboard();
        BlurUtils.beginFrame();
    }

    @CInline
    @CInject(method = "renderTooltip", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$renderCustomHotbar(ScaledResolution resolution, float partialTicks,
                                              InjectionCallback callbackInfo) {
        if (CustomHotbar.renderReplacement(resolution, partialTicks)) {
            callbackInfo.setCancelled(true);
        }
    }

    @CInline
    @CInject(method = "renderExpBar", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$hideVanillaExperience(ScaledResolution resolution, int x,
                                                 InjectionCallback callbackInfo) {
        if (CustomHotbar.replacesExperienceBar()) callbackInfo.setCancelled(true);
    }

    @CInline
    @CInject(method = "renderScoreboard", target = @CTarget("HEAD"), cancellable = true)
    private void mindless$renderUnifiedScoreboard(ScoreObjective objective, ScaledResolution resolution,
                                               InjectionCallback callbackInfo) {
        mindless.utility.RenderUtils.beginTextPass();
        if (!mindless.module.impl.render.ScoreboardModule.isCustomScoreboardEnabled()) {
            return;
        }
        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> sortedScores = scoreboard.getSortedScores(objective);
        GuiIngameState.visibleScores.clear();
        GuiIngameState.visibleLines.clear();

        for (Score score : sortedScores) {
            String playerName = score.getPlayerName();
            if (playerName != null && !playerName.startsWith("#")) {
                GuiIngameState.visibleScores.add(score);
            }
        }

        if (GuiIngameState.visibleScores.size() > 15) {
            GuiIngameState.visibleScores.subList(0, GuiIngameState.visibleScores.size() - 15).clear();
        }
        if (GuiIngameState.visibleScores.isEmpty()) {
            callbackInfo.setCancelled(true);
            return;
        }

        FontRenderer font = getFontRenderer();
        MindlessFontRenderer customFont = mindless.module.impl.render.ScoreboardModule.getCustomFont();
        float fontScale = customFont != null ? 1.0f : GuiIngameState.SCOREBOARD_SCALE;
        float scoreboardScale = mindless.module.impl.render.ScoreboardModule.getScale();
        String displayTitle = objective.getDisplayName();
        int contentWidth = HudTextRenderer.width(customFont, font, displayTitle);
        for (Score score : GuiIngameState.visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (mindless.module.ModuleManager.bedwars != null
                    && mindless.module.ModuleManager.bedwars.isEnabled()) {
                line = mindless.module.ModuleManager.bedwars.filterScoreboardLine(line);
            }

            GuiIngameState.visibleLines.add(line);
            contentWidth = Math.max(contentWidth, HudTextRenderer.width(customFont, font, line));
        }

        int lineHeight = HudTextRenderer.lineHeight(customFont, font);
        float scaledLineHeight = lineHeight * fontScale;
        float rowsHeight = GuiIngameState.visibleScores.size() * scaledLineHeight;
        float basePanelWidth = contentWidth * fontScale
                + GuiIngameState.HORIZONTAL_PADDING * 2.0f;
        float basePanelHeight = (GuiIngameState.visibleScores.size() + 1) * scaledLineHeight
                + GuiIngameState.VERTICAL_PADDING * 2.0f + 2.0f;
        float panelWidth = basePanelWidth * scoreboardScale;
        float panelHeight = basePanelHeight * scoreboardScale;
        float defaultBottom = resolution.getScaledHeight() / 2.0f
                + rowsHeight * scoreboardScale / 3.0f + 5.0f;
        float defaultLeft = resolution.getScaledWidth() - 3.0f - panelWidth;
        float defaultTop = defaultBottom - panelHeight;
        float left = HUD.getScoreboardX(panelWidth, resolution, defaultLeft);
        float top = HUD.getScoreboardY(panelHeight, resolution, defaultTop);
        float right = left + panelWidth;
        float bottom = top + panelHeight;
        HudRenderBounds.setScoreboard(left, top, right, bottom);

        GL20.glUseProgram(0);
        GlStateManager.setActiveTexture(GL13.GL_TEXTURE0);
        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
        GL11.glColorMask(true, true, true, true);

        BlurUtils.prepareBlur();
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius() * scoreboardScale, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                left - 2, top - 2, right - left + 4, bottom - top + 4);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius() * scoreboardScale, GuiIngameState.PANEL_FILL_COLOR);

        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.translate(left, top, 0.0f);
        GlStateManager.scale(scoreboardScale * fontScale, scoreboardScale * fontScale, 1.0f);

        float titleVisualWidth = HudTextRenderer.width(customFont, font, displayTitle) * fontScale;
        int titleX = Math.round((basePanelWidth - titleVisualWidth) / (2.0f * fontScale));
        int titleY = Math.round(GuiIngameState.VERTICAL_PADDING / fontScale);
        HudTextRenderer.draw(customFont, font, displayTitle, titleX, titleY, 0xFFFFFFFF, false);

        for (int i = 0; i < GuiIngameState.visibleScores.size(); i++) {
            String playerText = GuiIngameState.visibleLines.get(i);
            int y = Math.round((basePanelHeight - GuiIngameState.VERTICAL_PADDING
                    - (i + 1) * scaledLineHeight) / fontScale);
            int lineX = Math.round(GuiIngameState.HORIZONTAL_PADDING / fontScale);
            HudTextRenderer.draw(customFont, font, playerText, lineX, y, 0xFFFFFFFF, false);
        }

        GlStateManager.popMatrix();

        callbackInfo.setCancelled(true);
    }
}
