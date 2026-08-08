package supersymmetry.api.block;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import gregtech.api.block.VariantActiveBlock;

public class VariantActiveBlockExtraDataHandler implements BlockExtraDataHandler {

    public static final VariantActiveBlockExtraDataHandler INSTANCE = new VariantActiveBlockExtraDataHandler();

    private static final String ACTIVE = "active";

    @Override
    @Nullable
    public NBTTagCompound capture(World world, BlockPos pos, IBlockState state) {
        if (VariantActiveBlock.isBlockActive(world.provider.getDimension(), pos)) {
            NBTTagCompound extra = new NBTTagCompound();
            extra.setBoolean(ACTIVE, true);
            return extra;
        }
        return null;
    }

    @Override
    public void restore(World world, BlockPos pos, NBTTagCompound extra) {
        VariantActiveBlock.setBlockActive(world.provider.getDimension(), pos, extra.getBoolean(ACTIVE));
    }
}
