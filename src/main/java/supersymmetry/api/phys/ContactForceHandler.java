package supersymmetry.api.phys;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import supersymmetry.api.subworld.SubWorldPlot;

public final class ContactForceHandler {

    public static final double DEFAULT_THRESHOLD = 1.0e12;
    public static final double FRAGILE_THRESHOLD = 1.0e5;

    private static final int DOUBLES_PER_EVENT = 9;
    private static final Map<Block, Double> THRESHOLDS = new HashMap<>();

    static {
        THRESHOLDS.put(Blocks.GLASS, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.STAINED_GLASS, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.GLASS_PANE, FRAGILE_THRESHOLD);
        THRESHOLDS.put(Blocks.STAINED_GLASS_PANE, FRAGILE_THRESHOLD);
    }

    private static double[] buffer = new double[64];

    private ContactForceHandler() {}

    public static double thresholdFor(Block block) {
        return THRESHOLDS.getOrDefault(block, DEFAULT_THRESHOLD);
    }

    public static void onWorldTick(World world) {
        int worldId = Rapier.worldId(world);
        if (worldId < 0) return;
        int count = drain(worldId);
        for (int i = 0; i < count; i++) {
            int o = i * DOUBLES_PER_EVENT;
            double force = buffer[o];
            int tagA = (int) buffer[o + 1];
            int tagB = (int) buffer[o + 2];
            Vec3d pointA = new Vec3d(buffer[o + 3], buffer[o + 4], buffer[o + 5]);
            Vec3d pointB = new Vec3d(buffer[o + 6], buffer[o + 7], buffer[o + 8]);
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
        if (!(entity instanceof PhysicsWorldEntity body) || body.getPlot() == null) return;
        Vec3d local = body.localPointFromWorld(point);
        BlockPos pos = new BlockPos(local.x, local.y, local.z);
        SubWorldPlot plot = body.getPlot();
        IBlockState state = plot.getBlockState(plot.toGlobal(pos));
        if (state.getBlock() == Blocks.AIR || force < thresholdFor(state.getBlock())) return;
        world.setBlockState(plot.toGlobal(pos), Blocks.AIR.getDefaultState(), 3);
    }
}
