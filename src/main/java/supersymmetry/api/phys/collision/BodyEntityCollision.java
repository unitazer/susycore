package supersymmetry.api.phys.collision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.MoverType;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import org.jetbrains.annotations.Nullable;

import supersymmetry.api.phys.PhysicsWorldEntity;
import supersymmetry.api.phys.RigidPose;
import supersymmetry.api.subworld.SubWorldPlot;
import supersymmetry.api.util.Quaternion;

public final class BodyEntityCollision {

    private static final double MAX_SUBSTEP_DISTANCE = 0.25;
    private static final int MAX_SUBSTEPS = 16;
    private static final int MIN_PLAYER_SUBSTEPS = 8;
    private static final double CULL_MARGIN = 0.5;
    private static final double SCAN_MARGIN = 2.0;
    private static final double TRACK_MARGIN = 0.5;
    private static final double TRACK_DEPTH = 2.0;
    private static final double CONTACT_EPSILON = 1.0e-4;
    private static final double NORMAL_PROBE_INFLATION = 0.05;
    private static final double VERTICAL_NORMAL_THRESHOLD = 0.6;
    private static final double FLOOR_NORMAL_THRESHOLD = 0.8;
    private static final int MAX_PUSHOUT_ITERATIONS = 4;
    private static final double STEP_CHECK_INCREMENT = 1.0 / 16.0;
    private static final double STEP_PROBE_BACKOFF = STEP_CHECK_INCREMENT * 2;
    private static final double STEP_LATERAL_CLEARANCE = 0.05;
    private static final double SNEAK_SHAVE = 0.05;
    private static final double WALL_FRICTION = 0.995;
    private static final double SPRINT_LOSS_FRACTION = 0.1;
    private static final int ESCAPE_TICK_THRESHOLD = 10;
    private static final double ESCAPE_INCREMENT = 0.25;
    private static final double ESCAPE_MAX_DISTANCE = 3.0;

    private static final Vec3d AXIS_X = new Vec3d(1.0, 0.0, 0.0);
    private static final Vec3d AXIS_Y = new Vec3d(0.0, 1.0, 0.0);
    private static final Vec3d AXIS_Z = new Vec3d(0.0, 0.0, 1.0);
    private static final Vec3d[] WORLD_AXES = { AXIS_X, AXIS_Y, AXIS_Z };
    private static final Vec3d[] ESCAPE_DIRECTIONS = { AXIS_Y, AXIS_X, AXIS_X.scale(-1.0), AXIS_Z, AXIS_Z.scale(-1.0),
            AXIS_Y.scale(-1.0) };
    private static final Vec3d FULL_CUBE_HALF_EXTENTS = new Vec3d(0.5, 0.5, 0.5);

    private static final Map<Entity, StuckState> STUCK_TICKS = Collections.synchronizedMap(new WeakHashMap<>());

    private BodyEntityCollision() {}

    public static final class MoveResult {

        public final Vec3d motion;
        public final Vec3d carryDelta;
        @Nullable public final Vec3d escape;
        @Nullable public final PhysicsWorldEntity groundBody;
        @Nullable public final Vec3d groundNormal;
        @Nullable public final Vec3d ceilingNormal;
        @Nullable public final Vec3d wallNormal;

        private MoveResult(Vec3d motion, Vec3d carryDelta, @Nullable Vec3d escape,
                           @Nullable PhysicsWorldEntity groundBody,
                           @Nullable Vec3d groundNormal, @Nullable Vec3d ceilingNormal, @Nullable Vec3d wallNormal) {
            this.motion = motion;
            this.carryDelta = carryDelta;
            this.escape = escape;
            this.groundBody = groundBody;
            this.groundNormal = groundNormal;
            this.ceilingNormal = ceilingNormal;
            this.wallNormal = wallNormal;
        }
    }

