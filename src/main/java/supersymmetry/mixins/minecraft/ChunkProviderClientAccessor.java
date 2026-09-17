package supersymmetry.mixins.minecraft;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

@Mixin(ChunkProviderClient.class)
public interface ChunkProviderClientAccessor {

    @Accessor("loadedChunks")
    Long2ObjectMap<Chunk> susy$getLoadedChunks();
}
