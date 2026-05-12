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
        Vec3d[] corners = {
                new Vec3d(-hx, -hy, -hz), new Vec3d(hx, -hy, -hz),
                new Vec3d(-hx, hy, -hz), new Vec3d(hx, hy, -hz),
                new Vec3d(-hx, -hy, hz), new Vec3d(hx, -hy, hz),
                new Vec3d(-hx, hy, hz), new Vec3d(hx, hy, hz),
        };
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3d c : corners) {
            Vec3d r = rotation.rotatePoint(c);
            minX = Math.min(minX, r.x);
            minY = Math.min(minY, r.y);
            minZ = Math.min(minZ, r.z);
            maxX = Math.max(maxX, r.x);
            maxY = Math.max(maxY, r.y);
            maxZ = Math.max(maxZ, r.z);
        }
        return new AxisAlignedBB(
                position.x + minX,
                position.y + minY,
                position.z + minZ,
                position.x + maxX,
                position.y + maxY,
                position.z + maxZ);
    }
}
