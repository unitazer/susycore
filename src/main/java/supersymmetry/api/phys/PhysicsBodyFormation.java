package supersymmetry.api.phys;

import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.LongSet;
import supersymmetry.api.SusyLog;
import supersymmetry.api.block.BlockExtraDataRegistry;
import supersymmetry.api.subworld.SubWorldPlot;

public final class PhysicsBodyFormation {

    public static final int MAX_CHILD_SIZE_Y = 254 - SubWorldPlot.RENDER_ORIGIN_Y;
    private static final BlockPos CHILD_LOCAL_ORIGIN = new BlockPos(1, 1, 1);

    private PhysicsBodyFormation() {}

    public static BlockPos toChildLocal(BlockPos parentLocal, BlockPos regionMin) {
        return parentLocal.subtract(regionMin).add(CHILD_LOCAL_ORIGIN);
    }

    @Nullable public static PhysicsWorldEntity createChildEntity(PhysicsWorldEntity parent, BlockPos localMin,
                                                       BlockPos localMax, @Nullable LongSet regionBlocks) {
        World world = parent.getEntityWorld();
        SubWorldPlot plot = parent.getPlot();
        if (world.isRemote || plot == null) {
            SusyLog.logger.error("cant make a child body client side");
            return null;
        }
        int sizeX = localMax.getX() - localMin.getX() + 1;
        int sizeY = localMax.getY() - localMin.getY() + 1;
        int sizeZ = localMax.getZ() - localMin.getZ() + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) {
            SusyLog.logger.error("empty child region [{}, {}]", localMin, localMax);
            return null;
        }
        if (sizeY > MAX_CHILD_SIZE_Y) {
            SusyLog.logger.error(
                    "child region [{}, {}] exceeds the {} block interaction height cap",
                    localMin, localMax, MAX_CHILD_SIZE_Y);
            return null;
        }
        int minHeapY = SubWorldPlot.RENDER_ORIGIN_Y + localMin.getY();
        int maxHeapY = SubWorldPlot.RENDER_ORIGIN_Y + localMax.getY();
        if (localMin.getX() < 0 || localMin.getZ() < 0 || localMax.getX() >= (plot.getSizeChunksX() << 4) ||
                localMax.getZ() >= (plot.getSizeChunksZ() << 4) || minHeapY < 0 || maxHeapY > 254) {
            SusyLog.logger.error("child region [{}, {}] leaves plot {}", localMin, localMax,
                    plot);
            return null;
        }
        if (!regionHasBlocks(plot, localMin, localMax)) {
            SusyLog.logger.error("child region [{}, {}] in plot {} contains no blocks to detach",
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

    private static boolean regionHasBlocks(SubWorldPlot plot, BlockPos localMin, BlockPos localMax) {
        int minChunkX = localMin.getX() >> 4;
        int maxChunkX = localMax.getX() >> 4;
        int minChunkZ = localMin.getZ() >> 4;
        int maxChunkZ = localMax.getZ() >> 4;
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                Chunk chunk = plot.getLoadedChunk(plot.getOriginChunkX() + cx, plot.getOriginChunkZ() + cz);
                if (chunk == null) {
                    continue;
                }
                for (ExtendedBlockStorage storage : chunk.getBlockStorageArray()) {
                    if (storage == null || storage.isEmpty()) continue;
                    int baseLocalY = storage.getYLocation() - SubWorldPlot.RENDER_ORIGIN_Y;
                    int yLo = Math.max(localMin.getY(), baseLocalY);
                    int yHi = Math.min(localMax.getY(), baseLocalY + 15);
                    for (int x = localMin.getX(); x <= localMax.getX(); x++) {
                        for (int y = yLo; y <= yHi; y++) {
                            for (int z = localMin.getZ(); z <= localMax.getZ(); z++) {
                                if (storage.get(x & 15, y - baseLocalY, z & 15).getBlock() != Blocks.AIR) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void moveRegionToChild(PhysicsWorldEntity parent, PhysicsWorldEntity child,
                                          SubWorldPlot childPlot, BlockPos localMin, BlockPos localMax,
                                          @Nullable LongSet regionBlocks) {
        World world = parent.getEntityWorld();
        SubWorldPlot parentPlot = parent.getPlot();
        for (int x = localMin.getX(); x <= localMax.getX(); x++) {
            for (int y = localMin.getY(); y <= localMax.getY(); y++) {
                for (int z = localMin.getZ(); z <= localMax.getZ(); z++) {
                    BlockPos parentLocal = new BlockPos(x, y, z);
                    BlockPos global = parentPlot.toGlobal(parentLocal);
                    IBlockState state = parentPlot.getBlockState(global);
                    if (state.getBlock() == Blocks.AIR) continue;
                    if (regionBlocks != null && !regionBlocks.contains(parentLocal.toLong())) continue;
                    BlockStateInfo captured = parent.captureBlockState(global, state);
                    BlockPos childLocal = toChildLocal(parentLocal, localMin);
                    child.setPlotBlock(childLocal, captured.state, captured.tileData);
                    BlockExtraDataRegistry.restore(world, captured.state.getBlock(),
                            childPlot.toGlobal(childLocal), captured.extra);
                    parent.setPlotBlock(parentLocal, Blocks.AIR.getDefaultState(), null);
                }
            }
        }
    }
}
