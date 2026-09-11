package mindless.lag.api;

public final class BacktrackControlSnapshot {
    public static final BacktrackControlSnapshot EMPTY = disabled();
    private final boolean enabled;
    private final SessionEpoch epoch;
    private final boolean targetEligible;
    private final boolean flushOnDamage;
    private final boolean flushOnTargetHit;
    private final boolean debugLogging;
    private final boolean hasServerPosition;
    private final int serverX;
    private final int serverY;
    private final int serverZ;
    private final int localEntityId;
    private final int targetEntityId;
    private final long attackAtNanos;
    private final long attackWindowNanos;
    private final long cooldownNanos;
    private final long minDelayNanos;
    private final long maxDelayNanos;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final double displayedX;
    private final double displayedY;
    private final double displayedZ;
    private final double width;
    private final double height;
    private final double minDistance;
    private final double maxDistance;
    private final double startEpsilon;
    private final double continueEpsilon;

    private BacktrackControlSnapshot(Builder builder) {
        this.enabled = builder.enabled;
        this.epoch = builder.epoch;
        this.targetEligible = builder.targetEligible;
        this.flushOnDamage = builder.flushOnDamage;
        this.flushOnTargetHit = builder.flushOnTargetHit;
        this.debugLogging = builder.debugLogging;
        this.hasServerPosition = builder.hasServerPosition;
        this.serverX = builder.serverX;
        this.serverY = builder.serverY;
        this.serverZ = builder.serverZ;
        this.localEntityId = builder.localEntityId;
        this.targetEntityId = builder.targetEntityId;
        this.attackAtNanos = builder.attackAtNanos;
        this.attackWindowNanos = builder.attackWindowNanos;
        this.cooldownNanos = builder.cooldownNanos;
        this.minDelayNanos = builder.minDelayNanos;
        this.maxDelayNanos = builder.maxDelayNanos;
        this.eyeX = builder.eyeX;
        this.eyeY = builder.eyeY;
        this.eyeZ = builder.eyeZ;
        this.displayedX = builder.displayedX;
        this.displayedY = builder.displayedY;
        this.displayedZ = builder.displayedZ;
        this.width = builder.width;
        this.height = builder.height;
        this.minDistance = builder.minDistance;
        this.maxDistance = builder.maxDistance;
        this.startEpsilon = builder.startEpsilon;
        this.continueEpsilon = builder.continueEpsilon;
    }

    public static BacktrackControlSnapshot disabled() {
        return builder().enabled(false).targetEligible(false).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public SessionEpoch getEpoch() { return epoch; }
    public boolean isEnabled() { return enabled; }
    public boolean isTargetEligible() { return targetEligible; }
    public boolean isDebugLogging() { return debugLogging; }
    public boolean isFlushOnTargetHit() { return flushOnTargetHit; }
    public boolean hasServerPosition() { return hasServerPosition; }
    public int getServerX() { return serverX; }
    public int getServerY() { return serverY; }
    public int getServerZ() { return serverZ; }
    public boolean isFlushOnDamage() { return flushOnDamage; }
    public int getLocalEntityId() { return localEntityId; }
    public int getTargetEntityId() { return targetEntityId; }
    public long getAttackAtNanos() { return attackAtNanos; }
    public long getAttackWindowNanos() { return attackWindowNanos; }
    public long getCooldownNanos() { return cooldownNanos; }
    public long getMinDelayNanos() { return minDelayNanos; }
    public long getMaxDelayNanos() { return maxDelayNanos; }
    public double getEyeX() { return eyeX; }
    public double getEyeY() { return eyeY; }
    public double getEyeZ() { return eyeZ; }
    public double getDisplayedX() { return displayedX; }
    public double getDisplayedY() { return displayedY; }
    public double getDisplayedZ() { return displayedZ; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public double getMinDistance() { return minDistance; }
    public double getMaxDistance() { return maxDistance; }
    public double getStartEpsilon() { return startEpsilon; }
    public double getContinueEpsilon() { return continueEpsilon; }

    public static final class Builder {
        private boolean enabled;
        private SessionEpoch epoch;
        private boolean targetEligible;
        private boolean flushOnDamage = true;
        private boolean flushOnTargetHit;
        private boolean debugLogging;
        private boolean hasServerPosition;
        private int serverX;
        private int serverY;
        private int serverZ;
        private int localEntityId = Integer.MIN_VALUE;
        private int targetEntityId = Integer.MIN_VALUE;
        private long attackAtNanos = Long.MIN_VALUE;
        private long attackWindowNanos;
        private long cooldownNanos;
        private long minDelayNanos;
        private long maxDelayNanos;
        private double eyeX;
        private double eyeY;
        private double eyeZ;
        private double displayedX;
        private double displayedY;
        private double displayedZ;
        private double width = 0.6D;
        private double height = 1.8D;
        private double minDistance;
        private double maxDistance = Double.MAX_VALUE;
        private double startEpsilon = 0.025D;
        private double continueEpsilon = 0.01D;

        public Builder epoch(SessionEpoch value) { epoch = value; return this; }
        public Builder enabled(boolean value) { enabled = value; return this; }
        public Builder targetEligible(boolean value) { targetEligible = value; return this; }
        public Builder debugLogging(boolean value) { debugLogging = value; return this; }
        public Builder flushOnTargetHit(boolean value) { flushOnTargetHit = value; return this; }
        public Builder serverPosition(int x, int y, int z) { hasServerPosition = true; serverX = x; serverY = y; serverZ = z; return this; }
        public Builder flushOnDamage(boolean value) { flushOnDamage = value; return this; }
        public Builder localEntityId(int value) { localEntityId = value; return this; }
        public Builder targetEntityId(int value) { targetEntityId = value; return this; }
        public Builder attackAtNanos(long value) { attackAtNanos = value; return this; }
        public Builder attackWindowNanos(long value) { attackWindowNanos = Math.max(0L, value); return this; }
        public Builder cooldownNanos(long value) { cooldownNanos = Math.max(0L, value); return this; }
        public Builder minDelayNanos(long value) { minDelayNanos = Math.max(0L, value); return this; }
        public Builder maxDelayNanos(long value) { maxDelayNanos = Math.max(0L, value); return this; }
        public Builder eye(double x, double y, double z) { eyeX = x; eyeY = y; eyeZ = z; return this; }
        public Builder displayed(double x, double y, double z) { displayedX = x; displayedY = y; displayedZ = z; return this; }
        public Builder size(double valueWidth, double valueHeight) { width = valueWidth; height = valueHeight; return this; }
        public Builder distance(double min, double max) { minDistance = Math.max(0.0D, min); maxDistance = Math.max(minDistance, max); return this; }
        public Builder thresholds(double start, double cont) { startEpsilon = Math.max(0.0D, start); continueEpsilon = Math.max(0.0D, cont); return this; }
        public BacktrackControlSnapshot build() { return new BacktrackControlSnapshot(this); }
    }
}
