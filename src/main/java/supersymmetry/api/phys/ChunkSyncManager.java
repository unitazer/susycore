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
import supersymmetry.client.renderer.handler.PhysicsDebugRenderer;

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

    private static List<AxisAlignedBB> aabbTmp = new ArrayList<>();

    private static final int STALE_TICK_TIMEOUT = 20;

    private final Long2ObjectOpenHashMap<Ticket> tickets = new Long2ObjectOpenHashMap<>();
    private final WorldServer world;

    public ChunkSyncManager(WorldServer world) {
        this.world = world;
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFFF) << 40) | ((long) (y & 0xF) << 36) | ((long) (z & 0xFFFFFF));
    }

    public void update() {
        PhysicsDebugRenderer.debugBoxes2.clear();
        long gameTime = world.getTotalWorldTime();
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) {
            SusyLog.logger.error("worldId == null");
            return;
        }
        int worldId = worldIdObj;

        long startRem = System.nanoTime();
        var it = tickets.long2ObjectEntrySet().fastIterator();
        int removed = 0;
        while (it.hasNext()) {
            Ticket t = it.next().getValue();
            if (t.lastInhabitedTick < gameTime - STALE_TICK_TIMEOUT || !isSubchunkLoaded(t.x, t.y, t.z)) {
                Rapier.removeChunk(worldId, t.x, t.y, t.z);
                it.remove();
                removed++;
            } else {
                t.lastInhabitedTick = gameTime;
            }
        }
        if (removed > 0) {
            SusyLog.logger.info(
                    "removed {} stale subchunks ({} total tracked, removalMs={})",
                    removed,
                    tickets.size(),
                    String.format("%.3f", (System.nanoTime() - startRem) / 1_000_000.0));
        }

        long tickOverlap = System.nanoTime();
        int added = 0;

        PooledMutableBlockPos pos = PooledMutableBlockPos.retain();
        List<AbstractPhysicsEntity> ents = Rapier.entities;
        for (int i = 0, len = ents.size(); i < len; i++) {
            AbstractPhysicsEntity entity = ents.get(i);

            AxisAlignedBB bb = entity.getEntityBoundingBox();
            if (bb == null) {
                throw new RuntimeException("PhysicsDebugRenderer: AbstractPhysicsEntity has a null aabb");
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
            PhysicsDebugRenderer.debugBoxes2.add(
                    new AxisAlignedBB(
                            (double) (minCX * 16),
                            (double) (minCY * 16),
                            (double) (minCZ * 16),
                            (double) (maxCX * 16),
                            (double) (maxCY * 16),
                            (double) (maxCZ * 16)));

            for (int cx = minCX; cx <= maxCX; cx++) {
                for (int cz = minCZ; cz <= maxCZ; cz++) {
                    long chunkStart = System.nanoTime();
                    Chunk chunk = world.getChunk(cx, cz);
                    int subchunkAdded = 0;
                    for (int sy = minCY; sy <= maxCY; sy++) {
                        long k = key(cx, sy, cz);
                        if (tickets.containsKey(k)) continue;
                        if (sy < 0 || sy >= chunk.storageArrays.length) continue;
                        ExtendedBlockStorage storage = chunk.storageArrays[sy];
                        if (storage == null || storage.isEmpty()) continue;
                        Rapier.handleSubchunkAddition(world, chunk, aabbTmp, pos, storage, sy, worldId);
                        tickets.put(k, new Ticket(cx, sy, cz, gameTime));
                        subchunkAdded++;
                    }
                    double chunkMs = (System.nanoTime() - chunkStart) / 1_000_000.0;
                    if (subchunkAdded > 0 && chunkMs > 1.0) {
                        SusyLog.logger.info(
                                "subchunk adds at [{},{}] took {} ms ({} subchunks)",
                                cx,
                                cz,
                                String.format("%.3f", chunkMs),
                                subchunkAdded);
                    }
                    added += subchunkAdded;
                }
            }
        }
        pos.release();
        double overlapMs = (System.nanoTime() - tickOverlap) / 1_000_000.0;
        if (added > 0) {
            SusyLog.logger.info(
                    "added {} subchunks ({} total tracked, overlapScanMs={})",
                    added,
                    tickets.size(),
                    String.format("%.3f", overlapMs));
        }
        List<AxisAlignedBB> snapshot = getTrackedSubchunkBoxes();
        PhysicsDebugRenderer.debugBoxes = snapshot;
    }

    public List<AxisAlignedBB> getTrackedSubchunkBoxes() {
        List<AxisAlignedBB> boxes = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            boxes.add(
                    new AxisAlignedBB(
                            t.x * 16, t.y * 16, t.z * 16, t.x * 16 + 16, t.y * 16 + 16, t.z * 16 + 16));
        }
        return boxes;
    }

    public void handleBlockChange(BlockPos pos) {
        Integer worldIdObj = Rapier.initializedWorlds.get(world);
        if (worldIdObj == null) return;
        int worldId = worldIdObj;

        int cx = pos.getX() >> 4;
        int cy = pos.getY() >> 4;
        int cz = pos.getZ() >> 4;
        long k = key(cx, cy, cz);
        if (!tickets.containsKey(k)) return;

        BlockPos[] positions = new BlockPos[] {
                pos, pos.east(), pos.west(), pos.up(), pos.down(), pos.south(), pos.north()
        };

        PooledMutableBlockPos tmpPos = PooledMutableBlockPos.retain();
        for (BlockPos p : positions) {
            int pcx = p.getX() >> 4;
            int pcy = p.getY() >> 4;
            int pcz = p.getZ() >> 4;
            if (pcy < 0 || pcy > 15) continue;
            long pk = key(pcx, pcy, pcz);
            if (!tickets.containsKey(pk)) continue;

            int lx = p.getX() & 0xF;
            int ly = p.getY() & 0xF;
            int lz = p.getZ() & 0xF;
            int handle = Rapier.computeBlockColliderHandle(world, p, aabbTmp, tmpPos);
            Rapier.partialSubchunkUpdate(worldId, pcx, pcz, pcy, lx, ly, lz, handle);
        }
        tmpPos.release();
    }

    private boolean isSubchunkLoaded(int x, int y, int z) {
        if (!world.isBlockLoaded(new BlockPos(x << 4, 64, z << 4))) return false;
        Chunk chunk = world.getChunk(x, z);
        if (chunk == null) return false;
        return y >= 0 && y < chunk.storageArrays.length;
    }
}
