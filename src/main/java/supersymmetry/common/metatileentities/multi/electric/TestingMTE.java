package supersymmetry.common.metatileentities.multi.electric;

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
import gregtech.client.renderer.ICubeRenderer;
import gregtech.client.renderer.texture.Textures;
import gregtech.common.blocks.BlockMetalCasing.MetalCasingType;
import gregtech.common.blocks.MetaBlocks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import supersymmetry.api.phys.Rapier;

public class TestingMTE extends MultiblockWithDisplayBase {
  public TestingMTE(ResourceLocation res) {
    super(res);
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
              Rapier.initialize_world(this.getWorld(), 10f, 0.00001);
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
    return builder;
  }
}
