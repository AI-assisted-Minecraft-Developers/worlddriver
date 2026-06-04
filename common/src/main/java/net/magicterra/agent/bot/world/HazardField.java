package net.magicterra.agent.bot.world;

import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * A bounded grid of {@link HazardCell} around a center, computed purely from a
 * {@link WorldView} (server or client) + scalar inputs. No Minecraft client types,
 * so it is unit-testable headless via a synthetic WorldView or the server view.
 *
 * Coordinates: keyed by packed (dx,dz) offsets from center within [-radius,radius].
 * Vertical: for each (dx,dz) we find the standable foot near center.y by scanning a
 * small vertical band, then measure drop/water/contact at that foot.
 */
public final class HazardField {
    public final BlockPos center;
    public final int radius;
    public final int survivableFall;
    public final int deepWaterMax;
    private final Map<Long, HazardCell> cells;

    private HazardField(BlockPos center, int radius, int survivableFall, int deepWaterMax,
                        Map<Long, HazardCell> cells) {
        this.center = center;
        this.radius = radius;
        this.survivableFall = survivableFall;
        this.deepWaterMax = deepWaterMax;
        this.cells = cells;
    }

    public static long key(int dx, int dz) { return ((long) dx << 32) ^ (dz & 0xffffffffL); }

    public HazardCell at(int dx, int dz) {
        return cells.getOrDefault(key(dx, dz), HazardCell.unknown());
    }

    /** Max vertical band to search for a standable foot around center.y. */
    private static final int V_BAND = 4;
    /** How far down we probe for a drop before calling it "void/large". */
    private static final int DROP_PROBE = 24;

    public static HazardField compute(WorldView w, BlockPos center, int radius,
                                      int survivableFall, int deepWaterMax) {
        Map<Long, HazardCell> cells = new HashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                cells.put(key(dx, dz), cellAt(w, center, dx, dz, survivableFall, deepWaterMax));
            }
        }
        return new HazardField(center, radius, survivableFall, deepWaterMax, cells);
    }

    private static HazardCell cellAt(WorldView w, BlockPos center, int dx, int dz,
                                     int survivableFall, int deepWaterMax) {
        int cx = center.getX() + dx, cz = center.getZ() + dz;
        // find a standable foot near center.y within the band (prefer highest <= center.y+1)
        BlockPos foot = null;
        for (int dy = 1; dy >= -V_BAND; dy--) {
            BlockPos f = new BlockPos(cx, center.getY() + dy, cz);
            if (!w.isKnown(f)) return HazardCell.unknown();
            if (w.canStandAt(f)) { foot = f; break; }
        }
        if (foot == null) {
            // no standable footing in band -> treat as a wall (not standable, not lethal-to-flee
            // because you can't step there anyway)
            return new HazardCell(0, 0, false, false, false);
        }
        boolean contact = w.isHazard(foot) || w.isHazard(foot.above());
        // drop depth: air below the support until we hit solid/water
        int drop = 0;
        BlockPos below = foot.below();
        if (w.isWater(foot)) {
            // standing in water: measure water column depth downward
            int depth = 0;
            BlockPos p = foot;
            while (depth < DROP_PROBE && w.isKnown(p) && w.isWater(p)) { depth++; p = p.below(); }
            boolean lethalW = depth >= deepWaterMax;
            return new HazardCell(0, depth, contact, true, lethalW || contact);
        }
        // dry foot: count air gap under support (cliff)
        BlockPos p = below;
        while (drop < DROP_PROBE && w.isKnown(p) && w.isPassable(p) && !w.isWater(p)) { drop++; p = p.below(); }
        boolean lethal = contact || drop > survivableFall;
        return new HazardCell(drop, 0, contact, true, lethal);
    }

    /** Huge-but-finite avoidance cost for entering a lethal cell. Never Infinity (keeps A* feasible). */
    public double lethalPenalty(BlockPos foot) {
        int dx = foot.getX() - center.getX();
        int dz = foot.getZ() - center.getZ();
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return 0;
        return at(dx, dz).lethal() ? 10_000.0 : 0.0;
    }
}
