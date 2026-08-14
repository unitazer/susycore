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

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import supersymmetry.api.SusyLog;

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

    private static final int STALE_TICK_TIMEOUT = 80;
    private static final int MAX_CACHED_SUBCHUNKS = 1024;

    private static final int[][] BLOCK_CHANGE_OFFSETS = new int[][] {
            { 0, 0, 0 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 }, { 0, 0, 1 }, { 0, 0, -1 }
    };

    private final Long2ObjectOpenHashMap<Ticket> tickets = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<CachedSubchunk> cached = new Long2ObjectOpenHashMap<>();
    private final WorldServer world;

    public ChunkSyncManager(WorldServer world) {
        this.world = world;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40) | ((long) (y & 0xF) << 36) | ((long) (z & 0xFFFFFF));
    }

    public void update() {
        long gameTime = world.getTotalWorldTime();
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) {
            SusyLog.logger.error("worldId == null");
            return;
        }
        int worldId = worldIdObj;

        var it = tickets.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            Ticket t = it.next().getValue();
            if (t.lastInhabitedTick < gameTime - STALE_TICK_TIMEOUT || !isSubchunkLoaded(t.x, t.y, t.z)) {
                Rapier.removeChunk(worldId, t.x, t.y, t.z);
                it.remove();
            }
        }

        PooledMutableBlockPos pos = PooledMutableBlockPos.retain();
        List<AbstractPhysicsEntity> ents = Rapier.entities;
        for (int i = 0, len = ents.size(); i < len; i++) {
            AbstractPhysicsEntity entity = ents.get(i);

            AxisAlignedBB bb = entity.getEntityBoundingBox();
            if (bb == null) {
                throw new RuntimeException("AbstractPhysicsEntity has a null aabb");
            }

            bb = bb.union(bb.offset(entity.motionX, entity.motionY, entity.motionZ));

            double hSpeed = Math.max(Math.abs(entity.motionX), Math.abs(entity.motionZ)) / 16.0;

            int hPad = (int) Math.ceil(hSpeed * 1.3);
            int vPad = (int) Math.ceil((Math.abs(entity.motionY) / 16.0) * 1.5);
            int minCX = MathHelper.floor(bb.minX / 16.0) - hPad;
            int maxCX = MathHelper.ceil(bb.maxX / 16.0) + hPad;
            int minCY = MathHelper.clamp(MathHelper.floor(bb.minY / 16.0) - vPad, 0, 15);
            int maxCY = MathHelper.clamp(MathHelper.ceil(bb.maxY / 16.0) + vPad, 0, 15);
            int minCZ = MathHelper.floor(bb.minZ / 16.0) - hPad;
            int maxCZ = MathHelper.ceil(bb.maxZ / 16.0) + hPad;
            if (minCY == maxCY) {
                continue;
            }
            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    Chunk chunk = world.getChunkProvider().getLoadedChunk(cx, cz);
                    if (chunk == null || !chunk.isLoaded()) continue;
                    for (int sy = minCY; sy <= maxCY; sy++) {
                        long k = key(cx, sy, cz);
                        Ticket existing = tickets.get(k);
                        if (existing != null) {
                            existing.lastInhabitedTick = gameTime;
                            continue;
                        }
                        if (sy >= chunk.storageArrays.length) continue;
                        ExtendedBlockStorage storage = chunk.storageArrays[sy];
                        if (storage == null || storage.isEmpty()) continue;

                        CachedSubchunk cachedSub = cached.get(k);
                        if (cachedSub != null && cachedSub.storage == storage) {
                            Rapier.addCachedSubchunk(worldId, cx, sy, cz, cachedSub.handles);
                        } else {
                            int[] handles = Rapier.handleSubchunkAddition(world, chunk, aabbTmp, pos, storage, sy,
                                    worldId);
                            if (handles != null) {
                                putCache(k, new CachedSubchunk(storage, handles));
                            }
                        }
                        tickets.put(k, new Ticket(cx, sy, cz, gameTime));
                    }
                }
            }
        }
        pos.release();
    }

    private void putCache(long k, CachedSubchunk cachedSub) {
        if (cached.size() >= MAX_CACHED_SUBCHUNKS) {
            cached.clear();
        }
        cached.put(k, cachedSub);
    }

    public void handleBlockChange(BlockPos pos) {
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) return;
        int worldId = worldIdObj;

        PooledMutableBlockPos tmpPos = PooledMutableBlockPos.retain();
        for (int[] offset : BLOCK_CHANGE_OFFSETS) {
            int px = pos.getX() + offset[0];
            int py = pos.getY() + offset[1];
            int pz = pos.getZ() + offset[2];
            int pcy = py >> 4;
            if (pcy < 0 || pcy > 15) continue;
            int pcx = px >> 4;
            int pcz = pz >> 4;
            long pk = key(pcx, pcy, pcz);

            int lx = px & 0xF;
            int ly = py & 0xF;
            int lz = pz & 0xF;
            tmpPos.setPos(px, py, pz);
            int handle = Rapier.computeBlockColliderHandle(world, tmpPos, aabbTmp, tmpPos);

            CachedSubchunk cachedSub = cached.get(pk);
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

    private boolean isSubchunkLoaded(int x, int y, int z) {
        Chunk chunk = world.getChunkProvider().getLoadedChunk(x, z);
        if (chunk == null || !chunk.isLoaded()) return false;
        if (y < 0 || y >= chunk.storageArrays.length) return false;
        ExtendedBlockStorage storage = chunk.storageArrays[y];
        return storage != null && !storage.isEmpty();
    }
}
