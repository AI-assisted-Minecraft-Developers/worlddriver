package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Swim straight up in water. */
public final class SwimUp extends Move {
    public SwimUp() { super(0, 1, 0, 25); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!w.isWater(from)) return false;
        BlockPos to = apply(from);
        if (w.isWater(to)) return true;              // still rising WITHIN water — always fine
        if (!w.isPassable(to)) return false;
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
