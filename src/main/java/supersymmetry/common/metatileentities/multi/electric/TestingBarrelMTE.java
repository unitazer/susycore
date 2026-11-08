package supersymmetry.common.metatileentities.multi.electric;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;

import org.jetbrains.annotations.NotNull;

import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
import gregtech.api.metatileentity.multiblock.MultiblockWithDisplayBase;
import gregtech.api.pattern.BlockPattern;
import gregtech.api.pattern.FactoryBlockPattern;
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockMetalCasing.MetalCasingType;
import gregtech.common.blocks.MetaBlocks;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.UnformFlow;

public class TestingBarrelMTE extends MultiblockWithDisplayBase {

    private static final int BARREL_LENGTH = 100;

    public TestingBarrelMTE(ResourceLocation res) {
        super(res);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new TestingBarrelMTE(this.metaTileEntityId);
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        final double outerDiameter = 9.5D;
        final double innerRadius = 1.6D;

        final int size = (int) outerDiameter + 1;
        final double center = (size - 1) / 2.0D;
        final double outerRadius = outerDiameter / 2.0D;

        final int rowLength = Math.max(1, BARREL_LENGTH);
        final String casing = "C".repeat(rowLength);
        final String air = " ".repeat(rowLength);
        final String controller = "S" + casing.substring(1);

        var factory = FactoryBlockPattern.start();
        boolean placedController = false;

        for (int y = 0; y < size; y++) {
            String[] aisle = new String[size];

            for (int z = 0; z < size; z++) {
                double dy = y - center;
                double dz = z - center;
                double distSq = dy * dy + dz * dz;

                boolean inRing = distSq <= outerRadius * outerRadius && distSq >= innerRadius * innerRadius;

                aisle[z] = inRing ? casing : air;

                if (inRing && !placedController) {
                    aisle[z] = controller;
                    placedController = true;
                }
            }

            factory.aisle(aisle);
        }

        return factory
                .where('S', selfPredicate())
                .where('C', states(MetaBlocks.METAL_CASING.getState(MetalCasingType.INVAR_HEATPROOF)))
                .build();
    }

    @Override
    protected void updateFormedValid() {}

    @Override
    public void invalidateStructure() {
        boolean wasFormed = isStructureFormed();
        super.invalidateStructure();
        if (wasFormed && getWorld() != null && !getWorld().isRemote && !PhysicsWorldEntity.resyncingFormedMultiblocks) {
            UnformFlow.unform(PhysicsWorldEntity.forBlockPos(getWorld(), getPos()));
        }
    }

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
        return Textures.STEEL_FIREBOX;
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        return ModularUI.builder(GuiTextures.BACKGROUND, 198, 208).build(getHolder(), entityPlayer);
    }
}
