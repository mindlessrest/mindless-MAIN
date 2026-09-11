package mindless.placement;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

public final class PlacementCoordinator {
    public enum Priority {
        SCAFFOLD(100),
        HEAD_HITTER(350),
        AUTO_BLOCK_IN(300),
        BED_DEFENDER(150),
        CLUTCH(400),
        WATER_BUCKET(450);

        private final int value;

        Priority(int value) {
            this.value = value;
        }

        public int value() {
            return value;
        }
    }

    private static final PlacementCoordinator INSTANCE = new PlacementCoordinator();

    private PlacementLease activeLease;
    private Priority activePriority;
    private Object activePlayer;
    private Object activeWorld;
    private Object actionPlayer;
    private Object actionOwner;
    private PlacementLease actionLease;
    private int actionCount;
    private boolean rescueUsed;
    private long actionTick = Long.MIN_VALUE;
    private final Map<Object, PlacementIntent> intents = new IdentityHashMap<>();
    private final ThreadLocal<Object> controllerOrigin = new ThreadLocal<>();
    private PlacementContext context;
    private long nextSequence;

    private PlacementCoordinator() {
    }

    public static PlacementCoordinator get() {
        return INSTANCE;
    }

    public synchronized PlacementLease acquire(Object owner, Priority priority, Object player, Object world) {
        return acquireDirect(owner, priority, player, world);
    }

    public synchronized PlacementLease acquire(Object owner, Priority priority, Object player, Object world,
                                                long tick) {
        beginTick(player, world, tick);
        PlacementIntent requested = intents.get(owner);
        if (requested == null || requested.getExpiresAtTick() < tick
                || requested.getPlayer() != player || requested.getWorld() != world) {
            return null;
        }
        for (PlacementIntent contender : intents.values()) {
            if (contender != requested && contender.getExpiresAtTick() >= tick
                    && contender.getPlayer() == player && contender.getWorld() == world
                    && contender.precedes(requested)) {
                return null;
            }
        }
        return acquireDirect(owner, priority, player, world);
    }

    public synchronized void announce(Object owner, Priority priority, Object player, Object world,
                                      long expiresAtTick) {
        if (owner == null || priority == null || player == null || world == null) {
            return;
        }
        intents.put(owner, new PlacementIntent(owner, priority, player, world,
                expiresAtTick, nextSequence++));
    }

    public synchronized void cancel(Object owner) {
        intents.remove(owner);
        if (activeLease != null && activeLease.getOwner() == owner) {
            clearActive();
        }
    }

    private PlacementLease acquireDirect(Object owner, Priority priority, Object player, Object world) {
        if (owner == null || priority == null || player == null || world == null) {
            return null;
        }
        if (activeLease != null && (activePlayer != player || activeWorld != world)) {
            clearActive();
        }
        if (activeLease == null) {
            return open(owner, priority, player, world);
        }
        if (activeLease.getOwner() == owner) {
            return activeLease;
        }
        if (priority.value() > activePriority.value()) {
            clearActive();
            return open(owner, priority, player, world);
        }
        return null;
    }

    synchronized boolean owns(PlacementLease lease) {
        return lease != null && lease == activeLease;
    }

    synchronized boolean tryControllerAction(PlacementLease lease, long tick, PlacementLease.ControllerAction action) {
        return tryControllerAction(lease, tick, 1, action);
    }

    synchronized boolean tryControllerAction(PlacementLease lease, long tick, int limit, PlacementLease.ControllerAction action) {
        if (!owns(lease) || action == null || context == null || context.getTick() != tick
                || !isSelected(lease.getOwner(), context)) {
            return false;
        }
        if (limit < 1 || limit > (activePriority == Priority.SCAFFOLD ? 4 : 1)) {
            return false;
        }
        if (actionTick != tick) {
            actionLease = null;
            actionCount = 0;
            rescueUsed = false;
        }
        if (actionLease != null && actionLease != lease) {
            if (activePriority.value() < Priority.CLUTCH.value() || rescueUsed) return false;
            actionLease = lease;
            actionCount = 0;
        }
        if (actionCount >= limit) return false;
        actionLease = lease;
        actionCount++;
        rescueUsed |= activePriority.value() >= Priority.CLUTCH.value();
        actionPlayer = activePlayer;
        actionTick = tick;
        actionOwner = lease.getOwner();
        Object previous = controllerOrigin.get();
        controllerOrigin.set(actionOwner);
        try {
            return action.run();
        }
        finally {
            if (previous == null) {
                controllerOrigin.remove();
            }
            else {
                controllerOrigin.set(previous);
            }
        }
    }

    public synchronized boolean shouldBlockControllerAction(Object player, Object world, long tick) {
        Object owner = controllerOrigin.get();
        if (owner == null) {
            return false;
        }
        return activeLease == null || activeLease.getOwner() != owner || actionOwner != owner
                || actionPlayer != player || actionTick != tick || context == null
                || context.getWorld() != world;
    }

    public boolean isControllerAction() {
        return controllerOrigin.get() != null;
    }

    synchronized void release(PlacementLease lease) {
        if (lease == null) {
            return;
        }
        intents.remove(lease.getOwner());
        if (lease == activeLease) {
            clearActive();
        } else {
            lease.releaseInternal();
        }
    }

    public synchronized void beginTick(Object player, Object world, long tick) {
        if (player == null || world == null) {
            clear();
            return;
        }
        PlacementContext next = new PlacementContext(player, world, tick);
        if (context != null && !context.sameIdentity(next)) {
            clearActive();
            intents.clear();
            clearActions();
        }
        context = next;
        Iterator<Map.Entry<Object, PlacementIntent>> iterator = intents.entrySet().iterator();
        while (iterator.hasNext()) {
            PlacementIntent intent = iterator.next().getValue();
            if (!intent.matches(next) || intent.getExpiresAtTick() < tick) {
                iterator.remove();
            }
        }
    }

    public synchronized void clear() {
        clearActive();
        clearActions();
        intents.clear();
        context = null;
    }

    private PlacementLease open(Object owner, Priority priority, Object player, Object world) {
        activeLease = new PlacementLease(this, owner);
        activePriority = priority;
        activePlayer = player;
        activeWorld = world;
        return activeLease;
    }

    private void clearActive() {
        PlacementLease lease = activeLease;
        activeLease = null;
        activePriority = null;
        activePlayer = null;
        activeWorld = null;
        if (lease != null) {
            lease.releaseInternal();
        }
    }

    private void clearActions() {
        actionOwner = null;
        actionPlayer = null;
        actionTick = Long.MIN_VALUE;
        actionLease = null;
        actionCount = 0;
        rescueUsed = false;
    }

    private boolean isSelected(Object owner, PlacementContext current) {
        PlacementIntent requested = intents.get(owner);
        boolean activeOwner = activeLease != null && activeLease.getOwner() == owner
                && activePlayer == current.getPlayer() && activeWorld == current.getWorld();
        if ((requested == null || !requested.matches(current)
                || requested.getExpiresAtTick() < current.getTick()) && !activeOwner) {
            return false;
        }
        for (PlacementIntent contender : intents.values()) {
            if (contender == requested || !contender.matches(current)
                    || contender.getExpiresAtTick() < current.getTick()) {
                continue;
            }
            if (requested != null && requested.matches(current)
                    && requested.getExpiresAtTick() >= current.getTick()
                    && contender.precedes(requested)) {
                return false;
            }
            if (requested == null && contender.getPriority().value() > activePriority.value()) {
                return false;
            }
        }
        return true;
    }
}
