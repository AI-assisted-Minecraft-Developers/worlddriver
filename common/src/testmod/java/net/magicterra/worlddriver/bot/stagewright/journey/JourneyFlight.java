package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.magicterra.worlddriver.bot.movement.Walker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * How the body actually travelled one leg — recorded tick by tick, not read off the wreckage.
 *
 * <h2>Why a recorder and not another snapshot</h2>
 *
 * Every reading the nether crossing has produced so far is taken AFTER the goto run gave up:
 * {@code fortress.around.1} reported cave_air below the feet, cave_air in the bot's own cell and
 * air overhead, then two attempts reported lava on all six sides. Those lines say where the bot
 * ENDED. They cannot say how it got there, and the three ways it could have are the three
 * different bugs:
 *
 * <ol>
 *   <li>the floor it was standing on stopped being a floor — a thin roof, gravel, or the walker
 *       breaking through its own support;</li>
 *   <li>it walked off the edge of a floor that is still there — the planned route crossed a cave
 *       mouth or a lava shore;</li>
 *   <li>it never left the ground under its own steam and the leg was judged finished while it was
 *       in the air, in which case the fall is downstream of the verdict, not upstream of it.</li>
 * </ol>
 *
 * <p>All three end with a body hanging in {@code cave_air}, and the snapshot that reports that is
 * the same line in all three cases.
 *
 * <h2>Which cells it asks about, and why that had to be fixed once already</h2>
 *
 * Reading only the single cell at {@code blockPosition().below()} prints "left the ground in place
 * (air below the feet)", a line with two completely different causes and no way to tell them
 * apart, because <b>a player is 0.6 blocks wide and its support is whatever its bounding box
 * rests on</b>, which is up to four cells and frequently not the one under its centre. A body
 * standing on the last block of a lava shore has air directly beneath its feet position for a
 * whole tick before it falls. So the recorder reads the <i>footprint</i>: every cell the bounding
 * box spans, at the y just below it. Keeping the previous tick's footprint POSITIONS (not just
 * their names) is what makes "the floor was removed" and "the body walked off it" separable —
 * the same cells are re-read after the fall starts.
 *
 * <h2>What it reads, and from where</h2>
 *
 * The level and the body's own physics, never the bot's world view — the open question includes
 * "is the plan going somewhere the world does not agree with", and a view is not a witness to that.
 * Everything it touches is in the body's own chunk or the one it just left, both loaded by
 * definition, so a leg costs a handful of block reads per tick and no chunk loads.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * It never fails, never steers and never touches the world. A recorder that could end a leg would
 * change the thing it is measuring, and the current diagnosis needs an unmodified crossing far more
 * than it needs another guard. {@link JourneyNetherRungs}'s {@code hazardBlockingARetry} is the
 * guard; this is the instrument.
 */
public final class JourneyFlight implements JourneyRig.TickWatcher {

    /** A drop worth writing down. Three blocks: one and two are step-downs an ordinary walk makes
     *  constantly, and a list that recorded those would bury the twenty-block fall in noise. */
    private static final int NOTABLE_DROP = 3;

    /** How many falls get their own line before the rest are only counted. Six is more than any
     *  healthy leg has and few enough to stay readable in one evidence value. */
    private static final int MAX_FALLS = 6;

    /** How many ticks of run-up are kept for the first notable fall. Eight is about half a second —
     *  long enough to show the body walking to the edge, short enough to read in one line. */
    private static final int RUN_UP = 8;

    /** How many track samples to keep. When full the track halves itself and doubles its stride, so
     *  a leg of any length ends with the same number of evenly spaced samples — the alternative is
     *  choosing a stride up front, which is choosing wrong for either a 300-tick leg or a 48000-tick
     *  one. */
    private static final int TRACK_SAMPLES = 16;

    private final JourneyRig rig;
    private final BlockPos from;
    private final int goalX;
    private final int goalZ;
    private final int legLength;

    /** How far down vanilla's own "am I standing on something" question reaches: one tick of
     *  gravity (0.08 × 0.98). A grounded body's {@code deltaMovement.y} sits there every tick —
     *  the collision that clips it is exactly what sets {@code onGround}. */
    private static final double GROUND_PROBE = 0.0784;

    /** A player box is 0.6 × 0.6 = 0.36 of ground. Below a quarter of that the body is cornering
     *  on one block rather than standing on the ground, which is the state a walk cannot survive
     *  any drift from. */
    private static final double FULL_CONTACT = 0.36;
    private static final double EDGE_CONTACT = 0.09;

    /** How far the neighbourhood map looks down for lava. Twelve: past that a drop is fatal on
     *  its own and the map's job is to say which cells are a lava rim, not how deep it is. */
    private static final int LAVA_PROBE = 12;

    private int t;
    private BlockPos prev;
    private boolean prevOnGround;
    private double prevY;
    private List<BlockPos> prevSupport = List.of();
    private String prevSupportNames = "none";
    private int prevSupportSolid;
    private AABB prevBox;
    private Vec3 prevDelta = Vec3.ZERO;
    private double prevContact;
    private boolean prevSwept;
    private boolean prevSneak;
    private String prevDrive;
    private String prevJumpTag;

    /** The least of the body's own footprint that was ever holding it up, while grounded. A leg
     *  that never drops below {@link #FULL_CONTACT} walked on ground; one that spends ticks near
     *  zero walked a knife edge, and that is a property of the ROUTE, not of the fall it ends in. */
    private double leastContact = Double.MAX_VALUE;
    private String leastContactAt = "";
    private int edgeTicks;
    private int groundedTicks;

    /** One physics dump per recorded fall — see {@link #groundDump}. */
    private final List<String> grounds = new ArrayList<>();

    /** Which move entered each node this leg walked, counted once per node. */
    private final Map<String, Integer> moveTally = new LinkedHashMap<>();
    private BlockPos lastPlanNode;

    /**
     * How many nodes this goto run entered by a given move — the same tally the report's
     * "edges walked" clause prints, readable by the caller.
     *
     * <p>Exists so a caller can carry a total ACROSS legs. One leg's {@code {bridgePlace=15}} reads
     * as a detail; the corridor's legs 6..11 summing to sixty-odd with {@code walk=0} is the finding
     * — the surveyed waypoints are not terrain, they are the causeway a previous run BUILT, so every
     * one of them has to be rebuilt. No single leg's row can say that.
     */
    int moveCount(String move) {
        return moveTally.getOrDefault(move, 0);
    }

