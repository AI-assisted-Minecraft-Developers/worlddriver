package net.magicterra.worlddriver.bot.control;

import java.util.List;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.planning.PathTraceHolder;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.MoveCatalog;
import net.magicterra.worlddriver.bot.rules.moves.MediumMove;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.MobBody;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

/**
 * The walk of a body that moves freely through one medium: in water, a fish, a dolphin, a guardian, a
 * squid; in air, a parrot, a bee, a ghast, a phantom. It plans with that medium's {@link MediumMove}
 * strokes and follows the nodes in three dimensions, rising and sinking to each node's height as it goes.
 *
 * <p>A separate executor rather than more branches in the walker's phases, which are a player's walk:
 * they lift a swimming start to the surface, move goals onto standable ground, hop and shove a stalled
 * body sideways, and wait for a body to land before believing there is no path. A fish or a bird has
 * none of those situations. It keeps its own {@link Route} and its own result; the {@link Walker} that
 * owns it hands it every order and every tick while the body lives in a medium, and lends it the search
 * settings (profile, budget, escalation) so a process sees one walk either way.
 *
 * <p>A node is reached when the body's feet are in its cell, near its middle across: the cell a goal
 * reads. It repaths when the next node stops getting closer for {@link #STALL_TICKS} ticks, and fails
 * after {@link #MAX_STALLS} repaths that did not help, when the body is out of its medium for
 * {@link #OUT_TICKS}, or when the best the planner offers ends no nearer the goal than the body has
 * already been.
 */
final class MediumLocomotor implements Locomotor {

    /** A node's cell counts as reached with the body's middle this close to the cell's, on each horizontal axis. */
    private static final double REACH_XZ = 0.4;
    /** Vertical offset beyond which the body rises or sinks toward the node; under the feet's lift off the
     *  floor ({@link MediumMove#feetLift}), or a body resting at the edge of the band stays in the cell below. */
    private static final double VERTICAL_DEADBAND = 0.05;
    /** Horizontal distance under which a node straight above or below is moved to vertically alone. */
    private static final double VERTICAL_ONLY_XZ = 0.25;
    /** How far a swimmer's vertical speed coasts, in ticks of itself: the sum of 0.9^n. */
    private static final double SWIM_COAST_TICKS = 10;
    /** Ticks out of the medium before the walk fails: a leap or a bob above the surface falls back in. */
    private static final int OUT_TICKS = 40;
    /** Ticks without getting closer to the next node before a repath. */
    private static final int STALL_TICKS = 60;
    /** Repaths with no node reached in between before the walk fails. */
    private static final int MAX_STALLS = 3;

    private final Walker wk;
    private final MediumMove.Medium medium;
    /** "swim" or "fly": the drive tag, the trace's first word, and how failures name the medium. */
    private final String verb, through;
    private final Route route = new Route();
    private Goal goal;
    private volatile WalkResult result = WalkResult.NONE;
    private List<BlockPos> followed;
    private double bestNodeDist = Double.POSITIVE_INFINITY;
    private int noProgressTicks, stalls, outTicks;
    /** The nearest to the goal, by its estimate, that this walk has been when it planned. */
    private double bestGoalDist = Double.POSITIVE_INFINITY;
    private Goal walkGoal;

    private MediumLocomotor(Walker wk, MediumMove.Medium medium) {
        this.wk = wk;
        this.medium = medium;
        boolean air = medium == MediumMove.Medium.AIR;
        verb = air ? "fly" : "swim";
        through = air ? "through the air" : "through water";
    }

    /** The locomotor for the body {@code world} plans for: {@code current} when it is for that body's
     *  medium, a new one heading for {@code goal} when the medium changed, null for a body that walks. */
    static MediumLocomotor forBody(Walker wk, MediumLocomotor current, WorldView world, Goal goal) {
        MediumMove.Medium m = MoveCatalog.medium(world.moveModes());
        if (m == null) return null;
        if (current != null && current.medium == m) return current;
        MediumLocomotor fresh = new MediumLocomotor(wk, m);
        fresh.start(goal);
        return fresh;
    }

    @Override public void start(Goal g) {
        goal = g;
        result = WalkResult.NONE;
        route.restart();
    }

