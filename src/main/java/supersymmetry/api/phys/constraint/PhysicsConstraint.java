package supersymmetry.api.phys.constraint;

import java.util.ArrayList;
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

    private static final int RETRY_INTERVAL_TICKS = 20;
    private static final int MISS_WARN_TICKS = 1200;
    private static final double BLOCK_CENTER_OFFSET = 0.5;
    private static final int ANCHOR_TAG_BASE = 1 << 30;

    private static int anchorTagCounter = ANCHOR_TAG_BASE;

    @Nullable private final UUID otherEndpoint;
    private int anchorTag;
    private long rustHandle;
    private int epochSeen = -1;
    private final EnumSet<Axis> lockedAxes = EnumSet.noneOf(Axis.class);
    private final EnumSet<Axis> coupledAxes = EnumSet.noneOf(Axis.class);
    private final EnumMap<Axis, AxisState> axes = new EnumMap<>(Axis.class);
    private BlockPos anchorSelfBlock = BlockPos.ORIGIN;
    private BlockPos anchorOtherBlock = BlockPos.ORIGIN;
    private boolean contactsEnabled;
    private int retryAfterTick;
    private int consecutiveMisses;
    private int instantiateFailures;
    private boolean dead;

    private static final class AxisState {

        Double limitMin;
        Double limitMax;
        Motor motor;
    }

    private PhysicsConstraint(@Nullable UUID otherEndpoint) {
        this.otherEndpoint = otherEndpoint;
    }

    public static int nextAnchorTag() {
        return ++anchorTagCounter;
    }

    public static PhysicsConstraint toAnchor(BlockPos anchorBlock) {
        var constraint = new PhysicsConstraint(null);
        constraint.anchorOtherBlock = anchorBlock;
        return constraint;
    }

    public PhysicsConstraint lockTranslations() {
        lockedAxes.addAll(Axis.LINEAR);
        return this;
    }

    public PhysicsConstraint lockRotations(boolean x, boolean y, boolean z) {
        lockedAxes.addAll(rotationAxes(x, y, z));
        return this;
    }

    public PhysicsConstraint allowRotations(boolean x, boolean y, boolean z) {
        lockedAxes.addAll(Axis.ROTATIONAL);
        lockedAxes.removeAll(rotationAxes(x, y, z));
        return this;
    }

    public PhysicsConstraint couple(Axis first, Axis... rest) {
        coupledAxes.add(first);
        for (var axis : rest) coupledAxes.add(axis);
        return this;
    }

    public PhysicsConstraint limitAxis(Axis axis, double min, double max) {
        if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
            throw new IllegalArgumentException(
                    String.format("invalid joint limits [%f, %f]", min, max));
        }
        if (!lockedAxes.contains(axis)) {
            var s = state(axis);
            s.limitMin = min;
            s.limitMax = max;
        } else {
            SusyLog.logger.warn("ignoring limit on locked axis {}", axis);
        }
        return this;
    }

    public PhysicsConstraint spring(double stiffness, double damping) {
        requireFiniteMotor(0.0, 0.0, stiffness, damping, Double.MAX_VALUE);
        for (var axis : Axis.LINEAR) {
            if (lockedAxes.contains(axis)) continue;
            var motor = Motor.of(0.0, 0.0, stiffness, damping, Double.MAX_VALUE, true);
            motor.spring = true;
            state(axis).motor = motor;
        }
        return this;
    }

    public PhysicsConstraint motor(Axis axis, double targetPos, double targetVel, double stiffness, double damping,
                                   double maxForce, boolean forceBased) {
        requireFiniteMotor(targetPos, targetVel, stiffness, damping, maxForce);
        if (lockedAxes.contains(axis)) {
            SusyLog.logger.warn("ignoring motor on locked axis {}", axis);
            return this;
        }
        state(axis).motor = Motor.of(targetPos, targetVel, stiffness, damping, maxForce, forceBased);
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
        return otherEndpoint == null;
    }

    public boolean isDead() {
        return dead;
    }

    public void attachAnchor(World world, int tag) {
        if (otherEndpoint != null || tag == 0 || anchorTag == tag) return;
        teardown(world);
        anchorTag = tag;
    }

    public void remapAnchors(int dx, int dz, boolean self) {
        if (self) {
            anchorSelfBlock = anchorSelfBlock.add(dx, 0, dz);
        } else if (otherEndpoint != null) {
            anchorOtherBlock = anchorOtherBlock.add(dx, 0, dz);
        }
    }

    public boolean setMotor(World world, Axis axis, double targetPos, double targetVel, double stiffness,
                            double damping, double maxForce, boolean forceBased) {
        if (!finiteMotor(targetPos, targetVel, stiffness, damping, maxForce)) return false;
        if (lockedAxes.contains(axis)) {
            SusyLog.logger.warn("ignoring motor on locked axis {}", axis);
            return false;
        }
        state(axis).motor = Motor.of(targetPos, targetVel, stiffness, damping, maxForce, forceBased);
        return rustHandle != 0 && Rapier.setJointMotor(world, rustHandle, axis.index(), targetPos, targetVel,
                stiffness, damping, maxForce, forceBased);
    }

    public void teardown(World world) {
        if (rustHandle != 0) {
            Rapier.removeJoint(world, rustHandle);
            rustHandle = 0;
        }
        epochSeen = -1;
    }

    public void update(PhysicsWorldEntity self) {
        if (dead) return;
        var other = resolveBody(self);
        if (dead) return;
        if (!self.hasColliders() || (otherEndpoint == null ? anchorTag == 0 : other == null || !other.hasColliders())) {
            teardown(self.getEntityWorld());
            return;
        }
        if (other != null && !other.hasPhysicsBody()) return;
        if (rustHandle != 0 && (other == null || epochSeen == other.getBodyEpoch())) return;
        if (self.ticksExisted < retryAfterTick) return;
        tryInstantiate(self, other);
    }

    private void tryInstantiate(PhysicsWorldEntity self, @Nullable PhysicsWorldEntity other) {
        teardown(self.getEntityWorld());
        var wire = buildWire(self, other);
        var otherTag = other == null ? anchorTag : other.getEntityId();
        var handle = Rapier.addJoint(self.getEntityWorld(), self.getEntityId(), otherTag, wire.flags(), wire.data());
        if (handle != 0) {
            rustHandle = handle;
            epochSeen = other == null ? 0 : other.getBodyEpoch();
            retryAfterTick = 0;
            instantiateFailures = 0;
            return;
        }
        retryAfterTick = self.ticksExisted + RETRY_INTERVAL_TICKS;
        if (++instantiateFailures == 1 || instantiateFailures % 10 == 0) {
            SusyLog.logger.warn("failed to instantiate constraint toward {}",
                    other != null ? otherEndpoint : anchorOtherBlock);
        }
    }

    @Nullable private PhysicsWorldEntity resolveBody(PhysicsWorldEntity self) {
        if (otherEndpoint == null) return null;
        if (otherEndpoint.equals(self.getUniqueID())) {
            dead = true;
            SusyLog.logger.warn("dropping self-referential constraint toward {}", otherEndpoint);
            return null;
        }
        var other = resolveEndpoint(self.getEntityWorld(), otherEndpoint);
        if (other == null) {
            consecutiveMisses = Math.min(consecutiveMisses + 1, MISS_WARN_TICKS);
            if (consecutiveMisses == MISS_WARN_TICKS) {
                SusyLog.logger.warn("constraint endpoint {} unresolved for {} ticks, staying pending",
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
            for (var loaded : world.loadedEntityList) {
                if (uuid.equals(loaded.getUniqueID())) {
                    entity = loaded;
                    break;
                }
            }
        }
        return entity instanceof PhysicsWorldEntity ? (PhysicsWorldEntity) entity : null;
    }

    private JointWire buildWire(PhysicsWorldEntity self, @Nullable PhysicsWorldEntity other) {
        var wire = new JointWire(mask(lockedAxes), mask(coupledAxes), contactsEnabled);
        var restOffset = formationRestOffset(self, other);
        for (var entry : axes.entrySet()) {
            if (lockedAxes.contains(entry.getKey())) continue;
            var axis = entry.getKey();
            var s = entry.getValue();
            if (s.limitMin != null) wire.limit(axis, s.limitMin, s.limitMax);
            if (s.motor != null) {
                var m = s.motor;
                var targetPos = m.spring ? (Axis.LINEAR.contains(axis) ? linearComponent(restOffset, axis) : 0.0)
                        : m.targetPos;
                wire.motor(axis, targetPos, m.targetVel, m.stiffness, m.damping, m.maxForce, m.forceBased);
            }
        }
        wire.frameA(anchorCenter(anchorSelfBlock).subtract(self.getRotationPoint()));
        var frameB = other == null
                ? self.worldPointFromLocal(anchorCenter(anchorSelfBlock)).subtract(anchorCenter(anchorOtherBlock))
                : anchorCenter(anchorOtherBlock).subtract(other.getRotationPoint());
        wire.frameB(frameB);
        return wire;
    }

    public void writeToNBT(NBTTagCompound tag) {
        if (otherEndpoint == null) {
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
        var list = new NBTTagList();
        for (var entry : axes.entrySet()) {
            var axisTag = new NBTTagCompound();
            axisTag.setByte("Axis", (byte) entry.getKey().index());
            var s = entry.getValue();
            if (s.limitMin != null) {
                axisTag.setDouble("LimitMin", s.limitMin);
                axisTag.setDouble("LimitMax", s.limitMax);
            }
            if (s.motor != null) {
                var m = s.motor;
                axisTag.setDouble("TargetPos", m.targetPos);
                axisTag.setDouble("TargetVel", m.targetVel);
                axisTag.setDouble("Stiffness", m.stiffness);
                axisTag.setDouble("Damping", m.damping);
                axisTag.setDouble("MaxForce", m.maxForce);
                axisTag.setBoolean("ForceBased", m.forceBased);
                axisTag.setBoolean("Spring", m.spring);
            }
            list.appendTag(axisTag);
        }
        tag.setTag("Axes", list);
    }

    public static PhysicsConstraint readFromNBT(NBTTagCompound tag) {
        var constraint = new PhysicsConstraint(tag.getBoolean("AnchorStatic") ? null
                : new UUID(tag.getLong("OtherMost"), tag.getLong("OtherLeast")));
        constraint.lockedAxes.addAll(axesFromMask(tag.getInteger("LockedAxes")));
        constraint.coupledAxes.addAll(axesFromMask(tag.getInteger("CoupledAxes")));
        constraint.anchorSelfBlock = new BlockPos(tag.getInteger("AnchorSelfX"), tag.getInteger("AnchorSelfY"),
                tag.getInteger("AnchorSelfZ"));
        constraint.anchorOtherBlock = new BlockPos(tag.getInteger("AnchorOtherX"), tag.getInteger("AnchorOtherY"),
                tag.getInteger("AnchorOtherZ"));
        constraint.contactsEnabled = tag.getBoolean("ContactsEnabled");
        var list = tag.getTagList("Axes", Constants.NBT.TAG_COMPOUND);
        var allAxes = Axis.values();
        for (var i = 0; i < list.tagCount(); i++) {
            var axisTag = list.getCompoundTagAt(i);
            if (!axisTag.hasKey("Axis", Constants.NBT.TAG_BYTE)) continue;
            var idx = (int) axisTag.getByte("Axis");
            if (idx < 0 || idx >= allAxes.length) continue;
            var s = constraint.state(allAxes[idx]);
            if (axisTag.hasKey("LimitMin")) {
                var min = axisTag.getDouble("LimitMin");
                var max = axisTag.getDouble("LimitMax");
                if (Double.isFinite(min) && Double.isFinite(max) && min <= max) {
                    s.limitMin = min;
                    s.limitMax = max;
                }
            }
            if (axisTag.hasKey("Stiffness")) {
                var tp = axisTag.getDouble("TargetPos");
                var tv = axisTag.getDouble("TargetVel");
                var st = axisTag.getDouble("Stiffness");
                var da = axisTag.getDouble("Damping");
                var mf = axisTag.getDouble("MaxForce");
                if (finiteMotor(tp, tv, st, da, mf)) {
                    var m = Motor.of(tp, tv, st, da, mf, axisTag.getBoolean("ForceBased"));
                    m.spring = axisTag.getBoolean("Spring");
                    s.motor = m;
                }
            }
        }
        return constraint;
    }

    public static void writeConstraints(List<PhysicsConstraint> constraints, NBTTagCompound tag) {
        var list = new NBTTagList();
        for (var constraint : constraints) {
            var entry = new NBTTagCompound();
            constraint.writeToNBT(entry);
            list.appendTag(entry);
        }
        tag.setTag("Constraints", list);
    }

    public static List<PhysicsConstraint> readConstraints(NBTTagCompound tag) {
        var constraints = new ArrayList<PhysicsConstraint>();
        if (tag.hasKey("Constraints", Constants.NBT.TAG_LIST)) {
            var list = tag.getTagList("Constraints", Constants.NBT.TAG_COMPOUND);
            for (var i = 0; i < list.tagCount(); i++) {
                constraints.add(readFromNBT(list.getCompoundTagAt(i)));
            }
        }
        return constraints;
    }

    public static Vec3d anchorCenter(BlockPos anchorBlock) {
        return new Vec3d(anchorBlock).add(BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET, BLOCK_CENTER_OFFSET);
    }

    private static EnumSet<Axis> rotationAxes(boolean x, boolean y, boolean z) {
        var axes = EnumSet.noneOf(Axis.class);
        if (x) axes.add(Axis.ANG_X);
        if (y) axes.add(Axis.ANG_Y);
        if (z) axes.add(Axis.ANG_Z);
        return axes;
    }

    private static EnumSet<Axis> axesFromMask(int mask) {
        var axes = EnumSet.noneOf(Axis.class);
        for (var axis : Axis.values()) {
            if ((mask & axis.mask()) != 0) axes.add(axis);
        }
        return axes;
    }

    private static int mask(EnumSet<Axis> axes) {
        var m = 0;
        for (var axis : axes) m |= axis.mask();
        return m;
    }

    private static double linearComponent(Vec3d v, Axis axis) {
        if (axis == Axis.LIN_X) return v.x;
        if (axis == Axis.LIN_Y) return v.y;
        if (axis == Axis.LIN_Z) return v.z;
        return 0.0;
    }

    private static void requireFiniteMotor(double targetPos, double targetVel, double stiffness, double damping,
                                           double maxForce) {
        if (!finiteMotor(targetPos, targetVel, stiffness, damping, maxForce)) {
            throw new IllegalArgumentException(String.format(
                    "joint motor parameters must be finite: pos=%f vel=%f stiffness=%f damping=%f maxForce=%f",
                    targetPos, targetVel, stiffness, damping, maxForce));
        }
    }

    private static boolean finiteMotor(double targetPos, double targetVel, double stiffness, double damping,
                                       double maxForce) {
        return Double.isFinite(targetPos) && Double.isFinite(targetVel) && Double.isFinite(stiffness)
                && Double.isFinite(damping) && Double.isFinite(maxForce);
    }

    private AxisState state(Axis axis) {
        return axes.computeIfAbsent(axis, k -> new AxisState());
    }

    private Vec3d formationRestOffset(PhysicsWorldEntity self, @Nullable PhysicsWorldEntity other) {
        var otherCenter = other == null ? anchorCenter(anchorOtherBlock)
                : other.worldPointFromLocal(anchorCenter(anchorOtherBlock));
        return self.getRotation().conjugate()
                .rotatePoint(otherCenter.subtract(self.worldPointFromLocal(anchorCenter(anchorSelfBlock))));
    }
}
