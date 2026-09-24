package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.process.TowerProcess;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerBody;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * A bot standing on a staircase it cut, asked to get itself unstuck.
 *
 * <p>The ladder's rung 12 digs a flight of steps down to the lava, walks it twenty times, and — when
 * a return walk cannot reach the mould — pillars its way back to the surface. That recovery towered
 * from <b>wherever the bot happened to be standing</b>, and on the run of 2026-08-19 the bot was
 * standing on step seven of its own staircase:
 *
 * <pre>
 * cast8.returnStuck.2          = 0, 58, 19 cannot reach the stairs … pillaring back up to ground level y=66
 * cast8.returnStuck2#9.column  = 0,19 (tower column; changed if the bot cannot walk back to it)
 * cast8.returnStuck2#9.climb.0 = 0,58,19 above=air onGround=true water=false
 * cast8.stairsBroken#2         = 2/11 steps broken: 0, 58, 19 blocked at 0, 58, 19=cobblestone,
 *                                                   1, 57, 19 blocked at 1, 58, 19=cobblestone
 * cast8.stairsMend.0#2         = … → could not clear (bot at -1, 59, 19)
 * </pre>
 *
 * The rung then failed because the bot could not walk back into the mould: it stopped at
 * {@code -1, 59, 19} with the stair bottom at {@code 2, 56, 19} — one step above the two steps its
 * own recovery had walled up.
 *
 * <h2>Why this is a scene and not a ladder re-run</h2>
 *
 * The ladder reaches rung 12 about half the time and costs forty minutes doing it, so this failure
 * has one reproduction per hour at best. Everything it needs, though, is local: a flight of cut
 * steps, a bot on one of them, and one tower order. That fits in an arena, runs in a fraction of a
 * second, and is executed by every one of the six gates.
 *
 * <h2>Four arms over one staged flight, two pairs, one variable each</h2>
 *
 * Every scene here stages <b>the same flight</b>. The first pair differs in exactly one thing —
 * whether there is a standable cell beside the step the bot is on — and asks what a climb REQUESTS:
 *
 * <ul>
 *   <li>{@code wd.unwedgeRefusesTheStaircaseColumn} — a stairwell cut through rock, which is the
 *       ladder's own geometry. Both neighbours of a step are wall, so there is nowhere to step
 *       aside to and the only correct answer is <b>do not tower here at all</b>.</li>
 *   <li>{@code wd.unwedgeTowersBesideTheStaircase} — the same flight with a ledge alongside one
 *       step. Now there IS somewhere to go, and the chooser must find it and tower there.</li>
 * </ul>
 *
 * The second pair asks the same two questions of a column nobody requested — the one a PINNED climb's
 * drift correction adopted after it could not walk back. That column reached the tower without ever
 * being put through the chooser at all, and it killed rung 12 on 2026-08-19; see
 * {@link JourneyShaft#towerColumnAfterDrift} for the readings.
 *
 * <ul>
 *   <li>{@code wd.unwedgePinnedDriftRefusesTheStaircase} — nowhere to step aside to, so an adopted
 *       flight column must still answer null even though the climb is pinned.</li>
 *   <li>{@code wd.unwedgePinnedDriftTowersBesideTheStaircase} — a ledge exists, so it must be found
 *       and used. Without this one, "the pin now refuses" would be satisfied by a chooser that had
 *       simply been switched off.</li>
 * </ul>
 *
 * <h2>Every arm carries its own control, because "the stairs are fine" is easy to say by accident</h2>
 *
 * The criterion these arms end on is {@code JourneyStairs.faults(level).isEmpty()} — and a scene whose
 * tower never reached the flight at all would satisfy it without measuring anything. So each arm
 * FIRST drives the pre-fix behaviour (a tower from the flight cell the old code path handed back) and
 * requires the flight to come back BROKEN. Only then is it restaged and the subject run. An arm that
 * cannot break the staircase on purpose has not earned the right to report that it kept it intact.
 *
 * <p>The drift pair's control is stronger than an imitation: it asks the SAME production chooser for
 * its answer with {@code driftMoved=false}, which is both the pre-fix behaviour and the behaviour
 * still kept for a pin the drift has not touched. One boolean separates the control from the subject.
 *
 * <h2>What these arms do NOT cover</h2>
 *
 * The walk from the bot's cell to the column the chooser picked. That is
 * {@code JourneyShaft.ascendByTowering}'s drift correction, it needs the pathfinder and a rig, and
 * it has its own rows ({@code climb.N.drift*}) on every ladder run. Here the bot is placed in the
 * chosen cell directly, so a green arm says "the column chosen is a column a tower may safely build
 * in" and nothing about how the bot gets there. The drift pair covers what that correction DECIDES,
 * not the walking it does to get there.
 *
 * <h2>Arena footprint</h2>
 *
 * {@code dx ∈ [-4, 10]}, {@code dz ∈ [-4, 4]}, {@code dy ∈ [13, 34]} around the origin — inside the
 * default one-chunk window ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
 *
 * <p>The span is not a guess: it is derived from {@link JourneyShaft#OFF_FLIGHT_REACH} and
 * {@link JourneyShaft#COLUMN_FOOTHOLD_DROP}, which together say exactly which cells the chooser may
 * read. The flight occupies {@code dx ∈ [0, 6]} at {@code dz = 0}, so the chooser inspects
 * {@code dx ∈ [-3, 9]}, {@code dz ∈ [-3, 3]}, down to seven rows below the lowest step
 * ({@code dy = BASE - 6}). <b>Every one of those columns has to be terrain this scene staged</b> —
 * one unstaged cell with a floor in it would make "there is nowhere to step aside to" depend on
 * whatever the dogfood world happens to have at y≈214, which is a coin flip that reads as flakiness.
 *
 * <p>Stated here rather than left to be derived because {@code scripts/check_scene_arena.py} scans
 * the {@code scene} package only: these scenes live beside the code they test, and nothing checks
 * this paragraph.
 */
public final class JourneyUnwedgeScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.unwedgeRefusesTheStaircaseColumn", 200,
                        JourneyUnwedgeScenes::refusesTheStaircaseColumn),
                Scene.of("wd.unwedgeTowersBesideTheStaircase", 200,
                        JourneyUnwedgeScenes::towersBesideTheStaircase),
                Scene.of("wd.unwedgePinnedDriftRefusesTheStaircase", 200,
                        JourneyUnwedgeScenes::pinnedDriftRefusesTheStaircase),
                Scene.of("wd.unwedgePinnedDriftTowersBesideTheStaircase", 200,
                        JourneyUnwedgeScenes::pinnedDriftTowersBesideTheStaircase),
                // The other reason a tower gives up: not the staircase under it, the water around it.
                Scene.of("wd.unwedgeStopsToweringWhenASourceFeedsTheWater", 3_000,
                        JourneyUnwedgeScenes::stopsToweringWhenASourceFeedsTheWater));
    }

    // ---------------------------------------------------------------- rig ----

    /** The floor of the staged hill, as a dy offset. Twenty above the grid's y=200, which leaves the
     *  fifteen cells of head room the tallest arm needs and stays far under the build limit. */
    private static final int BASE = 20;

    /** Steps below the top landing. Six is enough for a bot to stand in the MIDDLE of a flight —
     *  with cut steps above it and below it — which is the only position the defect appears in: a
     *  bot on the bottom step has nothing beneath to wall up. */
    private static final int STEPS = 6;

    /** Which step the bot stands on. Three, so the tower has three steps under it to fill and two
     *  above it to be blocked by. */
    private static final int STAND_ON = 3;

    /** The step whose column a pinned climb was aimed at — the column the pour's ray chose. Two,
     *  which puts it ON the flight, exactly like rung 12's {@code 2,19}: {@code climbFrom} records
     *  the collision and leaves a pinned column alone, so the arm starts from the state the ladder
     *  actually starts from. */
    private static final int AIMED_ON = 2;

    /** The step the drift correction ended on — a DIFFERENT flight column, which is the whole point.
     *  Four, two along from {@link #AIMED_ON}, so "the correction changed the column" is true by
     *  construction and the arm asserts it rather than assuming it. */
    private static final int DRIFTED_ON = 4;

    private static final String BLOCK_ID = "minecraft:cobblestone";
    private static final int STOCK = 64;

    /** Synchronous ticks one tower order may spend. The same order of magnitude the sibling
     *  {@code wd.serverTowers*} arms use, and these towers are shorter. */
    private static final int DRIVE_BUDGET = 400;

    /** The flight's top landing — {@code cells[0]}, the cell the bot was already standing in when
     *  the dig started, which is why it is registered and never cut. */
    private static BlockPos top(SceneContext ctx) { return ctx.rel(0, BASE + 7, 0); }

    /** Step {@code s} of the flight, {@code s ∈ [1, STEPS]} — one along, one down, the shape
     *  {@code JourneyStairwell.digStairsDown} cuts. */
    private static BlockPos step(SceneContext ctx, int s) { return ctx.rel(s, BASE + 7 - s, 0); }

    /**
     * Cut the flight, register it, and (optionally) open a ledge beside one of its steps.
     *
     * <p>Called again between the control and the subject, so "restage" is literally the same
     * arrangement rather than a repair of the damage the control did — a restage that patched only
     * the cells it expected to be broken would hide a control that broke something else.
     *
     * <p>Three cells per step: the step, its head room, and the cell two above it that a climb has to
     * jump through. That third one is not spare — {@link JourneyStairs#faults} audits it, and leaving
     * it out would stage a flight that is faulty before anything runs.
     *
     * @param ledgeBesideStep which step gets a standable cell beside it, or {@code 0} for none. It is
     *                        a step number rather than a boolean because the drift arms stage their
     *                        ledge beside the step the DRIFT ended on, which is not the step the
     *                        original arms stand on.
     */
    private static void stage(SceneContext ctx, int ledgeBesideStep) {
        clearBox(ctx);
        // The hill the flight is cut into — exactly the columns the chooser may inspect, solid from
        // below its search floor to above the flight's top. See the class note's footprint paragraph
        // for why this is derived from OFF_FLIGHT_REACH and COLUMN_FOOTHOLD_DROP rather than eyeballed.
        for (int dx = -3; dx <= 9; dx++)
            for (int dz = -3; dz <= 3; dz++)
                for (int dy = BASE - 7; dy <= BASE + 10; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);

        // The landing: feet and head, and nothing above — its jump clearance is the surface the bot
        // arrived over, and JourneyStairs.faults exempts cells[0] from that check for that reason.
        ctx.setBlock(0, BASE + 7, 0, Blocks.AIR);
        ctx.setBlock(0, BASE + 8, 0, Blocks.AIR);
        for (int s = 1; s <= STEPS; s++)
            for (int dy = 0; dy <= 2; dy++)
                ctx.setBlock(s, BASE + 7 - s + dy, 0, Blocks.AIR);

        if (ledgeBesideStep > 0) {
            // ONE standable cell beside the named step, level with it. Its floor stays stone; the
            // column above it is opened far enough that a tower started there is limited by its
            // order and not by the ceiling.
            for (int dy = 0; dy <= 9; dy++)
                ctx.setBlock(ledgeBesideStep, BASE + 7 - ledgeBesideStep + dy, 1, Blocks.AIR);
        }

        JourneyStairs.reset(ctx.level(), top(ctx));
        for (int s = 1; s <= STEPS; s++) JourneyStairs.cut(step(ctx, s));
    }

    /** Air out the working box. One cell wider than the hill on every side, so a re-stage removes
     *  the control's cobblestone wherever it went rather than only where it was expected to go. */
    private static void clearBox(SceneContext ctx) {
        for (int dx = -4; dx <= 10; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 7; dy <= BASE + 14; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The switches both arms share. Breaking is off: a bot that can dig has a second way up, and
     *  an arm about what a tower PLACES must not be able to answer with a staircase. */
    private static void config(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = true;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        // The flight is a static of the ladder's own code. A gate scene that left one behind would
        // hand a ladder run in the same process a staircase it never dug.
        ctx.cleanup(JourneyStairs::forget);
        // Registered BEFORE anything is built, so an arm that fails mid-drive still hands the shared
        // dogfood world back empty.
        ctx.cleanup(() -> clearBox(ctx));
    }

    /** A bot standing in {@code foot}, stocked and settled, with the cleanup that removes it. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot) {
        ServerWorldDriver driver = SceneBody.managed(ctx, foot);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, STOCK));
        fp.getInventory().selected = 0;
        ServerPlayerBody av = driver.avatar();
        // Three physics steps with no input, so the bot is flush before anything is measured —
        // TowerProcess's READY phase refuses to jump on a bot that reports onGround()==false, and a
        // player that has never moved reports exactly that.
        for (int i = 0; i < 3; i++) av.step();
        return driver;
    }

    /** Everything one tower order produced. */
    private record Run(int startY, int endY, int driftX, int driftZ, int spent, int ticks,
                       String lastError) {
        int climbed() { return endY - startY; }
    }

    private static Run tower(SceneContext ctx, String arm, ServerWorldDriver driver, int courses) {
        ServerPlayer fp = driver.fakePlayer();
        BlockPos start = fp.blockPosition();
        int before = carrying(fp);
        driver.runProcess(new TowerProcess(start.getY() + courses, BLOCK_ID));
        int t = 0;
        for (; t < DRIVE_BUDGET && !driver.finished(); t++) driver.tick();
        BlockPos end = fp.blockPosition();
        // Server BotState, and CORRECT here — do not "fix" this to JourneyRig.slot. This arm drives
        // its own isolated driver with driver.runProcess + driver.tick(), so the server's BotState is
        // the one the process writes, whatever topology the suite is on. There is no rig and no helm
        // to route to. Same for JourneyLeg.walkerEnd, which the A/B scenes share.
        Run r = new Run(start.getY(), end.getY(), end.getX() - start.getX(),
                end.getZ() - start.getZ(), before - carrying(fp), t,
                driver.botState().builder.lastError);
        ctx.record(arm + ".tower", String.format(Locale.ROOT,
                "TowerProcess(+%d) from %s -> %s | climbed %d | drift %d,%d | spent %d | %d ticks"
                        + " | lastError=%s",
                courses, start.toShortString(), end.toShortString(), r.climbed(), r.driftX,
                r.driftZ, r.spent, r.ticks, r.lastError));
        WorldDriverCommon.LOG.info("[unwedge:{}] climbed={} spent={} drift={},{} lastError={}",
                arm, r.climbed(), r.spent, r.driftX, r.driftZ, r.lastError);
        return r;
    }

    private static int carrying(ServerPlayer fp) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) if (st.is(Items.COBBLESTONE)) n += st.getCount();
        return n;
    }

    /**
     * The pre-fix behaviour, run on purpose: tower from the flight cell the chooser would have
     * handed back.
     *
     * <p>Its job is to make the arm's real criterion mean something. {@code faults().isEmpty()} is
     * satisfied by a tower that never touched the flight — by an arena where the bot was staged
     * somewhere else, by a tower order that placed nothing, by a staircase registered with no cells
     * in it. So the arm requires the flight to come back BROKEN here first, and hard-fails naming
     * the rig if it does not: an arm that cannot break the staircase deliberately cannot report that
     * it kept it intact.
     *
     * @param stand           the cell to drive the control tower from. The drift arms pass the cell
     *                        {@code towerColumnAfterDrift} itself returns under the PRE-FIX flags, so
     *                        their control is the old code path rather than a hand-made imitation of
     *                        it — one boolean apart from the subject, same world, same tower order.
     * @param ledgeBesideStep what {@link #stage} is re-run with, so the restage is the arm's own
     *                        arrangement and not a guess at it
     */
    private static void controlMustBreakTheFlight(SceneContext ctx, BlockPos stand,
                                                  int ledgeBesideStep) {
        ServerLevel level = ctx.level();
        ctx.record("control.staged", "flight " + JourneyStairs.steps() + " cell(s), bot on "
                + stand.toShortString() + " | " + JourneyStairs.report(level));
        if (!JourneyStairs.faults(level).isEmpty())
            ctx.fail("THE RIG, not the subject: the flight is already faulty before anything ran — "
                    + JourneyStairs.report(level));

        Run r = tower(ctx, "control", body(ctx, stand), 2);
        String broken = JourneyStairs.report(level);
        int faults = JourneyStairs.faults(level).size();
        ctx.record("control.after", faults + " fault(s): " + broken);
        if (faults == 0)
            ctx.fail("THE RIG, not the subject: a tower driven from the step at " + stand.toShortString()
                    + " left the flight intact, so this arm's 'the staircase survived' criterion"
                    + " could not tell a fix from a tower that never reached it — climbed "
                    + r.climbed() + ", spent " + r.spent + ", " + broken);

        stage(ctx, ledgeBesideStep);
        ctx.record("control.restaged", JourneyStairs.report(level));
        if (!JourneyStairs.faults(level).isEmpty())
            ctx.fail("THE RIG, not the subject: re-staging did not put the flight back — "
                    + JourneyStairs.report(level));
    }

    // --------------------------------------------------------------- arms ----

    /**
     * <b>A stairwell cut through rock: there is nowhere to step aside to, so the unwedge must not
     * tower at all.</b>
     *
     * <p>This is the ladder's own geometry. {@code digStairsDown} opens three cells per step and
     * nothing else, so both neighbours of every step are the wall the flight was cut into — which is
     * why "pick a column beside it" cannot be the whole fix, and why
     * {@link JourneyShaft#towerColumnClearOfTheFlight} returns <b>null</b> rather than falling back
     * to the bot's own column. A fallback that towered anyway would be a rule bypassed by its own
     * escape hatch, and it would be bypassed on every single ladder run, because this shape is the
     * common case rather than the rare one.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li>the chooser returns null for the bot's own column — {@code do not tower here};</li>
     *   <li>it returns null for every one of the flight's other cells too, so the answer is a
     *       property of the geometry and not of the one cell the bot happens to be on;</li>
     *   <li>the row the climb would record names the step it refused, so a run that took this branch
     *       can be told from one that never met a staircase.</li>
     * </ol>
     */
    private static void refusesTheStaircaseColumn(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, 0);
        controlMustBreakTheFlight(ctx, step(ctx, STAND_ON), 0);

        BlockPos stand = step(ctx, STAND_ON);
        BlockPos chosen = JourneyShaft.towerColumnClearOfTheFlight(level, stand);
        ctx.record("subject.chosen", chosen == null ? "null (tower not allowed)" : chosen.toShortString());
        ctx.record("subject.row", JourneyShaft.offTheFlightRow(level, stand, chosen));

        ctx.check(chosen).as("A both sides of the stairwell are stone, so the answer must be \"do not"
                + " tower\" rather than \"pick any column\":"
                + " towerColumnClearOfTheFlight(" + stand.toShortString() + ") - null means \"use the"
                + " stairs this time\"; any non-null value is a column the chooser has just verified"
                + " cannot be stood on").isNull();

        StringBuilder each = new StringBuilder();
        int refused = 0;
        for (int s = 1; s <= STEPS; s++) {
            BlockPos c = JourneyShaft.towerColumnClearOfTheFlight(level, step(ctx, s));
            if (c == null) refused++;
            each.append(s == 1 ? "" : " ").append(step(ctx, s).toShortString()).append("->")
                    .append(c == null ? "null" : c.toShortString());
        }
        ctx.record("subject.everyStep", each.toString());
        ctx.check(refused).as("B every step must be refused; otherwise the refusal is a coincidence"
                + " of this one cell rather than a property of the geometry: " + each).isEqualTo(STEPS);

        ctx.check(JourneyShaft.offTheFlightRow(level, stand, chosen)
                        .contains("use the staircase itself instead"))
                .as("C the row must say which step it refused and why: »"
                        + JourneyShaft.offTheFlightRow(level, stand, chosen) + "«").isTrue();
    }

    /**
     * <b>The same flight with one standable cell beside it: the unwedge finds it, towers there, and
     * the staircase is still walkable afterwards.</b>
     *
     * <p>The only difference from the arm above is the ledge — nine cells of air over one stone floor
     * beside step {@value #STAND_ON}. Everything else, including the control, is the same, so a
     * disagreement between the two arms is about the ledge and about nothing else.
     *
     * <h2>Criteria — four, and none of them redundant</h2>
     *
     * <ol>
     *   <li><b>the chosen column is not the flight's.</b> The mechanism.</li>
     *   <li><b>the tower actually climbed its four courses and spent its four blocks.</b> Without
     *       this the criterion below is 0 == 0: a tower that placed nothing keeps any staircase
     *       intact, and the ladder has already been burnt once by a green reading over a verb that
     *       never ran.</li>
     *   <li><b>every cell of the flight is passable</b> — {@code faults()} empty, which is four
     *       block reads per step and covers the support, the step, its head room and its jump
     *       clearance.</li>
     *   <li><b>the audit's own sentence says so</b>, recorded on PASS as well as FAIL, because "7
     *       steps, all intact" and "mended" are different outcomes and this arm accepts only the
     *       first.</li>
     * </ol>
     */
    private static void towersBesideTheStaircase(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, STAND_ON);
        controlMustBreakTheFlight(ctx, step(ctx, STAND_ON), STAND_ON);

        BlockPos stand = step(ctx, STAND_ON);
        BlockPos ledge = ctx.rel(STAND_ON, BASE + 7 - STAND_ON, 1);
        BlockPos chosen = JourneyShaft.towerColumnClearOfTheFlight(level, stand);
        ctx.record("subject.chosen", chosen == null ? "null" : chosen.toShortString()
                + " (step " + stand.toShortString() + ", ledge " + ledge.toShortString() + ")");
        ctx.record("subject.row", JourneyShaft.offTheFlightRow(level, stand, chosen));

        ctx.check(chosen).as("A when a standable neighbouring column exists it must be chosen, not"
                + " refused: the ledge is at " + ledge.toShortString()).isEqualTo(ledge);

        final int courses = 4;
        Run r = tower(ctx, "subject", body(ctx, ledge), courses);
        String after = JourneyStairs.report(level);
        int faults = JourneyStairs.faults(level).size();
        ctx.record("subject.after", faults + " fault(s): " + after);

        ctx.check(r.climbed()).as("B the tower really climbed " + courses + " courses (otherwise"
                + " \"the stairs are fine\" is 0==0): from y="
                + r.startY() + " to y=" + r.endY() + ", drift " + r.driftX() + "," + r.driftZ()
                + ", lastError=" + r.lastError()).isEqualTo(courses);
        ctx.check(r.spent()).as("C exactly " + courses + " cobblestone spent: spent " + r.spent())
                .isEqualTo(courses);
        ctx.check(faults).as("D every step of the stairs is still walkable (support, step, head room"
                + " and jump clearance all read): " + after)
                .isEqualTo(0);
        ctx.check(after.contains("all intact")).as("E the audit's own result message must say"
                + " \"all intact\", not \"mended\": »" + after + "«").isTrue();
    }

    // ------------------------------------------------- arms: the drift path ----

    /**
     * The state the drift arms start from, recorded and CHECKED rather than assumed.
     *
     * <p>Three premises, and a rig that broke any of them would let both arms below report a colour
     * about something else: the aimed column is a flight column (so a pinned climb really does reach
     * {@code climbFrom}'s record-only branch), the drifted column is a flight column too (so the
     * adopted column is the thing under test), and the two are DIFFERENT (so {@code driftMoved} is
     * true of the arena and not merely of the argument the scene passes).
     */
    private static void driftPremises(SceneContext ctx, BlockPos aimed, BlockPos drifted) {
        ServerLevel level = ctx.level();
        BlockPos aimedStep = JourneyStairs.stepInColumn(level, aimed.getX(), aimed.getZ());
        BlockPos driftedStep = JourneyStairs.stepInColumn(level, drifted.getX(), drifted.getZ());
        ctx.record("drift.premise", "pinned column " + aimed.getX() + "," + aimed.getZ() + " → "
                + (aimedStep == null ? "null" : aimedStep.toShortString()) + "; after the drift the bot"
                + " is at " + drifted.toShortString() + ", column " + drifted.getX() + ","
                + drifted.getZ() + " → " + (driftedStep == null ? "null" : driftedStep.toShortString()));
        if (aimedStep == null || driftedStep == null
                || (aimed.getX() == drifted.getX() && aimed.getZ() == drifted.getZ()))
            ctx.fail("THE RIG, not the subject: this arm needs a PINNED column on the flight and a"
                    + " drift that ended in a DIFFERENT flight column - pinned " + aimed.getX() + ","
                    + aimed.getZ() + "=" + aimedStep + ", drifted to " + drifted.getX() + ","
                    + drifted.getZ() + "=" + driftedStep);
    }

    /**
     * The pre-fix answer, taken from the production chooser itself and then driven.
     *
     * <p>{@code towerColumnAfterDrift(level, drifted, pinned=true, driftMoved=false)} is exactly what
     * the drift branch used to compute for a pinned climb — the old {@code climbPinned ? back :
     * check(...)} — and it is still what the code computes for a pin the drift has NOT moved, which
     * is the behaviour {@code climbFrom} documents and this fix deliberately keeps. So the control is
     * not an imitation of the old path: it IS the old path, one boolean away from the subject.
     *
     * <p>It hard-fails on two different things, because they are two different lies. If the chooser
     * hands back anything but the drifted cell, the control is not the pre-fix answer at all. If a
     * tower driven from that cell leaves the flight intact, the arm's "the stairs are fine" criterion cannot tell
     * a fix from a tower that never reached the staircase.
     */
    private static void driftControlMustBreakTheFlight(SceneContext ctx, BlockPos drifted,
                                                       int ledgeBesideStep) {
        ServerLevel level = ctx.level();
        BlockPos prefix = JourneyShaft.towerColumnAfterDrift(level, drifted, true, false);
        ctx.record("control.prefixChoice", (prefix == null ? "null" : prefix.toShortString())
                + " (pinned, and the drift did not replace the column ⇒ the flight is not"
                + " consulted; this is the pre-fix answer)");
        if (!drifted.equals(prefix))
            ctx.fail("THE RIG, not the subject: the control is supposed to BE the pre-fix answer and"
                    + " it is not — towerColumnAfterDrift(" + drifted.toShortString()
                    + ", pinned=true, driftMoved=false) = " + prefix);
        controlMustBreakTheFlight(ctx, prefix, ledgeBesideStep);
    }

    /**
     * <b>A pinned climb whose drift ended in a staircase column: the pin stops protecting that column
     * the moment the drift replaced it, and there is nowhere to step aside to, so nothing is built.</b>
     *
     * <p>This is rung 12 of 2026-08-19. The raise was pinned to column {@code 2,19} — itself a flight
     * column, which {@code climbFrom} records and leaves alone because the column came out of the
     * pour's ray. Course one drifted to {@code 1,58,19}, {@code driftWedged} (a walk that moved the
     * bot zero cells), and {@code driftKeptPinned} adopted column {@code 1,19}: a different column,
     * chosen by a drift, and <b>a flight column that nothing ever put through the chooser</b>. Two
     * dirt went into {@code 1,58,19} and {@code 1,59,19}, the bot finished standing on the second,
     * and all three ascent walks then died on a staircase it was itself blocking.
     *
     * <p>The tread audit is not the fix, and this arm is why: {@code lava9.up} ran on that same trip
     * and mended the two treads it could see. The one it could not is the block under the bot's own
     * feet — a bot cannot mine what it is standing on — and that audit runs once and never re-asks.
     *
     * <h2>Criteria</h2>
     *
     * <ol>
     *   <li>the premises hold: a pinned flight column, and a drift into a DIFFERENT flight column;</li>
     *   <li>the control — the same chooser with {@code driftMoved=false} — hands back the drifted
     *       cell, and a tower there breaks the flight. Without this, criterion 3 is 0 == 0;</li>
     *   <li>with {@code driftMoved=true} the answer is <b>null</b>: do not tower here at all;</li>
     *   <li>the unpinned answer is the same null, so the outcome is a property of the geometry and
     *       not a special case bolted onto the pin.</li>
     * </ol>
     */
    private static void pinnedDriftRefusesTheStaircase(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, 0);

        BlockPos aimed = step(ctx, AIMED_ON);
        BlockPos drifted = step(ctx, DRIFTED_ON);
        driftPremises(ctx, aimed, drifted);
        driftControlMustBreakTheFlight(ctx, drifted, 0);

        BlockPos chosen = JourneyShaft.towerColumnAfterDrift(level, drifted, true, true);
        BlockPos unpinned = JourneyShaft.towerColumnAfterDrift(level, drifted, false, true);
        ctx.record("subject.chosen", chosen == null ? "null (tower not allowed)" : chosen.toShortString());
        ctx.record("subject.unpinned", unpinned == null ? "null" : unpinned.toShortString());
        ctx.record("subject.row", JourneyShaft.offTheFlightRow(level, drifted, chosen));

        ctx.check(chosen).as("A the drift has already replaced the pinned column, so this time the"
                + " flight must be consulted: towerColumnAfterDrift(" + drifted.toShortString()
                + ", pinned=true, driftMoved=true) - both sides of the stairwell are stone, so the"
                + " only valid answer is null").isNull();
        ctx.check(unpinned).as("B removing the pin must give the same answer; otherwise the refusal"
                + " is a patch on the pin rather than a property of the geometry: " + unpinned).isNull();
        ctx.check(JourneyShaft.offTheFlightRow(level, drifted, chosen)
                        .contains("use the staircase itself instead"))
                .as("C the row must say which step it refused and why: »"
                        + JourneyShaft.offTheFlightRow(level, drifted, chosen) + "«").isTrue();
    }

    /**
     * <b>The same drift with one standable cell beside it: the adopted column moves off the flight,
     * the tower is built there, and the staircase is still walkable.</b>
     *
     * <p>The arm above proves the pin stops REFUSING to look. This one proves the look then ACTS —
     * an arm that only ever answered null would be satisfied by a chooser that had simply been
     * switched off. The only difference from it is nine cells of air over one stone floor beside step
     * {@value #DRIFTED_ON}.
     *
     * <h2>Criteria — six, and none of them redundant</h2>
     *
     * <ol>
     *   <li>the premises hold, as above;</li>
     *   <li>the control is still the pre-fix answer and still breaks the flight;</li>
     *   <li>the chosen column is the ledge, not the flight's;</li>
     *   <li>the unpinned answer is the same ledge — once the drift has moved the column the pin makes
     *       no difference at all, which is this fix stated as an equality;</li>
     *   <li>the tower actually climbed its courses and spent its blocks, so the last one is not
     *       0 == 0;</li>
     *   <li>every cell of the flight is passable, and the audit's own sentence says "all intact"
     *       rather than "mended".</li>
     * </ol>
     *
     * <p>The tower is driven from what the chooser returned rather than from the ledge this scene
     * staged, and that is not a detail: the pre-fix reproduction of this arm handed back the STEP and
     * still reported {@code subject.after = 0 fault(s): 7 steps, all intact}, because the drive had been given
     * the right cell by the test instead of by the code.</p>
     */
    private static void pinnedDriftTowersBesideTheStaircase(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx, DRIFTED_ON);

        BlockPos aimed = step(ctx, AIMED_ON);
        BlockPos drifted = step(ctx, DRIFTED_ON);
        BlockPos ledge = ctx.rel(DRIFTED_ON, BASE + 7 - DRIFTED_ON, 1);
        driftPremises(ctx, aimed, drifted);
        driftControlMustBreakTheFlight(ctx, drifted, DRIFTED_ON);

        BlockPos chosen = JourneyShaft.towerColumnAfterDrift(level, drifted, true, true);
        BlockPos unpinned = JourneyShaft.towerColumnAfterDrift(level, drifted, false, true);
        ctx.record("subject.chosen", (chosen == null ? "null" : chosen.toShortString())
                + " (step " + drifted.toShortString() + ", ledge " + ledge.toShortString() + ")");
        ctx.record("subject.unpinned", unpinned == null ? "null" : unpinned.toShortString());
        ctx.record("subject.row", JourneyShaft.offTheFlightRow(level, drifted, chosen));

        ctx.check(chosen).as("A once the drift has changed the column, the pinned tower must also move"
                + " to the ledge: the ledge is at " + ledge.toShortString()).isEqualTo(ledge);
        ctx.check(unpinned).as("B after the drift, pinning makes no difference to the result (the"
                + " column the ray chose is no longer in use): " + unpinned).isEqualTo(chosen);

        // FROM THE CHOOSER'S OWN ANSWER, not from the cell this scene knows to be right. Driving the
        // tower from `ledge` would make the two criteria below true of a run in which the chooser had
        // handed back the staircase — measured: the pre-fix reproduction of this arm reported
        // `subject.after = 0 fault(s): 7 steps, all intact` beside `subject.chosen = …,100000`, the step
        // itself. `ctx.check` accumulates rather than throws, so a null here has to be stopped by a
        // `fail` or it would arrive as an NPE with no evidence attached.
        if (chosen == null)
            ctx.fail("the chooser refused a column that has a ledge beside it, so there is no tower"
                    + " to drive - ledge " + ledge.toShortString() + ", step " + drifted.toShortString());
        final int courses = 4;
        Run r = tower(ctx, "subject", body(ctx, chosen), courses);
        String after = JourneyStairs.report(level);
        int faults = JourneyStairs.faults(level).size();
        ctx.record("subject.after", faults + " fault(s): " + after);

        ctx.check(r.climbed()).as("C the tower really climbed " + courses + " courses (otherwise"
                + " \"the stairs are fine\" is 0==0): from y="
                + r.startY() + " to y=" + r.endY() + ", drift " + r.driftX() + "," + r.driftZ()
                + ", lastError=" + r.lastError()).isEqualTo(courses);
        ctx.check(r.spent()).as("D exactly " + courses + " cobblestone spent: spent " + r.spent())
                .isEqualTo(courses);
        ctx.check(faults).as("E every step of the stairs is still walkable (support, step, head room"
                + " and jump clearance all read): " + after)
                .isEqualTo(0);
        ctx.check(after.contains("all intact")).as("F the audit's own result message must say"
                + " \"all intact\", not \"mended\": »" + after + "«").isTrue();
    }

    // ------------------------------------------------- the wash-off's upstream ----

    /** The channel's floor, its walls, and the bot's cell, as dy offsets from {@link #BASE}. */
    private static final int WET_FLOOR = 0, WET_FEET = 1;

    /** Where the bot stands and where the source sits, as dx. Two apart, so the source is inside
     *  {@code JourneyShaft.WASHED_OFF_UPSTREAM} (4) from the bot's cell AND stays inside it after the
     *  flow has pushed the bot as far west as the channel's end wall allows. A source further off
     *  would make the reading answer "no water source block" for a puddle that visibly has one, which
     *  is state 4 of this arm's pre-registered outcomes and a staging bug rather than a finding. */
    private static final int WET_STAND_X = 1, WET_SOURCE_X = 3;

    /** Courses the climb is allowed. Eight, matching {@code WASHED_OFF_RETRIES}, so a fix that did
     *  NOT hand off has room to burn every retry and be seen doing it — an allowance of one would
     *  make criterion D pass by arithmetic instead of by behaviour. */
    private static final int WET_COURSES = 8;

    /**
     * A tower that stalls in water a live source keeps feeding hands off at once — it does not retry.
     *
     * <p><b>Why this has to be staged at all.</b> The branch under test is reached only from the
     * climb's STALL path, and rung 12's own healthy run now walks its last step correctly, so a
     * rehearsal may never enter a wash-off again. A branch a healthy run never executes is the runtime
     * form of a criterion that cannot fail: it reports nothing, forever, and reads like agreement.
     *
     * <p><b>What the arena reproduces and what it does not.</b> The subject is the QUESTION the stall
     * asks — "the water is moving; is anything feeding it?" — and the answer it acts on. The stall's
     * own cause is upstream of that and deliberately different here: the field run's bot was pushed
     * off its pillar, this one simply has no block to place, so {@code TowerProcess} gains nothing
     * and the course ends in the same place. Do not read this arm as evidence about WHY towers stall
     * in water.
     *
     * <p><b>The order the climb checks things in decides the staging</b>, and two of its branches
     * would swallow this one:
     *
     * <ul>
     *   <li>{@code pillarRiseBlockers} non-empty ⇒ the ceiling is mined or {@code wouldOpenFluid}
     *       stops the climb, both before the tower runs. So the bot's own column is left open.</li>
     *   <li>{@code !onGround} ⇒ the {@code afloat} branch, which is the FLOATING case and a different
     *       subject. So the water is one block deep over stone and the bot stands in it.</li>
     * </ul>
     *
     * <p>A dry control arm is deliberately absent: sourceless flowing water drains in a few dozen
     * ticks, so the staging would die before the assertion — the control would be measuring its own
     * decay. The fed-by-a-source arm alone separates the two mechanisms, because the row it asserts on names
     * the source it found.
     */
    private static void stopsToweringWhenASourceFeedsTheWater(SceneContext ctx) {
        config(ctx);
        // The channel: stone floor, stone walls on all four sides of a three-cell run, open above.
        for (int dx = -1; dx <= 6; dx++)
            for (int dz = -2; dz <= 2; dz++)
                ctx.setBlock(dx, BASE + WET_FLOOR, dz, Blocks.STONE);
        for (int dx = 0; dx <= WET_SOURCE_X + 1; dx++)
            for (int dy = 1; dy <= 2; dy++) {
                ctx.setBlock(dx, BASE + dy, -1, Blocks.STONE);
                ctx.setBlock(dx, BASE + dy, 1, Blocks.STONE);
            }
        // The two end walls. The west one is what keeps the bot inside the source's radius no matter
        // how long the flow pushes it — see WET_STAND_X.
        for (int dy = 1; dy <= 2; dy++) {
            ctx.setBlock(WET_STAND_X - 1, BASE + dy, 0, Blocks.STONE);
            ctx.setBlock(WET_SOURCE_X + 1, BASE + dy, 0, Blocks.STONE);
        }
        BlockPos src = ctx.rel(WET_SOURCE_X, BASE + WET_FEET, 0);
        ctx.setBlock(WET_SOURCE_X, BASE + WET_FEET, 0, Blocks.WATER);

        BlockPos foot = ctx.rel(WET_STAND_X, BASE + WET_FEET, 0);
        ServerWorldDriver driver = SceneBody.managed(ctx, foot);
        ServerPlayer fp = driver.fakePlayer();
        // NO PLACEABLE BLOCK, on purpose — see the class note above. `pillarBlock` falls back to
        // cobblestone with a count of zero, `.hand` records that it could not be held, and execution
        // falls through to the tower exactly as it does in the field.
        ServerPlayerBody av = driver.avatar();
        // The bot flush on the floor. TowerProcess's READY phase refuses a bot reporting
        // onGround()==false, and so does the climb's own afloat branch.
        for (int i = 0; i < 3; i++) av.step();

        JourneyRig rig = JourneyRig.forArena(ctx, JourneyStage.PORTAL_LIT, driver);
        // KEPT ON PURPOSE, and it is the reading that got this arm wrong the first time: `av.step()`
        // steps the BOT, not the world's fluid ticks, so twenty of them left the source sitting two
        // cells away with the bot's own cell still dry. The control asserted "standing in water"
        // there and went red while the subject's rows — taken two hundred ticks later, at the
        // stall — read `inWater=true` and named the source correctly. A precondition sampled long
        // before the branch it gates is not a precondition; it is a different measurement wearing
        // its name.
        ctx.record("staged.beforeFlow", "just placed (the world has not ticked fluids yet): inWater="
                + fp.isInWater() + ", foot cell " + ctx.level().getFluidState(foot).getType());
        rig.settle(new HoldStill(WET_SPREAD), WET_SPREAD * 2, () -> {
            ctx.record("staged.body", String.format(Locale.ROOT, "%s exact %.2f/%.2f/%.2f, onGround=%s,"
                            + " inWater=%s; water source %s", fp.blockPosition().toShortString(), fp.getX(),
                    fp.getY(), fp.getZ(), fp.onGround(), fp.isInWater(), src.toShortString()));
            ctx.check(fp.isInWater()).as("control A: the bot must really be standing in water -"
                    + " otherwise the entry condition of the whole washedOff branch is false and"
                    + " B/C/D are all 0==0: " + fp.blockPosition()).isTrue();
            ctx.check(fp.onGround()).as("control A2: the bot must be **standing on the ground** - a"
                    + " floating bot takes the afloat branch, which is a different subject, and this"
                    + " arm would then measure nothing").isTrue();
            climbInTheFlow(ctx, rig, fp, src, foot);
        });
    }

    /** Ticks the world gets to carry the source the two cells to the bot's own feet. Forty, which is
     *  several times vanilla's five-ticks-per-cell spread and cheap next to the tower's own 200. */
    private static final int WET_SPREAD = 40;

    private static void climbInTheFlow(SceneContext ctx, JourneyRig rig, ServerPlayer fp,
                                       BlockPos src, BlockPos foot) {
        JourneyShaft.ascendByTowering(rig, foot.getY() + 5, WET_COURSES, WET_COURSES, "fed", () -> {
            // BY SUFFIX, not by exact key: the climb's rows carry `climbSeq`, a run-global counter, so
            // the same arena writes a different key depending on what ran before it in the suite.
            List<Object> upstream = rig.evidenceEndingWith(".washedOffUpstream");
            List<Object> handedOff = rig.evidenceEndingWith(".washedOffFed");
            List<Object> retried = rig.evidenceEndingWith(".washedOff");
            List<Object> afloat = rig.evidenceEndingWith(".afloat");
            ctx.record("subject.upstream", String.valueOf(upstream));
            ctx.record("subject.handedOff", String.valueOf(handedOff));
            ctx.record("subject.retried", retried.size() + " row(s): " + retried);
            ctx.record("subject.afloat", afloat.size() + " row(s): " + afloat);
            ctx.record("subject.endedAt", fp.blockPosition().toShortString() + ", inWater="
                    + fp.isInWater() + ", onGround=" + fp.onGround());

            // B FIRST, because C and D are 0==0 without it: no upstream row means the stall never
            // reached the flow check at all (a floating bot, or a course that never stalled).
            ctx.check(upstream).as("B the climb must really have checked upstream before stopping -"
                    + " no washedOffUpstream row means this course never reached the flow check"
                    + " (afloat rows: " + afloat.size() + ")").isNotEmpty();
            ctx.check(String.valueOf(upstream.get(0))).as("B2 the upstream reading must name the"
                    + " source this scene placed, " + src + " - if it does not, the probe cannot reach"
                    + " it (radius 4), which is not the same as \"no source\"").contains(src.toShortString());
            ctx.check(handedOff).as("C with a source present the climb must take the \"no retry, hand"
                    + " off to the caller's fallback\" branch - no washedOffFed row means the fix did"
                    + " not take effect and the stall was retried as transient").isNotEmpty();
            ctx.check(retried).as("D the hand-off must be **immediate**, not made after burning "
                    + WET_COURSES + " retries - any washedOff retry row means the early stop is"
                    + " ordered after the retry: " + retried).isEmpty();
        });
    }
}
