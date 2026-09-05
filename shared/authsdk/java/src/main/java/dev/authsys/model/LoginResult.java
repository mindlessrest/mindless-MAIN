package dev.authsys.model;

/**
 * Result of a successful login. Contains the session token and its expiry.
 */
public class LoginResult {

    private final String token;
    private final String expiresAt;

    public LoginResult(String token, String expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    /** Opaque 64-char hex session token. Use as Bearer token for authenticated requests. */
    public String getToken() {
        return token;
    }

    /** ISO 8601 timestamp when this session expires. */
    public String getExpiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "LoginResult{expiresAt='" + expiresAt + "'}";
    }
}