    @Nullable public static MoveResult resolveMotion(Entity entity, MoverType moverType, Vec3d motion) {
        if (entity.isDead || entity.noClip || entity instanceof PhysicsWorldEntity) return null;
        PhysicsWorldEntity tracked = refreshTracking(entity);
        Vec3d start = entity.getPositionVector();
        AxisAlignedBB sweptWorldBox = boxAt(entity, start).union(boxAt(entity, start.add(motion)));
        List<PhysicsWorldEntity> bodies = candidateBodies(entity, sweptWorldBox);
        if (bodies.isEmpty()) {
            if (tracked == null) return null;
            bodies.add(tracked);
        }
        int substeps = substepCount(entity, motion, bodies);
        BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();
        List<BodyBlocks> bodyColliders = collectColliders(entity, bodies, tracked, sweptWorldBox, scratchPos);
        BodyBlocks trackedEntry = tracked == null ? null : findEntry(bodyColliders, tracked);
        beginSteps(bodyColliders, substeps, substeps);
        if (stuckTicks(entity) < ESCAPE_TICK_THRESHOLD) {
            motion = clampSneaking(entity, moverType, motion, bodyColliders);
        }
        Contact contact = new Contact();
        Vec3d pos = start;
        Vec3d carryDelta = Vec3d.ZERO;
        for (int i = 1; i <= substeps; i++) {
            beginSteps(bodyColliders, i, substeps);
            if (trackedEntry != null) {
                Vec3d carried = trackedEntry.mapThroughPoseDelta(pos);
                carryDelta = carryDelta.add(carried.subtract(pos));
                pos = carried;
            }
            pos = pushOut(entity, pos, bodyColliders, contact);
            Vec3d disp = motion.scale(1.0 / substeps);
            pos = clipAxis(entity, pos, disp.y, AXIS_Y, bodyColliders, contact, false);
            pos = clipAxis(entity, pos, disp.x, AXIS_X, bodyColliders, contact, true);
            pos = clipAxis(entity, pos, disp.z, AXIS_Z, bodyColliders, contact, true);
        }
        pos = pushOut(entity, pos, bodyColliders, contact);
        Vec3d escape = null;
        if (countOverlaps(boxAt(entity, pos), bodyColliders) > 0) {
            if (updateStuckTicks(entity, true) >= ESCAPE_TICK_THRESHOLD) {
                escape = findEscape(entity, pos, bodyColliders);
            }
        } else {
            updateStuckTicks(entity, false);
        }
        if (escape != null) pos = pos.add(escape);
        return new MoveResult(pos.subtract(start), carryDelta, escape, contact.groundBody, contact.groundNormal,
                contact.ceilingNormal, contact.wallNormal);
    }

    public static void applyMoveResult(Entity entity, MoveResult result) {
        IBodyRider rider = (IBodyRider) entity;
        Vec3d motion = new Vec3d(entity.motionX, entity.motionY, entity.motionZ);
        if (result.groundNormal != null) {
            motion = removeIntoSurface(motion, result.groundNormal);
        }
        if (result.ceilingNormal != null) {
            motion = removeIntoSurface(motion, result.ceilingNormal);
        }
        if (result.wallNormal != null) {
            motion = applyWallImpact(motion, result.wallNormal, entity);
        }
        entity.motionX = motion.x;
        entity.motionY = motion.y;
        entity.motionZ = motion.z;
        if (result.groundNormal != null) {
            entity.onGround = true;
            entity.collidedVertically = true;
            if (entity instanceof EntityLivingBase) fallOnBody((EntityLivingBase) entity);
            entity.fallDistance = 0.0F;
            if (result.groundBody != null) rider.susy$setTrackingBody(result.groundBody);
        }
        if (result.ceilingNormal != null) {
            entity.collidedVertically = true;
        }
        if (result.wallNormal != null) {
            entity.collidedHorizontally = true;
        }
        if (result.groundNormal != null || result.ceilingNormal != null || result.wallNormal != null) {
            entity.collided = true;
        }
        rider.susy$recordCarry(result.carryDelta);
    }

    public static boolean shouldSuppressMovedTooQuickly(EntityPlayerMP player, double packetX, double packetY,
                                                        double packetZ, double firstGoodX, double firstGoodY,
                                                        double firstGoodZ) {
        if (((IBodyRider) player).susy$getTrackingBody() == null) return false;
        double dx = packetX - firstGoodX;
        double dy = packetY - firstGoodY;
        double dz = packetZ - firstGoodZ;
        double packetDeltaSq = dx * dx + dy * dy + dz * dz;
        double motionSq = player.motionX * player.motionX + player.motionY * player.motionY +
                player.motionZ * player.motionZ;
        if (packetDeltaSq - motionSq <= 100.0) return false;
        double carry = ((IBodyRider) player).susy$getLastCarryDelta().length();
        double allowed = Math.sqrt(motionSq) + carry + 0.625;
        return packetDeltaSq <= allowed * allowed;
    }

    public static boolean shouldSuppressMovedWrongly(EntityPlayerMP player, double packetX, double packetY,
                                                     double packetZ) {
        if (((IBodyRider) player).susy$getTrackingBody() == null) return false;
        double dx = packetX - player.posX;
        double dy = packetY - player.posY;
        double dz = packetZ - player.posZ;
        double residualSq = dx * dx + dy * dy + dz * dz;
        if (residualSq <= 0.0625) return false;
        double carry = ((IBodyRider) player).susy$getLastCarryDelta().length();
        return Math.sqrt(residualSq) <= carry + 0.125;
    }

    private static void beginSteps(List<BodyBlocks> entries, int step, int substeps) {
        for (BodyBlocks entry : entries) {
            entry.beginStep(step, substeps);
        }
    }

