package net.magicterra.worlddriver.bot.control;

import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.body.BlockInteraction;
import net.magicterra.worlddriver.game.body.Equipment;
import net.magicterra.worlddriver.bot.control.BotLevelHolder;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.planning.PathTraceHolder;
import net.magicterra.worlddriver.bot.rules.SearchProfile;
import net.magicterra.worlddriver.bot.rules.Constraint;
import net.magicterra.worlddriver.bot.rules.constraints.NoBreak;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

import static net.magicterra.worlddriver.bot.control.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import java.util.Locale;
import java.util.Set;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.control.WalkerGeometry.*;

public final class Walker implements Locomotor {
    /** What a tick phase decided; {@link #tick} turns it into a {@link Locomotor.Status}. */
    enum Step { WALKING, ARRIVED, FAILED }
    // Water-bucket (MLG) fall handling lives in the always-on {@link
    // ClutchController} the bot ticks, so an unplanned fall self-rescues
    // whether or not a Walker is driving. The Walker only SIGNALS a planned fall
    // (MoveSignals.planClutch) as it steps off a fallBucket lip and biases the
    // step-off keys (walk-speed, no sprint) so the bot drops near-vertically.
    // Other tuning lives in BotConfig (mutable via mc.bot.setting).

    /** This walker's own event counts (searches, recovery hops, digs) — see {@link WalkerTallies}. */
    final WalkerTallies tallies = new WalkerTallies();

    private final WalkerInspector inspector = new WalkerInspector(this);
    /** The readings a diagnosis wants: the plan, the step, the per-tick tags. */
    public WalkerInspector inspect() { return inspector; }

    /** The telemetry of the body this walker last ticked; a private sink until the first tick. */
    WalkerTelemetry tel = new WalkerTelemetry();

    /** The chain or verb this walker moves for — see {@link WalkerSettings#owner}. */
    final String owner;

    public Walker() { this(WalkerSettings.DEFAULT); }

    public Walker(WalkerSettings settings) {
        this.owner = settings.owner();
        this.searchMaxNodes = settings.searchMaxNodes();
        this.searchMaxMs = settings.searchMaxMs();
        useProfile(settings.profile());
    }

    Goal goal;
    /** Per-intent search profile (bias + capability + constraints) forwarded to
     *  every PathFinder this Walker builds (A4a bias-only; A2a full profile).
     *  {@link SearchProfile#NONE} = plain navigation; IntentProcess sets it from the Intent. */
    SearchProfile profile = SearchProfile.NONE;
    /** Cached from {@link #profile} in {@link #useProfile}: does the current profile carry a
     *  {@link NoBreak} (a per-goto {@code forbidDig})? Cached so the execution-layer dig gate
     *  ({@link #mayBreak}) is a field read, not a per-tick constraint scan at every fallback site. */
    boolean profileForbidsBreak = false;

    /** Deep-search budget (nodes, ms); -1 = the configured one. See {@link WalkerSettings#withSearchBudget}. */
    final int searchMaxNodes;
    final long searchMaxMs;

    /** Single construction point for this Walker's deep-search PathFinders so the
     *  per-Walker budget override, tuning and scope source apply to every search launch site
     *  alike (foot repath, commit-end continuation, place-suppressed re-plan). See {@link WalkerFinders}. */
    PathFinder newPathFinder(WorldView world) { return WalkerFinders.deep(this, world); }

    /** The bot the last {@link #tick(Body, WorldView)} drove, for the finders' scope source.
     *  Null until the first tick, which is also the first time a search can start. */
    LivingEntity body;

    /** That bot's equipment and block interaction, or {@link WalkerNoHands} when it lacks either —
     *  never null, so the tick phases' dig and place sites read and drive them without a guard each. */
    Equipment equipment = WalkerNoHands.INSTANCE;
    BlockInteraction blocks = WalkerNoHands.INSTANCE;

    /** Search with {@code p} from now on. Null → {@link SearchProfile#NONE}. */
    private void useProfile(SearchProfile p) {
        this.profile = (p == null) ? SearchProfile.NONE : p;
        boolean forbids = false;
        for (Constraint c : this.profile.constraints()) if (c instanceof NoBreak) { forbids = true; break; }
        this.profileForbidsBreak = forbids;
    }

    /** True when the executor MAY mutate the world to recover (the discretionary dig FALLBACKS:
     *  wallDig, the swim/bank climb-out riser, the lily-pad ram). Honors BOTH the global
     *  {@link WorldConfig#allowBreak} master switch AND the per-intent {@link NoBreak} constraint
     *  (a per-goto {@code forbidDig}): an executor recovery must never exceed the world-mutation
     *  authority the planner was given. Before this, the fallbacks checked only the global switch,
     *  so a {@code forbidDig} bot pinned against terrain still tunnelled (survival day6: a leashed
     *  {@code goto entityId forbidDig:true} carved back underground; the live workaround was to kill
     *  global {@code allowBreak}, which also killed legitimate planned digs). Planned break edges
     *  are NOT gated here — {@code NoBreak} already prunes them at the planner. The single-head-cell
     *  anti-suffocation dig (pillarRecover) is DELIBERATELY exempt and stays on {@code allowBreak}
     *  only: suffocation is death, {@code forbidDig} is a navigation preference — safety wins. */
    boolean mayBreak() {
        return WorldConfig.allowBreak && !profileForbidsBreak;
    }
    /** Ticks the pointer has been HELD on a final node that is the goal while the foot is not yet
     *  in it (walkerHoldLastNodeUntilStanding); keyed to the path and step it counts for. */
    int finalNodeHold;
    Object finalNodeHoldPath;
    int finalNodeHoldStep = -1;
    boolean goalSnapChecked;   // one-shot per goal: snap an unstandable Goal.Block target to the nearest standable cell (see snapGoalToStandable) — needs a live WorldView so it runs on the first tick, not at start
    /** The plan and the searches for it; the only writer of either. */
    final Route route = new Route();
    /** The guards that get the last word on a walking tick's input. */
    final Safety safety = new Safety(this);
    /** The A* result before stringPull, as a tally — see {@link WalkerInspector#rawPlanTally()}. */
    String rawPlan;
    /** The first parkour takeoff this walk ever made — see WalkerTickDrive's latch. */
    String parkourTakeoff;
    int ticksSinceRepath;
    int stuckTicks;
    /** Physical stall clock (§84): XZ-anchor ticks-without-displacement, fully
     *  DECOUPLED from path/step/repath — the acceptance rig's "worst movement
     *  stall" metric mirrored into the executor. WHY: a safety-repath loop swaps
     *  the path every ~3s and each swap fires stepWindowFresh → stuckTicks=0, so
     *  a physically frozen bot never accumulates past the stuckT>40 gates and
     *  every stuck-gated recovery (wall-dig, ram-release) starves — the canopy
     *  jump-ram pin (C97-J1 replay: 1200t frozen, leaves one instabreak punch
     *  away, attack=false the whole time) is the third occurrence of this class
     *  (§71 wall-pin, §80 pin were the first two, masked there because the path
     *  happened to be stable). Anchor resets on >1.5 XZ blocks moved; vertical
     *  bob (jump-ram) deliberately does not count as movement.
     *  <p>Physical-stall anchor (task#96 step B7), owned by WalkerTickProgress; the stall
     *  verdict is read by the aim/drive gates. Self-managing (re-anchors on real motion)
     *  — no journey reset ever touched it. */
    final PhysicalStall physStall = new PhysicalStall();
    static final class PhysicalStall {
        double anchorX = Double.NaN, anchorZ;
        int stallTicks;
    }
    int totalTicks;
    int actionTicks;          // ticks spent on the current break/place edge
    /** Sticky planned-break dig (task#96 step B7): held across ticks so an interleaved
     *  travel tick can't release attack (a single released tick resets vanilla mining
     *  progress to zero; C28-J1 DIG-slow @-258,81,338: 200t of dig ticks interleaved with
     *  attack=false travel ticks never completed one block). Engaged by Climb/Drive,
     *  released by WalkerTickPrelude when the planned break is gone; deliberately NO
     *  journey reset (release is world-state-driven). */
    final StickyDig stickyDig = new StickyDig();
    boolean digDrivenThisTick, digKeyOwned;   // a dig drove the avatar this tick / the dig latch is a walker dig's (see DIG_KEY_RELEASE_TICKS)
    int digIdleTicks;                         // consecutive tick-ends with the latch owned and no dig driven
    static final class StickyDig {
        BlockPos pos;         // walkerStickyDig: planned-break cell being mined
        int ticks;            // watchdog for pos
        // PROGRESS-AWARE release (2026-07-21, Mountains bank live + 23 RELEASE
        // loops): a fixed tick cap cannot work because vanilla stacks the x5 (eye in
        // water) and x5 (airborne) dig penalties MULTIPLICATIVELY — a swimAshoreClimb
        // bank dig needs 450t (dirt) to 3750t (stone, wrong tool). A 150t cap released
        // every such dig with the block still solid, reset progress to zero, and the
        // walker re-acquired the same cell forever. Tracking the actual destroy
        // progress holds while it climbs and releases only on a true stall.
        float lastProgress;   // last observed vanilla destroyProgress (0..1)
        int stallTicks;       // consecutive ticks with no progress increase
        // The drive is on the exact cell and vanilla's crosshair-driven attack pass stands
        // aside for it (ClientIntents), so progress is decoupled from where the eye raycast
        // lands — the bob-reset latch that used to live here is gone with the attack key.
        /**
         * Claim the dig slot for {@code b}. <b>A claim, not a setter.</b>
         *
         * <p>Two walker phases call this every tick — the travel drive with the cell in its way,
         * the water climb-out with its bank riser — and until 2026-08-22 whoever called LAST won.
         * That is not a preference, it is a data loss: vanilla's {@code MultiPlayerGameMode} can
         * track exactly ONE destroy target, so a switch runs {@code startDestroyBlock} and throws
         * the accumulated {@code destroyProgress} away.
         *
         * <p>Measured on the real-client ladder, rung 3: the target alternated between y=62 and
         * y=64 every 20–40 ticks while ONE bare-handed afloat block needs 300 (the ×5 in-water and
         * ×5 airborne penalties multiply). Progress peaked at <b>0.81</b> and the rung timed out
         * after 8000 ticks having broken nothing — with the dig hold never once expiring, because
         * the hold was never the thing that was wrong.
         *
         * <p>So an incumbent that has real progress keeps the slot. Releasing is the prelude's job
         * and only the prelude's: it frees the claim when the cell stops being solid, drifts out of
         * reach, or truly stalls. A claim that could also be revoked here would put the release
         * policy in two places, which is how the fixed-clock release outlived the lesson that
         * killed it.
         */
        void engage(net.minecraft.core.BlockPos b) {
            // A LIVE CLAIM HOLDS, whatever its progress currently reads. Two earlier shapes failed:
            // last-caller-wins (no guard at all), and hold-while-lastProgress>0 — the latter written
            // and refuted on the same night (2026-08-22, real-client ladder rung 3). It asked the
            // incumbent to prove itself with the one quantity the challenger had just zeroed, and
            // worse: re-engaging the cell ALREADY held fell through to the reset below and wiped the
            // ticks/stall counters the prelude's release reads. That is why `dig-aim RELEASE` did not
            // fire once in 8022 ticks while the target alternated between y=62 and y=64 forever.
            // The claim is structural now — only the prelude, which owns the release policy, hands
            // the slot over. Re-engaging the incumbent cell is a no-op, not a refresh.
            if (b != null && pos != null) return;
            pos = b;
            ticks = 0;
            lastProgress = 0f;
            stallTicks = 0;
        }

