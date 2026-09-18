package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import supersymmetry.api.SusyLog;

public final class SubWorldContainer {

    public static final int GROW_MARGIN_CHUNKS = 1;

    private final World world;
    private final SubWorldAllocator allocator;
    private SubWorldAllocatorSavedData savedData;
    private final List<SubWorldPlot> allPlots = new ArrayList<>();
    private final Long2ObjectMap<SubWorldPlot> chunkToPlot = new Long2ObjectOpenHashMap<>();
    private final Map<SubWorldPlot, SubWorldRemovalReason> pendingRemovals = new HashMap<>();

    public SubWorldContainer(World world) {
        this.world = world;
        this.allocator = new SubWorldAllocator(SubWorldAllocator.HEAP_ORIGIN_CHUNK, SubWorldAllocator.HEAP_ORIGIN_CHUNK,
                SubWorldAllocator.HEAP_SIZE_CHUNKS, SubWorldAllocator.HEAP_BORDER_CHUNKS);
    }

    public void attachSavedData(SubWorldAllocatorSavedData data) {
        this.savedData = data;
        this.allocator.rebuildFromSaved(data.getAllocated());
    }

    public static SubWorldContainer getContainer(World world) {
        if (world instanceof SubWorldContainerHolder) {
            return ((SubWorldContainerHolder) world).susy$getSubWorldContainer();
        }
        return null;
    }

    @Nullable public SubWorldPlot getPlot(int chunkX, int chunkZ) {
        SubWorldPlot mapped = this.chunkToPlot.get(ChunkPos.asLong(chunkX, chunkZ));
        if (mapped != null && mapped.inBounds(chunkX, chunkZ)) {
            return mapped;
        }
        return this.findNearestMarginPlot(chunkX, chunkZ);
    }

