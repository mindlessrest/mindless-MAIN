package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.impl.client.HudEditor;
import mindless.module.impl.player.Freecam;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.RenderUtils;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.font.RavenFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class StatsHUD extends Module {
    private static final float DEFAULT_RELATIVE_X = 0.005f;
    private static final float DEFAULT_RELATIVE_Y = 0.015f;
    private static final float LINE_GAP = 1.0f;
    private static final int BG_COLOR = 0x55000000;
    private static final float BG_PAD_H = 3.0f;
    private static final float BG_PAD_V = 1.0f;

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
    }

    public SliderSetting scaleSetting() { return scale; }

    public float getPosX() { syncPositionToResolution(); return posX; }
    public float getPosY() { syncPositionToResolution(); return posY; }

    public float getRelativePosX() { syncPositionToResolution(); return relativePosX; }
    public float getRelativePosY() { syncPositionToResolution(); return relativePosY; }

    public void setRelativePosition(float normalizedX, float normalizedY) {
        relativePosX = normalizedX;
        relativePosY = normalizedY;
        syncPositionToResolution();
    }

    public void setAbsolutePosition(float absoluteX, float absoluteY) {
        ScaledResolution resolution = ScaledResolutionCache.get();
        posX = absoluteX;
        posY = absoluteY;
        relativePosX = absoluteX / Math.max(1, resolution.getScaledWidth());
        relativePosY = absoluteY / Math.max(1, resolution.getScaledHeight());
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

    public float[] renderPreview() { return draw(); }

    public float[] renderDesignerPreview(float absoluteLeft, float absoluteTop) {
        setAbsolutePosition(absoluteLeft, absoluteTop);
        return draw();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        draw();
    }

    private float[] draw() {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;
        if (!showFps.isToggled() && !showBps.isToggled() && !showPing.isToggled()) return null;

        syncPositionToResolution();
        float x = posX;
        float y = posY;
        float lineHeight = font.getFontHeight() + LINE_GAP;
        float maxWidth = 0;
        int lineIndex = 0;

        int themeColor = mindless.module.impl.theme.ThemeManager.getStatsLabelColor();
        int valueColor = mindless.module.impl.theme.ThemeManager.getStatsValueColor();

        if (showFps.isToggled()) {
            String label = "FPS ";
            String value = String.valueOf(Minecraft.getDebugFPS());
            float ly = y + lineHeight * lineIndex;
            float lw = font.getStringWidth(label + value);
            RenderUtils.drawRect(x - BG_PAD_H, ly - BG_PAD_V, x + lw + BG_PAD_H, ly + font.getFontHeight() + BG_PAD_V, BG_COLOR);
            font.drawString(label, x, ly, themeColor, false);
            font.drawString(value, x + font.getStringWidth(label), ly, valueColor, false);
            maxWidth = Math.max(maxWidth, lw);
            lineIndex++;
        }

        if (showBps.isToggled()) {
            double bps = Utils.gbps((Freecam.freeEntity == null) ? mc.thePlayer : Freecam.freeEntity, 1);
            String label = "BPS ";
            String value = String.format("%.1f", bps);
            float ly = y + lineHeight * lineIndex;
            float lw = font.getStringWidth(label + value);
            RenderUtils.drawRect(x - BG_PAD_H, ly - BG_PAD_V, x + lw + BG_PAD_H, ly + font.getFontHeight() + BG_PAD_V, BG_COLOR);
            font.drawString(label, x, ly, themeColor, false);
            font.drawString(value, x + font.getStringWidth(label), ly, valueColor, false);
            maxWidth = Math.max(maxWidth, lw);
            lineIndex++;
        }

        if (showPing.isToggled()) {
            String label = "PING ";
            String value = getPing() + "ms";
            float ly = y + lineHeight * lineIndex;
            float lw = font.getStringWidth(label + value);
            RenderUtils.drawRect(x - BG_PAD_H, ly - BG_PAD_V, x + lw + BG_PAD_H, ly + font.getFontHeight() + BG_PAD_V, BG_COLOR);
            font.drawString(label, x, ly, themeColor, false);
            font.drawString(value, x + font.getStringWidth(label), ly, valueColor, false);
            maxWidth = Math.max(maxWidth, lw);
            lineIndex++;
        }

        float totalHeight = lineHeight * lineIndex - LINE_GAP;
        return new float[] { x, y, x + maxWidth, y + totalHeight };
    }

    private int getPing() {
        if (mc.thePlayer == null || mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info != null ? (int) info.getResponseTime() : 0;
    }
}
