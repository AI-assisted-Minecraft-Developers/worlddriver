package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Swim straight up in water. */
public final class SwimUp extends Move {
    public SwimUp() { super(0, 1, 0, 25); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!w.isWater(from)) return false;
        BlockPos to = apply(from);
        if (w.isWater(to)) return true;              // still rising WITHIN water — always fine
        if (!w.isPassable(to)) return false;
        // KNOWN FICTION, kept on purpose: from a ONE-DEEP cell (solid floor under the water) no
        // swim lifts the feet a whole cell higher — a real client bobs to y+0.96 at best and never
        // grounds in `to`. Gating this on isFloatingWater(from) was tried on 2026-09-04 and made
        // the plan an in-place pillarUp instead, which the executor cannot walk in water: a wet
        // body cannot steer itself to within 0.45 of the node, so it stalled and repathed away
        // (wd.clientFlowingChannelPlaceOut went from 56 ticks to a failed leg). The fictional edge
        // sends the body to ram the bank, where the pillar takeover — a ground jump in low water,
        // a side foothold in deep — actually gets it out. Make the planner honest only together
        // with an in-place pillar the wet executor can perform.
        // Surfacing into AIR: a buoyant bot only gets a real foothold above the water if it
        // can step onto / build the first rung against an ADJACENT solid (a wall or bank).
        // Over an OPEN water column (nothing solid below or beside `to`) it just bobs back
        // down — canStandAt's water=floor fiction otherwise lets A* route the climb-out up a
        // mid-pool column the bot can never establish a foothold in (buoyantWallArena: A*
        // picked open-water z=60 over wall-adjacent z=61 → 587-tick waterline thrash).
        return Move.hasPlaceSupport(w, to);
    }
    public String name() { return "swimUp"; }
}
