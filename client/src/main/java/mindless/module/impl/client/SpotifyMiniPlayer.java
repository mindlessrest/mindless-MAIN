package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.media.SystemMediaClient;
import mindless.utility.media.SpotifyMiniPlayerRenderer;
import mindless.utility.Utils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.Color;

public class SpotifyMiniPlayer extends Module {
    private static final double UNSET_CUSTOM_POSITION = -1.0D;

    public static ButtonSetting showAlbumArt;
    public static ButtonSetting showProgressBar;
    public static SliderSetting progressBarColorMode;
    public static ButtonSetting showHeader;
    public static ButtonSetting showDetails;
    public static ButtonSetting showSourceApp;
    public static ButtonSetting showStatusBadge;
    public static ButtonSetting showLyrics;
    public static ButtonSetting fullLyricsView;
    public static ButtonSetting karaokeLyrics;
    public static ButtonSetting animateLyrics;
    public static ButtonSetting showIdleCard;
    public static ButtonSetting hideWhenPaused;
    public static ButtonSetting dynamicIslandStyle;
    public static ButtonSetting noBackground;
    public static SliderSetting customPosX;
    public static SliderSetting customPosY;
    public static SliderSetting scale;
    public static SliderSetting lyricTextScale;
    public static SliderSetting lyricAnimationSpeed;
    public static SliderSetting lyricSyncOffset;

    public SpotifyMiniPlayer() {
        super("Spotify Info", category.client);
        this.registerSetting(showAlbumArt = new ButtonSetting("Show album art", true));
        this.registerSetting(showProgressBar = new ButtonSetting("Show progress bar", true));
        this.registerSetting(progressBarColorMode = new SliderSetting("Progress bar colors", 0, new String[]{"HUD gradient", "Album accent"}));
        this.registerSetting(showHeader = new ButtonSetting("Show header", true));
        this.registerSetting(showDetails = new ButtonSetting("Show details", true));
        this.registerSetting(showSourceApp = new ButtonSetting("Show source app", true));
        this.registerSetting(showStatusBadge = new ButtonSetting("Show status badge", true));
        this.registerSetting(new DescriptionSetting("Lyrics"));
        this.registerSetting(showLyrics = new ButtonSetting("Show synced lyrics", true));
        this.registerSetting(fullLyricsView = new ButtonSetting("Full lyrics view", false));
        this.registerSetting(karaokeLyrics = new ButtonSetting("Karaoke highlight", false));
        this.registerSetting(animateLyrics = new ButtonSetting("Animate lyrics", true));
        this.registerSetting(lyricTextScale = new SliderSetting("Lyrics size", "x", 1.0, 0.85, 1.4, 0.05));
        this.registerSetting(lyricAnimationSpeed = new SliderSetting("Lyrics animation", "ms", 260, 80, 600, 20));
        this.registerSetting(lyricSyncOffset = new SliderSetting("Lyrics sync", "ms", 350, -1500, 1500, 50));
        this.registerSetting(showIdleCard = new ButtonSetting("Show when idle", true));
        this.registerSetting(hideWhenPaused = new ButtonSetting("Hide when paused", false));
        this.registerSetting(dynamicIslandStyle = new ButtonSetting("Accent tint", true));
        this.registerSetting(noBackground = new ButtonSetting("No background", false));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
        this.registerSetting(scale = new SliderSetting("UI scale", "x", 0.5, 0.4, 1.75, 0.05));
        this.registerSetting(customPosX = new SliderSetting("Custom Position X", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(customPosY = new SliderSetting("Custom Position Y", 0.0, -1.0, 1.0, 0.001));
        customPosX.visible = false;
        customPosY.visible = false;
        customPosX.setValueRaw(UNSET_CUSTOM_POSITION);
        customPosY.setValueRaw(UNSET_CUSTOM_POSITION);
        this.setEnabled(false);
    }

    @Override
    public void onEnable() {
        SystemMediaClient.getInstance().setEnabled(true);
    }

    @Override
    public void onDisable() {
        SystemMediaClient.getInstance().setEnabled(false);
    }

    // Render via RenderTickEvent so the player shows reliably on Lunar.
    // The GuiIngameForge transformer hook is kept as a secondary path but
    // Lunar may call a subclass that does not invoke the Forge super method.
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.currentScreen != null) return;
        if (!Utils.nullCheck()) return;
        SpotifyMiniPlayerRenderer.render();
    }

    public void openEditScreen() {
        mc.displayGuiScreen(new EditScreen());
    }

    @Override
    public void guiUpdate() {
        boolean lyricsVisible = showLyrics != null && showLyrics.isToggled();
        if (fullLyricsView != null) {
            fullLyricsView.setVisible(lyricsVisible, this);
        }
        if (karaokeLyrics != null) {
            karaokeLyrics.setVisible(lyricsVisible, this);
        }
        if (animateLyrics != null) {
            animateLyrics.setVisible(lyricsVisible, this);
        }
        if (lyricTextScale != null) {
            lyricTextScale.setVisible(lyricsVisible, this);
        }
        if (lyricSyncOffset != null) {
            lyricSyncOffset.setVisible(lyricsVisible, this);
        }
        boolean animateVisible = lyricsVisible && animateLyrics != null && animateLyrics.isToggled();
        if (lyricAnimationSpeed != null) {
            lyricAnimationSpeed.setVisible(animateVisible, this);
        }
    }

