package supersymmetry.client.renderer.subworld;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.CompiledChunk;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import org.lwjgl.opengl.GL11;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.Quaternion;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.api.util.SuSyUtility;
import supersymmetry.mixins.minecraft.RenderGlobalAccessor;

@SideOnly(Side.CLIENT)
public class SubWorldChunkRenderer {

    private static final Map<PhysicsWorldEntity, SubWorldChunkRenderer> RENDERERS = new HashMap<>();

    private final PhysicsWorldEntity entity;
    private SubWorldPlot plot;
    private final World world;
    private final Long2ObjectMap<SubWorldRenderChunk> sections = new Long2ObjectOpenHashMap<>();
    private final List<SubWorldRenderChunk> dirty = new ArrayList<>();
    private float partialTicks;

    private SubWorldChunkRenderer(PhysicsWorldEntity entity, SubWorldPlot plot) {
        this.entity = entity;
        this.plot = plot;
        this.world = entity.world;
    }

    public static SubWorldChunkRenderer getOrCreate(PhysicsWorldEntity entity) {
        SubWorldPlot plot = entity.getPlot();
        if (plot == null) {
            return null;
        }
        SubWorldChunkRenderer renderer = RENDERERS.get(entity);
        if (renderer == null) {
            renderer = new SubWorldChunkRenderer(entity, plot);
            RENDERERS.put(entity, renderer);
        } else if (renderer.plot != plot) {
            renderer.deleteGlResources();
            renderer.plot = plot;
        }
        return renderer;
    }