    /** Nothing about a swim or a flight carries over from one goal to the next, so a re-aim is a start. */
    @Override public void retarget(Goal g) { start(g); }

    @Override public void replan() { route.clear(); }

    /** The profile is the owning walker's; it has already switched to it. */
    @Override public void replan(net.magicterra.worlddriver.bot.rules.SearchProfile profile) { replan(); }

    @Override public void cancel(String reason) {
        PathTraceHolder.SINK.onTerminal(PathTrace.Outcome.CANCELLED, reason);
        start(null);
    }

    @Override public WalkResult result() { return result; }
    @Override public int pathLen() { return route.path() == null ? 0 : route.path().size(); }
    @Override public int pathStep() { return route.step(); }

    @Override public Status tick(Body a, WorldView world) {
        if (goal == null) {
            result = new WalkResult(Status.FAILED, "no goal: the walk was never started, or was cancelled",
                    "failed:no goal", false, -1, false);
            return Status.FAILED;
        }
        Walker.Step s = step(a, world);
        return s == Walker.Step.WALKING ? Status.MOVING : result.status();
    }

    private Walker.Step step(Body a, WorldView world) {
        LivingEntity p = a.entity();
        BlockPos foot = p.blockPosition();
        if (goal != walkGoal) { reset(); walkGoal = goal; }
        if (goal.reached(foot)) return end(a, Walker.Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, "arrived", foot);
        outTicks = inMedium(p) ? 0 : outTicks + 1;
        if (outTicks > OUT_TICKS) return fail(a, medium == MediumMove.Medium.AIR ? "fell into water" : "out of water", foot);

        if (route.path() == null || route.path() != followed) {
            Walker.Step s = plan(a, world, goal, foot);
            if (s != null) return s;
        }

        while (route.step() < route.path().size() && reached(p, route.path().get(route.step()))) {
            route.advance();
            bestNodeDist = Double.POSITIVE_INFINITY;
            noProgressTicks = 0;
            stalls = 0;
        }
        if (route.step() >= route.path().size()) return segmentEnd(a, goal, foot);

        BlockPos node = route.path().get(route.step());
        double feetAim = node.getY() + MediumMove.feetLift(p.getBbHeight());
        double tx = node.getX() + 0.5 - p.getX(), ty = feetAim - p.getY(), tz = node.getZ() + 0.5 - p.getZ();
        double dist = Math.sqrt(tx * tx + ty * ty + tz * tz);
        // Only reaching a node clears the repath count: the first reading after a repath always looks closer.
        if (dist < bestNodeDist - 0.05) { bestNodeDist = dist; noProgressTicks = 0; }
        else if (++noProgressTicks >= STALL_TICKS) {
            if (++stalls > MAX_STALLS) return fail(a, "stuck: no closer to node " + node.toShortString(), foot);
            route.drop("no closer to node " + node.toShortString());
            hold(a);
            return Walker.Step.WALKING;
        }
        String vertical = steer(a, p, tx, ty, tz);
        wk.driveTag = verb;
        wk.tel.lastTickTrace = String.format(java.util.Locale.ROOT,
                "%s step=%d/%d node=%s pos=%.2f,%.2f,%.2f vy=%.3f %s out=%d stalled=%d stalls=%d bestEffort=%b",
                verb, route.step(), route.path().size(), node.toShortString(), p.getX(), p.getY(), p.getZ(), p.getDeltaMovement().y,
                vertical, outTicks, noProgressTicks, stalls, route.seg.pathBestEffort);
        return Walker.Step.WALKING;
    }

    private boolean inMedium(LivingEntity p) {
        return medium == MediumMove.Medium.AIR ? !p.isInWater() && !p.isInLava() : p.isInWater();
    }

