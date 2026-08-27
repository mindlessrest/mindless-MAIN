package mindless.transformer.impl.render;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.module.impl.client.Settings;
import mindless.runtime.GuiIngameState;
import mindless.utility.HudRenderBounds;
import mindless.utility.RenderUtils;
import mindless.utility.TextGlowUtils;
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

    /**
     * Opens the HUD frame so the blur can be shared between panels.
     *
     * <p>This only existed on {@code GuiIngameForge} and on the mixin copy of this class. Lunar
     * has no {@code GuiIngameForge}, so on Lunar the frame counter never moved off zero, every
     * cache keyed on it treated itself as invalid, and each panel rebuilt the entire blur
     * downsample chain from scratch. That is most of a HUD's worth of full-screen shader work
     * repeated per panel, per frame.
     */
    @CInline
    @CInject(method = "renderGameOverlay", target = @CTarget("HEAD"))
    private void raven$beginHudBlurFrame(float partialTicks, InjectionCallback callbackInfo) {
        // Once a frame, before anything draws over the world: put GlStateManager's cache back in
        // agreement with the driver. The world pass is full of raw GL, ours and everyone else's,
        // and the cache is what decides whether a reset is worth forwarding -- so a colour or a
        // blend mode left behind out there is not merely still set, it is invisible to the calls
        // meant to clear it, and the whole HUD inherits it.
        RenderUtils.syncGlStateFromDriver();
        HudRenderBounds.clearScoreboard();
        BlurUtils.beginFrame();
    }

    @CInline
    @CInject(method = "renderScoreboard", target = @CTarget("HEAD"), cancellable = true)
    private void raven$renderUnifiedScoreboard(ScoreObjective objective, ScaledResolution resolution,
                                               InjectionCallback callbackInfo) {
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
        String displayTitle = mindless.module.impl.render.ScoreboardModule.applyTextSwaps(objective.getDisplayName());
        int contentWidth = font.getStringWidth(displayTitle);
        for (Score score : GuiIngameState.visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line = ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            line = mindless.module.impl.render.ScoreboardModule.applyTextSwaps(line);
            GuiIngameState.visibleLines.add(line);
            contentWidth = Math.max(contentWidth, font.getStringWidth(line));
        }

        int lineHeight = font.FONT_HEIGHT;
        float scaledLineHeight = lineHeight * GuiIngameState.SCOREBOARD_SCALE;
        float rowsHeight = GuiIngameState.visibleScores.size() * scaledLineHeight;
        float panelWidth = contentWidth * GuiIngameState.SCOREBOARD_SCALE
                + GuiIngameState.HORIZONTAL_PADDING * 2.0f;
        float panelHeight = (GuiIngameState.visibleScores.size() + 1) * scaledLineHeight
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
                GuiIngameState.panelRadius(), 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                left - 2, top - 2, right - left + 4, bottom - top + 4);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius(), GuiIngameState.PANEL_FILL_COLOR);

        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        GlStateManager.alphaFunc(GL11.GL_GREATER, 0.1F);
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.pushMatrix();
        GlStateManager.scale(GuiIngameState.SCOREBOARD_SCALE, GuiIngameState.SCOREBOARD_SCALE, 1.0f);

        float titleVisualWidth = font.getStringWidth(displayTitle) * GuiIngameState.SCOREBOARD_SCALE;
        int titleX = Math.round((left + (right - left - titleVisualWidth) / 2.0f) / GuiIngameState.SCOREBOARD_SCALE);
        int titleY = Math.round((top + GuiIngameState.VERTICAL_PADDING) / GuiIngameState.SCOREBOARD_SCALE);
        if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
            TextGlowUtils.drawGlow(font, displayTitle, titleX, titleY, 0xFFFFFFFF);
        }
        font.drawString(displayTitle, titleX, titleY, 0xFFFFFFFF);

        for (int i = 0; i < GuiIngameState.visibleScores.size(); i++) {
            String playerText = GuiIngameState.visibleLines.get(i);
            int y = Math.round((bottom - GuiIngameState.VERTICAL_PADDING
                    - (i + 1) * scaledLineHeight) / GuiIngameState.SCOREBOARD_SCALE);
            int lineX = Math.round((left + GuiIngameState.HORIZONTAL_PADDING)
                    / GuiIngameState.SCOREBOARD_SCALE);
            if (Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled()) {
                TextGlowUtils.drawGlow(font, playerText, lineX, y, 0xFFFFFFFF);
            }
            font.drawString(playerText, lineX, y, 0xFFFFFFFF);
        }

        GlStateManager.popMatrix();

        callbackInfo.setCancelled(true);
    }
}
