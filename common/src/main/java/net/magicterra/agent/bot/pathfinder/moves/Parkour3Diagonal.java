package net.magicterra.agent.bot.pathfinder.moves;

import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.magicterra.agent.bot.BotConfig;

/**
 * 3-block 45° diagonal leap — gated behind the same
 * {@link net.magicterra.agent.bot.BotConfig#allowParkour4} switch as
 * {@link Parkour4} since this is an even longer reach (~4.24 blocks
 * horizontal) at the absolute edge of sprint-jump physics. Cost 47.
 * Body sweeps the entire 2×2 corner column at foot+head; no stand-able
 * cell along the diagonal interior or A* should pick a cheaper
 * walk-and-diagonal chain.
 */
public final class Parkour3Diagonal extends Move {
    public Parkour3Diagonal(int dx, int dz) { super(dx * 3, 0, dz * 3, 47); }
    public boolean valid(WorldView w, BlockPos from) {
        if (!BotConfig.allowParkour4) return false;
        if (!Move.hasRunway(w, from)) return false;
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        if (!w.isPassable(from.offset(0, 2, 0))) return false;
        int sx = Integer.signum(dx), sz = Integer.signum(dz);
        // Check every cell in the 2x2 trapezoidal sweep between launch
        // and landing: the diagonal interior cells (1,1) (1,2) (2,1)
        // (2,2) (2,3) (3,2) — but the corners-only check (cells the body
        // physically passes through) is the 3 sequential diagonals
        // (1,1), (2,2), (3,3-skip-it's-dest) plus their adjacent cardinals.
        // Cheap conservative check: every cell in the 3×3 block between
        // launch (excl) and dest (excl) must be air+passable.
        for (int i = 1; i <= 2; i++) {
            for (int j = 1; j <= 2; j++) {
                if (i + j > 3) continue; // skip cells past the dest line
                BlockPos mid = from.offset(sx * i, 0, sz * j);
                if (!w.isPassable(mid) || w.isHazard(mid)) return false;
                BlockPos midHead = mid.offset(0, 1, 0);
                if (!w.isPassable(midHead) || w.isHazard(midHead)) return false;
                if (w.canStandAt(mid)) return false;
            }
        }
        return w.isPassable(to.offset(0, 1, 0));
    }
    public String name() { return "parkour3d"; }
}
