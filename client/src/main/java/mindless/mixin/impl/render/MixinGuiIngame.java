package mindless.mixin.impl.render;

import mindless.module.ModuleManager;
import mindless.module.impl.render.HUD;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.utility.HudRenderBounds;
import mindless.utility.font.MindlessFontRenderer;
import mindless.module.impl.render.ScoreboardModule;
import mindless.module.impl.render.CustomHotbar;
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
    private final List<Score> mindless$visibleScores = new ArrayList<Score>();

    @Unique
    private final List<String> mindless$visibleLines = new ArrayList<String>();

    @Shadow
    public abstract FontRenderer getFontRenderer();
@Unique
    private int mindless$width(MindlessFontRenderer custom, FontRenderer vanilla, String text) {
        return custom != null ? custom.getStringWidth(text) : vanilla.getStringWidth(text);
    }

    @Unique
    private void mindless$drawLine(MindlessFontRenderer custom, FontRenderer vanilla, String text,
                                float x, float y) {
        if (custom != null) {
            custom.drawString(text, x, y, 0xFFFFFFFF, false);
            return;
        }
        vanilla.drawString(text, x, y, 0xFFFFFFFF, false);
    }

    @Inject(method = "renderGameOverlay", at = @At("HEAD"))
    private void mindless$beginHudBlurFrame(float partialTicks, CallbackInfo callbackInfo) {
        BlurUtils.beginFrame();
    }

    @Inject(method = "renderTooltip", at = @At("HEAD"), cancellable = true)
    private void mindless$renderCustomHotbar(ScaledResolution resolution, float partialTicks,
                                              CallbackInfo callbackInfo) {
        if (CustomHotbar.renderReplacement(resolution, partialTicks)) callbackInfo.cancel();
    }

    @Inject(method = "renderExpBar", at = @At("HEAD"), cancellable = true)
    private void mindless$hideVanillaExperience(ScaledResolution resolution, int x,
                                                 CallbackInfo callbackInfo) {
        if (CustomHotbar.replacesExperienceBar()) callbackInfo.cancel();
    }

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void mindless$renderUnifiedScoreboard(ScoreObjective objective, ScaledResolution resolution,
                                               CallbackInfo callbackInfo) {
        mindless.utility.RenderUtils.beginTextPass();
        if (!mindless.module.impl.render.ScoreboardModule.isCustomScoreboardEnabled()) {
            return;
        }
        Scoreboard scoreboard = objective.getScoreboard();
        Collection<Score> sortedScores = scoreboard.getSortedScores(objective);
        mindless$visibleScores.clear();
        mindless$visibleLines.clear();
        GuiIngameState.visibleScores.clear();
        GuiIngameState.visibleLines.clear();

        for (Score score : sortedScores) {
            String playerName = score.getPlayerName();
            if (playerName != null && !playerName.startsWith("#")) {
                mindless$visibleScores.add(score);
            }
        }

        if (mindless$visibleScores.size() > 15) {
            mindless$visibleScores.subList(0, mindless$visibleScores.size() - 15).clear();
        }
        if (mindless$visibleScores.isEmpty()) {
            callbackInfo.cancel();
            return;
        }

        FontRenderer font = getFontRenderer();
        MindlessFontRenderer customFont = ScoreboardModule.getCustomFont();
        float fontScale = customFont != null ? 1.0f : SCOREBOARD_SCALE;
        float scoreboardScale = ScoreboardModule.getScale();

        String displayTitle = objective.getDisplayName();
        int contentWidth = mindless$width(customFont, font, displayTitle);
        for (Score score : mindless$visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line =
                    ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            if (ModuleManager.bedwars != null && ModuleManager.bedwars.isEnabled()) {
                line = ModuleManager.bedwars.filterScoreboardLine(line);
            }
            mindless$visibleLines.add(line);
            GuiIngameState.visibleScores.add(score);
            GuiIngameState.visibleLines.add(line);
            contentWidth = Math.max(contentWidth, mindless$width(customFont, font, line));
        }

        int lineHeight = customFont != null ? customFont.getLineHeight() : font.FONT_HEIGHT;
        float scaledLineHeight = lineHeight * fontScale;
        float rowsHeight = mindless$visibleScores.size() * scaledLineHeight;
        float basePanelWidth = contentWidth * fontScale + GuiIngameState.HORIZONTAL_PADDING * 2.0f;
        float basePanelHeight = (mindless$visibleScores.size() + 1) * scaledLineHeight
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
        BlurUtils.prepareBlur(left, top, right - left, bottom - top);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius() * scoreboardScale, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                left - 2.0f, top - 2.0f, right - left + 4.0f, bottom - top + 4.0f);
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

        float titleVisualWidth = mindless$width(customFont, font, displayTitle) * fontScale;
        int titleX = Math.round((basePanelWidth - titleVisualWidth) / (2.0f * fontScale));
        int titleY = Math.round(GuiIngameState.VERTICAL_PADDING / fontScale);
        mindless$drawLine(customFont, font, displayTitle, titleX, titleY);

        for (int i = 0; i < mindless$visibleScores.size(); i++) {
            String playerText = mindless$visibleLines.get(i);
            int y = Math.round((basePanelHeight - GuiIngameState.VERTICAL_PADDING
                    - (i + 1) * scaledLineHeight) / fontScale);
            int lineX = Math.round(GuiIngameState.HORIZONTAL_PADDING / fontScale);
            mindless$drawLine(customFont, font, playerText, lineX, y);
        }

        GlStateManager.popMatrix();

        callbackInfo.cancel();
    }
}