    /** Run or start the search; null once a path is adopted, else the step to return this tick. */
    private Walker.Step plan(Body a, WorldView world, Goal goal, BlockPos foot) {
        if (route.seg.activeSearch == null) route.searchFromFoot(wk.newPathFinder(world).newSearch(foot, goal), false);
        if (!route.seg.activeSearch.advance(ControlConfig.pathfinderSliceMs)) {
            hold(a);
            wk.driveTag = verb + ":search";
            return Walker.Step.WALKING;
        }
        PathFinder.Result r = route.takeSearchResult();
        if (r.path().size() < 2) {
            String why = r.goalReached() ? "no path: the goal is where the body already is" : "no path " + through;
            return fail(a, why, foot);
        }
        bestGoalDist = Math.min(bestGoalDist, goal.estimate(foot));
        // A best-effort path ends wherever the planner liked best, which need not be nearer: from the
        // closest spot it offers one a few cells off, and following those trades places forever.
        if (!r.goalReached() && goal.estimate(r.path().get(r.path().size() - 1)) >= bestGoalDist - 1e-6)
            return fail(a, "no route closer to the goal " + through, foot);
        route.install(r.path(), r.edges(), 1, !r.goalReached());
        followed = route.path();
        bestNodeDist = Double.POSITIVE_INFINITY;
        noProgressTicks = 0;
        return null;
    }

    /** The path is spent: arrived, or a best-effort segment ended and the next one is planned from here. */
    private Walker.Step segmentEnd(Body a, Goal goal, BlockPos foot) {
        if (!route.seg.pathBestEffort) {
            String why = WalkVerdict.classifyArrival(false, goal.reached(foot), false);
            return end(a, Walker.Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, why, foot);
        }
        route.drop("best-effort segment spent");
        hold(a);
        return Walker.Step.WALKING;
    }

    /**
     * Face the node and move at it: forward along the heading, rising or sinking to its height. The
     * vertical offset is read against where the current climb or sink coasts to: a swimmer's vertical
     * speed decays by a tenth a tick, so rising until level with the node carries a fish past the
     * surface, and a flyer brakes over {@link MobBody#FLIGHT_COAST_TICKS}.
     */
    private String steer(Body a, LivingEntity p, double tx, double ty, double tz) {
        double xz = Math.hypot(tx, tz);
        if (xz > 1e-3) p.setYRot((float) Math.toDegrees(Math.atan2(-tx, tz)));
        float forward = xz < VERTICAL_ONLY_XZ ? 0f : (float) Math.min(1.0, xz * 2);
        // A flyer's velocity left after its move has gravity and drag in it, which its next tick discards;
        // the move itself is what its climb or sink is.
        double lead = medium == MediumMove.Medium.AIR
                ? ty - MobBody.FLIGHT_COAST_TICKS * (p.getY() - p.yo)
                : ty - SWIM_COAST_TICKS * p.getDeltaMovement().y;
        boolean up = lead > VERTICAL_DEADBAND, down = lead < -VERTICAL_DEADBAND;
        a.commandMove(0f, forward);
        a.commandJump(up);
        a.commandSneak(down);
        return String.format(java.util.Locale.ROOT, "fwd=%.2f %s lead=%.2f", forward, up ? "up" : down ? "down" : "level", lead);
    }

    private static boolean reached(LivingEntity p, BlockPos node) {
        return Math.abs(p.getX() - (node.getX() + 0.5)) < REACH_XZ
                && Math.abs(p.getZ() - (node.getZ() + 0.5)) < REACH_XZ
                && p.getBlockY() == node.getY();
    }

    private Walker.Step fail(Body a, String error, BlockPos foot) {
        return end(a, Walker.Step.FAILED, PathTrace.Outcome.STUCK, error, "failed:" + error, foot);
    }

    private Walker.Step end(Body a, Walker.Step s, PathTrace.Outcome o, String reason, String endReason, BlockPos foot) {
        hold(a);
        reset();
        walkGoal = null;
        result = WalkVerdict.of(s == Walker.Step.FAILED, reason, goal.reached(foot), endReason, goal.estimate(foot),
                false, goal.open());
        PathTraceHolder.SINK.onTerminal(o, reason);
        return s;
    }

    private void reset() {
        route.abortSearch();
        followed = null;
        stalls = 0;
        outTicks = 0;
        bestGoalDist = Double.POSITIVE_INFINITY;
    }

    private static void hold(Body a) {
        a.commandMove(0f, 0f);
        a.commandJump(false);
        a.commandSneak(false);
    }
}
