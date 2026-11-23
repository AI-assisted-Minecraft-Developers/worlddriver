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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;

/**
 * How the regular walk moves while {@link RouteDig} holds the look to break or place the route's own blocks. The walk's
 * aim and drive cannot yet run without the look, so for now this is all it does on those ticks: stand still, with two
 * exceptions that come from what is being worked on.
 */
public final class StandToWork {
    /**
     * The pitch a swimmer rising into a bank's step looks up at. At 45 degrees it rose along the face in seven ticks,
     * and at 25 in eight, a hair either side of the shortest climb the course bench counts as one and not a knock; at
     * 15 it climbs the face in ten.
     */
    private static final float CRAWL_RISE_PITCH = -15f;

    /** The deepest sink {@link #sinkToStand} makes for room to stand: the bob's height and a little over. */
    private static final double SINK_TO_STAND_MAX = 0.8;

    /** How far short of the face a step dug under a ceiling treads; see {@link #faceGap}. */
    private static final double STEP_FACE_GAP = 0.15;

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
        // Only eyes under the surface slow the dig, and this sink lets go the tick they go under.
        if ((work.kind() == RouteDig.Kind.DIG || work.kind() == RouteDig.Kind.STEP) && sinkToStand(a, p, work)) {
            return TickStep.WALKING;
        }
        // The step is dug at the surface to the end; the sink into the swim waits for it to open. The server judges a
        // finished dig at its own rate of that moment, so one finished a tick after the eyes went under is a fifth too
        // slow and is undone. Sinking for the last few ticks of the dig lost that race when the body began the sink a
        // little low, and the dig went on at a twenty-fifth on the way to the pool floor, from where the swim could
        // not rise high enough along the face to be lifted onto the step.
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
                Keys.forward(a, !Digging.treadsShortOfFace(p, work.cell(), faceGap(work)));
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
     * A body come off its swim floating under a ceiling too low to stand in sinks until a standing body fits, holding
     * still otherwise. Left to bob there, its pose went between prone and crouched with every bob, and the server,
     * which settles the pose a tick behind and echoes it back, held a crouch while the body rose prone into the
     * ceiling: suffocation, 1 hp (dig-ashore benchmark course, third bank). Sinking changes the pose once, going down.
     */
    private static boolean sinkToStand(Body a, LivingEntity p, RouteDig.Work work) {
        // Like the floating climb-break: at the surface and digging at or above the feet, never on a dive's way down.
        if (!ControlConfig.navigatorSinkToStandBeforeBankDig
                || !p.isInWater()
                || p.onGround()
                || p.isUnderWater()
                || work.cell().getY() < p.getBlockY()) {
            return false;
        }
        AABB standing =
                p.getDimensions(Pose.STANDING).makeBoundingBox(p.position()).deflate(1.0E-7);
        // Only where a short sink makes the room: in a passage too low to stand in anywhere it would sink to the floor.
        if (p.level().noCollision(p, standing) || !p.level().noCollision(p, standing.move(0, -SINK_TO_STAND_MAX, 0))) {
            return false;
        }
        Keys.sneak(a, true);
        Keys.forward(a, !Digging.treadsShortOfFace(p, work.cell(), faceGap(work)));
        return true;
    }

    /**
     * How far short of the face a floating dig treads. A step dug under a ceiling closes in further: there the bob
     * cannot lift the body out of the water onto the face, and held back at the dig's usual gap, the swim into the
     * opened step met the face late in its rise and pressed it a third of a second, a knock rather than a climb.
     */
    private static double faceGap(RouteDig.Work work) {
        return ControlConfig.navigatorSinkToStandBeforeBankDig && work.kind() == RouteDig.Kind.STEP
                ? STEP_FACE_GAP
                : Digging.DIG_FACE_GAP_WHILE_DIGGING;
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
}
