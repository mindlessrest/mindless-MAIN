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
    private static final int BG_COLOR = 0x55000000;
    private static final float BG_PAD_H = 3.0f;
    private static final float BG_PAD_V = 1.0f;

    private final SliderSetting scale;
    private final ButtonSetting showFps;
    private final ButtonSetting showBps;
    private final ButtonSetting showPing;

    private final StatPanel fpsPanel = new StatPanel(0.005f, 0.015f);
    private final StatPanel bpsPanel = new StatPanel(0.005f, 0.040f);
    private final StatPanel pingPanel = new StatPanel(0.005f, 0.065f);

    public StatsHUD() {
        super("HUD", category.render);
        this.registerSetting(scale = new SliderSetting("Scale", 1.0, 0.6, 1.6, 0.05));
        this.registerSetting(showFps = new ButtonSetting("Show FPS", true));
        this.registerSetting(showBps = new ButtonSetting("Show BPS", true));
        this.registerSetting(showPing = new ButtonSetting("Show Ping", true));
    }

    public SliderSetting scaleSetting() { return scale; }

    public StatPanel getFpsPanel() { return fpsPanel; }
    public StatPanel getBpsPanel() { return bpsPanel; }
    public StatPanel getPingPanel() { return pingPanel; }

    public boolean isFpsEnabled() { return showFps.isToggled(); }
    public boolean isBpsEnabled() { return showBps.isToggled(); }
    public boolean isPingEnabled() { return showPing.isToggled(); }

    public void resetPosition() {
        fpsPanel.resetPosition();
        bpsPanel.resetPosition();
        pingPanel.resetPosition();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !Utils.nullCheck()) return;
        if (mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        if (showFps.isToggled()) drawStat(fpsPanel, "FPS ", String.valueOf(Minecraft.getDebugFPS()));
        if (showBps.isToggled()) {
            double bps = Utils.gbps((Freecam.freeEntity == null) ? mc.thePlayer : Freecam.freeEntity, 1);
            drawStat(bpsPanel, "BPS ", String.format("%.1f", bps));
        }
        if (showPing.isToggled()) drawStat(pingPanel, "PING ", getPing() + "ms");
    }

    public float[] renderFpsPreview() {
        if (!showFps.isToggled()) return null;
        return drawStat(fpsPanel, "FPS ", String.valueOf(Minecraft.getDebugFPS()));
    }

    public float[] renderBpsPreview() {
        if (!showBps.isToggled()) return null;
        double bps = Utils.gbps((Freecam.freeEntity == null) ? mc.thePlayer : Freecam.freeEntity, 1);
        return drawStat(bpsPanel, "BPS ", String.format("%.1f", bps));
    }

    public float[] renderPingPreview() {
        if (!showPing.isToggled()) return null;
        return drawStat(pingPanel, "PING ", getPing() + "ms");
    }

    public float[] renderFpsAt(float left, float top) {
        fpsPanel.setAbsolutePosition(left, top);
        return renderFpsPreview();
    }

    public float[] renderBpsAt(float left, float top) {
        bpsPanel.setAbsolutePosition(left, top);
        return renderBpsPreview();
    }

    public float[] renderPingAt(float left, float top) {
        pingPanel.setAbsolutePosition(left, top);
        return renderPingPreview();
    }

    private float[] drawStat(StatPanel panel, String label, String value) {
        RavenFontRenderer font = HUD.getHudFontRenderer();
        if (font == null) return null;

        panel.syncPositionToResolution();
        float x = panel.posX;
        float y = panel.posY;

        int themeColor = mindless.module.impl.theme.ThemeManager.getStatsLabelColor();
        int valueColor = mindless.module.impl.theme.ThemeManager.getStatsValueColor();

        float lw = font.getStringWidth(label + value);
        RenderUtils.drawRect(x - BG_PAD_H, y - BG_PAD_V, x + lw + BG_PAD_H, y + font.getFontHeight() + BG_PAD_V, BG_COLOR);
        font.drawString(label, x, y, themeColor, false);
        font.drawString(value, x + font.getStringWidth(label), y, valueColor, false);

        return new float[] { x - BG_PAD_H, y - BG_PAD_V, x + lw + BG_PAD_H, y + font.getFontHeight() + BG_PAD_V };
    }

    private int getPing() {
        if (mc.thePlayer == null || mc.getNetHandler() == null) return 0;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        return info != null ? (int) info.getResponseTime() : 0;
    }

    public static final class StatPanel {
        private final float defaultRelX;
        private final float defaultRelY;
        float posX = Float.NaN;
        float posY = Float.NaN;
        private float relativePosX = Float.NaN;
        private float relativePosY = Float.NaN;

        StatPanel(float defaultRelX, float defaultRelY) {
            this.defaultRelX = defaultRelX;
            this.defaultRelY = defaultRelY;
        }

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
            setRelativePosition(defaultRelX, defaultRelY);
        }

        void syncPositionToResolution() {
            ScaledResolution resolution = ScaledResolutionCache.get();
            int scaledWidth = Math.max(1, resolution.getScaledWidth());
            int scaledHeight = Math.max(1, resolution.getScaledHeight());

            if (Float.isNaN(relativePosX) || Float.isNaN(relativePosY)) {
                if (Float.isNaN(posX) || Float.isNaN(posY)) {
                    relativePosX = defaultRelX;
                    relativePosY = defaultRelY;
                } else {
                    relativePosX = posX / scaledWidth;
                    relativePosY = posY / scaledHeight;
                }
            }
            posX = relativePosX * scaledWidth;
            posY = relativePosY * scaledHeight;
        }
    }
}
