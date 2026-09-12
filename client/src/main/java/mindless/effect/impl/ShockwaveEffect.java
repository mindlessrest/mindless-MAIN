package mindless.effect.impl;

import mindless.effect.Effect;
import mindless.effect.EffectRenderer;

/**
 * A ring that expands at the point of impact rather than on the floor beneath it.
 *
 * Turned to face the viewer, so it reads the same from any angle and does not care what the ground
 * under the target looks like. That is the trade against the ripple: it survives stairs, slabs and
 * mid-air hits, and it gives up the sense of something landing.
 */
public final class ShockwaveEffect extends Effect {

    private final double radius;
    private final double thickness;
    private final int rgb;
    private final boolean seeThrough;

    public ShockwaveEffect(double x, double y, double z, int durationTicks,
                           double radius, double thickness, int rgb, boolean seeThrough) {
        super(x, y, z, durationTicks);
        this.radius = radius;
        this.thickness = thickness;
        this.rgb = rgb;
        this.seeThrough = seeThrough;
    }

    @Override
    public void render(float partialTicks) {
        float life = progress(partialTicks);
        if (life >= 1.0f) {
            return;
        }
        EffectRenderer.seeThrough(seeThrough);

        float inv = 1.0f - life;
        double travel = (1.0 - inv * inv * inv) * radius;
        double half = thickness * 0.5 * (1.0 - life * 0.5);
        EffectRenderer.facingRing(x, y, z, Math.max(0.0, travel - half), travel + half, rgb, inv);

        // A second, tighter ring just inside the first gives the edge somewhere to fall off to,
        // which stops a fast wave reading as a single hard hoop.
        double innerTravel = travel * 0.82;
        double innerHalf = half * 0.6;
        EffectRenderer.facingRing(x, y, z, Math.max(0.0, innerTravel - innerHalf),
                innerTravel + innerHalf, 0xFFFFFF, inv * 0.35f);

        EffectRenderer.seeThrough(false);
    }
}
