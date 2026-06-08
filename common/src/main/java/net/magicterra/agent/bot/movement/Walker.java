package net.magicterra.agent.bot.movement;

import net.magicterra.agent.bot.BotConfig;
import net.magicterra.agent.bot.Goal;
import net.magicterra.agent.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.agent.bot.pathfinder.Move;
import net.magicterra.agent.bot.pathfinder.PathFinder;
import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.magicterra.agent.bot.pathfinder.PathTraceHolder;
import net.magicterra.agent.bot.pathfinder.WorldView;
import net.magicterra.agent.bot.world.SurvivalMath;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

import static net.magicterra.agent.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.agent.bot.movement.PathSmoothing.*;
import static net.magicterra.agent.bot.util.BotInteract.*;
import static net.magicterra.agent.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.agent.AgentDriverCommon.LOG;

public final class Walker {
    private static final int CARROT_MAX_NODES = 10;
    private static final double CARROT_DIST = 4.0;
    /** Horizontal aim vector (blocks²) below which the heading is HELD instead of
     *  recomputed from atan2. During a vertical maneuver (stepUp/stepDown/pillar/
     *  fall) the bot sits almost directly over its target column, so adx/adz hover
     *  near zero and atan2 on that sub-block noise snaps the yaw between +90 and -90
     *  every tick — a 180° forward/backward thrash that also stalls forward thrust.
     *  A half-block (0.5) dead-zone keeps the last good heading until the bot is far
     *  enough from the column for the bearing to be meaningful again. Far larger than
     *  the old 1e-4 epsilon (which only caught standing-exactly-on-the-point). */
    private static final double YAW_DEADZONE_SQ = 0.25;
    /** Hard per-tick cap (degrees) on how far the commanded body yaw may turn during
     *  normal ground walking — independent of the cosmetic {@code smoothLook}. A single
     *  degenerate aim vector (reCentre pointing back at the previous node, atan2 on a
     *  near-zero vector, a fall's transient carrot) can otherwise snap the heading 180°
     *  in one tick — the "朝向反复跳变" the pathfinding charts surfaced. Capping the slew
     *  turns any such transient into a small wobble the next (correct) tick undoes, so
     *  the body holds its forward line; a stuck/reCentre limit-cycle can no longer spin
     *  it, so forward progress resumes and the bot escapes the stall. 30°/tick = 600°/s,
     *  far faster than any real turn needs, so legitimate corners are unaffected.
     *  Launches (parkour / MLG fall) bypass this and snap — you can't steer mid-air. */
    private static final float WALKER_MAX_YAW_SLEW_DEG = 30f;
    /** EMA factor for low-passing the TARGET heading. A near-45° goal makes A* emit a
     *  grid STAIRCASE whose immediate-waypoint bearing alternates ±~30° around the true
     *  diagonal every step; chasing that raw target (even clamped) leaves a bounded
     *  sawtooth. Averaging the target with this factor collapses the alternation to a
     *  steady bearing (a 20°/50° square wave settles to ~35° ±5° at 0.5) while still
     *  converging on a genuine turn in a few ticks. Launches bypass it (must snap). */
    private static final float YAW_SMOOTH_ALPHA = 0.5f;
    /** Heading tolerance for committing to a +1 step climb. A step is climbed by
     *  walking INTO the riser then jumping ONTO it, so the body must already FACE
     *  the step — if it arrived off the climb column or after a sharp path turn the
     *  bearing can be 90-150° off, and since smoothLook only turns
     *  {@link #WALKER_MAX_YAW_SLEW_DEG}°/tick the forward key would RAM the riser
     *  face (horizontalCollision, hSpd≈0) and the jump fire the wrong way for the
     *  several ticks it pivots — the bot bob-jams in place (measured: 36% of a
     *  jungle-hill climb was collision-stalled, sprint effective only 1% of ticks).
     *  While the step is still mis-aimed beyond this tolerance we PIVOT in place
     *  (cut forward + jump) so the body turns cleanly to face the step, then climbs. */
    private static final float STEPUP_AIM_TOLERANCE_DEG = 40f;
    public enum Step { WALKING, ARRIVED, FAILED }
    private static final double REACH_DIST_SQ = 0.45;
    private static final int STUCK_TICKS = 60;
    /** Jitter-immune wedge timer: max ticks the bot may dwell on the SAME path step
     *  before forcing a re-path (and blacklisting that node). Unlike {@link #stuckTicks}
     *  (progress-based — a bob/creep that finds a fractionally-closer approach each tick
     *  keeps resetting it to ≈0), this counts RAW ticks-on-step, so it catches a node the
     *  Walker physically cannot complete: e.g. a fallN whose drop is blocked by ground
     *  (the bot sits at the node's XZ with |Δy| stuck above the 1.2 reach gate, micro-
     *  jittering at hSpd≈0.03 so stuckTicks≈0). Such a node otherwise deadlocks FOREVER —
     *  a best-effort path takes no periodic repath, and stuckTicks≈0 fires no safety
     *  repath. Generous (5 s) so genuinely slow legit moves (water creep, pillar climb)
     *  finish well within it; bridge edges are excluded (they hard-zero progress timers). */
    private static final int WEDGE_TICKS = 100;
    /** Ticks an in-place pillar-up recovery is latched once armed — long enough for a
     *  jump's airborne arc to crest and place a support (vanilla peak ~tick 6-8), short
     *  enough that it re-evaluates promptly. Re-armed each grounded tick while the bot is
     *  still below the next node beyond jump reach (see overJump). */
    private static final int PILLAR_RECOVER_TICKS = 14;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the camera
     *  heading is FROZEN. A failed water climb-out makes every repath return a
     *  swim-back/circle best-effort; following each one U-turns the bot and the
     *  repeated U-turns wind the camera (the water "转圈"). Past this count the heading
     *  is held steady (see the heading block) — MC movement follows body yaw, so a
     *  frozen heading also steadies the bot pressing toward the climb-out instead of
     *  whipping around. Small so the spin is killed within ~1-2s of churn. */
    private static final int CHURN_REPATH_CAP = 3;
    /** Anti-spin: consecutive in-water repaths with no goal-progress before the goto
     *  gives up best-effort (ARRIVED) rather than pressing a wall forever. Well above
     *  {@link #CHURN_REPATH_CAP} so the freeze gets a fair chance to let the bot grind
     *  through a hard climb-out before we conclude it's truly walled. */
    private static final int CHURN_GIVEUP_CAP = 12;
    /** Bounded fresh re-searches at a loaded-chunk frontier before giving up (the
     *  bot is stationary while waiting, so a couple of tries is plenty — see
     *  {@link #frontierHoldOrArrive}). */
    private static final int FRONTIER_WAIT_CAP = 3;
    /** Min reduction in distance² (blocks²) to the current node that counts as real
     *  forward progress for the {@link #stuckTicks} no-progress timer. Set above the
     *  sub-0.1 b/tick position jitter of a treading / water-creeping bot but below a
     *  normal sprint step, so slow-but-steady advance (esp. ~0.08 b/tick in water)
     *  keeps resetting the timer and never trips the reCentre / wiggle recovery. */
    private static final double STUCK_PROGRESS_EPS = 0.02;
    /** Per-step ticks of bob-stalling before the water climb-out actuator places
     *  a foothold to ground a floating bot against a too-high bank. Keyed off the
     *  per-step no-progress timer ({@code totalTicks}, which a bob can't reset —
     *  unlike {@code stuckTicks}, which the oscillating foot-Y clears each cycle).
     *  Well under {@code walkerTotalTickBudget}, comfortably past a normal flush /
     *  staircase climb-out (grounds in <10 ticks, never stalls). */
    private static final int WATER_CLIMB_STALL = 30;
    /** Ticks after the jump press before placing the pillar block beneath —
     *  by then the player has cleared the old feet cell (matches TowerProcess). */
    private static final int PILLAR_PLACE_DELAY = 3;
    // Water-bucket (MLG) fall handling lives in the always-on {@link
    // ClutchController} (BotApiImpl#CLUTCH), so an unplanned fall self-rescues
    // whether or not a Walker is driving. The Walker only ARMS a planned fall
    // (CLUTCH.armPlanned) as it steps off a fallBucket lip and biases the
    // step-off keys (walk-speed, no sprint) so the body drops near-vertically.
    // Other tuning lives in BotConfig (mutable via mc.bot.setting).

    /** Snapshot of the most recent {@code findPath()} result across ALL
     *  Walkers (volatile so cross-thread reads in {@code mc.bot.status} are
     *  consistent). Surfaced under {@code status.lastPath} for debugging
     *  pathing failures — Baritone exposes the same via {@code path}/{@code stats}. */
    public record PathStats(int expanded, long ms, boolean goalReached, double finalCost, int pathLen) {}
    public static volatile PathStats lastStats;

    private Goal goal;
    private List<BlockPos> path;
    private List<Move.Edge> edges;   // aligned with path; edges.get(i) enters path.get(i)
    private int step;
    private int ticksSinceRepath;
    private int stuckTicks;
    private int totalTicks;
    private int actionTicks;          // ticks spent on the current break/place edge
    private int pillarStep = -1;      // path index of the pillar edge in progress
    private int pillarSinceJump = -1; // ticks since the pillar jump press (-1 = grounded)
    private int waterClimbStall;      // ticks bob-stalled (no NET height gain) climbing out of water
    private double waterClimbBestY = Double.NEGATIVE_INFINITY; // best Y this water-climb; a real rise resets the stall
    private int diveLatch;            // ticks left forcing a dive-under-cap (set on a blocked submerged descent; holds the dive through the sink so it doesn't flip-flop)
    private int pillarRecoverLatch;   // ticks left driving an in-place pillar-up recovery (bot fell below the climb path beyond jump reach) — latched across the jump's airborne phase so a place can land
    private BlockPos pillarRecoverCell; // the (grounded) feet cell the recovery is filling this rung
    private boolean descending;       // ending creative flight; wait to land before pathing
    private PathFinder.Search activeSearch;  // in-flight time-sliced A* (null = none)
    private double bestDistToGoal = Double.POSITIVE_INFINITY;
    private double bestGoalDist = Double.POSITIVE_INFINITY;  // anti-spin: best goal-estimate across repaths (5-block margin ignores micro-lunges)
    private int repathsNoProgress;                           // anti-spin: consecutive in-water repaths that didn't improve bestGoalDist
    private double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
    private int stuckStep = -1;                             // path index bestStepDist tracks; a step change starts a fresh progress window
    private int noStepProgressTicks;                        // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
    private int noProgressStep = -1;                        // path index noStepProgressTicks tracks (independent of bridge/progress resets)
    private boolean searchSuppressedPlace;                  // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
    private float smoothTargetYaw = Float.NaN;              // EMA-low-passed target heading (NaN = uninitialised; resync on launch/new goal)
    private boolean pathBestEffort;                         // current path is a best-effort partial (goal NOT reached) → commit to it before re-searching
    private BlockPos commitEnd;                             // last node of the current best-effort segment (null for a full path) → where continuation searches launch from
    private boolean searchFromEnd;                          // activeSearch is a continuation launched from commitEnd (deferred splice) vs a foot-search (splice immediately)
    private int frontierWaitTicks;                          // bounded retries re-searching at a loaded-chunk frontier before giving up (pathfinderFrontierCommit)
    private PathFinder.Result pendingSegment;              // a finished continuation segment awaiting splice at the current segment's end
    private int dbgPrevStep = -1;     // walkerDebug: detect step changes for per-step timing
    private int dbgTicksOnStep = 0;   // walkerDebug: ticks spent on the current step
    public String lastError;

    public void setGoal(Goal g) {
        this.goal = g;
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.totalTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.waterClimbStall = 0;
        this.diveLatch = 0;
        this.pillarRecoverLatch = 0;
        this.waterClimbBestY = Double.NEGATIVE_INFINITY;
        this.descending = false;
        this.activeSearch = null;
        this.bestDistToGoal = Double.POSITIVE_INFINITY;
        this.bestGoalDist = Double.POSITIVE_INFINITY;
        this.repathsNoProgress = 0;
        this.bestStepDist = Double.POSITIVE_INFINITY;
        this.stuckStep = -1;
        this.noStepProgressTicks = 0;
        this.noProgressStep = -1;
        this.smoothTargetYaw = Float.NaN;
        this.commitEnd = null;
        this.searchFromEnd = false;
        this.pendingSegment = null;
        this.lastError = null;
    }

