package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.player.Freecam;
import mindless.module.impl.theme.ThemeManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.FontManager;
import mindless.utility.font.RavenFontRenderer;
import mindless.utility.shader.BlurUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL20;

import java.awt.Color;

public class StatsHUD extends Module {
    private static final float DEFAULT_RELATIVE_X = 0.005f;
    private static final float DEFAULT_RELATIVE_Y = 0.015f;

    private static final float PAD_X = 10.0f;
    private static final float PAD_Y = 7.0f;
    private static final float CELL_GAP = 16.0f;

    private static final int COL_VALUE = new Color(255, 255, 255).getRGB();

    private final SliderSetting scale;
    private final ButtonSetting showFps;
    private final ButtonSetting showBps;
    private final ButtonSetting showPing;

    private float posX = Float.NaN;
    private float posY = Float.NaN;
    private float relativePosX = Float.NaN;
    private float relativePosY = Float.NaN;

    public StatsHUD() {
        super("HUD", category.render);
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.6, 1.6, 0.05));
        this.registerSetting(showFps = new ButtonSetting("Show FPS", true));
        this.registerSetting(showBps = new ButtonSetting("Show BPS", true));
        this.registerSetting(showPing = new ButtonSetting("Show Ping", true));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new HudEditor.Screen())));
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
            } else {
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

    public float[] renderPreview() {
        return draw();
    }

    public float[] renderDesignerPreview(float absoluteLeft, float absoluteTop) {
        ScaledResolution resolution = ScaledResolutionCache.get();
        setAbsolutePosition(absoluteLeft, absoluteTop, resolution);
        return draw();
    }

    // ------------------------------------------------------------------ rendering

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw();
    }

    private float[] measure() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;

        float s = (float) scale.getInput();
        int cellCount = 0;
        float contentWidth = 0;
        if (showFps.isToggled()) { cellCount++; contentWidth = Math.max(contentWidth, font.getStringWidth("999 FPS")); }
        if (showBps.isToggled()) { cellCount++; contentWidth = Math.max(contentWidth, font.getStringWidth("99.9 BPS")); }
        if (showPing.isToggled()) { cellCount++; contentWidth = Math.max(contentWidth, font.getStringWidth("999 MS")); }
        if (cellCount == 0) return null;

        float totalWidth = contentWidth * cellCount + CELL_GAP * (cellCount - 1) + PAD_X * 2.0f;
        float height = PAD_Y * 2.0f + font.getFontHeight();
        return new float[] { totalWidth * s, height * s };
    }

    private float[] draw() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;
        float[] size = measure();
        if (size == null) return null;

        syncPositionToResolution();
        float s = (float) scale.getInput();
        float w = size[0];
        float h = size[1];
        float left = posX;
        float top = posY;

        float radius = 9.0f * ThemeManager.roundingScale();

        RoundedUtils.drawRoundShadow(left, top, w, h, radius, 5.0f,
                new Color(0, 0, 0, 130).getRGB());

        BlurUtils.prepareBlur(left, top, w, h);
        RoundedUtils.drawRound(left, top, w, h, radius, 0xFF000000);
        BlurUtils.blurEndRegion(2, 2.4f, 0.85f, left - 2.0f, top - 2.0f, w + 4.0f, h + 4.0f);
        RoundedUtils.drawRound(left, top, w, h, radius, new Color(0, 0, 0, 130));
        RoundedUtils.drawGradientVertical(left, top, w, h, radius,
                new Color(255, 255, 255, 20), new Color(255, 255, 255, 4));
        RoundedUtils.drawRoundOutline(left, top, w, h, radius, 1.0f,
                new Color(0, 0, 0, 0), new Color(255, 255, 255, 30));

        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1.0f);
        float inv = 1.0f / s;

        float textLeft = left * inv + PAD_X;
        float textY = top * inv + PAD_Y;

        float cellWidth = ((w * inv) - PAD_X * 2.0f + CELL_GAP) / cellCount();
        int cellIndex = 0;
        int themeColor = HUD.getHudColor(0);

        if (showFps.isToggled()) {
            int fps = Minecraft.getDebugFPS();
            String value = String.valueOf(fps);
            String label = " FPS";
            float cx = textLeft + cellWidth * cellIndex;
            font.drawString(value, Math.round(cx), Math.round(textY), COL_VALUE, false);
            font.drawString(label, Math.round(cx) + font.getStringWidth(value), Math.round(textY), themeColor, false);
            cellIndex++;
        }

        if (showBps.isToggled()) {
            double bps = Utils.gbps((Freecam.freeEntity == null) ? mc.thePlayer : Freecam.freeEntity, 1);
            String value = String.format("%.1f", bps);
            String label = " BPS";
            float cx = textLeft + cellWidth * cellIndex;
            font.drawString(value, Math.round(cx), Math.round(textY), COL_VALUE, false);
            font.drawString(label, Math.round(cx) + font.getStringWidth(value), Math.round(textY), themeColor, false);
            cellIndex++;
        }

        if (showPing.isToggled()) {
            int ping = getPing();
            String value = String.valueOf(ping);
            String label = " MS";
            float cx = textLeft + cellWidth * cellIndex;
            font.drawString(value, Math.round(cx), Math.round(textY), COL_VALUE, false);
            font.drawString(label, Math.round(cx) + font.getStringWidth(value), Math.round(textY), themeColor, false);
        }

        GlStateManager.popMatrix();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        return new float[] { left, top, left + w, top + h };
    }

    private int cellCount() {
        int count = 0;
        if (showFps.isToggled()) count++;
        if (showBps.isToggled()) count++;
        if (showPing.isToggled()) count++;
        return count;
    }

    private int getPing() {
        if (mc.thePlayer == null || mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info != null ? (int) info.getResponseTime() : 0;
    }
}
