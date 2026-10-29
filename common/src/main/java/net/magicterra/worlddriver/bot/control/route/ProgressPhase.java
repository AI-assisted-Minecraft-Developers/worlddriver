package net.magicterra.worlddriver.bot.control.route;

import net.magicterra.worlddriver.bot.control.act.Keys;
import net.magicterra.worlddriver.bot.control.TickStep;
import net.magicterra.worlddriver.bot.control.ResetScope;
import net.magicterra.worlddriver.config.ControlConfig;
import net.magicterra.worlddriver.config.GameConfig;
import net.magicterra.worlddriver.bot.rules.Goal;
import net.magicterra.worlddriver.game.body.Body;
import net.magicterra.worlddriver.bot.rules.Capability;
import net.magicterra.worlddriver.bot.rules.Move;
import net.magicterra.worlddriver.bot.planning.PathFinder;
import net.magicterra.worlddriver.bot.planning.PathTrace;
import net.magicterra.worlddriver.bot.world.WorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;

import static net.magicterra.worlddriver.bot.control.route.PathSmoothing.*;
import static net.magicterra.worlddriver.game.body.BotInteract.*;
import static net.magicterra.worlddriver.game.body.BotUtil.*;
import java.util.Locale;
import static net.magicterra.worlddriver.config.Mod.LOG;
import static net.magicterra.worlddriver.bot.control.NavigatorConstants.*;
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.*;
// Not in NavigatorGeometry: that class is pure functions of their arguments and reads no BotConfig.
// This one consults navigatorPillarSurfacePlace, so it stays with the phase that owns the carve-out.
import static net.magicterra.worlddriver.bot.control.NavigatorGeometry.floodedShaft;
import net.magicterra.worlddriver.bot.control.WalkVerdict;
import net.magicterra.worlddriver.bot.control.Navigator;
import net.magicterra.worlddriver.bot.control.NavigatorGeometry;
import net.magicterra.worlddriver.bot.control.TickCtx;


/**
 * Mechanical extraction of {@code Navigator#tickInner} (pre-split lines 1647–2347): progressive quick-start hold, stall clocks, step-advance loop, arc-length shadow.
 * One phase of the per-tick pipeline — see {@link Navigator#tickInner} for the driver and
 * {@link TickCtx} for the shared per-tick locals. Bodies are UNCHANGED from the
 * original tick body except for the {@code w.}/{@code Navigator.} member prefixes; do not
 * restructure here without live/testkit evidence (this file is state-machine surgery).
 */
