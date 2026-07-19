package supersymmetry.api.phys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEventData;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Biomes;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.profiler.Profiler;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ReportedException;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.GameRules;
import net.minecraft.world.GameType;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldInfo;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.SyncedTileEntityBase;
import gregtech.api.util.world.DummySaveHandler;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import supersymmetry.common.network.SPacketPocketBlockUpdate;

public class PocketWorld extends World implements IShape {

    public PhysicsWorldEntity upstream;

    private final Long2ObjectMap<Chunk> loadedChunks = new Long2ObjectOpenHashMap<>();

    private final Set<NextTickListEntry> pendingTickListEntriesHashSet = Sets.newHashSet();
    private final TreeSet<NextTickListEntry> pendingTickListEntriesTreeSet = new TreeSet<>();
    private final List<NextTickListEntry> pendingTickListEntriesThisTick = Lists.newArrayList();

    private final List<BlockEventData>[] blockEventQueue = new List[] { new ArrayList<>(), new ArrayList<>() };
    private int blockEventCacheIndex;

    public PocketWorld(PhysicsWorldEntity upstream) {
        super(
                new DummySaveHandler(),
                new WorldInfo(
                        new WorldSettings(0L, GameType.SURVIVAL, false, false, WorldType.DEFAULT), "virtual"),
                new WorldProviderSurface(),
                new Profiler(),
                false);
        this.upstream = upstream;
        this.provider.setWorld(this);
        this.chunkProvider = createChunkProvider();
        if (upstream != null) upstream.setPocketWorld(this);
    }

    public PhysicsWorldEntity getUpstreamEntity() {
        return upstream;
    }

    @Override
    protected IChunkProvider createChunkProvider() {
        return new IChunkProvider() {

            @Nullable
            @Override
            public Chunk getLoadedChunk(int x, int z) {
                return loadedChunks.get(ChunkPos.asLong(x, z));
            }

            @Override
            public Chunk provideChunk(int x, int z) {
                long key = ChunkPos.asLong(x, z);
                Chunk chunk = loadedChunks.get(key);
                if (chunk == null) {
                    chunk = new Chunk(PocketWorld.this, x, z);
                    Arrays.fill(chunk.getBiomeArray(), (byte) Biome.getIdForBiome(Biomes.VOID));
                    loadedChunks.put(key, chunk);
                    chunk.onLoad();
                }
                return chunk;
            }

            @Override
            public boolean tick() {
                return false;
            }

            @Override
            public String makeString() {
                return "PocketChunkProvider";
            }

            @Override
            public boolean isChunkGeneratedAt(int x, int z) {
                return loadedChunks.containsKey(ChunkPos.asLong(x, z));
            }
        };
    }

    @Override
    protected boolean isChunkLoaded(int x, int z, boolean allowEmpty) {
        return loadedChunks.containsKey(ChunkPos.asLong(x, z));
    }

    @Override
    public boolean spawnEntity(Entity entityIn) {
        if (entityIn instanceof EntityPlayer) {
            return super.spawnEntity(entityIn);
        }
        entityIn.posX += upstream.posX;
        entityIn.posY += upstream.posY;
        entityIn.posZ += upstream.posZ;
        entityIn.world = upstream.world;
        entityIn.dimension = upstream.dimension;
        return upstream.world.spawnEntity(entityIn);
    }

    @Override
    public ShapeType type() {
        return ShapeType.CUSTOM;
    }

    @Override
    public float[] data() {
        return new float[0];
    }

    @Override
    public AxisAlignedBB boundingBox(Vec3d position, Quaternion rotation) {
        if (loadedChunks.isEmpty()) {
            return new AxisAlignedBB(
                    position.x, position.y, position.z, position.x, position.y, position.z);
        }
        double minX = Double.MAX_VALUE, minY = 0, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = 256, maxZ = -Double.MAX_VALUE;
        for (Chunk chunk : loadedChunks.values()) {
            int x1 = chunk.x * 16;
            int z1 = chunk.z * 16;
            int x2 = x1 + 16;
            int z2 = z1 + 16;
            Vec3d c1 = rotation.rotatePoint(new Vec3d(x1, 0, z1));
            Vec3d c2 = rotation.rotatePoint(new Vec3d(x2, 256, z2));
            minX = Math.min(minX, Math.min(c1.x, c2.x));
            minY = Math.min(minY, Math.min(c1.y, c2.y));
            minZ = Math.min(minZ, Math.min(c1.z, c2.z));
            maxX = Math.max(maxX, Math.max(c1.x, c2.x));
            maxY = Math.max(maxY, Math.max(c1.y, c2.y));
            maxZ = Math.max(maxZ, Math.max(c1.z, c2.z));
        }
        return new AxisAlignedBB(
                position.x + minX,
                position.y + minY,
                position.z + minZ,
                position.x + maxX,
                position.y + maxY,
                position.z + maxZ);
    }

