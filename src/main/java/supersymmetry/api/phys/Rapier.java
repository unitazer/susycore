package supersymmetry.api.phys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.PooledMutableBlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.mojang.realmsclient.util.Pair;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import supersymmetry.api.SusyLog;

public class Rapier {

    // IBlockState -> collision data handle
    // ideally this would hash the coords aswell but thats probably too much
    // doesnt work well with TE's
    static Object2IntOpenHashMap<IBlockState> blockStateCache = new Object2IntOpenHashMap<>();
    public static HashMap<World, Integer> initializedWorlds = new HashMap<>();
    private static final AxisAlignedBB CHUNK_BOX = new AxisAlignedBB(
            -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);

    // to be passed to rust as a mutable array reference so that you could write rotation/position
    // data to it
    private static double[] cache = new double[32];

    public static ArrayList<AbstractPhysicsEntity> entities = new ArrayList<>();

    // drag should be very low unless its somewhere in an endless ocean
    public static void initialize_world(World world, float gravity, double drag) {
        if (initializedWorlds.containsKey(world)) {
            return;
        }
        var x = initializedWorlds.size();
        initialize(x, gravity, drag);
        initializedWorlds.put(world, Integer.valueOf(x));
    }

    public static void destroyWorld(World world) {
        Integer dim = initializedWorlds.remove(world);
        if (dim != null) {
            destroyWorld(dim);
            if (initializedWorlds.isEmpty()) {
                reset();
                blockStateCache.clear();
                entities.clear();
            }
        }
    }

    public static void handleChunkAddition(Chunk chunk) {
        World world = chunk.getWorld();
        if (!initializedWorlds.containsKey(world)) {
            SusyLog.logger.error("handleChunkAddition on a chunk from an uninitialized world");
            return;
        }

        List<AxisAlignedBB> aabb_tmp = new ArrayList<>();
        long startTime = System.nanoTime();
        PooledMutableBlockPos pos = BlockPos.PooledMutableBlockPos.retain();

        Integer x = initializedWorlds.get(world);
        for (int i = 0; i < chunk.storageArrays.length; i++) {
            handleSubchunkAddition(
                    world, chunk, aabb_tmp, pos, chunk.storageArrays[i], i, x.intValue());
        }
        pos.release();

        long endTime = System.nanoTime();
        SusyLog.logger.info(
                String.format("handleChunkAddition took %.3f ms", (endTime - startTime) / 1000000f));
    }

