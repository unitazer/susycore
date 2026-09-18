package supersymmetry.api.phys;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.DataSerializers;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.network.play.server.SPacketBlockChange;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.common.registry.IEntityAdditionalSpawnData;

import org.jetbrains.annotations.Nullable;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import supersymmetry.api.SusyLog;
import supersymmetry.api.block.BlockExtraDataHandler;
import supersymmetry.api.block.BlockExtraDataRegistry;
import supersymmetry.api.phys.constraint.Axis;
import supersymmetry.api.phys.constraint.PhysicsConstraint;
import supersymmetry.api.subworld.SubWorldAllocator;
import supersymmetry.api.subworld.SubWorldContainer;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.api.subworld.SubWorldRemovalReason;
import supersymmetry.api.util.Quaternion;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;
import supersymmetry.common.Native;
import supersymmetry.common.network.SPacketSubworldPlotSync;

public class PhysicsWorldEntity extends Entity implements IEntityAdditionalSpawnData, SubWorldPlot.GrowthListener {

    private static final DataParameter<Quaternion> POSE_ROTATION = EntityDataManager.createKey(PhysicsWorldEntity.class,
            QuaternionDataSerializer.INSTANCE);

    private static final DataParameter<Float> POSE_RX = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    private static final DataParameter<Float> POSE_RY = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    private static final DataParameter<Float> POSE_RZ = EntityDataManager.createKey(PhysicsWorldEntity.class,
            DataSerializers.FLOAT);

    private SubWorldPlot plot;
    private boolean plotRelocated;
    private Vec3d plotSize;
    private AxisAlignedBB plotAABB;
    private AxisAlignedBB contentAABB;
    private AxisAlignedBB contentLocalBox;
    private boolean contentDirty = true;
    private final Set<EntityPlayerMP> lastTrackedPlayers = new ObjectOpenHashSet<>();
    private Vec3d prevPosePos;
    private Quaternion prevPoseRot;

    private static final Map<SubWorldPlot, PhysicsWorldEntity> PLOT_ENTITIES = new ConcurrentHashMap<>();
    private static final List<AxisAlignedBB> BODY_AABB_TMP = new ArrayList<>();

    private boolean bodyCreated;
    private int bodyEpoch;
    private final IntSet syncedSubchunks = new IntOpenHashSet();
    private final List<PhysicsConstraint> constraints = new ArrayList<>();
    private boolean clientContentDirty;

    @Nullable public static PhysicsWorldEntity forPlot(SubWorldPlot plot) {
        return plot == null ? null : PLOT_ENTITIES.get(plot);
    }

    @Nullable public static PhysicsWorldEntity forBlockPos(World world, BlockPos globalPos) {
        return forPlot(SubWorldRegistry.find(world, globalPos.getX() >> 4, globalPos.getZ() >> 4));
    }

    @Nullable public static PhysicsWorldEntity fromRaycastHit(World world, Rapier.RaycastHit hit) {
        if (hit == null || hit.tag < 0) return null;
        Entity e = world.getEntityByID(hit.tag);
        return e instanceof PhysicsWorldEntity ? (PhysicsWorldEntity) e : null;
    }

