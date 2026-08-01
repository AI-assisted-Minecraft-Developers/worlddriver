package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

import java.util.List;
import net.magicterra.worlddriver.bot.BotConfig;

/**
 * Dig straight down one block and drop into the hole — Baritone's
 * mine-down. The block under the feet must be breakable and the block
 * <em>two</em> below must be a safe solid floor to land on (so we don't
 * open a long fall or drop into lava). Cost = step + break time.
 */
public final class DownBreak extends Move {
    private static final int[][] HORIZ = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    public DownBreak() { super(0, -1, 0, 10); }
    @Override public boolean valid(WorldView w, BlockPos from) { return eval(w, from) != null; }
    @Override public Edge eval(WorldView w, BlockPos from) {
        if (!BotConfig.allowBreak) return null;
        BlockPos below = apply(from);                              // the cell we dig + drop into
        if (!w.isSolid(below)) return null;                        // air → Fall / StepDown handle it
        double c = w.breakCost(below, from);
        if (Double.isInfinite(c)) return null;
        BlockPos landFloor = below.offset(0, -1, 0);
        if (!w.isSolid(landFloor) || w.isHazard(landFloor) || w.isHazard(below)) return null;
        // Suffocation guard: a falling block (sand/gravel) above the head
        // cascades into the freshly opened shaft and buries the descending bot.
        if (w.isFallingBlock(from.offset(0, 1, 0))) return null;
        // Flood guard: opening a 1-wide shaft beside water lets it pour in and
        // drown the bot (digging below sea level next to the ocean was lethal).
        // Check both cells the bot occupies after dropping (below = feet,
        // from = head) for horizontally adjacent water.
        for (int[] h : HORIZ) {
            if (w.isWater(below.offset(h[0], 0, h[1]))) return null;
            if (w.isWater(from.offset(h[0], 0, h[1])))  return null;
        }
        return new Edge(below, cost + c, List.of(below), List.of(), name());
    }
    public String name() { return "downBreak"; }
}
