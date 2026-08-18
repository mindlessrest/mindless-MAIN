package mindless.runtime;

/**
 * JNI landing point. RavenNative.dll installs a JVMTI ClassFileLoadHook that
 * routes every retransform through {@link #transform(String, byte[])}. Kept
 * separate from RavenTransformerManager so the native side has a stable,
 * argument-free signature to look up.
 *
 * Return convention:
 *   null / same-length identical array => JVMTI keeps the original bytecode
 *   non-null different bytes           => new definition applied
 */
public final class TransformerHooks {
    private TransformerHooks() {}

    /** Invoked from C. Never throws — logs and returns null on failure. */
    public static byte[] transform(String internalName, byte[] originalBytes) {
        try {
            return RavenTransformerManager.get().transform(internalName, originalBytes);
        } catch (Throwable failure) {
            System.err.println("[TransformerHooks] transform threw for "
                    + internalName + ": " + failure);
            failure.printStackTrace();
            return null;
        }
    }

    /** Invoked from C for coarse progress logging. */
    public static void log(String message) {
        System.out.println("[TransformerHooks] " + message);
    }
}
