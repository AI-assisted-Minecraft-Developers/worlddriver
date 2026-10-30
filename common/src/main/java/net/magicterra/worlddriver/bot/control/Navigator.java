package net.magicterra.worlddriver.bot.control;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.GOAL_SNAP_RADIUS;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.magicterra.worlddriver.bot.control.act.Actuate;
import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.control.act.Safety;
import net.magicterra.worlddriver.bot.control.controller.Arbiter;
import net.magicterra.worlddriver.bot.control.controller.Channel;
import net.magicterra.worlddriver.bot.control.controller.Digging;
import net.magicterra.worlddriver.bot.control.controller.DrownEscape;
import net.magicterra.worlddriver.bot.control.controller.ParkourPlace;
import net.magicterra.worlddriver.bot.control.controller.Pillar;
import net.magicterra.worlddriver.bot.control.controller.Recovery;
import net.magicterra.worlddriver.bot.control.controller.RouteDig;
import net.magicterra.worlddriver.bot.control.controller.VineClimb;
import net.magicterra.worlddriver.bot.control.controller.WaterClimb;
import net.magicterra.worlddriver.bot.control.medium.MediumLocomotor;
import net.magicterra.worlddriver.bot.control.route.ProgressPhase;
import net.magicterra.worlddriver.bot.control.route.RepathPhase;
import net.magicterra.worlddriver.bot.control.route.Route;
import net.magicterra.worlddriver.bot.control.route.RouteAdoption;
import net.magicterra.worlddriver.bot.control.route.SearchPhase;
import net.magicterra.worlddriver.bot.control.sense.PreludePhase;
import net.magicterra.worlddriver.bot.control.sense.StallDetectPhase;
import net.magicterra.worlddriver.bot.control.steer.AimPhase;
import net.magicterra.worlddriver.bot.control.steer.DrivePhase;
import net.magicterra.worlddriver.bot.control.steer.EdgeGuardsPhase;
import net.magicterra.worlddriver.bot.control.steer.StandToWork;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.planning.PathTraceHolder;
import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.constraints.NoBreak;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.Equipment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

public final class Navigator implements Locomotor {
    // Water-bucket (MLG) fall handling lives in the always-on {@link
    // ClutchController} the bot ticks, so an unplanned fall self-rescues
    // whether or not a Navigator is driving. The Navigator only SIGNALS a planned fall
    // (MoveSignals.planClutch) as it steps off a fallBucket lip and biases the
    // step-off keys (walk-speed, no sprint) so the bot drops near-vertically.
    // Other tuning lives in BotConfig (mutable via mc.bot.setting).

    /** This navigator's own event counts (searches, recovery hops, digs) — see {@link NavigatorTallies}. */
    public final NavigatorTallies tallies = new NavigatorTallies();

    private final NavigatorInspector inspector = new NavigatorInspector(this);

    /** The telemetry of the body this navigator last ticked; a private sink until the first tick. */
    public NavigatorTelemetry tel = new NavigatorTelemetry();

    /** The chain or verb this navigator moves for — see {@link NavigatorSettings#owner}. */
    public final String owner;

    public Goal goal;
    /**
     * Per-intent search profile (bias + capability + constraints) forwarded to every PathFinder this Navigator builds
     * (A4a bias-only; A2a full profile). {@link SearchProfile#NONE} = plain navigation; IntentProcess sets it from the
     * Intent.
     */
    public SearchProfile profile = SearchProfile.NONE;
    /**
     * Cached from {@link #profile} in {@link #useProfile}: does the current profile carry a {@link NoBreak} (a per-goto
     * {@code forbidDig})? Cached so the execution-layer dig gate ({@link #mayBreak}) is a field read, not a per-tick
     * constraint scan at every fallback site.
     */
    private boolean profileForbidsBreak = false;

    /** Deep-search budget (nodes, ms); -1 = the configured one. See {@link NavigatorSettings#withSearchBudget}. */
    final int searchMaxNodes;

    final long searchMaxMs;

    /**
     * The bot the last {@link #tick(Body, WorldView)} drove, for the finders' scope source. Null until the first tick,
     * which is also the first time a search can start.
     */
    LivingEntity body;

