package supersymmetry.mixins.minecraft;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.util.BlockRenderLayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import cam72cam.mod.entity.ModdedEntity;
import supersymmetry.client.renderer.handler.IAlwaysRender;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;

@Mixin(net.minecraft.client.renderer.RenderGlobal.class)
public class RenderGlobalMixin {

    @Shadow
    private WorldClient world;

    @Unique
    private float susy$partialTicks;

    @WrapOperation(method = "renderEntities",
                   at = @At(value = "INVOKE",
                            target = "Lnet/minecraft/client/renderer/entity/RenderManager;shouldRender(Lnet/minecraft/entity/Entity;Lnet/minecraft/client/renderer/culling/ICamera;DDD)Z"))
    public boolean checkForAlwaysRender(RenderManager renderManager, Entity entityIn, ICamera camera, double camX,
                                        double camY, double camZ,
                                        Operation<Boolean> shouldRender) {
        if (entityIn instanceof IAlwaysRender) {
            return true;
        }
        if (entityIn instanceof ModdedEntity entity) {
            if (entity.getSelf() instanceof IAlwaysRender) {
                return true;
            }
        }
        return shouldRender.call(renderManager, entityIn, camera, camX, camY, camZ);
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;DILnet/minecraft/entity/Entity;)I",
            at = @At("HEAD"))
    public void susy$capturePartialTicks(BlockRenderLayer blockLayerIn, double partialTicks, int pass,
                                         Entity entityIn, CallbackInfoReturnable<Integer> cir) {
        this.susy$partialTicks = (float) partialTicks;
    }

    @Inject(method = "renderBlockLayer(Lnet/minecraft/util/BlockRenderLayer;)V",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/ChunkRenderContainer;renderChunkLayer(Lnet/minecraft/util/BlockRenderLayer;)V",
                     shift = At.Shift.AFTER))
    public void susy$renderSubWorldLayer(BlockRenderLayer blockLayerIn, CallbackInfo ci) {
        SubWorldChunkRenderer.renderBlockLayer(blockLayerIn, this.susy$partialTicks);
    }

    @Inject(method = "markBlockRangeForRenderUpdate", at = @At("HEAD"))
    public void susy$markSubWorldSectionsForUpdate(int x1, int y1, int z1, int x2, int y2, int z2,
                                                   CallbackInfo ci) {
        SubWorldChunkRenderer.markRangeDirty(this.world, x1, y1, z1, x2, y2, z2);
    }
}