    public static void handleSubchunkAddition(
                                              World world,
                                              Chunk chunk,
                                              List<AxisAlignedBB> aabb_tmp,
                                              PooledMutableBlockPos pos,
                                              ExtendedBlockStorage subchunk,
                                              int yLevel,
                                              int world_id) {
        int[] subchunkColliderInfo = new int[4096];

        if (subchunk != null && !subchunk.isEmpty()) {
            int chunkBaseX = chunk.x * 16;
            int chunkBaseZ = chunk.z * 16;
            int chunkBaseY = subchunk.getYLocation();
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {
                        pos.setPos(chunkBaseX + lx, chunkBaseY + ly, chunkBaseZ + lz);
                        int handle = computeBlockColliderHandle(world, pos, aabb_tmp, pos);
                        int index = ly << 8 | lz << 4 | lx;
                        subchunkColliderInfo[index] = handle;
                    }
                }
            }
        }
        addChunk(world_id, chunk.x, yLevel, chunk.z, subchunkColliderInfo);
    }

    public static void add_force_debug(
                                       AbstractPhysicsEntity entity, double fx, double fy, double fz) {
        var world_id = initializedWorlds.get(entity.getEntityWorld());
        if (world_id == null || !entity.getColliderId().isPresent()) {
            return;
        }
        addForceDebug(world_id, entity.getColliderId().get(), fx, fy, fz);
    }

    public static native void RbInfo(
                                     int world_id, long handle); // TODO api to get most of the collider struct in java

    public static void step_world(World w) {
        var id = initializedWorlds.get(w);
        if (id != null) {
            step(id);
        }
    }

    public static Pair<Vec3d, Quaternion> get_entity_pose(AbstractPhysicsEntity entity) {
        var world_id = initializedWorlds.get(entity.getEntityWorld());
        if (world_id == null) return Pair.of(Vec3d.ZERO, Quaternion.IDENTITY);
        getEntityPose(world_id, entity.getColliderId().get(), cache);
        return Pair.of(
                new Vec3d(cache[0], cache[1], cache[2]),
                new Quaternion(cache[6], cache[3], cache[4], cache[5]));
    }

    public static void syncEntity(AbstractPhysicsEntity entity) {
        if (!entity.getColliderId().isPresent()) return;
        var world_id = initializedWorlds.get(entity.getEntityWorld());
        if (world_id == null) return;
        getEntityPose(world_id, entity.getColliderId().get(), cache);
        entity.setPosition(cache[0], cache[1], cache[2]);
        entity.rotation = new Quaternion(cache[6], cache[3], cache[4], cache[5]);
        entity.motionX = cache[7];
        entity.motionY = cache[8];
        entity.motionZ = cache[9];
    }

    public static Optional<Long> add_entity(AbstractPhysicsEntity entity) {
        if (entity.getColliderId().isPresent()) {
            SusyLog.logger.warn(
                    "entity supplied to Rapier.add_entity already had a rapier collider handle");
            return entity.getColliderId();
        }
        var world = entity.getEntityWorld();
        var world_id = initializedWorlds.get(world);
        if (world_id == null) {
            // world_id = 0; // for testing
            SusyLog.logger.warn("tried to initialize an entity into an uninitialized world");
            entity.setDead();
            return Optional.empty();
        }
        var shape = entity.getShape();
        // if (shape.type() == null || shape.data() == null || shape.indices() == null) {
        // SusyLog.logger.error("shape null fields: type={}, data={}, indices={}", shape.type() == null,
        // shape.data() == null, shape.indices() == null);
        // return Optional.empty();
        // }

        long handle = addEntity(
                world_id,
                shape.type().getValue(),
                0.0,
                0.0,
                entity.posX,
                entity.posY,
                entity.posZ,
                shape.data(),
                shape.indices());
        entities.add(entity);
        return Optional.of(handle);
    }

    public static void remove_entity(AbstractPhysicsEntity entity) {
        if (!entity.colliderId.isPresent()) return;
        var world_id = initializedWorlds.get(entity.getEntityWorld());
        if (world_id == null) return;
        removeEntity(world_id, entity.colliderId.get());
        entities.remove(entity);
        entity.colliderId = Optional.empty();
    }

    private static native void initialize(int world_id, float gravity, double universal_drag);

    // returns a handle
    // the aabbs double array must have n%6==0 elements
    static native int addColliderInfo(
                                      double friction, double restitution, double density, double[] aabbs);

    // xyz chunk coordniates (chunk space)
    // [subchunk][xzy int handle] data
    static native void addChunk(int world_id, int x, int y, int z, int[] data);

    // xyz chunk coordniates (chunk space), subchunk
    static native void removeChunk(int world_id, int x, int y, int z);

    public static native void partialSubchunkUpdate(int world_id, int chunk_x, int chunk_z, int chunk_y, int x, int y,
                                                    int z, int new_data);

    public static int computeBlockColliderHandle(World world, BlockPos pos, List<AxisAlignedBB> aabbTmp,
                                                 PooledMutableBlockPos tmpPos) {
        IBlockState state = world.getBlockState(pos);
        Block block = state.getBlock();
        if (block == Blocks.AIR) return 0;
        tmpPos.setPos(pos.getX(), pos.getY(), pos.getZ());
        if (block.isPassable(world, tmpPos)) return 0;
        if (state.getMaterial() == Material.WATER || state.getMaterial() == Material.LAVA) return 0;
        // TODO maybe do this on the rust side?
        if (isOccluded(world, tmpPos)) return 0;

        int cached = blockStateCache.getInt(state);
        if (cached != 0 || blockStateCache.containsKey(state)) {
            return cached;
        }
        float friction = Math.max(Math.min(1f - block.getSlipperiness(state, world, tmpPos, null), 0), 1);
        aabbTmp.clear();
        state.addCollisionBoxToList(world, tmpPos, CHUNK_BOX, aabbTmp, null, true);
        int size = aabbTmp.size();
        int handle;
        if (size == 0) {
            handle = 0;
        } else {
            double[] boxData = new double[size * 6];
            for (int j = 0; j < size; j++) {
                AxisAlignedBB box = aabbTmp.get(j);
                int j0 = j * 6;
                boxData[j0] = box.minX - pos.getX();
                boxData[j0 + 1] = box.minY - pos.getY();
                boxData[j0 + 2] = box.minZ - pos.getZ();
                boxData[j0 + 3] = box.maxX - pos.getX();
                boxData[j0 + 4] = box.maxY - pos.getY();
                boxData[j0 + 5] = box.maxZ - pos.getZ();
            }
            handle = addColliderInfo(friction, 0.9, 1000.0, boxData);
        }
        blockStateCache.put(state, handle);
        return handle;
    }

    private static boolean isOccluded(World world, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        return isFullSolidBlock(world, x - 1, y, z) && isFullSolidBlock(world, x + 1, y, z) &&
                isFullSolidBlock(world, x, y - 1, z) && isFullSolidBlock(world, x, y + 1, z) &&
                isFullSolidBlock(world, x, y, z - 1) && isFullSolidBlock(world, x, y, z + 1);
    }

    private static boolean isFullSolidBlock(World world, int x, int y, int z) {
        if (y < 0 || y >= 256) return false;
        IBlockState state = world.getBlockState(new BlockPos(x, y, z));
        return state.isFullBlock() && state.isFullCube() && state.isOpaqueCube();
    }

    private static double volume(AxisAlignedBB aabb) {
        double x = aabb.maxX - aabb.minX;
        double y = aabb.maxY - aabb.minY;
        double z = aabb.maxZ - aabb.minZ;
        return x * y * z;
    }

    private static native void step(int world_id);

    public static void setEntityPose(AbstractPhysicsEntity entity) {
        var world_id = initializedWorlds.get(entity.getEntityWorld());
        if (world_id == null || !entity.getColliderId().isPresent()) return;
        setEntityPose(world_id, entity.getColliderId().get(),
                entity.posX, entity.posY, entity.posZ,
                (float) entity.rotation.getW(), (float) entity.rotation.getX(),
                (float) entity.rotation.getY(), (float) entity.rotation.getZ(),
                entity.motionX, entity.motionY, entity.motionZ);
    }

    // pos,quaternion rotation,velocity
    private static native void setEntityPose(int world_id, long collider_handle,
                                             double x, double y, double z,
                                             double qw, double qx, double qy, double qz,
                                             double vx, double vy, double vz);

    private static native void removeEntity(int world_id, long collider_handle);

    private static native long addEntity(
                                         int world_id,
                                         int type,
                                         double restitution,
                                         double friction,
                                         double x,
                                         double y,
                                         double z,
                                         float[] shapeData,
                                         int[] shapeIndicies);

    private static native void getEntityPose(int world_id, long collider_handle, double[] mut_array);

    private static native void addForceDebug(int world_id, long collider_handle, double fx, double fy, double fz);

    private static native void destroyWorld(int dimension);

    private static native void reset();
}
