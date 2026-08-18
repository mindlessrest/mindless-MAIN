package mindless.transformer.impl.render;

/** Cross-transformer bridge for the Weather time override. */
public final class WeatherTimeOverride {
    private WeatherTimeOverride() {}
    private static volatile boolean active;
    private static volatile long value;
    public static void set(long v) { value = v; active = true; }
    public static void clear() { active = false; }
    public static boolean isActive() { return active; }
    public static long value() { return value; }
}
