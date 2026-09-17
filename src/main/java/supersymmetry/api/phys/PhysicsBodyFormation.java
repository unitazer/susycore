package supersymmetry.api.phys;

import java.util.Set;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.SusyLog;
import supersymmetry.api.subworld.SubWorldPlot;

public final class PhysicsBodyFormation {

    public static final int MAX_CHILD_SIZE_Y = 254 - SubWorldPlot.RENDER_ORIGIN_Y;
    private static final int MIN_HEAP_Y = 0;
    private static final int MAX_HEAP_Y = 254;
    private static final BlockPos CHILD_LOCAL_ORIGIN = new BlockPos(1, 1, 1);

    private PhysicsBodyFormation() {}

    public static BlockPos toChildLocal(BlockPos parentLocal, BlockPos regionMin) {
        return parentLocal.subtract(regionMin).add(CHILD_LOCAL_ORIGIN);
    }

    @Nullable public static PhysicsWorldEntity createChildEntity(PhysicsWorldEntity parent, BlockPos localMin,
                                                       BlockPos localMax) {
        return createChildEntity(parent, localMin, localMax, null);
    }

    @Nullable public static PhysicsWorldEntity createChildEntity(PhysicsWorldEntity parent, BlockPos localMin,
                                                       BlockPos localMax, @Nullable Set<BlockPos> regionBlocks) {
        World world = parent.getEntityWorld();
        SubWorldPlot plot = parent.getPlot();
        if (world.isRemote || plot == null) {
            SusyLog.logger.error("PhysicsBodyFormation: cannot create a child body without a server-side plot");
            return null;
        }
        int sizeX = localMax.getX() - localMin.getX() + 1;
        int sizeY = localMax.getY() - localMin.getY() + 1;
        int sizeZ = localMax.getZ() - localMin.getZ() + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            SusyLog.logger.error("PhysicsBodyFormation: empty child region [{}, {}]", localMin, localMax);
            return null;
        }
        if (sizeY > MAX_CHILD_SIZE_Y) {
            SusyLog.logger.error(
                    "PhysicsBodyFormation: child region [{}, {}] exceeds the {} block interaction height cap",
                    localMin, localMax, MAX_CHILD_SIZE_Y);
            return null;
        }
        int minHeapY = SubWorldPlot.RENDER_ORIGIN_Y + localMin.getY();
        int maxHeapY = SubWorldPlot.RENDER_ORIGIN_Y + localMax.getY();
        if (localMin.getX() < 0 || localMin.getZ() < 0 || localMax.getX() >= (plot.getSizeChunksX() << 4) ||
                localMax.getZ() >= (plot.getSizeChunksZ() << 4) || minHeapY < MIN_HEAP_Y || maxHeapY > MAX_HEAP_Y) {
            SusyLog.logger.error("PhysicsBodyFormation: child region [{}, {}] leaves plot {}", localMin, localMax,
                    plot);
            return null;
        }
        if (!regionHasBlocks(plot, localMin, localMax, regionBlocks)) {
            SusyLog.logger.error("PhysicsBodyFormation: child region [{}, {}] in plot {} contains no blocks to detach",
                    localMin, localMax, plot);
            return null;
        }
        SubWorldPlot childPlot = SubWorldPlot.create(world, (sizeX >> 4) + 1, (sizeZ >> 4) + 1);
        PhysicsWorldEntity child = new PhysicsWorldEntity(world);
        child.attachPlot(childPlot);
        Vec3d childPos = parent.worldPointFromLocal(
                new Vec3d(localMin.getX() + sizeX / 2.0, localMin.getY() + sizeY / 2.0,
                        localMin.getZ() + sizeZ / 2.0));
        child.setPosition(childPos.x, childPos.y, childPos.z);
        child.setRotation(parent.getRotation());
        child.setRotationPoint(CHILD_LOCAL_ORIGIN.getX() + sizeX / 2.0f, CHILD_LOCAL_ORIGIN.getY() + sizeY / 2.0f,
                CHILD_LOCAL_ORIGIN.getZ() + sizeZ / 2.0f);
        child.setPlotSize(sizeX, sizeY, sizeZ);
        child.refreshPlotAABB();
        moveRegionToChild(parent, child, childPlot, localMin, localMax, regionBlocks);
        childPlot.seedLight();
        world.spawnEntity(child);
        return child;
    }

    private static boolean regionHasBlocks(SubWorldPlot plot, BlockPos localMin, BlockPos localMax,
                                           @Nullable Set<BlockPos> regionBlocks) {
        if (regionBlocks != null) return !regionBlocks.isEmpty();
        for (int x = localMin.getX(); x <= localMax.getX(); x++) {
            for (int y = localMin.getY(); y <= localMax.getY(); y++) {
                for (int z = localMin.getZ(); z <= localMax.getZ(); z++) {
                    IBlockState state = plot.getBlockState(plot.toGlobal(new BlockPos(x, y, z)));
                    if (state.getBlock() != Blocks.AIR) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void moveRegionToChild(PhysicsWorldEntity parent, PhysicsWorldEntity child,
                                          SubWorldPlot childPlot, BlockPos localMin, BlockPos localMax,
                                          @Nullable Set<BlockPos> regionBlocks) {
        World world = parent.getEntityWorld();
        SubWorldPlot parentPlot = parent.getPlot();
        for (int x = localMin.getX(); x <= localMax.getX(); x++) {
            for (int y = localMin.getY(); y <= localMax.getY(); y++) {
                for (int z = localMin.getZ(); z <= localMax.getZ(); z++) {
                    BlockPos parentLocal = new BlockPos(x, y, z);
                    BlockPos global = parentPlot.toGlobal(parentLocal);
                    IBlockState state = parentPlot.getBlockState(global);
                    if (state.getBlock() == Blocks.AIR) continue;
                    if (regionBlocks != null && !regionBlocks.contains(parentLocal)) continue;
                    BlockStateInfo captured = parent.captureBlockState(global, state);
                    BlockPos childLocal = toChildLocal(parentLocal, localMin);
                    child.setPlotBlock(childLocal, captured.state, captured.tileData);
                    BlockExtraStateTransfer.restore(world, captured.state.getBlock(), childPlot.toGlobal(childLocal),
                            captured.extra);
                    parent.setPlotBlock(parentLocal, Blocks.AIR.getDefaultState(), null);
                }
            }
        }
    }
}