    private static PhysicsWorldEntity refreshTracking(Entity entity) {
        IBodyRider rider = (IBodyRider) entity;
        PhysicsWorldEntity tracked = rider.susy$getTrackingBody();
        if (tracked == null) return null;
        if (tracked.isDead || tracked.world != entity.world || tracked.getPlot() == null || entity.isRiding() ||
                !stillOverBody(entity, tracked)) {
            rider.susy$setTrackingBody(null);
            return null;
        }
        return tracked;
    }

    private static List<BodyBlocks> collectColliders(Entity entity, List<PhysicsWorldEntity> bodies,
                                                     PhysicsWorldEntity tracked, AxisAlignedBB sweptWorldBox,
                                                     BlockPos.MutableBlockPos scratchPos) {
        List<BodyBlocks> entries = new ArrayList<>(bodies.size());
        for (PhysicsWorldEntity body : bodies) {
            entries.add(new BodyBlocks(body, collectBlocks(entity, body, body.currentPose(), sweptWorldBox,
                    sweepMargin(body), scratchPos)));
        }
        if (tracked != null && findEntry(entries, tracked) == null) {
            entries.add(new BodyBlocks(tracked, collectBlocks(entity, tracked, tracked.currentPose(), sweptWorldBox,
                    sweepMargin(tracked), scratchPos)));
        }
        return entries;
    }

    private static BodyBlocks findEntry(List<BodyBlocks> entries, PhysicsWorldEntity body) {
        for (BodyBlocks entry : entries) {
            if (entry.body == body) return entry;
        }
        return null;
    }

    private static Vec3d removeIntoSurface(Vec3d motion, Vec3d normal) {
        double into = motion.dotProduct(normal);
        if (into >= 0.0) return motion;
        return motion.subtract(normal.scale(into));
    }

    private static Vec3d applyWallImpact(Vec3d motion, Vec3d normal, Entity entity) {
        double into = motion.dotProduct(normal);
        if (into < 0.0) {
            Vec3d loss = normal.scale(into);
            if (loss.length() > motion.length() * SPRINT_LOSS_FRACTION) {
                entity.setSprinting(false);
            }
            motion = motion.subtract(loss);
        }
        return new Vec3d(motion.x * WALL_FRICTION, motion.y, motion.z * WALL_FRICTION);
    }

    private static void fallOnBody(EntityLivingBase entity) {
        float distance = entity.fallDistance;
        if (distance > 3.0F) entity.fall(distance, 1.0F);
    }

    private static boolean stillOverBody(Entity entity, PhysicsWorldEntity body) {
        AxisAlignedBB box = boxAt(entity, entity.getPositionVector());
        AxisAlignedBB below = new AxisAlignedBB(box.minX - TRACK_MARGIN, box.minY - TRACK_DEPTH,
                box.minZ - TRACK_MARGIN, box.maxX + TRACK_MARGIN, box.maxY, box.maxZ + TRACK_MARGIN);
        return body.getEntityBoundingBox().intersects(below);
    }

    private static int stuckTicks(Entity entity) {
        StuckState state = STUCK_TICKS.get(entity);
        return state == null ? 0 : state.current;
    }

    private static int updateStuckTicks(Entity entity, boolean stuck) {
        StuckState state = STUCK_TICKS.get(entity);
        if (state == null) {
            state = new StuckState();
            STUCK_TICKS.put(entity, state);
        }
        return state.update(entity.world.getTotalWorldTime(), stuck);
    }

    @Nullable private static Vec3d findEscape(Entity entity, Vec3d pos, List<BodyBlocks> entries) {
        int bestOverlaps = countOverlaps(boxAt(entity, pos), entries);
        Vec3d best = null;
        for (Vec3d direction : ESCAPE_DIRECTIONS) {
            for (double distance = ESCAPE_INCREMENT; distance <= ESCAPE_MAX_DISTANCE; distance += ESCAPE_INCREMENT) {
                AxisAlignedBB box = boxAt(entity, pos.add(direction.scale(distance)));
                if (!entity.world.getCollisionBoxes(entity, box).isEmpty()) break;
                int overlaps = countOverlaps(box, entries);
                if (overlaps >= bestOverlaps) continue;
                best = direction.scale(distance);
                bestOverlaps = overlaps;
                if (overlaps == 0) return best;
            }
        }
        return best;
    }

    private static int countOverlaps(AxisAlignedBB box, List<BodyBlocks> entries) {
        int overlaps = 0;
        for (BodyBlocks entry : entries) {
            if (entry.blocks.isEmpty() || !box.intersects(entry.body.getEntityBoundingBox())) continue;
            for (PlotBlock block : entry.near(box)) {
                if (entry.obb(block).mtv(box) != null) overlaps++;
            }
        }
        return overlaps;
    }

