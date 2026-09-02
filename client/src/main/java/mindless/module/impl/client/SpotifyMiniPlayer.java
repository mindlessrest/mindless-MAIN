package mindless.module.impl.client;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.module.impl.render.HUD;
import mindless.utility.font.ModuleFont;
import mindless.utility.media.SystemMediaClient;
import mindless.utility.media.MediaPlayerRenderer;
import mindless.utility.Utils;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import mindless.utility.gui.MindlessButton;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.Color;

public class SpotifyMiniPlayer extends Module {
    private static final double UNSET_CUSTOM_POSITION = -1.0D;

    public static SliderSetting widgetStyle;
    public static SliderSetting widgetFont;
    public static SliderSetting lyricsFont;
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
public static SliderSetting lyricsPosX;
    public static SliderSetting lyricsPosY;
    public static SliderSetting lyricsScale;

    public SpotifyMiniPlayer() {
        super("Spotify Info", "Displays currently playing song", category.render);
        this.registerSetting(widgetStyle = new SliderSetting("Mode", 0, new String[]{"Modern", "Old"}));
        this.registerSetting(widgetFont = new SliderSetting("Font", 0, FONT_OPTIONS));
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
        this.registerSetting(lyricsFont = new SliderSetting("Lyrics font", 0, FONT_OPTIONS));
        this.registerSetting(lyricTextScale = new SliderSetting("Lyrics size", "x", 1.0, 0.85, 1.4, 0.05));
        this.registerSetting(lyricAnimationSpeed = new SliderSetting("Lyrics animation", "ms", 260, 80, 600, 20));
        this.registerSetting(lyricSyncOffset = new SliderSetting("Lyrics sync", "ms", 350, -1500, 1500, 50));
        this.registerSetting(showIdleCard = new ButtonSetting("Show when idle", true));
        this.registerSetting(hideWhenPaused = new ButtonSetting("Hide when paused", false));
        this.registerSetting(dynamicIslandStyle = new ButtonSetting("Accent tint", true));
        this.registerSetting(noBackground = new ButtonSetting("No background", false));
        this.registerSetting(new ButtonSetting("Edit position", () -> mc.displayGuiScreen(new EditScreen())));
        this.registerSetting(scale = new SliderSetting("UI scale", "x", 0.5, 0.4, 1.75, 0.05));
        this.registerSetting(lyricsScale = new SliderSetting("Lyrics bubble size", "x", 1.0, 0.5, 2.0, 0.05));
        this.registerSetting(customPosX = new SliderSetting("Custom Position X", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(customPosY = new SliderSetting("Custom Position Y", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(lyricsPosX = new SliderSetting("Lyrics Position X", 0.0, -1.0, 1.0, 0.001));
        this.registerSetting(lyricsPosY = new SliderSetting("Lyrics Position Y", 0.0, -1.0, 1.0, 0.001));
        customPosX.visible = false;
        customPosY.visible = false;
        lyricsPosX.visible = false;
        lyricsPosY.visible = false;
        customPosX.setValueRaw(UNSET_CUSTOM_POSITION);
        customPosY.setValueRaw(UNSET_CUSTOM_POSITION);
        lyricsPosX.setValueRaw(UNSET_CUSTOM_POSITION);
        lyricsPosY.setValueRaw(UNSET_CUSTOM_POSITION);
        this.setEnabled(false);
    }

    @Override
    public void onEnable() {
        SystemMediaClient.getInstance().setEnabled(true);
    }

    @Override
    public void onDisable() {
        SystemMediaClient.getInstance().setLyricsWanted(false);
        SystemMediaClient.getInstance().setPlayerWantsArtwork(false);
        if (ModuleManager.audioVisualizer == null || !ModuleManager.audioVisualizer.isEnabled()) {
            SystemMediaClient.getInstance().setEnabled(false);
        }
    }
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        if (mc.currentScreen != null) return;
        if (!Utils.nullCheck()) return;
        SystemMediaClient mediaClient = SystemMediaClient.getInstance();
        mediaClient.setLyricsWanted(showLyrics.isToggled());
        mediaClient.setPlayerWantsArtwork(showAlbumArt.isToggled()
                || (int) progressBarColorMode.getInput() == 1);

        MediaPlayerRenderer.render();
    }

    public void openEditScreen() {
        mc.displayGuiScreen(new EditScreen());
    }

    @Override
    public void guiUpdate() {
        boolean widget = widgetStyle == null || (int) widgetStyle.getInput() == 0;
        if (showHeader != null) showHeader.setVisible(!widget, this);
        if (showDetails != null) showDetails.setVisible(!widget, this);
        if (showSourceApp != null) showSourceApp.setVisible(!widget, this);
        if (showStatusBadge != null) showStatusBadge.setVisible(!widget, this);
        if (showAlbumArt != null) showAlbumArt.setVisible(!widget, this);
        if (noBackground != null) noBackground.setVisible(!widget, this);
        if (fullLyricsView != null) fullLyricsView.setVisible(!widget, this);
        if (karaokeLyrics != null) karaokeLyrics.setVisible(!widget, this);

        boolean lyricsVisible = showLyrics != null && showLyrics.isToggled();
        if (fullLyricsView != null) {
            fullLyricsView.setVisible(lyricsVisible && !widget, this);
        }
        if (karaokeLyrics != null) {
            karaokeLyrics.setVisible(lyricsVisible && !widget, this);
        }
        if (animateLyrics != null) {
            animateLyrics.setVisible(lyricsVisible, this);
        }
        if (lyricTextScale != null) {
            lyricTextScale.setVisible(lyricsVisible, this);
        }
        if (lyricsScale != null) {
            lyricsScale.setVisible(lyricsVisible && widget, this);
        }
        if (lyricSyncOffset != null) {
            lyricSyncOffset.setVisible(lyricsVisible, this);
        }
        boolean animateVisible = lyricsVisible && animateLyrics != null && animateLyrics.isToggled();
        if (lyricAnimationSpeed != null) {
            lyricAnimationSpeed.setVisible(animateVisible, this);
        }
    }
private static final String[] FONT_OPTIONS = ModuleFont.options();

    public static String widgetFontName() {
        return ModuleFont.nameOf(widgetFont);
    }

    public static String lyricsFontName() {
        return ModuleFont.nameOf(lyricsFont);
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

    public static boolean hasLyricsPosition() {
        return lyricsPosX != null && lyricsPosY != null
                && lyricsPosX.getInput() >= 0.0D && lyricsPosY.getInput() >= 0.0D;
    }

    public static float getLyricsNormalizedX() {
        return lyricsPosX == null ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, lyricsPosX.getInput()));
    }

    public static float getLyricsNormalizedY() {
        return lyricsPosY == null ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, lyricsPosY.getInput()));
    }

