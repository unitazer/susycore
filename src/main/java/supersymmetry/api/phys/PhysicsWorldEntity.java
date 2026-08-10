package supersymmetry.api.phys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import gregtech.api.GregTechAPI;
import io.netty.buffer.ByteBuf;
import supersymmetry.api.SusyLog;
import supersymmetry.api.block.BlockExtraDataHandler;
import supersymmetry.api.block.BlockExtraDataRegistry;
import supersymmetry.api.subworld.SubWorldAllocator;
import supersymmetry.api.subworld.SubWorldContainer;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.api.subworld.SubWorldRemovalReason;
import supersymmetry.api.util.SuSyUtility;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;
import supersymmetry.common.network.SPacketSubworldPlotSync;

public class PhysicsWorldEntity extends Entity implements IEntityAdditionalSpawnData {

    public static class BlockStateInfo {

        public final BlockPos pos;
        public final IBlockState state;
        @Nullable
        public final NBTTagCompound tileData;
        @Nullable
        public final NBTTagCompound extra;

        public BlockStateInfo(BlockPos pos, IBlockState state) {
            this(pos, state, null, null);
        }

        public BlockStateInfo(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileData) {
            this(pos, state, tileData, null);
        }

        public BlockStateInfo(BlockPos pos, IBlockState state, @Nullable NBTTagCompound tileData,
                              @Nullable NBTTagCompound extra) {
            this.pos = pos;
            this.state = state;
            this.tileData = tileData;
            this.extra = extra;
        }
    }

    private static final DataParameter<Quaternion> POSE_ROTATION = EntityDataManager.createKey(PhysicsWorldEntity.class,
            QuaternionDataSerializer.INSTANCE);

    private static final DataParameter<Float> POSE_RX = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    private static final DataParameter<Float> POSE_RY = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    private static final DataParameter<Float> POSE_RZ = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    public static void writePose(PacketBuffer buf, Quaternion rot, Vec3d rp, float[] size) {
        buf.writeFloat((float) rot.getX());
        buf.writeFloat((float) rot.getY());
        buf.writeFloat((float) rot.getZ());
        buf.writeFloat((float) rot.getW());
        buf.writeFloat((float) rp.x);
        buf.writeFloat((float) rp.y);
        buf.writeFloat((float) rp.z);
        buf.writeFloat(size[0]);
        buf.writeFloat(size[1]);
        buf.writeFloat(size[2]);
    }

    public static Quaternion readRotation(PacketBuffer buf) {
        float x = buf.readFloat();
        float y = buf.readFloat();
        float z = buf.readFloat();
        float w = buf.readFloat();
        return new Quaternion(w, x, y, z);
    }

    public static void writeBlocks(PacketBuffer buf, List<BlockStateInfo> blocks) {
        buf.writeVarInt(blocks.size());
        for (BlockStateInfo info : blocks) {
            buf.writeVarInt(info.pos.getX());
            buf.writeVarInt(info.pos.getY());
            buf.writeVarInt(info.pos.getZ());
            buf.writeVarInt(Block.getStateId(info.state));
            buf.writeCompoundTag(info.tileData);
            buf.writeCompoundTag(info.extra);
        }
    }

