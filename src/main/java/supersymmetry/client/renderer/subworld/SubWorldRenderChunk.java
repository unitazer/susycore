package supersymmetry.client.renderer.subworld;

import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.chunk.RenderChunk;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class SubWorldRenderChunk extends RenderChunk {

    private final SubWorldChunkRenderer owner;

    public SubWorldRenderChunk(World world, RenderGlobal renderGlobal, SubWorldChunkRenderer owner, int index) {
        super(world, renderGlobal, index);
        this.owner = owner;
    }

    @Override
    protected double getDistanceSq() {
        BlockPos camLocal = owner.getProjectedCamera();
        double d0 = this.boundingBox.minX + 8.0D - camLocal.getX();
        double d1 = this.boundingBox.minY + 8.0D - camLocal.getY();
        double d2 = this.boundingBox.minZ + 8.0D - camLocal.getZ();
        return d0 * d0 + d1 * d1 + d2 * d2;
    }
}
