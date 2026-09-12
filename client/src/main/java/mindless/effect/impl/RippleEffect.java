package mindless.effect.impl;

import mindless.effect.Effect;
import mindless.effect.EffectRenderer;

/**
 * Rings pushed out flat along the ground from a point, each one behind the last.
 *
 * A coin dropped into water: the leading edge travels furthest and fades first, and the ones
 * behind it are smaller, slower and dimmer. Every ring shares the same easing so they stay
 * evenly spaced instead of bunching at the end of the lifetime.
 */
public final class RippleEffect extends Effect {

    private final double radius;
    private final double thickness;
    private final int rings;
    private final int rgb;
    private final boolean seeThrough;

    public RippleEffect(double x, double y, double z, int durationTicks,
                        double radius, double thickness, int rings, int rgb, boolean seeThrough) {
        super(x, y, z, durationTicks);
        this.radius = radius;
        this.thickness = thickness;
        this.rings = Math.max(1, rings);
        this.rgb = rgb;
        this.seeThrough = seeThrough;
    }

    @Override
    public void render(float partialTicks) {
        float life = progress(partialTicks);
        EffectRenderer.seeThrough(seeThrough);

        for (int i = 0; i < rings; i++) {
            // Each ring starts a fixed fraction of the lifetime after the one in front, and the
            // remaining time is restretched so a late ring still completes its travel.
            float offset = i * 0.16f;
            if (life <= offset) {
                continue;
            }
            float local = (life - offset) / (1.0f - offset);
            if (local >= 1.0f) {
                continue;
            }

            double travel = easeOut(local) * radius * (1.0 - i * 0.13);
            float fade = (1.0f - local) * (1.0f - i * 0.22f);
            if (fade <= 0.0f || travel <= 0.0) {
                continue;
            }
            // The band thins as it travels, the way a real wavefront loses energy spreading out.
            double half = thickness * 0.5 * (1.0 - local * 0.45);
            EffectRenderer.groundRing(x, y, z, Math.max(0.0, travel - half), travel + half,
                    rgb, fade);
        }

        EffectRenderer.seeThrough(false);
    }

    private static float easeOut(float t) {
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }
}