    private static int substepCount(Entity entity, Vec3d motion, List<PhysicsWorldEntity> bodies) {
        int minimum = entity instanceof EntityPlayer ? MIN_PLAYER_SUBSTEPS : 1;
        double maxBodyDelta = 0.0;
        for (PhysicsWorldEntity body : bodies) {
            maxBodyDelta = Math.max(maxBodyDelta,
                    body.getPositionVector().subtract(body.getPrevPosePos()).length());
        }
        double sweptDistance = motion.length() + maxBodyDelta;
        return Math.min(MAX_SUBSTEPS,
                Math.max(minimum, (int) Math.ceil(sweptDistance / MAX_SUBSTEP_DISTANCE)));
    }

    private static double sweepMargin(PhysicsWorldEntity body) {
        RigidPose current = body.currentPose();
        RigidPose previous = body.prevPose();
        double cosHalfAngle = Math.min(1.0, Math.abs(current.rotation.dot(previous.rotation)));
        double rotation = 2.0 * Math.acos(cosHalfAngle) * contentRadius(current, body);
        return body.getPositionVector().subtract(body.getPrevPosePos()).length() + rotation;
    }

    private static double contentRadius(RigidPose pose, PhysicsWorldEntity body) {
        AxisAlignedBB bodyRelative = pose.bodyRelativeBoxFromWorld(body.getEntityBoundingBox());
        double radiusSq = 0.0;
        for (double x : new double[] { bodyRelative.minX, bodyRelative.maxX }) {
            for (double y : new double[] { bodyRelative.minY, bodyRelative.maxY }) {
                for (double z : new double[] { bodyRelative.minZ, bodyRelative.maxZ }) {
                    radiusSq = Math.max(radiusSq, x * x + y * y + z * z);
                }
            }
        }
        return Math.sqrt(radiusSq);
    }

    private static List<PhysicsWorldEntity> candidateBodies(Entity entity, AxisAlignedBB sweptWorldBox) {
        List<PhysicsWorldEntity> bodies = new ArrayList<>();
        AxisAlignedBB query = sweptWorldBox.grow(CULL_MARGIN);
        for (PhysicsWorldEntity body : PhysicsWorldEntity.activeBodies()) {
            if (body.world != entity.world || body.getPlot() == null) continue;
            if (body.getEntityBoundingBox().intersects(query)) bodies.add(body);
        }
        return bodies;
    }

    private static Vec3d pushOut(Entity entity, Vec3d pos, List<BodyBlocks> entries, Contact contact) {
        for (int iteration = 0; iteration < MAX_PUSHOUT_ITERATIONS; iteration++) {
            AxisAlignedBB box = boxAt(entity, pos);
            Vec3d bestMtv = null;
            BodyBlocks bestEntry = null;
            for (BodyBlocks entry : entries) {
                if (entry.blocks.isEmpty() || !box.intersects(entry.body.getEntityBoundingBox())) continue;
                for (PlotBlock block : entry.near(box)) {
                    Vec3d mtv = entry.obb(block).mtv(box);
                    if (mtv == null || isInterior(entry, block, mtv)) continue;
                    if (bestMtv == null || mtv.lengthSquared() > bestMtv.lengthSquared()) {
                        bestMtv = mtv;
                        bestEntry = entry;
                    }
                }
            }
            if (bestMtv == null) return pos;
            double length = bestMtv.length();
            Vec3d normal = bestMtv.scale(1.0 / length);
            if (normal.y > VERTICAL_NORMAL_THRESHOLD) {
                normal = recordGround(contact, normal, bestEntry.body);
            } else if (normal.y < -VERTICAL_NORMAL_THRESHOLD) {
                contact.ceilingNormal = normal;
            } else {
                contact.wallNormal = normal;
            }
            pos = pos.add(normal.scale(length));
        }
        return pos;
    }

    private static Vec3d recordGround(Contact contact, Vec3d normal, PhysicsWorldEntity body) {
        if (normal.y > FLOOR_NORMAL_THRESHOLD) normal = AXIS_Y;
        contact.groundBody = body;
        contact.groundNormal = normal;
        contact.grounded = true;
        return normal;
    }

    private static Vec3d clipAxis(Entity entity, Vec3d pos, double displacement, Vec3d axis, List<BodyBlocks> entries,
                                  Contact contact, boolean allowStepUp) {
        if (displacement == 0.0) return pos;
        Vec3d direction = axis.scale(Math.signum(displacement));
        double distance = Math.abs(displacement);
        AxisAlignedBB box = boxAt(entity, pos);
        double allowed = distance;
        BodyBlocks hitEntry = null;
        PlotBlock hitBlock = null;
        for (BodyBlocks entry : entries) {
            if (entry.blocks.isEmpty() || !box.intersects(entry.body.getEntityBoundingBox())) continue;
            for (PlotBlock block : entry.near(box, distance)) {
                double sweep = entry.obb(block).sweepClip(box, direction, allowed);
                if (sweep < allowed) {
                    allowed = sweep;
                    hitEntry = entry;
                    hitBlock = block;
                }
            }
        }
        if (allowed >= distance) {
            return pos.add(direction.scale(distance));
        }
        Vec3d normal = hitEntry.contactNormal(hitBlock, boxAt(entity, pos.add(direction.scale(allowed))));
        if (normal.y > VERTICAL_NORMAL_THRESHOLD) {
            recordGround(contact, normal, hitEntry.body);
        } else if (normal.y < -VERTICAL_NORMAL_THRESHOLD) {
            contact.ceilingNormal = normal;
        } else if (allowStepUp) {
            double lift = tryStepUp(entity, pos, hitEntry, hitBlock, normal, entries, contact);
            if (lift >= 0.0) {
                Vec3d stepped = pos.add(0.0, lift, 0.0).add(normal.scale(-STEP_CHECK_INCREMENT));
                contact.grounded = true;
                return clipAxis(entity, stepped, displacement, axis, entries, contact, false);
            }
            contact.wallNormal = normal;
        } else {
            contact.wallNormal = normal;
        }
        return pos.add(direction.scale(allowed));
    }