    /**
     * That bot's equipment and block interaction, or {@link NoHands} when it lacks either — never null, so the tick
     * phases' dig and place sites read and drive them without a guard each.
     */
    public Equipment equipment = NoHands.INSTANCE;

    public BlockInteraction blocks = NoHands.INSTANCE;

    public boolean
            goalSnapChecked; // one-shot per goal: snap an unstandable Goal.Block target to the nearest standable cell
    // (see snapGoalToStandable) — needs a live WorldView so it runs on the first tick, not at
    // start
    /** The plan and the searches for it; the only writer of either. */
    public final Route route = new Route();
    /** The guards that get the last word on a walking tick's input. */
    public final Safety safety = new Safety(this);
    /** The jump, sneak and forward keys, with the log of where each jump came from. */
    public final Keys keys = new Keys(this);
    /** The tick's phases in pipeline order ({@link #tickInner}); each holds what it keeps from one tick to the next. */
    public final PreludePhase prelude = new PreludePhase();

    public final StallDetectPhase stallDetect = new StallDetectPhase();
    public final RepathPhase repath = new RepathPhase();
    public final SearchPhase search = new SearchPhase();
    public final ProgressPhase progress = new ProgressPhase();
    public final EdgeGuardsPhase edgeGuards = new EdgeGuardsPhase();
    public final AimPhase aim = new AimPhase();
    public final DrivePhase drive = new DrivePhase();
    /** The A* result before stringPull, as a tally — see {@link NavigatorInspector#rawPlanTally()}. */
    public String rawPlan;

    public int stuckTicks;
    public int totalTicks;
    public int actionTicks; // ticks spent on the current break/place edge
    /** The controllers, each holding its own state; the phases read some of it through these fields. */
    public final WaterClimb waterClimb = new WaterClimb();

    public final Pillar pillar = new Pillar();
    /** The back-off and the burst that move a stuck bot; asked for by the phases, driven by {@link #arbiter}. */
    public final Recovery recovery = new Recovery();
    /** Breaking and placing the route's own blocks; its work this tick tells the walk how to stand. */
    final RouteDig routeDig = new RouteDig();
    /** Who drives which channel this tick; {@link Arbiter#snapshot} says. */
    public final Arbiter arbiter = new Arbiter(
            List.of(new DrownEscape(), recovery, waterClimb, pillar, new ParkourPlace(), new VineClimb(), routeDig));
    // --- expectation alarms (navigatorExpectAlarm): live actual-vs-expected divergence detectors ---
    // Extracted to ExpectAlarms (all the ex* sentinel state + the observers) — purely
    // observational, never drives movement. Fed via exAlarms.tick / .notePlace / .noteRepathFlip.
    public final ExpectAlarms exAlarms = new ExpectAlarms();
    /**
     * Why the walk failed, or why a branch last threw the route away; the failing branch's text is what
     * {@link #result()} reports.
     */
    public String error;
    /** How the last walk ended, written once by {@link #terminalReport}. Volatile: status threads read. */
    private volatile WalkResult result = WalkResult.NONE;
    /**
     * True once snapGoalToStandable() rewrote the requested Goal.Block to a nearby standable cell — arrival then means
     * "arrived NEAR", not "arrived AT".
     */
    public boolean goalSnapped;

    /** The body the tick drives, recording movement into this tick's frame; see {@link Actuate}. */
    private final Actuate.FrameBody frameBody = new Actuate.FrameBody();

    /**
     * Which branch commanded THIS tick's jump (null = no jump commanded). Pure telemetry, reset each tick in the
     * prelude: fall post-mortems keep needing "who launched the fatal arc?" (bridge battery: three different launchers
     * over three rounds), and the aggregated drive jump erases the answer by the time the bot is airborne.
     */
    public String jumpTag;

    /**
     * This tick's aim-tree owner ({@code aimSrc}) — same telemetry channel as {@link NavigatorInspector#jumpTag}: wedge
     * post-mortems need "who owned the heading".
     */
    public String aimTag;

    /**
     * This tick's drive command (target yaw + impulse) as a compact string; null when the tick never reached the drive
     * tail (early-return branch) — which is itself the answer a pinned-bot autopsy is after. Reset each tick in the
     * prelude.
     */
    public String driveTag;
    /** The walk of a body living in water or air, which takes every order and tick while it exists. */
    private MediumLocomotor medium;
    /** The readings a diagnosis wants: the plan, the step, the per-tick tags. */
    public NavigatorInspector inspect() {
        return inspector;
    }

