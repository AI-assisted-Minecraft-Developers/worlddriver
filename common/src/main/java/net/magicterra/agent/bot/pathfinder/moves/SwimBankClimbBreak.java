package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Break-climb +1 up a TALL bank that rises straight out of deep water — the
 * multi-block sibling of {@link SwimAshoreBreak}. Chained, it carves a 1-wide
 * staircase up a river/ocean cliff onto an ELEVATED plateau, the route a bot
 * needs to reach trees on high ground across a wide, deep river.
 *
 * <p>{@link SwimAshoreBreak}/{@link SwimTraverseBreak} only stay valid while the
 * bot is within ~2 vertical steps of the waterline ({@link Move#waterEscapeContext}
 * expires once the water leaves the 3×3 ring below the feet), so a bank taller
 * than +2 was unclimbable and A* returned "no path" to any elevated far shore.
 * This move uses {@link Move#bankClimbContext} instead: it stays in context as
 * long as water lies straight DOWN within {@link BotConfig#swimBankClimbMaxHeight}
 * blocks through a CONTINUOUS solid bank face — i.e. the bot is still climbing
 * the same cliff it left the water on. A dry-land route far from water reads
 * false and the move is pruned, so land pathing is unchanged.
 *
 * <p>Geometry/cost mirror {@link SwimAshoreBreak} exactly: land on the already
 * solid bank cell {@code from+(dx,0,dz)} after breaking the destination foot
 * {@code from+(dx,1,dz)} and head {@code from+(dx,2,dz)} if solid; gated on the
 * water-escape break ({@link WorldView#escapeBreakCost}, default ON); must break
 * ≥1 cell or a plain {@link StepUp} is cheaper. The name shares the {@code
 * swimAshore} prefix so the Walker's water-escape break actuator (press-into-bank
 * + jump-to-mount) drives it with no new executor branch.
 */
public final class SwimBankClimbBreak extends Move {
    /** Same base as {@link SwimAshoreBreak} (22): climbing out of water is slower
     *  than a dry hop, so A* prefers a real walkable exit when one exists. */
    public SwimBankClimbBreak(int dx, int dz) { super(dx, 1, dz, 22); }

    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!bankClimbContext(w, from, BotConfig.swimBankClimbMaxHeight)) return null;
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
            return null;                                        // fluid / hazard we won't dig into
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

    /** Shares the {@code swimAshore} prefix so the Walker's water-escape break
     *  actuator (press-into-bank + jump-to-mount) drives this with no new branch. */
    public String name() { return "swimAshoreClimb"; }
}
