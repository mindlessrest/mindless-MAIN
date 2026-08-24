package mindless.utility.media;

import mindless.module.impl.render.AudioVisualizer;
import mindless.module.impl.render.HUD;
import mindless.utility.RenderUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL20;

import java.awt.Color;
import java.util.Arrays;

/**
 * Draws the bars. The same code serves the mini player section and the standalone overlay, which
 * is the point: two drawings of the same signal that did not match would look like two features.
 *
 * <p>The engine publishes at its own rate and the game draws at another, so the heights here are
 * eased toward the published ones per frame using elapsed time rather than a fixed step. Without
 * that, the visualiser would visibly run at the engine's rate rather than the game's, and would
 * change character with the frame rate.
 */
public final class VisualizerRenderer {
    /** Bar heights actually on screen, chasing the engine's. */
    private static float[] displayed = new float[0];
    /** Where the bars are being eased toward. Reused rather than reallocated each frame. */
    private static float[] targets = new float[0];
    private static long lastFrameNanos;
    /** Eases the whole thing in and out instead of popping when audio starts or stops. */
    private static float presence;

    private VisualizerRenderer() {
    }

    /** Forgets the animation state, so a re-shown visualiser starts from rest. */
    public static void reset() {
        displayed = new float[0];
        lastFrameNanos = 0L;
        presence = 0.0F;
    }

    /**
     * Draws the visualiser into the given rectangle.
     *
     * @param alphaScale overall fade applied on top of the configured opacity, for panels that
     *                   animate in
     * @param drawPanel  whether to paint the card behind the bars
     */
    public static void draw(float x, float y, float width, float height, float alphaScale,
                            boolean drawPanel) {
        AudioVisualizer module = AudioVisualizer.getInstance();
        if (module == null || width <= 1.0F || height <= 1.0F) {
            return;
        }

        SpotifyVisualizerEngine engine = SpotifyVisualizerEngine.getInstance();
        engine.configure(module.barCount(), module.smoothing(), module.updateRate());
        engine.requestFrame();

        // Read the clock exactly once. Every easing step this frame is measured against the
        // same delta -- taking a fresh reading per step would leave all but the first with
        // essentially no elapsed time, and those bars would sit still.
        float delta = advanceClock();

        SystemMediaInfo info = SystemMediaClient.getInstance().getCurrentInfo();
        boolean paused = info.isAvailable() && info.isPaused();
        boolean live = engine.isCapturing() && !paused;

        int behaviour = live
                ? AudioVisualizer.BEHAVIOUR_REACT
                : (paused ? module.pausedBehaviour() : module.idleBehaviour());
        if (behaviour == AudioVisualizer.BEHAVIOUR_HIDE) {
            // Still ease out rather than vanishing between one frame and the next.
            presence = approach(presence, 0.0F, delta, 6.0F);
            if (presence <= 0.01F) return;
        }
        else {
            presence = approach(presence, 1.0F, delta, 6.0F);
        }

        float alpha = Math.max(0.0F, Math.min(1.0F, alphaScale * module.opacity() * presence));
        if (alpha <= 0.01F) return;

        int count = Math.max(2, module.barCount());
        resolveTargets(engine, module, behaviour, count);
        advanceDisplayed(delta, module.animationSpeed());

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        if (drawPanel) {
            drawCard(module, x, y, width, height, alpha);
        }

        drawBars(module, x, y, width, height, alpha);

        // The rounded shader leaves a program bound; anything drawn after this without clearing it
        // comes out wrong.
        GL20.glUseProgram(0);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ------------------------------------------------------------------------------- bar values

    private static void resolveTargets(SpotifyVisualizerEngine engine, AudioVisualizer module,
                                       int behaviour, int count) {
        if (targets.length != count) {
            targets = new float[count];
        }

        if (behaviour == AudioVisualizer.BEHAVIOUR_REACT) {
            float[] bars = engine.getBars();
            float sensitivity = module.sensitivity();
            float intensity = module.intensity();
            for (int i = 0; i < count; i++) {
                float value = i < bars.length ? bars[i] : 0.0F;
                // Intensity is a curve rather than a multiplier: above 1 it holds the quiet bars
                // down and lets the loud ones through, which is what "more intense" looks like.
                if (intensity != 1.0F && value > 0.0F) {
                    value = (float) Math.pow(value, intensity);
                }
                value *= sensitivity;
                targets[i] = value > 1.0F ? 1.0F : value;
            }
            return;
        }

        if (behaviour == AudioVisualizer.BEHAVIOUR_WAVE) {
            // A slow travelling swell, so a paused player still looks alive without pretending to
            // be reacting to anything.
            double time = System.nanoTime() / 1000000000.0;
            for (int i = 0; i < count; i++) {
                double phase = time * 1.15 - i * 0.34;
                targets[i] = (float) (0.10 + 0.09 * (Math.sin(phase) + 1.0) * 0.5
                        + 0.045 * (Math.sin(phase * 2.3) + 1.0) * 0.5);
            }
            return;
        }

        // Flat: everything falls away to the minimum height.
        Arrays.fill(targets, 0.0F);
    }

    private static void advanceDisplayed(float delta, float speed) {
        if (displayed.length != targets.length) {
            displayed = new float[targets.length];
        }
        for (int i = 0; i < targets.length; i++) {
            // Rising fast and falling slower is what a level meter does, and it reads far better
            // than one rate for both: transients stay sharp while the decay stays smooth.
            float rate = targets[i] > displayed[i] ? 26.0F * speed : 11.0F * speed;
            displayed[i] = approach(displayed[i], targets[i], delta, rate);
        }
    }

    private static float advanceClock() {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0F / 60.0F;
        }
        float delta = (now - lastFrameNanos) / 1000000000.0F;
        lastFrameNanos = now;
        // A pause -- a loading screen, a stutter -- must not teleport every bar.
        return Math.max(0.0F, Math.min(0.1F, delta));
    }

