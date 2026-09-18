package supersymmetry.client.subworld;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.jetbrains.annotations.Nullable;

import supersymmetry.mixins.minecraft.ChunkProviderClientAccessor;

@SideOnly(Side.CLIENT)
public final class SubworldClientChunks {

    private SubworldClientChunks() {}

    public static void register(World world, Chunk chunk) {
        ChunkProviderClientAccessor provider = provider(world);
        if (provider != null) {
            provider.susy$getLoadedChunks().put(ChunkPos.asLong(chunk.x, chunk.z), chunk);
        }
    }

    public static void unregister(World world, Chunk chunk) {
        ChunkProviderClientAccessor provider = provider(world);
        if (provider != null) {
            provider.susy$getLoadedChunks().remove(ChunkPos.asLong(chunk.x, chunk.z), chunk);
        }
    }

    @Nullable private static ChunkProviderClientAccessor provider(World world) {
        IChunkProvider chunkProvider = world.getChunkProvider();
        return chunkProvider instanceof ChunkProviderClient ? (ChunkProviderClientAccessor) chunkProvider : null;
    }
}