public final class ProgressPhase {
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
     * <p>{@code soleOnSolid} is {@link NavigatorGeometry#soleOnSolid}, the predicate the navigator's
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
     * {@code GameConfig.navigatorDebug}, which that scene switches off.
     */
    public void noteStepAdvance(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, BlockPos w, BlockPos nx,
                         String cause, double cur2, double nd2, boolean overshot) {
        if (seenLegEpoch != nav.tel.legEpoch) {             // a new walk: fresh budget, see NavigatorTelemetry#newLeg
            seenLegEpoch = nav.tel.legEpoch;
            stepAdvEvents = 0;
            stepAdvCapped = false;
        }
        if (stepAdvEvents >= STEP_ADV_EVENTS) {
            if (!stepAdvCapped) {
                stepAdvCapped = true;
                LOG.info("[navigator] step advance: seq={}+/{} cap reached, later events are not logged (printed once)",
                        STEP_ADV_EVENTS + 1, STEP_ADV_EVENTS);
            }
            return;
        }
        stepAdvEvents++;
        nav.tel.stepAdvancesLogged++;
        LOG.info("[navigator] step advance: seq={}/{} cause={} oldStep={} newStep={} w={} nx={} foot={} exact=({}) "
                        + "cur2={} nd2={} overshot={} |w.y-p.y|={} |nx.y-p.y|={} onGround={} soleOnSolid={} fallSpeed={}",
                stepAdvEvents, STEP_ADV_EVENTS, cause, nav.route.step(), nav.route.step() + 1,
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
     * <p>Sixty-four, and the number is measured rather than chosen. It was 8 <b>per navigator</b>, and
     * a navigator outlives a whole rung: on the 2026-08-20 ladder the budget was spent in the first
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
    public static final int STEP_ADV_EVENTS = 64;

    private int stepAdvEvents;

    /** The walk this navigator last refreshed its advance budget for — see {@link NavigatorTelemetry#newLeg}. */
    private int seenLegEpoch = -1;

    /** One-shot latch for the "the cap swallowed an advance" line — see {@link #noteStepAdvance}. */
    private boolean stepAdvCapped;

    /** Ticks the pointer has been HELD on a final node that is the goal while the foot is not yet
     *  in it (navigatorHoldLastNodeUntilStanding); keyed to the path and step it counts for. */
    public int finalNodeHold;
    public Object finalNodeHoldPath;
    public int finalNodeHoldStep = -1;

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
     *  <p>Physical-stall anchor (task#96 step B7), owned by ProgressPhase; the stall
     *  verdict is read by the aim/drive gates. Self-managing (re-anchors on real motion)
     *  — no journey reset ever touched it. */
    public final PhysicalStall physStall = new PhysicalStall();

    /** Per-step progress clocks (task#96 step B6), owned by ProgressPhase (the
     *  per-tick updater); their verdicts feed StallDetect's wedge/ram folds and the
     *  aim/drive stall gates, and PreludePhase forwards noStepProgressTicks to the
     *  expectation alarms. {@link StepProgress#restartWindows()} is the journey reset
     *  (start + replan — exactly the five fields both cleared); adoption and
     *  beginScriptedFollow keep their DIFFERENT partial restarts as direct writes
     *  (adoption re-bases stuckStepHigh to step-1, not -1). The dwell/orbit counters
     *  self-manage on step/path change inside ProgressPhase. */
    public final StepProgress stepProg = new StepProgress();

    /** Phase-3: |ds| (blocks of arc-length per tick) at/under which the bot counts as making NO forward
     *  path progress. A normal walk is ~1.0/tick, the slowest legit water-creep still clears ~0.1, so 0.05
     *  flags a true wall-ram (literally pinned) without misfiring on slow-but-moving travel. */
    private static final double ARC_WEDGE_DS = 0.05;
    /** Phase-3: consecutive ARC_WEDGE_DS+horizontalCollision ticks before the arc-wedge folds into fellOffPath.
     *  30 ticks (1.5 s) cuts the legacy ~100-tick (5 s) wedge wait by 70% while sitting well past a legit
     *  stepUp/diagUp jump-mount (which presses the riser only ~5-10 t before it tops out and ds jumps). */
    public static final int ARC_WEDGE_TICKS = 30;
    /** Arc-length shadow pursuit (task#96 step B8): updated ONLY by {@link #arcShadowTick}
     *  (called per tick from ProgressPhase); the wedge/stall verdicts are consumed by
     *  StallDetect/Drive, the projection by Aim/Progress. NO journey reset — everything
     *  self-resets on a path-identity change (shadowPath != path), and Progress clears the
     *  windows on a no-projection tick. */
    public final ArcShadow arc = new ArcShadow();

    /** Restart the per-step windows; the stall anchor and the arc shadow re-base themselves. */
    public void reset(ResetScope scope) {
        stepProg.restartWindows();
    }

    /** Arc-length projection, the projector behind the advance, tangent aim and wedge flags. Projects the continuous foot XZ onto the
     *  path polyline within a FORWARD window [step, step+W] (so it can't snap backward onto a self-overlapping
     *  earlier segment — the dominant projection risk), honouring the adoption's overlap barriers (a submerged
     *  below-node is a hard dive barrier; a pending break/place edge stops the scan). Reports the projected
     *  segment, cumulative XZ arc-length s (from path[0]), horizontal perpendicular distance, and the tangent
     *  heading at s+lookahead — and counts backward-snap events (ds&lt;-0.5). DRIVES NOTHING; this only proves
     *  (via replay) that s is monotonic and the projected segment tracks the live {@code step} on clean runs,
     *  the precondition for Phase 1 driving the step pointer off the projection. */
    private void arcShadowTick(Navigator nav, WorldView world, BlockPos foot, double px, double pz, float liveYaw, boolean inW, boolean hCol) {
        arc.proj.compute(nav.route.path(), nav.route.edges(), world, nav.route.step(), foot.getY(), px, pz, 16, 2.5);
        // Monotonic / backward-snap check vs the previous tick (reset on a fresh path object).
        boolean reset = (nav.route.path() != arc.shadowPath);
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
        arc.shadowPath = nav.route.path();
        arc.shadowS = arc.proj.s;
        float dYaw = angleDiff(arc.proj.tangentYaw, liveYaw);    // the camera-vs-tangent gap
        if (GameConfig.navigatorDebug)
            LOG.info("[navigator] arc-shadow step={} projSeg={} segFrac={} s={} ds={} perp={} tanYaw={} liveYaw={} dYaw={} monoViol={} inW={}",
                    nav.route.step(), arc.proj.segIdx, String.format("%.2f", arc.proj.segFrac), String.format("%.2f", arc.proj.s),
                    String.format("%.2f", ds), String.format("%.2f", arc.proj.perp),
                    String.format("%.1f", arc.proj.tangentYaw), String.format("%.1f", liveYaw),
                    String.format("%.1f", Math.abs(dYaw)),
                    arc.monoViol, inW);
    }


    /** Reach (squared) for a grounded bot level with a node a climb enters or leaves: the centre may sit
     *  0.85 out, i.e. the 0.3-half-width box still over the node cell. */
    private static final double LEVEL_CLIMB_REACH_SQ = 0.72;

    /** Grounded and level with the node, its box still over the node cell, with a climb out of it, or a climb
     *  into it and a long flat leg out: reached. A diagUp that lands 0.08 past the cell edge (live
     *  1544.92,71,-158.15 at node 1545,71,-159, cur2 0.455) read unreached, took the next riser's jump from
     *  beside it and walked back, ~1 s per run; one landing at cur2 0.456 with a 13-block walk next ran on
     *  unreached until an off-path search re-routed the whole leg (1482,66,-309). A short leg out keeps the
     *  tight gate: consuming every stair landing early started each next riser off-centre, 4-6 s per cliff.
     *  Only for a bot that has come up to the node: one still short of it along the incoming leg keeps the
     *  tight gate. On a 1-wide strip (wd.bridgeStepTwoBypassNoPlace) a bot 0.76 short took the next diagUp
     *  from there, cut across the void beside the strip and was pinned at its edge. */
    private boolean levelAtClimb(Navigator nav, LivingEntity p, BlockPos w, double dyNode, double cur2) {
        if (!p.onGround() || p.isInWater() || Math.abs(dyNode) >= 0.05 || cur2 >= LEVEL_CLIMB_REACH_SQ) return false;
        if (shortOfNode(nav, p, w)) return false;
        if (nav.route.step() + 1 >= nav.route.path().size()) return false;
        BlockPos next = nav.route.path().get(nav.route.step() + 1);
        if (next.getY() > w.getY()) return true;
        boolean climbIn = nav.route.step() > 0 && nav.route.path().get(nav.route.step() - 1).getY() < w.getY();
        return climbIn && Math.max(Math.abs(next.getX() - w.getX()), Math.abs(next.getZ() - w.getZ())) >= 2;
    }

    /** Behind {@code w} along the leg from the previous node by more than the tight gate's radius. */
    private boolean shortOfNode(Navigator nav, LivingEntity p, BlockPos w) {
        if (nav.route.step() == 0) return false;
        BlockPos a = nav.route.path().get(nav.route.step() - 1);
        double ax = w.getX() - a.getX(), az = w.getZ() - a.getZ(), len = Math.hypot(ax, az);
        if (len == 0) return false;
        double along = ((p.getX() - (w.getX() + 0.5)) * ax + (p.getZ() - (w.getZ() + 0.5)) * az) / len;
        return along < -Math.sqrt(REACH_DIST_SQ);
    }

    /** The step-advance gates, in the order {@link #advanceCause} is handed their flags. */
    private static final String[] ADVANCE_NAMES = {
            "within", "passed", "tail", "crossDesc", "crossWalk",
            "waterStepDown", "waterWalk", "arc", "dropAhead"};

    /** [STEP-ADV-DIAG temp — remove before commit] why a grounded grossly-overshot node won't
     *  advance (-823 dimple churn): logs which advance fired + the descend-geometry sub-conditions.
     *  Lifted out of {@link #run} verbatim (same gate, same fields, same order) so the step-advance
     *  reading could be added without growing that method past its source budget. */
    private void stepAdvDiag(Navigator nav, LivingEntity p, BlockPos foot, BlockPos w, Move.Edge se,
                                    boolean within, boolean passed, boolean crossedDescendNode,
                                    boolean crossedWalkNode, double cur2) {
        if (!(GameConfig.navigatorDebug && !within && p.onGround() && cur2 > OVERSHOOT_RESYNC_SQ
                && stepProg.noStepProgressTicks > 6 && nav.route.step() + 1 < nav.route.path().size())) return;
        BlockPos dN = nav.route.path().get(nav.route.step() + 1);
        double dsegx = dN.getX() - w.getX(), dsegz = dN.getZ() - w.getZ();
        double doffx = p.getX() - (w.getX() + 0.5), doffz = p.getZ() - (w.getZ() + 0.5);
        LOG.info("[navigator] STEP-ADV-DIAG step={}/{} w={} cur2={} footNodeDy={} se={} disc={} | passed={} crossDesc={} crossWalk={} | nx={} nxY={} pY={} fwdDot={} nxYgate={}",
                nav.route.step(), nav.route.path().size(), w, String.format("%.2f", cur2),
                foot.getY() - w.getY(), (se != null && se.move != null ? se.move : "null"),
                (se != null && se.move != null && (se.move.startsWith("fall") || se.move.equals("stepDown"))),
                passed, crossedDescendNode, crossedWalkNode,
                dN, dN.getY(), String.format("%.2f", p.getY()),
                String.format("%.2f", doffx * dsegx + doffz * dsegz),
                Math.abs(dN.getY() - p.getY()) < 1.2);
    }

    /**
     * Every advance gate that was TRUE, joined with {@code +}; {@code other} when none was.
     *
     * <p>Not a ternary chain. A chain ending in a real gate name labels every case its earlier arms
     * failed to match as that last gate, so it reports a confident branch name for a tick nobody
     * described and can never say that the labels have drifted from the expression they name. Two
     * gates firing on the same tick is a real state here (a node can be both reached and passed),
     * and it prints as the two of them rather than as whichever the author happened to test first.
     * {@code other} is the reading that means this list is now short a term — believe nothing else on
     * that line's causal claim until the list is fixed.
     */
    private static String advanceCause(boolean... fired) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fired.length && i < ADVANCE_NAMES.length; i++) {
            if (!fired[i]) continue;
            if (sb.length() > 0) sb.append('+');
            sb.append(ADVANCE_NAMES[i]);
        }
        return sb.length() == 0 ? "other" : sb.toString();
    }

    /** Ticks of stalled step progress after which a far-ahead best-effort tail stops being
     *  held (navigatorTailConsumeDirectional) and the legacy distance-consume applies — a
     *  wedge-scale window: long enough to ride out a slow but real approach, short enough
     *  that walled pockets re-enter the repath/churn machinery promptly. */
    private static final int TAIL_HOLD_STALL_TICKS = 30;

    /** Distance alone reads a tail still far ahead as blown past, so hold it until the foot is
     *  beyond it along the incoming segment. A stalled approach is not held, since walled pockets
     *  escape through the consume, and neither is a vertical segment, which has no heading. */
    private boolean tailStillAhead(Navigator nav, LivingEntity p, BlockPos w) {
        if (!ControlConfig.navigatorTailConsumeDirectional || nav.route.step() == 0
                || stepProg.noStepProgressTicks > TAIL_HOLD_STALL_TICKS) return false;
        BlockPos pv = nav.route.path().get(nav.route.step() - 1);
        double sgx = w.getX() - pv.getX(), sgz = w.getZ() - pv.getZ();
        if (sgx == 0 && sgz == 0) return false;
        double ofx = p.getX() - (w.getX() + 0.5), ofz = p.getZ() - (w.getZ() + 0.5);
        return ofx * sgx + ofz * sgz <= 0;
    }

    /**
     * A bot in mid-air must not spend a path node on a climb it has not made.
     *
     * <p>The nine advance gates each answer "has the bot reached node {@code w}?" with a horizontal
     * test plus a vertical one taken <b>at this instant's y</b>, and mid-jump this instant's y is a
     * lie about where the bot will be standing. Consuming {@code w} at the apex points the navigator
     * at {@code nx}; if {@code nx} is higher than {@code w} and the bot then lands back on {@code
     * w}'s floor, the pointer is now one step further AND one block higher than anything the bot can
     * reach — a {@code +2} that {@code StepUp2}'s own gate says A* never plans. Measured on
     * {@code wd.buriedOre} (four consecutive advances, all airborne; this is advance 3 of them):
     *
     * <pre>
     * seq=3 cause=within oldStep=3 newStep=4 w=106659,223,99999 nx=106660,224,100000
     *      exact=(106659.527,223.252,100000.151) cur2=0.425 (&lt; REACH_DIST_SQ=0.45)
     *      |w.y-p.y|=0.252 |nx.y-p.y|=0.748 onGround=false soleOnSolid=0.0000
     * </pre>
     *
     * The bot landed at y=222 and spent the rest of the scene jumping at a node it tops out one
     * block under. Note the cause: {@code within}, whose vertical clause is {@code |dyNode| < 1.2}
     * and which has no ground test of any kind. Refuse at the ONE {@code step++} instead of inside
     * each gate, so {@code within}/{@code passed}/{@code tail}/{@code arc} are all covered by one
     * predicate that cannot fall out of step with them.
     *
     * <p>The three terms, and why each is exactly this and not something adjacent:
     *
     * <ul>
     *   <li><b>{@code nx.y > w.y} only.</b> Flat and descending continuations are untouched — a bot
     *       airborne over a walk or a drop is going where the pointer says regardless of when the
     *       node is consumed. Only a RISING continuation can strand the pointer above the bot.</li>
     *   <li><b>{@code !p.isInWater()} is load-bearing, not defensive.</b> A buoyant bot reads
     *       {@code onGround=false} and {@code soleOnSolid=0} for the whole of every crossing, and a
     *       flat surface swim advances on {@code passed} every few ticks. Without this term the
     *       water family would not stall occasionally — it would never advance again.</li>
     *   <li><b>{@code soleOnSolid < FOOTING_MIN}, not {@code !onGround()}.</b> Same predicate as
     *       {@code Navigator}'s footing guard and {@code TowerProcess}'s footing check, so this
     *       introduces no further opinion about what standing means. {@code onGround} describes the previous {@code move()} and is
     *       wrong in both directions.</li>
     * </ul>
     *
     * <p><b>Part of what this does is move the failure, deliberately.</b> With the pointer held on
     * {@code w} the navigator keeps driving the bot at a cell the arena's own audit says is standable;
     * if it genuinely cannot get there, {@code noStepProgressTicks} accumulates and the wedge/repath
     * machinery takes it from there. That is the entire gain: the old behaviour was a silent chase of
     * an unreachable target, silent precisely because every counter that could have complained sees a
     * pointer that keeps <i>advancing</i>. The new behaviour is a deterministic failure that reports
     * itself.
     *
     * <p>A sibling of the same defect is NOT fixed here and should be read as still suspect:
     * {@code within}'s {@code |dyNode| < 1.2}, which decides a vertical question from the y the bot
     * happens to hold this tick. This guard sits at the advance OUTLET, so it covers it; its own
     * criterion remains wrong.
     *
     * <p>A bot whose sole sits exactly on {@code w}'s row with any solid under it has landed on
     * {@code w}, however little of the sole that is: the next climb starts from this row. Live R1
     * 1545,71,-159: a diagUp landed on 0.11 of the node block's corner, the guard held the pointer,
     * and the drive jumped at the node it was already standing on, twice (~25 ticks, 4 runs in 6).
     * Not when {@code nx} is stacked over {@code w}: the next rung is placed under the bot, so it
     * has to be over the column, not on a corner of it ({@code wd.buoyantWall}: a pillar climbed off
     * its rung's corner drifted a block and the top jump fell back into the water).
     */
    private static boolean airborneClimbConsume(WorldView world, LivingEntity p, BlockPos w, BlockPos nx) {
        if (nx == null || nx.getY() <= w.getY() || p.isInWater()) return false;
        double sole = NavigatorGeometry.soleOnSolid(world, p);
        boolean stacked = nx.getX() == w.getX() && nx.getZ() == w.getZ();
        if (ControlConfig.navigatorCornerFootingConsume && !stacked && sole > 0 && Math.abs(p.getY() - w.getY()) < 1e-3) return false;
        return sole < FOOTING_MIN;
    }

    /**
     * A bot that is not standing must not spend a DRY final node — spending it reports an arrival
     * for a bot still in the air or still in the water.
     *
     * <p>Measured on the real client, {@code wd.clientFlushBankClimbOut} (2026-09-04): the stepUp out
     * of the pool consumed the bank node mid-jump —
     *
     * <pre>
     * step advance: cause=within oldStep=2 newStep=3 w=102566,221,100000 nx=none(last node) exact=(102565.835,221.549,100000.500)
     *      cur2=0.442 |w.y-p.y|=0.549 onGround=false soleOnSolid=0.0000 fallSpeed=0.1012
     * </pre>
     *
     * and the walk ended {@code path-consumed} with the bot hanging over the water's edge, which is
     * the shape TODO J47 describes. {@link #airborneClimbConsume} cannot see it: that guard is scoped
     * to a RISING continuation and excludes water. This one is scoped the way
     * {@link #unwalkedDescentConsume}'s final-node arm is — only when spending the node would report
     * an arrival ({@code goal.reached(w) && !goal.reached(foot)}) — so a best-effort tail and a bot
     * already in its goal are untouched, and a water final node keeps the floating arrival it has
     * always had. Holding costs nothing here: the bot lands within a few ticks, on the bank or back
     * in the water, and the stepUp drive simply continues.
     *
     * <p><b>Scoped to a hop OUT OF WATER</b> — the foot cell or the cell under it is water. The first
     * version held every dry final node whose sole read empty, and {@code wd.serverMineProcess} showed
     * what that costs on land: a bot stepping from one stone top across a one-cell gap to the next
     * reads {@code onGround} true and a sole of 0 for three ticks while {@code within} is true, the
     * hold ate those three ticks, the bot overshot the node by a block and never came back —
     * {@code no reachable target}. Over water the hold is cheap because the bot cannot go anywhere
     * but down; over land the same hold turns a consumed node into a missed one.
     */
    private boolean airborneDryArrival(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, BlockPos w, BlockPos nx) {
        return nx == null && !world.isWater(w)
                && (world.isWater(foot) || world.isWater(foot.below()))
                && nav.goal.reached(w) && !nav.goal.reached(foot)
                && NavigatorGeometry.soleOnSolid(world, p) < FOOTING_MIN;
    }

    /**
     * A bot standing on a floor must not spend a path node that lies below that floor.
     *
     * <p>The descending twin of {@link #airborneClimbConsume}, and the sibling that helper's javadoc
     * names and declines to fix: {@code within}'s vertical clause is {@code |dyNode| < 1.2}, so a
     * waypoint a FULL BLOCK under the feet reads as reached. Climbing and descending fail the same
     * way and cost differently — a stranded climb leaves the bot jumping at a node it tops out under,
     * a spent descent leaves it with no plan at all, because a descent is usually the last node or two
     * of a short segment and consuming it exhausts the path.
     *
     * <p>Measured on nether rung 14 (2026-08-19), the crossing's terminal wedge. The bot finished a
     * dug shaft perched on {@code 159,53,187} — a cell whose own floor is air, held up by 0.125 of
     * 0.36 of sole on the corner of {@code 158,52,187}, with a column three cells away that ends in
     * the lava lake at {@code y=50}. The footing guard sneak-pinned it, correctly. A* answered with
     * the three-node way out, {@code [158,53,187 → 159,52,187 → 159,51,188]}, every cell of it
     * standable; the navigator consumed all three in ONE tick without moving:
     *
     * <pre>{@code
     * step advance: seq=1/8 cause=within oldStep=1 newStep=2 w=158,53,187 nx=159,52,187 foot=(159.092,53.000,187.700) cur2=0.390 |w.y-p.y|=0.000
     * step advance: seq=2/8 cause=within oldStep=2 newStep=3 w=159,52,187 nx=159,51,188 foot=(159.092,53.000,187.700) cur2=0.207 |w.y-p.y|=1.000
     * }</pre>
     *
     * The second line prints {@code |w.y-p.y|=1.000} and advances on it. The pointer descended, the
     * bot did not, and the plan was gone. What follows is all downstream: {@code path == null} makes
     * every tick a safety repath, so A* is re-asked from the same cell ~3,500 times over four hops
     * (20 searches a second in {@code latest.log}), and 3,517 of the run's 3,551 no-plan ticks are
     * those four hops standing at that one coordinate.
     *
     * <p><b>Holding the pointer is what releases the pin.</b> {@link Safety#footingGuard}'s
     * planned-descent exemption asks whether {@code path.get(step).getY() < foot.getY()} — which is
     * true exactly while this node is held and false the moment it is spent. The old behaviour spent
     * the node, so the exemption stopped applying and the sneak re-engaged over a step the route meant
     * to take. The two mechanisms only compose the right way round.
     *
     * <p>The terms, for the same reasons {@link #airborneClimbConsume} lists:
     *
     * <ul>
     *   <li><b>Cell comparison, not a y delta.</b> {@code w.getY() < foot.getY()} asks whether the
     *       node is in a lower CELL than the bot, which is the question a step-down is; a float
     *       threshold would re-introduce the {@code |dyNode| &lt; 1.2} arbitrariness this exists to
     *       remove.</li>
     *   <li><b>{@code soleOnSolid > 0}, not {@code onGround()}.</b> A bot actually falling toward
     *       the node is going there and must keep advancing; a bot with anything under it has not
     *       descended yet. Zero is the bar rather than {@code FOOTING_MIN} on purpose — the rung-14
     *       bot was on 0.125 of a sole, well under that threshold, and it was emphatically not
     *       falling. Same reading {@code footingGuard} opens with, so no fifth opinion about
     *       standing.</li>
     *   <li><b>{@code !p.isInWater()}.</b> A buoyant bot reads no sole for a whole crossing and
     *       descends by sinking; the water gates own that.</li>
     *   <li><b>The LAST node counts too, but only when consuming it would declare an arrival the bot
     *       has not made.</b> This shipped scoped {@code nx != null} — mid-path nodes only — on the
     *       reading that the pointer reaching {@code path.size()} merely hands the segment to the
     *       navigator's arrival handling. That is true when the bot is already at the node and false
     *       when the last node is a step DOWN it has not taken: then "arrival" is not the remaining
     *       work, the step across is. Journey rung 13 (2026-08-20) mined its one way into a lit portal
     *       and could not walk the last cell. A* answered {@code Goal.Block(3,58,19)} with the one-step
     *       plan {@code [3,59,18 → 3,58,19]}, its only node both first and last, and the navigator spent
     *       it on the tick it was adopted:
     *
     *       <pre>{@code
     *       step advance: seq=1/8 cause=within oldStep=1 newStep=2 w=3,58,19 nx=none(last node) foot=(3.463,59.000,18.939)
     *            cur2=0.316 |w.y-p.y|=1.000 onGround=true soleOnSolid=0.2168
     *       }</pre>
     *
     *       The segment ended {@code path-consumed} with the goal unreached and the bot where it
     *       started — two consecutive goto runs (one XZ, one 3D) moved it by zero cells — and
     *       re-asking got the identical plan. Reproduced verbatim in
     *       {@code wd.serverStepsDownTheLastNodeOfItsPlan}, whose pre-fix arms both reported
     *       0.00 blocks walked and {@code end=path-consumed}.
     *
     *       <p><b>The final-node case is scoped twice over, and each term was bought.</b> Both were
     *       measured on the dedicated-server gate rather than argued:
     *
     *       <ul>
     *         <li>{@code nav.goal.reached(w) && !nav.goal.reached(foot)} — spending this node would
     *             report an arrival that has not happened. Without it (hold every final node below
     *             the feet) {@code wd.serverFightsAFlyingBlaze} wedged the server thread for
     *             <b>60 seconds</b> and took a watchdog crash: that scene pumps ~3 000 navigator ticks
     *             inside ONE server tick, so a held node that never resolves is a pathfinder search
     *             per iteration. The clause excludes it because a BEST-EFFORT segment's last node
     *             does not satisfy the goal — it is a splice point, not an arrival — and a bot
     *             already inside its goal has nothing left to walk. It is deliberately the same
     *             question the disk-goal hold below asks, extended from radius-{@code >0} goals.</li>
     *         <li>{@code foot.getY() >= nav.path.get(0).getY()} — the bot has not gone DOWN under
     *             this plan, so the descent the plan ends in has not been taken. Without a term of
     *             this kind the hold reached every ordinary descent's last node — 299 firings inside
     *             {@code wd.descent} alone in one gate run — a blast radius this reading does not
     *             justify, and one that put {@code wd.serverMineHarvest} red across three runs.
     *
     *             <p><b>Two narrower terms were tried first and both were wrong, each measured.</b>
     *             {@code nav.step == 1} — a POINTER index standing in for "the bot has not walked
     *             this plan" — failed on rung 13's next run: A* answered {@code Goal.Block(3,57,20)}
     *             from {@code 3,58,20} with a TWO-node plan, {@code [3,58,21 → 3,57,20]} (sideways
     *             onto the standable cell beside the bot, then down), and the navigator spent BOTH in
     *             one tick, at the same bot coordinates to three decimals:
     *
     *             <pre>{@code
     *             step advance: seq=1 cause=passed oldStep=1 newStep=2 w=3,58,21 nx=3,57,20 foot=(3.700,58.000,20.794) soleOnSolid=0.0563
     *             step advance: seq=2 cause=within oldStep=2 newStep=3 w=3,57,20 nx=none(last node) foot=(3.700,58.000,20.794) |w.y-p.y|=1.000
     *             }</pre>
     *
     *             {@code oldStep=2} on the deciding line, so no index test can see it. The replacement,
     *             {@code foot.equals(path.get(0))}, held for two ticks and then released one step too
     *             early — measured in the arena, {@code held1 held2} and then the bot took the plan's
     *             LATERAL first node, changed cell, and the guard let the descent be spent:
     *
     *             <pre>
     *             0:239776,211,100000 step2/3 node239776,210,100000 held1
     *             2:239776,211,100001 step3/3 nodeNone           held2   ← moved sideways, hold gone, plan gone
     *             </pre>
     *
     *             Walking a plan's flat nodes is not taking its descent, so the reading is the ROW.
     *             Both arenas pin it: {@code wd.serverStepsDownTheLastNodeOfItsPlan} (one-node plan)
     *             and {@code wd.serverStepsDownAPlanItSpentInOneTick} (two-node).</li>
     *         <li>{@code stepProg.noStepProgressTicks == 0} — the bot is still CLOSING on this
     *             node <i>this tick</i>. The row term alone is not enough: it is a statement about
     *             the plan, and a bot can satisfy it forever while standing perfectly still.
     *
     *             <p><b>That is what widening the scope cost, and it was measured rather than
     *             guessed.</b> A temporary per-firing probe over a filtered gate run
     *             ({@code -Pstagewright.scenes=…}) counted final-node holds and printed the stall
     *             clock and the sole at each:
     *
     *             <pre>
     *             wd.serverMineHarvest   100 holds  stall 0→27 (a smooth ramp)  sole 0.0015..0.0041  y=222.000 at ALL 100
     *             the two descent arenas   9 holds  stall 0 on 8 of 9, max 13   sole 0.0180..0.2631  the bot shuffling and dropping
     *             </pre>
     *
     *             Identical shape, opposite meaning. mineHarvest is the stride-floor-guard deadlock
     *             this javadoc already records, re-entered once per plan and now sat in for 27 ticks
     *             instead of released: the bot never moved a millimetre across a hundred holds, and
     *             the scene went from 113 ticks and green to 191 and {@code broke 2/4}. The arenas
     *             are the opposite — the stall clock RESETS on eight of nine holds, i.e. the bot got
     *             closer to the node on that very tick, which is the hold doing its job.
     *
     *             <p>So the tail branch does not reuse {@link #TAIL_HOLD_STALL_TICKS}: over the same
     *             run, {@code == 0} keeps 8 of 9 arena holds and 12 of 100 mineHarvest ones. A hold
     *             may extend an approach that is working; it may not outlive one that has stopped.
     *             {@code wd.serverMineHarvest} is the standing witness — 64 archived gate runs at
     *             104..125 ticks, and every widening without this term put it at 190+.</li>
     *       </ul></li>
     *   <li><b>{@link #TAIL_HOLD_STALL_TICKS} of stalled step progress releases it</b>, and that
     *       bound is not defensive — the first cut had no bound and turned {@code wd.serverMineHarvest}
     *       red: at {@code 100011,222,100000} the stride floor-guard refuses the very stride this hold
     *       insists on ({@code bottomless stride … plug FAILED}), so the two sat on each other and the
     *       sweep gave up with {@code broke 2/4}. A hold that outlives a guard's refusal of the same
     *       step is a deadlock by construction. Reusing the tail's own stall window rather than
     *       inventing a second one: it is already the file's answer to "long enough to ride out a slow
     *       but real approach, short enough that walled pockets re-enter the repath machinery
     *       promptly", and the two arenas below need a small fraction of it (the staircase holds ~1
     *       tick per tread, the perch 20 ticks over a 260-tick drive).</li>
     * </ul>
     */
    private boolean unwalkedDescentConsume(Navigator nav, WorldView world, LivingEntity p,
                                                  BlockPos foot, BlockPos w, BlockPos nx) {
        boolean unwalked = ControlConfig.navigatorDescentNodeHold
                && w.getY() < foot.getY()
                && !p.isInWater()
                && NavigatorGeometry.soleOnSolid(world, p) > 0.0
                && stepProg.noStepProgressTicks <= TAIL_HOLD_STALL_TICKS
                // A dip the bot stepped over: the plan goes down to w and straight back up to the
                // bot's own row, so there is no descent left to strand (live 1539,71,-180 held with the
                // bot beside it on the 72 row it rejoins, recovery-hopping until the stall released it).
                && !(nx != null && nx.getY() == foot.getY() && losWalkable(world, foot, nx));
        // Mid-path, hold outright: spending the node strands the pointer for the rest of the plan.
        // At the LAST node, three terms, each bought on the gate — see the javadoc:
        //   · the bot has not gone DOWN under this plan (its row is still at or above the row the
        //     plan was searched from), because that is what "the descent has not happened" means and
        //     lateral progress along the plan does not make it happen;
        //   · the bot is still CLOSING on the node this tick, so a hold can only ever extend an
        //     approach that is working, never outlive one that has stopped;
        //   · and the plan's end satisfies the goal while the bot does not.
        boolean held = unwalked && (nx != null
                || (foot.getY() >= nav.route.path().get(0).getY()
                        && stepProg.noStepProgressTicks == 0
                        && nav.goal != null && nav.goal.reached(w) && !nav.goal.reached(foot)));
        if (held) nav.tel.descentHolds++;
        return held;
    }

    /** Horizontal reach (squared) at which a best-effort tail counts as reached once its continuation waits. */
    private static final double TAIL_READY_SQ = 1.0;

    /** A best-effort tail whose continuation has already landed is a splice point, not a target: standing on its
     *  row within a block of it is reach enough. Held to the 0.45 gate, a bot that landed a diagUp 0.7 past R1's
     *  1387,77,-509 circled it, the bearing sweeping round and the camera 320° with it, in every run. */
    private boolean tailReady(Navigator nav, LivingEntity p, BlockPos foot, BlockPos w, double cur2) {
        PathFinder.Result next = nav.route.seg.pendingSegment;
        return next != null && next.hasPath() && p.onGround() && !p.isInWater()
                && foot.getY() == w.getY() && cur2 < TAIL_READY_SQ;
    }

    /** How many nodes past the current one {@link #dropAheadTarget} may skip to. */
    private static final int DROP_AHEAD_NODES = 4;

    /**
     * A descent the body took a row early. A body carrying momentum down a turning staircase steps
     * off the upper tread beside its node and grounds on the row the plan reaches only a node or two
     * later; holding the node above then costs a jump back up and a second drop (live 1436,64,-352 on
     * every R1 run: ~1.5 s hopping on a row the plan was about to leave). Returns the index of the
     * later node on the foot's row that the body can walk straight to, or -1. Only grounded and dry,
     * with the current node exactly one row up and every edge in between a plain walk or descent.
     */
    private int dropAheadTarget(Navigator nav, WorldView world, LivingEntity p, BlockPos foot) {
        if (!ControlConfig.navigatorDropAheadResync || foot == null || nav.route.path() == null || nav.route.step() >= nav.route.path().size()
                || p.isInWater() || !p.onGround() || nav.route.path().get(nav.route.step()).getY() != foot.getY() + 1) return -1;
        int prevY = foot.getY() + 1;
        for (int k = nav.route.step() + 1; k < nav.route.path().size() && k <= nav.route.step() + DROP_AHEAD_NODES; k++) {
            BlockPos n = nav.route.path().get(k);
            Move.Edge e = nav.route.edgeAt(k);
            if (n.getY() > prevY || n.getY() < foot.getY() || e == null || e.move == null
                    || !(e.move.equals("walk") || e.move.equals("stepDown") || e.move.equals("diagDown"))
                    || !e.toBreak.isEmpty() || !e.toPlace.isEmpty()) return -1;
            if (n.getY() == foot.getY() && losWalkableBody(world, p.getX(), p.getZ(), foot, n)) return k;
            prevY = n.getY();
        }
        return -1;
    }

    /**
     * A node STRAIGHT ABOVE THE BOT'S OWN COLUMN as the next one. Both spend-gates admit a +1
     * current node because a jump reaches a +1 beside the bot; neither can tell that from a +1 the
     * bot has not climbed whose successor sits over its head, which nothing reaches without a
     * block under the feet. wd.clientThreeHighBankPlaceOut: w was a +1 stepUp beside the bot, nx
     * the goal one block straight up; `within` read the stepUp as reached at cur2 0.449 / |dY| 1.0
     * with the bot still below it, and the pointer sat on a bridgePlace whose support cell was the
     * bot's own foot cell — 400 ticks of placing into itself. The stacked-above-w tie-break in the
     * passed block protects the pillar BASE; this protects the pillar itself, in both gates.
     */
    private boolean unclimbedUnderOverhead(Navigator nav, BlockPos foot, BlockPos w) {
        BlockPos nx = nav.route.step() + 1 < nav.route.path().size() ? nav.route.path().get(nav.route.step() + 1) : null;
        boolean nxOverhead = nx != null && nx.getY() > foot.getY()
                && nx.getX() == foot.getX() && nx.getZ() == foot.getZ();
        return nxOverhead && w.getY() > foot.getY();
    }

    /** A node the bot went PAST at arm's length: across the plane through it square to the incoming
     *  segment, within {@link NavigatorConstants#SKIRT_PASS_SQ}, and with a walkable line on to the next
     *  node. The halfway rule ({@code nd2 < cur2}) alone waits until the bot is nearer the NEXT node,
     *  so a node skirted 1.1 blocks to the side of a long pulled diagonal, followed by a 5-block leg,
     *  stayed current for half that leg; the stall clock ran up to a recovery hop on every such
     *  corner (live 2026-09-28 R1: 14 hops, node 1552,64,-132 passed at cur2 1.2, advanced at 8.1).
     *  Flat only: a riser skirted from below would hand the bot a next node it has not climbed to.
     *  Already riding the planned leg on to the next node counts as the walkable line: the foot cell's
     *  own ray crossed a one-deep hole the leg itself misses (R1 1424,63,-402), the node stayed
     *  current while the bot walked the leg, and 3 blocks on offPath re-searched from the hole onto
     *  another cliff face (+8 s). */
    private boolean skirtedPast(Navigator nav, WorldView world, LivingEntity p, BlockPos foot, BlockPos nx, double cur2) {
        BlockPos w = nav.route.path().get(nav.route.step());
        int wy = w.getY();
        return cur2 <= SKIRT_PASS_SQ && !p.isInWater() && p.onGround() && foot.getY() == wy && nx.getY() == wy
                && beyondNode(nav.route.path(), nav.route.step(), p) && (losWalkable(world, foot, nx) || onLeg(nav, w, nx, p));
    }

    /** On a plain walk leg w → nx: within {@link #LEG_HALF_WIDTH} of its centre line, forward of w. */
    private boolean onLeg(Navigator nav, BlockPos w, BlockPos nx, LivingEntity p) {
        if (!plainFlatWalk(nav.route.edgeAt(nav.route.step() + 1))) return false;
        double ax = nx.getX() - w.getX(), az = nx.getZ() - w.getZ(), len = Math.hypot(ax, az);
        double px = p.getX() - (w.getX() + 0.5), pz = p.getZ() - (w.getZ() + 0.5);
        return len > 0 && px * ax + pz * az > 0 && Math.abs(px * az - pz * ax) / len <= LEG_HALF_WIDTH;
    }

    private static final double LEG_HALF_WIDTH = 0.8;

    /**
     * Hold the pointer on the LAST node instead of spending it while the foot is still short of
     * the goal. Disk goals: the node sits inside the goal radius but the FOOT cell is one block
     * short; the node-reach gate (~0.67 blk) fires ~1 block out, so consuming here ends the path
     * with goal.reached(foot) still FALSE — the segment-end handler hits frontierHoldOrArrive,
     * STOPS the drive and declares ARRIVED a block short (live 2026-06-23: XZ -1700,900 r6 pinned
     * the bot at foot -1693, dx=7, OUTSIDE r6, then fake-ARRIVED). Exact goals, bounded
     * ({@code navigatorHoldLastNodeUntilStanding}): spending the last node from 0.67 away or from the
     * air ends the walk "path-consumed goalReached=false" with the bot about to stand in the goal
     * cell a few ticks later — a reading the ladder acts on. Either way the normal pure-pursuit
     * walks the foot ONTO the node and the goal.reached check at the top of the tick fires for
     * real; the exact hold gives up after FINAL_NODE_HOLD_TICKS so a cell the bot genuinely
     * cannot stand in still ends the walk the honest way.
     */
    private boolean holdLastNode(Navigator nav, BlockPos foot, BlockPos w) {
        if (nav.route.step() + 1 < nav.route.path().size() || nav.goal.reached(foot)) return false;
        boolean diskGoal = (nav.goal instanceof Goal.XZ xz && xz.radius() > 0)
                || (nav.goal instanceof Goal.Near nr && nr.radius() > 0);
        if (diskGoal) return nav.goal.reached(nav.route.path().get(nav.route.path().size() - 1));
        if (!ControlConfig.navigatorHoldLastNodeUntilStanding || nav.route.seg.pathBestEffort || !nav.goal.reached(w)) return false;
        if (finalNodeHoldPath != nav.route.path() || finalNodeHoldStep != nav.route.step()) {
            finalNodeHoldPath = nav.route.path();
            finalNodeHoldStep = nav.route.step();
            finalNodeHold = 0;
        }
        return finalNodeHold++ < FINAL_NODE_HOLD_TICKS;
    }

    /** @return non-null Step to end the tick (propagated by the driver); null = fall through. */
    public TickStep run(Navigator nav, TickCtx cx, Body a, WorldView world) {
        // ---- consume: rehydrate this phase's inputs from the tick products (TickCtx) ----
        LivingEntity p = cx.frame.p;
        BlockPos foot = cx.frame.foot;
        // ---- original body (byte-identical modulo member prefixes) ----
        // First path still computing (no path to follow yet). PROGRESSIVE
        // QUICK-START: instead of holding frozen for the whole background
        // search (the visible inter-segment stall), grab a tiny synchronous
        // stub segment toward the goal and start walking it this very tick;
        // the big result supersedes it on landing. Only hold if no useful
        // stub exists (boxed in — moving blind would jitter).
        if (nav.route.path() == null) {
            // A5 DIVE-INTENT HOLD: while a DIVE-opt-in intent's search is pending and
            // the bot floats IN WATER, do NOT adopt a progressive stub — HOLD (tread
            // water) until the real plan lands. tryQuickStart is a genuine mini-A*
            // whose best-effort partial happily contains climb-OUT edges, so from a
            // water-surface start it beat the sliced big search every time and hauled
            // the bot ASHORE before the first plan existed — by then the search start
            // was on LAND and the dive route was gone (live 2026-07-06 rc-a5c: every
            // t=2 sample already climbing out at ~(601.7,-49.6) with no [navigator]
            // repath line yet; at maxMs=8000 the land-start search burned 90k nodes
            // honestly-unreachable). tryWaterBeeline likewise marches the floater
            // AWAY along the surface, moving the next search's start off the dive
            // column. The swimDown*/depth-hold exemptions all key on the CURRENT
            // EDGE, which does not exist pre-path — this is the pre-path twin of
            // those gates. Planned climb-out EDGES from a real path still execute
            // (a dive route may legitimately end with a bank exit); dry-land segments
            // of a dive-intent journey keep their stubs (gate requires isInWater).
            // No DIVE opt-in → allowsOptIn false → byte-identical.
            boolean diveIntentWaterHold = p.isInWater()
                    && nav.profile.capability().allowsOptIn(Capability.DIVE);
            // A replay never plans again, so a replay with no plan is over: the plan was refused, or
            // something dropped it, and lastDrop says which. Holding here instead (as this once did)
            // spent the caller's whole tick budget standing still.
            if (nav.route.replay())
                return nav.terminalReport(TickStep.ARRIVED, PathTrace.Outcome.STUCK, null,
                        "replay-dropped:" + nav.route.lastDrop(), foot);
            if (nav.route.seg.activeSearch == null || diveIntentWaterHold
                    || (!RouteStubs.landBeeline(nav, world, foot, nav.goal)
                        && !RouteStubs.quickStart(nav, world, foot, nav.goal) && !RouteStubs.waterBeeline(nav, world, foot, nav.goal))) {
                Keys.forward(a, false);
                nav.keys.jump(a, false);
                p.setSprinting(false);
                return TickStep.WALKING;
            }
            // stub adopted → fall through and walk it
        }
        nav.repath.ticksSinceRepath++;
        // §84 physical stall clock — see field doc. Ticks on XZ displacement only,
        // survives every path/step/repath reset.
        // Euclidean 2.0 window (rig-metric parity): the first cut used |dx|+|dz|>1.5,
        // which a reCentre/carrot tug-of-war micro-orbit (1-2 block circles at a pinned
        // corner, C98-J2 replay) reset continuously — the clock never reached its gate.
        double sdxA = p.getX() - physStall.anchorX, sdzA = p.getZ() - physStall.anchorZ;
        if (Double.isNaN(physStall.anchorX) || sdxA * sdxA + sdzA * sdzA > 4.0) {
            physStall.anchorX = p.getX();
            physStall.anchorZ = p.getZ();
            physStall.stallTicks = 0;
        } else {
            physStall.stallTicks++;
        }
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
        Move.Edge curBridgeE = nav.route.edgeAt(nav.route.step()), nxtBridgeE = nav.route.edgeAt(nav.route.step() + 1);
        boolean onBridge = (curBridgeE != null && "bridgePlace".equals(curBridgeE.move))
                || (nxtBridgeE != null && "bridgePlace".equals(nxtBridgeE.move));
        // Jitter-immune WEDGE timer: counts raw ticks the bot stays on the SAME path
        // step, reset only when the step actually advances (or the path/segment ends).
        // Deliberately INDEPENDENT of the bridge/progress resets below — a bridgePlace
        // that can't place, or a fallN that can't drop, dwells on one step indefinitely
        // and IS a wedge; the progress-based stuckTicks (which a bob/creep keeps zeroing,
        // and which onBridge hard-zeroes) misses both, so without this the bot deadlocks.
        // navigatorStuckStepMonotonic (2nd site): a step RETREAT (projection jitter, repath
        // oscillation) must not reset the wedge clock either — C25-J2's ADVANCE-deadzone
        // stream showed noStepProg pinned at exactly 61 (threshold+1): the counter reached
        // the gate and was immediately re-zeroed by the next flip, so every wedge-gated
        // recovery re-armed forever. Only an ADVANCE opens a fresh window; a retreat
        // re-bases the closest-approach reference on the CURRENT distance (not +INF, which
        // would fake a new-low next tick and zero the clock through the progress branch).
        boolean npStepFresh = (nav.route.path() == null || nav.route.step() >= nav.route.path().size()
                || (ControlConfig.navigatorStuckStepMonotonic ? nav.route.step() > stepProg.noProgressStep : nav.route.step() != stepProg.noProgressStep));
        if (!npStepFresh && ControlConfig.navigatorStuckStepMonotonic
                && nav.route.path() != null && nav.route.step() < nav.route.path().size() && nav.route.step() < stepProg.noProgressStep) {
            stepProg.noProgressStep = nav.route.step();
            BlockPos rn = nav.route.path().get(nav.route.step());
            double rdx = (rn.getX() + 0.5) - p.getX();
            double rdy = rn.getY() - p.getY();
            double rdz = (rn.getZ() + 0.5) - p.getZ();
            stepProg.noProgressBestD2 = rdx * rdx + rdy * rdy + rdz * rdz;
        }
        if (npStepFresh) {
            stepProg.noProgressStep = nav.route.step();
            stepProg.noStepProgressTicks = 0;
            stepProg.noProgressBestD2 = Double.POSITIVE_INFINITY;
        } else {
            // Genuinely closing in on the current node is progress, not a wedge: a
            // water cruise crosses 20+-block string-pulled edges at ~1.5 b/s (260
            // ticks on ONE step — far over WEDGE_TICKS) and used to trip safety
            // repaths + anti-stuck bursts mid-lake. Track the closest-EVER approach
            // (monotonic, so a bob against a wall can't keep resetting) and only
            // count wedge ticks while that stops improving — a fall2 that can't
            // drop or a blocked bridgePlace never closes in and still trips.
            BlockPos wn = nav.route.path().get(nav.route.step());
            double wdx = (wn.getX() + 0.5) - p.getX();
            double wdy = wn.getY() - p.getY();
            double wdz = (wn.getZ() + 0.5) - p.getZ();
            // A buoyant bot bobs ±1.5 vertically; folding wdy² into the closest-approach
            // progress test makes wd2 oscillate even while the bot creeps FORWARD toward a
            // water goal, so noStepProgressTicks falsely climbs → wedged → safetyRepath churn:
            // every bob tick a cheap goal-reaching search resets path+step and the aim chases
            // the fresh node-1 (live J5 z1743: 3 repaths/s, yaw 96-131° while still closing on
            // the goal). In water, measure progress HORIZONTALLY only — the bob can't fake a
            // forward creep, while a truly stuck bot (circling, or pinned in the water below an
            // unmountable bank without gaining XZ) still fails to close XZ and trips the wedge
            // exactly as before (so deep-water climb-out detection is unchanged). Dry land keeps
            // the full 3D test (a stepUp/pillar that gains height IS progress there).
            double wd2 = p.isInWater()
                    ? wdx * wdx + wdz * wdz
                    : wdx * wdx + wdy * wdy + wdz * wdz;
            if (wd2 < stepProg.noProgressBestD2 - 0.05) {   // any real new-low counts; 0.05 is float-noise margin (1.0 starved a 1.5 b/s approach inside ~7 blocks: 2·d·v < 1)
                stepProg.noProgressBestD2 = wd2;
                stepProg.noStepProgressTicks = 0;
            } else {
                stepProg.noStepProgressTicks++;
            }
        }
        if (onBridge) {
            nav.stuckTicks = 0;
            stepProg.bestStepDist = Double.POSITIVE_INFINITY;
            stepProg.stuckStep = nav.route.step();
        } else if (nav.route.path() != null && nav.route.step() >= 0 && nav.route.step() < nav.route.path().size()) {
            BlockPos sn = nav.route.path().get(nav.route.step());
            double sdx = (sn.getX() + 0.5) - p.getX();
            double sdy = sn.getY() - p.getY();
            double sdz = (sn.getZ() + 0.5) - p.getZ();
            double sd2 = sdx * sdx + sdy * sdy + sdz * sdz;
            // navigatorStuckStepMonotonic: only a step ADVANCE opens a fresh progress window.
            // On an open-water straight path the buoyant drift makes the projection/within
            // machinery jitter the step pointer back and forth (wp 375↔387↔374 in one 44s
            // stall, A-4 2026-07-02); with the old `step != stuckStep` test every jitter
            // reset stuckTicks (observed pinned at 0-6, never reaching the nodeAim
            // fallback's 12), so ALL stuck-gated recovery starved while the yaw swept 660°
            // and thrust cancelled. A step RETREAT keeps the current window instead.
            // HIGH-WATER form (C33-J2 fix): the first cut compared against stuckStep and
            // the retreat re-base LOWERED it, so an oscillating pointer (5↔6 at a sand
            // pond lip, 1200t) still cleared the clock on every "advance" of the pair.
            // Only a step beyond the highest index EVER seen on this path is fresh.
            boolean stepWindowFresh = ControlConfig.navigatorStuckStepMonotonic
                    ? nav.route.step() > stepProg.stuckStepHigh : nav.route.step() != stepProg.stuckStep;
            if (ControlConfig.navigatorStuckStepMonotonic && !stepWindowFresh && nav.route.step() != stepProg.stuckStep) {
                // Retreat OR revisit inside the seen range: keep the stall clock running
                // but re-base the progress reference on the new node, else its
                // naturally-different sd2 would fake "real progress" through the side door.
                stepProg.stuckStep = nav.route.step();
                stepProg.bestStepDist = sd2;
            }
            if (stepWindowFresh) {                         // new node → fresh progress window
                stepProg.stuckStep = nav.route.step();
                stepProg.stuckStepHigh = nav.route.step();
                stepProg.bestStepDist = sd2;
                nav.stuckTicks = 0;
            } else if (sd2 < stepProg.bestStepDist
                    // Medium-split margin (§94): the dry 0.05 margin (C40-J1 wall-creep
                    // starvation fix) starves water's naturally slow rounding manoeuvres
                    // and the tripped recovery pins the bot on the obstacle corner.
                    - (p.isInWater() ? STUCK_PROGRESS_EPS_WATER : STUCK_PROGRESS_EPS)) {
                stepProg.bestStepDist = sd2;                        // closer than ever to this node → real progress
                nav.stuckTicks = 0;
            } else {
                nav.stuckTicks++;                              // no closer this tick → maybe stalled
            }
        } else {
            nav.stuckTicks = 0;
        }

        // Arc-length projection: projects the continuous foot XZ onto the path polyline within a forward window
        // and reports the projected segment, cumulative arc-length s, horizontal perpendicular distance, and the
        // tangent heading at s+lookahead. The structural replacement (refactor plan §7) for the ~8 instantaneous
        // step-advance gates + the bob-immune dwell zoo, which exist ONLY because the buoyancy bob defeats
        // the per-tick cur2<0.45 / |dyNode|<1.2 gates.
        if ((ControlConfig.navigatorArcLengthAdvance || ControlConfig.navigatorTangentAim || ControlConfig.navigatorArcLengthWedge)
                && nav.route.path() != null && nav.route.step() < nav.route.path().size() && foot != null) {
            arcShadowTick(nav, world, foot, p.getX(), p.getZ(), p.getYRot(), p.isInWater(), p.horizontalCollision);
        } else {
            arc.wedgeTicks = 0;   // no projection this tick → don't carry a stale ram count into the next path
        }

        int dropAheadTo = dropAheadTarget(nav, world, p, foot);
        while (nav.route.step() < nav.route.path().size()) {
            Move.Edge se = nav.route.edgeAt(nav.route.step());
            // Buoyant pillar (bunker / flooded-pit self-exit): the bot rises through
            // a water-filled shaft. Detect it from the WORLD — the cell being risen
            // FROM is water — not p.isInWater(), which flickers false at the bob peak
            // when the head clears the surface (that flicker stalled the climb).
            // A FLOODED shaft (destination cell is itself water) lets the bot float
            // straight up; a buoyant pillar more broadly is any pillarUp begun from
            // water (cell below is water / bot in water) — including the dry-air
            // chimney where the bot must place a support to climb out.
            boolean shaftFlooded = se != null && "pillarUp".equals(se.move) && floodedShaft(world, nav.route.path().get(nav.route.step()));
            boolean waterPillar = se != null && "pillarUp".equals(se.move)
                    && (shaftFlooded || p.isInWater() || world.isWater(nav.route.path().get(nav.route.step()).offset(0, -1, 0)));
            // Don't advance past a cell whose break/place actions are still
            // pending — otherwise a DownBreak (foot vertically aligned, < 1.2
            // away) would be skipped before we ever mine the floor.
            if (waterPillar) {
                // A pillarUp begun from water does not treat its unfilled place cell as pending;
                // only a still-solid ceiling is. This branch is NOT what protects the water-SURFACE
                // case (waterPillar is true there too — the bot is in water); the arrival gate
                // below is, via floodedShaft(). Neither alone explains why the step waits.
                boolean ceilingPending = false;
                for (BlockPos b : se.toBreak) if (world.isSolid(b)) { ceilingPending = true; break; }
                if (ceilingPending) break;
            } else if (hasPendingEdge(world, se)) break;
            // A pillar step isn't "reached" until risen to height. A FLOODED-shaft
            // float has no landing → risen-height alone promotes it; every other
            // pillar (dry, or the water-exit place) must be grounded first so we
            // don't advance mid-jump / before the support lands.
            if (se != null && "pillarUp".equals(se.move)
                    && !((p.onGround() || shaftFlooded) && p.getY() >= nav.route.path().get(nav.route.step()).getY() - 0.1)) break;
            // Don't advance past a parkour-place edge while airborne — keep the
            // settle phase owning the descent so it brakes the leap on landing.
            if (se != null && se.move != null && se.move.startsWith("parkourPlace")
                    && !p.onGround()) break;
            // Same for a parkour-descend leap: don't advance off it until we've
            // actually landed, so the descend landing-brake keeps owning the arc
            // (otherwise the next edge fires mid-air and the bot sails past).
            // EXEMPT in water: a buoyant bot never grounds on a water-side landing
            // (A* exits a shore DOWN into water with a parkourDescend, or a leap
            // falls short into a water gap), so the !onGround hold would pin the
            // step on the parkour node forever while the bot bobs at the surface —
            // the parkour jump just bobs it, pure-pursuit can't advance past the
            // node it drifted beyond, and it deadlocks until a safety repath (live
            // 2026-06-15: shoreline parkourDescend2d1 over water, bot bobbed y62.5
            // <-> 63 ~19 s, stuck 385). When in water let the normal water step-
            // advance (within / floatOverSubmerged / pure-pursuit) carry it past
            // the node so flatWaterWalk swims it on to the next node. Ride out the
            // isInWater bob-blink with surfaceWaterLatch: at a water EDGE p.isInWater()
            // flickers false at the bob apex, and without the latch the exemption drops
            // out mid-bob and re-pins the step on the parkour node — the overshoot-resync
            // below never runs, so the bot drives BACKWARD to the stale node instead of
            // advancing to the climb-out it already overshot into (live 2026-06-24 -582
            // pool: parkour landed at -581,62 past node -582,62,536, ~15 s of backward bob).
            if (se != null && se.move != null && se.move.startsWith("parkourDescend")
                    && !p.onGround() && !p.isInWater() && nav.aim.surfaceWaterLatch <= 0) break;
            // An ASCENDING parkour leap (parkourAscend / a rising parkour2d-3d that lands
            // higher than the launch) is the ONE jumped move WITHOUT a "don't advance until
            // executed" gate — pillarUp (above), parkourPlace and parkourDescend all have one,
            // but a rising parkour does not, so the within/passed re-sync CONSUMES the leap node
            // before the bot ever reaches the launch + jumps. The live -870 water climb-out
            // (2026-06-25 journey, 270-tick stall): the plan was
            //   …4 walk -870,62,379 · 5 stepUp -870,63,380 · 6 parkourAscend2 -872,64,380 · 7 walk -872,64,379
            // and at tick 1 `step` advanced 5→6→7 in ONE pass — node 5 `within` (foot at the
            // bank corner), node 6 `passed` (node 7 horizontally closer + both |Δy|<1.2 gates met)
            // — so the +2 climb-out leap was skipped and the bot was left pointed at node 7, a flat
            // `walk` node sitting +2 ABOVE the water. It then sank into the y62 pocket and bob-stalled
            // for ~14 s (a buoyant bot can't WALK up a +2 bank; the toolless underwater bank-dig is
            // ~25× slow) until a repath happened to find a gentler exit. HOLD the step on the rising
            // parkour node until the feet have actually RISEN to it (grounded at its Y, ±0.5) so the
            // parkour actuator (sprint+jump, aimed at the destination — line ~3946) owns the launch and
            // a too-far approach can't skip straight onto the unreachable landing. Mirrors the pillarUp
            // height-gate exactly. NOT water-exempt (unlike parkourDescend): the failure here ENDS in
            // water, so the hold must persist there to keep the bot anchored at the climb-out node — if
            // the leap still can't complete, noStepProgressTicks climbs and the existing fellOffPath /
            // wedge-repath re-routes from the real position (the deterministic escape the original took,
            // just sooner and from the correct node). Strict extension: a rising parkour mid-leap is
            // ALREADY below its landing and not grounded, so this is inert on a clean leap (it was going
            // to hold via the airborne state anyway); only the premature drift-skip is suppressed.
            if (ControlConfig.navigatorParkourAscendHold
                    && se != null && se.move != null && se.move.startsWith("parkour")
                    && !se.move.startsWith("parkourDescend") && !se.move.startsWith("parkourPlace")
                    && nav.route.path().get(nav.route.step()).getY() > foot.getY()
                    && !(p.onGround() && p.getY() >= nav.route.path().get(nav.route.step()).getY() - 0.5)) break;
            BlockPos w = nav.route.path().get(nav.route.step());
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
            // In WATER the buoyant bot rides the surface; a path node BELOW it
            // (A* routed a wide crossing along the riverbed) can never be reached
            // by Y — the bot floats over it forever (trace: rode y61.78 above a y60
            // diagDown node, |dY|=1.78 > 1.2, never advanced → a ~50-block river was
            // uncrossable). Treat horizontal alignment ALONE as "reached" for such
            // a submerged below-node so the crossing advances node-by-node at the
            // surface. Excluded: a deliberate swimDown dive edge (we want that
            // descent); a climb-up node (dyNode>0) stays gated by the clause below
            // so a buoyant bob can't skip an intermediate +1 climb node.
            boolean diveEdge = se != null && se.move != null && se.move.startsWith("swimDown");
            // A buoyant bot rides 1-2 blocks ABOVE the in-water below-nodes of a
            // riverbed crossing and can never close the Y gap — the flatWaterWalk
            // actuator already sprint-swims it flat at the surface, so horizontal
            // alignment ALONE must advance the step. The old gate also required
            // !p.isUnderWater(), which DISABLED the whole crossing the instant the
            // eyes bobbed under the waterline (isUnderWater flickers true on a
            // surface swimmer) — the bot then pinned at the node's XZ forever (live
            // 2026-06-15 deep-water arena: open-water cross, node 1.4 below,
            // undW=true bobbing, 2600+ ticks at step 1). Bounded to ≤2.5 below so a
            // genuine deep descent isn't skipped (A* places crossing nodes ~1 below
            // the floating foot, not at the riverbed). A deliberate swimDown dive is
            // still allowed to descend — UNLESS buoyancy has refused the sink past
            // WATER_DESCEND_GIVEUP (the same surface-pin failure for a dive edge),
            // in which case surface-cross past it too rather than deadlock.
            // Float over a NON-dive submerged node at ANY depth: A* routes a wide deep-water
            // crossing along the riverbed (live 2026-06-23: walk/parkour nodes placed at y58, FOUR
            // below the floating foot in 9-deep water), and the surface swimmer must cross
            // horizontally ABOVE them — never follow them down. The old -2.5 floor let the bot dive
            // to and bob at deep crossing nodes (totStuck 100+, the stall where the bot sinks to the bottom and digs into the wall). A deliberate
            // swimDown dive (diveEdge) keeps the tight -2.5 + give-up so a real descent isn't skipped.
            double floatOverFloor = diveEdge ? -2.5 : -FLOATOVER_NONDIVE_MAX_DROP;
            boolean floatOverSubmerged = p.isInWater() && dyNode < -0.5 && dyNode > floatOverFloor
                    && (!diveEdge || stepProg.noStepProgressTicks > WATER_DESCEND_GIVEUP);
            boolean unclimbedUnderOverhead = unclimbedUnderOverhead(nav, foot, w);
            // A surface sprint-swim cruise (DrivePhase.surfaceCruise) rides 1-2 blocks UNDER its
            // surface nodes on purpose, so the vertical gates here and in `passed` read every node as an
            // unclimbed rise and the pointer only crawls after the arc projection — one node BEHIND the
            // bot, whose bearing then drives it backward (the impulse goes negative and vanilla drops
            // the sprint for "no forward impulse"). Under the cruise, reach is horizontal: the bot is
            // at the surface for every purpose the gates protect.
            boolean cruiseUnder = nav.drive.driveLatch.cruiseOn && p.isInWater() && dyNode > 0 && dyNode <= 3.0;
            boolean within = (cur2 < REACH_DIST_SQ || levelAtClimb(nav, p, w, dyNode, cur2))
                    && (Math.abs(dyNode) < 1.2 || floatOverSubmerged || cruiseUnder)
                    && !(p.isInWater() && dyNode > 0.5 && !cruiseUnder)
                    && !unclimbedUnderOverhead;
            // Pure-pursuit re-sync: also advance past a node we've already gone
            // by — the next node being closer than this one means the player is
            // beyond it. Without this, sprinting toward a far carrot (or a
            // sliced repath that starts from a now-stale foot) leaves `step`
            // pointing at a node BEHIND the player, so the aim flips ~180°.
            boolean passed = false;
            // Hoisted for the advance reading below, so it prints the numbers the decision USED
            // rather than re-deriving them after the bot moved. They stay NaN/false when `within`
            // fired and this block never ran — the honest reading for "never computed", not a zero.
            double nd2 = Double.NaN;
            boolean overshot = false;
            if (!within && nav.route.step() + 1 < nav.route.path().size()) {
                BlockPos nx = nav.route.path().get(nav.route.step() + 1);
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
                // Same in-water climb gate as `within` above: the buoyant bot bobs
                // y±0.9, so at the bob's crest |nx.y−p.y| can dip under 1.2 for a
                // node it never actually climbed to — passing skips the climb base
                // and leaves an impossible +2 target (stuck at y62 vs node y64).
                // OVERSHOOT relaxation: once the foot is clearly PAST node w
                // horizontally (cur2 > OVERSHOOT_RESYNC_SQ) the bot is no longer
                // approaching a climb base, so the two near-base guards must not
                // freeze the pointer. (a) The strict '<' tie-break — which protects
                // a pillarUp/swimUp node stacked directly above w (ndx²+ndz² == cur2)
                // — relaxes to '<=' so a stacked climb node can't pin a node the bot
                // has overshot. (b) The in-water "don't skip a buoyant +1 climb" gate
                // is dropped. The |Δy|<1.2 reachability gate on nx STAYS in both
                // cases, so the re-sync still can't lock onto an impossible +2 climb.
                overshot = cur2 > OVERSHOOT_RESYNC_SQ;
                nd2 = ndx * ndx + ndz * ndz;
                // Fell BELOW a descend node the bot has gone PAST: a steep crest /
                // shoulder where the bot crosses with forward momentum and free-falls
                // 2-3 blocks past the fall node, grounding on the terrace below it
                // (live 2026-06-15 reverse (2426,99,2153): foot y96, node y99, 1.8 b
                // past it in z, pinned against the far face → stuck 1341 / ~67 s). The
                // |w.y - p.y| < 1.5 guard — there to refuse "passing" a node we haven't
                // risen TO — also refuses this dropped-past node, freezing `step` on a
                // node 3 ABOVE the foot with the aim pointing back-UP at it (none of
                // within / fellOffPath catch it: |Δy|=3 misses within, and 3 ≤ maxJump+2
                // misses the re-search). Relax it when w is clearly ABOVE the foot AND
                // the route keeps DESCENDING through it (nx no higher than w): then w is
                // behind+above, a dropped-past descend node, not a climb target — and the
                // nx reachability gate below (|nx.y - p.y| < 1.2) still bars locking onto
                // an impossible climb.
                // Dry land only: a buoyant bot in water legitimately rides above/below
                // its nodes (floatOverSubmerged / dive own that), so "foot below the node"
                // is normal there and must NOT skip a climb/parkour node — the live wedge
                // is a grounded terrace landing (inW=false, onG=true throughout).
                boolean droppedPastDescend = !p.isInWater()
                        && w.getY() - p.getY() >= 1.5
                        && nx.getY() <= w.getY();
                boolean cruisePassed = cruiseUnder && nx.getY() <= w.getY();   // the next surface node is no higher: see cruiseUnder at `within`
                passed = (nx.getX() == w.getX() && nx.getZ() == w.getZ()   // stacked next node: see STACKED_PASS_SQ
                            ? cur2 <= STACKED_PASS_SQ || (overshot && beyondNode(nav.route.path(), nav.route.step(), p))
                            : (overshot ? nd2 <= cur2 : nd2 < cur2) || skirtedPast(nav, world, p, foot, nx, cur2))
                        && (Math.abs(w.getY() - p.getY()) < 1.5 || droppedPastDescend || cruisePassed)
                        && (Math.abs(nx.getY() - p.getY()) < 1.2 || cruisePassed)
                        && !unclimbedUnderOverhead   // see the note at `within`
                        && !(!overshot && p.isInWater() && nx.getY() - p.getY() > 0.5 && !cruisePassed);
            }
            // TAIL overshoot: the foot blew past the FINAL node of a best-effort
            // (sliced / progressive quick-start-stub) segment. There is no next
            // node to re-sync onto, so the block above never runs and neither
            // `within` nor `passed` can fire — `step` froze on the tail while the
            // bot crabbed 100 blocks past it on anti-stuck bursts, waiting out the
            // slow superseding search (live 2026-06-15 wide-water crossing: step
            // 6/7, p 100 blk past a 6-node stub's last node, stuck 745, minutes of
            // burst-crab). A best-effort tail is a splice point, never the goal, so
            // a gross HORIZONTAL overshoot (cur2 gate, so a straight pillar-up to
            // the tail — same XZ, small cur2 — is excluded) means the segment is
            // spent: advance so the segment-end block below adopts the continuation
            // / repaths from HERE instead of pinning on the stale tail.
            // VERTICAL tail overshoot: the bot blew past the tail DOWNWARD (a fall
            // node on a cliff lip — it free-fell well below the tail and is grounding
            // at the bottom, horizontally still on the tail's XZ so the cur2 gate above
            // never trips). Same dead-zone as droppedPastDescend but with no next node
            // to re-sync onto (live 2026-06-15 reverse fall2 (2436,92,2180): foot fell
            // to y82, 10 below, |dy|=9.9, cur2 0.6 → stuck 128 / ~6.4 s waiting on the
            // fellOffPath re-search). Consume the spent descend tail so the segment-end
            // block repaths from HERE at once. Gated to a DESCEND incoming edge so a
            // pillarUp/climb tail (bot legitimately below it) is never aborted.
            boolean descendTail = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.startsWith("diagDown")
                        || se.move.equals("stepDown"));
            boolean tailDroppedPast = !p.isInWater() && descendTail
                    && p.getY() < w.getY() - 2.0;
            boolean tailOvershot = cur2 > OVERSHOOT_RESYNC_SQ && !tailStillAhead(nav, p, w);
            boolean tailConsumed = !within && nav.route.step() + 1 == nav.route.path().size() && nav.route.seg.pathBestEffort
                    && (tailOvershot || tailDroppedPast || tailReady(nav, p, foot, w, cur2));
            // DESCENT OVERSHOOT-ADVANCE (the in-place back-hop fix the in-place-hop comment
            // points to): on a dry descent step the bot drives the IMMEDIATE node
            // (driveTargetYaw = descentNodeYaw). The instant the foot crosses PAST that node
            // toward the next one, the node sits behind the bot and the decoupled drive
            // reverses — the ~0.1-0.25/tick backward hop seen on every slope / stepDown
            // (descentYawArena backSteps=42, worstBack=-0.25). `passed` only advances at the
            // w→nx MIDPOINT (nd2<cur2), leaving a 2-3 tick window where the drive rides the
            // overshot node and hops back. Advance one tick earlier — the moment the foot is on
            // the FORWARD side of node w along the w→nx segment (projection of w→foot onto w→nx
            // positive) — so the drive never rides a node behind it. Dry + descend-edge gated
            // (water/climb keep their own gates); the |Δy|<1.2 bar keeps it off an impossible
            // climb, and the forward-projection test means a switchback segment (foot past w but NOT
            // toward nx) never trips it.
            // DISCRETE descents only (fall off a lip / a single stepDown): a continuous diagDown
            // SLOPE legitimately rides the immediate node for trend-camera smoothing, and advancing
            // eagerly there over-leans the descent into MORE back-correction (descentYawArena
            // backSteps 42→71). A discrete drop has one clean overshoot to consume.
            boolean discreteDescend = se != null && se.move != null
                    && (se.move.startsWith("fall") || se.move.equals("stepDown"));
            boolean crossedDescendNode = false;
            if (!within && !passed && discreteDescend && !p.isInWater() && nav.route.step() + 1 < nav.route.path().size()) {
                BlockPos nxd = nav.route.path().get(nav.route.step() + 1);
                double segx = nxd.getX() - w.getX(), segz = nxd.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedDescendNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxd.getY() - p.getY()) < 1.2;
            }
            // WALK overshoot-advance — mirror of crossedDescendNode for a FLAT walk node the bot has
            // crossed and is now ORBITING in the step-advance dead-zone (cur2 ∈ [REACH_DIST_SQ,
            // OVERSHOOT_RESYNC_SQ]: too far for `within`, too near for the overshoot re-sync, and
            // circling so the next node never reads STRICTLY closer → `passed` never fires). Live V2
            // shore node -783,64,609: orbited z609±1 for 111 ticks ~5.5 s until the wedge-repath
            // rescued it. Gated on a CONFIRMED stuck (noStepProgressTicks) so a normally-approaching
            // walk advances via within/passed FIRST — this ONLY nudges an already-wedged orbit, never
            // cuts a live corner. walk-incoming-edge only (descents own crossedDescendNode and slopes
            // are deliberately excluded there; climbs/water keep their bases); |Δy|<1.2 bars a climb.
            boolean crossedWalkNode = false;
            if (!within && !passed && !crossedDescendNode
                    && se != null && se.move != null
                    && se.move.equals("walk")
                    && !p.isInWater() && stepProg.noStepProgressTicks > WALK_OVERSHOOT_STUCK_TICKS
                    && nav.route.step() + 1 < nav.route.path().size()) {
                BlockPos nxw = nav.route.path().get(nav.route.step() + 1);
                double segx = nxw.getX() - w.getX(), segz = nxw.getZ() - w.getZ();
                double offx = p.getX() - (w.getX() + 0.5), offz = p.getZ() - (w.getZ() + 0.5);
                crossedWalkNode = (offx * segx + offz * segz) > 0
                        && Math.abs(nxw.getY() - p.getY()) < 1.2;
            }
            // WATER-SURFACE STEP-DOWN float-and-advance: a stepDown (or short discrete descent) whose
            // node is a SHALLOW water-surface foothold (water at the node, SOLID floor one below, head
            // not water) lands a buoyant bot that grounds vertically AT the node (|dyNode|≈0) but pins
            // ~0.74 b short of centre (cur2≈0.55) — just over the tight REACH_DIST_SQ=0.45 — because
            // buoyancy + the water-climb jump keep lifting/ramming the foot and the prone swim can't
            // nudge the last 0.1 b in. `within` (cur2<0.45) never fires and the step freezes (live #47
            // -809,62,350: 12 s+ bob-ram). `floatOverSubmerged` misses it (node AT the foot, not below).
            // Treat arrival at the surface cell as reaching the node and advance at a RELAXED reach,
            // mirroring floatOverSubmerged/deepWaterRise — gated, like crossedWalkNode, on a CONFIRMED
            // stall so a clean approach advances via within/passed first and this only rescues the pin.
            // Strictly scoped: stepDown/fall/diagDown incoming edge + shallow water-surface node; a dry
            // step-down, a deep floating-water landing, a submerged node, and climb/walk/parkour edges
            // are all byte-identical inert (the gate never matches them).
            boolean waterStepDownFloat = false;
            if (ControlConfig.navigatorWaterStepDownFloat && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode
                    && se != null && se.move != null
                    && (se.move.equals("stepDown") || se.move.startsWith("fall") || se.move.startsWith("diagDown"))
                    && (p.isInWater() || nav.aim.surfaceWaterLatch > 0)
                    && stepProg.noStepProgressTicks > WATER_STEPDOWN_STALL_TICKS
                    && cur2 < WATER_STEPDOWN_REACH_SQ
                    && Math.abs(dyNode) < 1.2
                    && world.isWater(w) && world.isSolid(w.below()) && !world.isWater(w.above())) {
                waterStepDownFloat = true;
                if (GameConfig.navigatorDebug)
                    LOG.info("[navigator] water-stepdown-float ADVANCE step={}/{} node={} move={} cur2={} dyNode={} stall={}",
                            nav.route.step(), nav.route.path().size(), w, se.move, String.format(Locale.ROOT, "%.3f", cur2),
                            String.format(Locale.ROOT, "%.2f", dyNode), stepProg.noStepProgressTicks);
            }
            // WATER-SURFACE WALK relaxed-advance (the turn / wall-corner FREEZE breaker): a flat `walk`
            // water-surface node the buoyant bot sits ~0.67 b out from (cur2 floor ~0.455, just over
            // REACH_DIST_SQ=0.45) so `within` never closes. On a straight crossing forward momentum fires
            // `passed`, but at a TURN / terminal / WALL-CORNER node the bot isn't crossing toward the next node
            // so `passed` can't fire either — the flat water walk node then has NO relaxed-advance and the bot
            // orbits / freezes against the corner (live deep-water bay corner: cur2 1.142 FROZEN 321 ticks,
            // within=0, aim swinging 403° — the stuck-against-a-wall or stuck-in-water jank; attack=0, NOT digging). Mirror
            // waterStepDownFloat: on a CONFIRMED in-water stall at a flat walk water node
            // within a relaxed reach, advance so the segment continues / repaths from here. In water
            // noStepProgressTicks is HORIZONTAL-only (Navigator:~1666) so the bob can't fake-reset it — only a
            // genuine non-closing freeze accumulates it past the gate; a clean crossing advances via `passed`
            // in 1-2 ticks (noStepProgress stays low) and never trips it. The |Δy|<1.2 gate on the next node
            // bars an impossible climb-skip; dry walk and every non-walk edge are byte-identical inert.
            boolean waterWalkReach = false;
            if (ControlConfig.navigatorWaterWalkReach && !within && !passed
                    && !crossedDescendNode && !crossedWalkNode && !waterStepDownFloat
                    && se != null && se.move != null && se.move.equals("walk")
                    && p.isInWater()
                    && stepProg.noStepProgressTicks > WATER_WALK_STALL_TICKS
                    && cur2 < WATER_WALK_REACH_SQ
                    && nav.route.step() + 1 < nav.route.path().size()
                    && Math.abs(nav.route.path().get(nav.route.step() + 1).getY() - p.getY()) < 1.2) {
                waterWalkReach = true;
                if (GameConfig.navigatorDebug)
                    LOG.info("[navigator] water-walk-reach ADVANCE step={}/{} node={} cur2={} stall={}",
                            nav.route.step(), nav.route.path().size(), w, String.format(Locale.ROOT, "%.3f", cur2), stepProg.noStepProgressTicks);
            }
            stepAdvDiag(nav, p, foot, w, se, within, passed, crossedDescendNode, crossedWalkNode, cur2);
            // Phase-1 (navigatorArcLengthAdvance): the bob-immune path projection ADDS a step-advance the legacy
            // gates miss — advance when the foot's forward projection has reached a later segment
            // (arcProj.segIdx > step). It is a SUPPLEMENT, not a replacement: the six legacy gates
            // (passed/tailConsumed/crossedDescend/crossedWalk/waterStepDownFloat/waterWalkReach)
            // still fire because they advance in NEAR-reach cases the projection is too strict for — e.g. a
            // buoyant bot pinned just short of a shallow water-surface step-down (the foot hasn't projected past
            // the node so segIdx is pinned; waterStepDownFloatArena asserts exactly this). The union (legacy || projection) is strictly more
            // advancing than either alone, so it both kills the bob-defeated step-freeze (projection drives) AND
            // keeps the reach helpers (legacy drives). Edge-execution holds above still gate advancement.
            boolean legacyAdvance = within || passed || tailConsumed || crossedDescendNode || crossedWalkNode
                    || waterStepDownFloat || waterWalkReach || dropAheadTo > nav.route.step();
            BlockPos nxt = nav.route.step() + 1 < nav.route.path().size() ? nav.route.path().get(nav.route.step() + 1) : null;
            boolean doAdvance = (legacyAdvance || (ControlConfig.navigatorArcLengthAdvance && arc.proj.segIdx > nav.route.step()))
                    && !airborneClimbConsume(world, p, w, nxt)             // ONE outlet for all eight gates — an airborne bot must not spend a node on a climb; see the helper's javadoc for the wd.buriedOre reading
                    && !unwalkedDescentConsume(nav, world, p, foot, w, nxt) // …and a standing bot must not spend one on a descent — the LAST node too when spending it would fake an arrival; see that helper for the rung-14 and rung-13 readings
                    && !airborneDryArrival(nav, world, p, foot, w, nxt);    // …and a bot that is not standing must not spend a dry LAST node — the flush-bank reading on the real client
            if (doAdvance) {
                if (holdLastNode(nav, foot, w)) break;
                // The step-advance reading, at the ONE `step++` in the navigator (so within/passed/tail
                // are all covered here) and AFTER the disk-goal hold — see Navigator#noteStepAdvance.
                noteStepAdvance(nav, world, p, foot, w, nxt,
                        advanceCause(within, passed, tailConsumed, crossedDescendNode, crossedWalkNode,
                                waterStepDownFloat, waterWalkReach,
                                ControlConfig.navigatorArcLengthAdvance && arc.proj.segIdx > nav.route.step(),
                                dropAheadTo > nav.route.step()),
                        cur2, nd2, overshot);
                nav.route.advance();
            }
            else break;
        }
        if (nav.route.step() >= nav.route.path().size()) {
            if (!nav.route.seg.pathBestEffort || nav.goal.reached(foot)) {
                // A path that actually reaches the goal (or we ended up standing in
                // the goal cell): the journey is done.
                return nav.terminalReport(TickStep.ARRIVED, PathTrace.Outcome.SUCCESS, null,
                        WalkVerdict.classifyArrival(nav.route.seg.pathBestEffort, nav.goal.reached(foot), nav.goalSnapped), foot);
            }
            // Best-effort segment consumed but the goal is still ahead. DON'T give
            // up — this is the long-distance splice point. Adopt the continuation
            // if its background search has landed; otherwise hold here (keys
            // released) until it does. The total-tick budget above still bounds a
            // goal we can never make real progress toward, so this can't hang.
            if (nav.route.seg.pendingSegment != null) {
                PathFinder.Result next = nav.route.takeContinuation();
                if (next.hasPath() && next.path().size() > 1) {
                    Route.Adoption adoption = RouteAdoption.adopt(nav, next, world, foot);
                    if (!adoption.adopted()) {
                        // Mis-anchored continuation (the bot never made it to the
                        // commitEnd it was computed from) — drop it and force a
                        // fresh foot-search next tick instead of hanging on an
                        // unreachable step 1.
                        nav.route.abandonSegment("continuation refused: " + adoption.refusal());
                        Keys.forward(a, false);
                        nav.keys.jump(a, false);
                        p.setSprinting(false);
                        return TickStep.WALKING;
                    }
                    // adopted: step→1 on the new segment; fall through to walk it
                } else {
                    // Stale eager continuation found nothing — at a chunk frontier the
                    // newly-loaded terrain may now reveal the next segment, so re-search
                    // fresh before giving up (bounded).
                    return nav.search.frontierHoldOrArrive(nav, a, world, p);
                }
            } else {
                if (!nav.route.replay() && nav.route.seg.activeSearch == null && nav.route.seg.commitEnd != null) nav.route.searchFromEnd(nav, world);
                // PROGRESSIVE QUICK-START at the splice gap: the continuation
                // search hasn't landed yet (eager precompute missed this one) —
                // walk a synchronous stub toward the goal instead of holding
                // at the segment end until it does.
                if (nav.route.replay() || nav.route.seg.activeSearch == null
                        || (!RouteStubs.landBeeline(nav, world, foot, nav.goal)
                            && !RouteStubs.waterBeeline(nav, world, foot, nav.goal) && !RouteStubs.quickStart(nav, world, foot, nav.goal))) {
                    Keys.forward(a, false);
                    nav.keys.jump(a, false);
                    p.setSprinting(false);
                    return TickStep.WALKING;
                }
                // stub adopted (path replaced, step=1) → fall through and walk it
            }
        }
        // ---- publish: write this phase's products for the downstream phases (TickCtx) ----
        return null;
    }
}
