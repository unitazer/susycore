package supersymmetry.api.phys.constraint;

final class Motor {

    double targetPos;
    double targetVel;
    double stiffness;
    double damping;
    double maxForce = Double.MAX_VALUE;
    boolean forceBased;
    boolean spring;

    static Motor of(double targetPos, double targetVel, double stiffness, double damping, double maxForce,
                    boolean forceBased) {
        Motor motor = new Motor();
        motor.targetPos = targetPos;
        motor.targetVel = targetVel;
        motor.stiffness = stiffness;
        motor.damping = damping;
        motor.maxForce = maxForce;
        motor.forceBased = forceBased;
        return motor;
    }
}
