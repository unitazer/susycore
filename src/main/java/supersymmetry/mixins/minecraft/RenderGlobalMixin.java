package supersymmetry.mixins.minecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.client.MinecraftForgeClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import cam72cam.mod.entity.ModdedEntity;
import supersymmetry.client.renderer.handler.IAlwaysRender;
import supersymmetry.client.renderer.subworld.SubWorldChunkRenderer;

@Mixin(net.minecraft.client.renderer.RenderGlobal.class)
public class RenderGlobalMixin {

    @Shadow
    private WorldClient world;
    @Shadow
    private RenderManager renderManager;
    @Shadow
    private Minecraft mc;

    @Unique private float susy$partialTicks;

    /**
     * Vanilla {@code renderEntities} only ever visits an entity if the single 16^3
     * chunk section its position is anchored in is currently in {@code renderInfos}
     * (i.e. that section is being drawn). For entities much larger than a section
     * -- the rocket and the transporter/erector -- the anchor section frequently
     * leaves the frustum while the bulk of the model is still on screen, so the
     * entity is skipped entirely and {@code RenderManager#shouldRender} is never
     * called. Wrapping {@code shouldRender} therefore cannot keep them visible.
     * <p>
     * Instead we add our own pass over every loaded entity and force-render the
     * ones tagged {@link IAlwaysRender}, bypassing the per-section gating. We
     * inject right after {@code setRenderPosition}, so the render manager's
     * interpolated position is already set and we can hand off to
     * {@code renderEntityStatic} exactly as the vanilla loop does. This is also the
     * reason we avoid {@code LocalCapture}: that is what made the previous version
     * fail to apply under OptiFine, which rewrites this method's locals.
     */
    @Inject(method = "renderEntities",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/entity/RenderManager;setRenderPosition(DDD)V",
                     shift = At.Shift.AFTER))
    public void susy$forceRenderAlwaysRender(Entity renderViewEntity, ICamera camera, float partialTicks,
                                             CallbackInfo ci) {
        int pass = MinecraftForgeClient.getRenderPass();

        // Interpolated camera position, matching the d0/d1/d2 the vanilla loop derives.
        double camX = renderViewEntity.lastTickPosX +
                (renderViewEntity.posX - renderViewEntity.lastTickPosX) * partialTicks;
        double camY = renderViewEntity.lastTickPosY +
                (renderViewEntity.posY - renderViewEntity.lastTickPosY) * partialTicks;
        double camZ = renderViewEntity.lastTickPosZ +
                (renderViewEntity.posZ - renderViewEntity.lastTickPosZ) * partialTicks;

        boolean sleeping = this.mc.getRenderViewEntity() instanceof EntityLivingBase &&
                ((EntityLivingBase) this.mc.getRenderViewEntity()).isPlayerSleeping();
        this.mc.entityRenderer.enableLightmap();

        for (Entity entity : this.world.getLoadedEntityList()) {
            boolean alwaysRender = entity instanceof IAlwaysRender ||
                    (entity instanceof ModdedEntity && ((ModdedEntity) entity).getSelf() instanceof IAlwaysRender);
            if (alwaysRender) {
                this.forceRenderEntity(entity, camera, partialTicks, camX, camY, camZ, pass, sleeping);
            }
        }
    }

    private void forceRenderEntity(Entity entity, ICamera camera, float partialTicks, double camX, double camY,
                                   double camZ, int pass, boolean sleeping) {
        if (!entity.shouldRenderInPass(pass))
            return;
        entity.ignoreFrustumCheck = true;

        if ((entity != this.mc.getRenderViewEntity())) {
            this.renderManager.renderEntityStatic(entity, partialTicks, false);
            // IR draws StockModel#postRender (the rocket's clip-plane sweep) only through
            // the
            // multipass codepath, which vanilla runs in a separate, equally section-gated
            // loop. The
            // static render above brings back the base; this brings back the rocket part
            // with it.
            if (this.renderManager.isRenderMultipass(entity)) {
                this.renderManager.renderMultipass(entity, partialTicks);
            }
        }
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
