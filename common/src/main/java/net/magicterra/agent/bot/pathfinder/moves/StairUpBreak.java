package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.agent.bot.BotConfig;

/**
 * Climb +1 cardinally on DRY land by mining whatever obstructs the step — the
 * dry-land twin of {@link SwimAshoreBreak}. The bot auto-steps onto the solid
 * block at {@code from+(dx,0,dz)} (a pit/shaft wall) after breaking the
 * destination foot {@code from+(dx,1,dz)} and head {@code from+(dx,2,dz)} cells
 * if they're solid. Chained, it carves a 1-wide STAIRCASE up the walls of a
 * pit and out — needing NO placed blocks.
 *
 * <p>This is the escape a block-less bot needs from its own 挖三填一 bunker: after
 * sealing it has no blocks to {@link PillarUp} out of the 1-wide vertical shaft,
 * and on sand/sandstone/badlands it can't replenish (sandstone drops nothing by
 * hand, sand falls). With no break-to-ascend move A* returned "no path" and the
 * bot was trapped. StairUpBreak digs stairs out of the surrounding terrain
 * regardless of drops.
 *
 * <p>Gated on {@link BotConfig#allowBreak} (like {@link TraverseBreak}) and must
 * break at least one cell — otherwise a plain {@link StepUp} is cheaper and A*
 * uses that, so dry walkable routes are untouched. Cost = step + Σ break time,
 * so A* only stairs-up when there's no cheaper exit (exactly the pit case).
 */
public final class StairUpBreak extends Move {
    /** Same base as {@link StepUp} (15); the added break cost keeps a plain
     *  StepUp strictly cheaper wherever the step isn't blocked. */
    public StairUpBreak(int dx, int dz) { super(dx, 1, dz, 15); }

    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!BotConfig.allowBreak) return null;
        BlockPos to = apply(from);                              // from + (dx, 1, dz)
        BlockPos floor = to.offset(0, -1, 0);                   // = from + (dx, 0, dz): the wall we step onto
        if (!w.isSolid(floor) || w.isHazard(floor)) return null;// need a solid step to stand on (we don't place)
        BlockPos head = to.offset(0, 1, 0);
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        // Destination foot cell — break it if it's a solid wall block.
        if (w.isSolid(to)) {
            double c = w.breakCost(to);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(to);
        } else if (!w.isPassable(to) || w.isHazard(to)) {
            return null;                                        // fluid / hazard we won't dig into
        }
        // Destination head cell — clearance for the 2-tall hitbox at the new level.
        if (w.isSolid(head)) {
            double c = w.breakCost(head);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(head);
        } else if (!w.isPassable(head) || w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) return null;                          // nothing blocked → StepUp is cheaper
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    public String name() { return "stairUpBreak"; }
}
