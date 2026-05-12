package supersymmetry.api.phys;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

public record Sphere(float radius) implements IShape {

    public ShapeType type() {
        return ShapeType.BALL;
    }

    public float[] data() {
        return new float[] { radius };
    }

    @Override
    public AxisAlignedBB boundingBox(Vec3d position, Quaternion rotation) {
        return new AxisAlignedBB(
                position.x - radius, position.y - radius, position.z - radius,
                position.x + radius, position.y + radius, position.z + radius);
    }
}