    @Override
    public void scheduleBlockUpdate(BlockPos pos, Block blockIn, int delay, int priority) {
        if (blockIn == null) return;
        NextTickListEntry entry = new NextTickListEntry(pos, blockIn);
        entry.setPriority(priority);
        Material material = blockIn.getDefaultState().getMaterial();
        if (material != Material.AIR) {
            entry.setScheduledTime((long) delay + this.worldInfo.getWorldTotalTime());
        }
        if (!this.pendingTickListEntriesHashSet.contains(entry)) {
            this.pendingTickListEntriesHashSet.add(entry);
            this.pendingTickListEntriesTreeSet.add(entry);
        }
    }

    public boolean tickUpdates(boolean runAllPending) {
        int i = this.pendingTickListEntriesTreeSet.size();
        if (i != this.pendingTickListEntriesHashSet.size()) {
            throw new IllegalStateException("TickNextTick list out of synch");
        }
        if (i > 65536) {
            i = 65536;
        }
        for (int j = 0; j < i; ++j) {
            NextTickListEntry entry = this.pendingTickListEntriesTreeSet.first();
            if (!runAllPending && entry.scheduledTime > this.worldInfo.getWorldTotalTime()) {
                break;
            }
            this.pendingTickListEntriesTreeSet.remove(entry);
            this.pendingTickListEntriesHashSet.remove(entry);
            this.pendingTickListEntriesThisTick.add(entry);
        }
        Iterator<NextTickListEntry> iterator = this.pendingTickListEntriesThisTick.iterator();
        while (iterator.hasNext()) {
            NextTickListEntry entry = iterator.next();
            iterator.remove();
            if (this.isAreaLoaded(entry.position, entry.position)) {
                IBlockState state = this.getBlockState(entry.position);
                if (state.getMaterial() != Material.AIR && Block.isEqualTo(state.getBlock(), entry.getBlock())) {
                    try {
                        state.getBlock().updateTick(this, entry.position, state, this.rand);
                    } catch (Throwable t) {
                        CrashReport crash = CrashReport.makeCrashReport(t, "Exception while ticking a block");
                        CrashReportCategory category = crash.makeCategory("Block being ticked");
                        CrashReportCategory.addBlockInfo(category, entry.position, state);
                        throw new ReportedException(crash);
                    }
                }
            } else {
                this.scheduleBlockUpdate(entry.position, entry.getBlock(), 0, 0);
            }
        }
        return !this.pendingTickListEntriesTreeSet.isEmpty();
    }

    @Override
    public void addBlockEvent(BlockPos pos, Block blockIn, int eventID, int eventParam) {
        BlockEventData event = new BlockEventData(pos, blockIn, eventID, eventParam);
        for (BlockEventData existing : this.blockEventQueue[this.blockEventCacheIndex]) {
            if (existing.equals(event)) {
                return;
            }
        }
        this.blockEventQueue[this.blockEventCacheIndex].add(event);
    }

    private void fireBlockEvents() {
        while (!this.blockEventQueue[this.blockEventCacheIndex].isEmpty()) {
            int i = this.blockEventCacheIndex;
            this.blockEventCacheIndex ^= 1;
            for (BlockEventData event : this.blockEventQueue[i]) {
                IBlockState state = this.getBlockState(event.getPosition());
                if (state.getBlock() == event.getBlock()) {
                    state.onBlockEventReceived(
                            this, event.getPosition(), event.getEventID(), event.getEventParameter());
                }
            }
            this.blockEventQueue[i].clear();
        }
    }

    @Override
    public long getTotalWorldTime() {
        return upstream.world.getTotalWorldTime();
    }

    @Override
    public long getWorldTime() {
        return upstream.world.getWorldTime();
    }

    @Override
    public void setWorldTime(long time) {
        upstream.world.setWorldTime(time);
    }

