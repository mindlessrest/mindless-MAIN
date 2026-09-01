package mindless.module.impl.render;

import mindless.module.Module;
import mindless.module.ModuleManager;
import mindless.module.impl.client.HudEditor;
import mindless.module.setting.impl.ButtonSetting;
import mindless.module.setting.impl.ColorSetting;
import mindless.module.setting.impl.DescriptionSetting;
import mindless.module.setting.impl.SliderSetting;
import mindless.utility.ScaledResolutionCache;
import mindless.utility.Utils;
import mindless.utility.media.SpotifyVisualizerEngine;
import mindless.utility.media.SystemMediaClient;
import mindless.utility.media.VisualizerRenderer;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
public class AudioVisualizer extends Module {
    public static final int PLACEMENT_MINI_PLAYER = 0;
    public static final int PLACEMENT_STANDALONE = 1;

    public static final int STYLE_ROUNDED = 0;
    public static final int STYLE_SQUARE = 1;
    public static final int STYLE_DOTS = 2;
    public static final int STYLE_LINE = 3;

    public static final int COLOR_HUD = 0;
    public static final int COLOR_ALBUM = 1;
    public static final int COLOR_CUSTOM = 2;
public static final int BEHAVIOUR_REACT = -1;
    public static final int BEHAVIOUR_FLAT = 0;
    public static final int BEHAVIOUR_WAVE = 1;
    public static final int BEHAVIOUR_HIDE = 2;

    private static AudioVisualizer instance;

    private final SliderSetting placement;

    private final SliderSetting bars;
    private final SliderSetting barWidth;
    private final SliderSetting barSpacing;
    private final SliderSetting barStyle;
    private final SliderSetting minHeight;
    private final SliderSetting maxHeight;
    private final ButtonSetting mirror;

    private final SliderSetting sensitivity;
    private final SliderSetting intensity;
    private final SliderSetting smoothing;
    private final SliderSetting animationSpeed;
    private final SliderSetting updateRate;

    private final SliderSetting colorMode;
    private final ColorSetting customColor;
    private final ButtonSetting gradient;
    private final SliderSetting gradientDirection;
    private final SliderSetting opacity;

    private final ButtonSetting baseline;

    private final SliderSetting idleBehaviour;
    private final SliderSetting pausedBehaviour;

    private final SliderSetting standaloneWidth;
    private final SliderSetting standaloneHeight;
    private final SliderSetting standaloneScale;
private int reportedStatus = SpotifyVisualizerEngine.STATUS_STOPPED;

    private float relativeX = Float.NaN;
    private float relativeY = Float.NaN;
    private float posX = Float.NaN;
    private float posY = Float.NaN;

    public AudioVisualizer() {
        super("Audio Visualizer", category.render);
        instance = this;

        this.registerSetting(placement = new SliderSetting("Placement", PLACEMENT_MINI_PLAYER,
                new String[]{"Mini player", "Standalone"}));

        this.registerSetting(new DescriptionSetting("Bars"));
        this.registerSetting(bars = new SliderSetting("Bar count", 48, 8, 128, 1));
        this.registerSetting(barStyle = new SliderSetting("Bar style", STYLE_ROUNDED,
                new String[]{"Rounded", "Square", "Dots", "Line"}));
        this.registerSetting(barWidth = new SliderSetting("Bar width", "%", 72, 20, 100, 2));
        this.registerSetting(barSpacing = new SliderSetting("Bar spacing", "px", 1.0, 0.0, 6.0, 0.25));
        this.registerSetting(minHeight = new SliderSetting("Minimum height", "%", 4, 0, 40, 1));
        this.registerSetting(maxHeight = new SliderSetting("Maximum height", "%", 100, 30, 100, 5));
        this.registerSetting(mirror = new ButtonSetting("Mirror from centre", false));

        this.registerSetting(new DescriptionSetting("Response"));
        this.registerSetting(sensitivity = new SliderSetting("Sensitivity", "x", 1.0, 0.25, 3.0, 0.05));
        this.registerSetting(intensity = new SliderSetting("Intensity", "x", 1.0, 0.4, 2.5, 0.05));
        this.registerSetting(smoothing = new SliderSetting("Smoothing", 0.77, 0.0, 0.99, 0.01));
        this.registerSetting(animationSpeed = new SliderSetting("Animation speed", "x", 1.0, 0.2, 3.0, 0.1));
        this.registerSetting(updateRate = new SliderSetting("Update rate", " fps", 60, 20, 144, 5));

        this.registerSetting(new DescriptionSetting("Colour"));
        this.registerSetting(colorMode = new SliderSetting("Source", COLOR_HUD,
                new String[]{"HUD gradient", "Album art", "Custom"}));
        this.registerSetting(customColor = new ColorSetting("Custom colour", 128, 226, 137));
        this.registerSetting(gradient = new ButtonSetting("Gradient", true));
        this.registerSetting(gradientDirection = new SliderSetting("Gradient direction", 0,
                new String[]{"Across bars", "Up each bar"}));
        this.registerSetting(opacity = new SliderSetting("Opacity", "%", 100, 10, 100, 5));

        this.registerSetting(new DescriptionSetting("Panel"));
        this.registerSetting(baseline = new ButtonSetting("Baseline", true));

        this.registerSetting(new DescriptionSetting("When silent"));
        this.registerSetting(idleBehaviour = new SliderSetting("Idle", BEHAVIOUR_WAVE,
                new String[]{"Flat", "Gentle wave", "Hide"}));
        this.registerSetting(pausedBehaviour = new SliderSetting("Paused", BEHAVIOUR_FLAT,
                new String[]{"Flat", "Gentle wave", "Hide"}));

        this.registerSetting(new DescriptionSetting("Standalone"));
        this.registerSetting(standaloneWidth = new SliderSetting("Width", "px", 190, 60, 520, 5));
        this.registerSetting(standaloneHeight = new SliderSetting("Height", "px", 48, 16, 180, 2));
        this.registerSetting(standaloneScale = new SliderSetting("Scale", "x", 1.0, 0.5, 2.0, 0.05));

        this.setEnabled(false);
    }

