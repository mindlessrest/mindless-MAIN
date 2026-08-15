package keystrokesmod.runtime;

import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.util.MathHelper;

/**
 * Last movement pose which passed every SendPacketEvent listener and is about
 * to be handed to NetworkManager. Buffered/cancelled packets never reach this
 * recorder, so placement code can distinguish a calculated look from one that
 * was actually released to the connection.
 */
public final class SentPlayerState {
    private static boolean hasPosition;
    private static boolean hasLook;
    private static double x;
    private static double y;
    private static double z;
    private static float yaw;
    private static float pitch;

    private SentPlayerState() {
    }

    public static synchronized void record(Packet<?> packet) {
        if (packet instanceof C00Handshake
                || packet instanceof C00PacketLoginStart) {
            reset();
            return;
        }
        if (!(packet instanceof C03PacketPlayer)) {
            return;
        }
        C03PacketPlayer movement = (C03PacketPlayer) packet;
        if (movement.isMoving()) {
            x = movement.getPositionX();
            y = movement.getPositionY();
            z = movement.getPositionZ();
            hasPosition = true;
        }
        if (movement.getRotating()) {
            yaw = movement.getYaw();
            pitch = movement.getPitch();
            hasLook = true;
        }
    }

    public static synchronized Snapshot snapshot() {
        return hasPosition && hasLook
                ? new Snapshot(x, y, z, yaw, pitch) : null;
    }

    public static synchronized void reset() {
        hasPosition = false;
        hasLook = false;
        x = y = z = 0.0D;
        yaw = pitch = 0.0F;
    }

    public static final class Snapshot {
        public final double x;
        public final double y;
        public final double z;
        public final float yaw;
        public final float pitch;

        private Snapshot(double x, double y, double z,
                         float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        public boolean hasLook(float expectedYaw, float expectedPitch) {
            return !Float.isNaN(expectedYaw) && !Float.isNaN(expectedPitch)
                    && Math.abs(MathHelper.wrapAngleTo180_float(
                    yaw - expectedYaw)) <= 1.0E-3F
                    && Math.abs(pitch - expectedPitch) <= 1.0E-3F;
        }
    }
}
