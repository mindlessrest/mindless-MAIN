package keystrokesmod.module.impl.client;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.media.SystemMediaClient;
import keystrokesmod.utility.media.SpotifyMiniPlayerRenderer;
import keystrokesmod.utility.Utils;
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
        private float actualX;
        private float actualY;

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            float[] bounds = SpotifyMiniPlayerRenderer.getCurrentBounds();
            if (bounds != null) {
                this.actualX = bounds[0];
                this.actualY = bounds[1];
                SpotifyMiniPlayer.setCustomPositionFromAbsolute(actualX, actualY, bounds[2], bounds[3], new ScaledResolution(this.mc));
            }
        }

        @Override
        public void drawScreen(int mouseX, int mouseY, float partialTicks) {
            drawRect(0, 0, this.width, this.height, 0x7A000000);

            float[] bounds = SpotifyMiniPlayerRenderer.getCurrentBounds();
            if (bounds != null) {
                if (!dragging) {
                    actualX = bounds[0];
                    actualY = bounds[1];
                }
                else {
                    ScaledResolution resolution = new ScaledResolution(this.mc);
                    float clampedX = Math.max(0.0F, Math.min(resolution.getScaledWidth() - bounds[2], mouseX - dragOffsetX));
                    float clampedY = Math.max(0.0F, Math.min(resolution.getScaledHeight() - bounds[3], mouseY - dragOffsetY));
                    actualX = clampedX;
                    actualY = clampedY;
                    SpotifyMiniPlayer.setCustomPositionFromAbsolute(actualX, actualY, bounds[2], bounds[3], resolution);
                    bounds = SpotifyMiniPlayerRenderer.getCurrentBounds();
                    if (bounds != null) {
                        actualX = bounds[0];
                        actualY = bounds[1];
                    }
                }

                drawOutline(bounds[0], bounds[1], bounds[2], bounds[3]);
            }

            drawCenteredString(this.fontRendererObj, "Drag the Spotify mini player to move it.", this.width / 2, 18, Color.white.getRGB());
            drawCenteredString(this.fontRendererObj, "Press Esc when you're done.", this.width / 2, 30, 0xFFD0D7DE);
            super.drawScreen(mouseX, mouseY, partialTicks);
        }

        @Override
        protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
            super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
            if (button != 0) {
                return;
            }

            float[] bounds = SpotifyMiniPlayerRenderer.getCurrentBounds();
            if (bounds == null) {
                return;
            }

            if (!dragging) {
                if (mouseX >= bounds[0] && mouseX <= bounds[0] + bounds[2] && mouseY >= bounds[1] && mouseY <= bounds[1] + bounds[3]) {
                    dragging = true;
                    dragOffsetX = mouseX - bounds[0];
                    dragOffsetY = mouseY - bounds[1];
                }
            }
        }

        @Override
        protected void mouseReleased(int mouseX, int mouseY, int state) {
            super.mouseReleased(mouseX, mouseY, state);
            if (state == 0) {
                dragging = false;
            }
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

