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
 * Every reading the nether crossing has produced so far is taken AFTER the leg gave up:
 * {@code fortress.around.1 = 脚下=cave_air 身处=cave_air 头顶=air}, then two attempts of
 * {@code 六面全是 lava}. Those lines say where the body ENDED. They cannot say how it got there,
 * and the three ways it could have are the three different bugs:
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
 * The first version read the single cell at {@code blockPosition().below()} and printed
 * {@code 原地离地（脚下 air）} — a line with two completely different causes and no way to tell
 * them apart, because <b>a player is 0.6 blocks wide and its support is whatever its bounding box
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
    private String prevSupportNames = "无";
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
     * How many nodes this leg entered by a given move — the same tally {@code 走过的边} prints,
     * readable by the caller.
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
     * The vertical bill the PLAN ran up, split from the vertical the body took by falling.
     *
     * <h2>「y 41→5」says how far down the body got. It does not say who took it there.</h2>
     *
     * Two rung-14 legs died the same way and neither line could name the mechanism: the rehearsal's
     * wp4 descended 22 blocks into lava, the real ladder's wp5 descended 36 to bedrock, and both
     * ended {@code expanded=1} — unable to plan back out. Both printed a y range and an edge tally,
     * and neither answers <b>whether A* deliberately routed downward or the body fell off something
     * the plan expected it to stand on.</b> Those two want opposite fixes: the first is a cost the
     * planner is missing, the second is an executor that left the path.
     *
     * <p>So: sum the y deltas <b>between consecutive plan nodes</b>. That is the descent the planner
     * ASKED for. Compare it with the body's own drop (already in the y-range clause) and the
     * difference is what gravity contributed. {@code descentByMove} attributes the planned half to
     * the move kinds that spent it, because the evidence already showed {@code diagAscendPenalty}
     * pricing diagonal ASCENT while {@code diagDown} edges went unpriced — and a per-move split is
     * what turns「下潜没有标价」from a guess into a number.
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
        // THE leg boundary, for everything that is budgeted per leg. Walker#newLeg's note says why
        // it is taken from here and not given a definition of its own.
        Walker.newLeg();
    }

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
        // there, and the first version of this line did exactly that: `step=ARRIVED … 泡在岩浆里`,
        // which reads as "the walker believed it had arrived, in lava" and is simply false. The
        // process's own honest verdict is the goto slot's, so that is what this asks.
        if (finishedAt == null && rig.body().finished()) {
            // Server BotState on purpose, and NOT for the usual reason: goalReached/endReason ARE in
            // ProcessSlot.snapshot(), so `rig.slot("goto")` could serve this. The gate above cannot.
            // `finished()` asks the SERVER driver whether its process ended, and on the client helm
            // startLeg never gave it one — so routing the read under a dead gate would change
            // nothing observable. Gate and reading have to move together; see JourneyRig.slot.
            var slot = rig.body().botState().mc_goto;
            finishedAt = "goalReached=" + slot.goalReached + " end=" + slot.endReason
                    + " 在 " + at.toShortString() + " onGround=" + onGround
                    + " 支撑" + names(level, support)
                    + (inLava ? " 泡在岩浆里" : inWater ? " 泡在水里" : "");
        }

        if (lava == null && inLava) {
            lava = "t=" + t + " " + at.toShortString() + " —— "
                    + (airborne ? "从 y=" + launchY + " 掉进去的" : "走进去的")
                    + "；" + planCell(level, fp, at);
        }

        if (prev == null) {                        // first tick of the leg
            startedAirborne = !onGround;
            // A LEG CAN INHERIT A FALL, AND launch() CAN NEVER SEE ONE. It fires on a grounded →
            // airborne TRANSITION (`prevOnGround && !onGround`), so a leg handed a body that is
            // already in the air has no grounded previous tick and never arms any of it: `airborne`
            // stayed false for the whole leg, and every reading taken off that flag described a body
            // that was standing.
            //
            // Measured — corridor wp5, 2026-08-21 rehearsal, a leg the previous leg handed over
            // mid-fall: 「首次入岩浆 t=18 64,29,88 —— 走进去的」about a body that had dropped
            // thirteen blocks to get there, and 「离地 0 次」about a leg that spent all 269 of its
            // ticks off the ground. The first row is the expensive one, because it names a
            // MECHANISM: it says the walker steered into lava, which sends a reader after the cost
            // function instead of after the leg before it.
            if (startedAirborne && !inLava && !inWater) {
                airborne = true;
                launchAt = at;
                launchTick = t;
                launchY = at.getY();
                launchWhy = "（起步时就不在地上，这一段没看见起跳 —— 上一段交过来时身体已经在坠）";
                launchGround = "起步时就不在地上，没有起跳格可记";
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
        // Snapshot the run-up HERE and not when the fall lands. The first version kept it until
        // landing, by which time an eleven-block drop had rolled eight airborne ticks through the
        // ring and the evidence line read `t=72 空 撑0 | t=73 空 撑0 | …` — a perfect record of the
        // fall and none at all of the walk into it, which is the half being asked about. Held
        // pending rather than committed, because a launch is not yet a notable fall: an ordinary
        // one-block step down also leaves the ground, and the run-up wanted is the one belonging to
        // the drop that gets written down.
        pendingRunUp = String.join(" | ", runUp);
        launchPlan = planCell(level, fp, at);
        launchGround = groundDump(level, fp);
        openLeap(fp);
        if (prevSupportSolid > 0 && stillSolid == 0) {
            launchWhy = "上一 tick 撑着它的 " + prevSupportSolid + " 格没了（" + prevSupportNames
                    + " → " + names(level, prevSupport) + "）";
        } else if (prevSupportSolid > 0 && movedOff) {
            launchWhy = "走出了支撑格（上一 tick 踩着 " + prevSupportNames
                    + "，这一 tick 脚下是 " + names(level, support) + "）";
        } else if (prevSupportSolid == 0) {
            launchWhy = "上一 tick 就已经没有支撑格了（" + prevSupportNames
                    + "），onGround 却还报 true —— 支撑在别处，或者 onGround 迟了一拍";
        } else {
            launchWhy = "支撑格还在也没走开（" + prevSupportNames + "）";
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
        String want = node == null ? "无节点" : node.toShortString();
        String gap = node == null ? "没量到（起跳那一 tick 手上没有节点）"
                : String.format(Locale.ROOT, "剩 %.2f 格水平、dy=%d",
                        Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ()),
                        node.getY() - launchY);
        pendingLeap = "t=" + t + " " + move + " 从 " + launchAt.toShortString()
                + " 起跳瞄 " + want + "（" + gap + "）";
    }

    /** Close the pending leap with where it actually put the body. */
    private void closeLeap(BlockPos at, boolean inLava) {
        if (pendingLeap == null) return;
        leaps++;
        if (leapLines.size() < MAX_LEAPS) {
            String miss = pendingLeapTarget == null ? "没有目标可比"
                    : String.format(Locale.ROOT, "落在 %s，离目标 %.2f 格水平、dy=%d",
                            at.toShortString(),
                            Math.hypot(at.getX() - pendingLeapTarget.getX(),
                                    at.getZ() - pendingLeapTarget.getZ()),
                            at.getY() - pendingLeapTarget.getY());
            leapLines.add("#" + leaps + " " + pendingLeap + " → " + miss
                    + (inLava ? "，落进岩浆" : ""));
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
        String ended = inLava ? "落进岩浆 " + at.toShortString()
                : onGround ? "落到 " + at.toShortString() + "（脚下 " + names(level, support) + "）"
                : "落进水里 " + at.toShortString();
        // The two speeds are printed together on purpose. fastestDrop is measured off the body's own
        // y; fallDistance is the field vanilla keeps — and on a FakePlayer it stays 0 forever,
        // because ServerPlayer.checkFallDamage (the one Entity.move calls) is an EMPTY override and
        // the accumulating version, doCheckFallDamage, runs only off a movement packet this body
        // never sends. Printing both is what makes that provable from an evidence row instead of
        // arguable: 1.14 blocks in one tick against a fallDistance of 0.0.
        falls.add("#" + fallCount + " t=" + launchTick + " 从 " + launchAt.toShortString()
                + " " + launchWhy + " → " + ended
                + "，坠 " + drop + " 格（最快一 tick 掉 "
                + String.format(Locale.ROOT, "%.2f", fastestDrop)
                + " 格；同期 fallDistance 最大 "
                + String.format(Locale.ROOT, "%.1f", deepestFallField)
                + " —— 这个字段对 FakePlayer 恒为 0，不是「没掉」）"
                + "，已走 " + walkedAt(launchAt) + "/" + legLength + " 格"
                + "，" + launchPlan);
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
        // most. That line names the discarded plan's last node — but only when a discard happens,
        // and「the plans themselves route backwards」is precisely the branch where no discard does.
        // Measured per grounded tick out of the goal this leg already holds, so it needs no plan
        // end node and no plumbing: a leg that walks 61 edges to a net −8 either shows a positive
        // worst here (bad plans) or does not (good plans, taken away).
        double nodeAway = Math.hypot(node.getX() + 0.5 - goalX, node.getZ() + 0.5 - goalZ);
        double bodyAway = Math.hypot(fp.getX() - goalX, fp.getZ() - goalZ);
        if (nodeAway - bodyAway > backwards) {
            backwards = nodeAway - bodyAway;
            // Server BotState on purpose: pathMove is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
            backwardsAt = "t=" + t + " 身体 " + at.toShortString() + "（离目标 "
                    + Math.round(bodyAway) + "）计划下一格 " + node.toShortString() + "["
                    + rig.body().botState().mc_goto.pathMove + "]（离目标 " + Math.round(nodeAway) + "）";
        }
        double gap = Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ());
        if (gap <= worstOffPlan) return;
        worstOffPlan = gap;
        // Server BotState on purpose: pathMove is excluded from ProcessSlot.snapshot() — see JourneyRig.slot.
        worstOffPlanAt = "t=" + t + " 身体 " + at.toShortString() + " 计划下一格 "
                + node.toShortString() + "[" + rig.body().botState().mc_goto.pathMove + "]"
                + "，其脚下 " + blockName(level, node.below());
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
        runUp.add("t=" + t + " " + at.toShortString() + (onGround ? " 地" : " 空") + " 撑" + solid
                + " 接触" + String.format(Locale.ROOT, "%.3f", contact));
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
                + (inLava ? "(岩浆)" : onGround ? "" : "(空中)"));
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
        sb.append("走了 ").append(walkedAt(at)).append('/').append(legLength).append(" 格（还差 ")
          .append(awayFromGoal(at)).append("）；y ").append(from.getY()).append('→').append(at.getY());
        if (highestY != Integer.MIN_VALUE) {
            sb.append("（途中最高 ").append(highestY).append("，最低 ").append(lowestY).append("）");
        }
        sb.append("；共 ").append(t).append(" tick；离地 ").append(fallCount).append(" 次");
        if (fallCount > 0) sb.append("，最深 ").append(deepestDrop).append(" 格");
        if (startedAirborne) sb.append("；起步时就不在地上");
        if (airborne) {
            sb.append("；结束时还在空中（从 ").append(launchAt.toShortString())
              .append(" y=").append(launchY).append(" 起，").append(launchWhy).append("）");
        }
        // "没量到" and "从未有过计划" are different worlds and the counter beside it separates them:
        // this is only sampled on a GROUNDED tick that had a node, so a leg spent entirely airborne
        // and a leg the walker never steered both leave it unset.
        sb.append("；离计划最远 ").append(worstOffPlan < 0 ? "没量到（没有一个 tick 是既在地上又有计划的）"
                : String.format(Locale.ROOT, "%.2f 格（%s）", worstOffPlan, worstOffPlanAt));
        sb.append("；").append(ticksWithNoPlan).append("/").append(t).append(" tick 身上没有计划");
        sb.append("；").append(contactLine());
        sb.append("；走过的边 ").append(moveTally.isEmpty() ? "没有（这一段没执行过任何计划边）" : moveTally);
        // WHO TOOK THE BODY DOWN — the plan, or gravity. The y-range clause above says how far down
        // it got; this says how much of that A* deliberately asked for. Printed unconditionally,
        // including on a leg that never descended, because「计划下潜 0 格」on a body that ended 36
        // blocks lower is itself the finding, and a row that only appears when it is interesting
        // cannot be compared across two arms. See plannedDown.
        sb.append("；计划里的垂直账 下潜 ").append(plannedDown).append(" 格、上爬 ").append(plannedUp)
                .append(" 格（净 ").append(plannedUp - plannedDown).append("）")
                .append(descentByMove.isEmpty() ? "，没有一条计划边是往下的 —— 身体下去了多少全是掉的"
                        : "，下潜来自 " + descentByMove);
        // Unconditional for the same reason the vertical bill is: 「parkour 起跳 0 次」on a leg that
        // ended in a lava pit rules out a whole family in one word. See openLeap.
        sb.append("；parkour 起跳 ").append(leaps).append(" 次")
                .append(leaps > leapLines.size() ? "（只逐条记了前 " + leapLines.size() + " 次）" : "")
                .append(pendingLeap == null ? "" : "，另有一次起跳到收工都还没落地");
        // A leg that walked 61 edges to a net −8 has either been given bad plans or has had good
        // ones taken away from it. This is the number that says which, and it is the leg's own
        // delta rather than the JVM total — see repathsAtStart.
        sb.append("；计划最往回指 ").append(backwards < 0 ? "没量到"
                : String.format(Locale.ROOT, "%.2f 格（%s）", backwards, backwardsAt));
        int advances = Walker.stepAdvancesLogged - advancesAtStart;
        sb.append("；步进记了 ").append(advances).append(" 条")
          .append(advances >= Walker.stepAdvanceBudget()
                  ? "（记满了，后面的步进没进日志 —— 这一段的步进日志不完整）" : "（这一段的步进日志是完整的）");
        int repaths = Walker.guardForcedRepaths - repathsAtStart;
        int kept = Walker.guardKeptPlans - keptAtStart;
        // BOTH, ALWAYS, and never the discard alone — see keptAtStart. "丢掉 0 次" used to mean the
        // pin never reached its threshold; it now also covers a leg pinned to the threshold every
        // thirty ticks that kept its plan each time, and those are opposite terrain reports.
        sb.append("；守卫钉满 ").append(repaths + kept).append(" 次：丢掉计划 ").append(repaths)
          .append(" 次、判定沿岸走而保留 ").append(kept).append(" 次")
          .append(repaths + kept == 0 ? "（这一段从没被钉满过，走的一直是同一批计划）"
                  : repaths == 0 ? "（计划一次都没被丢掉 —— 每次钉满时它都在前进）"
                  : "，最后一次丢弃 " + Walker.lastGuardRepath);
        sb.append("；收工那一刻：").append(finishedAt == null
                ? "没有 —— 这一段是跑满 tick 被叫停的，不是进程自己结束的" : finishedAt);
        if (lava != null) sb.append("；首次入岩浆 ").append(lava);
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
        sb.append("走 ").append(walkedAt(at)).append('/').append(legLength).append(" 格 ")
          .append(t).append("t（无计划 ").append(ticksWithNoPlan).append("t）");
        if (fallCount > 0) sb.append("，离地 ").append(fallCount).append(" 次最深 ")
                             .append(deepestDrop).append(" 格");
        if (lava != null) sb.append("，入岩浆");
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
        if (groundedTicks == 0) return "没有一个 tick 是在地上的";
        return "落脚最窄时接触 " + (leastContact == Double.MAX_VALUE ? "没量到"
                : String.format(Locale.ROOT, "%.4f/%.2f（%s）", leastContact, FULL_CONTACT, leastContactAt))
                + "；" + edgeTicks + "/" + groundedTicks + " 个着地 tick 接触面积不足 "
                + String.format(Locale.ROOT, "%.2f", EDGE_CONTACT) + "（等于只踩住一个角）";
    }

    /** How many notable falls this leg had — see {@link #falls()} for what each was. */
    public int fallCount() { return fallCount; }

    /** The physics under the body on the tick before each recorded fall — see {@link #groundDump}. */
    public List<String> grounds() { return List.copyOf(grounds); }

    /** Ticks the walker had nothing to steer at. See {@link #ticksWithNoPlan}. */
    public int noPlanTicks() { return ticksWithNoPlan; }

    /** Ticks this leg ran for. Read by the crossing so「no plan for 67 ticks」can be given the
     *  denominator that decides whether re-planning was the cost or a rounding error. */
    public int ticks() { return t; }

    /** How the body first entered lava on this leg, or null when it never did. */
    public String lavaLine() { return lava; }

    /** The fall lines, in order. Empty when the leg never left the ground by more than a step. */
    public List<String> falls() {
        if (fallCount <= falls.size()) return List.copyOf(falls);
        List<String> out = new ArrayList<>(falls);
        out.add("…另有 " + (fallCount - falls.size()) + " 次没有逐条记");
        return List.copyOf(out);
    }

    /** The leg's shape, sampled evenly. */
    public String trackLine() {
        return "每 " + trackStride + " tick 一采：" + String.join(" → ", track);
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
        // The control group — see openLeap. Written even on a leg where nothing went wrong, because
        //「起跳时还剩 0.64 格」only means something against the leaps that worked.
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
     *   <li><b>Was {@code onGround} telling the truth?</b> {@code 脚下 0.08 格} is vanilla's own
     *       question, asked again here. Agreeing with {@code onGround} closes the "it lags a tick"
     *       theory; disagreeing is the first evidence for it.</li>
     *   <li><b>Which row did the old reading look at?</b> Both rows are printed when they differ.</li>
     *   <li><b>How much ground was left?</b> The contact area, in m² out of 0.36.</li>
     *   <li><b>Was it a one-block ridge over lava?</b> The 5×5 map, where {@code !} is an open cell
     *       with lava under it — the cells a drift lands in.</li>
     * </ol>
     */
    private String groundDump(ServerLevel level, ServerPlayer fp) {
        if (prevBox == null) return "上一 tick 没有记录";
        AABB box = prevBox;
        int row = Mth.floor(box.minY - 1.0E-7);
        int oldRow = Mth.floor(box.minY - 0.02);
        Vec3 now = fp.getDeltaMovement();
        StringBuilder sb = new StringBuilder();
        sb.append("上一 tick：位置 ")
          .append(String.format(Locale.ROOT, "(%.3f, %.4f, %.3f)", box.minX + 0.3, box.minY, box.minZ + 0.3))
          .append(" 速度 ").append(String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)",
                  prevDelta.x, prevDelta.y, prevDelta.z))
          .append(" onGround=").append(prevOnGround)
          .append(" 潜行=").append(prevSneak)
          .append("；walker 那一 tick：")
          .append(prevDrive == null
                  ? "没走到 drive 收尾（提前返回的分支 —— 致命边刹车/冲刺/跳跃都在收尾里，这一 tick 一条都没跑）"
                  : "drive=" + prevDrive)
          .append(" 跳=").append(prevJumpTag == null ? "没有分支命令跳" : prevJumpTag);
        sb.append("；vanilla 自己那一问（脚下 ").append(GROUND_PROBE).append(" 格内有碰撞吗）=")
          .append(prevSwept ? "有" : "没有")
          .append(prevSwept == prevOnGround ? "（和 onGround 一致 —— 它没有迟一拍）"
                  : "（和 onGround 不一致 —— onGround 说的是别的 tick 的事）");
        sb.append("；实心接触面积 ").append(String.format(Locale.ROOT, "%.4f/%.2f", prevContact, FULL_CONTACT));
        sb.append("；支撑行 y=").append(row);
        if (row != oldRow) sb.append("（旧读数用的 floor(minY-0.02)=").append(oldRow).append("，问错了行）");
        sb.append(" ").append(contactCells(level, box, row));
        sb.append("；这一 tick 速度 y=").append(String.format(Locale.ROOT, "%.3f", now.y))
          .append(now.y > 0.15 ? "（是起跳，不是走出去的）" : "（不是起跳）");
        sb.append("；").append(edgeBrakeVerdict(level,
                new BlockPos(Mth.floor(box.minX + 0.3), Mth.floor(box.minY), Mth.floor(box.minZ + 0.3))));
        sb.append("；立足面 5×5（y=").append(row).append("，行 z=")
          .append(Mth.floor(box.minZ + 0.3) - 2).append("..").append(Mth.floor(box.minZ + 0.3) + 2)
          .append("，列 x=").append(Mth.floor(box.minX + 0.3) - 2).append("..")
          .append(Mth.floor(box.minX + 0.3) + 2).append("）：").append(neighbourhood(level, box, row))
          .append("（#=实心 ~=岩浆 !=空的且下面有岩浆 .=空的且下面没岩浆）");
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
     * <p>Verdicts: {@code 固} the neighbour is solid (the loop skips it), {@code 底} it has a floor
     * one down, {@code 落N} an N-block dry drop, {@code 岩N} lava N down — lethal at any depth.
     */
    private static String edgeBrakeVerdict(ServerLevel level, BlockPos foot) {
        StringBuilder sb = new StringBuilder("致命边刹车照 level 重算（foot=" + foot.toShortString()
                + "，自己这一格的地板 " + blockName(level, foot.below())
                + (level.getBlockState(foot.below()).blocksMotion() ? "（撑得住）" : "（撑不住，而这一格守卫从来不问）")
                + "）：");
        boolean lethal = false;
        for (int[] o : EDGE_NEIGHBOURS) {
            BlockPos n = foot.offset(o[0], 0, o[1]);
            String verdict;
            if (level.getBlockState(n).blocksMotion()) {
                verdict = "固";
            } else if (isLava(level, n.below())) {
                verdict = "岩0";
            } else if (level.getBlockState(n.below()).blocksMotion()) {
                verdict = "底";
            } else {
                int fall = 1;
                BlockPos pr = n.below(2);
                String hit = null;
                while (fall <= SURVIVABLE_FALL + 2 && !level.getBlockState(pr).blocksMotion()) {
                    if (isLava(level, pr)) { hit = "岩" + fall; break; }
                    fall++;
                    pr = pr.below();
                }
                verdict = hit != null ? hit : fall > SURVIVABLE_FALL ? "落>" + SURVIVABLE_FALL : "落" + fall;
            }
            if (verdict.startsWith("岩") || verdict.startsWith("落>")) lethal = true;
            sb.append(' ').append(o[0]).append('/').append(o[1]).append('=').append(verdict);
        }
        return sb.append(lethal ? " → 该响" : " → 不该响").toString();
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
        if (cells.isEmpty()) return "[无]";
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
        return "计划第 " + slot.pathStep + "/" + slot.pathLen + " 步";
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
            return "计划里没有「下一格」（" + planAt() + "，move=" + slot.pathMove
                    + "）—— 这一刻没有任何节点在牵着身体走";
        }
        BlockPos under = node.below();
        boolean holds = level.getBlockState(under).blocksMotion();
        double gap = Math.hypot(node.getX() + 0.5 - fp.getX(), node.getZ() + 0.5 - fp.getZ());
        return "计划下一格 " + node.toShortString() + "[" + slot.pathMove + "]（" + planAt()
                + "）：那一格=" + blockName(level, node) + "，其脚下 " + under.toShortString() + "="
                + blockName(level, under) + (holds ? "（撑得住）" : "（撑不住）")
                + "，距身体 " + String.format(Locale.ROOT, "%.2f", gap) + " 格水平、dy="
                + (node.getY() - at.getY());
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
