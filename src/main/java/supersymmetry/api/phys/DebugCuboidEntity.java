package supersymmetry.api.phys;

import net.minecraft.entity.Entity;
import net.minecraft.util.DamageSource;
import net.minecraft.world.World;

public class DebugCuboidEntity extends AbstractPhysicsEntity {

    public DebugCuboidEntity(World w) {
        super(w, new Cuboid(25, 5, 5));
    }

    public DebugCuboidEntity(World w, int x, int y, int z) {
        super(w, new Cuboid(x, y, z));
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
                    Rapier.add_force_debug(this, (dx / dist) * 20.0, (dy / dist) * 20.0, (dz / dist) * 20.0);
                }
            }
        }
        return super.attackEntityFrom(source, amount);
    }
}
