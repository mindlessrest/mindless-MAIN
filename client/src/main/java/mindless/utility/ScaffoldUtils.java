package mindless.utility;

import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;

import java.util.PriorityQueue;

/** Rotation, hit-vector, and inventory helpers used by Scaffold. */
public final class ScaffoldUtils {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private ScaffoldUtils() {}

    private static final class Node implements Comparable<Node> {
        final float yaw, pitch, score;
        Node(float yaw, float pitch, float score) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.score = score;
        }
        @Override
        public int compareTo(Node other) {
            return Float.compare(score, other.score);
        }
    }

    public static float[] computeRotations(BlockPos block, EnumFacing face, float baseYaw,
                                           float basePitch, double reach, int algorithm, boolean strict) {
        double x = block.getX() + 0.5 + face.getDirectionVec().getX() * 0.5;
        double y = block.getY() + 0.5 + face.getDirectionVec().getY() * 0.5;
        double z = block.getZ() + 0.5 + face.getDirectionVec().getZ() * 0.5;
        Vec3 eye = mc.thePlayer.getPositionEyes(1.0f);
        float centerYaw = (float) Math.toDegrees(Math.atan2(z - eye.zCoord, x - eye.xCoord)) - 90.0f;
        centerYaw = baseYaw + MathHelper.wrapAngleTo180_float(centerYaw - baseYaw);
        float centerPitch = (float) -Math.toDegrees(Math.atan2(y - eye.yCoord,
                Math.sqrt((x - eye.xCoord) * (x - eye.xCoord) + (z - eye.zCoord) * (z - eye.zCoord))));
        centerPitch = MathHelper.clamp_float(centerPitch, -90.0f, 90.0f);

        if (algorithm == 1) {
            float[] ranges = {20.0f, 45.0f, 90.0f, 180.0f};
            float[] steps = {2.5f, 5.0f, 7.5f, 10.0f};
            for (int i = 0; i < ranges.length; i++) {
                float[] result = scan(block, face, centerYaw, centerPitch, baseYaw, basePitch,
                        ranges[i], steps[i], reach, strict);
                if (result != null) return result;
            }
            return scan(block, face, centerYaw, centerPitch, baseYaw, basePitch, 180.0f, 10.0f, reach, false);
        }

        float range = algorithm == 2 ? 15.0f : 180.0f;
        float step = algorithm == 2 ? 3.0f : 15.0f;
        return scan(block, face, centerYaw, centerPitch, baseYaw, basePitch, range, step, reach, strict);
    }

    private static float[] scan(BlockPos block, EnumFacing face, float centerYaw, float centerPitch,
                                float baseYaw, float basePitch, float range, float step,
                                double reach, boolean strict) {
        PriorityQueue<Node> open = new PriorityQueue<>();
        for (float yawOffset = -range; yawOffset <= range; yawOffset += step) {
            for (float pitchOffset = -range; pitchOffset <= range; pitchOffset += step) {
                float yaw = baseYaw + MathHelper.wrapAngleTo180_float(centerYaw + yawOffset - baseYaw);
                float pitch = MathHelper.clamp_float(centerPitch + pitchOffset, -90.0f, 90.0f);
                MovingObjectPosition hit = RotationUtils.rayCastBlock(reach, yaw, pitch);
                if (hit == null || !block.equals(hit.getBlockPos()) || hit.sideHit != face) continue;
                float dy = Math.abs(MathHelper.wrapAngleTo180_float(yaw - baseYaw));
                float dp = Math.abs(pitch - basePitch);
                open.add(new Node(yaw, pitch, dy * dy + dp * dp));
            }
        }
        Node best = open.poll();
        return best == null ? null : new float[]{best.yaw, best.pitch};
    }

    public static Vec3 computeHitVec(BlockPos block, EnumFacing face) {
        double x = block.getX() + 0.5;
        double y = block.getY() + 0.5;
        double z = block.getZ() + 0.5;
        switch (face) {
            case DOWN: y = block.getY(); break;
            case UP: y = block.getY() + 1.0; break;
            case NORTH: z = block.getZ(); break;
            case SOUTH: z = block.getZ() + 1.0; break;
            case WEST: x = block.getX(); break;
            case EAST: x = block.getX() + 1.0; break;
            default: break;
        }
        return new Vec3(x, y, z);
    }

    public static int countBlocks() {
        if (mc.thePlayer == null) return 0;
        int count = 0;
        for (ItemStack stack : mc.thePlayer.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemBlock
                    && Utils.canBePlaced((ItemBlock) stack.getItem())) count += stack.stackSize;
        }
        return count;
    }
}
