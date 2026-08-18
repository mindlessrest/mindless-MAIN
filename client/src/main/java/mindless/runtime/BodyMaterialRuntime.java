package mindless.runtime;

import mindless.module.impl.render.BodyMaterial;
import net.minecraft.entity.EntityLivingBase;

import java.util.ArrayDeque;
import java.util.Deque;

/** Keeps per-render-call state outside already-loaded Minecraft classes. */
public final class BodyMaterialRuntime {
    private static final ThreadLocal<Deque<Boolean>> ACTIVE =
            new ThreadLocal<Deque<Boolean>>() {
                @Override protected Deque<Boolean> initialValue() { return new ArrayDeque<>(); }
            };

    private BodyMaterialRuntime() {}

    public static void begin(EntityLivingBase entity, boolean equipment) {
        ACTIVE.get().push(BodyMaterial.begin(entity, equipment));
    }

    public static void end() {
        Deque<Boolean> state = ACTIVE.get();
        BodyMaterial.end(!state.isEmpty() && state.pop());
        if (state.isEmpty()) ACTIVE.remove();
    }
}
