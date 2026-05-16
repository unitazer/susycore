package supersymmetry.mixins.minecraft;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import supersymmetry.api.phys.ChunkSyncManager;
import supersymmetry.common.EventHandlers;

@Mixin(Chunk.class)
public class ChunkMixin {

    @Shadow
    @Final
    private World world;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void onSetBlockState(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        if (this.world.isRemote) return;
        IBlockState oldState = cir.getReturnValue();
        if (oldState == null || oldState == state) return;

        ChunkSyncManager csm = EventHandlers.chunkSyncManagers.get(this.world);
        if (csm != null) csm.handleBlockChange(pos);
    }
}
