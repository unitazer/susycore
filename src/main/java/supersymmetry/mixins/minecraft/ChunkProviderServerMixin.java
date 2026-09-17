package supersymmetry.mixins.minecraft;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.subworld.SubWorldAllocator;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.api.subworld.SubWorldVoidChunks;
import supersymmetry.api.subworld.SubWorldVoidFallbacks;

@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {

    @Shadow
    @Final
    public WorldServer world;

    @Inject(method = "provideChunk", at = @At("HEAD"), cancellable = true)
    private void susy$onProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = this.susy$getSubWorldChunk(x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "loadChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"), cancellable = true)
    private void susy$onLoadChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = this.susy$getSubWorldChunk(x, z);
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

    @Inject(method = "chunkExists", at = @At("HEAD"), cancellable = true)
    private void susy$onChunkExists(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldAllocator.isInHeapRegion(x, z)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isChunkGeneratedAt", at = @At("HEAD"), cancellable = true)
    private void susy$onIsChunkGeneratedAt(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldAllocator.isInHeapRegion(x, z)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "queueUnload", at = @At("HEAD"), cancellable = true)
    private void susy$neverQueuePlotChunks(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(this.world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "saveChunkData", at = @At("HEAD"), cancellable = true)
    private void susy$neverSavePlotChunks(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(this.world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "saveChunkExtraData", at = @At("HEAD"), cancellable = true)
    private void susy$neverSavePlotChunkExtraData(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(this.world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }

    @Unique private Chunk susy$getSubWorldChunk(int x, int z) {
        Chunk chunk = SubWorldRegistry.getPlotChunk(this.world, x, z);
        if (chunk != null) {
            return chunk;
        }
        if (SubWorldAllocator.isInHeapRegion(x, z)) {
            return SubWorldVoidFallbacks.get(this.world, "server", x, z);
        }
        return null;
    }
}
