package mindless.event;

import mindless.rotation.RotationArbiter;
import mindless.rotation.RotationSource;
import net.minecraftforge.fml.common.eventhandler.Event;

public class ClientRotationEvent extends Event {
    public ClientRotationEvent() {
        this(null, null);
    }

    private final Float baseYaw;
    private final Float basePitch;
    private final RotationArbiter rotations;

    public ClientRotationEvent(Float yaw, Float pitch) {
        this(yaw, pitch, new RotationArbiter());
    }

    public ClientRotationEvent(Float yaw, Float pitch, RotationArbiter rotations) {
        this.baseYaw = yaw;
        this.basePitch = pitch;
        this.rotations = rotations;
    }

    public Float getBaseYaw() {
        return baseYaw;
    }

    public Float getBasePitch() {
        return basePitch;
    }

    public Float getYaw() {
        return rotations.resolveYaw(baseYaw);
    }

    public Float getPitch() {
        return rotations.resolvePitch(basePitch);
    }

    public boolean requestRotation(RotationSource source, Float yaw, Float pitch) {
        return rotations.request(source, yaw, pitch);
    }

    public boolean requestYaw(RotationSource source, float yaw) {
        return rotations.request(source, yaw, null);
    }

    public boolean requestPitch(RotationSource source, float pitch) {
        return rotations.request(source, null, pitch);
    }

    public boolean hasRotationRequest() {
        return rotations.hasRequest();
    }

    public RotationSource getYawSource() {
        return rotations.getYawSource();
    }

    public RotationSource getPitchSource() {
        return rotations.getPitchSource();
    }
}
