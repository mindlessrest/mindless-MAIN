package mindless.effect;

/**
 * One short-lived thing drawn in the world.
 *
 * An effect owns a position, a lifetime measured in ticks, and nothing else. It is ticked on the
 * client thread and drawn once per frame inside the state {@link EffectSystem} has already set up,
 * so a subclass never touches GL state of its own: it emits geometry and stops.
 *
 * Lifetime is in ticks rather than milliseconds because everything it can be anchored to moves on
 * the tick clock. Frames in between interpolate through partialTicks, the same way entities do,
 * or the effect visibly stutters at anything under the monitor's refresh rate.
 */
public abstract class Effect {

    protected final double x;
    protected final double y;
    protected final double z;
    protected final int durationTicks;

    private int age;

    protected Effect(double x, double y, double z, int durationTicks) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.durationTicks = Math.max(1, durationTicks);
    }

    public final void tick() {
        age++;
    }

    public final boolean expired() {
        return age >= durationTicks;
    }

    /** 0 at the moment of spawning, 1 as it expires, smooth between ticks. */
    protected final float progress(float partialTicks) {
        float p = (age + partialTicks) / (float) durationTicks;
        return p < 0.0f ? 0.0f : p > 1.0f ? 1.0f : p;
    }

    /** Age in ticks including the fraction of the current one. */
    protected final float elapsed(float partialTicks) {
        return age + partialTicks;
    }

    public abstract void render(float partialTicks);
}
