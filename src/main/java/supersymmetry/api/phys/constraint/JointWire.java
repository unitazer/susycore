package supersymmetry.api.phys.constraint;

import net.minecraft.util.math.Vec3d;

import supersymmetry.api.util.Quaternion;

public final class JointWire {

    private static final int WIRE_BUF_LEN = 62;
    private static final int MOTOR_WIRE_OFFSET = 12;
    private static final int MOTOR_WIRE_STRIDE = 6;
    private static final int FRAME_A_WIRE_OFFSET = 48;
    private static final int FRAME_B_WIRE_OFFSET = 55;
    private static final int COUPLED_SHIFT = 6;
    private static final int LIMIT_SHIFT = 12;
    private static final int MOTOR_SHIFT = 18;
    private static final int FORCE_BASED_SHIFT = 24;
    private static final int CONTACTS_BIT = 1 << 30;

    private final double[] data = new double[WIRE_BUF_LEN];
    private int flags;

    public JointWire(int lockedMask, int coupledMask, boolean contactsEnabled) {
        flags = lockedMask | (coupledMask << COUPLED_SHIFT) | (contactsEnabled ? CONTACTS_BIT : 0);
        for (Axis axis : Axis.values()) {
            int index = axis.index();
            data[2 * index] = Double.NEGATIVE_INFINITY;
            data[2 * index + 1] = Double.POSITIVE_INFINITY;
            data[MOTOR_WIRE_OFFSET + MOTOR_WIRE_STRIDE * index + 4] = Double.MAX_VALUE;
        }
    }

    public void limit(Axis axis, double min, double max) {
        int index = axis.index();
        flags |= axis.mask() << LIMIT_SHIFT;
        data[2 * index] = min;
        data[2 * index + 1] = max;
    }

    public void motor(Axis axis, double targetPos, double targetVel, double stiffness, double damping, double maxForce,
                      boolean forceBased) {
        flags |= axis.mask() << MOTOR_SHIFT;
        if (forceBased) {
            flags |= axis.mask() << FORCE_BASED_SHIFT;
        }
        int slot = MOTOR_WIRE_OFFSET + MOTOR_WIRE_STRIDE * axis.index();
        data[slot] = targetPos;
        data[slot + 1] = targetVel;
        data[slot + 2] = stiffness;
        data[slot + 3] = damping;
        data[slot + 4] = maxForce;
    }

    public void frameA(Vec3d translation) {
        frameA(translation, Quaternion.IDENTITY);
    }

    public void frameB(Vec3d translation) {
        frameB(translation, Quaternion.IDENTITY);
    }

    public void frameA(Vec3d translation, Quaternion rotation) {
        writeFrame(FRAME_A_WIRE_OFFSET, translation, rotation);
    }

    public void frameB(Vec3d translation, Quaternion rotation) {
        writeFrame(FRAME_B_WIRE_OFFSET, translation, rotation);
    }

    private void writeFrame(int offset, Vec3d translation, Quaternion rotation) {
        data[offset] = translation.x;
        data[offset + 1] = translation.y;
        data[offset + 2] = translation.z;
        data[offset + 3] = rotation.getX();
        data[offset + 4] = rotation.getY();
        data[offset + 5] = rotation.getZ();
        data[offset + 6] = rotation.getW();
    }

    public double[] data() {
        return data;
    }

    public int flags() {
        return flags;
    }
}