    /** Frame-rate independent easing: the same journey takes the same time at any frame rate. */
    private static float approach(float current, float target, float delta, float rate) {
        float factor = 1.0F - (float) Math.exp(-delta * rate);
        return current + (target - current) * factor;
    }

    // ----------------------------------------------------------------------------------- drawing

    private static void drawCard(AudioVisualizer module, float x, float y, float width,
                                 float height, float alpha) {
        float radius = module.cornerRadius();

        boolean hasBackground = module.backgroundEnabled();
        boolean hasBorder = module.borderEnabled();
        if (!hasBackground && !hasBorder) return;

        int backgroundAlpha = hasBackground
                ? Math.min(255, Math.round(255 * module.backgroundOpacity() * alpha)) : 0;
        int borderAlpha = hasBorder
                ? Math.min(255, Math.round(255 * module.borderOpacity() * alpha)) : 0;

        // One shader call draws fill and hairline together. Faking the outline with a larger rect
        // behind the fill -- as some of the older panels here do -- only produces a rim when the
        // fill is opaque, and this one usually is not.
        RoundedUtils.drawRoundOutline(x, y, width, height, radius, 1.0F,
                new Color(0, 0, 0, backgroundAlpha),
                new Color(255, 255, 255, borderAlpha));

        if (backgroundAlpha > 1) {
            // A touch brighter at the top, like the other glass in the client.
            RoundedUtils.drawGradientVertical(x, y, width, height, radius,
                    new Color(255, 255, 255, Math.round(13 * alpha)),
                    new Color(255, 255, 255, Math.round(3 * alpha)));
        }
    }

