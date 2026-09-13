package net.magicterra.worlddriver.bot.movement;

import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.debug.BotLevelHolder;
import net.magicterra.worlddriver.bot.movement.PathSmoothing.SmoothResult;
import net.magicterra.worlddriver.bot.pathfinder.Move;
import net.magicterra.worlddriver.bot.pathfinder.PathFinder;
import net.magicterra.worlddriver.bot.pathfinder.PathTrace;
import net.magicterra.worlddriver.bot.pathfinder.PathTraceHolder;
import net.magicterra.worlddriver.bot.pathfinder.SearchProfile;
import net.magicterra.worlddriver.bot.pathfinder.Constraint;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.pathfinder.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static net.magicterra.worlddriver.bot.movement.ClutchController.CLUTCH;
import static net.magicterra.worlddriver.bot.movement.PathSmoothing.*;
import static net.magicterra.worlddriver.bot.util.BotInteract.*;
import static net.magicterra.worlddriver.bot.util.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.WorldDriverCommon.LOG;
import static net.magicterra.worlddriver.bot.movement.WalkerConstants.*;
import static net.magicterra.worlddriver.bot.movement.WalkerGeometry.*;

public final class Walker {
    public enum Step { WALKING, ARRIVED, FAILED }
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

    /** This walker's own event counts (searches, recovery hops, digs) — see {@link WalkerTallies}. */
    final WalkerTallies tallies = new WalkerTallies();
    public WalkerTallies tallies() { return tallies; }

    /** How many times {@code WalkerTickProgress}'s unwalked-descent refusal has held the step pointer
     *  on a node below the body's feet, process-wide. Monotone, never reset by the walker — a caller
     *  that wants a window takes the difference, the way {@link #lastStats} is read.
     *
     *  <p>Exists so a scene can tell「the hold cost nothing」from「the hold never ran」, which are the
     *  same reading from outside and want opposite conclusions: a negative control over an ordinary
     *  staircase passes identically whether the guard is doing its job cheaply or is inert, and a
     *  guard that is inert on every descent this suite stages is a guard the suite cannot judge. */
    public static volatile long descentHolds;

    /** gap#72-④: the chain/verb this walker moves for (e.g. "mine", "retreat",
     *  "goto") — threaded into every {@link PathFinder} it launches so latest.log's
     *  search-begin lines are attributable. "?" = an untagged caller (test rigs,
     *  direct arena walkers). Telemetry only; never read by planning/steering.
     *  <p>Who owns this Walker's searches, for telemetry. Defaults to a name that at least says WHAT
     *  it is: a thread dump reading {@code owner=?} cost a round of this investigation, because "?"
     *  reads as "not a Walker's" when it actually means "a Walker nobody named". Scenes that drive
     *  the bot directly never call {@link #setOwner}, so this default is what they get. */
    String owner = "walker.unnamed";

    public Walker() {}
    /** gap#72-④: construct pre-tagged — the one-expression form for the processes'
     *  {@code private final Walker walker = new Walker("mine")} field initializers. */
    public Walker(String owner) { setOwner(owner); }
    public void setOwner(String owner) { if (owner != null && !owner.isEmpty()) this.owner = owner; }

    Goal goal;
    /** Per-intent search profile (bias + capability + constraints) forwarded to
     *  every PathFinder this Walker builds (A4a bias-only; A2a full profile).
     *  {@link SearchProfile#NONE} = plain navigation; IntentProcess sets it from the Intent. */
    SearchProfile profile = SearchProfile.NONE;
    /** Cached from {@link #profile} in {@link #setSearchProfile}: does the current profile carry a
     *  {@link NoBreak} (a per-goto {@code forbidDig})? Cached so the execution-layer dig gate
     *  ({@link #mayBreak}) is a field read, not a per-tick constraint scan at every fallback site. */
    boolean profileForbidsBreak = false;

    /** Optional per-Walker deep-search budget (nodes, ms); -1 = the global
     *  {@link BotConfig#pathfinderMaxNodes}/{@link BotConfig#pathfinderMaxMs}.
     *  For owners whose goals are always NEAR (mine's approach stands sit within
     *  its ≤64-block scan): an ore stand embedded in a wall is proven unreachable
     *  after a few thousand expansions, and grinding the full 50k/2s budget per
     *  candidate is pure waste — sliced at {@link BotConfig#pathfinderIdleSliceMs}
     *  it also chops the client to ~25 fps for seconds right after each block
     *  break (the "挖完卡一下" report). The real gate for hopeless stands is the
     *  mine process's 100t no-approach blacklist; a small budget just reaches it
     *  sooner. Long-haul navigation (goto/escape) keeps the full defaults. */
    int searchMaxNodes = -1;
    long searchMaxMs = -1;
    public void setSearchBudget(int nodes, long ms) {
        this.searchMaxNodes = nodes;
        this.searchMaxMs = ms;
    }

    /** Single construction point for this Walker's deep-search PathFinders so the
     *  per-Walker budget override, tuning and scope source apply to every search launch site
     *  alike (foot repath, commit-end continuation, place-suppressed re-plan). See {@link WalkerFinders}. */
    PathFinder newPathFinder(WorldView world) { return WalkerFinders.deep(this, world); }

    /** The body the last {@link #tick(Avatar, WorldView)} drove, for the finders' scope source.
     *  Null until the first tick, which is also the first time a search can start. */
    Player body;

    /** Set the per-intent search profile for subsequent searches. Null → {@link SearchProfile#NONE}. */
    public void setSearchProfile(SearchProfile p) {
        this.profile = (p == null) ? SearchProfile.NONE : p;
        boolean forbids = false;
        for (Constraint c : this.profile.constraints()) if (c instanceof NoBreak) { forbids = true; break; }
        this.profileForbidsBreak = forbids;
    }

