package net.magicterra.worlddriver.bot.rules.constraints;

import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Break only inside the box {@code min}..{@code max}: prune any edge that plans a dig outside it. For a verb whose work
 * is a box, such as a clear: whatever lies around the box is someone's border, frame or floor, and the walk between two
 * cells of the box has no business digging through it. Like {@link NoBreak} it also closes the executor's own recovery
 * digs (see {@code Navigator.mayBreak}), since those choose their cell without asking.
 */
public record BreakWithin(BlockPos min, BlockPos max) implements Constraint {
    @Override
    public boolean allows(BlockPos from, BlockPos to, Move.Edge edge, Goal goal, WorldView world) {
        if (edge == null) {
            return true;
        }
        for (BlockPos b : edge.toBreak) {
            if (!inside(b)) {
                return false;
            }
        }
        return true;
    }

    /** Whether {@code b} lies inside the box. */
    public boolean inside(BlockPos b) {
        return b.getX() >= min.getX()
                && b.getX() <= max.getX()
                && b.getY() >= min.getY()
                && b.getY() <= max.getY()
                && b.getZ() >= min.getZ()
                && b.getZ() <= max.getZ();
    }
}
