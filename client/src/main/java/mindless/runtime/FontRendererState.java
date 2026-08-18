package mindless.runtime;

import mindless.module.ModuleManager;
import mindless.module.impl.other.NameHider;
import mindless.module.impl.render.AntiShuffle;

/** State and pure helpers that must not be copied onto FontRenderer by JVMTI. */
public final class FontRendererState {
    private static final ThreadLocal<Boolean> REENTRANT = new ThreadLocal<>();

    private FontRendererState() {}

    public static boolean isReentrant() {
        return Boolean.TRUE.equals(REENTRANT.get());
    }

    public static void enter() {
        REENTRANT.set(Boolean.TRUE);
    }

    public static void exit() {
        REENTRANT.remove();
    }

    public static String rewrite(String string) {
        if (string == null) return null;
        if (ModuleManager.nameHider != null && ModuleManager.nameHider.isEnabled()) {
            string = NameHider.getFakeName(string);
        }
        if (ModuleManager.antiShuffle != null && ModuleManager.antiShuffle.isEnabled()) {
            string = AntiShuffle.removeObfuscation(string);
        }
        return string;
    }
}