        /** Revoke unconditionally, for a preempting SAFETY dig (suffocation) — that is not a
         *  navigation preference and must not queue behind one. The expiry policy still lives only
         *  in the prelude; this is a named override with a caller, not a second clock. */
        void revoke() { pos = null; ticks = 0; lastProgress = 0f; stallTicks = 0; }
    }
    /** The controllers, each holding its own state; the phases read some of it through these fields. */
    final WaterClimb waterClimb = new WaterClimb();
    final Pillar pillar = new Pillar();
    /** The back-off and the burst that move a stuck bot; asked for by the phases, driven by {@link #arbiter}. */
    final Recovery recovery = new Recovery();
    /** Breaking and placing the route's own blocks; its work this tick tells the walk how to stand. */
    final RouteDig routeDig = new RouteDig();
    /** Who drives which channel this tick; {@link Arbiter#snapshot} says. */
    final Arbiter arbiter = new Arbiter(List.of(new DrownEscape(), recovery, waterClimb, pillar,
            new ParkourPlace(), new VineClimb(), routeDig));
    // --- expectation alarms (walkerExpectAlarm): live actual-vs-expected divergence detectors ---
    // Extracted to WalkerExpectAlarms (all the ex* sentinel state + the observers) — purely
    // observational, never drives movement. Fed via exAlarms.tick / .notePlace / .noteRepathFlip.
    final WalkerExpectAlarms exAlarms = new WalkerExpectAlarms();
    /** Submerged-descent dive latches (task#96 step B), owned by WalkerTickAim; both
     *  cleared on every journey reset via {@link DiveLatches#reset()}. */
    final DiveLatches dive = new DiveLatches();
    static final class DiveLatches {
        int latch;         // ticks left forcing a dive-under-cap (set on a blocked submerged descent; holds the dive through the sink so it doesn't flip-flop)
        int hold;          // ticks left holding an ACTIVE descent (diving) across repaths — a mid-sink repath re-plans from the buoyancy point with a dy=1 first hop, which alone never re-arms diving, so the bot pops back up (round45 water-well live)
        void reset() {
            latch = 0;
            hold = 0;
        }
    }
    final AscendMovement ascendMovement = new AscendMovement();   // task#82 per-move machine (drives only when walkerAscendMovement is ON)
    boolean forceFellOffPath;   // task#82: AscendMovement returned UNREACHABLE/FAILED last delegated tick → OR into fellOffPath (line 1042) so the proven re-route fires
    /** Riser-ram fold counters (task#96 step B7): the four stepUpFreeze/bank-follow
     *  feeders, counted by WalkerTickAim (bankFollowRamTicks also driven in Drive).
     *  All self-zero on their own disengage conditions — no journey reset ever
     *  touched them. */
    final RamFold ramFold = new RamFold();
    static final class RamFold {
        int stepRamStuckTicks;       // GROUNDED ticks ramming an above-node riser (bob-immune; dry OR shallow water) → STEPUP_FREEZE_TICKS engages stepUpFreeze
        int ascentRamBobTicks;       // foot-below-node + lateral-close ticks IGNORING onGround (bob-resettable; steep-bank +1 mount) → ORs into stepUpFreeze (gated walkerAscentRamBobBreak)
        int floatingBankBobTicks;    // FLOATING +1 water-bank: !onGround + foot-below-node + lateral-ram, IGNORING the in/out-water bob → ORs into stepUpFreeze (gated walkerFloatingBankBobFreeze)
        int bankFollowRamTicks;      // FLOATING water-bank lateral ram (ANY node-Y) → drives a slide ALONG the bank toward a mountable exit (gated walkerFloatingBankFollow)
    }
    boolean descending;       // ending creative flight; wait to land before pathing
    /** Water anti-spin governor (task#96 step B9): consumed by the search/stall gates.
     *  {@link GoalSpin#resetForNewGoal()} (start only) clears the baselines;
     *  goalSpin.churnResets deliberately PERSISTS across goals (fresh-baseline grants are a
     *  per-bot budget, not per-journey — do NOT add it to the reset). */
    final GoalSpin goalSpin = new GoalSpin();
    static final class GoalSpin {
        double bestDistToGoal = Double.POSITIVE_INFINITY;
        double bestGoalDist = Double.POSITIVE_INFINITY;  // anti-spin: best goal-estimate across repaths (5-block margin ignores micro-lunges)
        int repathsNoProgress;                           // anti-spin: consecutive in-water repaths that didn't improve goalSpin.bestGoalDist
        int churnResets;                                 // anti-spin: fresh baselines granted after a stall (tolerates water go-arounds; real progress clears it)
        void resetForNewGoal() {
            bestDistToGoal = Double.POSITIVE_INFINITY;
            bestGoalDist = Double.POSITIVE_INFINITY;
            repathsNoProgress = 0;
        }
    }
    /** Land boxed-pocket churn window (task#96 step B4), owned by WalkerTickStallDetect;
     *  Walker's carrot logic reads hColRamTicks (walkerCarrotHColShrink).
     *  {@link BoxedChurn#resetForNewGoal()} runs in start only (replan never
     *  cleared this window); hColRamTicks has NO reset site anywhere — it self-zeroes
     *  on every collision-free tick. The water anti-spin's goalSpin.churnResets stays a loose
     *  field (cross-goal persistence — see its doc). */
    final BoxedChurn churn = new BoxedChurn();
    static final class BoxedChurn {
        BlockPos base;         // foot at the start of the current net-displacement window
        int windowTicks;       // ticks elapsed in the current window
        int escapes;           // consecutive windows that detected churn (escalates the charge radius)
        int hColRamTicks;      // wall-corner: consecutive ticks of sustained horizontalCollision (the signature of the bot pinned against a wall)
        int cycle;             // this window: -1 got nearer the goal, 0 near base, 1 left it, 2 came back to it (WalkerTickStallDetect.trackWindow)
        void resetForNewGoal() {
            base = null;
            windowTicks = 0;
            escapes = 0;
        }
    }
    /** Sticky steep-barrier escalation clock (task#96 step B4): tick is the monotonic
     *  per-walker tick counter (incremented by WalkerTickPrelude, NEVER reset — the
     *  sticky timer compares against it); armed by the churn window (WalkerTickStallDetect)
     *  and the proactive pinch check ({@link #maybeArmPinchEscalation}); read live by this
     *  walker's finders through {@link WalkerFinders}. Both journey resets
     *  {@link EscalationClock#disarm()} so the escalation never leaks into the next goto. */
    final EscalationClock escal = new EscalationClock();
    static final class EscalationClock {
        long tick;             // monotonic per-tick counter (drives the sticky boxed-escalation timer)
        long untilTick;        // escalation armed until this tick (sticky so a few net-progress windows mid-climb don't drop it)
        boolean wasArmed;      // armed on the previous tick, so the debug line marks the edge only
        boolean armed() {
            return tick < untilTick;
        }
        void arm(long stickyTicks) {
            untilTick = tick + stickyTicks;
        }
        void disarm() {
            untilTick = 0;
        }
    }
    /** Per-step progress clocks (task#96 step B6), owned by WalkerTickProgress (the
     *  per-tick updater); their verdicts feed StallDetect's wedge/ram folds and the
     *  aim/drive stall gates, and WalkerTickPrelude forwards noStepProgressTicks to the
     *  expectation alarms. {@link StepProgress#restartWindows()} is the journey reset
     *  (start + replan — exactly the five fields both cleared); adoption and
     *  beginScriptedFollow keep their DIFFERENT partial restarts as direct writes
     *  (adoption re-bases stuckStepHigh to step-1, not -1). The dwell/orbit counters
     *  self-manage on step/path change inside WalkerTickProgress. */
    final StepProgress stepProg = new StepProgress();
    static final class StepProgress {
        double bestStepDist = Double.POSITIVE_INFINITY; // closest approach² to the current node (drives the progress-based stuckTicks)
        int stuckStep = -1;                         // path index bestStepDist tracks; a step change starts a fresh progress window
        int stuckStepHigh = -1;                     // walkerStuckStepMonotonic HIGH-WATER mark: the stall clock only resets when step exceeds this (C33-J2: an oscillating pointer 5↔6 cleared the clock on every "advance" because the retreat re-base LOWERED stuckStep — the high-water mark never goes down within one path)
        int noStepProgressTicks;                    // jitter-immune ticks on the SAME step (resets only when step advances/path changes) → wedge detector
        int noProgressStep = -1;                    // path index noStepProgressTicks tracks (independent of bridge/progress resets)
        double noProgressBestD2 = Double.POSITIVE_INFINITY; // closest-ever approach² to the tracked step; monotonic, so a bob can't reset the wedge timer but a slow water cruise along a long string-pulled edge does
        int crestOrbitTicks;                        // BOB-IMMUNE dwell on a dry stepUp/diagUp CREST step: resets ONLY on step-advance/path-change, never on the 3D new-low the vertical bob fakes on dry land → lets walkerStepUpCrestReach fire on a WIDE bob orbit where noStepProgressTicks keeps zeroing
        int crestOrbitStep = -1;                    // path index crestOrbitTicks tracks
        int rawStepDwellTicks;                      // PURELY step-tracked dwell (resets ONLY on step-change/path-change, NEVER on a 3D new-low) → jitter-immune wedge gate for the steep-ascent slide-back recovery (walkerAscentRamJitterImmune)
        int ramRecoverLastFireDwell = -1;           // rawStepDwellTicks at the last walkerAscentRamJitterImmune fold (-1 = none this episode); a RAM_RECOVER_DEBOUNCE gap caps the fold rate AND re-attempts a stubborn same-step ram; cleared on step/path change
        void restartWindows() {
            bestStepDist = Double.POSITIVE_INFINITY;
            stuckStep = -1;
            stuckStepHigh = -1;
            noStepProgressTicks = 0;
            noProgressStep = -1;
        }
    }
    /** Phase-3: |ds| (blocks of arc-length per tick) at/under which the bot counts as making NO forward
     *  path progress. A normal walk is ~1.0/tick, the slowest legit water-creep still clears ~0.1, so 0.05
     *  flags a true wall-ram (literally pinned) without misfiring on slow-but-moving travel. */
    static final double ARC_WEDGE_DS = 0.05;
    /** Phase-3: consecutive ARC_WEDGE_DS+horizontalCollision ticks before the arc-wedge folds into fellOffPath.
     *  30 ticks (1.5 s) cuts the legacy ~100-tick (5 s) wedge wait by 70% while sitting well past a legit
     *  stepUp/diagUp jump-mount (which presses the riser only ~5-10 t before it tops out and ds jumps). */
    static final int ARC_WEDGE_TICKS = 30;
    /** Phase-3b net-arc-progress window (walkerArcProgressWedge). 40 ticks (2 s): a healthy walk advances ~8 blocks
     *  of arc-length, the slowest legit climb still clears several, so requiring ≥ ARC_PROG_MIN net progress over the
     *  window flags an oscillating/frozen limit cycle (net ≈ 0) without misfiring on slow-but-moving travel. Shorter
     *  than the legacy 100-tick wedge so it recovers ~2.5× sooner. */
    static final int ARC_PROG_WINDOW = 40;
    static final double ARC_PROG_MIN = 2.0;
    // ===== Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow) — DRIVES NOTHING, logged only =====
    /** Arc-length shadow pursuit (task#96 step B8): updated ONLY by {@link #arcShadowTick}
     *  (called per tick from WalkerTickProgress); the wedge/stall verdicts are consumed by
     *  StallDetect/Drive, the projection by Aim/Progress. NO journey reset — everything
     *  self-resets on a path-identity change (shadowPath != path), and Progress clears the
     *  windows on a no-projection tick. */
    final ArcShadow arc = new ArcShadow();
    static final class ArcShadow {
        final PathProjection proj = new PathProjection();  // reusable arc-length projector (the carved-out, fully-readable core; the executor only holds onto its result)
        List<BlockPos> shadowPath;    // path identity the shadow s tracks (reset s/ds when the path object changes)
        double shadowS = Double.NaN;  // last tick's cumulative arc-length (XZ, from path[0] to the foot's polyline projection); for ds/dt + monotonic-violation detection
        long monoViol;                // count of backward-snap events (ds < -0.5) — must stay ~0 on clean runs for the projection to be trustworthy enough to drive in Phase 1
        // Phase-3 (walkerArcLengthWedge): GROUNDED-RAM wedge timer measured in ARC-LENGTH, not 3D-new-low. The legacy
        // descentRamStuck/ascentRamSlide recoveries hang on noStepProgressTicks (a 3D-new-low counter) which the
        // buoyancy bob AND the sub-block ram-jitter (the bot sliding a few cm against a wall) ZERO every tick → the
        // gate never fills and the ram bobs ~5 s until the slow 100-tick wedge fires. The horizontal arc projection s
        // is immune to that vertical/jitter noise (a wall-ram makes no forward arc progress regardless of bob), so
        // ticks of |ds|<ARC_WEDGE_DS while horizontalCollision accumulate reliably → fold into fellOffPath far sooner.
        int wedgeTicks;               // consecutive ticks of ~zero arc-progress while ramming (bob/jitter-immune); reset on any real ds or path change
        // Phase-3b (walkerArcProgressWedge): NET arc-length progress over a WINDOW — catches an OSCILLATING limit cycle
        // the per-tick ram wedge (wedgeTicks, needs hCol + per-tick |ds|<0.05) and the anti-churn net-XZ both miss.
        // A steep-face diagUp churn (live 2026-06-28 -815, replay-0006) has the bot bob-jumping airborne (no hCol), making
        // small per-tick FORWARD ds then sliding back — net arc-s ≈ 0 over the cycle yet per-tick |ds| > 0.05 (so the ram
        // wedge resets) and net-XZ swings 35 blocks laterally (so the anti-churn is fooled). arc-s is the projection ONTO
        // the path, immune to the lateral swing AND the vertical bob, so net arc-s over a window cleanly flags "no path
        // progress despite motion". Folds into the SAME fellOffPath recovery (fresh foot-search blacklists the
        // un-advanceable node + re-routes). Window-based so it does NOT need hCol or onGround.
        double progBaseS = Double.NaN;  // proj.s at the start of the current progress window
        int progWindowTicks;          // ticks elapsed in the current arc-progress window
        boolean progStall;            // last completed window made < ARC_PROG_MIN net arc-s progress → stuck (consumed by fellOffPath)
    }
    float freeHangDriveYaw = Float.NaN;             // slew-limited world heading of the free-hang vine DRIVE (NaN = resync); smooths the step-jitter ±180° flips that would circle the bot off a narrow column — EdgeGuards-owned, self-resyncing (no journey reset)
    int surfaceWaterLatch = 0;                      // ticks the open-water swim stays latched after a surface bob lifts the foot out of the fluid (rides out the isInWater blink) — read across Aim/Climb/Progress; belongs to a future water-state family
    /** Aim/heading smoothing state — see {@link AimSmoothing}. */
    final AimSmoothing aimSmooth = new AimSmoothing();
    /** Descent/water sprint-brake latches + drive debouncers (task#96 step B5), owned by
     *  WalkerTickDrive. {@link DriveLatches#reset()} clears only the two sprint-brake
     *  latches (exactly what both journey resets cleared); the debounce counters
     *  self-manage per tick. */
    final DriveLatches driveLatch = new DriveLatches();
    static final class DriveLatches {
        int deepWaterDriftLatch = 0;                // ticks the deep-water drift sprint-brake stays latched after firing while grounded, so sprint stays OFF through the airborne sub-arcs of a step-down descent toward a deep pocket (otherwise sprint re-arms each airborne tick and the accumulated forward momentum still overshoots into the water)
        int steepDescentLatch = 0;                  // DRY sibling of deepWaterDriftLatch: ticks the steep-descent sprint-brake stays latched across the airborne sub-arcs of a step-down, so sprint can't re-arm mid-fall and accumulate forward momentum off a survivable-deep lip (live 2026-07-11 Mountains massif: onG=false→sprint=true walked the bot off a 19-block lip to death)
        int climbPressConsec = 0;                   // consecutive ticks the buoyant-climb-press raw condition has held (debounces the surface-bob false trigger)
        int descentDriveRejectStreak = 0;           // consecutive back-hop rejections on a dry diagDown slope (escape-hatch snaps to the real node after WATER_DRIVE_MAX_REJECT)
        int underwaterTicks;                        // consecutive eyes-under ticks → debounces the swim-up jump (surface bob ≠ sinking)
        int hopY = Integer.MIN_VALUE, hopTick;      // a cruise hop in flight: the level it left and the tick it was keyed (see WalkerTickPrelude.hopFoot)
        boolean cruiseOn;                           // surface cruise engaged last tick (entry needs the eyes out; the dip then takes them under on purpose)
        boolean cruiseBreath;                       // surface cruise: air ran low → bob and breathe until it refills (hysteresis, see WalkerTickDrive.surfaceCruise)
        int cruiseSwimTicks;                        // surface cruise: consecutive ticks in the swim pose (0 = not swimming; log edge + the server-confirm hold)
        int cruiseDipTicks;                         // surface cruise: ticks spent sinking for the pose without getting it → past CRUISE_DIP_MAX_TICKS the cruise backs off
        int cruiseCooldown;                         // surface cruise: ticks left in that back-off
        boolean lavaBrakeLogged;                    // edge-trigger for the hazard-ahead brake's log line: that brake can hold for every tick of a legitimate lava-side passage, so it prints once per engagement rather than once per tick. Not cleared by reset() — it is a log latch, not a drive latch, and a repath mid-passage should not re-announce the same creep
        void reset() {
            deepWaterDriftLatch = 0;
            steepDescentLatch = 0;
        }
    }
    /** @see SearchGovernors */
    final SearchGovernors searchGov = new SearchGovernors();
    int dbgPrevStep = -1;     // walkerDebug: detect step changes for per-step timing
    int dbgTicksOnStep = 0;   // walkerDebug: ticks spent on the current step
    /** Why the walk failed, or why a branch last threw the route away; the failing branch's text is
     *  what {@link #result()} reports. */
    String error;
    /** How the last walk ended, written once by {@link #terminalReport}. Volatile: status threads read. */
    private volatile WalkResult result = WalkResult.NONE;
    /** True once snapGoalToStandable() rewrote the requested Goal.Block to a nearby
     *  standable cell — arrival then means "arrived NEAR", not "arrived AT". */
    boolean goalSnapped;

