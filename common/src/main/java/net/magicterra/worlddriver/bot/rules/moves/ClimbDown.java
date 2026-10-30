package net.magicterra.worlddriver.bot.rules.moves;

import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;

/** Climb down a ladder/vine. */
public final class ClimbDown extends Move {
    public ClimbDown() {
        super(0, -1, 0, 15);
    }

    @Override
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        return (w.isClimbable(from) || w.isClimbable(to)) && (w.isClimbable(to) || w.canStandAt(to));
    }

    @Override
    public String name() {
        return "climbDown";
    }
}
