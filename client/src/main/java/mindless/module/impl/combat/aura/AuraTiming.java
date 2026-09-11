package mindless.module.impl.combat.aura;

public final class AuraTiming {
    public interface ApsSampler {
        int sample(int minimum, int maximum);
    }

    private AuraTiming() {
    }

    public static long decrement(long value) {
        return value > 0L ? value - 50L : value;
    }

    public static long addAttackDelay(long cooldown, int minimumAps, int maximumAps,
                                      ApsSampler sampler) {
        int minimum = Math.max(1, Math.min(20, minimumAps));
        int maximum = Math.max(minimum, Math.min(20, maximumAps));
        int aps = sampler == null ? minimum : sampler.sample(minimum, maximum);
        aps = Math.max(minimum, Math.min(maximum, aps));
        return cooldown + 1000L / aps;
    }

    public static int sampleInclusive(int minimum, int maximum, double unit) {
        int low = Math.min(minimum, maximum);
        int high = Math.max(minimum, maximum);
        if (high == low) {
            return low;
        }
        return low + (int) Math.floor(Math.max(0.0, Math.min(0.999999999, unit)) * (high - low + 1));
    }
}
