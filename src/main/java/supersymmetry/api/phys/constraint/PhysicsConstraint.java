package supersymmetry.api.phys.constraint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.SusyLog;
import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.Rapier;

public final class PhysicsConstraint {

    private static final int WIRE_LEN = 62;
    private static final int MOTOR_WIRE_OFFSET = 12;
    private static final int MOTOR_WIRE_STRIDE = 6;
    private static final int FRAME_A_WIRE_OFFSET = 48;
    private static final int FRAME_B_WIRE_OFFSET = 55;
    private static final int COUPLED_SHIFT = 6;
    private static final int LIMIT_SHIFT = 12;
    private static final int MOTOR_SHIFT = 18;
    private static final int FORCE_BASED_SHIFT = 24;
    private static final int CONTACTS_BIT = 1 << 30;
    private static final int RETRY_INTERVAL_TICKS = 20;
    private static final int MISS_WARN_TICKS = 1200;
    private static final double BLOCK_CENTER_OFFSET = 0.5;
    private static final int ANCHOR_TAG_BASE = 1 << 30;

    private static int anchorTagCounter = ANCHOR_TAG_BASE;

    @Nullable private final UUID otherEndpoint;
    private boolean staticEndpoint;
    private int anchorTag;
    private long rustHandle;
    private int epochSeen = -1;
    private final EnumSet<Axis> lockedAxes = EnumSet.noneOf(Axis.class);
    private final EnumSet<Axis> coupledAxes = EnumSet.noneOf(Axis.class);
    private final Map<Axis, AxisConfig> axes = new EnumMap<>(Axis.class);
    private BlockPos anchorSelfBlock = BlockPos.ORIGIN;
    private BlockPos anchorOtherBlock = BlockPos.ORIGIN;
    private boolean contactsEnabled;
    private int retryAfterTick;
    private int consecutiveMisses;
    private int instantiateFailures;
    private boolean dead;

    private PhysicsConstraint(@Nullable UUID otherEndpoint) {
        this.otherEndpoint = otherEndpoint;
    }

    public static int nextAnchorTag() {
        return ++anchorTagCounter;
    }

    public static PhysicsConstraint toAnchor(BlockPos anchorBlock) {
        PhysicsConstraint constraint = new PhysicsConstraint(null);
        constraint.staticEndpoint = true;
        constraint.anchorOtherBlock = anchorBlock;
        return constraint;
    }

    public PhysicsConstraint lockTranslations() {
        lockedAxes.addAll(Axis.LINEAR);
        return this;
    }

    public PhysicsConstraint lockRotations(boolean x, boolean y, boolean z) {
        for (Axis axis : rotationAxes(x, y, z)) {
            lockedAxes.add(axis);
        }
        return this;
    }

    public PhysicsConstraint allowRotations(boolean x, boolean y, boolean z) {
        EnumSet<Axis> allowed = rotationAxes(x, y, z);
        lockedAxes.addAll(Axis.ROTATIONAL);
        lockedAxes.removeAll(allowed);
        return this;
    }

    public PhysicsConstraint couple(Axis first, Axis... rest) {
        coupledAxes.add(first);
        Collections.addAll(coupledAxes, rest);
        return this;
    }

