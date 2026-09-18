package supersymmetry.api.phys;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Rotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.jetbrains.annotations.Nullable;

import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.MetaTileEntityHolder;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import supersymmetry.api.block.BlockExtraDataRegistry;
import supersymmetry.api.subworld.SubWorldRegistry;
import supersymmetry.api.util.Quaternion;

public final class UnformFlow {

    private static final double QUARTER_TURN = Math.PI / 2.0;
    private static final float EXPLOSION_BASE_POWER = 3.0f;
    private static final float EXPLOSION_POWER_PER_BLOCK = 0.05f;
    private static final float EXPLOSION_MAX_POWER = 8.0f;

    private UnformFlow() {}

    public static void unform(@Nullable PhysicsWorldEntity entity) {
        if (entity == null || entity.isDead || entity.getEntityWorld().isRemote || entity.getPlot() == null) {
            return;
        }
        World world = entity.getEntityWorld();
        List<BlockStateInfo> blocks = entity.collectBlocks();
        List<Placement> placements = computePlacements(world, entity, blocks);
        if (placements == null) {
            Vec3d center = entity.getPositionVector();
            world.newExplosion(null, center.x, center.y, center.z, explosionPower(blocks.size()), false, true);
        } else {
            for (Placement placement : placements) {
                place(world, placement);
            }
        }
        entity.setDead();
    }

    private static float explosionPower(int blockCount) {
        return Math.min(EXPLOSION_BASE_POWER + EXPLOSION_POWER_PER_BLOCK * blockCount, EXPLOSION_MAX_POWER);
    }

    @Nullable private static List<Placement> computePlacements(World world, PhysicsWorldEntity entity,
                                                     List<BlockStateInfo> blocks) {
        if (blocks.isEmpty()) {
            return Collections.emptyList();
        }
        RigidPose pose = entity.currentPose();
        Rotation rotation = snappedRotation(entity.getRotation());
        List<Placement> placements = new ArrayList<>(blocks.size());
        LongOpenHashSet targets = new LongOpenHashSet();
        for (BlockStateInfo info : blocks) {
            Vec3d worldPoint = pose.worldFromPlotLocal(new Vec3d(info.pos));
            BlockPos target = new BlockPos(worldPoint.x, worldPoint.y, worldPoint.z);
            if (target.getY() < 0 || target.getY() > 255 || !targets.add(target.toLong()) ||
                    SubWorldRegistry.inPlotArea(world, target.getX() >> 4, target.getZ() >> 4, 0) ||
                    !world.getBlockState(target).getMaterial().isReplaceable()) {
                return null;
            }
            IBlockState state = info.state.withRotation(rotation);
            NBTTagCompound tileData = info.tileData != null ? info.tileData.copy() : null;
            if (tileData != null) {
                tileData.setInteger("x", target.getX());
                tileData.setInteger("y", target.getY());
                tileData.setInteger("z", target.getZ());
            }
            placements.add(new Placement(target, state, rotation, tileData, info.extra));
        }
        return placements;
    }

    private static Rotation snappedRotation(Quaternion rotation) {
        Vec3d forward = rotation.rotatePoint(new Vec3d(0.0, 0.0, 1.0));
        double yaw = Math.atan2(forward.x, forward.z);
        double snapped = Math.round(yaw / QUARTER_TURN) * QUARTER_TURN;
        Quaternion snappedRotation = new Quaternion(Math.cos(snapped / 2.0), 0.0, Math.sin(snapped / 2.0), 0.0);
        Vec3d mappedNorth = snappedRotation.rotatePoint(new Vec3d(EnumFacing.NORTH.getDirectionVec()));
        EnumFacing target = horizontal(mappedNorth);
        for (Rotation candidate : Rotation.values()) {
            if (candidate.rotate(EnumFacing.NORTH) == target) {
                return candidate;
            }
        }
        return Rotation.NONE;
    }

    private static EnumFacing horizontal(Vec3d direction) {
        return Math.abs(direction.x) > Math.abs(direction.z) ?
                (direction.x > 0 ? EnumFacing.EAST : EnumFacing.WEST) :
                (direction.z > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH);
    }

    private static void place(World world, Placement placement) {
        if (!world.setBlockState(placement.pos, placement.state)) {
            return;
        }
        if (placement.tileData != null) {
            TileEntity tileEntity = world.getTileEntity(placement.pos);
            if (tileEntity != null) {
                tileEntity.readFromNBT(placement.tileData);
                rotateHolderFacing(tileEntity, placement.rotation);
            }
        }
        if (placement.extra != null) {
            BlockExtraDataRegistry.restore(world, placement.state.getBlock(), placement.pos, placement.extra);
        }
    }

    private static void rotateHolderFacing(TileEntity tileEntity, Rotation rotation) {
        if (!(tileEntity instanceof MetaTileEntityHolder holder)) {
            return;
        }
        MetaTileEntity metaTileEntity = holder.getMetaTileEntity();
        if (metaTileEntity != null && metaTileEntity.hasFrontFacing()) {
            metaTileEntity.setFrontFacing(rotation.rotate(metaTileEntity.getFrontFacing()));
        }
    }

    private static final class Placement {

        private final BlockPos pos;
        private final IBlockState state;
        private final Rotation rotation;
        @Nullable private final NBTTagCompound tileData;
        @Nullable private final NBTTagCompound extra;

        private Placement(BlockPos pos, IBlockState state, Rotation rotation, @Nullable NBTTagCompound tileData,
                          @Nullable NBTTagCompound extra) {
            this.pos = pos;
            this.state = state;
            this.rotation = rotation;
            this.tileData = tileData;
            this.extra = extra;
        }
    }
}
