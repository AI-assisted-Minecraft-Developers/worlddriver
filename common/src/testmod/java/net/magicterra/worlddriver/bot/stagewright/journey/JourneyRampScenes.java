package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.sim.ServerAvatarManager;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.sim.ServerWorldDriver;
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
                        JourneyRampScenes::stepsAsideWhenTheBodyIsInItsOwnStep).withRequired(false),
                Scene.of("wd.rampFootholdRisesWithTheFlightItLaid", 200,
                        JourneyRampScenes::footholdRisesWithTheFlightItLaid).withRequired(false));
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
        clearBox(ctx);
        for (int dx = -2; dx <= 3; dx++)
            for (int dz = -4; dz <= 4; dz++)
                for (int dy = BASE - 2; dy <= BASE + 8; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
        for (BlockPos c : JourneyForge.corridor(at(ctx), AWAY, PUSH))
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
        ServerWorldDriver driver = ServerWorldDriver.createIsolated(ctx.level(),
                foot.getX() + 0.5, foot.getY(), foot.getZ() + 0.5);
        ServerPlayer fp = driver.fakePlayer();
        ctx.cleanup(() -> { ServerAvatarManager.unregister(driver); fp.discard(); });
        fp.getInventory().clearContent();
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
}
