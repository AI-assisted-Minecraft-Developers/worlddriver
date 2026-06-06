package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.bot.BotConfig;

/**
 * Parkour ASCEND -- Baritone {@code MovementParkour} with a +1 landing: a
 * sprint-jump across a 2-3 block cardinal gap that lands one block HIGHER
 * than the launch. (A 1-block ascend is just a {@link StepUp}; ascends
 * taller than 3 are not reachable by vanilla sprint-jump physics.) The
 * rising body sweeps a taller corridor than a flat parkour, so each gap
 * column must be clear 3 tall (foot, head, head+1) -- a ceiling at y+2
 * clips the jump apex. There must also be no stand-able floor at launch
 * level in the gap, or a cheaper Walk/StepUp chain would win. Distance-2 is
 * a reliable vanilla leap; distance-3 (clearing 3 while rising 1) is at the
 * physics edge, so it is gated behind
 * {@link net.magicterra.agent.bot.BotConfig#allowParkour4} like the other
 * marginal long leaps. Cost = flat-parkour + 5 (jump-up overhead, as in
 * {@link StepUp}). The {@code parkourAscend} name starts with "parkour" so
 * the Walker's existing parkour actuator (jump+sprint, yaw-snapped, aimed
 * at the destination) drives it unchanged.
 */
public final class ParkourAscend extends Move {
    private final int dist;
    public ParkourAscend(int dx, int dz, int dist) {
        super(dx * dist, 1, dz * dist, (dist == 2 ? 22 : 32) + 5);
        this.dist = dist;
    }
    @Override public boolean availableInSearch(WorldView w) { return dist < 3 || BotConfig.allowParkour4; }
    public boolean valid(WorldView w, BlockPos from) {
        if (dist >= 3 && !BotConfig.allowParkour4) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);                 // (sx*dist, +1, sz*dist)
        if (!w.canStandAt(to)) return false;
        // Launch jump clearance (head+1 at the lip).
        if (!w.isPassable(from.offset(0, 2, 0)) || w.isHazard(from.offset(0, 2, 0))) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        for (int i = 1; i < dist; i++) {
            // 3-tall clear corridor: the body rises from y to y+1 across the
            // gap, so foot/head/head+1 must all be open.
            for (int dyOff = 0; dyOff <= 2; dyOff++) {
                BlockPos c = from.offset(sx * i, dyOff, sz * i);
                if (!w.isPassable(c) || w.isHazard(c)) return false;
            }
            // Real gap at launch level (a mid floor -> cheaper Walk/StepUp).
            if (w.canStandAt(from.offset(sx * i, 0, sz * i))) return false;
        }
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkourAscend" + dist; }
}
