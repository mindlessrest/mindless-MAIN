package mindless.placement;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PlacementCoordinatorTest {
    @After
    public void clearCoordinator() {
        PlacementCoordinator.get().clear();
    }

    @Test
    public void selectsTheHighestDeclaredParticipant() {
        Object player = new Object();
        Object world = new Object();
        Object scaffold = new Object();
        Object clutch = new Object();
        PlacementCoordinator coordinator = PlacementCoordinator.get();

        coordinator.announce(scaffold, PlacementCoordinator.Priority.SCAFFOLD, player, world, 10L);
        coordinator.announce(clutch, PlacementCoordinator.Priority.CLUTCH, player, world, 10L);

        assertNull(coordinator.acquire(scaffold, PlacementCoordinator.Priority.SCAFFOLD, player, world, 10L));
        assertTrue(coordinator.acquire(clutch, PlacementCoordinator.Priority.CLUTCH, player, world, 10L).isActive());
    }

    @Test
    public void reservesOneControllerActionForTheWinnerPerTick() {
        Object player = new Object();
        Object world = new Object();
        Object owner = new Object();
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        coordinator.announce(owner, PlacementCoordinator.Priority.CLUTCH, player, world, 4L);
        PlacementLease lease = coordinator.acquire(owner, PlacementCoordinator.Priority.CLUTCH, player, world, 4L);
        final int[] calls = new int[] {0};

        assertTrue(lease.tryControllerAction(4L, new PlacementLease.ControllerAction() {
            @Override
            public boolean run() {
                calls[0]++;
                return true;
            }
        }));
        assertFalse(lease.tryControllerAction(4L, new PlacementLease.ControllerAction() {
            @Override
            public boolean run() {
                calls[0]++;
                return true;
            }
        }));
        assertEquals(1, calls[0]);
    }

    @Test
    public void onlyRestoresValuesStillOwnedByTheLease() {
        Object player = new Object();
        Object world = new Object();
        Object owner = new Object();
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        coordinator.announce(owner, PlacementCoordinator.Priority.AUTO_BLOCK_IN, player, world, 3L);
        PlacementLease lease = coordinator.acquire(owner, PlacementCoordinator.Priority.AUTO_BLOCK_IN, player, world, 3L);
        Hotbar hotbar = new Hotbar(2);
        Input input = new Input(false, false);
        Rotation rotation = new Rotation(15.0F, 20.0F);

        assertTrue(lease.claimHotbar(hotbar, 5));
        assertTrue(lease.claimInput(input, true));
        assertTrue(lease.claimRotation(rotation, 30.0F, 40.0F));
        hotbar.slot = 7;
        input.pressed = false;
        rotation.yaw = 60.0F;
        lease.release();

        assertEquals(7, hotbar.slot);
        assertFalse(input.pressed);
        assertEquals(60.0F, rotation.yaw, 0.0F);
        assertEquals(40.0F, rotation.pitch, 0.0F);
    }

    @Test
    public void leaseReplacementCannotRefillBudgetButClutchHasOneRescueAttempt() {
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        Object player = new Object();
        Object world = new Object();
        Object scaffold = new Object();
        coordinator.beginTick(player, world, 8L);
        PlacementLease lease = coordinator.acquire(scaffold, PlacementCoordinator.Priority.SCAFFOLD, player, world);
        assertFalse(lease.tryControllerAction(8L, () -> false));
        lease.release();
        lease = coordinator.acquire(scaffold, PlacementCoordinator.Priority.SCAFFOLD, player, world);
        assertFalse(lease.tryControllerAction(8L, () -> true));
        PlacementLease rescue = coordinator.acquire(new Object(), PlacementCoordinator.Priority.CLUTCH, player, world);
        assertTrue(rescue.tryControllerAction(8L, () -> true));
        rescue.release();
        rescue = coordinator.acquire(new Object(), PlacementCoordinator.Priority.CLUTCH, player, world);
        assertFalse(rescue.tryControllerAction(8L, () -> true));
        coordinator.beginTick(player, world, 9L);
        assertTrue(rescue.tryControllerAction(9L, () -> true));
    }

    @Test
    public void scaffoldBatchIsBoundedAndFailedAttemptsConsumeBudget() {
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        Object player = new Object();
        Object world = new Object();
        coordinator.beginTick(player, world, 8L);
        PlacementLease lease = coordinator.acquire(new Object(), PlacementCoordinator.Priority.SCAFFOLD, player, world);
        assertTrue(lease.tryControllerAction(8L, 4, () -> true));
        assertFalse(lease.tryControllerAction(8L, 4, () -> false));
        assertTrue(lease.tryControllerAction(8L, 4, () -> true));
        assertTrue(lease.tryControllerAction(8L, 4, () -> true));
        assertFalse(lease.tryControllerAction(8L, 4, () -> true));
        assertFalse(lease.tryControllerAction(8L, 5, () -> true));
    }

    @Test
    public void releasingInputRestoresCurrentPhysicalState() {
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        PlacementLease lease = coordinator.acquire(new Object(), PlacementCoordinator.Priority.CLUTCH,
                new Object(), new Object());
        Input released = new Input(true, false);
        Input held = new Input(false, true);
        lease.claimInput(released, true);
        lease.claimInput(held, false);
        lease.release();
        assertFalse(released.pressed);
        assertTrue(held.pressed);
    }

    @Test
    public void keepsAnActiveLeaseUsableAfterItsAnnouncementExpires() {
        Object player = new Object();
        Object world = new Object();
        Object owner = new Object();
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        coordinator.announce(owner, PlacementCoordinator.Priority.HEAD_HITTER, player, world, 1L);
        PlacementLease lease = coordinator.acquire(owner, PlacementCoordinator.Priority.HEAD_HITTER,
                player, world, 1L);
        final int[] calls = new int[] {0};

        coordinator.beginTick(player, world, 3L);
        assertTrue(lease.tryControllerAction(3L, new PlacementLease.ControllerAction() {
            @Override
            public boolean run() {
                calls[0]++;
                return true;
            }
        }));
        assertEquals(1, calls[0]);
    }

    @Test
    public void releaseLetsAWaitingParticipantAcquireImmediately() {
        Object player = new Object();
        Object world = new Object();
        Object first = new Object();
        Object second = new Object();
        PlacementCoordinator coordinator = PlacementCoordinator.get();
        coordinator.announce(first, PlacementCoordinator.Priority.CLUTCH, player, world, 7L);
        PlacementLease firstLease = coordinator.acquire(first, PlacementCoordinator.Priority.CLUTCH,
                player, world, 7L);
        coordinator.announce(second, PlacementCoordinator.Priority.SCAFFOLD, player, world, 7L);

        firstLease.release();

        assertTrue(coordinator.acquire(second, PlacementCoordinator.Priority.SCAFFOLD,
                player, world, 7L).isActive());
    }

    private static final class Hotbar implements PlacementLease.HotbarState {
        private int slot;

        private Hotbar(int slot) {
            this.slot = slot;
        }

        @Override
        public int getSelectedSlot() {
            return slot;
        }

        @Override
        public void setSelectedSlot(int slot) {
            this.slot = slot;
        }
    }

    private static final class Input implements PlacementLease.InputState {
        private boolean pressed;
        private final boolean physical;

        private Input(boolean pressed, boolean physical) {
            this.pressed = pressed;
            this.physical = physical;
        }

        @Override
        public boolean isPressed() {
            return pressed;
        }

        @Override
        public boolean isPhysicallyPressed() {
            return physical;
        }

        @Override
        public void setPressed(boolean pressed) {
            this.pressed = pressed;
        }
    }

    private static final class Rotation implements PlacementLease.RotationState {
        private float yaw;
        private float pitch;

        private Rotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }

        @Override
        public float getYaw() {
            return yaw;
        }

        @Override
        public float getPitch() {
            return pitch;
        }

        @Override
        public void setRotation(float yaw, float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
