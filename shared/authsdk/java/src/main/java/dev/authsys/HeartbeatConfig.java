package dev.authsys;

/**
 * Configuration for the session heartbeat loop.
 */
public class HeartbeatConfig {

    private int intervalSeconds;
    private int maxFailures;
    private int retryDelaySeconds;

    /** Creates a config with default values: 60s interval, 3 max failures, 10s retry delay. */
    public HeartbeatConfig() {
        this.intervalSeconds = 60;
        this.maxFailures = 3;
        this.retryDelaySeconds = 10;
    }

    public HeartbeatConfig(int intervalSeconds, int maxFailures, int retryDelaySeconds) {
        this.intervalSeconds = intervalSeconds;
        this.maxFailures = maxFailures;
        this.retryDelaySeconds = retryDelaySeconds;
    }

    /** How often to call session-validate, in seconds. */
    public int getIntervalSeconds() { return intervalSeconds; }
    public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }

    /** Consecutive failures before giving up and firing onSessionInvalid. */
    public int getMaxFailures() { return maxFailures; }
    public void setMaxFailures(int maxFailures) { this.maxFailures = maxFailures; }

    /** Seconds to wait between retries after a transient failure. */
    public int getRetryDelaySeconds() { return retryDelaySeconds; }
    public void setRetryDelaySeconds(int retryDelaySeconds) { this.retryDelaySeconds = retryDelaySeconds; }
}
