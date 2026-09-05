package dev.authsys.model;

/**
 * Session validation result from the software endpoint.
 */
public class SessionInfo {

    private final boolean valid;
    private final String username;
    private final String expiresAt;

    public SessionInfo(boolean valid, String username, String expiresAt) {
        this.valid = valid;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    /** Whether the session is currently active. */
    public boolean isValid() {
        return valid;
    }

    /** Username associated with this session. */
    public String getUsername() {
        return username;
    }

    /** ISO 8601 expiry timestamp. */
    public String getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "SessionInfo{valid=" + valid + ", username='" + username + "', expiresAt='" + expiresAt + "'}";
    }
}
