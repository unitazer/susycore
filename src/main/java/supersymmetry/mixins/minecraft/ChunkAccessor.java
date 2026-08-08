package supersymmetry.mixins.minecraft;

import java.util.Map;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Chunk.class)
public interface ChunkAccessor {

    @Accessor("loaded")
    void setLoaded(boolean loaded);

    @Accessor("tileEntities")
    Map<BlockPos, TileEntity> getTileEntityMap();
}
