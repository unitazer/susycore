package supersymmetry.mixins.minecraft;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;

@Mixin(TileEntity.class)
public abstract class TileEntityMixin {

    @Shadow
    public World world;

    @Shadow
    public BlockPos pos;

    @Inject(method = "handleUpdateTag", remap = false, at = @At("RETURN"))
    private void susy$markPlotSectionDirtyOnSync(NBTTagCompound tag, CallbackInfo ci) {
        if (world != null && world.isRemote) {
            SubWorldChunkRenderer.markSectionDirty(world, pos);
        }
    }
}
