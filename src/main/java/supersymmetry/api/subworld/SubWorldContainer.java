package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class SubWorldContainer {

    private final World world;
    private final SubWorldAllocator allocator;
    private SubWorldAllocatorSavedData savedData;
    private final List<SubWorldPlot> allPlots = new ArrayList<>();
    private final Long2ObjectMap<SubWorldPlot> chunkToPlot = new Long2ObjectOpenHashMap<>();
    private final Map<SubWorldPlot, SubWorldRemovalReason> pendingRemovals = new HashMap<>();

    public SubWorldContainer(World world) {
        this.world = world;
        this.allocator = new SubWorldAllocator(SubWorldAllocator.HEAP_ORIGIN_CHUNK, SubWorldAllocator.HEAP_ORIGIN_CHUNK,
                SubWorldAllocator.HEAP_SIZE_CHUNKS);
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

    @Nullable
    public SubWorldPlot getPlot(int chunkX, int chunkZ) {
        return this.chunkToPlot.get(ChunkPos.asLong(chunkX, chunkZ));
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
        this.addPlotCells(plot);
    }

    public void remove(SubWorldPlot plot) {
        if (!this.allPlots.remove(plot)) {
            return;
        }
        this.removePlotCells(plot);
    }

    public void freePlot(SubWorldPlot plot) {
        if (this.world.isRemote || !this.allPlots.contains(plot)) {
            return;
        }
        plot.destroy();
        this.allocator.free(plot.getRect());
        this.onAllocatorChanged();
    }

    public void freeOwnedRect(SubWorldAllocator.Rect rect) {
        if (this.world.isRemote) {
            return;
        }
        if (this.allocator.freeOwned(rect)) {
            this.onAllocatorChanged();
        }
    }

    public SubWorldPlot growPlot(SubWorldPlot plot, int targetSizeX, int targetSizeZ) {
        if (this.world.isRemote) {
            return plot;
        }
        SubWorldAllocator.Rect grown = this.allocator.grow(plot.getRect(), targetSizeX, targetSizeZ);
        this.onAllocatorChanged();
        if (grown == plot.getRect()) {
            this.reindex(plot);
            return plot;
        }
        SubWorldPlot moved = this.relocate(plot, grown);
        this.onAllocatorChanged();
        return moved;
    }

    private SubWorldPlot relocate(SubWorldPlot plot, SubWorldAllocator.Rect newRect) {
        int dx = newRect.x - plot.getOriginChunkX();
        int dz = newRect.z - plot.getOriginChunkZ();
        plot.unregisterAllChunks();
        SubWorldPlot moved = new SubWorldPlot(this.world, newRect);
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
        this.allocator.free(plot.getRect());
        this.remove(plot);
        this.register(moved);
        return moved;
    }

    private void reindex(SubWorldPlot plot) {
        this.removePlotCells(plot);
        this.addPlotCells(plot);
    }

    private void addPlotCells(SubWorldPlot plot) {
        for (int cx = plot.getOriginChunkX(); cx < plot.getOriginChunkX() + plot.getSizeChunksX(); cx++) {
            for (int cz = plot.getOriginChunkZ(); cz < plot.getOriginChunkZ() + plot.getSizeChunksZ(); cz++) {
                this.chunkToPlot.put(ChunkPos.asLong(cx, cz), plot);
            }
        }
    }

    private void removePlotCells(SubWorldPlot plot) {
        for (int cx = plot.getOriginChunkX(); cx < plot.getOriginChunkX() + plot.getSizeChunksX(); cx++) {
            for (int cz = plot.getOriginChunkZ(); cz < plot.getOriginChunkZ() + plot.getSizeChunksZ(); cz++) {
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

    public void replace(SubWorldPlot plot, SubWorldRemovalReason reason) {
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
            plot.destroy();
            if (!this.world.isRemote && entry.getValue() == SubWorldRemovalReason.ENTITY_DEAD) {
                this.allocator.free(plot.getRect());
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