    @Override public WalkResult result() { return medium != null ? medium.result() : result; }

    /**
     * Re-aim an in-progress pursuit at a quarry that moved, WITHOUT telling the walker this is
     * a new journey. {@link #start} clears the futile-search governor, which is right for a
     * fresh goto and wrong for a chase: a caller that re-goals every time its quarry changes
     * block zeroes the counter faster than the counter can reach its cap, so the guard that
     * exists to stop a bot burning a full A* per tick toward something it cannot reach never
     * fires. Measured on a chase of a descending goal: 240 ticks produced 240 full searches and
     * a counter that never passed 1. Everything else about the goal genuinely IS new, so this
     * is {@code start} plus the six fields that describe "how this pursuit has been going"
     * rather than "which cell we want" — including the backoff, since taking away an armed
     * cooldown is the same leak one cap below.
     *
     * <p>A search already running from the feet is kept too: the futile judge counts only landed
     * searches, and a quarry pacing in a pen re-aims faster than a full search lands, so dropping it
     * leaves a chase that never lands one — never counted, never routed. A continuation from the
     * segment end goes, since the path it would extend is cleared.
     */
    @Override public void retarget(Goal g) {
        double bestDist = searchGov.futileBestDist;
        BlockPos bestFoot = searchGov.futileFoot;
        Goal yardstick = searchGov.futileGoal;
        BlockPos latchFoot = searchGov.futileLatchFoot;
        int searches = searchGov.futileSearches;
        int backoff = searchGov.searchBackoffTicks;
        PathFinder.Search running = route.seg.searchFromEnd ? null : route.seg.activeSearch;
        boolean placeSuppressed = route.seg.searchSuppressedPlace;
        start(g);
        searchGov.futileBestDist = bestDist;
        searchGov.futileFoot = bestFoot;
        searchGov.futileGoal = yardstick;
        searchGov.futileLatchFoot = latchFoot;
        searchGov.futileSearches = searches;
        searchGov.searchBackoffTicks = backoff;
        if (running != null) route.searchFromFoot(running, placeSuppressed);
        if (medium != null) medium.retarget(g);
    }

