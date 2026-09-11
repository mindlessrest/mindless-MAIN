package mindless.rotation;

import java.util.EnumMap;
import java.util.Map;

public final class RotationArbiter {
    private final Map<RotationSource, RotationRequest> requests = new EnumMap<RotationSource, RotationRequest>(RotationSource.class);

    public boolean request(RotationSource source, Float yaw, Float pitch) {
        if (source == null || (yaw == null && pitch == null)) {
            return false;
        }
        if (!isFinite(yaw) || !isFinite(pitch)) {
            return false;
        }
        RotationRequest existing = requests.get(source);
        Float requestedYaw = yaw != null ? yaw : existing == null ? null : existing.getYaw();
        Float requestedPitch = pitch != null ? pitch : existing == null ? null : existing.getPitch();
        requests.put(source, new RotationRequest(source, requestedYaw, requestedPitch));
        return true;
    }

    public Float resolveYaw(Float fallback) {
        RotationRequest request = winningYaw();
        return request == null ? fallback : request.getYaw();
    }

    public Float resolvePitch(Float fallback) {
        RotationRequest request = winningPitch();
        return request == null ? fallback : request.getPitch();
    }

    public RotationSource getYawSource() {
        RotationRequest request = winningYaw();
        return request == null ? null : request.getSource();
    }

    public RotationSource getPitchSource() {
        RotationRequest request = winningPitch();
        return request == null ? null : request.getSource();
    }

    public boolean hasRequest() {
        return !requests.isEmpty();
    }

    public void clear() {
        requests.clear();
    }

    public void remove(RotationSource source) { requests.remove(source); }

    private RotationRequest winningYaw() {
        RotationRequest winner = null;
        for (RotationRequest request : requests.values()) {
            if (request.getYaw() != null && winsOver(request, winner)) {
                winner = request;
            }
        }
        return winner;
    }

    private RotationRequest winningPitch() {
        RotationRequest winner = null;
        for (RotationRequest request : requests.values()) {
            if (request.getPitch() != null && winsOver(request, winner)) {
                winner = request;
            }
        }
        return winner;
    }

    private boolean winsOver(RotationRequest candidate, RotationRequest current) {
        if (current == null) {
            return true;
        }
        int priority = Integer.compare(candidate.getSource().getPriority(), current.getSource().getPriority());
        return priority > 0 || (priority == 0 && candidate.getSource().ordinal() < current.getSource().ordinal());
    }

    private boolean isFinite(Float value) {
        return value == null || (!value.isNaN() && !value.isInfinite());
    }
}
