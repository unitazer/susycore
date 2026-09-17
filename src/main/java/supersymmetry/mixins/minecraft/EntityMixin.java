package supersymmetry.mixins.minecraft;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.collision.BodyEntityCollision;
import supersymmetry.api.phys.collision.IBodyRider;
import supersymmetry.common.event.GravityHandler;

@Mixin(Entity.class)
public abstract class EntityMixin implements ICommandSender, ICapabilitySerializable<NBTTagCompound>, IBodyRider {

    @Unique private PhysicsWorldEntity susyTrackingBody;
    @Unique private Vec3d susyCarryDelta = Vec3d.ZERO;
    @Unique private int susyCarryTick = -1;
    @Unique private boolean susyResolving;

    @Inject(method = "onUpdate", at = @At("TAIL"))
    public void applyGravity(CallbackInfo callback) {
        GravityHandler.applyGravity((Entity) (Object) this);
    }

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    public void resolveBodyCollision(MoverType moverType, double x, double y, double z, CallbackInfo callback) {
        if (susyResolving) return;
        Entity self = (Entity) (Object) this;
        BodyEntityCollision.MoveResult result = BodyEntityCollision.resolveMotion(self, moverType, new Vec3d(x, y, z));
        if (result == null) return;
        if (result.escape != null) {
            self.setPosition(self.posX + result.escape.x, self.posY + result.escape.y, self.posZ + result.escape.z);
            self.motionX = 0.0;
            self.motionY = 0.0;
            self.motionZ = 0.0;
            BodyEntityCollision.applyMoveResult(self, result);
            callback.cancel();
            return;
        }
        if (sneakingOnBody(self, moverType)) {
            self.onGround = false;
        }
        susyResolving = true;
        try {
            self.move(moverType, result.motion.x, result.motion.y, result.motion.z);
        } finally {
            susyResolving = false;
        }
        BodyEntityCollision.applyMoveResult(self, result);
        callback.cancel();
    }

    private static boolean sneakingOnBody(Entity self, MoverType moverType) {
        return self.onGround && self.isSneaking() && self instanceof EntityPlayer &&
                (moverType == MoverType.SELF || moverType == MoverType.PLAYER);
    }

    @Override
    public PhysicsWorldEntity susy$getTrackingBody() {
        return susyTrackingBody;
    }

    @Override
    public void susy$setTrackingBody(PhysicsWorldEntity body) {
        susyTrackingBody = body;
    }

    @Override
    public void susy$recordCarry(Vec3d delta) {
        susyCarryDelta = delta;
        susyCarryTick = ((Entity) (Object) this).ticksExisted;
    }

    @Override
    public Vec3d susy$getLastCarryDelta() {
        return ((Entity) (Object) this).ticksExisted - susyCarryTick <= 1 ? susyCarryDelta : Vec3d.ZERO;
    }
}
