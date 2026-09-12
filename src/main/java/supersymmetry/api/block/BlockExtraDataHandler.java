package supersymmetry.api.block;

import org.jetbrains.annotations.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public interface BlockExtraDataHandler {

    @Nullable
    NBTTagCompound capture(World world, BlockPos pos, IBlockState state);

    void restore(World world, BlockPos pos, NBTTagCompound extra);
}
