package supersymmetry.api.phys;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

public class CuboidEntityRender extends Render<DebugCuboidEntity> {

    public CuboidEntityRender(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    public void doRender(
                         DebugCuboidEntity entity, double x, double y, double z, float entityYaw, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(entity.getRotation().toLWJGL());
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(0.3f, 0.0f, 0.3f, 0.9f);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        float hx = entity.shape.data()[0];
        float hy = entity.shape.data()[1];
        float hz = entity.shape.data()[2];

        float x0 = -hx, x1 = hx;
        float y0 = -hy, y1 = hy;
        float z0 = -hz, z1 = hz;

        buf.pos(x0, y0, z0).endVertex();
        buf.pos(x1, y0, z0).endVertex();
        buf.pos(x1, y0, z1).endVertex();
        buf.pos(x0, y0, z1).endVertex();

        buf.pos(x0, y1, z0).endVertex();
        buf.pos(x0, y1, z1).endVertex();
        buf.pos(x1, y1, z1).endVertex();
        buf.pos(x1, y1, z0).endVertex();

        buf.pos(x0, y0, z0).endVertex();
        buf.pos(x0, y1, z0).endVertex();
        buf.pos(x1, y1, z0).endVertex();
        buf.pos(x1, y0, z0).endVertex();

        buf.pos(x0, y0, z1).endVertex();
        buf.pos(x1, y0, z1).endVertex();
        buf.pos(x1, y1, z1).endVertex();
        buf.pos(x0, y1, z1).endVertex();

        buf.pos(x0, y0, z0).endVertex();
        buf.pos(x0, y0, z1).endVertex();
        buf.pos(x0, y1, z1).endVertex();
        buf.pos(x0, y1, z0).endVertex();

        buf.pos(x1, y0, z0).endVertex();
        buf.pos(x1, y1, z0).endVertex();
        buf.pos(x1, y1, z1).endVertex();
        buf.pos(x1, y0, z1).endVertex();

        tess.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    @Override
    protected ResourceLocation getEntityTexture(DebugCuboidEntity entity) {
        return null;
    }
}
