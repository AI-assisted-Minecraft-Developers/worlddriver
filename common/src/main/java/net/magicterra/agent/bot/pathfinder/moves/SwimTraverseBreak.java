package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Break one cardinal cell at the SAME Y to escape water — the horizontal twin
 * of {@link SwimAshoreBreak}. From a water cell, mine a thin bank wall to reach
 * the cell beyond, which must already have a sound floor (solid OR more water —
 * we tunnel through the wall, we don't build ground). Lets a bot trapped behind
 * a 1-block lip at water level punch through to open water or a level shore.
 *
 * <p>Like {@link TraverseBreak} but gated on the water-escape break
 * ({@link WorldView#escapeBreakCost}, default ON) and only from a
 * {@link Move#waterEscapeContext}, so it never tunnels on dry land. Must break
 * at least one cell or a plain {@link Walk} is cheaper.
 */
public final class SwimTraverseBreak extends Move {
    public SwimTraverseBreak(int dx, int dz) { super(dx, 0, dz, 16); }  // swim ≈ 1.6× walk

    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!waterEscapeContext(w, from)) return null;
        BlockPos to = apply(from);
        BlockPos floor = to.offset(0, -1, 0);
        // Destination must have a sound floor: solid bank to walk onto, or water
        // to keep swimming into. (No placing — a void floor is rejected.)
        boolean floorOk = (w.isSolid(floor) && !w.isHazard(floor)) || w.isWater(floor);
        if (!floorOk) return null;
        BlockPos head = to.offset(0, 1, 0);
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        if (w.isSolid(to)) {
            double c = w.escapeBreakCost(to);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(to);
        } else if (!w.isPassable(to) || w.isHazard(to)) {
            return null;
        }
        if (w.isSolid(head)) {
            double c = w.escapeBreakCost(head);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(head);
        } else if (!w.isPassable(head) || w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) return null;                          // nothing blocked → Walk handles it
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    public String name() { return "swimTraverseBreak"; }
}
