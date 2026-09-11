package mindless.rotation;

public final class RotationRequest {
    private final RotationSource source;
    private final Float yaw;
    private final Float pitch;

    public RotationRequest(RotationSource source, Float yaw, Float pitch) {
        this.source = source;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    public RotationSource getSource() {
        return source;
    }

    public Float getYaw() {
        return yaw;
    }

    public Float getPitch() {
        return pitch;
    }
}
