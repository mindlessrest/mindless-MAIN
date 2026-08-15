package keystrokesmod.runtime;

import net.minecraft.client.entity.EntityPlayerSP;

/** Per-render state kept outside RenderManager so retransformation changes no schema. */
public final class RenderManagerState {
    private static final ThreadLocal<Float> CACHED_PITCH = new ThreadLocal<>();
    private static final ThreadLocal<Float> CACHED_PREV_PITCH = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> CAPTURED = new ThreadLocal<>();

    private RenderManagerState() {}

    public static void capture(EntityPlayerSP player) {
        CACHED_PITCH.set(player.rotationPitch);
        CACHED_PREV_PITCH.set(player.prevRotationPitch);
        CAPTURED.set(Boolean.TRUE);
    }

    public static boolean hasCapturedState() {
        return Boolean.TRUE.equals(CAPTURED.get());
    }

    public static void restore(EntityPlayerSP player) {
        if (!hasCapturedState()) return;
        Float cached = CACHED_PITCH.get();
        Float cachedPrev = CACHED_PREV_PITCH.get();
        if (cached != null) player.rotationPitch = cached;
        if (cachedPrev != null) player.prevRotationPitch = cachedPrev;
        CACHED_PITCH.remove();
        CACHED_PREV_PITCH.remove();
        CAPTURED.remove();
    }
}
