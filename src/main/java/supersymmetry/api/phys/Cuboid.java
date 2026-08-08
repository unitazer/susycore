package supersymmetry.api.phys;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

public record Cuboid(float hx, float hy, float hz) implements IShape {

    public ShapeType type() {
        return ShapeType.CUBOID;
    }

    public float[] data() {
        return new float[] { hx, hy, hz };
    }

    @Override
    public AxisAlignedBB boundingBox(Vec3d position, Quaternion rotation) {
        return rotation.rotateAABB(new AxisAlignedBB(-hx, -hy, -hz, hx, hy, hz)).offset(position.x, position.y,
                position.z);
    }
}
