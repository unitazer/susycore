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

    public static final int BARREL_LENGTH = 100;
    public static final int TRUNNION_I = 4;
    public static final int CONTROLLER_I = 0;

    public TestingBarrelMTE(ResourceLocation res) {
        super(res);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new TestingBarrelMTE(this.metaTileEntityId);
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        var outerBreech = 5.0D;
        var outerMuzzle = 2.8D;
        var innerRadius = 1.2D;
        var breechCap = 3;

        var rowLength = Math.max(1, BARREL_LENGTH);
        var size = (int) (outerBreech * 2) + 1;
        var center = (size - 1) / 2.0D;
        var innerSq = innerRadius * innerRadius;
        var span = Math.max(1, rowLength - breechCap - 1);

        var outerSq = new double[rowLength];
        for (var i = 0; i < rowLength; i++) {
            var t = i <= breechCap ? 0.0D : Math.min(1.0D, (double) (i - breechCap) / span);
            var r = outerBreech - (outerBreech - outerMuzzle) * t;
            outerSq[i] = r * r;
        }

        var ci = (int) Math.round(center);
        var sy = ci;
        var sz = ci;

        var factory = FactoryBlockPattern.start();
        for (var y = 0; y < size; y++) {
            var aisle = new String[size];
            for (var z = 0; z < size; z++) {
                var dy = y - center;
                var dz = z - center;
                var distSq = dy * dy + dz * dz;
                var row = new char[rowLength];
                for (var i = 0; i < rowLength; i++) {
                    var solid = i < breechCap ? distSq <= outerSq[i] : distSq <= outerSq[i] && distSq >= innerSq;
                    row[i] = solid ? 'C' : ' ';
                }
                if (y == sy && z == sz) row[CONTROLLER_I] = 'S';
                aisle[z] = new String(row);
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
    public boolean allowsExtendedFacing() {
        return false;
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
