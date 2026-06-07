package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;
import net.magicterra.agent.bot.BotConfig;

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

        java.util.List<BlockPos> toBreak = new java.util.ArrayList<>();
        double cost = Move.PILLAR_COST;

        // Own-column ceiling: the cell the rising head climbs into. Must be open
        // or breakable (then mined first so there's room to rise).
        if (w.isSolid(ceiling)) {
            if (!BotConfig.allowBreak) return null;
            double c = w.breakCost(ceiling);
            if (Double.isInfinite(c)) return null;
            toBreak.add(ceiling);
            cost += c;
        } else if (w.isHazard(ceiling)) {
            return null;
        }

        // AABB head-sweep: the player box is 0.6 wide, so when execution lands the
        // bot off-centre in its cell the rising HEAD also sweeps the cardinal
        // neighbours of the ceiling level. A breakable obstruction there — an
        // oak_leaves canopy gap is the canonical case — caps the jump below the
        // place height (head jams on the neighbour leaf at +2), so the pillar
        // never reaches `place.y + 1` and bobs forever. Pre-list such breakables
        // so the Walker clears a body-wide channel before jumping. A SOLID,
        // UNBREAKABLE neighbour is left alone — a centred body clears it, and
        // breaking the whole world to insure against drift would explode the
        // search. Gated on allowBreak (demo-safe movement breaks nothing) and
        // unreachable in headless GameTest (canPlace=false short-circuits above).
        if (BotConfig.allowBreak) {
            BlockPos[] sides = {
                ceiling.offset(1, 0, 0), ceiling.offset(-1, 0, 0),
                ceiling.offset(0, 0, 1), ceiling.offset(0, 0, -1),
            };
            for (BlockPos n : sides) {
                if (!w.isSolid(n)) continue;                  // open / plant-with-no-collision → no clip
                double c = w.breakCost(n);
                if (Double.isInfinite(c)) continue;           // solid wall we can't break → centred body clears it
                toBreak.add(n);
                cost += c;
            }
        }

        return new Edge(to, cost, toBreak, List.of(from), name());
    }
    public String name() { return "pillarUp"; }
    @Override public boolean placesBlock() { return true; }
}
