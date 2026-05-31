package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.model.BlockPos;

import java.util.List;

/**
 * Pillar up one block — Baritone's {@code MovementPillar}: place a block at
 * the current feet position while jumping over it, then land on top a block
 * higher. The block under the feet must be solid (the support we click) and
 * the head cell two above must be clear, or breakable (then mined first so
 * there's room to rise). Lets A* <em>gain</em> height to reach a goal above,
 * the vertical counterpart to {@link BridgePlace}. Executed by the Walker's
 * jump-place actuator, which owns the airborne timing.
 */
public final class PillarUp extends Move {
    public PillarUp() { super(0, 1, 0, 10); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!w.canPlace()) return null;
        // No world-solidity check on the support below: every standing node
        // A* reaches already has a real-or-placed solid block beneath it
        // (canStandAt guarantees it for walked nodes; a preceding PillarUp
        // placed it for chained ones). Re-checking the static world here
        // would reject pillar #2+ — their support is the block #1 just
        // placed, which the immutable WorldView still sees as air. The
        // place cell `from` must be open to build into (always true — it's
        // the bot's own feet).
        if (!w.isPassable(from) || w.isHazard(from)) return null;
        BlockPos to = apply(from);                                     // new feet = from + 1
        if (!w.isPassable(to) || w.isHazard(to)) return null;          // current head cell — must be open
        BlockPos ceiling = from.offset(0, 2, 0);                       // head room after rising (= to + 1)
        if (w.isSolid(ceiling)) {
            if (!net.magicterra.agent.bot.BotConfig.allowBreak) return null;
            double c = w.breakCost(ceiling);
            if (Double.isInfinite(c)) return null;
            return new Edge(to, Move.PILLAR_COST + c, List.of(ceiling), List.of(from), name());
        }
        if (w.isHazard(ceiling)) return null;
        return new Edge(to, Move.PILLAR_COST, List.of(), List.of(from), name());
    }
    public String name() { return "pillarUp"; }
}
