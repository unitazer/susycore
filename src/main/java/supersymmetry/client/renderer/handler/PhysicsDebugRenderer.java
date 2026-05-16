package supersymmetry.client.renderer.handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import supersymmetry.Supersymmetry;
import supersymmetry.api.phys.AbstractPhysicsEntity;
import supersymmetry.api.phys.IShape;
import supersymmetry.api.phys.Quaternion;
import supersymmetry.api.phys.Rapier;

// this should be removed eventually
@Mod.EventBusSubscriber(modid = Supersymmetry.MODID)
public class PhysicsDebugRenderer {

    public static List<AxisAlignedBB> debugBoxes = new ArrayList<>();
    public static List<AxisAlignedBB> debugBoxes2 = new ArrayList<>();
    public static volatile List<AxisAlignedBB> debugBoxes3 = new ArrayList<>();
    private static volatile List<AxisAlignedBB> writeBuffer = Collections.synchronizedList(new ArrayList<>());

    public static void clear_boxes() {
        if (!writeBuffer.isEmpty()) {
            debugBoxes3 = writeBuffer;
            writeBuffer = Collections.synchronizedList(new ArrayList<>());
        }
    }

    private static void drawOrientedBox(Vec3d[] c, float r, float g, float b, float a) {
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buf = tessellator.getBuffer();
        buf.begin(1, DefaultVertexFormats.POSITION_COLOR);
        addLine(buf, c[0], c[1], r, g, b, a);
        addLine(buf, c[1], c[3], r, g, b, a);
        addLine(buf, c[3], c[2], r, g, b, a);
        addLine(buf, c[2], c[0], r, g, b, a);
        addLine(buf, c[4], c[5], r, g, b, a);
        addLine(buf, c[5], c[7], r, g, b, a);
        addLine(buf, c[7], c[6], r, g, b, a);
        addLine(buf, c[6], c[4], r, g, b, a);
        addLine(buf, c[0], c[4], r, g, b, a);
        addLine(buf, c[1], c[5], r, g, b, a);
        addLine(buf, c[2], c[6], r, g, b, a);
        addLine(buf, c[3], c[7], r, g, b, a);
        tessellator.draw();
    }

    private static void addLine(BufferBuilder buf, Vec3d from, Vec3d to, float r, float g, float b, float a) {
        buf.pos(from.x, from.y, from.z).color(r, g, b, a).endVertex();
        buf.pos(to.x, to.y, to.z).color(r, g, b, a).endVertex();
    }

    public static void add_box(
                               double minx, double miny, double minz, double maxx, double maxy, double maxz) {
        writeBuffer.add(new AxisAlignedBB(minx, miny, minz, maxx, maxy, maxz));
    }

    @SubscribeEvent
    public static void onRenderWorldLast(RenderWorldLastEvent event) {
        if (!Minecraft.getMinecraft().getRenderManager().isDebugBoundingBox()) return;
        List<AxisAlignedBB> boxes = new ArrayList<>(debugBoxes);
        List<AxisAlignedBB> boxes2 = new ArrayList<>(debugBoxes2);
        List<AxisAlignedBB> boxes3 = new ArrayList<>(debugBoxes3);
        Entity view = Minecraft.getMinecraft().getRenderViewEntity();
        if (view == null) return;

        double dx = view.lastTickPosX + (view.posX - view.lastTickPosX) * event.getPartialTicks();
        double dy = view.lastTickPosY + (view.posY - view.lastTickPosY) * event.getPartialTicks();
        double dz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * event.getPartialTicks();

        GlStateManager.pushMatrix();
        GlStateManager.depthMask(false);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ZERO);
        // GlStateManager.glLineWidth(0.5F);
        //
        // for (AxisAlignedBB box : boxes) {
        // AxisAlignedBB renderBox = box.offset(-dx, -dy, -dz);
        // RenderGlobal.drawSelectionBoundingBox(renderBox, 0.0F, 1.0F, 0.0F, 0.8F);
        // }
        GlStateManager.glLineWidth(1.0F);
        for (AxisAlignedBB box : boxes2) {
            AxisAlignedBB renderBox = box.offset(-dx, -dy, -dz);
            RenderGlobal.drawSelectionBoundingBox(renderBox, 0.5F, 0.0F, 0.5F, 1F);
        }
        for (AxisAlignedBB box : boxes3) {
            AxisAlignedBB renderBox = box.offset(-dx, -dy, -dz);
            RenderGlobal.drawSelectionBoundingBox(renderBox, 1F, 1.0F, 0.0F, 0.8F);
        }

        if (!Rapier.entities.isEmpty()) {
            for (AbstractPhysicsEntity phys : Rapier.entities) {
                IShape shape = phys.getShape();
                Quaternion rot = phys.getRotation();

                AxisAlignedBB local = shape.boundingBox(Vec3d.ZERO, Quaternion.IDENTITY);

                double lx1 = local.minX, ly1 = local.minY, lz1 = local.minZ;
                double lx2 = local.maxX, ly2 = local.maxY, lz2 = local.maxZ;

                double ex = phys.posX;
                double ey = phys.posY;
                double ez = phys.posZ;

                Vec3d[] localCorners = {
                        new Vec3d(lx1, ly1, lz1), new Vec3d(lx2, ly1, lz1),
                        new Vec3d(lx1, ly1, lz2), new Vec3d(lx2, ly1, lz2),
                        new Vec3d(lx1, ly2, lz1), new Vec3d(lx2, ly2, lz1),
                        new Vec3d(lx1, ly2, lz2), new Vec3d(lx2, ly2, lz2),
                };

                Vec3d[] worldCorners = new Vec3d[8];
                for (int i = 0; i < 8; i++) {
                    Vec3d rp = rot.rotatePoint(localCorners[i]);
                    worldCorners[i] = new Vec3d(rp.x + ex - dx, rp.y + ey - dy, rp.z + ez - dz);
                }

                drawOrientedBox(worldCorners, 0.0F, 1.0F, 0.0F, 0.6F);
            }
        }

        GlStateManager.glLineWidth(1.0F);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.depthMask(true);
        GlStateManager.popMatrix();
    }
}
