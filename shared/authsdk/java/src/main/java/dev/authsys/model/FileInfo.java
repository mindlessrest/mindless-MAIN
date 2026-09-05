package dev.authsys.model;

/**
 * File metadata returned by admin file listing.
 */
public class FileInfo {

    private final String id;
    private final String name;
    private final boolean isPublic;
    private final boolean encrypted;
    private final String uploadedBy;
    private final String createdAt;

    public FileInfo(String id, String name, boolean isPublic, boolean encrypted,
                    String uploadedBy, String createdAt) {
        this.id = id;
        this.name = name;
        this.isPublic = isPublic;
        this.encrypted = encrypted;
        this.uploadedBy = uploadedBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public boolean isPublic() { return isPublic; }
    public boolean isEncrypted() { return encrypted; }
    public String getUploadedBy() { return uploadedBy; }
    public String getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "FileInfo{id='" + id + "', name='" + name + "'}";
    }
}
