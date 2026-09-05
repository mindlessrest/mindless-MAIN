package dev.authsys.model;

/**
 * Response from the software ping endpoint. Used for health checks and clock sync.
 */
public class PingResult {

    private final boolean ok;
    private final String serverTime;

    public PingResult(boolean ok, String serverTime) {
        this.ok = ok;
        this.serverTime = serverTime;
    }

    /** Whether the server is healthy. */
    public boolean isOk() {
        return ok;
    }

    /** Server's current time as ISO 8601 string. Use for clock skew correction. */
    public String getServerTime() {
        return serverTime;
    }

    @Override
    public String toString() {
        return "PingResult{ok=" + ok + ", serverTime='" + serverTime + "'}";
    }
}