    public static boolean hasCustomPosition() {
        return customPosX != null && customPosY != null && customPosX.getInput() >= 0.0D && customPosY.getInput() >= 0.0D;
    }

    public static float getCustomNormalizedX() {
        return customPosX == null ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, customPosX.getInput()));
    }

    public static float getCustomNormalizedY() {
        return customPosY == null ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, customPosY.getInput()));
    }

    public static void clearCustomPosition() {
        if (customPosX != null) {
            customPosX.setValueRaw(UNSET_CUSTOM_POSITION);
        }
        if (customPosY != null) {
            customPosY.setValueRaw(UNSET_CUSTOM_POSITION);
        }
    }

    public static void setCustomPositionFromAbsolute(float absoluteX, float absoluteY, float width, float height, ScaledResolution resolution) {
        if (customPosX == null || customPosY == null || resolution == null) {
            return;
        }

        float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
        float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
        float clampedX = Math.max(0.0F, Math.min(maxX, absoluteX));
        float clampedY = Math.max(0.0F, Math.min(maxY, absoluteY));
        customPosX.setValueRaw(maxX <= 0.0F ? 0.0D : clampedX / maxX);
        customPosY.setValueRaw(maxY <= 0.0F ? 0.0D : clampedY / maxY);
    }

    public static class EditScreen extends GuiScreen {
        private GuiButtonExt resetPosition;
        private boolean dragging;
        private float dragOffsetX;
        private float dragOffsetY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            // Force a preview render so panelVisible/bounds are populated immediately
            // even though onRenderTick skips rendering while a GUI screen is open.
            float[] bounds = SpotifyMiniPlayerRenderer.renderPreview();
            if (bounds != null && !SpotifyMiniPlayer.hasCustomPosition()) {
                ScaledResolution sr = new ScaledResolution(this.mc);
                float w = bounds[2] - bounds[0], h = bounds[3] - bounds[1];
                SpotifyMiniPlayer.setCustomPositionFromAbsolute(bounds[0], bounds[1], w, h, sr);
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, this.width, this.height, 0x7A000000);

            // renderPreview() drives the renderer so panelX/Y/W/H are always fresh.
            float[] rect = SpotifyMiniPlayerRenderer.renderPreview();

            if (rect != null) {
                float px = rect[0], py = rect[1];
                float pw = rect[2] - rect[0], ph = rect[3] - rect[1];

                if (dragging) {
                    ScaledResolution sr = new ScaledResolution(this.mc);
                    float nx = Math.max(0.0F, Math.min(sr.getScaledWidth() - pw, mouseX - dragOffsetX));
                    float ny = Math.max(0.0F, Math.min(sr.getScaledHeight() - ph, mouseY - dragOffsetY));
                    SpotifyMiniPlayer.setCustomPositionFromAbsolute(nx, ny, pw, ph, sr);
                    // Re-render with updated position so outline matches
                    rect = SpotifyMiniPlayerRenderer.renderPreview();
                    if (rect != null) { px = rect[0]; py = rect[1]; pw = rect[2]-rect[0]; ph = rect[3]-rect[1]; }
                }

                drawOutline(px, py, pw, ph);
            }

            drawCenteredString(this.fontRendererObj, "Drag the Spotify mini player to move it.", this.width / 2, 18, Color.white.getRGB());
            drawCenteredString(this.fontRendererObj, "Press Esc when you're done.", this.width / 2, 30, 0xFFD0D7DE);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) throws java.io.IOException {
            super.mouseClicked(mouseX, mouseY, button);
            if (button != 0) return;
            float[] rect = SpotifyMiniPlayerRenderer.renderPreview();
            if (rect == null) return;
            float px = rect[0], py = rect[1], pw = rect[2]-rect[0], ph = rect[3]-rect[1];
            if (mouseX >= px && mouseX <= px + pw && mouseY >= py && mouseY <= py + ph) {
                dragging = true;
                dragOffsetX = mouseX - px;
                dragOffsetY = mouseY - py;
            }
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            // Dragging state is set in mouseClicked; movement handled in drawScreen.
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) dragging = false;
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == resetPosition) {
                SpotifyMiniPlayer.clearCustomPosition();
            }
        }

        @Override
        public boolean doesGuiPauseGame() {
            return false;
        }

        private void drawOutline(float x, float y, float width, float height) {
            int borderColor = 0xFFFFFFFF;
            drawRect((int) x - 1, (int) y - 1, (int) (x + width) + 1, (int) y, borderColor);
            drawRect((int) x - 1, (int) (y + height), (int) (x + width) + 1, (int) (y + height) + 1, borderColor);
            drawRect((int) x - 1, (int) y - 1, (int) x, (int) (y + height) + 1, borderColor);
            drawRect((int) (x + width), (int) y - 1, (int) (x + width) + 1, (int) (y + height) + 1, borderColor);
        }
    }
}