    public Navigator() {
        this(NavigatorSettings.DEFAULT);
    }

    public Navigator(NavigatorSettings settings) {
        this.owner = settings.owner();
        this.searchMaxNodes = settings.searchMaxNodes();
        this.searchMaxMs = settings.searchMaxMs();
        useProfile(settings.profile());
    }

    /**
     * Single construction point for this Navigator's deep-search PathFinders so the per-Navigator budget override,
     * tuning and scope source apply to every search launch site alike (foot repath, commit-end continuation,
     * place-suppressed re-plan). See {@link NavigatorFinders}.
     */
    public PathFinder newPathFinder(WorldView world) {
        return NavigatorFinders.deep(this, world);
    }

    /** Search with {@code p} from now on. Null → {@link SearchProfile#NONE}. */
    private void useProfile(SearchProfile p) {
        this.profile = p == null ? SearchProfile.NONE : p;
        boolean forbids = false;
        for (Constraint c : this.profile.constraints()) {
            if (c instanceof NoBreak) {
                forbids = true;
                break;
            }
        }
        this.profileForbidsBreak = forbids;
    }

    /**
     * True when the executor MAY mutate the world to recover (the discretionary dig FALLBACKS: wallDig, the swim/bank
     * climb-out riser, the lily-pad ram). Honors BOTH the global {@link WorldConfig#allowBreak} master switch AND the
     * per-intent {@link NoBreak} constraint (a per-goto {@code forbidDig}): an executor recovery must never exceed the
     * world-mutation authority the planner was given. Before this, the fallbacks checked only the global switch, so a
     * {@code forbidDig} bot pinned against terrain still tunnelled (survival day6: a leashed {@code goto entityId
     * forbidDig:true} carved back underground; the live workaround was to kill global {@code allowBreak}, which also
     * killed legitimate planned digs). Planned break edges are NOT gated here — {@code NoBreak} already prunes them at
     * the planner. The single-head-cell anti-suffocation dig (pillarRecover) is DELIBERATELY exempt and stays on
     * {@code allowBreak} only: suffocation is death, {@code forbidDig} is a navigation preference — safety wins.
     */
    public boolean mayBreak() {
        return WorldConfig.allowBreak && !profileForbidsBreak;
    }

    @Override
    public WalkResult result() {
        return medium != null ? medium.result() : result;
    }

    /**
     * Re-aim an in-progress pursuit at a quarry that moved, WITHOUT telling the navigator this is a new journey.
     * {@link #start} clears the futile-search governor, which is right for a fresh goto and wrong for a chase: a caller
     * that re-goals every time its quarry changes block zeroes the counter faster than the counter can reach its cap,
     * so the guard that exists to stop a bot burning a full A* per tick toward something it cannot reach never fires.
     * Measured on a chase of a descending goal: 240 ticks produced 240 full searches and a counter that never passed 1.
     * Everything else about the goal genuinely IS new, so this is {@code start} plus the six fields that describe "how
     * this pursuit has been going" rather than "which cell we want" — including the backoff, since taking away an armed
     * cooldown is the same leak one cap below.
     *
     * <p>A search already running from the feet is kept too: the futile judge counts only landed searches, and a quarry
     * pacing in a pen re-aims faster than a full search lands, so dropping it leaves a chase that never lands one —
     * never counted, never routed. A continuation from the segment end goes, since the path it would extend is cleared.
     */
    @Override
    public void retarget(Goal g) {
        double bestDist = search.gov.futileBestDist;
        BlockPos bestFoot = search.gov.futileFoot;
        Goal yardstick = search.gov.futileGoal;
        BlockPos latchFoot = search.gov.futileLatchFoot;
        int searches = search.gov.futileSearches;
        int backoff = search.gov.searchBackoffTicks;
        PathFinder.Search running = route.seg.searchFromEnd ? null : route.seg.activeSearch;
        boolean placeSuppressed = route.seg.searchSuppressedPlace;
        start(g);
        search.gov.futileBestDist = bestDist;
        search.gov.futileFoot = bestFoot;
        search.gov.futileGoal = yardstick;
        search.gov.futileLatchFoot = latchFoot;
        search.gov.futileSearches = searches;
        search.gov.searchBackoffTicks = backoff;
        if (running != null) {
            route.searchFromFoot(running, placeSuppressed);
        }
        if (medium != null) {
            medium.retarget(g);
        }
    }

