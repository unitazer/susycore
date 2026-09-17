package supersymmetry.api.phys;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.SusyLog;
import supersymmetry.api.block.BlockExtraDataHandler;
import supersymmetry.api.block.BlockExtraDataRegistry;

public final class BlockExtraStateTransfer {

    private BlockExtraStateTransfer() {}

    public static void restore(World world, Block block, BlockPos global, @Nullable NBTTagCompound extra) {
        if (extra == null) {
            return;
        }
        BlockExtraDataHandler handler = BlockExtraDataRegistry.get(block);
        if (handler == null) {
            return;
        }
        try {
            handler.restore(world, global, extra);
        } catch (Exception e) {
            SusyLog.logger.warn("BlockExtraStateTransfer: failed to restore extra state at {}", global, e);
        }
    }
}
