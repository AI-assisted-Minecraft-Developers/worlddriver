package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk off a 1-block ledge to a position 1 block lower. */
public final class StepDown extends Move {
    public StepDown(int dx, int dz) { super(dx, -1, dz, 10); }

    /** The body carries on a cell past the landing before it has dropped, its head still at the launch
     *  height: a block there at landing + 2 takes the head. R1 met a birch canopy right past its landings
     *  (1512,72,-251; 1503,71,-266; 1493,68,-287) in every run, ~0.3 s each. */
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!valid(w, from)) return null;
        return new Edge(apply(from), cost + headStrikeTax(w, apply(from), dx, dz),
                java.util.List.of(), java.util.List.of(), name());
    }

    /** {@link BotConfig#pathfinderHeadStrikeTax} when a block sits at landing + 2 in the cell past
     *  {@code to} along (dx, dz), or in either corner a diagonal sweeps; else 0. */
    static double headStrikeTax(WorldView w, BlockPos to, int dx, int dz) {
        double tax = BotConfig.pathfinderHeadStrikeTax;
        if (tax <= 0) return 0;
        BlockPos h = to.offset(0, 2, 0);
        boolean strike = !w.isPassable(h.offset(dx, 0, dz))
                || dx != 0 && dz != 0 && (!w.isPassable(h.offset(dx, 0, 0)) || !w.isPassable(h.offset(0, 0, dz)));
        return strike ? tax : 0;
    }

    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // A buoyant bot can't follow a descend INTO submerged water (water at the
        // destination foot AND head): it floats over the below-node and oscillates
        // swimUp↔descend instead of sinking. Mirror Fall/FallIntoWater's submerged-
        // landing gate so A* never routes the unexecutable dive (live z2744 slot
        // canyon: swimUp 918 / diagDown 200, totStuck 1154, no net progress). A
        // descend to the water SURFACE (head air) stays valid — the bot floats there.
        if (w.isWater(to) && w.isWater(to.offset(0, 1, 0))) return false;
        // Foot is solid 1 below; we step into the air block above.
        if (!w.canStandAt(to)) return false;
        BlockPos passthrough = from.offset(dx, 0, dz);
        return w.isPassable(passthrough) && !w.isHazard(passthrough)
            && w.isPassable(passthrough.offset(0, 1, 0));
    }
    public String name() { return "stepDown"; }
}