    public static void pruneDead() {
        var it = RENDERERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<PhysicsWorldEntity, SubWorldChunkRenderer> entry = it.next();
            PhysicsWorldEntity entity = entry.getKey();
            if (entity.isDead || entity.world != Minecraft.getMinecraft().world) {
                entry.getValue().deleteGlResources();
                it.remove();
            }
        }
    }

    public BlockPos getProjectedCamera() {
        EntityPlayer player = Minecraft.getMinecraft().player;
        BlockPos origin = plot.getRenderOrigin();
        double dx = player.lastTickPosX + (player.posX - player.lastTickPosX) * partialTicks - entity.posX;
        double dy = player.lastTickPosY + (player.posY - player.lastTickPosY) * partialTicks - entity.posY;
        double dz = player.lastTickPosZ + (player.posZ - player.lastTickPosZ) * partialTicks - entity.posZ;
        Vec3d local = entity.getRotation().conjugate().rotatePoint(new Vec3d(dx, dy, dz));
        Vec3d rp = entity.getRotationPoint();
        return new BlockPos(origin.getX() + rp.x + local.x, origin.getY() + rp.y + local.y,
                origin.getZ() + rp.z + local.z);
    }

    private void syncSections() {
        Long2ObjectOpenHashMap<SubWorldRenderChunk> present = new Long2ObjectOpenHashMap<>();
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            int localX = chunk.x - (plot.getRenderOrigin().getX() >> 4);
            int localZ = chunk.z - (plot.getRenderOrigin().getZ() >> 4);
            ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
            for (int sy = 0; sy < storages.length; sy++) {
                if (storages[sy] == null || storages[sy].isEmpty()) {
                    continue;
                }
                int key = (localX << 16) | (localZ << 8) | sy;
                present.put(key, null);
                if (!sections.containsKey(key)) {
                    SubWorldRenderChunk rc = new SubWorldRenderChunk(world, Minecraft.getMinecraft().renderGlobal,
                            this, sections.size());
                    rc.setPosition(chunk.x << 4, sy << 4, chunk.z << 4);
                    sections.put(key, rc);
                    dirty.add(rc);
                }
            }
        }
        List<Long> stale = new ArrayList<>();
        for (long key : sections.keySet()) {
            if (!present.containsKey(key)) {
                stale.add(key);
            }
        }
        for (long key : stale) {
            SubWorldRenderChunk rc = sections.remove(key);
            dirty.remove(rc);
            rc.deleteGlResources();
        }
    }

    private void compile() {
        if (dirty.isEmpty()) {
            return;
        }
        ChunkRenderDispatcher dispatcher = ((RenderGlobalAccessor) Minecraft.getMinecraft().renderGlobal)
                .getRenderDispatcher();
        for (SubWorldRenderChunk rc : dirty) {
            dispatcher.updateChunkNow(rc);
            rc.clearNeedsUpdate();
        }
        dirty.clear();
    }

    public static void renderBlockLayer(BlockRenderLayer layer, float partialTicks) {
        pruneDead();
        boolean solid = layer == BlockRenderLayer.SOLID;
        for (SubWorldChunkRenderer renderer : RENDERERS.values()) {
            renderer.partialTicks = partialTicks;
            if (solid) {
                renderer.syncSections();
            }
        }
        if (solid) {
            for (SubWorldChunkRenderer renderer : RENDERERS.values()) {
                renderer.compile();
            }
        }
        Frustum frustum = null;
        Vec3d cam = null;
        Entity cameraEntity = Minecraft.getMinecraft().getRenderViewEntity();
        if (cameraEntity != null) {
            cam = interpPos(cameraEntity, partialTicks);
            frustum = new Frustum();
            frustum.setPosition(cam.x, cam.y, cam.z);
        }
        for (SubWorldChunkRenderer renderer : RENDERERS.values()) {
            renderer.drawLayer(layer, frustum, cam);
        }
    }

    private void drawLayer(BlockRenderLayer layer, Frustum frustum, Vec3d cam) {
        if (sections.isEmpty()) {
            return;
        }
        BlockPos origin = plot.getRenderOrigin();
        Vec3d epos = interpPos(entity, partialTicks);
        Quaternion q = entity.getRenderRotation(partialTicks);
        Vec3d rp = entity.getRenderRotationPoint(partialTicks);
        for (SubWorldRenderChunk rc : sections.values()) {
            CompiledChunk cc = rc.getCompiledChunk();
            if (cc == CompiledChunk.DUMMY || cc.isLayerEmpty(layer)) {
                continue;
            }
            BlockPos sp = rc.getPosition();
            if (frustum != null && !isInFrustum(sp, q, rp, epos, origin, frustum)) {
                continue;
            }
            GlStateManager.pushMatrix();
            if (cam != null) {
                GlStateManager.translate(epos.x - cam.x, epos.y - cam.y, epos.z - cam.z);
            }
            GlStateManager.rotate(q.toLWJGL());
            GlStateManager.translate(sp.getX() - origin.getX() - rp.x, sp.getY() - origin.getY() - rp.y,
                    sp.getZ() - origin.getZ() - rp.z);
            VertexBuffer vb = rc.getVertexBufferByLayer(layer.ordinal());
            vb.bindBuffer();
            setupArrayPointers();
            vb.drawArrays(GL11.GL_QUADS);
            GlStateManager.popMatrix();
        }
        OpenGlHelper.glBindBuffer(OpenGlHelper.GL_ARRAY_BUFFER, 0);
    }

    private boolean isInFrustum(BlockPos sp, Quaternion q, Vec3d rp, Vec3d epos, BlockPos origin, Frustum frustum) {
        double sx = sp.getX() - origin.getX() - rp.x;
        double sy = sp.getY() - origin.getY() - rp.y;
        double sz = sp.getZ() - origin.getZ() - rp.z;
        AxisAlignedBB rotated = q.rotateAABB(new AxisAlignedBB(sx, sy, sz, sx + 16, sy + 16, sz + 16))
                .offset(epos.x, epos.y, epos.z);
        return frustum.isBoundingBoxInFrustum(rotated);
    }

    private static Vec3d interpPos(Entity entity, float partialTicks) {
        return SuSyUtility.lerp(
                new Vec3d(entity.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ),
                new Vec3d(entity.posX, entity.posY, entity.posZ),
                partialTicks);
    }

    public void render(float partialTicks) {
        this.partialTicks = partialTicks;
        pruneDead();
        syncSections();
        compile();
        renderBlockEntities(partialTicks);
    }

    private void renderBlockEntities(float partialTicks) {
        RenderHelper.enableStandardItemLighting();
        TileEntityRendererDispatcher dispatcher = TileEntityRendererDispatcher.instance;
        Quaternion q = entity.getRenderRotation(partialTicks);
        Vec3d rp = entity.getRenderRotationPoint(partialTicks);
        for (SubWorldRenderChunk rc : sections.values()) {
            for (TileEntity te : rc.getCompiledChunk().getTileEntities()) {
                BlockPos local = plot.toLocal(te.getPos());
                Vec3d rotated = q.rotatePoint(new Vec3d(local.getX() - rp.x, local.getY() - rp.y, local.getZ() - rp.z));
                dispatcher.render(te, rotated.x, rotated.y, rotated.z, partialTicks, 0, 1.0F);
            }
        }
        RenderHelper.disableStandardItemLighting();
    }

    public static void markSectionDirty(World world, BlockPos pos) {
        for (SubWorldChunkRenderer renderer : RENDERERS.values()) {
            if (renderer.world == world) {
                renderer.markDirty(pos);
            }
        }
    }

    public static void markRangeDirty(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (RENDERERS.isEmpty()) {
            return;
        }
        long area = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        for (SubWorldChunkRenderer renderer : RENDERERS.values()) {
            if (renderer.world != world || renderer.plot == null) {
                continue;
            }
            if (area > 65536L) {
                for (SubWorldRenderChunk rc : renderer.sections.values()) {
                    if (!renderer.dirty.contains(rc)) {
                        renderer.dirty.add(rc);
                    }
                }
                continue;
            }
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        renderer.markDirty(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    private void markDirty(BlockPos pos) {
        if (plot == null) {
            return;
        }
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (!plot.inBounds(chunkX, chunkZ)) {
            return;
        }
        int localX = chunkX - (plot.getRenderOrigin().getX() >> 4);
        int localZ = chunkZ - (plot.getRenderOrigin().getZ() >> 4);
        int key = (localX << 16) | (localZ << 8) | (pos.getY() >> 4);
        SubWorldRenderChunk rc = sections.get(key);
        if (rc != null && !dirty.contains(rc)) {
            dirty.add(rc);
        }
    }

    private void setupArrayPointers() {
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, 28, 0);
        GlStateManager.glColorPointer(4, GL11.GL_UNSIGNED_BYTE, 28, 12);
        GlStateManager.glTexCoordPointer(2, GL11.GL_FLOAT, 28, 16);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.lightmapTexUnit);
        GlStateManager.glTexCoordPointer(2, GL11.GL_SHORT, 28, 24);
        OpenGlHelper.setClientActiveTexture(OpenGlHelper.defaultTexUnit);
    }

    private void deleteGlResources() {
        for (SubWorldRenderChunk rc : sections.values()) {
            rc.deleteGlResources();
        }
        sections.clear();
        dirty.clear();
    }
}
