package mindless.placement;

public final class PlacementContext {
    private final Object player;
    private final Object world;
    private final long tick;

    public PlacementContext(Object player, Object world, long tick) {
        this.player = player;
        this.world = world;
        this.tick = tick;
    }

    public Object getPlayer() {
        return player;
    }

    public Object getWorld() {
        return world;
    }

    public long getTick() {
        return tick;
    }

    public boolean isValid() {
        return player != null && world != null;
    }

    public boolean sameIdentity(PlacementContext other) {
        return other != null && player == other.player && world == other.world;
    }
}
