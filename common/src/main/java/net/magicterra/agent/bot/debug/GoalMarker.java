package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.Goal;
import net.minecraft.core.BlockPos;

/** Derive a representative goal cell for the chart marker. Open goals
 *  (RunAway/Inverted/StrictDirection/Axis) have no single point → null. */
final class GoalMarker {
    private GoalMarker() {}

    static BlockPos of(Goal g) {
        if (g instanceof Goal.Block b) return b.target();
        if (g instanceof Goal.Near n) return n.target();
        if (g instanceof Goal.GetToBlock gb) return gb.target();
        if (g instanceof Goal.TwoBlocks tb) return tb.target();
        if (g instanceof Goal.XZ xz) return new BlockPos(xz.x(), 0, xz.z());
        if (g instanceof Goal.Composite c && c.children().length > 0) return of(c.children()[0]);
        return null;
    }
}
