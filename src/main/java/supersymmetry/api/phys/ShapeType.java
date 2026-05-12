package supersymmetry.api.phys;

// the numbers are directly translated to the rust ShapeType enum, do not change them
public enum ShapeType {

    BALL(0),
    CUBOID(1),
    CAPSULE(2),
    SEGMENT(3),
    TRIANGLE(4),
    VOXELS(5),
    TRI_MESH(6),
    POLYLINE(7),
    HALF_SPACE(8),
    HEIGHT_FIELD(9),
    COMPOUND(10),
    CONVEX_POLYGON(11),
    CONVEX_POLYHEDRON(12),
    CYLINDER(13),
    CONE(14),
    ROUND_CUBOID(15),
    ROUND_TRIANGLE(16),
    ROUND_CYLINDER(17),
    ROUND_CONE(18),
    ROUND_CONVEX_POLYHEDRON(19),
    ROUND_CONVEX_POLYGON(20),
    // probably chunk
    CUSTOM(21);

    private final int value;

    ShapeType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }
}
