package mindless.utility;

import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.List;

/**
 * Keeps track of which bed is yours, once, for everything that needs to know.
 *
 * <p>BedAura and Bed Wars each used to work this out for themselves, with the same approach and
 * therefore the same three faults. Both recorded a "spawn anchor" -- wherever you happened to be
 * standing when the scoreboard first read as a live game -- and then treated whichever bed sat
 * nearest to it as yours. That breaks down in ways that are easy to hit and hard to notice:
 *
 * <ul>
 *   <li>The anchor was only taken once {@code getBedwarsStatus()} returned 2. If that check was
 *       late, the anchor was recorded wherever you had run to by then; if it never returned 2 --
 *       which it does not once Red and Blue are both eliminated -- no anchor was taken at all and
 *       the whitelist silently never applied.
 *   <li>Nearest-to-the-anchor has no notion of whose bed it is. Once yours was broken, the nearest
 *       bed became an enemy one, and the whitelist started protecting that instead.
 *   <li>It only counted while you stood within about 28 blocks of the anchor, so walking away
 *       turned your own bed back into a target.
 * </ul>
 *
 * <p>What this does instead is what meowutils does: shortly after you spawn, look for the bed
 * nearest you and remember the block. From then on the question "is this my bed" is an identity
 * check, which holds wherever you are standing and however late into the game it is. The only
 * things that clear it are your bed actually being destroyed and your team changing.
 *
 * <p>There is no event subscription here. It is driven by whichever modules are already receiving
 * chat and tick events, and every entry point is safe to call more than once per tick, so it does
 * not matter how many of them are switched on.
 */
public final class OwnBedTracker {
    private static final Minecraft mc = Minecraft.getMinecraft();

    /** How far from your spawn to look. Hypixel islands sit well inside this. */
    private static final int SEARCH_RADIUS = 25;
    /** Let the world settle after a spawn, while you are still standing on your island. */
    private static final long SCAN_DELAY_MS = 1000L;
    /** A spawn can beat its chunks, so a scan gets a few goes before giving up. */
    private static final int SCAN_ATTEMPTS = 4;

    /** The foot half of your bed, which identifies the pair. Null when unknown or gone. */
    private static BlockPos ownBedFoot;
    private static boolean destroyed;
    private static long scanAt;
    private static int attempts;
    private static boolean announced;

    private OwnBedTracker() {
    }

    // ------------------------------------------------------------------------------ lifecycle

    /** Feed every chat line here. Recognises the messages that mean "look again" or "it is gone". */
    public static void handleChat(String strippedMessage) {
        if (strippedMessage == null) {
            return;
        }

        if (strippedMessage.startsWith(" ")
                && strippedMessage.contains("Protect your bed and destroy the enemy beds.")) {
            ownBedFoot = null;
            destroyed = false;
            scheduleScan();
        }
        else if (strippedMessage.equals("You have respawned!")) {
            // Respawning puts you back on your island, which is the one moment the search is
            // guaranteed to be looking at the right place.
            if (!destroyed && ownBedFoot == null) {
                scheduleScan();
            }
        }
        else if (strippedMessage.contains("BED DESTRUCTION > Your Bed")) {
            // Nothing left to protect. Holding on to the old position is what made the whitelist
            // start shielding an enemy bed instead.
            ownBedFoot = null;
            destroyed = true;
            scanAt = 0L;
        }
        else if (strippedMessage.contains("Your team swapped and you are now:")) {
            ownBedFoot = null;
            destroyed = false;
            scheduleScan();
        }
    }

    /** Call once per tick from any module that is running. Idempotent within a tick. */
    public static void tick() {
        if (!Utils.nullCheck()) {
            return;
        }

        if (scanAt != 0L && System.currentTimeMillis() >= scanAt) {
            runScan();
        }

        // A bed can go without the message reaching us -- different mode wording, a missed packet,
        // a rejoin. Checking the block is still a bed stops a destroyed one protecting its old
        // spot forever.
        if (ownBedFoot != null && footHeadPair(ownBedFoot) == null) {
            ownBedFoot = null;
        }
    }

    /** Forget everything. For world changes and module shutdown. */
    public static void reset() {
        ownBedFoot = null;
        destroyed = false;
        scanAt = 0L;
        attempts = 0;
        announced = false;
    }

    private static void scheduleScan() {
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

    // --------------------------------------------------------------------------------- queries

    /** The foot block of your bed, or null when it is unknown or destroyed. */
    public static BlockPos getOwnBedFoot() {
        return ownBedFoot;
    }

    /** Whether your bed has been broken this game. */
    public static boolean isDestroyed() {
        return destroyed;
    }

    /** Whether we currently know where your bed is. */
    public static boolean isKnown() {
        return ownBedFoot != null;
    }

    /** The middle of your bed, for distance readouts, or null when unknown. */
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

    /**
     * Drops your own bed from a list of candidates.
     *
     * @return true when one was removed
     */
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

    // ---------------------------------------------------------------------------------- search

    /**
     * The bed nearest the player, searched outwards from where they stand.
     *
     * <p>Shell by shell rather than one sweep of the whole cube, for two reasons. It returns the
     * closest bed rather than whichever happens to lie at the most negative corner, which matters
     * on a map where an enemy bed is inside the radius. And it stops the moment it finds one, so
     * the usual case costs a fraction of the fifty-one-cubed lookups a full sweep would take.
     */
    private static BlockPos[] findNearestBed() {
        BlockPos origin = new BlockPos(mc.thePlayer);

        for (int r = 0; r <= SEARCH_RADIUS; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        // Only the newly reached surface of the cube; the inside was already
                        // covered by a smaller radius.
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        BlockPos[] pair = footHeadPair(origin.add(dx, dy, dz));
                        if (pair != null) {
                            return pair;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolves a block into the foot and head of a whole bed, or null if it is not one.
     *
     * <p>Both halves are verified to agree on facing and part, so a half bed left behind by a
     * partial break is not mistaken for a bed that can still be slept in.
     */
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
