package supersymmetry.mixins.gregtech;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import codechicken.lib.raytracer.RayTracer;
import gregtech.api.block.machines.BlockMachine;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.RigidPose;

@Mixin(value = BlockMachine.class, remap = false)
public abstract class BlockMachineMixin {

    /**
     * Swap the receiver and argument of the collision intersection test so that
     * {@code entityBox.intersects(offsetBox)} is called instead of
     * {@code offsetBox.intersects(entityBox)}. Immersive Railroading has an obscure
     * incompatibility with the original call direction.
     */
    @Redirect(method = "addCollisionBoxToList",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/util/math/AxisAlignedBB;intersects(Lnet/minecraft/util/math/AxisAlignedBB;)Z"),
              remap = true)
    private boolean susy$swapIntersectsOrder(AxisAlignedBB offsetBox, AxisAlignedBB entityBox) {
        return entityBox.intersects(offsetBox);
    }

    @Redirect(method = "onBlockActivated",
              remap = true,
              at = @At(value = "INVOKE",
                       target = "Lcodechicken/lib/raytracer/RayTracer;retraceBlock(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/RayTraceResult;",
                       remap = false))
    private static RayTraceResult susy$remapPlotBlockRayTrace(World world, EntityPlayer player, BlockPos pos) {
        PhysicsWorldEntity body = PhysicsWorldEntity.forBlockPos(world, pos);
        if (body == null || body.getPlot() == null) {
            return RayTracer.retraceBlock(world, player, pos);
        }
        RigidPose pose = body.currentPose();
        Vec3d start = susy$heapFromWorld(body, pose, RayTracer.getStartVec(player));
        Vec3d end = susy$heapFromWorld(body, pose, RayTracer.getEndVec(player));
        return world.getBlockState(pos).collisionRayTrace(world, pos, start, end);
    }

    @Unique private static Vec3d susy$heapFromWorld(PhysicsWorldEntity body, RigidPose pose, Vec3d worldVec) {
        return pose.plotLocalFromWorld(worldVec).add(new Vec3d(body.getPlot().getRenderOrigin()));
    }
}
