package supersymmetry.mixins.minecraft;

import net.minecraft.client.renderer.chunk.ChunkRenderWorker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.subworld.SubWorldRegistry;

@Mixin(ChunkRenderWorker.class)
public abstract class ChunkRenderWorkerMixin {

    @Inject(method = "isChunkExisting", at = @At("HEAD"), cancellable = true)
    private void susy$plotChunksAlwaysExist(BlockPos pos, World worldIn, CallbackInfoReturnable<Boolean> cir) {
        if (SubWorldRegistry.inPlotArea(worldIn, pos.getX() >> 4, pos.getZ() >> 4, 1)) {
            cir.setReturnValue(true);
        }
    }
}
