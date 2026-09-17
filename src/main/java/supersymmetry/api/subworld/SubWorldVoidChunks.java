package supersymmetry.api.subworld;

import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.init.Biomes;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

public final class SubWorldVoidChunks {

    private static final Map<World, Long2ObjectMap<Chunk>> VOID_CHUNKS = new WeakHashMap<>();

    private SubWorldVoidChunks() {}

    public static Chunk get(World world, int chunkX, int chunkZ) {
        synchronized (VOID_CHUNKS) {
            return VOID_CHUNKS.computeIfAbsent(world, key -> new Long2ObjectOpenHashMap<>())
                    .computeIfAbsent(ChunkPos.asLong(chunkX, chunkZ), key -> create(world, chunkX, chunkZ));
        }
    }

    public static void invalidate(World world, int chunkX, int chunkZ) {
        synchronized (VOID_CHUNKS) {
            Long2ObjectMap<Chunk> chunks = VOID_CHUNKS.get(world);
            if (chunks != null) {
                chunks.remove(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    private static Chunk create(World world, int chunkX, int chunkZ) {
        Chunk chunk = new Chunk(world, chunkX, chunkZ);
        Arrays.fill(chunk.getBiomeArray(), (byte) Biome.getIdForBiome(Biomes.VOID));
        return chunk;
    }
}