    public static List<BlockStateInfo> readBlocks(PacketBuffer buf) throws IOException {
        int count = buf.readVarInt();
        List<BlockStateInfo> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int x = buf.readVarInt();
            int y = buf.readVarInt();
            int z = buf.readVarInt();
            int blockId = buf.readVarInt();
            NBTTagCompound tileData = buf.readCompoundTag();
            NBTTagCompound extra = buf.readCompoundTag();
            blocks.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData, extra));
        }
        return blocks;
    }

    public static void writeBlocksToNBT(NBTTagCompound tag, List<BlockStateInfo> blocks) {
        NBTTagList blockList = new NBTTagList();
        for (BlockStateInfo info : blocks) {
            NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("x", info.pos.getX());
            entry.setShort("y", (short) info.pos.getY());
            entry.setInteger("z", info.pos.getZ());
            entry.setInteger("b", Block.getStateId(info.state));
            if (info.tileData != null) {
                entry.setTag("te", info.tileData);
            }
            if (info.extra != null) {
                entry.setTag("e", info.extra);
            }
            blockList.appendTag(entry);
        }
        tag.setTag("plotBlocks", blockList);
    }

    public static List<BlockStateInfo> readBlocksFromNBT(NBTTagCompound tag) {
        List<BlockStateInfo> blocks = new ArrayList<>();
        if (tag.hasKey("plotBlocks", Constants.NBT.TAG_LIST)) {
            NBTTagList blockList = tag.getTagList("plotBlocks", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < blockList.tagCount(); i++) {
                NBTTagCompound entry = blockList.getCompoundTagAt(i);
                int x = entry.getInteger("x");
                int y = entry.getShort("y");
                int z = entry.getInteger("z");
                int blockId = entry.getInteger("b");
                NBTTagCompound tileData = entry.hasKey("te", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("te") :
                        null;
                NBTTagCompound extra = entry.hasKey("e", Constants.NBT.TAG_COMPOUND) ? entry.getCompoundTag("e") : null;
                blocks.add(new BlockStateInfo(new BlockPos(x, y, z), Block.getStateById(blockId), tileData, extra));
            }
        }
        return blocks;
    }

    private SubWorldPlot plot;
    private boolean plotRelocated;
    private Vec3d plotSize;
    private AxisAlignedBB plotAABB;
    private final Set<EntityPlayerMP> lastTrackedPlayers = new HashSet<>();
    private Quaternion prevRotation;
    private Vec3d prevRotationPoint;

    public PhysicsWorldEntity(World world) {
        super(world);
    }

    public void setPlotSize(float sx, float sy, float sz) {
        this.plotSize = new Vec3d(sx, sy, sz);
    }

    public float[] getPlotSize() {
        if (plotSize == null) {
            return new float[] { 0.0f, 0.0f, 0.0f };
        }
        return new float[] { (float) plotSize.x, (float) plotSize.y, (float) plotSize.z };
    }

    public void refreshPlotAABB() {
        if (plotSize == null || plot == null) {
            return;
        }
        Quaternion q = getRotation();
        Vec3d rp = getRotationPoint();
        AxisAlignedBB local = new AxisAlignedBB(
                1.0 - rp.x, 1.0 - rp.y, 1.0 - rp.z,
                1.0 + plotSize.x - rp.x, 1.0 + plotSize.y - rp.y, 1.0 + plotSize.z - rp.z);
        AxisAlignedBB rotated = q.rotateAABB(local);
        plotAABB = new AxisAlignedBB(
                posX + rotated.minX, posY + rotated.minY, posZ + rotated.minZ,
                posX + rotated.maxX, posY + rotated.maxY, posZ + rotated.maxZ);
        setEntityBoundingBox(plotAABB);
    }

    @Override
    public AxisAlignedBB getEntityBoundingBox() {
        return plotAABB != null ? plotAABB : super.getEntityBoundingBox();
    }

    public void setRotation(float qx, float qy, float qz, float qw) {
        dataManager.set(POSE_ROTATION, new Quaternion(qw, qx, qy, qz));
    }

    public void setRotation(Quaternion q) {
        dataManager.set(POSE_ROTATION, q);
    }

    public Quaternion getRotation() {
        return dataManager.get(POSE_ROTATION);
    }

    public void setRotationPoint(float x, float y, float z) {
        dataManager.set(POSE_RX, x);
        dataManager.set(POSE_RY, y);
        dataManager.set(POSE_RZ, z);
    }

    public void setRotationPoint(Vec3d point) {
        setRotationPoint((float) point.x, (float) point.y, (float) point.z);
    }

    public Vec3d getRotationPoint() {
        return new Vec3d(dataManager.get(POSE_RX), dataManager.get(POSE_RY), dataManager.get(POSE_RZ));
    }

    public Quaternion getRenderRotation(float partialTicks) {
        Quaternion current = getRotation();
        if (prevRotation == null) {
            return current;
        }
        return prevRotation.slerp(current, partialTicks);
    }

    public Vec3d getRenderRotationPoint(float partialTicks) {
        Vec3d current = getRotationPoint();
        if (prevRotationPoint == null) {
            return current;
        }
        return SuSyUtility.lerp(prevRotationPoint, current, partialTicks);
    }

    @Nullable
    public SubWorldPlot getPlot() {
        return plot;
    }

    public void attachPlot(SubWorldPlot plot) {
        this.plot = plot;
    }

    public void setPlotBlock(BlockPos local, IBlockState state, @Nullable NBTTagCompound tileData) {
        if (plot == null) {
            return;
        }
        ensurePlotCovers(local);
        BlockPos global = plot.toGlobal(local);
        plot.setBlockState(global, state, tileData);
    }

    public void transferExtraState(BlockPos sourcePos, BlockPos local) {
        if (plot == null || world.isRemote) {
            return;
        }
        IBlockState source = world.getBlockState(sourcePos);
        BlockExtraDataHandler handler = BlockExtraDataRegistry.get(source.getBlock());
        if (handler == null) {
            return;
        }
        NBTTagCompound extra = handler.capture(world, sourcePos, source);
        if (extra != null) {
            handler.restore(world, plot.toGlobal(local), extra);
        }
    }

    public void applyPlotSync(int originChunkX, int originChunkZ, int sizeChunksX, int sizeChunksZ,
                              List<BlockStateInfo> blocks, Quaternion rot, Vec3d rp, float[] size) {
        setRotation(rot);
        setRotationPoint(rp);
        setPlotSize(size[0], size[1], size[2]);
        rebuildPlot(originChunkX, originChunkZ, sizeChunksX, sizeChunksZ, blocks);
        refreshPlotAABB();
    }

    @Override
    public void onEntityUpdate() {
        if (world.isRemote) {
            prevRotation = getRotation();
            prevRotationPoint = getRotationPoint();
            return;
        }
        if (ticksExisted >= 2) {
            Set<EntityPlayerMP> tracked = getTrackingPlayers();
            for (EntityPlayerMP player : tracked) {
                if (!lastTrackedPlayers.contains(player)) {
                    sendChunkPackets(player);
                }
            }
            lastTrackedPlayers.clear();
            lastTrackedPlayers.addAll(tracked);
        }
        if (plotRelocated) {
            plotRelocated = false;
            SPacketSubworldPlotSync packet = new SPacketSubworldPlotSync(this);
            for (EntityPlayerMP player : getTrackingPlayers()) {
                GregTechAPI.networkHandler.sendTo(packet, player);
            }
        }
        refreshPlotAABB();
    }

    @Override
    public void setDead() {
        destroyPlot();
        isDead = true;
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        destroyPlot();
    }

    public List<BlockStateInfo> collectBlocks() {
        ArrayList<BlockStateInfo> list = new ArrayList<>();
        if (plot == null) return list;
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 256; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockPos global = new BlockPos(chunk.x * 16 + x, y, chunk.z * 16 + z);
                        IBlockState state = plot.getBlockState(global);
                        if (state.getBlock() == Blocks.AIR) continue;
                        BlockPos local = plot.toLocal(global);
                        TileEntity te = plot.getTileEntity(global);
                        NBTTagCompound tileData = null;
                        if (te != null) {
                            try {
                                tileData = te.serializeNBT();
                            } catch (Exception e) {
                                SusyLog.logger.warn("PhysicsWorldEntity: failed to serialize a te {}", global, e);
                            }
                        }
                        NBTTagCompound extra = null;
                        BlockExtraDataHandler handler = BlockExtraDataRegistry.get(state.getBlock());
                        if (handler != null) {
                            try {
                                extra = handler.capture(world, global, state);
                            } catch (Exception e) {
                                SusyLog.logger.warn("PhysicsWorldEntity: failed to capture extra state at {}", global,
                                        e);
                            }
                        }
                        list.add(new BlockStateInfo(local, state, tileData, extra));
                    }
                }
            }
        }
        return list;
    }

    @Override
    public void writeSpawnData(ByteBuf buf) {
        PacketBuffer pb = new PacketBuffer(buf);
        pb.writeInt(plot != null ? plot.getOriginChunkX() : 0);
        pb.writeInt(plot != null ? plot.getOriginChunkZ() : 0);
        pb.writeInt(plot != null ? plot.getSizeChunksX() : 0);
        pb.writeInt(plot != null ? plot.getSizeChunksZ() : 0);
        writePose(pb, getRotation(), getRotationPoint(), getPlotSize());
        writeBlocks(pb, collectBlocks());
    }

    @Override
    public void readSpawnData(ByteBuf buf) {
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            int originChunkX = pb.readInt();
            int originChunkZ = pb.readInt();
            int sizeChunksX = pb.readInt();
            int sizeChunksZ = pb.readInt();
            setRotation(readRotation(pb));
            setRotationPoint(pb.readFloat(), pb.readFloat(), pb.readFloat());
            setPlotSize(pb.readFloat(), pb.readFloat(), pb.readFloat());
            List<BlockStateInfo> blocks = readBlocks(pb);
            rebuildPlot(originChunkX, originChunkZ, sizeChunksX, sizeChunksZ, blocks);
            refreshPlotAABB();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void entityInit() {
        dataManager.register(POSE_ROTATION, Quaternion.IDENTITY);
        dataManager.register(POSE_RX, 0.0f);
        dataManager.register(POSE_RY, 0.0f);
        dataManager.register(POSE_RZ, 0.0f);
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setInteger("plotX", plot != null ? plot.getOriginChunkX() : 0);
        compound.setInteger("plotZ", plot != null ? plot.getOriginChunkZ() : 0);
        compound.setInteger("plotW", plot != null ? plot.getSizeChunksX() : 0);
        compound.setInteger("plotH", plot != null ? plot.getSizeChunksZ() : 0);
        Quaternion rot = getRotation();
        Vec3d rp = getRotationPoint();
        compound.setFloat("qx", (float) rot.getX());
        compound.setFloat("qy", (float) rot.getY());
        compound.setFloat("qz", (float) rot.getZ());
        compound.setFloat("qw", (float) rot.getW());
        compound.setFloat("rx", (float) rp.x);
        compound.setFloat("ry", (float) rp.y);
        compound.setFloat("rz", (float) rp.z);
        if (plotSize != null) {
            compound.setFloat("sx", (float) plotSize.x);
            compound.setFloat("sy", (float) plotSize.y);
            compound.setFloat("sz", (float) plotSize.z);
        }
        writeBlocksToNBT(compound, collectBlocks());
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        int originChunkX = compound.getInteger("plotX");
        int originChunkZ = compound.getInteger("plotZ");
        int sizeChunksX = compound.getInteger("plotW");
        int sizeChunksZ = compound.getInteger("plotH");
        setRotation(compound.getFloat("qx"), compound.getFloat("qy"), compound.getFloat("qz"), compound.getFloat("qw"));
        setRotationPoint(compound.getFloat("rx"), compound.getFloat("ry"), compound.getFloat("rz"));
        if (compound.hasKey("sx") && compound.hasKey("sy") && compound.hasKey("sz")) {
            setPlotSize(compound.getFloat("sx"), compound.getFloat("sy"), compound.getFloat("sz"));
        }
        if (world.isRemote) {
            return;
        }
        if (sizeChunksX <= 0 || sizeChunksZ <= 0) {
            return;
        }
        List<BlockStateInfo> blocks = readBlocksFromNBT(compound);
        reallocatePlot(sizeChunksX, sizeChunksZ, blocks,
                new SubWorldAllocator.Rect(originChunkX, originChunkZ, sizeChunksX, sizeChunksZ));
    }

    private void ensurePlotCovers(BlockPos local) {
        if (world.isRemote || plot == null) {
            return;
        }
        int localChunkX = local.getX() >> 4;
        int localChunkZ = local.getZ() >> 4;
        if (localChunkX >= 0 && localChunkZ >= 0 && localChunkX < plot.getSizeChunksX() &&
                localChunkZ < plot.getSizeChunksZ()) {
            return;
        }
        if (localChunkX < 0 || localChunkZ < 0) {
            SusyLog.logger.warn("PhysicsWorldEntity: refusing to grow plot {} backward (local={})", plot, local);
            return;
        }
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null) {
            return;
        }
        int targetW = Math.max(plot.getSizeChunksX(), localChunkX + 1);
        int targetH = Math.max(plot.getSizeChunksZ(), localChunkZ + 1);
        SubWorldPlot grown = container.growPlot(plot, targetW, targetH);
        if (grown != plot) {
            this.plot = grown;
            this.plotRelocated = true;
        }
    }

    private Set<EntityPlayerMP> getTrackingPlayers() {
        if (world.isRemote || !(world instanceof WorldServer)) {
            return Collections.emptySet();
        }
        AxisAlignedBB box = getEntityBoundingBox();
        int margin = ((WorldServer) world).getMinecraftServer().getPlayerList().getViewDistance() * 16 + 32;
        Set<EntityPlayerMP> tracking = new HashSet<>();
        for (EntityPlayer player : world.playerEntities) {
            if (player instanceof EntityPlayerMP &&
                    player.posX >= box.minX - margin && player.posX <= box.maxX + margin &&
                    player.posY >= box.minY - margin && player.posY <= box.maxY + margin &&
                    player.posZ >= box.minZ - margin && player.posZ <= box.maxZ + margin) {
                tracking.add((EntityPlayerMP) player);
            }
        }
        return tracking;
    }

    private void sendChunkPackets(EntityPlayerMP player) {
        if (plot == null) {
            return;
        }
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            SPacketChunkData packet = new SPacketChunkData(chunk, 65535);
            player.connection.sendPacket(packet);
        }
    }

    private void destroyPlot() {
        if (plot != null) {
            if (world.isRemote) {
                SubWorldChunkRenderer.pruneDead();
            }
            SubWorldRegistry.markRemoved(world, plot, SubWorldRemovalReason.ENTITY_DEAD);
            plot = null;
        }
    }

    private void rebuildPlot(int originChunkX, int originChunkZ, int sizeChunksX, int sizeChunksZ,
                             List<BlockStateInfo> blocks) {
        if (plot != null) {
            SubWorldContainer container = SubWorldContainer.getContainer(world);
            if (container != null) {
                container.replace(plot, SubWorldRemovalReason.REMOVED);
            } else {
                plot.destroy();
            }
        }
        SubWorldPlot rebuilt = new SubWorldPlot(world, originChunkX, originChunkZ, sizeChunksX, sizeChunksZ);
        SubWorldRegistry.register(rebuilt);
        buildPlot(rebuilt, blocks);
        plot = rebuilt;
    }

    private void buildPlot(SubWorldPlot target, List<BlockStateInfo> blocks) {
        for (BlockStateInfo info : blocks) {
            try {
                BlockPos global = target.toGlobal(info.pos);
                target.setBlockState(global, info.state, info.tileData);
                if (info.extra != null) {
                    BlockExtraDataHandler handler = BlockExtraDataRegistry.get(info.state.getBlock());
                    if (handler != null) {
                        handler.restore(world, global, info.extra);
                    }
                }
            } catch (Exception e) {
                SusyLog.logger.warn("PhysicsWorldEntity: failed to rebuild block at {} ({})", info.pos, info.state, e);
            }
        }
        target.seedLight();
    }

    private void reallocatePlot(int sizeChunksX, int sizeChunksZ, List<BlockStateInfo> blocks,
                                SubWorldAllocator.Rect previous) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null) {
            rebuildPlot(previous.x, previous.z, sizeChunksX, sizeChunksZ, blocks);
            return;
        }
        if (plot != null) {
            container.replace(plot, SubWorldRemovalReason.REMOVED);
        }
        container.freeOwnedRect(previous);
        SubWorldPlot reallocated = container.allocatePlot(sizeChunksX, sizeChunksZ);
        buildPlot(reallocated, blocks);
        plot = reallocated;
        plotRelocated = true;
        refreshPlotAABB();
    }
}
