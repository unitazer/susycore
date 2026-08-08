package supersymmetry.client.renderer.handler.entity;

import javax.annotation.Nullable;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;

@SideOnly(Side.CLIENT)
public class RenderSubWorldEntity extends Render<PhysicsWorldEntity> {

    public RenderSubWorldEntity(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(
                         PhysicsWorldEntity entity,
                         double x,
                         double y,
                         double z,
                         float entityYaw,
                         float partialTicks) {
        SubWorldChunkRenderer renderer = SubWorldChunkRenderer.getOrCreate(entity);
        if (renderer == null) {
            return;
        }
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        renderer.render(partialTicks);
        GlStateManager.popMatrix();
    }

    @Nullable
    @Override
    protected ResourceLocation getEntityTexture(PhysicsWorldEntity entity) {
        return null;
    }
}