    @Override
    public void start(Goal g) {
        this.goal = g;
        this.goalSnapChecked = false;
        this.goalSnapped = false;
        this.result = WalkResult.NONE;
        reset(ResetScope.GOAL, g == null ? "cancelled" : "new goal");
        if (medium != null) {
            medium.start(g);
        }
    }

    private void reset(ResetScope scope, String why) {
        stuckTicks = 0;
        actionTicks = 0;
        arbiter.reset(scope, why);
        prelude.reset(scope);
        stallDetect.reset(scope);
        repath.reset(scope);
        search.reset(scope);
        progress.reset(scope);
        aim.reset(scope);
        drive.reset(scope);
        if (scope == ResetScope.REPLAN) {
            route.clear();
            return;
        }
        route.restart();
        totalTicks = 0;
        safety.guardPlugCell = null; // plug arming is per-journey; a stale cell must not pre-arm the next goal
        safety.guardPlugFires = 0;
        // The pin itself is kept: it is about the lip the bot stands at, not the goal, and releasing a
        // hold mid-overhang dropped the bot (r9). A walk that ends lets go of it in Safety#stand.
        error = null;
    }

    /**
     * If the goal is an exact-cell {@link Goal.Block} whose target is NOT standable per {@code world.canStandAt},
     * replace it with a Goal.Block on the nearest standable cell within {@link #GOAL_SNAP_RADIUS}. This is the fix for
     * a random/long-distance goto landing its target inside terrain or a sub-stand pocket: A* can never accept the
     * unstandable cell as the goal, so it burns its whole node budget every repath (~5 s freeze) while the bot
     * oscillates at the cell and drifts into nearby water. Snapping to a cell the pathfinder itself considers standable
     * lets the search terminate and the bot settle at the closest reachable spot. Uses the SAME predicate the planner
     * uses, so planning and arrival ({@code goal.reached}) stay consistent. No-op for standable targets, non-Block
     * goals (Near/XZ already tolerate it), or when nothing standable is near (then the goal is unchanged — fails as
     * before, never worse).
     */
    public void snapGoalToStandable(WorldView world, BlockPos foot) {
        if (!(goal instanceof Goal.Block b)) {
            return;
        }
        BlockPos t = b.target();
        if (world.canStandAt(t)) {
            return; // already fine — leave it
            // A dive goal sits under the surface on purpose. Under the surface-node model that cell is
            // not standable, and snapping it up would walk the diver to the wrong place.
        }
        if (WorldConfig.pathfinderSurfaceWaterNodes
                && world.isWater(t)
                && profile.capability().allowsOptIn(net.magicterra.worlddriver.bot.rules.Capability.DIVE)) {
            return;
        }
        // navigatorPillarReachGoalNoSnap: don't snap an elevated AIR goal DOWN when it's reachable
        // by PILLARING up. canStandAt(t) here fails only because t's floor is air — but pillaring
        // creates that floor, so the goal IS reachable. Snapping it to the highest currently-
        // standable cell makes the bot stop 1+ blocks short (live 2026-06-27 summitArena: goal
        // y233 → snapped to standable y232, bot pillars 2 then arrives at y232, never pillars the
        // last block, so an uphill goal in the air is never reached). Skip the snap so A* keeps the real goal and finds
        // the
        // pillarUp path. Guard against a truly FLOATING void goal (no base to pillar from): require
        // the goal cell + head to be passable AND a solid base within a few blocks below.
        if (ControlConfig.navigatorPillarReachGoalNoSnap
                && WorldConfig.allowPlace
                && !world.isSolid(t)
                && !world.isSolid(t.above())) {
            boolean baseBelow = false;
            for (int d = 1; d <= 5; d++) {
                if (world.isSolid(t.offset(0, -d, 0))) {
                    baseBelow = true;
                    break;
                }
            }
            if (baseBelow) {
                return; // pillar-reachable elevated goal — let A* pillar up to it
            }
        }
        BlockPos best = null;
        long bestToTarget = Long.MAX_VALUE;
        long bestToFoot = Long.MAX_VALUE;
        for (int dy = -GOAL_SNAP_RADIUS; dy <= GOAL_SNAP_RADIUS; dy++) {
            for (int dx = -GOAL_SNAP_RADIUS; dx <= GOAL_SNAP_RADIUS; dx++) {
                for (int dz = -GOAL_SNAP_RADIUS; dz <= GOAL_SNAP_RADIUS; dz++) {
                    BlockPos c = t.offset(dx, dy, dz);
                    if (!world.canStandAt(c)) {
                        continue;
                    }
                    long dT = (long) c.distSqr(t);
                    if (dT > bestToTarget) {
                        continue;
                    }
                    long dF = (long) c.distSqr(foot);
                    // Nearest to the target wins; ties broken toward the bot so the snap
                    // doesn't pick a standable cell on the far side of the obstruction.
                    if (dT < bestToTarget || dF < bestToFoot) {
                        bestToTarget = dT;
                        bestToFoot = dF;
                        best = c;
                    }
                }
            }
        }
        if (best != null && !best.equals(t)) {
            if (GameConfig.navigatorDebug) {
                LOG.info(
                        "[navigator] goal snap: unstandable target {} → nearest standable {} (d={})",
                        t,
                        best,
                        String.format(Locale.ROOT, "%.1f", Math.sqrt(bestToTarget)));
            }
            this.goal = new Goal.Block(best);
            this.goalSnapped = true;
        }
    }

