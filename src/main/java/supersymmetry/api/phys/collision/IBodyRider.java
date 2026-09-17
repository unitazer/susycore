package supersymmetry.api.phys.collision;

import net.minecraft.util.math.Vec3d;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.phys.PhysicsWorldEntity;

public interface IBodyRider {

    @Nullable PhysicsWorldEntity susy$getTrackingBody();

    void susy$setTrackingBody(@Nullable PhysicsWorldEntity body);

    void susy$recordCarry(Vec3d delta);

    Vec3d susy$getLastCarryDelta();
}