    @Override public void start(Goal g) {
        this.goal = g;
        this.goalSnapChecked = false;
        this.goalSnapped = false;
        this.result = WalkResult.NONE;
        reset(Scope.GOAL, g == null ? "cancelled" : "new goal");
        if (medium != null) medium.start(g);
    }

    /**
     * How much of the walk a change clears. A new goal clears all of it. A replan drops the plan and what
     * was measured against it — a resume mid climb-out would otherwise carry an armed stall into the next
     * tick and fire a spurious takeover — but keeps how this pursuit has been going: the anti-spin and churn
     * baselines, the search governors bar the quick cooldown, the wedge count. A running controller is
     * stopped only for a new goal; after a replan one that depended on the old plan stops itself.
     */
    enum Scope { GOAL, REPLAN }

    private void reset(Scope scope, String why) {
        ticksSinceRepath = 0;
        stuckTicks = 0;
        actionTicks = 0;
        arbiter.reset(scope, why);
        dive.reset();
        driveLatch.reset();
        descending = false;
        escal.disarm();
        stepProg.restartWindows();
        aimSmooth.reset();
        if (scope == Scope.REPLAN) {
            route.clear();
            searchGov.quickCooldown = 0;
            return;
        }
        route.restart();
        totalTicks = 0;
        goalSpin.resetForNewGoal();
        searchGov.reset();
        churn.resetForNewGoal();
        safety.guardPlugCell = null;    // plug arming is per-journey; a stale cell must not pre-arm the next goal
        safety.guardPlugFires = 0;
        error = null;
    }

    /**
     * If the goal is an exact-cell {@link Goal.Block} whose target is NOT standable per
     * {@code world.canStandAt}, replace it with a Goal.Block on the nearest standable cell
     * within {@link #GOAL_SNAP_RADIUS}. This is the fix for a random/long-distance goto
     * landing its target inside terrain or a sub-stand pocket: A* can never accept the
     * unstandable cell as the goal, so it burns its whole node budget every repath (~5 s
     * freeze) while the bot oscillates at the cell and drifts into nearby water. Snapping
     * to a cell the pathfinder itself considers standable lets the search terminate and the
     * bot settle at the closest reachable spot. Uses the SAME predicate the planner uses, so
     * planning and arrival ({@code goal.reached}) stay consistent. No-op for standable
     * targets, non-Block goals (Near/XZ already tolerate it), or when nothing standable is
     * near (then the goal is unchanged — fails as before, never worse).
     */
    void snapGoalToStandable(WorldView world, BlockPos foot) {
        if (!(goal instanceof Goal.Block b)) return;
        BlockPos t = b.target();
        if (world.canStandAt(t)) return;                 // already fine — leave it
        // A dive goal sits under the surface on purpose. Under the surface-node model that cell is
        // not standable, and snapping it up would walk the diver to the wrong place.
        if (WorldConfig.pathfinderSurfaceWaterNodes && world.isWater(t)
                && profile.capability().allowsOptIn(net.magicterra.worlddriver.bot.rules.Capability.DIVE)) return;
        // walkerPillarReachGoalNoSnap: don't snap an elevated AIR goal DOWN when it's reachable
        // by PILLARING up. canStandAt(t) here fails only because t's floor is air — but pillaring
        // creates that floor, so the goal IS reachable. Snapping it to the highest currently-
        // standable cell makes the bot stop 1+ blocks short (live 2026-06-27 summitArena: goal
        // y233 → snapped to standable y232, bot pillars 2 then arrives at y232, never pillars the
        // last block, so an uphill goal in the air is never reached). Skip the snap so A* keeps the real goal and finds the
        // pillarUp path. Guard against a truly FLOATING void goal (no base to pillar from): require
        // the goal cell + head to be passable AND a solid base within a few blocks below.
        if (ControlConfig.walkerPillarReachGoalNoSnap && WorldConfig.allowPlace
                && !world.isSolid(t) && !world.isSolid(t.above())) {
            boolean baseBelow = false;
            for (int d = 1; d <= 5; d++)
                if (world.isSolid(t.offset(0, -d, 0))) { baseBelow = true; break; }
            if (baseBelow) return;   // pillar-reachable elevated goal — let A* pillar up to it
        }
        BlockPos best = null;
        long bestToTarget = Long.MAX_VALUE, bestToFoot = Long.MAX_VALUE;
        for (int dy = -GOAL_SNAP_RADIUS; dy <= GOAL_SNAP_RADIUS; dy++)
            for (int dx = -GOAL_SNAP_RADIUS; dx <= GOAL_SNAP_RADIUS; dx++)
                for (int dz = -GOAL_SNAP_RADIUS; dz <= GOAL_SNAP_RADIUS; dz++) {
                    BlockPos c = t.offset(dx, dy, dz);
                    if (!world.canStandAt(c)) continue;
                    long dT = (long) c.distSqr(t);
                    if (dT > bestToTarget) continue;
                    long dF = (long) c.distSqr(foot);
                    // Nearest to the target wins; ties broken toward the bot so the snap
                    // doesn't pick a standable cell on the far side of the obstruction.
                    if (dT < bestToTarget || dF < bestToFoot) {
                        bestToTarget = dT; bestToFoot = dF; best = c;
                    }
                }
        if (best != null && !best.equals(t)) {
            if (GameConfig.walkerDebug)
                LOG.info("[walker] goal snap: unstandable target {} → nearest standable {} (d={})",
                        t, best, String.format(Locale.ROOT, "%.1f", Math.sqrt(bestToTarget)));
            this.goal = new Goal.Block(best);
            this.goalSnapped = true;
        }
    }

    /** Drop the cached path (keep the goal) so the next {@link #tick} recomputes
     *  from the current position. Used when a movement process is resumed after
     *  being preempted by a higher-priority chain — during the suspension the bot
     *  may have been knocked back or the terrain changed, so reusing the stale
     *  path would walk into a wall. See ProcessScheduler / UserTaskChain.onResume. */
    @Override public void replan() {
        reset(Scope.REPLAN, "replan");
        if (medium != null) medium.replan();
    }

    @Override public void replan(SearchProfile profile) {
        useProfile(profile);
        replan();
    }

