package supersymmetry.mixins.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import supersymmetry.api.phys.PlotInteraction;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Shadow
    @Final
    private Minecraft mc;

    @Unique private PlotInteraction.PlotHit susyPlotHit;

    @Inject(method = "getMouseOver",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/entity/Entity;getPositionEyes(F)Lnet/minecraft/util/math/Vec3d;"))
    private void susy$tracePlotBlocks(float partialTicks, CallbackInfo ci) {
        susyPlotHit = PlotInteraction.rayTraceBodies(this.mc, partialTicks, this.mc.objectMouseOver);
        PlotInteraction.setLastHit(susyPlotHit);
        if (susyPlotHit != null) {
            this.mc.objectMouseOver = susyPlotHit.hit;
        }
    }

    @Redirect(method = "getMouseOver",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/util/math/Vec3d;distanceTo(Lnet/minecraft/util/math/Vec3d;)D"),
              slice = @Slice(from = @At(value = "INVOKE",
                                        target = "Lnet/minecraft/entity/Entity;rayTrace(DF)Lnet/minecraft/util/math/RayTraceResult;"),
                             to = @At(value = "INVOKE",
                                      target = "Lnet/minecraft/entity/Entity;getLook(F)Lnet/minecraft/util/math/Vec3d;")))
    private double susy$remapPlotHitDistance(Vec3d hitVec, Vec3d eyes) {
        PlotInteraction.PlotHit hit = susyPlotHit;
        if (hit == null) {
            return hitVec.distanceTo(eyes);
        }
        return hit.worldFromHeap(hitVec).distanceTo(eyes);
    }
}
