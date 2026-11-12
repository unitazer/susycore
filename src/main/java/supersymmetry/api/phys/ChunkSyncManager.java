package supersymmetry.api.phys;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import supersymmetry.api.SusyLog;
import supersymmetry.api.subworld.SubWorldAllocator;
import supersymmetry.api.subworld.SubWorldRegistry;

public class ChunkSyncManager {

    private static final class Ticket {

        final int x, y, z;
        long lastInhabitedTick;

        Ticket(int x, int y, int z, long tick) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastInhabitedTick = tick;
        }
    }

    private static final class CachedSubchunk {

        final ExtendedBlockStorage storage;
        final int[] handles;

        CachedSubchunk(ExtendedBlockStorage storage, int[] handles) {
            this.storage = storage;
            this.handles = handles;
        }
    }

    private static List<AxisAlignedBB> aabbTmp = new ArrayList<>();

    private static final int STALE_TICK_TIMEOUT = 600;
    private static final int OUT_OF_REGION_GRACE_TICKS = 40;
    private static final int MAX_CACHED_SUBCHUNKS = 4096;
    private static final int MAX_HPAD_CHUNKS = 8;
    private static final int MAX_SYNC_RADIUS_CHUNKS = 12;
    private static final double CRITICAL_GROW = 1.5;
    private static final long SPECULATIVE_BUDGET_NANOS = 2_000_000L;

    private static final class SubchunkTask {

        final int cx, cy, cz;
        boolean critical;

        SubchunkTask(int cx, int cy, int cz, boolean critical) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.critical = critical;
        }
    }

    private final Long2ObjectOpenHashMap<Ticket> tickets = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<CachedSubchunk> cached = new Long2ObjectOpenHashMap<>();
    private final WorldServer world;
    private int bulkDepth;
    private final LongOpenHashSet bulkTouched = new LongOpenHashSet();
    private final List<SubchunkTask> tasks = new ArrayList<>();
    private final Long2IntOpenHashMap planned = new Long2IntOpenHashMap();

    public ChunkSyncManager(WorldServer world) {
        this.world = world;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40) | ((long) (y & 0xF) << 36) | ((long) (z & 0xFFFFFF));
    }

    private static int keyX(long k) {
        int v = (int) ((k >>> 40) & 0xFFFFFF);
        return (v & 0x800000) != 0 ? v | 0xFF000000 : v;
    }

    private static int keyY(long k) {
        return (int) ((k >>> 36) & 0xF);
    }

    private static int keyZ(long k) {
        int v = (int) (k & 0xFFFFFF);
        return (v & 0x800000) != 0 ? v | 0xFF000000 : v;
    }

    public void beginBulk() {
        bulkDepth++;
    }

    public void endBulk() {
        if (bulkDepth <= 0) return;
        if (--bulkDepth == 0 && !bulkTouched.isEmpty()) {
            Integer worldIdObj = Rapier.initializedWorlds.get(world);
            if (worldIdObj != null) {
                int worldId = worldIdObj;
                LongIterator it = bulkTouched.iterator();
                while (it.hasNext()) {
                    long k = it.nextLong();
                    resyncSubchunk(worldId, keyX(k), keyY(k), keyZ(k));
                }
            }
            bulkTouched.clear();
        }
    }

    public void update() {
        long gameTime = world.getTotalWorldTime();
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) {
            SusyLog.logger.error("worldId == null");
            return;
        }
        int worldId = worldIdObj;

        int staleRemoved = 0;
        var it = tickets.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Ticket t = it.next().getValue();
            if (isPlotOrHeapChunk(t.x, t.z) || t.lastInhabitedTick < gameTime - STALE_TICK_TIMEOUT ||
                    !isSubchunkLoaded(t.x, t.y, t.z)) {
                Rapier.removeChunk(worldId, t.x, t.y, t.z);
                it.remove();
                staleRemoved++;
            }
        }

        tasks.clear();
        planned.clear();
        planned.defaultReturnValue(-1);
        PooledMutableBlockPos pos = PooledMutableBlockPos.retain();
        long start = System.nanoTime();
        int bodies = 0;
        try {
            for (AbstractPhysicsEntity entity : Rapier.entities) {
                AxisAlignedBB bb = entity.getEntityBoundingBox();
                if (bb == null) {
                    throw new RuntimeException("AbstractPhysicsEntity has a null aabb");
                }
                bodies++;
                collectTerrainTasks(bb, entity.motionX, entity.motionY, entity.motionZ);
            }
            // stream() now; parallelStream() later if per-body collect is made pure/thread-safe
            PhysicsWorldEntity.activeBodies().forEach(entity -> {
                if (entity.getEntityWorld() != world || entity.getPlot() == null) return;
                AxisAlignedBB bb = entity.getEntityBoundingBox();
                if (bb == null) return;
                collectTerrainTasks(bb, entity.motionX, entity.motionY, entity.motionZ);
            });
            for (PhysicsWorldEntity entity : PhysicsWorldEntity.activeBodies()) {
                if (entity.getEntityWorld() == world && entity.getPlot() != null &&
                        entity.getEntityBoundingBox() != null)
                    bodies++;
            }

            applyTasks(worldId, pos, gameTime, start);

            var oit = tickets.long2ObjectEntrySet().fastIterator();
            while (oit.hasNext()) {
                var e = oit.next();
                Ticket t = e.getValue();
                if (planned.get(e.getLongKey()) < 0 &&
                        gameTime - t.lastInhabitedTick > OUT_OF_REGION_GRACE_TICKS) {
                    Rapier.removeChunk(worldId, t.x, t.y, t.z);
                    oit.remove();
                    chunksRemovedThisTick++;
                }
            }
        } finally {
            pos.release();
        }
        int fullScans = fullScansAdded;
        int cachedAdds = cachedAddsThisTick;
        int speculativeSkipped = speculativeSkippedThisTick;
        int removed = chunksRemovedThisTick;
        cachedAddsThisTick = 0;
        fullScansAdded = 0;
        speculativeSkippedThisTick = 0;
        chunksRemovedThisTick = 0;
        double ms = (System.nanoTime() - start) / 1e6;
        if (ms > 20) {
            SusyLog.logger.warn(
                    "chunk sync slow: bodies={} tickets={} tasks={} stale={} oob={} fullScans={} cachedAdds={} specSkip={} took={} ms",
                    bodies, tickets.size(), tasks.size(), staleRemoved, removed, fullScans, cachedAdds,
                    speculativeSkipped, ms);
        } else if (SusyLog.logger.isDebugEnabled()) {
            SusyLog.logger.debug(
                    "bodies={} tickets={} tasks={} stale={} oob={} fullScans={} cachedAdds={} specSkip={} took={} ms",
                    bodies, tickets.size(), tasks.size(), staleRemoved, removed, fullScans, cachedAdds,
                    speculativeSkipped, ms);
        }
    }

    private void collectTerrainTasks(AxisAlignedBB bb, double motionX, double motionY, double motionZ) {
        AxisAlignedBB swept = bb.union(bb.offset(motionX, motionY, motionZ));
        AxisAlignedBB criticalBox = swept.grow(CRITICAL_GROW);
        AxisAlignedBB marginBox = swept.grow(1.0);

        double hSpeed = Math.max(Math.abs(motionX), Math.abs(motionZ)) / 16.0;
        int hPad = Math.min((int) Math.ceil(hSpeed * 1.3), MAX_HPAD_CHUNKS);
        int vPad = Math.min((int) Math.ceil((Math.abs(motionY) / 16.0) * 1.5), 4);
        int centerCX = MathHelper.floor((marginBox.minX + marginBox.maxX) * 0.03125);
        int centerCZ = MathHelper.floor((marginBox.minZ + marginBox.maxZ) * 0.03125);
        int minCX = Math.max(MathHelper.floor(marginBox.minX / 16.0) - hPad, centerCX - MAX_SYNC_RADIUS_CHUNKS);
        int maxCX = Math.min(MathHelper.ceil(marginBox.maxX / 16.0) + hPad, centerCX + MAX_SYNC_RADIUS_CHUNKS);
        int minCY = MathHelper.clamp(MathHelper.floor(marginBox.minY / 16.0) - vPad, 0, 15);
        int maxCY = MathHelper.clamp(MathHelper.ceil(marginBox.maxY / 16.0) + vPad, 0, 15);
        int minCZ = Math.max(MathHelper.floor(marginBox.minZ / 16.0) - hPad, centerCZ - MAX_SYNC_RADIUS_CHUNKS);
        int maxCZ = Math.min(MathHelper.ceil(marginBox.maxZ / 16.0) + hPad, centerCZ + MAX_SYNC_RADIUS_CHUNKS);

        // collected sequentially now; can be produced per-body and merged if collect is parallelized
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (isPlotOrHeapChunk(cx, cz)) continue;
                Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                if (chunk == null || !chunk.isLoaded()) continue;
                for (int sy = minCY; sy <= maxCY; sy++) {
                    long k = key(cx, sy, cz);
                    boolean critical = intersectsSubchunk(criticalBox, cx, sy, cz);
                    int prev = planned.get(k);
                    if (prev >= 0) {
                        if (critical) tasks.get(prev).critical = true;
                        continue;
                    }
                    planned.put(k, tasks.size());
                    tasks.add(new SubchunkTask(cx, sy, cz, critical));
                }
            }
        }
    }

    private static boolean intersectsSubchunk(AxisAlignedBB box, int cx, int cy, int cz) {
        double minX = cx * 16.0;
        double minY = cy * 16.0;
        double minZ = cz * 16.0;
        return box.maxX > minX && box.minX < minX + 16.0 && box.maxY > minY && box.minY < minY + 16.0 &&
                box.maxZ > minZ && box.minZ < minZ + 16.0;
    }

    private void applyTasks(int worldId, PooledMutableBlockPos pos, long gameTime, long tickStartNanos) {
        // JNI mutations are serial (Rust world lock). stream() now; keep apply serial even if planning parallelizes.
        tasks.stream().filter(t -> t.critical).forEach(t -> syncTask(t, worldId, pos, gameTime, false, tickStartNanos));
        tasks.stream().filter(t -> !t.critical).forEach(t -> syncTask(t, worldId, pos, gameTime, true, tickStartNanos));
    }

    private void syncTask(SubchunkTask task, int worldId, PooledMutableBlockPos pos, long gameTime,
                          boolean speculative, long tickStartNanos) {
        int cx = task.cx;
        int sy = task.cy;
        int cz = task.cz;
        long k = key(cx, sy, cz);
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null || !chunk.isLoaded()) return;

        boolean hadTicket = tickets.containsKey(k);
        if (hadTicket) {
            ExtendedBlockStorage current = sy < chunk.storageArrays.length ? chunk.storageArrays[sy] : null;
            if (current == null || current.isEmpty()) {
                Rapier.removeChunk(worldId, cx, sy, cz);
                tickets.remove(k);
                cached.remove(k);
                return;
            }
            CachedSubchunk cachedSub = cached.get(k);
            if (cachedSub != null && cachedSub.storage == current) {
                tickets.get(k).lastInhabitedTick = gameTime;
                return;
            }
            // storage object replaced or cache invalid: mandatory resync to avoid stale Rapier geometry
            Rapier.removeChunk(worldId, cx, sy, cz);
            tickets.remove(k);
            hadTicket = false;
        }

        if (sy >= chunk.storageArrays.length) return;
        ExtendedBlockStorage storage = chunk.storageArrays[sy];
        if (storage == null || storage.isEmpty()) return;

        if (speculative && System.nanoTime() - tickStartNanos > SPECULATIVE_BUDGET_NANOS) {
            speculativeSkippedThisTick++;
            return;
        }

        CachedSubchunk cachedSub = cached.get(k);
        if (cachedSub != null && cachedSub.storage == storage) {
            if (speculative && System.nanoTime() - tickStartNanos > SPECULATIVE_BUDGET_NANOS) {
                speculativeSkippedThisTick++;
                return;
            }
            Rapier.addCachedSubchunk(worldId, cx, sy, cz, cachedSub.handles);
            cachedAddsThisTick++;
        } else {
            if (speculative) {
                speculativeSkippedThisTick++;
                return;
            }
            int[] handles = Rapier.handleSubchunkAddition(world, chunk, aabbTmp, pos, storage, sy, worldId);
            fullScansAdded++;
            if (handles != null) {
                putCache(k, new CachedSubchunk(storage, handles));
            }
        }
        tickets.put(k, new Ticket(cx, sy, cz, gameTime));
    }

    private void putCache(long k, CachedSubchunk cachedSub) {
        if (cached.size() >= MAX_CACHED_SUBCHUNKS && !cached.containsKey(k)) {
            int toRemove = MAX_CACHED_SUBCHUNKS / 4;
            long[] victims = new long[toRemove];
            long[] victimTick = new long[toRemove];
            int filled = 0;
            var it = cached.long2ObjectEntrySet().fastIterator();
            while (it.hasNext() && filled < toRemove) {
                long ck = it.next().getLongKey();
                Ticket t = tickets.get(ck);
                long tick = t == null ? Long.MIN_VALUE : t.lastInhabitedTick;
                victims[filled] = ck;
                victimTick[filled] = tick;
                filled++;
            }
            while (it.hasNext()) {
                long ck = it.next().getLongKey();
                Ticket t = tickets.get(ck);
                long tick = t == null ? Long.MIN_VALUE : t.lastInhabitedTick;
                int worst = 0;
                for (int i = 1; i < filled; i++) {
                    if (victimTick[i] < victimTick[worst]) worst = i;
                }
                if (tick < victimTick[worst]) {
                    victims[worst] = ck;
                    victimTick[worst] = tick;
                }
            }
            for (int i = 0; i < filled; i++) {
                cached.remove(victims[i]);
            }
        }
        cached.put(k, cachedSub);
    }

    public void handleBlockChange(BlockPos pos) {
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) return;
        int worldId = worldIdObj;

        PooledMutableBlockPos tmpPos = PooledMutableBlockPos.retain();
        for (int[] offset : Rapier.BLOCK_CHANGE_OFFSETS) {
            int px = pos.getX() + offset[0];
            int py = pos.getY() + offset[1];
            int pz = pos.getZ() + offset[2];
            int pcy = py >> 4;
            if (pcy < 0 || pcy > 15) continue;
            int pcx = px >> 4;
            int pcz = pz >> 4;
            long pk = key(pcx, pcy, pcz);

            if (bulkDepth > 0) {
                bulkTouched.add(pk);
                continue;
            }

            CachedSubchunk cachedSub = cached.get(pk);
            if (cachedSub == null && !tickets.containsKey(pk) && bodyOverlapsWorldBlock(px, py, pz)) {
                cachedSub = forceSyncSubchunk(worldId, pcx, pcy, pcz);
            }
            if (cachedSub == null && !tickets.containsKey(pk)) continue;

            int lx = px & 0xF;
            int ly = py & 0xF;
            int lz = pz & 0xF;
            tmpPos.setPos(px, py, pz);
            int handle = Rapier.computeBlockColliderHandle(world, tmpPos, aabbTmp, tmpPos);

            if (cachedSub != null) {
                Chunk c = world.getChunkProvider().getLoadedChunk(pcx, pcz);
                if (c != null && pcy < c.storageArrays.length && cachedSub.storage == c.storageArrays[pcy]) {
                    cachedSub.handles[ly << 8 | lz << 4 | lx] = handle;
                }
            }

            if (tickets.containsKey(pk)) {
                Rapier.partialSubchunkUpdate(worldId, pcx, pcz, pcy, lx, ly, lz, handle);
            }
        }
        tmpPos.release();
    }

    @Nullable private CachedSubchunk forceSyncSubchunk(int worldId, int cx, int cy, int cz) {
        if (isPlotOrHeapChunk(cx, cz)) return null;
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null || !chunk.isLoaded() || cy < 0 || cy >= chunk.storageArrays.length) {
            return null;
        }
        ExtendedBlockStorage storage = chunk.storageArrays[cy];
        if (storage == null || storage.isEmpty()) {
            return null;
        }
        long k = key(cx, cy, cz);
        CachedSubchunk cachedSub = cached.get(k);
        if (cachedSub != null && cachedSub.storage == storage) {
            Rapier.addCachedSubchunk(worldId, cx, cy, cz, cachedSub.handles);
        } else {
            PooledMutableBlockPos pos = PooledMutableBlockPos.retain();
            int[] handles = Rapier.handleSubchunkAddition(world, chunk, aabbTmp, pos, storage, cy, worldId);
            pos.release();
            cachedSub = handles == null ? null : new CachedSubchunk(storage, handles);
            if (cachedSub != null) {
                putCache(k, cachedSub);
            }
        }
        tickets.put(k, new Ticket(cx, cy, cz, world.getTotalWorldTime()));
        return cachedSub;
    }

    private void resyncSubchunk(int worldId, int cx, int cy, int cz) {
        long k = key(cx, cy, cz);
        if (isPlotOrHeapChunk(cx, cz)) {
            if (tickets.remove(k) != null) Rapier.removeChunk(worldId, cx, cy, cz);
            cached.remove(k);
            return;
        }
        Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (chunk == null || !chunk.isLoaded() || cy < 0 || cy >= chunk.storageArrays.length) {
            if (tickets.remove(k) != null) Rapier.removeChunk(worldId, cx, cy, cz);
            return;
        }
        ExtendedBlockStorage storage = chunk.storageArrays[cy];
        if (storage == null || storage.isEmpty()) {
            if (tickets.remove(k) != null) Rapier.removeChunk(worldId, cx, cy, cz);
            cached.remove(k);
            return;
        }
        PooledMutableBlockPos pos = PooledMutableBlockPos.retain();
        int[] handles = Rapier.handleSubchunkAddition(world, chunk, aabbTmp, pos, storage, cy, worldId);
        pos.release();
        if (handles == null) {
            if (tickets.remove(k) != null) Rapier.removeChunk(worldId, cx, cy, cz);
            cached.remove(k);
            return;
        }
        putCache(k, new CachedSubchunk(storage, handles));
        tickets.put(k, new Ticket(cx, cy, cz, world.getTotalWorldTime()));
    }

    private boolean bodyOverlapsWorldBlock(int x, int y, int z) {
        AxisAlignedBB box = new AxisAlignedBB(x - 1.0, y - 1.0, z - 1.0, x + 2.0, y + 2.0, z + 2.0);
        for (int i = 0, len = Rapier.entities.size(); i < len; i++) {
            if (Rapier.entities.get(i).getEntityBoundingBox().intersects(box)) {
                return true;
            }
        }
        for (PhysicsWorldEntity entity : PhysicsWorldEntity.activeBodies()) {
            if (entity.getEntityWorld() == world && entity.getEntityBoundingBox().intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private int cachedAddsThisTick;
    private int fullScansAdded;
    private int speculativeSkippedThisTick;
    private int chunksRemovedThisTick;

    private boolean isPlotOrHeapChunk(int cx, int cz) {
        return SubWorldAllocator.isInHeapRegion(cx, cz) || SubWorldRegistry.peekPlotChunk(world, cx, cz) != null;
    }

    private boolean isSubchunkLoaded(int x, int y, int z) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
        if (chunk == null || !chunk.isLoaded()) return false;
        if (y < 0 || y >= chunk.storageArrays.length) return false;
        ExtendedBlockStorage storage = chunk.storageArrays[y];
        return storage != null && !storage.isEmpty();
    }
}
