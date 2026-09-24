package net.magicterra.worlddriver.bot.pathfinder.moves;

import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;

/** Walk + jump up one block. Foot+head must clear at both src head and dst head. */
public final class StepUp extends Move {
    public StepUp(int dx, int dz) { super(dx, 1, dz, 15); }
    public boolean valid(WorldView w, BlockPos from) {
        BlockPos to = apply(from);
        // A floating player cannot push off underwater, so no ascent starts from a source below the
        // surface or lands in water. But it CAN mount a +1 dry bank from the SURFACE cell: vanilla
        // boosts a swimming player that collides with a block (LivingEntity.travel, +0.3 up), and the
        // real client did exactly that in wd.clientFlushBankClimbOut. The old blanket refusal left A*
        // no way onto a flush bank except digging it, which the same scene measured at 100+ ticks.
        if (w.isFloatingWater(from) && !surfaceBankExit(w, from, to)) return false;
        // A submerged ascending step (destination still fully under water) is a buoyant
        // fiction the bot can only sink-churn against — forbid so A* can't dive to the
        // pool floor and climb a submerged bank face. See WorldView#isSubmergedAscent.
        if (w.isSubmergedAscent(from, to)) return false;
        if (!w.canStandAt(to)) return false;
        // Need air above current head (jump clearance, foot.y + 2).
        return w.isPassable(from.offset(0, 2, 0));
    }
    /** The bot floats in the top water cell and the step lands on dry ground with dry head room. */
    private static boolean surfaceBankExit(WorldView w, BlockPos from, BlockPos to) {
        return !w.isWater(from.above()) && !w.isWater(to) && !w.isWater(to.above());
    }
    public String name() { return "stepUp"; }
}
