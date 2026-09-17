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

    private static final int BARREL_LENGTH = 9;

    public TestingBarrelMTE(ResourceLocation res) {
        super(res);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new TestingBarrelMTE(this.metaTileEntityId);
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        String[] column = new String[BARREL_LENGTH + 1];
        column[0] = "S";
        for (int i = 1; i <= BARREL_LENGTH; i++) {
            column[i] = "C";
        }
        return FactoryBlockPattern.start()
                .aisle(column)
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
