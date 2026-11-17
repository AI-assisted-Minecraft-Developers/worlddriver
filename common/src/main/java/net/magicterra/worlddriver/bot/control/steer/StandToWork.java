package net.magicterra.worlddriver.bot.control.steer;

import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.control.controller.Digging;
import net.magicterra.worlddriver.bot.control.controller.RouteDig;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * How the regular walk moves while {@link RouteDig} holds the look to break or place the route's own blocks. The walk's
 * aim and drive cannot yet run without the look, so for now this is all it does on those ticks: stand still, with two
 * exceptions that come from what is being worked on.
 */
public final class StandToWork {
    /**
     * Ticks before the step opens that a body treading at the surface starts to sink. Sneaking from a level tread, the
     * eyes went under nine ticks on (the dig-ashore benchmark course, under the ceiling at 1048,151,1200), and a step
     * still unopened then is dug at a fifth of the speed again.
     */
    private static final int CRAWL_SINK_TICKS = 7;

    /**
     * The pitch a swimmer rising into a bank's step looks up at. At 45 degrees it rose along the face in seven ticks,
     * and at 25 in eight, a hair either side of the shortest climb the course bench counts as one and not a knock; at
     * 15 it climbs the face in ten.
     */
    private static final float CRAWL_RISE_PITCH = -15f;

    private StandToWork() {}

    /** Runs one tick of the stand-still behaviour while {@code work} is being carried out. */
    public static TickStep run(Navigator nav, TickCtx cx, Body a, RouteDig.Work work) {
        LivingEntity p = cx.frame.player;
        final BlockPos foot = cx.frame.foot;
        Keys.forward(a, false);
        nav.keys.jump(a, false);
        p.setSprinting(false);
        if (work == null || work.cell() == null) {
            return TickStep.WALKING;
        }
        String move = work.move();
        if (work.kind() == RouteDig.Kind.CRAWL) {
            crawl(nav, a, p, work.cell());
            return TickStep.WALKING;
        }
        if (work.kind() == RouteDig.Kind.STEP && stepAlmostOpen(nav, p, work.cell())) {
            // Sinking takes about as long as the last of the dig: the eyes go under as the step opens.
            Keys.sneak(a, true);
            return TickStep.WALKING;
        }
        if (work.kind() == RouteDig.Kind.DIG || work.kind() == RouteDig.Kind.STEP) {
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). An unconditional press drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the bot against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak =
                    move != null && (move.startsWith("swimAshore") || move.startsWith("swimTraverseBreak"));
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
            boolean floatingPocket = !swimEscapeBreak && p.isInWater() && !p.onGround() && !p.isUnderWater();
            boolean climbBreak = floatingPocket && work.cell().getY() >= foot.getY();
            if ((swimEscapeBreak && p.isInWater() && !p.isUnderWater()) || climbBreak) {
                // press into the aimed bank (surface only)
                Keys.forward(a, !Digging.treadsShortOfFace(p, work.cell(), Digging.DIG_FACE_GAP_WHILE_DIGGING));
                if (climbBreak || move.startsWith("swimAshore")) {
                    nav.keys.jump(a, true); // rise to mount the +1 / out of the pocket
                }
            }
        } else if (work.kind() == RouteDig.Kind.PLACE
                && ControlConfig.navigatorBridgeDescentPlaceAnchor
                && work.cell().getY() < foot.getY()
                && p.onGround()) {
            Keys.sneak(a, true);
            p.setShiftKeyDown(true);
        }
        return TickStep.WALKING;
    }

    /**
     * Into the one-high step of a bank, from the water: sink until the eyes are under, where vanilla lets a sprint
     * start the prone swim, then swim at the step with the jump held, so the swim rises to the surface and the press on
     * the face lifts it in.
     */
    private static void crawl(Navigator nav, Body a, LivingEntity p, BlockPos cell) {
        double dx = cell.getX() + 0.5 - p.getX();
        double dz = cell.getZ() + 0.5 - p.getZ();
        boolean swimming = p.isSwimming();
        // The sprint that keeps the swim needs the forward key, so while the sink still carries the body down it swims
        // away from the face: met sinking, the face was a knock before the rise along it began.
        float away = swimming && p.getDeltaMovement().y < 0 ? 180f : 0f;
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)) + away);
        // A swim rises toward where it looks; level, only the held jump lifted it, a tenth of a block a tick.
        p.setXRot(swimming ? CRAWL_RISE_PITCH : 0f);
        boolean under = p.isUnderWater();
        Keys.sneak(a, !swimming && !under);
        p.setSprinting(swimming || under);
        Keys.forward(a, swimming || under);
        nav.keys.jump(a, swimming);
    }

    /**
     * Whether the step being dug from the water opens within {@link #CRAWL_SINK_TICKS} at this tick's rate. Without a
     * progress reading (a body that reports none), never.
     */
    private static boolean stepAlmostOpen(Navigator nav, LivingEntity p, BlockPos cell) {
        float prog = nav.blocks.destroyProgress();
        if (prog < 0 || !(p instanceof Player player)) {
            return false;
        }
        float rate = player.level().getBlockState(cell).getDestroyProgress(player, player.level(), cell);
        if (player.isEyeInFluid(FluidTags.WATER)) {
            // Read at the treading rate: once the sink has the eyes under, the step is still the one to sink for.
            rate *= 5f;
        }
        return rate > 0 && (1f - prog) / rate <= CRAWL_SINK_TICKS;
    }
}
