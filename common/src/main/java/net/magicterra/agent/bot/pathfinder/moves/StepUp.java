package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk + jump up one block. Foot+head must clear at both src head and dst head. */
public final class StepUp extends Move {
    public StepUp(int dx, int dz) { super(dx, 1, dz, 15); }
    public boolean valid(WorldView w, BlockPos from) {
        // A floating bot can't jump up out of deep water onto a +1 bank (no floor to
        // push off) — only a flush walk-out or a dig-to-flush climb works. Forbid the
        // ascending step from a floating-water source so A* never plans the
        // unexecutable +1 climb the bot would only bob-stall against.
        if (w.isFloatingWater(from)) return false;
        BlockPos to = apply(from);
        // A submerged ascending step (destination still fully under water) is a buoyant
        // fiction the bot can only sink-churn against — forbid so A* can't dive to the
        // pool floor and climb a submerged bank face. See WorldView#isSubmergedAscent.
        if (w.isSubmergedAscent(from, to)) return false;
        if (!w.canStandAt(to)) return false;
        // Need air above current head (jump clearance, foot.y + 2).
        return w.isPassable(from.offset(0, 2, 0));
    }
    public String name() { return "stepUp"; }
}
