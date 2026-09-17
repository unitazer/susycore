package supersymmetry.mixins.minecraft;

import net.minecraft.client.multiplayer.ChunkProviderClient;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.subworld.SubWorldAllocator;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.api.subworld.SubWorldVoidChunks;
import supersymmetry.api.subworld.SubWorldVoidFallbacks;

@Mixin(ChunkProviderClient.class)
public abstract class ChunkProviderClientMixin {

    @Shadow
    @Final
    private World world;

    @Inject(method = "loadChunk", at = @At("HEAD"), cancellable = true)
    private void susy$routePlotLoadChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = SubWorldRegistry.getPlotChunk(this.world, chunkX, chunkZ);
        if (chunk == null && SubWorldAllocator.isInHeapRegion(chunkX, chunkZ)) {
            chunk = SubWorldVoidFallbacks.get(this.world, "client", chunkX, chunkZ);
        }
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "provideChunk", at = @At("HEAD"), cancellable = true)
    private void susy$onProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = SubWorldRegistry.getPlotChunk(this.world, x, z);
        if (chunk == null && SubWorldAllocator.isInHeapRegion(x, z)) {
            chunk = SubWorldVoidFallbacks.get(this.world, "client", x, z);
        }
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "getLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void susy$onGetLoadedChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = SubWorldRegistry.peekPlotChunk(this.world, x, z);
        if (chunk == null && SubWorldAllocator.isInHeapRegion(x, z)) {
            chunk = SubWorldVoidChunks.get(this.world, x, z);
        }
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "isChunkGeneratedAt", at = @At("HEAD"), cancellable = true)
    private void susy$onIsChunkGeneratedAt(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldAllocator.isInHeapRegion(x, z)) {
            cir.setReturnValue(true);
        }
    }
}
