package supersymmetry.api.block;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.block.Block;

public final class BlockExtraDataRegistry {

    private static final Map<Class<? extends Block>, BlockExtraDataHandler> HANDLERS = new HashMap<>();

    private BlockExtraDataRegistry() {}

    public static void register(Class<? extends Block> blockClass, BlockExtraDataHandler handler) {
        HANDLERS.put(blockClass, handler);
    }

    @Nullable
    public static BlockExtraDataHandler get(Block block) {
        for (Class<?> c = block.getClass(); c != null && Block.class.isAssignableFrom(c); c = c.getSuperclass()) {
            BlockExtraDataHandler handler = HANDLERS.get(c);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }
}