    public static AudioVisualizer getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        SystemMediaClient.getInstance().setEnabled(true);
        VisualizerRenderer.reset();
    }

    @Override
    public void onDisable() {
        SystemMediaClient.getInstance().setVisualizerWantsArtwork(false);
        reportedStatus = SpotifyVisualizerEngine.STATUS_STOPPED;
        SpotifyVisualizerEngine.getInstance().shutdown();
        VisualizerRenderer.reset();
        if (ModuleManager.spotifyMiniPlayer == null || !ModuleManager.spotifyMiniPlayer.isEnabled()) {
            SystemMediaClient.getInstance().setEnabled(false);
        }
    }

    @Override
    public void guiUpdate() {
        boolean standalone = placement() == PLACEMENT_STANDALONE;
        if (standaloneWidth != null) standaloneWidth.setVisible(standalone, this);
        if (standaloneHeight != null) standaloneHeight.setVisible(standalone, this);
        if (standaloneScale != null) standaloneScale.setVisible(standalone, this);
        if (customColor != null) customColor.setVisible(colorMode() == COLOR_CUSTOM, this);
        if (gradientDirection != null) gradientDirection.setVisible(gradientEnabled(), this);
    }
@SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !this.isEnabled()) return;
        if (mc.currentScreen != null) {
            SpotifyVisualizerEngine engine = SpotifyVisualizerEngine.getInstance();
            engine.configure(barCount(), smoothing(), updateRate());
            engine.requestFrame();
        }

        int status = SpotifyVisualizerEngine.getInstance().getStatus();
        if (status == reportedStatus) return;
        reportedStatus = status;

        if (status == SpotifyVisualizerEngine.STATUS_UNSUPPORTED) {
            Utils.sendMessage("&cAudio Visualizer: &rper-process audio capture needs Windows 10 "
                    + "build 20348 or newer.");
        }
        else if (status == SpotifyVisualizerEngine.STATUS_FAILED) {
            Utils.sendMessage("&cAudio Visualizer: &r"
                    + SpotifyVisualizerEngine.getInstance().getStatusText());
        }
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!this.isEnabled() || placement() != PLACEMENT_STANDALONE) return;
        if (mc.currentScreen != null) return;
        if (!Utils.nullCheck()) return;
        if (mc.gameSettings != null && mc.gameSettings.showDebugInfo) return;
        draw();
    }
public float[] renderPreview() {
        return draw();
    }
public float[] renderDesignerPreview(float left, float top) {
        setAbsolute(left, top, ScaledResolutionCache.get());
        return draw();
    }

    private float[] draw() {
        syncPosition();
        float scale = (float) standaloneScale.getInput();
        float width = (float) standaloneWidth.getInput() * scale;
        float height = (float) standaloneHeight.getInput() * scale;
        VisualizerRenderer.draw(posX, posY, width, height, 1.0F);
        return new float[]{posX, posY, posX + width, posY + height};
    }

    public float getPosX() {
        syncPosition();
        return posX;
    }

    public float getPosY() {
        syncPosition();
        return posY;
    }

    public SliderSetting scaleSetting() {
        return standaloneScale;
    }
