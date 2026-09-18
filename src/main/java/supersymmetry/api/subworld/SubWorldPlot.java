package supersymmetry.api.subworld;

import java.util.Arrays;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import supersymmetry.api.SusyLog;
import supersymmetry.client.subworld.SubworldClientChunks;
import supersymmetry.mixins.minecraft.ChunkAccessor;

public class SubWorldPlot {

    public static final int RENDER_ORIGIN_Y = 127;

    public interface GrowthListener {

        void onPlotGrown(SubWorldPlot oldPlot, SubWorldPlot newPlot, int plotLocalShiftX, int plotLocalShiftZ,
                         int heapShiftX, int heapShiftZ);
    }

    private final World world;
    private final SubWorldAllocator.Rect rect;
    private final Long2ObjectMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    @Nullable private GrowthListener growthListener;

    public SubWorldPlot(World world, int originChunkX, int originChunkZ, int sizeChunksX, int sizeChunksZ) {
        this(world, new SubWorldAllocator.Rect(originChunkX, originChunkZ, sizeChunksX, sizeChunksZ));
    }

    public SubWorldPlot(World world, SubWorldAllocator.Rect rect) {
        this.world = world;
        this.rect = rect.copy();
    }

    public static SubWorldPlot create(World world, int sizeChunksX, int sizeChunksZ) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null) {
            throw new IllegalStateException("no container" + world);
        }
        return container.allocatePlot(sizeChunksX, sizeChunksZ);
    }

    public World getWorld() {
        return world;
    }

    public int getOriginChunkX() {
        return rect.x;
    }

    public int getOriginChunkZ() {
        return rect.z;
    }

    public int getSizeChunksX() {
        return rect.w;
    }

    public int getSizeChunksZ() {
        return rect.h;
    }

    public SubWorldAllocator.Rect getRect() {
        return rect;
    }

    public boolean inBounds(int chunkX, int chunkZ) {
        return rect.contains(chunkX, chunkZ);
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return this.resolveChunk(chunkX, chunkZ);
    }

    @Nullable private Chunk resolveChunk(int chunkX, int chunkZ) {
        if (inBounds(chunkX, chunkZ)) {
            return this.chunkFor(chunkX, chunkZ);
        }
        if (world.isRemote) {
            return null;
        }
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null) {
            return null;
        }
        int margin = SubWorldContainer.GROW_MARGIN_CHUNKS;
        if (chunkX < rect.x - margin || chunkX >= rect.x + rect.w + margin || chunkZ < rect.z - margin ||
                chunkZ >= rect.z + rect.h + margin) {
            return null;
        }
        if (!container.ensureCovered(this, chunkX, chunkZ)) {
            return null;
        }
        SubWorldPlot owner = container.getPlot(chunkX, chunkZ);
        if (owner == null || !owner.inBounds(chunkX, chunkZ)) {
            SusyLog.logger.warn("growth of {} did not claim chunk ({}, {})", this, chunkX, chunkZ);
            return null;
        }
        return owner.chunkFor(chunkX, chunkZ);
    }

    private Chunk chunkFor(int chunkX, int chunkZ) {
        long key = ChunkPos.asLong(chunkX, chunkZ);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            chunk = new Chunk(world, chunkX, chunkZ);
            Arrays.fill(chunk.getBiomeArray(), (byte) Biome.getIdForBiome(Biomes.VOID));
            chunks.put(key, chunk);
            registerChunkOnProvider(chunk);
        }
        return chunk;
    }

    @Nullable public GrowthListener getGrowthListener() {
        return growthListener;
    }

    public void setGrowthListener(@Nullable GrowthListener growthListener) {
        this.growthListener = growthListener;
    }

    private void registerChunkOnProvider(Chunk chunk) {
        SubWorldVoidChunks.invalidate(world, chunk.x, chunk.z);
        if (world.isRemote) {
            SubworldClientChunks.register(world, chunk);
        } else if (world.getChunkProvider() instanceof ChunkProviderServer) {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            provider.loadedChunks.put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
        }
        ((ChunkAccessor) chunk).setLoaded(true);
    }

    private void unregisterChunkFromProvider(Chunk chunk) {
        if (world.isRemote) {
            SubworldClientChunks.unregister(world, chunk);
        } else if (world.getChunkProvider() instanceof ChunkProviderServer) {
            ChunkProviderServer provider = (ChunkProviderServer) world.getChunkProvider();
            provider.loadedChunks.remove(ChunkPos.asLong(chunk.x, chunk.z));
        }
        ((ChunkAccessor) chunk).setLoaded(false);
    }

    @Nullable public Chunk getLoadedChunk(int chunkX, int chunkZ) {
        if (!inBounds(chunkX, chunkZ)) {
            return null;
        }
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public BlockPos getRenderOrigin() {
        return new BlockPos(rect.x << 4, RENDER_ORIGIN_Y, rect.z << 4);
    }

    public BlockPos toGlobal(BlockPos local) {
        return local.add(getRenderOrigin());
    }

    public BlockPos toLocal(BlockPos global) {
        return global.subtract(getRenderOrigin());
    }

    public IBlockState getBlockState(BlockPos pos) {
        Chunk chunk = getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? Blocks.AIR.getDefaultState() : chunk.getBlockState(pos);
    }

    public TileEntity setBlockState(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileNbt) {
        IBlockState newState = state == null ? Blocks.AIR.getDefaultState() : state;
        Chunk chunk = getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) {
            return null;
        }
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        int sy = pos.getY() >> 4;
        if (sy < 0 || sy >= storages.length) {
            return null;
        }
        IBlockState oldState = chunk.getBlockState(pos);
        TileEntity previous = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
        if (storages[sy] == null) {
            storages[sy] = new ExtendedBlockStorage(sy << 4, world.provider.hasSkyLight());
        }
        storages[sy].set(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, newState);
        chunk.setModified(true);

        if (previous != null && previous.shouldRefresh(world, pos, oldState, newState)) {
            chunk.removeTileEntity(pos);
            ((ChunkAccessor) chunk).getTileEntityMap().remove(pos);
            world.markTileEntityForRemoval(previous);
        }

        if (!newState.getBlock().hasTileEntity(newState)) {
            return null;
        }
        TileEntity te = chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
        if (te == null) {
            te = newState.getBlock().createTileEntity(world, newState);
            chunk.addTileEntity(pos, te);
        }
        if (tileNbt != null) {
            te.readFromNBT(tileNbt);
        }
        te.setPos(pos);
        if (!world.loadedTileEntityList.contains(te)) {
            world.addTileEntity(te);
        }
        return te;
    }

    public TileEntity getTileEntity(BlockPos pos) {
        Chunk chunk = getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk == null ? null : chunk.getTileEntity(pos, Chunk.EnumCreateEntityType.CHECK);
    }

    public Long2ObjectMap<Chunk> getLoadedChunks() {
        return chunks;
    }

    public void unregisterAllChunks() {
        for (Chunk chunk : chunks.values()) {
            unregisterChunkFromProvider(chunk);
        }
    }

    public void clearChunkTileEntities() {
        for (Chunk chunk : chunks.values()) {
            ((ChunkAccessor) chunk).getTileEntityMap().clear();
        }
    }

    // TODO actually sample the lighting from the entity position
    public void seedLight() {
        for (Chunk chunk : chunks.values()) {
            for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                if (storage == null) {
                    continue;
                }
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            storage.setSkyLight(x, y, z, 15);
                            storage.setBlockLight(x, y, z, 15);
                        }
                    }
                }
            }
        }
    }

    public void destroy() {
        for (Chunk chunk : chunks.values()) {
            for (TileEntity te : chunk.getTileEntityMap().values()) {
                world.markTileEntityForRemoval(te);
            }
            unregisterChunkFromProvider(chunk);
        }
        chunks.clear();
        SubWorldRegistry.unregister(this);
    }
}