    /**
     * The vertical bill the PLAN ran up, split from the vertical the bot took by falling.
     *
     * <h2>"y 41→5" says how far down the bot got. It does not say what took it there.</h2>
     *
     * Two rung-14 goto runs died the same way and neither line could name the mechanism: the
     * rehearsal's wp4 descended 22 blocks into lava, the real ladder's wp5 descended 36 to bedrock,
     * and both ended {@code expanded=1}, unable to plan back out. Both printed a y range and an edge
     * tally, and neither answers <b>whether A* deliberately routed downward or the bot fell off
     * something the plan expected it to stand on.</b> Those two want opposite fixes: the first is a
     * cost the planner is missing, the second is an executor that left the path.
     *
     * <p>So: sum the y deltas <b>between consecutive plan nodes</b>. That is the descent the planner
     * ASKED for. Compare it with the bot's own drop (already in the y-range clause) and the
     * difference is what gravity contributed. {@code descentByMove} attributes the planned half to
     * the move kinds that spent it, because the evidence already showed {@code diagAscendPenalty}
     * pricing diagonal ASCENT while {@code diagDown} edges went unpriced, and a per-move split is
     * what turns "descent has no cost" from a guess into a number.
     *
     * <p><b>Measured before priced.</b> Adding a descent penalty now would be a free parameter with
     * no measurement behind it, and this repo has already paid for one of those; the two-armed
     * comparison it would need has to have something to compare.
     */
    private int plannedDown;
    private int plannedUp;
    private final Map<String, Integer> descentByMove = new LinkedHashMap<>();

    /** See {@link #openLeap} — every parkour launch, so successful ones have rows too. */
    private final List<String> leapLines = new ArrayList<>();
    private int leaps;
    private String pendingLeap;
    private BlockPos pendingLeapTarget;
    private static final int MAX_LEAPS = 12;

    private boolean airborne;
    private BlockPos launchAt;
    private int launchTick;
    private int launchY;
    private String launchWhy = "";
    private String launchPlan = "";
    private String launchGround = "";
    private float deepestFallField;
    private double fastestDrop;

    private final List<String> falls = new ArrayList<>();
    private int fallCount;
    private int deepestDrop;
    private boolean startedAirborne;
    private String lava;
    private String finishedAt;
    private String runUpOfFirstFall;
    private String pendingRunUp;

    private int lowestY = Integer.MAX_VALUE;
    private int highestY = Integer.MIN_VALUE;

    /** How far the body ever got from the node it was steering at, WHILE ON THE GROUND, and where.
     *  Grounded only on purpose: a body mid-fall is trivially far from its plan and that says
     *  nothing — the question this answers is whether the body walks off its own route before any
     *  fall starts. */
    private double worstOffPlan = -1;
    private String worstOffPlanAt = "";
    /** The worst amount by which the node being steered at was FURTHER from this leg's goal than the
     *  body itself, and where. Never negative in a healthy leg by more than a node's own length. */
    private double backwards = -1;
    private String backwardsAt = "";
    /** Ticks the walker had no node to steer at. A leg that spends most of itself here is not being
     *  steered at all, which is a different machine from one steered at a bad node. */
    private int ticksWithNoPlan;

    private final List<String> runUp = new ArrayList<>();
    private final List<String> track = new ArrayList<>();
    private int trackStride = 100;
    private int trackNext = 0;

    private JourneyFlight(JourneyRig rig, BlockPos from, int goalX, int goalZ) {
        this.rig = rig;
        this.from = from;
        this.goalX = goalX;
        this.goalZ = goalZ;
        this.legLength = (int) Math.round(Math.hypot(from.getX() - goalX, from.getZ() - goalZ));
        this.repathsAtStart = Walker.guardForcedRepaths;
        this.keptAtStart = Walker.guardKeptPlans;
        this.advancesAtStart = Walker.stepAdvancesLogged;
        this.strideFiresAtStart = Walker.strideGuardFires;
        this.strideSkipsAtStart = new long[Walker.STRIDE_SKIP_REASONS.length];
        for (int i = 0; i < strideSkipsAtStart.length; i++)
            strideSkipsAtStart[i] = Walker.strideGuardSkips.get(i);
        // THE leg boundary, for everything that is budgeted per leg. Walker#newLeg's note says why
        // it is taken from here and not given a definition of its own.
        Walker.newLeg();
    }

    /** {@link Walker#strideGuardFires} and {@link Walker#strideGuardSkips} when this leg began.
     *
     *  <p>Reported only on a goto run that actually entered lava — see {@link #report()}. Elsewhere
     *  the distribution is just terrain, and a row printed on every run is a row nobody reads. On
     *  the run that burned, it is the only thing that separates "the guard let the bot through"
     *  from "the guard was never in a position to fire", and those want opposite fixes. */
    private final int strideFiresAtStart;
    private final long[] strideSkipsAtStart;

    /** {@link Walker#guardForcedRepaths} when this leg began, so the leg can report its own DELTA.
     *
     *  <p>The one thing the walker does that used to leave no trace: a sustained guard pin throws
     *  the plan away. On the 2026-08-20 shuttle that made two opposite diagnoses fit every row —
     *  a body given a bad plan, and a body whose good plan kept being discarded under it — and the
     *  evidence map could not choose. A per-leg count, next to the moves the leg walked, can. */
    private final int repathsAtStart;

    /** {@link Walker#guardKeptPlans} when this leg began. <b>Reported beside the discards and never
     *  instead of them</b>: since 2026-08-21 a pinned streak has two outcomes, so a leg that reports
     *  zero discards may have been pinned to the threshold dozens of times and kept its plan every
     *  time. 0/0 was never pinned; 0/45 walked a rim. One number can no longer say which. */
    private final int keptAtStart;

    /** {@link Walker#stepAdvancesLogged} when this leg began — the denominator that says whether the
     *  advance log a reader is holding is the WHOLE leg or only its opening. */
    private final int advancesAtStart;

    /** Start watching a leg that is about to be walked towards the column {@code (x, z)}. */
    public static JourneyFlight watching(JourneyRig rig, BlockPos from, int x, int z) {
        return new JourneyFlight(rig, from, x, z);
    }

