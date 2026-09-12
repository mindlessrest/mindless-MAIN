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
    /** Normalised drop below the player used before the bubble has been placed by hand. */
    private static final float DETACHED_LYRICS_DROP = 0.12F;
    private final Module settingOwner;

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
    public static ButtonSetting separateLyrics;
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
        this(null);
    }

    public SpotifyMiniPlayer(Module owner) {
        super("Spotify Info", "Shows your current track, art and lyrics.", category.render);
        this.settingOwner = owner == null ? this : owner;
        settingOwner.registerSetting(new DescriptionSetting("Spotify info"));
        settingOwner.registerSetting(widgetStyle = new SliderSetting("Spotify mode", 0, new String[]{"Modern", "Old"}));
        settingOwner.registerSetting(widgetFont = new SliderSetting("Spotify font", 0, FONT_OPTIONS));
        settingOwner.registerSetting(showAlbumArt = new ButtonSetting("Show album art", true));
        settingOwner.registerSetting(showProgressBar = new ButtonSetting("Show progress bar", true));
        settingOwner.registerSetting(progressBarColorMode = new SliderSetting("Progress bar colors", 0, new String[]{"HUD gradient", "Album accent"}));
        settingOwner.registerSetting(showHeader = new ButtonSetting("Show header", true));
        settingOwner.registerSetting(showDetails = new ButtonSetting("Show details", true));
        settingOwner.registerSetting(showSourceApp = new ButtonSetting("Show source app", true));
        settingOwner.registerSetting(showStatusBadge = new ButtonSetting("Show status badge", true));
        settingOwner.registerSetting(new DescriptionSetting("Lyrics"));
        settingOwner.registerSetting(showLyrics = new ButtonSetting("Show synced lyrics", true));
        settingOwner.registerSetting(separateLyrics = new ButtonSetting("Separate lyrics widget", false));
        settingOwner.registerSetting(fullLyricsView = new ButtonSetting("Full lyrics view", false));
        settingOwner.registerSetting(karaokeLyrics = new ButtonSetting("Karaoke highlight", false));
        settingOwner.registerSetting(animateLyrics = new ButtonSetting("Animate lyrics", true));
        settingOwner.registerSetting(lyricsFont = new SliderSetting("Lyrics font", 0, FONT_OPTIONS));
        settingOwner.registerSetting(lyricTextScale = new SliderSetting("Lyrics size", "x", 1.0, 0.85, 1.4, 0.05));
        settingOwner.registerSetting(lyricAnimationSpeed = new SliderSetting("Lyrics animation", "ms", 260, 80, 600, 20));
        settingOwner.registerSetting(lyricSyncOffset = new SliderSetting("Lyrics sync", "ms", 350, -1500, 1500, 50));
        settingOwner.registerSetting(showIdleCard = new ButtonSetting("Show when idle", true));
        settingOwner.registerSetting(hideWhenPaused = new ButtonSetting("Hide when paused", false));
        settingOwner.registerSetting(dynamicIslandStyle = new ButtonSetting("Accent tint", true));
        settingOwner.registerSetting(noBackground = new ButtonSetting("No background", false));
        settingOwner.registerSetting(new ButtonSetting("Edit Spotify position", () -> mc.displayGuiScreen(new EditScreen())));
        settingOwner.registerSetting(scale = new SliderSetting("Spotify scale", "x", 0.5, 0.4, 1.75, 0.05));
        settingOwner.registerSetting(lyricsScale = new SliderSetting("Lyrics bubble size", "x", 1.0, 0.5, 2.0, 0.05));
        settingOwner.registerSetting(customPosX = new SliderSetting("Spotify Position X", 0.0, -1.0, 1.0, 0.001));
        settingOwner.registerSetting(customPosY = new SliderSetting("Spotify Position Y", 0.0, -1.0, 1.0, 0.001));
        settingOwner.registerSetting(lyricsPosX = new SliderSetting("Lyrics Position X", 0.0, -1.0, 1.0, 0.001));
        settingOwner.registerSetting(lyricsPosY = new SliderSetting("Lyrics Position Y", 0.0, -1.0, 1.0, 0.001));
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
        boolean islandHandlesPlayer = ModuleManager.dynamicIsland != null
                && ModuleManager.dynamicIsland.handlesSpotify();
        mediaClient.setPlayerWantsArtwork(islandHandlesPlayer || showAlbumArt.isToggled()
                || (int) progressBarColorMode.getInput() == 1);

        if (!islandHandlesPlayer) MediaPlayerRenderer.render();
    }

    public void openEditScreen() {
        mc.displayGuiScreen(new EditScreen());
    }

    @Override
    public void guiUpdate() {
        boolean widget = widgetStyle == null || (int) widgetStyle.getInput() == 0;
        if (showHeader != null) showHeader.setVisible(!widget, settingOwner);
        if (showDetails != null) showDetails.setVisible(!widget, settingOwner);
        if (showSourceApp != null) showSourceApp.setVisible(!widget, settingOwner);
        if (showStatusBadge != null) showStatusBadge.setVisible(!widget, settingOwner);
        if (showAlbumArt != null) showAlbumArt.setVisible(!widget, settingOwner);
        if (noBackground != null) noBackground.setVisible(!widget, settingOwner);
        if (fullLyricsView != null) fullLyricsView.setVisible(!widget, settingOwner);
        if (karaokeLyrics != null) karaokeLyrics.setVisible(!widget, settingOwner);

        boolean lyricsVisible = showLyrics != null && showLyrics.isToggled();
        if (fullLyricsView != null) {
            fullLyricsView.setVisible(lyricsVisible && !widget, settingOwner);
        }
        if (karaokeLyrics != null) {
            karaokeLyrics.setVisible(lyricsVisible && !widget, settingOwner);
        }
        if (animateLyrics != null) {
            animateLyrics.setVisible(lyricsVisible, settingOwner);
        }
        if (lyricTextScale != null) {
            lyricTextScale.setVisible(lyricsVisible, settingOwner);
        }
        boolean detached = lyricsVisible && separateLyrics != null && separateLyrics.isToggled();
        if (separateLyrics != null) {
            separateLyrics.setVisible(lyricsVisible, settingOwner);
        }
        if (lyricsScale != null) {
            lyricsScale.setVisible(lyricsVisible && (widget || detached), settingOwner);
        }
        if (lyricSyncOffset != null) {
            lyricSyncOffset.setVisible(lyricsVisible, settingOwner);
        }
        boolean animateVisible = lyricsVisible && animateLyrics != null && animateLyrics.isToggled();
        if (lyricAnimationSpeed != null) {
            lyricAnimationSpeed.setVisible(animateVisible, settingOwner);
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

    /**
     * Whether the lyric bubble is its own widget rather than a strip under the player.
     *
     * This used to be implied purely by a custom position existing, but the position sliders
     * are hidden, so the only way to detach was to drag the bubble in the HUD editor -- and
     * there was nothing to drag until it had already detached. The toggle is now the switch
     * and the position is just where it sits.
     */
    public static boolean hasLyricsPosition() {
        if (separateLyrics != null && separateLyrics.isToggled()) {
            return true;
        }
        return lyricsPosX != null && lyricsPosY != null
                && lyricsPosX.getInput() >= 0.0D && lyricsPosY.getInput() >= 0.0D;
    }

    /** True once the bubble has an explicit place, as opposed to falling back to a default. */
    public static boolean hasExplicitLyricsPosition() {
        return lyricsPosX != null && lyricsPosY != null
                && lyricsPosX.getInput() >= 0.0D && lyricsPosY.getInput() >= 0.0D;
    }

    public static float getLyricsNormalizedX() {
        if (!hasExplicitLyricsPosition()) {
            // Detached but never placed: sit under the player rather than snapping to the
            // top-left corner, which is where clamping an unset -1 would put it.
            return getCustomNormalizedX();
        }
        return lyricsPosX == null ? 0.0F : (float) Math.max(0.0D, Math.min(1.0D, lyricsPosX.getInput()));
    }

    public static float getLyricsNormalizedY() {
        if (!hasExplicitLyricsPosition()) {
            return Math.min(1.0F, getCustomNormalizedY() + DETACHED_LYRICS_DROP);
        }
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
                if (dragging) {
                    mindless.utility.ProfileUtils.markUnsaved();
                }
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

