package supersymmetry.client.renderer.handler.entity;

import java.util.List;
import java.util.Random;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.init.Biomes;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.EnumBlockRenderType;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import codechicken.lib.render.CCRenderState;
import codechicken.lib.render.pipeline.IVertexOperation;
import codechicken.lib.vec.Matrix4;
import gregtech.api.block.VariantActiveBlock;
import gregtech.api.metatileentity.IFastRenderMetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.PhysicsWorldEntity.BlockStateInfo;

@SideOnly(Side.CLIENT)
public class RenderPhysicsWorldEntity extends Render<PhysicsWorldEntity> {

    public RenderPhysicsWorldEntity(RenderManager renderManager) {
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
        List<BlockStateInfo> blocks = entity.getClientBlocks();
        if (blocks == null || blocks.isEmpty()) return;

        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, z);
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.LOCATION_BLOCKS_TEXTURE);

        BlockRendererDispatcher dispatcher = Minecraft.getMinecraft().getBlockRendererDispatcher();
        Random rand = new Random();
        Tessellator tessellator = Tessellator.getInstance();

        IBlockAccess lightWrapper = new IBlockAccess() {

            public TileEntity getTileEntity(BlockPos pos) {
                return null;
            }

            public int getCombinedLight(BlockPos pos, int minLight) {
                return 15728640;
            }

            public IBlockState getBlockState(BlockPos pos) {
                return Blocks.AIR.getDefaultState();
            }

            public boolean isAirBlock(BlockPos pos) {
                return true;
            }

            public Biome getBiome(BlockPos pos) {
                return Biomes.PLAINS;
            }

            public int getStrongPower(BlockPos pos, EnumFacing direction) {
                return 0;
            }

            public WorldType getWorldType() {
                return WorldType.DEFAULT;
            }

            public boolean isSideSolid(BlockPos pos, EnumFacing side, boolean _default) {
                return false;
            }
        };

        TileEntityRendererDispatcher terDispatcher = TileEntityRendererDispatcher.instance;
        for (BlockStateInfo info : blocks) {
            IBlockState state = info.state;

            GlStateManager.pushMatrix();
            GlStateManager.translate(
                    info.pos.getX(),
                    info.pos.getY() - 127,
                    info.pos.getZ());

            NBTTagCompound teNbt = info.tileData;

            if (teNbt != null) {
                TileEntity te = TileEntity.create(entity.world, teNbt);
                if (te instanceof MetaTileEntityHolder holder && holder.getMetaTileEntity() != null) {
                    MetaTileEntity mte = holder.getMetaTileEntity();
                    CCRenderState renderState = CCRenderState.instance();
                    renderState.reset();
                    renderState.startDrawing(org.lwjgl.opengl.GL11.GL_QUADS,
                            DefaultVertexFormats.BLOCK);
                    renderState.lightMatrix.locate(lightWrapper, BlockPos.ORIGIN);
                    IVertexOperation[] pipeline = new IVertexOperation[] { renderState.lightMatrix };
                    mte.renderMetaTileEntity(
                            renderState, new Matrix4(), pipeline);
                    mte.renderCovers(
                            renderState, new Matrix4(), BlockRenderLayer.CUTOUT_MIPPED);
                    renderState.draw();

                    if (mte instanceof IFastRenderMetaTileEntity fast) {
                        fast.renderMetaTileEntity(0, 0, 0, partialTicks);
                    }
                } else if (te != null) {
                    te.setWorld(entity.world);
                    terDispatcher.render(te, 0, 0, 0, partialTicks, 0, 1.0F);
                }
            }

            if (teNbt == null && state.getRenderType() == EnumBlockRenderType.MODEL) {
                IBakedModel model = dispatcher.getModelForState(state);
                IBlockState renderState = state;
                if (state.getBlock() instanceof VariantActiveBlock) {
                    renderState = state.getBlock().getExtendedState(state, lightWrapper, info.pos);
                }
                BufferBuilder buffer = tessellator.getBuffer();
                buffer.begin(org.lwjgl.opengl.GL11.GL_QUADS,
                        DefaultVertexFormats.BLOCK);
                dispatcher.getBlockModelRenderer().renderModel(
                        lightWrapper, model, renderState, BlockPos.ORIGIN,
                        buffer, false, rand.nextLong());
                tessellator.draw();
            }

            GlStateManager.popMatrix();
        }

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.popMatrix();
    }

    @Nullable
    @Override
    protected ResourceLocation getEntityTexture(PhysicsWorldEntity entity) {
        return null;
    }
}
