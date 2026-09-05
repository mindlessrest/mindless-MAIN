package dev.authsys.model;

/**
 * Public server configuration. Fetched without authentication.
 */
public class PublicConfig {

    private final String appName;
    private final boolean hwidLocking;
    private final boolean ipLocking;
    private final boolean inviteOnly;

    public PublicConfig(String appName, boolean hwidLocking, boolean ipLocking, boolean inviteOnly) {
        this.appName = appName;
        this.hwidLocking = hwidLocking;
        this.ipLocking = ipLocking;
        this.inviteOnly = inviteOnly;
    }

    /** Application display name. */
    public String getAppName() {
        return appName;
    }

    /** Whether hardware ID locking is enabled. */
    public boolean isHwidLocking() {
        return hwidLocking;
    }

    /** Whether IP address locking is enabled. */
    public boolean isIpLocking() {
        return ipLocking;
    }

    /** Whether registration requires an invite code. */
    public boolean isInviteOnly() {
        return inviteOnly;
    }

    @Override
    public String toString() {
        return "PublicConfig{appName='" + appName + "', hwidLocking=" + hwidLocking
                + ", ipLocking=" + ipLocking + ", inviteOnly=" + inviteOnly + "}";
    }
}
