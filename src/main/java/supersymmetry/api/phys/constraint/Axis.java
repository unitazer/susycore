package supersymmetry.api.phys.constraint;

import java.util.EnumSet;

public enum Axis {

    LIN_X,
    LIN_Y,
    LIN_Z,
    ANG_X,
    ANG_Y,
    ANG_Z;

    public static final EnumSet<Axis> LINEAR = EnumSet.of(LIN_X, LIN_Y, LIN_Z);
    public static final EnumSet<Axis> ROTATIONAL = EnumSet.of(ANG_X, ANG_Y, ANG_Z);

    public int index() {
        return ordinal();
    }

    public int mask() {
        return 1 << ordinal();
    }
}
