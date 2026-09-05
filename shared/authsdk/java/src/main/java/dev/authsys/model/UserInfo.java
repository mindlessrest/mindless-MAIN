package dev.authsys.model;

/**
 * User account information. Returned by /auth/me and admin user endpoints.
 */
public class UserInfo {

    private final String id;
    private final String username;
    private final String email;
    private final boolean emailVerified;
    private final boolean isAdmin;
    private final boolean banned;
    private final String createdAt;
    private final int sessionCount;

    public UserInfo(String id, String username, String email, boolean emailVerified,
                    boolean isAdmin, boolean banned, String createdAt, int sessionCount) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.emailVerified = emailVerified;
        this.isAdmin = isAdmin;
        this.banned = banned;
        this.createdAt = createdAt;
        this.sessionCount = sessionCount;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public boolean isAdmin() { return isAdmin; }
    public boolean isBanned() { return banned; }
    public String getCreatedAt() { return createdAt; }

    /** Number of active sessions. Only populated from admin detail endpoint. */
    public int getSessionCount() { return sessionCount; }

    @Override
    public String toString() {
        return "UserInfo{id='" + id + "', username='" + username + "'}";
    }
}
