package supersymmetry.common.metatileentities.multi.electric;

import java.util.ArrayList;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
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
import gregtech.api.metatileentity.multiblock.MultiblockControllerBase;
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
import supersymmetry.api.phys.ChunkSyncManager;
import supersymmetry.api.phys.PhysicsBodyFormation;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.Rapier;
import supersymmetry.api.phys.UnformFlow;
import supersymmetry.api.phys.constraint.Axis;
import supersymmetry.api.phys.constraint.JointWire;
import supersymmetry.api.phys.constraint.PhysicsConstraint;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.common.EventHandlers;
import supersymmetry.common.metatileentities.SuSyMetaTileEntities;

public class TurretFoundationMTE extends MultiblockWithDisplayBase {

    private static final double HOLD_STIFFNESS = 500_000.0;
    private static final double HOLD_DAMPING = 100_000.0;
    private static final double AIM_STIFFNESS = 0.0;
    private static final double AIM_DAMPING = 100_000.0;
    private static final double AIM_ANGULAR_VELOCITY = 0.5;
    private static final double MAX_TORQUE = 5_000_000_000.0;
    private static final float AIM_DEAD_ZONE = 0.02f;
    private static final int GUN_OFFSET_Y = 12;
    private static final int GUN_GRACE_TICKS = 10;