    @Override
    public void tick() {
        ServerPlayer fp = rig.player();
        ServerLevel level = fp.serverLevel();
        BlockPos at = fp.blockPosition();
        boolean onGround = fp.onGround();
        boolean inLava = fp.isInLava();
        boolean inWater = fp.isInWater();
        t++;
        lowestY = Math.min(lowestY, at.getY());
        highestY = Math.max(highestY, at.getY());
        sample(at, onGround, inLava);

        List<BlockPos> support = footprint(fp);
        int solid = 0;
        for (BlockPos p : support) if (level.getBlockState(p).blocksMotion()) solid++;
        AABB box = fp.getBoundingBox();
        double contact = contactArea(level, box);
        if (onGround) {
            groundedTicks++;
            if (contact < EDGE_CONTACT) edgeTicks++;
            if (contact < leastContact) {
                leastContact = contact;
                leastContactAt = "t=" + t + " " + at.toShortString();
            }
        }
        watchThePlan(level, fp, at, onGround);

        // The tick the leg was decided on, whatever the verdict.
        //
        // NOT ServerWorldDriver.lastStep(). On the runProcess path that field is set to ARRIVED for
        // ANY process that reports done — a walk that ended `no path (expanded=1)` prints ARRIVED
        // there, so reading it would print `step=ARRIVED … immersed in lava`, which reads as
        // "the walker believed it had arrived, in lava" and is simply false. The
        // process's own honest verdict is the goto slot's, so that is what this asks.
        if (finishedAt == null && rig.body().finished()) {
            // Server BotState on purpose, and NOT for the usual reason: goalReached/endReason ARE in
            // ProcessSlot.snapshot(), so `rig.slot("goto")` could serve this. The gate above cannot.
            // `finished()` asks the SERVER driver whether its process ended, and on the client helm
            // startLeg never gave it one — so routing the read under a dead gate would change
            // nothing observable. Gate and reading have to move together; see JourneyRig.slot.
            var slot = rig.body().botState().mc_goto;
            finishedAt = "goalReached=" + slot.goalReached + " end=" + slot.endReason
                    + " at " + at.toShortString() + " onGround=" + onGround
                    + " support " + names(level, support)
                    + (inLava ? " immersed in lava" : inWater ? " immersed in water" : "");
        }

        if (lava == null && inLava) {
            lava = "t=" + t + " " + at.toShortString() + ", "
                    + (airborne ? "fell in from y=" + launchY : "walked in")
                    + "; " + planCell(level, fp, at);
        }

        if (prev == null) {                        // first tick of the goto run
            startedAirborne = !onGround;
            // A GOTO RUN CAN INHERIT A FALL, AND launch() CAN NEVER SEE ONE. It fires on a grounded →
            // airborne TRANSITION (`prevOnGround && !onGround`), so a run handed a bot that is
            // already in the air has no grounded previous tick and never arms any of it: `airborne`
            // would stay false for the whole run, and every reading taken off that flag would
            // describe a bot that was standing.
            //
            // Measured on corridor wp5 in the 2026-08-21 rehearsal, a run the previous movement task
            // handed over mid-fall: the first lava entry was reported as "t=18 64,29,88, walked in"
            // for a bot that had dropped thirteen blocks to get there, and "left the ground 0 times"
            // for a run that spent all 269 of its ticks off the ground. The first row is the
            // expensive one, because it names a MECHANISM: it says the walker steered into lava,
            // which sends a reader after the cost function instead of after the movement task
            // before it.
            if (startedAirborne && !inLava && !inWater) {
                airborne = true;
                launchAt = at;
                launchTick = t;
                launchY = at.getY();
                launchWhy = "(not on the ground at the start; this run saw no take-off, the bot was"
                        + " already falling when the previous movement task handed it over)";
                launchGround = "not on the ground at the start, no take-off cell to record";
            }
            remember(fp, at, onGround, support, names(level, support), solid, box, contact);
            return;
        }

        if (airborne) {
            deepestFallField = Math.max(deepestFallField, fp.fallDistance);
            fastestDrop = Math.max(fastestDrop, prevY - fp.getY());
            if (onGround || inLava || inWater) land(level, at, onGround, inLava, support);
        } else if (prevOnGround && !onGround && !inLava && !inWater) {
            launch(level, fp, at, support);
        }
        rememberRunUp(at, onGround, solid, contact);
        remember(fp, at, onGround, support, names(level, support), solid, box, contact);
    }

    /**
     * Note the cell the body just left the ground from, and WHY it stopped being ground.
     *
     * <p>Three readings, and the order they are tested in is the diagnosis. The previous tick's
     * footprint POSITIONS are re-read now: solid then and gone now is a floor that disappeared under
     * a standing body. Still solid, and the body's footprint has moved off it, is a body that walked
     * over the edge — and the cells it walked ONTO name what it walked into. Still solid and the
     * footprint unchanged is neither, and says so rather than picking one.
     *
     * <p><b>And the plan, snapshotted HERE.</b> All three of those readings are about the cell under
     * the body's own feet, and none of them can say whether the body was doing what it was told: a
     * next node across a gap and a next node the body overshot leave identical footprints. See
     * {@link #planCell}. It is taken at the launch tick and not at the landing tick because those
     * are different plans — a fall lasts long enough for the walker to consume steps, repath, or run
     * out of path entirely, and the question is what it was steering at when it left the ground.
     */
    private void launch(ServerLevel level, ServerPlayer fp, BlockPos at, List<BlockPos> support) {
        int stillSolid = 0;
        for (BlockPos p : prevSupport) if (level.getBlockState(p).blocksMotion()) stillSolid++;
        boolean movedOff = !prevSupport.equals(support);
        airborne = true;
        launchAt = prev;
        launchTick = t;
        launchY = prev.getY();
        deepestFallField = 0;
        fastestDrop = 0;
        // Snapshot the run-up HERE and not when the fall lands. By landing time an eleven-block drop
        // has rolled eight airborne ticks through the ring, and the evidence line would read
        // `t=72 air support0 | t=73 air support0 | …`: a perfect record of the fall and none at all
        // of the walk into it, which is the half being asked about. Held
        // pending rather than committed, because a launch is not yet a notable fall: an ordinary
        // one-block step down also leaves the ground, and the run-up wanted is the one belonging to
        // the drop that gets written down.
        pendingRunUp = String.join(" | ", runUp);
        launchPlan = planCell(level, fp, at);
        launchGround = groundDump(level, fp);
        openLeap(fp);
        if (prevSupportSolid > 0 && stillSolid == 0) {
            launchWhy = "the " + prevSupportSolid + " cell(s) supporting it on the previous tick are"
                    + " gone (" + prevSupportNames + " → " + names(level, prevSupport) + ")";
        } else if (prevSupportSolid > 0 && movedOff) {
            launchWhy = "walked off its support cells (on the previous tick it stood on "
                    + prevSupportNames + ", on this tick below the feet is " + names(level, support)
                    + ")";
        } else if (prevSupportSolid == 0) {
            launchWhy = "there were already no support cells on the previous tick ("
                    + prevSupportNames + "), yet onGround still reported true: the support is"
                    + " elsewhere, or onGround lags by one tick";
        } else {
            launchWhy = "the support cells are still there and the bot did not move off them ("
                    + prevSupportNames + ")";
        }
    }

