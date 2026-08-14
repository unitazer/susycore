package supersymmetry.mixins.minecraft;

import net.minecraft.util.BitArray;
import net.minecraft.world.chunk.BlockStateContainer;
import net.minecraft.world.chunk.IBlockStatePalette;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlockStateContainer.class)
public interface BlockStateContainerAccessor {

    @Accessor
    BitArray getStorage();

    @Accessor
    IBlockStatePalette getPalette();
}
