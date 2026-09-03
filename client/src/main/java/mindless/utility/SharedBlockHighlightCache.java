package mindless.utility;

import mindless.event.ReceivePacketEvent;
import mindless.module.setting.impl.BlockListSetting;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.play.server.S21PacketChunkData;
import net.minecraft.network.play.server.S22PacketMultiBlockChange;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S26PacketMapChunkBulk;
import net.minecraft.util.BlockPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class SharedBlockHighlightCache {

    private static final Minecraft mc = Minecraft.getMinecraft();
    private static final SharedBlockHighlightCache INSTANCE = new SharedBlockHighlightCache();

    private final Map<Long, Set<BlockPos>> blockListByChunk = new ConcurrentHashMap<>();
    private final Map<Long, Set<BlockPos>> bedFootByChunk = new ConcurrentHashMap<>();
    private final Set<UpdateListener> updateListeners = ConcurrentHashMap.newKeySet();
    // Chunk packets arrive on the netty thread while the scan drains on the client thread, so
    // both the queue and its dedupe set have to be concurrent.
    private final Queue<Long> scanQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> queuedChunks = ConcurrentHashMap.newKeySet();
    private final Set<Long> scannedChunks = ConcurrentHashMap.newKeySet();
    private final List<Long> pendingSweep = new ArrayList<>();

    private BlockListHighlightMatcher blockListMatcher;
    private boolean bedAttached;

    private static final BedFootHighlightMatcher BED_MATCHER = new BedFootHighlightMatcher();

    private SharedBlockHighlightCache() {
    }

    public interface UpdateListener {
        void onBlockChanged(BlockPos pos, IBlockState newState);

        void onChunkQueued(int chunkX, int chunkZ);

        void onChunkRemoved(int chunkX, int chunkZ);

        void onCacheCleared();
    }

    public static SharedBlockHighlightCache get() {
        return INSTANCE;
    }

    public void attachBlockList(BlockListSetting setting) {
        this.blockListMatcher = new BlockListHighlightMatcher(setting);
        scannedChunks.clear();
    }

    public void detachBlockList() {
        this.blockListMatcher = null;
        blockListByChunk.clear();
    }

    public void attachBed() {
        this.bedAttached = true;
        scannedChunks.clear();
    }

    public void detachBed() {
        this.bedAttached = false;
        bedFootByChunk.clear();
    }

    private boolean isBlockListActive() {
        return blockListMatcher != null && blockListMatcher.isActive();
    }

    private boolean isBedActive() {
        return bedAttached;
    }

    public boolean anyConsumerActive() {
        return isBlockListActive() || isBedActive();
    }

    public void clear() {
        blockListByChunk.clear();
        bedFootByChunk.clear();
        clearQueue();
        for (UpdateListener listener : updateListeners) {
            listener.onCacheCleared();
        }
    }

    private void clearQueue() {
        scanQueue.clear();
        queuedChunks.clear();
        scannedChunks.clear();
    }

    public void addUpdateListener(UpdateListener listener) {
        if (listener != null) {
            updateListeners.add(listener);
        }
    }

    public void removeUpdateListener(UpdateListener listener) {
        if (listener != null) {
            updateListeners.remove(listener);
        }
    }

    public void enqueueChunk(int chunkX, int chunkZ) {
        if (!anyConsumerActive()) {
            return;
        }
        long k = key(chunkX, chunkZ);
        scannedChunks.remove(k);
        if (queuedChunks.add(k)) {
            scanQueue.add(k);
        }
        for (UpdateListener listener : updateListeners) {
            listener.onChunkQueued(chunkX, chunkZ);
        }
    }

    public void removeChunk(int chunkX, int chunkZ) {
        long k = key(chunkX, chunkZ);
        blockListByChunk.remove(k);
        bedFootByChunk.remove(k);
        scannedChunks.remove(k);
        for (UpdateListener listener : updateListeners) {
            listener.onChunkRemoved(chunkX, chunkZ);
        }
    }

    public void enqueueLoadedChunks() {
        if (!anyConsumerActive()) {
            return;
        }
        clearQueue();
        if (mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        enqueueAroundPlayer(false);
    }

    public void tickScan(int maxSections) {
        if (mc.theWorld == null || !anyConsumerActive()) {
            return;
        }
        if (blockListMatcher != null) {
            blockListMatcher.beginScanPass();
        }
        int remaining = maxSections;
        while (remaining > 0) {
            Long queuedKey = scanQueue.poll();
            if (queuedKey == null) {
                break;
            }
            long k = queuedKey;
            queuedChunks.remove(k);
            int cx = (int) (k >> 32), cz = (int) k;
            Chunk chunk = mc.theWorld.getChunkFromChunkCoords(cx, cz);
            if (chunk == null || chunk instanceof EmptyChunk) {
                // Chunk packets are seen on the netty thread before the client thread installs
                // the chunk. Charging the budget keeps a burst of not-yet-loaded chunks from
                // draining the whole queue in one tick; sweepMissedChunks re-queues them once
                // they land.
                remaining--;
                continue;
            }
            scannedChunks.add(k);
            remaining -= scanChunk(chunk);
        }
    }

    /**
     * Re-queues loaded chunks the scan never got to. A chunk dropped for not being loaded yet,
     * or missed because a packet was lost between the two threads, would otherwise stay unscanned
     * for as long as it stays loaded -- which is what left the bed index empty.
     */
    public void sweepMissedChunks() {
        if (!anyConsumerActive() || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }
        enqueueAroundPlayer(true);
    }

    /**
     * Queues the chunks around the player closest-first.
     *
     * Both callers used to raster from the far corner of the render distance inwards, so the ring
     * of chunks actually near the player was scanned last on every pass. With a small scan budget
     * that is minutes of the nearest beds being the last ones found -- they only appeared once
     * everything behind them had been done.
     */
    private void enqueueAroundPlayer(boolean skipScanned) {
        final int pcx = (int) Math.floor(mc.thePlayer.posX) >> 4;
        final int pcz = (int) Math.floor(mc.thePlayer.posZ) >> 4;
        int rd = mc.gameSettings.renderDistanceChunks;

        pendingSweep.clear();
        for (int cx = pcx - rd; cx <= pcx + rd; cx++) {
            for (int cz = pcz - rd; cz <= pcz + rd; cz++) {
                long k = key(cx, cz);
                if (queuedChunks.contains(k) || (skipScanned && scannedChunks.contains(k))) {
                    continue;
                }
                Chunk chunk = mc.theWorld.getChunkFromChunkCoords(cx, cz);
                if (chunk == null || chunk instanceof EmptyChunk) {
                    continue;
                }
                pendingSweep.add(k);
            }
        }

        Collections.sort(pendingSweep, new Comparator<Long>() {
            @Override
            public int compare(Long first, Long second) {
                return Integer.compare(chunkDistanceSq(first, pcx, pcz),
                        chunkDistanceSq(second, pcx, pcz));
            }
        });

        for (int i = 0; i < pendingSweep.size(); i++) {
            long k = pendingSweep.get(i);
            enqueueChunk((int) (k >> 32), (int) k);
        }
    }

    private static int chunkDistanceSq(long chunkKey, int originX, int originZ) {
        int dx = (int) (chunkKey >> 32) - originX;
        int dz = (int) chunkKey - originZ;
        return dx * dx + dz * dz;
    }

    public void onBlockChange(BlockPos pos, IBlockState newState) {
        if (blockListMatcher != null) {
            blockListMatcher.beginScanPass();
        }
        long ck = key(pos.getX() >> 4, pos.getZ() >> 4);
        BlockPos immutablePos = new BlockPos(pos.getX(), pos.getY(), pos.getZ());

        if (isBlockListActive()) {
            if (blockListMatcher.matchesBlock(newState) && blockListMatcher.shouldIndexAt(pos, newState)) {
                blockListByChunk.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(immutablePos);
            } else {
                Set<BlockPos> set = blockListByChunk.get(ck);
                if (set != null) {
                    set.remove(pos);
                }
            }
        }

        if (isBedActive()) {
            if (BED_MATCHER.matchesBlock(newState) && BED_MATCHER.shouldIndexAt(pos, newState)) {
                bedFootByChunk.computeIfAbsent(ck, k -> ConcurrentHashMap.newKeySet()).add(immutablePos);
            } else {
                Set<BlockPos> set = bedFootByChunk.get(ck);
                if (set != null) {
                    set.remove(pos);
                }
            }
        }

        for (UpdateListener listener : updateListeners) {
            listener.onBlockChanged(immutablePos, newState);
        }
    }

    public void onBlockListSettingsChanged() {
        if (blockListMatcher != null) {
            blockListMatcher.beginScanPass();
        }
        rescanBlockListLayer();
    }

    private void rescanBlockListLayer() {
        blockListByChunk.clear();
        clearQueue();
        if (!anyConsumerActive()) {
            return;
        }
        enqueueLoadedChunks();
    }

    public Iterable<Map.Entry<Long, Set<BlockPos>>> entriesBlockList() {
        return blockListByChunk.entrySet();
    }

    public Iterable<Map.Entry<Long, Set<BlockPos>>> entriesBedFeet() {
        return bedFootByChunk.entrySet();
    }

    public int totalBlockList() {
        int n = 0;
        for (Set<BlockPos> s : blockListByChunk.values()) {
            n += s.size();
        }
        return n;
    }

    public int totalBedFeet() {
        int n = 0;
        for (Set<BlockPos> s : bedFootByChunk.values()) {
            n += s.size();
        }
        return n;
    }

    public boolean containsBlockList(BlockPos pos) {
        if (pos == null) {
            return false;
        }
        long ck = key(pos.getX() >> 4, pos.getZ() >> 4);
        Set<BlockPos> set = blockListByChunk.get(ck);
        return set != null && set.contains(pos);
    }

    public void handleReceivePacket(ReceivePacketEvent e) {
        if (!anyConsumerActive()) {
            return;
        }
        if (e.getPacket() instanceof S23PacketBlockChange) {
            S23PacketBlockChange pkt = (S23PacketBlockChange) e.getPacket();
            onBlockChange(pkt.getBlockPosition(), pkt.getBlockState());
        } else if (e.getPacket() instanceof S22PacketMultiBlockChange) {
            S22PacketMultiBlockChange pkt = (S22PacketMultiBlockChange) e.getPacket();
            for (S22PacketMultiBlockChange.BlockUpdateData data : pkt.getChangedBlocks()) {
                onBlockChange(data.getPos(), data.getBlockState());
            }
        } else if (e.getPacket() instanceof S21PacketChunkData) {
            S21PacketChunkData pkt = (S21PacketChunkData) e.getPacket();
            if (pkt.getExtractedSize() == 0) {
                removeChunk(pkt.getChunkX(), pkt.getChunkZ());
            } else {
                enqueueChunk(pkt.getChunkX(), pkt.getChunkZ());
            }
        } else if (e.getPacket() instanceof S26PacketMapChunkBulk) {
            S26PacketMapChunkBulk pkt = (S26PacketMapChunkBulk) e.getPacket();
            for (int i = 0; i < pkt.getChunkCount(); i++) {
                enqueueChunk(pkt.getChunkX(i), pkt.getChunkZ(i));
            }
        }
    }

    private int scanChunk(Chunk chunk) {
        int scanned = 0;
        long ck = key(chunk.xPosition, chunk.zPosition);
        Set<BlockPos> blockFound = ConcurrentHashMap.newKeySet();
        Set<BlockPos> bedFound = ConcurrentHashMap.newKeySet();

        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        int baseX = chunk.xPosition << 4;
        int baseZ = chunk.zPosition << 4;

        // Hoisted: these were being re-evaluated once per block, 4096 times a section.
        boolean blockListActive = isBlockListActive();
        boolean bedActive = isBedActive();

        for (int si = 0; si < sections.length; si++) {
            ExtendedBlockStorage section = sections[si];
            if (section == null) {
                continue;
            }
            scanned++;
            int baseY = si << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        IBlockState state = section.get(x, y, z);
                        if (state == null) {
                            continue;
                        }
                        // Match on the state first. Building a BlockPos for every block in the
                        // chunk was 32k throwaway objects per chunk, which is most of what made
                        // the scan slow enough to need a budget this small.
                        boolean blockWanted = blockListActive && blockListMatcher.matchesBlock(state);
                        boolean bedWanted = bedActive && BED_MATCHER.matchesBlock(state);
                        if (!blockWanted && !bedWanted) {
                            continue;
                        }

                        BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                        if (blockWanted && blockListMatcher.shouldIndexAt(pos, state)) {
                            blockFound.add(pos);
                        }
                        if (bedWanted && BED_MATCHER.shouldIndexAt(pos, state)) {
                            bedFound.add(pos);
                        }
                    }
                }
            }
        }

        if (isBlockListActive()) {
            if (!blockFound.isEmpty()) {
                blockListByChunk.put(ck, blockFound);
            } else {
                blockListByChunk.remove(ck);
            }
        }
        if (isBedActive()) {
            if (!bedFound.isEmpty()) {
                bedFootByChunk.put(ck, bedFound);
            } else {
                bedFootByChunk.remove(ck);
            }
        }

        return Math.max(scanned, 1);
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
