package supersymmetry.common.metatileentities.multi.electric;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import gregtech.api.gui.GuiTextures;
import gregtech.api.gui.ModularUI;
import gregtech.api.gui.widgets.ClickButtonWidget;
import gregtech.api.gui.widgets.SliderWidget;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import gregtech.api.metatileentity.interfaces.IGregTechTileEntity;
import gregtech.api.metatileentity.multiblock.IMultiblockPart;
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
import supersymmetry.api.phys.PhysicsBodyFormation;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.Rapier;
import supersymmetry.api.phys.UnformFlow;
import supersymmetry.api.phys.constraint.Axis;
import supersymmetry.api.phys.constraint.PhysicsConstraint;
import supersymmetry.api.subworld.SubWorldPlot;

public class TurretFoundationMTE extends MultiblockWithDisplayBase {

    private static final double HOLD_STIFFNESS = 30.0;
    private static final double HOLD_DAMPING = 8.0;
    private static final double AIM_ANGULAR_VELOCITY = 1.2;
    private static final double AIM_DRIVE_DAMPING = 80.0;
    private static final double AIM_TORQUE = 150.0;
    private static final float AIM_DEAD_ZONE = 0.02f;
    private static final int GUN_OFFSET_Y = 3;
    private static final int GUN_GRACE_TICKS = 100;

    @Nullable private UUID gunId;
    private int anchorTag;
    private int gunMisses;

    public TurretFoundationMTE(ResourceLocation res) {
        super(res);
    }

    @Override
    public MetaTileEntity createMetaTileEntity(IGregTechTileEntity tileEntity) {
        return new TurretFoundationMTE(this.metaTileEntityId);
    }

