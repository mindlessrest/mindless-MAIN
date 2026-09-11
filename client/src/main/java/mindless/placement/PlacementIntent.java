package mindless.placement;

final class PlacementIntent {
    private final Object owner;
    private final PlacementCoordinator.Priority priority;
    private final Object player;
    private final Object world;
    private final long expiresAtTick;
    private final long sequence;

    PlacementIntent(Object owner, PlacementCoordinator.Priority priority, Object player, Object world,
                    long expiresAtTick, long sequence) {
        this.owner = owner;
        this.priority = priority;
        this.player = player;
        this.world = world;
        this.expiresAtTick = expiresAtTick;
        this.sequence = sequence;
    }

    Object getOwner() {
        return owner;
    }

    Object getPlayer() {
        return player;
    }

    Object getWorld() {
        return world;
    }

    PlacementCoordinator.Priority getPriority() {
        return priority;
    }

    long getExpiresAtTick() {
        return expiresAtTick;
    }

    boolean matches(PlacementContext context) {
        return context != null && player == context.getPlayer() && world == context.getWorld();
    }

    boolean precedes(PlacementIntent other) {
        return priority.value() > other.priority.value()
                || priority.value() == other.priority.value() && sequence < other.sequence;
    }
}
