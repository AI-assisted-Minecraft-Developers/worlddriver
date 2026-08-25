package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
import net.magicterra.worlddriver.bot.stagewright.SceneBody;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The flight that laid nothing because the body was standing on its own bottom step.
 *
 * <h2>The run, in four rows</h2>
 *
 * <p>Ladder run of 2026-08-20, rung 12, cast 9 of 10 — the last ring cell. Read them in the order
 * the rung wrote them:
 *
 * <pre>
 * cast9.raiseTo.gotoEnd.1 = end=arrived …（判为到达：停在 2, 56, 20，距 2,20 0 格，容差 5）
 * cast9.ramp.flight       = 3 级：2, 56, 20 → 3, 57, 20 → 2, 58, 20（壁龛地板 y=56，身体 2, 56, 20）
 * cast9.ramp.laid         = 0/3 级垫好了（身体 2, 56, 20）
 * cast9.raisedY           = 59/59（停在 3,17，指定柱 2,20，不是同一柱 —— 射线是照那一柱算的）
 * </pre>
 *
 * <p>The raise walked the body into the column it had verified, and it landed on the alcove floor at
 * {@code 2,56,20}. The planner then searched down from the landing and its bottom course came to
 * rest on exactly that cell — {@code flight.get(0).below()} IS the cell the body is standing in. The
 * loop broke out on its own first course and {@link JourneyRamp#lay} answered「laid nothing」with
 * 「walking changes nothing」, which is the rule written for a REFUSED placement. Nothing was built,
 * nothing was reported beyond {@code 0/3}, and the tower behind it then drifted into the flooded
 * floor row and ended six columns away.
 *
 * <h2>The rule is right about one case and wrong about the other</h2>
 *
 * <p>The same run proves the half that must survive. Cell nine's flight refused its top course from
 * two stands nine blocks apart and got the identical answer both times — the {@code #2} suffix is
 * the rig's own duplicate-key marker, so these are two writes of one key, not one row:
 *
 * <pre>
 * cell.9.ramp.step.2   = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 0, 58, 19
 * cell.9.ramp.step.2#2 = 3, 58, 20 垫不上（… 六邻没有能贴的实心面），身体 2, 56, 20
 * </pre>
 *
 * <p>Six air neighbours are six air neighbours from anywhere. So the fix is not「always retry」: it
 * is to stop answering two findings with one sentence. {@link JourneyRamp.Stop} names why a pass
 * stopped and {@link JourneyRamp#stepAsideFor} spends one step-aside on
 * {@link JourneyRamp.Stop#BODY_IN_THE_WAY} and none on the other three.
 *
 * <h2>Why the planner is not where this is fixed</h2>
 *
 * <p>The obvious cheaper answer — refuse to plan a course into the cell the body occupies, the way
 * {@link JourneySight#onALineToCome} refuses a cell a pour still has to shoot through — was
 * enumerated against the run's own geometry and does not exist there. From the landing
 * {@code 2,59,20} the descent has three continuations, and with {@code 2,56,20} forbidden as a
 * support all three die: {@code 2,58,19} needs {@code 2,57,19}, which is the descent staircase's
 * head room and {@link JourneyStairs#needsOpen} refuses it; {@code 3,58,20} continues only into
 * {@code 2,57,20} (whose support IS the body's cell), {@code 3,57,19} and {@code 3,57,21} (both
 * already cobblestone from earlier cells' flights, so not standable); {@code 2,58,21} continues into
 * the same three. The body's cell was the only bottom course this alcove had left. A reservation
 * there would have moved the failure one leg earlier and told the run less.
 *
 * <h2>What these arms drive, and what they do not</h2>
 *
 * <p>{@link JourneyRamp#layWhereItStands} and {@link JourneyRamp#stepAsideFor} are production code
 * called directly, over a staged alcove, by a real body holding real cobblestone — every placement
 * goes through {@code gameMode.useItemOn} and every course is read back off the world.
 * {@link JourneyRamp#lay} itself is NOT driven: it needs a {@link JourneyRig} to
 * {@code settle} a walk, and a scene cannot host one. Two lines of it are therefore covered by
 * reading the diff and not by a test — the {@code walkTo} that carries the body to the cell
 * {@code stepAsideFor} names, and the recursion that re-enters with the pass's own {@code laid}.
 * {@link #driveTheLoop} below is that method with those two lines replaced by putting the body in
 * the named cell; everything it calls in between is the real thing.
 *
 * <p>That substitution is the honest boundary and it is also the reason
 * {@link JourneyRamp#approach} now prints {@code .stand} and {@code .standShort}. The run above
 * cannot say whether {@code approach} found nowhere to go or walked and did not arrive, because it
 * wrote no row at all; if the WALK is what fails, no arena in this file can see it and only the
 * ladder can.
 *
 * <h2>Arena footprint</h2>
 *
 * <p>Stated by hand: {@code scripts/check_scene_arena.py} scans only
 * {@code .../bot/stagewright/scene/}, so a journey scene's footprint is not machine-checked.
 * Every cell these arms touch lies in {@code dx ∈ [-2, 3]}, {@code dz ∈ [-4, 4]},
 * {@code dy ∈ [BASE-2, BASE+8] = [18, 28]} — a solid stone block with the five-wide, two-deep,
 * seven-tall alcove carved out of it, well inside the default one-chunk window.
 */
public final class JourneyRampScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.rampStepsAsideWhenTheBodyIsInItsOwnStep", 200,
                        JourneyRampScenes::stepsAsideWhenTheBodyIsInItsOwnStep),
                Scene.of("wd.rampFootholdRisesWithTheFlightItLaid", 200,
                        JourneyRampScenes::footholdRisesWithTheFlightItLaid),
                Scene.of("wd.rampNeverFoldsBackIntoItsOwnHeadroom", 400,
                        JourneyRampScenes::neverFoldsBackIntoItsOwnHeadroom),
                Scene.of("wd.rampSeparatesAFaceThatExistsFromOneItCanHit", 200,
                        JourneyRampScenes::separatesAFaceThatExistsFromOneItCanHit));
    }

    // ---------------------------------------------------------------------- arena ----

    /** The alcove's floor row, as a dy offset — the row the mould's base sits in. Twenty above the
     *  grid's y=200, like the sibling journey arenas. */
    private static final int BASE = 20;

    /** How far the mould is pushed out from the shaft the body arrives down. Two, which is what the
     *  run this file is about used, so the corridor is the same two ranks by five. */
    private static final int PUSH = 2;

    /** East, so the corridor runs {@code dx ∈ {0,1}} and its width runs along z. Same handedness as
     *  the run. */
    private static final Direction AWAY = Direction.EAST;

    /** The shaft-bottom cell the corridor is measured from — {@code 2,56,19} in the run. */
    private static BlockPos at(SceneContext ctx) { return ctx.rel(0, BASE, 0); }

    /** Where the flight has to deliver the feet. Three courses up and one cell over, which is the
     *  shape of the run's own {@code cast9} raise ({@code 2,56,19} → {@code 2,59,20}). */
    private static BlockPos landing(SceneContext ctx) { return ctx.rel(0, BASE + 3, 1); }

    private static Set<BlockPos> corridor(SceneContext ctx) {
        return Set.copyOf(JourneyForge.corridor(at(ctx), AWAY, PUSH));
    }

    /** Solid rock with the alcove cut out of it. Nothing else: no mould, no staircase, no steps. The
     *  question here is what the loop does about a body, and a frame cell in the way would answer a
     *  different one. */
    private static void stage(SceneContext ctx) {
        stage(ctx, JourneyForge.corridor(at(ctx), AWAY, PUSH));
    }

    /** The same rock, with the caller naming which cells are hollow. Split out for the fold arm,
     *  whose whole subject is a corridor shape {@link JourneyForge} does not produce: the route the
     *  planner must refuse only exists when one continuation is missing, and a generated alcove has
     *  them all. The rock box is unchanged either way, so the arena footprint in the class note
     *  still covers both. */
    private static void stage(SceneContext ctx, Iterable<BlockPos> corridor) {
        clearBox(ctx);
        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 2; dy <= BASE + 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (BlockPos c : corridor)
            ctx.level().setBlockAndUpdate(c, Blocks.AIR.defaultBlockState());
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 2; dy <= BASE + 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The switches every arm shares, plus the statics these scenes have to hand back. A step set or
     *  a mould left behind would give a ladder run in the same process a flight it never built. */
    private static void config(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        // Placement stays ON: this arena's whole subject is a loop that places blocks, and every
        // course it claims is read back off the world rather than off the call.
        BotConfig.allowPlace = true;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ServerAvatarManager.clear();
        ctx.cleanup(ServerAvatarManager::clear);
        ctx.cleanup(JourneyRamp::reset);
        // JourneySight.lineOwner is consulted inside the loop. With no mould registered it answers
        // null for everything, which is what a bare alcove should say — but a mould left over from
        // JourneyPourLineScenes would make this file's flights print borrow rows about a frame that
        // is not here.
        JourneySight.forgetMould();
        ctx.cleanup(JourneySight::forgetMould);
        // JourneyRamp.fillable consults JourneyStairs.needsOpen, so a staircase from another scene
        // would refuse cells of this alcove for a reason that has nothing to do with the subject.
        JourneyStairs.forget();
        ctx.cleanup(JourneyStairs::forget);
        ctx.cleanup(() -> clearBox(ctx));
    }

    /** A body standing in {@code foot}, settled, carrying the rung's own cobblestone. */
    private static ServerWorldDriver body(SceneContext ctx, BlockPos foot) {
        ServerWorldDriver driver = SceneBody.managed(ctx, foot);
        ServerPlayer fp = driver.fakePlayer();
        fp.getInventory().items.set(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().selected = 0;
        settle(driver);
        return driver;
    }

    /** Put the body in a cell and let physics catch up. This is the stand-in for the {@code walkTo}
     *  {@link JourneyRamp#lay} makes — see the class note for why a scene cannot make the real one. */
    private static void placeAt(ServerWorldDriver driver, BlockPos foot) {
        driver.fakePlayer().moveTo(foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
        settle(driver);
    }

    private static void settle(ServerWorldDriver driver) {
        ServerPlayerAvatar av = driver.avatar();
        for (int i = 0; i < 3; i++) av.step();
    }

    /** A flight as the blocks it lays, which is how {@code ramp.flight} prints it: each entry is a
     *  cell the body STANDS in, and the cobblestone goes under it. */
    private static String supports(List<BlockPos> flight) {
        StringBuilder out = new StringBuilder();
        for (BlockPos s : flight)
            out.append(out.isEmpty() ? "" : " → ").append(s.below().toShortString());
        return out.toString();
    }

    /** How many courses of this flight the WORLD is holding up — not how many the loop claimed. */
    private static int standing(ServerLevel level, List<BlockPos> flight) {
        int n = 0;
        for (BlockPos s : flight) if (level.getBlockState(s.below()).blocksMotion()) n++;
        return n;
    }

    // ------------------------------------------------------------------ the driver ----

    /** One run of {@link JourneyRamp#lay}, with its walk replaced and its recursion unrolled. */
    private record Loop(JourneyRamp.Pass last, int passes, List<String> trace) {}

    /**
     * {@link JourneyRamp#lay}, line for line, with two substitutions named in the class note: the
     * {@code walkTo} becomes {@link #placeAt}, and the recursion becomes this loop. Everything
     * between — the pass, the decision, the one-shot latch, what is carried into the next pass — is
     * the production call.
     *
     * <p>Bounded at eight passes so a decision that never stops is a FAIL with a trace rather than a
     * scene that hangs; a three-course flight needs at most three.
     */
    private static Loop driveTheLoop(SceneContext ctx, ServerWorldDriver driver,
                                     Set<BlockPos> corridor, List<BlockPos> flight, int from,
                                     boolean alreadyAside, String tag) {
        ServerLevel level = ctx.level();
        ServerPlayer fp = driver.fakePlayer();
        List<String> trace = new ArrayList<>();
        Map<String, Object> rows = new LinkedHashMap<>();
        JourneyRamp.Pass pass = null;
        int passes = 0;
        while (passes < 8) {
            passes++;
            pass = JourneyRamp.layWhereItStands(level, fp, driver.avatar(), corridor, flight, from,
                    rows::put, tag);
            BlockPos to = JourneyRamp.stepAsideFor(level, fp, corridor, flight, pass, from,
                    alreadyAside);
            trace.add("第 " + passes + " 趟：身体 " + fp.blockPosition().toShortString()
                    + "，从第 " + from + " 级起，垫到 " + pass.laid() + "/" + flight.size()
                    + "，停在 " + pass.stop()
                    + (pass.at() == null ? "" : " " + pass.at().toShortString())
                    + " → " + (to == null ? "不再问了" : "挪到 " + to.toShortString()));
            if (to == null) break;
            placeAt(driver, to);
            alreadyAside = pass.laid() == from;
            from = pass.laid();
        }
        for (Map.Entry<String, Object> e : rows.entrySet()) ctx.record(e.getKey(), e.getValue());
        return new Loop(pass, passes, trace);
    }

    // ------------------------------------------------- the body on its own bottom step ----

    /**
     * <b>A body standing on {@code flight.get(0).below()} is an obstruction that can walk away, and
     * the loop used to treat it as one that cannot.</b>
     *
     * <p>Staged the way the run produced it and not the way it is convenient to assert: the alcove
     * is carved, the production planner is asked for a flight, and THEN the body is put on whatever
     * cell that flight chose for its bottom course. So the premise「the body is standing in the cell
     * the flight has to fill」is the planner's own answer, not a coordinate this file picked.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>A</b> the first pass lays nothing, and names why — {@code BODY_IN_THE_WAY} at the
     *       bottom support, which is {@code cast9.ramp.laid = 0/3} with a reason attached;</li>
     *   <li><b>B</b> the world agrees: no course is standing. Without it A is a report about a
     *       return value and not about a staircase;</li>
     *   <li><b>C</b> <b>the fix</b> — the decision hands back a cell to step aside to. Before the
     *       fix this is null and the arm goes red here;</li>
     *   <li><b>D</b> that cell is a real stand: inside the corridor, off the flight's footprint, not
     *       the cell the body is already in;</li>
     *   <li><b>E, F, G the controls</b> — the same pass with the step-aside already spent, a
     *       {@code REFUSED} pass, and an {@code OUT_OF_REACH} pass all get nothing. These are what
     *       stop C being satisfied by a decision that says yes to everything, and E is
     *       {@code cell.9.ramp.step.2}/{@code #2}: the same cell refused from two stands, where
     *       walking really did change nothing;</li>
     *   <li><b>H, I</b> after the step aside the production loop lays the whole flight, and the
     *       WORLD is holding every course up.</li>
     * </ol>
     */
    private static void stepsAsideWhenTheBodyIsInItsOwnStep(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx);

        Set<BlockPos> corridor = corridor(ctx);
        BlockPos landing = landing(ctx);
        int floorY = JourneyRamp.floorOf(corridor);
        List<BlockPos> flight = JourneyRamp.planKeeping(level, corridor, floorY, landing, false);
        if (flight == null || flight.isEmpty())
            ctx.fail("THE RIG, not the subject: 这个壁龛里规划器修不出通往 " + landing.toShortString()
                    + " 的楼梯，那下面量的就不是「身体挡住了第一级」");
        BlockPos bottom = flight.get(0).below();
        ctx.record("flight", flight.size() + " 级：" + supports(flight)
                + "（壁龛地板 y=" + floorY + "，落脚格 " + landing.toShortString() + "）");

        ServerWorldDriver driver = body(ctx, bottom);
        ServerPlayer fp = driver.fakePlayer();
        ctx.record("staged", "身体 " + fp.blockPosition().toShortString()
                + " 就站在这道楼梯自己的第一级垫脚 " + bottom.toShortString()
                + " 里 —— 这是规划器选的格，不是本文件挑的");
        if (!fp.blockPosition().equals(bottom))
            ctx.fail("THE RIG, not the subject: 身体没落在 " + bottom.toShortString() + "，落在 "
                    + fp.blockPosition().toShortString() + " —— 前提就没摆出来");

        // ---- A/B: one pass, from where the raise left the body ----
        Map<String, Object> rows = new LinkedHashMap<>();
        JourneyRamp.Pass first = JourneyRamp.layWhereItStands(level, fp, driver.avatar(), corridor,
                flight, 0, rows::put, "pass1");
        for (Map.Entry<String, Object> e : rows.entrySet()) ctx.record(e.getKey(), e.getValue());
        ctx.record("pass1", first.laid() + "/" + flight.size() + " 级，停在 " + first.stop()
                + (first.at() == null ? "" : " " + first.at().toShortString()));
        ctx.check(first.laid()).as("A 第一趟一级也垫不了 —— 这就是 cast9.ramp.laid = 0/3").isEqualTo(0);
        ctx.check(first.stop()).as("A 而且说得出为什么：身体自己压在 " + bottom.toShortString()
                + " 里，不是放不下").isEqualTo(JourneyRamp.Stop.BODY_IN_THE_WAY);
        ctx.check(first.at()).as("A 停在的那一格就是第一级的垫脚").isEqualTo(bottom);
        ctx.check(standing(level, flight)).as("B 世界里一级台阶也没有 —— 否则 A 说的是返回值，"
                + "不是楼梯").isEqualTo(0);

        // ---- C/D: the fix ----
        BlockPos aside = JourneyRamp.stepAsideFor(level, fp, corridor, flight, first, 0, false);
        ctx.record("aside", aside == null ? "没有（修复前就是这个答案，这一级到此为止）"
                : aside.toShortString() + "（施工位：实底、头脚都空、不在足迹上）");
        ctx.check(aside).as("C 修复：一趟没垫且原因是身体自己挡着，就该给出一个挪开的落脚格 —— "
                + "修复前这里是 null，整道楼梯 0/" + flight.size() + " 级").isNotNull();
        if (aside != null) {
            boolean onFlight = false;
            for (BlockPos s : flight)
                if (s.equals(aside) || s.above().equals(aside) || s.below().equals(aside)
                        || s.below(2).equals(aside)) onFlight = true;
            ctx.check(corridor.contains(aside)).as("D 挪去的格子在壁龛里").isTrue();
            ctx.check(onFlight).as("D 而且不在这道楼梯自己的足迹上 —— 挪到台阶上等于换一格继续挡")
                    .isFalse();
            ctx.check(aside.equals(bottom)).as("D 也不是身体现在这一格").isFalse();
            ctx.check(level.getBlockState(aside.below()).blocksMotion())
                    .as("D 脚下要有实底，否则那是一格空中").isTrue();
        }

        // ---- E/F/G: the controls that keep C from being「永远给一格」 ----
        ctx.check(JourneyRamp.stepAsideFor(level, fp, corridor, flight, first, 0, true))
                .as("E 只挪这一次：同一趟的第二次问，什么也不给 —— 不然这就是一个没有上界的重试")
                .isNull();
        JourneyRamp.Pass refused = new JourneyRamp.Pass(0, JourneyRamp.Stop.REFUSED, bottom);
        ctx.check(JourneyRamp.stepAsideFor(level, fp, corridor, flight, refused, 0, false))
                .as("F 放不下就是放不下 —— cell.9.ramp.step.2 / #2 在相隔九格的两个位置上"
                        + "得到同一句「六邻没有能贴的实心面」，走一趟改变不了六个空邻居").isNull();
        JourneyRamp.Pass reach = new JourneyRamp.Pass(0, JourneyRamp.Stop.OUT_OF_REACH, bottom);
        ctx.check(JourneyRamp.stepAsideFor(level, fp, corridor, flight, reach, 0, false))
                .as("G 够不着也一样：approach 已经把身体放在最近的施工位上了，"
                        + "再走回同一格是没有新信息的重试").isNull();

        // ---- H/I: the loop finishes the job from the cell the decision named ----
        Loop run = aside == null ? null
                : driveTheLoop(ctx, driver, corridor, flight, 0, false, "loop");
        ctx.record("loop.trace", run == null ? "没跑（C 已经红了）" : String.join("；", run.trace()));
        ctx.record("loop.standing", (run == null ? 0 : standing(level, flight)) + "/" + flight.size()
                + " 级在世界里立着");
        ctx.check(run == null ? null : run.last().stop())
                .as("H 挪开之后同一个循环把整道楼梯垫完").isEqualTo(JourneyRamp.Stop.FINISHED);
        ctx.check(standing(level, flight)).as("I 而且是世界里真的立着 " + flight.size()
                + " 级，不是循环自己说的").isEqualTo(flight.size());
    }

    // --------------------------------------------- what the flight was holding up ----

    /**
     * <b>The pinned tower's drift into the flooded floor row is a consequence of the flight laying
     * nothing, not a second defect.</b>
     *
     * <p>The run's own chain, after {@code cast9.ramp.laid = 0/3}: the raise fell short, the tower
     * drifted, {@code climb.0.driftInto.1 = 2, 56, 20} walked the body DOWN to the alcove floor, and
     * {@code climb.12.afloat = 2, 56, 18 浮在水里，8 次都没落地} — a tower cannot start in water, so
     * the raise went to its {@code YLevel} fallback and ended in the wrong column. The question the
     * chain leaves open is whether {@link JourneyShaft#footholdInColumn} choosing a submerged cell is
     * its own bug. It is not: that column had exactly one cell with anything solid under it, because
     * the flight that would have given it three was the one that laid nothing.
     *
     * <p>Measured here rather than argued, on the production function, before and after the
     * production loop runs. The flood is one cell — the landing column's floor — because the claim
     * is about which cell {@code footholdInColumn} returns, not about how far the water spread.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>THE RIG</b> the step-aside stand is in a different column from the landing, so the
     *       arm is staging「the drift has to walk back to another column」and not a body already in
     *       it;</li>
     *   <li><b>A</b> before the flight, that column's only foothold is the alcove floor row;</li>
     *   <li><b>B</b> and it is under water — which is what {@code afloat} reports and what a tower
     *       cannot start from;</li>
     *   <li><b>C</b> after the production loop lays the flight, the same call returns a cell higher
     *       up;</li>
     *   <li><b>D</b> that is dry;</li>
     *   <li><b>E</b> and it is a cell of the flight — so the thing that raised the foothold is the
     *       staircase, which is the whole claim.</li>
     * </ol>
     */
    private static void footholdRisesWithTheFlightItLaid(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        stage(ctx);

        Set<BlockPos> corridor = corridor(ctx);
        BlockPos landing = landing(ctx);
        int floorY = JourneyRamp.floorOf(corridor);
        List<BlockPos> flight = JourneyRamp.planKeeping(level, corridor, floorY, landing, false);
        if (flight == null || flight.isEmpty())
            ctx.fail("THE RIG, not the subject: 修不出楼梯，下面量的就不是「楼梯把落脚点抬高了」");
        BlockPos bottom = flight.get(0).below();
        ctx.record("flight", flight.size() + " 级：" + supports(flight));

        ServerWorldDriver driver = body(ctx, bottom);
        ServerPlayer fp = driver.fakePlayer();
        BlockPos aside = JourneyRamp.stepAsideFor(level, fp, corridor, flight,
                JourneyRamp.layWhereItStands(level, fp, driver.avatar(), corridor, flight, 0,
                        (k, v) -> { }, "probe"),
                0, false);
        if (aside == null)
            ctx.fail("THE RIG, not the subject: 没有施工位，那这一臂根本走不到「垫完之后」");
        if (aside != null && aside.getX() == landing.getX() && aside.getZ() == landing.getZ())
            ctx.fail("THE RIG, not the subject: 施工位 " + aside.toShortString()
                    + " 跟落脚柱同一柱 —— 那量的不是「漂移要走回另一柱」");

        // ---- A/B: the column before the flight, flooded ----
        BlockPos flooded = new BlockPos(landing.getX(), floorY, landing.getZ());
        level.setBlockAndUpdate(flooded, Blocks.WATER.defaultBlockState());
        BlockPos before = JourneyShaft.footholdInColumn(level, landing.getX(), landing.getZ(),
                landing.getY());
        ctx.record("before", (before == null ? "这一柱没有一格站得住" : before.toShortString())
                + "（从 y=" + (landing.getY() + 1) + " 往下找，指定柱 " + landing.getX() + ","
                + landing.getZ() + "）");
        ctx.check(before).as("A 垫之前，这一柱唯一站得住的就是壁龛地板那一排 "
                + flooded.toShortString() + " —— 楼梯没垫，柱子里就没有别的实底")
                .isEqualTo(flooded);
        ctx.check(before != null && !level.getFluidState(before).isEmpty())
                .as("B 而那一格在水里 —— 这就是 climb.12.afloat「浮在水里，8 次都没落地」，"
                        + "塔在水里起不来").isTrue();

        // ---- C/D/E: after the production loop ----
        Loop run = driveTheLoop(ctx, driver, corridor, flight, 0, false, "loop");
        ctx.record("loop.trace", String.join("；", run.trace()));
        BlockPos after = JourneyShaft.footholdInColumn(level, landing.getX(), landing.getZ(),
                landing.getY());
        ctx.record("after", (after == null ? "这一柱没有一格站得住" : after.toShortString())
                + "，世界里立着 " + standing(level, flight) + "/" + flight.size() + " 级");
        ctx.check(after == null ? null : after.getY())
                .as("C 垫完之后，同一个调用给的是更高的一格（之前 y=" + floorY + "）")
                .isGreaterThan(floorY);
        ctx.check(after != null && level.getFluidState(after).isEmpty())
                .as("D 而且是干的 —— 漂移走回来能站住，塔起得来").isTrue();
        ctx.check(after != null && flight.contains(after))
                .as("E 那一格就是这道楼梯的一级 " + supports(flight)
                        + " —— 把落脚点抬起来的正是垫台阶这件事，所以水里那一格是 0/"
                        + flight.size() + " 的后果，不是另一个缺陷").isTrue();
    }

    // ------------------------------------------------- the flight that sealed itself ----

    /** The fold arm's alcove: two ranks in x, three in z, six tall, floor on {@link #BASE}.
     *
     * <p><b>Narrower in z than a generated corridor on purpose.</b> The descent from the landing runs
     * NORTH twice and then wants a third; {@code dz = -3} is rock, so the third does not exist and the
     * only continuation the old planner had left was to double back. That missing cell IS the
     * fixture — a five-wide alcove has the straight route and never asks the question. */
    private static Set<BlockPos> foldCorridor(SceneContext ctx) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int dx = -1; dx <= 0; dx++)
            for (int dz = -2; dz <= 0; dz++)
                for (int dy = BASE; dy <= BASE + 5; dy++)
                    cells.add(ctx.rel(dx, dy, dz));
        return Set.copyOf(cells);
    }

    /** Where this flight's feet have to end up. Four courses above the floor, which is what rung 12's
     *  {@code wet.8} asked for. */
    private static BlockPos foldLanding(SceneContext ctx) { return ctx.rel(0, BASE + 4, 0); }

    /**
     * The first support of this flight that stands in another course's cell or head room — or null
     * when every course is enterable.
     *
     * <p>The PROPERTY, checked over all pairs, deliberately not the rule
     * {@link JourneyRamp} implements. The rule is「a course may not double back on the one two
     * below」and it is a derivation; if the derivation is wrong this must still catch it. A flight
     * that satisfies this is one the body can walk up, because the only thing that can seal a step
     * of a staircase built entirely out of this flight's own blocks is another block of it.
     */
    private static BlockPos sealedBy(List<BlockPos> flight) {
        for (int i = 0; i < flight.size(); i++) {
            BlockPos support = flight.get(i).below();
            for (int j = 0; j < flight.size(); j++) {
                if (j == i) continue;
                BlockPos stand = flight.get(j);
                if (support.equals(stand) || support.equals(stand.above())) return support;
            }
        }
        return null;
    }

    /**
     * A flight may not fill the head room of a course below it — and the body must be able to walk up
     * the one that does not.
     *
     * <h2>The run</h2>
     *
     * <p>Ladder {@code journey-n3}, 2026-08-25, rung 12, cell {@code wet.8}. The planner searched down
     * from {@code 3,60,20} NORTH, NORTH, SOUTH and returned stands {@code 3,57,19 → 3,58,18 →
     * 3,59,19 → 3,60,20}. Course 2's support is {@code 3,58,19} and course 0's head room is
     * {@code 3,57,19.above()} — the same cell. Every placement then landed:
     *
     * <pre>
     * [place] 成功 点击格=3, 55, 19 面=up    邻格=3, 56, 19→cobblestone 身体y=56.000 结果=SUCCESS
     * [place] 成功 点击格=3, 57, 17 面=south 邻格=3, 57, 18→cobblestone 身体y=56.000 结果=SUCCESS
     * [place] 成功 点击格=4, 58, 19 面=west  邻格=3, 58, 19→cobblestone 身体y=56.000 结果=SUCCESS
     * [place] 成功 点击格=3, 58, 19 面=south 邻格=3, 58, 20→cobblestone 身体y=56.000 结果=SUCCESS
     * [place] 成功 点击格=3, 58, 20 面=up    邻格=3, 59, 20→cobblestone 身体y=56.000 结果=SUCCESS
     * [pathfinder] search-begin owner=goto start=3, 56, 20 goal=BlockPos{x=3, y=60, z=20}
     * [walker] 步进: w=2, 56, 20 nx=1, 57, 20 …
     * </pre>
     *
     * <p>The staircase was complete and the {@code goto} onto its top step walked WEST out of the
     * alcove and finished on the surface at {@code -5,65,20}, 9.85 blocks from the cell it was sent
     * to; three retries never came back to the column. <b>A* was right.</b> The body cannot enter
     * course 0 — its head is under course 2 — so there is no route up this flight, and the rung then
     * poured every remaining cast from a column nobody had verified.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>A THE RIG</b> with folds allowed this alcove yields a flight — otherwise B is
     *       measuring an alcove with no staircase rather than a rule;</li>
     *   <li><b>A</b> and that flight really does seal itself. <b>Without this the arm is 0==0</b>: a
     *       staging that cannot reproduce the run's own geometry would let any planner pass;</li>
     *   <li><b>B</b> with the rule on, a flight still exists — the rule must not answer「no route」to
     *       an alcove that has one, which is the failure mode of checking on the way back out;</li>
     *   <li><b>B</b> and no course of it stands in another's cell or head room;</li>
     *   <li><b>C</b> the production loop builds it, and the WORLD holds every course up — so B is
     *       about a staircase and not about a list;</li>
     *   <li><b>D</b> a real body, walked by the real {@link Walker} from the alcove floor, ENDS ON
     *       the top step. Not「the walker returned ARRIVED」— that is the reading rung 12 was already
     *       getting from a body nine blocks away.</li>
     * </ol>
     */
    private static void neverFoldsBackIntoItsOwnHeadroom(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);
        Set<BlockPos> corridor = foldCorridor(ctx);
        stage(ctx, corridor);

        BlockPos landing = foldLanding(ctx);
        int floorY = JourneyRamp.floorOf(corridor);

        // ---- A: the negative control, measured on this staging rather than argued ----
        List<BlockPos> folded = JourneyRamp.planKeeping(level, corridor, floorY, landing, false, true);
        ctx.record("folded", folded == null ? "修不出楼梯"
                : folded.size() + " 级：" + supports(folded));
        ctx.check(folded).as("A THE RIG: 放开折返之后这个壁龛必须修得出楼梯到 "
                + landing.toShortString() + " —— 修不出的话 B 量的是「壁龛没有楼梯」，不是规则")
                .isNotNull();
        BlockPos foldSeal = folded == null ? null : sealedBy(folded);
        ctx.record("folded.seal", foldSeal == null ? "没有哪一级压住别的级"
                : foldSeal.toShortString() + " 既是某一级的垫脚，又是下面某一级的落脚格或头顶格");
        ctx.check(foldSeal).as("A THE RIG: 而且那条路必须真的自封 —— 这个布景要复现的就是 wet.8 的"
                + "「NORTH, NORTH, SOUTH」，复现不出来，下面全是 0==0："
                + (folded == null ? "null" : supports(folded))).isNotNull();

        // ---- B: the subject ----
        List<BlockPos> flight = JourneyRamp.planKeeping(level, corridor, floorY, landing, false);
        ctx.record("flight", flight == null ? "修不出楼梯" : flight.size() + " 级：" + supports(flight));
        ctx.check(flight).as("B 禁掉折返之后仍然修得出楼梯 —— 这条路在选方向那一刻就被排除，"
                + "子级还剩另外三个方向可走；要是改成事后否决，整棵子树会被连根丢掉，"
                + "本来有解的壁龛会被判成无解").isNotNull();
        if (flight == null) return;
        ctx.check(sealedBy(flight)).as("B 而且这一条没有任何一级把别的级的落脚格或头顶格垫死："
                + supports(flight)).isNull();
        ctx.check(flight.get(flight.size() - 1)).as("B 顶级还是要送到指定的落脚格").isEqualTo(landing);

        // ---- C: build it for real ----
        // The one floor cell beside the bottom support — taken FROM the flight rather than named
        // again, so the arm cannot end up building one staircase and climbing beside another.
        BlockPos entry = flight.get(0).below().relative(Direction.SOUTH);
        ServerWorldDriver driver = body(ctx, entry);
        ServerPlayer fp = driver.fakePlayer();
        Loop run = driveTheLoop(ctx, driver, corridor, flight, 0, false, "lay");
        ctx.record("lay.trace", String.join("；", run.trace()));
        ctx.record("lay.standing", standing(level, flight) + "/" + flight.size()
                + " 级立在世界里（收在 " + run.last().stop() + "）");
        ctx.check(standing(level, flight)).as("C 世界里每一级都立着 —— 否则 D 走不上去说的是"
                + "「没垫完」，不是「垫完了走不上去」").isEqualTo(flight.size());

        // ---- D: and walk it, judged by where the body ENDS ----
        placeAt(driver, entry);
        ctx.record("walk.from", fp.blockPosition().toShortString() + " → " + landing.toShortString());
        if (!fp.blockPosition().equals(entry))
            ctx.fail("THE RIG, not the subject: 身体没落在起步格 " + entry.toShortString()
                    + "，落在 " + fp.blockPosition().toShortString());
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(landing));
        LevelWorldView view = new LevelWorldView(level, fp);
        BlockPos best = fp.blockPosition();
        for (int t = 0; t < WALK_TICKS && !fp.blockPosition().equals(landing); t++) {
            walker.tick(driver.avatar(), view);
            driver.avatar().step();
            if (fp.blockPosition().getY() > best.getY()) best = fp.blockPosition();
        }
        ctx.record("walk.end", fp.blockPosition().toShortString() + "（最高爬到 "
                + best.toShortString() + "，" + WALK_TICKS + " tick 预算）");
        ctx.check(fp.blockPosition()).as("D 身体自己走上了顶级台阶 " + landing.toShortString()
                + " —— 判的是身体停在哪，不是 walker 返回了什么：真梯上那具身体报的是走完了，"
                + "人停在九格外的地表上").isEqualTo(landing);
    }

    /** How long the climb gets. Four courses at one cell each; the budget is loose enough that a
     *  failure here is「cannot」and not「not yet」. */
    private static final int WALK_TICKS = 300;

    // ------------------------------------------ the face that exists and cannot be hit ----

    /**
     * A solid neighbour is not a face this body can click, and {@code whyNotLaid} must say both.
     *
     * <h2>The row that could not make its own distinction</h2>
     *
     * <p>Ladder {@code journey-n3}, rung 12: {@code wet.8.ramp.step.3 = 3, 59, 20 垫不上（…可贴的面
     * down），身体 3, 56, 20}. The comment above that code says it exists so that「没有面可点」and
     * 「点了却被拒」stop reading alike — and it asked {@code blocksMotion()} on the neighbour and
     * nothing else. The support's only solid neighbour was the block directly BELOW it, whose top
     * face cannot be seen from underneath, so the row reported a face the body could never click and
     * a reader ruled out the one cause that was live.
     *
     * <h2>Why this arm drives the predicate and not the row</h2>
     *
     * <p>{@code whyNotLaid} runs only on {@link JourneyRamp.Stop#REFUSED}, and a server-side body in
     * a staged alcove places successfully — the suite emits the row <b>zero</b> times, measured on
     * {@code gate-j68.log}. So the row's wiring stays read-by-diff and what is driven here is
     * {@link JourneyRamp#canClick}, the part that can be wrong: the face-normal convention. A ruler
     * nobody calibrated is how a false positive gets to break working code.
     *
     * <h2>判据</h2>
     *
     * <ol>
     *   <li><b>A</b> the face EXISTS from both stands — {@code cell.below()} is solid either way.
     *       Without it B is satisfied by an alcove with no face at all;</li>
     *   <li><b>B</b> from directly underneath, the body cannot click it: a ray up the column stops
     *       on that block's BOTTOM face, which is a different face of the same block;</li>
     *   <li><b>C the control</b> — the same cell, the same face, a stand level with it: clickable.
     *       This is what stops B being satisfied by a predicate that answers no to everything, and
     *       it is the whole claim in one line: <b>the answer is a property of the body, not of the
     *       world</b>.</li>
     * </ol>
     */
    private static void separatesAFaceThatExistsFromOneItCanHit(SceneContext ctx) {
        ServerLevel level = ctx.level();
        config(ctx);

        Set<BlockPos> corridor = new LinkedHashSet<>();
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 2; dz++)
                for (int dy = BASE; dy <= BASE + 5; dy++)
                    corridor.add(ctx.rel(dx, dy, dz));
        stage(ctx, corridor);

        // The cell a step would go into, and the ONE solid neighbour it has: the block under it.
        BlockPos cell = ctx.rel(0, BASE + 3, 0);
        BlockPos under = cell.below();
        ctx.setBlock(0, BASE + 2, 0, Blocks.STONE);
        // Floor for the level stand, two cells along and out of `cell`'s six neighbours.
        ctx.setBlock(0, BASE + 2, 2, Blocks.STONE);

        BlockPos below = ctx.rel(0, BASE, 0);          // directly under the cell, as on the ladder
        BlockPos alongside = ctx.rel(0, BASE + 3, 2);  // level with it, two cells away

        int solidFaces = 0;
        for (Direction d : Direction.values())
            if (level.getBlockState(cell.relative(d)).blocksMotion()) solidFaces++;
        ctx.record("rig", cell.toShortString() + " 的六邻里 " + solidFaces + " 个是实心的，唯一那个是 "
                + under.toShortString() + "（要点的是它的顶面）");
        ctx.check(solidFaces).as("THE RIG: 这一格必须恰好只有一个实心邻居，否则 B/C 说的是别的面")
                .isEqualTo(1);

        ServerWorldDriver driver = body(ctx, below);
        ServerPlayer fp = driver.fakePlayer();
        boolean solidFromBelow = level.getBlockState(under).blocksMotion();
        boolean hitFromBelow = JourneyRamp.canClick(level, fp,
                JourneySight.eyeFor(fp, fp.blockPosition()), cell, Direction.DOWN);
        ctx.record("below", "身体 " + fp.blockPosition().toShortString() + "（眼睛 "
                + fp.getEyePosition() + "）：有实心面 " + solidFromBelow
                + "，射得到 " + hitFromBelow);

        placeAt(driver, alongside);
        if (!fp.blockPosition().equals(alongside))
            ctx.fail("THE RIG, not the subject: 身体没落在齐平站位 " + alongside.toShortString()
                    + "，落在 " + fp.blockPosition().toShortString());
        boolean solidAlongside = level.getBlockState(under).blocksMotion();
        boolean hitAlongside = JourneyRamp.canClick(level, fp,
                JourneySight.eyeFor(fp, fp.blockPosition()), cell, Direction.DOWN);
        ctx.record("alongside", "身体 " + fp.blockPosition().toShortString() + "（眼睛 "
                + fp.getEyePosition() + "）：有实心面 " + solidAlongside
                + "，射得到 " + hitAlongside);

        ctx.check(solidFromBelow && solidAlongside)
                .as("A 两个站位都看得见这个实心面 —— 面是存在的，两张表的左边一栏一样").isTrue();
        ctx.check(hitFromBelow).as("B 但正下方那具身体点不到它：射线上去先撞的是同一块的底面，"
                + "这就是 wet.8.ramp.step.3 里那句「可贴的面 down」瞒住的事").isFalse();
        ctx.check(hitAlongside).as("C 齐平站位点得到 —— 同一格、同一个面，答案却相反，"
                + "所以这条谓词问的是身体，不是世界；没有这一条，B 可以靠一个永远说不的谓词满足")
                .isTrue();
    }
}
