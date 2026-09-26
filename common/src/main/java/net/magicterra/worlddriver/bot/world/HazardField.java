package net.magicterra.worlddriver.bot.world;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
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
        BlockPos body = new BlockPos(cx, center.getY(), cz);
        if (!w.isKnown(body)) return HazardCell.unknown();
        // Explicit foot/head hazard check: if lava/fire/etc occupies the bot's own level,
        // classify it immediately as contactDamage, not standable, lethal — before the
        // standable-foot scan (canStandAt excludes hazard cells so they'd otherwise read
        // as walls/drops and never set contactDamage=true).
        BlockPos head = new BlockPos(cx, center.getY() + 1, cz);
        if (w.isHazard(body) || w.isHazard(head)) {
            return new HazardCell(0, 0, true, false, true);
        }
        // Find where the bot would LAND stepping into this column: the highest standable
        // foot from center.y+1 down to center.y-DROP_PROBE. Searching from the bot's OWN
        // level downward is what makes a cliff register as a drop rather than as a wall.
        BlockPos foot = null;
        for (int dy = 1; dy >= -DROP_PROBE; dy--) {
            BlockPos f = new BlockPos(cx, center.getY() + dy, cz);
            if (!w.isKnown(f)) break;
            // A hazard cell (lava/fire) in the fall path BEFORE any standable ground means the
            // bot falls into it → lethal contact, regardless of how far the drop is. Without
            // this, a lava-FILLED pit reads as foot==null ("blocked column", not lethal): when
            // the pit's lava has flowed up to cover the only standable floor, canStandAt rejects
            // every cell and the lethal pit is silently dropped. (A single un-flowed lava block
            // still leaves the stone floor standable → lethal via drop — hence the flaky split.)
            if (w.isHazard(f)) {
                int d = Math.max(0, center.getY() - f.getY());
                return new HazardCell(d, 0, true, false, true);
            }
            if (w.canStandAt(f)) { foot = f; break; }
        }
        if (foot == null) {
            // blocked column or bottomless within probe: not a walkable step target.
            return new HazardCell(0, 0, false, false, false);
        }
        int drop = Math.max(0, center.getY() - foot.getY());
        boolean contact = w.isHazard(foot) || w.isHazard(foot.above());
        if (w.isWater(foot)) {
            int depth = 0;
            BlockPos p = foot;
            while (depth < DROP_PROBE && w.isKnown(p) && w.isWater(p)) { depth++; p = p.below(); }
            return new HazardCell(drop, depth, contact, true, contact || depth >= deepWaterMax);
        }
        boolean lethal = contact || drop > survivableFall;
        return new HazardCell(drop, 0, contact, true, lethal);
    }

    /** Huge-but-finite avoidance cost for entering a lethal cell. Never Infinity (keeps A* feasible).
     *  Deep water is the one lethal verdict the planner does not pay for
     *  ({@link BotConfig#pathfinderDeepWaterPriced}): its surface cell is a swim the water taxes
     *  price, and charging it 10 000 sent every crossing to the nearest rim. */
    public double lethalPenalty(BlockPos foot) {
        int dx = foot.getX() - center.getX();
        int dz = foot.getZ() - center.getZ();
        if (Math.abs(dx) > radius || Math.abs(dz) > radius) return 0;
        HazardCell c = at(dx, dz);
        if (!c.lethal()) return 0.0;
        if (BotConfig.pathfinderDeepWaterPriced && c.deepWaterDepth() > 0 && !c.contactDamage()) return 0.0;
        return 10_000.0;
    }
}
