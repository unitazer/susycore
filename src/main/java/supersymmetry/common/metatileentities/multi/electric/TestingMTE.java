package supersymmetry.common.metatileentities.multi.electric;

import java.util.ArrayList;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.NotNull;

import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.ClickButtonWidget;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockAbility;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.BlockPattern;
import gregtech.api.pattern.FactoryBlockPattern;
import gregtech.api.util.BlockInfo;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockMetalCasing.MetalCasingType;
import gregtech.common.blocks.MetaBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import supersymmetry.api.SusyLog;
import supersymmetry.api.phys.DebugCuboidEntity;
import supersymmetry.api.phys.DebugSphereEntity;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.PocketWorld;
import supersymmetry.api.phys.Rapier;

public class TestingMTE extends MultiblockWithDisplayBase {

    public TestingMTE(ResourceLocation res) {
        super(res);
    }

    @Override
    public void update() {
        super.update();
        if (getWorld().isRemote) return;
        if (getWorld().getTotalWorldTime() % 50 == 0) {
            SusyLog.logger.info("TestingMTE at {}, formed: {}", getPos(), this.isStructureFormed());
        }
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new TestingMTE(this.metaTileEntityId);
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        return FactoryBlockPattern.start()
                .aisle("XXX", "CCC", "CCC", "XXX")
                .aisle("XXX", "C#C", "C#C", "XMX")
                .aisle("XSX", "CCC", "CCC", "XXX")
                .where('S', selfPredicate())
                .where(
                        'X',
                        states(MetaBlocks.METAL_CASING.getState(MetalCasingType.INVAR_HEATPROOF))
                                .setMinGlobalLimited(9)
                                .or(autoAbilities(true, true)))
                .where('M', abilities(MultiblockAbility.MUFFLER_HATCH))
                .where('C', heatingCoils())
                .where('#', air())
                .build();
    }

    @Override
    protected void updateFormedValid() {}

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
        return Textures.STEEL_FIREBOX;
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        return createUITemplate(entityPlayer).build(getHolder(), entityPlayer);
    }

    //
    // private static long BallHandle = 0;

    protected ModularUI.Builder createUITemplate(EntityPlayer entityPlayer) {
        ModularUI.Builder builder = ModularUI.builder(GuiTextures.BACKGROUND, 198, 208);
        builder.widget(
                new ClickButtonWidget(
                        58,
                        10,
                        25,
                        18,
                        "init",
                        (clickData -> {
                            Rapier.initialize_world(this.getWorld(), -10f, 0.00001);
                        })));
        builder.widget(
                new ClickButtonWidget(
                        10,
                        10,
                        38,
                        18,
                        "blow up",
                        (clickData -> {
                            Rapier.handleChunkAddition(this.getWorld().getChunk(this.getPos()));
                        })));
        builder.widget(
                new ClickButtonWidget(
                        148,
                        10,
                        38,
                        18,
                        "step",
                        (clickData -> {
                            // Rapier.RbInfo(0, BallHandle);
                            Rapier.step_world(this.getWorld());
                        })));
        builder.widget(
                new ClickButtonWidget(
                        148,
                        30,
                        38,
                        18,
                        "sphere",
                        (clickData -> {
                            var entity = new DebugSphereEntity(this.getWorld());
                            entity.setPosition(this.getPos().getX(), 100, this.getPos().getZ());
                            this.getWorld().spawnEntity(entity);
                        })));
        builder.widget(
                new ClickButtonWidget(
                        148,
                        50,
                        38,
                        18,
                        "cuboid",
                        (clickData -> {
                            var entity = new DebugCuboidEntity(this.getWorld());
                            entity.setPosition(this.getPos().getX(), 100, this.getPos().getZ());
                            this.getWorld().spawnEntity(entity);
                        })));
        builder.widget(
                new ClickButtonWidget(
                        14,
                        80,
                        38,
                        18,
                        "entity",
                        (clickData -> {
                            goog();
                        })));

        return builder;
    }

    private void goog() {
        if (this.isStructureFormed() && this.structurePattern != null) {
            var cache = this.structurePattern.cache;
            var box = new AxisAlignedBB(this.getPos());
            for (Long2ObjectMap.Entry<BlockInfo> entry : cache.long2ObjectEntrySet()) {
                BlockPos pos = BlockPos.fromLong(entry.getLongKey());
                box = box.union(new AxisAlignedBB(pos));
            }

            var center = box.getCenter();
            var offsetd = (Vec3d.ZERO.subtract(center)).add(new Vec3d(0d, 127d, 0d));
            var offset = new BlockPos(offsetd.x, offsetd.y, offsetd.z);

            var entity = new PhysicsWorldEntity(this.getWorld());
            entity.setPosition(-offset.getX(), -offset.getY() + 127, -offset.getZ());

            var world = new PocketWorld(entity);

            var toRemove = new ArrayList<BlockPos>();
            for (Long2ObjectMap.Entry<BlockInfo> entry : cache.long2ObjectEntrySet()) {
                BlockPos lpos = BlockPos.fromLong(entry.getLongKey());
                var pos = lpos.add(offset);
                world.setBlockState(pos, entry.getValue().getBlockState());
                TileEntity te = entry.getValue().getTileEntity();
                if (te != null) {
                    NBTTagCompound tag = te.serializeNBT();
                    world.removeTileEntity(pos);
                    world.setBlockState(pos, entry.getValue().getBlockState());
                    TileEntity fresh = world.getTileEntity(pos);
                    if (fresh != null) {
                        fresh.deserializeNBT(tag);
                        fresh.setPos(pos);
                    }
                }
                toRemove.add(lpos);
            }

            entity.updatePocketBounds();
            this.getWorld().spawnEntity(entity);
            for (BlockPos lpos : toRemove) {
                this.getWorld().setBlockToAir(lpos);
            }
        }
    }
}
