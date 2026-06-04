package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Break the block directly ABOVE and rise into it — the vertical escape from a
 * capped underwater pocket (a dug shaft that flooded and sealed, or any water
 * column roofed by solid blocks where autoSwim can't reach air). From a water
 * cell, mine the ceiling cell {@code from+(0,1,0)} (and the one above it for
 * head clearance) so the bot can swim up toward the surface.
 *
 * <p>The horizontal twins {@link SwimAshoreBreak}/{@link SwimTraverseBreak} need
 * a solid bank to climb onto; a fully-capped pocket has none, so without this a
 * roofed bot drowns. Same gating: {@link WorldView#escapeBreakCost} (default-ON
 * {@code allowSwimEscapeBreak}, confined to the local escape bubble) and only
 * from a {@link Move#waterEscapeContext}. Must break ≥1 cell or {@link SwimUp}
 * handles it.
 */
public final class SwimUpBreak extends Move {
    public SwimUpBreak() { super(0, 1, 0, 24); }

    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }

    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!waterEscapeContext(w, from)) return null;
        if (!w.isWater(from)) return null;                      // must launch from water
        BlockPos to = apply(from);                              // ceiling cell we rise into
        BlockPos head = to.offset(0, 1, 0);                     // clearance above the new foot
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        if (w.isSolid(to)) {
            double c = w.escapeBreakCost(to);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(to);
        } else if (w.isHazard(to)) {
            return null;
        }
        if (w.isSolid(head)) {
            double c = w.escapeBreakCost(head);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(head);
        } else if (w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) return null;                          // ceiling already clear → SwimUp is cheaper
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }

    public String name() { return "swimUpBreak"; }
}
