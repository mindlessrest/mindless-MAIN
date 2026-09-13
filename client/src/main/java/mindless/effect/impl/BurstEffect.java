package mindless.effect.impl;

import mindless.effect.Effect;
import mindless.effect.EffectRenderer;

import java.util.Random;

/**
 * A handful of sparks thrown outward and pulled back down.
 *
 * Positions are not stepped every tick. Each spark keeps the velocity it was born with and its
 * position is solved from the elapsed time, so a frame drawn between ticks lands exactly where it
 * should and the whole burst can be replayed from four numbers instead of a list that has to be
 * integrated in lockstep with the tick loop.
 */
public final class BurstEffect extends Effect {

    private static final double GRAVITY = 0.055;

    private final float[] vx;
    private final float[] vy;
    private final float[] vz;
    private final double size;
    private final int rgb;
    private final boolean gravity;
    private final boolean seeThrough;

    public BurstEffect(double x, double y, double z, int durationTicks,
                       int count, double speed, double spread, double size,
                       int rgb, boolean gravity, boolean seeThrough, Random random) {
        super(x, y, z, durationTicks);
        int n = Math.max(1, Math.min(160, count));
        this.vx = new float[n];
        this.vy = new float[n];
        this.vz = new float[n];
        this.size = size;
        this.rgb = rgb;
        this.gravity = gravity;
        this.seeThrough = seeThrough;

        for (int i = 0; i < n; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double horizontal = (0.45 + random.nextDouble() * 0.55) * speed;
            vx[i] = (float) (Math.cos(angle) * horizontal);
            vz[i] = (float) (Math.sin(angle) * horizontal);
            vy[i] = (float) ((random.nextDouble() * spread) * speed);
        }
    }

    @Override
    public void render(float partialTicks) {
        float life = progress(partialTicks);
        if (life >= 1.0f) {
            return;
        }
        float t = elapsed(partialTicks);
        float alpha = 1.0f - life * life;

        EffectRenderer.seeThrough(seeThrough);
        EffectRenderer.beginSparks();
        for (int i = 0; i < vx.length; i++) {
            double px = x + vx[i] * t;
            double pz = z + vz[i] * t;
            double py = y + vy[i] * t - (gravity ? GRAVITY * t * t : 0.0);
            if (gravity && py < y) {
                py = y;
            }
            EffectRenderer.spark(px, py, pz, size, rgb, alpha);
        }
        EffectRenderer.endSparks();
        EffectRenderer.seeThrough(false);
    }
}