    public static Collection<PhysicsWorldEntity> activeBodies() {
        return PLOT_ENTITIES.values();
    }

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
        Vec3d rp = getRotationPoint();
        AxisAlignedBB local = new AxisAlignedBB(
                1.0 - rp.x, 1.0 - rp.y, 1.0 - rp.z,
                1.0 + plotSize.x - rp.x, 1.0 + plotSize.y - rp.y, 1.0 + plotSize.z - rp.z);
        plotAABB = currentPose().worldBoxFromBodyRelative(local);
        refreshContentBounds();
        setEntityBoundingBox(getEntityBoundingBox());
    }

    private void refreshContentBounds() {
        if (plot == null || plotSize == null) {
            contentAABB = null;
            return;
        }
        if (contentDirty) {
            contentLocalBox = scanContentLocalBox();
            contentDirty = false;
        }
        if (contentLocalBox == null) {
            contentAABB = bodyCreated ? new AxisAlignedBB(posX, posY, posZ, posX, posY, posZ) : null;
            return;
        }
        contentAABB = currentPose().worldBoxFromBodyRelative(contentLocalBox);
    }

    @Nullable private AxisAlignedBB scanContentLocalBox() {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        boolean any = false;
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            int lx = chunk.x - plot.getOriginChunkX();
            int lz = chunk.z - plot.getOriginChunkZ();
            ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
            for (int ly = 0; ly < storages.length; ly++) {
                ExtendedBlockStorage storage = storages[ly];
                if (storage == null || storage.isEmpty()) continue;
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            if (storage.get(x, y, z).getBlock() == Blocks.AIR) continue;
                            any = true;
                            minX = Math.min(minX, lx * 16 + x);
                            minY = Math.min(minY, ly * 16 + y - SubWorldPlot.RENDER_ORIGIN_Y);
                            minZ = Math.min(minZ, lz * 16 + z);
                            maxX = Math.max(maxX, lx * 16 + x + 1);
                            maxY = Math.max(maxY, ly * 16 + y + 1 - SubWorldPlot.RENDER_ORIGIN_Y);
                            maxZ = Math.max(maxZ, lz * 16 + z + 1);
                        }
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        Vec3d rp = getRotationPoint();
        return new AxisAlignedBB(
                minX - rp.x, minY - rp.y, minZ - rp.z,
                maxX - rp.x, maxY - rp.y, maxZ - rp.z);
    }

    @Override
    public AxisAlignedBB getEntityBoundingBox() {
        if (contentAABB != null) return contentAABB;
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

    public Vec3d getPrevPosePos() {
        return prevPosePos != null ? prevPosePos : getPositionVector();
    }

    public Quaternion getPrevPoseRot() {
        return prevPoseRot != null ? prevPoseRot : getRotation();
    }

    public RigidPose currentPose() {
        return new RigidPose(getPositionVector(), getRotation(), getRotationPoint());
    }

    public RigidPose prevPose() {
        return new RigidPose(getPrevPosePos(), getPrevPoseRot(), getRotationPoint());
    }

    public RigidPose renderPose(float partialTicks) {
        return currentPose().interpolated(prevPose(), partialTicks);
    }

    public Vec3d worldPointFromLocal(Vec3d local) {
        return currentPose().worldFromPlotLocal(local);
    }

    public Vec3d worldPointFromLocal(BlockPos local) {
        return worldPointFromLocal(new Vec3d(local));
    }

    public Vec3d plotLocalFromHeap(Vec3d heapPoint) {
        return heapPoint.subtract(new Vec3d(plot.getRenderOrigin()));
    }

    @Nullable public SubWorldPlot getPlot() {
        return plot;
    }

    public void attachPlot(SubWorldPlot plot) {
        this.plot = plot;
        plot.setGrowthListener(this);
        PLOT_ENTITIES.put(plot, this);
    }

    public boolean hasPhysicsBody() {
        return bodyCreated;
    }

    @Nullable public BlockPos getLocalPos(BlockPos globalPos) {
        return plot == null ? null : plot.toLocal(globalPos);
    }

    public void applyImpulseAtPoint(Vec3d worldPoint, Vec3d impulse) {
        if (!bodyCreated) return;
        Rapier.applyImpulseAtPoint(world, getEntityId(), worldPoint, impulse);
    }

    public int getBodyEpoch() {
        return bodyEpoch;
    }

    public boolean hasColliders() {
        return !syncedSubchunks.isEmpty();
    }

    public void addConstraint(PhysicsConstraint constraint) {
        UUID other = constraint.getOther();
        if (other != null) {
            if (getConstraintFor(other) != null) {
                SusyLog.logger.warn("already constrained toward {}, ignoring the new constraint", other);
                return;
            }
            PhysicsWorldEntity endpoint = PhysicsConstraint.resolveEndpoint(world, other);
            if (endpoint != null && endpoint.getConstraintFor(getUniqueID()) != null) {
                SusyLog.logger.warn("{} already holds a constraint toward this body, ignoring", other);
                return;
            }
        }
        constraints.add(constraint);
    }

    public void removeConstraint(PhysicsConstraint constraint) {
        constraint.teardown(world);
        constraints.remove(constraint);
    }

    public List<PhysicsConstraint> getConstraints() {
        return Collections.unmodifiableList(constraints);
    }

    @Nullable public PhysicsConstraint getConstraintFor(UUID other) {
        for (PhysicsConstraint constraint : constraints) {
            if (other != null && other.equals(constraint.getOther())) {
                return constraint;
            }
        }
        return null;
    }

    public boolean setJointMotor(PhysicsConstraint constraint, Axis axis, double targetPos, double targetVel,
                                 double stiffness, double damping, double maxForce, boolean forceBased) {
        if (!constraints.contains(constraint)) {
            return false;
        }
        return constraint.setMotor(world, axis, targetPos, targetVel, stiffness, damping, maxForce, forceBased);
    }

    public boolean setJointMotor(PhysicsConstraint constraint, Axis axis, double targetPos, double stiffness,
                                 double damping) {
        return setJointMotor(constraint, axis, targetPos, 0.0, stiffness, damping, Double.MAX_VALUE, false);
    }

    private static final int MAX_BODY_CHUNKS = 256;

    private int subchunkKey(int lx, int ly, int lz) {
        return (lx << 16) | (lz << 8) | ly;
    }

    private void ensurePhysicsBody() {
        if (world.isRemote || bodyCreated || plot == null || !Native.ENABLED) return;
        if (Rapier.worldId(world) < 0) return;
        if (plot.getSizeChunksX() >= MAX_BODY_CHUNKS || plot.getSizeChunksZ() >= MAX_BODY_CHUNKS) {
            SusyLog.logger.error("plot {} too large for a physics body ({} x {} chunks)",
                    plot, plot.getSizeChunksX(), plot.getSizeChunksZ());
            return;
        }
        Quaternion rot = getRotation();
        if (!Rapier.createChunkletBody(world, getEntityId(), posX, posY, posZ, rot)) return;
        bodyCreated = true;
        bodyEpoch++;
        syncedSubchunks.clear();
        contentDirty = true;
        Rapier.setChunkletBodyPose(world, getEntityId(), getPositionVector(), rot,
                new Vec3d(motionX, motionY, motionZ));
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            syncChunkToBody(chunk);
        }
        refreshContentBounds();
    }

    private void removePhysicsBody() {
        if (!bodyCreated) return;
        bodyCreated = false;
        bodyEpoch++;
        syncedSubchunks.clear();
        contentDirty = true;
        refreshContentBounds();
        for (PhysicsConstraint constraint : constraints) {
            constraint.teardown(world);
        }
        if (Native.ENABLED) {
            Rapier.removeChunkletBody(world, getEntityId());
        }
    }

    private void syncChunkToBody(Chunk chunk) {
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        for (int sy = 0; sy < storages.length; sy++) {
            if (storages[sy] == null || storages[sy].isEmpty()) continue;
            syncSubchunkToBody(chunk, storages[sy]);
        }
    }

    private void syncSubchunkToBody(Chunk chunk, ExtendedBlockStorage storage) {
        contentDirty = true;
        int lx = chunk.x - plot.getOriginChunkX();
        int lz = chunk.z - plot.getOriginChunkZ();
        int ly = storage.getYLocation() >> 4;
        int key = subchunkKey(lx, ly, lz);
        BlockPos.PooledMutableBlockPos tmp = BlockPos.PooledMutableBlockPos.retain();
        int[] handles = Rapier.computePlotSubchunkColliderInfo(plot, chunk, storage, BODY_AABB_TMP, tmp);
        tmp.release();
        if (handles == null) {
            if (syncedSubchunks.remove(key)) {
                Rapier.removeBodyChunk(world, getEntityId(), lx, ly, lz);
            }
            return;
        }
        Vec3d rp = getRotationPoint();
        double ox = (chunk.x - plot.getOriginChunkX()) * 16 - rp.x;
        double oy = storage.getYLocation() - SubWorldPlot.RENDER_ORIGIN_Y - rp.y;
        double oz = (chunk.z - plot.getOriginChunkZ()) * 16 - rp.z;
        Rapier.addBodyChunk(world, getEntityId(), lx, ly, lz, ox, oy, oz, handles);
        syncedSubchunks.add(key);
    }

    public void syncPlotBlockToBody(BlockPos local) {
        if (!bodyCreated) {
            despawnIfEmpty();
            return;
        }
        int globalY = local.getY() + SubWorldPlot.RENDER_ORIGIN_Y;
        int lx = local.getX() >> 4;
        int lz = local.getZ() >> 4;
        int ly = globalY >> 4;
        Chunk chunk = plot.getLoadedChunk(plot.getOriginChunkX() + lx, plot.getOriginChunkZ() + lz);
        if (chunk == null) return;
        ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
        if (ly >= 0 && ly < storages.length) {
            ExtendedBlockStorage storage = storages[ly];
            if (storage != null && !storage.isEmpty()) {
                if (!syncedSubchunks.contains(subchunkKey(lx, ly, lz))) {
                    syncSubchunkToBody(chunk, storage);
                }
            } else {
                if (syncedSubchunks.remove(subchunkKey(lx, ly, lz))) {
                    Rapier.removeBodyChunk(world, getEntityId(), lx, ly, lz);
                }
            }
        }
        BlockPos.PooledMutableBlockPos tmp = BlockPos.PooledMutableBlockPos.retain();
        for (int[] offset : Rapier.BLOCK_CHANGE_OFFSETS) {
            BlockPos neighbor = local.add(offset[0], offset[1], offset[2]);
            int nGlobalY = neighbor.getY() + SubWorldPlot.RENDER_ORIGIN_Y;
            int nlx = neighbor.getX() >> 4;
            int nlz = neighbor.getZ() >> 4;
            int nly = nGlobalY >> 4;
            int nKey = subchunkKey(nlx, nly, nlz);
            if (!syncedSubchunks.contains(nKey)) continue;
            BlockPos global = plot.toGlobal(neighbor);
            tmp.setPos(global);
            int handle = Rapier.computeBlockColliderHandle(world, plot, global, BODY_AABB_TMP, tmp);
            if (!Rapier.updateBodyChunkBlock(world, getEntityId(), nlx, nly, nlz, neighbor.getX() & 15,
                    nGlobalY & 15, neighbor.getZ() & 15, handle)) {
                resyncSubchunk(nlx, nly, nlz);
            }
        }
        tmp.release();
        contentDirty = true;
        refreshContentBounds();
        despawnIfEmpty();
    }

    private void despawnIfEmpty() {
        if (world.isRemote || isDead || plot == null || !isPlotContentEmpty()) {
            return;
        }
        SusyLog.logger.info("plot {} content is empty, despawning body", plot);
        setDead();
    }

    private boolean isPlotContentEmpty() {
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                if (storage != null && !storage.isEmpty()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void resyncSubchunk(int lx, int ly, int lz) {
        syncedSubchunks.remove(subchunkKey(lx, ly, lz));
        Chunk chunk = plot.getLoadedChunk(plot.getOriginChunkX() + lx, plot.getOriginChunkZ() + lz);
        if (chunk == null || ly < 0 || ly >= chunk.getBlockStorageArray().length) {
            Rapier.removeBodyChunk(world, getEntityId(), lx, ly, lz);
            return;
        }
        ExtendedBlockStorage storage = chunk.getBlockStorageArray()[ly];
        if (storage == null || storage.isEmpty()) {
            Rapier.removeBodyChunk(world, getEntityId(), lx, ly, lz);
            return;
        }
        syncSubchunkToBody(chunk, storage);
    }

    public void setPlotBlock(BlockPos local, IBlockState state, @Nullable NBTTagCompound tileData) {
        if (plot == null) {
            return;
        }
        BlockPos global = plot.toGlobal(local);
        ensurePlotCovers(global);
        local = plot.toLocal(global);
        global = plot.toGlobal(local);
        IBlockState oldState = plot.getBlockState(global);
        plot.setBlockState(global, state, tileData);
        syncPlotBlockToBody(local);
        if (!world.isRemote && oldState != state) {
            SPacketBlockChange packet = new SPacketBlockChange(world, global);
            for (EntityPlayerMP player : getTrackingPlayers()) {
                player.connection.sendPacket(packet);
            }
        }
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

    public BlockStateInfo captureBlockState(BlockPos global, IBlockState state) {
        return new BlockStateInfo(global, state, serializeTileData(global), captureExtraState(global, state));
    }

    @Nullable private NBTTagCompound serializeTileData(BlockPos global) {
        TileEntity te = plot.getTileEntity(global);
        if (te == null) {
            return null;
        }
        try {
            return te.serializeNBT();
        } catch (Exception e) {
            SusyLog.logger.warn("failed to serialize the te at {}", global, e);
            return null;
        }
    }

    @Nullable private NBTTagCompound captureExtraState(BlockPos global, IBlockState state) {
        BlockExtraDataHandler handler = BlockExtraDataRegistry.get(state.getBlock());
        if (handler == null) {
            return null;
        }
        try {
            return handler.capture(world, global, state);
        } catch (Exception e) {
            SusyLog.logger.warn("failed to capture extra state at {}", global, e);
            return null;
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
        prevPosePos = getPositionVector();
        prevPoseRot = getRotation();
        super.onEntityUpdate();
        if (world.isRemote) {
            if (clientContentDirty) {
                clientContentDirty = false;
                recomputeClientContentBounds();
            }
            refreshPlotAABB();
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
            Set<EntityPlayerMP> tracked = getTrackingPlayers();
            for (EntityPlayerMP player : tracked) {
                GregTechAPI.networkHandler.sendTo(packet, player);
            }
            if (tracked.isEmpty()) {
                resyncFormedMultiblocks();
            }
        }
        ensurePhysicsBody();
        if (bodyCreated) {
            RigidPose pose = Rapier.getBodyPose(world, getEntityId());
            if (pose != null) {
                setPosition(pose.position.x, pose.position.y, pose.position.z);
                dataManager.set(POSE_ROTATION, pose.rotation);
                motionX = pose.velocity.x;
                motionY = pose.velocity.y;
                motionZ = pose.velocity.z;
            }
        }
        if (bodyCreated && Native.ENABLED) {
            for (PhysicsConstraint constraint : constraints) {
                constraint.update(this);
            }
            constraints.removeIf(PhysicsConstraint::isDead);
        }
        BodySplitter.runPendingCheck(this);
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

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!world.isRemote) {
            ensurePhysicsBody();
        }
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
                        BlockStateInfo captured = captureBlockState(global, state);
                        list.add(new BlockStateInfo(local, captured.state, captured.tileData, captured.extra));
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
        getRotation().writeToBuf(pb);
        Vec3d rp = getRotationPoint();
        pb.writeFloat((float) rp.x);
        pb.writeFloat((float) rp.y);
        pb.writeFloat((float) rp.z);
        float[] size = getPlotSize();
        pb.writeFloat(size[0]);
        pb.writeFloat(size[1]);
        pb.writeFloat(size[2]);
        BlockStateInfo.writeBlocks(pb, collectBlocks());
    }

    @Override
    public void readSpawnData(ByteBuf buf) {
        try {
            PacketBuffer pb = new PacketBuffer(buf);
            int originChunkX = pb.readInt();
            int originChunkZ = pb.readInt();
            int sizeChunksX = pb.readInt();
            int sizeChunksZ = pb.readInt();
            setRotation(Quaternion.readFromBuf(pb));
            setRotationPoint(pb.readFloat(), pb.readFloat(), pb.readFloat());
            setPlotSize(pb.readFloat(), pb.readFloat(), pb.readFloat());
            List<BlockStateInfo> blocks = BlockStateInfo.readBlocks(pb);
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
        BlockStateInfo.writeBlocksToNBT(compound, collectBlocks());
        PhysicsConstraint.writeConstraints(constraints, compound);
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
        constraints.clear();
        constraints.addAll(PhysicsConstraint.readConstraints(compound));
        if (sizeChunksX <= 0 || sizeChunksZ <= 0) {
            return;
        }
        List<BlockStateInfo> blocks = BlockStateInfo.readBlocksFromNBT(compound);
        reallocatePlot(sizeChunksX, sizeChunksZ, blocks,
                new SubWorldAllocator.Rect(originChunkX, originChunkZ, sizeChunksX, sizeChunksZ));
    }

    private void ensurePlotCovers(BlockPos global) {
        if (world.isRemote || plot == null) {
            return;
        }
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null) {
            return;
        }
        container.ensureCovered(plot, global.getX() >> 4, global.getZ() >> 4);
    }

    @Override
    public void onPlotGrown(SubWorldPlot oldPlot, SubWorldPlot newPlot, int plotLocalShiftX, int plotLocalShiftZ,
                            int heapShiftX, int heapShiftZ) {
        PLOT_ENTITIES.remove(oldPlot);
        this.plot = newPlot;
        PLOT_ENTITIES.put(newPlot, this);
        boolean resyncChunklets = plotLocalShiftX != 0 || plotLocalShiftZ != 0;
        if (resyncChunklets) {
            setRotationPoint(getRotationPoint().add(new Vec3d(plotLocalShiftX, 0, plotLocalShiftZ)));
            remapConstraintAnchors(plotLocalShiftX, plotLocalShiftZ);
        }
        if (resyncChunklets || heapShiftX != 0 || heapShiftZ != 0) {
            resyncBodyChunklets();
        }
        contentDirty = true;
        this.plotRelocated = true;
        refreshPlotAABB();
    }

    private void remapConstraintAnchors(int dx, int dz) {
        for (PhysicsConstraint constraint : constraints) {
            constraint.remapAnchors(dx, dz, true);
        }
        if (world.isRemote) {
            return;
        }
        for (PhysicsWorldEntity entity : activeBodies()) {
            if (entity == this) {
                continue;
            }
            for (PhysicsConstraint constraint : entity.getConstraints()) {
                if (getUniqueID().equals(constraint.getOther())) {
                    constraint.remapAnchors(dx, dz, false);
                }
            }
        }
    }

    private void resyncBodyChunklets() {
        if (!bodyCreated) {
            return;
        }
        bodyEpoch++;
        for (int key : syncedSubchunks.toIntArray()) {
            Rapier.removeBodyChunk(world, getEntityId(), key >> 16, key & 0xFF, (key >> 8) & 0xFF);
        }
        syncedSubchunks.clear();
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            syncChunkToBody(chunk);
        }
    }

    public static boolean resyncingFormedMultiblocks;

    public void resyncFormedMultiblocks() {
        resyncingFormedMultiblocks = true;
        try {
            for (Chunk chunk : plot.getLoadedChunks().values()) {
                for (TileEntity te : new ArrayList<>(chunk.getTileEntityMap().values())) {
                    if (!(te instanceof MetaTileEntityHolder holder)) {
                        continue;
                    }
                    MetaTileEntity mte = holder.getMetaTileEntity();
                    if (mte instanceof MultiblockControllerBase controller && controller.isStructureFormed()) {
                        controller.invalidateStructure();
                        controller.checkStructurePattern();
                    }
                }
            }
        } finally {
            resyncingFormedMultiblocks = false;
        }
    }

    public Set<EntityPlayerMP> getTrackingPlayers() {
        if (world.isRemote || !(world instanceof WorldServer)) {
            return Collections.emptySet();
        }
        AxisAlignedBB box = getEntityBoundingBox();
        int margin = ((WorldServer) world).getMinecraftServer().getPlayerList().getViewDistance() * 16 + 32;
        Set<EntityPlayerMP> tracking = new ObjectOpenHashSet<>();
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
            removePhysicsBody();
            SubWorldRegistry.markRemoved(world, plot, SubWorldRemovalReason.ENTITY_DEAD);
            PLOT_ENTITIES.remove(plot);
            plot = null;
        }
    }

    private void rebuildPlot(int originChunkX, int originChunkZ, int sizeChunksX, int sizeChunksZ,
                             List<BlockStateInfo> blocks) {
        if (plot != null) {
            SubWorldContainer container = SubWorldContainer.getContainer(world);
            if (container != null) {
                container.replace(plot);
            } else {
                plot.destroy();
            }
            PLOT_ENTITIES.remove(plot);
            removePhysicsBody();
        }
        SubWorldPlot rebuilt = new SubWorldPlot(world, originChunkX, originChunkZ, sizeChunksX, sizeChunksZ);
        SubWorldRegistry.register(rebuilt);
        buildPlot(rebuilt, blocks);
        attachPlot(rebuilt);
        if (world.isRemote) {
            recomputeClientContentBounds();
            BlockPos origin = rebuilt.getRenderOrigin();
            world.markBlockRangeForRenderUpdate(origin.getX(), 0, origin.getZ(),
                    origin.getX() + (sizeChunksX << 4) - 1, 255, origin.getZ() + (sizeChunksZ << 4) - 1);
        }
    }

    public void markClientContentDirty() {
        clientContentDirty = true;
    }

    private void recomputeClientContentBounds() {
        if (plot == null || plotSize == null) {
            contentAABB = null;
            return;
        }
        syncedSubchunks.clear();
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            int lx = chunk.x - plot.getOriginChunkX();
            int lz = chunk.z - plot.getOriginChunkZ();
            ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
            for (int sy = 0; sy < storages.length; sy++) {
                ExtendedBlockStorage storage = storages[sy];
                if (storage == null || storage.isEmpty()) continue;
                syncedSubchunks.add(subchunkKey(lx, storage.getYLocation() >> 4, lz));
            }
        }
        contentDirty = true;
        refreshContentBounds();
    }

    private void buildPlot(SubWorldPlot target, List<BlockStateInfo> blocks) {
        for (BlockStateInfo info : blocks) {
            try {
                BlockPos global = target.toGlobal(info.pos);
                target.setBlockState(global, info.state, info.tileData);
                BlockExtraDataRegistry.restore(world, info.state.getBlock(), global, info.extra);
            } catch (Exception e) {
                SusyLog.logger.warn("failed to rebuild block at {} ({})", info.pos, info.state, e);
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
            SubWorldAllocator.Rect liveRect = plot.getRect().copy();
            container.replace(plot);
            PLOT_ENTITIES.remove(plot);
            removePhysicsBody();
            container.freeOwnedRect(liveRect);
            SubWorldPlot reallocated = container.allocatePlot(sizeChunksX, sizeChunksZ);
            buildPlot(reallocated, blocks);
            attachPlot(reallocated);
            plotRelocated = true;
            refreshPlotAABB();
            return;
        }
        if (container.isPlotRectAllocated(previous)) {
            SubWorldPlot reclaimed = new SubWorldPlot(world, previous.x, previous.z, sizeChunksX, sizeChunksZ);
            SubWorldRegistry.register(reclaimed);
            buildPlot(reclaimed, blocks);
            attachPlot(reclaimed);
            plotRelocated = true;
            refreshPlotAABB();
            return;
        }
        SusyLog.logger.warn("saved plot rect {} not allocated, reallocating without preserving heap coords", previous);
        SubWorldPlot reallocated = container.allocatePlot(sizeChunksX, sizeChunksZ);
        buildPlot(reallocated, blocks);
        attachPlot(reallocated);
        plotRelocated = true;
        refreshPlotAABB();
    }
}