    /** Drop the cached path (keep the goal) so the next {@link #tick} recomputes
     *  from the current position. Used when a movement process is resumed after
     *  being preempted by a higher-priority chain — during the suspension the bot
     *  may have been knocked back or the terrain changed, so reusing the stale
     *  path would walk into a wall. See ProcessScheduler / UserTaskChain.onResume. */
    public void forceRepath() {
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.actionTicks = 0;
        this.pillarStep = -1;
        this.pillarSinceJump = -1;
        this.activeSearch = null;
        this.bestStepDist = Double.POSITIVE_INFINITY;
        this.stuckStep = -1;
        this.noStepProgressTicks = 0;
        this.noProgressStep = -1;
        this.smoothTargetYaw = Float.NaN;
        this.commitEnd = null;
        this.searchFromEnd = false;
        this.pendingSegment = null;
        // Clear the water-climb-out / descent state too (setGoal resets these): a
        // resume mid water-climb otherwise carries a stale waterClimbBestY/Stall, so
        // the next tick either fires a spurious foothold-place takeover (stall already
        // past threshold) or suppresses a legitimate stall (stale-high best-Y).
        this.waterClimbStall = 0;
        this.diveLatch = 0;
        this.pillarRecoverLatch = 0;
        this.waterClimbBestY = Double.NEGATIVE_INFINITY;
        this.descending = false;
    }

    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }

    // Jump / sneak actuators — drive the player's OWN AgentInput (Input.jumping /
    // Input.shiftKeyDown) instead of the SHARED global keybinds mc.options.keyJump /
    // keyShift, so the Walker never fights a human's space/shift. Per-tick (see
    // AgentInput): tick() sets a default below, branches override. p.input is always
    // an AgentInput here (installed at the top of tick()); the guard keeps it safe if
    // a respawn swapped a fresh KeyboardInput in between.
    private static void agentJump(Avatar a, boolean v) { a.commandJump(v); }
    private static void agentSneak(Avatar a, boolean v) { a.commandSneak(v); }
    /** Raw forward (keyUp equivalent) for the special branches that drive the impulse
     *  themselves (the main walk path uses commandMove). v=false also zeroes strafe. */
    private static void agentForward(Avatar a, boolean v) { a.commandForward(v ? 1f : 0f); }

    /** Client bridge: existing callers pass {@link Minecraft}; wrap it in a
     *  {@link ClientPlayerAvatar} (1:1 passthrough). The decoupled core is
     *  {@link #tick(Avatar, WorldView)}, which the server path calls directly. */
    public Step tick(Minecraft mc, WorldView world) {
        return tick(new ClientPlayerAvatar(mc), world);
    }

    public Step tick(Avatar a, WorldView world) {
        Player p = a.player();
        if (p == null) { lastError = "player vanished"; return terminal(Step.FAILED, PathTrace.Outcome.ERROR, lastError); }
        // AgentInput install (client) is handled inside the Avatar implementation.

        // Per-tick baseline for the jump/sneak channel: default to "not jumping / not
        // sneaking" so any path that returns without setting them can't leak a stale
        // value — branches below override as needed. (jump only matters on the ground,
        // so a default-false on an airborne tick is a no-op; see AgentInput.)
        agentJump(a, false);
        agentSneak(a, false);

        // Ground pathfinder: end creative flight so the player descends and
        // the walk/jump actuator (which relies on gravity + onGround) works.
        // While flying the player floats above the ground path, overshoots
        // waypoints frictionlessly, and spins in place re-aiming — then
        // times out hovering. Re-applied each tick so re-toggling flight
        // mid-path can't strand the bot.
        if (p.getAbilities().flying) {
            p.getAbilities().flying = false;
            p.onUpdateAbilities();
            if (!descending && BotConfig.walkerDebug)
                LOG.info(
                        "[walker] creative flight detected → disabling, will descend (y={})", p.getY());
            descending = true;
        }
        // Let the post-flight free-fall settle before we path — A* from a
        // mid-air foot finds no neighbours and reports "no path". Scoped to
        // this one-shot descent so the legitimately-airborne ticks of
        // jump / parkour / fall moves are untouched.
        if (descending) {
            if (!p.onGround() && !world.isWater(new BlockPos(
                    (int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ())))) {
                agentForward(a, false);
                agentJump(a, false);
                p.setSprinting(false);
                if (BotConfig.walkerDebug)
                    LOG.info(
                            "[walker] descending… y={} onGround={}", p.getY(), p.onGround());
                return Step.WALKING;
            }
            descending = false;
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] landed at y={} → resume ground pathing", p.getY());
        }

        // Water-bucket (MLG) fall execution is owned by the always-on
        // CLUTCH (see BotApiImpl#clientTick): once armed — planned, via
        // CLUTCH.armPlanned below as we step off a fallBucket lip, OR
        // reactively, on any unplanned damaging fall — the clutch runs at the
        // top of clientTick, takes the keys for the whole descent, and returns
        // early so this Walker is suspended until the bot has landed and
        // scooped. So there's nothing to do here; the walk dispatch below only
        // ARMS the planned case and biases the step-off keys.

        BlockPos foot = new BlockPos((int) Math.floor(p.getX()), (int) Math.floor(p.getY()), (int) Math.floor(p.getZ()));
        sampleTick(p);
        if (goal.reached(foot)) {
            // While mid-pillar-jump the floored feet-Y can tick into the goal
            // cell at the apex before we've placed the block to stand on —
            // declaring ARRIVED there makes the bot abort the last placement
            // and fall back a block. Wait until grounded so it ends up
            // actually standing in the goal cell. Scoped to pillar edges so
            // swim/parkour arrivals (legitimately airborne) are unaffected.
            // A parkour-place leap can put the floored feet-Y into the goal
            // cell mid-arc — like the pillar case — before we've landed on the
            // placed block and braked the overshoot. Hold ARRIVED until grounded
            // so the settle phase can stop the bot ON the block, not past it.
            // A parkour-DESCEND leap likewise puts the floored feet-Y into the
            // lower goal cell mid-arc while still airborne and carrying forward
            // momentum; declaring ARRIVED there skips the landing brake and the
            // bot overshoots the 1-wide block into the void. Hold until grounded.
            Move.Edge ce = edgeAt(step);
            boolean midAirEdge = ce != null && ce.move != null && !p.onGround()
                    && ("pillarUp".equals(ce.move) || ce.move.startsWith("parkourPlace")
                        || ce.move.startsWith("parkourDescend"));
            if (!midAirEdge) return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
        }

        // Hard tick budget: prevents infinite walking when A* returns a partial path
        // for an unreachable goal (best-effort fallback path > 1 node satisfies ok()).
        // Reset on real progress (closer to goal) so legitimate long walks aren't killed.
        double d = goal.estimate(foot);
        if (d < bestDistToGoal - 0.5) {
            bestDistToGoal = d;
            totalTicks = 0;
        } else if (++totalTicks > BotConfig.walkerTotalTickBudget) {
            lastError = "no progress for " + BotConfig.walkerTotalTickBudget + " ticks (best dist=" + Math.round(bestDistToGoal) + ")";
            return terminal(Step.FAILED, PathTrace.Outcome.STUCK, lastError);
        }

        // Re-searching toward a FAR goal mid-segment returns a DIVERGENT best-effort
        // path that can point backward, so the bot zig-zags/backtracks. Baritone-style
        // segment commitment avoids that by committing to each best-effort segment and
        // only splicing the next one (computed from the segment's END) once the bot is
        // standing there. Two kinds of re-search, kept distinct so this stays
        // backtrack-free:
        //   • SAFETY / FULL — re-search from the CURRENT foot and splice the
        //     result immediately. Fires when we have no path, we're stuck, we've
        //     been knocked off the path, or (for a path that actually reaches the
        //     goal) on the periodic refresh interval.
        //   • CONTINUATION — for a committed best-effort partial: the moment a
        //     segment is adopted, pre-compute the NEXT segment in the background
        //     starting from THIS segment's END (commitEnd), so the search overlaps
        //     the walk and the result is ready to splice the instant we arrive.
        //     Adoption is DEFERRED to the segment end (see below) so the new path
        //     always starts where the bot will be — no backward yaw flip.
        boolean offPath = path != null && step < path.size() && path.get(step).distSqr(foot) > 9;
        boolean wedged = noStepProgressTicks > WEDGE_TICKS;   // jitter-immune: stuck on a node the Walker can't complete (e.g. a ground-blocked fallN)
        // Fell off the committed climb path VERTICALLY (the current node is more than a jump
        // above/below the feet — a fumbled pillar/parkour dropped the bot below the route, or
        // it slid down). A CONTINUATION search (launched from commitEnd, not the foot) can
        // only return a path from where the bot ISN'T, so letting it finish just wastes ~6s of
        // bobbing toward a stale far carrot (the #3 far-climb-target stall). Preempt it so the
        // foot-search below restarts from the actual position NOW. Only cancels a continuation;
        // an in-flight foot-search is already from the right place and is left to finish.
        boolean fellOffPath = path != null && step < path.size()
                && Math.abs(path.get(step).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2;
        if (fellOffPath && activeSearch != null && searchFromEnd) {
            activeSearch = null;
            searchFromEnd = false;
        }
        boolean safetyRepath = (path == null) || (stuckTicks > STUCK_TICKS) || offPath || wedged;
        boolean fullPeriodic = !pathBestEffort && path != null
                && ticksSinceRepath > BotConfig.walkerRepathEveryTicks;
        if ((safetyRepath || fullPeriodic) && activeSearch == null) {
            // Stuck too long on a move the Walker can't execute (a steep stepUp it
            // slides off, a pillar it can't ground)? Blacklist that node so this
            // re-search routes AROUND the wedge instead of re-planning into it —
            // otherwise A* keeps returning the same unclimbable spot and the bot
            // bobs there until an unrelated repath happens to diverge (a 600+-tick
            // stall observed on a steep mountain). Soft + decaying, so a sole route
            // is still taken eventually.
            if ((stuckTicks > STUCK_TICKS || wedged) && path != null && step < path.size()) {
                world.penalizeStuckNode(path.get(step));
            }
            activeSearch = new PathFinder(world).newSearch(foot, goal);
            searchFromEnd = false;
            searchSuppressedPlace = false;    // normal search: placing allowed; budget re-checked on result
            pendingSegment = null;            // a foot-search supersedes any stashed continuation
            ticksSinceRepath = 0;
        } else if (pathBestEffort && commitEnd != null
                && activeSearch == null && pendingSegment == null) {
            // Eagerly precompute the next best-effort segment from the committed end.
            activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
            searchFromEnd = true;
            searchSuppressedPlace = false;
            ticksSinceRepath = 0;
        }
        // Advance any in-flight search by one tick-slice so a big search never
        // blocks the render thread in a single tick (fixes the stutter).
        boolean searchDone = false;
        if (activeSearch != null) {
            long sb = System.nanoTime();
            searchDone = activeSearch.advance(BotConfig.pathfinderSliceMs);
            if (BotConfig.walkerDebug && !searchDone)
                LOG.info(
                        "[walker] search slice {}ms expanded={} (still running)",
                        (System.nanoTime() - sb) / 1_000_000, activeSearch.expanded());
        }
        if (searchDone) {
            PathFinder.Result res = activeSearch.result();
            activeSearch = null;
            boolean wasFromEnd = searchFromEnd;
            searchFromEnd = false;
            lastStats = new PathStats(res.expanded(), res.ms(), res.goalReached(),
                    res.finalCost(), res.path().size());
            PathTraceHolder.SINK.onSearchResult(res.path(), res.edges(), res.goalReached(),
                    res.expanded(), res.ms(), res.finalCost());
            if (BotConfig.walkerDebug)
                LOG.info(
                        "[walker] repath from {} → goalReached={} pathLen={} expanded={} ms={}",
                        wasFromEnd ? commitEnd : foot, res.goalReached(), res.path().size(), res.expanded(), res.ms());
            if (wasFromEnd && path != null && step < path.size()) {
                // Continuation finished while we're still walking the current
                // segment — stash it and splice only once we reach the segment end
                // (see the step>=size handler), so its start coincides with the bot.
                // Stash even an empty (no-path) result: the segment-end handler
                // reads that as "no further progress" and ends cleanly, rather than
                // letting the kickoff re-launch the same boxed-in search forever.
                pendingSegment = res;
            } else if (wasFromEnd && !res.hasPath()) {
                // Already at/over the segment end and no onward route. With frontier
                // planning this may be a STALE continuation (computed before we
                // arrived & loaded the chunks beyond) — re-search fresh before giving
                // up; otherwise we've gone as far as the best effort allows.
                return frontierHoldOrArrive(a, world, p);
            } else if (res.hasPath()) {
                // ANTI-SPIN (water repath-churn): a failed water climb-out (bot can't
                // mount the bank) makes every repath return a best-effort that swims
                // back / circles without the bot's ACTUAL position getting any closer
                // to the goal; following each one U-turns the bot and the repeated
                // U-turns wind the camera (the water "转圈"). This is a TEMPORAL signal
                // (no net progress across repaths), not a single-path property — the
                // churn segment can even END on dry land (the unreachable climb-out
                // target). Track the bot's best goal-distance: when several consecutive
                // repaths IN WATER fail to improve it, end best-effort instead of
                // churning. A real journey keeps improving (counter stays 0); land is
                // exempt (go-arounds may step away from the goal there).
                // (d = goal.estimate(foot), computed above for the hard tick budget)
                // Count consecutive in-water repaths that don't get the bot closer to
                // the goal (the 5-unit margin ignores micro-lunges). This drives the
                // anti-spin camera FREEZE in the heading block (repathsNoProgress >
                // CHURN_REPATH_CAP) so the churn stops winding the camera while the bot
                // keeps pressing toward the climb-out; only after a long stall with no
                // progress at all do we give up best-effort instead of pressing forever.
                if (d < bestGoalDist - 5.0) { bestGoalDist = d; repathsNoProgress = 0; }
                else repathsNoProgress++;
                boolean overWaterRepath = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
                if (overWaterRepath && !res.goalReached() && repathsNoProgress > CHURN_GIVEUP_CAP) {
                    if (BotConfig.walkerDebug)
                        LOG.info("[walker] anti-spin: {} water repaths w/o progress (d={}) → end best-effort",
                                repathsNoProgress, String.format(Locale.ROOT, "%.0f", d));
                    agentForward(a, false);
                    agentJump(a, false);
                    p.setSprinting(false);
                    return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
                }
                // BLOCK-BUDGET ("搭桥前算够不够，否则就挖"): if this path would place
                // more blocks (bridge/pillar/parkour-place) than the bot carries, it
                // would bridge partway, burn its blocks and strand. Re-search with
                // placing OFF so A* digs through / routes around (break moves need no
                // blocks). Guard with searchSuppressedPlace so the place-off result is
                // adopted as-is (no second reroute / loop).
                if (!searchSuppressedPlace) {
                    int placesNeeded = countPlaceEdges(res.edges());
                    if (placesNeeded > world.placeableBlockCount()) {
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] path needs {} placed blocks, have {} → re-search place-off (dig/around)",
                                    placesNeeded, world.placeableBlockCount());
                        activeSearch = new PathFinder(world).newSearch(foot, goal, true);
                        searchFromEnd = false;
                        searchSuppressedPlace = true;
                        pendingSegment = null;
                        return Step.WALKING;
                    }
                }
                adoptPath(res, world);
            } else if (path == null) {
                // No route and nothing to fall back on. But if we're airborne
                // — plummeting from an unplanned fall (knockback, the ground
                // broken out from under us, a tp) — a mid-air foot ALWAYS has
                // no walkable neighbours, so failing here ends the process and
                // stops ticking, and the reactive water-clutch + MLG latch at
                // the top of tick() never get to run. Hold instead so they keep
                // ticking; once we land, the next repath finds a route or fails
                // for real. (Bounded by the total-tick budget above, so a void
                // fall with no clutch can't hang the goto forever.)
                if (!p.onGround()) {
                    agentForward(a, false);
                    p.setSprinting(false);
                    return Step.WALKING;
                }
                lastError = "no path (expanded=" + res.expanded() + ")";
                return terminal(Step.FAILED, PathTrace.Outcome.NO_PATH, lastError);
            }
            // else: search failed but we still have the previous path — keep it.
        }
        // First path still computing (no path to follow yet) → hold, don't spin.
        if (path == null) {
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }
        ticksSinceRepath++;
        // "Stuck" = no PROGRESS toward the current node — NOT a foot block that has
        // not changed. A bot creeping forward (water ≈ 0.08 b/tick, a place-bridge
        // sneak ≈ 1 block/15 ticks) stays in the same foot block for many ticks while
        // genuinely advancing; the old block-change test mis-flagged that as stuck and
        // fired the reCentre recovery (which aims yaw at the PREVIOUS node) → a
        // forward/backward yaw limit-cycle (0↔180) at zero net speed. So measure
        // 3D distance² to the current step node and only count ticks that fail to
        // improve on the closest approach so far. The vertical (dy) term matters: a
        // stepUp/stairUpBreak climbs in place — horizontal distance is frozen while
        // the bot rises toward the node — so a horizontal-only metric would falsely
        // count the whole climb as stuck. Bridge edges still hard-zero the counter
        // (a sneak crawl barely moves the carrot, so even node-distance can stall
        // mid-place).
        Move.Edge curBridgeE = edgeAt(step), nxtBridgeE = edgeAt(step + 1);
        boolean onBridge = (curBridgeE != null && "bridgePlace".equals(curBridgeE.move))
                || (nxtBridgeE != null && "bridgePlace".equals(nxtBridgeE.move));
        // Jitter-immune WEDGE timer: counts raw ticks the bot stays on the SAME path
        // step, reset only when the step actually advances (or the path/segment ends).
        // Deliberately INDEPENDENT of the bridge/progress resets below — a bridgePlace
        // that can't place, or a fallN that can't drop, dwells on one step indefinitely
        // and IS a wedge; the progress-based stuckTicks (which a bob/creep keeps zeroing,
        // and which onBridge hard-zeroes) misses both, so without this the bot deadlocks.
        if (path == null || step >= path.size() || step != noProgressStep) {
            noProgressStep = step;
            noStepProgressTicks = 0;
        } else {
            noStepProgressTicks++;
        }
        if (onBridge) {
            stuckTicks = 0;
            bestStepDist = Double.POSITIVE_INFINITY;
            stuckStep = step;
        } else if (path != null && step >= 0 && step < path.size()) {
            BlockPos sn = path.get(step);
            double sdx = (sn.getX() + 0.5) - p.getX();
            double sdy = sn.getY() - p.getY();
            double sdz = (sn.getZ() + 0.5) - p.getZ();
            double sd2 = sdx * sdx + sdy * sdy + sdz * sdz;
            if (step != stuckStep) {                       // new node → fresh progress window
                stuckStep = step;
                bestStepDist = sd2;
                stuckTicks = 0;
            } else if (sd2 < bestStepDist - STUCK_PROGRESS_EPS) {
                bestStepDist = sd2;                        // closer than ever to this node → real progress
                stuckTicks = 0;
            } else {
                stuckTicks++;                              // no closer this tick → maybe stalled
            }
        } else {
            stuckTicks = 0;
        }

        while (step < path.size()) {
            Move.Edge se = edgeAt(step);
            // Buoyant pillar (bunker / flooded-pit self-exit): the bot rises through
            // a water-filled shaft. Detect it from the WORLD — the cell being risen
            // FROM is water — not p.isInWater(), which flickers false at the bob peak
            // when the head clears the surface (that flicker stalled the climb).
            // A FLOODED shaft (destination cell is itself water) lets the bot float
            // straight up; a buoyant pillar more broadly is any pillarUp begun from
            // water (cell below is water / bot in water) — including the dry-air
            // chimney where the bot must place a support to climb out.
            boolean shaftFlooded = se != null && "pillarUp".equals(se.move) && world.isWater(path.get(step));
            boolean waterPillar = se != null && "pillarUp".equals(se.move)
                    && (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0)));
            // Don't advance past a cell whose break/place actions are still
            // pending — otherwise a DownBreak (foot vertically aligned, < 1.2
            // away) would be skipped before we ever mine the floor.
            if (waterPillar) {
                // The buoyant climb never places the support via the dry path, so the
                // unfilled place cell is not genuinely pending; only a still-solid
                // ceiling is. (In the dry-air case the in-water actuator does place a
                // support, which makes the bot ground and falls through to the gate.)
                boolean ceilingPending = false;
                for (BlockPos b : se.toBreak) if (world.isSolid(b)) { ceilingPending = true; break; }
                if (ceilingPending) break;
            } else if (hasPendingEdge(world, se)) break;
            // A pillar step isn't "reached" until risen to height. A FLOODED-shaft
            // float has no landing → risen-height alone promotes it; every other
            // pillar (dry, or the water-exit place) must be grounded first so we
            // don't advance mid-jump / before the support lands.
            if (se != null && "pillarUp".equals(se.move)
                    && !((p.onGround() || shaftFlooded) && p.getY() >= path.get(step).getY() - 0.1)) break;
            // Don't advance past a parkour-place edge while airborne — keep the
            // settle phase owning the descent so it brakes the leap on landing.
            if (se != null && se.move != null && se.move.startsWith("parkourPlace")
                    && !p.onGround()) break;
            // Same for a parkour-descend leap: don't advance off it until we've
            // actually landed, so the descend landing-brake keeps owning the arc
            // (otherwise the next edge fires mid-air and the bot sails past).
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround()) break;
            BlockPos w = path.get(step);
            double dx = (w.getX() + 0.5) - p.getX();
            double dz = (w.getZ() + 0.5) - p.getZ();
            double cur2 = dx * dx + dz * dz;
            // A climb node counts as REACHED only once the feet are up at it. The
            // |Δy|<1.2 gate marks a +1 climb node "reached" from a full block below —
            // fine on dry land (mid-step), but in WATER the bot bobs at the surface 1
            // block under the node, so `within` fired early, advanced `step` to the
            // NEXT node, and left an impossible +2 climb (trace: stuck at y62 targeting
            // node y64). For an upward node while in water, don't advance until the
            // feet have actually risen to it.
            double dyNode = w.getY() - p.getY();
            // In WATER the buoyant body rides the surface; a path node BELOW it
            // (A* routed a wide crossing along the riverbed) can never be reached
            // by Y — the bot floats over it forever (trace: rode y61.78 above a y60
            // diagDown node, |dY|=1.78 > 1.2, never advanced → a ~50-block river was
            // uncrossable). Treat horizontal alignment ALONE as "reached" for such
            // a submerged below-node so the crossing advances node-by-node at the
            // surface. Excluded: a deliberate swimDown dive edge (we want that
            // descent); a climb-up node (dyNode>0) stays gated by the clause below
            // so a buoyant bob can't skip an intermediate +1 climb node.
            boolean diveEdge = se != null && se.move != null && se.move.startsWith("swimDown");
            boolean floatOverSubmerged = p.isInWater() && !p.isUnderWater()
                    && dyNode < -0.5 && !diveEdge;
            boolean within = cur2 < REACH_DIST_SQ
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged)
                    && !(p.isInWater() && dyNode > 0.5);
            // Pure-pursuit re-sync: also advance past a node we've already gone
            // by — the next node being closer than this one means the player is
            // beyond it. Without this, sprinting toward a far carrot (or a
            // sliced repath that starts from a now-stale foot) leaves `step`
            // pointing at a node BEHIND the player, so the aim flips ~180°.
            boolean passed = false;
            if (!within && step + 1 < path.size()) {
                BlockPos nx = path.get(step + 1);
                double ndx = (nx.getX() + 0.5) - p.getX();
                double ndz = (nx.getZ() + 0.5) - p.getZ();
                // Skip the current node only if the player is genuinely beyond it
                // (next node STRICTLY horizontally closer) AND the next node is
                // itself vertically reachable from where the player actually IS.
                // Without the vertical clause, standing at the bottom of a 2-deep
                // pit the re-sync skips the intermediate +1 climb node and locks
                // onto a node +2 above — an impossible single jump → bot wedged
                // (bunker pit, totStuck>1000). The STRICT '<' matters when the next
                // node is stacked directly above the current one (a pillarUp: same
                // x,z → ndx²+ndz² EQUALS cur2): with '<=' the tie reads as "passed"
                // and the bot skips the walk-to-the-pillar-base node, then tries to
                // pillar in place wherever it happens to be standing (observed:
                // mining an offset trunk, bot jumped at x=16.7 chasing a pillar at
                // x=15, never placing). A real overshoot makes next STRICTLY closer,
                // so '<' still resyncs those. The 1.2 gate matches a jump's climb.
                passed = (ndx * ndx + ndz * ndz) < cur2
                        && Math.abs(w.getY() - p.getY()) < 1.5
                        && Math.abs(nx.getY() - p.getY()) < 1.2;
            }
            if (within || passed) step++;
            else break;
        }
        if (step >= path.size()) {
            if (!pathBestEffort || goal.reached(foot)) {
                // A path that actually reaches the goal (or we ended up standing in
                // the goal cell): the journey is done.
                return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
            }
            // Best-effort segment consumed but the goal is still ahead. DON'T give
            // up — this is the long-distance splice point. Adopt the continuation
            // if its background search has landed; otherwise hold here (keys
            // released) until it does. The total-tick budget above still bounds a
            // goal we can never make real progress toward, so this can't hang.
            if (pendingSegment != null) {
                PathFinder.Result next = pendingSegment;
                pendingSegment = null;
                if (next.hasPath() && next.path().size() > 1) {
                    adoptPath(next, world);     // step→1 on the new segment; fall through to walk it
                } else {
                    // Stale eager continuation found nothing — at a chunk frontier the
                    // newly-loaded terrain may now reveal the next segment, so re-search
                    // fresh before giving up (bounded).
                    return frontierHoldOrArrive(a, world, p);
                }
            } else {
                if (activeSearch == null && commitEnd != null) {
                    activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
                    searchFromEnd = true;
                }
                agentForward(a, false);
                agentJump(a, false);
                p.setSprinting(false);
                return Step.WALKING;
            }
        }

        Move.Edge edge = edgeAt(step);

        // === Comprehensive per-tick Walker trace (walkerDebug) ===
        // The single source of truth for "why is the bot stuck": for the current
        // step it prints the exact advance-decision inputs (horizontal dist² vs the
        // REACH_DIST_SQ gate, the |Δy| vs the 1.2 gate that together decide `within`),
        // how many ticks we've been stuck on THIS step, the move type + its
        // break/place needs, and the live body state. Read this trace top-to-bottom
        // to see precisely which condition fails tick after tick — no guessing.
        if (BotConfig.walkerDebug) {
            if (step != dbgPrevStep) { dbgPrevStep = step; dbgTicksOnStep = 0; }
            dbgTicksOnStep++;
            BlockPos nd = path.get(step);
            double ddx = (nd.getX() + 0.5) - p.getX();
            double ddz = (nd.getZ() + 0.5) - p.getZ();
            double cur2 = ddx * ddx + ddz * ddz;
            double dY = nd.getY() - p.getY();
            boolean within = cur2 < REACH_DIST_SQ && Math.abs(dY) < 1.2;
            BlockPos br0 = (edge != null && !edge.toBreak.isEmpty()) ? edge.toBreak.get(0) : null;
            LOG.info("[walker] t={} step={}/{} move={} node={},{},{} p=({},{},{}) cur2={} (gate {}) |dY|={} (gate 1.2) within={} onG={} inW={} undW={} stuck={} totStuck={} pend={} break0={}{}",
                    dbgTicksOnStep, step, path.size(), edge != null ? edge.move : "-",
                    nd.getX(), nd.getY(), nd.getZ(),
                    String.format(Locale.ROOT, "%.2f", p.getX()), String.format(Locale.ROOT, "%.2f", p.getY()), String.format(Locale.ROOT, "%.2f", p.getZ()),
                    String.format(Locale.ROOT, "%.3f", cur2), REACH_DIST_SQ,
                    String.format(Locale.ROOT, "%.2f", Math.abs(dY)), within,
                    p.onGround(), p.isInWater(), p.isUnderWater(),
                    stuckTicks, totalTicks, edge != null && hasPendingEdge(world, edge),
                    br0, br0 != null ? (world.isSolid(br0) ? " (solid)" : " (clear)") : "");
        }

        // === Water climb-out foothold (place to get grounded) ===
        // Runs BEFORE the break/place actuators so it catches a bob-stall no matter
        // how A* labelled the climb (plain StepUp, StairUpBreak into the bank, …).
        // A floating bot can't gain height onto a bank whose top sits ABOVE the
        // water surface: swim-up tops out AT the surface (~0.6 short of the step-up
        // grab) and a break/pillar can't actuate from deep water either — so it
        // bob-cycles forever (live trace: y6.2 peak → sinks to y4.7, no NET rise).
        // Fix: once stalled with no vertical progress, at a bob peak (feet clear of
        // the water, surface right beneath) place ONE throwaway block to fill that
        // top water cell → a flush foothold the bot rests on, GROUNDED; from solid
        // ground the ordinary climb (step-up / break-carve / pillar) finishes the
        // +1/+2 (verified live). The counter only advances while height is NOT
        // rising, so a working pillar / swim-up that DOES gain height never trips
        // it. Default-ON escape permission + a placeable in hand required; reads
        // only here, so the pathfinder is byte-for-byte unchanged.
        {
            BlockPos cwp = path.get(step);
            boolean waterClimbing = edge != null && cwp.getY() > foot.getY() && !p.onGround()
                    && (p.isInWater() || world.isWater(foot) || world.isWater(foot.below()));
            if (!waterClimbing) {
                waterClimbStall = 0;
                waterClimbBestY = p.getY();
            } else if (p.getY() > waterClimbBestY + 0.3) {
                waterClimbBestY = p.getY();        // real rise → reset the stall (don't fight a working climb)
                waterClimbStall = 0;
            } else {
                waterClimbStall++;
            }
            if (waterClimbing && waterClimbStall > WATER_CLIMB_STALL
                    && BotConfig.allowSwimEscapePlace && a.holdPlaceable()) {
                // Locate the top water cell in the bot's column (the foothold to
                // fill) and the surface above it — independent of the bob phase.
                BlockPos topWater = world.isWater(foot) ? foot : foot.below();
                while (world.isWater(topWater.above())) topWater = topWater.above();
                int surfaceY = topWater.getY() + 1;                          // first air above the column
                if (world.isWater(topWater) && Move.hasPlaceSupport(world, topWater)) {
                    if (BotConfig.walkerDebug && waterClimbStall == WATER_CLIMB_STALL + 1)
                        LOG.info("[walker] water climb-out: takeover engaged (bob-stalled), swimming up to foothold {},{},{} surfaceY={}",
                                topWater.getX(), topWater.getY(), topWater.getZ(), surfaceY);
                    // TAKE OVER the keys: a clean swim straight up toward the bank.
                    // The break / walk actuators below otherwise pin the bot low
                    // against the wall (aiming at the break) so it never clears the
                    // place cell. Face the climb node so 'forward' holds the body
                    // against the bank, hold jump to surface, look down to aim.
                    double ax = (cwp.getX() + 0.5) - p.getX();
                    double az = (cwp.getZ() + 0.5) - p.getZ();
                    if (ax * ax + az * az > 1e-4) {
                        float yaw = (float) Math.toDegrees(Math.atan2(-ax, az));
                        p.setYRot(yaw); p.yHeadRot = yaw; p.yBodyRot = yaw;
                    }
                    p.setXRot(40f);
                    agentForward(a, true);
                    p.setSprinting(false);
                    agentJump(a, true);
                    if (p.getY() >= surfaceY) {                              // feet cleared the place cell
                        a.place(world,topWater);                 // fill it → flush, grounded foothold
                        // Re-plan from the (now grounded) surface. Unconditional —
                        // the client place is same-tick, so next tick topWater reads
                        // solid and the outer isWater guard blocks any re-place; the
                        // bot then climbs the bank from solid ground.
                        path = null;
                        stuckTicks = 0;
                        totalTicks = 0;
                        waterClimbStall = 0;
                        waterClimbBestY = p.getY();
                        if (BotConfig.walkerDebug)
                            LOG.info("[walker] water climb-out: foothold placed at {},{},{} → repath from grounded",
                                    topWater.getX(), topWater.getY(), topWater.getZ());
                    }
                    return Step.WALKING;
                }
            }
        }

        // Pillar-up actuator: clear the ceiling if one blocks the rise, then
        // jump and place the support block beneath at the apex. Distinct from
        // the generic place actuator because it owns the airborne timing
        // (you can't place a block in the cell you're standing in).
        if (edge != null && "pillarUp".equals(edge.move) && hasPendingEdge(world, edge)) {
            agentForward(a, false);
            p.setSprinting(false);
            totalTicks = 0;
            stuckTicks = 0;   // pillaring stays on one cell while placing — not "stuck"
            if (step != pillarStep) { pillarStep = step; pillarSinceJump = -1; }
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                a.breakHold(false);
                agentJump(a, false);
                lastError = "pillar stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    agentJump(a, false);
                    a.selectTool(b);
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    return Step.WALKING;
                }
            }
            a.breakHold(false);
            // Buoyant pillar — the bot is rising out of water. Two sub-cases:
            //   (a) FLOODED shaft (the destination cell is itself water): just hold
            //       jump and FLOAT up through it; water follows up so the next
            //       ceiling break repeats until the bot surfaces. No place — a block
            //       would only dam the float.
            //   (b) DRY air above (a partly-mined bunker / 1-deep pocket with an open
            //       chimney): the swim-bob alone never gains permanent height, so on
            //       the crest — when the feet clear the place cell — PLACE a support
            //       there to stand on, lifting the bot one block; after that first
            //       lift it is grounded and the normal dry pillar climbs the rest.
            // Detect water from the world (cell below is water), not p.isInWater(),
            // which flickers false at the bob peak and would drop the jump.
            boolean shaftFlooded = world.isWater(path.get(step));
            if (shaftFlooded || p.isInWater() || world.isWater(path.get(step).offset(0, -1, 0))) {
                agentJump(a, true);
                if (!shaftFlooded && a.holdPlaceable()) {
                    BlockPos wp = edge.toPlace.get(0);
                    p.setXRot(89.5f);                       // look down to aim the support
                    if (p.getY() >= wp.getY() + 0.9) {      // bobbed clear of the place cell
                        a.placeOn(wp.offset(0, -1, 0), Direction.UP);
                    }
                }
                return Step.WALKING;
            }
            if (!a.holdPlaceable()) {
                lastError = "pillar: no placeable block in hotbar";
                path = null;
                return Step.WALKING;
            }
            BlockPos place = edge.toPlace.get(0);                    // cell we fill (old feet cell)
            BlockPos support = place.offset(0, -1, 0);               // click its top face (block we stood on)
            p.setXRot(89.5f);                                        // look straight down (snap)
            if (p.onGround()) {
                agentJump(a, true);
                pillarSinceJump = 0;
            } else {
                agentJump(a, false);
                if (pillarSinceJump >= 0) pillarSinceJump++;
                // Place only once the feet have actually risen clear of the cell
                // being filled. The target IS the old feet cell, so vanilla's
                // entity-collision check (Level#isUnobstructed) silently rejects
                // the block while the player AABB still overlaps it — i.e. until
                // the feet (getY) reach that cell's top face, place.y + 1. A
                // vanilla jump (peak ~+1.25) only crosses +1.0 around tick 4, so
                // the old fixed 3-tick delay fired at ~+0.99 and the place
                // no-op'd against the player's own body. Gate on real height.
                if (pillarSinceJump >= PILLAR_PLACE_DELAY && p.getY() >= place.getY() + 1.0) {
                    a.placeOn(support, Direction.UP);
                }
            }
            return Step.WALKING;
        }

        // Parkour-place actuator (Baritone allowParkourPlace): a two-phase leap
        // owned here so the generic place actuator below (which freezes all
        // motion to place) can't kill the arc's momentum.
        //   LEAP  (floor still air): forward + sprint + jump off the lip with
        //         run-up PRESERVED, then place the landing block mid-air the
        //         instant a support is in reach. The hit is synthetic
        //         (clientUseItemOn), so aiming down isn't needed — the
        //         crosshair stays on the destination for the whole arc.
        //   SETTLE(floor placed, still airborne): keep driving forward to clear
        //         the gap, but DROP sprint and hold sneak — sneak's ledge guard
        //         stops the bot on the fresh 1-wide block on touchdown instead
        //         of letting sprint momentum carry it off the far edge into a
        //         gap beyond (matters when the place-support is below/beside,
        //         not a walkable same-level wall).
        // Once placed AND grounded the guard falls through to the normal walk /
        // arrival check.
        if (edge != null && edge.move != null && edge.move.startsWith("parkourPlace")
                && (hasPendingEdge(world, edge) || !p.onGround())) {
            BlockPos dest = path.get(step);
            BlockPos floor = edge.toPlace.get(0);
            boolean placed = !hasPendingEdge(world, edge);   // landing block is down
            boolean grounded = p.onGround();
            totalTicks = 0;
            stuckTicks = 0;       // owns this cell while leaping — not "stuck"
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                lastError = "parkour-place stalled at " + dest;
                path = null;
                return Step.WALKING;
            }
            // Snap heading at the destination — can't course-correct mid-air.
            double adx = (dest.getX() + 0.5) - p.getX();
            double adz = (dest.getZ() + 0.5) - p.getZ();
            if (Math.abs(adx) > 1e-4 || Math.abs(adz) > 1e-4) {
                float yaw = (float) Math.toDegrees(Math.atan2(-adx, adz));
                p.setYRot(yaw);
                p.yHeadRot = yaw;
                p.yBodyRot = yaw;
                LookController.requestSnap();   // a parkour leap's heading is functional — exempt from the global slew
            }
            p.setXRot(0f);
            agentForward(a, true);
            agentJump(a, !placed && grounded);   // jump off the lip once
            boolean sprint = !placed;                          // brake after the block is down
            p.setSprinting(sprint);
            agentSneak(a, placed);               // sneak-brake / ledge-guard on landing
            p.setShiftKeyDown(placed);
            if (!placed && !grounded && a.holdPlaceable()) {
                Vec3 eye = p.getEyePosition();
                double fdx = (floor.getX() + 0.5) - eye.x, fdy = (floor.getY() + 0.5) - eye.y, fdz = (floor.getZ() + 0.5) - eye.z;
                boolean inReach = fdx * fdx + fdy * fdy + fdz * fdz < 16;   // ~4 blocks of the eye
                if (inReach) {
                    a.place(world,floor);
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] parkour-place floor={},{},{} y={} dy={} solid={}",
                                floor.getX(), floor.getY(), floor.getZ(),
                                String.format(Locale.ROOT, "%.2f", p.getY()),
                                String.format(Locale.ROOT, "%.2f", p.getDeltaMovement().y),
                                world.isSolid(floor));
                }
            }
            return Step.WALKING;
        }

        // Break/place actuator: mine or place the blocks this edge needs
        // before walking into the cell. Functional aim SNAPS (same-tick),
        // independent of smoothLook. Returns each tick until the edge is
        // clear, then falls through to the normal walk below.
        if (edge != null && hasPendingEdge(world, edge)) {
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            totalTicks = 0;                       // breaking/placing IS progress
            stuckTicks = 0;                       // foot stays put while placing — don't trip the wiggle-jump (it'd leap off a 1-wide bridge)
            if (++actionTicks > BotConfig.breakTimeoutTicks) {
                // Lag or an unexpected obstruction — drop the path and let
                // the next tick repath from the current position.
                a.breakHold(false);
                lastError = "break/place stalled at " + path.get(step);
                path = null;
                return Step.WALKING;
            }
            // Water-escape break (swimAshore / swimTraverseBreak): while breaking the
            // bank, a FLOATING bot drifts off its foot cell and the edge invalidates
            // before the block breaks — it bobs and never climbs out. We anchor by
            // pressing INTO the bank, but ONLY when the head is at the surface
            // (!isUnderWater). The earlier unconditional keyUp drowned the bot:
            // forward input while SUBMERGED drops it into the prone swim pose and it
            // sinks. Gating on surface means forward can't trigger swim-pose, so it
            // presses the body against the bank + jumps to mount, holding position
            // long enough to finish the dig. (#9 autoSwim still surfaces it each tick.)
            boolean swimEscapeBreak = edge.move != null
                    && (edge.move.startsWith("swimAshore") || edge.move.startsWith("swimTraverseBreak"));
            for (BlockPos b : edge.toBreak) {
                if (world.isSolid(b)) {
                    a.selectTool(b);
                    a.aimAtBlock(b);
                    a.breakHold(true);
                    if (swimEscapeBreak && p.isInWater() && !p.isUnderWater()) {
                        agentForward(a, true);     // press into the aimed bank (surface only)
                        if (edge.move.startsWith("swimAshore"))
                            agentJump(a, true);   // rise to mount the +1
                    }
                    return Step.WALKING;
                }
            }
            a.breakHold(false);
            for (BlockPos b : edge.toPlace) {
                if (!world.isSolid(b)) {
                    if (BotConfig.walkerDebug)
                        LOG.info(
                                "[walker] place-act foot={},{},{} y={} step={} placing={},{},{} onGround={} edge={}",
                                foot.getX(), foot.getY(), foot.getZ(), String.format(Locale.ROOT, "%.2f", p.getY()),
                                step, b.getX(), b.getY(), b.getZ(), p.onGround(), edge.move);
                    a.aimAtBlock(b);
                    a.place(world,b);
                    return Step.WALKING;
                }
            }
            return Step.WALKING;                  // settle a tick before walking on
        }
        a.breakHold(false);
        actionTicks = 0;

        // Pillar placed but the player is still rising onto it — hold (no
        // horizontal walk) until grounded at the new level, so we don't walk
        // off the fresh block mid-jump.
        if (edge != null && "pillarUp".equals(edge.move)
                && !(p.onGround() && p.getY() >= path.get(step).getY() - 0.1)) {
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }

        BlockPos wp = path.get(step);
        Move.Edge nextEdge = edgeAt(step + 1);
        boolean parkourEdge = edge != null && edge.move != null && edge.move.startsWith("parkour");
        // VINE traversal (user insight 2026-06-06): vines are CLIMBABLE, so when the
        // walker fights a stale waypoint while the body is clinging to a vine it wedges
        // for seconds (the jungle cling-freeze: suspended off-ground, hCol, hSpd≈0, while
        // the waypoint sat 3 blocks BELOW). Don't fight it — a vine is a fixed climb
        // sub-path. Once the body is suspended ON a vine (!onGround), take over: face the
        // path-ahead bearing and, if the path ahead is up/level ("direction correct"),
        // CLIMB the vine (forward+jump); the offPath(>3) / stuck repath then re-routes
        // from the new elevation ("修正路径"). If the path ahead is BELOW (we over-climbed),
        // release the climb keys so the body slides back down the vine to be re-planned.
        // Gated on !onGround so it never hijacks normal ground-walking through a vine-
        // draped cell; ladders are excluded by isClimbable's caller using vines in jungle.
        boolean onVine = !parkourEdge && !p.isInWater() && !p.onGround() && world.isClimbable(foot);
        if (onVine) {
            BlockPos ahead = path.get(Math.min(step + 2, path.size() - 1));
            double vdx = (ahead.getX() + 0.5) - p.getX();
            double vdz = (ahead.getZ() + 0.5) - p.getZ();
            float vYaw = (vdx * vdx + vdz * vdz < YAW_DEADZONE_SQ)
                    ? p.getYRot()
                    : (float) Math.toDegrees(Math.atan2(-vdx, vdz));
            if (Math.abs(angleDiff(p.getYRot(), vYaw)) > BotConfig.walkerYawHysteresisDeg) {
                float want = smoothAngle(p.getYRot(), vYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                float ny = p.getYRot() + dyaw;
                p.setYRot(ny); p.yHeadRot = ny; p.yBodyRot = ny;
            }
            p.setXRot(smoothAngle(p.getXRot(), 0f));
            boolean climbUp = ahead.getY() >= foot.getY();   // path ahead up/level → climb; below → over-climbed, descend
            agentForward(a, climbUp);               // forward INTO the vine = climb up (vanilla vine ascent)
            agentJump(a, climbUp);             // jump also drives vine ascent; off → slide back down
            agentSneak(a, false);              // sneak would HALT the vine climb
            p.setShiftKeyDown(false);
            p.setSprinting(false);
            if (BotConfig.walkerDebug)
                LOG.info("[walker] vine-climb foot={},{},{} ahead={},{},{} climbUp={} yaw={}",
                        foot.getX(), foot.getY(), foot.getZ(),
                        ahead.getX(), ahead.getY(), ahead.getZ(), climbUp,
                        String.format(Locale.ROOT, "%.0f", p.getYRot()));
            return Step.WALKING;
        }
        // Stepping off into an MLG fall: walk off at WALK speed (no sprint) so
        // the body drops near-vertically and lands in the water we place under
        // it — a sprint launch carries horizontal momentum that drifts the bot
        // off the placed source and it clips the edge (partial fall damage).
        // Do NOT sneak though: sneak is ledge-protection and would stop the bot
        // walking off the edge at all. Triggered when the edge we're walking
        // onto OR the next one is an MLG fall.
        boolean steppingOffFall = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallBucket"));
        // A fall into EXISTING water (Move.FallIntoWater, "fallWater*") wants
        // the same near-vertical step-off — no sprint — so the longer airtime
        // of a tall drop doesn't carry the bot horizontally past the 1-wide
        // water column and onto dry land beyond. But there's no source to
        // place or scoop here, so it must NOT arm the mlg latch (that latch
        // drives water placement and would strand an empty hand mid-fall).
        boolean steppingOffWaterFall = (edge != null && edge.move != null && edge.move.startsWith("fallWater"))
                || (nextEdge != null && nextEdge.move != null && nextEdge.move.startsWith("fallWater"));
        // Commit to the MLG fall while still on the launch lip: the latch at
        // the top of tick() then owns the entire descent (placing/scooping the
        // water) independent of how A* relabels the edge as we near the ground.
        if (steppingOffFall) {
            // Commit to the MLG fall while still on the launch lip, recording
            // the planned landing column so the airborne clutch can damp drift
            // back toward it: a walk-off's residual horizontal momentum (zero
            // air friction) otherwise carries the body past a 1-wide landing
            // before it descends, and the water gets placed on whatever happens
            // to be below the drifted-into column instead.
            BlockPos land = (edge != null && edge.move != null && edge.move.startsWith("fallBucket"))
                    ? wp : path.get(step + 1);
            if (land != null) CLUTCH.armPlanned(land.getX(), land.getZ());
        }

        // Aim at a line-of-sight carrot further along the (now string-pulled)
        // path so the heading stays steady — no left-right wobble. For a
        // vertical move or a real parkour leap, face the actual waypoint so
        // the jump goes the right way.
        boolean aimAtWaypoint = wp.getY() != foot.getY() || parkourEdge;
        // Re-centre recovery on a stuck flat walk: a 1-wide channel needs the
        // body centred on the lane axis or the off-centre hitbox snags a corner
        // and wedges (the look-ahead carrot aims diagonally, so it never centres
        // and the bot grinds the boundary). When genuinely stuck, steer to the
        // centre of the last confirmed on-spine node (the cell we came from):
        // that pulls the body straight onto the lane axis, after which the carrot
        // — aimed at the next node, same axis — is a clean straight push. Only
        // fires when stuck (normal open walking never is), so it can't reverse a
        // healthy run.
        // Cross-axis re-centre on a stuck flat walk: a 1-wide channel flush against
        // a wall needs the body held on the lane axis or the off-centre hitbox
        // grazes the wall and can't slide forward. When stuck, steer PURELY along
        // the cross axis of the immediate cardinal move (correct X for a N/S lane,
        // Z for an E/W lane) — never along the lane itself, so it can't cancel the
        // forward carrot by pulling backward (the bug that pinned the bot mid-
        // channel). Once centred (≤0.1) it releases and the carrot's straight push
        // resumes; the two alternate but only ever add forward + lateral, so the
        // bot threads the channel instead of grinding the wall.
        // Off-spine recovery: if we've drifted to a cell from which the immediate
        // waypoint is diagonal (slid back across a corner), the carrot would aim
        // diagonally and re-snag the wall — instead steer back to the previous
        // on-spine node's centre to regain the lane. Lateral lane-CENTRING on the
        // spine is handled by the strafe below, so this only handles the gross
        // drift-off case (foot no longer in the spine cell).
        boolean reCentre = false;
        double recX = 0, recZ = 0;
        if (!aimAtWaypoint && stuckTicks > 5 && step > 0) {
            BlockPos sp = path.get(step - 1);
            boolean onSpine = foot.getX() == sp.getX() && foot.getZ() == sp.getZ();
            if (!onSpine && losWalkable(world, foot, sp)) {
                double rcx = (sp.getX() + 0.5) - p.getX();
                double rcz = (sp.getZ() + 0.5) - p.getZ();
                // Only re-centre toward the previous node when it is NOT behind us:
                // once the bot has progressed past sp, aiming at its centre points
                // backward and steers the body the wrong way (a stuck spot then drags
                // the heading ~180° around — the residual backward episode the slew
                // clamp turned from a flip into a slow reversal). Gate on the dot
                // product with the forward (toward-waypoint) direction so reCentre only
                // fires for genuine lateral drift, never to reverse. Backward case
                // falls through to the carrot, which keeps pushing forward.
                double fwx = (wp.getX() + 0.5) - p.getX();
                double fwz = (wp.getZ() + 0.5) - p.getZ();
                if (rcx * fwx + rcz * fwz >= 0) {
                    recX = rcx;
                    recZ = rcz;
                    reCentre = true;
                }
            }
        }
        double adx, adz;
        if (aimAtWaypoint) {
            adx = (wp.getX() + 0.5) - p.getX();
            adz = (wp.getZ() + 0.5) - p.getZ();
        } else if (reCentre) {
            adx = recX; adz = recZ;
        } else {
            double[] c = carrotPoint(world, foot, p.getX(), p.getZ());
            adx = c[0] - p.getX();
            adz = c[1] - p.getZ();
        }
        // Hold heading when the horizontal aim vector is tiny (within the dead-zone)
        // so atan2 on sub-block noise can't snap the yaw ±90/±180 each tick — see
        // YAW_DEADZONE_SQ. Above the dead-zone the bearing is well-defined.
        float targetYaw = (adx * adx + adz * adz < YAW_DEADZONE_SQ)
                ? p.getYRot()   // essentially on the aim column — hold heading, don't thrash atan2
                : (float) Math.toDegrees(Math.atan2(-adx, adz));
        // A launch into a leap (parkour or MLG fall) must SNAP the heading even when
        // smoothLook is on — you can't course-correct mid-air, so a lagged launch sends
        // the bot off at an angle (drifts off a narrow landing). Launches bypass both the
        // EMA and the slew cap; everything else is smoothed + capped.
        boolean launch = parkourEdge || steppingOffFall || steppingOffWaterFall;
        // Low-pass the TARGET heading (EMA on the shortest angle, kept in [-180,180]) so a
        // grid staircase on a diagonal — whose immediate-waypoint bearing alternates ±~30°
        // around the true diagonal each step — averages to a steady bearing instead of a
        // bounded sawtooth (see YAW_SMOOTH_ALPHA). Resync on launch / first use.
        if (Float.isNaN(smoothTargetYaw) || launch) {
            smoothTargetYaw = targetYaw;
        } else {
            smoothTargetYaw = angleDiff(0f, smoothTargetYaw + YAW_SMOOTH_ALPHA * angleDiff(smoothTargetYaw, targetYaw));
        }
        float aimYaw = launch ? targetYaw : smoothTargetYaw;
        // ANTI-SPIN camera freeze: while churning in water (consecutive repaths with no
        // goal-progress — a failed climb-out whose best-effort segments keep flipping
        // the waypoint behind the bot), HOLD the heading instead of chasing the
        // flipping target. A ~180° flip resolves the same rotational way each time, so
        // chasing it winds the camera one direction (raw yaw past -900 ≈ 2.5 turns in
        // the trace — the water "转圈"). Freezing stops the wind AND, since MC movement
        // follows body yaw, steadies the bot pressing one direction toward the climb-
        // out rather than U-turning. Launches still snap. Cleared as soon as progress
        // resumes (repathsNoProgress resets). */
        // "Over water" covers the bob-at-a-bank case too: bobbing into a climb-out
        // ledge the body pops to y+0.x above the surface (isInWater flickers false) yet
        // is still a water stall — gate on water UNDER the foot as well so the freeze
        // catches the surface/bank spin, not only the fully-submerged one.
        boolean overWater = p.isInWater() || world.isWater(foot.offset(0, -1, 0));
        boolean spinFreeze = !launch && overWater && repathsNoProgress > CHURN_REPATH_CAP;
        if (!spinFreeze && Math.abs(angleDiff(p.getYRot(), aimYaw)) > BotConfig.walkerYawHysteresisDeg) {
            float ny;
            if (launch) {
                ny = aimYaw;   // launches must snap — no mid-air course correction
                LookController.requestSnap();   // exempt this leap's takeoff heading from the global camera slew
            } else {
                // Cap the per-tick turn (see WALKER_MAX_YAW_SLEW_DEG) so even a transient
                // bad aim vector can only nudge the heading, never reverse it in one tick.
                float want = smoothAngle(p.getYRot(), aimYaw);
                float dyaw = angleDiff(p.getYRot(), want);
                if (Math.abs(dyaw) > WALKER_MAX_YAW_SLEW_DEG) dyaw = Math.copySign(WALKER_MAX_YAW_SLEW_DEG, dyaw);
                ny = p.getYRot() + dyaw;
            }
            p.setYRot(ny);
            p.yHeadRot = ny;
            p.yBodyRot = ny;
        }
        // DIVE to follow a submerged node under a ceiling. In water the prone swim
        // travels along the LOOK vector, so a level pitch pins the body at the surface
        // — when the next node is BELOW and the bot is HORIZONTALLY BLOCKED (it rams a
        // low overhang lip: a submerged tunnel whose stone ceiling sits at the
        // DESTINATION cell's foot+1, so a fixed foot+2 check at the bot's own cell
        // misses it — the lethal stuck: hCol=true, hSpd=0, bobbing y62↔63 into the
        // lip), the bot must DIVE: pitch down + the prone-swim sprint (below) sink the
        // ~0.6-tall body to the tunnel floor where it fits under the lip and threads on.
        // Triggered by the real symptom — a blocked submerged descent — and LATCHED a
        // few ticks so the dive holds through the sink even as the collision flickers
        // off mid-descent (else it flip-flops upright and bobs back into the lip). An
        // OPEN descent (no collision) never triggers, so it keeps a level camera there.
        if (p.isInWater() && wp.getY() < foot.getY() && p.horizontalCollision) diveLatch = 12;
        else if (diveLatch > 0) diveLatch--;
        boolean diveUnderCap = p.isInWater() && wp.getY() < foot.getY() && diveLatch > 0;
        p.setXRot(smoothAngle(p.getXRot(), diveUnderCap ? 50f : 0f));
        // STEP-UP HEADING GATE (卡碰撞箱 fix): if a +1 step is still badly mis-aimed,
        // pivot in place instead of ramming the riser. The jump gate (ascendJumpReady
        // below) already checks POSITION alignment, but neither it nor the forward key
        // checked HEADING — so a drift-off-column / sharp-turn arrival bob-jammed the
        // step. Gate both forward (keyUp) and the step jump on this. Excludes parkour
        // and water (own handling; launches snap heading so the error is ≈0 anyway).
        float stepHeadingErr = Math.abs(angleDiff(p.getYRot(), aimYaw));
        boolean pivotForStepUp = wp.getY() > foot.getY() && !parkourEdge && !p.isInWater()
                && stepHeadingErr > STEPUP_AIM_TOLERANCE_DEG;
        // Parkour DESCEND landing brake: a leap onto a LOWER 1-wide block
        // touches down with more horizontal momentum than a flat leap (extra
        // airtime accelerating forward), so a sprint launch slides the bot off
        // the far edge into the void (observed: a parkourDescend2d1 landed but
        // overshot the 1-wide pad and fell). Once we're airborne AND already
        // horizontally over (or nearly over) the landing column — i.e. the gap
        // is cleared — cut forward+sprint and hold sneak so the bot coasts to a
        // stop ON the block instead of past it (same trick as the parkour-place
        // SETTLE phase). We brake only when close to the landing center, so
        // cutting thrust can never drop the leap short into the gap.
        boolean descendLeap = edge != null && edge.move != null && edge.move.startsWith("parkourDescend");
        double landDx = (wp.getX() + 0.5) - p.getX();
        double landDz = (wp.getZ() + 0.5) - p.getZ();
        boolean descendBrake = descendLeap && !p.onGround()
                && (landDx * landDx + landDz * landDz) < 1.4;   // within ~1.2 block of landing center
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the body clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
        // vector to pick the key. Skipped during leaps/brakes/bridging.
        boolean strafeL = false, strafeR = false;
        // Baritone MovementAscend jump-timing for a DRY cardinal +1 step. Baritone
        // does NOT sprint an ascend and does NOT hold jump continuously — it walks
        // toward the step squaring up, then presses jump ONLY when CLOSE
        // (flatDist≤1.2 along the move axis), ALIGNED (sideDist≤0.2 on the cross
        // axis), and not drifting sideways (|lateralMotion|≤0.1). Our old "sprint +
        // hold jump for any wp.y>foot.y" bonked the step face off-axis and slid back
        // down → the 633-tick steep-mountain wedge. Mirror Baritone: centre on the
        // cross axis (strafe gate below now includes dryStepUp), drop sprint, and
        // gate the jump on alignment+proximity. Scoped to a single cardinal +1 step
        // on dry land — diagUp / +2 / water / parkour keep their own handling.
        // Attribute-driven step/jump (read the controlled entity — the vehicle when
        // mounted). On foot: step 0 (a +1 needs a jump), jump reaches +1. A horse:
        // step 1.0 (walks a full block up, no jump), jump up to +2.
        int upDy = wp.getY() - foot.getY();
        int maxStepUp = world.maxStepUpBlocks();
        int maxJumpUp = world.maxJumpUpBlocks();
        boolean cardinalUp = upDy >= 1 && ((wp.getX() == foot.getX()) ^ (wp.getZ() == foot.getZ()));
        // A jump is needed only to rise BEYOND the auto-step height.
        boolean needJumpForStep = upDy >= 1 && upDy > maxStepUp;
        // A step taller than we could clear even WITH a jump — we slid back below a
        // +1 start so it now reads +2, or terrain demands a height we can't make.
        // Don't bob against it: accelerate the stuck timer so the search blacklists
        // the node and reroutes (the steep-climb wedge), instead of jumping forever.
        boolean overJump = needJumpForStep && upDy > maxJumpUp && p.onGround() && !p.isInWater()
                && edge != null && !"pillarUp".equals(edge.move) && !parkourEdge;
        if (overJump) stuckTicks += 3;
        // Vertical recovery (execution hardening): the bot is BELOW the next node by more
        // than it can jump — it slid/fell below the committed climb path, and a plain stepUp
        // here just RAMS the wall (jump is suppressed for an unreachable height) → the
        // "被面前的高方块挡住不动" stall. If we carry blocks, PILLAR UP in place to regain
        // the height instead of ramming: look down, jump off the ground, and place a support
        // in the feet cell once risen clear of it — the same actuation as a planned pillarUp.
        // Latched across the jump's airborne phase (overJump needs onGround, so one tick
        // can't both jump and place) and re-armed each grounded tick while still too low;
        // ends when back within jump reach (overJump clears) or blocks run out, after which
        // the normal step logic resumes. Reuses ensureHolding + clientUseItemOn.
        if (overJump && world.canPlace() && a.holdPlaceable()) {
            pillarRecoverLatch = PILLAR_RECOVER_TICKS;
            pillarRecoverCell = foot;                 // grounded feet cell = the rung we fill
        }
        if (pillarRecoverLatch > 0 && pillarRecoverCell != null) {
            pillarRecoverLatch--;
            agentForward(a, false);
            p.setSprinting(false);
            agentSneak(a, false);
            p.setShiftKeyDown(false);
            p.setXRot(89.5f);                         // look straight down to aim the support
            if (p.onGround()) {
                agentJump(a, true);     // jump off the current rung
            } else {
                agentJump(a, false);
                // Place into the feet cell once risen clear of it (vanilla rejects the place
                // while the player AABB still overlaps the target cell — gate on real height).
                if (p.getY() >= pillarRecoverCell.getY() + 1.0) {
                    a.placeOn(pillarRecoverCell.offset(0, -1, 0), Direction.UP);
                }
            }
            return Step.WALKING;
        }
        // Baritone MovementAscend jump-timing applies whenever we actually JUMP a
        // cardinal step within reach (+1, or +2 for a horse/jump-boost) — align +
        // approach before the jump. A horse auto-walk-up needs no jump; water/parkour
        // differ; an over-jump (handled above) is excluded.
        boolean dryStepUp = needJumpForStep && cardinalUp && upDy <= maxJumpUp
                && !p.isInWater() && !parkourEdge;
        boolean ascendJumpReady = true;
        boolean sprintAscend = false;
        if (dryStepUp) {
            int xA = wp.getX() != foot.getX() ? 1 : 0;
            int zA = wp.getZ() != foot.getZ() ? 1 : 0;
            double flatDist = xA * Math.abs((wp.getX() + 0.5) - p.getX()) + zA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            double sideDist = zA * Math.abs((wp.getX() + 0.5) - p.getX()) + xA * Math.abs((wp.getZ() + 0.5) - p.getZ());
            Vec3 vel = p.getDeltaMovement();
            double lateralMotion = xA * vel.z + zA * vel.x;
            // SPRINT-BUNNY-HOP a cardinal +1 step (丝滑 stair climb). The deterministic
            // ascentSpeedArena shows a gentle staircase costs ~40% speed: each step drops
            // sprint + the in-place jump rams the riser + the body re-accelerates from ~0.
            // Two levers were each A/B-disproven IN ISOLATION (2026-06-06): sprint + a LATE
            // jump (flatDist≤1.2) rams the riser HARDER; an early jump (≤1.7) WITHOUT sprint
            // lands short and re-approaches. TOGETHER they compose — the sprint forward-boost
            // carries the EARLY launch up and OVER the riser onto the step with momentum
            // intact, exactly how a vanilla player sprint-jumps stairs. Gate HARD on cross-
            // axis alignment (square-up sideDist≤0.2, not drifting |lateralMotion|≤0.1) so the
            // boosted launch flies straight at the step instead of bonking a corner; when
            // aligned, jump EARLY (flatDist≤1.7) AND keep sprint (sprintAscend → sprint gate
            // below). Misaligned → don't jump yet (the pivot/strafe centres first), matching
            // the old square-up-before-jump discipline. The arena A/B (not noisy live terrain,
            // which the prior disproofs used) is the gate for this.
            boolean aligned = Math.abs(lateralMotion) <= 0.1 && sideDist <= 0.2;
            sprintAscend = aligned;
            ascendJumpReady = aligned && flatDist <= 1.7;
        }
        // Climbing a +1 ledge OUT OF a water film: buoyancy drifts the body off the
        // target column so the cardinal stepUp approach goes diagonal and forward
        // just grinds the ledge side (trace: inW, foot drifted to a diagonal of the
        // stepUp node, cur2 stuck at 1.27, bobbed 797 ticks → budget expiry, "never
        // left spawn"). The lane-keep strafe below is gated to flat walks
        // (wp.y==foot.y), so a vertical climb gets NO lateral correction. Treat a
        // water climb-out like a lane-keep but centre on BOTH axes toward the target
        // column so the body sits under the ledge; the existing forward+jump then
        // mounts it (the aligned cardinal climb that already works on dry land).
        boolean waterClimb = p.isInWater() && wp.getY() > foot.getY();
        if (!descendBrake && !parkourEdge && !steppingOffFall && (wp.getY() == foot.getY() || waterClimb || cardinalUp)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double latX = 0, latZ = 0;
            if (waterClimb) { latX = (wp.getX() + 0.5) - p.getX(); latZ = (wp.getZ() + 0.5) - p.getZ(); } // centre on the target column
            else if (ddx == 0 && ddz != 0) latX = (wp.getX() + 0.5) - p.getX();        // N/S lane → hold X
            else if (ddz == 0 && ddx != 0) latZ = (wp.getZ() + 0.5) - p.getZ();   // E/W lane → hold Z
            // Anti-drift in a current: a flowing-water cell pushes the body
            // downstream, so steer upstream (subtract the flow vector from the
            // lateral target). The strafe is far stronger than the push, so this
            // holds the crossing on the planned line PROACTIVELY instead of letting
            // the lane-keep only react after the bot has already drifted. Only the
            // cross-lane component survives the right-vector projection below; the
            // along-lane part is ignored (handled by the upstream COST, not steering).
            if (BotConfig.waterFlowPenalty > 0 && p.isInWater()) {
                Vec3 flow = world.waterFlow(foot);
                latX -= flow.x; latZ -= flow.z;
            }
            if (Math.abs(latX) > 0.06 || Math.abs(latZ) > 0.06) {
                double yr = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yr), fz = Math.cos(yr);   // forward unit (x,z)
                double rx = -fz, ry = fx;                       // player's right = forward rot +90°
                double dotR = latX * rx + latZ * ry;
                if (dotR > 0.04) strafeR = true;
                else if (dotR < -0.04) strafeL = true;
            }
        }
        // Camera-decoupled drive (see AgentInput). Rotate the body-frame movement intent
        // (forward + lane-keep strafe) from the desired travel heading (aimYaw) into the
        // camera frame by Δ = aimYaw − cameraYaw, so vanilla travel()'s rotate-by-yaw moves
        // the body ALONG the heading even while the camera is still slewing toward it — the
        // body no longer rams a wall waiting for the look to catch up (动态纠偏). At Δ=0
        // (camera caught up) the impulse equals the old keyed (dL,dF), so steady-state walking
        // is byte-identical; only the slew transient changes. spinFreeze deliberately holds
        // the heading (water anti-wind), so drive along the frozen camera there (Δ=0) to keep
        // its press-one-way climb-out behaviour. Special branches above return before here, so
        // they keep their own key-based actuation (AgentInput falls back to keys uncommanded).
        double driveF = (!descendBrake && !pivotForStepUp) ? 1.0 : 0.0;
        double driveL = strafeL ? 1.0 : (strafeR ? -1.0 : 0.0);
        double driveDelta = Math.toRadians(spinFreeze ? 0.0 : angleDiff(p.getYRot(), aimYaw));
        double driveCos = Math.cos(driveDelta), driveSin = Math.sin(driveDelta);
        a.commandMove(
                (float) (driveL * driveCos - driveF * driveSin),
                (float) (driveL * driveSin + driveF * driveCos));
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        boolean bridging = (edge != null && "bridgePlace".equals(edge.move))
                || (nextEdge != null && "bridgePlace".equals(nextEdge.move));
        // Lethal-edge sneak-brake (DEATH #8 fix): if a fatal drop is one step ahead
        // in the heading, hold sneak so vanilla's ledge-guard pins the body at the
        // block edge — the controller can no longer drift off a cliff while fleeing
        // or walking a lip. Lethal-only, so it never blocks a legitimate planned
        // step-down (those are capped at survivableFall by the PathFinder).
        // A lethal drop borders the foot — but only PIN with sneak when we're NOT
        // making a planned step-DOWN. Vanilla's ledge-guard refuses to walk off ANY
        // edge, so if the brake stays on while the path needs to descend (a lethal
        // drop in some OTHER direction, e.g. a mountainside, while the safe planned
        // step is down-and-across), the bot DEADLOCKS — sneak on, hCol=false, creeping
        // at ~0 b/s on a ridge forever (the "速度陡降 / blocked" stall). The PathFinder
        // caps every planned step-down at survivableFall, so releasing sneak for the
        // intended descent is safe. Same-level lip-walking keeps the full pin (death #8),
        // and sprint stays OFF whenever a lethal edge is near (see sprint below) so the
        // released descent has no drift/overshoot momentum off the lip.
        boolean lethalNear = BotConfig.lethalEdgeBrake && p.onGround()
                && lethalDropAdjacent(world, p, foot);
        boolean plannedDescent = wp.getY() < foot.getY();
        boolean edgeBrake = lethalNear && !plannedDescent;
        agentSneak(a, bridging || descendBrake || edgeBrake);
        p.setShiftKeyDown(bridging || descendBrake || edgeBrake);
        // Jump for a real upward step, a parkour-leap edge (by move type, not
        // raw distance — string-pulling makes plain walk waypoints far apart
        // too), or a brief stuck-wiggle.
        // The stuck-wiggle jump unsticks a bot wedged on a corner, but on a
        // 1-wide bridge it would hop the bot clean off into the gap — so
        // never wiggle-jump while bridging. Stop pressing jump once braking
        // (we're descending onto the block — no more lift wanted).
        // Stay afloat while pathing through water. autoSwim is gated OFF during
        // goto/follow/explore/runAway (it would fight the Walker's jump), so the
        // Walker itself must swim up — otherwise the bot sinks in deep water and
        // drowns mid-path (the "swam into the ocean and died" failure). Hold jump
        // whenever the eyes are submerged, unless we're deliberately diving a
        // swimDown edge (then let it descend).
        boolean diving = edge != null && edge.move != null && edge.move.startsWith("swimDown");
        boolean swimUp = p.isInWater() && p.isUnderWater() && !diving;
        // The stuck-wiggle hop unsticks a corner on DRY land, but in shallow water
        // on a flat walk it just bobs the bot off the floor into the buoyant drift
        // (it floats off its cell and slides — the very stall it's meant to break).
        // Suppress it there; treading + steady forward threads the channel instead.
        boolean flatWaterWalk = wp.getY() == foot.getY() && p.isInWater();
        boolean wiggle = !bridging && !flatWaterWalk && !pivotForStepUp && stuckTicks > 10 && stuckTicks < 18;
        // Climbing a +1 ledge out of a SHALLOW water film needs a BALLISTIC,
        // GROUNDED jump: |Δy|=0.8 exceeds the 0.6 auto-step, and a *held* jump in
        // water just swims the bot up to bob at the surface (y+0.2, onGround=false)
        // — it never clears the dry ledge (trace: stuck 797t at cur2≈1, |dY|=0.8).
        // So for a water climb-out, jump ONLY when grounded: releasing between
        // launches lets the body settle the 0.2 back onto the shaft floor, from
        // which a real jump (+1.25) tops the ledge while the strafe-centring above
        // holds it under the target column so forward carries it on. (swimUp below
        // still rescues a genuinely-submerged deep-water climb — undW there.)
        // Water climb-out jump rule covers BOTH depths:
        //  • SUBMERGED (deep water / flooded pit, undW): swim up — hold jump so the
        //    bot rises through the water column toward the surface (never sinks /
        //    drowns; this is the original swimUp behaviour).
        //  • AT THE SURFACE of a shallow film (inW, !undW): a held jump only bobs
        //    (y+0.2, onGround=false) and can't clear the 0.8 dry ledge, so jump
        //    ONLY when grounded — releasing lets the body settle the 0.2 onto the
        //    floor, from which a real jump (+1.25) tops the ledge (strafe-centring
        //    above holds it under the column so forward carries it on).
        // Still ASCENDING a water column when the head cell (or eyes) is water —
        // a deep/flooded pit is multiple blocks deep, so keep swimming up (hold
        // jump) until the head clears; checking only undW stops mid-column (eyes
        // pop out at y+0.5 while feet are still 2 below) and the bot sinks back,
        // oscillating at the pit bottom (trace: bobbed y60.0↔60.57, never rose).
        boolean swimColumn = p.isInWater() && (p.isUnderWater() || world.isWater(foot.above()));
        // Climb a +1 ledge OUT of water like a dry-land climb: hold jump CONTINUOUSLY
        // (vanilla "hold forward+jump to climb out of water"), not only when grounded.
        // The old `waterClimb ? (swimColumn || onGround)` fired jump ~never at a shallow
        // film — the buoyant body is onGround ≈1/10 ticks and swimColumn is false (no
        // water above the head), so it just treaded into the bank wall (trace: stuck
        // 814t at a lake bank, jump=false every tick). wp.getY()>foot.getY() is true for
        // ANY water climb-out, so this presses jump throughout it; swimColumn still
        // rises a deep submerged column and swimUp covers a fully-submerged climb.
        // For a dry cardinal +1 step, only jump once Baritone-aligned (ascendJumpReady):
        // close + squared-up + not drifting. Early/off-axis jumps bonk the step and
        // slide back (the steep-climb wedge); waiting to jump also keeps the body
        // moving smoothly instead of bobbing in place (no jittery camera on stream).
        // Jump a step only when a jump is actually needed (beyond auto-step) AND the
        // step is within reach (≤ maxJumpUp) — never bob-jump an unreachable height —
        // and, for the +1 cardinal case, only once Baritone-aligned.
        boolean stepUpJump = needJumpForStep && upDy <= maxJumpUp && (!dryStepUp || ascendJumpReady) && !pivotForStepUp;
        // Don't hold the swim-up jump when a SOLID cell caps the head (foot+2) while
        // in water: the path threads a submerged / stone-overhung tunnel (water
        // surface capped by solid above), so bobbing up just RAMS that ceiling and
        // the body can't move horizontally under it — the stuck-bobbing trace at a
        // stone-capped water surface (hCol=true, hSpd=0, y oscillating into the
        // ceiling, pos frozen). Staying low lets forward thread the tunnel. Only the
        // water jumps are gated; a dry stepUp / parkour launch is never suppressed
        // (their own gates apply), and an OPEN water column (head not capped) still
        // swims up so a deep crossing can't drown.
        boolean cappedHead = p.isInWater() && world.isSolid(foot.offset(0, 2, 0));
        boolean jump = !descendBrake
                && (stepUpJump || parkourEdge
                    || ((swimUp || swimColumn) && !cappedHead) || wiggle);
        agentJump(a, jump);
        // Sprint in water ONLY on a FLAT crossing (flatWaterWalk: wp.y==foot.y). The
        // prone swim pose that sprint+forward forces is exactly what a wide open-ocean
        // crossing needs (vanilla's fast swim) — WITHOUT it the bot treads upright in
        // place and STALLS (full-HP naked bot stuck at the spawn bay edge, 0-1 blk over
        // 40s; the deepWaterMax routing fix made the path exist but the controller
        // couldn't execute it). Pitch is held ~horizontal (setXRot→0 above) so the prone
        // swim hugs the surface and doesn't dive; swimUp/swimColumn still hold jump if it
        // dips under, so it can't drown over a long crossing.
        // KEEP no-sprint for CLIMB-OUT nodes (wp.y>foot.y → !flatWaterWalk): there the
        // prone pose can't rise a bank — ROOT CAUSE of the old "stuck bobbing, can't climb
        // out" trace (sprint=true, |dY|≈2.4, bobbing y61 under a y64 node). Treading + jump
        // lets vanilla auto-climb the 1-block ledge out of the water.
        // EXCEPTION — diveUnderCap: a submerged tunnel capped by solid (water under a
        // stone lip) is only ~2 cells tall, and an UPRIGHT body (1.8) rams the ceiling
        // (the lethal stuck: hCol=true, hSpd=0, bobbing into the y+1 stone). Sprinting
        // in water forces the PRONE swim pose (~0.6 tall) which fits under the lip, and
        // with the dive pitch (above) + jump suppressed (cappedHead) it threads the
        // tunnel along the floor instead of bobbing into the cap. So force sprint here
        // even though it's a (downward) vertical node.
        // A parkour ASCEND leap (parkourEdge rising) is the ONE jumped ascend that MUST
        // sprint: a +2 (or gap) parkour clears only with the run-up momentum (Baritone's
        // MovementParkour sprints). The blanket no-sprint-on-needJumpForStep below was tuned
        // for a +1 CARDINAL stepUp (sprint rams the riser there), but it wrongly starved the
        // parkour leap of momentum → it jumped +1 in place and bob-stalled against the step
        // (live: parkourAscend2 frozen, hSpd=0, bobbing y82↔83). Let a parkour ascend sprint.
        boolean parkourAscend = parkourEdge && wp.getY() > foot.getY();
        boolean sprint = !bridging && !steppingOffFall && !steppingOffWaterFall
                && !descendBrake && (!lethalNear || parkourAscend) && (!needJumpForStep || parkourAscend || sprintAscend)   // !lethalNear (not !edgeBrake): never sprint NEAR a lethal edge — incl. a planned descent past it — so no drift/overshoot momentum off the lip while sneak is released for the step-down. Baritone doesn't sprint a jumped CARDINAL ascend (overshoots/bonks) but DOES sprint a parkour leap; a horse auto-walk-up keeps sprint
                // A/B-DISPROVEN (2026-06-06): re-enabling sprint on an aligned ascend (sprintableAscend)
                // regressed hCol 13%→36% / mean hSpd .112→.082 — because the jump fires CLOSE to the riser
                // (ascendJumpReady flatDist≤1.2), the sprint forward-boost rams the riser face HARDER instead
                // of arcing over it. A sprint-jump only clears a step if launched EARLY (before the riser);
                // closing that gap needs an early-jump-timing change, not just flipping sprint on. Kept no-sprint.
                && (!p.isInWater() || flatWaterWalk || diveUnderCap);
        p.setSprinting(sprint);
        if (BotConfig.walkerDebug) {
            // [dbgcollide] hard physics evidence for the hill speed-sawtooth: is the
            // bot actually COLLIDING (hitbox snagging a trunk/step face) or just
            // turning? hSpd = horizontal velocity magnitude (sprint≈0.28 b/tick);
            // hCol/minorCol = vanilla collision flags; pos = real feet so we can see
            // dwell (pos frozen while wp/yaw change = stuck, not moving).
            Vec3 dm = p.getDeltaMovement();
            double hSpd = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
            LOG.info("[walker] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={} sneak={} hCol={} minorCol={} hSpd={} pos={},{},{} onG={} attack={}",
                    String.format(Locale.ROOT, "%.0f", p.getYRot()),
                    wp.getX(), wp.getY(), wp.getZ(),
                    a.dbgForwardImpulse(), a.dbgJumping(), p.isSprinting(),
                    a.dbgSneak(),
                    p.horizontalCollision, p.minorHorizontalCollision,
                    String.format(Locale.ROOT, "%.3f", hSpd),
                    String.format(Locale.ROOT, "%.2f", p.getX()),
                    String.format(Locale.ROOT, "%.2f", p.getY()),
                    String.format(Locale.ROOT, "%.2f", p.getZ()),
                    p.onGround(), a.breakHeld());
        }
        return Step.WALKING;
    }

    /** Fire onTerminal and return the step verdict in one place, so every terminal
     *  return site stays a one-liner. */
    private Step terminal(Step s, PathTrace.Outcome outcome, String reason) {
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /** Splice in a freshly-searched route: string-pull it, reset the per-path
     *  follow state, and record whether it's a best-effort partial (so the next
     *  segment is precomputed from its end — see the kickoff/splice logic in
     *  {@link #tick}). {@code commitEnd} is the segment's last node, the launch
     *  point for that continuation search. */
    /** At a loaded-chunk frontier the (stale) eager continuation from commitEnd —
     *  computed before the bot arrived — found no onward route. Re-search FRESH from
     *  the frontier: now that the bot stands there, chunks ~render-distance further
     *  have loaded and the next segment is visible. Bounded by {@link #FRONTIER_WAIT_CAP}
     *  (the bot is stationary while waiting, so retrying more can't load new chunks)
     *  so a genuine box-in still terminates. Holds (keys released) and returns
     *  WALKING while retrying; ARRIVED when out of retries or the feature is off. */
    private Step frontierHoldOrArrive(Avatar a, WorldView world, Player p) {
        if (BotConfig.pathfinderFrontierCommit && commitEnd != null
                && frontierWaitTicks < FRONTIER_WAIT_CAP) {
            frontierWaitTicks++;
            if (activeSearch == null) {
                activeSearch = new PathFinder(world).newSearch(commitEnd, goal);
                searchFromEnd = true;
            }
            agentForward(a, false);
            agentJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }
        return terminal(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null);
    }

    private void adoptPath(PathFinder.Result res, WorldView world) {
        // String-pull flat walk runs so the heading stays steady over the
        // staircase (no left-right camera wobble) and the bot walks straight;
        // action/vertical/parkour nodes are preserved.
        SmoothResult sm = stringPull(world, res.path(), res.edges());
        path = sm.path;
        edges = sm.edges;
        pathBestEffort = !res.goalReached();
        commitEnd = (pathBestEffort && !path.isEmpty()) ? path.get(path.size() - 1) : null;
        step = 1;
        stuckTicks = 0;
        frontierWaitTicks = 0;          // progress made → reset the frontier re-search budget
        stuckStep = -1;                 // new path geometry → restart the progress window
        noStepProgressTicks = 0;        // new path → restart the wedge timer (else a same-index step re-triggers instantly)
        noProgressStep = -1;
        bestStepDist = Double.POSITIVE_INFINITY;
        actionTicks = 0;
        if (BotConfig.walkerDebug) {
            StringBuilder sbp = new StringBuilder();
            for (int i = 0; i < path.size(); i++) {
                Move.Edge e = i < edges.size() ? edges.get(i) : null;
                sbp.append(i).append(':').append(path.get(i).getX()).append(',').append(path.get(i).getY())
                   .append(',').append(path.get(i).getZ()).append('[').append(e != null ? e.move : "-").append("] ");
            }
            LOG.info("[walker] path = {}", sbp);
        }
    }

    /** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
    private void sampleTick(Player p) {
        // Cheap gate: skip the per-tick WalkerSample allocation entirely unless capture is on.
        // Keeps the hot path free in normal play and in a stripped (NOOP) release build.
        if (!BotConfig.pathDebug) return;
        double tx = Double.NaN, tz = Double.NaN;
        String mv = null;
        if (path != null && step >= 0 && step < path.size()) {
            BlockPos t = path.get(step);
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            Move.Edge e = edgeAt(step);
            mv = (e != null) ? e.move : null;
        }
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, step, mv, p.onGround(), p.isInWater()));
    }

    private static float angleDiff(float a, float b) { return ((b - a) % 360f + 540f) % 360f - 180f; }

    /** Horizontal neighbour offsets (4 cardinals + 4 diagonals) of the foot cell. */
    private static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** True if a LETHAL drop borders the cell the bot is standing on — any horizontal
     *  neighbour that is an open foot-cell with no floor, falling to the next solid/
     *  water surface deeper than {@link SurvivalMath#survivableFall} at the bot's HP.
     *  Checking ALL neighbours (not just the heading) catches lateral/momentum drift
     *  off a lip while walking ALONG it — the actual DEATH #8 mode. Vanilla sneak then
     *  pins the body to this block in every direction. Lethal-only, so it never blocks
     *  a legitimate planned step-down (those land within survivable, or in water). */
    private static boolean lethalDropAdjacent(WorldView world, Player p, BlockPos foot) {
        int survivable = SurvivalMath.survivableFall(p.getHealth());
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            // A drop needs the foot-cell AND the cell below it both open (no floor).
            // A present floor = flat walk or a safe 1-block step-down; water = a splash.
            if (world.isSolid(n) || world.isWater(n)) continue;
            BlockPos below = n.below();
            if (world.isSolid(below) || world.isWater(below)) continue;
            int fall = 1;
            BlockPos pr = below.below();
            while (fall <= survivable + 2 && !world.isSolid(pr) && !world.isWater(pr)) {
                fall++;
                pr = pr.below();
            }
            if (fall > survivable) return true;
        }
        return false;
    }

    private Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
    }

    /** Total blocks a path would PLACE — the sum of each edge's toPlace size. Used
     *  by the block-budget reroute (搭桥前算够不够) to compare against inventory. */
    private static int countPlaceEdges(List<Move.Edge> edges) {
        int n = 0;
        for (Move.Edge e : edges) if (e != null && e.toPlace != null) n += e.toPlace.size();
        return n;
    }

    /** A continuously-sliding aim point {@code CARROT_DIST} blocks ahead
     *  along the path (interpolated between nodes), capped by line-of-sight —
     *  the pure-pursuit "carrot". Because it's interpolated (not snapped to a
     *  discrete node), the bearing to it changes smoothly as the player moves,
     *  so the heading doesn't wobble over the cardinal/diagonal staircase.
     *  Stops at a wall/turn or an action cell so it never aims through one.
     *  Returns {x, z} world coords. */
    private double[] carrotPoint(WorldView world, BlockPos foot, double px, double pz) {
        double remaining = CARROT_DIST;
        double cx = px, cz = pz, tx = px, tz = pz;
        for (int i = step; i < path.size() && i - step <= CARROT_MAX_NODES; i++) {
            BlockPos node = path.get(i);
            double nx = node.getX() + 0.5, nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, edgeAt(i))) return new double[]{nx, nz}; // face the action cell
            if (!losWalkable(world, foot, node)) break;                        // don't aim past a wall
            double seg = Math.hypot(nx - cx, nz - cz);
            if (seg < 1e-6) { tx = nx; tz = nz; continue; }
            if (remaining <= seg) {
                double t = remaining / seg;
                return new double[]{cx + (nx - cx) * t, cz + (nz - cz) * t};
            }
            remaining -= seg;
            cx = nx; cz = nz; tx = nx; tz = nz;
        }
        return new double[]{tx, tz};
    }
}