    public PhysicsConstraint limitAxis(Axis axis, double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            throw new IllegalArgumentException("joint limits must be finite: [" + min + ", " + max + "]");
        }
        if (min > max) {
            throw new IllegalArgumentException("inverted joint limits: [" + min + ", " + max + "]");
        }
        if (rejectLocked(axis)) {
            return this;
        }
        AxisConfig config = config(axis);
        config.limitMin = min;
        config.limitMax = max;
        return this;
    }

    public PhysicsConstraint spring(double stiffness, double damping) {
        requireFiniteMotor(0.0, 0.0, stiffness, damping, Double.MAX_VALUE);
        for (Axis axis : Axis.LINEAR) {
            if (rejectLocked(axis)) {
                continue;
            }
            Motor motor = Motor.of(0.0, 0.0, stiffness, damping, Double.MAX_VALUE, true);
            motor.spring = true;
            config(axis).motor = motor;
        }
        return this;
    }

    public PhysicsConstraint motor(Axis axis, double targetPos, double targetVel, double stiffness, double damping,
                                   double maxForce, boolean forceBased) {
        requireFiniteMotor(targetPos, targetVel, stiffness, damping, maxForce);
        if (rejectLocked(axis)) {
            return this;
        }
        config(axis).motor = Motor.of(targetPos, targetVel, stiffness, damping, maxForce, forceBased);
        return this;
    }

    public PhysicsConstraint anchorSelfBlock(BlockPos anchorBlock) {
        anchorSelfBlock = anchorBlock;
        return this;
    }

    public PhysicsConstraint anchorOtherBlock(BlockPos anchorBlock) {
        anchorOtherBlock = anchorBlock;
        return this;
    }

    public PhysicsConstraint contactsEnabled(boolean enabled) {
        contactsEnabled = enabled;
        return this;
    }

    @Nullable public UUID getOther() {
        return otherEndpoint;
    }

    public boolean isStatic() {
        return staticEndpoint;
    }

    public void attachAnchor(World world, int tag) {
        if (!staticEndpoint || tag == 0 || anchorTag == tag) {
            return;
        }
        teardown(world);
        anchorTag = tag;
    }

    public void remapAnchors(int dx, int dz, boolean self) {
        if (self) {
            anchorSelfBlock = anchorSelfBlock.add(dx, 0, dz);
        } else if (!staticEndpoint) {
            anchorOtherBlock = anchorOtherBlock.add(dx, 0, dz);
        }
    }

    public boolean isDead() {
        return dead;
    }

    public boolean setMotor(World world, Axis axis, double targetPos, double targetVel, double stiffness,
                            double damping, double maxForce, boolean forceBased) {
        if (!finiteMotor(targetPos, targetVel, stiffness, damping, maxForce)) {
            return false;
        }
        if (rejectLocked(axis)) {
            return false;
        }
        config(axis).motor = Motor.of(targetPos, targetVel, stiffness, damping, maxForce, forceBased);
        if (rustHandle == 0) {
            return false;
        }
        return Rapier.setJointMotor(world, rustHandle, axis.index(), targetPos, targetVel, stiffness, damping,
                maxForce, forceBased);
    }

    public void teardown(World world) {
        if (rustHandle != 0) {
            Rapier.removeJoint(world, rustHandle);
            rustHandle = 0;
        }
        epochSeen = -1;
    }

    public void update(PhysicsWorldEntity self) {
        if (dead) {
            return;
        }
        PhysicsWorldEntity other = staticEndpoint ? null : resolveEntityEndpoint(self);
        if (dead) {
            return;
        }
        if (!self.hasColliders() || (other == null ? anchorTag == 0 : !other.hasColliders())) {
            teardown(self.getEntityWorld());
            return;
        }
        if (other != null && !other.hasPhysicsBody()) {
            return;
        }
        if (rustHandle != 0 && (other == null || epochSeen == other.getBodyEpoch())) {
            return;
        }
        if (self.ticksExisted < retryAfterTick) {
            return;
        }
        teardown(self.getEntityWorld());
        if (instantiate(self, other)) {
            retryAfterTick = 0;
            instantiateFailures = 0;
        } else {
            retryAfterTick = self.ticksExisted + RETRY_INTERVAL_TICKS;
            if (++instantiateFailures == 1 || instantiateFailures % 10 == 0) {
                SusyLog.logger.warn("PhysicsConstraint: failed to instantiate the constraint toward {}",
                        other != null ? otherEndpoint : anchorOtherBlock);
            }
        }
    }

    @Nullable private PhysicsWorldEntity resolveEntityEndpoint(PhysicsWorldEntity self) {
        if (otherEndpoint.equals(self.getUniqueID())) {
            dead = true;
            SusyLog.logger.warn("PhysicsConstraint: dropping self-referential constraint toward {}", otherEndpoint);
            return null;
        }
        PhysicsWorldEntity other = resolveEndpoint(self.getEntityWorld(), otherEndpoint);
        if (other == null) {
            consecutiveMisses = Math.min(consecutiveMisses + 1, MISS_WARN_TICKS);
            if (consecutiveMisses == MISS_WARN_TICKS) {
                SusyLog.logger.warn("PhysicsConstraint: endpoint {} unresolved for {} ticks, staying pending",
                        otherEndpoint, consecutiveMisses);
            }
            return null;
        }
        consecutiveMisses = 0;
        if (other.isDead) {
            dead = true;
            teardown(self.getEntityWorld());
            return null;
        }
        return other;
    }

    @Nullable public static PhysicsWorldEntity resolveEndpoint(World world, UUID uuid) {
        Entity entity = null;
        if (world instanceof WorldServer) {
            entity = ((WorldServer) world).getEntityFromUuid(uuid);
        } else {
            for (Entity loaded : world.loadedEntityList) {
                if (uuid.equals(loaded.getUniqueID())) {
                    entity = loaded;
                    break;
                }
            }
        }
        return entity instanceof PhysicsWorldEntity ? (PhysicsWorldEntity) entity : null;
    }

    private boolean instantiate(PhysicsWorldEntity self, @Nullable PhysicsWorldEntity other) {
        Vec3d restOffset = formationRestOffset(self, other);
        double[] data = new double[WIRE_LEN];
        EnumSet<Axis> limited = EnumSet.noneOf(Axis.class);
        EnumSet<Axis> motorized = EnumSet.noneOf(Axis.class);
        EnumSet<Axis> forceBased = EnumSet.noneOf(Axis.class);
        for (Axis axis : Axis.values()) {
            data[2 * axis.index()] = Double.NEGATIVE_INFINITY;
            data[2 * axis.index() + 1] = Double.POSITIVE_INFINITY;
            data[MOTOR_WIRE_OFFSET + MOTOR_WIRE_STRIDE * axis.index() + 4] = Double.MAX_VALUE;
        }
        for (Axis axis : Axis.values()) {
            AxisConfig config = axes.get(axis);
            if (config == null || lockedAxes.contains(axis)) {
                continue;
            }
            if (config.limitMin != null) {
                limited.add(axis);
                data[2 * axis.index()] = config.limitMin;
                data[2 * axis.index() + 1] = config.limitMax;
            }
            if (config.motor != null) {
                Motor motor = config.motor;
                motorized.add(axis);
                if (motor.forceBased) {
                    forceBased.add(axis);
                }
                int slot = MOTOR_WIRE_OFFSET + MOTOR_WIRE_STRIDE * axis.index();
                data[slot] = motor.spring ? (Axis.LINEAR.contains(axis) ? component(restOffset, axis) : 0.0)
                        : motor.targetPos;
                data[slot + 1] = motor.targetVel;
                data[slot + 2] = motor.stiffness;
                data[slot + 3] = motor.damping;
                data[slot + 4] = motor.maxForce;
            }
        }
        writeFrame(data, FRAME_A_WIRE_OFFSET, anchorSelfBlock, self);
        if (other == null) {
            Vec3d pivot = self.worldPointFromLocal(anchorCenter(anchorSelfBlock))
                    .subtract(anchorCenter(anchorOtherBlock));
            data[FRAME_B_WIRE_OFFSET] = pivot.x;
            data[FRAME_B_WIRE_OFFSET + 1] = pivot.y;
            data[FRAME_B_WIRE_OFFSET + 2] = pivot.z;
            data[FRAME_B_WIRE_OFFSET + 6] = 1.0;
        } else {
            writeFrame(data, FRAME_B_WIRE_OFFSET, anchorOtherBlock, other);
        }
        int otherTag = other == null ? anchorTag : other.getEntityId();
        long handle = Rapier.addJoint(self.getEntityWorld(), self.getEntityId(), otherTag,
                flags(limited, motorized, forceBased), data);
        if (handle == 0) {
            return false;
        }
        rustHandle = handle;
        epochSeen = other == null ? 0 : other.getBodyEpoch();
        return true;
    }

    public void writeToNBT(NBTTagCompound tag) {
        if (staticEndpoint) {
            tag.setBoolean("AnchorStatic", true);
        } else {
            tag.setLong("OtherMost", otherEndpoint.getMostSignificantBits());
            tag.setLong("OtherLeast", otherEndpoint.getLeastSignificantBits());
        }
        tag.setInteger("LockedAxes", mask(lockedAxes));
        tag.setInteger("CoupledAxes", mask(coupledAxes));
        tag.setInteger("AnchorSelfX", anchorSelfBlock.getX());
        tag.setInteger("AnchorSelfY", anchorSelfBlock.getY());
        tag.setInteger("AnchorSelfZ", anchorSelfBlock.getZ());
        tag.setInteger("AnchorOtherX", anchorOtherBlock.getX());
        tag.setInteger("AnchorOtherY", anchorOtherBlock.getY());
        tag.setInteger("AnchorOtherZ", anchorOtherBlock.getZ());
        tag.setBoolean("ContactsEnabled", contactsEnabled);
        NBTTagList axisList = new NBTTagList();
        for (Map.Entry<Axis, AxisConfig> entry : axes.entrySet()) {
            AxisConfig config = entry.getValue();
            NBTTagCompound axisTag = new NBTTagCompound();
            axisTag.setByte("Axis", (byte) entry.getKey().index());
            if (config.limitMin != null) {
                axisTag.setDouble("LimitMin", config.limitMin);
                axisTag.setDouble("LimitMax", config.limitMax);
            }
            if (config.motor != null) {
                Motor motor = config.motor;
                axisTag.setDouble("TargetPos", motor.targetPos);
                axisTag.setDouble("TargetVel", motor.targetVel);
                axisTag.setDouble("Stiffness", motor.stiffness);
                axisTag.setDouble("Damping", motor.damping);
                axisTag.setDouble("MaxForce", motor.maxForce);
                axisTag.setBoolean("ForceBased", motor.forceBased);
                axisTag.setBoolean("Spring", motor.spring);
            }
            axisList.appendTag(axisTag);
        }
        tag.setTag("Axes", axisList);
    }

    public static PhysicsConstraint readFromNBT(NBTTagCompound tag) {
        boolean staticEndpoint = tag.getBoolean("AnchorStatic");
        PhysicsConstraint constraint = new PhysicsConstraint(
                staticEndpoint ? null : new UUID(tag.getLong("OtherMost"), tag.getLong("OtherLeast")));
        constraint.staticEndpoint = staticEndpoint;
        constraint.lockedAxes.addAll(axesFromMask(tag.getInteger("LockedAxes")));
        constraint.coupledAxes.addAll(axesFromMask(tag.getInteger("CoupledAxes")));
        constraint.anchorSelfBlock = new BlockPos(tag.getInteger("AnchorSelfX"), tag.getInteger("AnchorSelfY"),
                tag.getInteger("AnchorSelfZ"));
        constraint.anchorOtherBlock = new BlockPos(tag.getInteger("AnchorOtherX"), tag.getInteger("AnchorOtherY"),
                tag.getInteger("AnchorOtherZ"));
        constraint.contactsEnabled = tag.getBoolean("ContactsEnabled");
        NBTTagList axisList = tag.getTagList("Axes", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < axisList.tagCount(); i++) {
            NBTTagCompound axisTag = axisList.getCompoundTagAt(i);
            if (!axisTag.hasKey("Axis", Constants.NBT.TAG_BYTE)) {
                continue;
            }
            int index = axisTag.getByte("Axis");
            if (index < 0 || index >= Axis.values().length) {
                continue;
            }
            AxisConfig config = constraint.config(Axis.values()[index]);
            if (axisTag.hasKey("LimitMin")) {
                double limitMin = axisTag.getDouble("LimitMin");
                double limitMax = axisTag.getDouble("LimitMax");
                if (Double.isFinite(limitMin) && Double.isFinite(limitMax) && limitMin <= limitMax) {
                    config.limitMin = limitMin;
                    config.limitMax = limitMax;
                }
            }
            if (axisTag.hasKey("Stiffness")) {
                double targetPos = axisTag.getDouble("TargetPos");
                double targetVel = axisTag.getDouble("TargetVel");
                double stiffness = axisTag.getDouble("Stiffness");
                double damping = axisTag.getDouble("Damping");
                double maxForce = axisTag.getDouble("MaxForce");
                if (Double.isFinite(targetPos) && Double.isFinite(targetVel) && Double.isFinite(stiffness) &&
                        Double.isFinite(damping) && Double.isFinite(maxForce)) {
                    Motor motor = Motor.of(targetPos, targetVel, stiffness, damping, maxForce,
                            axisTag.getBoolean("ForceBased"));
                    motor.spring = axisTag.getBoolean("Spring");
                    config.motor = motor;
                }
            }
        }
        return constraint;
    }

    public static void writeConstraints(List<PhysicsConstraint> constraints, NBTTagCompound tag) {
        NBTTagList list = new NBTTagList();
        for (PhysicsConstraint constraint : constraints) {
            NBTTagCompound entry = new NBTTagCompound();
            constraint.writeToNBT(entry);
            list.appendTag(entry);
        }
        tag.setTag("Constraints", list);
    }

    public static List<PhysicsConstraint> readConstraints(NBTTagCompound tag) {
        List<PhysicsConstraint> constraints = new ArrayList<>();
        if (tag.hasKey("Constraints", Constants.NBT.TAG_LIST)) {
            NBTTagList list = tag.getTagList("Constraints", Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < list.tagCount(); i++) {
                constraints.add(readFromNBT(list.getCompoundTagAt(i)));
            }
        }
        return constraints;
    }

    private static EnumSet<Axis> rotationAxes(boolean x, boolean y, boolean z) {
        EnumSet<Axis> axes = EnumSet.noneOf(Axis.class);
        if (x) {
            axes.add(Axis.ANG_X);
        }
        if (y) {
            axes.add(Axis.ANG_Y);
        }
        if (z) {
            axes.add(Axis.ANG_Z);
        }
        return axes;
    }

    private static EnumSet<Axis> axesFromMask(int mask) {
        EnumSet<Axis> axes = EnumSet.noneOf(Axis.class);
        for (Axis axis : Axis.values()) {
            if ((mask & axis.mask()) != 0) {
                axes.add(axis);
            }
        }
        return axes;
    }

    private static int mask(EnumSet<Axis> axes) {
        int mask = 0;
        for (Axis axis : axes) {
            mask |= axis.mask();
        }
        return mask;
    }

    private static double component(Vec3d vector, Axis axis) {
        switch (axis) {
            case LIN_X:
                return vector.x;
            case LIN_Y:
                return vector.y;
            case LIN_Z:
                return vector.z;
            default:
                return 0.0;
        }
    }

    public static Vec3d anchorCenter(BlockPos anchorBlock) {
        return new Vec3d(anchorBlock).add(BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET);
    }

    private static void writeFrame(double[] data, int offset, BlockPos anchorBlock, PhysicsWorldEntity entity) {
        Vec3d center = anchorCenter(anchorBlock).subtract(entity.getRotationPoint());
        data[offset] = center.x;
        data[offset + 1] = center.y;
        data[offset + 2] = center.z;
        data[offset + 3] = 0.0;
        data[offset + 4] = 0.0;
        data[offset + 5] = 0.0;
        data[offset + 6] = 1.0;
    }

    private boolean rejectLocked(Axis axis) {
        if (!lockedAxes.contains(axis)) {
            return false;
        }
        SusyLog.logger.warn("PhysicsConstraint: ignoring a limit or motor on locked axis {}", axis);
        return true;
    }

    private static void requireFiniteMotor(double targetPos, double targetVel, double stiffness, double damping,
                                           double maxForce) {
        if (!finiteMotor(targetPos, targetVel, stiffness, damping, maxForce)) {
            throw new IllegalArgumentException(
                    String.format("joint motor parameters must be finite: pos=%f vel=%f stiffness=%f damping=%f maxForce=%f", targetPos, targetVel, stiffness, damping, maxForce));
        }
    }

    private static boolean finiteMotor(double targetPos, double targetVel, double stiffness, double damping,
                                       double maxForce) {
        return Double.isFinite(targetPos) && Double.isFinite(targetVel) && Double.isFinite(stiffness) &&
                Double.isFinite(damping) && Double.isFinite(maxForce);
    }

    private AxisConfig config(Axis axis) {
        return axes.computeIfAbsent(axis, key -> new AxisConfig());
    }

    private int flags(EnumSet<Axis> limited, EnumSet<Axis> motorized, EnumSet<Axis> forceBased) {
        int flags = mask(lockedAxes) | (mask(coupledAxes) << COUPLED_SHIFT) | (mask(limited) << LIMIT_SHIFT) |
                (mask(motorized) << MOTOR_SHIFT) | (mask(forceBased) << FORCE_BASED_SHIFT);
        return contactsEnabled ? flags | CONTACTS_BIT : flags;
    }

    private Vec3d formationRestOffset(PhysicsWorldEntity self, @Nullable PhysicsWorldEntity other) {
        Vec3d otherCenter = other == null ? anchorCenter(anchorOtherBlock) :
                other.worldPointFromLocal(anchorCenter(anchorOtherBlock));
        Vec3d worldDelta = otherCenter.subtract(self.worldPointFromLocal(anchorCenter(anchorSelfBlock)));
        return self.getRotation().conjugate().rotatePoint(worldDelta);
    }
}
