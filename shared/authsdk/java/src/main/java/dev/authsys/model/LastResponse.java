package dev.authsys.model;

/**
 * Stores the server's nonce and timestamp from the most recent response.
 * Callers can use this to verify server responses if needed.
 */
public class LastResponse {

    private final String nonce;
    private final long timestamp;

    public LastResponse(String nonce, long timestamp) {
        this.nonce = nonce;
        this.timestamp = timestamp;
    }

    /** Server-generated 64 hex char nonce from the last response. */
    public String getNonce() {
        return nonce;
    }

    /** Server's unix timestamp from the last response. */
    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "LastResponse{nonce='" + nonce + "', timestamp=" + timestamp + "}";
    }
}
