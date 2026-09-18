package supersymmetry.mixins.minecraft;

import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;

import supersymmetry.api.SusyLog;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.collision.BodyEntityCollision;

@Mixin(NetHandlerPlayServer.class)
public abstract class NetHandlerPlayServerMixin {

    @Shadow
    public EntityPlayerMP player;

    @Shadow
    private double firstGoodX;

    @Shadow
    private double firstGoodY;

    @Shadow
    private double firstGoodZ;

    @Unique private double susyPacketX;

    @Unique private double susyPacketY;

    @Unique private double susyPacketZ;

    @Unique private BlockPos susyUsePos;

    @Unique private BlockPos susyDigPos;

    @Unique private static final double DIG_REJECT_ZERO_LIMIT = -1.0;

    @Redirect(method = "processPlayer",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/network/play/client/CPacketPlayer;getX(D)D"))
    public double capturePacketX(CPacketPlayer packet, double fallback) {
        susyPacketX = packet.getX(fallback);
        return susyPacketX;
    }

    @Redirect(method = "processPlayer",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/network/play/client/CPacketPlayer;getY(D)D"))
    public double capturePacketY(CPacketPlayer packet, double fallback) {
        susyPacketY = packet.getY(fallback);
        return susyPacketY;
    }

    @Redirect(method = "processPlayer",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/network/play/client/CPacketPlayer;getZ(D)D"))
    public double capturePacketZ(CPacketPlayer packet, double fallback) {
        susyPacketZ = packet.getZ(fallback);
        return susyPacketZ;
    }

    @Redirect(method = "processPlayer",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/entity/player/EntityPlayerMP;isInvulnerableDimensionChange()Z"),
              slice = @Slice(from = @At(value = "INVOKE",
                                        target = "Lnet/minecraft/network/play/client/CPacketPlayer;getZ(D)D"),
                             to = @At(value = "INVOKE",
                                      target = "Lnet/minecraft/world/World;getCollisionBoxes(Lnet/minecraft/entity/Entity;Lnet/minecraft/util/math/AxisAlignedBB;)Ljava/util/List;")))
    public boolean suppressMovedTooQuickly(EntityPlayerMP player) {
        return player.isInvulnerableDimensionChange() ||
                BodyEntityCollision.shouldSuppressMovedTooQuickly(this.player, susyPacketX, susyPacketY, susyPacketZ,
                        firstGoodX, firstGoodY, firstGoodZ);
    }

    @Redirect(method = "processPlayer",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/server/management/PlayerInteractionManager;isCreative()Z"))
    public boolean suppressMovedWrongly(PlayerInteractionManager interactionManager) {
        return interactionManager.isCreative() ||
                BodyEntityCollision.shouldSuppressMovedWrongly(player, susyPacketX, susyPacketY, susyPacketZ);
    }

    @Redirect(method = "processTryUseItemOnBlock",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/network/play/client/CPacketPlayerTryUseItemOnBlock;getPos()Lnet/minecraft/util/math/BlockPos;"))
    public BlockPos captureUsePos(CPacketPlayerTryUseItemOnBlock packet) {
        susyUsePos = packet.getPos();
        return susyUsePos;
    }

    @Redirect(method = "processTryUseItemOnBlock",
              at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayerMP;getDistanceSq(DDD)D"))
    public double remapUseReachDistance(EntityPlayerMP player, double x, double y, double z) {
        PhysicsWorldEntity body = susyUsePos == null ? null : PhysicsWorldEntity.forBlockPos(player.world, susyUsePos);
        if (body == null) {
            return player.getDistanceSq(x, y, z);
        }
        Vec3d heapTarget = new Vec3d(x, y, z);
        Vec3d plotLocalTarget = body.plotLocalFromHeap(heapTarget);
        Vec3d plotLocalFeet = body.currentPose().plotLocalFromWorld(player.getPositionVector());
        double sqDist = plotLocalFeet.squareDistanceTo(plotLocalTarget);
        if (sqDist >= 64.0) {
            SusyLog.logger.debug("use reach reject: feet={} target={} sqDist={}", plotLocalFeet, plotLocalTarget,
                    sqDist);
        }
        return sqDist;
    }

    @Redirect(method = "processPlayerDigging",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/network/play/client/CPacketPlayerDigging;getPosition()Lnet/minecraft/util/math/BlockPos;"))
    public BlockPos captureDigPos(CPacketPlayerDigging packet) {
        susyDigPos = packet.getPosition();
        return susyDigPos;
    }

    @Redirect(method = "processPlayerDigging",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/entity/ai/attributes/IAttributeInstance;getAttributeValue()D"))
    public double remapDiggingReach(IAttributeInstance reach) {
        BlockPos heapPos = susyDigPos;
        if (heapPos == null) {
            return reach.getAttributeValue();
        }
        PhysicsWorldEntity body = PhysicsWorldEntity.forBlockPos(player.world, heapPos);
        if (body == null) {
            return reach.getAttributeValue();
        }
        Vec3d heapTarget = new Vec3d(heapPos.getX() + 0.5, heapPos.getY() + 0.5, heapPos.getZ() + 0.5);
        Vec3d plotLocalTarget = body.plotLocalFromHeap(heapTarget);
        Vec3d plotLocalEyes = body.currentPose().plotLocalFromWorld(player.getPositionEyes(1.0F));
        double sqDist = plotLocalEyes.squareDistanceTo(plotLocalTarget);
        double sqLimit = square(reach.getAttributeValue() + 1.0);
        if (sqDist > sqLimit) {
            SusyLog.logger.debug("dig reach reject: eyes={} target={} sqDist={} limit={}", plotLocalEyes,
                    plotLocalTarget, sqDist, sqLimit);
            return DIG_REJECT_ZERO_LIMIT;
        }
        return vanillaCrossFramePassThrough(heapPos);
    }

    @Unique private double vanillaCrossFramePassThrough(BlockPos heapPos) {
        double dx = player.posX - (heapPos.getX() + 0.5);
        double dy = player.posY - (heapPos.getY() + 0.5) + 1.5;
        double dz = player.posZ - (heapPos.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Unique private static double square(double value) {
        return value * value;
    }
}
