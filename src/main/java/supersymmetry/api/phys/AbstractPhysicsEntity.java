package supersymmetry.api.phys;

import java.util.Optional;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.datasync.DataParameter;
import net.minecraft.network.datasync.EntityDataManager;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

public class AbstractPhysicsEntity extends Entity {

    private static final DataParameter<Quaternion> ROTATION = EntityDataManager.createKey(AbstractPhysicsEntity.class,
            QuaternionDataSerializer.INSTANCE);

    Optional<Long> colliderId = Optional.empty();
    Quaternion rotation = Quaternion.IDENTITY;
    public IShape shape;

    public AbstractPhysicsEntity(World w, IShape shape) {
        super(w);
        this.shape = shape;
        this.noClip = true;
    }

    public Optional<Long> getColliderId() {
        return colliderId;
    }

    public Quaternion getRotation() {
        return rotation;
    }

    public IShape getShape() {
        return shape;
    }

    @Override
    public AxisAlignedBB getEntityBoundingBox() {
        return this.shape.boundingBox(new Vec3d(this.posX, this.posY, this.posZ), rotation);
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return this.getEntityBoundingBox();
    }

    @Override
    public void onCollideWithPlayer(EntityPlayer entityIn) {}

    @Override
    public void onUpdate() {
        super.onUpdate();
        if (this.world.isRemote) {
            this.rotation = this.dataManager.get(ROTATION);
        }
    }

    @Override
    public void onEntityUpdate() {
        super.onEntityUpdate();
        if (!this.world.isRemote && this.world instanceof WorldServer && this.colliderId != null &&
                !this.colliderId.isEmpty()) {
            Rapier.syncEntity(this);
            this.dataManager.set(ROTATION, this.rotation);
        }
    }

    @Override
    public boolean canBePushed() {
        return false;
    }

    @Override
    public boolean canRenderOnFire() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return true;
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!this.getEntityWorld().isRemote) {
            this.colliderId = Rapier.add_entity(this);
            Rapier.setEntityPose(this);
        }
    }

    @Override
    public void setDead() {
        Rapier.remove_entity(this);
        super.setDead();
    }

    @Override
    protected boolean canBeRidden(Entity entityIn) {
        return false;
    }

    @Override
    protected void writeEntityToNBT(NBTTagCompound compound) {
        compound.setDouble("qx", this.rotation.getX());
        compound.setDouble("qy", this.rotation.getY());
        compound.setDouble("qz", this.rotation.getZ());
        compound.setDouble("qw", this.rotation.getW());
    }

    @Override
    protected void readEntityFromNBT(NBTTagCompound compound) {
        this.rotation = new Quaternion(
                compound.getDouble("qw"),
                compound.getDouble("qx"),
                compound.getDouble("qy"),
                compound.getDouble("qz"));
    }

    @Override
    protected void entityInit() {
        this.dataManager.register(ROTATION, Quaternion.IDENTITY);
    }
}
