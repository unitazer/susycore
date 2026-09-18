package supersymmetry.api.phys;

import java.util.function.UnaryOperator;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

import supersymmetry.api.util.Quaternion;
import supersymmetry.api.util.SuSyUtility;

public final class RigidPose {

    public final Vec3d position;
    public final Quaternion rotation;
    public final Vec3d rotationPoint;
    public final Vec3d velocity;

    public RigidPose(Vec3d position, Quaternion rotation, Vec3d rotationPoint) {
        this(position, rotation, rotationPoint, Vec3d.ZERO);
    }

    public RigidPose(Vec3d position, Quaternion rotation, Vec3d rotationPoint, Vec3d velocity) {
        this.position = position;
        this.rotation = rotation;
        this.rotationPoint = rotationPoint;
        this.velocity = velocity;
    }

    public Vec3d worldFromPlotLocal(Vec3d plotLocal) {
        return position.add(rotation.rotatePoint(plotLocal.subtract(rotationPoint)));
    }

    public Vec3d plotLocalFromWorld(Vec3d worldPoint) {
        return rotation.conjugate().rotatePoint(worldPoint.subtract(position)).add(rotationPoint);
    }

    public Vec3d bodyRelativeFromWorld(Vec3d worldPoint) {
        return rotation.conjugate().rotatePoint(worldPoint.subtract(position));
    }

    public Vec3d worldFromBodyRelative(Vec3d bodyRelative) {
        return position.add(rotation.rotatePoint(bodyRelative));
    }

    public Vec3d bodyRelativeFromPlotLocal(Vec3d plotLocal) {
        return plotLocal.subtract(rotationPoint);
    }

    public Vec3d directionToWorld(Vec3d direction) {
        return rotation.rotatePoint(direction);
    }

    public Vec3d directionToPlotLocal(Vec3d direction) {
        return rotation.conjugate().rotatePoint(direction);
    }

    public AxisAlignedBB worldBoxFromPlotLocal(AxisAlignedBB plotLocalBox) {
        return transformCorners(plotLocalBox, this::worldFromPlotLocal);
    }

    public AxisAlignedBB plotLocalBoxFromWorld(AxisAlignedBB worldBox) {
        return transformCorners(worldBox, this::plotLocalFromWorld);
    }

    public AxisAlignedBB bodyRelativeBoxFromWorld(AxisAlignedBB worldBox) {
        return transformCorners(worldBox, this::bodyRelativeFromWorld);
    }

    public AxisAlignedBB worldBoxFromBodyRelative(AxisAlignedBB bodyRelativeBox) {
        return transformCorners(bodyRelativeBox, this::worldFromBodyRelative);
    }

    public RigidPose interpolated(RigidPose prev, double t) {
        return new RigidPose(SuSyUtility.lerp(prev.position, position, (float) t),
                prev.rotation.slerp(rotation, t), rotationPoint);
    }

    private static AxisAlignedBB transformCorners(AxisAlignedBB box, UnaryOperator<Vec3d> transform) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (Vec3d corner : corners(box)) {
            Vec3d transformed = transform.apply(corner);
            minX = Math.min(minX, transformed.x);
            minY = Math.min(minY, transformed.y);
            minZ = Math.min(minZ, transformed.z);
            maxX = Math.max(maxX, transformed.x);
            maxY = Math.max(maxY, transformed.y);
            maxZ = Math.max(maxZ, transformed.z);
        }
        return new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    private static Vec3d[] corners(AxisAlignedBB box) {
        return new Vec3d[] { new Vec3d(box.minX, box.minY, box.minZ), new Vec3d(box.maxX, box.minY, box.minZ),
                new Vec3d(box.minX, box.maxY, box.minZ), new Vec3d(box.maxX, box.maxY, box.minZ),
                new Vec3d(box.minX, box.minY, box.maxZ), new Vec3d(box.maxX, box.minY, box.maxZ),
                new Vec3d(box.minX, box.maxY, box.maxZ), new Vec3d(box.maxX, box.maxY, box.maxZ) };
    }
}
