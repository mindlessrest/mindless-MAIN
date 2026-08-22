package mindless.utility;

import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Direct Mindless translation of Yuri PlayerUtils placement helpers. */
public final class ScaffoldPlayerUtils {
    private static final Minecraft mc = Minecraft.getMinecraft();

    private ScaffoldPlayerUtils() {}

    public static Block blockRelativeToPlayer(double x, double y, double z) {
        return BlockUtils.getBlock(mc.thePlayer.posX + x, mc.thePlayer.posY + y, mc.thePlayer.posZ + z);
    }

    public static boolean isBlockUnder(double height) {
        AxisAlignedBB box = mc.thePlayer.getEntityBoundingBox().offset(0, -height, 0);
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer, box).isEmpty();
    }

    public static Vec3 getPlacePossibility(double offsetX, double offsetY, double offsetZ, Integer plane) {
        List<Vec3> possibilities = new ArrayList<>();
        int range = (int) (5 + Math.abs(offsetX) + Math.abs(offsetZ));
        for (int x = -range; x <= range; x++) for (int y = -range; y <= range; y++) for (int z = -range; z <= range; z++) {
            Block block = blockRelativeToPlayer(x, y, z);
            BlockPos pos = new BlockPos(mc.thePlayer.posX + x, mc.thePlayer.posY + y, mc.thePlayer.posZ + z);
            if (!block.isReplaceable(mc.theWorld, pos)) {
                for (int d = -1; d <= 1; d += 2) {
                    possibilities.add(new Vec3(mc.thePlayer.posX + x + d, mc.thePlayer.posY + y, mc.thePlayer.posZ + z));
                    possibilities.add(new Vec3(mc.thePlayer.posX + x, mc.thePlayer.posY + y + d, mc.thePlayer.posZ + z));
                    possibilities.add(new Vec3(mc.thePlayer.posX + x, mc.thePlayer.posY + y, mc.thePlayer.posZ + z + d));
                }
            }
        }
        possibilities.removeIf(v -> mc.thePlayer.getDistance(v.xCoord, v.yCoord, v.zCoord) > 5
                || !blockRelativeToPlayer(v.xCoord - mc.thePlayer.posX, v.yCoord - mc.thePlayer.posY, v.zCoord - mc.thePlayer.posZ)
                .isReplaceable(mc.theWorld, new BlockPos(v.xCoord, v.yCoord, v.zCoord)));
        if (plane != null) possibilities.removeIf(v -> Math.floor(v.yCoord + 1) != plane);
        possibilities.sort(Comparator.comparingDouble(v -> {
            double x = mc.thePlayer.posX + offsetX - v.xCoord;
            double y = mc.thePlayer.posY - 1 + offsetY - v.yCoord;
            double z = mc.thePlayer.posZ + offsetZ - v.zCoord;
            return MathHelper.sqrt_double(x * x + y * y + z * z);
        }));
        return possibilities.isEmpty() ? null : possibilities.get(0);
    }

    public static EnumFacingOffset getEnumFacing(Vec3 position, boolean downwards) {
        List<EnumFacingOffset> faces = new ArrayList<>();
        for (int z = -1; z <= 1; z += 2) if (!BlockUtils.getBlock(position.xCoord, position.yCoord, position.zCoord + z)
                .isReplaceable(mc.theWorld, new BlockPos(position.xCoord, position.yCoord, position.zCoord + z)))
            faces.add(new EnumFacingOffset(z < 0 ? EnumFacing.SOUTH : EnumFacing.NORTH, new Vec3(0, 0, z)));
        for (int x = -1; x <= 1; x += 2) if (!BlockUtils.getBlock(position.xCoord + x, position.yCoord, position.zCoord)
                .isReplaceable(mc.theWorld, new BlockPos(position.xCoord + x, position.yCoord, position.zCoord)))
            faces.add(new EnumFacingOffset(x > 0 ? EnumFacing.WEST : EnumFacing.EAST, new Vec3(x, 0, 0)));
        faces.sort(Comparator.comparingDouble(f -> Math.abs(MathHelper.wrapAngleTo180_double(
                Math.toDegrees(Math.atan2(f.getOffset().zCoord, f.getOffset().xCoord)) - RotationUtils.serverRotations[0] - 90))));
        if (!faces.isEmpty()) return faces.get(0);
        for (int y = -1; y <= 1; y += 2) if (!BlockUtils.getBlock(position.xCoord, position.yCoord + y, position.zCoord)
                .isReplaceable(mc.theWorld, new BlockPos(position.xCoord, position.yCoord + y, position.zCoord))) {
            if (y < 0) return new EnumFacingOffset(EnumFacing.UP, new Vec3(0, y, 0));
            if (downwards) return new EnumFacingOffset(EnumFacing.DOWN, new Vec3(0, y, 0));
        }
        return null;
    }
}
