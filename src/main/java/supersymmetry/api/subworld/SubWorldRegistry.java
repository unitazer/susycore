package supersymmetry.api.subworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

public final class SubWorldRegistry {

    private SubWorldRegistry() {}

    public static void register(SubWorldPlot plot) {
        SubWorldContainer container = SubWorldContainer.getContainer(plot.getWorld());
        if (container != null) {
            container.register(plot);
        }
    }

    public static void unregister(SubWorldPlot plot) {
        SubWorldContainer container = SubWorldContainer.getContainer(plot.getWorld());
        if (container != null) {
            container.remove(plot);
        }
    }

    public static void unregisterWorld(World world) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container != null) {
            container.removeAllPlots();
        }
    }

    public static void markRemoved(World world, SubWorldPlot plot, SubWorldRemovalReason reason) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container != null) {
            container.markRemoved(plot, reason);
        } else {
            plot.destroy();
        }
    }

    @Nullable
    public static SubWorldPlot find(World world, int chunkX, int chunkZ) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        return container == null ? null : container.getPlot(chunkX, chunkZ);
    }

    public static Chunk getPlotChunk(World world, int chunkX, int chunkZ) {
        SubWorldPlot plot = find(world, chunkX, chunkZ);
        return plot == null ? null : plot.getChunk(chunkX, chunkZ);
    }

    @Nullable
    public static Chunk peekPlotChunk(World world, int chunkX, int chunkZ) {
        SubWorldPlot plot = find(world, chunkX, chunkZ);
        return plot == null ? null : plot.getLoadedChunk(chunkX, chunkZ);
    }

    public static boolean inPlotArea(World world, int chunkX, int chunkZ, int expansion) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null || container.getPlots().isEmpty()) {
            return false;
        }
        for (SubWorldPlot plot : container.getPlots()) {
            int minX = plot.getOriginChunkX() - expansion;
            int maxX = plot.getOriginChunkX() + plot.getSizeChunksX() - 1 + expansion;
            int minZ = plot.getOriginChunkZ() - expansion;
            int maxZ = plot.getOriginChunkZ() + plot.getSizeChunksZ() - 1 + expansion;
            if (chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ) {
                return true;
            }
        }
        return false;
    }

    public static List<Chunk> collectPlotChunks(World world) {
        SubWorldContainer container = SubWorldContainer.getContainer(world);
        if (container == null || container.getPlots().isEmpty()) {
            return Collections.emptyList();
        }
        List<Chunk> chunks = new ArrayList<>();
        for (SubWorldPlot plot : container.getPlots()) {
            chunks.addAll(plot.getLoadedChunks().values());
        }
        return chunks;
    }
}