    private static void drawBars(AudioVisualizer module, float x, float y, float width,
                                 float height, float alpha) {
        int count = displayed.length;
        if (count == 0) return;

        float padding = Math.min(4.0F, Math.max(1.5F, height * 0.10F));
        float innerX = x + padding;
        float innerWidth = Math.max(1.0F, width - padding * 2.0F);
        float bottom = y + height - padding;
        float innerHeight = Math.max(1.0F, height - padding * 2.0F);

        float spacing = module.barSpacing();
        float slot = innerWidth / count;
        float barWidth = Math.max(0.75F, (slot - spacing) * module.barWidthFraction());
        float minHeight = innerHeight * module.minHeightFraction();
        float maxHeight = innerHeight * module.maxHeightFraction();
        float span = Math.max(0.0F, maxHeight - minHeight);

        if (module.baselineEnabled()) {
            // Sits under the bars rather than around them, at the same weight as the rule above
            // the lyrics, so the section reads as part of the card instead of a panel on top of
            // it. The bars overlap it, which is what makes them look like they are standing on it.
            int baselineAlpha = Math.round(34 * alpha);
            if (baselineAlpha > 1) {
                RenderUtils.drawRect(innerX, bottom, innerX + innerWidth, bottom + 0.75F,
                        (baselineAlpha << 24) | 0xFFFFFF);
            }
        }

        int style = module.barStyle();
        boolean mirror = module.mirrored();
        boolean gradient = module.gradientEnabled();
        boolean verticalGradient = module.gradientVertical();

        for (int i = 0; i < count; i++) {
            // Mirroring folds the spectrum so bass sits at the centre and treble runs out to both
            // edges -- the same data, read from the middle outwards.
            int source = mirror
                    ? (i < count / 2 ? (count / 2 - 1 - i) * 2 : (i - count / 2) * 2)
                    : i;
            if (source >= count) source = count - 1;

            float value = displayed[source];
            float barHeight = minHeight + span * value;
            if (barHeight < 0.5F) barHeight = 0.5F;

            float left = innerX + slot * i + (slot - barWidth) * 0.5F;
            float top = bottom - barHeight;

            float position = count == 1 ? 0.0F : i / (float) (count - 1);
            int baseColor = module.barColor(gradient && !verticalGradient ? position : 0.0F);

            int topAlpha = Math.round(255 * alpha);
            if (topAlpha <= 1) continue;

            switch (style) {
                case AudioVisualizer.STYLE_SQUARE:
                    if (gradient && verticalGradient) {
                        drawVertical(left, top, barWidth, barHeight, baseColor,
                                module.barColor(1.0F), topAlpha);
                    }
                    else {
                        RenderUtils.drawRect(left, top, left + barWidth, bottom,
                                withAlpha(baseColor, topAlpha));
                    }
                    break;

                case AudioVisualizer.STYLE_DOTS: {
                    float dot = Math.min(barWidth, 3.5F);
                    RoundedUtils.drawRound(left + (barWidth - dot) * 0.5F, top, dot, dot,
                            dot * 0.5F, colorOf(baseColor, topAlpha));
                    // A faint stem, so a quiet passage still shows where the bars are.
                    RenderUtils.drawRect(left + (barWidth - 1.0F) * 0.5F, top + dot,
                            left + (barWidth + 1.0F) * 0.5F, bottom,
                            withAlpha(baseColor, Math.max(8, topAlpha / 6)));
                    break;
                }

                case AudioVisualizer.STYLE_LINE: {
                    float cap = Math.max(1.25F, height * 0.045F);
                    RoundedUtils.drawRound(left, top, barWidth, cap, cap * 0.5F,
                            colorOf(baseColor, topAlpha));
                    break;
                }

                case AudioVisualizer.STYLE_ROUNDED:
                default: {
                    // Each rounded rect is its own shader bind, and at 128 bars that is 128 of
                    // them per frame. Below about three pixels wide the rounding is not visible
                    // anyway, so narrow bars take the plain-quad path -- which is exactly the
                    // case where the bar count is high enough for the cost to matter.
                    if (barWidth < 3.0F) {
                        if (gradient && verticalGradient) {
                            drawVertical(left, top, barWidth, barHeight, baseColor,
                                    module.barColor(1.0F), topAlpha);
                        }
                        else {
                            RenderUtils.drawRect(left, top, left + barWidth, bottom,
                                    withAlpha(baseColor, topAlpha));
                        }
                        break;
                    }
                    float radius = Math.min(barWidth * 0.5F, barHeight * 0.5F);
                    if (gradient && verticalGradient) {
                        RoundedUtils.drawGradientVertical(left, top, barWidth, barHeight, radius,
                                colorOf(module.barColor(1.0F), topAlpha),
                                colorOf(baseColor, topAlpha));
                    }
                    else {
                        RoundedUtils.drawRound(left, top, barWidth, barHeight, radius,
                                colorOf(baseColor, topAlpha));
                    }
                    break;
                }
            }
        }
    }

    private static void drawVertical(float left, float top, float barWidth, float barHeight,
                                     int bottomColor, int topColor, int alpha) {
        RenderUtils.drawVerticalGradientRect(left, top, left + barWidth, top + barHeight,
                withAlpha(topColor, alpha), withAlpha(bottomColor, alpha));
    }

    private static Color colorOf(int rgb, int alpha) {
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF,
                Math.max(0, Math.min(255, alpha)));
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    /** The colour the mini player should tint its section header with, for a matching look. */
    public static int accentColor() {
        AudioVisualizer module = AudioVisualizer.getInstance();
        return module == null ? HUD.getHudColor(0.0D) & 0xFFFFFF : module.barColor(0.0F);
    }
}
