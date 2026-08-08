package supersymmetry.mixins.minecraft;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.subworld.SubWorldRegistry;

@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {

    @Shadow
    @Final
    public WorldServer world;

    @Inject(method = "provideChunk", at = @At("HEAD"), cancellable = true)
    private void onProvideChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = SubWorldRegistry.getPlotChunk(world, x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "getLoadedChunk", at = @At("HEAD"), cancellable = true)
    private void onGetLoadedChunk(int x, int z, CallbackInfoReturnable<Chunk> cir) {
        Chunk chunk = SubWorldRegistry.peekPlotChunk(world, x, z);
        if (chunk != null) {
            cir.setReturnValue(chunk);
        }
    }

    @Inject(method = "chunkExists", at = @At("HEAD"), cancellable = true)
    private void onChunkExists(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldRegistry.find(world, x, z) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isChunkGeneratedAt", at = @At("HEAD"), cancellable = true)
    private void onIsChunkGeneratedAt(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldRegistry.find(world, x, z) != null) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "queueUnload", at = @At("HEAD"), cancellable = true)
    private void susy$neverQueuePlotChunks(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "saveChunkData", at = @At("HEAD"), cancellable = true)
    private void susy$neverSavePlotChunks(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }

    @Inject(method = "saveChunkExtraData", at = @At("HEAD"), cancellable = true)
    private void susy$neverSavePlotChunkExtraData(Chunk chunkIn, CallbackInfo ci) {
        if (SubWorldRegistry.find(world, chunkIn.x, chunkIn.z) != null) {
            ci.cancel();
        }
    }
}
