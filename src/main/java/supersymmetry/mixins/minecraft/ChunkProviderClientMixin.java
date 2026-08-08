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

import supersymmetry.api.subworld.SubWorldRegistry;

@Mixin(ChunkProviderClient.class)
public abstract class ChunkProviderClientMixin {

    @Shadow
    @Final
    private World world;

    @Inject(method = "loadChunk", at = @At("HEAD"), cancellable = true)
    private void susy$routePlotLoadChunk(int chunkX, int chunkZ, CallbackInfoReturnable<Chunk> cir) {
        if (SubWorldRegistry.find(world, chunkX, chunkZ) != null) {
            cir.setReturnValue(SubWorldRegistry.getPlotChunk(world, chunkX, chunkZ));
        }
    }

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

    @Inject(method = "isChunkGeneratedAt", at = @At("HEAD"), cancellable = true)
    private void onIsChunkGeneratedAt(int x, int z, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldRegistry.find(world, x, z) != null) {
            cir.setReturnValue(true);
        }
    }
}
