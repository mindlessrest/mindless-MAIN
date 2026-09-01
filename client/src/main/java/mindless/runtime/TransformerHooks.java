package mindless.runtime;
public final class TransformerHooks {
    private TransformerHooks() {}
public static byte[] transform(String internalName, byte[] originalBytes) {
        try {
            return MindlessTransformerManager.get().transform(internalName, originalBytes);
        } catch (Throwable failure) {
            System.err.println("[TransformerHooks] transform threw for "
                    + internalName + ": " + failure);
            failure.printStackTrace();
            return null;
        }
    }
public static void log(String message) {
        System.out.println("[TransformerHooks] " + message);
    }

    public static boolean untransformNative() {
        return false;
    }

    public static boolean retransformNative() {
        try {
            return retransformNative0();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static native boolean untransformNative0();
    private static native boolean retransformNative0();
}
