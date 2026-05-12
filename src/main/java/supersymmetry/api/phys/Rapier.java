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
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import com.mojang.realmsclient.util.Pair;

import supersymmetry.api.SusyLog;

public class Rapier {

    // IBlockState -> collision data handle
    // ideally this would hash the coords aswell but thats probably too much
    // doesnt work well with TE's
    private static HashMap<IBlockState, Integer> blockStateCache = new HashMap<>();
    public static HashMap<World, Integer> initializedWorlds = new HashMap<>();
    // to be passed to rust as a mutable array reference so that you could write rotation/position
    // data to it
    private static double[] cache = new double[32];

    // drag should be very low unless its somewhere in an endless ocean
    public static void initialize_world(World world, float gravity, double drag) {
        if (initializedWorlds.containsKey(world)) {
            return;
        }
        var x = initializedWorlds.size();
        initialize(x, gravity, drag);
        initializedWorlds.put(world, Integer.valueOf(x));
    }

    public static void handleChunkAddition(Chunk chunk) {
        // TODO maybe keep this outside of the function?
        AxisAlignedBB chunk_box = new AxisAlignedBB(BlockPos.ORIGIN, new BlockPos(16, 256, 16))
                .offset(chunk.x * 16, 0, chunk.z * 16);

        World world = chunk.getWorld();
        if (!initializedWorlds.containsKey(world)) {
            SusyLog.logger.error("handleChunkAddition on a chunk from an uninitialized world");
            return;
        }
        // TODo maybe keep this outside of the function?
        List<AxisAlignedBB> aabb_tmp = new ArrayList<>();

        long startTime = System.nanoTime();
        int[][] chunkdata = new int[16][4096];

        var pos = BlockPos.PooledMutableBlockPos.retain();

        for (int i = 0; i < chunk.storageArrays.length; i++) {
            ExtendedBlockStorage subchunk = chunk.storageArrays[i];
            if (subchunk == null || subchunk.isEmpty()) {
                continue;
            }
            int chunkBaseY = subchunk.getYLocation();
            int[] subchunkColliderInfo = new int[4096];
            int chunkBaseX = chunk.x * 16;
            int chunkBaseZ = chunk.z * 16;
            for (int ly = 0; ly < 16; ly++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int lx = 0; lx < 16; lx++) {

                        int worldX = chunkBaseX + lx;
                        int worldY = chunkBaseY + ly;
                        int worldZ = chunkBaseZ + lz;
                        pos.setPos(worldX, worldY, worldZ);

                        IBlockState blockstate1 = subchunk.get(lx, ly, lz);
                        Block block = blockstate1.getBlock();
                        if (block == Blocks.AIR) {
                            continue;
                        }
                        if (block.isPassable(world, pos)) {
                            continue;
                        }
                        if (blockstate1.getMaterial() == Material.WATER || blockstate1.getMaterial() == Material.LAVA) {
                            continue; // not dealing with that yet
                        }

                        int colliderInfoHandle = blockStateCache.computeIfAbsent(
                                blockstate1,
                                (blockstate) -> {
                                    float friction = Math.max(
                                            Math.min(1f - block.getSlipperiness(blockstate, world, pos, null), 0),
                                            1);
                                    aabb_tmp.clear();
                                    blockstate.addCollisionBoxToList(world, pos, chunk_box, aabb_tmp, null, true);
                                    int size = aabb_tmp.size();
                                    if (size == 0) {
                                        return 0;
                                    }
                                    double[] box_data = new double[size * 6];

                                    for (int j = 0; j < size; j++) {
                                        AxisAlignedBB box = aabb_tmp.get(j);
                                        int j0 = j * 6;
                                        box_data[j0] = box.minX - worldX;
                                        box_data[j0 + 1] = box.minY - worldY;
                                        box_data[j0 + 2] = box.minZ - worldZ;
                                        box_data[j0 + 3] = box.maxX - worldX;
                                        box_data[j0 + 4] = box.maxY - worldY;
                                        box_data[j0 + 5] = box.maxZ - worldZ;
                                    }
                                    // very slow because of the jni
                                    int h = addColliderInfo(
                                            friction, 0.9, // TODO
                                            1000.0, // TODO
                                            box_data);
                                    return h;
                                });
                        int index = ly << 8 | lz << 4 | lx;
                        subchunkColliderInfo[index] = colliderInfoHandle;
                    }
                }
            }
            chunkdata[i] = subchunkColliderInfo;
        }
        pos.release();
        var x = initializedWorlds.get(world);
        addChunk(x.intValue(), chunk.x, chunk.z, chunkdata);

        long endTime = System.nanoTime();

        SusyLog.logger.info(
                String.format("handleChunkAddition took %.3f ms", (endTime - startTime) / 1000000f));
    }

    public static long debugging_ball_w(World w, int x, int y, int z) {
        var id = initializedWorlds.get(w);
        if (id != null) {
            return debuggingBall(id, x, y, z);
        }
        return 0;
    }

    // stuff that deals with terrain specifically

    public static void add_force_debug(AbstractPhysicsEntity entity, double fx, double fy, double fz) {
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
        // int world_id = 0;
        getEntityPose(world_id, entity.getColliderId().get(), cache);
        return Pair.of(
                new Vec3d(cache[0], cache[1], cache[2]),
                new Quaternion(cache[6], cache[3], cache[4], cache[5]));
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

        return Optional.of(
                addEntity(
                        world_id,
                        shape.type().getValue(),
                        0.0,
                        0.0,
                        entity.posX,
                        entity.posY,
                        entity.posZ,
                        shape.data(),
                        shape.indices()));
    }

    private static native void initialize(int world_id, float gravity, double universal_drag);

    // returns a handle
    // the aabbs double array must have n%6==0 elements
    private static native int addColliderInfo(
                                              double friction, double restitution, double density, double[] aabbs);

    // xy chunk coordniates (chunk space)
    // [subchunk][xzy int handle] data
    private static native void addChunk(int world_id, int x, int z, int[][] data);

    // todo
    private static native void removeChunk(int world_id, int x, int z);

    // todo
    private static native void partialSubchunkUpdate(
                                                     int world_id, int chunk_x, int chunk_z, int chunk_y, int x, int y,
                                                     int z, int new_data);

    private static double volume(AxisAlignedBB aabb) {
        double x = aabb.maxX - aabb.minX;
        double y = aabb.maxY - aabb.minY;
        double z = aabb.maxZ - aabb.minZ;
        return x * y * z;
    }

    private static native long debuggingBall(int world_id, int x, int y, int z);

    private static native void step(int world_id);

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
}