    /** Replay a fixed archived plan. Caller has already teleported the bot to the plan
     *  start and restored the block envelope. Disables A* entirely — the plan executes
     *  with real physics; a wedge is recorded, not re-planned: whatever would have dropped
     *  the plan ends the walk instead, and a plan the adoption refuses ends it on the first tick. */
    public void replay(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, BlockPos startFoot) {
        start(endGoal);
        route.enterReplay();
        Route.Adoption a = RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), world, startFoot);
        if (!a.adopted()) route.drop("replay plan refused: " + a.refusal());
    }

    /**
     * TEST SEAM — adopt a hand-built plan with the LIVE recovery/repath machinery left ON
     * (unlike {@link #replay}, which puts the route in replay and disables A*), then
     * point {@code step} at an arbitrary mid-plan node. The harness uses this to force the
     * exact step-pointer-mismatch STATE the live wedge lands in — bot grounded N blocks off a
     * descend node — INDEPENDENT of how the bot drifted there live (which a momentum overshoot
     * on a synthetic 2-D arena could not reliably reproduce). Because the route is not in replay
     * false, the real {@code fellOffPath}/{@code safetyRepath}/blacklist recovery runs, giving
     * the clean A/B the live wedge couldn't. Pass {@code foot==null} so {@link RouteAdoption#adopt} does
     * NOT fast-forward/re-anchor the step it just set. Not wired to any transport; test-only.
     */
    public void beginScriptedFollow(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, int startStep) {
        start(endGoal);
        RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, false, 0, 0L, 0.0), world, null);
        route.seek(startStep);
        this.stepProg.noProgressStep = -1;          // force a fresh noStepProgressTicks baseline at the injected step
        this.stepProg.noStepProgressTicks = 0;
    }

    /**
     * How many NODES the current path holds, <b>including the one the bot starts on</b>.
     *
     * <p><b>Nodes, not steps.</b> {@link #edges} is index-aligned with {@link #path} — {@code edges
     * .get(i)} is the edge that ENTERS {@code path.get(i)} — so node 0 is where the walk began and
     * carries no traversal of its own. A path of N nodes is <b>N−1 moves</b>, and reading it as
     * "N blocks" overstates the reach of every plan by one. It is also NOT a distance: a single
     * {@code fall7} or a smoothed diagonal covers several blocks in one node.
     *
     * <p><b>The last node is the goal only when the search reached it.</b>
     * {@code seg.pathBestEffort = !res.goalReached()} — a best-effort partial ends at the closest
     * node A* could get to, which for a bot on a small island is its own edge. So "the last node
     * of the path" and "the destination" are different questions and {@link WalkerInspector#planTally()} answers
     * the first one.
     */
    @Override public int pathLen() {
        return medium != null ? medium.pathLen() : route.path() == null ? 0 : route.path().size();
    }
    @Override public int pathStep() { return medium != null ? medium.pathStep() : route.step(); }

    /** Latch the FIRST parkour takeoff of this walk. The sprint decision is taken a tick before the
     *  jump, so {@code isSprinting()} here is the state the leap actually launches with. Only the
     *  first is kept: a bot that has already fallen keeps producing these.
     *
     *  <p><b>The five samples are not guaranteed to be five CONSECUTIVE ticks.</b> This call sits in
     *  the drive tail, which a dozen branches (dig, pillar, escape, step-up) return before reaching,
     *  so "t+0..t+4" means "the first five ticks the drive got this far". A run of five with
     *  {@code onGround=false} therefore does NOT by itself distinguish a bot that was standing and
     *  refused the jump from a bot that was already falling when the parkour edge became current —
     *  the exact y and the sole area are what separate them, which is why both are printed. */
    void noteParkourTakeoff(WorldView world, Body a, LivingEntity p, boolean jump, BlockPos foot) {
        if (parkourSamples >= 5) return;
        double h = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
        if (parkourSamples == 0) parkourTakeoffBuf = new StringBuilder();
        long now = p.level().getGameTime();
        long lastJump = a.dbgLastJumpTick();
        parkourTakeoffBuf.append(parkourSamples == 0 ? "" : " | ")
                // The SAMPLE index and the GAME TICK, because they are not the same thing: this
                // latch sits in the drive tail, which a dozen branches return before reaching, so
                // "t+2" is the third sample, not the third tick. Without the absolute tick the
                // phrase "N ticks before takeoff" has no meaning to argue over.
                .append("t+").append(parkourSamples).append("@").append(now)
                // Ticks since the bot last actually EMITTED an impulse (not since one was asked
                // for). This is what separates a bot that jumped ITSELF off the platform — the
                // parkour edge became current mid-arc — from one that simply walked off the lip:
                // the first has a small number here, the second has "none".
                .append(" sinceLastJump=").append(lastJump < 0 ? "none" : String.valueOf(now - lastJump))
                .append(" jump=").append(jump)
                .append(" sprinting=").append(p.isSprinting())
                .append(String.format(java.util.Locale.ROOT, " h=%.4f", h))
                // ASK THE ATTRIBUTE, do not extrapolate the curve. Sprint is ×1.3 through
                // Attributes.MOVEMENT_SPEED (verified in 1.21.1: SPEED_MODIFIER_SPRINTING = 0.3,
                // ADD_MULTIPLIED_TOTAL), so 0.13 here means the channel works and 0.1 means the
                // modifier never took. The expected steady-state cap is printed beside it so nobody
                // has to redo the arithmetic.
                .append(String.format(java.util.Locale.ROOT, " speedAttr=%.4f(expected steady-state cap %.4f)",
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) * 2.1585))
                // THE QUANTITY THE JUMP BRANCHES ON (vanilla's gate, on both bots), kept as an
                // input rather than as truth: onGround IS vanilla's verticalCollisionBelow
                // (Entity.move assigns one from the other in a single statement), so it describes the
                // previous MOVE and is wrong in both directions about where the bot is.
                .append(" onGround=").append(p.onGround())
                // THE SOLE READING the walker's own footing guards use. Printed beside onGround
                // so a run says which of the two was lying, and printed with the EXACT y because
                // that is the only thing that tells a standing bot from a falling one when the
                // block coordinate below is the same for both (a bot falling from y=49.9 spends
                // three ticks inside block y=49, exactly like a bot resting on y=49.0).
                .append(String.format(java.util.Locale.ROOT, " soleOnSolid=%.4f y=%.4f",
                        WalkerGeometry.soleOnSolid(world, p), p.getY()))
                .append(" foot=").append(foot.toShortString());
        parkourSamples++;
        parkourTakeoff = parkourTakeoffBuf.toString();
    }

    private int parkourSamples;
    private StringBuilder parkourTakeoffBuf;

    /** TEST SEAM — run {@link RouteAdoption#adopt} with an explicit {@code foot} so a harness can exercise the
     *  segment anchor-gate (the {@code mis-anchored segment} reject vs the deep-water-float open-water
     *  bee-line exemption) deterministically, INDEPENDENT of the full continuation/repath machinery the
     *  headless server-sim doesn't reproduce for this live water dead-stop. @return the adoption's verdict:
     *  true = ACCEPTED (segment adopted, the bot will drive it), false = REJECTED as mis-anchored. */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, BlockPos foot) {
        return adoptForTest(world, plan, planEdges, foot, false);
    }

    /** {@link #adoptForTest} for a scene that goes on to TICK the walker. The 4-arg form declares the
     *  plan BEST-EFFORT, and a best-effort segment is one the walker is entitled to replace: it kicks
     *  off a continuation search, adopts the result, and the synthetic edges are gone. That is how
     *  {@code wd.surfacePillarPointerNeedsItsSupport} lost its subject on tick ONE — its terminal read
     *  {@code path-consumed}, a class {@link WalkVerdict#classifyArrival} can only return once
     *  {@code seg.pathBestEffort} is false, and {@link RouteAdoption#adopt} is its only writer. A synthetic plan
     *  whose last node IS the goal has to say so, or it does not survive its own first tick. */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges,
                                BlockPos foot, boolean goalReached) {
        return RouteAdoption.adopt(this, new PathFinder.Result(plan, planEdges, goalReached, 0, 0L, 0.0), world, foot).adopted();
    }

    /**
     * Hand this walker a previewed route ({@code mc.bot.goto} with {@code planId}). Feed the RAW search
     * result, never the straightened route a preview showed: adoption straightens on its own, cuts a
     * best-effort tail, and skips the prefix the bot already walked, so a pre-straightened route would be
     * straightened twice. A preview whose first stretch has been built over comes back refused, which is
     * the caller's cue to let the walker search as usual.
     *
     * @param foot where the bot stands now; the route is anchored to its nearest prefix node
     * @return false when the route was refused (mis-anchored or stale)
     */
    public boolean adoptPlan(PathFinder.Result raw, WorldView world, BlockPos foot) {
        if (raw == null || raw.path().isEmpty()) return false;
        return RouteAdoption.adopt(this, raw, world, foot).adopted();
    }

    // Jump / sneak actuators — drive the player's OWN AvatarInput (Input.jumping /
    // Input.shiftKeyDown) instead of the SHARED global keybinds mc.options.keyJump /
    // keyShift, so the Walker never fights a human's space/shift. Per-tick (see
    // AvatarInput): tick() sets a default below, branches override. p.input is always
    // an AvatarInput here (installed at the top of tick()); the guard keeps it safe if
    // a respawn swapped a fresh KeyboardInput in between.
    /**
     * The one funnel every jump request goes through — and now the one place that records WHERE a
     * jump came from.
     *
     * <p>It was {@code static} and the latch is why it is not any more. Enumerating the disjuncts of
     * {@code WalkerTickDrive}'s {@code boolean jump} to work out which one fired on the End platform
     * was wrong three ways over, and the shape of the error is worth keeping: that expression is
     * <b>one of eleven</b> places that can set the request. {@code WalkerTickClimb} alone has eight
     * ({@code :158, :465, :727, :781, :847, :868, :930, :1021}), and there are more in the drive's
     * pillar-recover rung, the vine guard and the unstuck burst. Only five of them label themselves
     * with {@code jumpTag}. Reading the branch conditions of one of eleven and calling the survivor
     * "the only term that can fire" was never a measurement.
     *
     * <p>So the source is taken from the CALL SITE, not from a hand-kept list: a file and line from
     * the stack cannot fall out of date when a twelfth site appears, and a site that never labelled
     * itself still names itself. {@code jumpTag} is printed beside it as the human-readable branch
     * when the site set one — {@code WalkerTickPrelude} clears it every tick, so a stale label cannot
     * be attributed to this tick — and {@code untagged} when it did not.
     *
     * <p>The drive's label chain ends in {@code other} rather than a bare {@code "swim"}. That is
     * not decoration: a chain whose last arm is a real branch name labels every unmatched case as
     * that branch, so it can never report that the labels have fallen behind the expression they
     * describe. If {@code branch=other} is ever printed, the chain is missing a term — fix the chain
     * before believing any label it produced.
     *
     * <p>One line per EVENT (entry to a run of held ticks), capped at {@link #JUMP_SRC_EVENTS}: the
     * walker holds jump for dozens of consecutive ticks and a per-tick line would be a hose. The
     * stack walk happens only on the lines that are actually emitted.
     *
     * <p><b>"Entry to a run" is measured against the PREVIOUS CALL, and must never be measured
     * against the clock.</b> It used to be {@code getGameTime() - jumpAskTick > 1}, and that is
     * blind for a whole family of scenes: anything that pumps the bot in a tight in-body loop
     * ({@code wd.buriedOre} runs 800 {@code BotTicker.tickAll()} iterations inside ONE
     * server tick, as do the {@code wd.serverMine*} family, {@code wd.selfShaftDigUp} and every
     * other synchronous scene body) advances the walker without advancing the clock, so {@code now}
     * is CONSTANT for the whole run. After the first emitted line {@code now - jumpAskTick == 0} is
     * permanently false, the latch never re-opens, and exactly one line is printed no matter how
     * many separate jump events occurred — so "it happened once" and "it happened forty times and
     * only the first was printed" read identically. {@code lastJumpAsk} states the same semantics
     * without a clock: {@code WalkerTickPrelude} calls this once per walker tick with the
     * {@code false} baseline and the branches override it, so "the previous call also asked" IS
     * "the previous tick also asked" — in a per-tick world and in a one-tick world alike. A held
     * run still yields exactly one line in a real world, which is the property the rehearsal and
     * ladder logs depend on.
     *
     * <p>{@code seq=} is the event ordinal within the cap; when {@code t=} is frozen (the one-tick
     * family) it is the only thing that orders the lines. {@code seq=6/6} says the cap was REACHED,
     * which is not the same claim as "more happened" — the follow-on {@code seq=7+/6 cap reached}
     * line is the one that says an event was actually swallowed, and its ABSENCE after a
     * {@code seq=6/6} means the bot really did stop asking.
     */
    void avatarJump(Body a, boolean v) {
        boolean newEvent = v && !lastJumpAsk;
        lastJumpAsk = v;
        if (newEvent) noteJumpSource(a);
        a.commandJump(v);
    }

    private void noteJumpSource(Body a) {
        LivingEntity p = a.entity();
        if (p == null) return;
        if (jumpSrcEvents >= JUMP_SRC_EVENTS) {
            // A silent cap and a bot that genuinely jumped exactly JUMP_SRC_EVENTS times print
            // IDENTICALLY — "seq=6/6" is the last line in both worlds. Say it once, at the first
            // event actually suppressed, so the difference is on the page. One-shot on purpose:
            // the whole reason the cap exists is that a hose of these lines is unreadable.
            if (!jumpSrcCapped) {
                jumpSrcCapped = true;
                LOG.info("[walker] jump source: seq={}+/{} cap reached, later events are not logged (printed once)",
                        JUMP_SRC_EVENTS + 1, JUMP_SRC_EVENTS);
            }
            return;
        }
        jumpSrcEvents++;
        long now = p.level().getGameTime();
        BlockPos foot = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        BlockPos wp = route.path() != null && route.step() >= 0 && route.step() < route.path().size() ? route.path().get(route.step()) : null;
        String site = StackWalker.getInstance().walk(s -> s.skip(2)
                .map(f -> f.getFileName() + ":" + f.getLineNumber()).findFirst().orElse("?"));
        // The water pair is here because a `swimColumn` tag on dry obsidian has three readings and
        // the tag alone cannot separate them: the BOT's own flag (p.isInWater(), what the branch
        // actually tests) and the WORLD's block at the foot are printed side by side, so a stale
        // flag, a genuinely wet cell, and a tag that disagrees with its own precondition are three
        // distinct rows instead of one ambiguous one.
        LOG.info("[walker] jump source: seq={}/{} t={} branch={} site={} foot={} exact=({}) waypoint={} wp.y-foot.y={} inWater={} underWater={} footBlock={} aboveFoot={}",
                jumpSrcEvents, JUMP_SRC_EVENTS,
                now, jumpTag == null ? "untagged" : jumpTag, site, foot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                wp == null ? "none" : wp.toShortString(), wp == null ? "?" : String.valueOf(wp.getY() - foot.getY()),
                p.isInWater(), p.isUnderWater(),
                p.level().getBlockState(foot).getBlock().toString(),
                p.level().getBlockState(foot.above()).getBlock().toString());
    }

    /** Jump-source lines emitted per walker before the latch goes quiet. */
    private static final int JUMP_SRC_EVENTS = 6;
    private int jumpSrcEvents;
    /** One-shot latch for the "the cap swallowed an event" line — see {@link #noteJumpSource}. */
    private boolean jumpSrcCapped;
    /** Whether the PREVIOUS {@link #avatarJump} call asked for a jump — the clock-free
     *  "entry to a held run" latch. See that method's javadoc for why a game-time delta
     *  cannot do this job in a scene that runs its whole body inside one server tick. */
    private boolean lastJumpAsk;

    /**
     * One line per STEP ADVANCE — which gate consumed the node, and whether the bot was standing
     * on anything when it did.
     *
     * <p><b>The pair of readings that separates two step-advance stories.</b> A pointer that has
     * moved past a node is consistent with the bot having reached it and with the bot having been
     * mid-jump over it, and the vertical distance alone cannot tell them apart: the {@code passed}
     * gate's {@code |nx.y − p.y| < 1.2} reachability bar is evaluated against the bot's y AT THIS
     * INSTANT, and a 0.42 impulse lifts a grounded bot ~1.25 blocks, so a node two above the
     * FLOOR reads as within reach at the top of a jump the bot cannot stay at. {@code onGround},
     * {@code soleOnSolid} and {@code fallSpeed} therefore ride on the same line as the two |Δy|
     * terms — a distance without a footing is the same row in both worlds, and the whole point of
     * the line is that they be different rows.
     *
     * <p>{@code soleOnSolid} is {@link WalkerGeometry#soleOnSolid}, the predicate the walker's
     * footing guards steer by, deliberately rather than a second opinion about what standing means:
     * an executor and a reading that answer that question differently is how a diagnosis comes to
     * describe a bot that does not exist.
     *
     * <p><b>{@code cause=} is the branch that FIRED, not a reconstruction.</b> The flags are passed in
     * from the decision site and joined, so two simultaneously-true gates print as {@code
     * within+passed} instead of the caller having to pick one, and an advance matching NONE of the
     * named flags prints {@code other} — a chain whose last arm is a real gate name would label every
     * unmatched case as that gate and could never report that the labels had fallen behind the
     * expression. {@code cur2}/{@code nd2}/{@code overshot} come from the same site for the same
     * reason; {@code nd2=NaN} is the honest reading for a tick where {@code within} fired and the
     * {@code passed} arithmetic never ran.
     *
     * <p>Capped like {@link #noteJumpSource} and for the same reason, with the same one-shot
     * {@code cap reached} line — and, as there, the gate is an ORDINAL and never the clock:
     * {@code wd.buriedOre} pumps its whole body inside ONE server tick, so a game-time latch would
     * print the first line and go blind for the rest of the run. NOT gated on
     * {@code GameConfig.walkerDebug}, which that scene switches off.
     */
    void noteStepAdvance(WorldView world, LivingEntity p, BlockPos foot, BlockPos w, BlockPos nx,
                         String cause, double cur2, double nd2, boolean overshot) {
        if (seenLegEpoch != tel.legEpoch) {             // a new walk: fresh budget, see WalkerTelemetry#newLeg
            seenLegEpoch = tel.legEpoch;
            stepAdvEvents = 0;
            stepAdvCapped = false;
        }
        if (stepAdvEvents >= STEP_ADV_EVENTS) {
            if (!stepAdvCapped) {
                stepAdvCapped = true;
                LOG.info("[walker] step advance: seq={}+/{} cap reached, later events are not logged (printed once)",
                        STEP_ADV_EVENTS + 1, STEP_ADV_EVENTS);
            }
            return;
        }
        stepAdvEvents++;
        tel.stepAdvancesLogged++;
        LOG.info("[walker] step advance: seq={}/{} cause={} oldStep={} newStep={} w={} nx={} foot={} exact=({}) "
                        + "cur2={} nd2={} overshot={} |w.y-p.y|={} |nx.y-p.y|={} onGround={} soleOnSolid={} fallSpeed={}",
                stepAdvEvents, STEP_ADV_EVENTS, cause, route.step(), route.step() + 1,
                w == null ? "none" : w.toShortString(),
                nx == null ? "none(last node)" : nx.toShortString(),
                foot == null ? "none" : foot.toShortString(),
                String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                String.format(Locale.ROOT, "%.3f", cur2), String.format(Locale.ROOT, "%.3f", nd2), overshot,
                w == null ? "?" : String.format(Locale.ROOT, "%.3f", Math.abs(w.getY() - p.getY())),
                nx == null ? "?" : String.format(Locale.ROOT, "%.3f", Math.abs(nx.getY() - p.getY())),
                p.onGround(),
                world == null ? "?" : String.format(Locale.ROOT, "%.4f", soleOnSolid(world, p)),
                String.format(Locale.ROOT, "%.4f", p.getDeltaMovement().y));
    }

    /**
     * Step-advance lines emitted per WALK before the latch goes quiet.
     *
     * <p>Sixty-four, and the number is measured rather than chosen. It was 8 <b>per walker</b>, and
     * a walker outlives a whole rung: on the 2026-08-20 ladder the budget was spent in the first
     * seconds of a 5 209-tick crossing, so the four wedged hops that are the entire question —
     * 900 ticks each, 61 to 76 walk edges apiece — produced not one advance line between them. The
     * reading those hops need is whether the pointer advanced through nodes the bot never walked,
     * and that is exactly what an exhausted latch cannot say. 64 covers the 61 edges the worst
     * measured hop walked, so a wedge can be read end to end instead of only its opening.
     *
     * <p><b>Still bounded, and per walk rather than unbounded</b>: 24 hops x 64 lines of ~300 chars
     * is about 460 KB for a whole crossing, which is a log a human opens. An uncapped advance log
     * over a wedged hop is how a 40-minute run becomes an unreadable one.
     */
    static final int STEP_ADV_EVENTS = 64;
    private int stepAdvEvents;
    /** The walk this walker last refreshed its advance budget for — see {@link WalkerTelemetry#newLeg}. */
    private int seenLegEpoch = -1;
    /** One-shot latch for the "the cap swallowed an advance" line — see {@link #noteStepAdvance}. */
    private boolean stepAdvCapped;

    static void avatarSneak(Body a, boolean v) { a.commandSneak(v); }
    /** Raw forward (keyUp equivalent) for the special branches that drive the impulse
     *  themselves (the main walk path uses commandMove). v=false also zeroes strafe. */
    static void avatarForward(Body a, boolean v) { a.commandForward(v ? 1f : 0f); }

    /** The body the tick drives, recording movement into this tick's frame; see {@link Actuate}. */
    private final Actuate.FrameBody frameBody = new Actuate.FrameBody();

    /** Decide the tick against a recording body, then hand what it decided to the real one. */
    @Override public Status tick(Body body, WorldView world) {
        Actuate.FrameBody a = frameBody.begin(body);
        try {
            return decide(a, world);
        } finally {
            Actuate.apply(a.frame, body);
        }
    }

    private Status decide(Body a, WorldView world) {
        body = a.entity();
        if (body != null) tel = WalkerTelemetry.of(body);
        equipment = a.equipment().orElse(WalkerNoHands.INSTANCE);
        blocks = a.blockInteraction().orElse(WalkerNoHands.INSTANCE);
        if (goal == null) {
            result = new WalkResult(Status.FAILED, "no goal: the walk was never started, or was cancelled",
                    "failed:no goal", false, -1, false);
            return Status.FAILED;
        }
        if ((medium = MediumLocomotor.forBody(this, medium, world, goal)) != null) return medium.tick(a, world);
        return walk(a, world) == Step.WALKING ? Status.MOVING : result.status();
    }

    /** One tick of a body that walks: the phases, then {@link Safety} on a tick that keeps walking. */
    private Step walk(Body a, WorldView world) {
        // Single-exit wrapper: tickInner() has dozens of early returns (pillar, dig, escape,
        // stepUp...), so a safety invariant appended to its tail only covers SOME ticks — the
        // gap #53 death strode over a well mouth from a branch that never reached it. Run the
        // stride floor-guard here, after EVERY decision path, before the avatar integrates.
        Step s = tickInner(a, world);
        WalkerTickDrive.settleDigKey(this, a);   // a walker-held attack key outlives its dig by DIG_KEY_RELEASE_TICKS only
        LivingEntity tp = a.entity();
        tel.lastTickTrace = "step=" + s + " jumpTag=" + (jumpTag == null ? "untagged" : jumpTag)
                + (tp == null ? "" : " pos=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                        tp.getX(), tp.getY(), tp.getZ())
                    + String.format(java.util.Locale.ROOT, " speedH=%.3f",
                        tp.getDeltaMovement().horizontalDistance())
                    + " sole=" + String.format(java.util.Locale.ROOT, "%.3f",
                        WalkerGeometry.soleOnSolid(world, tp)))
                + " node=" + (route.path() == null || route.step() < 0 || route.step() >= route.path().size()
                        ? "none" : route.path().get(route.step()).toShortString());
        if (tp != null && WalkerGeometry.soleOnSolid(world, tp) > 0.0) tel.lastSupportedTrace = tel.lastTickTrace;
        if (s == Step.WALKING) safety.run(a, world);
        else safety.stand(a);
        return s;
    }

    /** How far out {@link #wiggleHop} measures the nearest lethal drop. Beyond {@link
     *  WalkerGeometry#HOP_RANGE} on purpose: the point of the reading is to show whether the gate's
     *  radius is SHORTER than the throw it guards against, and a scan that stops at the gate's own
     *  radius can only ever answer "clean", which is the answer already in doubt. */
    private static final int WIGGLE_SCAN_MAX = 4;

    /**
     * The stuck-wiggle recovery hop — and the one place that says why it did or did not fire.
     *
     * <p>Moved out of {@code WalkerTickDrive}'s jump expression because the decision needed a
     * number the expression threw away. It began as {@code lethalDropWithinHopRange}, a boolean
     * over Chebyshev ≤{@link WalkerGeometry#HOP_RANGE} of the LAUNCH cell; printing the RING
     * instead of the boolean is what showed the radius (2) to be shorter than the arc (measured
     * 3.47), and printing the CELL is what made a bearing computable. <b>Those numbers are still
     * inputs — do not read the conclusion off the comment.</b>
     *
     * <p><b>The gate is no longer that ring.</b> It is {@link WalkerGeometry#hopSuppressed}: the
     * columns the arc would come down in along the drive bearing. The stuck window is unchanged
     * ({@code precond && stuckTicks > 10 && stuckTicks < 18}), so the scan still runs only inside
     * it, exactly as the old short-circuit arranged; only the predicate after it moved.
     *
     * <p>One line per EVENT, not per tick: the window is 7 ticks wide and a bot sits in it for
     * runs of them, so a per-tick line would be a hose. Entry to the window is the event. Capped at
     * {@link #WIGGLE_EVENTS} so a bot that stalls repeatedly still cannot flood a rehearsal log.
     *
     * <p><b>"Entry" is adjacency of CALLS, never of game time.</b> The gate was
     * {@code getGameTime() - wiggleLastTick > 1}, which cannot see anything in a scene whose whole
     * method runs inside one server tick — {@code wd.buriedOre}'s 800 {@code tickAll()} iterations,
     * the {@code wd.serverMine*} family, {@code wd.selfShaftDigUp}. There {@code now} never moves,
     * so after the first line the delta is 0 forever and one line is printed however many times the
     * bot entered the stall window. {@code wiggleCalls} is bumped on EVERY call, before the
     * precondition, so {@code call - wiggleLastCall > 1} means exactly "the immediately preceding
     * call was not itself inside the window" — the same event in a per-tick world (this is called
     * at most once per walker tick) and a working one in a one-tick world. {@code seq=} is the
     * ordinal within the cap, the only thing that orders lines whose {@code t=} is frozen; a
     * trailing {@code seq=5+/4 cap reached} line (and only that line) means a further entry was
     * swallowed, so {@code seq=4/4} alone still means "exactly four entries".
     *
     * <p><b>And the bearing, measurement only.</b> The ring answered "is the reach shorter than the
     * throw" — yes, by one — but the answer does NOT license widening it: one directed rehearsal
     * caught both sides, ring 3 firing (a ladder bot took that into a lava lake) and ring 2 holding
     * through a three-minute stall. Direction is what separates them, so {@link
     * WalkerGeometry#hopBearingRow} prints where the bot points against where the lethal cell is.
     * Nothing branches on it yet, by the same rule that turned this gate's boolean into a ring.
     * ⚠️ That row prints the CAMERA; the bot is pushed along {@code driveTargetYaw}, decoupled
     * from it by design, so it is NOT the gate. The gate is {@link WalkerGeometry#hopSuppressed} —
     * the arc's own landing columns along the DRIVE bearing, which subsumes the ring test rather
     * than joining it — and {@link WalkerGeometry#hopLandingRow} prints the cells it read.
     *
     * <p>Why this earns a line at all: rung 20's takeoff samples showed the bot already airborne
     * with {@code sinceLastJump=7}, and eliminating the jump terms that need a riser or water leaves
     * {@code wiggle} as the only one that can fire on a flat dry level walk. That elimination is
     * REASONING, not a reading — the rehearsal log has no per-tick walker lines to check it against.
     * This is the reading.
     */
    boolean wiggleHop(WorldView world, LivingEntity p, BlockPos foot,
                      boolean precond, float driveYaw) {
        long call = ++wiggleCalls;   // bumped BEFORE the precondition: adjacency must count skipped calls too
        if (!(precond && stuckTicks > 10 && stuckTicks < 18)) return false;
        BlockPos lethal = WalkerGeometry.nearestLethalHopCell(world, p, foot, WIGGLE_SCAN_MAX);
        int ring = WalkerGeometry.ringOf(foot, lethal);
        boolean gated = ControlConfig.walkerRecoveryHopFloorGate && WalkerGeometry.hopSuppressed(world, p, foot, driveYaw);
        if (!gated && call - wiggleLastCall > 1) tallies.recoveryHops++;   // one per window entry, not per tick
        if (wiggleEvents < WIGGLE_EVENTS && call - wiggleLastCall > 1) {
            wiggleEvents++;
            LOG.info("[walker] recovery hop: seq={}/{} t={} stuck={} foot={} exact=({}) ringGateRadius={} nearestLethalRing={} gate={} jump={} {} | {}",
                    wiggleEvents, WIGGLE_EVENTS,
                    p.level().getGameTime(), stuckTicks, foot.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                    WalkerGeometry.HOP_RANGE, ring < 0 ? ">" + WIGGLE_SCAN_MAX : String.valueOf(ring),
                    ControlConfig.walkerRecoveryHopFloorGate, !gated,
                    WalkerGeometry.hopBearingRow(p, foot, lethal),
                    WalkerGeometry.hopLandingRow(world, p, foot, driveYaw));
        } else if (wiggleEvents >= WIGGLE_EVENTS && !wiggleCapped && call - wiggleLastCall > 1) {
            // Same blind spot as the jump-source cap: "seq=4/4" is the last line both when the bot
            // entered the stall window exactly WIGGLE_EVENTS times and when it entered it forty
            // times. One line, at the first entry the cap actually swallowed.
            wiggleCapped = true;
            LOG.info("[walker] recovery hop: seq={}+/{} cap reached, later events are not logged (printed once)",
                    WIGGLE_EVENTS + 1, WIGGLE_EVENTS);
        }
        wiggleLastCall = call;
        return !gated;
    }

    /**
     * Recovery-hop events logged per walker before the latch goes quiet.
     *
     * <p>Sixteen, not four. The budget is per WALKER INSTANCE — {@code wiggleEvents} is an instance
     * field with no reset, and every process carries its own {@code new Walker(…)} — so it is spent
     * per settle, not per run. That is not as generous as it sounds, because a single stall episode
     * burns it: on 2026-08-26 rung 12's bot stalled on a lava lake's rim, spent all four inside
     * thirteen seconds, and printed seq=5+/4 for the hops that mattered — the ones between the last
     * logged position and the bot's death in the lake. A cap that runs out inside the one stall
     * worth reading is not a hose guard, it is a blind spot with a budget. Sixteen is still bounded
     * — the line is one per ENTRY to a 7-tick window, not one per tick — and the seq=N+/16 line
     * still says when even that was not enough. Measured: one rehearsal stall printed six
     * consecutive suppressed hops (ring 2, gate=true, jump=false), of which a cap of four would
     * have shown four.
     */
    private static final int WIGGLE_EVENTS = 16;
    private int wiggleEvents;
    /** One-shot latch for the "the cap swallowed an entry" line — see {@link #wiggleHop}. */
    private boolean wiggleCapped;
    /** Monotone call counter for {@link #wiggleHop}'s clock-free adjacency latch, and the index of
     *  the last call that was inside the stall window. See that method's javadoc. */
    private long wiggleCalls;
    private long wiggleLastCall = Long.MIN_VALUE / 4;

    /** Which branch commanded THIS tick's jump (null = no jump commanded). Pure telemetry,
     *  reset each tick in the prelude: fall post-mortems keep needing "who launched the
     *  fatal arc?" (bridge battery: three different launchers over three rounds), and the
     *  aggregated drive jump erases the answer by the time the bot is airborne. */
    String jumpTag;

    /** This tick's aim-tree owner ({@code aimSrc}) — same telemetry channel as
     *  {@link WalkerInspector#jumpTag}: wedge post-mortems need "who owned the heading". */
    String aimTag;

    /** This tick's drive command (target yaw + impulse) as a compact string; null when
     *  the tick never reached the drive tail (early-return branch) — which is itself
     *  the answer a pinned-bot autopsy is after. Reset each tick in the prelude. */
    String driveTag;
    /** The walk of a body living in water or air, which takes every order and tick while it exists. */
    private MediumLocomotor medium;

    private Step tickInner(Body a, WorldView world) {
        WalkerTickCtx c = new WalkerTickCtx();
        Step r;
        r = WalkerTickPrelude.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickStallDetect.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickRepath.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickSearch.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickProgress.run(this, c, a, world); if (r != null) return r;
        // The route is settled for this tick; now the controllers.
        Set<Channel> taken = arbiter.tick(this, c, a, world);
        if (!taken.contains(Channel.HANDS)) {
            blocks.breakHold(false);   // nothing is breaking or placing this tick
            actionTicks = 0;
        }
        // The regular walk cannot yet drive only the channels no controller took: a controller on MOVE
        // ends the tick's decisions here, and one on LOOK leaves the walk standing to let it work.
        if (taken.contains(Channel.MOVE)) return Step.WALKING;
        if (taken.contains(Channel.LOOK)) return StandToWork.run(this, c, a, routeDig.work());
        r = WalkerTickClimb.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickEdgeGuards.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickAim.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickDrive.run(this, c, a, world);
        if (r == Step.WALKING) WalkerDig.preDigNextBreak(this, a, world);
        return r;
    }

    /**
     * End the walk: stamp {@link #result} and fire the route trace's terminal, so every terminal
     * return site stays a one-liner. {@code foot} may be null (no player) → conservatively not reached.
     */
    Step terminalReport(Step s, PathTrace.Outcome outcome, String reason, String endReason, BlockPos foot) {
        boolean reached = foot != null && goal != null && !goalSnapped && goal.reached(foot);
        double dist = (foot != null && goal != null) ? goal.estimate(foot) : -1;
        result = WalkVerdict.of(s == Step.FAILED, reason, reached, endReason, dist, goalSnapped,
                goal != null && goal.open());
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /** Stopped from outside before the walk ended (mc.bot.cancel, superseded by a new goto, the bot
     *  left the dimension). Fires the trace's CANCELLED terminal so per-session observers finalize
     *  the partial run (a path archive is flushed for a wedge the agent cancelled out of) — a no-op
     *  for the recorders if no session is open, so a cancel after a natural end is harmless — then
     *  drops the goal and everything about the walk. */
    @Override public void cancel(String reason) {
        PathTraceHolder.SINK.onTerminal(PathTrace.Outcome.CANCELLED, reason);
        start(null);
    }

    /** No onward route from the segment end: hold (keys released) and return WALKING while
     *  {@link Route#retryAtFrontier} re-searches; ARRIVED when out of retries or the feature is off. */
    Step frontierHoldOrArrive(Body a, WorldView world, LivingEntity p) {
        if (route.retryAtFrontier(this, world)) {
            avatarForward(a, false);
            avatarJump(a, false);
            p.setSprinting(false);
            return Step.WALKING;
        }
        return terminalReport(Step.ARRIVED, PathTrace.Outcome.SUCCESS, null, "frontier-giveup", p.blockPosition());
    }

    /** PROACTIVE PINCH escalation — see {@link #PINCH_MIN_PROGRESS}. When the big search
     *  commits a best-effort segment that barely closed the goal distance, the planner is
     *  wedged at a pinch (deepwater→bank, boxed canyon, steep barrier); arm the deep-search
     *  escalation on that FIRST struggling commit so the NEXT search suppresses its horizon,
     *  raises the depth penalty and routes AROUND the obstacle — instead of waiting for the
     *  executor to churn ~20 s until the reactive churn-window fires. Re-arms (extends) on
     *  each struggling commit while the pinch persists; logs only on the transition. Gated
     *  to {@link ControlConfig#pathfinderProgressive} (default OFF → behaviour unchanged). */
    void maybeArmPinchEscalation(BlockPos foot, PathFinder.Result res) {
        if (!ControlConfig.pathfinderProgressive) return;
        if (res.goalReached() || route.seg.commitEnd == null) return;      // healthy full route → no escalation
        double progress = goal.estimate(foot) - goal.estimate(route.seg.commitEnd);
        if (progress >= PINCH_MIN_PROGRESS) return;              // real headway → not a pinch
        boolean wasArmed = escal.armed();
        escal.arm(BOXED_ESCALATE_STICKY_TICKS);
        if (GameConfig.walkerDebug && !wasArmed)
            LOG.info("[walker] proactive pinch escalation ARMED: best-effort commit gained only {} blocks toward goal → deepen next search",
                    String.format("%.1f", progress));
    }

    /** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
    void sampleTick(LivingEntity p) {
        // Cheap gate: skip the per-tick WalkerSample allocation entirely unless capture is on.
        // Keeps the hot path free in normal play and in a stripped (NOOP) release build.
        if (!ControlConfig.pathDebug && !ControlConfig.pathArchive) return;
        // Expose the bot's level to PathArchiveRecorder (and other PathTrace sinks) without
        // referencing the client-only Minecraft class.  Set here — before onSearchResult or
        // onSearchBegin can fire within the same tick (both sites are below line 451) — so
        // the first segment's NodePhysics and envelope sampling always see a non-null level.
        BotLevelHolder.current = p.level();
        double tx = Double.NaN, tz = Double.NaN;
        String mv = null;
        if (route.path() != null && route.step() >= 0 && route.step() < route.path().size()) {
            BlockPos t = route.path().get(route.step());
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            Move.Edge e = edgeAt(route.step());
            mv = (e != null) ? e.move : null;
        }
        boolean overlap = !p.level().noCollision(p, p.getBoundingBox().deflate(1.0E-7));
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, route.step(), mv, p.onGround(), p.isInWater(),
                p.getPose().name(), overlap, p.getId()));
    }

    Move.Edge edgeAt(int i) {
        return route.edgeAt(i);
    }

    /** task#82 migrated ascent move-names (spec §3.3): exactly the three the recovery gates already
     *  test — a plain stepUp, its break-carrying sibling stairUpBreak, and the diagonal diagUp.
     *  stepUp2 (+2, horse) and parkourAscend* are excluded (spec §10). Cheap string check so the OFF
     *  delegation short-circuit costs nothing. */
    static boolean isMigratedAscent(String move) {
        return "stepUp".equals(move) || "stairUpBreak".equals(move) || "diagUp".equals(move);
    }

    /** Phase-0 SHADOW arc-length pursuit (walkerArcLengthShadow). Projects the continuous foot XZ onto the
     *  path polyline within a FORWARD window [step, step+W] (so it can't snap backward onto a self-overlapping
     *  earlier segment — the dominant projection risk), honouring the adoption's overlap barriers (a submerged
     *  below-node is a hard dive barrier; a pending break/place edge stops the scan). Reports the projected
     *  segment, cumulative XZ arc-length s (from path[0]), horizontal perpendicular distance, and the tangent
     *  heading at s+lookahead — and counts backward-snap events (ds&lt;-0.5). DRIVES NOTHING; this only proves
     *  (via replay) that s is monotonic and the projected segment tracks the live {@code step} on clean runs,
     *  the precondition for Phase 1 driving the step pointer off the projection. */
    void arcShadowTick(WorldView world, BlockPos foot, double px, double pz, float liveYaw, boolean inW, boolean hCol) {
        arc.proj.compute(route.path(), route.edges(), world, route.step(), foot.getY(), px, pz, 16, 2.5);
        // Monotonic / backward-snap check vs the previous tick (reset on a fresh path object).
        boolean reset = (route.path() != arc.shadowPath);
        double ds = (reset || Double.isNaN(arc.shadowS)) ? 0.0 : arc.proj.s - arc.shadowS;
        if (!reset && ds < -0.5) arc.monoViol++;
        // Phase-3 arc-wedge accumulator: a grounded DRY forward-RAM that makes no arc progress. Excluded when the
        // forward window is blocked by a pending vertical edge (arc.proj.barrierHit = a legit pillar/bridge/parkour
        // hold where zero ds is expected) or on a fresh path. The horizontalCollision gate keeps it off deliberate
        // non-ramming pauses (brakes, goal-dwell). EXCLUDES water: in water |ds|≈0 while hCol is the NORMAL state of
        // a swim-into-bank / climb-out, which owns its own dedicated recovery (waterCellTax / swimBankClimb / dig);
        // firing the wedge there repath-storms the climb-out and resets its aim every reroute (live -665 water-gap:
        // 7× arc-wedge at step 1 in water, dYaw stuck 95°, repeated turning back and side-to-side swerving). The targeted ram is the DRY mountain
        // wall-ram. Bob/jitter-immune: vertical motion doesn't move the XZ projection.
        if (reset || inW || arc.proj.barrierHit || !hCol || Math.abs(ds) > ARC_WEDGE_DS) arc.wedgeTicks = 0;
        else arc.wedgeTicks++;
        // Phase-3b NET arc-progress window: an oscillating limit cycle makes per-tick |ds| > ARC_WEDGE_DS (so the ram
        // counter above resets) yet zero NET path progress. Measure s over a whole window instead. Excludes water
        // (own recovery) and a legit pending-vertical-edge hold (barrierHit = expected zero ds). No hCol/onGround gate
        // — a bob-jumping airborne churn has neither.
        if (reset || inW) { arc.progBaseS = arc.proj.s; arc.progWindowTicks = 0; arc.progStall = false; }
        else if (++arc.progWindowTicks >= ARC_PROG_WINDOW) {
            arc.progStall = !arc.proj.barrierHit && (arc.proj.s - arc.progBaseS) < ARC_PROG_MIN;
            if (arc.progStall && GameConfig.walkerDebug)
                LOG.info("[walker] arc-progress-wedge: net s={} < {} over {} ticks at step={} node={} → fellOffPath",
                        String.format("%.2f", arc.proj.s - arc.progBaseS), ARC_PROG_MIN, ARC_PROG_WINDOW, route.step(),
                        (route.step() < route.path().size() ? route.path().get(route.step()) : null));
            arc.progBaseS = arc.proj.s;
            arc.progWindowTicks = 0;
        }
        arc.shadowPath = route.path();
        arc.shadowS = arc.proj.s;
        float dYaw = angleDiff(arc.proj.tangentYaw, liveYaw);    // the camera-vs-tangent gap
        if (GameConfig.walkerDebug)
            LOG.info("[walker] arc-shadow step={} projSeg={} segFrac={} s={} ds={} perp={} tanYaw={} liveYaw={} dYaw={} monoViol={} inW={}",
                    route.step(), arc.proj.segIdx, String.format("%.2f", arc.proj.segFrac), String.format("%.2f", arc.proj.s),
                    String.format("%.2f", ds), String.format("%.2f", arc.proj.perp),
                    String.format("%.1f", arc.proj.tangentYaw), String.format("%.1f", liveYaw),
                    String.format("%.1f", Math.abs(dYaw)),
                    arc.monoViol, inW);
    }

    /** A continuously-sliding aim point {@code CARROT_DIST} blocks ahead
     *  along the path (interpolated between nodes), capped by line-of-sight —
     *  the pure-pursuit "carrot". Because it's interpolated (not snapped to a
     *  discrete node), the bearing to it changes smoothly as the player moves,
     *  so the heading doesn't wobble over the cardinal/diagonal staircase.
     *  Stops at a wall/turn or an action cell so it never aims through one.
     *  Returns {x, z} world coords. */
    double[] carrotPoint(WorldView world, BlockPos foot, double px, double pz) {
        double remaining = CARROT_DIST;
        // Sustained wall collision → the far carrot is steering the bot at a gap only
        // the LOS ray fits (jungle trunks). Collapse pursuit to the immediate node —
        // the A* chain is walkable at player width by construction (walkerCarrotHColShrink).
        if (ControlConfig.walkerCarrotHColShrink && churn.hColRamTicks >= 8) remaining = 0.01;
        // No LOS even to the current node falls back to that node, not to the bot itself: a zero aim
        // vector holds the stale yaw, and a bot that walked off a pulled diagonal kept ramming the
        // corner it had drifted onto (live 2026-09-28, 1389.57,66,-478.70: 38 ticks, bearing to wp -131, yaw -178).
        double cx = px, cz = pz;
        double tx = route.step() < route.path().size() ? route.path().get(route.step()).getX() + 0.5 : px, tz = route.step() < route.path().size() ? route.path().get(route.step()).getZ() + 0.5 : pz;
        for (int i = route.step(); i < route.path().size() && i - route.step() <= CARROT_MAX_NODES; i++) {
            BlockPos node = route.path().get(i);
            double nx = node.getX() + 0.5, nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, edgeAt(i))) return new double[]{nx, nz}; // face the action cell
            boolean losOk = ControlConfig.walkerCarrotBodyLos
                    ? PathSmoothing.losWalkableBody(world, foot, node)
                    : losWalkable(world, foot, node);
            if (!losOk) break;                                                 // don't aim past a wall
            double seg = Math.hypot(nx - cx, nz - cz);
            if (seg < 1e-6) { tx = nx; tz = nz; continue; }
            if (remaining <= seg) {
                double t = remaining / seg, ax = cx + (nx - cx) * t, az = cz + (nz - cz) * t;
                // Past a corner the chord to the carrot is not the checked foot→node line: live
                // 1489,65,-399 was a pit beside the 1491,66,-400 corner that the chord crossed.
                // At the box's true half-width: with the margin, a body pressed on a trunk failed every chord.
                if (i > route.step() && !PathSmoothing.losWalkableBody(world, px, pz, foot, ax, az, node.getY()))
                    return new double[]{cx, cz};
                return new double[]{ax, az};
            }
            // The LOS test ramps y from foot to node, so it passes a straight line up a stair flight that
            // takes one jump per riser: live 1543,64,-151 aimed 45° off a three-stepUp flight and fell
            // into the pit beside it (1541,62,-154), then pillared out (5 s).
            if (ControlConfig.walkerCarrotStopAtRise && node.getY() > foot.getY()) return new double[]{nx, nz};
            remaining -= seg;
            cx = nx; cz = nz; tx = nx; tz = nz;
        }
        return new double[]{tx, tz};
    }

}

