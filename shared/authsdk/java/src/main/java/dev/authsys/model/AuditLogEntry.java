package dev.authsys.model;

import java.util.Map;

/**
 * Single entry from the admin audit log.
 */
public class AuditLogEntry {

    private final String id;
    private final String action;
    private final String actorId;
    private final String targetId;
    private final String ip;
    private final Map<String, Object> metadata;
    private final String createdAt;

    public AuditLogEntry(String id, String action, String actorId, String targetId,
                         String ip, Map<String, Object> metadata, String createdAt) {
        this.id = id;
        this.action = action;
        this.actorId = actorId;
        this.targetId = targetId;
        this.ip = ip;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getAction() { return action; }
    public String getActorId() { return actorId; }
    public String getTargetId() { return targetId; }
    public String getIp() { return ip; }
    public Map<String, Object> getMetadata() { return metadata; }
    public String getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "AuditLogEntry{action='" + action + "', actorId='" + actorId + "', createdAt='" + createdAt + "'}";
    }
}
