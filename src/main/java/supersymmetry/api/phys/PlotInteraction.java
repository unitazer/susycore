package supersymmetry.api.phys;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.subworld.SubWorldPlot;

public final class PlotInteraction {

    private static final double EXTENDED_REACH_DISTANCE = 6.0;

    @Nullable private static PlotHit lastHit;

    private PlotInteraction() {}

    public static final class PlotHit {

        public final PhysicsWorldEntity body;
        public final RayTraceResult hit;

        private final float partialTicks;

        private PlotHit(PhysicsWorldEntity body, RayTraceResult hit, float partialTicks) {
            this.body = body;
            this.hit = hit;
            this.partialTicks = partialTicks;
        }

        public Vec3d worldFromHeap(Vec3d heapVec) {
            return body.renderPose(partialTicks).worldFromPlotLocal(body.plotLocalFromHeap(heapVec));
        }
    }

    @Nullable public static PlotHit rayTraceBodies(Minecraft mc, float partialTicks,
                                         @Nullable RayTraceResult vanillaHit) {
        Entity viewer = mc.getRenderViewEntity();
        if (viewer == null || mc.world == null) {
            return null;
        }
        Vec3d eyes = viewer.getPositionEyes(partialTicks);
        Vec3d look = viewer.getLook(partialTicks);
        double reach = mc.playerController.extendedReach() ? EXTENDED_REACH_DISTANCE :
                mc.playerController.getBlockReachDistance();
        Vec3d end = eyes.add(look.scale(reach));
        double bestDist = vanillaHit == null ? Double.MAX_VALUE : vanillaHit.hitVec.distanceTo(eyes);
        PlotHit best = null;
        for (PhysicsWorldEntity body : PhysicsWorldEntity.activeBodies()) {
            if (body.world != mc.world || body.getPlot() == null) continue;
            if (!raySegmentIntersects(eyes, end, body.getEntityBoundingBox())) continue;
            RayTraceResult hit = rayTraceBody(body, eyes, look, reach, partialTicks);
            if (hit == null) continue;
            PlotHit candidate = new PlotHit(body, hit, partialTicks);
            double dist = candidate.worldFromHeap(hit.hitVec).distanceTo(eyes);
            if (dist < bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
    }

    public static void setLastHit(@Nullable PlotHit hit) {
        lastHit = hit;
    }

    @Nullable public static PlotHit lastHit() {
        return lastHit;
    }

    private static boolean raySegmentIntersects(Vec3d start, Vec3d end, AxisAlignedBB box) {
        return box.contains(start) || box.calculateIntercept(start, end) != null;
    }

    @Nullable private static RayTraceResult rayTraceBody(PhysicsWorldEntity body, Vec3d eyes, Vec3d look, double reach,
                                               float partialTicks) {
        SubWorldPlot plot = body.getPlot();
        if (plot == null) {
            return null;
        }
        RigidPose pose = body.renderPose(partialTicks);
        Vec3d heapOrigin = pose.plotLocalFromWorld(eyes).add(new Vec3d(plot.getRenderOrigin()));
        Vec3d direction = pose.directionToPlotLocal(look);
        RayTraceResult hit = body.world.rayTraceBlocks(heapOrigin, heapOrigin.add(direction.scale(reach)), false,
                false, true);
        if (hit == null || hit.typeOfHit != RayTraceResult.Type.BLOCK) {
            return null;
        }
        BlockPos heapPos = hit.getBlockPos();
        return plot.inBounds(heapPos.getX() >> 4, heapPos.getZ() >> 4) ? hit : null;
    }
}
