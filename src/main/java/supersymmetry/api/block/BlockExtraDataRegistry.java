package supersymmetry.api.block;

import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import supersymmetry.api.SusyLog;

public final class BlockExtraDataRegistry {

    private static final Map<Class<? extends Block>, BlockExtraDataHandler> HANDLERS = new Object2ObjectOpenHashMap<>();

    private BlockExtraDataRegistry() {}

    public static void register(Class<? extends Block> blockClass, BlockExtraDataHandler handler) {
        HANDLERS.put(blockClass, handler);
    }

    @Nullable public static BlockExtraDataHandler get(Block block) {
        for (Class<?> c = block.getClass(); c != null && Block.class.isAssignableFrom(c); c = c.getSuperclass()) {
            BlockExtraDataHandler handler = HANDLERS.get(c);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    public static void restore(World world, Block block, BlockPos global, @Nullable NBTTagCompound extra) {
        if (extra == null) {
            return;
        }
        BlockExtraDataHandler handler = get(block);
        if (handler == null) {
            return;
        }
        try {
            handler.restore(world, global, extra);
        } catch (Exception e) {
            SusyLog.logger.warn("failed to restore extra state at {}", global, e);
        }
    }
}
