package mindless.lag.api;

public final class SessionEpoch {
    private final long connectionGeneration;
    private final long worldGeneration;

    public SessionEpoch(long connectionGeneration, long worldGeneration) {
        this.connectionGeneration = connectionGeneration;
        this.worldGeneration = worldGeneration;
    }

    public long getConnectionGeneration() {
        return connectionGeneration;
    }

    public long getWorldGeneration() {
        return worldGeneration;
    }

    public boolean isSameSession(SessionEpoch other) {
        return other != null
                && connectionGeneration == other.connectionGeneration
                && worldGeneration == other.worldGeneration;
    }

    public SessionEpoch nextConnection() {
        return new SessionEpoch(connectionGeneration + 1L, worldGeneration);
    }

    public SessionEpoch nextWorld() {
        return new SessionEpoch(connectionGeneration, worldGeneration + 1L);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SessionEpoch)) return false;
        SessionEpoch that = (SessionEpoch) other;
        return connectionGeneration == that.connectionGeneration
                && worldGeneration == that.worldGeneration;
    }

    @Override
    public int hashCode() {
        long value = connectionGeneration * 31L + worldGeneration;
        return (int) (value ^ (value >>> 32));
    }

    @Override
    public String toString() {
        return connectionGeneration + ":" + worldGeneration;
    }
}