    /**
     * Drop the cached path (keep the goal) so the next {@link #tick} recomputes from the current position. Used when a
     * movement process is resumed after being preempted by a higher-priority chain — during the suspension the bot may
     * have been knocked back or the terrain changed, so reusing the stale path would walk into a wall. See
     * ProcessScheduler / UserTaskChain.onResume.
     */
    @Override
    public void replan() {
        reset(ResetScope.REPLAN, "replan");
        if (medium != null) {
            medium.replan();
        }
    }

    @Override
    public void replan(SearchProfile profile) {
        useProfile(profile);
        replan();
    }

    /**
     * Replay a fixed archived plan. Caller has already teleported the bot to the plan start and restored the block
     * envelope. Disables A* entirely — the plan executes with real physics; a wedge is recorded, not re-planned:
     * whatever would have dropped the plan ends the walk instead, and a plan the adoption refuses ends it on the first
     * tick.
     */
    public void replay(
            WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, BlockPos startFoot) {
        start(endGoal);
        route.enterReplay();
        Route.Adoption a =
                RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), world, startFoot);
        if (!a.adopted()) {
            route.drop("replay plan refused: " + a.refusal());
        }
    }

    /**
     * TEST SEAM — adopt a hand-built plan with the LIVE recovery/repath machinery left ON (unlike {@link #replay},
     * which puts the route in replay and disables A*), then point {@code step} at an arbitrary mid-plan node. The
     * harness uses this to force the exact step-pointer-mismatch STATE the live wedge lands in — bot grounded N blocks
     * off a descend node — INDEPENDENT of how the bot drifted there live (which a momentum overshoot on a synthetic 2-D
     * arena could not reliably reproduce). Because the route is not in replay false, the real
     * {@code fellOffPath}/{@code safetyRepath}/blacklist recovery runs, giving the clean A/B the live wedge couldn't.
     * Pass {@code foot==null} so {@link RouteAdoption#adopt} does NOT fast-forward/re-anchor the step it just set. Not
     * wired to any transport; test-only.
     */
    public void beginScriptedFollow(
            WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, int startStep) {
        start(endGoal);
        RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, false, 0, 0L, 0.0), world, null);
        route.seek(startStep);
        progress.stepProg.noProgressStep = -1; // force a fresh noStepProgressTicks baseline at the injected step
        progress.stepProg.noStepProgressTicks = 0;
    }

    /**
     * How many NODES the current path holds, <b>including the one the bot starts on</b>.
     *
     * <p><b>Nodes, not steps.</b> {@link #edges} is index-aligned with {@link #path} — {@code edges .get(i)} is the
     * edge that ENTERS {@code path.get(i)} — so node 0 is where the walk began and carries no traversal of its own. A
     * path of N nodes is <b>N−1 moves</b>, and reading it as "N blocks" overstates the reach of every plan by one. It
     * is also NOT a distance: a single {@code fall7} or a smoothed diagonal covers several blocks in one node.
     *
     * <p><b>The last node is the goal only when the search reached it.</b> {@code seg.pathBestEffort =
     * !res.goalReached()} — a best-effort partial ends at the closest node A* could get to, which for a bot on a small
     * island is its own edge. So "the last node of the path" and "the destination" are different questions and
     * {@link NavigatorInspector#planTally()} answers the first one.
     */
    @Override
    public int pathLen() {
        return medium != null
                ? medium.pathLen()
                : route.path() == null ? 0 : route.path().size();
    }

    @Override
    public int pathStep() {
        return medium != null ? medium.pathStep() : route.step();
    }

    /**
     * TEST SEAM — run {@link RouteAdoption#adopt} with an explicit {@code foot} so a harness can exercise the segment
     * anchor-gate (the {@code mis-anchored segment} reject vs the deep-water-float open-water bee-line exemption)
     * deterministically, INDEPENDENT of the full continuation/repath machinery the headless server-sim doesn't
     * reproduce for this live water dead-stop. @return the adoption's verdict: true = ACCEPTED (segment adopted, the
     * bot will drive it), false = REJECTED as mis-anchored.
     */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, BlockPos foot) {
        return adoptForTest(world, plan, planEdges, foot, false);
    }

    /**
     * {@link #adoptForTest} for a scene that goes on to TICK the navigator. The 4-arg form declares the plan
     * BEST-EFFORT, and a best-effort segment is one the navigator is entitled to replace: it kicks off a continuation
     * search, adopts the result, and the synthetic edges are gone. That is how
     * {@code wd.surfacePillarPointerNeedsItsSupport} lost its subject on tick ONE — its terminal read
     * {@code path-consumed}, a class {@link WalkVerdict#classifyArrival} can only return once
     * {@code seg.pathBestEffort} is false, and {@link RouteAdoption#adopt} is its only writer. A synthetic plan whose
     * last node IS the goal has to say so, or it does not survive its own first tick.
     */
    public boolean adoptForTest(
            WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, BlockPos foot, boolean goalReached) {
        return RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, goalReached, 0, 0L, 0.0), world, foot)
                .adopted();
    }

    /**
     * Hand this navigator a previewed route ({@code mc.bot.goto} with {@code planId}). Feed the RAW search result,
     * never the straightened route a preview showed: adoption straightens on its own, cuts a best-effort tail, and
     * skips the prefix the bot already walked, so a pre-straightened route would be straightened twice. A preview whose
     * first stretch has been built over comes back refused, which is the caller's cue to let the navigator search as
     * usual.
     *
     * @param foot where the bot stands now; the route is anchored to its nearest prefix node
     * @return false when the route was refused (mis-anchored or stale)
     */
    public boolean adoptPlan(PathFinder.Result raw, WorldView world, BlockPos foot) {
        if (raw == null || raw.path().isEmpty()) {
            return false;
        }
        return RouteAdoption.adopt(this, raw, world, foot).adopted();
    }

    /** Decide the tick against a recording body, then hand what it decided to the real one. */
    @Override
    public Status tick(Body body, WorldView world) {
        Actuate.FrameBody a = frameBody.begin(body);
        try {
            return decide(a, world);
        } finally {
            Actuate.apply(a.frame, body);
        }
    }

    private Status decide(Body a, WorldView world) {
        body = a.entity();
        if (body != null) {
            tel = NavigatorTelemetry.of(body);
        }
        equipment = a.equipment().orElse(NoHands.INSTANCE);
        blocks = a.blockInteraction().orElse(NoHands.INSTANCE);
        if (goal == null) {
            result = new WalkResult(
                    Status.FAILED,
                    "no goal: the walk was never started, or was cancelled",
                    "failed:no goal",
                    false,
                    -1,
                    false);
            return Status.FAILED;
        }
        if ((medium = MediumLocomotor.forBody(this, medium, world, goal)) != null) {
            return medium.tick(a, world);
        }
        return walk(a, world) == TickStep.WALKING ? Status.MOVING : result.status();
    }

    /** One tick of a body that walks: the phases, then {@link Safety} on a tick that keeps walking. */
    private TickStep walk(Body a, WorldView world) {
        // Single-exit wrapper: tickInner() has dozens of early returns (pillar, dig, escape,
        // stepUp...), so a safety invariant appended to its tail only covers SOME ticks — the
        // gap #53 death strode over a well mouth from a branch that never reached it. Run the
        // stride floor-guard here, after EVERY decision path, before the avatar integrates.
        TickStep s = tickInner(a, world);
        drive.settleDigKey(this, a); // a navigator-held attack key outlives its dig by DIG_KEY_RELEASE_TICKS only
        LivingEntity tp = a.entity();
        tel.lastTickTrace = "step=" + s + " jumpTag=" + (jumpTag == null ? "untagged" : jumpTag)
                + (tp == null
                        ? ""
                        : " pos="
                                + String.format(
                                        java.util.Locale.ROOT, "%.2f,%.2f,%.2f", tp.getX(), tp.getY(), tp.getZ())
                                + String.format(
                                        java.util.Locale.ROOT,
                                        " speedH=%.3f",
                                        tp.getDeltaMovement().horizontalDistance())
                                + " sole="
                                + String.format(
                                        java.util.Locale.ROOT, "%.3f", NavigatorGeometry.soleOnSolid(world, tp)))
                + " node="
                + (route.path() == null
                                || route.step() < 0
                                || route.step() >= route.path().size()
                        ? "none"
                        : route.path().get(route.step()).toShortString());
        if (tp != null && NavigatorGeometry.soleOnSolid(world, tp) > 0.0) {
            tel.lastSupportedTrace = tel.lastTickTrace;
        }
        if (s == TickStep.WALKING) {
            safety.run(a, world);
        } else {
            safety.stand(a);
        }
        return s;
    }

    private TickStep tickInner(Body a, WorldView world) {
        TickCtx c = new TickCtx();
        TickStep r;
        r = prelude.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = stallDetect.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = repath.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = search.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = progress.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        // The route is settled for this tick; now the controllers.
        Set<Channel> taken = arbiter.tick(this, c, a, world);
        if (!taken.contains(Channel.HANDS)) {
            blocks.breakHold(false); // nothing is breaking or placing this tick
            actionTicks = 0;
        }
        // The regular walk cannot yet drive only the channels no controller took: a controller on MOVE
        // ends the tick's decisions here, and one on LOOK leaves the walk standing to let it work.
        if (taken.contains(Channel.MOVE)) {
            return TickStep.WALKING;
        }
        if (taken.contains(Channel.LOOK)) {
            return StandToWork.run(this, c, a, routeDig.work());
        }
        r = edgeGuards.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = aim.run(this, c, a, world);
        if (r != null) {
            return r;
        }
        r = drive.run(this, c, a, world);
        if (r == TickStep.WALKING) {
            Digging.preDigNextBreak(this, a, world);
        }
        return r;
    }

    /**
     * End the walk: stamp {@link #result} and fire the route trace's terminal, so every terminal return site stays a
     * one-liner. {@code foot} may be null (no player) → conservatively not reached.
     */
    public TickStep terminalReport(
            TickStep s, PathTrace.Outcome outcome, String reason, String endReason, BlockPos foot) {
        boolean reached = foot != null && goal != null && !goalSnapped && goal.reached(foot);
        double dist = foot != null && goal != null ? goal.estimate(foot) : -1;
        result = WalkVerdict.of(
                s == TickStep.FAILED, reason, reached, endReason, dist, goalSnapped, goal != null && goal.open());
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /**
     * Stopped from outside before the walk ended (mc.bot.cancel, superseded by a new goto, the bot left the dimension).
     * Fires the trace's CANCELLED terminal so per-session observers finalize the partial run (a path archive is flushed
     * for a wedge the agent cancelled out of) — a no-op for the recorders if no session is open, so a cancel after a
     * natural end is harmless — then drops the goal and everything about the walk.
     */
    @Override
    public void cancel(String reason) {
        PathTraceHolder.SINK.onTerminal(PathTrace.Outcome.CANCELLED, reason);
        start(null);
    }
}
