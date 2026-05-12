package supersymmetry.api.phys;

import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

public class DebugSphereEntity extends AbstractPhysicsEntity {

    public DebugSphereEntity(World w) {
        super(w, new Sphere(3));
    }

    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (!this.world.isRemote && this.getColliderId().isPresent()) {
            Entity attacker = source.getTrueSource();
            if (attacker != null) {
                double dx = this.posX - attacker.posX;
                double dz = this.posZ - attacker.posZ;
                double dy = this.posY - attacker.posY;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 0.01) {
                    Rapier.add_force_debug(this, dx / dist, dy / dist, dz / dist);
                }
            }
        }
        return super.attackEntityFrom(source, amount);
    }
}
