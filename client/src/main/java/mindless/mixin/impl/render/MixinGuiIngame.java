package mindless.mixin.impl.render;

import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import mindless.module.impl.client.Settings;
import mindless.utility.HudRenderBounds;
import mindless.utility.TextGlowUtils;
import mindless.utility.font.MindlessFontRenderer;
import mindless.module.impl.render.ScoreboardModule;
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

    /**
     * Measures through whichever face is actually going to draw the line.
     *
     * <p>The panel sizes itself from these numbers, so taking them from the Minecraft font while
     * drawing in another one is how a scoreboard ends up with its longest line hanging over the
     * edge of its own background.
     */
    @Unique
    private int mindless$width(MindlessFontRenderer custom, FontRenderer vanilla, String text) {
        return custom != null ? custom.getStringWidth(text) : vanilla.getStringWidth(text);
    }

    @Unique
    private void mindless$drawLine(MindlessFontRenderer custom, FontRenderer vanilla, String text,
                                float x, float y, boolean glow) {
        if (custom != null) {
            if (glow) {
                TextGlowUtils.drawGlow(custom, text, x, y, 0xFFFFFFFF);
            }
            custom.drawString(text, x, y, 0xFFFFFFFF, false);
            return;
        }

        if (glow) {
            TextGlowUtils.drawGlow(vanilla, text, x, y, 0xFFFFFFFF);
        }
        // The float overload with no drop shadow, which is what the int one resolves to anyway.
        vanilla.drawString(text, x, y, 0xFFFFFFFF, false);
    }

    @Inject(method = "renderGameOverlay", at = @At("HEAD"))
    private void mindless$beginHudBlurFrame(float partialTicks, CallbackInfo callbackInfo) {
        BlurUtils.beginFrame();
    }

    @Inject(method = "renderScoreboard", at = @At("HEAD"), cancellable = true)
    private void mindless$renderUnifiedScoreboard(ScoreObjective objective, ScaledResolution resolution,
                                               CallbackInfo callbackInfo) {
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
        // A bundled face brings its own size, so the panel's fixed shrink is not applied on top of
        // it -- the module's own scale setting is the one control over how large it comes out.
        MindlessFontRenderer customFont = ScoreboardModule.getCustomFont();
        float fontScale = customFont != null ? 1.0f : SCOREBOARD_SCALE;

        String displayTitle = objective.getDisplayName();
        int contentWidth = mindless$width(customFont, font, displayTitle);
        for (Score score : mindless$visibleScores) {
            ScorePlayerTeam team = scoreboard.getPlayersTeam(score.getPlayerName());
            String line =
                    ScorePlayerTeam.formatPlayerName(team, score.getPlayerName());
            mindless$visibleLines.add(line);
            GuiIngameState.visibleScores.add(score);
            GuiIngameState.visibleLines.add(line);
            contentWidth = Math.max(contentWidth, mindless$width(customFont, font, line));
        }

        int lineHeight = customFont != null ? customFont.getLineHeight() : font.FONT_HEIGHT;
        float scaledLineHeight = lineHeight * fontScale;
        float rowsHeight = mindless$visibleScores.size() * scaledLineHeight;
        float panelWidth = contentWidth * fontScale + GuiIngameState.HORIZONTAL_PADDING * 2.0f;
        float panelHeight = (mindless$visibleScores.size() + 1) * scaledLineHeight
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

        // Region form: the mask is one screen-sized buffer shared by every panel, and the
        // scoreboard only ever writes its own rectangle into it. Wiping all of it here was a
        // full-screen clear every frame for the sake of a few hundred pixels.
        BlurUtils.prepareBlur(left, top, right - left, bottom - top);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius(), 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, GuiIngameState.PANEL_BLUR_OPACITY,
                left - 2.0f, top - 2.0f, right - left + 4.0f, bottom - top + 4.0f);
        RoundedUtils.drawRound(left, top, right - left, bottom - top,
                GuiIngameState.panelRadius(), GuiIngameState.PANEL_FILL_COLOR);

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
        GlStateManager.scale(fontScale, fontScale, 1.0f);

        boolean glow = Settings.scoreboardGlow != null && Settings.scoreboardGlow.isToggled();
        float titleVisualWidth = mindless$width(customFont, font, displayTitle) * fontScale;
        int titleX = Math.round((left + (right - left - titleVisualWidth) / 2.0f) / fontScale);
        int titleY = Math.round((top + GuiIngameState.VERTICAL_PADDING) / fontScale);
        mindless$drawLine(customFont, font, displayTitle, titleX, titleY, glow);

        for (int i = 0; i < mindless$visibleScores.size(); i++) {
            String playerText = mindless$visibleLines.get(i);
            int y = Math.round((bottom - GuiIngameState.VERTICAL_PADDING
                    - (i + 1) * scaledLineHeight) / fontScale);
            int lineX = Math.round((left + GuiIngameState.HORIZONTAL_PADDING) / fontScale);
            mindless$drawLine(customFont, font, playerText, lineX, y, glow);
        }

        GlStateManager.popMatrix();

        callbackInfo.cancel();
    }
}
