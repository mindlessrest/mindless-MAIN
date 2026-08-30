package mindless.runtime;

import com.google.common.util.concurrent.ListenableFuture;
import mindless.Mindless;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Entry point invoked by MindlessNative.dll after the injection JAR is added
 * to the LaunchClassLoader. Bypasses the Forge @Mod lifecycle: registers the
 * mixin configuration and drives Mindless.init(null) manually.
 *
 * Any failure inside Mindless initialization is expanded (unwrap InvocationTarget /
 * causes) and dumped both to System.err and to a text file next to the DLL log,
 * so the trace survives the DLL unload that happens on bootstrap failure.
 */
public final class NativeBootstrap {
    private enum BootstrapState {
        NOT_STARTED,
        STARTING,
        TIMED_OUT,
        COMPLETE,
        FAILED
    }

    private static final AtomicReference<BootstrapState> STATE =
            new AtomicReference<>(BootstrapState.NOT_STARTED);
    private static volatile Throwable bootstrapFailure;

    private NativeBootstrap() {}

    public static synchronized void resetStateForReinject() {
        STATE.set(BootstrapState.NOT_STARTED);
        bootstrapFailure = null;
    }

    public static synchronized void start() {
        BootstrapState currentState = STATE.get();
        if (currentState != BootstrapState.NOT_STARTED) {
            if (currentState == BootstrapState.FAILED) {
                STATE.set(BootstrapState.NOT_STARTED);
                bootstrapFailure = null;
            } else {
                log("start() ignored: bootstrap is already " + currentState.name().toLowerCase());
                return;
            }
        }
        STATE.set(BootstrapState.STARTING);
        ProgressPipe.connect();
        ProgressPipe.report(0.55f, "Starting Mindless");
        try {
            ProgressPipe.report(0.57f, "Loading Mindless");
            MindlessTransformerManager manager = MindlessTransformerManager.get();
            manager.setDisabled(false);
            TransformerHooks.retransformNative();
            ProgressPipe.report(0.62f, "Loading Mindless");
            manager.assertNoTransformFailures();
            ProgressPipe.report(0.65f, "Loading Mindless");
            log("MindlessTransformerManager instantiated ("
                    + manager.registeredCount()
                    + " targets)");
        } catch (Throwable managerFailure) {
            bootstrapFailure = managerFailure;
            STATE.set(BootstrapState.FAILED);
            reportFailure("MindlessTransformerManager init", managerFailure);
            throw new RuntimeException(
                    "Mindless transformer initialization failed (see mindless-native-java.log)",
                    managerFailure);
        }
        try {
            ProgressPipe.report(0.68f, "Starting modules");
            ProgressPipe.report(0.70f, "Starting modules");
            final Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft != null && minecraft.isCallingFromMinecraftThread()) {
                driveModInit();
            } else if (minecraft != null) {
                // Try scheduling on client thread first, fall back to direct if it times out
                log("Attempting client-thread init (5s timeout, then direct)");
                if (!driveModInitOnClientThreadWithFallback(minecraft)) {
                    return;
                }
            } else {
                driveModInit();
            }
        } catch (Throwable initFailure) {
            bootstrapFailure = initFailure;
            STATE.set(BootstrapState.FAILED);
            reportFailure("Mindless init", initFailure);
            throw new RuntimeException("Mindless init failed (see mindless-native-java.log)",
                    initFailure);
        }
        STATE.set(BootstrapState.COMPLETE);
        ProgressPipe.report(1.0f, "Ready");
        ProgressPipe.close();
        log("Mindless bootstrap complete");
    }

    private static boolean driveModInitOnClientThreadWithFallback(Minecraft minecraft) throws Exception {
        final AtomicReference<Throwable> initFailure = new AtomicReference<>();
        final AtomicBoolean runPermission = new AtomicBoolean(true);
        ListenableFuture<?> scheduled = minecraft.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!runPermission.compareAndSet(true, false)) return;
                try {
                    driveModInit();
                    STATE.set(BootstrapState.COMPLETE);
                } catch (Throwable failure) {
                    initFailure.set(failure);
                    bootstrapFailure = failure;
                    STATE.set(BootstrapState.FAILED);
                    reportFailure("Mindless init (client thread)", failure);
                }
            }
        });
        try {
            scheduled.get(5L, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (runPermission.compareAndSet(true, false)) {
                scheduled.cancel(false);
                log("Client thread not processing tasks — initializing directly");
                driveModInit();
            } else {
                // Task started running, wait for it
                log("Task started on client thread, waiting...");
                scheduled.get(30L, TimeUnit.SECONDS);
            }
        }
        Throwable failure = initFailure.get();
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
        return true;
    }

    private static boolean driveModInitOnClientThread() throws Exception {
        final Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            throw new IllegalStateException("Minecraft client is not available");
        }
        if (minecraft.isCallingFromMinecraftThread()) {
            driveModInit();
            return true;
        }

        final AtomicReference<Throwable> initFailure = new AtomicReference<>();
        final AtomicBoolean runPermission = new AtomicBoolean(true);
        ListenableFuture<?> scheduled = minecraft.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (!runPermission.compareAndSet(true, false)) return;
                try {
                    driveModInit();
                    BootstrapState previous = STATE.getAndSet(BootstrapState.COMPLETE);
                    if (previous == BootstrapState.TIMED_OUT) {
                        log("Mindless bootstrap completed after the client-thread wait timed out");
                    }
                } catch (Throwable failure) {
                    initFailure.set(failure);
                    bootstrapFailure = failure;
                    BootstrapState previous = STATE.getAndSet(BootstrapState.FAILED);
                    if (previous == BootstrapState.TIMED_OUT) {
                        reportFailure("Mindless init after client-thread timeout", failure);
                    }
                }
            }
        });

        log("driveModInitOnClientThread: waiting for client thread (30s timeout)");
        try {
            scheduled.get(30L, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            log("driveModInitOnClientThread: TIMED OUT waiting for client thread");
            if (runPermission.compareAndSet(true, false)) {
                // Timeout won before the Runnable entered Mindless.init. Cancel the
                // queue entry; even if FutureTask is between dispatch steps, the
                // gate makes its body a no-op.
                scheduled.cancel(false);
                throw new IllegalStateException(
                        "Timed out before Mindless initialization began on the client thread",
                        timeout);
            }

            if (STATE.compareAndSet(BootstrapState.STARTING, BootstrapState.TIMED_OUT)) {
                log("Mindless initialization is still running on the client thread; "
                        + "bootstrap will complete asynchronously");
                return false;
            }
            BootstrapState terminalState = STATE.get();
            if (terminalState == BootstrapState.COMPLETE) return true;
            Throwable lateFailure = initFailure.get();
            if (lateFailure instanceof Exception) throw (Exception) lateFailure;
            if (lateFailure instanceof Error) throw (Error) lateFailure;
            if (lateFailure != null) throw new RuntimeException(lateFailure);
            if (terminalState == BootstrapState.FAILED) {
                throw new IllegalStateException(
                        "Mindless initialization failed while the client-thread wait timed out",
                        bootstrapFailure);
            }
            return false;
        }

        Throwable failure = initFailure.get();
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new RuntimeException(failure);
        return true;
    }

    private static void driveModInit() throws Exception {
        ProgressPipe.report(0.72f, "Starting modules");
        log("driveModInit: begin on thread " + Thread.currentThread().getName());
        ProgressPipe.report(0.75f, "Loading modules");
        if (Mindless.isUnloaded()) {
            log("Re-initializing Mindless via loader re-injection");
            Mindless.reinject();
        } else {
            log("driveModInit: constructing Mindless");
            Mindless mod = new Mindless();
            log("driveModInit: Mindless constructed");
            ProgressPipe.report(0.80f, "Loading modules");
            ProgressPipe.report(0.83f, "Applying patches");
            Method init = Mindless.class.getDeclaredMethod(
                    "init", net.minecraftforge.fml.common.event.FMLInitializationEvent.class);
            init.setAccessible(true);
            try {
                log("driveModInit: invoking Mindless.init");
                ProgressPipe.report(0.88f, "Almost there");
                init.invoke(mod, (Object) null);
                log("driveModInit: Mindless.init returned");
            } catch (InvocationTargetException wrapper) {
                Throwable cause = wrapper.getCause();
                log("driveModInit: Mindless.init THREW: " + cause);
                reportFailure("Mindless.init", cause != null ? cause : wrapper);
                if (cause instanceof Exception) throw (Exception) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw wrapper;
            }
        }
        ProgressPipe.report(0.92f, "Almost there");
        ProgressPipe.report(0.95f, "Finishing up");
        log("Mindless.init(null) completed on " + Thread.currentThread().getName());
    }

    private static void reportFailure(String phase, Throwable error) {
        StringBuilder rendered = new StringBuilder();
        rendered.append("[MindlessNative] ").append(phase).append(" failed:\n");
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 16) {
            StringWriter buffer = new StringWriter();
            current.printStackTrace(new PrintWriter(buffer));
            rendered.append(depth == 0 ? "== root ==\n" : "== caused by ==\n");
            rendered.append(buffer);
            current = current.getCause();
            ++depth;
        }
        String text = rendered.toString();
        System.err.println(text);
        System.err.flush();
        System.out.println(text);
        System.out.flush();
        writeToLog(text);
    }

    private static void writeToLog(String text) {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            File logDir = new File(tempDir, "MindlessNative");
            logDir.mkdirs();
            File logFile = new File(logDir, "mindless-native-java.log");
            try (PrintWriter writer = new PrintWriter(
                    new java.io.FileOutputStream(logFile, true), true)) {
                writer.println("--- " + new java.util.Date() + " ---");
                writer.println(text);
            }
        } catch (Throwable ignored) {
            // last-resort logging path: swallow to avoid masking the original error
        }
    }

    private static void log(String message) {
        System.out.println("[MindlessNative] " + message);
        System.out.flush();
    }
}