    @Override
    public float getCelestialAngle(float partialTicks) {
        return upstream.world.getCelestialAngle(partialTicks);
    }

    @Override
    public float getCelestialAngleRadians(float partialTicks) {
        return upstream.world.getCelestialAngleRadians(partialTicks);
    }

    @Override
    public int getSkylightSubtracted() {
        return upstream.world.getSkylightSubtracted();
    }

    @Override
    public float getSunBrightness(float partialTicks) {
        return upstream.world.getSunBrightness(partialTicks);
    }

    @Override
    public float getCurrentMoonPhaseFactor() {
        return upstream.world.getCurrentMoonPhaseFactor();
    }

    @Override
    public int getMoonPhase() {
        return upstream.world.getMoonPhase();
    }

    @Override
    public boolean isDaytime() {
        return upstream.world.isDaytime();
    }

    @Override
    public float getRainStrength(float partialTicks) {
        return upstream.world.getRainStrength(partialTicks);
    }

    @Override
    public float getThunderStrength(float partialTicks) {
        return upstream.world.getThunderStrength(partialTicks);
    }

    @Override
    public boolean isRaining() {
        return upstream.world.isRaining();
    }

    @Override
    public boolean isThundering() {
        return upstream.world.isThundering();
    }

    @Override
    public MinecraftServer getMinecraftServer() {
        return upstream.world.getMinecraftServer();
    }

    @Override
    public GameRules getGameRules() {
        return upstream.world.getGameRules();
    }

    @Override
    public EnumDifficulty getDifficulty() {
        return upstream.world.getDifficulty();
    }

    @Override
    public WorldBorder getWorldBorder() {
        return upstream.world.getWorldBorder();
    }

    @Override
    public BlockPos getSpawnPoint() {
        return upstream.world.getSpawnPoint();
    }

    @Override
    public long getSeed() {
        return upstream.world.getSeed();
    }

    @Override
    public ISaveHandler getSaveHandler() {
        return upstream.world.getSaveHandler();
    }

    @Override
    public MapStorage getMapStorage() {
        return upstream.world.getMapStorage();
    }

    @Override
    public Scoreboard getScoreboard() {
        return upstream.world.getScoreboard();
    }

    @Override
    public int getActualHeight() {
        return upstream.world.getActualHeight();
    }

    @Override
    public double getHorizon() {
        return upstream.world.getHorizon();
    }

    @Override
    public Biome getBiome(BlockPos pos) {
        return upstream.world.getBiome(pos);
    }

    @Override
    public Biome getBiomeForCoordsBody(BlockPos pos) {
        return upstream.world.getBiomeForCoordsBody(pos);
    }

    @Override
    public int getCombinedLight(BlockPos pos, int lightValue) {
        return (15 << 20 | 15 << 4);
    }

    @Override
    public void notifyBlockUpdate(
                                  BlockPos pos, IBlockState oldState, IBlockState newState, int flags) {
        if (isRemote || upstream == null || upstream.isDead) return;

        NBTTagCompound tileNbt = null;
        NBTTagCompound teUpdateNbt = null;
        TileEntity te = getTileEntity(pos);
        if (te != null && !te.isInvalid()) {
            tileNbt = new NBTTagCompound();
            te.writeToNBT(tileNbt);
            if (te instanceof SyncedTileEntityBase synced) {
                var pkt = synced.getUpdatePacket();
                if (pkt != null) teUpdateNbt = pkt.getNbtCompound();
            }
        }

        GregTechAPI.networkHandler.sendToAllTracking(
                new SPacketPocketBlockUpdate(upstream.getEntityId(), pos, newState, tileNbt, teUpdateNbt), upstream);
    }

    @Override
    public void tick() {
        super.tick();
        this.profiler.startSection("tickPending");
        this.tickUpdates(false);
        this.profiler.endSection();
        this.profiler.startSection("blockEvents");
        this.fireBlockEvents();
        this.profiler.endSection();
        chunkProvider.tick();
        updateEntities();
    }

    public Long2ObjectMap<Chunk> getLoadedChunks() {
        return loadedChunks;
    }

    public void destroy() {
        for (Chunk chunk : loadedChunks.values()) {
            chunk.onUnload();
        }
        loadedChunks.clear();
        pendingTickListEntriesHashSet.clear();
        pendingTickListEntriesTreeSet.clear();
        pendingTickListEntriesThisTick.clear();
    }
}