    /** True when the executor MAY mutate the world to recover (the discretionary dig FALLBACKS:
     *  wallDig, the swim/bank climb-out riser, the lily-pad ram). Honors BOTH the global
     *  {@link BotConfig#allowBreak} master switch AND the per-intent {@link NoBreak} constraint
     *  (a per-goto {@code forbidDig}): an executor recovery must never exceed the world-mutation
     *  authority the planner was given. Before this, the fallbacks checked only the global switch,
     *  so a {@code forbidDig} bot pinned against terrain still tunnelled (survival day6: a leashed
     *  {@code goto entityId forbidDig:true} carved back underground; the live workaround was to kill
     *  global {@code allowBreak}, which also killed legitimate planned digs). Planned break edges
     *  are NOT gated here — {@code NoBreak} already prunes them at the planner. The single-head-cell
     *  anti-suffocation dig (pillarRecover) is DELIBERATELY exempt and stays on {@code allowBreak}
     *  only: suffocation is death, {@code forbidDig} is a navigation preference — safety wins. */
    boolean mayBreak() {
        return BotConfig.allowBreak && !profileForbidsBreak;
    }
    /** Ticks the pointer has been HELD on a final node that is the goal while the foot is not yet
     *  in it (walkerHoldLastNodeUntilStanding); keyed to the path and step it counts for. */
    int finalNodeHold;
    Object finalNodeHoldPath;
    int finalNodeHoldStep = -1;
    boolean goalSnapChecked;   // one-shot per goal: snap an unstandable Goal.Block target to the nearest standable cell (see snapGoalToStandable) — needs a live WorldView so it runs on the first tick, not at setGoal
    List<BlockPos> path;
    List<Move.Edge> edges;   // aligned with path; edges.get(i) enters path.get(i)
    /** The A* result before stringPull, as a tally — see {@link #rawPlanTally()}. */
    private String rawPlan;
    /** The first parkour takeoff this walk ever made — see WalkerTickDrive's latch. */
    String parkourTakeoff;
    int step;
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
        // loops): the old fixed 150t cap assumed "any reachable block completes
        // inside 150t", but vanilla stacks x5 (eye in water) and x5 (airborne)
        // dig penalties MULTIPLICATIVELY — a swimAshoreClimb bank dig needs
        // 450t (dirt) to 3750t (stone, wrong tool), so the cap released every
        // dig at 151t with the block still solid, progress reset to zero, and
        // the walker re-acquired the same cell forever. Track真实 destroy
        // progress instead: hold while it climbs, release only on a true stall.
        float lastProgress;   // last observed vanilla destroyProgress (0..1)
        int stallTicks;       // consecutive ticks with no progress increase
        // Bob-reset bypass (same live session): while the bot BOBS in water the
        // eye/raycast dips behind the bank lip on some ticks, vanilla's
        // continueAttack then targets a DIFFERENT cell and zeroes the progress
        // — the dig can never finish no matter how long we hold. The direct
        // drive (gameMode.continueDestroyBlock on the exact cell — the
        // AntiSuffocate gap#69 pattern) decouples progress from the crosshair
        // entirely, and every dig site now runs it unconditionally.
        int rayMiss;          // raycast-off-target ticks for this dig (cumulative)
        boolean direct;       // latched once the raycast has wandered: drop keyAttack,
                              // so vanilla cannot drive a second cell alongside ours
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
            rayMiss = 0;
            direct = false;
        }

        /** Revoke unconditionally, for a preempting SAFETY dig (suffocation) — that is not a
         *  navigation preference and must not queue behind one. The expiry policy still lives only
         *  in the prelude; this is a named override with a caller, not a second clock. */
        void revoke() { pos = null; ticks = 0; lastProgress = 0f; stallTicks = 0; rayMiss = 0; direct = false; }
    }
    /** In-progress pillarUp edge (task#96 step B8), owned by WalkerTickClimb; both
     *  fields cleared to -1 by both journey resets via {@link PillarEdge#reset()}. */
    final PillarEdge pillar = new PillarEdge();
    static final class PillarEdge {
        int step = -1;        // path index of the pillar edge in progress
        int sinceJump = -1;   // ticks since the pillar jump press (-1 = grounded)
        void reset() {
            step = -1;
            sinceJump = -1;
        }
    }
    /** Water climb-out machine (task#96 step B10), owned by WalkerTickClimb (digging is
     *  read by Search/StallDetect for the breakingEdge OR). {@link WaterClimb#reset()}
     *  clears the fourteen fields both journey resets cleared (a resume mid climb-out
     *  otherwise carries a stale window base-Y / armed stall). NOT in the reset — all
     *  latch-gated or self-managing: lastDigAimEyeY (re-snaps per dig aim), the LOCKED
     *  engage column/heading (targetY/colX/colZ/yaw — only read while pillaring), and
     *  digGroundedStreak (per-commit blip counter). */
    final WaterClimb waterClimb = new WaterClimb();
    static final class WaterClimb {
        int stall;                // armed flag (>WATER_CLIMB_STALL) once net-displacement window shows a bob-stall climbing out of water
        int touchRecent;          // sticky countdown: >0 while in a water climb-out, kept latched through bob-peak surface breaches (see WATER_TOUCH_STICKY)
        int wantClimbRecent;      // sticky countdown: >0 while a higher node sits ahead, latched through bob-peak/repath flicker (see WANT_CLIMB_STICKY)
        boolean pillaring;        // latched: pillaring up the bot's column to bank stand level
        boolean digging;          // set the tick the block-less bank-dig actuator swings; OR'd into breakingEdge next tick so the anti-stuck burst can't yank the bot off the riser mid-dig (it has no planned toBreak edge of its own)
        BlockPos lastDigRiser;    // the riser the dig last aimed at; re-snap the look ONLY when it changes (not every tick) so the camera holds steady instead of juddering off the bobbing eye — the bob keeps the crosshair on the 1-tall block between re-aims
        double lastDigAimEyeY = Double.NaN;   // eye-Y at the last dig aim-snap; re-snap once the buoyant bob has moved the eye far enough (DIG_REAIM_EYE_DY) that the fixed ray would drift OFF the 1-tall riser face (the ±0.5 once-only assumption fails for a ±1.5 deep-water bob → break never lands, 30s bob-stall)
        BlockPos digRiser;        // LATCHED bank-dig riser cell — held while still solid so a buoyant bob (foot.y flickering ±1) or lateral drift (foot.z wandering) can't re-target a LOWER block of the same column or a neighbouring column mid-dig; cleared once the block breaks so the next +1 step is chosen fresh (drift-arena over-dig fix)
        int digCommitTicks;       // ticks spent committed to the CURRENT latched riser; while >0 and <CAP the climb-out won't disengage on a transient wantClimb flicker, so a slow stone-bank break (~750 ticks by hand) finishes instead of being abandoned mid-dig → wander/sink; reset per riser
        int digFloatTicks;        // consecutive ticks committed to the current latched riser while the bot stayed AFLOAT (onGround=false); reset to 0 on any ground contact. Feeds the futile-overhang early-release (walkerFutileBankDigRelease): a high count + a >=2-above-foot riser that breaks NOTHING = an unreachable overhang, not a legit grounded climb-out (which touches ground and resets this)
        int futileBankDigCooldown;    // ticks left during which the bank-DIG must NOT re-engage after a futile-overhang early-release (walkerFutileBankDigRelease) — gives the reactive back-off burst time to physically move the bot off the unreachable riser before any dig can re-latch it; decremented per tick
        boolean pillarGaveUp;     // latched once the pillar takeover proves futile (drifted off its locked column, or bob peak never clears the surface fill cell) → block pillar re-engage + let the bank-DIG take over even with a place block in hand; cleared when the climb context ends
        BlockPos gaveUpPos;       // where the pillar proved futile (walkerClimbGaveUpSticky) — while the foot stays within 3 blocks and the TTL runs, the gave-up latch survives climb-context resets (repath node swaps) so the proven-futile pillar can't re-engage in a loop
        int gaveUpTtl;            // ticks left on the sticky gave-up latch (walkerClimbGaveUpSticky); decremented per tick, 0 = expired
        int pillarNoPlaceTicks;   // ticks the pillar takeover has been engaged without a successful place / height gain — buoyant bob can't lift feet above a surface fill cell, so beyond PILLAR_FUTILE_TICKS the place is hopeless and we fall to the dig
        BlockPos placeAttemptCell;   // the cell the last climb-out click aimed at, carried to the NEXT tick so the ledger above can ask whether it actually filled. Avatar.place is void — the click has no verdict — and vanilla refuses any placement whose cell still intersects the body's AABB, so "clicked" and "placed" are different events and only the second one is progress. Cleared once read.
        int pillarHighWaterY = Integer.MIN_VALUE;   // highest foot.getY() this takeover has reached — the OTHER half of "successful place / height gain". A HIGH-WATER MARK, not a per-tick delta: a buoyant bob crosses a block boundary every cycle, so "higher than last tick" is true forever and would count buoyancy as progress; "higher than ever" stops refreshing the moment the bob settles between two cells. Re-based at engagePillar so a re-locked column cannot inherit the previous segment's height.
        int targetY;              // safety ceiling Y for the pillar (engage foot + a few); bail if exceeded
        int colX, colZ;           // LOCKED column the takeover pillars in (don't chase repathing nodes)
        float yaw;                // LOCKED heading toward the bank at engage (no horizontal chase → no wander)
        boolean sideRung;         // this takeover has placed a side foothold: keys are anticipatory from here (forward only while wet, jump only wet or settled on the ground) — see WalkerTickClimb; cleared at engage
        final java.util.Set<BlockPos> placedRungs = new java.util.HashSet<>();   // every cell a climb-out click has aimed at; a rung of our own beside the body is not an exit, however dry (walkerShallowWaterSideFoothold)
        int digGroundedStreak;    // consecutive grounded ticks during a committed bank dig — a bob bottom-blip (<=5) must not break the dig commit (walkerBankDigGroundBlip)
        void reset() {
            stall = 0;
            touchRecent = 0;
            wantClimbRecent = 0;
            pillaring = false;
            digging = false;
            pillarGaveUp = false;
            gaveUpPos = null;
            gaveUpTtl = 0;
            pillarNoPlaceTicks = 0;
            placeAttemptCell = null;
            pillarHighWaterY = Integer.MIN_VALUE;
            lastDigRiser = null;
            digRiser = null;
            digCommitTicks = 0;
            digFloatTicks = 0;
            futileBankDigCooldown = 0;
        }
    }
    /** Drowning-escape override (task#96 step B): walkerDrowningEscape — air critically
     *  low while submerged → surface-for-air until air recovers. Owned by WalkerTickClimb.
     *  {@link DrownGuard#reset()} clears only the latch and the heading-hold timer (exactly
     *  what both journey resets cleared): heading is scratch (only read while turnTicks>0)
     *  and probe deliberately PERSISTS across goals so a falsely-open pocket direction is
     *  not re-picked forever. */
    final DrownGuard drownGuard = new DrownGuard();
    static final class DrownGuard {
        boolean latch;     // surface-for-air override active until air recovers
        int turnTicks;     // pocket probe: ticks left holding the current escape heading
        float heading;     // current pocket-escape heading (deg)
        int probe;         // rotating probe start index so a falsely-open direction is not re-picked forever
        void reset() {
            latch = false;
            turnTicks = 0;
        }
    }
    /** Grind-locked stepUp back-off (task#96 step B): walkerStepUpBackoffRetry — drive
     *  straight BACK from a grind-locked riser to open sprint runway. Armed by
     *  WalkerTickDrive, driven/decayed by WalkerTickRepath. {@link StepUpBackoff#reset()}
     *  clears the drive and cooldown timers (exactly what both journey resets cleared);
     *  yaw is scratch (only read while ticks>0). */
    final StepUpBackoff stepUpBackoff = new StepUpBackoff();
    static final class StepUpBackoff {
        int ticks;         // ticks left driving straight BACK from the riser
        float yaw;         // heading of the back-off drive (bearing away from the riser), camera-frame decoupled
        int cooldown;      // ticks before the back-off may trigger again (prevents oscillating retreat at a genuinely unmountable riser)
        void reset() {
            ticks = 0;
            cooldown = 0;
        }
    }
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
    /** Fell-below-route pillar-up recovery (task#96 step B4): engaged by
     *  WalkerTickStallDetect, driven (place + latch decay) by WalkerTickDrive, latch read
     *  by WalkerTickClimb's edge gate. {@link PillarRecover#reset()} clears only the
     *  latch (exactly what both journey resets cleared) — cell/peakY/stallTicks are
     *  latch-gated and re-initialized by StallDetect on each fresh engage. */
    final PillarRecover pillarRecover = new PillarRecover();
    static final class PillarRecover {
        int latch;             // ticks left driving an in-place pillar-up recovery (bot fell below the climb path beyond jump reach) — latched across the jump's airborne phase so a place can land
        BlockPos cell;         // the (grounded) feet cell the recovery is filling this rung
        int peakY;             // highest foot Y this pillar-recovery has reached (no-rise give-up tracking)
        int stallTicks;        // consecutive recovery ticks with no height gain → PILLAR_NORISE_GIVEUP re-routes
        void reset() {
            latch = 0;
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
     *  {@link GoalSpin#resetForNewGoal()} (setGoal only) clears the baselines;
     *  goalSpin.churnResets deliberately PERSISTS across goals (fresh-baseline grants are a
     *  per-body budget, not per-journey — do NOT add it to the reset). */
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
     *  {@link BoxedChurn#resetForNewGoal()} runs in setGoal only (forceRepath never
     *  cleared this window); hColRamTicks has NO reset site anywhere — it self-zeroes
     *  on every collision-free tick. The water anti-spin's goalSpin.churnResets stays a loose
     *  field (cross-goal persistence — see its doc). */
    final BoxedChurn churn = new BoxedChurn();
    static final class BoxedChurn {
        BlockPos base;         // foot at the start of the current net-displacement window
        int windowTicks;       // ticks elapsed in the current window
        int escapes;           // consecutive windows that detected churn (escalates the charge radius)
        int hColRamTicks;      // wall-corner: consecutive ticks of sustained horizontalCollision (the §39 贴墙卡住 ram signature)
        void resetForNewGoal() {
            base = null;
            windowTicks = 0;
            escapes = 0;
        }
    }
    /** Sticky steep-barrier escalation clock (task#96 step B4): tick is the monotonic
     *  per-walker tick counter (incremented by WalkerTickPrelude, NEVER reset — the
     *  sticky timer compares against it); armed by the churn window (WalkerTickStallDetect)
     *  and the proactive pinch check ({@link #maybeArmPinchEscalation}); read each tick by
     *  WalkerTickPrelude to publish BotConfig.pathfinderBoxedEscalate. Both journey resets
     *  {@link EscalationClock#disarm()} so the escalation never leaks into the next goto. */
    final EscalationClock escal = new EscalationClock();
    static final class EscalationClock {
        long tick;             // monotonic per-tick counter (drives the sticky boxed-escalation timer)
        long untilTick;        // escalation armed until this tick (sticky so a few net-progress windows mid-climb don't drop it)
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
     *  (setGoal + forceRepath — exactly the five fields both cleared); adoptPath and
     *  beginScriptedFollow keep their DIFFERENT partial restarts as direct writes
     *  (adoptPath re-bases stuckStepHigh to step-1, not -1). The dwell/orbit counters
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
    float freeHangDriveYaw = Float.NaN;             // slew-limited world heading of the free-hang vine DRIVE (NaN = resync); smooths the step-jitter ±180° flips that would circle the body off a narrow column — EdgeGuards-owned, self-resyncing (no journey reset)
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
        int steepDescentLatch = 0;                  // DRY sibling of deepWaterDriftLatch (task#36): ticks the steep-descent sprint-brake stays latched across the airborne sub-arcs of a step-down, so sprint can't re-arm mid-fall and accumulate forward momentum off a survivable-deep lip (live 2026-07-11 Mountains massif: onG=false→sprint=true walked the body off a 19-block lip to death)
        int climbPressConsec = 0;                   // consecutive ticks the buoyant-climb-press raw condition has held (debounces the surface-bob false trigger)
        int descentDriveRejectStreak = 0;           // consecutive back-hop rejections on a dry diagDown slope (escape-hatch snaps to the real node after WATER_DRIVE_MAX_REJECT)
        int underwaterTicks;                        // consecutive eyes-under ticks → debounces the swim-up jump (surface bob ≠ sinking)
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
    /** Async search + best-effort segment commit (task#96 step B9): the in-flight
     *  time-sliced A* and the partial-segment plumbing around it, driven by
     *  WalkerTickSearch/Repath and Walker's adopt/splice/frontier logic.
     *  {@link SegmentCommit#reset()} clears the search + continuation state (exactly
     *  what both journey resets cleared); seg.pathBestEffort/seg.searchSuppressedPlace follow
     *  path adoption, and seg.frontierWaitTicks resets on adoption progress only. */
    final SegmentCommit seg = new SegmentCommit();
    static final class SegmentCommit {
        PathFinder.Search activeSearch;             // in-flight time-sliced A* (null = none)
        boolean pathBestEffort;                     // current path is a best-effort partial (goal NOT reached) → commit to it before re-searching
        BlockPos commitEnd;                         // last node of the current best-effort segment (null for a full path) → where continuation searches launch from
        boolean searchFromEnd;                      // seg.activeSearch is a continuation launched from seg.commitEnd (deferred splice) vs a foot-search (splice immediately)
        PathFinder.Result pendingSegment;           // a finished continuation segment awaiting splice at the current segment's end
        boolean searchSuppressedPlace;              // the in-flight search dropped placing moves (block-budget reroute) → adopt its result without re-checking
        int frontierWaitTicks;                      // bounded retries re-searching at a loaded-chunk frontier before giving up (pathfinderFrontierCommit)
        void reset() {
            activeSearch = null;
            commitEnd = null;
            searchFromEnd = false;
            pendingSegment = null;
        }
    }
    /** Anti-stuck forced displacement (task#96 step B): the wedge-repath counter anchored
     *  at lastWedgeFoot (WalkerTickRepath is the ONLY setter of the anchor) and the
     *  displacement burst it fires (burst driven by WalkerTickRepath; also armed directly
     *  by WalkerTickStallDetect's riser-shove). Deliberately NOT touched by forceRepath:
     *  the wedge memory must survive a process resume, else a resume loop at the same
     *  wedge never escalates to the burst. {@link Unstuck#resetForNewGoal()} runs in
     *  setGoal only (burstYaw is scratch — read only while burstTicks>0);
     *  {@link Unstuck#dropWedgeAnchor()} is the full anchor drop (progressive-stub
     *  adoption, post-burst displacement) — WalkerTickClimb's dig-start clear is a
     *  PARTIAL one (count only, anchor retained) and stays a direct field write. */
    final Unstuck unstuck = new Unstuck();
    static final class Unstuck {
        BlockPos lastWedgeFoot;   // where the last wedge/stuck repath fired
        int wedgeRepathsHere;     // consecutive wedge repaths from (about) the same foot
        int countCooldown;        // min ticks between counted repath events (debounce)
        int burstTicks;           // ticks left driving the forced displacement
        float burstYaw;           // fixed heading for the displacement burst
        void resetForNewGoal() {
            lastWedgeFoot = null;
            wedgeRepathsHere = 0;
            countCooldown = 0;
            burstTicks = 0;
        }
        void dropWedgeAnchor() {
            wedgeRepathsHere = 0;
            lastWedgeFoot = null;
        }
    }
    /** @see SearchGovernors */
    final SearchGovernors searchGov = new SearchGovernors();
    int dbgPrevStep = -1;     // walkerDebug: detect step changes for per-step timing
    int dbgTicksOnStep = 0;   // walkerDebug: ticks spent on the current step
    boolean replayMode;   // executing a fixed archived plan: no repath/quick-start/splice/anti-stuck repath
    public String lastError;
    /** gap#68-R2 honest terminal report: why the last tick() returned ARRIVED/FAILED,
     *  whether the (possibly snapped-away-from) goal was ACTUALLY reached at the foot,
     *  and the heuristic distance left. Written once at every terminal() call site;
     *  read by IntentProcess to stamp the status slot. Volatile: status threads read. */
    public volatile String lastEndReason;
    public volatile boolean lastGoalReached;
    public volatile double lastFinalDist;
    /** True once snapGoalToStandable() rewrote the requested Goal.Block to a nearby
     *  standable cell — arrival then means "arrived NEAR", not "arrived AT". */
    boolean goalSnapped;

    /** {@link #goalSnapped} for the post-mortem channel. Package-private fields are invisible to
     *  testmod scene classes, and this bit is half of what "the walker arrived and the ore is still
     *  there" means: a snapped goal says the cell the process asked for was not the cell the walker
     *  drove at, so the two can be judged separately. */
    public boolean goalSnapped() { return goalSnapped; }

    /** The water climb-out's place-futility ledger, for scenes that assert on the FALLBACK rather
     *  than on the climb: {@code > PILLAR_FUTILE_TICKS} is what hands the bank over to the dig.
     *  Exposed because the counter is the subject — a scene that could only watch the body would
     *  have to distinguish "never gave up" from "gave up and the dig also failed", which look the
     *  same from outside. */
    public int pillarNoPlaceTicks() { return waterClimb.pillarNoPlaceTicks; }

    /** The verdict that ledger feeds: has the climb-out place been futile long enough to hand this
     *  bank to the dig? Exposed instead of the raw threshold so {@link WalkerConstants} stays
     *  package-private — a scene needs the DECISION, not the number behind it. Transient by
     *  nature: the tick it goes true, the takeover releases and zeroes the counter, so a scene
     *  must sample it per tick rather than read it at the end. */
    public boolean pillarPlaceFutile() { return waterClimb.pillarNoPlaceTicks > PILLAR_FUTILE_TICKS; }

    /** Is the climb-out takeover still holding the body? Exposed because {@link #pillarNoPlaceTicks}
     *  reading zero is AMBIGUOUS on its own: the ledger is zeroed both by a landing (the thing a
     *  scene wants to prove) and by every bail path (WalkerTickClimb releases and resets). A scene
     *  asserting "a real place cleared the counter" has to be able to say the takeover was still
     *  engaged when it read the zero, or a bail one tick earlier answers its question for it. */
    public boolean pillarEngaged() { return waterClimb.pillaring; }

    /** Read-only one-line probe of the follow state (step pointer, carrot node,
     *  best-effort/burst/churn/escalation counters) for test-scene diagnostics —
     *  the package-private fields are invisible to testmod scene classes and the
     *  log stream drops lines under load, so failures embed this in ctx.fail. */
    public String progressProbe() {
        BlockPos wp = path != null && step < path.size() ? path.get(step) : null;
        return (path == null ? "path=null" : "step=" + step + "/" + path.size()
                + " wp=" + (wp == null ? "-" : wp.getX() + "," + wp.getY() + "," + wp.getZ())
                + (seg.pathBestEffort ? " bestEffort" : ""))
                + " unstuck=" + unstuck.burstTicks + " churnEsc=" + churn.escapes
                + " escal=" + (escal.armed() ? "ON" : "off")
                + " noStep=" + stepProg.noStepProgressTicks
                + " digFloat=" + waterClimb.digFloatTicks + " gaveUp=" + waterClimb.pillarGaveUp;
    }

    /** Ultra-compact per-tick probe for scene kinematics trails: plan identity tag (the
     *  list's identityHashCode mod 1000, so a trail exposes PLAN SWAPS mid-window — the
     *  breach-time {@link #planProbe} is post-facto and fatal windows often straddle a
     *  repath), step pointer, and the current waypoint. */
    public String tickProbe() {
        if (path == null) return "p#---";
        BlockPos wp = step < path.size() ? path.get(step) : null;
        return "p#" + String.format("%03d", Math.floorMod(System.identityHashCode(path), 1000))
                + " s" + step + "/" + path.size()
                + (wp == null ? "" : " w" + wp.getX() + "," + wp.getY() + "," + wp.getZ())
                + " st" + stuckTicks + "/" + physStall.stallTicks;
    }

    /** Read-only compact dump of the CURRENT plan (nodes + move labels) for the same
     *  post-mortem channel as {@link #progressProbe}: "which edge flung the body" is
     *  unanswerable from the step pointer alone once the plan has been replaced, and
     *  the [walker] path log line is droppable under end-of-suite load. */
    public String planProbe() {
        if (path == null) return "plan=null";
        StringBuilder sb = new StringBuilder("plan=").append(step).append('/').append(path.size());
        for (int i = 0; i < path.size(); i++) {
            BlockPos n = path.get(i);
            sb.append(' ').append(i).append(':')
              .append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ());
            if (edges != null && i < edges.size() && edges.get(i) != null)
                sb.append('[').append(edges.get(i).move).append(']');
        }
        return sb.toString();
    }

    /**
     * {@link #planProbe}'s companion, read against the WORLD instead of against itself: for every
     * node of the CURRENT plan, the three cells that node's own geometry occupies — the node cell
     * ({@code 本格}, where the feet go), its support ({@code 支撑} = {@code below}) and its head
     * ({@code 头顶} = {@code above}) — each as {@code 实}/{@code 空} (the {@code blocksMotion}
     * predicate {@code ServerWorldView.isSolid} steers by) plus the block.
     *
     * <p><b>Why the coordinates must come from the plan and not from a scene's arithmetic.</b> A
     * scene that hand-derives cells from its own arena origin audits the cells its AUTHOR expects
     * the body to use. {@code wd.buriedOre}'s fixed staircase audit did exactly that and read seven
     * cells all at {@code z=cz}, while the node the body could not reach sat at {@code z=cz−1}: the
     * reading was complete, consistent, and blind to the one column that mattered. Nodes come from
     * {@link #path}, so the audit follows the plan wherever the planner actually put it.
     *
     * <p><b>The three outcomes this separates, for a body stalled before node {@code i}:</b>
     * <ol>
     *   <li>{@code 本格=实} — the plan wants the feet INSIDE a solid cell and the entering edge is
     *       not a break move ({@code [stepUp]}, not {@code [stairUpBreak]}): a planning-side account.
     *       The node is not enterable by any execution, so no executor fix can reach it.</li>
     *   <li>{@code 本格=空 且 支撑=实} — the node is legal and standable; the body simply fails to
     *       GET there: an execution-side account (for the trail this was written from, the one-cell
     *       lateral move in {@code z}). Look at the drive/jump gates, not at the planner.</li>
     *   <li>{@code 支撑=空} — nothing under the node's feet, so the body could not stand there even
     *       if it arrived: planning-side again, but a DIFFERENT mechanism from (1) — an edge whose
     *       floor the plan assumed, or whose floor a later break removed.</li>
     * </ol>
     * {@code 头顶=实} on a node the body must stand in is a fourth, weaker signal (the head cell is
     * occupied) and is printed rather than judged.
     *
     * <p>A plan that is absent must not read like a probe that never ran: {@code 节点=无(plan=null)}
     * and {@code 节点=无(plan为空)} are distinct strings, and both are distinct from the absence of
     * the fragment. Callers embed this in {@code ctx.fail} for the same reason as
     * {@link #progressProbe} — the log stream drops lines under end-of-suite load, {@code
     * results.jsonl} does not.
     *
     * <p>Read-only: takes a {@link BlockGetter}, touches no walker state, and must stay that way.
     * It also must be called BEFORE any scene cleanup — a cleanup that fills the arena with AIR
     * makes every node read {@code 空} and the audit becomes a very convincing lie.
     */
    public String planCellAudit(BlockGetter lvl) {
        if (lvl == null) return "节点=无(level=null)";
        if (path == null) return "节点=无(plan=null)";
        if (path.isEmpty()) return "节点=无(plan为空)";
        StringBuilder sb = new StringBuilder("节点数=").append(path.size()).append(" 指针=").append(step);
        for (int i = 0; i < path.size(); i++) {
            BlockPos n = path.get(i);
            sb.append(" 节点").append(i).append('=')
              .append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ());
            String mv = (edges != null && i < edges.size() && edges.get(i) != null)
                    ? String.valueOf(edges.get(i).move) : "无边";
            sb.append('[').append(mv).append(']');
            appendCell(sb, lvl, " 本格=", n);
            appendCell(sb, lvl, " 支撑=", n.below());
            appendCell(sb, lvl, " 头顶=", n.above());
        }
        return sb.toString();
    }

    private static void appendCell(StringBuilder sb, BlockGetter lvl, String label, BlockPos p) {
        // A post-mortem must not MAKE world. On a ServerLevel, getBlockState on an unloaded chunk
        // loads/generates it on the calling thread — a probe that hangs the gate run it was added
        // to diagnose. Plan nodes are normally inside the arena the search just walked, but this
        // is a public probe over an arbitrary plan, so 未加载 is a legal reading and a stall is not.
        if (lvl instanceof net.minecraft.world.level.LevelReader lr && !lr.hasChunkAt(p)) {
            sb.append(label).append("未加载");
            return;
        }
        BlockState st = lvl.getBlockState(p);
        sb.append(label).append(st.blocksMotion() ? "实" : "空").append(st.getBlock());
    }

    /**
     * Re-aim an in-progress pursuit at a quarry that moved, WITHOUT telling the walker this is
     * a new journey. {@link #setGoal} clears the futile-search governor, which is right for a
     * fresh goto and wrong for a chase: a caller that re-goals every time its quarry changes
     * block zeroes the counter faster than the counter can reach its cap, so the guard that
     * exists to stop a body burning a full A* per tick toward something it cannot reach never
     * fires. Measured on a chase of a descending goal: 240 ticks produced 240 full searches and
     * a counter that never passed 1. Everything else about the goal genuinely IS new, so this
     * is {@code setGoal} plus the six fields that describe "how this pursuit has been going"
     * rather than "which cell we want" — including the backoff, since taking away an armed
     * cooldown is the same leak one cap below.
     */
    public void retargetGoal(Goal g) {
        double bestDist = searchGov.futileBestDist;
        BlockPos bestFoot = searchGov.futileFoot;
        Goal yardstick = searchGov.futileGoal;
        BlockPos latchFoot = searchGov.futileLatchFoot;
        int searches = searchGov.futileSearches;
        int backoff = searchGov.searchBackoffTicks;
        setGoal(g);
        searchGov.futileBestDist = bestDist;
        searchGov.futileFoot = bestFoot;
        searchGov.futileGoal = yardstick;
        searchGov.futileLatchFoot = latchFoot;
        searchGov.futileSearches = searches;
        searchGov.searchBackoffTicks = backoff;
    }

    public void setGoal(Goal g) {
        this.goal = g;
        this.goalSnapChecked = false;
        this.goalSnapped = false;
        this.lastEndReason = null;
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.totalTicks = 0;
        this.actionTicks = 0;
        this.pillar.reset();
        this.waterClimb.reset();
        this.drownGuard.reset();
        this.stepUpBackoff.reset();
        this.dive.reset();
        this.driveLatch.reset();
        this.pillarRecover.reset();
        this.descending = false;
        this.seg.reset();
        this.goalSpin.resetForNewGoal();
        this.searchGov.reset();
        this.churn.resetForNewGoal();
        this.escal.disarm();
        BotConfig.pathfinderBoxedEscalate = false;          // never leak the steep-barrier escalation into the next goto
        this.stepProg.restartWindows();
        this.aimSmooth.reset();
        this.unstuck.resetForNewGoal();
        this.guardPlugCell = null;                          // plug arming is per-journey; a stale
        this.guardPlugFires = 0;                            // cell must not pre-arm the next goal
        this.replayMode = false;
        this.lastError = null;
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
        if (BotConfig.pathfinderSurfaceWaterNodes && world.isWater(t)
                && profile.capability().allowsOptIn(net.magicterra.worlddriver.bot.pathfinder.Capability.DIVE)) return;
        // walkerPillarReachGoalNoSnap: don't snap an elevated AIR goal DOWN when it's reachable
        // by PILLARING up. canStandAt(t) here fails only because t's floor is air — but pillaring
        // creates that floor, so the goal IS reachable. Snapping it to the highest currently-
        // standable cell makes the bot stop 1+ blocks short (live 2026-06-27 summitArena: goal
        // y233 → snapped to standable y232, bot pillars 2 then arrives at y232, never pillars the
        // last block; "上坡跳不上高空目标"). Skip the snap so A* keeps the real goal and finds the
        // pillarUp path. Guard against a truly FLOATING void goal (no base to pillar from): require
        // the goal cell + head to be passable AND a solid base within a few blocks below.
        if (BotConfig.walkerPillarReachGoalNoSnap && BotConfig.allowPlace
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
            if (BotConfig.walkerDebug)
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
    public void forceRepath() {
        this.path = null;
        this.edges = null;
        this.step = 0;
        this.ticksSinceRepath = 0;
        this.stuckTicks = 0;
        this.actionTicks = 0;
        this.pillar.reset();
        this.seg.reset();
        this.stepProg.restartWindows();
        this.aimSmooth.reset();
        this.searchGov.quickCooldown = 0;
        // Clear the water-climb-out / descent state too (setGoal resets these): a
        // resume mid water-climb otherwise carries a stale window base-Y / armed
        // stall, so the next tick either fires a spurious foothold-place takeover
        // (stall already past threshold) or suppresses a legitimate stall.
        this.waterClimb.reset();
        this.drownGuard.reset();
        this.stepUpBackoff.reset();
        this.dive.reset();
        this.driveLatch.reset();
        this.pillarRecover.reset();
        this.descending = false;
        this.escal.disarm();
        BotConfig.pathfinderBoxedEscalate = false;          // never leak the steep-barrier escalation across a forced repath
    }

    /** Replay a fixed archived plan. Caller has already teleported the bot to the plan
     *  start and restored the block envelope. Disables A* entirely — the plan executes
     *  with real physics; a wedge is recorded, not re-planned. */
    public void beginReplay(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, BlockPos startFoot) {
        setGoal(endGoal);
        this.replayMode = true;
        adoptPath(new PathFinder.Result(plan, planEdges, true, 0, 0L, 0.0), world, startFoot);
    }

    /**
     * TEST SEAM — adopt a hand-built plan with the LIVE recovery/repath machinery left ON
     * (unlike {@link #beginReplay}, which sets {@code replayMode} and disables A*), then
     * point {@code step} at an arbitrary mid-plan node. The harness uses this to force the
     * exact step-pointer-mismatch STATE the live wedge lands in — bot grounded N blocks off a
     * descend node — INDEPENDENT of how the bot drifted there live (which a momentum overshoot
     * on a synthetic 2-D arena could not reliably reproduce). Because {@code replayMode} stays
     * false, the real {@code fellOffPath}/{@code safetyRepath}/blacklist recovery runs, giving
     * the clean A/B the live wedge couldn't. Pass {@code foot==null} so {@link #adoptPath} does
     * NOT fast-forward/re-anchor the step it just set. Not wired to any transport; test-only.
     */
    public void beginScriptedFollow(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, Goal endGoal, int startStep) {
        setGoal(endGoal);
        this.replayMode = false;
        adoptPath(new PathFinder.Result(plan, planEdges, false, 0, 0L, 0.0), world, null);
        this.step = Math.max(0, Math.min(startStep, (path == null ? 1 : path.size())));
        this.stepProg.noProgressStep = -1;          // force a fresh noStepProgressTicks baseline at the injected step
        this.stepProg.noStepProgressTicks = 0;
    }

    /**
     * How many NODES the current path holds, <b>including the one the body starts on</b>.
     *
     * <p><b>Nodes, not steps.</b> {@link #edges} is index-aligned with {@link #path} — {@code edges
     * .get(i)} is the edge that ENTERS {@code path.get(i)} — so node 0 is where the walk began and
     * carries no traversal of its own. A path of N nodes is <b>N−1 moves</b>, and reading it as「N 格」
     * overstates the reach of every plan by one. It is also NOT a distance: a single {@code fall7}
     * or a smoothed diagonal covers several blocks in one node.
     *
     * <p><b>The last node is the goal only when the search reached it.</b>
     * {@code seg.pathBestEffort = !res.goalReached()} — a best-effort partial ends at the closest
     * node A* could get to, which for a body on a small island is its own edge. So「路的末节点」and
     *「要去的地方」are different questions and {@link #planTally()} answers the first one.
     */
    public int pathLen() { return path == null ? 0 : path.size(); }
    public int pathStep() { return step; }

    /**
     * What the world looks like UNDER the plan, sampled along it — the reading that separates a
     * broken support check from a world that was never there to check.
     *
     * <p>Both produce the same plan. A {@code stepUp}/{@code walk} chain laid across empty space is
     * what you get when the support predicate forgot to ask, AND what you get when the predicate
     * asked correctly and an ungenerated chunk answered「passable」— and the two want opposite fixes
     * (fix the predicate vs. load before planning / treat unknown as impassable). {@code isKnown} is
     * the only thing that tells them apart, so it is sampled beside every cell.
     *
     * <p>Reported in {@link WorldView}'s own vocabulary rather than as block names, deliberately: the
     * planner never sees a {@code BlockState}, so a row naming one would describe a world the
     * decision under investigation did not consult.
     */
    public String planTerrain(WorldView w) {
        if (path == null || path.isEmpty() || w == null) return "无路径";
        StringBuilder sb = new StringBuilder();
        int n = path.size();
        int stride = Math.max(1, (n - 1) / 4);
        for (int i = 0; i < n; i += stride) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(sample(w, i));
        }
        if ((n - 1) % stride != 0) sb.append(' ').append(sample(w, n - 1));
        return sb.toString();
    }

    private String sample(WorldView w, int i) {
        BlockPos c = path.get(i);
        BlockPos under = c.below();
        return "[" + i + "]" + c.toShortString()
                + " 本格" + (w.isSolid(c) ? "实心" : w.isWater(c) ? "水" : "空")
                + " 下方" + (w.isSolid(under) ? "实心" : w.isWater(under) ? "水" : "空")
                + " known=" + w.isKnown(c) + "/" + w.isKnown(under);
    }

    /**
     * The shape of the whole current plan, for a caller that can only otherwise see the ONE move
     * entering the current node.
     *
     * <p>Reports the last node, whether the search actually reached the goal, and a count of every
     * move name in the path. That last part is what separates「the planner routed a bridge and the
     * body never executed it」from「the planner never planned one」— two diagnoses that
     * {@link #pathMove()} alone reports identically whenever the step pointer happens to sit on a
     * walk.
     *
     * <p><b>A move count is not a block count, and the gap is large.</b> {@link #adoptPath} runs
     * {@code PathSmoothing.stringPull} before the path is ever driven, and that collapses a whole run
     * of flat same-Y {@code walk}/{@code diag} edges into ONE edge spanning the straight line between
     * its endpoints; only vertical, parkour, climb, break and place edges survive as hard waypoints.
     * So {@code walk×7} may be seven cells or seventy. Measured 2026-08-17: {@code {walk=7,
     * parkour3=1, stepUp=8}} — 18 cells if every move were one cell — spanned x 98→51, forty-seven
     * blocks. Nothing was inconsistent; the eight {@code stepUp} and one {@code parkour3} are
     * per-cell waypoints and the seven smoothed walks carried the remaining thirty-six.
     *
     * <p>That is the third reading in this family to be mistaken for a distance, after
     * {@link #pathLen()} (nodes, not blocks) and {@link #pathMove()} (one edge, not the plan). The
     * rule they share: <b>ask what a path reading counts before dividing by it.</b>
     */
    public String planTally() {
        return tallyOf(path, edges) + " 到得了目标=" + !seg.pathBestEffort;
    }

    private static String tallyOf(List<BlockPos> nodes, List<Move.Edge> es) {
        if (nodes == null || nodes.isEmpty()) return "无路径";
        java.util.Map<String, Integer> byMove = new java.util.LinkedHashMap<>();
        if (es != null)
            for (Move.Edge e : es)
                if (e != null && e.move != null) byMove.merge(e.move, 1, Integer::sum);
        return "节点=" + nodes.size() + "（即 " + (nodes.size() - 1) + " 步）"
                + " 末节点=" + nodes.get(nodes.size() - 1).toShortString()
                + " 各 move " + (byMove.isEmpty() ? "{}" : byMove.toString());
    }

    /**
     * The plan as A* returned it, before {@code stringPull} — the pre-image {@link #planTally()}
     * describes the optimised version of.
     *
     * <p><b>Two different faults produce the same smoothed path and need opposite fixes.</b> Either
     * A* itself routed an edge across ground that is not there — then the defect is in that move's
     * feasibility check — or A* laid a correct cell-by-cell run and the string-pull joined two of its
     * nodes with a straight line nobody re-verified, which would make the optimiser the thing that
     * broke an invariant its own input satisfied. Without this row the smoothed path is the only
     * evidence and it cannot tell them apart.
     */
    public String rawPlanTally() { return rawPlan == null ? "无" : rawPlan; }

    /** What the first parkour leap of this walk was launched with. {@code Parkour3} is costed as the
     *  <b>sprint-jump maximum</b>, so {@code sprinting=false} at takeoff means the executor attempted
     *  a leap the planner priced for a run-up it was not allowed to take. Deliberately not read from
     *  {@code onGround}: this body's is wrong in both directions. */
    public String parkourTakeoff() { return parkourTakeoff == null ? "无" : parkourTakeoff; }

    /** Latch the FIRST parkour takeoff of this walk. The sprint decision is taken a tick before the
     *  jump, so {@code isSprinting()} here is the state the leap actually launches with. Only the
     *  first is kept: a body that has already fallen keeps producing these.
     *
     *  <p><b>The five samples are not guaranteed to be five CONSECUTIVE ticks.</b> This call sits in
     *  the drive tail, which a dozen branches (dig, pillar, escape, step-up) return before reaching,
     *  so "t+0..t+4" means "the first five ticks the drive got this far". A run of five with
     *  {@code 站住=false} therefore does NOT by itself distinguish a body that was standing and
     *  refused the jump from a body that was already falling when the parkour edge became current —
     *  the exact y and the sole area are what separate them, which is why both are printed. */
    void noteParkourTakeoff(WorldView world, Avatar a, net.minecraft.world.entity.player.Player p, boolean jump, BlockPos foot) {
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
                // Ticks since the body last actually EMITTED an impulse (not since one was asked
                // for). This is what separates a body that jumped ITSELF off the platform — the
                // parkour edge became current mid-arc — from one that simply walked off the lip:
                // the first has a small number here, the second has 无.
                .append(" 距上次起跳=").append(lastJump < 0 ? "无" : String.valueOf(now - lastJump))
                .append(" jump=").append(jump)
                .append(" sprinting=").append(p.isSprinting())
                .append(String.format(java.util.Locale.ROOT, " h=%.4f", h))
                // ASK THE ATTRIBUTE, do not extrapolate the curve. Sprint is ×1.3 through
                // Attributes.MOVEMENT_SPEED (verified in 1.21.1: SPEED_MODIFIER_SPRINTING = 0.3,
                // ADD_MULTIPLIED_TOTAL), so 0.13 here means the channel works and 0.1 means the
                // modifier never took. The expected steady-state cap is printed beside it so nobody
                // has to redo the arithmetic.
                .append(String.format(java.util.Locale.ROOT, " 属性=%.4f(稳态上限应为 %.4f)",
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) * 2.1585))
                // THE QUANTITY THE JUMP USED TO BRANCH ON, kept as an input rather than as truth:
                // onGround IS vanilla's verticalCollisionBelow (Entity.move assigns one from the
                // other in a single statement), so it describes the previous MOVE and is wrong in
                // both directions about where the body is.
                .append(" onGround=").append(p.onGround())
                // WHAT THE GATE ASKS NOW — see ServerPlayerAvatar.step(). Printed beside onGround
                // so a run says which of the two was lying, and printed with the EXACT y because
                // that is the only thing that tells a standing body from a falling one when the
                // block coordinate below is the same for both (a body falling from y=49.9 spends
                // three ticks inside block y=49, exactly like a body resting on y=49.0).
                .append(String.format(java.util.Locale.ROOT, " 脚底实心=%.4f y=%.4f",
                        WalkerGeometry.soleOnSolid(world, p), p.getY()))
                .append(" 身体=").append(foot.toShortString());
        parkourSamples++;
        parkourTakeoff = parkourTakeoffBuf.toString();
    }

    private int parkourSamples;
    private StringBuilder parkourTakeoffBuf;

    /** String-pull the search result, keeping a tally of what it looked like BEFORE — see
     *  {@link #rawPlanTally()}. The latch lives here rather than at the call site so
     *  {@code adoptPath} does not grow: it is already grandfathered at its line budget. */
    private SmoothResult smoothAndRemember(WorldView world, PathFinder.Result res,
                                           List<net.magicterra.worlddriver.bot.pathfinder.CostModifier> bias) {
        rawPlan = tallyOf(res.path(), res.edges());
        return stringPull(world, res.path(), res.edges(), bias);
    }

    /**
     * Every cell the plan's straight lines actually cross, for the first few segments — the reading
     * that node samples structurally cannot give.
     *
     * <p>{@link #planTerrain} samples NODES, and after smoothing consecutive nodes can be nine blocks
     * apart. Measured 2026-08-17: node [0] {@code 100,49,0} and node [4] {@code 91,50,0} both sat on
     * solid ground with their chunks loaded, and the body began falling at {@code 96,46,0} — inside
     * the span between them, where nothing had been sampled. A reading that only looks where the plan
     * stops cannot see what the plan crosses.
     *
     * <p>Reports each segment's cell count and lists the cells whose support is missing, so a healthy
     * segment is still visible (「缺口无」) rather than merely absent — an all-clear that is only ever
     * printed by silence cannot be told from a check that never ran.
     *
     * <h2>Which path this samples, and ⚠️ what a「缺口」does and does not mean</h2>
     *
     * <b>The CURRENT adopted path</b> — {@link #path}, the same list {@link #planTally()} and
     * {@link #planTerrain} read, in the same statement. There is no second copy: all three are called
     * from one string concatenation in {@code IntentProcess}, so「发出全清」and「采纳有缺口」are
     * statements about the same nodes and the contradiction between them is real rather than an
     * artefact of comparing two plans.
     *
     * <p><b>But an unsupported cell is only a defect under an edge that walks.</b> This method判s
     * support cell by cell and originally said nothing about the MOVE crossing them — and a
     * {@code parkour3} or a {@code fall} is supposed to cross thin air; that is what it is for. A row
     * reading「缺口2格」under a jump edge names a fault that does not exist, which is exactly the
     * shape of evidence this rung has lost rounds to. The move entering each segment is therefore
     * printed beside it, and the gap count must never be read without it.
     */
    public String planSpans(WorldView w, int segments) {
        if (path == null || path.size() < 2 || w == null) return "无路径";
        StringBuilder sb = new StringBuilder();
        int last = Math.min(segments, path.size() - 1);
        for (int i = 0; i < last; i++) {
            BlockPos a = path.get(i), b = path.get(i + 1);
            int steps = Math.max(Math.abs(b.getX() - a.getX()), Math.abs(b.getZ() - a.getZ()));
            StringBuilder holes = new StringBuilder();
            int nHoles = 0;
            for (int s = 0; s <= steps; s++) {
                double t = steps == 0 ? 0 : (double) s / steps;
                BlockPos c = new BlockPos(
                        (int) Math.round(a.getX() + (b.getX() - a.getX()) * t),
                        (int) Math.round(a.getY() + (b.getY() - a.getY()) * t),
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * t));
                if (w.isSolid(c.below())) continue;
                nHoles++;
                if (nHoles <= 6) holes.append(nHoles == 1 ? "" : ",").append(c.toShortString())
                        .append(w.isKnown(c.below()) ? "" : "(未加载)");
            }
            Move.Edge into = edgeAt(i + 1);
            String move = into == null || into.move == null ? "?" : into.move;
            sb.append(i == 0 ? "" : " ").append("段").append(i).append(' ')
              .append(a.toShortString()).append("→").append(b.toShortString())
              .append(" move=").append(move)
              .append(" 共").append(steps + 1).append("格 ")
              .append(nHoles == 0 ? "缺口无"
                      : "缺口" + nHoles + "格[" + holes + "]"
                        // A jump is SUPPOSED to cross thin air. Saying so here, beside the count, is
                        // what keeps the row from naming a fault that does not exist.
                        + (move.startsWith("parkour") || move.startsWith("fall")
                                ? "（这是跳跃边，跨空是它的用途，不是缺陷）" : ""));
        }
        return sb.toString();
    }
    /** Node the step-pointer currently targets (null when no path / consumed). Test seam. */
    public BlockPos pathNode() { return (path != null && step >= 0 && step < path.size()) ? path.get(step) : null; }

    /**
     * Name of the move that ENTERS {@link #pathNode()} — {@code walk}, {@code stepDown},
     * {@code fall7}, {@code diagDown}… (null when there is no such edge).
     *
     * <p>The node alone cannot say whether a drop was planned. Every {@code Fall} edge carries its
     * height in its own name, so this is the one reading that separates "the plan was to drop N"
     * from "the body left a plan that never contained a drop at all" — and those two want opposite
     * fixes (a planner budget vs. an executor that overshot).
     */
    public String pathMove() {
        Move.Edge e = edgeAt(step);
        return e == null ? null : e.move;
    }

    /** TEST SEAM — run {@link #adoptPath} with an explicit {@code foot} so a harness can exercise the
     *  segment anchor-gate (the {@code mis-anchored segment} reject vs the deep-water-float open-water
     *  bee-line exemption) deterministically, INDEPENDENT of the full continuation/repath machinery the
     *  headless server-sim doesn't reproduce for this live water dead-stop. @return adoptPath's verdict:
     *  true = ACCEPTED (segment adopted, the bot will drive it), false = REJECTED as mis-anchored. */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges, BlockPos foot) {
        return adoptForTest(world, plan, planEdges, foot, false);
    }

    /** {@link #adoptForTest} for a scene that goes on to TICK the walker. The 4-arg form declares the
     *  plan BEST-EFFORT, and a best-effort segment is one the walker is entitled to replace: it kicks
     *  off a continuation search, adopts the result, and the synthetic edges are gone. That is how
     *  {@code wd.surfacePillarPointerNeedsItsSupport} lost its subject on tick ONE — its terminal read
     *  {@code path-consumed}, a class {@link #classifyArrival} can only return once
     *  {@code seg.pathBestEffort} is false, and {@link #adoptPath} is its only writer. A synthetic plan
     *  whose last node IS the goal has to say so, or it does not survive its own first tick. */
    public boolean adoptForTest(WorldView world, List<BlockPos> plan, List<Move.Edge> planEdges,
                                BlockPos foot, boolean goalReached) {
        return adoptPath(new PathFinder.Result(plan, planEdges, goalReached, 0, 0L, 0.0), world, foot);
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
     * be attributed to this tick — and {@code 未标} when it did not.
     *
     * <p>The drive's label chain now ends in {@code 其它} rather than a bare {@code "swim"}. That is
     * not decoration: a chain whose last arm is a real branch name labels every unmatched case as
     * that branch, so it can never report that the labels have fallen behind the expression they
     * describe. If {@code 支=其它} is ever printed, the chain is missing a term — fix the chain before
     * believing any label it produced.
     *
     * <p>One line per EVENT (entry to a run of held ticks), capped at {@link #JUMP_SRC_EVENTS}: the
     * walker holds jump for dozens of consecutive ticks and a per-tick line would be a hose. The
     * stack walk happens only on the lines that are actually emitted.
     *
     * <p><b>"Entry to a run" is measured against the PREVIOUS CALL, and must never be measured
     * against the clock.</b> It used to be {@code getGameTime() - jumpAskTick > 1}, and that is
     * blind for a whole family of scenes: anything that pumps the body in a tight in-body loop
     * ({@code wd.buriedOre} runs 800 {@code ServerAvatarManager.tickAll()} iterations inside ONE
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
     * <p>{@code 序=} is the event ordinal within the cap; when {@code t=} is frozen (the one-tick
     * family) it is the only thing that orders the lines. {@code 序=6/6} says the cap was REACHED,
     * which is not the same claim as "more happened" — the follow-on {@code 序=7+/6 已达上限} line
     * is the one that says an event was actually swallowed, and its ABSENCE after a {@code 序=6/6}
     * means the body really did stop asking.
     */
    void avatarJump(Avatar a, boolean v) {
        boolean newEvent = v && !lastJumpAsk;
        lastJumpAsk = v;
        if (newEvent) noteJumpSource(a);
        a.commandJump(v);
    }

    private void noteJumpSource(Avatar a) {
        net.minecraft.world.entity.player.Player p = a.player();
        if (p == null) return;
        if (jumpSrcEvents >= JUMP_SRC_EVENTS) {
            // A silent cap and a body that genuinely jumped exactly JUMP_SRC_EVENTS times print
            // IDENTICALLY — "序=6/6" is the last line in both worlds. Say it once, at the first
            // event actually suppressed, so the difference is on the page. One-shot on purpose:
            // the whole reason the cap exists is that a hose of these lines is unreadable.
            if (!jumpSrcCapped) {
                jumpSrcCapped = true;
                LOG.info("[walker] 起跳来源: 序={}+/{} 已达上限，后续事件未记录（此行只印一次）",
                        JUMP_SRC_EVENTS + 1, JUMP_SRC_EVENTS);
            }
            return;
        }
        jumpSrcEvents++;
        long now = p.level().getGameTime();
        BlockPos foot = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        BlockPos wp = path != null && step >= 0 && step < path.size() ? path.get(step) : null;
        String site = StackWalker.getInstance().walk(s -> s.skip(2)
                .map(f -> f.getFileName() + ":" + f.getLineNumber()).findFirst().orElse("?"));
        // The water pair is here because a `swimColumn` tag on dry obsidian has three readings and
        // the tag alone cannot separate them: the BODY's own flag (p.isInWater(), what the branch
        // actually tests) and the WORLD's block at the foot are printed side by side, so a stale
        // flag, a genuinely wet cell, and a tag that disagrees with its own precondition are three
        // distinct rows instead of one ambiguous one.
        LOG.info("[walker] 起跳来源: 序={}/{} t={} 支={} 处={} 身体={} 精确=({}) 路点={} wp.y-foot.y={} 水={} 没顶={} 脚格={} 脚上={}",
                jumpSrcEvents, JUMP_SRC_EVENTS,
                now, jumpTag == null ? "未标" : jumpTag, site, foot.toShortString(),
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                wp == null ? "无" : wp.toShortString(), wp == null ? "?" : String.valueOf(wp.getY() - foot.getY()),
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
     * One line per STEP ADVANCE — which gate consumed the node, and whether the body was standing
     * on anything when it did.
     *
     * <p><b>The pair of readings that separates two step-advance stories.</b> A pointer that has
     * moved past a node is consistent with the body having reached it and with the body having been
     * mid-jump over it, and the vertical distance alone cannot tell them apart: the {@code passed}
     * gate's {@code |nx.y − p.y| < 1.2} reachability bar is evaluated against the body's y AT THIS
     * INSTANT, and a 0.42 impulse lifts a grounded body ~1.25 blocks, so a node two above the
     * FLOOR reads as within reach at the top of a jump the body cannot stay at. {@code onGround},
     * {@code 脚底实心} and {@code 落速} therefore ride on the same line as the two |Δy| terms — a
     * distance without a footing is the same row in both worlds, and the whole point of the line is
     * that they be different rows.
     *
     * <p>{@code 脚底实心} is {@link WalkerGeometry#soleOnSolid}, the predicate the ground jump gate
     * itself steers by, deliberately rather than a second opinion about what standing means: an
     * executor and a reading that answer that question differently is how a diagnosis comes to
     * describe a body that does not exist.
     *
     * <p><b>{@code 因=} is the branch that FIRED, not a reconstruction.</b> The flags are passed in
     * from the decision site and joined, so two simultaneously-true gates print as {@code
     * within+passed} instead of the caller having to pick one, and an advance matching NONE of the
     * named flags prints {@code 其它} — a chain whose last arm is a real gate name would label every
     * unmatched case as that gate and could never report that the labels had fallen behind the
     * expression. {@code cur2}/{@code nd2}/{@code overshot} come from the same site for the same
     * reason; {@code nd2=NaN} is the honest reading for a tick where {@code within} fired and the
     * {@code passed} arithmetic never ran.
     *
     * <p>Capped like {@link #noteJumpSource} and for the same reason, with the same one-shot
     * {@code 已达上限} line — and, as there, the gate is an ORDINAL and never the clock:
     * {@code wd.buriedOre} pumps its whole body inside ONE server tick, so a game-time latch would
     * print the first line and go blind for the rest of the run. NOT gated on
     * {@code BotConfig.walkerDebug}, which that scene switches off.
     */
    void noteStepAdvance(WorldView world, Player p, BlockPos foot, BlockPos w, BlockPos nx,
                         String cause, double cur2, double nd2, boolean overshot) {
        if (seenLegEpoch != legEpoch) {                 // a new leg: fresh budget, see #newLeg
            seenLegEpoch = legEpoch;
            stepAdvEvents = 0;
            stepAdvCapped = false;
        }
        if (stepAdvEvents >= STEP_ADV_EVENTS) {
            if (!stepAdvCapped) {
                stepAdvCapped = true;
                LOG.info("[walker] 步进: 序={}+/{} 已达上限，后续事件未记录（此行只印一次）",
                        STEP_ADV_EVENTS + 1, STEP_ADV_EVENTS);
            }
            return;
        }
        stepAdvEvents++;
        stepAdvancesLogged++;
        LOG.info("[walker] 步进: 序={}/{} 因={} 旧步={} 新步={} w={} nx={} 身体={} 精确=({}) "
                        + "cur2={} nd2={} overshot={} |w.y-p.y|={} |nx.y-p.y|={} onGround={} 脚底实心={} 落速={}",
                stepAdvEvents, STEP_ADV_EVENTS, cause, step, step + 1,
                w == null ? "无" : w.toShortString(),
                nx == null ? "无(末节点)" : nx.toShortString(),
                foot == null ? "无" : foot.toShortString(),
                String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                String.format(Locale.ROOT, "%.3f", cur2), String.format(Locale.ROOT, "%.3f", nd2), overshot,
                w == null ? "?" : String.format(Locale.ROOT, "%.3f", Math.abs(w.getY() - p.getY())),
                nx == null ? "?" : String.format(Locale.ROOT, "%.3f", Math.abs(nx.getY() - p.getY())),
                p.onGround(),
                world == null ? "?" : String.format(Locale.ROOT, "%.4f", soleOnSolid(world, p)),
                String.format(Locale.ROOT, "%.4f", p.getDeltaMovement().y));
    }

    /**
     * Step-advance lines emitted per LEG before the latch goes quiet.
     *
     * <p>Sixty-four, and the number is measured rather than chosen. It was 8 <b>per walker</b>, and
     * a walker outlives a whole rung: on the 2026-08-20 ladder the budget was spent in the first
     * seconds of a 5 209-tick crossing, so the four wedged hops that are the entire question —
     * 900 ticks each, 61 to 76 walk edges apiece — produced not one advance line between them. The
     * reading those hops need is whether the pointer advanced through nodes the body never walked,
     * and that is exactly what an exhausted latch cannot say. 64 covers the 61 edges the worst
     * measured hop walked, so a wedge can be read end to end instead of only its opening.
     *
     * <p><b>Still bounded, and per leg rather than unbounded</b>: 24 hops x 64 lines of ~300 chars
     * is about 460 KB for a whole crossing, which is a log a human opens. An uncapped advance log
     * over a wedged hop is how a 40-minute run becomes an unreadable one.
     */
    private static final int STEP_ADV_EVENTS = 64;
    private int stepAdvEvents;
    /** The leg this walker last refreshed its advance budget for — see {@link #newLeg}. */
    private int seenLegEpoch = -1;
    /** One-shot latch for the "the cap swallowed an advance" line — see {@link #noteStepAdvance}. */
    private boolean stepAdvCapped;

    /**
     * Which leg the per-leg log budgets belong to, and the one call that starts a new one.
     *
     * <h2>One definition of「leg」, not a second one</h2>
     *
     * A walker outlives every leg that uses it, so a per-walker budget is spent by whichever leg
     * happens to run first. What the instruments mean by a leg is already defined — {@link
     * net.magicterra.worlddriver.bot.stagewright.journey.JourneyFlight JourneyFlight} is constructed
     * once per leg and takes its {@code guardForcedRepaths} delta from exactly that moment — so this
     * is bumped from the same constructor rather than being given a notion of its own. Two
     * definitions of one word is how the pin/streak confusion started, and it is not repeated here.
     *
     * <h2>Behaviour must not read this</h2>
     *
     * It gates a LOG BUDGET and nothing else, which is why a static is safe where {@link
     * #lastTickTrace}'s note says one would not be: every walker in the JVM refreshing its logging
     * allowance on a new leg is the intended effect, and no decision the bot makes can observe it.
     */
    public static volatile int legEpoch;

    /** Start a new leg: every walker's per-leg log budget refreshes on its next line. */
    public static void newLeg() { legEpoch++; }

    /**
     * Advance lines actually emitted, so a leg can report whether its own log is COMPLETE.
     *
     * <p>The budget above is worth nothing if a reader cannot tell「this hop advanced 61 times and
     * all 61 are here」from「this hop advanced 300 times and you are looking at the first 64」. The
     * landing allowance taught the same lesson one commit ago from the other side: an instrument
     * that costs nothing and an instrument that never ran are indistinguishable unless one of them
     * says which. {@code JourneyFlight} prints this as a per-leg delta against
     * {@link #STEP_ADV_EVENTS}.
     */
    public static volatile int stepAdvancesLogged;

    /** The per-leg advance budget, for the instrument that reports how much of it a leg spent. An
     *  accessor rather than a copied literal: a row that assumes 64 stops being a report about this
     *  budget the day the budget changes. */
    public static int stepAdvanceBudget() { return STEP_ADV_EVENTS; }
    static void avatarSneak(Avatar a, boolean v) { a.commandSneak(v); }
    /** Raw forward (keyUp equivalent) for the special branches that drive the impulse
     *  themselves (the main walk path uses commandMove). v=false also zeroes strafe. */
    static void avatarForward(Avatar a, boolean v) { a.commandForward(v ? 1f : 0f); }
    /** THE ONE DOOR for a walker dig — see {@link WalkerDig#avatarDig}. Aim at the RETURNED cell. */
    static BlockPos avatarDig(Walker wk, Avatar a, BlockPos cell) { return WalkerDig.avatarDig(wk, a, cell, false); }
    static BlockPos avatarDig(Walker wk, Avatar a, BlockPos cell, boolean selectTool) { return WalkerDig.avatarDig(wk, a, cell, selectTool); }
    /** {@link #avatarDig} for a dig that must not queue: suffocation. Takes the slot, then digs. */
    static BlockPos avatarDigPreempt(Walker wk, Avatar a, BlockPos cell, boolean selectTool) { return WalkerDig.avatarDigPreempt(wk, a, cell, selectTool); }

    /** Client bridge: existing callers pass {@link Minecraft}; wrap it in a
     *  {@link ClientPlayerAvatar} (1:1 passthrough). The decoupled core is
     *  {@link #tick(Avatar, WorldView)}, which the server path calls directly. */
    public Step tick(Minecraft mc, WorldView world) {
        return tick(new ClientPlayerAvatar(mc), world);
    }

    public Step tick(Avatar a, WorldView world) {
        body = a.player();
        // Single-exit wrapper: tickInner() has dozens of early returns (pillar, dig, escape,
        // stepUp...), so a safety invariant appended to its tail only covers SOME ticks — the
        // gap #53 death strode over a well mouth from a branch that never reached it. Run the
        // stride floor-guard here, after EVERY decision path, before the avatar integrates.
        Step s = tickInner(a, world);
        WalkerTickDrive.settleDigKey(this, a);   // a walker-held attack key outlives its dig by DIG_KEY_RELEASE_TICKS only
        Player tp = a.player();
        lastTickTrace = "step=" + s + " 跳标=" + (jumpTag == null ? "未标" : jumpTag)
                + (tp == null ? "" : " 身体=" + String.format(java.util.Locale.ROOT, "%.2f,%.2f,%.2f",
                        tp.getX(), tp.getY(), tp.getZ())
                    + String.format(java.util.Locale.ROOT, " 速度h=%.3f",
                        tp.getDeltaMovement().horizontalDistance())
                    + " 脚底=" + String.format(java.util.Locale.ROOT, "%.3f",
                        WalkerGeometry.soleOnSolid(world, tp)))
                + " 节点=" + (path == null || step < 0 || step >= path.size()
                        ? "无" : path.get(step).toShortString());
        if (tp != null && WalkerGeometry.soleOnSolid(world, tp) > 0.0) lastSupportedTrace = lastTickTrace;
        boolean fired = strideFloorGuard(a, world);
        boolean footing = footingGuard(a, world);
        // Pin HYSTERESIS: the guard's fire predicate needs translation (h ≥ 0.03), so the
        // pin's own deceleration un-fires it the next tick — pin/release alternation. On a
        // spinning-drive arc at a lip that alternation is fatal twice over: the release
        // ticks let the creep resume (bridge stop-family: body slid off between pins,
        // breach@t=94), and the streak reset below kept the ≥30 forced repath from ever
        // firing. Hold the pin for a short tail after the last fire; held ticks count
        // toward the streak. Parkour ticks stay exempt (a deliberate leap must launch).
        if (fired) guardHoldTicks = GUARD_PIN_HOLD;
        else if (guardHoldTicks > 0) {
            Player hp = a.player();
            BlockPos fc = hp == null ? null
                    : BlockPos.containing(hp.getX(), hp.getY() + 0.05, hp.getZ());
            // Planned-descent release (same exemption the fire predicate has): when the
            // current waypoint is BELOW the foot the walker is deliberately descending,
            // and steep descents stand on knife edges BY DESIGN — holding the pin there
            // froze the whole descent at the crest (r10 regression: descentYaw never left
            // the top; caught four rounds late behind bridge-filtered greps).
            boolean plannedDescent = fc != null && path != null && step < path.size()
                    && path.get(step).getY() < fc.getY();
            if (guardParkourTick || plannedDescent || hp == null) guardHoldTicks = 0;
            else {
                // While the body OVERHANGS (grounded only by the epsilon face-contact of
                // a neighbouring block — sneak lets it balance on such a knife edge —
                // with a passable column under its own foot cell) the countdown pauses:
                // releasing there drops the body straight down (stop-family r9: pin
                // walked the body back toward the deck at +0.04/tick but the tail
                // expired two blocks short). Gated on an ACTIVE hold so routine diagonal
                // corner-crossing transients never stutter-sneak.
                boolean overhang = hp.onGround() && !hp.isInWater()
                        && world.isPassable(fc.below()) && !world.isWater(fc.below());
                if (!overhang) guardHoldTicks--;
                avatarSneak(a, true);
                a.commandJump(false);
                hp.setSprinting(false);
            }
        } else guardHoldTicks = 0;
        if (!footing) footingPinned = false;
        boolean pinned = fired || footing || guardHoldTicks > 0;
        // Self-releasing latch: the pin must last exactly as long as the hazard (plus the
        // hold tail). A sneak that nothing releases turns a one-stride save into a
        // permanent stall (ridge descent pinned at maxNoProgress=205 in the first
        // full-suite run).
        if (guardSneakLatch && !pinned) avatarSneak(a, false);
        guardSneakLatch = pinned;
        if (fired) {
            // A pin is a deliberate hold, not a stall: revert this tick's stuck accounting so
            // anti-stuck recovery bursts don't shove the body over the very lip the pin holds it
            // from (live 2026-07-13 death: 8 clean pins at the cliff, stuckT climbing 2→4, then a
            // recovery nudge pushed the 2.5HP body over a 6-block drop — the guard's save undone
            // by the machinery around it).
            if (stuckTicks > 0) stuckTicks--;
            // Sustained pinning MAY be a planning fact — the current route leads over a lethal lip
            // — rather than an actuation stall, and when it is, dropping the path lets safetyRepath
            // solve a fresh route from here instead of letting the drive fight the pin indefinitely
            // (descentDrift livelock: 567 pins in one run). It is not always that: a rim walk pins
            // every few ticks for tens of blocks while consuming a perfectly good plan. The callee
            // decides which one this is — do NOT read this call as an unconditional discard.
            forcedRepathIfPinnedTooLong(a);
        } else if (pinned) {
            // Held ticks keep the streak alive AND advancing: the pin/release alternation
            // used to reset it every other tick, so a livelocked lip approach never reached
            // the forced repath (stop-family creep: 10+ fires, streak never past 1). The
            // repath drops the PLAN only — the hold itself must survive it (r9: clearing
            // the hold here released the sneak mid-overhang and dropped the body).
            forcedRepathIfPinnedTooLong(a);
        } else { guardPinStreak = 0; guardStreakCells = 0; guardStreakCell = null; }
        return s;
    }

    /**
     * Pin a body that is grounded on almost nothing, beside a drop that would kill it.
     *
     * <h2>Why here and not in the drive</h2>
     *
     * The lethal-edge gate in {@code WalkerTickDrive} is the natural home and it cannot do this job,
     * for two measured reasons. It probes FORWARD — the cell 0.6 blocks toward the waypoint — so a
     * body that has drifted off its floor sideways, or that is being steered at a node behind it,
     * reads perfectly clean: on the tick before an eleven-block drop into a nether lava lake,
     * {@code gapAhead} was false (the cell toward the waypoint was netherrack), {@code offCentre}
     * was 0.17, and the body's own sole was on <b>0.0000 of 0.36</b>. And it lives in the drive
     * TAIL, which dozens of branches — dig, pillar, escape, step-up — return before reaching: the
     * next rehearsal fell on exactly such a tick, {@code drive=null}, while the crossing was digging
     * its way along. That is the same lesson {@link #strideFloorGuard} was hoisted here for.
     *
     * <h2>What it asks, in the order that makes it cheap</h2>
     *
     * The sole first ({@link WalkerGeometry#soleOnSolid}, four block reads, and on ordinary ground
     * it answers 0.36 immediately), and only for a body already down to half a sole does it pay for
     * {@link WalkerGeometry#lethalDropAdjacent}'s eight columns. So a walk over solid ground costs
     * four reads a tick and nothing else.
     *
     * <h2>What the pin is, and what it is not</h2>
     *
     * Vanilla sneak: {@code Player.maybeBackOffFromEdge} refuses the part of a move that would take
     * a shift-held body off its floor. It is a REFUSAL to step further out, not a rescue — a body
     * already over the void falls whatever this does, which is why the sole threshold is half a sole
     * and not zero. Jump is cancelled with it, because sneak has never clamped a jump and the nether
     * crossing's first fatal launch was exactly that: sneak held, {@code diagUp} planned, +0.42 of
     * upward velocity, into the lake.
     *
     * <p>Lethal-only, so ordinary ledge-hopping keeps its speed, and the same {@code lethalEdgeBrake}
     * switch the drive's gate answers to.
     */
    boolean footingGuard(Avatar a, WorldView world) {
        if (!BotConfig.lethalEdgeBrake) return false;
        Player p = a.player();
        if (p == null || p.isInWater()) return false;
        // NOT p.onGround(): that flag is verticalCollisionBelow, i.e. a report on the last move(),
        // and this guard's worst ticks are exactly the ones with no informative last move — the tick
        // after a placement, after a jump, after a reposition. The sole read below is the same
        // question asked of the world, so the flag was never adding a fact, only a false negative:
        // wd.serverWidensAThinFooting stages a body flush on obsidian with sole 0.168 and the guard
        // returned on this line every tick while the body walked off the ledge in 18.
        // A zero sole is still a return: nothing is under the body, it is falling, and sneak is a
        // refusal to step further out rather than a rescue (see the note above).
        double sole = soleOnSolid(world, p);
        if (sole <= 0.0) return false;
        // Over the VOID the threshold is higher, because the two mistakes are not symmetric. Half a
        // sole is the right bar beside an ordinary drop: a graze costs health the body walks off,
        // and pinning more often would make ridge walking crawl. Beside a bottomless column the
        // same graze ends the run — this body cannot die, so it does not respawn, it falls forever.
        // Measured: the last tick that still had support before a rung-20 departure read
        // 「身体=-31.05,82.75,25.49 速度h=0.000 脚底=0.212」 — stationary, so the stride guard (h ≥
        // 0.03) was silent, and 0.212 > 0.18, so this guard was silent too. Both guards off by
        // construction at exactly the reading that precedes the fall.
        double bar = FOOTING_MIN;
        if (voidBeside(world, BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ()))) {
            bar = VOID_FOOTING_MIN;
        }
        if (sole >= bar) return false;
        BlockPos foot = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
        // A PLANNED DESCENT is exempt, and this is not a nicety — it is the same release the drive's
        // own lethal-edge gate has carried since DEATH #8, for the same reason: vanilla's sneak
        // refuses to walk off ANY edge, so a pin held over a step the route means to take deadlocks
        // the descent instead of protecting it. Left out of the first cut, and the suite named the
        // cost in one run: wd.descent "crouch-deadlock: did not reach the bottom step",
        // wd.bridgeDescend "descending bridge wedged (sneak ledge-guard?)", wd.descentYaw thrashing
        // to 2463°. Every fall this guard is for was a body walking or jumping at a node level with
        // it or above it, so nothing it protects is given up here.
        if (path != null && step >= 0 && step < path.size()
                && path.get(step).getY() < foot.getY()) return false;
        if (!lethalDropAdjacent(world, p, foot)) return false;
        avatarSneak(a, true);
        a.commandJump(false);
        p.setSprinting(false);
        // Edge-triggered: a ridge walk pins for runs of ticks and a line per tick would bury the
        // rest of the log. The entry is the event — "the body reached a cell it is barely on".
        if (!footingPinned) {
            LOG.info("[walker] footing guard: sole {} < {} at {},{},{} beside a lethal drop → sneak-pin",
                    String.format(java.util.Locale.ROOT, "%.4f", soleOnSolid(world, p)), FOOTING_MIN,
                    foot.getX(), foot.getY(), foot.getZ());
        }
        footingPinned = true;
        widenFooting(a, world, p, foot);
        return true;
    }

    /**
     * True when this body is on a graze with the void beside it — the state from which no jump can
     * be allowed to leave the ground.
     *
     * <p>The planner's leap and diagonal gates cannot see this. They rule on EDGES, and the jump
     * that killed rung 20 here is the walker's own {@code stepUpFreeze}: measured
     * 「跳标=stepUpFreeze 身体=-42.70,102.00,5.30 速度h=0.528 脚底=0.000 节点=-43,103,4」 —— a
     * step-up fired at speed off a tower top with a sole that rounds to nothing. Six families of
     * departure have now been closed one at a time; this is the one that belongs to the executor
     * rather than to the plan, and it is why closing all seven planner moves did not end the falls.
     */
    static boolean grazingBesideTheVoid(WorldView world, Player p) {
        if (p == null || p.isInWater()) return false;
        double sole = soleOnSolid(world, p);
        if (sole <= 0.0 || sole >= VOID_FOOTING_MIN) return false;
        return voidBeside(world, BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ()));
    }

    /** The footing guard's own threshold, for scenes that must stage a body it actually looks at.
     *  An accessor rather than a copied literal: a rig that assumes 0.18 stops being a test of this
     *  guard the day the guard changes its mind. */
    public static double footingMin() { return FOOTING_MIN; }

    /** The bar over a bottomless column: 5/6 of the 0.36 sole, against 1/2 beside an ordinary drop.
     *  Not「be careful」as a number — it is the smallest reading that still leaves the body a whole
     *  half-cell of margin on every axis, and the void gives no second attempt. */
    static final double VOID_FOOTING_MIN = 0.30;

    /**
     * True when any column NEXT TO {@code at} runs out of the world.
     *
     * <p>Beside, not under — and the first cut got that backwards. A body with a sole to measure is
     * standing on something by definition, so the column under it is never bottomless; asking there
     * returns false every time and the raised bar could never arm. On a 1-wide tower it is the
     * pillar itself that answers, which is exactly the geometry the bar exists for: measured
     * 「身体=-32.70,82.00,26.86 速度h=0.671 脚底=0.263」 — under the 0.30 bar, moving fast, and the
     * guard silent because it had asked whether the tower it was standing on was made of air.
     *
     * <p>Same floor as the stride guard's own bottomless test, so the two agree about「虚空」.
     */
    static boolean voidBeside(WorldView world, BlockPos at) {
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                boolean open = true;
                for (int y = at.getY(); y >= BOTTOMLESS_SCAN_FLOOR; y--) {
                    if (!world.isPassable(new BlockPos(at.getX() + dx, y, at.getZ() + dz))) {
                        open = false;
                        break;
                    }
                }
                if (open) return true;
            }
        return false;
    }

    /**
     * Fill the empty column under the overhanging half of the sole.
     *
     * <p>The stride guard plugs the cell AHEAD; nothing has ever plugged the cell the body is
     * already half off. That gap is what makes rung 20's lip a dead end: measured across two runs at
     * the identical cell {@code (-32,85,27)}, the body stood on 16% of its sole beside the void with
     * 500+ cobblestone in the bag while the footing guard pinned it, the stride guard refused the
     * next step, the recovery hop refused to jump (a lethal drop one cell away) and the ascent
     * executor called the plan's own next node UNREACHABLE. Four correct refusals and no legal move.
     * One block under the body turns the perch into a floor and every one of those guards releases.
     *
     * <p>Only over a BOTTOMLESS column, for the same reason the stride guard's instant arming is:
     * over an ordinary drop a thin sole is a graze the body walks off, and spending blocks on every
     * ridge walk is how a bridging contract gets eaten. Over the void it is the difference between
     * continuing and falling forever.
     */
    private void widenFooting(Avatar a, WorldView world, Player p, BlockPos foot) {
        if (!BotConfig.allowPlace || a.breakHeld() || !a.holdPlaceable()) return;
        var box = p.getBoundingBox();
        for (int cx = (int) Math.floor(box.minX); cx <= (int) Math.floor(box.maxX); cx++) {
            for (int cz = (int) Math.floor(box.minZ); cz <= (int) Math.floor(box.maxZ); cz++) {
                BlockPos support = new BlockPos(cx, foot.getY() - 1, cz);
                if (!world.isPassable(support)) continue;             // already solid under here
                boolean bottomless = true;
                for (int y = support.getY() - 1; y >= BOTTOMLESS_SCAN_FLOOR; y--) {
                    if (!world.isPassable(new BlockPos(cx, y, cz))) { bottomless = false; break; }
                }
                if (!bottomless) continue;
                a.place(world, support);
                exAlarms.notePlace(support);
                LOG.info("[walker] footing guard: 垫脚 {},{},{}（脚底 {} < {}，该列直通虚空）",
                        support.getX(), support.getY(), support.getZ(),
                        String.format(java.util.Locale.ROOT, "%.4f", soleOnSolid(world, p)),
                        FOOTING_MIN);
                return;                                               // one block per tick
            }
        }
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
     * <p>One line per EVENT, not per tick: the window is 7 ticks wide and a body sits in it for
     * runs of them, so a per-tick line would be a hose. Entry to the window is the event. Capped at
     * {@link #WIGGLE_EVENTS} so a body that stalls repeatedly still cannot flood a rehearsal log.
     *
     * <p><b>"Entry" is adjacency of CALLS, never of game time.</b> The gate was
     * {@code getGameTime() - wiggleLastTick > 1}, which cannot see anything in a scene whose whole
     * body runs inside one server tick — {@code wd.buriedOre}'s 800 {@code tickAll()} iterations,
     * the {@code wd.serverMine*} family, {@code wd.selfShaftDigUp}. There {@code now} never moves,
     * so after the first line the delta is 0 forever and one line is printed however many times the
     * body entered the stall window. {@code wiggleCalls} is bumped on EVERY call, before the
     * precondition, so {@code call - wiggleLastCall > 1} means exactly "the immediately preceding
     * call was not itself inside the window" — the same event in a per-tick world (this is called
     * at most once per walker tick) and a working one in a one-tick world. {@code 序=} is the
     * ordinal within the cap, the only thing that orders lines whose {@code t=} is frozen; a
     * trailing {@code 序=5+/4 已达上限} line (and only that line) means a further entry was
     * swallowed, so {@code 序=4/4} alone still means "exactly four entries".
     *
     * <p><b>And the bearing, measurement only.</b> The ring answered「is the reach shorter than the
     * throw」— yes, by one — but the answer does NOT license widening it: one directed rehearsal
     * caught both sides, ring 3 firing (a ladder body took that into a lava lake) and ring 2 holding
     * through a three-minute stall. Direction is what separates them, so {@link
     * WalkerGeometry#hopBearingRow} prints where the body points against where the lethal cell is.
     * Nothing branches on it yet, by the same rule that turned this gate's boolean into a ring.
     * ⚠️ That row prints the CAMERA; the body is pushed along {@code driveTargetYaw}, decoupled
     * from it by design, so it is NOT the gate. The gate is {@link WalkerGeometry#hopSuppressed} —
     * the arc's own landing columns along the DRIVE bearing, which subsumes the ring test rather
     * than joining it — and {@link WalkerGeometry#hopLandingRow} prints the cells it read.
     *
     * <p>Why this earns a line at all: rung 20's takeoff samples showed the body already airborne
     * with {@code 距上次起跳=7}, and eliminating the jump terms that need a riser or water leaves
     * {@code wiggle} as the only one that can fire on a flat dry level walk. That elimination is
     * REASONING, not a reading — the rehearsal log has no per-tick walker lines to check it against.
     * This is the reading.
     */
    boolean wiggleHop(WorldView world, net.minecraft.world.entity.player.Player p, BlockPos foot,
                      boolean precond, float driveYaw) {
        long call = ++wiggleCalls;   // bumped BEFORE the precondition: adjacency must count skipped calls too
        if (!(precond && stuckTicks > 10 && stuckTicks < 18)) return false;
        BlockPos lethal = WalkerGeometry.nearestLethalHopCell(world, p, foot, WIGGLE_SCAN_MAX);
        int ring = WalkerGeometry.ringOf(foot, lethal);
        boolean gated = BotConfig.walkerRecoveryHopFloorGate && WalkerGeometry.hopSuppressed(world, p, foot, driveYaw);
        if (!gated && call - wiggleLastCall > 1) tallies.recoveryHops++;   // one per window entry, not per tick
        if (wiggleEvents < WIGGLE_EVENTS && call - wiggleLastCall > 1) {
            wiggleEvents++;
            LOG.info("[walker] 恢复跳: 序={}/{} t={} 卡住={} 身体={} 精确=({}) 旧闸半径={} 最近致命格={} 闸={} 起跳={} {} | {}",
                    wiggleEvents, WIGGLE_EVENTS,
                    p.level().getGameTime(), stuckTicks, foot.toShortString(),
                    String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                    WalkerGeometry.HOP_RANGE, ring < 0 ? ">" + WIGGLE_SCAN_MAX : String.valueOf(ring),
                    BotConfig.walkerRecoveryHopFloorGate, !gated,
                    WalkerGeometry.hopBearingRow(p, foot, lethal),
                    WalkerGeometry.hopLandingRow(world, p, foot, driveYaw));
        } else if (wiggleEvents >= WIGGLE_EVENTS && !wiggleCapped && call - wiggleLastCall > 1) {
            // Same blind spot as the jump-source cap: "序=4/4" is the last line both when the body
            // entered the stall window exactly WIGGLE_EVENTS times and when it entered it forty
            // times. One line, at the first entry the cap actually swallowed.
            wiggleCapped = true;
            LOG.info("[walker] 恢复跳: 序={}+/{} 已达上限，后续事件未记录（此行只印一次）",
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
     * burns it: on 2026-08-26 rung 12's body stalled on a lava lake's rim, spent all four inside
     * thirteen seconds, and printed 序=5+/4 for the hops that mattered — the ones between the last
     * logged position and a corpse in the lake. A cap that runs out inside the one stall worth
     * reading is not a hose guard, it is a blind spot with a budget. Sixteen is still bounded —
     * the line is one per ENTRY to a 7-tick window, not one per tick — and the 序=N+/16 line still
     * says when even that was not enough. Measured immediately after: one rehearsal stall printed
     * six consecutive suppressed hops (ring 2, 闸=true, 起跳=false), of which the old cap would
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

    /** True while {@link #footingGuard} is holding, so the log records the entry and not every tick. */
    private boolean footingPinned;

    /** Consecutive ticks the stride floor-guard has pinned; sustained pinning forces a repath. */
    int guardPinStreak;

    /** How many consecutive pinned ticks throw the plan away. Named rather than inlined twice: the
     *  two call sites are the fire path and the hold path and they must not be able to drift. */
    static final int GUARD_PIN_REPATH = 30;

    /** The last stride cell this pin streak fired on, and how many times that cell has CHANGED
     *  during it. Not a distinct-cell count: an A→B→A→B oscillation reads 4, not 2. That is why
     *  it is diagnostics and not the repath predicate — see {@link #forcedRepathIfPinnedTooLong}. */
    private BlockPos guardStreakCell;
    private int guardStreakCells;

    /** The plan this pin streak opened on, and the step index it was at. The repath predicate
     *  reads them; reference identity is the right comparison because every re-plan hands back a
     *  fresh list (smoothing included), so a changed reference IS a changed plan. */
    private List<BlockPos> guardStreakPath;
    private int guardStreakStartStep;

    /**
     * Throw the plan away when the pin has held for {@link #GUARD_PIN_REPATH} ticks —
     * <b>unless the body is consuming that plan</b> — and SAY SO either way.
     *
     * <h2>Why this was worth a method</h2>
     *
     * The two call sites were identical two-line expressions and the event they perform — a plan
     * silently discarded — <b>was the only thing the walker does that left no trace at all</b>. The
     * 2026-08-20 ladder ended rung 14 in a shuttle: four hops of 900 ticks each, 61-76 walk edges
     * apiece, net −8 to −28 blocks, over a lava sea the body had bridged itself. The stride guard
     * fired 491 times in that crossing on 184 distinct cells, and with {@link #GUARD_PIN_HOLD} = 8
     * a fire every eight ticks keeps this streak alive — so「the plan keeps being thrown away」and
     * 「the plan is bad」were both consistent with every row the run produced, and nothing in the
     * log or the evidence map could separate them.
     *
     * <h2>The measurement that separated them</h2>
     *
     * The 2026-08-21 BLAZE_ROD rehearsal, walking the scripted Nether corridor. Between two
     * waypoints 45 blocks apart the body was pinned to the threshold three times, and every
     * discarded plan was <b>complete</b>:
     *
     * <pre>
     * 第 43 次：连钉 30 tick，4 个不同的格子；丢掉的计划还剩 134 个节点，末节点 132,43,165；身体 103,41,135
     * 第 44 次：连钉 30 tick，3 个不同的格子；丢掉的计划还剩 132 个节点，末节点 132,43,165；身体 102,41,131
     * 第 45 次：连钉 30 tick，4 个不同的格子；丢掉的计划还剩 123 个节点，末节点 130,43,167；身体  97,41,115
     * </pre>
     *
     * {@code 132,43,165} was the leg's goal. The pathfinder kept solving the whole crossing — 132
     * nodes for 45 blocks of straight-line distance is the long way around the lava sea, which is
     * the correct route — and the body never got to walk it, because a rim walk pins every few
     * ticks and the 8-tick hold tail bridges the gaps. 45 forced repaths in one run. The escape
     * hatch built for a livelock was firing on a body that was not stuck.
     *
     * <h2>What it branches on, and what it does not</h2>
     *
     * <b>Whether {@link #step} advanced while the streak ran.</b> Sustained pinning is evidence
     * against a <i>route</i>; a route the body is visibly consuming is not the thing to indict. The
     * livelock this hatch exists for (567 pins at ONE cell) has a frozen step, so it still escapes.
     *
     * <p><b>Not the cell count.</b> That was the obvious candidate and it is the wrong predicate: a
     * body oscillating between two cells is livelocked and would read「一路换格」just as a rim walk
     * does. The count stays for the reader; only {@code step} decides.
     *
     * <p>A plan swapped out from under the streak (some other branch re-planned) rebases the streak
     * instead of being discarded: the pinned ticks were evidence against the plan they accrued
     * against, and a plan that has not been walked yet has not earned them.
     *
     * <p>Keeping a plan is not unbounded — {@link BotConfig#walkerTotalTickBudget} and the caller's
     * own tick cap still convert a slow-but-progressing route into an actionable FAILED. That is
     * deliberately the only backstop: a second threshold here (「enough progress」) would be a free
     * parameter with no measurement behind it.
     */
    private void forcedRepathIfPinnedTooLong(Avatar a) {
        if (guardPinStreak == 0) {
            guardStreakPath = path;
            guardStreakStartStep = step;
        }
        if (guardPlugCell != null && !guardPlugCell.equals(guardStreakCell)) {
            guardStreakCell = guardPlugCell;
            guardStreakCells++;
        }
        if (++guardPinStreak < GUARD_PIN_REPATH) return;
        Player p = a.player();
        String where = "；身体 " + (p == null ? "无" : p.blockPosition().toShortString());
        // A COVERAGE COUNT, NOT A MOVE COUNT. guardStreakCells increments on every cell ENTRY and
        // the first entry is the streak's own opening cell, so 1 means the body never left it. The
        // first cut printed「点火过 1 次换格（一直是同一格）」, which contradicts itself in one clause.
        String cells = "，其间钉过 " + guardStreakCells + " 个格位（含首格）"
                + (guardStreakCells <= 1 ? "（没离开过那一格）" : "（换过格 —— 参考项，不是判据）");

        if (path != guardStreakPath) {
            guardStreakRebases++;
            if (guardStreakRebases <= GUARD_STREAK_EVENTS) {
                LOG.info("[walker] guard pin streak rebased: 连钉 {} tick{} —— 期间计划被别处换过，"
                        + "这 {} tick 是记在旧计划头上的，新计划还没走过一步，不丢{}",
                        guardPinStreak, cells, guardPinStreak, where);
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        int consumed = step - guardStreakStartStep;
        if (consumed > 0) {
            guardKeptPlans++;
            lastGuardKeep = "第 " + guardKeptPlans + " 次：连钉 " + guardPinStreak
                    + " tick，但计划前进了 " + consumed + " 步（第 " + guardStreakStartStep
                    + " → " + step + " 节点）—— 身体在沿岸走，不是卡死，计划保留" + cells
                    + "；这条计划还剩 " + Math.max(0, path.size() - Math.max(step, 0))
                    + " 个节点，末节点 "
                    + (path.isEmpty() ? "无" : path.get(path.size() - 1).toShortString()) + where;
            if (guardKeptPlans <= GUARD_STREAK_EVENTS) {
                LOG.info("[walker] guard pin kept the plan: {}", lastGuardKeep);
            } else if (guardKeptPlans == GUARD_STREAK_EVENTS + 1) {
                LOG.info("[walker] guard pin kept the plan: 已达上限，后续保留只计数不再逐条打印"
                        + "（读 guardKeptPlans / lastGuardKeep）");
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        if (path == null) {
            // NOTHING TO THROW AWAY, so this is not a discard and must not be counted as one. Ten of
            // the 45 "forced repaths" in the 2026-08-21 rehearsal read「丢掉的计划还剩 0 个节点，
            // 末节点 无」— a body pinned with empty hands. Left in the same counter they inflate it
            // by a fifth, and a before/after comparison of guardForcedRepaths silently compares two
            // different populations. safetyRepath already re-plans on a null path; nothing to do here
            // but let the clock start over.
            guardPinnedWithNoPlan++;
            if (guardPinnedWithNoPlan <= GUARD_STREAK_EVENTS) {
                LOG.info("[walker] guard pin with no plan: 连钉 {} tick，而这段时间身上一直没有计划{}"
                        + " —— 没有东西可丢，不计入丢弃数{}", guardPinStreak, cells, where);
            }
            guardPinStreak = 0;
            guardStreakCells = 0;
            guardStreakCell = null;
            return;
        }

        guardForcedRepaths++;
        lastGuardRepath = "第 " + guardForcedRepaths + " 次：连钉 " + guardPinStreak
                + " tick，计划一步都没前进（停在第 " + guardStreakStartStep + " 节点）—— 这是原地卡死"
                + cells
                + "；丢掉的计划还剩 " + Math.max(0, path.size() - Math.max(step, 0))
                + " 个节点，末节点 "
                + (path.isEmpty() ? "无" : path.get(path.size() - 1).toShortString())
                + where;
        LOG.info("[walker] guard pin forced a repath: {}", lastGuardRepath);
        path = null;
        guardPinStreak = 0;
        guardStreakCells = 0;
        guardStreakCell = null;
    }

    /** Per-JVM cap on how many kept/rebased streaks print in full; both keep counting past it. */
    private static final int GUARD_STREAK_EVENTS = 8;

    /**
     * What {@link #forcedRepathIfPinnedTooLong} decided, since this JVM started, and what the last
     * decision of each kind looked like.
     *
     * <p>Write-only breadcrumbs on the same terms as {@link #lastTickTrace}: nothing branches on
     * them, they are read by instruments that hold no Walker instance — chiefly {@code
     * JourneyFlight}, which needs a per-leg delta and has no channel to the walker driving it.
     *
     * <p><b>Read them together, never {@code guardForcedRepaths} alone.</b> Before 2026-08-21 a
     * pinned streak had exactly one outcome, so one counter said everything; now「never reached the
     * threshold」and「reached it 45 times and kept the plan every time」are different runs that both
     * report zero forced repaths. A leg that reports 0/0 was never pinned; 0/45 walked a rim.
     *
     * <p><b>And the old number was never one population to begin with.</b> Of the 45 discards in the
     * run that motivated all this, 10 had no plan in hand at all (「还剩 0 个节点，末节点 无」), 25
     * dropped a 7-node scrap, and <b>3</b> dropped a complete route to the leg's goal — the three
     * that actually cost the crossing. Splitting them into four counters is what makes 45 → N a
     * comparison of the same thing twice instead of a headline.
     */
    public static volatile int guardForcedRepaths;
    public static volatile String lastGuardRepath = "还没强制重规划过";
    public static volatile int guardKeptPlans;
    public static volatile String lastGuardKeep = "还没有过「钉满但计划仍在前进」";
    /** Streaks whose plan was replaced by some other branch mid-streak, so the pinned ticks were
     *  evidence against a plan that no longer exists. Neither a discard nor a keep. */
    public static volatile int guardStreakRebases;

    /**
     * The water climb-out pillar takeover, reported for the same reason the guard counters above
     * are: an instrument holds no {@link Walker}, and the whole water-climb scene family records
     * <b>zero</b> evidence — eleven scenes whose only published bit is their own colour.
     *
     * <p><b>Read the three together.</b> A takeover that never engages and one that engages and
     * tops out well under its ceiling both leave {@link #waterPillarCeilingBails} at zero, and
     * those are opposite findings: the first means the branch was never exercised at all — the
     * scene proved nothing about it — and the second means it was exercised and stayed in bounds.
     * {@link #waterPillarEngages} separates them, and {@link #waterPillarTopRise} says how close
     * the run ever came to the ceiling, so「never fired」can be told from「never got near」.
     *
     * <p>Written by {@code WalkerTickClimb.engagePillar} and the pillar branch beside it. This
     * exists because a A/B over the whole suite could not see a bail that was
     * <i>unconditionally false</i>: nothing changed colour, so nothing changed.
     */
    public static volatile int waterPillarEngages;
    /** Times the climb-out actually clicked — {@code crestClearOf} passed and {@code Avatar#place}
     *  was called. The one bit neither the ledger nor the world can supply: a cell that stayed water
     *  means「clicked and vanilla refused」and「never clicked at all」equally well, and those are
     *  opposite findings about the crest gate. {@code wd.pillarLedgerCountsRefusedPlaces} ran a full
     *  400 ticks against a threshold that made the second one true and reported the colour the first
     *  one would have — read it together with whether the cell turned solid. */
    public static volatile int waterPillarPlaceCalls;
    /** Times the pillar exceeded its engage-anchored ceiling and bailed to the fallback actuators.
     *  <b>Never read alone</b> — see {@link #waterPillarEngages}. */
    public static volatile int waterPillarCeilingBails;
    /** The greatest rise above the engage foot any pillar reached, in blocks. Distinguishes a bail
     *  that never fired because the climb stayed low from one that could not fire at all. */
    public static volatile int waterPillarTopRise;
    /** Streaks that hit the threshold with no plan in hand. Not a discard — there was nothing to
     *  discard — and counted apart so it stops inflating {@link #guardForcedRepaths}. */
    public static volatile int guardPinnedWithNoPlan;

    /** Names for {@link #strideGuardSkips}, in bucket order, so the instrument printing them and
     *  the code filling them cannot drift apart. Declared FIRST because the array below is sized
     *  from it: a bucket added to one and not the other then fails at the increment rather than
     *  silently landing in a neighbour's tally. */
    public static final String[] STRIDE_SKIP_REASONS = {
            "关着/跑酷 tick", "没有身体", "在水里", "脚不在实心上", "没在平移 h<0.03",
            "前方那格不可穿过（就是地）", "计划本来就要下到那一柱", "那一柱在危险之前就见底了"};

    /**
     * Why {@link #strideFloorGuard} said nothing this tick — one bucket per early return, plus the
     * fires, so a silent guard can be told apart from an absent one.
     *
     * <p><b>The reading a burn post-mortem could not get.</b> That guard's fire line is
     * unconditional and it is the right guard for「about to stride into lava」, so「0 lines」looks
     * like a verdict. It is not: it collapses every one of these states into one number. The 2026-08-23
     * rehearsal walked into a source pool at −10,63,19 with the guard logging zero times, and
     * nothing on disk could say whether the flag was off, the sole was airborne, the stride cell
     * was solid ground, the plan had claimed that column, or the column really did floor out
     * safely. Same disease as {@code guardForcedRepaths} before it was split four ways
     * (see its javadoc above), and the same remedy: count the branches, not the outcome.
     *
     * <p>Exactly one bucket moves per tick — the method is a chain of early returns — so the sum is
     * the tick count the guard ran over, and any single bucket's share is directly readable.
     * Write-only breadcrumbs on the same terms as the counters above; nothing branches on them.
     *
     * <p>{@link java.util.concurrent.atomic.AtomicLongArray}, not a bare {@code long[]}: every
     * counter around this one is {@code volatile} precisely because the instruments reading them
     * hold no Walker and sit on another thread. A plain array's ELEMENTS carry no such guarantee,
     * so「every bucket is zero」would have had a second reading —「the writes are not visible
     * yet」— on the one occasion the row exists to settle. A reading that cannot distinguish its
     * own staleness from its subject is not a reading.
     */
    public static final java.util.concurrent.atomic.AtomicLongArray strideGuardSkips =
            new java.util.concurrent.atomic.AtomicLongArray(STRIDE_SKIP_REASONS.length);
    /** Ticks {@link #strideFloorGuard} actually pinned. Read beside the skips, never alone. */
    public static volatile int strideGuardFires;

    /** Tally one skip bucket and report「the guard did not act」in a single expression, so every
     *  early return in {@link #strideFloorGuard} stays a one-liner and none can be added without
     *  naming which bucket it belongs to. */
    private static boolean skipStride(int reason) {
        strideGuardSkips.incrementAndGet(reason);
        return false;
    }

    /**
     * Why the futile-search gate did not count a completed search — one bucket per exclusion, plus
     * the three ways it DID act. Same contract as {@link #strideGuardSkips}: exactly one bucket per
     * completed search, first match in the gate's written order, so the sum is the number of
     * searches the gate ran over and any bucket's share is directly readable.
     *
     * <p><b>Why counting was needed at all.</b> A run of 318 consecutive searches on one goal, over
     * two minutes, never tripped a cap of 5 — so five exclusions were suspects and the log named
     * none of them. It was settled that once from a DIFFERENT instrument's field (a {@code [place]}
     * row's neighbour cell read {@code water} at the body's own foot), which is luck, not method.
     * Nothing here changes behaviour; the gate is unchanged and this only says what it did.
     */
    public static final java.util.concurrent.atomic.AtomicLongArray futileGateBuckets =
            new java.util.concurrent.atomic.AtomicLongArray(10);
    /** Names for {@link #futileGateBuckets}, in bucket order — buckets 0-5 are exclusions (the gate
     *  never ran), 6-9 are what it did when it did run. Bucket 9 is split OUT of 6 rather than
     *  folded into it: a first search after a reset has no baseline to beat, so counting it as
     *  "got closer" made a reset look like progress the body had earned. */
    public static final String[] FUTILE_GATE_BUCKETS = {
            "闸关着(cap<=0)", "搜索到达了目标", "正在挖(breakHeld)", "水中攀爬正在挖",
            "脚格是水(让给水里的反转圈)", "无路且拉黑还没过期",
            "清零:离目标更近了", "清零:身体挪了>2格", "计入", "复位后播种(不判)"};


    /** Remaining hold-tail ticks after the last guard fire (pin hysteresis). */
    int guardHoldTicks;

    /** Hold-tail length: long enough to outlast the drive re-acceleration between fires
     *  (~3-5 ticks observed), short enough that a false pin costs under half a second. */
    static final int GUARD_PIN_HOLD = 8;

    /** True while the stride floor-guard's sneak-pin is held; cleared (and the sneak
     *  released) on the first tick the hazard is gone. */
    boolean guardSneakLatch;

    /** Plug arming: the stride cell the guard last fired on + accumulated fires on it.
     *  Resets only when the fired-on CELL changes (not on quiet ticks — a pinned body's
     *  velocity decays under 0.03 so fires on one cell arrive in bursts between pin
     *  cycles, and a consecutive-streak would never accumulate). */
    BlockPos guardPlugCell;
    int guardPlugFires;

    /** Fires on one stride cell before the plug placement arms. A genuine unplanned
     *  crossing (gap#53 well-mouth ON the corridor) re-fires the same cell across 2-3
     *  pin cycles and passes this quickly; a transient diagonal edge-graze on a normal
     *  walk sweeps a NEW cell every few ticks and never arms (StepTwo bypass r28: six
     *  dirt spent on plugs during a clean detour walk, every event at a different
     *  position, st≈0 — the pin alone was the load-bearing safety, the grounded-sneak
     *  edge clamp holds without any block). The pin itself stays instant. 12, not 7:
     *  the guard-pin corridor recovery (Aim, guardPinClock) engages at ~6 pinned ticks
     *  and must decisively win the race against plug arming on a place-free plan
     *  (r32: 7 fires accumulated before the recovery steered off the cell — 2 dirt).
     *  Construction plans (see canPlug) bypass this entirely. */
    static final int GUARD_PLUG_ARM_FIRES = 12;
    /** How far down「no floor at all」is checked before a drop counts as bottomless. Below every
     *  dimension's floor (the End starts at 0, the Overworld at -64), so a column clear to here is
     *  clear to the void. Scanned only on a tick the stride guard already fired — about 1% of them
     *  on the End island — so the cost is a rounding error next to being right about the void. */
    static final int BOTTOMLESS_SCAN_FLOOR = -70;

    /** True while this tick's edge is a parkour launch — the guard must not sneak-pin or
     *  jump-cancel a deliberate leap over void (its landing is the plan). Set inside
     *  {@link #tickInner}; reset each tick. */
    boolean guardParkourTick;

    /** Which branch commanded THIS tick's jump (null = no jump commanded). Pure telemetry,
     *  reset each tick in the prelude: fall post-mortems keep needing "who launched the
     *  fatal arc?" (bridge battery: three different launchers over three rounds), and the
     *  aggregated drive jump erases the answer by the time the body is airborne. */
    public String jumpTag;

    /**
     * Write-only breadcrumb: what the walker was doing on its most recent tick, for readers that
     * hold no Walker instance — chiefly the journey rig, which latches a fall long after the tick
     * that caused it and until now could report only WHERE the body last stood.
     *
     * <p>Four island-rim coordinates inside four blocks of each other survived five rounds of fixes
     * because the reading had a place and no action: every round guessed a mechanism, gated a move
     * family, and got another coordinate back. <b>Nothing branches on this.</b> It is read on the
     * fall path and printed; behaviour must never consult it, which is what keeps a static safe
     * here (a static another thread pathfinds against is how an A/B once measured nothing at all).
     */
    public static volatile String lastTickTrace = "还没跑过";

    /**
     * The last trace taken while the sole was still on something — the crime scene, as opposed to
     * {@link #lastTickTrace}, which is wherever the body had got to by the time anyone looked.
     *
     * <p>Both are needed and neither substitutes for the other. The final tick of a fall reports a
     * body at y=-64 chasing a node at y=-64: true, useless, and it reads like a planner defect when
     * it is only the walker re-planning for a body that is already there. The interesting tick is
     * the last one with support, which is the one that decided.
     */
    public static volatile String lastSupportedTrace = "还没站稳过";

    /** This tick's aim-tree owner ({@code aimSrc}) — same telemetry channel as
     *  {@link #jumpTag}: wedge post-mortems need "who owned the heading". */
    public String aimTag;

    /** This tick's drive command (target yaw + impulse) as a compact string; null when
     *  the tick never reached the drive tail (early-return branch) — which is itself
     *  the answer a pinned-body autopsy is after. Reset each tick in the prelude. */
    public String driveTag;

    /** Stride floor-guard (gap #53, the 2026-07-12 survival death; #51's stair-side void is
     *  the same invariant): while GROUNDED and dry, project the body's actual horizontal
     *  VELOCITY ~4 ticks ahead; if that cell is passable with NO floor within
     *  {@link BotConfig#pathfinderMaxDryFall}+1 below — a drop the planner can never have
     *  routed (Fall.valid caps at maxDryFall), so the exposure is always UNPLANNED — pin the
     *  body with vanilla sneak (maybeBackOffFromEdge stops it at the edge), cancel any pending
     *  jump, and when a placeable is at hand + allowPlace, plug the mouth so the crossing
     *  becomes real (backfill-as-you-go). Velocity, not the commanded yaw, is used: the fatal
     *  strides (live well-mouth crossing; arena pillar-top drift) moved the body along headings
     *  the drive variables did not predict. Planned descents (current waypoint below foot in
     *  the stride column) and parkour launches are exempt; water has its own physics.
     * <p><b>Recorded, not acted on: this guard is also an unplanned bridge-builder.</b>
     *
     * <p>Rung 14's crossing of 2026-08-20 was read back out of its own region files, and the box it
     * shuttled in is a lava sea — 18 458 lava cells against 2 543 netherrack in the walk band. The
     * only ground in it beyond one netherrack shelf is a <b>127-block dirt causeway running from
     * (74,86) to (96,110)</b>, and dirt does not generate in nether wastes. The body built it. Not
     * by plan either: the plans walked ONE {@code bridgePlace} edge per hop, while this guard fired
     * <b>491 times and plugged 142 blocks</b> across 184 distinct cells, of which only 5 ever
     * reached {@link #GUARD_PLUG_ARM_FIRES}. Thirty blocks of lava sea were bridged one safety
     * backfill at a time, and the crossing then shuttled up and down the bridge it had made.
     *
     * <p>That is a surprise worth having written down where the code is, and it is <b>deliberately
     * not acted on</b>. The backfill may be load-bearing: without it the body may have no route
     * across a lava sea at all, and「stop paving」could turn a slow crossing into an impossible one.
     * Deciding that needs the readings the next ladder run will carry — {@code guardForcedRepaths}
     * per leg, the distinct-cell count in the discard line, and {@code 计划最往回指}. Do not tune the
     * plug on the strength of this paragraph; it is a measurement, not a verdict.
     */
    boolean strideFloorGuard(Avatar a, WorldView world) {
        if (!BotConfig.walkerStrideFloorGuard || guardParkourTick) return skipStride(0);
        Player p = a.player();
        // soleOnSolid, NOT p.onGround(). `onGround` is `verticalCollisionBelow` — it describes the
        // last move() and is wrong in BOTH directions, which is why ServerPlayerAvatar's jump gate
        // abandoned it and why `wd.flushJumpIgnoresOnGround` pins that a body can be flush on stone
        // while it reads false. Every other reader of it has been converted one at a time; this one
        // is the most expensive to have left, because a stale false silently switches OFF the only
        // guard whose job is to stop the body striding into a bottomless drop. Measured on journey
        // rung 20 (2026-08-18): a whole run over the End island — void on every side — logged the
        // guard ZERO times, and the body walked off the edge.
        // THREE BUCKETS, not one. These were a single `||` until 2026-08-26, when a ladder rung
        // reported 「脚不在实心上（或在水里）=395」 for all 395 ticks it ran and that number could not
        // say which: a body afloat and a body over a drop read alike here and want opposite
        // remedies (get ashore vs. stop striding). One bucket per condition costs nothing and the
        // sum is unchanged, so the contract above — exactly one bucket per tick — still holds.
        if (p == null) return skipStride(1);
        if (p.isInWater()) return skipStride(2);
        if (WalkerGeometry.soleOnSolid(world, p) <= 0.0) return skipStride(3);
        Vec3 dm = p.getDeltaMovement();
        double h = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
        if (h < 0.03) return skipStride(4);                     // not translating
        double lead = Math.max(0.9, h * 4);                     // ~4 ticks of travel, min one cell
        BlockPos strideCell = BlockPos.containing(
                p.getX() + dm.x / h * lead, p.getY() + 0.05, p.getZ() + dm.z / h * lead);
        BlockPos footCell = BlockPos.containing(p.getX(), p.getY() + 0.05, p.getZ());
        if (strideCell.equals(footCell) || !world.isPassable(strideCell)) return skipStride(5);
        // Planned descent into that exact column (current or next few nodes — chained falls
        // put the landing node a step or two ahead of the pointer). Column must match
        // EXACTLY: a Chebyshev-1 slack would exempt the pit mouth beside a staircase and
        // re-open the descentDrift launch death.
        if (path != null) {
            int end = Math.min(step + 8, path.size());
            for (int i = Math.max(step, 0); i < end; i++) {
                BlockPos n = path.get(i);
                if (n.getY() < footCell.getY() && n.getX() == strideCell.getX() && n.getZ() == strideCell.getZ())
                    return skipStride(6);
            }
        }
        // Hazard threshold is LETHALITY at current HP, not mere unplannability: fall damage is
        // (blocks - 3), so a drop can kill only from ceil(HP)+3 blocks up (3.8HP → 7; full HP →
        // 23, the vanilla lethal line). Small unplanned falls are normal walker dynamics —
        // pinning them (first cut used maxDryFall+1=5) perturbed legitimate steep descents into
        // NEW failures (descentYaw chord-cuts are 5-6 block hops). The planner-unplannable
        // floor (maxDryFall+1) is kept as the minimum so a dying bot never out-shrinks it.
        int lethalDepth = Math.max(BotConfig.pathfinderMaxDryFall + 1, (int) Math.ceil(p.getHealth()) + 3);
        for (int i = 1; i <= lethalDepth; i++) {
            BlockPos below = strideCell.below(i);
            // A HAZARD anywhere in the column is lethal at whatever depth it sits at, and the test
            // has to come BEFORE the floor test because lava is `isPassable` — not solid, not water
            // — so this scan descended straight THROUGH a lake and stopped on its stone bed, reading
            // 「a floor → safe」about a drop into fire. Measured on nether rung 14 (2026-08-19): the
            // body strode off 80,42,81 over a bay whose lava starts 13 down and whose netherrack bed
            // sits exactly 23 down — one cell inside this loop's own reach at full health — so the
            // loop found its floor on the last index and the guard stayed silent for the whole
            // run-up. {@link WalkerGeometry#dropAdjacentExceeds} learned this in round52 and carries
            // the same line; the two guards cannot be allowed to disagree about what a fall column
            // ends in. Break rather than return: everything above index i is passable by
            // construction (the loop would have returned), so「open all the way down to lava」is
            // exactly the stride this guard exists to refuse.
            if (world.isHazard(below)) break;
            if (!world.isPassable(below) || world.isWater(below))
                return skipStride(7);                           // a floor or a water landing → safe
        }
        strideGuardFires++;
        avatarSneak(a, true);
        a.commandJump(false);
        p.setSprinting(false);
        // Log the plug's REAL outcome: the 2026-07-13 live death log claimed "plug" eight ticks
        // running for the same cell — if the first placement had landed, the second scan would
        // have found floor and never fired. The place() result was being discarded.
        if (strideCell.equals(guardPlugCell)) guardPlugFires++;
        else { guardPlugCell = strideCell.immutable(); guardPlugFires = 1; }
        // CONSTRUCTION plans plug instantly: when the current plan itself contains a
        // place-family edge, the journey is a build (buoyantWall: swimUp→pillarUp×2→
        // bridgePlace→pillarUp) and the guard's backfill is part of the same toolbox —
        // gating it behind the dwell broke the mount (r29-r32: 17 fires spread 3-4 per
        // cell, none armed, the shelf the route needed never existed, and the replan
        // dead-ended in a valve-less centroid pin). A place-free plan means the planner
        // judged walking cheaper — there the dwell keeps transient corner-cut grazes
        // from spending blocks the contract says to keep (StepTwo r28: 6 dirt causeway).
        boolean constructionPlan = false;
        if (edges != null)
            for (var e : edges)
                if (e != null && e.move != null
                        && ("pillarUp".equals(e.move) || "bridgePlace".equals(e.move)
                            || e.move.startsWith("parkourPlace"))) {
                    constructionPlan = true;
                    break;
                }
        // BOTTOMLESS arms the plug at once. The dwell above exists so a transient corner-cut graze
        // over an ordinary drop does not spend blocks — a fall of 23 onto stone costs health the bot
        // can walk off. A column with NO floor at all is a different thing: in the End it is the
        // void, and this body cannot even die of it (isInvulnerableTo is true on both fake players),
        // so instead of a death there is a body falling forever and a rung spending its budget on
        // orders to it. Measured on journey rung 20 across seven runs, that was the dominant failure,
        // and the guard was sneak-pinning correctly at the lip every time while declining to place
        // the one block that would have made the lip a floor.
        boolean bottomless = true;
        for (int y = strideCell.getY() - lethalDepth - 1; y >= BOTTOMLESS_SCAN_FLOOR; y--) {
            if (!world.isPassable(new BlockPos(strideCell.getX(), y, strideCell.getZ()))) {
                bottomless = false;
                break;
            }
        }
        // Over the void, BRAKE — do not merely stop asking the body to move. Vanilla's own edge
        // protection, Player.maybeBackOffFromEdge, is gated on p.onGround(), the flag this file has
        // documented as wrong in BOTH directions; on the tick it reads false the sneak above buys
        // nothing and the body slides off carrying the momentum it already had. That is the whole
        // gap between「守卫点了火」and「身体还是走下去了」: rung 20 logged the guard firing at the
        // lip and left the world anyway, from -15,60,36 / -16,61,34 / -18,61,36 — three cells
        // inside four blocks of each other on the same island rim. Only bottomless columns get
        // this: over an ordinary drop a graze costs health the body walks off, and killing momentum
        // on every ledge would make ridge walking crawl.
        if (bottomless) p.setDeltaMovement(0.0, dm.y, 0.0);
        boolean canPlug = BotConfig.allowPlace && !a.breakHeld()
                && (bottomless || constructionPlan || guardPlugFires >= GUARD_PLUG_ARM_FIRES);
        boolean held = canPlug && a.holdPlaceable();
        if (held) a.place(world, strideCell.below());
        // place() has no return value, so read the WORLD for the outcome. A client-side place
        // may land a tick later — then the next scan finds floor and stops firing, which is the
        // same signal; what matters is that repeated fires on one cell now read "plug FAILED"
        // instead of eight confident "plug" lines while nothing was ever placed.
        boolean plugged = held && !world.isPassable(strideCell.below());
        // Unconditional: firing means the body was one stride from an unplanned lethal drop —
        // rare by design, and the one signal that matters when reconstructing a fall post-mortem.
        LOG.info("[walker] stride floor-guard: bottomless stride {},{},{} (vel {}, {}) → sneak-pin{}",
                    strideCell.getX(), strideCell.getY(), strideCell.getZ(),
                    String.format(java.util.Locale.ROOT, "%.2f", dm.x),
                    String.format(java.util.Locale.ROOT, "%.2f", dm.z),
                    plugged ? " + plug " + strideCell.below()
                            : !canPlug ? "" : !held ? " (no placeable held)"
                            : " (plug FAILED " + strideCell.below() + ")");
        return true;
    }

    private Step tickInner(Avatar a, WorldView world) {
        WalkerTickCtx c = new WalkerTickCtx();
        Step r;
        r = WalkerTickPrelude.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickStallDetect.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickRepath.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickSearch.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickProgress.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickClimb.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickEdgeGuards.run(this, c, a, world); if (r != null) return r;
        r = WalkerTickAim.run(this, c, a, world); if (r != null) return r;
        return WalkerTickDrive.run(this, c, a, world);
    }

    /** Fire onTerminal and return the step verdict in one place, so every terminal
     *  return site stays a one-liner. */
    Step terminal(Step s, PathTrace.Outcome outcome, String reason) {
        PathTraceHolder.SINK.onTerminal(outcome, reason);
        return s;
    }

    /** Pure classification of a generic ARRIVED exit (static & matrix-testable).
     *  Public like the sibling {@code RetreatChain.shouldEnter}/{@code shouldRelease}
     *  static gates — the neoforge matrix gametest calls it cross-package/cross-module. */
    public static String classifyArrival(boolean bestEffort, boolean reachedFoot, boolean snapped) {
        if (snapped && reachedFoot) return "goal-snapped";
        if (reachedFoot) return "arrived";
        return bestEffort ? "best-effort-consumed" : "path-consumed";
    }

    /** terminal() + honest report stamp. foot may be null (no player) → conservative false. */
    Step terminalReport(Step s, PathTrace.Outcome outcome, String reason,
                                String endReason, BlockPos foot) {
        lastEndReason = endReason;
        lastGoalReached = foot != null && goal != null && !goalSnapped && goal.reached(foot);
        lastFinalDist = (foot != null && goal != null) ? goal.estimate(foot) : -1;
        return terminal(s, outcome, reason);
    }

    /** Externally cancelled (mc.bot.cancel / superseded by a new goto) before a
     *  natural terminal. Fire onTerminal(CANCELLED) so per-session trace observers
     *  finalize the partial run (e.g. a path archive is flushed for a wedge the
     *  agent cancelled out of). A no-op for the recorders if no session is open
     *  (they guard on sessionOpen), so a double-fire after a natural terminal is
     *  harmless. */
    public void abort(String reason) {
        PathTraceHolder.SINK.onTerminal(PathTrace.Outcome.CANCELLED, reason);
    }

    /** At a loaded-chunk frontier the (stale) eager continuation from seg.commitEnd —
     *  computed before the bot arrived — found no onward route. Re-search FRESH from
     *  the frontier: now that the bot stands there, chunks ~render-distance further
     *  have loaded and the next segment is visible. Bounded by {@link #FRONTIER_WAIT_CAP}
     *  (the bot is stationary while waiting, so retrying more can't load new chunks)
     *  so a genuine box-in still terminates. Holds (keys released) and returns
     *  WALKING while retrying; ARRIVED when out of retries or the feature is off. */
    Step frontierHoldOrArrive(Avatar a, WorldView world, Player p) {
        if (!replayMode && BotConfig.pathfinderFrontierCommit && seg.commitEnd != null
                && seg.frontierWaitTicks < FRONTIER_WAIT_CAP) {
            seg.frontierWaitTicks++;
            if (seg.activeSearch == null) {
                seg.activeSearch = newPathFinder(world).newSearch(seg.commitEnd, goal);
                seg.searchFromEnd = true;
            }
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
     *  to {@link BotConfig#pathfinderProgressive} (default OFF → behaviour unchanged). */
    void maybeArmPinchEscalation(BlockPos foot, PathFinder.Result res) {
        if (!BotConfig.pathfinderProgressive) return;
        if (res.goalReached() || seg.commitEnd == null) return;      // healthy full route → no escalation
        double progress = goal.estimate(foot) - goal.estimate(seg.commitEnd);
        if (progress >= PINCH_MIN_PROGRESS) return;              // real headway → not a pinch
        boolean wasArmed = escal.armed();
        escal.arm(BOXED_ESCALATE_STICKY_TICKS);
        if (BotConfig.walkerDebug && !wasArmed)
            LOG.info("[walker] proactive pinch escalation ARMED: best-effort commit gained only {} blocks toward goal → deepen next search",
                    String.format("%.1f", progress));
    }

    /** PROGRESSIVE QUICK-START (渐进式寻路): the big re-plan is still slicing in
     *  the background and the bot has nothing to walk — that gap is the visible
     *  inter-segment freeze (several seconds standing still at every hard
     *  obstacle). Spend a tiny SYNCHRONOUS node budget on a short best-effort
     *  segment toward the goal and start walking it right now; the big result
     *  supersedes the stub when it lands through the normal adoption path.
     *  Frame cost is irrelevant here: with no path the bot is frozen anyway
     *  (same argument as the idle-slice). Only a stub that actually nears the
     *  goal is adopted — a boxed-in micro-search returns pacing scraps that
     *  would jitter, so those back off for {@link #QUICK_RETRY_TICKS}.
     *  @return true if a stub segment was adopted (path != null, step = 1). */
    boolean tryQuickStart(WorldView world, BlockPos foot, Goal goal) {
        if (BotConfig.pathfinderQuickNodes <= 0) return false;
        if (searchGov.quickCooldown > 0) { searchGov.quickCooldown--; return false; }
        PathFinder.Search q = WalkerFinders.quick(this, world, QUICK_MAX_MS).newSearch(foot, goal);
        while (!q.advance(QUICK_MAX_MS)) { /* bounded by the node cap / QUICK_MAX_MS */ }
        PathFinder.Result res = q.result();
        boolean useful = res.hasPath() && res.path().size() > 1
                && goal.estimate(foot)
                   - goal.estimate(res.path().get(res.path().size() - 1)) >= 2.0;
        if (!useful) {
            searchGov.quickCooldown = QUICK_RETRY_TICKS;
            return false;
        }
        if (BotConfig.walkerDebug)
            LOG.info("[walker] quick-start stub adopted: len={} expanded={} ms={} (big search still running)",
                    res.path().size(), res.expanded(), res.ms());
        adoptPath(res, world, null);    // stub starts at the foot — no fast-forward needed
        return true;
    }

    /** PROGRESSIVE OPEN-WATER BEE-LINE (渐进式水面直线 stub): over a wide deep-water
     *  crossing the sliced A* re-plan is expensive — it expands the whole 3-D water
     *  volume (canStandAt accepts every depth), thousands of nodes over several
     *  seconds, and even a bounded {@link #tryQuickStart} can't progress 2 blocks. So
     *  the bot consumes its short committed segment, has no fresh forward path, wedges,
     *  and anti-stuck bursts crab it across jerkily (live 2026-06-15 wide-water run:
     *  burst every ~6 s, the bot 30+ blocks past a stale segment, max step-stuck 106).
     *  A flat open-water crossing needs no search: greedily march toward the goal along
     *  the SURFACE — at each cell take the 8-neighbour that most reduces goal.estimate
     *  and is open surface water — and adopt that straight run as a long stub so the bot
     *  keeps swimming smoothly while the big search lands and supersedes it. A greedy
     *  local minimum is harmless: the stub is a stopgap, replaced the instant the real
     *  path arrives; it only ever drives the bot over open water it could swim anyway.
     *  Gated to a body of water under the feet. Tried BEFORE {@link #tryQuickStart} in water:
     *  under the surface water model the quick search succeeds there, and its first node
     *  follows the bob (a swimUp when the foot is sunk, a stepDown when it rides high), so
     *  each adoption re-aimed the jump/sneak and the crossing became a piston. The march
     *  starts from the SURFACE cell of the foot's column, whichever way the bob has the
     *  foot at this tick. @return true if a bee-line was adopted. */
    boolean tryWaterBeeline(WorldView world, BlockPos foot, Goal goal) {
        if (!world.isWater(foot)) return false;
        BlockPos surface = isOpenSurfaceWater(world, foot) ? foot
                : isOpenSurfaceWater(world, foot.above()) ? foot.above()
                : isOpenSurfaceWater(world, foot.below()) ? foot.below() : foot;
        List<BlockPos> path = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        path.add(surface);
        edges.add(null);                       // start node carries no inbound edge
        BlockPos cur = surface;
        double curEst = goal.estimate(surface);
        for (int i = 0; i < BEELINE_MAX_STEPS; i++) {
            BlockPos best = null;
            double bestEst = curEst;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos n = cur.offset(dx, 0, dz);
                    if (!isOpenSurfaceWater(world, n)) continue;
                    double e = goal.estimate(n);
                    if (e < bestEst) { bestEst = e; best = n; }
                }
            if (best == null) break;            // no goal-ward open-water step (boxed / reached a bank)
            path.add(best);
            edges.add(new Move.Edge(best, 10, List.of(), List.of(), "walk"));
            cur = best;
            curEst = bestEst;
        }
        if (path.size() <= BEELINE_MIN_STEPS) return false;   // too short to be worth a stub
        if (BotConfig.walkerDebug)
            LOG.info("[walker] open-water bee-line stub adopted: len={} toward goal (big search still running)",
                    path.size());
        adoptPath(new PathFinder.Result(path, edges, false, 0, 0L, 0.0), world, null);
        return true;
    }

    /** PROGRESSIVE LAND COARSE-DIRECTION stub (渐进式陆地直行 stub): the dry-land twin of
     *  {@link #tryWaterBeeline}. Over open / gently-sloped land the big sliced A* re-plan
     *  can burn thousands of nodes before it commits, leaving the bot frozen at the
     *  start-of-segment gap (the visible startup / inter-segment churn). No search is
     *  needed to START moving the right way: greedily march toward the goal — at each
     *  cell take the 8-neighbour, at the SAME Y, that most reduces {@code goal.estimate}
     *  and is safely STANDABLE on dry ground — and adopt that run as a coarse stub the
     *  bot walks immediately while the big search runs and supersedes it. A greedy local
     *  minimum (a wall, a slope, a cliff, water) is harmless: the march simply stops
     *  there and the real path replaces the stub the instant it lands; the stub only ever
     *  drives the bot over flat ground it could walk anyway. Same-Y only (like the water
     *  bee-line) so it never steps off a ledge or rams a riser — slopes hand back to A*.
     *  Gated to {@link BotConfig#pathfinderProgressive} + dry land under the feet (water
     *  is {@link #tryWaterBeeline}'s job). @return true if a land run was adopted. */
    boolean tryLandBeeline(WorldView world, BlockPos foot, Goal goal) {
        if (!BotConfig.pathfinderProgressive) return false;
        if (world.isWater(foot)) return false;          // water is tryWaterBeeline's job
        List<BlockPos> path = new ArrayList<>();
        List<Move.Edge> edges = new ArrayList<>();
        path.add(foot);
        edges.add(null);                                // start node carries no inbound edge
        BlockPos cur = foot;
        double curEst = goal.estimate(foot);
        for (int i = 0; i < BEELINE_MAX_STEPS; i++) {
            BlockPos best = null;
            double bestEst = curEst;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    BlockPos n = cur.offset(dx, 0, dz);
                    if (!isDryWalkable(world, n)) continue;
                    double e = goal.estimate(n);
                    if (e < bestEst) { bestEst = e; best = n; }
                }
            if (best == null) break;            // no goal-ward dry step (slope / wall / water / cliff)
            path.add(best);
            edges.add(new Move.Edge(best, 10, List.of(), List.of(), "walk"));
            cur = best;
            curEst = bestEst;
        }
        if (path.size() <= BEELINE_MIN_STEPS) return false;   // too short to be worth a stub
        if (BotConfig.walkerDebug)
            LOG.info("[walker] land coarse-direction stub adopted: len={} toward goal (big search still running)",
                    path.size());
        adoptPath(new PathFinder.Result(path, edges, false, 0, 0L, 0.0), world, null);
        return true;
    }


    /** Splice in a freshly-searched route: string-pull it, reset the per-path
     *  follow state, and record whether it's a best-effort partial (so the next
     *  segment is precomputed from its end — see the kickoff/splice logic in
     *  {@link #tick}). {@code seg.commitEnd} is the segment's last node, the launch
     *  point for that continuation search.
     *
     *  @return false if the segment was REJECTED because its start is nowhere
     *  near the feet. A continuation is computed from the previous segment's
     *  seg.commitEnd; when the bot never actually made it there (fumbled the climb,
     *  fell off the route) the spliced path STARTS in mid-air several blocks
     *  away — step 1 is unreachable, fast-forward finds nothing near, and the
     *  bot just hangs against the wall until the wedge timer fires (live
     *  2026-06-09: cave pocket, step1 4 blocks up / 4.5 away, 15 s freeze).
     *  Rejecting here lets the caller drop the stale segment and re-search
     *  from where the bot really is. */
    boolean adoptPath(PathFinder.Result res, WorldView world, BlockPos foot) {
        if (foot != null && !res.path().isEmpty()) {
            // Anchor on the nearest node of the PREFIX, not just path[0]. While a
            // search runs (0.4-1.7 s) a SWIMMING bot keeps drifting 5-12 blocks
            // along its old segment — both routes head for the same goal, so the
            // fresh path's prefix IS the corridor the bot just swam. Judging only
            // path[0] turns that drift into a reject→re-search→drift-again
            // oscillation (live 2026-06-10: 6 rejects in 18 s, 35 min circling a
            // water-shore pocket, the 75-node highland exit killed twice). A
            // truly mis-anchored continuation (computed from a seg.commitEnd the bot
            // never reached) has its WHOLE prefix far/high, so it still rejects.
            List<BlockPos> raw = res.path();
            int anchor = 0;
            double anchorD = raw.get(0).distSqr(foot);
            int lim = Math.min(raw.size() - 1, 16);
            for (int i = 1; i <= lim && anchorD > 1.0; i++) {
                Move.Edge e = i < res.edges().size() ? res.edges().get(i) : null;
                if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
                double d2 = raw.get(i).distSqr(foot);
                if (d2 < anchorD) { anchorD = d2; anchor = i; }
            }
            // In WATER the gate must absorb CURRENT drift: a river pushes the
            // bot ~1.5 b/s downstream while it holds path-less, so by the time
            // a 0.4-1.7 s search lands the feet are 5-7 blocks away — with the
            // dry 4-block gate every fresh segment got rejected, the hold let
            // the river push further, and the loop swept the bot 50 blocks
            // downstream (live 2026-06-10: 10 rejects in 30 s straight down
            // z=-561, each segment's nearest prefix node = the previous foot).
            // Swimming 8 blocks back onto the route is always executable; the
            // tight gate only exists for dry falls/jumps that aren't.
            double rejectGate = world.isWater(foot) ? 64 : 16;
            // DEEP-WATER-FLOAT BEE-LINE EXEMPTION (live #47 deterministic dead-stop): a bot floating in
            // deep open water commits a best-effort segment whose continuation (from seg.commitEnd) starts a
            // long clear-LOS open-water WALK node tens of blocks ahead — the only intermediate node is an
            // IN-PLACE swimUp (same XZ as the foot), so the anchor finds NO near forward node and rejects
            // (d2≈2300). The reject drops the path, the foot-search returns the SAME swimUp+far-walk
            // best-effort, and it re-pins → 13 repaths, no progress, hSpd=0, anti-spin ends best-effort
            // (~6 s dead-stop; telemetry "reject mis-anchored: -812,62 (d2=2305) vs foot -860,58"). But
            // that far node IS reachable: the buoyant body can swim a STRAIGHT line to it over open water
            // (no wall, all water/air). Accepting the segment lets the carrot bee-line toward the far node
            // and make forward progress (the deep-water-float analog of the quick-start stub / water
            // bee-line). STRICTLY gated so it can NOT exempt a truly mis-anchored / walled / climb segment:
            //   • the foot must be FLOATING in water (the rejectGate's own water test);
            //   • the straight line foot→anchor must be a CLEAR OPEN-WATER bee-line (losWalkable AND every
            //     sampled cell water/air — a bank, ledge or dry walkway fails it);
            //   • the anchor node must sit within ±4 Y of the floating foot — a SURFACE crossing the buoyant
            //     body swims up+across to (the live foot bobs y58↔61 under a y62 surface node, dy up to 4),
            //     never an impossible bank climb (a real climb-out has a solid bank IN the line → the
            //     open-water LOS already rejects it, so this band only bounds the swim-up reach).
            // A genuinely fumbled continuation (computed from a seg.commitEnd behind a wall / up a cliff the
            // bot never reached) fails the open-water LOS or the ±4 Y band, so it still rejects.
            // The exemption suppresses BOTH reject conditions (the d2 distance gate AND the |Δy| jump gate):
            // for an open-water bee-line the Y difference is a buoyant swim-UP to the surface node, not a dry
            // jump/fall, so the maxJumpUp gate (which exists to bar impossible dry climbs) must not veto it.
            boolean openWaterBeeline = BotConfig.walkerDeepWaterFloatBeeline
                    && world.isWater(foot)
                    && Math.abs(raw.get(anchor).getY() - foot.getY()) <= 4
                    && losWalkable(world, foot, raw.get(anchor))
                    && isOpenWaterLine(world, foot, raw.get(anchor));
            if (!openWaterBeeline
                    && (anchorD > rejectGate
                        || Math.abs(raw.get(anchor).getY() - foot.getY()) > world.maxJumpUpBlocks() + 2)) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] reject mis-anchored segment: nearest prefix node {},{},{} (d2={}) vs foot {},{},{}",
                            raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                            (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
                return false;
            }
            if (openWaterBeeline && anchorD > rejectGate && BotConfig.walkerDebug)
                LOG.info("[walker] accept open-water bee-line segment: far node {},{},{} (d2={}) reachable by clear-LOS swim from foot {},{},{}",
                        raw.get(anchor).getX(), raw.get(anchor).getY(), raw.get(anchor).getZ(),
                        (int) anchorD, foot.getX(), foot.getY(), foot.getZ());
        }
        if ((res = PathSmoothing.dropStalePrefix(world, res)).path().size() < 2) return false;   // the body dug while the search ran
        // String-pull flat walk runs so the heading stays steady over the staircase (no left-right
        // camera wobble) and the bot walks straight; action/vertical/parkour nodes are preserved.
        SmoothResult sm = smoothAndRemember(world, res, profile.bias());
        path = sm.path;
        edges = sm.edges;
        seg.pathBestEffort = !res.goalReached();
        // §93 commit-tail platform retreat (#15 final lane). A best-effort segment's
        // tail is wherever the node budget ran out — often MID-SLOPE on complex steep
        // terrain. The bot then climbs to a half-mounted ledge, the periodic repath
        // re-plans from that awkward stance, and the climb-fall oscillation burns
        // ~100s (slow-map zones; the SAME terrain runs clean in 8s standalone, §92b —
        // the grind is planner state, not executor skill). When the tail node is not
        // a platform (fewer than 2 same-Y standable cardinal neighbours), retreat the
        // commit up to 8 nodes to the nearest platform node so the segment ends on
        // ground the executor can stand square on while the next search runs.
        if (BotConfig.walkerCommitTailPlatform && seg.pathBestEffort && path.size() > 4) {
            int cut = -1;
            for (int k = path.size() - 1; k >= Math.max(2, path.size() - 8); k--) {
                BlockPos n = path.get(k);
                int flat = 0;
                for (int[] d4 : new int[][]{{1,0},{-1,0},{0,1},{0,-1}})
                    if (world.canStandAt(n.offset(d4[0], 0, d4[1]))) flat++;
                if (flat >= 2) { cut = k; break; }
            }
            if (cut > 0 && cut < path.size() - 1) {
                if (BotConfig.walkerDebug)
                    LOG.info("[walker] commit-tail retreat: {} -> {} (platform {},{},{})",
                            path.size() - 1, cut, path.get(cut).getX(), path.get(cut).getY(), path.get(cut).getZ());
                path = List.copyOf(path.subList(0, cut + 1));
                edges = Collections.unmodifiableList(new ArrayList<>(edges.subList(0, cut + 1)));
            }
        }
        seg.commitEnd = (seg.pathBestEffort && !path.isEmpty()) ? path.get(path.size() - 1) : null;
        step = 1;
        searchGov.noPathWaitTicks = 0;            // a segment was found → the no-path wait starts over
        // A segment that ends FARTHER from the goal than it starts can only be
        // the last-resort ESCAPE (every other selector is strictly goal-ward):
        // its start is a PROVEN dead pocket. Charge it now so subsequent searches
        // stop folding back into it — without this the cave-funnel relapse loop
        // is fully deterministic (live 2026-06-09: escape→high route→fall→free
        // h-improving corridors all funnel back to the same pocket, ~3 min/lap,
        // and nothing on the lap ever calls penalizeStuckNode).
        if (goal != null && path.size() > 1
                && goal.estimate(path.get(path.size() - 1)) > goal.estimate(path.get(0))) {
            world.penalizeStuckNode(path.get(0));
            world.penalizeStuckNode(path.get(0).above());
            if (BotConfig.walkerDebug)
                LOG.info("[walker] escape segment adopted → penalize dead pocket at {}", path.get(0));
        }
        // Fast-forward past a prefix the bot has already covered. A quick-start
        // stub (progressive pathfinding) carries the bot a few blocks ahead of
        // where the superseding big search was LAUNCHED, so the fresh path can
        // start behind the feet — without this the bot visibly walks BACKWARD
        // to rejoin at path[0]. Skip to the nearest on-path node in the first
        // few steps, but never across an edge that still needs to break/place
        // blocks (skipping its action would desync the build state). Only jump
        // when a node is genuinely at the feet; a normal foot-search has
        // path[0] == foot and is untouched.
        if (foot != null && path.size() > 2) {
            // Loose rejoin ONLY when path[0] is already off the feet (the bot
            // drifted while the search ran — open water, quick-start carry).
            // A normally-anchored path keeps the strict gate: skipping to a
            // node 2-3 blocks out also skips the stair base / bridge lip
            // between here and there.
            boolean drifted = path.get(0).distSqr(foot) > 2.0;
            int window = drifted ? 16 : 8;
            // In water, accept a rejoin node as far as the reject gate lets a
            // segment in (river drift, see adopt-gate above): swimming back a
            // few blocks is always executable, and refusing leaves step=1
            // pointing far UPSTREAM of a drifted bot.
            double accept = drifted ? (world.isWater(foot) ? 64.0 : 9.0) : 2.0;
            int nearest = step;
            double nearestD = path.get(step).distSqr(foot);
            for (int i = step + 1; i < Math.min(path.size() - 1, step + window); i++) {
                Move.Edge e = i < edges.size() ? edges.get(i) : null;
                if (e != null && (!e.toBreak.isEmpty() || !e.toPlace.isEmpty())) break;
                // Never anchor onto a node beyond ±1 of the feet: a drifted bot
                // in water sits 2 below the bank nodes (3D distSqr still ranks
                // them "near", dy=2 → +4) — starting there is an impossible +2
                // climb (live: wedged at y62 vs a y64 diagUp for 100 ticks).
                // DOWN must be capped too: anchoring onto a node 5 BELOW a
                // cliff-top bot aims the walk at the column past the lip and it
                // just rams the wall (live: wp walk@y57 vs feet y62, hCol,
                // hSpd=0). ±1 keeps the rejoin on the bot's own walking layer.
                int ffDy = path.get(i).getY() - foot.getY();
                // A V-shaped underwater path (dive → ride the bed → climb out)
                // overlaps itself in XZ, so the climb-out branch ranks "nearest"
                // and skipping flattens the V: the bot anchors onto the node
                // ABOVE its head, the dive trigger never arms (wp not below),
                // and it pins against the well wall (live round46: wp=(3156,64)
                // vs feet y62, hCol, hSpd=0, 17 bursts in 2 min). A submerged
                // valley node is a hard rejoin barrier — stop the scan there:
                // everything beyond is only reachable THROUGH the dive.
                if (ffDy < -1 && world.isWater(path.get(i))) break;
                if (Math.abs(ffDy) > 1) continue;
                double d2 = path.get(i).distSqr(foot);
                if (d2 < nearestD) { nearestD = d2; nearest = i; }
            }
            if (nearest > step && nearestD <= accept) step = nearest;
        }
        stuckTicks = 0;
        seg.frontierWaitTicks = 0;          // progress made → reset the frontier re-search budget
        stepProg.stuckStep = -1;        // new path geometry → restart the progress window
        stepProg.noStepProgressTicks = 0;   // new path → restart the wedge timer (else a same-index step re-triggers instantly)
        stepProg.noProgressStep = -1;
        // A freshly adopted PROGRESSIVE stub (quick-start / open-water bee-line —
        // the only adopts with foot==null) is a brand-new runway in a NEW
        // direction, so the wedge-burst counter accrued against the stale segment
        // is no longer valid: leaving it set fires a forced-displacement burst
        // ~1 s after adoption (the 40-tick count cooldown was already armed),
        // which yanks the bot BACKWARD off the runway it just got — live
        // 2026-06-15 wide-water run: bee-line adopted then burst 1 s later, on
        // repeat, crabbing the bot the WRONG way along the shore. Clear it so the
        // stub gets a clean ~6 s trial; if the bot genuinely can't follow it the
        // counter simply re-arms and bursts as before. The big-search continuation
        // (foot != null) keeps its burst intact — that's the deterministic
        // same-segment deadlock breaker and must not be reset away.
        if (foot == null) unstuck.dropWedgeAnchor();
        stepProg.bestStepDist = Double.POSITIVE_INFINITY;
        stepProg.stuckStepHigh = step - 1;   // new path, new index semantics: one fresh window on the first tick, then high-water applies
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
        return true;
    }

    /** Emit a per-tick execution sample. Pure reads; cheap; gated to NOOP in release. */
    void sampleTick(Player p) {
        // Cheap gate: skip the per-tick WalkerSample allocation entirely unless capture is on.
        // Keeps the hot path free in normal play and in a stripped (NOOP) release build.
        if (!BotConfig.pathDebug && !BotConfig.pathArchive) return;
        // Expose the bot's level to PathArchiveRecorder (and other PathTrace sinks) without
        // referencing the client-only Minecraft class.  Set here — before onSearchResult or
        // onSearchBegin can fire within the same tick (both sites are below line 451) — so
        // the first segment's NodePhysics and envelope sampling always see a non-null level.
        BotLevelHolder.current = p.level();
        double tx = Double.NaN, tz = Double.NaN;
        String mv = null;
        if (path != null && step >= 0 && step < path.size()) {
            BlockPos t = path.get(step);
            tx = t.getX() + 0.5;
            tz = t.getZ() + 0.5;
            Move.Edge e = edgeAt(step);
            mv = (e != null) ? e.move : null;
        }
        boolean overlap = !p.level().noCollision(p, p.getBoundingBox().deflate(1.0E-7));
        PathTraceHolder.SINK.onWalkerTick(new PathTrace.WalkerSample(
                p.tickCount, p.getX(), p.getY(), p.getZ(), p.getYRot(),
                tx, tz, step, mv, p.onGround(), p.isInWater(),
                p.getPose().name(), overlap, p.getId()));
    }


    Move.Edge edgeAt(int i) {
        return (edges != null && i >= 0 && i < edges.size()) ? edges.get(i) : null;
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
     *  earlier leg — the dominant projection risk, §7), honouring the adoptPath overlap barriers (a submerged
     *  below-node is a hard dive barrier; a pending break/place edge stops the scan). Reports the projected
     *  segment, cumulative XZ arc-length s (from path[0]), horizontal perpendicular distance, and the tangent
     *  heading at s+lookahead — and counts backward-snap events (ds&lt;-0.5). DRIVES NOTHING; this only proves
     *  (via replay) that s is monotonic and the projected segment tracks the live {@code step} on clean runs,
     *  the precondition for Phase 1 driving the step pointer off the projection. */
    void arcShadowTick(WorldView world, BlockPos foot, double px, double pz, float liveYaw, boolean inW, boolean hCol) {
        arc.proj.compute(path, edges, world, step, foot.getY(), px, pz, 16, 2.5);
        // Monotonic / backward-snap check vs the previous tick (reset on a fresh path object).
        boolean reset = (path != arc.shadowPath);
        double ds = (reset || Double.isNaN(arc.shadowS)) ? 0.0 : arc.proj.s - arc.shadowS;
        if (!reset && ds < -0.5) arc.monoViol++;
        // Phase-3 arc-wedge accumulator: a grounded DRY forward-RAM that makes no arc progress. Excluded when the
        // forward window is blocked by a pending vertical edge (arc.proj.barrierHit = a legit pillar/bridge/parkour
        // hold where zero ds is expected) or on a fresh path. The horizontalCollision gate keeps it off deliberate
        // non-ramming pauses (brakes, goal-dwell). EXCLUDES water: in water |ds|≈0 while hCol is the NORMAL state of
        // a swim-into-bank / climb-out, which owns its own dedicated recovery (waterCellTax / swimBankClimb / dig);
        // firing the wedge there repath-storms the climb-out and resets its aim every reroute (live -665 water-gap:
        // 7× arc-wedge at step 1 in water, dYaw stuck 95°, 频繁回头/横跳). The targeted ram is the DRY mountain
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
            if (arc.progStall && BotConfig.walkerDebug)
                LOG.info("[walker] arc-progress-wedge: net s={} < {} over {} ticks at step={} node={} → fellOffPath",
                        String.format("%.2f", arc.proj.s - arc.progBaseS), ARC_PROG_MIN, ARC_PROG_WINDOW, step,
                        (step < path.size() ? path.get(step) : null));
            arc.progBaseS = arc.proj.s;
            arc.progWindowTicks = 0;
        }
        arc.shadowPath = path;
        arc.shadowS = arc.proj.s;
        float dYaw = liveYaw - arc.proj.tangentYaw;    // wrap to [-180,180] for the camera-vs-tangent gap
        while (dYaw > 180) dYaw -= 360;
        while (dYaw < -180) dYaw += 360;
        if (BotConfig.walkerDebug)
            LOG.info("[walker] arc-shadow step={} projSeg={} segFrac={} s={} ds={} perp={} tanYaw={} liveYaw={} dYaw={} monoViol={} inW={}",
                    step, arc.proj.segIdx, String.format("%.2f", arc.proj.segFrac), String.format("%.2f", arc.proj.s),
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
        // Sustained wall collision → the far carrot is steering the body at a gap only
        // the LOS ray fits (jungle trunks). Collapse pursuit to the immediate node —
        // the A* chain is body-walkable by construction (walkerCarrotHColShrink).
        if (BotConfig.walkerCarrotHColShrink && churn.hColRamTicks >= 8) remaining = 0.01;
        double cx = px, cz = pz, tx = px, tz = pz;
        for (int i = step; i < path.size() && i - step <= CARROT_MAX_NODES; i++) {
            BlockPos node = path.get(i);
            double nx = node.getX() + 0.5, nz = node.getZ() + 0.5;
            if (hasPendingEdge(world, edgeAt(i))) return new double[]{nx, nz}; // face the action cell
            boolean losOk = BotConfig.walkerCarrotBodyLos
                    ? PathSmoothing.losWalkableBody(world, foot, node)
                    : losWalkable(world, foot, node);
            if (!losOk) break;                                                 // don't aim past a wall
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

