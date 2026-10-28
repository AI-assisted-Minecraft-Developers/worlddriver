package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Locale;

import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.worlddriver.bot.stagewright.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.Targets;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * <b>A hop judged while the bot was one tick above the floor.</b>
 *
 * <h2>The run this is a copy of</h2>
 *
 * Rung 14's Nether crossing is healthy — 11.4 tick/block, 1% of its ticks without a plan, 2 968 of
 * a 21 600-tick hop budget spent, 402 blocks to walk. It stopped 141 blocks short, and this is the
 * row that stopped it (rendered in English):
 *
 * <pre>
 * fortress.crossing = 6 hops, 141 blocks still to go … stopped after hop 6: the bot is still
 *                     falling (179, 43, 198, fall speed -0.38 blocks/tick, 0 blocks down to solid)
 * fortress.around.6 = below=netherrack … onGround=false fallSpeed=-0.38 dropToSolid=0
 * </pre>
 *
 * <p><b>Zero blocks down to solid.</b> The bot was not in a chasm; it was a hair above netherrack,
 * mid-landing, and would have been standing on it on the next tick. {@code hazardBlockingARetry} is
 * right that a falling bot is not somewhere a walk order can act on; it is wrong to take that
 * reading from a bot it never let finish falling. Eighteen of twenty-four hops and 16 200 hop ticks
 * went unspent over one tick of patience.
 *
 * <h2>What put the bot in the air, and why the navigator is not the defect</h2>
 *
 * The same hop's own physics dump (rendered in English):
 *
 * <pre>
 * fortress.ground.6.0 = previous tick: position (166.653, 57.0000, 177.409)
 *   velocity (-0.007, -0.078, 0.111) onGround=true … vanilla's own check (any collision within
 *   0.0784 blocks below the feet) = none (disagrees with onGround)
 *   solid contact area 0.0000/0.36 … lethal edge brake recomputed against the level … → should
 *   not fire
 * </pre>
 *
 * <p>{@code onGround} was indeed a tick stale — vanilla's own sweep disagreed with it. But nothing
 * in the navigator steers on that flag: {@link Safety#footingGuard} and {@link Safety#strideFloorGuard}
 * both open on {@code NavigatorGeometry.soleOnSolid}, which read {@code 0.0000} on the same tick, and
 * both were silent for the reason the row spells out — <b>the drops were 4 and 8 blocks</b>, against
 * a lethal line of {@code survivableFall(20) = 22}. Teaching them to refuse those strides is not a
 * fix, it is the failure {@code wd.serverWalksOffASurvivableLedge} was committed to catch: a guard
 * that pins at every lip turns a Nether crossing, which is nothing but lips, into a wall.
 *
 * <p>So this pair asks the navigator for nothing at all. It walks a bot off a survivable lip with the
 * guards at their live settings, records that they stayed out of the way (that reading is the
 * measurement, not an assertion — the shore pair owns that), and then asks the CROSSING's verdict
 * the two questions it gets wrong and right.
 *
 * <h2>Two arms, one variable: how far it is to the floor</h2>
 *
 * <ul>
 *   <li>{@code wd.crossingWaitsOutASurvivableDrop} — a four-block step-down, the fall-#6 geometry.
 *       The bot lands well inside {@link JourneyNetherRungs#LANDING_TICKS} and the verdict must
 *       come back clean, so the crossing spends its remaining hops.</li>
 *   <li>{@code wd.crossingStillStopsForALongFall} — the same bay from thirty-nine blocks up. The
 *       allowance runs out with the bot still in the air and the verdict must STILL stop the
 *       crossing. Without this arm, "the verdict now clears" would be satisfied by deleting the
 *       branch, and a rung that walks its next plan from a bot in free fall is the retry that
 *       changes nothing this rung already has a name for.</li>
 * </ul>
 *
 * <h2>Each arm carries its own control</h2>
 *
 * The first arm takes the verdict TWICE over one fall: once at the instant the hop would have ended
 * (the pre-fix reading) and once after the allowance. The first must come back non-null — an arm
 * whose control did not reproduce the stop has not earned the right to report that the allowance
 * fixed it, and it fails as THE RIG rather than passing quietly. The second arm's control is the
 * first arm: same staging, same allowance, opposite answer.
 *
 * <h2>Why the allowance is modelled and not called</h2>
 *
 * {@link JourneyNetherRungs#LANDING_TICKS}, {@code stillFalling} and {@code hazardBlockingARetry}
 * are the production constant, the production predicate and the production verdict, called here
 * directly — a copy of any of the three would be a scene measuring itself. What a scene cannot host
 * is a {@link JourneyRig}: it is entered against the ladder's own ledger and drives a static bot,
 * so {@code settleToGround}'s tick pump is stepped here instead, with the impulse released first,
 * which is what {@link JourneyRig#hold} does and the reason the crossing waits under it rather than under
 * the walk it just ended.
 *
 * <p>What that leaves uncovered is one line: that {@code oneHop} wraps its continuation in the
 * allowance at all. Said out loud because it is the half an arena cannot reach, not because it is
 * unimportant — it is the whole delivery.
 *
 * <h2>Arena footprint</h2>
 *
 * {@code dx ∈ [-4, 4]}, {@code dz ∈ [-3, 16]}, {@code dy ∈ [0, 43]} around the origin — inside the
 * default one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}. Every cell
 * in that box is written by {@link #stage}: an unstaged column with a floor in it would decide the
 * arms' only variable by whatever the dogfood world happens to have at y≈200.
 */
public final class JourneyCrossingScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.guardRepathSeparatesARimWalkFromALivelock", 600,
                        JourneyCrossingScenes::repathSeparatesARimWalkFromALivelock));
    }

    /** dy of the shelf's top block. The bot's foot cell is one above it. */
    private static final int DECK = 6;

    /** Cells of shelf along +z, from {@code dz = -2}. The lip is the last of them. */
    private static final int SHELF_CELLS = 9;

    /** dy of the bay floor's top block. Four rows under the deck, so the step down from foot cell to
     *  foot cell is FOUR — the drop {@code fortress.fell.6.0} measured, and far under the
     *  {@code survivableFall(20) = 22} line, which is what keeps both navigator guards out of this
     *  arena by their own rules rather than by a switch. */
    private static final int BAY_BED = DECK - 4;

    /** dy the long-fall arm starts its bot at. Thirty-nine rows over the bay floor's standing cell,
     *  so the bot is still in the air when the allowance expires: 26 ticks of gravity cover 23.4
     *  blocks and the run-up to {@code stillFalling} costs four more. */
    private static final int DEEP_START = 42;

    /** Idle ticks before a drive, so a bot that vanilla itself cannot hold up says so before the
     *  measurement rather than during it. */
    private static final int SETTLE_TICKS = 20;

    /** Physics ticks the walk out to the lip gets. The shelf is nine cells and a walk covers about
     *  one every five ticks, so this is roughly triple what a healthy walk-off needs. */
    private static final int WALK_TICKS = 160;

    // ── the plan the guard throws away ───────────────────────────────────────────────────────

    /**
     * <b>A sustained pin discards the plan, and until now it did so in total silence.</b>
     *
     * <h2>The run this is a copy of</h2>
     *
     * Rung 14's 2026-08-20 shuttle: four hops of 900 ticks each, 61–76 walk edges apiece, net −8 to
     * −28 blocks, all four inside one 27×31 box. The terrain, read out of that run's own region
     * files, is a lava sea — 18 458 lava cells against 2 543 netherrack in the walk band — and the
     * only ground in it beyond one netherrack shelf is a <b>127-block dirt causeway the bot built
     * itself</b>. The stride guard fired 491 times in that crossing across 184 distinct cells and
     * plugged 142 of them; only 5 cells ever reached the 12-fire plug dwell.
     *
     * <p>With {@link Safety#GUARD_PIN_HOLD} = 8, a fire every eight ticks keeps
     * {@code guardPinStreak} alive, and at ≥30 it throws the plan away. So two opposite diagnoses
     * fit every row that run produced — the bot was given plans that route backwards, or the bot
     * was given good plans that kept being discarded under it — and <b>nothing in the log or the
     * evidence map could choose</b>, because the discard logged nothing and was counted nowhere.
     *
     * <h2>What this arm asks</h2>
     *
     * Not whether the discard is right. That is a behaviour question this run cannot answer, and
     * {@code wd.serverKeepsWalkingAtALavaRim} already records that the escape hatch is reached in
     * one approach shape and not another. This asks only whether the new reading <b>separates the
     * two situations the counter was built for</b>:
     *
     * <ul>
     *   <li>a LIVELOCK — the bot held against one lip, pinning on the same cell, which is the
     *       567-pins-at-one-cell run the forced repath exists for;</li>
     *   <li>a RIM WALK — the bot travelling along a lava shore, pinning on a new cell every few
     *       ticks, which is what a Nether crossing is made of.</li>
     * </ul>
     *
     * <p>Both must force a repath — that is the CONTROL, and an arm where either does not has not
     * reproduced the situation and fails as THE RIG rather than reporting that the reading told
     * them apart. What must differ is the distinct-cell count in the line.
     *
     * <p><b>Red before the line existed:</b> the discard produced no line at all, so neither arm
     * had anything to read.
     */
    private static void repathSeparatesARimWalkFromALivelock(SceneContext ctx) {
        Harness.pin(ctx);
        Harness.set("allowPlace", false);      // paving the trench is another answer; the pin is the subject
        Harness.set("allowBreak", false);
        Harness.set("navigatorDebug", false);
        Harness.set("lethalEdgeBrake", false); // isolate the STRIDE guard, as the shore/rim pairs do
        ctx.cleanup(() -> clearTrench(ctx));

        stageTrench(ctx);
        ctx.record("rig", "a lava trench running the full length along z (surface dy="
                + (TRENCH_DECK - 4) + ", " + TRENCH_ROWS + " rows of lava); to the east, dx≥"
                + TRENCH_EDGE + " is a stone deck " + TRENCH_CELLS + " blocks long. Both arms stand on"
                + " the same shore, and the only variable is whether the bot is allowed to move forward");

        Pin walk = drivePin(ctx, "rimWalk", true);
        Pin lock = drivePin(ctx, "livelock", false);
        ctx.record("rimWalk", walk.line());
        ctx.record("livelock", lock.line());

        // THE CONTROL: both situations must actually reach the discard, or the arm has measured
        // nothing and must not report that the reading separated them.
        if (walk.repaths() < 1)
            ctx.fail("THE RIG, not the subject: the rim-walk arm never triggered a forced repath ("
                    + walk.line() + "); with no event there is no reading to compare");
        if (lock.repaths() < 1)
            ctx.fail("THE RIG, not the subject: the livelock arm never triggered a forced repath ("
                    + lock.line() + "); \"the rim walk reported many cells\" then cannot tell whether"
                    + " the reading had an effect or this arm never ran far enough");

        // THE PARSER'S OWN CONTRACT, checked before the two readings are compared. cellsIn reads the
        // navigator's WORDING, and that wording has silently changed twice under it (see cellsIn). A −1
        // is not a measurement of the bot; it is this scene failing to read the line. Left to flow
        // into the checks below it would arrive as "covered -1 distinct cells", which reads like a
        // subject that misbehaved and sent the 2026-08-22 gate looking at the navigator. It is the rig.
        if (walk.cells() < 0 || lock.cells() < 0)
            ctx.fail("THE RIG, not the subject: cannot parse the navigator's forced-repath line; cellsIn's"
                    + " pattern CELLS_IN_LINE (" + CELLS_IN_LINE.pattern() + ") does not match the"
                    + " line's current wording. The rim walk parsed as " + walk.cells()
                    + " and the livelock as " + lock.cells() + ". The raw lines are in the evidence"
                    + " rows rimWalk.lastLine / livelock.lastLine. Update the pattern to the line's"
                    + " wording before judging whether the two arms separate; until then this scene"
                    + " has measured nothing");

        ctx.check(lock.cells()).as("A the livelock must report exactly one cell, which is the situation"
                + " this counter was built for: " + lock.line()).isEqualTo(1);
        ctx.check(walk.cells() > lock.cells()).as("B the rim walk must report more cells than that;"
                + " otherwise the line still prints \"walked ninety blocks\" and \"stuck on one cell\""
                + " as the same sentence: rim walk " + walk.cells()
                + " cells, livelock " + lock.cells() + " cells").isTrue();
    }

    /** What one drive against the trench produced. */
    private record Pin(int ticks, int repaths, int cells, int pinnedTicks, double travelled) {
        String line() {
            return String.format(Locale.ROOT,
                    "%d ticks, pinned %d ticks, %d forced repaths, the last covering %d distinct cells,"
                            + " travelled %.2f blocks along the shore",
                    ticks, pinnedTicks, repaths, cells, travelled);
        }
    }

    /**
     * Drive the trench once and report the discard the pin forced.
     *
     * <p>{@code travelling} is the arm's only variable. Both bots stand on the same shore and
     * both are steered at the lava; the travelling one is also pushed along +z, so its stride cell
     * sweeps, while the other is held against one lip and pins on the same cell for as long as it
     * takes. Sneak is not re-imposed — it is the channel the pin uses and the thing being measured.
     */
    private static Pin drivePin(SceneContext ctx, String arm, boolean travelling) {
        stageTrench(ctx);
        ServerLevel level = ctx.level();
        int standY = ctx.rel(0, TRENCH_DECK + 1, 0).getY();
        SceneBot av = SceneBot.spawn(ctx, level, ctx.originX() + TRENCH_EDGE + 0.5, standY, ctx.originZ() + 1.5);
        ServerPlayer fp = av.player();
        fp.getInventory().clearContent();
        // Face into the trench (−x) for the livelock, and RIM_LEAN for the rim walk so the bot
        // also travels along +z — the heading the ladder's own rim pins were all measured on.
        Runnable face = () -> {
            fp.setYRot(travelling ? RIM_LEAN : 90f);
            fp.yHeadRot = fp.getYRot();
            fp.yBodyRot = fp.getYRot();
        };
        face.run();
        for (int i = 0; i < SETTLE_TICKS; i++) step(av);

        av.goTo(Targets.block(ctx.rel(TRENCH_EDGE - 6, TRENCH_DECK + 1, TRENCH_CELLS - 2)));
        long before = av.navigator().counter("guardForcedRepaths");
        double z0 = fp.getZ();
        int pinned = 0, t = 0;
        long repaths = 0;
        for (; t < PIN_TICKS; t++) {
            if (straightOn(av, face)) pinned++;
            av.step();
            repaths = av.navigator().counter("guardForcedRepaths") - before;
            if (repaths >= 2) break;   // two is enough to read the line
        }
        String line = av.navigator().traces().getOrDefault("lastGuardRepath", "");
        int cells = cellsIn(line);
        ctx.record(arm + ".lastLine", repaths == 0 ? "(no forced repath)" : line);
        return new Pin(t, (int) repaths, cells, pinned, Math.abs(fp.getZ() - z0));
    }

    /**
     * The cell-coverage count out of the navigator's forced-repath line, or −1 when the line cannot be
     * read at all.
     *
     * <p><b>Parsed rather than recomputed, on purpose:</b> this arm's whole claim is that the LINE
     * separates a rim walk from a livelock, so reading the navigator's {@code guardStreakCells} directly
     * would assert something no reader ever sees and leave the sentence itself untested.
     *
     * <p><b>⚠️ That couples this scene to the WORDING of a log line, and the coupling has already
     * rotted twice unnoticed.</b> The navigator has reworded its distinct-cell clause twice since the
     * pattern was first written, and each time this matcher went on returning −1. The 2026-08-22
     * double-loader gate is what caught it, and only because −1 reached an assertion: <b>both
     * loaders</b> reported "the last covering -1 distinct cells" while the navigator's own numbers were
     * exactly right (rim 2, livelock 1). The bot, the scene's premise and the navigator's arithmetic
     * were all correct and the parser was the only broken part. A silent −1 that goes on to be
     * compared as if it were a cell count is the entire
     * defect, which is why this no longer gets to be quiet: see the caller, which fails the RIG
     * rather than the subject the moment this returns −1.
     *
     * <p>Contrast {@code WorldDriverCoverageScenes}'s {@code churnEsc=(\d+)}, which has never
     * rotted: it parses a {@code key=value} probe rather than a sentence. Prose gets reworded, a key
     * does not — and this one has to stay prose precisely because the prose IS the subject.
     */
    private static int cellsIn(String line) {
        var m = CELLS_IN_LINE.matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** The navigator's CURRENT wording of the pinned-cell count clause that {@code Navigator} logs,
     *  deliberately not a union of every past phrasing: accepting the dead ones would buy nothing and
     *  would hide the next rewording. The caller turns a miss into a loud rig failure that names
     *  this constant. */
    private static final java.util.regex.Pattern CELLS_IN_LINE =
            java.util.regex.Pattern.compile("pinned in (\\d+) cells");

    /** dy of the shore deck's top block. */
    private static final int TRENCH_DECK = 20;
    /** Rows of lava in the trench. Four, so a bot that goes in is in it. */
    private static final int TRENCH_ROWS = 4;
    /** Westmost deck cell: dx below this is open trench, so the rim runs the whole arena at one x. */
    private static final int TRENCH_EDGE = 1;
    /** Cells of shore along +z — long enough that a travelling pin sweeps many stride cells. */
    private static final int TRENCH_CELLS = 26;
    /** Physics ticks one drive gets. Past 30 pinned ticks with room to spare. */
    private static final int PIN_TICKS = 220;

    /** Heading for the travelling arm, in degrees; 0 is +z and 90 is −x (into the trench). 30°
     *  walks the shore while leaning at it — the same shape as {@code wd.serverKeepsWalkingAtALavaRim}'s
     *  own 25°, and the shape every one of rung 12's 83 rim pins was measured on. The first cut used
     *  150°, which is mostly −z: the bot walked backwards off the arena and pinned on one cell,
     *  and the arm reported the livelock's own answer for the rim. */
    private static final float RIM_LEAN = 30f;

    private static void clearTrench(SceneContext ctx) {
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -3; dz <= TRENCH_CELLS + 2; dz++)
                for (int dy = TRENCH_DECK - 10; dy <= TRENCH_DECK + 4; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** One shore, one lava trench beside it. The rim under both arms is identical. */
    private static void stageTrench(SceneContext ctx) {
        clearTrench(ctx);
        for (int dx = TRENCH_EDGE; dx <= 8; dx++)                       // the deck
            for (int dz = -3; dz <= TRENCH_CELLS + 2; dz++)
                for (int dy = TRENCH_DECK - 10; dy <= TRENCH_DECK; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dx = -8; dx <= TRENCH_EDGE - 1; dx++)                  // the basin, rim and bed
            for (int dz = -3; dz <= TRENCH_CELLS + 2; dz++)
                for (int dy = TRENCH_DECK - 10; dy <= TRENCH_DECK - 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (int dx = -7; dx <= TRENCH_EDGE - 1; dx++)                  // …and the lava in it
            for (int dz = -2; dz <= TRENCH_CELLS + 1; dz++)
                for (int dy = TRENCH_DECK - 7; dy <= TRENCH_DECK - 4; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.LAVA);
    }

    // ── the row a wedged hop is read from ────────────────────────────────────────────────────

    /**
     * dy of the shaft's floor — eighteen rows under the foot cell, and the number is squeezed
     * between three constants rather than picked.
     *
     * <p>It has to be deeper than {@code surroundings}' own 16-cell probe, or neither bot reads a
     * drop to solid of {@code >16} and the control has nothing to be about. It has to be SHALLOWER than
     * what the navigator's two guards refuse, or the walking half never happens: measured on the first
     * cut of this arena, over a bottomless shaft {@link Safety#strideFloorGuard} fired, killed the
     * horizontal momentum and sneak-pinned the bot on a 0.0001-wide sliver of the lip for all 160
     * ticks — the guard doing exactly its job, and an arena that mistook it for a rig failure.
     * Eighteen clears both: the stride guard's fall scan reaches 23 at full health and finds this
     * floor, and {@code survivableFall(20) = 22} keeps {@link Safety#footingGuard} out too.
     */
    private static final int SHAFT_FLOOR = DECK + 1 - 18 - 1;

    /** dy the shaft is cleared up from — one row over its floor. */
    private static final int SHAFT_CLEAR = SHAFT_FLOOR + 1;

    // ── the rig ──────────────────────────────────────────────────────────────────────────────

    /** What one walk out to the lip produced. */
    private record Walk(int ticks, double walked, boolean leftTheGround, int pinnedTicks,
                        double soleAtLaunch, boolean onGroundAtLaunch, boolean sweptAtLaunch) {
        String line() {
            return String.format(Locale.ROOT,
                    "%d ticks, walked %.2f blocks along the deck, left the ground=%s, pinned by a"
                            + " guard for %d ticks; on the tick before leaving the ground: solid"
                            + " sole area %.4f/0.36, onGround=%s, vanilla's own check (any"
                            + " collision within 0.0784 blocks below the feet)=%s",
                    ticks, walked, leftTheGround ? "yes" : "no", pinnedTicks,
                    soleAtLaunch, onGroundAtLaunch, sweptAtLaunch ? "yes" : "no");
        }
    }

    /** What spending the allowance cost, and the last verdict taken while the bot was still in the
     *  air: the live row's own reading (0 blocks down to solid) rather than the first one, which is
     *  taken three blocks up and understates how close the crossing was to a landing. */
    private record Allowance(int landedAt, String lastMidAir) {}

    /** One idle physics tick with everything released, exactly what {@link JourneyRig#hold} does. */
    private static void step(SceneBot av) {
        av.press(av.keys().move(0, 0).jump(false).attack(false));
        av.step();
    }

    /**
     * The brain half of a rig tick: the brain runs (its guards included), then {@code aim} and the forward
     * impulse are re-imposed and the jump let go. Sneak is left alone. Whether the brain held sneak; the caller
     * steps the body.
     */
    private static boolean straightOn(SceneBot av, Runnable aim) {
        av.think();
        aim.run();
        return av.press(av.keys().move(0, 1).jump(false)).shift();
    }

    /** A bot at {@code dy}, optionally left to stand for {@link #SETTLE_TICKS} first. The settle is
     *  for an arm that starts ON something — it proves vanilla itself holds the stance up before the
     *  measurement rather than during it. An arm that starts in the air must NOT have it: those
     *  ticks are its own fall. */
    private static SceneBot spawn(SceneContext ctx, double z, int dy, boolean settle) {
        SceneBot av = SceneBot.spawn(ctx, ctx.level(), ctx.originX() + 0.5, ctx.rel(0, dy, 0).getY(), z);
        ServerPlayer fp = av.player();
        fp.getInventory().clearContent();
        aim(fp);
        if (!settle) return av;
        for (int i = 0; i < SETTLE_TICKS; i++) step(av);
        if (fp.getY() < ctx.rel(0, dy, 0).getY() - 0.5)
            ctx.fail("THE RIG, not the subject: vanilla physics alone did not hold this stance ("
                    + where(ctx, fp) + " after " + SETTLE_TICKS + " idle ticks)");
        return av;
    }

    /** Face +z, head and body with it — see the shore pair: a heading the navigator may slew turns a
     *  straight walk into a measurement of A*. */
    private static void aim(ServerPlayer fp) {
        fp.setYRot(0f);
        fp.yHeadRot = 0f;
        fp.yBodyRot = 0f;
    }

    private static String where(SceneContext ctx, ServerPlayer fp) {
        return String.format(Locale.ROOT,
                "bot=(%.2f,%.2f,%.2f) onGround=%s fallSpeed=%.3f below=%s dropToSolid=%s",
                fp.getX(), fp.getY(), fp.getZ(), fp.onGround(), fp.getDeltaMovement().y,
                blockUnder(ctx, fp), dropBelow(ctx, fp.blockPosition()));
    }

    private static String blockUnder(SceneContext ctx, ServerPlayer fp) {
        BlockPos below = fp.blockPosition().below();
        return BuiltInRegistries.BLOCK.getKey(ctx.level().getBlockState(below).getBlock()).getPath();
    }

    /**
     * How far it is straight down to the first block that would hold the bot.
     *
     * <p><b>Not the same reading as the rung's own {@code dropBelow}.</b> {@link JourneyNetherRungs}'
     * probe stops at 16 and answers "void" below the build limit; this one looks 48 down and has no
     * void case, because the arena's bay is deeper than a
     * nether cave and no scene here can fall out of the world. So a number printed by one and a
     * number printed by the other are comparable only up to 16 — say which probe produced a row
     * before reading them side by side.
     */
    private static String dropBelow(SceneContext ctx, BlockPos at) {
        for (int d = 1; d <= 48; d++) {
            if (ctx.level().getBlockState(at.below(d)).blocksMotion()) return String.valueOf(d - 1);
        }
        return ">48";
    }

    // ── the terrain ──────────────────────────────────────────────────────────────────────────

}