    @Nullable private UUID gunId;
    private int anchorTag;
    private int carriageTag;
    private long yawJoint;
    private long pitchJoint;
    private int gunMisses;
    private BlockPos gunMountBlock = BlockPos.ORIGIN;
    private double yawTarget;
    private double pitchTarget;
    private float yawCommand;
    private float pitchCommand;
    private long lastRigLogTick;
    private long lastPitchDeferLogTick;

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
                SusyLog.logger.warn("turret releasing gun: gunId={} gun={} dead={} formed={} misses={}", gunId,
                        gun == null ? "null" : gun.getEntityId(), gun != null && gun.isDead, isStructureFormed(),
                        gunMisses);
                releaseGun();
            }
            return;
        }
        gunMisses = 0;
        ensureRig(gun);
        tickAim(gun);
        logRigState(gun);
    }

    private void tickAim(PhysicsWorldEntity gun) {
        if (yawJoint == 0 || getWorld() == null) return;
        Axis pitchAng = pitchAxisFor(gun);
        if (Math.abs(yawCommand) > AIM_DEAD_ZONE) {
            yawTarget += yawCommand * AIM_ANGULAR_VELOCITY * 0.05;
            Rapier.setJointMotor(getWorld(), yawJoint, Axis.ANG_Y.index(), yawTarget,
                    yawCommand * AIM_ANGULAR_VELOCITY, AIM_STIFFNESS, AIM_DAMPING, MAX_TORQUE, false);
        } else {
            Rapier.setJointMotor(getWorld(), yawJoint, Axis.ANG_Y.index(), yawTarget, 0.0, HOLD_STIFFNESS,
                    HOLD_DAMPING, MAX_TORQUE, false);
        }
        if (Math.abs(pitchCommand) > AIM_DEAD_ZONE) {
            pitchTarget += -pitchCommand * AIM_ANGULAR_VELOCITY * 0.05;
            Rapier.setJointMotor(getWorld(), yawJoint, pitchAng.index(), pitchTarget,
                    -pitchCommand * AIM_ANGULAR_VELOCITY, AIM_STIFFNESS, AIM_DAMPING, MAX_TORQUE, false);
        } else {
            Rapier.setJointMotor(getWorld(), yawJoint, pitchAng.index(), pitchTarget, 0.0, HOLD_STIFFNESS,
                    HOLD_DAMPING, MAX_TORQUE, false);
        }
    }

    private void logRigState(PhysicsWorldEntity gun) {
        World world = getWorld();
        if (world == null || gun == null) return;
        long now = world.getTotalWorldTime();
        if (now - lastRigLogTick < 100) return;
        if (yawJoint != 0) return;
        lastRigLogTick = now;
        SusyLog.logger.warn(
                "turret rig incomplete: gunId={} entityId={} hasBody={} hasColliders={} anchor={} joint={} yawTarget={} pitchTarget={} mount={} size={},{},{}",
                gunId, gun.getEntityId(), gun.hasPhysicsBody(), gun.hasColliders(), anchorTag, yawJoint, yawTarget,
                pitchTarget, gunMountBlock,
                gun.getPlotSize() != null ? gun.getPlotSize()[0] : -1,
                gun.getPlotSize() != null ? gun.getPlotSize()[1] : -1,
                gun.getPlotSize() != null ? gun.getPlotSize()[2] : -1);
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
    public void onUnload() {
        super.onUnload();
        if (getWorld() != null && !getWorld().isRemote) {
            removeRig();
        }
    }

    @Override
    protected void updateFormedValid() {}

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
        var builder = ModularUI.builder(GuiTextures.BACKGROUND, 198, 208);
        builder.widget(new ClickButtonWidget(7, 10, 88, 18, "form turret", clickData -> formTurretEntity()));
        builder.widget(new ClickButtonWidget(98, 10, 93, 18, "spawn barrel ctl", clickData -> spawnGunController()));
        builder.widget(
                new SliderWidget("pitch", 10, 40, 178, 20, -1.0f, 1.0f, 0.0f, this::aimPitch));
        builder.widget(
                new SliderWidget("yaw", 10, 70, 178, 20, -1.0f, 1.0f, 0.0f, this::aimYaw));
        return builder.build(getHolder(), entityPlayer);
    }

    private void spawnGunController() {
        var world = getWorld();
        if (world == null || world.isRemote) return;
        var pos = gunPos();
        if (PhysicsWorldEntity.forBlockPos(world, pos) != null) return;
        var te = world.getTileEntity(pos);
        if (te instanceof MetaTileEntityHolder h && h.getMetaTileEntity() instanceof TestingBarrelMTE) return;
        if (!world.isAirBlock(pos) && !world.getBlockState(pos).getBlock().isReplaceable(world, pos)) {
            SusyLog.logger.warn("cannot spawn barrel controller at {}: occupied", pos);
            return;
        }
        world.setBlockState(pos, MetaBlocks.MACHINE.getDefaultState(), 3);
        var te2 = world.getTileEntity(pos);
        if (!(te2 instanceof MetaTileEntityHolder holder)) return;
        var actual = holder.setMetaTileEntity(SuSyMetaTileEntities.TEST_BARREL);
        actual.setFrontFacing(getFrontFacing());
        if (actual instanceof MultiblockControllerBase mb) mb.reinitializeStructurePattern();
        holder.markDirty();
    }

    private void formTurretEntity() {
        World world = getWorld();
        SusyLog.logger.info("formTurretEntity: pos={} remote={} formed={} gunId={}", getPos(),
                world != null && world.isRemote, isStructureFormed(), gunId);
        if (world == null || world.isRemote || !isStructureFormed() || structurePattern == null) return;
        if (gunId != null) {
            PhysicsWorldEntity existing = PhysicsConstraint.resolveEndpoint(world, gunId);
            if (existing != null && !existing.isDead) {
                SusyLog.logger.info("formTurretEntity: gun already formed {}", gunId);
                return;
            }
            releaseGun();
        }
        yawTarget = 0.0;
        pitchTarget = 0.0;
        TestingBarrelMTE gun = findGun();
        if (gun == null || !gun.isStructureFormed() || gun.structurePattern == null) {
            SusyLog.logger.warn("no formed gun controller at {}", gunPos());
            return;
        }
        if (PhysicsWorldEntity.forBlockPos(world, gun.getPos()) != null) {
            SusyLog.logger.warn("gun controller {} already sits inside a physics body", gun.getPos());
            return;
        }
        SusyLog.logger.info("formTurretEntity: forming turret with gun controller at {}", gun.getPos());

        AxisAlignedBB box = TestingMTE.structureBounds(gun.structurePattern, gun.getPos());
        BlockPos offset = new BlockPos(1 - (int) box.minX, 1 - (int) box.minY, 1 - (int) box.minZ);

        float sx = (float) (box.maxX - box.minX);
        float sy = (float) (box.maxY - box.minY);
        float sz = (float) (box.maxZ - box.minZ);
        if (sy > PhysicsBodyFormation.MAX_CHILD_SIZE_Y) {
            SusyLog.logger.error("gun height {} exceeds the {} block cap, not forming", sy,
                    PhysicsBodyFormation.MAX_CHILD_SIZE_Y);
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
        entity.beginBulkEdit();
        ChunkSyncManager csm = world instanceof net.minecraft.world.WorldServer ?
                EventHandlers.chunkSyncManagers.get(world) : null;
        if (csm != null) csm.beginBulk();
        try {
            for (Long2ObjectMap.Entry<BlockInfo> entry : gun.structurePattern.cache.long2ObjectEntrySet()) {
                BlockPos pos = BlockPos.fromLong(entry.getLongKey());
                BlockPos local = pos.add(offset);
                TileEntity tileEntity = entry.getValue().getTileEntity();
                NBTTagCompound tileTag = tileEntity != null ? tileEntity.serializeNBT() : null;
                entity.transferExtraState(pos, local);
                plot.setBlockState(plot.toGlobal(local), entry.getValue().getBlockState(), tileTag);
                toRemove.add(pos);
            }
            entity.finishBulkEdit();
            entity.refreshPlotAABB();
            plot.seedLight();
            world.spawnEntity(entity);
            for (BlockPos pos : toRemove) {
                world.setBlockToAir(pos);
            }
        } finally {
            if (csm != null) csm.endBulk();
        }

        gunId = entity.getUniqueID();
        gunMountBlock = gun.getPos().add(offset);
        SusyLog.logger.info("formTurretEntity: spawned gun entity {} mount={} pivotPos={}", gunId, gunMountBlock,
                entity.getPositionVector());
        createRig(entity);
    }

    private void ensureRig(PhysicsWorldEntity gun) {
        if (yawJoint != 0 && anchorTag != 0) {
            return;
        }
        createRig(gun);
    }

    private void createRig(PhysicsWorldEntity gun) {
        World world = getWorld();
        if (world == null) return;
        if (!gun.hasPhysicsBody() || !gun.hasColliders()) {
            long now = world.getTotalWorldTime();
            if (now - lastPitchDeferLogTick >= 100) {
                lastPitchDeferLogTick = now;
                SusyLog.logger.warn("turret deferring joint: gun={} hasBody={} hasColliders={}", gun.getEntityId(),
                        gun.hasPhysicsBody(), gun.hasColliders());
            }
            return;
        }
        Axis pitchAng = pitchAxisFor(gun);
        Axis rollAng = rollAxisFor(pitchAng);
        Vec3d mount = PhysicsConstraint.anchorCenter(gunMountBlock).subtract(gun.getRotationPoint());
        Vec3d pivot = BlockPos.ORIGIN.equals(gunMountBlock) ?
                PhysicsConstraint.anchorCenter(getPos()) :
                gun.worldPointFromLocal(PhysicsConstraint.anchorCenter(gunMountBlock));
        if (anchorTag == 0) {
            int tag = PhysicsConstraint.nextAnchorTag();
            if (Rapier.addStaticAnchor(world, tag, pivot) != 0) {
                anchorTag = tag;
                SusyLog.logger.info("created turret anchor {} at pivot {}", anchorTag, pivot);
            } else {
                SusyLog.logger.warn("failed to create turret anchor tag={} pivot={}", tag, pivot);
                return;
            }
        }
        if (yawJoint == 0) {
            int locked = Axis.maskOf(Axis.LIN_X, Axis.LIN_Y, Axis.LIN_Z, rollAng);
            var wire = new JointWire(locked, 0, false);
            wire.frameA(Vec3d.ZERO);
            wire.frameB(mount);
            wire.motor(Axis.ANG_Y, yawTarget, 0.0, HOLD_STIFFNESS, HOLD_DAMPING, MAX_TORQUE, false);
            wire.motor(pitchAng, pitchTarget, 0.0, HOLD_STIFFNESS, HOLD_DAMPING, MAX_TORQUE, false);
            yawJoint = Rapier.addJoint(world, anchorTag, gun.getEntityId(), wire.flags(), wire.data());
            if (yawJoint == 0) {
                SusyLog.logger.warn(
                        "failed to create turret joint (anchor={} gun={} pitch={} roll={} size={},{},{})", anchorTag,
                        gun.getEntityId(), pitchAng, rollAng,
                        gun.getPlotSize() != null ? gun.getPlotSize()[0] : -1,
                        gun.getPlotSize() != null ? gun.getPlotSize()[1] : -1,
                        gun.getPlotSize() != null ? gun.getPlotSize()[2] : -1);
            } else {
                double mass = Rapier.getBodyMass(world, gun.getEntityId());
                SusyLog.logger.info(
                        "created turret joint {} (anchor={} gun={} yaw={} pitch={} roll={} size={},{},{} mass={})",
                        yawJoint, anchorTag, gun.getEntityId(), Axis.ANG_Y, pitchAng, rollAng,
                        gun.getPlotSize() != null ? gun.getPlotSize()[0] : -1,
                        gun.getPlotSize() != null ? gun.getPlotSize()[1] : -1,
                        gun.getPlotSize() != null ? gun.getPlotSize()[2] : -1, mass);
            }
        }
    }

    private static Axis longitudinalAxis(PhysicsWorldEntity gun) {
        float[] s = gun.getPlotSize();
        if (s == null || s.length < 3) return Axis.ANG_Z;
        float sx = s[0], sy = s[1], sz = s[2];
        if (sx >= sy && sx >= sz) return Axis.ANG_X;
        if (sy >= sx && sy >= sz) return Axis.ANG_Y;
        return Axis.ANG_Z;
    }

    private static Axis pitchAxisFor(PhysicsWorldEntity gun) {
        Axis roll = longitudinalAxis(gun);
        if (roll == Axis.ANG_Z) return Axis.ANG_X;
        if (roll == Axis.ANG_X) return Axis.ANG_Z;
        return Axis.ANG_X;
    }

    private static Axis rollAxisFor(Axis pitchAng) {
        for (Axis a : Axis.ROTATIONAL) {
            if (a != Axis.ANG_Y && a != pitchAng) return a;
        }
        return Axis.ANG_Z;
    }

    private void removeJoints() {
        World world = getWorld();
        if (world != null) {
            if (yawJoint != 0) Rapier.removeJoint(world, yawJoint);
            if (pitchJoint != 0) Rapier.removeJoint(world, pitchJoint);
        }
        yawJoint = 0;
        pitchJoint = 0;
    }

    private void removeRig() {
        removeJoints();
        World world = getWorld();
        if (world != null) {
            if (carriageTag != 0) Rapier.removeCarriageBody(world, carriageTag);
            if (anchorTag != 0) Rapier.removeStaticAnchor(world, anchorTag);
        }
        carriageTag = 0;
        anchorTag = 0;
        yawTarget = 0.0;
        pitchTarget = 0.0;
        yawCommand = 0.0f;
        pitchCommand = 0.0f;
    }

    private void releaseGun() {
        if (gunId != null) {
            UnformFlow.unform(PhysicsConstraint.resolveEndpoint(getWorld(), gunId));
        }
        removeRig();
        gunId = null;
        gunMountBlock = BlockPos.ORIGIN;
        gunMisses = 0;
    }

    private BlockPos gunPos() {
        return getPos().up(GUN_OFFSET_Y);
    }

    @Nullable private TestingBarrelMTE findGun() {
        var tileEntity = getWorld().getTileEntity(gunPos());
        return tileEntity instanceof MetaTileEntityHolder holder &&
                holder.getMetaTileEntity() instanceof TestingBarrelMTE gun ? gun : null;
    }

    private void aimYaw(float command) {
        if (getWorld() == null || getWorld().isRemote) return;
        if (Math.abs(command) > AIM_DEAD_ZONE || yawJoint == 0) {
            PhysicsWorldEntity gun = resolveGun();
            var pose = gun != null ? Rapier.getBodyPose(getWorld(), gun.getEntityId()) : null;
            SusyLog.logger.info(
                    "turret aimYaw cmd={} gunId={} joint={} anchor={} posePos={} poseRot={}", command, gunId,
                    yawJoint, anchorTag, pose != null ? pose.position : "null",
                    pose != null ? pose.rotation : "null");
        }
        if (yawJoint == 0) {
            SusyLog.logger.warn("turret yaw command ignored: joint=0 anchor={} gunId={}", anchorTag, gunId);
            return;
        }
        if (Math.abs(command) > AIM_DEAD_ZONE) {
            yawTarget += command * 0.5;
        }
        aimJoint(yawJoint, Axis.ANG_Y, yawTarget, command, command);
    }

    private void aimPitch(float command) {
        if (getWorld() == null || getWorld().isRemote) return;
        PhysicsWorldEntity gun = resolveGun();
        if (Math.abs(command) > AIM_DEAD_ZONE || gun == null || yawJoint == 0) {
            var pose = gun != null ? Rapier.getBodyPose(getWorld(), gun.getEntityId()) : null;
            SusyLog.logger.info(
                    "turret aimPitch cmd={} gunId={} resolved={} joint={} hasBody={} hasColliders={} posePos={} poseRot={}",
                    command, gunId, gun != null, yawJoint, gun != null && gun.hasPhysicsBody(),
                    gun != null && gun.hasColliders(), pose != null ? pose.position : "null",
                    pose != null ? pose.rotation : "null");
        }
        if (gun == null) {
            SusyLog.logger.warn("turret pitch command ignored: gun unresolved gunId={} joint={}", gunId, yawJoint);
            return;
        }
        if (yawJoint == 0) {
            SusyLog.logger.warn(
                    "turret pitch command ignored: joint=0 gun={} hasBody={} hasColliders={} anchor={}",
                    gun.getEntityId(), gun.hasPhysicsBody(), gun.hasColliders(), anchorTag);
            return;
        }
        Axis pitchAng = pitchAxisFor(gun);
        if (Math.abs(command) > AIM_DEAD_ZONE) {
            pitchTarget += -command * 0.5;
        }
        aimJoint(yawJoint, pitchAng, pitchTarget, command, -command);
    }

    private void aimJoint(long joint, Axis axis, double target, float command, double dir) {
        if (getWorld() == null || getWorld().isRemote || joint == 0) return;
        boolean ok;
        if (Math.abs(command) <= AIM_DEAD_ZONE) {
            ok = Rapier.setJointMotor(getWorld(), joint, axis.index(), target, 0.0, HOLD_STIFFNESS, HOLD_DAMPING,
                    Double.MAX_VALUE, false);
        } else {
            ok = Rapier.setJointMotor(getWorld(), joint, axis.index(), target, dir * AIM_ANGULAR_VELOCITY,
                    AIM_STIFFNESS, AIM_DAMPING, Double.MAX_VALUE, false);
        }
        if (!ok) {
            SusyLog.logger.warn("turret setJointMotor failed: joint={} axis={} target={} cmd={} dir={}", joint, axis,
                    target, command, dir);
        } else if (SusyLog.logger.isDebugEnabled()) {
            SusyLog.logger.debug("turret setJointMotor ok: joint={} axis={} target={} cmd={} vel={}", joint, axis,
                    target, command, dir * AIM_ANGULAR_VELOCITY);
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
        if (gunMountBlock != null && !gunMountBlock.equals(BlockPos.ORIGIN)) {
            data.setInteger("MountX", gunMountBlock.getX());
            data.setInteger("MountY", gunMountBlock.getY());
            data.setInteger("MountZ", gunMountBlock.getZ());
        }
        return data;
    }

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        if (data.hasKey("GunMost") && data.hasKey("GunLeast")) {
            gunId = new UUID(data.getLong("GunMost"), data.getLong("GunLeast"));
        }
        if (data.hasKey("MountX")) {
            gunMountBlock = new BlockPos(data.getInteger("MountX"), data.getInteger("MountY"),
                    data.getInteger("MountZ"));
        }
        anchorTag = 0;
        carriageTag = 0;
        yawJoint = 0;
        pitchJoint = 0;
        gunMisses = 0;
        yawTarget = 0.0;
        pitchTarget = 0.0;
        yawCommand = 0.0f;
        pitchCommand = 0.0f;
        lastRigLogTick = 0;
        lastPitchDeferLogTick = 0;
    }
}
