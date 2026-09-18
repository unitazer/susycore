package supersymmetry.api.phys;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import supersymmetry.api.subworld.SubWorldPlot;

public final class ContactForceHandler {

    public static final double DEFAULT_THRESHOLD = 1.0e12;
    public static final double FRAGILE_THRESHOLD = 1.0e5;

    private static final int DOUBLES_PER_EVENT = 9;
    private static final Object2DoubleOpenHashMap<Block> THRESHOLDS = new Object2DoubleOpenHashMap<>();

    static {
        THRESHOLDS.put(Blocks.GLASS, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.STAINED_GLASS, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.GLASS_PANE, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.STAINED_GLASS_PANE, FRAGILE_THRESHOLD);
        THRESHOLDS.defaultReturnValue(DEFAULT_THRESHOLD);
    }

    private static double[] buffer = new double[64];

    private ContactForceHandler() {}

    public static double thresholdFor(Block block) {
        return THRESHOLDS.getDouble(block);
    }

    public static void onWorldTick(World world) {
        int worldId = Rapier.worldId(world);
        if (worldId < 0) return;
        int count = drain(worldId);
        for (int i = 0; i < count; i++) {
            int offset = i * DOUBLES_PER_EVENT;
            double force = buffer[offset];
            int tagA = (int) buffer[offset + 1];
            int tagB = (int) buffer[offset + 2];
            Vec3d pointA = new Vec3d(buffer[offset + 3], buffer[offset + 4], buffer[offset + 5]);
            Vec3d pointB = new Vec3d(buffer[offset + 6], buffer[offset + 7], buffer[offset + 8]);
            processSide(world, force, tagA, pointA);
            processSide(world, force, tagB, pointB);
        }
    }

    private static int drain(int worldId) {
        int count = Rapier.drainContactForces(worldId, buffer);
        while (count < 0) {
            buffer = new double[buffer.length * 2];
            count = Rapier.drainContactForces(worldId, buffer);
        }
        return count;
    }

    private static void processSide(World world, double force, int tag, Vec3d point) {
        if (tag < 0) {
            breakTerrainBlock(world, force, point);
        } else {
            breakBodyBlock(world, force, tag, point);
        }
    }

    private static void breakTerrainBlock(World world, double force, Vec3d point) {
        BlockPos pos = new BlockPos(point.x, point.y, point.z);
        IBlockState state = world.getBlockState(pos);
        if (state.getBlock() == Blocks.AIR || force < thresholdFor(state.getBlock())) return;
        world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
    }

    private static void breakBodyBlock(World world, double force, int tag, Vec3d point) {
        Entity entity = world.getEntityByID(tag);
        if (!(entity instanceof PhysicsWorldEntity body)) return;
        SubWorldPlot plot = body.getPlot();
        if (plot == null) return;
        RigidPose freshPose = Rapier.getBodyPose(world, tag);
        if (freshPose == null) return;
        RigidPose pose = new RigidPose(freshPose.position, freshPose.rotation, body.getRotationPoint());
        Vec3d localPoint = pose.plotLocalFromWorld(point);
        BlockPos local = new BlockPos(localPoint.x, localPoint.y, localPoint.z);
        BlockPos global = plot.toGlobal(local);
        IBlockState state = plot.getBlockState(global);
        if (state.getBlock() == Blocks.AIR || force < thresholdFor(state.getBlock())) return;
        world.setBlockState(global, Blocks.AIR.getDefaultState(), 3);
    }
}
