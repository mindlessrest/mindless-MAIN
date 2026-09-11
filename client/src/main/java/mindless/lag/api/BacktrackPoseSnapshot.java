package mindless.lag.api;

public final class BacktrackPoseSnapshot {
    public static final BacktrackPoseSnapshot EMPTY = new BacktrackPoseSnapshot(
            new SessionEpoch(0L, 0L), false, false, false, Integer.MIN_VALUE,
            0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D,
            0.6D, 1.8D, 0L
    );

    private final SessionEpoch epoch;
    private final boolean pending;
    private final boolean active;
    private final boolean delayedUseful;
    private final int entityId;
    private final double displayedX;
    private final double displayedY;
    private final double displayedZ;
    private final double shadowX;
    private final double shadowY;
    private final double shadowZ;
    private final double width;
    private final double height;
    private final long publishedAtNanos;

    public BacktrackPoseSnapshot(
            SessionEpoch epoch,
            boolean pending,
            boolean active,
            boolean delayedUseful,
            int entityId,
            double displayedX,
            double displayedY,
            double displayedZ,
            double shadowX,
            double shadowY,
            double shadowZ,
            double width,
            double height,
            long publishedAtNanos
    ) {
        this.epoch = epoch == null ? new SessionEpoch(0L, 0L) : epoch;
        this.pending = pending;
        this.active = active;
        this.delayedUseful = delayedUseful;
        this.entityId = entityId;
        this.displayedX = displayedX;
        this.displayedY = displayedY;
        this.displayedZ = displayedZ;
        this.shadowX = shadowX;
        this.shadowY = shadowY;
        this.shadowZ = shadowZ;
        this.width = width;
        this.height = height;
        this.publishedAtNanos = publishedAtNanos;
    }

    public SessionEpoch getEpoch() { return epoch; }
    public boolean isPending() { return pending; }
    public boolean isActive() { return active; }
    public boolean isDelayedUseful() { return delayedUseful; }
    public boolean hasShadowPosition() { return pending || active || delayedUseful; }
    public int getEntityId() { return entityId; }
    public double getDisplayedX() { return displayedX; }
    public double getDisplayedY() { return displayedY; }
    public double getDisplayedZ() { return displayedZ; }
    public double getShadowX() { return shadowX; }
    public double getShadowY() { return shadowY; }
    public double getShadowZ() { return shadowZ; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public long getPublishedAtNanos() { return publishedAtNanos; }
}