    @Override
    protected @NotNull BlockPattern createStructurePattern() {
        return FactoryBlockPattern.start()
                .aisle("          C          ")
                .aisle("      CCCCCCCCC      ")
                .aisle("    CCCCCCCCCCCCC    ")
                .aisle("   CCCCCCCCCCCCCCC   ")
                .aisle("  CCCCCCCCCCCCCCCCC  ")
                .aisle("  CCCCCCCCCCCCCCCCC  ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle("CCCCCCCCCCSCCCCCCCCCC")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle(" CCCCCCCCCCCCCCCCCCC ")
                .aisle("  CCCCCCCCCCCCCCCCC  ")
                .aisle("  CCCCCCCCCCCCCCCCC  ")
                .aisle("   CCCCCCCCCCCCCCC   ")
                .aisle("    CCCCCCCCCCCCC    ")
                .aisle("      CCCCCCCCC      ")
                .aisle("          C          ")
                .where('S', selfPredicate())
                .where('C', states(MetaBlocks.METAL_CASING.getState(MetalCasingType.INVAR_HEATPROOF)))
                .build();
    }

    @Override
    public void update() {
        super.update();
        World world = getWorld();
        if (world == null || world.isRemote || gunId == null) {
            return;
        }
        PhysicsWorldEntity gun = PhysicsConstraint.resolveEndpoint(world, gunId);
        if (gun == null || gun.isDead || !isStructureFormed()) {
            if (++gunMisses >= GUN_GRACE_TICKS) {
                releaseGun();
            }
            return;
        }
        gunMisses = 0;
        PhysicsConstraint constraint = staticConstraint(gun);
        if (constraint == null) {
            releaseGun();
            return;
        }
        ensureAnchor(constraint);
    }

    @Override
    public void invalidateStructure() {
        boolean wasFormed = isStructureFormed();
        super.invalidateStructure();
        if (wasFormed && getWorld() != null && !getWorld().isRemote) {
            releaseGun();
        }
    }

    @Override
    protected void updateFormedValid() {}

    @Override
    public ICubeRenderer getBaseTexture(IMultiblockPart sourcePart) {
        return Textures.STEEL_FIREBOX;
    }

    @Override
    protected ModularUI createUI(EntityPlayer entityPlayer) {
        ModularUI.Builder builder = ModularUI.builder(GuiTextures.BACKGROUND, 198, 208);
        builder.widget(
                new ClickButtonWidget(10, 10, 120, 18, "form turret entity", clickData -> formTurretEntity()));
        builder.widget(
                new SliderWidget("pitch (x)", 10, 40, 178, 20, -1.0f, 1.0f, 0.0f, value -> aim(Axis.ANG_X, value)));
        builder.widget(
                new SliderWidget("yaw (y)", 10, 70, 178, 20, -1.0f, 1.0f, 0.0f, value -> aim(Axis.ANG_Y, value)));
        return builder.build(getHolder(), entityPlayer);
    }

    private void formTurretEntity() {
        World world = getWorld();
        if (world.isRemote || !isStructureFormed() || structurePattern == null) return;
        if (gunId != null) {
            PhysicsWorldEntity existing = PhysicsConstraint.resolveEndpoint(world, gunId);
            if (existing != null && !existing.isDead) {
                return;
            }
            releaseGun();
        }
        TestingBarrelMTE gun = findGun();
        if (gun == null || !gun.isStructureFormed() || gun.structurePattern == null) {
            SusyLog.logger.warn("TurretFoundationMTE: no formed gun controller found {} blocks above {}",
                    GUN_OFFSET_Y, getPos());
            return;
        }
        if (PhysicsWorldEntity.forBlockPos(world, gun.getPos()) != null) {
            SusyLog.logger.warn("TurretFoundationMTE: gun controller {} already sits inside a physics body",
                    gun.getPos());
            return;
        }

        AxisAlignedBB box = TestingMTE.structureBounds(gun.structurePattern, gun.getPos());
        BlockPos offset = new BlockPos(1 - (int) box.minX, 1 - (int) box.minY, 1 - (int) box.minZ);

        float sx = (float) (box.maxX - box.minX);
        float sy = (float) (box.maxY - box.minY);
        float sz = (float) (box.maxZ - box.minZ);
        if (sy > PhysicsBodyFormation.MAX_CHILD_SIZE_Y) {
            SusyLog.logger.error(
                    "TurretFoundationMTE: gun height {} exceeds the {} block interaction height cap, not forming",
                    sy, PhysicsBodyFormation.MAX_CHILD_SIZE_Y);
            return;
        }

        int minLocalX = Integer.MAX_VALUE;
        int maxLocalX = Integer.MIN_VALUE;
        int minLocalZ = Integer.MAX_VALUE;
        int maxLocalZ = Integer.MIN_VALUE;
        for (Long2ObjectMap.Entry<BlockInfo> entry : gun.structurePattern.cache.long2ObjectEntrySet()) {
            BlockPos local = BlockPos.fromLong(entry.getLongKey()).add(offset);
            minLocalX = Math.min(minLocalX, local.getX());
            maxLocalX = Math.max(maxLocalX, local.getX());
            minLocalZ = Math.min(minLocalZ, local.getZ());
            maxLocalZ = Math.max(maxLocalZ, local.getZ());
        }
        int sizeChunksX = (maxLocalX >> 4) + 1;
        int sizeChunksZ = (maxLocalZ >> 4) + 1;

        PhysicsWorldEntity entity = new PhysicsWorldEntity(world);
        entity.setPosition(box.minX + sx / 2.0, box.minY + sy / 2.0, box.minZ + sz / 2.0);
        SubWorldPlot plot = SubWorldPlot.create(world, sizeChunksX, sizeChunksZ);
        entity.attachPlot(plot);
        entity.setRotation(0f, 0f, 0f, 1f);
        entity.setRotationPoint(sx / 2.0f + 1.0f, sy / 2.0f + 1.0f, sz / 2.0f + 1.0f);
        entity.setPlotSize(sx, sy, sz);

        var toRemove = new ArrayList<BlockPos>();
        for (Long2ObjectMap.Entry<BlockInfo> entry : gun.structurePattern.cache.long2ObjectEntrySet()) {
            BlockPos pos = BlockPos.fromLong(entry.getLongKey());
            BlockPos local = pos.add(offset);
            TileEntity tileEntity = entry.getValue().getTileEntity();
            NBTTagCompound tileTag = tileEntity != null ? tileEntity.serializeNBT() : null;
            entity.transferExtraState(pos, local);
            entity.setPlotBlock(local, entry.getValue().getBlockState(), tileTag);
            toRemove.add(pos);
        }
        plot.seedLight();
        world.spawnEntity(entity);
        for (BlockPos pos : toRemove) {
            world.setBlockToAir(pos);
        }

        gunId = entity.getUniqueID();
        PhysicsConstraint constraint = PhysicsConstraint.toAnchor(getPos())
                .lockTranslations()
                .allowRotations(true, true, false)
                .motor(Axis.ANG_Y, 0.0, 0.0, HOLD_STIFFNESS, HOLD_DAMPING, Double.MAX_VALUE, true)
                .motor(Axis.ANG_X, 0.0, 0.0, HOLD_STIFFNESS, HOLD_DAMPING, Double.MAX_VALUE, true)
                .contactsEnabled(false)
                .anchorSelfBlock(gun.getPos().add(offset));
        entity.addConstraint(constraint);
        ensureAnchor(constraint);
    }

    private void ensureAnchor(PhysicsConstraint constraint) {
        if (anchorTag != 0) {
            return;
        }
        int tag = PhysicsConstraint.nextAnchorTag();
        if (Rapier.addStaticAnchor(getWorld(), tag, PhysicsConstraint.anchorCenter(getPos())) == 0) {
            return;
        }
        anchorTag = tag;
        constraint.attachAnchor(getWorld(), tag);
    }

    private void releaseGun() {
        if (gunId != null) {
            UnformFlow.unform(PhysicsConstraint.resolveEndpoint(getWorld(), gunId));
        }
        removeAnchor();
        gunId = null;
        gunMisses = 0;
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (getWorld() == null || getWorld().isRemote) {
            return;
        }
        removeAnchor();
    }

    private void removeAnchor() {
        if (anchorTag == 0) {
            return;
        }
        Rapier.removeStaticAnchor(getWorld(), anchorTag);
        anchorTag = 0;
    }

    @Nullable private static PhysicsConstraint staticConstraint(PhysicsWorldEntity gun) {
        return gun.getConstraints().stream()
                .filter(PhysicsConstraint::isStatic)
                .findFirst()
                .orElse(null);
    }

    @Nullable private TestingBarrelMTE findGun() {
        TileEntity tileEntity = getWorld().getTileEntity(getPos().up(GUN_OFFSET_Y));
        return tileEntity instanceof MetaTileEntityHolder holder &&
                holder.getMetaTileEntity() instanceof TestingBarrelMTE gun ? gun : null;
    }

    private void aim(Axis axis, float command) {
        if (getWorld().isRemote) return;
        PhysicsWorldEntity gun = resolveGun();
        if (gun == null) return;
        for (PhysicsConstraint constraint : gun.getConstraints()) {
            if (Math.abs(command) <= AIM_DEAD_ZONE) {
                gun.setJointMotor(constraint, axis, 0.0, 0.0, HOLD_STIFFNESS, HOLD_DAMPING, Double.MAX_VALUE, true);
            } else {
                gun.setJointMotor(constraint, axis, 0.0, Math.signum(command) * AIM_ANGULAR_VELOCITY, 0.0,
                        AIM_DRIVE_DAMPING, AIM_TORQUE * Math.abs(command), true);
            }
        }
    }

    @Nullable private PhysicsWorldEntity resolveGun() {
        if (gunId == null) return null;
        PhysicsWorldEntity gun = PhysicsConstraint.resolveEndpoint(getWorld(), gunId);
        return gun == null || gun.isDead ? null : gun;
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        if (gunId != null) {
            data.setLong("GunMost", gunId.getMostSignificantBits());
            data.setLong("GunLeast", gunId.getLeastSignificantBits());
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey("GunMost") && data.hasKey("GunLeast")) {
            gunId = new UUID(data.getLong("GunMost"), data.getLong("GunLeast"));
        }
    }
}