    private static double tryStepUp(Entity entity, Vec3d pos, BodyBlocks entry, PlotBlock block, Vec3d wallNormal,
                                    List<BodyBlocks> entries, Contact contact) {
        if ((!entity.onGround && !contact.grounded) || entity.stepHeight <= 0.0F) return -1.0;
        double liftNeeded = entry.obb(block).topY() - boxAt(entity, pos).minY;
        if (liftNeeded <= 0.0 || liftNeeded > entity.stepHeight + 0.001) return -1.0;
        Vec3d towardWall = wallNormal.scale(-STEP_PROBE_BACKOFF);
        Vec3d lastMtv = null;
        boolean blockedBelow = false;
        for (double height = 0.0; height <= entity.stepHeight; height += STEP_CHECK_INCREMENT) {
            Vec3d mtv = penetrationAt(entity, pos.add(0.0, height, 0.0).add(towardWall), entries, block);
            if (mtv == null) {
                if (!blockedBelow || lastMtv == null || lastMtv.normalize().y <= FLOOR_NORMAL_THRESHOLD) return -1.0;
                return height;
            }
            blockedBelow = true;
            lastMtv = mtv;
        }
        return -1.0;
    }

    @Nullable private static Vec3d penetrationAt(Entity entity, Vec3d pos, List<BodyBlocks> entries,
                                       PlotBlock excluded) {
        AxisAlignedBB probe = boxAt(entity, pos).grow(STEP_LATERAL_CLEARANCE, 0.0, STEP_LATERAL_CLEARANCE);
        if (!entity.world.getCollisionBoxes(entity, probe).isEmpty()) return AXIS_Y;
        for (BodyBlocks entry : entries) {
            for (PlotBlock block : entry.near(probe)) {
                if (block == excluded) continue;
                Vec3d mtv = entry.obb(block).mtv(probe);
                if (mtv != null) return mtv;
            }
        }
        return null;
    }

    private static boolean isInterior(BodyBlocks entry, PlotBlock block, Vec3d mtv) {
        if (!block.fullCube) return false;
        Vec3d local = entry.pose.directionToPlotLocal(mtv.normalize());
        int ox = (int) Math.round(local.x);
        int oy = (int) Math.round(local.y);
        int oz = (int) Math.round(local.z);
        if (ox == 0 && oy == 0 && oz == 0) return false;
        SubWorldPlot plot = entry.body.getPlot();
        BlockPos neighbor = new BlockPos(plot.getOriginChunkX() * 16 + block.gx + ox,
                SubWorldPlot.RENDER_ORIGIN_Y + block.gy + oy, plot.getOriginChunkZ() * 16 + block.gz + oz);
        return plot.getBlockState(neighbor).isFullCube();
    }

    private static Vec3d clampSneaking(Entity entity, MoverType moverType, Vec3d motion, List<BodyBlocks> entries) {
        if (!(entity instanceof EntityPlayer) || entries.isEmpty()) return motion;
        if (moverType != MoverType.SELF && moverType != MoverType.PLAYER) return motion;
        if (!entity.onGround || !entity.isSneaking()) return motion;
        double drop = entity.stepHeight;
        AxisAlignedBB start = boxAt(entity, entity.getPositionVector());
        double dx = motion.x;
        while (dx != 0.0 && !hasSneakSupport(entity, start.offset(dx, -drop, 0.0), entries)) {
            dx = shaveSneak(dx);
        }
        double dz = motion.z;
        while (dz != 0.0 && !hasSneakSupport(entity, start.offset(0.0, -drop, dz), entries)) {
            dz = shaveSneak(dz);
        }
        while (dx != 0.0 && dz != 0.0 && !hasSneakSupport(entity, start.offset(dx, -drop, dz), entries)) {
            dx = shaveSneak(dx);
            dz = shaveSneak(dz);
        }
        return new Vec3d(dx, motion.y, dz);
    }

