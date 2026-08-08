package supersymmetry.mixins.minecraft;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderGlobal.class)
public interface RenderGlobalAccessor {

    @Accessor("renderDispatcher")
    ChunkRenderDispatcher getRenderDispatcher();
}
