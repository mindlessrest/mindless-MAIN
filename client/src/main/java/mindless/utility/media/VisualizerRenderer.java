package mindless.utility.media;

import mindless.module.impl.render.AudioVisualizer;
import mindless.module.impl.render.HUD;
import mindless.utility.RenderUtils;
import mindless.utility.shader.RoundedUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import org.lwjgl.opengl.GL11;
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
    /** Reused across frames; the bars are redrawn every frame and never outlive one. */
    private static final QuadBatch QUAD_BATCH = new QuadBatch();

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
     */
    public static void draw(float x, float y, float width, float height, float alphaScale) {
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

        // Every bar that comes out a plain quad goes into one batch. Drawn one at a time, each was
        // its own tessellator pass wrapped in a dozen state calls, and at fifty-five bars that is
        // fifty-five draws and the better part of a thousand calls to put up a shape the hardware
        // finishes in microseconds. Every bar in a given style takes the same path -- barWidth does
        // not vary between them -- so the batch either takes all of them or none, and nothing can
        // land out of order with the rounded styles below.
        QuadBatch batch = QUAD_BATCH;
        batch.begin(gradient && verticalGradient);

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
                        batch.add(left, top, left + barWidth, top + barHeight,
                                withAlpha(module.barColor(1.0F), topAlpha),
                                withAlpha(baseColor, topAlpha));
                    }
                    else {
                        int flat = withAlpha(baseColor, topAlpha);
                        batch.add(left, top, left + barWidth, bottom, flat, flat);
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
                            batch.add(left, top, left + barWidth, top + barHeight,
                                    withAlpha(module.barColor(1.0F), topAlpha),
                                    withAlpha(baseColor, topAlpha));
                        }
                        else {
                            int flat = withAlpha(baseColor, topAlpha);
                            batch.add(left, top, left + barWidth, bottom, flat, flat);
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

        batch.end();
    }

    /**
     * Flat quads for a whole spectrum, submitted once.
     *
     * <p>Colour is per vertex so a gradient bar and a solid one live in the same batch; a solid
     * one simply carries the same colour at both ends, which smooth shading interpolates to
     * exactly itself.
     */
    private static final class QuadBatch {
        private static final int MAX_QUADS = 512;

        private final float[] bounds = new float[MAX_QUADS * 4];
        private final int[] colors = new int[MAX_QUADS * 2];
        private int count;
        private boolean alphaTestOff;

        private void begin(boolean disableAlphaTest) {
            count = 0;
            alphaTestOff = disableAlphaTest;
        }

        private void add(float left, float top, float right, float bottom, int topColor, int bottomColor) {
            if (right <= left || bottom <= top) {
                return;
            }
            if (count >= MAX_QUADS) {
                flush();
            }

            int b = count * 4;
            bounds[b] = left;
            bounds[b + 1] = top;
            bounds[b + 2] = right;
            bounds[b + 3] = bottom;
            colors[count * 2] = topColor;
            colors[count * 2 + 1] = bottomColor;
            count++;
        }

        private void end() {
            flush();
        }

        private void flush() {
            if (count == 0) {
                return;
            }

            GlStateManager.disableTexture2D();
            GlStateManager.enableBlend();
            if (alphaTestOff) {
                GlStateManager.disableAlpha();
            }
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            GlStateManager.shadeModel(GL11.GL_SMOOTH);

            Tessellator tessellator = Tessellator.getInstance();
            WorldRenderer worldRenderer = tessellator.getWorldRenderer();
            worldRenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);

            for (int i = 0; i < count; i++) {
                int b = i * 4;
                float left = bounds[b];
                float top = bounds[b + 1];
                float right = bounds[b + 2];
                float bottom = bounds[b + 3];

                int topColor = colors[i * 2];
                int bottomColor = colors[i * 2 + 1];
                float ta = (topColor >> 24 & 0xFF) / 255.0F;
                float tr = (topColor >> 16 & 0xFF) / 255.0F;
                float tg = (topColor >> 8 & 0xFF) / 255.0F;
                float tb = (topColor & 0xFF) / 255.0F;
                float ba = (bottomColor >> 24 & 0xFF) / 255.0F;
                float br = (bottomColor >> 16 & 0xFF) / 255.0F;
                float bg = (bottomColor >> 8 & 0xFF) / 255.0F;
                float bb = (bottomColor & 0xFF) / 255.0F;

                worldRenderer.pos(right, top, 0).color(tr, tg, tb, ta).endVertex();
                worldRenderer.pos(left, top, 0).color(tr, tg, tb, ta).endVertex();
                worldRenderer.pos(left, bottom, 0).color(br, bg, bb, ba).endVertex();
                worldRenderer.pos(right, bottom, 0).color(br, bg, bb, ba).endVertex();
            }

            tessellator.draw();

            GlStateManager.shadeModel(GL11.GL_FLAT);
            GlStateManager.disableBlend();
            if (alphaTestOff) {
                GlStateManager.enableAlpha();
            }
            GlStateManager.enableTexture2D();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            count = 0;
        }
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
