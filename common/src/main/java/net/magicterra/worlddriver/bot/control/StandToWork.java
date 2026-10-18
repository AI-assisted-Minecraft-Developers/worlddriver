package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * How the regular walk moves while {@link RouteDig} holds the look to break or place the route's own blocks.
 * The walk's aim and drive cannot yet run without the look, so for now this is all it does on those ticks:
 * stand still, with two exceptions that come from what is being worked on.
 */
final class StandToWork {
    private StandToWork() {}

    static Walker.Step run(Walker wk, WalkerTickCtx cx, Body a, RouteDig.Work work) {
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        Walker.avatarForward(a, false);
        wk.avatarJump(a, false);
        p.setSprinting(false);
        if (work == null || work.cell() == null) return Walker.Step.WALKING;
        String move = work.move();
        if (work.kind() == RouteDig.Kind.DIG) {
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). An unconditional press drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the bot against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak = move != null
                    && (move.startsWith("swimAshore") || move.startsWith("swimTraverseBreak"));
            // Generic floating-pocket climb-break: the planner can route a *dry*
            // move (stairUpBreak / stepUp) through a flooded canyon pocket. Executed
            // while the bot floats (isInWater && !onGround) with the break cell at or
            // above the feet, the buoyant bot drifts off its foot cell and the edge
            // invalidates before the block breaks → it bobs forever hand-mining the
            // bank without escaping (live canyon water-pocket dig-loop at
            // (2358,62,1863), break0=(2358,63,1862); the break-exempt stuck counter
            // means neither the freeze-breaker nor the anti-stuck burst engages).
            // Anchor it like swimAshore: press INTO the aimed bank at the surface
            // (gated !isUnderWater so forward never triggers the prone-swim drown)
            // and jump to mount the freed cell. swimEscape moves keep their own
            // handling (excluded), descending/diving breaks (cell below the feet)
            // are excluded so this never fights a swimDown.
            boolean floatingPocket = !swimEscapeBreak && p.isInWater()
                    && !p.onGround() && !p.isUnderWater();
            boolean climbBreak = floatingPocket && work.cell().getY() >= foot.getY();
            if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                Walker.avatarForward(a, true);     // press into the aimed bank (surface only)
                if (climbBreak || move.startsWith("swimAshore"))
                    wk.avatarJump(a, true);   // rise to mount the +1 / out of the pocket
            }
        } else if (work.kind() == RouteDig.Kind.PLACE) {
            // Descending-place LIP ANCHOR (task#4, replay-0013): the place
            // cell is BELOW the foot — the bot stands at a lip bridging
            // DOWN. Standing still only zeroes the drive inputs; residual
            // walk momentum still slides the bot off the lip during the
            // aim ticks (live: x 89.81→89.28 over 9 ticks, fall, 74×
            // climb-back/repath wedge). Hold sneak while the support is
            // pending: vanilla's ledge-guard arrests the slide AT the
            // edge, the place lands, and the moment the cell is solid
            // the dig controller stops — the normal (sneak-free) walk
            // performs the planned step-down. Same-level places are untouched.
            if (ControlConfig.walkerBridgeDescentPlaceAnchor
                    && work.cell().getY() < foot.getY() && p.onGround()) {
                Walker.avatarSneak(a, true);
                p.setShiftKeyDown(true);
            }
        }
        return Walker.Step.WALKING;
    }
}
