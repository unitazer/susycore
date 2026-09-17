package supersymmetry.api.subworld;

import java.util.Collections;
import java.util.Set;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import supersymmetry.api.SusyLog;

public final class SubWorldVoidFallbacks {

    private static final int LOG_CAP = 4096;
    private static final Set<Long> LOGGED = Collections.synchronizedSet(new LongOpenHashSet());

    private SubWorldVoidFallbacks() {}

    public static Chunk get(World world, String side, int chunkX, int chunkZ) {
        if (LOGGED.add(ChunkPos.asLong(chunkX, chunkZ))) {
            if (LOGGED.size() > LOG_CAP) {
                LOGGED.clear();
            }
            SusyLog.logger.debug("[susy-growth] {} void chunk fallback at ({}, {}), no plot hint", side, chunkX,
                    chunkZ);
        }
        return SubWorldVoidChunks.get(world, chunkX, chunkZ);
    }
}