    private static boolean hasSneakSupport(Entity entity, AxisAlignedBB box, List<BodyBlocks> entries) {
        if (!entity.world.getCollisionBoxes(entity, box).isEmpty()) return true;
        for (BodyBlocks entry : entries) {
            for (PlotBlock block : entry.near(box)) {
                if (entry.obb(block).mtv(box) != null) return true;
            }
        }
        return false;
    }

    private static double shaveSneak(double value) {
        if (value < SNEAK_SHAVE && value > -SNEAK_SHAVE) return 0.0;
        return value > 0.0 ? value - SNEAK_SHAVE : value + SNEAK_SHAVE;
    }

    private static List<PlotBlock> collectBlocks(Entity entity, PhysicsWorldEntity body, RigidPose referencePose,
                                                 AxisAlignedBB sweptWorldBox, double poseShift,
                                                 BlockPos.MutableBlockPos scratchPos) {
        List<PlotBlock> blocks = new ArrayList<>();
        SubWorldPlot plot = body.getPlot();
        if (plot == null) return blocks;
        AxisAlignedBB plotLocalScan = referencePose.plotLocalBoxFromWorld(sweptWorldBox).grow(SCAN_MARGIN + poseShift);
        int minX = MathHelper.floor(plotLocalScan.minX);
        int maxX = MathHelper.floor(plotLocalScan.maxX);
        int minZ = MathHelper.floor(plotLocalScan.minZ);
        int maxZ = MathHelper.floor(plotLocalScan.maxZ);
        int minGy = MathHelper.floor(plotLocalScan.minY) + SubWorldPlot.RENDER_ORIGIN_Y;
        int maxGy = MathHelper.floor(plotLocalScan.maxY) + SubWorldPlot.RENDER_ORIGIN_Y;
        if (maxX < 0 || minX >= plot.getSizeChunksX() * 16 || maxZ < 0 || minZ >= plot.getSizeChunksZ() * 16 ||
                maxGy < 0 || minGy > 255) {
            return blocks;
        }
        PlotScan scan = new PlotScan(entity.world, referencePose, scratchPos, plot, minX, maxX, minZ, maxZ, minGy,
                maxGy);
        int lxMin = Math.max(0, minX >> 4);
        int lxMax = Math.min(plot.getSizeChunksX() - 1, maxX >> 4);
        int lzMin = Math.max(0, minZ >> 4);
        int lzMax = Math.min(plot.getSizeChunksZ() - 1, maxZ >> 4);
        int lyMin = Math.max(0, minGy >> 4);
        int lyMax = Math.min(15, maxGy >> 4);
        for (int ly = lyMin; ly <= lyMax; ly++) {
            for (int lx = lxMin; lx <= lxMax; lx++) {
                for (int lz = lzMin; lz <= lzMax; lz++) {
                    Chunk chunk = plot.getLoadedChunk(plot.getOriginChunkX() + lx, plot.getOriginChunkZ() + lz);
                    if (chunk == null) continue;
                    collectSubchunk(blocks, scan, chunk.getBlockStorageArray()[ly], lx, ly, lz);
                }
            }
        }
        return blocks;
    }

    private static void collectSubchunk(List<PlotBlock> blocks, PlotScan scan, ExtendedBlockStorage storage,
                                        int lx, int ly, int lz) {
        if (storage == null || storage.isEmpty()) return;
        int gxMin = Math.max(scan.minX, lx << 4);
        int gxMax = Math.min(scan.maxX, (lx << 4) + 15);
        int gzMin = Math.max(scan.minZ, lz << 4);
        int gzMax = Math.min(scan.maxZ, (lz << 4) + 15);
        int gyMin = Math.max(scan.minGy, ly << 4);
        int gyMax = Math.max(scan.maxGy, (ly << 4) + 15);
        for (int gx = gxMin; gx <= gxMax; gx++) {
            for (int gy = gyMin; gy <= gyMax; gy++) {
                for (int gz = gzMin; gz <= gzMax; gz++) {
                    IBlockState state = storage.get(gx & 15, gy & 15, gz & 15);
                    if (state.getMaterial() == Material.AIR) continue;
                    addPlotBlock(blocks, state, scan, gx, gy, gz);
                }
            }
        }
    }