    public static void setLyricsPositionFromAbsolute(float absoluteX, float absoluteY, float width, float height, ScaledResolution resolution) {
        if (lyricsPosX == null || lyricsPosY == null || resolution == null) {
            return;
        }
        float maxX = Math.max(0.0F, resolution.getScaledWidth() - width);
        float maxY = Math.max(0.0F, resolution.getScaledHeight() - height);
        float clampedX = Math.max(0.0F, Math.min(maxX, absoluteX));
        float clampedY = Math.max(0.0F, Math.min(maxY, absoluteY));
        lyricsPosX.setValueRaw(maxX <= 0.0F ? 0.0D : clampedX / maxX);
        lyricsPosY.setValueRaw(maxY <= 0.0F ? 0.0D : clampedY / maxY);
    }

    public static void clearLyricsPosition() {
        if (lyricsPosX != null) {
            lyricsPosX.setValueRaw(UNSET_CUSTOM_POSITION);
        }
        if (lyricsPosY != null) {
            lyricsPosY.setValueRaw(UNSET_CUSTOM_POSITION);
        }
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
        private MindlessButton resetPosition;
        private boolean dragging;
private boolean draggingLyrics;
        private float dragOffsetX;
        private float dragOffsetY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new MindlessButton(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            float[] bounds = MediaPlayerRenderer.renderPreview();
            if (bounds != null && !SpotifyMiniPlayer.hasCustomPosition()) {
                ScaledResolution sr = new ScaledResolution(this.mc);
                float w = bounds[2] - bounds[0], h = bounds[3] - bounds[1];
                SpotifyMiniPlayer.setCustomPositionFromAbsolute(bounds[0], bounds[1], w, h, sr);
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, this.width, this.height, 0x7A000000);
            float[] rect = MediaPlayerRenderer.renderPreview();

            if (rect != null) {
                float px = rect[0], py = rect[1];
                float pw = rect[2] - rect[0], ph = rect[3] - rect[1];

                if (dragging && !draggingLyrics) {
                    ScaledResolution sr = new ScaledResolution(this.mc);
                    float nx = Math.max(0.0F, Math.min(sr.getScaledWidth() - pw, mouseX - dragOffsetX));
                    float ny = Math.max(0.0F, Math.min(sr.getScaledHeight() - ph, mouseY - dragOffsetY));
                    SpotifyMiniPlayer.setCustomPositionFromAbsolute(nx, ny, pw, ph, sr);
                    rect = MediaPlayerRenderer.renderPreview();
                    if (rect != null) { px = rect[0]; py = rect[1]; pw = rect[2]-rect[0]; ph = rect[3]-rect[1]; }
                }

                drawOutline(px, py, pw, ph);
            }

            float[] lyrics = MediaPlayerRenderer.getLyricsRect();
            if (lyrics != null) {
                float lx = lyrics[0], ly = lyrics[1];
                float lw = lyrics[2] - lyrics[0], lh = lyrics[3] - lyrics[1];
                if (dragging && draggingLyrics) {
                    ScaledResolution sr = new ScaledResolution(this.mc);
                    float nx = Math.max(0.0F, Math.min(sr.getScaledWidth() - lw, mouseX - dragOffsetX));
                    float ny = Math.max(0.0F, Math.min(sr.getScaledHeight() - lh, mouseY - dragOffsetY));
                    SpotifyMiniPlayer.setLyricsPositionFromAbsolute(nx, ny, lw, lh, sr);
                    MediaPlayerRenderer.renderPreview();
                    float[] moved = MediaPlayerRenderer.getLyricsRect();
                    if (moved != null) {
                        lx = moved[0]; ly = moved[1];
                        lw = moved[2] - moved[0]; lh = moved[3] - moved[1];
                    }
                }
                drawOutline(lx, ly, lw, lh);
            }

            drawCenteredString(this.fontRendererObj, "Drag the Spotify mini player to move it.", this.width / 2, 18, Color.white.getRGB());
            drawCenteredString(this.fontRendererObj, "The lyrics bubble drags separately. Press Esc when you're done.", this.width / 2, 30, 0xFFD0D7DE);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int button) throws java.io.IOException {
            super.mouseClicked(mouseX, mouseY, button);
            if (button != 0) return;
            float[] rect = MediaPlayerRenderer.renderPreview();
            float[] lyrics = MediaPlayerRenderer.getLyricsRect();
            if (lyrics != null && mouseX >= lyrics[0] && mouseX <= lyrics[2]
                    && mouseY >= lyrics[1] && mouseY <= lyrics[3]) {
                dragging = true;
                draggingLyrics = true;
                dragOffsetX = mouseX - lyrics[0];
                dragOffsetY = mouseY - lyrics[1];
                return;
            }
            if (rect == null) return;
            float px = rect[0], py = rect[1], pw = rect[2]-rect[0], ph = rect[3]-rect[1];
            if (mouseX >= px && mouseX <= px + pw && mouseY >= py && mouseY <= py + ph) {
                dragging = true;
                draggingLyrics = false;
                dragOffsetX = mouseX - px;
                dragOffsetY = mouseY - py;
            }
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                dragging = false;
                draggingLyrics = false;
            }
        }

        @Override
        public void actionPerformed(GuiButton button) {
            if (button == resetPosition) {
                SpotifyMiniPlayer.clearCustomPosition();
                SpotifyMiniPlayer.clearLyricsPosition();
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

