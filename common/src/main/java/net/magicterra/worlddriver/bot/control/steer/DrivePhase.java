package net.magicterra.worlddriver.bot.control.steer;

import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CLIMB_PRESS_DEBOUNCE;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_AIR_LOW;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_AIR_OK;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_BANK_DIST_SQ;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_CONFIRM_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_COOLDOWN_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_DIP_MAX_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_LOOKAHEAD;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.CRUISE_SINK_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.DEEP_WATER_DRIFT_LATCH;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.DIG_KEY_RELEASE_TICKS;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.FINAL_APPROACH_WALK_SQ;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.STEEP_DESCENT_DRIFT_LATCH;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.STEEP_DESCENT_LOOKAHEAD_NODES;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.WATER_DRIVE_MAX_REJECT;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.WATER_DRIVE_MAX_TURN;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.angleDiff;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.dropAdjacentExceeds;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.lethalDropAdjacent;
import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.hasPendingEdge;
import static net.magicterra.worlddriver.config.Mod.LOG;

import java.util.Locale;
import net.magicterra.worlddriver.bot.control.AscendMovement;
import net.magicterra.worlddriver.bot.control.MovementContext;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorGeometry;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.bot.control.TickCtx;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.control.act.Safety;
import net.magicterra.worlddriver.bot.control.controller.Digging;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.rules.ElytraPhysics;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.config.RulesConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.game.observe.CellText;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 4182–5290): final key assembly: step-up /
 * freezes / brakes / swim gates -> forward, sprint, jump, sneak, strafe. One phase of the per-tick pipeline — see
 * {@link Navigator#tickInner} for the driver and {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED
 * from the original tick body except for the {@code w.}/{@code Navigator.} member prefixes; do not restructure here
 * without live/testkit evidence (this file is state-machine surgery).
 *
 * <h2>Why the lethal-edge sprint exemption is {@code parkourEdge}, not {@code parkourAscend}</h2>
 *
 * <p>Two gates in {@link #run} carry a {@code parkourEdge} term whose reason is one measurement, so it is written here
 * once rather than twice inline (and here rather than inline at all, because {@code run} is on the source-budget
 * grandfather list and may only shrink):
 *
 * <ul>
 *   <li>the sprint expression's {@code (!lethalNear || parkourEdge)} term, and
 *   <li>the dynamic brake block's {@code && !parkourEdge} exclusion.
 * </ul>
 *
 * <p>{@code lethalNear} asks {@code lethalDropAdjacent(world, p, foot)} — does ANY of the FOOT cell's eight horizontal
 * neighbours drop further than {@code survivableFall}. On the rim of a pad over void that is true on the lip cell and
 * false one cell back, so the sprint channel closes or opens purely on <b>which cell the bot happens to launch
 * from</b>. Two scenes with identical geometry and different run-up lengths measured exactly that split:
 *
 * <pre>
 * wd.parkourVoidShortRunway  takeoff.x=223905.02  (lip=223904 ⇒ launched from BEHIND the lip)
 *     t+0 sprinting=true h=0.1232 → t+1 sprinting=true  h=0.2475    (+0.124: the impulse fired)
 * wd.parkourVoidLongRunway   takeoff.x=224416.93  (lip=224416 ⇒ launched ON the lip)
 *     t+0 sprinting=true h=0.1563 → t+1 sprinting=false h=0.1400    (x0.896: plain air decay, no impulse)
 * </pre>
 *
 * <p>The only difference between the arms is {@code sprinting} on the takeoff tick, and the long arm fell into the gap.
 * A fuller run-up makes the bot MORE likely to stand on the lip, so under the old gate a run-up was actively harmful —
 * the exemption has to key on the leap, not on the cell.
 *
 * <p>It keys on {@code parkourEdge} and not on {@code parkourAscend} ({@code = parkourEdge && wp.y > foot.y}) because a
 * FLAT leap is identically {@code false} there: the old term said "a jump onto a shallow ledge needs the impulse, a
 * jump over an abyss does not", and it is only over an abyss that {@code lethalNear} is true at all. Narrowed to
 * {@code parkourEdge} rather than loosened to a blanket {@code !lethalNear} drop, because the non-parkour half of
 * {@code lethalNear} is what {@code wd.bridgeLethalGapStop} guards — a walk-off lip must still lose its sprint.
 *
 * <p>The brake exclusion is the same launch tick seen from the other side: {@code edgeBrake} on the lip makes
 * {@code bridgeBrake} hold sneak through the takeoff, and a crouching bot's input is scaled by {@code SNEAKING_SPEED}
 * (0.3) from that same tick — {@code LocalPlayer.aiStep} on the client, {@code BotBody.aiStep} on the server — so the
 * leap gets 30% of its control input. Sprint alone does not clear the gap while sneak is throttling it. Sibling
 * {@code parkourEdge} exclusions already exist in this file on the lane-keep strafe and on
 * {@code descentAirborneDriftClamp}; the brake block was the one that was missing.
 */
public final class DrivePhase {
    /** {@link #segmentLaneOffset} when no lane is held; shared so the per-tick call allocates nothing. */
    private static final double[] NO_LANE = new double[0];

    /** Two-decimal number format for log lines. */
    private static final String FMT2 = "%.2f";

    /** Zero-decimal number format for log lines. */
    private static final String FMT0 = "%.0f";

    /** Move name of a cardinal one-block step up. */
    private static final String MOVE_STEP_UP = "stepUp";

    /**
     * How far out {@link #wiggleHop} measures the nearest lethal drop. Beyond {@link NavigatorGeometry#HOP_RANGE} on
     * purpose: the point of the reading is to show whether the gate's radius is SHORTER than the throw it guards
     * against, and a scan that stops at the gate's own radius can only ever answer "clean", which is the answer already
     * in doubt.
     */
    private static final int WIGGLE_SCAN_MAX = 4;

    /**
     * Recovery-hop events logged per navigator before the latch goes quiet.
     *
     * <p>Sixteen, not four. The budget is per NAVIGATOR INSTANCE — {@code wiggleEvents} is an instance field with no
     * reset, and every process carries its own {@code new Navigator(…)} — so it is spent per settle, not per run. That
     * is not as generous as it sounds, because a single stall episode burns it: on 2026-08-26 rung 12's bot stalled on
     * a lava lake's rim, spent all four inside thirteen seconds, and printed seq=5+/4 for the hops that mattered — the
     * ones between the last logged position and the bot's death in the lake. A cap that runs out inside the one stall
     * worth reading is not a hose guard, it is a blind spot with a budget. Sixteen is still bounded — the line is one
     * per ENTRY to a 7-tick window, not one per tick — and the seq=N+/16 line still says when even that was not enough.
     * Measured: one rehearsal stall printed six consecutive suppressed hops (ring 2, gate=true, jump=false), of which a
     * cap of four would have shown four.
     */
    private static final int WIGGLE_EVENTS = 16;

    private static final double LANE_KP = 3.0;

    private static final double LANE_KD = 8.0;

    private static final double BACKOFF_RUNWAY = 1.8; // how far 12 ticks of walking back carries the bot, with margin

    private static final double RISER_SCAN = 1.6; // farther than any lead a sprint launch needs (~1.05)

    /** How close to a step's centre an unsprinted climb onto it jumps: Baritone's MovementAscend distance. */
    private static final double STOP_ON_STEP_JUMP_DIST = 1.2;

    private static final String[] SPRINT_VETO_NAMES = {
        "bridge",
        "offFall",
        "offWaterFall",
        "diagAscent",
        "finalApproach",
        "lowHp",
        "hazard",
        "descendBrake",
        "lethalNear",
        "steepDescent",
        "deepWaterDrift",
        "stepJump",
        "water",
        "dipLeap",
        "padLeap"
    };

    /**
     * Horizontal speed a parkour launch needs over the void. Walking is ~0.13 and a sprint ~0.28; this is below a walk
     * on purpose — the rule is meant to catch a standstill, not to referee how fast is fast enough, and a bar set at
     * sprint speed would refuse leaps that work.
     */
    private static final double PARKOUR_MIN_TAKEOFF_SPEED = 0.08;

    /** Below this horizontal speed a bot on a leap's launch cell is not running at the lip, and jumps where it is. */
    private static final double LIP_HOLD_MIN_SPEED = 0.1;

    /**
     * Horizontal speed from which a body counts as running: a one-block drop across a one-block gap is run off rather
     * than jumped, and a short leap onto a pad goes unsprinted.
     */
    private static final double RUN_OFF_MIN_SPEED = 0.2;

    /** How many cells past its landing a sprinted run off a ledge into water can carry the body. */
    private static final int POOL_RUN_OFF_REACH = 3;

    /** How far under a ledge's top the feet of a body in water may be for the water to lift it over. */
    private static final double BANK_LIFT_DEPTH = 0.6;

    /** How close to a bank's face a rising swimmer counts as at the face, where it climbs. */
    private static final double BANK_FACE_MARGIN = 0.05;

    /** Floor for a swimmer's speed when predicting how high it will be at a bank's face. */
    private static final double BANK_MIN_SPEED = 0.02;

    /** Vertical speed below which a body entering water is plunging in from a fall rather than stepping in. */
    private static final double CRUISE_PLUNGE_VY = -0.4;

    /** Height of a water source's surface within its cell. */
    private static final double WATER_SURFACE = 8.0 / 9.0;

    /** Share of its rise a swimmer keeps each tick with the jump let go. */
    private static final double SWIM_COAST_KEEP = 0.8;

    /** How far over a bank's top the feet should be when a rising swimmer reaches its face. */
    private static final double BANK_BREACH_CLEARANCE = 0.1;

    /**
     * The slowest a wader times its bob to a bank at: a twentieth of a block a tick is the bench's stall speed, and
     * this keeps a margin over it.
     */
    private static final double WADE_HOLD_MIN_SPEED = 0.07;

    /** How far from a bank's face a wader starts timing its bob to it: about one bob of wading. */
    private static final double WADE_HOLD_LOOK = 2.5;

    /** Vanilla's {@code jumpInLiquid}: the rise a held jump adds each tick in water. */
    private static final double WADE_BOB_LIFT = 0.04;

    /** Vanilla's sink in water for a body not sprinting: gravity over sixteen. */
    private static final double WADE_BOB_SINK = 0.005;

    /** Share of its vertical speed a player keeps each tick in the air. */
    private static final double AIR_DRAG_KEEP = 0.98;

    /** Steepest a swimmer rising under water looks up, in degrees. */
    private static final double SWIM_RISE_MAX_PITCH = 45.0;

    /** Shortest run a rising swimmer's gaze is measured over, so a node nearly overhead does not stand it on end. */
    private static final double SWIM_RISE_MIN_RUN = 0.5;

    /** How far a sprinting swimmer's rise coasts, in ticks of its vertical speed: 0.8 kept a tick, summed. */
    private static final double SWIM_RISE_COAST = 4.0;

    /** A sprinting body's ground drive on its take-off tick: sprint speed 0.13 times vanilla's 0.98 input scale. */
    private static final double LEAP_TAKEOFF_DRIVE = 0.13 * 0.98;

    /** How far short of a pool drop's lip a sprinting bot leaps: about one grounded sprint tick. */
    private static final double POOL_LEAP_LIP = 0.3;

    /** Below this grounded speed a sprinting bot is still getting going; a full sprint keeps about 0.15. */
    private static final double SPRINT_JUMP_START_SPEED = 0.12;

    /** How many ticks after leaving the water a slow bot may still sprint-jump to get going. */
    private static final int SPRINT_JUMP_START_AFTER_WATER = 20;

    /** How far, in degrees, the facing may be off the run for the sprint-jump's boost to go along it. */
    private static final double SPRINT_JUMP_START_YAW = 10.0;

    /** Half the body's width: a leap into a pool comes down to the ledge's height only this far past the lip. */
    private static final double POOL_LEAP_HALF_WIDTH = 0.3;

    /** How far off the launch's row a bot may stand and still leap along it. */
    private static final double POOL_LEAP_ACROSS = 0.3;

    /** Room a leap into a pool keeps between its predicted entry and the far side of the water. */
    private static final double POOL_LEAP_MARGIN = 1.0;

    /** Ticks of flight a leap into a pool is followed for; a fall longer than this is not leapt. */
    private static final int POOL_LEAP_MAX_TICKS = 40;

    /** How far past a pool drop's landing the water is counted; a leap carries well short of it. */
    private static final int POOL_LEAP_MAX_CELLS = 12;

    /** The body's height over its feet, and the 1.25 a jump lifts the feet, for the head room over a leap. */
    private static final int POOL_LEAP_HEAD_CELLS = 3;

    /** Horizontal speed a bot on a one-cell pillar must slow to before it leaps on. */
    private static final double PAD_SETTLE_SPEED = 0.12;

    /** How far short of the lip a held leap still jumps, past what the next tick covers. */
    private static final double LIP_HOLD_MARGIN = 0.05;

    private int wiggleEvents;

    /** One-shot latch for the "the cap swallowed an entry" line — see {@link #wiggleHop}. */
    private boolean wiggleCapped;

    /**
     * Monotone call counter for {@link #wiggleHop}'s clock-free adjacency latch, and the index of the last call that
     * was inside the stall window. See that method's javadoc.
     */
    private long wiggleCalls;

    private long wiggleLastCall = Long.MIN_VALUE / 4;

    /** The first parkour takeoff this walk ever made — see {@link #noteParkourTakeoff}. */
    public String parkourTakeoff;

    public boolean digDrivenThisTick;

    public boolean digKeyOwned; // a dig drove the avatar this tick / the dig latch is a navigator dig's (see

    // DIG_KEY_RELEASE_TICKS)
    public int digIdleTicks; // consecutive tick-ends with the latch owned and no dig driven

    public final AscendMovement ascendMovement =
            new AscendMovement(); // task#82 per-move machine (drives only when navigatorAscendMovement is ON)

    /**
     * Descent/water sprint-brake latches + drive debouncers (task#96 step B5), owned by DrivePhase.
     * {@link DriveLatches#reset()} clears only the two sprint-brake latches (exactly what both journey resets cleared);
     * the debounce counters self-manage per tick.
     */
    public final DriveLatches driveLatch = new DriveLatches();

    private int parkourSamples;

    /**
     * The stuck-wiggle recovery hop — and the one place that says why it did or did not fire.
     *
     * <p>Moved out of {@code DrivePhase}'s jump expression because the decision needed a number the expression threw
     * away. It began as {@code lethalDropWithinHopRange}, a boolean over Chebyshev ≤{@link NavigatorGeometry#HOP_RANGE}
     * of the LAUNCH cell; printing the RING instead of the boolean is what showed the radius (2) to be shorter than the
     * arc (measured 3.47), and printing the CELL is what made a bearing computable. <b>Those numbers are still inputs —
     * do not read the conclusion off the comment.</b>
     *
     * <p><b>The gate is no longer that ring.</b> It is {@link NavigatorGeometry#hopSuppressed}: the columns the arc
     * would come down in along the drive bearing. The stuck window is unchanged ({@code precond && stuckTicks > 10 &&
     * stuckTicks < 18}), so the scan still runs only inside it, exactly as the old short-circuit arranged; only the
     * predicate after it moved.
     *
     * <p>One line per EVENT, not per tick: the window is 7 ticks wide and a bot sits in it for runs of them, so a
     * per-tick line would be a hose. Entry to the window is the event. Capped at {@link #WIGGLE_EVENTS} so a bot that
     * stalls repeatedly still cannot flood a rehearsal log.
     *
     * <p><b>"Entry" is adjacency of CALLS, never of game time.</b> The gate was {@code getGameTime() - wiggleLastTick >
     * 1}, which cannot see anything in a scene whose whole method runs inside one server tick — a mine that ticks the
     * bot 800 times in one server task, the {@code wd.serverMine*} family, {@code wd.selfShaftDigUp}. There {@code now}
     * never moves, so after the first line the delta is 0 forever and one line is printed however many times the bot
     * entered the stall window. {@code wiggleCalls} is bumped on EVERY call, before the precondition, so {@code call -
     * wiggleLastCall > 1} means exactly "the immediately preceding call was not itself inside the window" — the same
     * event in a per-tick world (this is called at most once per navigator tick) and a working one in a one-tick world.
     * {@code seq=} is the ordinal within the cap, the only thing that orders lines whose {@code t=} is frozen; a
     * trailing {@code seq=5+/4 cap reached} line (and only that line) means a further entry was swallowed, so
     * {@code seq=4/4} alone still means "exactly four entries".
     *
     * <p><b>And the bearing, measurement only.</b> The ring answered "is the reach shorter than the throw" — yes, by
     * one — but the answer does NOT license widening it: one directed rehearsal caught both sides, ring 3 firing (a
     * ladder bot took that into a lava lake) and ring 2 holding through a three-minute stall. Direction is what
     * separates them, so {@link NavigatorGeometry#hopBearingRow} prints where the bot points against where the lethal
     * cell is. Nothing branches on it yet, by the same rule that turned this gate's boolean into a ring. ⚠️ That row
     * prints the CAMERA; the bot is pushed along {@code driveTargetYaw}, decoupled from it by design, so it is NOT the
     * gate. The gate is {@link NavigatorGeometry#hopSuppressed} — the arc's own landing columns along the DRIVE
     * bearing, which subsumes the ring test rather than joining it — and {@link NavigatorGeometry#hopLandingRow} prints
     * the cells it read.
     *
     * <p>Why this earns a line at all: rung 20's takeoff samples showed the bot already airborne with
     * {@code sinceLastJump=7}, and eliminating the jump terms that need a riser or water leaves {@code wiggle} as the
     * only one that can fire on a flat dry level walk. That elimination is REASONING, not a reading — the rehearsal log
     * has no per-tick navigator lines to check it against. This is the reading.
     */
    public boolean wiggleHop(
            Navigator nav, WorldView world, LivingEntity p, BlockPos foot, boolean precond, float driveYaw) {
        long call = ++wiggleCalls; // bumped BEFORE the precondition: adjacency must count skipped calls too
        if (!(precond && nav.stuckTicks > 10 && nav.stuckTicks < 18)) {
            return false;
        }
        BlockPos lethal = NavigatorGeometry.nearestLethalHopCell(world, p, foot, WIGGLE_SCAN_MAX);
        int ring = NavigatorGeometry.ringOf(foot, lethal);
        boolean gated = ControlConfig.navigatorRecoveryHopFloorGate
                && NavigatorGeometry.hopSuppressed(world, p, foot, driveYaw);
        if (!gated && call - wiggleLastCall > 1) {
            nav.tallies.recoveryHops++; // one per window entry, not per tick
        }
        if (wiggleEvents < WIGGLE_EVENTS && call - wiggleLastCall > 1) {
            wiggleEvents++;
            LOG.info(
                    "[navigator] recovery hop: seq={}/{} t={} stuck={} foot={} exact=({}) ringGateRadius={} nearestLethalRing={} gate={} jump={} {} | {}",
                    wiggleEvents,
                    WIGGLE_EVENTS,
                    p.level().getGameTime(),
                    nav.stuckTicks,
                    foot.toShortString(),
                    String.format(Locale.ROOT, "%.3f,%.3f,%.3f", p.getX(), p.getY(), p.getZ()),
                    NavigatorGeometry.HOP_RANGE,
                    ring < 0 ? ">" + WIGGLE_SCAN_MAX : String.valueOf(ring),
                    ControlConfig.navigatorRecoveryHopFloorGate,
                    !gated,
                    NavigatorGeometry.hopBearingRow(p, foot, lethal),
                    NavigatorGeometry.hopLandingRow(world, p, foot, driveYaw));
        } else if (wiggleEvents >= WIGGLE_EVENTS && !wiggleCapped && call - wiggleLastCall > 1) {
            // Same blind spot as the jump-source cap: "seq=4/4" is the last line both when the bot
            // entered the stall window exactly WIGGLE_EVENTS times and when it entered it forty
            // times. One line, at the first entry the cap actually swallowed.
            wiggleCapped = true;
            LOG.info(
                    "[navigator] recovery hop: seq={}+/{} cap reached, later events are not logged (printed once)",
                    WIGGLE_EVENTS + 1,
                    WIGGLE_EVENTS);
        }
        wiggleLastCall = call;
        return !gated;
    }

    /**
     * Whether a move is one of the ascents the migrated recovery gates handle: exactly the three they already test — a
     * plain stepUp, its break-carrying sibling stairUpBreak, and the diagonal diagUp. stepUp2 (+2, horse) and
     * parkourAscend* are excluded. A cheap string check, so the OFF delegation short-circuit costs nothing.
     */
    public static boolean isMigratedAscent(String move) {
        return MOVE_STEP_UP.equals(move) || "stairUpBreak".equals(move) || "diagUp".equals(move);
    }

    /** Release the two sprint brakes; the debouncers and the dig-key latch manage themselves. */
    public void reset(ResetScope scope) {
        driveLatch.reset();
        if (scope == ResetScope.GOAL) {
            driveLatch.ticksSinceWaterOrStart = 0; // a walk just begun is getting going, like one just out of water
        }
    }

    /**
     * Latch the FIRST parkour takeoff of this walk. The sprint decision is taken a tick before the jump, so
     * {@code isSprinting()} here is the state the leap actually launches with. Only the first is kept: a bot that has
     * already fallen keeps producing these.
     *
     * <p><b>The five samples are not guaranteed to be five CONSECUTIVE ticks.</b> This call sits in the drive tail,
     * which a dozen branches (dig, pillar, escape, step-up) return before reaching, so "t+0..t+4" means "the first five
     * ticks the drive got this far". A run of five with {@code onGround=false} therefore does NOT by itself distinguish
     * a bot that was standing and refused the jump from a bot that was already falling when the parkour edge became
     * current — the exact y and the sole area are what separate them, which is why both are printed.
     */
    public void noteParkourTakeoff(WorldView world, Body a, LivingEntity p, boolean jump, BlockPos foot) {
        if (parkourSamples >= 5) {
            return;
        }
        double h = Math.hypot(p.getDeltaMovement().x, p.getDeltaMovement().z);
        // The five samples arrive on five different ticks; the text so far is the previous samples joined.
        StringBuilder buf = new StringBuilder(256);
        if (parkourSamples != 0) {
            buf.append(parkourTakeoff);
        }
        long now = p.level().getGameTime();
        long lastJump = a.dbgLastJumpTick();
        buf.append(parkourSamples == 0 ? "" : " | ")
                // The SAMPLE index and the GAME TICK, because they are not the same thing: this
                // latch sits in the drive tail, which a dozen branches return before reaching, so
                // "t+2" is the third sample, not the third tick. Without the absolute tick the
                // phrase "N ticks before takeoff" has no meaning to argue over.
                .append("t+")
                .append(parkourSamples)
                .append('@')
                .append(now)
                // Ticks since the bot last actually EMITTED an impulse (not since one was asked
                // for). This is what separates a bot that jumped ITSELF off the platform — the
                // parkour edge became current mid-arc — from one that simply walked off the lip:
                // the first has a small number here, the second has "none".
                .append(" sinceLastJump=")
                .append(lastJump < 0 ? "none" : String.valueOf(now - lastJump))
                .append(" jump=")
                .append(jump)
                .append(" sprinting=")
                .append(p.isSprinting())
                .append(String.format(Locale.ROOT, " h=%.4f", h))
                // ASK THE ATTRIBUTE, do not extrapolate the curve. Sprint is ×1.3 through
                // Attributes.MOVEMENT_SPEED (verified in 1.21.1: SPEED_MODIFIER_SPRINTING = 0.3,
                // ADD_MULTIPLIED_TOTAL), so 0.13 here means the channel works and 0.1 means the
                // modifier never took. The expected steady-state cap is printed beside it so nobody
                // has to redo the arithmetic.
                .append(String.format(
                        Locale.ROOT,
                        " speedAttr=%.4f(expected steady-state cap %.4f)",
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED),
                        p.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                                * 2.1585))
                // THE QUANTITY THE JUMP BRANCHES ON (vanilla's gate, on both bots), kept as an
                // input rather than as truth: onGround IS vanilla's verticalCollisionBelow
                // (Entity.move assigns one from the other in a single statement), so it describes the
                // previous MOVE and is wrong in both directions about where the bot is.
                .append(" onGround=")
                .append(p.onGround())
                // THE SOLE READING the navigator's own footing guards use. Printed beside onGround
                // so a run says which of the two was lying, and printed with the EXACT y because
                // that is the only thing that tells a standing bot from a falling one when the
                // block coordinate below is the same for both (a bot falling from y=49.9 spends
                // three ticks inside block y=49, exactly like a bot resting on y=49.0).
                .append(String.format(
                        Locale.ROOT, " soleOnSolid=%.4f y=%.4f", NavigatorGeometry.soleOnSolid(world, p), p.getY()))
                .append(" foot=")
                .append(foot.toShortString());
        parkourSamples++;
        parkourTakeoff = buf.toString();
    }

    /**
     * The below-node jump suppression <b>without a wall to ram</b>.
     *
     * <p>{@link #run}'s existing term is {@code !(p.horizontalCollision && p.onGround() && wp.getY() < foot.getY())}:
     * it kills the futile overhang bob-jump, but it needs {@code horizontalCollision}. On an open rim there is nothing
     * to ram, so a bot that has drifted one block above its route still jumps at a node it is <b>already standing next
     * to</b>, and the launch's forward momentum carries it past the cell it was trying to step onto.
     *
     * <p>Measured on rung 14's corridor segment 5 — five leaps, and the split is clean: the two launched under one
     * block out both ended in lava (one missed by 4.12 and fell fourteen, one fell fifteen), the three launched over
     * two blocks out all survived. The table, and why this is inert on a genuine gap and on every ascend, is on
     * {@link ControlConfig#navigatorNoLaunchAtAdjacentBelowNode}.
     *
     * <p>Lives here rather than inline because {@code run} is on the source-budget grandfather list and may only shrink
     * — the same reason this class's other shared reasoning is up top.
     */
    private static boolean atAnAdjacentNodeBelow(LivingEntity p, BlockPos wp, BlockPos foot) {
        if (!ControlConfig.navigatorNoLaunchAtAdjacentBelowNode) {
            return false;
        }
        if (!p.onGround() || wp.getY() >= foot.getY()) {
            return false;
        }
        return Math.hypot(wp.getX() + 0.5 - p.getX(), wp.getZ() + 0.5 - p.getZ())
                < ControlConfig.navigatorNoLaunchWithin;
    }

    /**
     * The LAST node is spent by closing to within ~0.67 of its centre, and a sprinting bot turning onto it from a
     * diagonal passes wider than that (see AimPhase's last-node note). Walk the final two blocks; there is nothing
     * after them to carry momentum into. A best-effort segment's last node has its continuation after it, so it keeps
     * the sprint.
     */
    private boolean finalApproach(Navigator nav, LivingEntity p, BlockPos wp, boolean parkourEdge) {
        double dx = wp.getX() + 0.5 - p.getX();
        double dz = wp.getZ() + 0.5 - p.getZ();
        // A waypoint the body goes on past is not where it stops: walking in to it, the parkour benchmark course's run
        // dropped its sprint at every checkpoint (before the ladder at 1068,150,1000, 0.20 to 0.12 a tick).
        return ControlConfig.navigatorFinalNodeDirectAim
                && !(ControlConfig.navigatorSprintThroughWaypoints && nav.passThrough)
                && !p.isInWater()
                && !parkourEdge
                && !nav.route.seg.pathBestEffort
                && nav.route.step() == nav.route.path().size() - 1
                && dx * dx + dz * dz < FINAL_APPROACH_WALK_SQ;
    }

    /**
     * The surface sprint-swim cruise's verdict for this tick: {@code on} suppresses the surface jump and forces sprint,
     * {@code dip} sneaks the bot under (for the pose, then through the server-confirm hold), {@code swimming} is the
     * pose; swimming without dip holds the jump that keeps the prone eyes at the waterline.
     */
    record Cruise(boolean on, boolean dip, boolean swimming, boolean jump) {
        static final Cruise OFF = new Cruise(false, false, false, false);
    }

    /**
     * SURFACE SPRINT-SWIM CRUISE ({@link ControlConfig#navigatorSurfaceSprintSwim}). Vanilla accepts a sprint in water
     * only while the eyes are under ({@code LocalPlayer.aiStep}) and keeps it once the SWIMMING pose is on; the pose
     * itself starts only from sprint + eyes under ({@code Entity.updateSwimming}). The surface bob's held jump keeps
     * the head out, so the sprint the drive asks for every tick was cancelled before travel and the bot treaded at ~2
     * blocks/s. Over deep water with no bank within reach: release the jump and sneak-sink until the eyes go under,
     * hold sprint, keep sinking for CRUISE_CONFIRM_TICKS once the pose is on (the server's flag sync lags; see
     * NavigatorConstants), then hold the jump: vanilla's jumpInLiquid lifts the prone bot until the water over the feet
     * is shallower than 0.4, i.e. the eyes at the waterline. A breath latch bobs the bot up when the air still runs low
     * and rejoins once it has refilled; a dip that never gets the pose backs off for a while.
     */
    private Cruise surfaceCruise(
            Navigator nav,
            WorldView world,
            LivingEntity p,
            Body a,
            BlockPos foot,
            BlockPos wp,
            boolean diving,
            boolean diveUnderCap) {
        Cruise c = cruiseVerdict(nav, world, p, a, foot, wp, diving, diveUnderCap);
        world.signals().cruising =
                c.on(); // AutoSwim's drowning backstop yields to it (cleared at the start of each bot tick)
        return c;
    }

    /** The node the cruise reads: the one after {@code wp} when {@code wp} is straight above a cruising bot. */
    private BlockPos cruiseNode(Navigator nav, LivingEntity p, BlockPos wp) {
        double wdx = wp.getX() + 0.5 - p.getX();
        double wdz = wp.getZ() + 0.5 - p.getZ();
        // A node straight above a cruising bot is the swimUp a fresh plan puts over a sunk foot:
        // the pose surfaces on its own, so read the node after it. Uncommitted, it is a rise.
        if (wdx * wdx + wdz * wdz < 1.0
                && driveLatch.cruiseOn
                && nav.route.path() != null
                && nav.route.step() + 1 < nav.route.path().size()) {
            return nav.route.path().get(nav.route.step() + 1);
        }
        return wp;
    }

    /**
     * Whether the bot is at the surface of deep water with a free run, and the cruise may start or go on. When it may
     * not, the cruise latches are dropped.
     */
    private boolean cruiseWanted(
            Navigator nav,
            WorldView world,
            LivingEntity p,
            BlockPos foot,
            BlockPos wp,
            boolean diving,
            boolean diveUnderCap) {
        DriveLatches l = driveLatch;
        double wdx = wp.getX() + 0.5 - p.getX();
        double wdz = wp.getZ() + 0.5 - p.getZ();
        // A surface cell one step or more away, over deep water, nothing solid overhead. The dip
        // sinks the foot to two cells under the surface node before the eyes go under (standing
        // eye height 1.62 against a 0.89 surface), so an engaged cruise tolerates that.
        int dy = wp.getY() - foot.getY();
        boolean surfaceNode = p.isInWater()
                && !diving
                && !diveUnderCap
                && !p.onGround()
                && world.isWater(wp)
                && !world.isWater(wp.above())
                && dy >= -1
                && dy <= (l.cruiseOn ? 3 : 1)
                && wdx * wdx + wdz * wdz >= 1.0
                && (world.isWater(foot.below())
                        || (ControlConfig.navigatorCruiseOverShallowFloor && l.cruiseOn && world.isWater(foot)))
                && !world.isSolid(foot.offset(0, 2, 0))
                && !plunging(p, l);
        // Engage only from the surface (eyes out): a bot still rising from a sunken start keeps
        // its swim-up; once engaged the dip takes the eyes under on purpose.
        if (!ControlConfig.navigatorSurfaceSprintSwim
                || !surfaceNode
                || nav.route.path() == null
                || (!l.cruiseOn && p.isUnderWater())) {
            if (GameConfig.navigatorDebug && l.cruiseOn) {
                LOG.info(
                        "[navigator] surface cruise: off — node {} dy={} lat2={} inW={} onG={} diving={} deepBelow={} capped={} pos=({},{},{})",
                        wp.toShortString(),
                        dy,
                        String.format("%.1f", wdx * wdx + wdz * wdz),
                        p.isInWater(),
                        p.onGround(),
                        diving,
                        world.isWater(foot.below()),
                        world.isSolid(foot.offset(0, 2, 0)),
                        String.format(FMT2, p.getX()),
                        String.format(FMT2, p.getY()),
                        String.format(FMT2, p.getZ()));
            }
            l.cruiseDipTicks = 0;
            l.cruiseSwimTicks = 0;
            l.cruiseOn = false;
            return false;
        }
        return true;
    }

    /**
     * A body falling into the water from a height does not start a cruise: the plunge already takes the eyes under, and
     * the cruise's dip sneaked the bot on down to the floor, a cell deeper than a person who swims on from the splash
     * (swimming benchmark course, the leap into the pool at 1065,149,1100: the bank's face met at 148.6 against a
     * person's 148.9).
     */
    private static boolean plunging(LivingEntity p, DriveLatches l) {
        return ControlConfig.navigatorNoDipOnPlunge && !l.cruiseOn && p.getDeltaMovement().y < CRUISE_PLUNGE_VY;
    }

    /**
     * Whether a bank within {@code CRUISE_BANK_DIST_SQ} lies ahead. Only the first non-water node ahead is looked at,
     * on purpose.
     */
    private static boolean bankAhead(Navigator nav, WorldView world, LivingEntity p) {
        for (int i = nav.route.step(), n = Math.min(nav.route.path().size(), nav.route.step() + CRUISE_LOOKAHEAD);
                i < n;
                i++) {
            BlockPos node = nav.route.path().get(i);
            if (!world.isWater(node)) {
                double dx = node.getX() + 0.5 - p.getX();
                double dz = node.getZ() + 0.5 - p.getZ();
                return dx * dx + dz * dz < CRUISE_BANK_DIST_SQ;
            }
        }
        return false;
    }

    /** The cruise's ladder of exits after the gate: cooldown, breath, bank, then the dip and the lift. */
    private Cruise cruiseVerdict(
            Navigator nav,
            WorldView world,
            LivingEntity p,
            Body a,
            BlockPos foot,
            BlockPos wp,
            boolean diving,
            boolean diveUnderCap) {
        DriveLatches l = driveLatch;
        wp = cruiseNode(nav, p, wp);
        if (!cruiseWanted(nav, world, p, foot, wp, diving, diveUnderCap)) {
            return Cruise.OFF;
        }
        if (l.cruiseCooldown > 0) {
            l.cruiseCooldown--;
            l.cruiseOn = false;
            return Cruise.OFF;
        }
        if (p.getAirSupply() < CRUISE_AIR_LOW && !l.cruiseBreath) {
            l.cruiseBreath = true;
            if (GameConfig.navigatorDebug) {
                LOG.info("[navigator] surface cruise: air {} — bobbing up to breathe", p.getAirSupply());
            }
        } else if (p.getAirSupply() >= CRUISE_AIR_OK) {
            l.cruiseBreath = false;
        }
        if (l.cruiseBreath) {
            l.cruiseOn = false;
            return Cruise.OFF;
        }
        // A bank ahead: the climb-out needs the upright pose and the surface bob, so hand over early.
        if (bankAhead(nav, world, p)) {
            l.cruiseOn = false;
            return Cruise.OFF;
        }
        l.cruiseOn = true;
        boolean swimming = p.isSwimming();
        if (GameConfig.navigatorDebug && (swimming != (l.cruiseSwimTicks > 0) || p.tickCount % 20 == 0)) {
            LOG.info(
                    "[navigator] surface cruise: {} step={}/{} seg={} y={} vy={} pitch={} yaw={} sprint={} eyesUnder={} air={} dip={} swimT={} pose={} inW={} onG={} fwd={} sneak={} jump={} food={} using={} drive[{}]",
                    swimming ? "swimming" : "dipping",
                    nav.route.step(),
                    nav.route.path().size(),
                    nav.progress.arc.proj.segIdx,
                    String.format(FMT2, p.getY()),
                    String.format("%.3f", p.getDeltaMovement().y),
                    String.format(FMT0, p.getXRot()),
                    String.format(FMT0, p.getYRot()),
                    p.isSprinting(),
                    p.isUnderWater(),
                    p.getAirSupply(),
                    l.cruiseDipTicks,
                    l.cruiseSwimTicks,
                    p.getPose(),
                    p.isInWater(),
                    p.onGround(),
                    a.dbgForwardImpulse(),
                    a.dbgSneak(),
                    a.dbgJumping(),
                    a.asPlayer() == null ? -1 : a.asPlayer().getFoodData().getFoodLevel(),
                    p.isUsingItem(),
                    nav.driveTag);
        }
        if (swimming) {
            l.cruiseDipTicks = 0;
            l.cruiseSwimTicks++;
        } else if (++l.cruiseDipTicks > CRUISE_DIP_MAX_TICKS) {
            // The bot would not go under, or the pose was refused: bob as before for a while.
            l.cruiseDipTicks = 0;
            l.cruiseCooldown = CRUISE_COOLDOWN_TICKS;
            l.cruiseOn = false;
            if (GameConfig.navigatorDebug) {
                LOG.info("[navigator] surface cruise: no swim pose after the dip — backing off");
            }
            return Cruise.OFF;
        }
        if (!swimming) {
            l.cruiseSwimTicks = 0;
        }
        // Sneak a few ticks past the pose (deep enough for standing eyes to stay under), hover through the
        // server-confirm window, then pulse the jump up to the waterline: a held jump (jumpInLiquid +0.04/tick
        // against the sprint's 0.9 drag) launches the bot clear out of the water, while a pulse coasts ~9× its
        // vy. Deep under, pulse whenever the rise is slow; within the last 0.3 block, only from a standstill,
        // so the coast ends with the prone eyes (0.4) just above the source block's 8/9 surface.
        double eyeGap = wp.getY() + 0.889 - (p.getY() + 0.4);
        double vy = p.getDeltaMovement().y;
        boolean lift = swimming
                && l.cruiseSwimTicks > CRUISE_CONFIRM_TICKS
                && p.isUnderWater()
                && (eyeGap > 0.3 ? vy < 0.05 : vy <= 0.005);
        return new Cruise(true, !swimming || l.cruiseSwimTicks <= CRUISE_SINK_TICKS, swimming, lift);
    }

    /**
     * Offset from the body to the nearest point of the planned segment into {@code wp}, or null when there is no reason
     * to steer back onto it. Drifted off a string-pulled segment, foot→wp reads diagonal and no lane is held, but the
     * segment is the line that was checked clear: pinned on a face (live 1536.73,72,-189.70: grazing the column beside
     * lane x=1537 by 0.03 of a block, 2.5 s of stall and anti-stuck jumps), or with
     * {@link ControlConfig#navigatorDescentLaneKeep} walking a one-block step-down, where the column beside a one-wide
     * stair is the drop (live 1512.7,76,-240.5: the heading lagged west of the x=1513 stair and the body walked off a
     * 5-block ridge, most runs).
     */
    // An empty array (NO_LANE) means "no lane held this tick".
    private double[] segmentLaneOffset(Navigator nav, LivingEntity p, BlockPos wp, boolean stepDown) {
        if (!(p.horizontalCollision || stepDown && ControlConfig.navigatorDescentLaneKeep) || nav.route.step() <= 0) {
            return NO_LANE;
        }
        BlockPos sp = nav.route.path().get(nav.route.step() - 1);
        if (stepDown && sp.getX() != wp.getX() && sp.getZ() != wp.getZ()) {
            return NO_LANE;
        }
        double ax = sp.getX() + 0.5;
        double az = sp.getZ() + 0.5;
        double sx = wp.getX() + 0.5 - ax;
        double sz = wp.getZ() + 0.5 - az;
        double t = Mth.clamp(((p.getX() - ax) * sx + (p.getZ() - az) * sz) / Math.max(1e-6, sx * sx + sz * sz), 0, 1);
        return new double[] {ax + sx * t - p.getX(), az + sz * t - p.getZ()};
    }

    /**
     * The lane-keep strafe impulse (left positive). In water it stays bang-bang; on dry ground (on a dry cardinal climb
     * only, without {@code navigatorProportionalLaneKeep}) it is proportional with velocity damping. A step cut into a
     * wall is a one-wide notch that the 0.6 box only fits within ±0.2 of the column centre, and full strafe until the
     * error fell under the dead band overshot by ~0.4 each way, so every jump clipped a neighbour column (live
     * 2026-09-28, 1390,99,-534: x swung 1390.07↔1390.91 for ~10 s). On a flat walk the same overshoot flipped the
     * strafe every three ticks, so the body zigzagged ±10° about its heading the whole way (the cross-country benchmark
     * course, 1491,63,-436).
     *
     * <p>Error and velocity are the lane-keep's own lateral offset ({@code latX}, {@code latZ}) and the velocity along
     * it, not the offset to the node centre: with the heading a few degrees off the lane, the distance still to run
     * along it leaked into the player's right and could strafe against the bang side.
     */
    private static double laneStrafe(
            LivingEntity p, double latX, double latZ, boolean strafeL, boolean strafeR, boolean proportional) {
        double bang = strafeL ? 1.0 : (strafeR ? -1.0 : 0.0);
        if (!proportional || bang == 0.0) {
            return bang;
        }
        double yr = Math.toRadians(p.getYRot());
        double rx = -Math.cos(yr);
        double rz = -Math.sin(yr); // the player's right, as in the lane-keep above
        double errR = latX * rx + latZ * rz;
        Vec3 v = p.getDeltaMovement();
        double velR = (latX == 0 ? 0 : v.x) * rx + (latZ == 0 ? 0 : v.z) * rz;
        return -Mth.clamp(LANE_KP * errR - LANE_KD * velR, -1.0, 1.0);
    }

    /**
     * True when the route goes on past the step {@code wp} in the direction the bot climbs onto it, so the run-up a
     * sprinted step-up carries is spent on the route. A sprint-jump onto a +1 step carries about two and a half blocks:
     * when the next node turns off that line, the bot flies past the step it was meant to stop on (cross-country's
     * cliff: a {@code stepUp} to 1441,93,-552 followed by a turn west landed at -550.6 and walked off the far side to y
     * 89).
     */
    private static boolean runsOnPastStep(Navigator nav, BlockPos foot, BlockPos wp) {
        int i = nav.route.step() + 1;
        if (nav.route.path() == null || i >= nav.route.path().size()) {
            return false;
        }
        BlockPos next = nav.route.path().get(i);
        int dx = Integer.signum(wp.getX() - foot.getX());
        int dz = Integer.signum(wp.getZ() - foot.getZ());
        return Integer.signum(next.getX() - wp.getX()) == dx && Integer.signum(next.getZ() - wp.getZ()) == dz;
    }

    /**
     * True when the runway the step-up backoff would retreat over has floor at the bot's level. On a one-wide cliff
     * staircase the cell behind is the drop the bot just climbed out of, so a blind 12-tick retreat walked it off the
     * stair (live 2026-09-28, twice at 1390,99,-534: fell 3 blocks, then pillar-recovered, ~12 s each time).
     */
    private static boolean backoffRunwayFloored(WorldView world, LivingEntity p, BlockPos wp) {
        double bx = p.getX() - (wp.getX() + 0.5);
        double bz = p.getZ() - (wp.getZ() + 0.5);
        double len = Math.hypot(bx, bz);
        if (len < 1e-3) {
            return false;
        }
        for (double s = 0.6; s <= BACKOFF_RUNWAY; s += 0.6) {
            BlockPos cell = BlockPos.containing(p.getX() + bx / len * s, p.getY(), p.getZ() + bz / len * s);
            if (!world.canStandOn(cell.below())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The block navigatorWallDigFallback punches: the wp-facing one at head then feet height. §85: that probe goes
     * EMPTY-HANDED when the pinning wall's normal is not the wp direction (ultra#2: wall south, wp east; C98-J2 canopy
     * pin: wp a stepDown below, hCol from a side trunk) — hCol says "a wall touches the box" but not WHERE. Fall back
     * to the drive heading, then sweep the four neighbours at head/feet height.
     *
     * <p>Never the block the next node stands on, nor a feet-height block while that node is above the feet: that is
     * the riser, and digging it turned a stepUp into a pillar the bot then fell off (live 1444,80,-553 on the
     * cross-country course's east cliff, four minutes of dig-pillar-fall).
     */
    private static BlockPos wallDigTarget(WorldView world, LivingEntity p, BlockPos wp, double ux, double uz) {
        BlockPos support = wp.below();
        boolean riser = wp.getY() > Mth.floor(p.getY() + 1e-3);
        java.util.function.Predicate<BlockPos> ok = c -> world.isSolid(c) && !c.equals(support);
        double[][] dirs = {
            {ux, uz},
            {-Math.sin(Math.toRadians(p.getYRot())), Math.cos(Math.toRadians(p.getYRot()))},
            {1, 0},
            {-1, 0},
            {0, 1},
            {0, -1}
        };
        for (double[] d : dirs) {
            BlockPos head = BlockPos.containing(p.getX() + d[0], p.getY() + 1.4, p.getZ() + d[1]);
            BlockPos feet = BlockPos.containing(p.getX() + d[0], p.getY() + 0.4, p.getZ() + d[1]);
            if (ok.test(head)) {
                return head;
            }
            if (!riser && ok.test(feet)) {
                return feet;
            }
        }
        return null;
    }

    /**
     * Runs the phase for one tick. Returns the step that ends the tick (propagated by the driver), or null to fall
     * through.
     */
    // The stages of one tick share dozens of locals; splitting would hoist them into fields to carry values between
    // the helpers, which is worse than one long method.
    @SuppressWarnings("checkstyle:javancss")
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.player;
        BlockPos foot = cx.frame.foot;
        boolean breakingEdge = cx.stall.breakingEdge;
        Move.Edge edge = cx.edges.edge;
        BlockPos wp = cx.edges.wp;
        boolean parkourEdge = cx.edges.parkourEdge;
        final boolean steppingOffWaterFall = cx.edges.steppingOffWaterFall;
        final boolean aimAtWaypoint = cx.aim.aimAtWaypoint;
        final boolean reCentre = cx.aim.reCentre;
        final String aimSrc = cx.aim.aimSrc;
        final float descentNodeYaw = cx.aim.descentNodeYaw;
        final boolean dryDescent = cx.aim.dryDescent;
        final boolean flatWaterTrend = cx.aim.flatWaterTrend;
        final boolean trendCam = cx.aim.trendCam;
        final float aimYaw = cx.aim.aimYaw;
        final boolean diveUnderCap = cx.aim.diveUnderCap;
        final boolean diving = cx.aim.diving;
        final double stepColDx = cx.aim.stepColDx;
        final double stepColDz = cx.aim.stepColDz;
        final boolean stepUpFreeze = cx.aim.stepUpFreeze;
        final boolean pivotForStepUp = cx.aim.pivotForStepUp;
        boolean descendBrake = cx.aim.descendBrake;
        // ---- original body (byte-identical modulo member prefixes) ----
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the bot clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
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
        // A DIAGONAL +1 step (BOTH axes change AND rising). The lane-keep strafe below
        // only centres cardinal lanes / waterClimb, so a diagUp got NO lateral
        // correction: the bot drifts off the diagonal, ends aligned on one axis but
        // ~0.8 off on the other, rams the perpendicular riser — and because the dry-land
        // aim points at the CLOSE waypoint (whose bearing swings on any sub-block drift)
        // stepHeadingErr never clears, so pivotForStepUp cuts forward FOREVER and the bot
        // FREEZES at the riser (live 2026-06-15: cur2=0.640 constant 300+ ticks ≈ 19 s,
        // hCol=true, until an anti-stuck burst routed around). Centre it on the target
        // column on BOTH axes (the strafe still runs while pivot cuts forward), so its
        // cross-axis component pulls the bot back onto the diagonal line, the bearing
        // steadies, pivotForStepUp releases, and forward + stepUpJump mounts the corner.
        boolean diagUp =
                upDy >= 1 && wp.getX() != foot.getX() && wp.getZ() != foot.getZ() && !parkourEdge && !p.isInWater();
        // A jump is needed only to rise BEYOND the auto-step height.
        boolean needJumpForStep = upDy >= 1 && upDy > maxStepUp;
        // task#82 per-move ASCENT machine (plan B1 2026-07-16). Flag FIRST so OFF short-circuits with
        // no allocation. isMigratedAscent is a cheap string check; !isInWater keeps water ascents on
        // legacy dig-recovery. B1 weave: the machine NEVER early-returns — PREP/RUNNING/SUCCESS fall
        // through so the legacy jump-timing + drive below stays the single actuation source (an early
        // return here skips the shared drive and forces a re-implementation — the disproven Option-A
        // trap). The machine owns only the per-edge episode + dead-zone watchdog; step advancement
        // stays with the legacy advance loop (~line 2265) so there is no double-advance race.
        if (ControlConfig.navigatorAscendMovement && edge != null && isMigratedAscent(edge.move) && !p.isInWater()) {
            MovementContext ctx = new MovementContext(
                    p, world, a, edge, foot, nav.route.path().get(nav.route.step()), breakingEdge);
            switch (ascendMovement.updateState(ctx)) {
                case UNREACHABLE,
                        FAILED -> { // fold into the existing re-route (consumed next tick by StallDetectPhase)
                    TickStep give = noteDeadZone(nav, p, foot, edge);
                    if (give != null) {
                        return give;
                    }
                }
                case PREP, RUNNING, SUCCESS -> {} // fall through — legacy drive actuates this tick
                default -> {}
            }
        }
        // Baritone MovementAscend jump-timing applies whenever we actually JUMP a
        // cardinal step within reach (+1, or +2 for a horse/jump-boost) — align +
        // approach before the jump. A horse auto-walk-up needs no jump; water/parkour
        // differ; an over-jump (handled above) is excluded.
        boolean dryStepUp = needJumpForStep && cardinalUp && upDy <= maxJumpUp && !p.isInWater() && !parkourEdge;
        boolean ascendJumpReady = true;
        boolean sprintAscend = false;
        if (dryStepUp) {
            int alongX = wp.getX() == foot.getX() ? 0 : 1;
            int alongZ = wp.getZ() == foot.getZ() ? 0 : 1;
            double flatDist =
                    alongX * Math.abs(wp.getX() + 0.5 - p.getX()) + alongZ * Math.abs(wp.getZ() + 0.5 - p.getZ());
            double sideDist =
                    alongZ * Math.abs(wp.getX() + 0.5 - p.getX()) + alongX * Math.abs(wp.getZ() + 0.5 - p.getZ());
            Vec3 vel = p.getDeltaMovement();
            double lateralMotion = alongX * vel.z + alongZ * vel.x;
            // SPRINT-BUNNY-HOP a cardinal +1 step (a smooth stair climb). A deterministic stair
            // arena showed a gentle staircase costs ~40% speed: each step drops
            // sprint + the in-place jump rams the riser + the bot re-accelerates from ~0.
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
            // Pressed on the riser and stalled, the strict square-up deadlocks: no room to strafe, offset just
            // over 0.2 (live 1401.24,87,-531.70: 0.26, wedged 12 s). A standing jump off the face still lands on it.
            boolean faceWedged = p.horizontalCollision && nav.stuckTicks > 4 && sideDist <= 0.35 && flatDist <= 1.0;
            boolean aligned = faceWedged || (Math.abs(lateralMotion) <= 0.1 && sideDist <= 0.2);
            boolean stopsOnStep = ControlConfig.navigatorStepUpStopsBeforeTurn && !runsOnPastStep(nav, foot, wp);
            sprintAscend = aligned && !stopsOnStep;
            ascendJumpReady = aligned && flatDist <= (stopsOnStep ? STOP_ON_STEP_JUMP_DIST : 1.7);
            // STEPUP BACKOFF-RETRY trigger (navigatorStepUpBackoffRetry, default OFF): the early
            // jump above assumes sprint momentum from the approach, but a slid-back mount
            // retries from a STANDING start pressed against the riser — a near-vertical hop
            // that grazes the lip and slides back forever (live node(14,58,102): ~1400 grind
            // ticks, hSpd 0.05, hCol=false). Grounded + stuck + pressed-close + momentum-less
            // → arm an 8-tick straight-back drive (executed early in tick()) to open runway,
            // then the normal approach re-launches WITH momentum. Cooldown guards a genuinely
            // unmountable riser from oscillating retreat (other recoveries take over).
            // Two grind signatures qualify: (a) GROUNDED standing-start press (onGround +
            // momentum-less), (b) the airborne RIM-GRAZE bob — the bot hangs against the
            // riser face (hCol=true every tick, onGround NEVER true, y bobbing ~0.5 wide;
            // replay-0016: p=(14.50,57.0-57.25,103.30) for 500+ ticks). Both need the same
            // cure: back off the face, ground, and re-approach with momentum.
            boolean grindPress = p.onGround() && Math.sqrt(vel.x * vel.x + vel.z * vel.z) < 0.1;
            boolean grindGraze = p.horizontalCollision && nav.stuckTicks > 30;
            if (ControlConfig.navigatorStepUpBackoffRetry
                    && nav.recovery.backOffReady(nav.stallDetect.escal.tick)
                    && nav.stuckTicks > 15
                    && flatDist < 1.1
                    && (grindPress || grindGraze)
                    && backoffRunwayFloored(world, p, wp)) {
                nav.recovery.backOff(
                        (float) (Math.toDegrees(Math.atan2(-(wp.getX() + 0.5 - p.getX()), wp.getZ() + 0.5 - p.getZ()))
                                + 180.0),
                        nav.stallDetect.escal.tick);
                if (GameConfig.navigatorDebug) {
                    LOG.info(
                            "[navigator] STEPUP-BACKOFF armed ({}): wp={},{},{} flatDist={} → back off 12t for runway",
                            grindPress ? "press" : "graze",
                            wp.getX(),
                            wp.getY(),
                            wp.getZ(),
                            String.format(Locale.ROOT, FMT2, flatDist));
                }
            }
        }
        // NOTE: extending the cardinal sprint-bunny-hop to DIAGONAL step-ups was tried + A/B-
        // DISPROVEN here (2026-06-20) on a 45° slope arena: forcing sprint on a diagonal
        // climb RAMS the riser CORNER (two perpendicular faces) — sprint% 48→94 dropped diagBps
        // 3.04→2.71 and raised hcol 7→12; jumping √2-earlier was worse still (2.95, hcol 22, and
        // it broke a diagonal dig-staircase up out of water). The partial-sprint 3.04 b/s (≈71%
        // of walk) baseline is the achievable limit under the corner-ram constraint; the camera
        // (yaw) is already steadied by the STAIR_TREND_LOOKAHEAD path-trend aim. Left cardinalUp-
        // only on purpose. Hill climbs are timed by the benchmark's cross-country course.
        // Climbing a +1 ledge OUT OF a water film: buoyancy drifts the bot off the
        // target column so the cardinal stepUp approach goes diagonal and forward
        // just grinds the ledge side (trace: inW, foot drifted to a diagonal of the
        // stepUp node, cur2 stuck at 1.27, bobbed 797 ticks → budget expiry, "never
        // left spawn"). The lane-keep strafe below is gated to flat walks
        // (wp.y==foot.y), so a vertical climb gets NO lateral correction. Treat a
        // water climb-out like a lane-keep but centre on BOTH axes toward the target
        // column so the bot sits under the ledge; the existing forward+jump then
        // mounts it (the aligned cardinal climb that already works on dry land).
        boolean waterClimb = p.isInWater() && wp.getY() > foot.getY();
        boolean stepDownLane = ControlConfig.navigatorDescentLaneKeep && wp.getY() == foot.getY() - 1 && !p.isInWater();
        // Lateral lane-keeping STRAFE: on a flat cardinal walk, hold the cross-axis
        // at the lane centre with a sideways strafe so the bot clears a flush 1-wide
        // channel wall WITHOUT turning off the forward heading. Pure yaw steering
        // can't do both (turning to centre kills forward progress, so the bot only
        // creeps and grinds the wall); strafing centres while forward still drives
        // it down the lane. Projects the cross-axis error onto the player's right
        // vector to pick the key. Skipped during leaps/brakes/bridging.
        boolean steppingOffFall = cx.edges.steppingOffFall;
        boolean strafeL = false;
        boolean strafeR = false;
        double laneLatX = 0;
        double laneLatZ = 0;
        if (!descendBrake
                && !parkourEdge
                && !steppingOffFall
                && (wp.getY() == foot.getY() || waterClimb || cardinalUp || diagUp || stepDownLane)) {
            int ddx = wp.getX() - foot.getX();
            int ddz = wp.getZ() - foot.getZ();
            double[] seg = ddx != 0 && ddz != 0 && (wp.getY() == foot.getY() || stepDownLane) && !waterClimb && !diagUp
                    ? segmentLaneOffset(nav, p, wp, stepDownLane)
                    : NO_LANE;
            double latX = 0;
            double latZ = 0;
            if (seg.length > 0) {
                latX = seg[0];
                latZ = seg[1];
            } else if (waterClimb) {
                latX = wp.getX() + 0.5 - p.getX();
                latZ = wp.getZ() + 0.5 - p.getZ();
            } // centre on the target column
            else if (diagUp) {
                latX = wp.getX() + 0.5 - p.getX();
                latZ = wp.getZ() + 0.5 - p.getZ();
            } // centre on the diagonal toward the step corner
            else if (ddx == 0 && ddz != 0) {
                latX = wp.getX() + 0.5 - p.getX(); // N/S lane → hold X
            } else if (ddz == 0 && ddx != 0) {
                latZ = wp.getZ() + 0.5 - p.getZ(); // E/W lane → hold Z
                // Anti-drift in a current: a flowing-water cell pushes the bot
                // downstream, so steer upstream (subtract the flow vector from the
                // lateral target). The strafe is far stronger than the push, so this
                // holds the crossing on the planned line PROACTIVELY instead of letting
                // the lane-keep only react after the bot has already drifted. Only the
                // cross-lane component survives the right-vector projection below; the
                // along-lane part is ignored (handled by the upstream COST, not steering).
            }
            if (WorldConfig.waterFlowPenalty > 0 && p.isInWater()) {
                Vec3 flow = world.waterFlow(foot);
                latX -= flow.x;
                latZ -= flow.z;
            }
            laneLatX = latX;
            laneLatZ = latZ;
            if (Math.abs(latX) > 0.06 || Math.abs(latZ) > 0.06) {
                double yr = Math.toRadians(p.getYRot());
                double fx = -Math.sin(yr);
                double fz = Math.cos(yr); // forward unit (x,z)
                double rx = -fz;
                double ry = fx; // player's right = forward rot +90°
                double dotR = latX * rx + latZ * ry;
                if (dotR > 0.04) {
                    strafeR = true;
                } else if (dotR < -0.04) {
                    strafeL = true;
                }
            }
        }
        // Camera-decoupled drive (see AvatarInput). Rotate the bot-frame movement intent
        // (forward + lane-keep strafe) from the desired travel heading (aimYaw) into the
        // camera frame by Δ = aimYaw − cameraYaw, so vanilla travel()'s rotate-by-yaw moves
        // the bot ALONG the heading even while the camera is still slewing toward it — the
        // bot no longer rams a wall waiting for the look to catch up (dynamic heading correction). At Δ=0
        // (camera caught up) the impulse equals the old keyed (dL,dF), so steady-state walking
        // is byte-identical; only the slew transient changes. Special branches above return
        // before here, so they keep their own key-based actuation (AvatarInput falls back to
        // keys uncommanded).
        //
        // The DRIVE always targets aimYaw — even while the camera is frozen by the anti-wind
        // spinFreeze. The freeze exists ONLY to stop the visual spinning (camera chasing a ~180°-
        // flipping target winds one way); it must NOT also freeze the bot's travel. The old
        // `spinFreeze ? 0` here drove the bot along the STALE frozen camera heading — in a
        // badlands water basin that heading pointed straight at the bank, so the bot rammed it
        // for 500+ ticks while a steady bearing pointed ~150° off (the 18-min near-deadlock).
        // Decoupling them lets the bot crab toward the waypoint (Δ = aimYaw − cameraYaw) and
        // make net progress, which resets repathsNoProgress and releases the freeze on its own,
        // so the vicious cycle (frozen drive → no progress → freeze stays frozen) can't form.
        // AIRBORNE forward-drift clamp (task#36, 2026-07-12, repurposed navigatorDescentStepSkipBrake,
        // isolable backup toggle to the sprint-kill above). When the steep-descent latch is armed
        // (set on the grounded tick before the launch, ~line 4443) and the bot is now airborne over
        // the descent, zero the forward drive so it drops onto the near tread instead of sailing off
        // the lip on driveF=1. Reads the latch FIELD (its previous-tick value — correct, the latch's
        // whole purpose is to persist across the airborne arc). Airborne-only: a grounded descent
        // keeps driveF=1 and gravity still drops the bot, so it can never stall/deadlock the descent
        // (the vanilla-sneak edge-pin the old grounded variant used COULD stall; this cannot). Sprint
        // is killed independently by steepDescentNear, so with this OFF the fix is arming-only.
        // EXCLUDE parkourEdge: descendBrake already zeros driveF for parkourDescend* leaps, but a
        // plain parkour* move that lands lower is parkourEdge yet NOT descendLeap — clamping its
        // takeoff drive would land it short into the gap (a NEW fall death inside this fix's blast
        // radius). The validated RED launches were plain walk-offs, never parkourEdge, so guarding
        // here cannot weaken the fix; it only spares deliberate gap-crossing leaps.
        // No wp-vs-foot term here: it flickers during the arc (see the latch release above) and
        // every flicker tick was one tick of full impulse at a bearing that swings ±90° when the
        // node is nearly underfoot. The latch alone decides; driveF=0 makes the bearing moot.
        // Not on a ladder or vine either: there the body slides at a clamped speed, and a bot letting go of one to step
        // off sideways (VineClimb.stepOffDue) needs the drive the clamp would take away.
        // A leap into a pool flies on its run: the clamp released the forward key in the air and the leap's 0.26 a tick
        // bled to 0.12 before the water (swimming benchmark course, off the ledge at 1061,155,1100).
        if (p.onGround() || p.isInWater()) {
            driveLatch.poolLeapAirborne = false;
        }
        boolean descentAirborneDriftClamp = ControlConfig.navigatorDescentStepSkipBrake
                && !p.onGround()
                && driveLatch.steepDescentLatch > 0
                && !driveLatch.poolLeapAirborne
                && !parkourEdge
                && !(ControlConfig.navigatorVineStepOff && p.onClimbable());
        final Float onward = flownPastLanding(nav, world, p, wp, parkourEdge);
        double driveF = descendBrake || pivotForStepUp || descentAirborneDriftClamp ? 0.0 : 1.0;
        double driveL = laneStrafe(
                p,
                laneLatX,
                laneLatZ,
                strafeL,
                strafeR,
                !p.isInWater() && (cardinalUp || ControlConfig.navigatorProportionalLaneKeep));
        // Drive heading: normally aimYaw (decoupled from the slewing camera). EXCEPTION —
        // a BUOYANT slope-mount. A floating bot at a +1/+2 bank top bobs UP to the bank
        // height but, with forward zeroed by pivotForStepUp (aim not yet aligned) and the
        // drive pointed at a CHURNING aimYaw, never translates ONTO the ledge — it falls
        // back and bob-stalls (live 2026-06-20 sand slope: py peaked 64.17 at the y64 bank
        // with z frozen at 3572.30, then the dig/anti-stuck burst kicked in, ~30 s of camera whipping).
        // The whole shoreline is a deep-water-base slope (planner can't route around it), so
        // the mount MUST be actuated: press forward HARD and STEADILY straight at the target
        // column (not the oscillating aimYaw), so vanilla's swim-step carries the bob peak up
        // the +1. Gated tight — floating, climbing (wp above foot), laterally ON the column —
        // so flat water travel and dry steps are byte-unchanged. (Special climb-out takeovers
        // — pillar / dig — return before here, so this only drives the plain-stepUp bob.)
        // Trend camera (dry descent OR flat water swim): the camera faces the far trend heading
        // (above) but the bot must still drive the IMMEDIATE node so it follows the path step by
        // step — keep movement on the captured node heading. In WATER this decouple is essential, not
        // just polish: coupling the drive to the far trend made the bot swim straight at the centroid
        // THROUGH a divider it had to round via a side gap, and ram it; driving the immediate node
        // rounds the corner while the camera trend still kills the head-shake.
        // navigatorTangentAim: drive the bot along the smoothed path tangent (aimYaw, which Edit A
        // above set to the tangent) in ALL cases — including water, where the legacy smoothWaterDriveYaw EMA
        // is a separate node-following heading that the tangent supersedes. The flip-rejection block below is
        // skipped when this is on (the tangent already never reverses, so there is no back-hop to reject).
        // NOTE (bridge battery r30/r31): do NOT swap the tangent-mode drive source off aimYaw. Driving the
        // captured pre-centroid heading (descentNodeYaw) was tried twice against the dogleg corner-cut —
        // unconditionally (r30: 9 regressions — slide-back pair, whole stop family, selfShaftDigUp,
        // the water divider rounded through a side gap) and guard-pin-scoped (r31: stop family drifted off the spine at
        // the lip
        // and spiralled into futile-FAILED@t137) — the EMA'd aimYaw drive is the suite's tuned
        // equilibrium. Corner-cut recovery is fixed on the AIM side instead: recoverySnagAim (Aim) drops
        // the trendCam centroid while a wall-pin OR guard-pin recovery owns the tree, so the corridor
        // bearing flows through the same EMA into this unchanged drive.
        // A leap drives at its landing node, whatever the camera trends at: on a diagonal leap the trend centroid of
        // the zigzag ahead pointed 45° off the landing, and the bot flew straight into the pit (parkour course,
        // 1039,150,1000 to 1041,150,998).
        float driveTargetYaw = onward != null
                ? onward
                : ControlConfig.navigatorLaunchDrivesLanding && parkourEdge
                        ? descentNodeYaw
                        : ControlConfig.navigatorTangentAim
                                ? aimYaw
                                : flatWaterTrend
                                        ? nav.aim.aimSmooth.smoothWaterDriveYaw
                                        : trendCam ? descentNodeYaw : aimYaw;
        // Dry diagDown SLOPE back-hop damping (the visible side-to-side hopping on snowy slopes). On a continuous
        // diagonal descent the decoupled drive rides the IMMEDIATE node (descentNodeYaw); each time
        // the foot overshoots that node it sits BEHIND the bot and the bearing reverses ~180°, so
        // the drive hops backward for a few ticks until `passed` advances the pointer. The discrete
        // overshoot-advance above is gated OFF for diagDown (eager-advancing a slope regressed
        // a steep diagonal descent's backSteps 42→71), and a plain heading-override was reverted because it had
        // no escape and self-amplified into a wedge. Port the WATER drive's flip-rejection+ESCAPE:
        // when the node bearing points sharply behind the steady trend (aimYaw, the look-ahead
        // centroid that already aims down-path), hold the trend so the bot keeps descending forward,
        // but SNAP back to the real node after WATER_DRIVE_MAX_REJECT ticks so node-following always
        // re-syncs (a true switchback segment sits ~±50° off trend < the 120° gate, so only a real
        // overshoot trips this). dryDescent-only; water/discrete keep their own handling.
        if (!ControlConfig.navigatorTangentAim
                && dryDescent
                && !flatWaterTrend
                && edge != null
                && edge.move != null
                // parkourDescend is a diagonal descender too: a parkourDescend staircase suffers the
                // SAME overshoot back-hop (live 2026-06-24 combined journey at -813,71: a
                // parkourDescend2d1 chain, yaw/driveYaw flipped +33→-103 on each overshoot → ~65 t
                // bob-jump zigzag). trendCam/aimYaw already give it the centroid trend (dryDescent==true);
                // only this flip-rejection gate excluded it, so the drive kept chasing the flipping node.
                // ...and fall / stepDown edges off a ledge: same overshoot back-hop. Live 2026-06-24
                // journey4 at -857,70 (move=fall4, bot overshot a stepUp up to y75 then the path falls
                // back to y71): FREEZE-DIAG showed dTgtYaw flip -148↔+32 with fwdComp +1↔-1 (the drive
                // ran FORWARD then BACKWARD = the "moving-backward jump", a slight retreat), while camYaw held
                // steady at -148 — i.e. the camera trend was fine, only the drive chased the flipping
                // below-node bearing. Holding the trend lets it commit forward off the edge and drop.
                && (edge.move.startsWith("diagDown")
                        || edge.move.startsWith("parkourDescend")
                        || edge.move.startsWith("fall")
                        || edge.move.startsWith("stepDown"))
                && Math.abs(angleDiff(aimYaw, descentNodeYaw)) > WATER_DRIVE_MAX_TURN) {
            if (++driveLatch.descentDriveRejectStreak <= WATER_DRIVE_MAX_REJECT) {
                driveTargetYaw = aimYaw;
            } else {
                driveLatch.descentDriveRejectStreak = 0; // escape: drive the real node this tick to re-sync
            }
        } else {
            driveLatch.descentDriveRejectStreak = 0;
        }
        boolean rawClimbPress = p.isInWater()
                && !p.onGround()
                && !parkourEdge
                && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) < 2.5;
        // Debounce the surface bob (see CLIMB_PRESS_DEBOUNCE): only a PERSISTENT wp-above-foot is a
        // real bank mount; a 1-tick down-bob under a same-level surface node is not.
        driveLatch.climbPressConsec = rawClimbPress ? driveLatch.climbPressConsec + 1 : 0;
        boolean buoyantClimbPress = rawClimbPress && driveLatch.climbPressConsec >= CLIMB_PRESS_DEBOUNCE;
        if (buoyantClimbPress) {
            driveF = 1.0;
            driveL = 0.0;
            driveTargetYaw = (float) Math.toDegrees(Math.atan2(-stepColDx, stepColDz));
        }
        final boolean climbWait = dugBankClimbWait(nav, world, p);
        final boolean digHold = digApproachHold(nav, world, p) || climbWait;
        if (digHold || wadeBankHold(world, p, wp)) {
            driveF = 0.0; // after the climb press, which would drive the body into the bank before it is dug
        }
        // A fall onto a ladder or vine cell lands only if the body is still in that column when it drops: a walk-off at
        // walking pace carried the bot a block past the ladder and it fell the whole wall instead (parkour course,
        // 3 HP off 1071,156,1000). Steer toward the column centre, slowing as the offset closes, from the lip on.
        double catchOff = Math.hypot(stepColDx, stepColDz);
        if (ControlConfig.navigatorCatchClimbableLanding
                && wp.getY() < foot.getY()
                && catchOff < 1.0
                && !p.onClimbable()
                && world.isClimbable(wp)) {
            driveF = Math.min(1.0, catchOff * 2.5);
            driveL = 0.0;
            driveTargetYaw = (float) Math.toDegrees(Math.atan2(-stepColDx, stepColDz));
        }
        // Near-goal DISK CLOSURE. On the final node of a radius>0 disk goal with the foot still
        // OUTSIDE the disk, the immediate drive bearing is the very-close final node — for a floating
        // bot that bearing collapses/bobs and the bot freezes a block short of the radius (live
        // 2026-06-23 XZ -1700,900 r6: pinned at foot dist 7, never closed). The disk CENTRE is still
        // 6-7 blocks away, so its bearing is STABLE (no collapse). Drive straight at the centre to
        // close the last fraction INTO the disk, where the goal.reached check at the top of step()
        // fires for real. radius>0 only (exact-cell / radius-0 water arrivals unchanged).
        if (nav.route.step() + 1 >= nav.route.path().size() && !nav.goal.reached(foot)) {
            int gcx = Integer.MIN_VALUE;
            int gcz = 0;
            if (nav.goal instanceof Goal.XZ xz && xz.radius() > 0) {
                gcx = xz.x();
                gcz = xz.z();
            } else if (nav.goal instanceof Goal.Near nr && nr.radius() > 0) {
                gcx = nr.target().getX();
                gcz = nr.target().getZ();
            }
            if (gcx != Integer.MIN_VALUE) {
                double gdx = gcx + 0.5 - p.getX();
                double gdz = gcz + 0.5 - p.getZ();
                if (gdx * gdx + gdz * gdz > 0.5) { // not already essentially on centre
                    driveTargetYaw = (float) Math.toDegrees(Math.atan2(-gdx, gdz));
                    driveF = 1.0;
                    driveL = 0.0;
                }
            }
        }
        double driveDelta = Math.toRadians(angleDiff(p.getYRot(), driveTargetYaw));
        double driveCos = Math.cos(driveDelta);
        double driveSin = Math.sin(driveDelta);
        double cmdStrafe = driveL * driveCos - driveF * driveSin;
        double cmdFwd = driveL * driveSin + driveF * driveCos;
        // CORNER-CLEARANCE repulsion (navigatorCornerClearance): pure-pursuit cuts corners by
        // design, and the 0.6-wide player then GRAZES a solid corner the carrot line passes
        // within half-width of — the flat §39 wedge (bridge bypass trio: side-step lane
        // past a barrier, the bot wedged at the barrier's west face corner z=0.91, churn
        // escapes floor-gated on the narrow deck → FAILED). PREVENT the graze instead of
        // escaping the wedge: for each solid bot-height cell whose closest face point is
        // within BOT half-width + a grazing pad of the bot centre, blend a small push
        // away. Self-limiting: cell-centred walking beside a wall sits at ≥0.5 (no push),
        // a 1-wide corridor pushes cancel, and the nudge is capped well under the drive
        // impulse so it bends the line rather than steering it.
        if (ControlConfig.navigatorCornerClearance && !p.isInWater() && (driveF != 0 || driveL != 0)) {
            double rpx = 0;
            double rpz = 0;
            for (int cdx = -1; cdx <= 1; cdx++) {
                for (int cdz = -1; cdz <= 1; cdz++) {
                    if (cdx == 0 && cdz == 0) {
                        continue;
                    }
                    BlockPos n = foot.offset(cdx, 0, cdz);
                    if (!world.isSolid(n) && !world.isSolid(n.above())) {
                        continue;
                    }
                    double ncx = Math.max(n.getX(), Math.min(p.getX(), n.getX() + 1.0));
                    double ncz = Math.max(n.getZ(), Math.min(p.getZ(), n.getZ() + 1.0));
                    double ox = p.getX() - ncx;
                    double oz = p.getZ() - ncz;
                    double d = Math.sqrt(ox * ox + oz * oz);
                    if (d >= 0.45 || d < 1e-6) {
                        continue;
                    }
                    double push = (0.45 - d) / 0.45;
                    rpx += ox / d * push;
                    rpz += oz / d * push;
                }
            }
            double repelMag = Math.sqrt(rpx * rpx + rpz * rpz);
            if (repelMag > 1e-6) {
                double scale = Math.min(0.5, repelMag) / repelMag; // cap the nudge at half impulse
                // world → camera frame: forward = (−sinθ, cosθ), left = (cosθ, sinθ)
                double cam = Math.toRadians(p.getYRot());
                double camSin = Math.sin(cam);
                double camCos = Math.cos(cam);
                cmdStrafe += (rpx * camCos + rpz * camSin) * scale;
                cmdFwd += (rpz * camCos - rpx * camSin) * scale;
            }
        }
        nav.driveTag = String.format(
                Locale.ROOT, "y%.0f F%.2f L%.2f s%.2f f%.2f", driveTargetYaw, driveF, driveL, cmdStrafe, cmdFwd);
        a.commandMove((float) cmdStrafe, (float) cmdFwd);
        // TEMP FREEZE-DIAG (approach-freeze / pit-cascade phase-3 investigation): capture the exact
        // drive state when a dryDescent stalls (stuckTicks high) — which steering branch + driveF +
        // forward component + brakes — so the cur2-frozen hSpd~0 stall mechanism is identified, not guessed.
        if (GameConfig.navigatorDebug && dryDescent && nav.stuckTicks > 18) {
            double fwdComp = driveL * driveSin + driveF * driveCos;
            LOG.info(
                    "[navigator] FREEZE-DIAG stuckT={} driveF={} fwdComp={} ddeg={} dTgtYaw={} camYaw={} aimAtWp={} reCentre={} pivotSU={} descBrake={} stepUpFreeze={} sneak={} sprint={} wpY-footY={} stepCol2={}",
                    nav.stuckTicks,
                    String.format(Locale.ROOT, FMT2, driveF),
                    String.format(Locale.ROOT, FMT2, fwdComp),
                    String.format(Locale.ROOT, FMT0, Math.toDegrees(driveDelta)),
                    String.format(Locale.ROOT, FMT0, driveTargetYaw),
                    String.format(Locale.ROOT, FMT0, p.getYRot()),
                    aimAtWaypoint,
                    reCentre,
                    pivotForStepUp,
                    descendBrake,
                    stepUpFreeze,
                    a.dbgSneak(),
                    p.isSprinting(),
                    wp.getY() - foot.getY(),
                    String.format(Locale.ROOT, FMT2, stepColDx * stepColDx + stepColDz * stepColDz));
        }
        // Bridging a chasm one placed block at a time: sneak (so a sprint
        // overshoot can't carry the bot off the fresh 1-wide block into the
        // gap ahead) and don't sprint. Triggered when the edge we're walking
        // onto OR the next one is a place-bridge — Baritone sneaks for the
        // same reason. Released the moment we're back on real ground.
        // (`bridging` is computed once, hoisted up near the travel-aim pitch.)
        // Lethal-edge sneak-brake (DEATH #8 fix): if a fatal drop is one step ahead
        // in the heading, hold sneak so vanilla's ledge-guard pins the bot at the
        // block edge — the controller can no longer drift off a cliff while fleeing
        // or walking a lip. Lethal-only, so it never blocks a legitimate planned
        // step-down (those are capped at survivableFall by the PathFinder).
        // A lethal drop borders the foot — but only PIN with sneak when we're NOT
        // making a planned step-DOWN. Vanilla's ledge-guard refuses to walk off ANY
        // edge, so if the brake stays on while the path needs to descend (a lethal
        // drop in some OTHER direction, e.g. a mountainside, while the safe planned
        // step is down-and-across), the bot DEADLOCKS — sneak on, hCol=false, creeping
        // at ~0 b/s on a ridge forever (the sudden-slowdown or blocked stall). The PathFinder
        // caps every planned step-down at survivableFall, so releasing sneak for the
        // intended descent is safe. Same-level lip-walking keeps the full pin (death #8),
        // and sprint stays OFF whenever a lethal edge is near (see sprint below) so the
        // released descent has no drift/overshoot momentum off the lip.
        boolean lethalNear = ControlConfig.lethalEdgeBrake && p.onGround() && lethalDropAdjacent(world, p, foot);
        boolean plannedDescent = wp.getY() < foot.getY();
        // Low-HP care (survival DEATH #3, 2026-07-11): releasing the pin for a
        // planned descent bets that the bot lands exactly on the planned cell.
        // At critical health that bet is fatal — survivableFall shrinks to 3-4
        // blocks, so ordinary walk/jump drift past the lip onto a deeper drop
        // kills. Below the threshold keep the sneak pin even across a planned
        // step-down: a pinned descent stalls and repaths (recoverable); a fall
        // at 1-6 HP is not. Sprint is also suppressed below (same gate).
        boolean lowHpCareful = ControlConfig.lowHealthCareful > 0 && p.getHealth() <= ControlConfig.lowHealthCareful;
        final boolean edgeBrake = lethalNear && (!plannedDescent || lowHpCareful);
        // Steep-descent SPRINT brake (NOT a sneak-pin): a survivable-but-deep drop in an
        // edge-neighbour while descending is invisible to lethalNear (its threshold is
        // survivableFall ≈ 23 blk, even higher with resist buffs), so the bot SPRINTS a
        // diagDown/step-down and the momentum drifts it OFF the lip off-path into a 13-19
        // blk fall + 80 t fellOffPath deadlock (live 2026-06-24 random journey at -679,83:
        // sprinted a diagDown, drifted -680→-677 off a 12-drop). Drop sprint when a drop
        // deeper than a normal step (>4 blk) sits adjacent during a planned descent so the
        // bot decelerates onto the node instead of overshooting the lip; sneak stays
        // released (a survivable drop needn't pin the bot, so the step-down still proceeds).
        // LATCH the sprint-drop across the airborne sub-arcs of the step-down (task#36, DRY sibling
        // of the deepWaterDriftLatch below): the raw brake is gated onGround, so on the airborne
        // half of each step sprint RE-ARMS and the accumulated FORWARD momentum walks the bot off
        // a survivable-deep (>4, <survivableFall) lip into a fatal cumulative fall — live 2026-07-11
        // Mountains massif telemetry: grounded sprint=false at the lip, but onG=false→sprint=true on
        // every fall tick, forward z crept the bot over a 19-block lip to death. Hold the brake
        // STEEP_DESCENT_DRIFT_LATCH ticks after each grounded fire so sprint stays off through the
        // whole descent; it re-arms on each grounded step and decays once the descent flattens (wp
        // flush/ascending) or a long free-fall outruns it (harmless — nothing to brake mid-air).
        // Off entirely when the flag is off (byte-identical: raw already requires onGround, and the
        // release clause clears the latch on the next non-raw tick). A gentle ≤4 staircase never
        // trips the raw, so normal descents keep full sprint.
        // CUMULATIVE-descent arm (task#36, 2026-07-12, gated navigatorSteepDescentLatch): the single-
        // edge dropAdjacentExceeds only fires on a >maxDryFall CLIFF neighbour. A mountain descent
        // the planner routes as a run of individually-legal ≤maxDryFall steps (live Mountains
        // y79→77→73→72) has no such neighbour at ANY grounded tick, so steepDescentRaw never armed,
        // the latch never engaged, and the bot sprint-sailed off the slope (hSpd rising 0.21→0.23,
        // sprint=T, 6+ block continuous fall to death). Sum the drop the planner actually laid over
        // the next few nodes (foot.Y − min node.Y); arm when it exceeds a single legal step. Uses
        // the PATH (authoritative — exactly maxStepDrop≤4 per node) not a hand-rolled world probe.
        int steepDescentPathDrop = 0;
        if (nav.route.path() != null && !nav.route.path().isEmpty()) {
            int loY = foot.getY();
            for (int k = nav.route.step();
                    k < Math.min(nav.route.path().size(), nav.route.step() + STEEP_DESCENT_LOOKAHEAD_NODES);
                    k++) {
                loY = Math.min(loY, nav.route.path().get(k).getY());
            }
            steepDescentPathDrop = foot.getY() - loY;
        }
        boolean steepDescentPathAhead = ControlConfig.navigatorSteepDescentLatch
                && p.onGround()
                && steepDescentPathDrop > RulesConfig.pathfinderMaxDryFall;
        boolean steepDescentRaw = p.onGround()
                && ((plannedDescent && dropAdjacentExceeds(world, foot, 4)) // local >4 cliff neighbour
                        || steepDescentPathAhead); // NEW: cumulative slope ahead
        if (steepDescentRaw) {
            driveLatch.steepDescentLatch = STEEP_DESCENT_DRIFT_LATCH;
            // Release ONLY when GROUNDED at/above the node. The old instantaneous wp-vs-foot check
            // flickered true MID-ARC (gap #51 trace t=43: foot 233.7 falls past wp 234 for one tick)
            // and zeroed the latch in the air — re-enabling full drive at a swinging bearing and
            // re-arming sprint mid-fall, which is exactly the sideways kick that walked the bot off
            // the 1-wide stair (landed x=301 on a corner, slid off, fell to -60). A latch armed for
            // an airborne descent arc must survive the whole arc; landing is the only sane release.
        } else if (driveLatch.steepDescentLatch > 0
                && (!ControlConfig.navigatorSteepDescentLatch || (p.onGround() && wp.getY() >= foot.getY()))) {
            driveLatch.steepDescentLatch = 0;
        } else if (driveLatch.steepDescentLatch > 0) {
            driveLatch.steepDescentLatch--;
        }
        final boolean steepDescentNear = steepDescentRaw || driveLatch.steepDescentLatch > 0;
        if (steepDescentPathAhead && GameConfig.navigatorDebug) {
            LOG.info(
                    "[navigator] STEEP-DESCENT path-arm: foot y={} pathDrop={}>{} over {} nodes → latch {} (noSprint{})",
                    foot.getY(),
                    steepDescentPathDrop,
                    RulesConfig.pathfinderMaxDryFall,
                    STEEP_DESCENT_LOOKAHEAD_NODES,
                    STEEP_DESCENT_DRIFT_LATCH,
                    ControlConfig.navigatorDescentStepSkipBrake ? "+airborneDriveFClamp" : "");
        }
        // DESCENT STEP-SKIP grounded sneak-brake — RETIRED (task#36, 2026-07-12). Its gate
        // (onGround && foot.Y-wp.Y > maxDryFall) was proven a literal NO-OP by live trace: the
        // far-below wp only appears while AIRBORNE (grounded max(foot.Y-wp.Y) is exactly 4.0,
        // never >4), so this never fired. The flag navigatorDescentStepSkipBrake is now repurposed as
        // the AIRBORNE forward-drift clamp on driveF (see ~line 4259). Kept as `false` here so the
        // brakeSneak/sprint terms below stay byte-identical without editing them.
        // DEEP-WATER drift SPRINT brake (mechanism b, sibling of steepDescentNear for a water
        // hazard): a dry descending/flush staircase that runs ALONG a deep floating-water pocket
        // is invisible to the dry-drop brakes (dropAdjacentExceeds skips water as a splash), so the
        // bot sprints the stepDown/diagDown and the momentum overshoots laterally off the dry edge
        // into the pocket — where a buoyant bot bob-stalls on the un-climbable-out surface for
        // tens of seconds (live replay-0008 -870: foot drifted x-870.5→-866.3 off the staircase,
        // 800+ frozen pocket ticks). Drop sprint (NOT sneak — a sneak-pin would deadlock the
        // step-down, see the long edgeBrake note) when a deep-water cell borders the foot during a
        // descent or flat edge-walk, so the bot decelerates onto the node instead of overshooting.
        // Never fires when the path DELIBERATELY enters the water (planned next node is itself deep
        // water — a river/lake crossing WANTS the bot in): then there is no dry line to hold and the
        // brake would only slow a legitimate entry. Ascents keep sprint (parkour/swim-approach need
        // the momentum; an ascent isn't drifting DOWN into the pocket).
        // radius 2, maxDrop 7: a deep pocket sits against a TALL bank with a stepped face, so
        // while the bot is still GROUNDED on the upper steps the open water is ~2 cells away and
        // ~6 below (live -870: grounded foot x-871/y68, floating water begins at x-869/-868,y62 —
        // a 1-neighbour/4-deep scan never reached it and the brake stayed inert; the bot went
        // airborne, dropping the onGround precondition, before the water became an immediate
        // neighbour). A dry fall column self-terminates at its first solid/shallow floor, so a
        // wider/deeper scan only adds reach toward genuinely deep water, never a dry-cliff false
        // positive. 2 covers the lateral overshoot a sprint adds; 7 the 6-deep drift with margin.
        // Only water the velocity is carrying the body toward counts: the ring walked past every
        // pond unsprinted (live 2026-09-28 on the cross-country course: 195 vetoed ticks a run, ~3 s of the route).
        boolean deepWaterEdgeRaw = ControlConfig.navigatorDeepWaterDriftBrake
                && p.onGround()
                && wp.getY() <= foot.getY() // descending or flush walk along the edge (not an ascent)
                && !world.isFloatingWater(wp) // not a deliberate deep-water entry (the path means to swim in)
                && NavigatorGeometry.deepWaterAlongVelocity(
                        world,
                        p.getX(),
                        p.getZ(),
                        foot.getY(),
                        p.getDeltaMovement().x,
                        p.getDeltaMovement().z,
                        wp.getY() < foot.getY() ? 2.5 : 1.5,
                        7);
        // LATCH the brake across the airborne sub-arcs of a step-down descent: a staircase is a
        // chain of small drops, so the bot is airborne (onGround false) for ~half the ticks, and
        // without the latch sprint re-arms on every airborne tick and the accumulated forward
        // momentum still carries the bot off the dry line into the pocket (live -870 bottom node:
        // grounded sprint=false, but the overshoot to x-866 / inW happened across the un-braked
        // airborne ticks between steps). Hold the brake DEEP_WATER_DRIFT_LATCH ticks after each
        // grounded fire so sprint stays off through the whole descent past the edge; it decays
        // once the bot stops re-triggering (moved away from the deep edge / finished the descent).
        // Released the instant the path turns to deliberately ENTER the water (wp is deep water),
        // so a real crossing isn't slowed. Off entirely when the flag is off (byte-identical).
        if (deepWaterEdgeRaw) {
            driveLatch.deepWaterDriftLatch = DEEP_WATER_DRIFT_LATCH;
        } else if (driveLatch.deepWaterDriftLatch > 0
                && (!ControlConfig.navigatorDeepWaterDriftBrake || world.isFloatingWater(wp))) {
            driveLatch.deepWaterDriftLatch = 0;
        } else if (driveLatch.deepWaterDriftLatch > 0) {
            driveLatch.deepWaterDriftLatch--;
        }
        final boolean deepWaterDriftNear = deepWaterEdgeRaw || driveLatch.deepWaterDriftLatch > 0;
        // DYNAMIC fall-correction while bridging: correct the heading to prevent falls rather than
        // crouching the whole time at the cost of speed. A 1-wide place-bridge has void on BOTH sides, so the
        // lethal-edge pin
        // (edgeBrake) AND the old blanket bridge-sneak BOTH held shift for the ENTIRE
        // span — crouch-walking the whole bridge at a ~0.9 b/s crawl. Instead, walk the
        // already-placed blocks at full speed and brake (sneak — its ledge-guard pins the
        // bot so it can't step off) ONLY on the ticks with an ACTUAL fall risk:
        //   • gapAhead — no solid footing ~0.6 blocks ahead toward wp (about to overshoot
        //     off the front edge into the not-yet-placed frontier), or
        //   • offCentre — drifted >0.3 off the foot→wp centreline (side fall on the 1-wide
        //     span; the camera-decoupled impulse already re-aims at wp's centre, so this
        //     only fires on real drift).
        // Planned step-downs still release entirely (a descending bridge deadlocks under
        // any pin — see the long edgeBrake note above). Sprint is OFF during bridging
        // (below), so a non-braked tick can't build enough momentum to overshoot a whole
        // block before the next look-ahead check brakes it.
        // Generalised beyond bridging (the 2-block crawl after leaving a bridge): right after a bridge — or on
        // any cliff-lip walk — `edgeBrake` re-pinned the bot the moment `bridging`
        // dropped, crouch-crawling until the void left the 8-neighbourhood. The SAME
        // dynamic gate is the cert'd fix for the same risk on a 1-wide span (the
        // strictly harder case), so apply it to every lethal-edge walk: full speed on
        // safe ticks, brake only on gapAhead/off-centre drift. Sprint stays OFF near
        // a lethal edge (below), bounding per-tick travel just like on the bridge.
        // EXECUTION-TIME path hazard re-check (devil-bench iron ep-014 death #27):
        // the plan was hazard-free, then LAVA FLOWED into the planned corridor at
        // y8 — a breached pocket's flow front chased the tunnel — and the actuator
        // walked the bot into the flow (the hazardAhead brake below only SLOWS;
        // it exists for lava-BESIDE-path passages). A hazard IN the path is not a
        // creep-past case: when the waypoint column itself — or, on a diagonal
        // step, either corner column the bot sweeps — has BECOME hazardous, stop
        // and replan around the new flow. A* never plans through hazard cells, so
        // this can only fire on world change; static scenes never see it.
        {
            boolean wpHazard = world.isHazard(wp) || world.isHazard(wp.above());
            if (!wpHazard && wp.getX() != foot.getX() && wp.getZ() != foot.getZ()) {
                BlockPos ca = new BlockPos(wp.getX(), wp.getY(), foot.getZ());
                BlockPos cb = new BlockPos(foot.getX(), wp.getY(), wp.getZ());
                wpHazard = world.isHazard(ca)
                        || world.isHazard(ca.above())
                        || world.isHazard(cb)
                        || world.isHazard(cb.above());
            }
            // FALL-EDGE flow-front margin (iron ep-025 death #29): a ≥2-drop
            // commit is irreversible — once airborne, the brake below fires
            // into gravity (live: fall2 adopted at t=0, lava flowed into the
            // landing during the 8-tick arc, brake fired at t=8 mid-air, dead
            // one tick later). While still GROUNDED before such a drop, treat
            // lava beside the landing column as hazardous too: a flow front
            // one cell away arrives within ~1.5s, faster than the fall+walk.
            if (!wpHazard && p.onGround() && wp.getY() <= foot.getY() - 2) {
                BlockPos land = wp;
                for (Direction d : new Direction[] {
                    Direction.NORTH, Direction.EAST,
                    Direction.SOUTH, Direction.WEST
                }) {
                    BlockPos n1 = land.relative(d);
                    if (world.isHazard(n1) || world.isHazard(n1.below())) {
                        wpHazard = true;
                        break;
                    }
                }
            }
            if (wpHazard) {
                // UNCONDITIONAL — see announceLavaBrake below for why this one is not throttled.
                LOG.info(
                        "[navigator] path-hazard brake: waypoint {} column now hazardous "
                                + "(flowed in after plan) → replan",
                        wp.toShortString());
                a.releaseInputs();
                nav.replan();
                return TickStep.WALKING;
            }
        }
        // HAZARD-AHEAD brake (lava ×4 live in two rounds): the danger ring only
        // PRICES a lava-hugging route — when no detour exists A* still commits
        // one, and the 0.6-wide player drifts into the neighbouring lava cell
        // mid-stroke (fire res masked what kills a naked bot). Hard guard at the
        // actuator: if the cell ~0.8 ahead along the heading is a hazard at foot
        // or head level, sneak-brake this tick (ledge-guard semantics keep the
        // bot out of the cell; sneak still creeps ~0.9 b/s so a mandatory
        // lava-side passage stays passable, just slow — exactly right there).
        BlockPos hazardCell = null; // the CELL, kept so the log can name it — see announceLavaBrake
        {
            double hax = wp.getX() + 0.5 - p.getX();
            double haz = wp.getZ() + 0.5 - p.getZ();
            double hal = Math.sqrt(hax * hax + haz * haz);
            if (hal > 1e-3) {
                BlockPos aheadCell =
                        BlockPos.containing(p.getX() + hax / hal * 0.8, p.getY(), p.getZ() + haz / hal * 0.8);
                if (world.isHazard(aheadCell) || world.isHazard(aheadCell.above())) {
                    hazardCell = aheadCell;
                }
            }
        }
        boolean hazardAhead = hazardCell != null;
        boolean bridgeBrake = false;
        boolean bridging = cx.edges.bridging;
        // Descending-place lip anchor (task#4, replay-0013): the !plannedDescent gate
        // below exists because a sneak pin across a planned step-down deadlocks — but
        // when the descent runs over a bridgePlace whose SUPPORT IS NOT PLACED YET,
        // "refuse the edge" is exactly right: there is nothing to step down ONTO.
        // Re-admit the dynamic brake for that one case (approach momentum control);
        // the pending check self-releases the moment the support block lands, so the
        // real step-down still proceeds pin-free. The actuator's own sneak hold
        // (StandToWork, while RouteDig places) covers the aim ticks; this covers the approach.
        boolean descentPlacePending = false;
        if (ControlConfig.navigatorBridgeDescentPlaceAnchor && bridging && plannedDescent) {
            Move.Edge nextE = nav.route.edgeAt(nav.route.step() + 1);
            outer:
            for (Move.Edge e : new Move.Edge[] {edge, nextE}) {
                if (e == null) {
                    continue;
                }
                for (BlockPos b : e.toPlace) {
                    // Only the support of the cell this step drops onto holds the bot at the lip. A descending
                    // bridge's next place is always unplaced and below the foot, so counting it pinned the bot at the
                    // lip of a step down onto a block already there (dig-and-build course, 1021,152,1300).
                    if (ControlConfig.navigatorDescentAnchorOwnSupport && !b.equals(wp.below())) {
                        continue;
                    }
                    if (b.getY() < foot.getY() && !world.isSolid(b)) {
                        descentPlacePending = true;
                        break outer;
                    }
                }
            }
        }
        if ((bridging || edgeBrake)
                && (!plannedDescent || descentPlacePending)
                && !parkourEdge) { // !parkourEdge: sneak on the takeoff tick costs the leap 70% of its steering
            // (SNEAKING_SPEED 0.3 from the crouching tick on, on both bots) — same lip, same
            // measurement as the sprint term; see this class's javadoc. Sibling exclusions: the
            // lane-keep strafe and descentAirborneDriftClamp above
            double bdx = wp.getX() + 0.5 - p.getX();
            double bdz = wp.getZ() + 0.5 - p.getZ();
            double blen = Math.sqrt(bdx * bdx + bdz * bdz);
            if (blen > 1e-3) {
                double ax = p.getX() + bdx / blen * 0.6;
                double az = p.getZ() + bdz / blen * 0.6;
                BlockPos aheadFoot = new BlockPos((int) Math.floor(ax), foot.getY(), (int) Math.floor(az));
                boolean gapAhead = !world.isSolid(aheadFoot.below()) && !world.isSolid(aheadFoot);
                double fx = foot.getX() + 0.5;
                double fz = foot.getZ() + 0.5;
                double offCentre = Math.abs((p.getX() - fx) * bdz - (p.getZ() - fz) * bdx) / blen;
                bridgeBrake = gapAhead || offCentre > 0.30;
            }
        }
        // The dynamic brake REPLACES the constant lethal-edge pin everywhere (bridge
        // AND cliff-lip): the pin's job — don't drift off a lethal edge — is done by
        // the per-tick gapAhead/offCentre gate at full walking speed.
        boolean lavaBrake = hazardAhead
                && !p.isInWater(); // land-only: a surface swimmer sneaking beside lava would DIVE (active sink), not
        // stop
        announceLavaBrake(lavaBrake, foot, hazardCell, wp, p);
        // sneak in water = vanilla active SINK (buoyancy never sinks a surface swimmer on its
        // own) — so the dry-land safety brakes (bridge / cliff-descend) must NOT sneak in water,
        // exactly like lavaBrake above: a brake's job is to STOP, but shift in water DIVES the bot
        // to the floor. Live 2026-06-24 deep-pool crossing: a descending parkour edge fired
        // descendBrake → shift → the buoyant bot sank y62→58 and churned ~100 t clawing back to the
        // surface, then re-planned a parkour-onto-water it cannot execute. Only a real dive (diving)
        // sneaks in water; buoyancy alone already keeps a surface swimmer off any lethal edge.
        boolean padSettle = padSettle(nav, world, p, foot, parkourEdge);
        boolean brakeSneak = (bridgeBrake || descendBrake || padSettle) && !p.isInWater();
        // A5 UNDERWATER HORIZONTAL DEPTH-HOLD (dive → traverse). After a dive the path
        // continues through SUBMERGED horizontal edges — the planner emits plain walk/diag
        // nodes mid-water (they are not named swimDown*, so none of the dive gates hold
        // them), and without a depth-hold the swim-up bob below (swimUp / swimColumn /
        // deepWaterRise) ratchets the bot back to the surface where it pins (live
        // 2026-07-04 underwater-base run: dove to -57, walk edges at -59 through a 2-tall
        // doorway, bot ratcheted to -50.8 and drowned at the surface). Gate — as narrow as
        // the dive gates, keyed on the DIVE opt-in plus WATER STATE, not move name:
        //   • the profile explicitly opted into DIVE (empty optIn → byte-identical:
        //     a non-dive bot submerged over a same-level/1-below waypoint keeps the
        //     pre-A5 buoyant swim-up rise), AND
        //   • the bot is SUBMERGED (water two above the feet — WorldView#isSubmergedFoot;
        //     a surface floater NEVER reads true, so the surface machinery — carrot-drive,
        //     bob-latch, flatWaterWalk sprint, float-over crossing, climb-out — is
        //     completely untouched), AND
        //   • the current step target is itself UNDERWATER (its head cell is water too;
        //     a climb-out / bank node reads false), AND
        //   • the node is at-or-below the feet (wp above foot = a planned ascent → gate
        //     off, the normal swim-up rise applies).
        // Action: feet ABOVE the node level → sneak (the vanilla shift-sink, the exact
        // actuator a swimDown* dive rides) until back at level; AT level → no sneak, the
        // plain forward drive traverses (and the swim-up jump stays suppressed so the
        // level HOLDS); feet BELOW → gate is off by the third clause.
        boolean underwaterDepthHold = nav.dives() // the opt-in, or a dive the planner fell back to past a wall
                && p.isInWater()
                && world.isSubmergedFoot(foot)
                && world.isWater(wp)
                && world.isWater(wp.above())
                && wp.getY() <= foot.getY();
        boolean underwaterSink = underwaterDepthHold && wp.getY() < foot.getY();
        Cruise cruise = surfaceCruise(nav, world, p, a, foot, wp, diving, diveUnderCap);
        Keys.sneak(a, brakeSneak || diving || lavaBrake || underwaterSink || cruise.dip());
        p.setShiftKeyDown(brakeSneak || lavaBrake);
        // Jump for a real upward step, a parkour-leap edge (by move type, not
        // raw distance — string-pulling makes plain walk waypoints far apart
        // too), or a brief stuck-wiggle.
        // The stuck-wiggle jump unsticks a bot wedged on a corner, but on a
        // 1-wide bridge it would hop the bot clean off into the gap — so
        // never wiggle-jump while bridging. Stop pressing jump once braking
        // (we're descending onto the block — no more lift wanted).
        // Stay afloat while pathing through water. autoSwim is gated OFF during
        // goto/follow/explore/runAway (it would fight the Navigator's jump), so the
        // Navigator itself must swim up — otherwise the bot sinks in deep water and
        // drowns mid-path (the "swam into the ocean and died" failure). Hold jump
        // whenever the eyes are submerged, unless we're deliberately diving a
        // swimDown edge (then let it descend).
        // (diving is computed earlier, beside diveUnderCap, so the pitch/sneak
        // actuators can drive the ACTIVE sink — see that block for the rationale.)
        // DEBOUNCED (≥3 ticks submerged): on a surface cruise the eye line bobs in
        // and out of the waterline every few strokes, and each 1-2-tick dip pulsed
        // the swim-up jump — a +1y overshoot tooth every ~2-3 s with a synchronized
        // speed dip (round43b pathChart: regular sawtooth across the whole sea crossing).
        // A genuine sink keeps the eyes under for many consecutive ticks, so a
        // 3-tick (150 ms) gate costs real buoyancy nothing; swimColumn (water at
        // head height) still rises a true column un-debounced.
        driveLatch.underwaterTicks = p.isInWater() && p.isUnderWater() ? driveLatch.underwaterTicks + 1 : 0;
        boolean swimUp = driveLatch.underwaterTicks >= 3 && !diving;
        // The stuck-wiggle hop unsticks a corner on DRY land, but in shallow water
        // on a flat walk it just bobs the bot off the floor into the buoyant drift
        // (it floats off its cell and slides — the very stall it's meant to break).
        // Suppress it there; treading + steady forward threads the channel instead.
        // A floating bot rides ONE block above the in-water path nodes (A* places
        // water nodes at the cell whose head breaks the surface; the buoyant foot
        // is in the cell above), so a same-level open-water crossing shows up as
        // wp.y == foot.y − 1 with a WATER wp — treat that as flat too, or the
        // whole crossing loses the prone sprint-swim and treads at ~1.5 blk/s
        // (live 2026-06-09: sprint=false across a lake, hSpd 0.077, each 200-tick
        // repath advanced only ~8 blocks → zig-zag + stall feel). A real swimDown
        // edge keeps its own handling (diving), and climb-outs (wp above foot)
        // stay non-sprint as before.
        boolean flatWaterWalk = p.isInWater()
                && !diving
                && (wp.getY() == foot.getY() || (foot.getY() - wp.getY() == 1 && world.isWater(wp)));
        // Floor-gated (navigatorRecoveryHopFloorGate): the "while bridging" carve-out above
        // encodes edge TYPE, but the fatal case is footing GEOMETRY — walking an EXISTING
        // 1-wide strip is not `bridging`, and the wiggle there launched a sprint-jump arc
        // along a mid-slew heading clean over the deck (bridge battery, every shed).
        // Hop-range (Chebyshev ≤2) against an arc MEASURED at 3.47, launched from a floored cell one
        // stride INSIDE the rim — foot-adjacent scans stay blind. driveTargetYaw, not the camera:
        // NavigatorGeometry.hopLandingRow.
        boolean wiggle = wiggleHop(nav, world, p, foot, !bridging && !flatWaterWalk && !pivotForStepUp, driveTargetYaw);
        // Climbing a +1 ledge out of a SHALLOW water film needs a BALLISTIC,
        // GROUNDED jump: |Δy|=0.8 exceeds the 0.6 auto-step, and a *held* jump in
        // water just swims the bot up to bob at the surface (y+0.2, onGround=false)
        // — it never clears the dry ledge (trace: stuck 797t at cur2≈1, |dY|=0.8).
        // So for a water climb-out, jump ONLY when grounded: releasing between
        // launches lets the bot settle the 0.2 back onto the shaft floor, from
        // which a real jump (+1.25) tops the ledge while the strafe-centring above
        // holds it under the target column so forward carries it on. (swimUp below
        // still rescues a genuinely-submerged deep-water climb — undW there.)
        // Water climb-out jump rule covers BOTH depths:
        //  • SUBMERGED (deep water / flooded pit, undW): swim up — hold jump so the
        //    bot rises through the water column toward the surface (never sinks /
        //    drowns; this is the original swimUp behaviour).
        //  • AT THE SURFACE of a shallow film (inW, !undW): a held jump only bobs
        //    (y+0.2, onGround=false) and can't clear the 0.8 dry ledge, so jump
        //    ONLY when grounded — releasing lets the bot settle the 0.2 onto the
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
        // film — the buoyant bot is onGround ≈1/10 ticks and swimColumn is false (no
        // water above the head), so it just treaded into the bank wall (trace: stuck
        // 814t at a lake bank, jump=false every tick). wp.getY()>foot.getY() is true for
        // ANY water climb-out, so this presses jump throughout it; swimColumn still
        // rises a deep submerged column and swimUp covers a fully-submerged climb.
        // For a dry cardinal +1 step, only jump once Baritone-aligned (ascendJumpReady):
        // close + squared-up + not drifting. Early/off-axis jumps bonk the step and
        // slide back (the steep-climb wedge); waiting to jump also keeps the bot
        // moving smoothly instead of bobbing in place (no jittery camera on stream).
        // Jump a step only when a jump is actually needed (beyond auto-step) AND the
        // step is within reach (≤ maxJumpUp) — never bob-jump an unreachable height —
        // and, for the +1 cardinal case, only once Baritone-aligned.
        double riserLead = riserLead(nav, cx, world, p, foot);
        boolean leadJump = riserLead <= 0; // NaN (no clean riser ahead) is false
        boolean stepUpJump = !halfStepRise(p, foot, wp)
                && (leadJump
                        || needJumpForStep
                                && upDy <= maxJumpUp
                                && (!dryStepUp || ascendJumpReady)
                                && !pivotForStepUp
                                && !cruise.on());
        // Don't hold the swim-up jump when a SOLID cell caps the head (foot+2) while
        // in water: the path threads a submerged / stone-overhung tunnel (water
        // surface capped by solid above), so bobbing up just RAMS that ceiling and
        // the bot can't move horizontally under it — the stuck-bobbing trace at a
        // stone-capped water surface (hCol=true, hSpd=0, y oscillating into the
        // ceiling, pos frozen). Staying low lets forward thread the tunnel. Only the
        // water jumps are gated; a dry stepUp / parkour launch is never suppressed
        // (their own gates apply), and an OPEN water column (head not capped) still
        // swims up so a deep crossing can't drown.
        boolean cappedHead = p.isInWater() && world.isSolid(foot.offset(0, 2, 0));
        // A buoyant bot reaching the surface of DEEP water (water below its feet) on a level or
        // slightly-rising WATER node has NO swim-up and sinks to the floor: swimUp needs the eyes
        // already submerged ≥3t, and swimColumn needs water at foot+1 — but a bot whose foot
        // BLOCK sits AT the surface cell has AIR at foot+1, so both miss, jump=false, and it drops
        // straight to the bottom and wedges in a crevice (live 2026-06-24 walk edge to -798,62,649:
        // y62 surface→y47 floor, jump=false the whole way down, then 117t wedged hCol at y47).
        // descendBrake is a parkourDescend-only brake (not this walk edge) and diving is false
        // (sneak=false), so nothing else holds it up. Hold the swim-up jump whenever DEEP water is
        // under the feet and the target is a WATER cell at/above us, so it stays afloat across the
        // crossing. Excludes dives (diving, or wp below the foot) and dry bank climb-outs
        // (!isWater(wp) → the grounded ballistic climb-out jump still applies); cappedHead below
        // still threads a stone-lipped submerged tunnel low.
        boolean deepWaterRise = p.isInWater()
                && !diving
                && world.isWater(foot.below())
                && wp.getY() >= foot.getY()
                && world.isWater(wp);
        // The leap's own checks stand in for the launch guards below: its node is always below and within reach.
        boolean poolLeap = poolLeap(nav, world, p, foot);
        driveLatch.poolLeapAirborne |= poolLeap;
        boolean wadeBob = wadeBob(world, p, foot, wp);
        driveLatch.ticksSinceWaterOrStart = p.isInWater() ? 0 : Math.min(driveLatch.ticksSinceWaterOrStart + 1, 1000);
        boolean startJump = sprintJumpStart(nav, world, p, foot, driveLatch.ticksSinceWaterOrStart);
        boolean swimRiseCoast = timedBank(nav, world, p, wp) != null
                ? bankBreachCoast(nav, world, p, wp)
                : swimRiseCoasts(world, p, foot, wp);
        boolean jump = poolLeap
                || wadeBob
                || startJump
                || climbWait
                || !descendBrake
                        && !swimRiseCoast
                        // Below-node ram (the +1 desync-ram wedge, see descentRamStuck): the bot drifted 1 above the
                        // route and rams an overhang at a node BELOW it. Every jump term here is futile going DOWN (no
                        // riser to mount), and the bob-jump (y+1.25) just bonks the overhang AND lifts the foot off the
                        // ground — STARVING the grounded-gated descentRamStuck recovery, so the wedge bob-drags ~46t
                        // instead of resolving at ~24t (live 2026-06-24 V2 climb replay: y85<->86.25 bob, cur2 frozen
                        // 0.640, reroute re-wedged once). Suppress the jump while grounded + ramming + node-below so
                        // the
                        // bot stays planted and descentRamStuck fires on time. A real descent never rams (clean drop),
                        // and a jump-to-a-lower-node is a parkour/fall edge (own gates) not this walk-drive — so this
                        // kills ONLY the futile overhang bob-jump.
                        && !(p.horizontalCollision && p.onGround() && wp.getY() < foot.getY())
                        && !atAnAdjacentNodeBelow(p, wp, foot)
                        && (stepUpJump
                                || (parkourEdge
                                        && !parkourLanded(nav, p, wp, foot)
                                        && !dipWalkIn(nav, world, wp, foot, parkourEdge)
                                        && !shortOfLip(nav, p, foot, wp)
                                        && !runOffDrop(nav, world, p, foot, wp)
                                        && !padSettle)
                                // Freeze-breaker: force a GROUNDED jump straight up the step once a
                                // stepUp/diagUp has rammed the riser past STEPUP_FREEZE_TICKS — the
                                // normal stepUpJump gate (ascendJumpReady) can stay false there (the
                                // bot is a touch off the cross-axis), so without this it bobs forever.
                                || (stepUpFreeze && p.onGround())
                                // !diving: swimColumn is true for any submerged bot, so during an
                                // ACTIVE dive the held jump cancelled the sneak-sink exactly —
                                // !underwaterDepthHold: same cancellation for the SUBMERGED HORIZONTAL
                                // traverse after a dive (walk/diag edges through deep water — see the
                                // depth-hold gate beside brakeSneak): the swim-up bob is exactly the
                                // ratchet that hauled the bot back to the surface mid-traverse. A
                                // planned ascent (wp above foot) reads false there, so a real rise
                                // and every surface behavior keep their jump. —
                                // the bot hovered at constant depth (hSpd 0.02, jump+sneak both
                                // down) while the burst storm wound yaw 4.5 turns (mangrove live).
                                || ((swimUp || swimColumn || deepWaterRise)
                                        && !cappedHead
                                        && !diving
                                        && !underwaterDepthHold
                                        && !cruise.on())
                                || cruise.jump()
                                || wiggle); // a confirmed cruise holds jump: vanilla's jumpInLiquid keeps the prone
        // eyes at the
        // waterline
        // TEMP-DIAG (sunken-start deadlock): dump every jump term while submerged & stuck
        if (GameConfig.navigatorDebug
                && p.isInWater()
                && p.isUnderWater()
                && nav.stuckTicks > 20
                && nav.stuckTicks % 20 == 1) {
            LOG.info(
                    "[navigator] JUMP-DIAG jump={} swimUp={} swimCol={} dwRise={} capped={} diving={} descBrake={} belowRam={} stepUpJump={} wiggle={} uwT={} wp={},{},{} foot={},{},{}",
                    jump,
                    swimUp,
                    swimColumn,
                    deepWaterRise,
                    cappedHead,
                    diving,
                    descendBrake,
                    p.horizontalCollision && p.onGround() && wp.getY() < foot.getY(),
                    stepUpJump,
                    wiggle,
                    driveLatch.underwaterTicks,
                    wp.getX(),
                    wp.getY(),
                    wp.getZ(),
                    foot.getX(),
                    foot.getY(),
                    foot.getZ());
        }
        if (jump && vetoJumpOnGraze(nav, world, p, parkourEdge)) {
            jump = false;
        }
        if (jump) {
            nav.jumpTag = stepUpJump
                    ? MOVE_STEP_UP
                    : parkourEdge
                            ? "parkour" // "other" = this chain is stale, see Navigator#avatarJump
                            : stepUpFreeze && p.onGround()
                                    ? "stepUpFreeze"
                                    : wiggle
                                            ? "wiggle"
                                            : swimUp
                                                    ? "swimUp"
                                                    : swimColumn
                                                            ? "swimColumn"
                                                            : deepWaterRise
                                                                    ? "deepWaterRise"
                                                                    : poolLeap
                                                                            ? "poolLeap"
                                                                            : wadeBob
                                                                                    ? "wadeBob"
                                                                                    : startJump ? "startJump" : "other";
        }
        if (parkourEdge) {
            noteParkourTakeoff(world, a, p, jump, foot);
        }
        nav.keys.jump(a, jump); // latch: NavigatorInspector.parkourTakeoff()
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
        // stone lip) is only ~2 cells tall, and an UPRIGHT player (1.8) rams the ceiling
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
        // Sprint the climb-out APPROACH (swimming toward a bank while still >~1.6 blk lateral
        // from it), not just flat crossings. The no-sprint-on-climb-out rule (water term below)
        // was tuned for the CLOSE press where the prone pose can't rise the bank — but it also
        // killed sprint on the long swim UP TO the bank, so the bot tread-bobbed in at hSpd ~0.06
        // for 2-3 blocks before even reaching it (live 2026-06-24 -597 / -671 water edges, slow
        // swim throughout = the shared root behind the water-edge churn fix I didn't cover).
        // rawClimbPress (latDist²<2.5) still drops sprint for the final rise, so the "stuck
        // bobbing, can't climb out" trace stays fixed; this only speeds the approach swim.
        boolean climbApproach = p.isInWater()
                && !p.onGround()
                && wp.getY() > foot.getY()
                && (stepColDx * stepColDx + stepColDz * stepColDz) >= 2.5;
        boolean diagAscent = !parkourEdge
                && !p.isInWater() // why: sprintVeto's javadoc, DIAG_ASCENT
                && wp.getX() != foot.getX()
                && wp.getZ() != foot.getZ()
                && wp.getY() > foot.getY();
        boolean diagSprint = diagAscent && ControlConfig.navigatorDiagAscentSprint;
        boolean poolRunOff = driveLatch.poolLeapAirborne
                || (steppingOffWaterFall || steepDescentNear) && poolRunOff(nav, world, foot);
        boolean swimRise = swimUpSprint(world, p, foot, wp);
        int sprintVeto = sprintVeto(
                bridging,
                steppingOffFall,
                steppingOffWaterFall && !poolRunOff,
                diagAscent && !leadJump && !diagSprint,
                finalApproach(nav, p, wp, parkourEdge),
                lowHpCareful,
                hazardAhead,
                descendBrake,
                lethalNear && !parkourEdge,
                steepDescentNear && !poolRunOff,
                deepWaterDriftNear,
                needJumpForStep && !parkourAscend && !sprintAscend && !leadJump && !diagSprint && !swimRise,
                p.isInWater() && !flatWaterWalk && !diveUnderCap && !climbApproach && !swimRise,
                dipLeap(nav, world, wp, parkourEdge) && foot.getY() < wp.getY(),
                padLeap(nav, world, p, wp, parkourEdge));
        boolean sprint = (cruise.on() && !hazardAhead && !lowHpCareful)
                || sprintVeto == 0; // the cruise IS its sprint: only hazard and low HP outrank it (see surfaceCruise)
        p.setSprinting(sprint && !digHold); // a sprinting swimmer keeps 0.9 of its speed a tick and coasts twice as far

        // Lily pads sit ON the water plane with a real collision box; the planner
        // deliberately treats the thin shape as passable (a fast prone swim slides
        // under), but the moment the swim slows, the upright treading player rams the
        // pad — hCol=true, hSpd→0 — and the swamp current + anti-stuck bursts spin
        // the bot in place (round28: 7×7 of open water + pads, yaw wound to -994°).
        // Pads are instabreak: punch the one ahead (or overhead) and keep swimming.
        // mayBreak(): a pad is a WALK-edge traversal (the planner treats it passable, so NoBreak
        // does NOT prune it) — this is the ONE fallback a forbidDig bot can reach with a full plan,
        // and it historically checked neither allowBreak NOR NoBreak. Gate it so forbidDig (and the
        // global switch) are both honored — an executor recovery must never out-mutate the planner.
        if (p.isInWater() && p.horizontalCollision && nav.mayBreak()) {
            double bdx = wp.getX() + 0.5 - p.getX();
            double bdz = wp.getZ() + 0.5 - p.getZ();
            double bl = Math.sqrt(bdx * bdx + bdz * bdz);
            BlockPos surf = BlockPos.containing(p.getX(), p.getY() + 1.0, p.getZ());
            BlockPos aheadPad =
                    bl > 1e-3 ? BlockPos.containing(p.getX() + bdx / bl, p.getY() + 1.0, p.getZ() + bdz / bl) : surf;
            BlockPos pad = world.isBreakableObstruction(aheadPad)
                    ? aheadPad
                    : world.isBreakableObstruction(surf) ? surf : null;
            if (pad != null) {
                Digging.avatarDig(nav, a, pad, false);
            }
        }
        // navigatorWallDigFallback (§71, C49): the purest "recovery fires but does nothing"
        // form — a downhill node behind a 1-block dirt wall pins the bot hCol with the
        // stall clock climbing correctly (132+ after the EPS fix) while safetyRepath
        // returns the SAME route and nothing ever tries to DIG the wall (attack=false
        // for the whole 90s churn, pickaxe in hand, allowBreak on). When dry, grounded,
        // wall-pinned and confirmed stalled, punch the waypoint-facing block at head
        // then feet height; the digAimPriority latch (armed below) keeps the crosshair
        // on it through subsequent travel ticks. Default OFF.
        // C55-J3 (§73): the jump-ram loop keeps the bot AIRBORNE (jump->wall-bonk->land->
        // instantly jump again), so an onGround precondition here starves the dig for the
        // whole 393-tick stall — the wall is reachable mid-air; drop the ground gate and
        // key on the collision itself.
        // Survival-run death#1 family: this fallback used to fire regardless of
        // allowBreak — with breaking globally OFF the planner emits walk-only paths,
        // and every wall-pin stall then silently punched through terrain anyway
        // (bare-hand stone, 7.5s+/block: looked like "the bot chose to tunnel").
        // An executor recovery must never exceed the world-mutation authority the
        // planner was given.
        if (ControlConfig.navigatorWallDigFallback
                && nav.mayBreak()
                && !p.isInWater() // mayBreak(): honor per-goto forbidDig (day6 tunnel), not just the global switch
                && p.horizontalCollision
                && (nav.stuckTicks > 40
                        || (ControlConfig.navigatorPhysicalStallClock && nav.progress.physStall.stallTicks > 60))
                && !nav.blocks.breakHeld()) {
            double fdx = wp.getX() + 0.5 - p.getX();
            double fdz = wp.getZ() + 0.5 - p.getZ();
            double fl = Math.sqrt(fdx * fdx + fdz * fdz);
            if (fl > 1e-3) {
                BlockPos tgt = wallDigTarget(world, p, wp, fdx / fl, fdz / fl);
                if (tgt != null) {
                    Digging.avatarDig(nav, a, tgt, true);
                    if (GameConfig.navigatorDebug) {
                        LOG.info(
                                "[navigator] wall-dig FALLBACK {},{},{} stuckT={}",
                                tgt.getX(),
                                tgt.getY(),
                                tgt.getZ(),
                                nav.stuckTicks);
                    }
                }
            }
        }
        // digAimReassert (navigatorDigAimPriority): the travel tick has fully run — drive,
        // recovery, repath all had their say. Now re-hold ONLY the crosshair + attack on
        // the committed dig cell so the interleaved travel tick can't zero vanilla mining
        // progress (the C36-J1 cave-dig replay slowdown). Movement keys stay whatever the
        // travel logic chose: a human holding W+LMB against the wall being dug.
        if (ControlConfig.navigatorDigAimPriority
                && nav.prelude.stickyDig.pos != null
                && world.isSolid(nav.prelude.stickyDig.pos)) {
            Digging.avatarDig(nav, a, nav.prelude.stickyDig.pos, true); // prelude's repeat of this cell is dropped
        }
        if (GameConfig.navigatorDebug) {
            // [dbgcollide] hard physics evidence for the hill speed-sawtooth: is the
            // bot actually COLLIDING (hitbox snagging a trunk/step face) or just
            // turning? hSpd = horizontal velocity AFTER vanilla's ground friction (x0.546), so a
            // full grounded sprint reads ~0.15 b/tick although the bot covers ~0.28 per tick;
            // hCol/minorCol = vanilla collision flags; pos = real feet so we can see
            // dwell (pos frozen while wp/yaw change = stuck, not moving).
            Vec3 dm = p.getDeltaMovement();
            double horizSpeed = Math.sqrt(dm.x * dm.x + dm.z * dm.z);
            LOG.info(
                    "[navigator] walk-keys yaw={} wp={},{},{} up={} jump={} sprint={}{} sneak={} hCol={} minorCol={} hSpd={} pos={},{},{} onG={} attack={} dryDesc={} driveYaw={} aim={} stuckT={} lead={}",
                    String.format(Locale.ROOT, FMT0, p.getYRot()),
                    wp.getX(),
                    wp.getY(),
                    wp.getZ(),
                    a.dbgForwardImpulse(),
                    a.dbgJumping(),
                    p.isSprinting(),
                    sprint ? "" : sprintVetoNames(sprintVeto),
                    a.dbgSneak(),
                    p.horizontalCollision,
                    p.minorHorizontalCollision,
                    String.format(Locale.ROOT, "%.3f", horizSpeed),
                    String.format(Locale.ROOT, FMT2, p.getX()),
                    String.format(Locale.ROOT, FMT2, p.getY()),
                    String.format(Locale.ROOT, FMT2, p.getZ()),
                    p.onGround(),
                    nav.blocks.breakHeld(),
                    dryDescent,
                    String.format(Locale.ROOT, FMT0, driveTargetYaw),
                    aimSrc,
                    nav.stuckTicks,
                    Double.isNaN(riserLead) ? cx.leadWhy : String.format(Locale.ROOT, FMT2, riserLead));
        }
        return TickStep.WALKING;
    }

    /**
     * {@link ControlConfig#navigatorRiserLeadJump}: jump the +1 riser ahead NOW if one more grounded tick would leave
     * the box too little run to rise above it. A jump reaches y+0.42 and y+0.75 on its first two airborne ticks and
     * clears a block top only on the third, so the face must lie beyond those two ticks' travel (sprint launch boost
     * included); deciding once per tick, the last safe moment is when the next ground move would eat that margin. The
     * riser is the current node when it is the +1, or the next one while the pointer still sits on the flat cell before
     * it — that cell is only "within" half a block from the face, far too late for a sprinting launch.
     *
     * <p>Returns how much run is left before that moment (≤ 0: jump now), or NaN with no clean +1 riser ahead. Lifting
     * the stepJump sprint veto over the whole approach (any finite value) was tried: three starts averaged 160.5 s
     * against 154.6 s, take-off speed unchanged.
     */
    private double riserLead(Navigator nav, TickCtx tick, WorldView world, LivingEntity p, BlockPos foot) {
        tick.leadWhy = "air";
        if (!ControlConfig.navigatorRiserLeadJump || !p.onGround() || p.isInWater() || nav.route.path() == null) {
            return Double.NaN;
        }
        int idx = -1;
        tick.leadWhy = "noRise";
        for (int i = nav.route.step(); i < Math.min(nav.route.path().size(), nav.route.step() + 2) && idx < 0; i++) {
            BlockPos n = nav.route.path().get(i);
            if (n.getY() == foot.getY() + 1) {
                idx = i;
            } else if (n.getY() != foot.getY()) {
                return Double.NaN;
            }
        }
        if (idx < 0) {
            return Double.NaN;
        }
        Move.Edge e = nav.route.edgeAt(idx);
        if (e == null
                || e.move == null
                || !(e.move.startsWith(MOVE_STEP_UP) || e.move.startsWith("diagUp"))
                || hasPendingEdge(world, e)) {
            tick.leadWhy = "edge:" + (e == null ? "null" : e.move);
            return Double.NaN;
        }
        BlockPos node = nav.route.path().get(idx);
        // The riser two cells out can be a half-step flight too: led into, the jump cleared the first stair and met
        // the second in the air (swimming benchmark course, oak stairs from 1054,150,1100: 0.36 s at 1054.7).
        if (ControlConfig.navigatorHalfStepNoLead && halfStepRise(p, foot, node)) {
            tick.leadWhy = "halfStep";
            return Double.NaN;
        }
        tick.leadWhy = "contact";
        Vec3 v = p.getDeltaMovement();
        double vh = Math.hypot(v.x, v.z);
        double dx = node.getX() + 0.5 - p.getX();
        double dz = node.getZ() + 0.5 - p.getZ();
        if (vh > 0.03) {
            dx = v.x;
            dz = v.z;
        }
        double len = Math.hypot(dx, dz);
        if (len < 1e-3) {
            return Double.NaN;
        }
        BlockPos from = ControlConfig.navigatorRiserLeadRow && idx > 0 && e.move.startsWith(MOVE_STEP_UP)
                ? nav.route.path().get(idx - 1)
                : null;
        double contact = riserContact(tick, world, p, foot.getY(), dx / len, dz / len, node, from);
        double air = 0.026;
        double m1 = vh + 0.2 + air;
        double m2 = m1 * 0.91 + air; // sprint-jump boost + air accel
        // navigatorRiserLeadMargin: at 0 a launch timed to the hundredth still rubs the face on the second airborne
        // tick (cross-country course 1386,64,-464: 0.75 of run, 0.75 travelled).
        return contact
                - (m1
                        + m2
                        + ControlConfig.navigatorRiserLeadMargin
                        + vh
                        + 0.13); // + the ground move a wait would spend
    }

    /**
     * Distance the player box can travel along (ux,uz) at foot level {@code fy} before touching a solid cell, or NaN
     * when nothing is met within reach or what it meets is not a +1 riser with head room over it (a wall must not be
     * jumped at), or, given a {@code node}, it is not the step under it. A block beside it is not the one to mount:
     * live 1544,72,-159, met along a velocity still carrying the previous diagonal, was jumped onto and fallen off four
     * times beside node 1545,72,-160. Not even a diagonal's corner cell: landing there puts the next step off its line
     * (the cross-country course's first leg, 2026-09-28: onto corner 1545,70,-158, then the same 1544,72,-159, 5.8 s →
     * 6.5 s).
     *
     * <p>Except, for a cardinal step up from {@code from}, the riser's neighbour across the step: it is the same ledge,
     * its face in the same plane, and the body lands on the node's level half over the node. Refusing it left the
     * cross-country course's riser at 1483,64,-462 to the late jump at the node, which met the face on its first
     * airborne tick.
     */
    private static double riserContact(
            TickCtx tick, WorldView world, LivingEntity p, int fy, double ux, double uz, BlockPos node, BlockPos from) {
        for (double s = 0.0; s <= RISER_SCAN; s += 0.05) {
            double cx = p.getX() + ux * s;
            double cz = p.getZ() + uz * s;
            boolean hit = false;
            for (int x = Mth.floor(cx - 0.3); x <= Mth.floor(cx + 0.2999); x++) {
                for (int z = Mth.floor(cz - 0.3); z <= Mth.floor(cz + 0.2999); z++) {
                    BlockPos c = new BlockPos(x, fy, z);
                    if (!world.isSolid(c)) {
                        continue;
                    }
                    if (world.isSolid(c.above()) || world.isSolid(c.above(2))) {
                        tick.leadWhy = "wall@" + x + "," + z;
                        return Double.NaN;
                    }
                    if (node != null
                            && (ControlConfig.navigatorRiserLeadExact
                                    ? x != node.getX() || z != node.getZ()
                                    : Math.abs(x - node.getX()) > 1 || Math.abs(z - node.getZ()) > 1)
                            && !besideAcrossStep(node, from, x, z)) {
                        tick.leadWhy = "beside@" + x + "," + z;
                        return Double.NaN;
                    }
                    hit = true;
                }
            }
            if (hit) {
                return s;
            }
        }
        return Double.NaN;
    }

    /** Whether (x,z) is beside {@code node} across a cardinal step from {@code from}; see {@link #riserContact}. */
    private static boolean besideAcrossStep(BlockPos node, BlockPos from, int x, int z) {
        if (from == null) {
            return false;
        }
        int sx = node.getX() - from.getX();
        int sz = node.getZ() - from.getZ();
        if (Math.abs(sx) + Math.abs(sz) != 1) {
            return false;
        }
        return Math.abs(x - node.getX()) == Math.abs(sz) && Math.abs(z - node.getZ()) == Math.abs(sx);
    }

    /**
     * Every reason the walk drops sprint, one bit each in {@link #SPRINT_VETO_NAMES} order, so the walk-keys line can
     * say WHICH gate took sprint away. A bare {@code sprint=false} could not: a flat stretch walked 17% of its grounded
     * ticks unsprinted with no collision, and nothing told the dozen gates apart.
     *
     * <ul>
     *   <li>{@code diagAscent} — LATERAL DRIFT on steep diagonal climbs (live 2026-06-25 -787 ridge): the flat run-up
     *       between diagonal steps carried sprint momentum through the next jump arc and pushed the bot 1-2 blocks off
     *       the √2 staircase, into pillar-spam slides (56 cobble / 292 pillarUp on one climb) or a sideways ram of the
     *       ridge block. Parkour leaps and water climb-approaches still sprint; they need the momentum.
     *   <li>{@code lowHp} — sprint is the drift amplifier behind every unplanned fall; at ≤lowHealthCareful HP walk
     *       everything (DEATH #3).
     *   <li>{@code hazard} — never carry sprint momentum INTO a lava/hazard cell, in water too.
     *   <li>{@code lethalNear} — never sprint near a lethal edge, incl. a planned descent past it; a parkour edge is
     *       exempt, since a flat leap over an abyss needs the run-up (measured 0.1563→0.1400 with no impulse, fell in,
     *       vs 0.1232→0.2475 one cell back), while wd.bridgeLethalGapStop's walk-off lip still loses its sprint.
     *   <li>{@code deepWaterDrift} — as lethalNear, for a deep-water pocket bordering a descent.
     *   <li>{@code stepJump} — a jumped CARDINAL ascend walks (Baritone does the same; a parkour ascend and a
     *       sprintAscend are exempt). A/B-DISPROVEN 2026-06-06: sprinting an aligned ascend regressed hCol 13%→36% and
     *       mean hSpd .112→.082, because the jump fires close to the riser (flatDist≤1.2) and the sprint boost rams its
     *       face; a sprint-jump clears a step only when launched early.
     *   <li>{@code water} — in water, sprint only a flat crossing, a capped tunnel (the prone pose fits under the lip)
     *       or a climb-out approach; the prone pose cannot rise a bank.
     *   <li>{@code dipLeap} — see {@link #dipLeap}.
     *   <li>{@code padLeap} — see {@link #padLeap}.
     * </ul>
     */
    // One flag per veto arm, read in one expression by the caller; a parameter object would only move the list.
    @SuppressWarnings("checkstyle:parameternumber")
    private static int sprintVeto(
            boolean bridge,
            boolean offFall,
            boolean offWaterFall,
            boolean diagAscent,
            boolean finalApproach,
            boolean lowHp,
            boolean hazard,
            boolean descendBrake,
            boolean lethalNear,
            boolean steepDescent,
            boolean deepWaterDrift,
            boolean stepJump,
            boolean water,
            boolean dipLeap,
            boolean padLeap) {
        boolean[] v = {
            bridge,
            offFall,
            offWaterFall,
            diagAscent,
            finalApproach,
            lowHp,
            hazard,
            descendBrake,
            lethalNear,
            steepDescent,
            deepWaterDrift,
            stepJump,
            water,
            dipLeap,
            padLeap
        };
        int mask = 0;
        for (int i = 0; i < v.length; i++) {
            if (v[i]) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * A level two-cell leap over a one-deep dip, where the dip itself is a floor. A leap has no safe length there:
     * sprinted, live 1569,64,-107 → 1567,64,-109 flew past into the pit behind; unsprinted it fell short into the dip
     * on some starts, ~2 s either way. So {@link #dipWalkIn} walks down into it and the edge's jump fires from the dip
     * floor, unsprinted so the climb-out lands its cell. A leap over a hole, which needs the run-up, keeps its sprint.
     */
    private boolean dipLeap(Navigator nav, WorldView world, BlockPos wp, boolean parkourEdge) {
        if (!parkourEdge
                || nav.route.step() <= 0
                || nav.route.step() >= nav.route.path().size()) {
            return false;
        }
        BlockPos from = nav.route.path().get(nav.route.step() - 1);
        int dx = wp.getX() - from.getX();
        int dz = wp.getZ() - from.getZ();
        if (wp.getY() != from.getY() || Math.max(Math.abs(dx), Math.abs(dz)) != 2) {
            return false;
        }
        return world.canStandAt(from.offset(Integer.signum(dx), -1, Integer.signum(dz)));
    }

    /** Still on the takeoff level of a {@link #dipLeap}: walk off into the dip instead of jumping it. */
    private boolean dipWalkIn(Navigator nav, WorldView world, BlockPos wp, BlockPos foot, boolean parkourEdge) {
        return foot.getY() >= wp.getY() && dipLeap(nav, world, wp, parkourEdge);
    }

    /**
     * The leap is over: grounded on the landing's level, off the takeoff cell, short of the node by a walk. Held jump
     * used to fire a second hop from there — live 1569,64,-107 → 1567,64,-109 landed 0.85 short, hopped again and on
     * some starts over into the pit behind, ~1 s.
     */
    private boolean parkourLanded(Navigator nav, LivingEntity p, BlockPos wp, BlockPos foot) {
        if (!p.onGround()
                || foot.getY() != wp.getY()
                || nav.route.step() <= 0
                || foot.equals(nav.route.path().get(nav.route.step() - 1))) {
            return false;
        }
        // Still held up by the launch cell, or by something behind it, is not landed, however far the middle has gone
        // over the gap: on the parkour course a body that came down on the launch cell's far edge from a diagonal read
        // as landed, held the jump and walked off. What holds the body up is asked, not what is under its middle, so a
        // landing on the near edge of the landing block, middle still over the gap, stays landed.
        if (ControlConfig.navigatorLandedPastGap
                && heldUpBehindLanding(p, nav.route.path().get(nav.route.step() - 1), wp)) {
            return false;
        }
        double dx = wp.getX() + 0.5 - p.getX();
        double dz = wp.getZ() + 0.5 - p.getZ();
        return dx * dx + dz * dz < 1.5 * 1.5;
    }

    /**
     * Whether the block holding the body up lies at or behind {@code launch} along the leap to {@code landing}: the
     * body is still on the launch side of the gap. False when the game has not named a supporting block.
     */
    private static boolean heldUpBehindLanding(LivingEntity p, BlockPos launch, BlockPos landing) {
        BlockPos support = p.mainSupportingBlockPos.orElse(null);
        if (support == null) {
            return false;
        }
        int lx = Integer.signum(landing.getX() - launch.getX());
        int lz = Integer.signum(landing.getZ() - launch.getZ());
        return (support.getX() - launch.getX()) * lx + (support.getZ() - launch.getZ()) * lz <= 0;
    }

    /**
     * A running bot on a straight leap's launch cell holds the jump until the next tick would carry it to the lip. It
     * jumped the moment it stood on the cell instead: on the parkour course the three-block jump left from 1018.02, 0.7
     * short of the lip at 1019, and came down 0.25 short of the far side. A bot too slow to be running keeps the jump,
     * so a pinned one is never held down. A diagonal leap is held only from short of its launch cell, onto it
     * ({@link ControlConfig#navigatorLeapFromCellEdge}).
     */
    private static boolean shortOfLip(Navigator nav, LivingEntity p, BlockPos foot, BlockPos wp) {
        if (!ControlConfig.navigatorLeapFromLip || !p.onGround() || nav.route.step() <= 0) {
            return false;
        }
        BlockPos launch = nav.route.path().get(nav.route.step() - 1);
        int dx = Integer.signum(wp.getX() - launch.getX());
        int dz = Integer.signum(wp.getZ() - launch.getZ());
        double speed = p.getDeltaMovement().horizontalDistance();
        if (speed < LIP_HOLD_MIN_SPEED) {
            return false;
        }
        if (foot.equals(launch) && (dx == 0 || dz == 0)) {
            double lip = dx == 0 ? launch.getZ() + 0.5 + 0.5 * dz : launch.getX() + 0.5 + 0.5 * dx;
            double toLip = dx == 0 ? (lip - p.getZ()) * dz : (lip - p.getX()) * dx;
            return toLip > speed + LIP_HOLD_MARGIN;
        }
        // A diagonal leap whose node came up with the body still on the cell before the launch: held until the body
        // stands on the launch cell, so the take-off no longer falls wherever the tick the node came up did.
        return ControlConfig.navigatorLeapFromCellEdge
                && dx != 0
                && dz != 0
                && !foot.equals(launch)
                && foot.getY() == launch.getY()
                && Math.abs(foot.getX() - launch.getX()) + Math.abs(foot.getZ() - launch.getZ()) == 1
                && headingInto(p, foot, launch);
    }

    /** Whether the body's run on {@code foot} heads across into the side-by-side {@code cell}. */
    private static boolean headingInto(LivingEntity p, BlockPos foot, BlockPos cell) {
        Vec3 v = p.getDeltaMovement();
        return v.x * (cell.getX() - foot.getX()) + v.z * (cell.getZ() - foot.getZ()) > 0.0;
    }

    /**
     * A cardinal one-block rise onto a bottom slab, or onto a bottom stair that climbs the way the bot goes, is two
     * half-block steps that vanilla's step-up walks without a jump. Jumping it instead hit the next riser in the air
     * and dropped the sprint to a crawl on every stair (parkour course, oak stairs from 1054,150,1000), where a person
     * ran the flight on the ground at full speed.
     */
    private static boolean halfStepRise(LivingEntity p, BlockPos foot, BlockPos wp) {
        int dx = wp.getX() - foot.getX();
        int dz = wp.getZ() - foot.getZ();
        // Any straight-line distance: the riser lead fires its jump while the node is still two cells out.
        if (!ControlConfig.navigatorWalkUpHalfSteps
                || wp.getY() - foot.getY() != 1
                || (dx == 0) == (dz == 0)
                || p.isInWater()) {
            return false;
        }
        BlockState rise = p.level().getBlockState(wp.below());
        if (rise.getBlock() instanceof SlabBlock) {
            return rise.getValue(SlabBlock.TYPE) == SlabType.BOTTOM;
        }
        return rise.getBlock() instanceof StairBlock
                && rise.getValue(StairBlock.HALF) == Half.BOTTOM
                && rise.getValue(StairBlock.FACING) == Direction.fromDelta(Integer.signum(dx), 0, Integer.signum(dz));
    }

    /**
     * A one-block drop across a one-block gap is run off, not jumped, by a bot already running: falling the block takes
     * about five ticks, which carries a sprint 1.3 blocks, past the gap. The jump went 1.25 up first and landed 0.3 s
     * later than a person who ran off the edge (parkour course, 1029,151,1000 to 1031,150,1000). Only onto a landing
     * with room past it, so a run-off that comes down long still stands.
     */
    private static boolean runOffDrop(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, BlockPos wp) {
        int dx = wp.getX() - foot.getX();
        int dz = wp.getZ() - foot.getZ();
        return ControlConfig.navigatorRunOffShortDrop
                && p.onGround()
                && foot.getY() - wp.getY() == 1
                && (Math.abs(dx) == 2 && dz == 0 || Math.abs(dz) == 2 && dx == 0)
                && p.getDeltaMovement().horizontalDistance() >= RUN_OFF_MIN_SPEED
                && roomPastLanding(nav, world, wp);
    }

    /**
     * The leap's own heading while a bot in the air has flown past its landing node onto floor that goes on: driving at
     * the node turned the bot round in mid-air and bled the run to a crawl, 0.4 s on a one-block drop across a gap
     * (parkour course, landing 1031,150,1000, the bot over 1032). Null when the node is still ahead, or nothing past it
     * can be stood on.
     */
    public static Float flownPastLanding(
            Navigator nav, WorldView world, LivingEntity p, BlockPos wp, boolean parkourEdge) {
        if (!ControlConfig.navigatorRunOffShortDrop || !parkourEdge || p.onGround() || nav.route.step() <= 0) {
            return null;
        }
        BlockPos launch = nav.route.path().get(nav.route.step() - 1);
        double ux = wp.getX() - launch.getX();
        double uz = wp.getZ() - launch.getZ();
        double past = (p.getX() - wp.getX() - 0.5) * ux + (p.getZ() - wp.getZ() - 0.5) * uz;
        if (past <= 0 || !roomPastLanding(nav, world, wp)) {
            return null;
        }
        return (float) Math.toDegrees(Math.atan2(-ux, uz));
    }

    /**
     * A level two-by-two diagonal leap onto a cell with nothing to stand on past it goes unsprinted. A bot that had
     * just landed the leap before still carried its run, and the sprint jump's boost on top flew it 3.6 blocks for the
     * leap's 2.8, past the far edge of a one-block pillar (parkour course, 1045,150,1000 to 1047,150,1002). A bot still
     * at walking pace keeps the sprint, which is what carries a leap from a standstill.
     */
    private static boolean padLeap(Navigator nav, WorldView world, LivingEntity p, BlockPos wp, boolean parkourEdge) {
        if (!ControlConfig.navigatorPadLeapUnsprinted
                || !parkourEdge
                || nav.route.step() <= 0
                || p.getDeltaMovement().horizontalDistance() < RUN_OFF_MIN_SPEED) {
            return false;
        }
        BlockPos launch = nav.route.path().get(nav.route.step() - 1);
        int sx = Integer.signum(wp.getX() - launch.getX());
        int sz = Integer.signum(wp.getZ() - launch.getZ());
        // A pad: nothing to stand on past it along either axis of the leap. A strip running on along one axis (the
        // course's one-wide spit) is a landing a leap needs its sprint to reach.
        return Math.abs(wp.getX() - launch.getX()) == 2
                && Math.abs(wp.getZ() - launch.getZ()) == 2
                && wp.getY() == launch.getY()
                && !world.canStandAt(wp.offset(sx, 0, 0))
                && !world.canStandAt(wp.offset(0, 0, sz))
                && !world.canStandAt(wp.offset(sx, 0, sz));
    }

    /**
     * A planned drop into water with open water past its landing, which is run off at a sprint rather than stepped off
     * at a walk. Stepped off, the drop fell almost straight down and the body hung in the pool to start over; a person
     * runs off the ledge, carries two or three blocks through the air and lands already swimming (swimming benchmark
     * course, the six-block drop from 1060,155,1100 into the pool at 1061..1067). Only when every cell the run carries
     * over is water at the landing's level, with nothing overhead up to the ledge, so the extra reach cannot land on a
     * rim or hit a ceiling.
     */
    private static boolean poolRunOff(Navigator nav, WorldView world, BlockPos foot) {
        if (!ControlConfig.navigatorSprintIntoPool || nav.route.step() <= 0) {
            return false;
        }
        // The drop is any edge the steep-descent veto looks ahead to, while the body still climbs or runs to the lip.
        int drop = -1;
        int last = nav.route.step() + STEEP_DESCENT_LOOKAHEAD_NODES - 1;
        for (int i = nav.route.step(); i <= last && i < nav.route.path().size(); i++) {
            Move.Edge e = nav.route.edgeAt(i);
            if (e != null && e.move != null && e.move.startsWith("fallWater")) {
                drop = i;
                break;
            }
        }
        if (drop < 1) {
            return false;
        }
        BlockPos landing = nav.route.path().get(drop);
        BlockPos launch = nav.route.path().get(drop - 1);
        int sx = Integer.signum(landing.getX() - launch.getX());
        int sz = Integer.signum(landing.getZ() - launch.getZ());
        if (Math.abs(sx) + Math.abs(sz) != 1 || foot.getY() <= landing.getY()) {
            return false;
        }
        for (int k = 0; k <= POOL_RUN_OFF_REACH; k++) {
            BlockPos cell = landing.offset(sx * k, 0, sz * k);
            if (!world.isWater(cell)) {
                return false;
            }
            for (int y = cell.getY() + 1; y <= foot.getY() + 1; y++) {
                if (!world.isPassable(new BlockPos(cell.getX(), y, cell.getZ()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A sprinting bot at the lip of a planned drop into a pool leaps, as a person does. Run off, the body lost its
     * ground drive at the lip and fell nearly straight down, 0.15 blocks a tick, to land a cell and a half out; the
     * sprint-jump's boost carries it at 0.29 a tick for the whole fall and lands it swimming far out in the pool
     * (swimming benchmark course, the six-block drop from 1060,155,1100). It leaps as early as the flight still comes
     * down past the lip, as a person does: leapt at the lip, the body reached the far side and met the bank's face at
     * the pool floor (1067.7,147), where a person who leapt from the top stair at 1058.35 entered the water at 1064.
     * Only when the flight, followed tick by tick with vanilla's air physics, enters water with room before the far
     * side and every cell it crosses is open up to the jump's head height.
     */
    private static boolean poolLeap(Navigator nav, WorldView world, LivingEntity p, BlockPos foot) {
        if (!ControlConfig.navigatorLeapIntoPool
                || !p.onGround()
                || p.isInWater()
                || !p.isSprinting()
                || nav.route.step() <= 0) {
            return false;
        }
        // The drop is this edge, or the next one while the body runs along the ledge to the launch node.
        int d = nav.route.step();
        if (!isFallWater(nav.route.edgeAt(d)) && !isFallWater(nav.route.edgeAt(++d))) {
            return false;
        }
        if (d >= nav.route.path().size()) {
            return false;
        }
        BlockPos landing = nav.route.path().get(d);
        BlockPos launch = nav.route.path().get(d - 1);
        int sx = Integer.signum(landing.getX() - launch.getX());
        int sz = Integer.signum(landing.getZ() - launch.getZ());
        final int gap = Math.abs(landing.getX() - launch.getX()) + Math.abs(landing.getZ() - launch.getZ());
        if (Math.abs(sx) + Math.abs(sz) != 1 || foot.getY() != launch.getY() || landing.getY() >= launch.getY()) {
            return false;
        }
        // Along the drop from the launch cell's centre, where the lip is at 0.5; across it, on the launch's row.
        double t = (p.getX() - launch.getX() - 0.5) * sx + (p.getZ() - launch.getZ() - 0.5) * sz;
        double across = (p.getX() - launch.getX() - 0.5) * sz - (p.getZ() - launch.getZ() - 0.5) * sx;
        double v = p.getDeltaMovement().horizontalDistance();
        // Back down to the ledge's height only past the lip, by the box's half width.
        double clear = leapCarry(v, 0);
        if (Math.abs(across) > POOL_LEAP_ACROSS || t + clear - POOL_LEAP_HALF_WIDTH < 0.5 && t < 0.5 - POOL_LEAP_LIP) {
            return false;
        }
        double carry = leapCarry(v, landing.getY() + WATER_SURFACE - p.getY());
        int water = 0;
        while (water <= POOL_LEAP_MAX_CELLS && world.isWater(landing.offset(sx * water, 0, sz * water))) {
            water++;
        }
        if (Double.isNaN(carry) || t + carry + POOL_LEAP_MARGIN > gap + water - 0.5) {
            return false;
        }
        // Head room from the cell the body stands in to where the flight enters the water.
        for (int k = (int) Math.floor(t + 0.5); k <= (int) Math.ceil(t + carry); k++) {
            int bottom = k < gap ? launch.getY() : landing.getY() + 1;
            for (int y = bottom; y <= launch.getY() + POOL_LEAP_HEAD_CELLS; y++) {
                if (!world.isPassable(new BlockPos(launch.getX() + sx * k, y, launch.getZ() + sz * k))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A sprinting bot still slow on the ground, just out of the water or off a landing, sprint-jumps to get up to
     * speed, as a person does: the jump's 0.2 boost and the air's light drag carry it at once at the 0.29 a tick that
     * the ground's friction takes four or five ticks to build (swimming benchmark course, out onto the banks at
     * 1035,150,1100, 1051 and 1068: a person was at 0.46 a tick on the first tick after landing where the bot crept on
     * at 0.1). Only down a straight run the flight keeps to: the plan goes on in the same direction past the landing,
     * every cell under the flight is floor at the take-off level or one below, with head room for the arc, and the goal
     * lies beyond it. Only within a second of leaving the water or of setting off: the flight checks one cell wide, and
     * on the cross-country course a start jumped anywhere put the body's side into trunks and banks beside that line,
     * two or three more bumps a run (1498,69,-363 and 1466,66,-516).
     */
    private static boolean sprintJumpStart(
            Navigator nav, WorldView world, LivingEntity p, BlockPos foot, int ticksSinceWaterOrStart) {
        if (!ControlConfig.navigatorSprintJumpStart
                || ticksSinceWaterOrStart > SPRINT_JUMP_START_AFTER_WATER
                || !p.onGround()
                || p.isInWater()
                || !p.isSprinting()
                || nav.route.path() == null
                || nav.route.step() >= nav.route.path().size()) {
            return false;
        }
        double v = p.getDeltaMovement().horizontalDistance();
        BlockPos node = nav.route.path().get(nav.route.step());
        int sx = Integer.signum(node.getX() - foot.getX());
        int sz = Integer.signum(node.getZ() - foot.getZ());
        if (v >= SPRINT_JUMP_START_SPEED
                || Math.abs(sx) + Math.abs(sz) != 1
                || Math.abs(angleDiff(p.getYRot(), (float) Math.toDegrees(Math.atan2(-sx, sz))))
                        > SPRINT_JUMP_START_YAW) {
            return false;
        }
        // The last cell the box is over when it comes down, counted from the foot cell.
        double offset = (p.getX() - foot.getX() - 0.5) * sx + (p.getZ() - foot.getZ() - 0.5) * sz;
        int reach = (int) Math.floor(0.5 + offset + leapCarry(v, 0, true) + POOL_LEAP_HALF_WIDTH);
        // The plan runs straight on along the flight, at the take-off level or one below, and goes on past it.
        int far = 0;
        for (int i = nav.route.step(); i < nav.route.path().size() && far <= reach; i++) {
            BlockPos n = nav.route.path().get(i);
            int along = (n.getX() - foot.getX()) * sx + (n.getZ() - foot.getZ()) * sz;
            int across = (n.getX() - foot.getX()) * sz - (n.getZ() - foot.getZ()) * sx;
            if (across != 0 || along < far || n.getY() > foot.getY() || n.getY() < foot.getY() - 1) {
                return false;
            }
            far = along;
        }
        if (far <= reach) {
            return false;
        }
        for (int k = 1; k <= reach; k++) {
            BlockPos cell = foot.offset(sx * k, 0, sz * k);
            if (!(world.canStandAt(cell) || world.canStandAt(cell.below())) || world.isHazard(cell.below())) {
                return false;
            }
            // A body that comes down on farmland from a jump tramples it to dirt and pops the crop on it; walking does
            // not. On the farming course a start across the plot trampled the cell it landed on.
            if (p.level().getBlockState(cell.below()).is(Blocks.FARMLAND)
                    || p.level().getBlockState(cell.below(2)).is(Blocks.FARMLAND)) {
                return false;
            }
            for (int y = 0; y <= POOL_LEAP_HEAD_CELLS; y++) {
                if (!world.isPassable(cell.above(y))) {
                    return false;
                }
            }
        }
        return world.isPassable(foot.above(POOL_LEAP_HEAD_CELLS));
    }

    private static boolean isFallWater(Move.Edge e) {
        return e != null && e.move != null && (e.move.startsWith("fallWater") || isStepDownToPool(e));
    }

    /**
     * A step down off a bank onto a pool's surface, a cell below, which is leapt like a deeper drop into a pool. Run
     * off, the body stood in the water, too high for its eyes to go under, for seven ticks, and lost two thirds of its
     * speed to the water's drag and to the sneak that sinks it before its swim began (swimming benchmark course, the
     * pool at 1005,149,1100). Leapt, it carries three cells at the jump's speed and comes down hard enough to go under
     * at once. Whether the step lands in water with room past it is {@link #poolLeap}'s check.
     */
    private static boolean isStepDownToPool(Move.Edge e) {
        return ControlConfig.navigatorLeapOffBankIntoPool
                && e != null
                && e.move != null
                && e.move.startsWith("stepDown");
    }

    /**
     * How far a sprint-jump from the ground at horizontal speed {@code v} carries before the feet fall {@code drop}
     * below the take-off height (negative), or NaN past {@link #POOL_LEAP_MAX_TICKS}. Vanilla's order: the jump adds
     * 0.42 up and the 0.2 sprint boost, the take-off tick still gets the ground's sprint drive and friction, then air
     * drive 0.026 against 0.91 drag, and gravity 0.08 against 0.98 drag.
     *
     * <p>As vanilla moves it ({@code navigatorLeapCarryAsVanilla}): the ground drive is the sprint speed 0.13 times the
     * 0.98 vanilla scales input by, its friction factor being 1 on a plain block, and the carry is where the tick that
     * comes down past {@code drop} starts, since vanilla resolves that tick's fall before its step forward; a drive of
     * 0.17 counted to that tick's end puts the landing half a block too far, past the lip of a low bank's pool.
     */
    private static double leapCarry(double v, double drop) {
        return leapCarry(v, drop, false);
    }

    /**
     * {@link #leapCarry(double, double)}, or with {@code tickEnd} where the tick that comes down past {@code drop}
     * ends: the furthest the body's feet get before it is on the ground, the reach a scan of the cells it may come down
     * on has to cover.
     */
    private static double leapCarry(double v, double drop, boolean tickEnd) {
        boolean asVanilla = ControlConfig.navigatorLeapCarryAsVanilla;
        double x = v + 0.2 + (asVanilla ? LEAP_TAKEOFF_DRIVE : 0.17);
        double vh = x * 0.546;
        double y = 0.42;
        double vy = (0.42 - 0.08) * 0.98;
        double before = 0;
        for (int i = 1; i < POOL_LEAP_MAX_TICKS; i++) {
            if (y <= drop) {
                return asVanilla && !tickEnd ? before : x;
            }
            before = x;
            vh += 0.026;
            x += vh;
            vh *= 0.91;
            y += vy;
            vy = (vy - 0.08) * 0.98;
        }
        return Double.NaN;
    }

    /**
     * Wading one-deep water toward a level node, the jump is held, as a person does. Vanilla drops the sprint of a body
     * in water with its eyes out, so walking the floor crawls at 0.10 blocks a tick; the held jump lifts the body out
     * of the water every second or so, and each stretch in the air runs up to 0.17 (swimming benchmark course, the
     * shallow pool 1071..1080,149,1100: a person crossed at 0.125 a tick against the bot's 0.10). Only with a floor
     * under the feet, air over the water and head room for the bob.
     */
    private static boolean wadeBob(WorldView world, LivingEntity p, BlockPos foot, BlockPos wp) {
        return ControlConfig.navigatorWadeBob
                && p.isInWater()
                && !p.isUnderWater()
                && wp.getY() == foot.getY()
                && world.isWater(wp)
                && world.isWater(foot)
                && !world.isWater(foot.above())
                && world.isSolid(foot.below())
                && world.isPassable(foot.offset(0, 2, 0));
    }

    /**
     * Wading the bob toward a bank one block up, the body lets go of forward while, kept on, it would reach the bank's
     * face with its feet still below the bank's top, so that it meets the face at the top of a bob and goes over it.
     * Kept on, the bob met the face wherever it happened to be, and on its way down that was a knock on the face before
     * vanilla's climb-out lifted the body over (swimming benchmark course, every bot run: the step at 1081,150,1100 out
     * of the shallow pool). The bob is the held jump of {@link #wadeBob}: in water each tick adds 0.04 to the rise and
     * keeps 0.8 of it less 0.005, in the air gravity takes 0.08 and drag 0.02. Never below
     * {@link #WADE_HOLD_MIN_SPEED}, so the wait cannot become a stand in the water.
     */
    private static boolean wadeBankHold(WorldView world, LivingEntity p, BlockPos wp) {
        if (!ControlConfig.navigatorWadeBankTiming
                || !p.isInWater()
                || p.isUnderWater()
                || p.horizontalCollision
                || p.getY() >= wp.getY()
                || world.isWater(wp)
                || !world.isSolid(wp.below())
                || !world.canStandAt(wp)) {
            return false;
        }
        BlockPos wade = BlockPos.containing(p.getX(), wp.getY() - 1, p.getZ());
        double speed = p.getDeltaMovement().horizontalDistance();
        double toFace = toFace(p, wp);
        if (!world.isWater(wade)
                || !world.isSolid(wade.below())
                || speed < WADE_HOLD_MIN_SPEED
                || toFace <= BANK_FACE_MARGIN
                || toFace > WADE_HOLD_LOOK) {
            return false;
        }
        double surface = wade.getY() + WATER_SURFACE;
        double y = p.getY();
        double vy = p.getDeltaMovement().y;
        for (int t = 0; t < Math.ceil(toFace / speed); t++) {
            if (y < surface) {
                vy += WADE_BOB_LIFT;
                y += vy;
                vy = vy * SWIM_COAST_KEEP - WADE_BOB_SINK;
            } else {
                y += vy;
                vy = (vy - ElytraPhysics.GRAVITY) * AIR_DRAG_KEEP;
            }
        }
        return y < wp.getY();
    }

    /**
     * Under water with the eyes under, rising to a node that is itself water: swum at a sprint, which vanilla keeps
     * while the eyes are under and which gives the prone swim. Treading up unsprinted crossed at half a person's speed
     * (swimming benchmark course, after the drop into the pool at 1063,147,1100: 0.10 against 0.19 blocks a tick). A
     * bank node is not water, so the climb-out keeps its upright pose.
     */
    private static boolean swimUpSprint(WorldView world, LivingEntity p, BlockPos foot, BlockPos wp) {
        return ControlConfig.navigatorSprintSwimUp
                && p.isUnderWater()
                && wp.getY() > foot.getY()
                && (world.isWater(wp) || swimUpToBank(world, wp));
    }

    /**
     * A bank node a swimmer still under water rises toward: swum up to at a sprint and looking up, like a surface node,
     * until the eyes break the surface, where the upright climb-out takes over.
     */
    private static boolean swimUpToBank(WorldView world, BlockPos wp) {
        return ControlConfig.navigatorSwimRiseLookUp && !world.isWater(wp) && world.canStandAt(wp);
    }

    /**
     * The pitch for a bot swimming up under water to the bank it climbs out onto: toward the bank's top, and level
     * otherwise. Vanilla's swim rises toward where the swimmer looks, so a level gaze rose 0.10 blocks a tick where a
     * person looking up rose 0.17, and met the bank's face a cell lower, pressed against it for twice as long (swimming
     * benchmark course, the bank at 1068,150,1100 out of the pool). Not toward a surface node: the swim-up's coast
     * already settles the eyes at the waterline, and a surface node can be the last before a dive (the same course,
     * 1043,149,1100), where looking up only lifted the body a cell the dive then had to sink back. A surface node whose
     * next node is the bank is risen through toward the bank.
     */
    static float swimRisePitch(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, BlockPos wp) {
        if (!ControlConfig.navigatorSwimRiseLookUp
                || !p.isUnderWater()
                || wp.getY() <= foot.getY()
                || world.isSolid(foot.above(2))
                || bankBreachCoast(nav, world, p, wp)) {
            return 0f;
        }
        BlockPos bank = risingToBank(nav, world, wp);
        if (bank == null) {
            return 0f;
        }
        double run =
                Math.max(SWIM_RISE_MIN_RUN, Math.hypot(bank.getX() + 0.5 - p.getX(), bank.getZ() + 0.5 - p.getZ()));
        double up = Math.toDegrees(Math.atan2(bank.getY() - p.getEyeY(), run));
        return (float) -Mth.clamp(up, 0, SWIM_RISE_MAX_PITCH);
    }

    /**
     * Swimming to the last water node before a bank that has to be dug, the body lets go of forward to stop in front of
     * the bank, where it will tread water while it digs. Swum on, it ran into the face before the dig began, one knock
     * per bank (dig-ashore benchmark course, 1014.7,149.6,1200.5).
     */
    private static boolean digApproachHold(Navigator nav, WorldView world, LivingEntity p) {
        if (!ControlConfig.navigatorTreadWhileDigging || !p.isInWater() || nav.route.path() == null) {
            return false;
        }
        int step = nav.route.step();
        for (int i = step; i <= step + 1 && i < nav.route.path().size(); i++) {
            Move.Edge edge = nav.route.edgeAt(i);
            if (edge != null && hasPendingEdge(world, edge)) {
                return Digging.treadsShortOfFace(p, nav.route.path().get(i), Digging.DIG_FACE_GAP_WHILE_DIGGING);
            }
        }
        return false;
    }

    /**
     * A dug bank is climbed from the top of the bob: at its face with the feet more than 0.6 under its top, the body
     * holds back and swims up first, since the water lifts a body over a ledge only from there. Sent in low, with the
     * jump let go as the bob fell, it pressed the face for half a second while it sank and rose again (dig-ashore
     * benchmark course, 1030.7,148.8,1200.5).
     */
    private static boolean dugBankClimbWait(Navigator nav, WorldView world, LivingEntity p) {
        if (!ControlConfig.navigatorTreadWhileDigging
                || !p.isInWater()
                || p.onGround()
                || nav.route.path() == null
                || nav.route.step() >= nav.route.path().size()) {
            return false;
        }
        BlockPos wp = nav.route.path().get(nav.route.step());
        Move.Edge into = nav.route.edgeAt(nav.route.step());
        return into != null
                && !into.toBreak.isEmpty()
                && !hasPendingEdge(world, into)
                && !world.isWater(wp)
                && world.canStandAt(wp)
                && p.getY() < wp.getY() - BANK_LIFT_DEPTH
                && Digging.treadsShortOfFace(p, wp, Digging.CLIMB_FACE_GAP);
    }

    /** The bank a swimmer is heading up to: the node itself, or the one after a surface node. Null otherwise. */
    private static BlockPos risingToBank(Navigator nav, WorldView world, BlockPos wp) {
        if (swimUpToBank(world, wp)) {
            return wp;
        }
        int next = nav.route.step() + 1;
        if (world.isWater(wp)
                && !world.isWater(wp.above())
                && next < nav.route.path().size()) {
            BlockPos n = nav.route.path().get(next);
            return n.getY() > wp.getY() && swimUpToBank(world, n) ? n : null;
        }
        return null;
    }

    /**
     * Rising to a bank out of deep water, the swimmer lets go of the jump, and looks level, while the rise it already
     * has will carry its feet over the bank's top by the time it reaches the face, and presses it again as soon as it
     * would not; the rise then tracks the face and tops out on it. Holding the jump all the way, a body that broke the
     * surface a block short fell back against the face, and one that met the face with its feet a block down pressed
     * against it for a quarter of a second while it climbed (swimming benchmark course, the banks at 1051,150,1100 and
     * 1068,150,1100). A swimmer with its eyes out of the water cannot top out by rising, since a jump there only bobs
     * it up and lets it fall back before the face (the bank at 1035,150,1100); it swims on to the face and climbs
     * there. Once the feet are clear of the water the jump is left alone.
     */
    static boolean bankBreachCoast(Navigator nav, WorldView world, LivingEntity p, BlockPos wp) {
        BlockPos bank = timedBank(nav, world, p, wp);
        if (bank == null) {
            return false;
        }
        if (!p.isUnderWater()) {
            return true;
        }
        double vy = Math.max(0, p.getDeltaMovement().y);
        double toFace = toFace(p, bank);
        double ticks = toFace / Math.max(p.getDeltaMovement().horizontalDistance(), BANK_MIN_SPEED);
        double coast = vy * (1 - Math.pow(SWIM_COAST_KEEP, ticks)) / (1 - SWIM_COAST_KEEP);
        return p.getY() + coast >= bank.getY() + BANK_BREACH_CLEARANCE;
    }

    /**
     * The bank a swimmer in deep water is rising to and has not yet reached the face of, or null. The rise to it is
     * timed by {@link #bankBreachCoast}. Shallow water is left alone: wading, the bob does the lifting.
     */
    private static BlockPos timedBank(Navigator nav, WorldView world, LivingEntity p, BlockPos wp) {
        if (!ControlConfig.navigatorBankBreachTiming || !p.isInWater() || p.horizontalCollision) {
            return null;
        }
        BlockPos bank = risingToBank(nav, world, wp);
        if (bank == null
                || !world.isWater(BlockPos.containing(p.getX(), bank.getY() - 2, p.getZ()))
                || p.getY() >= bank.getY() - 1 + WATER_SURFACE
                || toFace(p, bank) <= BANK_FACE_MARGIN) {
            return null;
        }
        return bank;
    }

    /** How far the body's side is from a bank cell's near face, along the axis it is furthest off on. */
    private static double toFace(LivingEntity p, BlockPos bank) {
        double dx = bank.getX() + 0.5 - p.getX();
        double dz = bank.getZ() + 0.5 - p.getZ();
        return Math.max(Math.abs(dx), Math.abs(dz)) - 0.5 - POOL_LEAP_HALF_WIDTH;
    }

    /**
     * A sprint-swim rising to a surface node lets go of the jump once its climb will coast the eyes to the waterline: a
     * sprinting swimmer has no gravity in water and keeps 0.8 of its rise a tick, so it coasts four times its vertical
     * speed. Held to the end, the jump shot the body out of the water, it fell back and sank to the floor, and the
     * cycle repeated every three blocks of the swimming benchmark course's first pool (1009..1031,149,1100).
     */
    private static boolean swimRiseCoasts(WorldView world, LivingEntity p, BlockPos foot, BlockPos wp) {
        // The prone body is 0.6 tall: its feet reach the surface cell while the eyes are still under.
        if (!ControlConfig.navigatorSprintSwimUp
                || !p.isUnderWater()
                || wp.getY() < foot.getY()
                || !world.isWater(wp)
                || world.isWater(wp.above())) {
            return false;
        }
        double gap = wp.getY() + WATER_SURFACE - p.getEyeY();
        return gap <= SWIM_RISE_COAST * Math.max(0, p.getDeltaMovement().y);
    }

    /**
     * A bot that has just landed on a one-cell pillar with its next leap ahead sneaks, and holds the jump, until it has
     * slowed. Leaping again at once carried the last leap's run into the next one and slid it off the far edge of the
     * next pillar (parkour course, 1045,150,1000 to 1047,150,1002); sneaking keeps it on the pillar meanwhile.
     */
    private static boolean padSettle(
            Navigator nav, WorldView world, LivingEntity p, BlockPos foot, boolean parkourEdge) {
        if (!ControlConfig.navigatorPadLeapUnsprinted
                || !parkourEdge
                || !p.onGround()
                || nav.route.step() <= 0
                || p.getDeltaMovement().horizontalDistance() <= PAD_SETTLE_SPEED) {
            return false;
        }
        // The pillar is the launch node, not the foot cell: landed on its rim, the body's centre sat over the pit
        // beside it (1045.80,150,999.97 on the pillar at 1045,150,1000).
        BlockPos pad = nav.route.path().get(nav.route.step() - 1);
        double dx = pad.getX() + 0.5 - p.getX();
        double dz = pad.getZ() + 0.5 - p.getZ();
        BlockPos next = nav.route.path().get(nav.route.step());
        // A straight leap on goes the way the run already carries the body; holding its jump slid the bot off the
        // pillar's lip instead (1047,150,1002 on to 1049,150,1002). Only a turn onto a diagonal leap settles first.
        boolean diagonal = next.getX() != pad.getX() && next.getZ() != pad.getZ();
        if (!diagonal || pad.getY() != foot.getY() || dx * dx + dz * dz > 1.0) {
            return false;
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (world.canStandAt(pad.relative(d))) {
                return false;
            }
        }
        return true;
    }

    /** The cell one step past a leap's landing, in the leap's direction, can be stood on. */
    public static boolean roomPastLanding(Navigator nav, WorldView world, BlockPos wp) {
        if (nav.route.step() <= 0) {
            return false;
        }
        BlockPos launch = nav.route.path().get(nav.route.step() - 1);
        int dx = Integer.signum(wp.getX() - launch.getX());
        int dz = Integer.signum(wp.getZ() - launch.getZ());
        return world.canStandAt(wp.offset(dx, 0, dz));
    }

    private static String sprintVetoNames(int mask) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < SPRINT_VETO_NAMES.length; i++) {
            if ((mask & (1 << i)) != 0) {
                sb.append(sb.length() > 1 ? "+" : "").append(SPRINT_VETO_NAMES[i]);
            }
        }
        return sb.append(')').toString();
    }

    /**
     * LAST WORD on any jump: a bot on a graze beside the void does not leave the ground.
     *
     * <p>Every gate above rules on the PLAN; this one rules on the BOT, which is why closing all seven planner leap
     * moves and all three diagonals still left rung 20 falling — the jump that did it was the executor's own step-up:
     * {@code jumpTag=stepUpFreeze pos=-42.70,102.00,5.30 speedH=0.528 sole=0.000}. Called after the whole decision
     * chain so no branch can route around it, and it tags the refusal rather than vetoing silently: a jump that does
     * not happen and a jump that was never considered look identical in a log, and this run has already paid for that
     * confusion.
     *
     * <p>A helper rather than five lines inline because {@code run()} is grandfathered at 1262 lines and may shrink,
     * not grow.
     */
    private boolean vetoJumpOnGraze(Navigator nav, WorldView world, LivingEntity p, boolean parkour) {
        if (Safety.grazingBesideTheVoid(world, p)) {
            nav.jumpTag = "vetoed: grazing beside the void";
            return true;
        }
        // A leap priced at the sprint-jump maximum, launched from a standstill. The runway rule
        // added earlier is a PLANNER gate — it asks whether the cells behind the launch could be run
        // along, never whether the bot actually ran along them. Measured:
        // "jumpTag=parkour pos=-32.71,18.00,37.28 speedH=0.024 sole=0.339 node=-34,21,37" — two
        // across and three up, fired at a twentieth of walking speed, over the void. Refusing costs
        // a few ticks of walking, which is how the momentum gets there; taking it costs the run.
        if (parkour
                && p != null
                && p.getDeltaMovement().horizontalDistance() < PARKOUR_MIN_TAKEOFF_SPEED
                && Safety.voidBeside(world, BlockPos.containing(p.getX(), p.getY() - 0.5, p.getZ()))) {
            nav.jumpTag = "vetoed: takeoff speed too low";
            return true;
        }
        // A step jump launched with any part of the box still under a ceiling two above the feet
        // rises 0.2 and lands back (live 1546,69,-156: birch leaves over the cell behind the riser
        // cell, the take-off 0.3 short of clearing them). One more grounded tick carries the box out
        // from under it; a bot that is not moving keeps the jump, so a pinned one is never held down.
        if (!parkour
                && p != null
                && p.onGround()
                && !p.isInWater()
                && p.getDeltaMovement().horizontalDistance() >= 0.1
                && headCapped(world, p)) {
            nav.jumpTag = "vetoed: ceiling over the take-off";
            return true;
        }
        return false;
    }

    /** A solid cell two above the feet over any column the player box covers. */
    private static boolean headCapped(WorldView world, LivingEntity p) {
        int y = Mth.floor(p.getY() + 1e-3) + 2;
        for (int x = Mth.floor(p.getX() - 0.3); x <= Mth.floor(p.getX() + 0.2999); x++) {
            for (int z = Mth.floor(p.getZ() - 0.3); z <= Mth.floor(p.getZ() + 0.2999); z++) {
                if (world.isSolid(new BlockPos(x, y, z))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Count an ascent dead-zone, and give up the walk once re-routing provably cannot help.
     *
     * <p>{@code UNREACHABLE} folds into a re-route, which is right when the next plan can differ. Measured on journey
     * rung 20 (2026-08-18) it could not: a bot perched on a 0.16 sole beside the void re-routed for 2400 ticks — the
     * walk's entire budget — and every single re-route returned the identical {@code diagUp} to the identical node,
     * while the footing guard, the stride floor-guard and the recovery hop each correctly declined to move it. Four
     * right answers and no legal move, in total silence, ending as a plain timeout with {@code end=null}.
     *
     * <p>{@link ControlConfig#navigatorFutileSearchCap} structurally cannot cover it: that counter is gated on the
     * search NOT reaching the goal, and here the search reaches it every time — the plan is fine and the bot cannot
     * perform it. That is what the message says, because "no route progress" would be a lie about which half failed.
     *
     * <p>The counter advances only while BOTH the foot cell and the target node are unchanged, so a bot that genuinely
     * shifts keeps its full allowance and a transient dead-zone still re-routes.
     *
     * @return a terminal {@code FAILED} step to return from {@code run()}, or null to carry on
     */
    private TickStep noteDeadZone(Navigator nav, LivingEntity p, BlockPos foot, Move.Edge edge) {
        nav.stallDetect.forceFellOffPath = true;
        BlockPos node = nav.route.path().get(nav.route.step());
        if (foot.equals(nav.search.gov.deadZoneFoot) && node.equals(nav.search.gov.deadZoneNode)) {
            nav.search.gov.deadZoneRepeats++;
        } else {
            nav.search.gov.deadZoneFoot = foot;
            nav.search.gov.deadZoneNode = node;
            nav.search.gov.deadZoneRepeats = 1;
        }
        LOG.info(
                "[navigator] ascend dead-zone UNREACHABLE move={} node={} foot={} pos=({},{},{}) repeats={} → re-route",
                edge.move,
                node,
                foot,
                p.getX(),
                p.getY(),
                p.getZ(),
                nav.search.gov.deadZoneRepeats);
        if (ControlConfig.navigatorAscendDeadZoneCap <= 0
                || nav.search.gov.deadZoneRepeats < ControlConfig.navigatorAscendDeadZoneCap) {
            return null;
        }
        nav.error = "ascent dead-zone " + nav.search.gov.deadZoneRepeats
                + " times from the same cell — the search keeps returning " + edge.move
                + " to " + CellText.of(node) + " and the executor keeps refusing it (foot=" + CellText.of(foot)
                + "); the plan is fine and the bot cannot perform it";
        return nav.terminalReport(
                TickStep.FAILED, PathTrace.Outcome.NO_PATH, nav.error, "failed:" + nav.error, p.blockPosition());
    }

    /**
     * One line per ENGAGEMENT of the hazard-ahead brake, not one per tick.
     *
     * <p>Both lava brakes in {@code run()} used to log behind {@link GameConfig#navigatorDebug}, which the journey
     * never sets — so when the 2026-08-23 rehearsal burned to death, a full log could not say whether either brake had
     * engaged. That is the whole cost of an instrument behind a flag nobody turns on. Both are unconditional now, and
     * they differ only in cadence, which follows from how often each can fire:
     *
     * <ul>
     *   <li>The path-hazard brake can only fire on a WORLD CHANGE (A* never plans through a hazard cell), so it is a
     *       once-per-run event on a static scene → print every time.
     *   <li>This one prices a lava-hugging passage the planner legitimately committed to, and can hold for every tick
     *       of it → print the first tick, then stay quiet until the bot has been clear again. A post-mortem still
     *       learns that the bot crept past lava and where, without 600 identical lines for one mandatory 30-block
     *       corridor.
     * </ul>
     *
     * <p><b>Two cells, both named.</b> This line used to print {@code foot} alone, under the words "creeping past lava
     * at …" — wording that reads as the HAZARD's position and is the BOT's. On 2026-08-26 that cost a rung-12
     * post-mortem its first pass: the bot died at {@code -11,63,19} and the brake row said "lava at -11,63,19", so it
     * was read as "the brake saw lava ahead of the bot" when what it actually said was "the bot is already standing in
     * it". The two readings point at opposite defects. Naming both cells ends it.
     *
     * <p>And two numbers, because "braked" is not "stopped": sneak still creeps ~0.9 b/s, and sprint is gated on
     * {@code !hazardAhead} — so a run that wants to ask "was it carrying sprint momentum?" needs the answer printed
     * here. A full ladder log of 2026-08-26 contained the word "sprint" zero times, which made that question
     * unanswerable rather than answered no.
     */
    private void announceLavaBrake(boolean lavaBrake, BlockPos foot, BlockPos hazard, BlockPos wp, LivingEntity p) {
        if (lavaBrake && !driveLatch.lavaBrakeLogged) {
            // BOT and HAZARD as two named cells, plus the two numbers that decide whether the
            // sneak can hold: speed (sneak still creeps ~0.9 b/s, so "braked" is not "stopped")
            // and sprint (gated on !hazardAhead above — printing it is what turns "was it
            // sprinting?" from a hypothesis into a reading; the 2026-08-26 post-mortem had to
            // withdraw that question because the word never appeared in a full ladder log).
            LOG.info(
                    "[navigator] hazard-ahead brake: bot at {} sneaking past hazard {} "
                            + "(wp {}, speed h={} sprint={} onGround={})",
                    foot.toShortString(),
                    hazard == null ? "?" : hazard.toShortString(),
                    wp.toShortString(),
                    String.format(Locale.ROOT, "%.3f", p.getDeltaMovement().horizontalDistance()),
                    p.isSprinting(),
                    p.onGround());
        }
        driveLatch.lavaBrakeLogged = lavaBrake;
    }

    /**
     * Tick tail: release a dig latch the navigator's own dig set once no dig has driven it for
     * {@link net.magicterra.worlddriver.bot.control.NavigatorConstants#DIG_KEY_RELEASE_TICKS} ticks. Holds other owners
     * set are not ours.
     */
    public void settleDigKey(Navigator nav, Body a) {
        if (digDrivenThisTick) {
            digIdleTicks = 0;
        } else if (digKeyOwned && ++digIdleTicks >= DIG_KEY_RELEASE_TICKS) {
            if (nav.blocks.breakHeld()) {
                nav.blocks.breakHold(false);
            }
            digKeyOwned = false;
            digIdleTicks = 0;
        }
        digDrivenThisTick = false;
    }
}
