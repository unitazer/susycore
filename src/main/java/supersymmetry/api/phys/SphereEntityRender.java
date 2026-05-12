package supersymmetry.api.phys;

import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

public class SphereEntityRender extends Render<DebugSphereEntity> {

    public SphereEntityRender(RenderManager renderManager) {
        super(renderManager);
    }

    @Override
    protected ResourceLocation getEntityTexture(DebugSphereEntity entity) {
        return null;
    }

    @Override
    public void doRender(
                         DebugSphereEntity entity, double x, double y, double z, float entityYaw, float partialTicks) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.rotate(entity.getRotation().toLWJGL());
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GlStateManager.color(1f, 0.447f, 0.015f, 1f);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION);
        var radius = entity.shape.data()[0];

        float step = (float) (Math.PI / 20);
        for (float theta = 0; theta < Math.PI; theta += step) {
            for (float phi = 0; phi < 2 * Math.PI; phi += step) {
                float st = (float) Math.sin(theta), ct = (float) Math.cos(theta);
                float sp = (float) Math.sin(phi), cp = (float) Math.cos(phi);
                float st1 = (float) Math.sin(theta + step), ct1 = (float) Math.cos(theta + step);
                float sp1 = (float) Math.sin(phi + step), cp1 = (float) Math.cos(phi + step);
                buf.pos(radius * st * cp, radius * ct, radius * st * sp).endVertex();
                buf.pos(radius * st * cp1, radius * ct, radius * st * sp1).endVertex();
                buf.pos(radius * st1 * cp1, radius * ct1, radius * st1 * sp1).endVertex();
                buf.pos(radius * st1 * cp, radius * ct1, radius * st1 * sp).endVertex();
            }
        }
        tess.draw();
        GlStateManager.enableTexture2D();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }
}
