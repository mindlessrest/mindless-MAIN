package dev.authsys;

import dev.authsys.model.SessionInfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Background heartbeat that periodically validates the session via
 * {@code GET /software/session-validate}. Runs as a daemon thread.
 *
 * <p>Behavior:
 * <ul>
 *   <li>On success (200): resets failure counter, fires onSuccess callback.</li>
 *   <li>On 401/403: session is dead — fires onSessionInvalid immediately, stops.</li>
 *   <li>On network error or 5xx: increments failure counter. After maxFailures consecutive
 *       failures, fires onSessionInvalid and stops. Otherwise retries after retryDelaySeconds.</li>
 * </ul>
 */
public class Heartbeat {

    private final AuthClient client;
    private final HeartbeatConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger failureCount = new AtomicInteger(0);

    private Consumer<String> onInvalid;
    private Runnable onSuccess;
    private Thread heartbeatThread;

    /**
     * @param client an AuthClient with a valid session token already set
     * @param config heartbeat timing and retry configuration
     */
    public Heartbeat(AuthClient client, HeartbeatConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Called when the session is determined to be invalid. Receives a reason string. */
    public void setOnInvalid(Consumer<String> callback) {
        this.onInvalid = callback;
    }

    /** Called on each successful heartbeat. */
    public void setOnSuccess(Runnable callback) {
        this.onSuccess = callback;
    }

    /** Starts the heartbeat loop in a background daemon thread. */
    public void start() {
        if (running.getAndSet(true)) {
            return; // Already running
        }

        heartbeatThread = new Thread(this::heartbeatLoop, "authsys-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /** Signals the heartbeat thread to stop and waits for it to exit. */
    public void stop() {
        running.set(false);
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            try {
                heartbeatThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Returns true if the heartbeat loop is currently active. */
    public boolean isRunning() {
        return running.get();
    }

    private void heartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(config.getIntervalSeconds() * 1000L);
            } catch (InterruptedException e) {
                // stop() was called
                break;
            }

            if (!running.get()) break;

            try {
                SessionInfo info = client.validateSession();

                if (!info.isValid()) {
                    fireInvalid("session reported as invalid by server");
                    return;
                }

                // Success — reset failure counter
                failureCount.set(0);
                if (onSuccess != null) {
                    onSuccess.run();
                }

            } catch (AuthException e) {
                String code = e.getCode();

                // 401 or 403 means the session is definitively dead or banned
                if ("UNAUTHORIZED".equals(code) || "INVALID_CREDENTIALS".equals(code)
                        || "FORBIDDEN".equals(code) || "BANNED".equals(code)
                        || "HWID_MISMATCH".equals(code) || "IP_MISMATCH".equals(code)) {
                    fireInvalid(code + ": " + e.getMessage());
                    return;
                }

                // Transient failure (network error, 5xx, etc.)
                int failures = failureCount.incrementAndGet();
                if (failures >= config.getMaxFailures()) {
                    fireInvalid("max consecutive failures reached (" + failures + ")");
                    return;
                }

                // Wait before retrying
                try {
                    Thread.sleep(config.getRetryDelaySeconds() * 1000L);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        running.set(false);
    }

    private void fireInvalid(String reason) {
        running.set(false);
        if (onInvalid != null) {
            onInvalid.accept(reason);
        }
    }
}
