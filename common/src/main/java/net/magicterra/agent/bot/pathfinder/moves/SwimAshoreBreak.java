package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Climb +1 cardinally out of water onto a bank, mining whatever obstructs the
 * step — the "get ashore over a high bank" move. From a water cell, the bot
 * jumps onto the block at {@code from+(dx,0,dz)} (the bank face it climbs onto)
 * after breaking the destination foot {@code from+(dx,1,dz)} and head
 * {@code from+(dx,2,dz)} cells if they're solid. Chained, it digs a staircase
 * up and out of a flooded pit or a 2-3-high lake/ocean bank.
 *
 * <p>Distinct from {@link StepUp} (no breaking) and {@link TraverseBreak}
 * (same-Y, gated on {@code allowBreak}): this one is gated on the water-escape
 * break ({@link WorldView#escapeBreakCost}, default ON) and fires ONLY from a
 * {@link Move#waterEscapeContext}, so it never tunnels on dry land. It must
 * break at least one cell, otherwise a plain {@link StepUp} is cheaper and A*
 * uses that instead.
 */
public final class SwimAshoreBreak extends Move {
    /** Base a touch above StepUp's 15: swimming/climbing out of water is slower
     *  than a dry hop, so A* prefers a real walkable exit when one exists. */
    public SwimAshoreBreak(int dx, int dz) { super(dx, 1, dz, 22); }

    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!waterEscapeContext(w, from)) return null;          // only as a water escape
        if (w.isSubmergedFoot(from)) return null;               // must start within 1 of the surface to jump-mount (WorldView#isSubmergedFoot)
        BlockPos to = apply(from);                              // from + (dx, 1, dz)
        BlockPos floor = to.offset(0, -1, 0);                   // = from + (dx, 0, dz): the bank we land on
        if (!w.isSolid(floor) || w.isHazard(floor)) return null;// need a solid bank to stand on (we don't place)
        BlockPos head = to.offset(0, 1, 0);
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        // Destination foot cell — break it if it's a solid bank block.
        if (w.isSolid(to)) {
            double c = w.escapeBreakCost(to);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(to);
        } else if (!w.isPassable(to) || w.isHazard(to)) {
            return null;                                        // lava/hazard we won't dig into
        }
        // Destination head cell — clearance for the 2-tall hitbox at the new level.
        if (w.isSolid(head)) {
            double c = w.escapeBreakCost(head);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(head);
        } else if (!w.isPassable(head) || w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) return null;                          // nothing blocked → StepUp is cheaper
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    public String name() { return "swimAshore"; }
}