    @Nullable private SubWorldPlot findNearestMarginPlot(int chunkX, int chunkZ) {
        SubWorldPlot nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (SubWorldPlot plot : this.allPlots) {
            if (plot.inBounds(chunkX, chunkZ)) {
                return plot;
            }
            SubWorldAllocator.Rect rect = plot.getRect();
            int dx = Math.max(0, Math.max(rect.x - chunkX, chunkX - (rect.x + rect.w - 1)));
            int dz = Math.max(0, Math.max(rect.z - chunkZ, chunkZ - (rect.z + rect.h - 1)));
            int distance = Math.max(dx, dz);
            if (distance > GROW_MARGIN_CHUNKS) {
                continue;
            }
            if (nearest == null || distance < nearestDistance ||
                    (distance == nearestDistance && precedes(rect, nearest.getRect()))) {
                nearest = plot;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean precedes(SubWorldAllocator.Rect a, SubWorldAllocator.Rect b) {
        return a.x != b.x ? a.x < b.x : a.z < b.z;
    }

    public SubWorldPlot allocatePlot(int sizeChunksX, int sizeChunksZ) {
        if (this.world.isRemote) {
            throw new IllegalStateException("not meant to work client side");
        }
        SubWorldAllocator.Rect rect = this.allocator.allocate(sizeChunksX, sizeChunksZ);
        SubWorldPlot plot = new SubWorldPlot(this.world, rect);
        this.register(plot);
        this.onAllocatorChanged();
        return plot;
    }

    public void register(SubWorldPlot plot) {
        if (this.allPlots.contains(plot)) {
            return;
        }
        this.allPlots.add(plot);
        this.addCells(plot);
    }

    public void remove(SubWorldPlot plot) {
        if (!this.allPlots.remove(plot)) {
            return;
        }
        this.removeCells(plot, plot.getRect());
    }

    public boolean isPlotRectAllocated(SubWorldAllocator.Rect rect) {
        return this.allocator.isAllocated(rect);
    }

    public void freeOwnedRect(SubWorldAllocator.Rect rect) {
        if (this.world.isRemote) {
            return;
        }
        if (this.allocator.freeOwned(rect)) {
            this.onAllocatorChanged();
        }
    }

    public boolean ensureCovered(SubWorldPlot plot, int chunkX, int chunkZ) {
        if (this.world.isRemote || !this.allPlots.contains(plot)) {
            return false;
        }
        SubWorldAllocator.Rect rect = plot.getRect();
        int west = Math.max(0, rect.x - chunkX);
        int north = Math.max(0, rect.z - chunkZ);
        int east = Math.max(0, chunkX - (rect.x + rect.w - 1));
        int south = Math.max(0, chunkZ - (rect.z + rect.h - 1));
        if (west == 0 && north == 0 && east == 0 && south == 0) {
            return true;
        }
        SubWorldAllocator.Rect oldBounds = rect.copy();
        if (this.allocator.expand(rect, west, north, east, south)) {
            this.rebuildIndex(plot, oldBounds);
            plot.seedLight();
            this.onAllocatorChanged();
            this.notifyGrowth(plot, plot, west * 16, north * 16, 0, 0);
            return true;
        }
        SubWorldPlot moved = this.relocate(plot, chunkX, chunkZ, west, north, east, south);
        if (moved == null) {
            SusyLog.logger.warn("failed to grow plot {} to cover chunk ({}, {})", plot, chunkX, chunkZ);
            return false;
        }
        this.onAllocatorChanged();
        moved.seedLight();
        this.notifyGrowth(plot, moved, west * 16, north * 16, (moved.getOriginChunkX() + west - oldBounds.x) * 16,
                (moved.getOriginChunkZ() + north - oldBounds.z) * 16);
        return true;
    }

    @Nullable private SubWorldPlot relocate(SubWorldPlot plot, int chunkX, int chunkZ, int west, int north, int east,
                                  int south) {
        SubWorldAllocator.Rect rect = plot.getRect();
        int newW = rect.w + west + east;
        int newH = rect.h + north + south;
        if (!this.allocator.freeOwned(rect)) {
            return null;
        }
        SubWorldAllocator.Rect target = this.allocator.allocateAt(rect.x - west, rect.z - north, newW, newH);
        if (target == null) {
            target = this.allocator.allocateContaining(newW, newH, chunkX, chunkZ);
        }
        if (target == null) {
            try {
                target = this.allocator.allocate(newW, newH);
                SusyLog.logger.warn("plot {} relocated to {} without preserving content heap coords", plot, target);
            } catch (IllegalStateException e) {
                target = null;
            }
        }
        if (target == null) {
            this.allocator.reoccupy(rect);
            SusyLog.logger.warn("no free space to relocate plot {} while growing to ({}, {})", plot, chunkX, chunkZ);
            return null;
        }
        SubWorldAllocator.Rect oldBounds = rect.copy();
        int dx = target.x + west - rect.x;
        int dz = target.z + north - rect.z;
        if (dx != 0 || dz != 0) {
            SusyLog.logger.warn("plot {} relocated to {} without preserving content heap coords", plot, target);
        }
        plot.unregisterAllChunks();
        SubWorldPlot moved = new SubWorldPlot(this.world, target);
        moved.setGrowthListener(plot.getGrowthListener());
        SubWorldRemovalReason pendingReason = this.pendingRemovals.remove(plot);
        if (pendingReason != null) {
            this.pendingRemovals.put(moved, pendingReason);
        }
        for (Chunk old : plot.getLoadedChunks().values()) {
            int nx = old.x + dx;
            int nz = old.z + dz;
            Chunk nc = moved.getChunk(nx, nz);
            nc.setStorageArrays(old.getBlockStorageArray());
            nc.setBiomeArray(old.getBiomeArray().clone());
            if (nc.heightMap.length == old.heightMap.length) {
                System.arraycopy(old.heightMap, 0, nc.heightMap, 0, old.heightMap.length);
            }
            for (TileEntity te : new ArrayList<>(old.getTileEntityMap().values())) {
                nc.addTileEntity(te.getPos().add(dx << 4, 0, dz << 4), te);
            }
        }
        plot.clearChunkTileEntities();
        plot.getLoadedChunks().clear();
        this.removeCells(plot, oldBounds);
        this.allPlots.remove(plot);
        this.allPlots.add(moved);
        this.addCells(moved);
        return moved;
    }

    private void notifyGrowth(SubWorldPlot oldPlot, SubWorldPlot newPlot, int plotLocalShiftX, int plotLocalShiftZ,
                              int heapShiftX, int heapShiftZ) {
        SubWorldPlot.GrowthListener listener = oldPlot.getGrowthListener();
        if (listener != null) {
            listener.onPlotGrown(oldPlot, newPlot, plotLocalShiftX, plotLocalShiftZ, heapShiftX, heapShiftZ);
        }
    }

    private void rebuildIndex(SubWorldPlot plot, SubWorldAllocator.Rect oldBounds) {
        this.removeCells(plot, oldBounds);
        this.addCells(plot);
    }

    private void addCells(SubWorldPlot plot) {
        SubWorldAllocator.Rect rect = plot.getRect();
        int minX = rect.x - GROW_MARGIN_CHUNKS;
        int minZ = rect.z - GROW_MARGIN_CHUNKS;
        int maxX = rect.x + rect.w + GROW_MARGIN_CHUNKS;
        int maxZ = rect.z + rect.h + GROW_MARGIN_CHUNKS;
        for (int cx = minX; cx < maxX; cx++) {
            for (int cz = minZ; cz < maxZ; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                boolean inside = plot.inBounds(cx, cz);
                if (inside || (!this.world.isRemote && !this.chunkToPlot.containsKey(key))) {
                    this.chunkToPlot.put(key, plot);
                }
            }
        }
    }

    private void removeCells(SubWorldPlot plot, SubWorldAllocator.Rect bounds) {
        int minX = bounds.x - GROW_MARGIN_CHUNKS;
        int minZ = bounds.z - GROW_MARGIN_CHUNKS;
        int maxX = bounds.x + bounds.w + GROW_MARGIN_CHUNKS;
        int maxZ = bounds.z + bounds.h + GROW_MARGIN_CHUNKS;
        for (int cx = minX; cx < maxX; cx++) {
            for (int cz = minZ; cz < maxZ; cz++) {
                long key = ChunkPos.asLong(cx, cz);
                if (this.chunkToPlot.get(key) == plot) {
                    this.chunkToPlot.remove(key);
                }
            }
        }
    }

    private void onAllocatorChanged() {
        if (this.savedData != null) {
            this.savedData.setAllocated(this.allocator.getAllocatedRects());
        }
    }

    public void removeAllPlots() {
        for (SubWorldPlot plot : new ArrayList<>(this.allPlots)) {
            plot.destroy();
        }
    }

    public void replace(SubWorldPlot plot) {
        this.pendingRemovals.remove(plot);
        this.remove(plot);
        plot.destroy();
    }

    public void markRemoved(SubWorldPlot plot, SubWorldRemovalReason reason) {
        this.pendingRemovals.put(plot, reason);
    }

    public void processRemovals() {
        if (this.pendingRemovals.isEmpty()) {
            return;
        }
        boolean freed = false;
        for (Map.Entry<SubWorldPlot, SubWorldRemovalReason> entry : this.pendingRemovals.entrySet()) {
            SubWorldPlot plot = entry.getKey();
            boolean stillOwned = this.allPlots.contains(plot);
            plot.destroy();
            if (!this.world.isRemote && entry.getValue() == SubWorldRemovalReason.ENTITY_DEAD && stillOwned) {
                this.allocator.freeOwned(plot.getRect());
                freed = true;
            }
        }
        this.pendingRemovals.clear();
        if (freed) {
            this.onAllocatorChanged();
        }
    }

    public void tick() {
        this.processRemovals();
    }

    public List<SubWorldPlot> getPlots() {
        return this.allPlots;
    }

    public World getWorld() {
        return this.world;
    }
}
