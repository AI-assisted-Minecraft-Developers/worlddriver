package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/**
 * Diagonal walk: both adjacent cardinals must be passable too, or the
 * player physically wedges on the corner. Cost 14 ≈ sqrt(2) * 10.
 */
public final class Diagonal extends Move {
    public Diagonal(int dx, int dz) { super(dx, 0, dz, 14); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        if (!w.canStandAt(to)) return false;
        BlockPos sideA = from.offset(dx, 0, 0);
        BlockPos sideB = from.offset(0, 0, dz);
        // BOTH corner columns must be clear. The bot walks a diagonal by aiming
        // straight at the destination centre (no active corner-navigation), so a
        // single solid corner snags its hitbox and it wedges in place forever
        // (observed: stuck bobbing against a sandstone corner, cur2 never closing).
        // Requiring both sides clear matches this class's doc intent and forces A*
        // to route a blocked corner as two cardinal walks through the open cell —
        // which the Walker executes cleanly. Costs at most one extra step per
        // corner; in exchange no diagonal ever wedges.
        // ...and at least one corner must have GROUND under it. Passable is the wedge test; it says
        // nothing about what the body walks over. A diagonal is executed by aiming straight at the
        // destination centre, so the hitbox crosses both corner columns — and on an island rim both
        // of them are open void. Rung 20 kept ending with the bot falling out of the world from cells like -15,60,36:
        // flat ground, no leap involved, a diagonal off the edge. One cornered floor is enough,
        // because the body then always has something under some part of it during the crossing.
        if (BotConfig.pathfinderForbidParkourOverTheVoid
                && Move.bottomless(w, sideA) && Move.bottomless(w, sideB)) return false;
        return clearColumn(w, sideA) && clearColumn(w, sideB);
    }
    public String name() { return "diag"; }
}