public float getRelativePosX() {
        syncPosition();
        return relativeX;
    }

    public float getRelativePosY() {
        syncPosition();
        return relativeY;
    }

    public void setRelativePosition(float x, float y) {
        relativeX = Math.max(0.0F, Math.min(1.0F, x));
        relativeY = Math.max(0.0F, Math.min(1.0F, y));
        syncPosition();
    }

    public void resetPosition() {
        relativeX = 0.02F;
        relativeY = 0.72F;
        syncPosition();
    }

    private void syncPosition() {
        ScaledResolution resolution = ScaledResolutionCache.get();
        int width = Math.max(1, resolution.getScaledWidth());
        int height = Math.max(1, resolution.getScaledHeight());
        if (Float.isNaN(relativeX) || Float.isNaN(relativeY)) {
            relativeX = 0.02F;
            relativeY = 0.72F;
        }
        posX = relativeX * width;
        posY = relativeY * height;
    }

    private void setAbsolute(float left, float top, ScaledResolution resolution) {
        posX = left;
        posY = top;
        relativeX = left / Math.max(1, resolution.getScaledWidth());
        relativeY = top / Math.max(1, resolution.getScaledHeight());
    }
public static boolean wantsMiniPlayerSection() {
        AudioVisualizer module = instance;
        return module != null && module.isEnabled() && module.placement() == PLACEMENT_MINI_PLAYER;
    }
public static float miniPlayerSectionHeight(float uiScale) {
        AudioVisualizer module = instance;
        if (module == null) return 0.0F;
        return Math.max(16.0F, 34.0F * uiScale);
    }

    public int placement() {
        return (int) placement.getInput();
    }

    public int barCount() {
        return (int) bars.getInput();
    }

    public int barStyle() {
        return (int) barStyle.getInput();
    }

    public float barWidthFraction() {
        return (float) barWidth.getInput() / 100.0F;
    }

    public float barSpacing() {
        return (float) barSpacing.getInput();
    }

    public float minHeightFraction() {
        return (float) minHeight.getInput() / 100.0F;
    }

    public float maxHeightFraction() {
        return (float) maxHeight.getInput() / 100.0F;
    }

    public boolean mirrored() {
        return mirror.isToggled();
    }

    public float sensitivity() {
        return (float) sensitivity.getInput();
    }

    public float intensity() {
        return (float) intensity.getInput();
    }

    public double smoothing() {
        return smoothing.getInput();
    }

    public float animationSpeed() {
        return (float) animationSpeed.getInput();
    }

    public int updateRate() {
        return (int) updateRate.getInput();
    }

    public int colorMode() {
        return (int) colorMode.getInput();
    }

    public boolean gradientEnabled() {
        return gradient.isToggled();
    }

    public boolean gradientVertical() {
        return (int) gradientDirection.getInput() == 1;
    }

    public float opacity() {
        return (float) opacity.getInput() / 100.0F;
    }
public boolean baselineEnabled() {
        return baseline.isToggled();
    }

    public int idleBehaviour() {
        return (int) idleBehaviour.getInput();
    }

    public int pausedBehaviour() {
        return (int) pausedBehaviour.getInput();
    }
public int barColor(float position) {
        switch (colorMode()) {
            case COLOR_ALBUM: {
                int accent = SystemMediaClient.getInstance().getAlbumAccentColor();
                if (accent != 0) {
                    if (gradientEnabled() && position > 0.0F) {
                        return blend(accent, 0xFFFFFF, position * 0.45F);
                    }
                    return accent;
                }
                return HUD.getHudColor(position * 120.0D) & 0xFFFFFF;
            }
            case COLOR_CUSTOM: {
                int base = customColor.getRGB();
                if (gradientEnabled() && position > 0.0F) {
                    return blend(base, 0xFFFFFF, position * 0.45F);
                }
                return base;
            }
            case COLOR_HUD:
            default:
                return HUD.getHudColor(gradientEnabled() ? position * 120.0D : 0.0D) & 0xFFFFFF;
        }
    }

    private static int blend(int from, int to, float amount) {
        float clamped = amount < 0.0F ? 0.0F : (amount > 1.0F ? 1.0F : amount);
        int red = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * clamped);
        int green = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * clamped);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * clamped);
        return (red << 16) | (green << 8) | blue;
    }
}
