package mindless.runtime;

import com.google.common.util.concurrent.ListenableFuture;
import mindless.Raven;
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
 * Entry point invoked by RavenNative.dll after the injection JAR is added
 * to the LaunchClassLoader. Bypasses the Forge @Mod lifecycle: registers the
 * mixin configuration and drives Raven.init(null) manually.
 *
 * Any failure inside Raven initialization is expanded (unwrap InvocationTarget /
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

    public static synchronized void start() {
        BootstrapState currentState = STATE.get();
        if (currentState != BootstrapState.NOT_STARTED) {
            if (currentState == BootstrapState.FAILED) {
                throw new IllegalStateException(
                        "Raven bootstrap already failed and cannot be retried safely",
                        bootstrapFailure);
            }
            log("start() ignored: bootstrap is already " + currentState.name().toLowerCase());
            return;
        }
        STATE.set(BootstrapState.STARTING);
        ProgressPipe.connect();
        ProgressPipe.report(0.55f, "Initializing bootstrap");
        try {
            ProgressPipe.report(0.57f, "Loading transformer registry");
            RavenTransformerManager manager = RavenTransformerManager.get();
            ProgressPipe.report(0.62f, "Asserting transformer integrity");
            manager.assertNoTransformFailures();
            ProgressPipe.report(0.65f, "Transformers ready");
            log("RavenTransformerManager instantiated ("
                    + manager.registeredCount()
                    + " targets)");
        } catch (Throwable managerFailure) {
            bootstrapFailure = managerFailure;
            STATE.set(BootstrapState.FAILED);
            reportFailure("RavenTransformerManager init", managerFailure);
            throw new RuntimeException(
                    "Raven transformer initialization failed (see raven-native-java.log)",
                    managerFailure);
        }
        try {
            ProgressPipe.report(0.68f, "Scheduling client thread initialization");
            ProgressPipe.report(0.70f, "Starting modules");
            if (!driveModInitOnClientThread()) {
                // The client-thread task claimed permission to run before the
                // timeout. It owns the terminal COMPLETE/FAILED transition and
                // startup remains accepted so native does not tear down hooks
                // beneath a partially executing Raven.init.
                return;
            }
        } catch (Throwable initFailure) {
            bootstrapFailure = initFailure;
            STATE.set(BootstrapState.FAILED);
            reportFailure("Raven init", initFailure);
            throw new RuntimeException("Raven init failed (see raven-native-java.log)",
                    initFailure);
        }
        STATE.set(BootstrapState.COMPLETE);
        ProgressPipe.report(1.0f, "Ready");
        ProgressPipe.close();
        log("Raven bootstrap complete");
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
                        log("Raven bootstrap completed after the client-thread wait timed out");
                    }
                } catch (Throwable failure) {
                    initFailure.set(failure);
                    bootstrapFailure = failure;
                    BootstrapState previous = STATE.getAndSet(BootstrapState.FAILED);
                    if (previous == BootstrapState.TIMED_OUT) {
                        reportFailure("Raven init after client-thread timeout", failure);
                    }
                }
            }
        });

        try {
            scheduled.get(30L, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            if (runPermission.compareAndSet(true, false)) {
                // Timeout won before the Runnable entered Raven.init. Cancel the
                // queue entry; even if FutureTask is between dispatch steps, the
                // gate makes its body a no-op.
                scheduled.cancel(false);
                throw new IllegalStateException(
                        "Timed out before Raven initialization began on the client thread",
                        timeout);
            }

            if (STATE.compareAndSet(BootstrapState.STARTING, BootstrapState.TIMED_OUT)) {
                log("Raven initialization is still running on the client thread; "
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
                        "Raven initialization failed while the client-thread wait timed out",
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
        ProgressPipe.report(0.72f, "Preparing module context");
        ProgressPipe.report(0.75f, "Loading modules");
        Raven mod = new Raven();
        ProgressPipe.report(0.80f, "Instantiated mod container");
        ProgressPipe.report(0.83f, "Applying patches");
        Method init = Raven.class.getDeclaredMethod(
                "init", net.minecraftforge.fml.common.event.FMLInitializationEvent.class);
        init.setAccessible(true);
        try {
            ProgressPipe.report(0.88f, "Running mod initialization");
            init.invoke(mod, (Object) null);
        } catch (InvocationTargetException wrapper) {
            Throwable cause = wrapper.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw wrapper;
        }
        ProgressPipe.report(0.92f, "Mod initialized");
        ProgressPipe.report(0.95f, "Finishing up");
        log("Raven.init(null) invoked on " + Thread.currentThread().getName());
    }

    private static void reportFailure(String phase, Throwable error) {
        StringBuilder rendered = new StringBuilder();
        rendered.append("[RavenNative] ").append(phase).append(" failed:\n");
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
            File logDir = new File(tempDir, "RavenNative");
            logDir.mkdirs();
            File logFile = new File(logDir, "raven-native-java.log");
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
        System.out.println("[RavenNative] " + message);
        System.out.flush();
    }
}
