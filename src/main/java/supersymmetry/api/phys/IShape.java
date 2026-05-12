package supersymmetry.api.phys;

import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.Vec3d;

// information for the rust side to reconstruct shapes
public interface IShape {

    public ShapeType type();

    public float[] data();

    public AxisAlignedBB boundingBox(Vec3d position, Quaternion rotation);

    public default int[] indices() {
        return null;
    } // optional
}
