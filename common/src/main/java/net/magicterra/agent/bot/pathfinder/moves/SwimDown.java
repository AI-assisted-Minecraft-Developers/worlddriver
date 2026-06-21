package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Swim straight down in water. */
public final class SwimDown extends Move {
    public SwimDown() { super(0, -1, 0, 25); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!w.isWater(from) || !w.isWater(apply(from))) return false;
        // A buoyant bot can't controllably dive from the SURFACE — buoyancy floats
        // it back up, so A* must never route a surface float DOWN to set up a low
        // bank climb-out: the executor over-sinks past the target to the floor and
        // the climb never lands, so A* re-dives deeper each replan (live 2026-06-20
        // deep stone bank: swimDown plan ratcheted the bot y61→y32, then boxed +
        // futile-dug). A surface bot escapes by carving a WATERLINE staircase UP, it
        // never dives. Only CONTINUE a descent that is already submerged (head still
        // underwater, isSubmergedFoot).
        return w.isSubmergedFoot(from);
    }
    public String name() { return "swimDown"; }
}
