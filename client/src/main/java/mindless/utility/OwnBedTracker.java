package mindless.utility;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.List;
public final class OwnBedTracker {
    private static final Minecraft mc = Minecraft.getMinecraft();
private static final int SEARCH_RADIUS = 25;
private static final long SCAN_DELAY_MS = 1000L;
private static final int SCAN_ATTEMPTS = 4;
private static BlockPos ownBedFoot;
    private static boolean destroyed;
    private static long scanAt;
    private static int attempts;
    private static boolean announced;
    private static boolean awaitingSpawnTeleport;

    private OwnBedTracker() {
    }
public static void handleChat(String strippedMessage) {
        if (strippedMessage == null) {
            return;
        }

        if (HypixelLanguage.contains(strippedMessage, HypixelLanguage.Key.BED_INTRO)) {
            ownBedFoot = null;
            destroyed = false;
            scanAt = 0L;
            awaitingSpawnTeleport = true;
        }
        else if (HypixelLanguage.contains(strippedMessage, HypixelLanguage.Key.RESPAWNED)) {
            if (!destroyed && ownBedFoot == null) {
                scheduleScan();
            }
        }
        else if (HypixelLanguage.contains(strippedMessage, HypixelLanguage.Key.BED_DESTRUCTION)
                && HypixelLanguage.contains(strippedMessage, HypixelLanguage.Key.YOUR_BED)) {
            ownBedFoot = null;
            destroyed = true;
            awaitingSpawnTeleport = false;
            scanAt = 0L;
        }
        else if (HypixelLanguage.contains(strippedMessage, HypixelLanguage.Key.TEAM_SWAP)) {
            ownBedFoot = null;
            destroyed = false;
            scheduleScan();
        }
    }
public static void tick() {
        if (!Utils.nullCheck()) {
            return;
        }

        if (scanAt != 0L && System.currentTimeMillis() >= scanAt) {
            runScan();
        }
        if (ownBedFoot != null && mc.theWorld.isAreaLoaded(ownBedFoot.add(-1, 0, -1),
                ownBedFoot.add(1, 0, 1)) && footHeadPair(ownBedFoot) == null) {
            ownBedFoot = null;
            destroyed = true;
            awaitingSpawnTeleport = false;
            scanAt = 0L;
        }
    }
public static void reset() {
        ownBedFoot = null;
        destroyed = false;
        scanAt = 0L;
        attempts = 0;
        announced = false;
        awaitingSpawnTeleport = false;
    }

    public static void handleSpawnTeleport() {
        if (awaitingSpawnTeleport) {
            awaitingSpawnTeleport = false;
            scheduleScan();
        }
    }

    private static void scheduleScan() {
        awaitingSpawnTeleport = false;
        scanAt = System.currentTimeMillis() + SCAN_DELAY_MS;
        attempts = 0;
        announced = false;
    }

    private static void runScan() {
        BlockPos[] found = findNearestBed();
        if (found != null) {
            ownBedFoot = found[0];
            destroyed = false;
            scanAt = 0L;
            if (!announced) {
                announced = true;
                Utils.sendMessage("&aBed&7: whitelisted your bed at &a" + ownBedFoot.getX()
                        + "&7, &a" + ownBedFoot.getY() + "&7, &a" + ownBedFoot.getZ());
            }
            return;
        }

        if (++attempts < SCAN_ATTEMPTS) {
            scanAt = System.currentTimeMillis() + SCAN_DELAY_MS;
            return;
        }

        scanAt = 0L;
        if (!announced) {
            announced = true;
            Utils.sendMessage("&cBed&7: could not find your bed to whitelist.");
        }
    }
public static BlockPos getOwnBedFoot() {
        return ownBedFoot;
    }
public static boolean isDestroyed() {
        return destroyed;
    }
public static boolean isKnown() {
        return ownBedFoot != null;
    }
public static Vec3 getOwnBedCenter() {
        BlockPos[] pair = ownBedFoot == null ? null : footHeadPair(ownBedFoot);
        if (pair == null) {
            return null;
        }
        AxisAlignedBB bounds = BlockUtils.unionBlockBounds(pair[0], pair[1]);
        return new Vec3((bounds.minX + bounds.maxX) * 0.5,
                (bounds.minY + bounds.maxY) * 0.5,
                (bounds.minZ + bounds.maxZ) * 0.5);
    }

    public static boolean isOwnBed(BlockPos[] pair) {
        return pair != null && ownBedFoot != null && ownBedFoot.equals(pair[0]);
    }
public static boolean removeOwnBed(List<BlockPos[]> pairs) {
        if (ownBedFoot == null || pairs == null || pairs.isEmpty()) {
            return false;
        }
        for (int i = 0; i < pairs.size(); i++) {
            if (ownBedFoot.equals(pairs.get(i)[0])) {
                pairs.remove(i);
                return true;
            }
        }
        return false;
    }
private static BlockPos[] findNearestBed() {
        BlockPos origin = new BlockPos(mc.thePlayer.posX,
                mc.thePlayer.posY + mc.thePlayer.getEyeHeight(), mc.thePlayer.posZ);
        BlockPos[] closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int distance = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
                    if (distance >= closestDistance) continue;
                    BlockPos[] pair = footHeadPair(origin.add(dx, dy, dz));
                    if (pair != null) {
                        closest = pair;
                        closestDistance = distance;
                    }
                }
            }
        }
        return closest;
    }
public static BlockPos[] footHeadPair(BlockPos at) {
        if (mc.theWorld == null) {
            return null;
        }
        IBlockState state = mc.theWorld.getBlockState(at);
        if (!(state.getBlock() instanceof BlockBed)) {
            return null;
        }

        BlockBed.EnumPartType part = (BlockBed.EnumPartType) state.getValue(BlockBed.PART);
        EnumFacing facing = (EnumFacing) state.getValue(BlockBed.FACING);
        BlockPos foot = part == BlockBed.EnumPartType.FOOT ? at : at.offset(facing.getOpposite());

        IBlockState footState = mc.theWorld.getBlockState(foot);
        if (!(footState.getBlock() instanceof BlockBed)
                || footState.getValue(BlockBed.PART) != BlockBed.EnumPartType.FOOT) {
            return null;
        }

        EnumFacing footFacing = (EnumFacing) footState.getValue(BlockBed.FACING);
        BlockPos head = foot.offset(footFacing);
        IBlockState headState = mc.theWorld.getBlockState(head);
        if (!(headState.getBlock() instanceof BlockBed)
                || headState.getValue(BlockBed.PART) != BlockBed.EnumPartType.HEAD
                || headState.getValue(BlockBed.FACING) != footFacing) {
            return null;
        }

        return new BlockPos[]{foot, head};
    }
}
