package supersymmetry.api.phys;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import supersymmetry.api.SusyLog;
import supersymmetry.api.subworld.SubWorldPlot;

public final class BodySplitter {

    private static final int MAX_SPLIT_COMPONENTS = 8;
    private static final int[][] NEIGHBOR_OFFSETS = {
            { 1, 0, 0 },
            { -1, 0, 0 },
            { 0, 1, 0 },
            { 0, -1, 0 },
            { 0, 0, 1 },
            { 0, 0, -1 } };
    private static final Map<PhysicsWorldEntity, Long> LAST_CHECK_TICK = Collections
            .synchronizedMap(new WeakHashMap<>());
    private static final Set<PhysicsWorldEntity> PENDING_CHECKS = Collections
            .synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private BodySplitter() {}

    public static void onPlotBlockRemoved(PhysicsWorldEntity body) {
        if (body.getEntityWorld().isRemote || !body.hasPhysicsBody()) return;
        long tick = body.getEntityWorld().getTotalWorldTime();
        Long last = LAST_CHECK_TICK.get(body);
        if (last != null && last == tick) {
            PENDING_CHECKS.add(body);
            return;
        }
        LAST_CHECK_TICK.put(body, tick);
        splitIfDisconnected(body);
    }

    public static void runPendingCheck(PhysicsWorldEntity body) {
        if (!PENDING_CHECKS.remove(body)) return;
        LAST_CHECK_TICK.put(body, body.getEntityWorld().getTotalWorldTime());
        splitIfDisconnected(body);
    }

    private static void splitIfDisconnected(PhysicsWorldEntity body) {
        SubWorldPlot plot = body.getPlot();
        if (plot == null || !body.hasColliders()) return;
        Set<BlockPos> solid = collectSolidLocals(body.getEntityWorld(), plot);
        List<Component> components = findComponents(solid);
        if (components.size() <= 1) return;
        if (components.size() > MAX_SPLIT_COMPONENTS) {
            SusyLog.logger.warn(
                    "BodySplitter: body {} fell apart into {} components, above the cap of {}, keeping it as one body",
                    body.getEntityId(), components.size(), MAX_SPLIT_COMPONENTS);
            return;
        }
        components.sort(Comparator.comparingInt((Component component) -> component.blocks.size()).reversed());
        for (int i = 1; i < components.size(); i++) {
            Component component = components.get(i);
            PhysicsBodyFormation.createChildEntity(body, component.min(), component.max(), component.blocks);
        }
    }

    private static Set<BlockPos> collectSolidLocals(World world, SubWorldPlot plot) {
        Set<BlockPos> solid = new HashSet<>();
        for (Chunk chunk : plot.getLoadedChunks().values()) {
            ExtendedBlockStorage[] storages = chunk.getBlockStorageArray();
            int baseX = (chunk.x - plot.getOriginChunkX()) << 4;
            int baseZ = (chunk.z - plot.getOriginChunkZ()) << 4;
            for (int sy = 0; sy < storages.length; sy++) {
                ExtendedBlockStorage storage = storages[sy];
                if (storage == null || storage.isEmpty()) continue;
                int baseY = (sy << 4) - SubWorldPlot.RENDER_ORIGIN_Y;
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (!Rapier.hasBlockCollider(world, storage.get(x, y, z))) continue;
                            solid.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        return solid;
    }

    private static List<Component> findComponents(Set<BlockPos> solid) {
        List<Component> components = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        for (BlockPos start : solid) {
            if (!visited.add(start)) continue;
            Component component = new Component();
            Deque<BlockPos> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos pos = queue.poll();
                component.add(pos);
                for (int[] offset : NEIGHBOR_OFFSETS) {
                    BlockPos neighbor = pos.add(offset[0], offset[1], offset[2]);
                    if (solid.contains(neighbor) && visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            components.add(component);
        }
        return components;
    }

    private static final class Component {

        private final Set<BlockPos> blocks = new HashSet<>();
        private int minX = Integer.MAX_VALUE;
        private int minY = Integer.MAX_VALUE;
        private int minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE;
        private int maxY = Integer.MIN_VALUE;
        private int maxZ = Integer.MIN_VALUE;

        private void add(BlockPos pos) {
            blocks.add(pos);
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }

        private BlockPos min() {
            return new BlockPos(minX, minY, minZ);
        }

        private BlockPos max() {
            return new BlockPos(maxX, maxY, maxZ);
        }
    }
}
