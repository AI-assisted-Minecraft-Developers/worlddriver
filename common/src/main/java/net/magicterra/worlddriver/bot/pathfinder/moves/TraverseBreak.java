package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;

/**
 * Walk one cardinal cell at the same Y, mining whatever solid blocks
 * obstruct the destination foot/head — Baritone's {@code MovementTraverse}
 * with a non-empty {@code toBreak}. The destination must already have a
 * solid, safe floor (we tunnel <em>through</em> walls, we don't build the
 * ground under them — {@link BridgePlace} does that). At least one cell
 * must actually need breaking, otherwise a plain {@link Walk} is cheaper
 * and A* should use it. Cost = walk + Σ break time.
 */
public final class TraverseBreak extends Move {
    public TraverseBreak(int dx, int dz) { super(dx, 0, dz, 10); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!BotConfig.allowBreak) return null;
        BlockPos to = apply(from);
        BlockPos floor = to.offset(0, -1, 0);
        if (!w.isSolid(floor) || w.isHazard(floor)) return null;   // no safe ground to land on
        double bc = 0;
        List<BlockPos> br = new ArrayList<>(2);
        BlockPos head = to.offset(0, 1, 0);
        // Destination foot cell.
        if (w.isSolid(to)) {
            double c = w.breakCost(to, from);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(to);
        } else if (!w.isPassable(to) || w.isHazard(to)) {
            return null;                                            // fluid / hazard we won't dig
        }
        // Destination head cell.
        if (w.isSolid(head)) {
            double c = w.breakCost(head, from);
            if (Double.isInfinite(c)) return null;
            bc += c; br.add(head);
        } else if (!w.isPassable(head) || w.isHazard(head)) {
            return null;
        }
        if (br.isEmpty()) return null;                              // nothing blocked → Walk handles it
        return new Edge(to, cost + bc, List.copyOf(br), List.of(), name());
    }
    public String name() { return "traverseBreak"; }
}