    /**
     * Every parkour launch, not only the ones that ended badly — <b>the control group.</b>
     *
     * <h2>A record that only exists when the body fell cannot say whether the fall was unusual</h2>
     *
     * The {@code fell.*} rows are written at {@link #land} and only when the drop is notable, so a
     * leg where every leap worked leaves no leap rows at all. That made the rung-14 wp5 reading
     * unreadable in the direction that matters: the body took a {@code parkour2d} whose target was
     * <b>0.64 blocks away and one block DOWN</b>, jumped, and fell fifteen blocks into lava — and
     * nothing in the file could say whether launching at 0.64 remaining is routine (so the distance
     * is not the variable) or exceptional (so it is).
     *
     * <p>What is captured is the launch-tick <b>remaining horizontal distance to the node being
     * steered at</b>, because that is the quantity {@code WalkerTickDrive}'s sprint exemption does
     * NOT look at: {@code (!lethalNear || parkourEdge)} keeps the sprint impulse and
     * {@code && !parkourEdge} drops the sneak brake for <i>any</i> parkour edge, measured on a long
     * void leap where the impulse is required. Whether that generalises to a 0.64-block hop is the
     * open question, and it needs a distribution rather than one anecdote.
     *
     * <p><b>Closed at landing with the overshoot</b>, so each row is a pair: what was asked for and
     * what happened. A leap that lands on its node and a leap that sails past it are otherwise the
     * same two coordinates in a log.
     */
    private void openLeap(ServerPlayer fp) {
        // Server BotState on purpose: pathMove/pathNode are excluded from ProcessSlot.snapshot(), so
        // JourneyRig.slot has no routed reading to give. Dead on the client helm until status() carries them.
        String move = rig.body().botState().mc_goto.pathMove;
        if (move == null || !move.startsWith("parkour")) { pendingLeap = null; return; }
        BlockPos node = rig.body().botState().mc_goto.pathNode;
        pendingLeapTarget = node;
        String want = node == null ? "no node" : node.toShortString();
        String gap = node == null ? "not measured (no node was held on the take-off tick)"
                : String.format(Locale.ROOT, "%.2f blocks horizontal remaining, dy=%d",
                        Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ()),
                        node.getY() - launchY);
        pendingLeap = "t=" + t + " " + move + " took off from " + launchAt.toShortString()
                + " aiming at " + want + " (" + gap + ")";
    }

    /** Close the pending leap with where it actually put the bot. */
    private void closeLeap(BlockPos at, boolean inLava) {
        if (pendingLeap == null) return;
        leaps++;
        if (leapLines.size() < MAX_LEAPS) {
            String miss = pendingLeapTarget == null ? "no target to compare against"
                    : String.format(Locale.ROOT, "landed at %s, %.2f blocks horizontal from the"
                                    + " target, dy=%d",
                            at.toShortString(),
                            Math.hypot(at.getX() - pendingLeapTarget.getX(),
                                    at.getZ() - pendingLeapTarget.getZ()),
                            at.getY() - pendingLeapTarget.getY());
            leapLines.add("#" + leaps + " " + pendingLeap + " → " + miss
                    + (inLava ? ", landed in lava" : ""));
        }
        pendingLeap = null;
        pendingLeapTarget = null;
    }

    /** Close a fall episode and, if it was worth recording, write its line. */
    private void land(ServerLevel level, BlockPos at, boolean onGround, boolean inLava,
                      List<BlockPos> support) {
        closeLeap(at, inLava);
        int drop = launchY - at.getY();
        deepestDrop = Math.max(deepestDrop, drop);
        boolean worth = drop >= NOTABLE_DROP || inLava;
        airborne = false;
        if (!worth) return;
        fallCount++;
        if (runUpOfFirstFall == null) runUpOfFirstFall = pendingRunUp;
        if (falls.size() >= MAX_FALLS) return;
        grounds.add("#" + fallCount + " " + launchGround);
        String ended = inLava ? "fell into lava at " + at.toShortString()
                : onGround ? "landed at " + at.toShortString() + " (below the feet "
                             + names(level, support) + ")"
                : "fell into water at " + at.toShortString();
        // The two speeds are printed together on purpose. fastestDrop is measured off the body's own
        // y; fallDistance is the field vanilla keeps. Until 2026-09-14 the server body's stayed 0
        // forever, because ServerPlayer.checkFallDamage (the one Entity.move calls) is an EMPTY
        // override and the accumulating version, doCheckFallDamage, runs only off a movement packet
        // that body never sent; printing both made that provable from an evidence row: 1.14 blocks in
        // one tick against a fallDistance of 0.0. JoinedBody.pump runs the packet tail now, so the
        // two should agree, and a row where they do not is worth reading again.
        falls.add("#" + fallCount + " t=" + launchTick + " from " + launchAt.toShortString()
                + " " + launchWhy + " → " + ended
                + ", fell " + drop + " blocks (fastest single tick "
                + String.format(Locale.ROOT, "%.2f", fastestDrop)
                + " blocks; largest fallDistance over the same span "
                + String.format(Locale.ROOT, "%.1f", deepestFallField)
                + ")"
                + ", walked " + walkedAt(launchAt) + "/" + legLength + " blocks so far"
                + ", " + launchPlan);
    }

    /**
     * Keep the worst distance between the body and the node it is being steered at.
     *
     * <p>The falls list answers "what happened at the edge". This answers the question one step
     * earlier: was the body ON its route at all. A walk whose worst grounded offset is a block and a
     * half is executing its plan and fell off a plan that went somewhere bad; one that reaches six
     * blocks off is not executing it, and the plan's quality is beside the point.
     */
    private void watchThePlan(ServerLevel level, ServerPlayer fp, BlockPos at, boolean onGround) {
        // Server BotState on purpose: pathNode is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
        BlockPos node = rig.body().botState().mc_goto.pathNode;
        if (node == null) { ticksWithNoPlan++; return; }
        // WHICH EDGES THIS LEG ACTUALLY WALKED, counted once per node rather than per tick.
        // A leg's fall names one move; this names the diet. It is also the only honest check that
        // a change to what the PLANNER is allowed to cost reached the plan at all — a cost knob
        // set on a static and read on another tick can silently do nothing, and a leg that still
        // walks the move it was told to avoid says so here instead of being argued about.
        if (!node.equals(lastPlanNode)) {
            // Server BotState on purpose: pathMove is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
            String move = rig.body().botState().mc_goto.pathMove;
            String kind = move == null ? "?" : move;
            moveTally.merge(kind, 1, Integer::sum);
            // The y delta BETWEEN PLAN NODES, which is the descent the planner asked for — see
            // plannedDown. Skipped on the first node of the leg because there is no previous node to
            // subtract, and a leg's opening node is where the body already is, not a step it took.
            if (lastPlanNode != null) {
                int dy = node.getY() - lastPlanNode.getY();
                if (dy < 0) {
                    plannedDown += -dy;
                    descentByMove.merge(kind, -dy, Integer::sum);
                } else {
                    plannedUp += dy;
                }
            }
            lastPlanNode = node;
        }
        if (!onGround) return;
        // IS THE PLAN POINTING BACKWARDS? The node's distance to this leg's goal, minus the body's.
        // Positive means the walker is being steered further from the goal than it already is.
        //
        // This exists because the forced-repath line cannot answer it in the case that matters
        // most. That line names the discarded plan's last node, but only when a discard happens,
        // and "the plans themselves route backwards" is precisely the branch where no discard does.
        // Measured per grounded tick out of the goal this leg already holds, so it needs no plan
        // end node and no plumbing: a leg that walks 61 edges to a net −8 either shows a positive
        // worst here (bad plans) or does not (good plans, taken away).
        double nodeAway = Math.hypot(node.getX() + 0.5 - goalX, node.getZ() + 0.5 - goalZ);
        double bodyAway = Math.hypot(fp.getX() - goalX, fp.getZ() - goalZ);
        if (nodeAway - bodyAway > backwards) {
            backwards = nodeAway - bodyAway;
            // Server BotState on purpose: pathMove is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
            backwardsAt = "t=" + t + " bot " + at.toShortString() + " (" + Math.round(bodyAway)
                    + " from the goal), next plan cell " + node.toShortString() + "["
                    + rig.body().botState().mc_goto.pathMove + "] (" + Math.round(nodeAway)
                    + " from the goal)";
        }
        double gap = Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ());
        if (gap <= worstOffPlan) return;
        worstOffPlan = gap;
        // Server BotState on purpose: pathMove is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
        worstOffPlanAt = "t=" + t + " bot " + at.toShortString() + ", next plan cell "
                + node.toShortString() + "[" + rig.body().botState().mc_goto.pathMove + "]"
                + ", the block under it " + blockName(level, node.below());
    }

    private void remember(ServerPlayer fp, BlockPos at, boolean onGround,
                          List<BlockPos> support, String supportNames, int solid,
                          AABB box, double contact) {
        prev = at;
        prevY = fp.getY();
        prevOnGround = onGround;
        prevSupport = support;
        prevSupportNames = supportNames;
        prevSupportSolid = solid;
        prevBox = box;
        prevDelta = fp.getDeltaMovement();
        prevContact = contact;
        prevSwept = !fp.serverLevel().noCollision(fp, groundSlab(box));
        prevSneak = rig.body().avatar().dbgSneak();
        // Server BotState on purpose: driveTag/jumpTag are excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
        prevDrive = rig.body().botState().mc_goto.driveTag;
        prevJumpTag = rig.body().botState().mc_goto.jumpTag;
    }

    /** The last few ticks, kept so the FIRST notable fall can show its run-up. The contact area
     *  is what makes the run-up readable as a DRIFT: a body walking a knife edge sheds it tick by
     *  tick, and the count of solid cells cannot show that (it is 1 the whole way). */
    private void rememberRunUp(BlockPos at, boolean onGround, int solid, double contact) {
        runUp.add("t=" + t + " " + at.toShortString() + (onGround ? " ground" : " air") + " support"
                + solid + " contact" + String.format(Locale.ROOT, "%.3f", contact));
        if (runUp.size() > RUN_UP) runUp.remove(0);
    }

    /**
     * Keep an evenly spaced track of the whole leg, at whatever stride fits.
     *
     * <p>The falls list says where the body dropped; this says what the leg looked like between
     * them. A crossing that descended steadily for two hundred blocks and one that walked level and
     * then fell off a cliff produce the same fall entry and completely different tracks.
     */
    private void sample(BlockPos at, boolean onGround, boolean inLava) {
        if (t < trackNext) return;
        track.add(at.getX() + "," + at.getY() + "," + at.getZ()
                + (inLava ? "(lava)" : onGround ? "" : "(airborne)"));
        trackNext = t + trackStride;
        if (track.size() < TRACK_SAMPLES) return;
        for (int i = track.size() - 1; i > 0; i -= 2) track.remove(i);
        trackStride *= 2;
    }

    // -----------------------------------------------------------------------------------------
    // Reading it back.
    // -----------------------------------------------------------------------------------------

    /** One line for the whole leg: how far it got, how it moved, and how it ended. */
    public String report() {
        ServerPlayer fp = rig.player();
        BlockPos at = fp.blockPosition();
        StringBuilder sb = new StringBuilder();
        sb.append("walked ").append(walkedAt(at)).append('/').append(legLength).append(" blocks (")
          .append(awayFromGoal(at)).append(" still to go); y ").append(from.getY()).append('→')
          .append(at.getY());
        if (highestY != Integer.MIN_VALUE) {
            sb.append(" (highest ").append(highestY).append(", lowest ").append(lowestY)
              .append(" along the way)");
        }
        sb.append("; ").append(t).append(" ticks in total; left the ground ").append(fallCount)
          .append(" time(s)");
        if (fallCount > 0) sb.append(", deepest ").append(deepestDrop).append(" blocks");
        if (startedAirborne) sb.append("; not on the ground at the start");
        if (airborne) {
            sb.append("; still in the air at the end (took off from ").append(launchAt.toShortString())
              .append(" y=").append(launchY).append(", ").append(launchWhy).append(")");
        }
        // "Not measured" and "never had a plan" are different situations, and the counter beside it
        // separates them: this is only sampled on a GROUNDED tick that had a node, so a run spent
        // entirely airborne and a run the walker never steered both leave it unset.
        sb.append("; furthest from the plan ").append(worstOffPlan < 0
                ? "not measured (no tick was both on the ground and holding a plan)"
                : String.format(Locale.ROOT, "%.2f blocks (%s)", worstOffPlan, worstOffPlanAt));
        sb.append("; ").append(ticksWithNoPlan).append("/").append(t).append(" ticks with no plan");
        sb.append("; ").append(contactLine());
        sb.append("; edges walked ").append(moveTally.isEmpty()
                ? "none (this run executed no plan edges)" : moveTally);
        // WHAT TOOK THE BOT DOWN — the plan, or gravity. The y-range clause above says how far down
        // it got; this says how much of that A* deliberately asked for. Printed unconditionally,
        // including on a run that never descended, because "planned descent 0 blocks" on a bot that
        // ended 36 blocks lower is itself the finding, and a row that only appears when it is
        // interesting cannot be compared across two arms. See plannedDown.
        sb.append("; planned vertical total: down ").append(plannedDown).append(" blocks, up ")
                .append(plannedUp).append(" blocks (net ").append(plannedUp - plannedDown).append(")")
                .append(descentByMove.isEmpty()
                        ? ", no plan edge went downward, so every block the bot descended was a fall"
                        : ", descent came from " + descentByMove);
        // Unconditional for the same reason the vertical total is: "parkour take-offs 0" on a run
        // that ended in a lava pit rules out a whole family in one word. See openLeap.
        sb.append("; parkour take-offs ").append(leaps)
                .append(leaps > leapLines.size() ? " (only the first " + leapLines.size()
                        + " recorded individually)" : "")
                .append(pendingLeap == null ? "" : ", plus one take-off that had not landed when the"
                        + " run ended");
        // A run that walked 61 edges to a net −8 has either been given bad plans or has had good
        // ones taken away from it. This is the number that says which, and it is the run's own
        // delta rather than the JVM total — see repathsAtStart.
        sb.append("; plan pointing furthest backwards ").append(backwards < 0 ? "not measured"
                : String.format(Locale.ROOT, "%.2f blocks (%s)", backwards, backwardsAt));
        int advances = Walker.stepAdvancesLogged - advancesAtStart;
        sb.append("; step advances logged ").append(advances)
          .append(advances >= Walker.stepAdvanceBudget()
                  ? " (budget full, later advances were not logged; this run's step log is"
                    + " incomplete)"
                  : " (this run's step log is complete)");
        int repaths = Walker.guardForcedRepaths - repathsAtStart;
        int kept = Walker.guardKeptPlans - keptAtStart;
        // BOTH, ALWAYS, and never the discard alone — see keptAtStart. "0 discards" also covers a
        // run pinned to the threshold every thirty ticks that kept its plan each time, not only a
        // pin that never reached its threshold, and those are opposite terrain reports.
        sb.append("; guard pin reached the threshold ").append(repaths + kept)
          .append(" time(s): plan discarded ").append(repaths)
          .append(", kept as a rim walk ").append(kept)
          .append(repaths + kept == 0
                  ? " (this run never reached the pin threshold and kept the same plans throughout)"
                  : repaths == 0
                  ? " (no plan was ever discarded; the bot was advancing each time the pin reached"
                    + " the threshold)"
                  : ", last discard " + Walker.lastGuardRepath);
        sb.append("; at the moment the run ended: ").append(finishedAt == null
                ? "nothing; this run was stopped when its tick budget ran out, the process did not"
                  + " finish on its own"
                : finishedAt);
        if (lava != null) sb.append("; first lava entry ").append(lava).append("; ").append(strideGuardDelta());
        return sb.toString();
    }

    /**
     * This leg's share of the stride guard's fires and skips.
     *
     * <p>Deltas, not totals, for the reason {@link #repathsAtStart} spells out: a total answers
     * "did this ever happen on this run" when the question is "did it happen HERE". The buckets are
     * named by {@link Walker#STRIDE_SKIP_REASONS} rather than by index, so a reader does not have to
     * hold the order in their head and a bucket added later cannot silently shift the meaning of
     * this row.
     */
    private String strideGuardDelta() {
        StringBuilder sb = new StringBuilder("stride guard fired ")
                .append(Walker.strideGuardFires - strideFiresAtStart)
                .append(" time(s) during this run; reasons it did not fire: ");
        for (int i = 0; i < Walker.STRIDE_SKIP_REASONS.length; i++)
            sb.append(i == 0 ? "" : ", ").append(Walker.STRIDE_SKIP_REASONS[i]).append('=')
              .append(Walker.strideGuardSkips.get(i) - strideSkipsAtStart[i]);
        return sb.toString();
    }

    /**
     * The same leg in one clause, for a crossing that records one of these per hop.
     *
     * <p>{@link #report()} is the right size for a leg that IS the crossing and the wrong size for
     * one of twenty — twenty of them in one evidence row is a paragraph nobody reads. What survives
     * the shortening is what differs between a healthy hop and a wedged one: distance, ticks, and
     * the two readings that name WHY a hop went nowhere (ticks with no plan at all, and falls).
     */
    public String brief() {
        BlockPos at = rig.player().blockPosition();
        StringBuilder sb = new StringBuilder();
        sb.append("walked ").append(walkedAt(at)).append('/').append(legLength).append(" blocks ")
          .append(t).append("t (no plan ").append(ticksWithNoPlan).append("t)");
        if (fallCount > 0) sb.append(", left the ground ").append(fallCount).append(" time(s), deepest ")
                             .append(deepestDrop).append(" blocks");
        if (lava != null) sb.append(", entered lava");
        return sb.toString();
    }

    /**
     * How narrow the ground under this leg got, as a property of the whole leg.
     *
     * <p>The falls list says where the body came off. This says whether it was ever properly on:
     * a body that spends a third of its grounded ticks under a quarter of a sole's worth of
     * contact is walking a knife edge, and the next fall is the terrain's, not the executor's.
     */
    private String contactLine() {
        if (groundedTicks == 0) return "not a single tick on the ground";
        return "narrowest footing contact " + (leastContact == Double.MAX_VALUE ? "not measured"
                : String.format(Locale.ROOT, "%.4f/%.2f (%s)", leastContact, FULL_CONTACT, leastContactAt))
                + "; " + edgeTicks + "/" + groundedTicks + " grounded ticks with contact area under "
                + String.format(Locale.ROOT, "%.2f", EDGE_CONTACT) + " (standing on one corner only)";
    }

    /** How many notable falls this leg had — see {@link #falls()} for what each was. */
    public int fallCount() { return fallCount; }

    /** The physics under the body on the tick before each recorded fall — see {@link #groundDump}. */
    public List<String> grounds() { return List.copyOf(grounds); }

    /** Ticks the walker had nothing to steer at. See {@link #ticksWithNoPlan}. */
    public int noPlanTicks() { return ticksWithNoPlan; }

    /** Ticks this goto run lasted. Read by the crossing so "no plan for 67 ticks" can be given the
     *  denominator that decides whether re-planning was the cost or a rounding error. */
    public int ticks() { return t; }

    /** How the body first entered lava on this leg, or null when it never did. */
    public String lavaLine() { return lava; }

    /** The fall lines, in order. Empty when the leg never left the ground by more than a step. */
    public List<String> falls() {
        if (fallCount <= falls.size()) return List.copyOf(falls);
        List<String> out = new ArrayList<>(falls);
        out.add("…plus " + (fallCount - falls.size()) + " more not recorded individually");
        return List.copyOf(out);
    }

    /** The leg's shape, sampled evenly. */
    public String trackLine() {
        return "one sample every " + trackStride + " ticks: " + String.join(" → ", track);
    }

    /**
     * Attach everything this recorder has to the rung's evidence, under {@code <what>.<tag>}.
     *
     * <p>Recorded on arrival as well as on failure, deliberately. A leg that reached its column
     * after a thirty-block fall into a cave arrived by the goal's definition and is still the
     * finding — and a PASS prints no evidence, so the only place that row can be read is the
     * results file, which is exactly where it belongs.
     */
    public void recordInto(String what, String tag) {
        rig.evidence(what + ".flight." + tag, report());
        List<String> lines = falls();
        for (int i = 0; i < lines.size(); i++) rig.evidence(what + ".fell." + tag + "." + i, lines.get(i));
        // The control group — see openLeap. Written even on a run where nothing went wrong, because
        // "0.64 blocks remaining at take-off" only means something against the leaps that worked.
        for (int i = 0; i < leapLines.size(); i++)
            rig.evidence(what + ".leap." + tag + "." + i, leapLines.get(i));
        for (int i = 0; i < grounds.size(); i++) rig.evidence(what + ".ground." + tag + "." + i, grounds.get(i));
        if (runUpOfFirstFall != null) rig.evidence(what + ".runUp." + tag, runUpOfFirstFall);
        rig.evidence(what + ".track." + tag, trackLine());
    }

    // -----------------------------------------------------------------------------------------

    /**
     * The cells the body's bounding box would rest on — its real support, not the one under its
     * centre.
     *
     * <p>A player box is 0.6 wide, so it spans one or two cells per axis and up to four in total.
     * Reading only {@code blockPosition().below()} is what made the first version of this recorder
     * unable to tell "the block was removed" from "the body was standing on the very edge of it".
     */
    private static List<BlockPos> footprint(ServerPlayer fp) {
        AABB box = fp.getBoundingBox();
        int y = Mth.floor(box.minY - 0.02);
        int x0 = Mth.floor(box.minX + 1.0E-4);
        int x1 = Mth.floor(box.maxX - 1.0E-4);
        int z0 = Mth.floor(box.minZ + 1.0E-4);
        int z1 = Mth.floor(box.maxZ - 1.0E-4);
        List<BlockPos> out = new ArrayList<>(4);
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++) out.add(new BlockPos(x, y, z));
        return List.copyOf(out);
    }

    /**
     * The slab immediately under the body's box — the volume vanilla sweeps to decide
     * {@code onGround}.
     *
     * <p>{@code Entity.move} asks for a collision against the box displaced by this tick's
     * {@code deltaMovement}, and a standing body's y-component is one tick of gravity, so a
     * collision inside this slab is EXACTLY the thing that sets {@code onGround}. Asking it
     * directly is what separates "onGround is lying" from "the reading looked at the wrong cells":
     * the two produce the same evidence line and want opposite fixes.
     */
    private static AABB groundSlab(AABB box) {
        return new AABB(box.minX, box.minY - GROUND_PROBE, box.minZ, box.maxX, box.minY, box.maxZ);
    }

    /**
     * How much of the body's 0.36 m² of sole is actually resting on something solid.
     *
     * <p><b>Enumerated the way VANILLA's collision does — outward by 1e-7 — and not the way
     * {@link #footprint} does, inward by 1e-4.</b> That difference is not pedantry: a body walking
     * off a ledge spends its last grounded tick overlapping the ledge by a hair, and an inward
     * epsilon DISCARDS exactly that cell. The old reading then printed "the previous tick had no
     * support at all and onGround still said true", which reads as an engine lie and is really a
     * body standing on a sliver. The area says which: a real sliver is a small positive number.
     *
     * <p>The row is {@code floor(minY − 1e-7)} — the row the sole sits ON, which is the block below
     * for a body flush on a full cube and the block ITSELF for one resting on a shorter shape
     * (soul sand, a slab). {@code floor(minY − 0.02)} answers the same for the first case and the
     * WRONG row for a body that has risen even 0.02 off the floor — the first tick of a jump.
     */
    private static double contactArea(ServerLevel level, AABB box) {
        int y = Mth.floor(box.minY - 1.0E-7);
        double area = 0;
        for (int x = Mth.floor(box.minX - 1.0E-7); x <= Mth.floor(box.maxX + 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ - 1.0E-7); z <= Mth.floor(box.maxZ + 1.0E-7); z++) {
                if (!level.getBlockState(new BlockPos(x, y, z)).blocksMotion()) continue;
                area += overlap(box.minX, box.maxX, x) * overlap(box.minZ, box.maxZ, z);
            }
        }
        return area;
    }

    private static double overlap(double lo, double hi, int cell) {
        return Math.max(0, Math.min(hi, cell + 1.0) - Math.max(lo, cell));
    }

    /**
     * Everything about the ground under the body on the tick BEFORE it left it.
     *
     * <p>The five questions the last three rounds each answered by guessing, in one row:
     * <ol>
     *   <li><b>Did it jump or did it walk off?</b> The y-velocity says so outright — a jump seeds
     *       +0.42 and a step off a ledge is −0.08. Every fall so far was argued about without it.</li>
     *   <li><b>Was {@code onGround} telling the truth?</b> The collision check 0.08 blocks below the
     *       feet is vanilla's own question, asked again here. Agreeing with {@code onGround} closes the "it lags a tick"
     *       theory; disagreeing is the first evidence for it.</li>
     *   <li><b>Which row did the old reading look at?</b> Both rows are printed when they differ.</li>
     *   <li><b>How much ground was left?</b> The contact area, in m² out of 0.36.</li>
     *   <li><b>Was it a one-block ridge over lava?</b> The 5×5 map, where {@code !} is an open cell
     *       with lava under it — the cells a drift lands in.</li>
     * </ol>
     */
    private String groundDump(ServerLevel level, ServerPlayer fp) {
        if (prevBox == null) return "no record of the previous tick";
        AABB box = prevBox;
        int row = Mth.floor(box.minY - 1.0E-7);
        int oldRow = Mth.floor(box.minY - 0.02);
        Vec3 now = fp.getDeltaMovement();
        StringBuilder sb = new StringBuilder();
        sb.append("previous tick: position ")
          .append(String.format(Locale.ROOT, "(%.3f, %.4f, %.3f)", box.minX + 0.3, box.minY, box.minZ + 0.3))
          .append(" velocity ").append(String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                  prevDelta.x, prevDelta.y, prevDelta.z))
          .append(" onGround=").append(prevOnGround)
          .append(" sneaking=").append(prevSneak)
          .append("; walker on that tick: ")
          .append(prevDrive == null
                  ? "did not reach the end of drive (an early-return branch; the lethal edge brake,"
                    + " sprint and jump all live at the end of drive, and none of them ran on this"
                    + " tick)"
                  : "drive=" + prevDrive)
          .append(" jump=").append(prevJumpTag == null ? "no branch commanded a jump" : prevJumpTag);
        sb.append("; vanilla's own check (any collision within ").append(GROUND_PROBE)
          .append(" blocks below the feet)=")
          .append(prevSwept ? "yes" : "no")
          .append(prevSwept == prevOnGround ? " (agrees with onGround; it did not lag a tick)"
                  : " (disagrees with onGround; onGround describes a different tick)");
        sb.append("; solid contact area ").append(String.format(Locale.ROOT, "%.4f/%.2f", prevContact, FULL_CONTACT));
        sb.append("; support row y=").append(row);
        if (row != oldRow) sb.append(" (the old reading used floor(minY-0.02)=").append(oldRow)
                             .append(", the wrong row)");
        sb.append(" ").append(contactCells(level, box, row));
        sb.append("; velocity y on this tick=").append(String.format(Locale.ROOT, "%.3f", now.y))
          .append(now.y > 0.15 ? " (a jump, not a walk-off)" : " (not a jump)");
        sb.append("; ").append(edgeBrakeVerdict(level,
                new BlockPos(Mth.floor(box.minX + 0.3), Mth.floor(box.minY), Mth.floor(box.minZ + 0.3))));
        sb.append("; footing 5×5 (y=").append(row).append(", rows z=")
          .append(Mth.floor(box.minZ + 0.3) - 2).append("..").append(Mth.floor(box.minZ + 0.3) + 2)
          .append(", columns x=").append(Mth.floor(box.minX + 0.3) - 2).append("..")
          .append(Mth.floor(box.minX + 0.3) + 2).append("): ").append(neighbourhood(level, box, row))
          .append(" (#=solid ~=lava !=open with lava below .=open with no lava below)");
        return sb.toString();
    }

    /**
     * Re-ask the walker's own lethal-edge question here, off the LEVEL, cell by cell.
     *
     * <p>"The body was not sneaking beside a lava lake" has three causes and the walker's telemetry
     * separates only one of them ({@code driveTag} says whether the tick reached the brake at all).
     * The other two are "the brake asked and got false" and "the brake asked about the wrong cells",
     * and nothing in the run could tell them apart — so this recomputes
     * {@code WalkerGeometry.dropAdjacentExceeds}'s loop verbatim and prints every neighbour's
     * verdict, plus the one cell that loop never looks at: <b>the body's own floor</b>.
     *
     * <p>Verdicts: {@code solid} the neighbour is solid (the loop skips it), {@code floor} it has a
     * floor one down, {@code dropN} an N-block dry drop, {@code lavaN} lava N down — lethal at any
     * depth.
     */
    private static String edgeBrakeVerdict(ServerLevel level, BlockPos foot) {
        StringBuilder sb = new StringBuilder("lethal edge brake recomputed against the level (foot="
                + foot.toShortString() + ", this cell's own floor " + blockName(level, foot.below())
                + (level.getBlockState(foot.below()).blocksMotion() ? " (holds)"
                        : " (does not hold, and the guard never checks this cell)")
                + "):");
        boolean lethal = false;
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            String verdict;
            if (level.getBlockState(n).blocksMotion()) {
                verdict = "solid";
            } else if (isLava(level, n.below())) {
                verdict = "lava0";
            } else if (level.getBlockState(n.below()).blocksMotion()) {
                verdict = "floor";
            } else {
                int fall = 1;
                BlockPos pr = n.below(2);
                String hit = null;
                while (fall <= SURVIVABLE_FALL + 2 && !level.getBlockState(pr).blocksMotion()) {
                    if (isLava(level, pr)) { hit = "lava" + fall; break; }
                    fall++;
                    pr = pr.below();
                }
                verdict = hit != null ? hit : fall > SURVIVABLE_FALL ? "drop>" + SURVIVABLE_FALL : "drop" + fall;
            }
            if (verdict.startsWith("lava") || verdict.startsWith("drop>")) lethal = true;
            sb.append(' ').append(o[0]).append('/').append(o[1]).append('=').append(verdict);
        }
        return sb.append(lethal ? " → should fire" : " → should not fire").toString();
    }

    /** The walker's own neighbour set, copied rather than imported: this is a re-derivation and it
     *  must stay one even if the walker's list changes — a copy that drifted would be visible, a
     *  shared constant would hide the drift. */
    private static final int[][] EDGE_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    /** {@code SurvivalMath.survivableFall(20)} — the threshold a full-health body's brake uses. */
    private static final int SURVIVABLE_FALL = 22;

    private static boolean isLava(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getFluidState().is(FluidTags.LAVA);
    }

    /** Every cell the box touches at {@code y}, with how much of the box each one holds up. A
     *  cell whose share is a ten-thousandth of a block is the whole diagnosis of one fall. */
    private static String contactCells(ServerLevel level, AABB box, int y) {
        StringBuilder sb = new StringBuilder("[");
        for (int x = Mth.floor(box.minX - 1.0E-7); x <= Mth.floor(box.maxX + 1.0E-7); x++) {
            for (int z = Mth.floor(box.minZ - 1.0E-7); z <= Mth.floor(box.maxZ + 1.0E-7); z++) {
                BlockPos c = new BlockPos(x, y, z);
                double share = overlap(box.minX, box.maxX, x) * overlap(box.minZ, box.maxZ, z);
                if (sb.length() > 1) sb.append(' ');
                sb.append(c.toShortString()).append('=').append(blockName(level, c))
                  .append(String.format(Locale.ROOT, "(%.4f)", share));
            }
        }
        return sb.append(']').toString();
    }

    /** The 5×5 of the support row around the body, and what a drift off each open cell lands in. */
    private static String neighbourhood(ServerLevel level, AABB box, int y) {
        int cx = Mth.floor(box.minX + 0.3);
        int cz = Mth.floor(box.minZ + 0.3);
        StringBuilder sb = new StringBuilder();
        for (int dz = -2; dz <= 2; dz++) {
            if (dz > -2) sb.append('/');
            for (int dx = -2; dx <= 2; dx++) {
                BlockPos c = new BlockPos(cx + dx, y, cz + dz);
                if (level.getBlockState(c).blocksMotion()) { sb.append('#'); continue; }
                if (level.getBlockState(c).getFluidState().is(FluidTags.LAVA)) {
                    sb.append('~');
                    continue;
                }
                sb.append(lavaUnder(level, c) ? '!' : '.');
            }
        }
        return sb.toString();
    }

    private static boolean lavaUnder(ServerLevel level, BlockPos from) {
        for (int d = 1; d <= LAVA_PROBE; d++) {
            BlockPos c = from.below(d);
            if (level.getBlockState(c).getFluidState().is(FluidTags.LAVA)) return true;
            if (level.getBlockState(c).blocksMotion()) return false;
        }
        return false;
    }

    private static String names(ServerLevel level, List<BlockPos> cells) {
        if (cells.isEmpty()) return "[none]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(cells.get(i).toShortString()).append('=').append(blockName(level, cells.get(i)));
        }
        return sb.append(']').toString();
    }

    /** Where in the plan the walker was, at the moment asked. A body that left the ground on step
     *  3 of 40 was following a route; one that left it with the plan exhausted was not. */
    private String planAt() {
        // Server BotState on purpose, and NOT for the usual reason: pathStep/pathLen ARE in
        // ProcessSlot.snapshot(). But this string is only ever spliced into planCell's rows, which
        // also print pathMove/pathNode — and those have no routed reading. Converting this half
        // alone would build one row out of two channels, one live and one dead, which is a worse
        // reading than a uniformly dead one. Both halves move when status() carries the plan fields.
        var slot = rig.body().botState().mc_goto;
        return "plan step " + slot.pathStep + "/" + slot.pathLen;
    }

    /**
     * WHICH CELL the plan was steering at, and whether that cell could hold a body.
     *
     * <p><b>The reading every earlier diagnosis of this crossing was missing.</b> Everything the
     * recorder knew was about the cell under the body's own FEET, and two completely different
     * failures write the same feet: a plan whose next node really is across a lava shore (the
     * planner is at fault) and a plan that is fine while the body slid past its node (the executor
     * is). One says re-plan in shorter hops, the other says stop overshooting, and choosing between
     * them without this row is guessing.
     *
     * <p>Three answers, and each is a different machine:
     *
     * <ul>
     *   <li><b>No node at all</b> — the plan was consumed and the body was still moving. Nothing
     *       was steering it off that edge, and no change to how routes are cut would help.</li>
     *   <li><b>A node with nothing under it</b> — the plan asked for this. The move name says
     *       whether it asked ON PURPOSE: every {@code Fall} edge carries its height in its name
     *       ({@code fall3}), so {@code walk} over a hole is a planner that read the world wrong,
     *       and {@code fall7} is a planner that was allowed to spend seven blocks of drop.</li>
     *   <li><b>A node standing on solid ground, some way off</b> — the plan was walkable and the
     *       body is not on it. That is the executor, and the horizontal gap is its size.</li>
     * </ul>
     *
     * <p>Read off the LEVEL, not the bot's world view: this rung's own guard exists because the
     * view can belong to another dimension, and a view is not a witness against itself.
     */
    private String planCell(ServerLevel level, ServerPlayer fp, BlockPos at) {
        // Server BotState on purpose: pathNode/pathMove are excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
        var slot = rig.body().botState().mc_goto;
        BlockPos node = slot.pathNode;
        if (node == null) {
            return "the plan has no next cell (" + planAt() + ", move=" + slot.pathMove
                    + "); no node was steering the bot at this moment";
        }
        BlockPos under = node.below();
        boolean holds = level.getBlockState(under).blocksMotion();
        double gap = Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ());
        return "next plan cell " + node.toShortString() + "[" + slot.pathMove + "] (" + planAt()
                + "): that cell=" + blockName(level, node) + ", the block under it "
                + under.toShortString() + "=" + blockName(level, under)
                + (holds ? " (holds)" : " (does not hold)")
                + ", " + String.format(Locale.ROOT, "%.2f", gap) + " blocks horizontal from the bot,"
                + " dy=" + (node.getY() - at.getY());
    }

    private int walkedAt(BlockPos at) {
        return (int) Math.round(Math.hypot(at.getX() - from.getX(), at.getZ() - from.getZ()));
    }

    private int awayFromGoal(BlockPos at) {
        return (int) Math.round(Math.hypot(at.getX() - goalX, at.getZ() - goalZ));
    }

    private static String blockName(ServerLevel level, BlockPos p) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(p).getBlock()).getPath();
    }
}
