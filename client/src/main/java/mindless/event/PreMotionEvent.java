package mindless.event;

import mindless.rotation.RotationArbiter;
import mindless.rotation.RotationSource;
import mindless.module.ModuleManager;
import mindless.script.model.PlayerState;
import net.minecraftforge.fml.common.eventhandler.Event;

public class PreMotionEvent extends Event {
    public PreMotionEvent() {
        this(0.0D, 0.0D, 0.0D, 0.0F, 0.0F, false, false, false);
    }

    private double posX;
    public double posY;
    private double posZ;
    private float yaw;
    private float pitch;
    private boolean onGround;
    private static boolean setRenderYaw;
    private boolean isSprinting;
    private boolean isSneaking;
    private final RotationArbiter rotations = new RotationArbiter();
    public static boolean setRotations;
    public static float preMotionYaw;

    public PreMotionEvent(double posX, double posY, double posZ, float yaw, float pitch, boolean onGround, boolean isSprinting, boolean isSneaking) {
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.yaw = yaw;
        this.pitch = pitch;
        this.onGround = onGround;
        this.isSprinting = isSprinting;
        this.isSneaking = isSneaking;
    }

    public double getPosX() {
        return posX;
    }

    public double getPosY() {
        return posY;
    }

    public double getPosZ() {
        return posZ;
    }

    public float getYaw() {
        Float resolved = rotations.resolveYaw(yaw);
        return resolved == null ? yaw : resolved;
    }

    public float getPitch() {
        Float resolved = rotations.resolvePitch(pitch);
        return resolved == null ? pitch : resolved;
    }

    public RotationSource getYawSource() {
        return rotations.getYawSource();
    }

    public RotationSource getPitchSource() {
        return rotations.getPitchSource();
    }

    public boolean hasRotationRequest() {
        return rotations.hasRequest();
    }

    public boolean isOnGround() {
        return onGround;
    }

    public void setPosX(double posX) {
        this.posX = posX;
    }

    public void setPosY(double posY) {
        this.posY = posY;
    }

    public void setPosZ(double posZ) {
        this.posZ = posZ;
    }

    public void setYaw(float yaw) {
        requestYaw(RotationSource.LEGACY, yaw);
    }

    public boolean requestYaw(RotationSource source, float yaw) {
        boolean accepted = rotations.request(source, yaw, null);
        if (!accepted) {
            return false;
        }
        this.setRenderYaw = true;
        setRotations = true;
        preMotionYaw = getYaw();
        return true;
    }

    public void setRotations(float yaw, float pitch) {
        requestRotation(RotationSource.LEGACY, yaw, pitch);
    }

    public boolean requestRotation(RotationSource source, float yaw, float pitch) {
        boolean accepted = rotations.request(source, yaw, pitch);
        if (!accepted) {
            return false;
        }
        this.setRenderYaw = true;
        setRotations = true;
        preMotionYaw = getYaw();
        return true;
    }

    public void setPitch(float pitch) {
        requestPitch(RotationSource.LEGACY, pitch);
    }

    public boolean requestPitch(RotationSource source, float pitch) {
        boolean accepted = rotations.request(source, null, pitch);
        if (!accepted) {
            return false;
        }
        setRotations = true;
        return true;
    }

    public void setOnGround(boolean onGround) {
        this.onGround = onGround;
    }

    public static boolean setRenderYaw() {
        return setRenderYaw && (ModuleManager.movementFix == null
                || !ModuleManager.movementFix.isEnabled()
                || ModuleManager.movementFix.isStrict());
    }

    public static void setRenderYaw(boolean setYaw) {
        setRenderYaw = setYaw;
    }

    public boolean isSprinting() {
        return isSprinting;
    }

    public void setSprinting(boolean sprinting) {
        this.isSprinting = sprinting;
    }

    public boolean isSneaking() {
        return isSneaking;
    }

    public void setSneaking(boolean sneaking) {
        this.isSneaking = sneaking;
    }

    public boolean isEquals(PlayerState e) {
        return e.x == this.posX && e.y == this.posY && e.z == this.posZ && e.yaw == getYaw() && e.pitch == getPitch() && e.onGround == this.onGround && e.isSprinting == this.isSprinting && e.isSneaking == this.isSneaking;
    }
}
