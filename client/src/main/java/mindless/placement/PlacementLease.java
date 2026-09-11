package mindless.placement;

import java.util.IdentityHashMap;
import java.util.Map;

public final class PlacementLease {
    public interface HotbarState {
        int getSelectedSlot();
        void setSelectedSlot(int slot);
    }

    public interface RotationState {
        float getYaw();
        float getPitch();
        void setRotation(float yaw, float pitch);
    }

    public interface InputState {
        boolean isPressed();
        boolean isPhysicallyPressed();
        void setPressed(boolean pressed);
    }

    public interface ControllerAction {
        boolean run();
    }

    private final PlacementCoordinator coordinator;
    private final Object owner;
    private HotbarState hotbarState;
    private int originalSlot;
    private int ownedSlot;
    private RotationState rotationState;
    private float originalYaw;
    private float originalPitch;
    private float ownedYaw;
    private float ownedPitch;
    private final Map<InputState, InputClaim> inputs = new IdentityHashMap<>();
    private boolean released;

    PlacementLease(PlacementCoordinator coordinator, Object owner) {
        this.coordinator = coordinator;
        this.owner = owner;
    }

    public boolean isActive() {
        return !released && coordinator.owns(this);
    }

    public boolean claimHotbar(HotbarState state, int slot) {
        if (!isActive() || state == null) {
            return false;
        }
        if (hotbarState == null) {
            hotbarState = state;
            originalSlot = state.getSelectedSlot();
        } else if (hotbarState != state) {
            throw new IllegalStateException("hotbar already claimed through another state adapter");
        }
        ownedSlot = slot;
        state.setSelectedSlot(slot);
        return true;
    }

    public boolean claimInput(InputState state, boolean pressed) {
        if (!isActive() || state == null) {
            return false;
        }
        InputClaim claim = inputs.get(state);
        if (claim == null) {
            claim = new InputClaim(pressed);
            inputs.put(state, claim);
        } else {
            claim.ownedPressed = pressed;
        }
        state.setPressed(pressed);
        return true;
    }

    public boolean claimRotation(RotationState state, float yaw, float pitch) {
        if (!isActive() || state == null) {
            return false;
        }
        if (rotationState == null) {
            rotationState = state;
            originalYaw = state.getYaw();
            originalPitch = state.getPitch();
        }
        else if (rotationState != state) {
            throw new IllegalStateException("rotation already claimed through another state adapter");
        }
        ownedYaw = yaw;
        ownedPitch = pitch;
        state.setRotation(yaw, pitch);
        return true;
    }

    public boolean tryControllerAction(long tick, ControllerAction action) {
        return isActive() && action != null && coordinator.tryControllerAction(this, tick, action);
    }

    public boolean tryControllerAction(long tick, int limit, ControllerAction action) {
        return isActive() && action != null && coordinator.tryControllerAction(this, tick, limit, action);
    }

    public void releaseHotbar(boolean restore) {
        if (hotbarState == null) {
            return;
        }
        if (restore && hotbarState.getSelectedSlot() == ownedSlot) {
            hotbarState.setSelectedSlot(originalSlot);
        }
        hotbarState = null;
    }

    public void setRestoreSlot(int slot) {
        if (isActive() && hotbarState != null && slot >= 0 && slot < 9) originalSlot = slot;
    }

    public void release() {
        coordinator.release(this);
    }

    Object getOwner() {
        return owner;
    }

    void releaseInternal() {
        if (released) {
            return;
        }
        released = true;
        for (Map.Entry<InputState, InputClaim> entry : inputs.entrySet()) {
            InputState state = entry.getKey();
            InputClaim claim = entry.getValue();
            if (state.isPressed() == claim.ownedPressed) {
                state.setPressed(state.isPhysicallyPressed());
            }
        }
        inputs.clear();
        releaseHotbar(true);
        if (rotationState != null && same(rotationState.getYaw(), ownedYaw)
                && same(rotationState.getPitch(), ownedPitch)) {
            rotationState.setRotation(originalYaw, originalPitch);
        }
        rotationState = null;
    }

    private static boolean same(float left, float right) {
        return Float.floatToIntBits(left) == Float.floatToIntBits(right);
    }

    private static final class InputClaim {
        private boolean ownedPressed;

        private InputClaim(boolean ownedPressed) {
            this.ownedPressed = ownedPressed;
        }
    }
}
