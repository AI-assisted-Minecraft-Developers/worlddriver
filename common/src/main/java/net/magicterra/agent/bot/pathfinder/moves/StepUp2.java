package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Walk + jump up TWO blocks — only reachable by an entity whose jump apex clears
 * +2 (a strong horse, or Jump Boost). Gated on {@link WorldView#maxJumpUpBlocks()}
 * &ge; 2 so an on-foot player (apex ~1.25 → 1) never plans it. Needs the two-tall
 * destination clear ({@code canStandAt}) and head clearance through the whole jump
 * column (the body rises to {@code from + 3}). Costlier than a +1 {@link StepUp},
 * so A* still prefers a +1 staircase when one exists.
 */
public final class StepUp2 extends Move {
    public StepUp2(int dx, int dz) { super(dx, 2, dz, 24); }
    public boolean valid(WorldView w, BlockPos from) {
        if (w.maxJumpUpBlocks() < 2) return false;            // can't jump this high → not a legal move
        if (w.isFloatingWater(from)) return false;            // a floating bot can't jump up out of deep water
        BlockPos to = apply(from);
        if (w.isSubmergedAscent(from, to)) return false;      // submerged-face climb is a buoyant fiction (WorldView#isSubmergedAscent)
        if (!w.canStandAt(to)) return false;
        // Head clearance for a +2 jump: feet reach from+2, head reaches from+3.
        return w.isPassable(from.offset(0, 2, 0)) && w.isPassable(from.offset(0, 3, 0));
    }
    public String name() { return "stepUp2"; }
}
