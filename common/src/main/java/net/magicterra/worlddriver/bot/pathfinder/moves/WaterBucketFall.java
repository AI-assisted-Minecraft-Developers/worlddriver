package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Fall further than the no-water cap (3 blocks) and break the fall with a
 * water bucket placed on the landing block — Baritone's MLG. Only emitted
 * when the world view reports a water bucket is available
 * ({@link WorldView#canWaterBucketFall}) and the drop is within the live
 * {@link WorldView#maxWaterBucketFall} cap. The water placement / scoop is
 * an actuator concern (keyed off the {@code fallBucket*} move name), so this
 * is a pure-movement edge with no {@code toBreak}/{@code toPlace}.
 */
public final class WaterBucketFall extends Move {
    private final int drop;
    public WaterBucketFall(int dx, int dz, int drop) {
        super(dx, -drop, dz, Move.WATER_BUCKET_COST + Move.WATER_FALL_PER_BLOCK * drop);
        this.drop = drop;
    }
    @Override public boolean availableInSearch(WorldView w) {
        // No bucket in hand (or this drop exceeds the live cap) → can't fire anywhere
        // this search. Drops all ~68 WaterBucketFall variants for a bucketless bot.
        return w.canWaterBucketFall() && drop <= w.maxWaterBucketFall();
    }
    public boolean valid(WorldView w, BlockPos from) {
        if (!w.canWaterBucketFall() || drop > w.maxWaterBucketFall()) return false;
        BlockPos to = apply(from);
        // The water source is placed IN the landing cell against the floor's
        // top face, so the floor must be a full, non-waterloggable solid cube
        // (not air, not a hazard, not a trapdoor/slab/end-rod — those steal or
        // can't host the water and the fall isn't broken). The landing cell
        // itself must currently be empty — if it's already water a plain
        // Fall/SwimDown handles the descent.
        BlockPos floor = to.offset(0, -1, 0);
        if (!w.isMlgFloor(floor) || w.isHazard(floor)) return false;
        if (w.isWater(to)) return false;
        if (!w.isPassable(to) || w.isHazard(to)) return false;
        BlockPos head = to.offset(0, 1, 0);
        if (!w.isPassable(head) || w.isHazard(head)) return false;
        // Clear falling column (foot AND head) from the launch lip down to
        // the landing — an overhang anywhere would wedge the falling body.
        for (int dyOff = 0; dyOff > -drop; dyOff--) {
            BlockPos f = from.offset(dx, dyOff, dz);
            if (!w.isPassable(f) || w.isHazard(f)) return false;
            BlockPos h = from.offset(dx, dyOff + 1, dz);
            if (!w.isPassable(h) || w.isHazard(h)) return false;
        }
        // Head clearance at the launch position (step off the ledge).
        return w.isPassable(from.offset(0, 1, 0));
    }
    public String name() { return "fallBucket" + drop; }
}
