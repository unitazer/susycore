package supersymmetry.api.phys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class Rapier {
  // IBlockState -> VoxelInformation handle
  // ideally this would hash the World aswell but thats probably too much
  private static HashMap<IBlockState, BlockCollisionsHandle> BlockStateCache = new HashMap<>();

  private static native void initialize(int dimension, double gravity, double drag);

  // returns a BlockCollisionsHandle
  // the aabbs double array must have n%6==0 elements
  private static native int addColliderInfo(
      double friction, double volume, double restitution, double[] aabbs);

  // stuff that deals with terrain specifically

  // xy chunk coordniates (chunk space)
  // [subchunk][xzy BlockCollisionsHandle] data
  private static native void addChunk(int dimension, int x, int z, int[][] data);

  // todo
  private static native void removeChunk(int dimension, int x, int z);

  // todo
  private static native void partialSubchunkUpdate(
      int dimension, int chunk_x, int chunk_z, int x, int y, int z, int new_data);

  private static double volume(AxisAlignedBB aabb) {
    double x = aabb.maxX - aabb.minX;
    double y = aabb.maxY - aabb.minY;
    double z = aabb.maxZ - aabb.minZ;
    return x * y * z;
  }

  public void handleChunkAddition(Chunk chunk) {
    // todo maybe keep this outside of the function?
    AxisAlignedBB chunk_box =
        new AxisAlignedBB(BlockPos.ORIGIN, new BlockPos(16, 256, 16))
            .offset(chunk.x >> 4, 0, chunk.z >> 4);

    World world = chunk.getWorld();
    // todo maybe keep this outside of the function?
    List<AxisAlignedBB> aabb_tmp = new ArrayList<>();

    for (int i = 0; i < chunk.storageArrays.length; i++) {
      ExtendedBlockStorage subchunk = chunk.storageArrays[i];
      if (subchunk == null || subchunk.isEmpty()) {
        continue;
      }
      int ybase = subchunk.getYLocation();
      int[] subchunkColliderInfo = new int[4096];
      for (int lx = 0; lx < 16; lx++) {
        for (int lz = 0; lz < 16; lz++) {
          for (int ly = 0; ly < 16; ly++) {
            final int lxF = lx;
            final int lzF = lz;
            final int lyF = ly;
            IBlockState blockstate1 = subchunk.get(lx, ly, lz);
            Block block = blockstate1.getBlock();
            if (block == Blocks.AIR) {
              continue;
            }
            if (block.isPassable(world, new BlockPos(lx, ly, lz))) {
              continue;
            }
            if (blockstate1.getMaterial() == Material.WATER
                || blockstate1.getMaterial() == Material.LAVA) {
              continue; // not dealing with that yet
            }

            BlockCollisionsHandle colliderInfoHandle =
                BlockStateCache.computeIfAbsent(
                    blockstate1,
                    (blockstate) -> {
                      int worldX = chunk.x * 16 + lxF;
                      int worldY = ybase + lyF;
                      int worldZ = chunk.z * 16 + lzF;
                      // todo use mutableblockpos
                      BlockPos pos = new BlockPos(worldX, worldY, worldZ);
                      float friction =
                          Math.max(
                              Math.min(1 - block.getSlipperiness(blockstate1, world, pos, null), 0),
                              1);
                      aabb_tmp.clear();
                      blockstate.addCollisionBoxToList(world, pos, chunk_box, aabb_tmp, null, true);
                      double[] box_data = new double[aabb_tmp.size() * 6];

                      for (int j = 0; j < aabb_tmp.size() * 6; j += 6) {
                        AxisAlignedBB box = aabb_tmp.get(j);
                        box_data[j] = box.minX - worldX;
                        box_data[j + 1] = box.minY - worldY;
                        box_data[j + 2] = box.minZ - worldZ;
                        box_data[j + 3] = box.maxX - worldX;
                        box_data[j + 4] = box.maxY - worldY;
                        box_data[j + 5] = box.maxZ - worldZ;
                      }

                      addColliderInfo(
                          friction,
                          aabb_tmp.stream().mapToDouble(x -> this.volume(x)).sum(),
                          friction,
                          box_data);
                      return null;
                    });
            int index = ly << 8 | lz << 4 | lx;
            subchunkColliderInfo[index] = colliderInfoHandle.handle();
          }
        }
      }
    }
  }
}