    private static void addPlotBlock(List<PlotBlock> blocks, IBlockState state, PlotScan scan, int gx, int gy,
                                     int gz) {
        boolean fullCube = state.isFullCube();
        double centerX = gx + 0.5;
        double centerY = gy - SubWorldPlot.RENDER_ORIGIN_Y + 0.5;
        double centerZ = gz + 0.5;
        Vec3d halfExtents = FULL_CUBE_HALF_EXTENTS;
        if (!fullCube) {
            scan.scratchPos.setPos(scan.originX + gx, gy, scan.originZ + gz);
            AxisAlignedBB shape = state.getCollisionBoundingBox(scan.world, scan.scratchPos);
            if (shape == null) return;
            centerX += (shape.minX + shape.maxX) * 0.5 - 0.5;
            centerY += (shape.minY + shape.maxY) * 0.5 - 0.5;
            centerZ += (shape.minZ + shape.maxZ) * 0.5 - 0.5;
            halfExtents = new Vec3d((shape.maxX - shape.minX) * 0.5, (shape.maxY - shape.minY) * 0.5,
                    (shape.maxZ - shape.minZ) * 0.5);
        }
        blocks.add(new PlotBlock(gx, gy - SubWorldPlot.RENDER_ORIGIN_Y, gz,
                scan.pose.bodyRelativeFromPlotLocal(new Vec3d(centerX, centerY, centerZ)), halfExtents, fullCube));
    }

    private static AxisAlignedBB boxAt(Entity entity, Vec3d pos) {
        Vec3d current = entity.getPositionVector();
        return entity.getEntityBoundingBox().offset(pos.x - current.x, pos.y - current.y, pos.z - current.z);
    }

    private static final class ObbFrame {

        private final Vec3d[] axes;
        private Vec3d[] separatingAxes;

        private ObbFrame(Quaternion rotation) {
            this.axes = new Vec3d[] { rotation.rotatePoint(AXIS_X), rotation.rotatePoint(AXIS_Y),
                    rotation.rotatePoint(AXIS_Z) };
        }

        private Vec3d[] separatingAxes() {
            if (separatingAxes == null) {
                Vec3d[] computed = new Vec3d[15];
                computed[0] = AXIS_X;
                computed[1] = AXIS_Y;
                computed[2] = AXIS_Z;
                int count = 3;
                for (Vec3d obbAxis : axes) {
                    computed[count++] = obbAxis;
                }
                for (Vec3d worldAxis : WORLD_AXES) {
                    for (Vec3d obbAxis : axes) {
                        Vec3d cross = worldAxis.crossProduct(obbAxis);
                        if (cross.lengthSquared() > 1.0e-6) {
                            computed[count++] = cross.normalize();
                        }
                    }
                }
                separatingAxes = count == computed.length ? computed : Arrays.copyOf(computed, count);
            }
            return separatingAxes;
        }
    }

    private static final class Obb {

        private final BodyBlocks entry;
        private final PlotBlock block;

        private Obb(BodyBlocks entry, PlotBlock block) {
            this.entry = entry;
            this.block = block;
        }

        private Vec3d center() {
            return entry.pose.worldFromBodyRelative(block.bodyRelativeCenter);
        }

        private Vec3d[] axes() {
            return entry.frame.axes;
        }

        private Vec3d[] separatingAxes() {
            return entry.frame.separatingAxes();
        }

        private double topY() {
            Vec3d[] axes = axes();
            return center().y + Math.abs(axes[0].y) * block.halfExtents.x +
                    Math.abs(axes[1].y) * block.halfExtents.y + Math.abs(axes[2].y) * block.halfExtents.z;
        }

        private double radiusAlong(Vec3d axis) {
            Vec3d[] axes = axes();
            return block.halfExtents.x * Math.abs(axis.dotProduct(axes[0])) +
                    block.halfExtents.y * Math.abs(axis.dotProduct(axes[1])) +
                    block.halfExtents.z * Math.abs(axis.dotProduct(axes[2]));
        }

        @Nullable private Vec3d mtv(AxisAlignedBB worldBox) {
            Vec3d relative = center().subtract(worldBox.getCenter());
            double bestOverlap = Double.POSITIVE_INFINITY;
            double bestSign = 1.0;
            Vec3d bestAxis = null;
            for (Vec3d axis : separatingAxes()) {
                double overlap = boxRadiusAlong(worldBox, axis) + radiusAlong(axis) -
                        Math.abs(relative.dotProduct(axis));
                if (overlap <= CONTACT_EPSILON) return null;
                if (overlap < bestOverlap) {
                    bestOverlap = overlap;
                    bestSign = relative.dotProduct(axis) > 0.0 ? -1.0 : 1.0;
                    bestAxis = axis;
                }
            }
            return bestAxis.scale(bestSign * bestOverlap);
        }

        private double sweepClip(AxisAlignedBB box, Vec3d direction, double distance) {
            Vec3d relative = center().subtract(box.getCenter());
            double lower = Double.NEGATIVE_INFINITY;
            double upper = Double.POSITIVE_INFINITY;
            for (Vec3d axis : separatingAxes()) {
                double travel = direction.dotProduct(axis);
                double radius = boxRadiusAlong(box, axis) + radiusAlong(axis);
                double offset = relative.dotProduct(axis);
                if (Math.abs(travel) < 1.0e-9) {
                    if (Math.abs(offset) > radius - CONTACT_EPSILON) return distance;
                    continue;
                }
                double first = (offset - radius) / travel;
                double second = (offset + radius) / travel;
                if (first > second) {
                    double swap = first;
                    first = second;
                    second = swap;
                }
                if (first > lower) lower = first;
                if (second < upper) upper = second;
                if (lower > upper) return distance;
            }
            if (upper <= 0.0 || lower > distance) return distance;
            return Math.max(0.0, lower - CONTACT_EPSILON);
        }
    }

    private static double boxRadiusAlong(AxisAlignedBB box, Vec3d axis) {
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        return halfX * Math.abs(axis.x) + halfY * Math.abs(axis.y) + halfZ * Math.abs(axis.z);
    }

    private static final class PlotBlock {

        private final int gx;
        private final int gy;
        private final int gz;
        private final Vec3d bodyRelativeCenter;
        private final Vec3d halfExtents;
        private final boolean fullCube;

        private PlotBlock(int gx, int gy, int gz, Vec3d bodyRelativeCenter, Vec3d halfExtents, boolean fullCube) {
            this.gx = gx;
            this.gy = gy;
            this.gz = gz;
            this.bodyRelativeCenter = bodyRelativeCenter;
            this.halfExtents = halfExtents;
            this.fullCube = fullCube;
        }
    }

    private static final class BodyBlocks {

        private final PhysicsWorldEntity body;
        private final List<PlotBlock> blocks;
        private final RigidPose current;
        private RigidPose pose;
        private RigidPose previousPose;
        private ObbFrame frame;

        private BodyBlocks(PhysicsWorldEntity body, List<PlotBlock> blocks) {
            this.body = body;
            this.blocks = blocks;
            this.current = body.currentPose();
        }

        private void beginStep(int step, int substeps) {
            double t = (double) step / substeps;
            pose = t >= 1.0 ? current : current.interpolated(body.prevPose(), t);
            double previousT = t - 1.0 / substeps;
            previousPose = previousT >= 1.0 ? current : current.interpolated(body.prevPose(), Math.max(0.0, previousT));
            frame = new ObbFrame(pose.rotation);
        }

        private Vec3d mapThroughPoseDelta(Vec3d worldPos) {
            return pose.worldFromBodyRelative(previousPose.bodyRelativeFromWorld(worldPos));
        }

        private Obb obb(PlotBlock block) {
            return new Obb(this, block);
        }

        private Vec3d contactNormal(PlotBlock block, AxisAlignedBB contactBox) {
            Vec3d mtv = obb(block).mtv(contactBox.grow(NORMAL_PROBE_INFLATION));
            return mtv == null ? AXIS_Y : mtv.normalize();
        }

        private List<PlotBlock> near(AxisAlignedBB worldBox) {
            return near(worldBox, 0.0);
        }

        private List<PlotBlock> near(AxisAlignedBB worldBox, double margin) {
            List<PlotBlock> result = new ArrayList<>();
            AxisAlignedBB query = pose.bodyRelativeBoxFromWorld(worldBox.grow(margin));
            for (PlotBlock block : blocks) {
                if (overlaps(query, block)) result.add(block);
            }
            return result;
        }

        private static boolean overlaps(AxisAlignedBB query, PlotBlock block) {
            Vec3d center = query.getCenter();
            return Math.abs(block.bodyRelativeCenter.x - center.x) <= (query.maxX - query.minX) * 0.5 +
                    block.halfExtents.x &&
                    Math.abs(block.bodyRelativeCenter.y - center.y) <= (query.maxY - query.minY) * 0.5 +
                            block.halfExtents.y &&
                    Math.abs(block.bodyRelativeCenter.z - center.z) <= (query.maxZ - query.minZ) * 0.5 +
                            block.halfExtents.z;
        }
    }

    private static final class Contact {

        private PhysicsWorldEntity groundBody;
        private Vec3d groundNormal;
        private Vec3d ceilingNormal;
        private Vec3d wallNormal;
        private boolean grounded;
    }

    private static final class StuckState {

        private long tick;
        private int previous;
        private int current;

        private int update(long now, boolean stuck) {
            if (tick != now) {
                previous = current;
                tick = now;
            }
            current = stuck ? previous + 1 : 0;
            return current;
        }
    }

    private static final class PlotScan {

        private final World world;
        private final RigidPose pose;
        private final BlockPos.MutableBlockPos scratchPos;
        private final int originX;
        private final int originZ;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int minGy;
        private final int maxGy;

        private PlotScan(World world, RigidPose pose, BlockPos.MutableBlockPos scratchPos, SubWorldPlot plot,
                         int minX, int maxX, int minZ, int maxZ, int minGy, int maxGy) {
            this.world = world;
            this.pose = pose;
            this.scratchPos = scratchPos;
            this.originX = plot.getOriginChunkX() << 4;
            this.originZ = plot.getOriginChunkZ() << 4;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.minGy = minGy;
            this.maxGy = maxGy;
        }
    }
}
