package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.movement.Walker;
import net.magicterra.worlddriver.bot.movement.WalkerGeometry;
import net.magicterra.worlddriver.bot.sim.ServerPlayerAvatar;
import net.magicterra.worlddriver.bot.world.LevelWorldView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * <b>A hop judged while the body was one tick above the floor.</b>
 *
 * <h2>The run this is a copy of</h2>
 *
 * Rung 14's Nether crossing is healthy — 11.4 tick/block, 1% of its ticks without a plan, 2 968 of
 * a 21 600-tick hop budget spent, 402 blocks to walk. It stopped 141 blocks short, and this is the
 * row that stopped it:
 *
 * <pre>
 * fortress.crossing = 6 段，还差 141 格 …… 第 6 段之后停手：身体还在下坠
 *                     （179, 43, 198，落速 -0.38 格/tick，脚下到实心 0 格）
 * fortress.around.6 = 脚下=netherrack …… onGround=false 落速=-0.38 脚下到实心=0
 * </pre>
 *
 * <p><b>脚下到实心 0.</b> The body was not in a chasm; it was a hair above netherrack, mid-landing,
 * and would have been standing on it on the next tick. {@code hazardBlockingARetry} is right that a
 * falling body is not somewhere a walk order can act on — it is wrong to take that reading from a
 * body it never let finish falling. Eighteen of twenty-four hops and 16 200 hop ticks went unspent
 * over one tick of patience.
 *
 * <h2>What put the body in the air, and why the walker is not the defect</h2>
 *
 * The same leg's own physics dumps, verbatim:
 *
 * <pre>
 * fortress.ground.6.0 = 上一 tick：位置 (166.653, 57.0000, 177.409) 速度 (-0.007, -0.078, 0.111)
 *   onGround=true …… vanilla 自己那一问（脚下 0.0784 格内有碰撞吗）=没有（和 onGround 不一致）
 *   实心接触面积 0.0000/0.36 …… 致命边刹车照 level 重算 …… → 不该响
 * </pre>
 *
 * <p>{@code onGround} was indeed a tick stale — vanilla's own sweep disagreed with it. But nothing
 * in the walker steers on that flag: {@link Walker#footingGuard} and {@link Walker#strideFloorGuard}
 * both open on {@link WalkerGeometry#soleOnSolid}, which read {@code 0.0000} on the same tick, and
 * both were silent for the reason the row spells out — <b>the drops were 4 and 8 blocks</b>, against
 * a lethal line of {@code survivableFall(20) = 22}. Teaching them to refuse those strides is not a
 * fix, it is the failure {@code wd.serverWalksOffASurvivableLedge} was committed to catch: a guard
 * that pins at every lip turns a Nether crossing, which is nothing but lips, into a wall.
 *
 * <p>So this pair asks the walker for nothing at all. It walks a body off a survivable lip with the
 * guards at their live settings, records that they stayed out of the way (that reading is the
 * measurement, not an assertion — the shore pair owns that), and then asks the CROSSING's verdict
 * the two questions it gets wrong and right.
 *
 * <h2>Two arms, one variable: how far it is to the floor</h2>
 *
 * <ul>
 *   <li>{@code wd.crossingWaitsOutASurvivableDrop} — a four-block step-down, the fall-#6 geometry.
 *       The body lands well inside {@link JourneyNetherRungs#LANDING_TICKS} and the verdict must
 *       come back clean, so the crossing spends its remaining hops.</li>
 *   <li>{@code wd.crossingStillStopsForALongFall} — the same bay from thirty-nine blocks up. The
 *       allowance runs out with the body still in the air and the verdict must STILL stop the
 *       crossing. Without this arm,「the verdict now clears」would be satisfied by deleting the
 *       branch, and a rung that walks its next plan from a body in free fall is the retry that
 *       changes nothing this rung already has a name for.</li>
 * </ul>
 *
 * <h2>Each arm carries its own control</h2>
 *
 * The first arm takes the verdict TWICE over one fall: once at the instant the leg would have ended
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
 * is a {@link JourneyRig}: it is entered against the ladder's own ledger and drives a static body,
 * so {@code settleToGround}'s tick pump is stepped here instead, with the impulse released first,
 * which is what {@link HoldStill} does and the reason the crossing waits under it rather than under
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
                Scene.of("wd.crossingWaitsOutASurvivableDrop", 400,
                        JourneyCrossingScenes::waitsOutASurvivableDrop),
                Scene.of("wd.crossingStillStopsForALongFall", 400,
                        JourneyCrossingScenes::stillStopsForALongFall),
                Scene.of("wd.crossingRowSeparatesAPerchFromMidAir", 400,
                        JourneyCrossingScenes::rowSeparatesAPerchFromMidAir),
                Scene.of("wd.guardRepathSeparatesARimWalkFromALivelock", 600,
                        JourneyCrossingScenes::repathSeparatesARimWalkFromALivelock));
    }

    /** dy of the shelf's top block. The body's foot cell is one above it. */
    private static final int DECK = 6;

    /** Cells of shelf along +z, from {@code dz = -2}. The lip is the last of them. */
    private static final int SHELF_CELLS = 9;

    /** dy of the bay floor's top block. Four rows under the deck, so the step down from foot cell to
     *  foot cell is FOUR — the drop {@code fortress.fell.6.0} measured, and far under the
     *  {@code survivableFall(20) = 22} line, which is what keeps both walker guards out of this
     *  arena by their own rules rather than by a switch. */
    private static final int BAY_BED = DECK - 4;

    /** dy the long-fall arm starts its body at. Thirty-nine rows over the bay floor's standing cell,
     *  so the body is still in the air when the allowance expires: 26 ticks of gravity cover 23.4
     *  blocks and the run-up to {@code stillFalling} costs four more. */
    private static final int DEEP_START = 42;

    /** Idle ticks before a drive, so a body that vanilla itself cannot hold up says so before the
     *  measurement rather than during it. */
    private static final int SETTLE_TICKS = 20;

    /** Physics ticks the walk out to the lip gets. The shelf is nine cells and a walk covers about
     *  one every five ticks, so this is roughly triple what a healthy walk-off needs. */
    private static final int WALK_TICKS = 160;

    /**
     * A body walked off a four-block lip, judged twice: as the crossing used to, and as it does now.
     *
     * <p>See the class note. The control is the first verdict; if it comes back clean this arena
     * never reproduced the stop and the arm says so instead of passing.
     */
    private static void waitsOutASurvivableDrop(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        // The guards keep their live values — this arm is a claim about them staying out of the way,
        // and an arm that switched them off could not make it. Placement is off because a plug under
        // the body would change the geometry the arms differ in; the shore pair owns that question.
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ctx.cleanup(() -> clear(ctx));

        stage(ctx);
        ctx.record("rig", "3 格宽下界岩台 dz=-2.." + (SHELF_CELLS - 2) + "，顶面 dy=" + DECK
                + "；越过台缘落到 dy=" + BAY_BED + " 的湾底，落差 " + (DECK - BAY_BED)
                + " 格 —— 满血能扛 22 格，所以这座场地里两个 walker 守卫都该按自己的规矩闭嘴，"
                + "而不是被开关关掉");

        ServerPlayerAvatar av = spawn(ctx, ctx.originZ() + 1.5, DECK + 1, true);
        ServerPlayer fp = av.fakePlayer();
        Walk walk = walkOffTheLip(ctx, av);
        ctx.record("walk", walk.line());
        if (!walk.leftTheGround())
            ctx.fail("THE RIG, not the subject: 身体没走下台缘（" + walk.line()
                    + "） —— 这一臂要判的是「离地之后怎么判决」，身体没离地就什么都没量到");
        if (!JourneyNetherRungs.stillFalling(fp))
            ctx.fail("THE RIG, not the subject: 走下去了但从来没进入「还在下坠」这个状态（"
                    + walk.line() + "） —— 判决那一条分支根本没被触发");

        // CONTROL: the verdict oneHop used to take, straight off a body still in the air.
        BlockPos airborneAt = fp.blockPosition();
        String judgedNow = JourneyNetherRungs.hazardBlockingARetry(fp, airborneAt);
        ctx.record("control.judgedInMidAir", judgedNow == null
                ? "没有障碍 —— 这一臂什么都没测到" : judgedNow);
        if (judgedNow == null)
            ctx.fail("THE RIG, not the subject: 身体还在半空中，判决却说没有障碍 —— "
                    + "那么「等落地之后判决放行」这条判据分不清「等待起了作用」和"
                    + "「这座场地本来就不会停手」：" + walk.line());

        Allowance spent = allowanceToLand(ctx, av, "subject");
        String judgedAfter = JourneyNetherRungs.hazardBlockingARetry(fp, fp.blockPosition());
        ctx.record("subject.judgedAfterLanding", judgedAfter == null ? "没有障碍（放行）" : judgedAfter);
        ctx.record("subject.after", where(ctx, fp));

        ctx.check(judgedAfter).as("A 给完落地余量之后，这一段必须可以被判决 —— 对照臂在半空中判到的是「"
                + judgedNow + "」，落地前最后一 tick 判到的是「" + spent.lastMidAir() + "」").isNull();
        ctx.check(spent.landedAt() >= 1 && spent.landedAt() <= JourneyNetherRungs.LANDING_TICKS)
                .as("B 而且余量必须够用：" + (DECK - BAY_BED) + " 格的落差应当在 "
                        + JourneyNetherRungs.LANDING_TICKS + " tick 之内落地，实测第 "
                        + spent.landedAt() + " tick（-1 = 一直没落地）").isTrue();
        ctx.check(blockUnder(ctx, fp)).as("C 而且是站在湾底那层下界岩上，不是停在别的什么东西上："
                + where(ctx, fp)).isEqualTo("netherrack");
    }

    /**
     * The same bay from thirty-nine blocks up: the allowance expires and the verdict must still stop.
     *
     * <p>Staged as a drop rather than as a walk-off on purpose. A lip this arm could walk off would
     * have to be a lethal one, and a lethal lip is a stride both walker guards are supposed to
     * refuse — the arm would end up measuring them instead of the allowance, and would fail for
     * being right about something else.
     */
    private static void stillStopsForALongFall(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ctx.cleanup(() -> clear(ctx));

        stage(ctx);
        ctx.record("rig", "同一座湾（湾底 dy=" + BAY_BED + "），身体从 dy=" + DEEP_START
                + " 开始下坠，共 " + (DEEP_START - (BAY_BED + 1)) + " 格；"
                + JourneyNetherRungs.LANDING_TICKS + " tick 的余量只够掉 23.4 格，所以余量花完时"
                + "身体还在空中 —— 这一臂问的是那时候判决还停不停手");

        // No idle settle: every tick of one is a tick of this arm's own fall, and twenty of them
        // spent 14 of the 39 blocks before the allowance ever started (measured — the first run of
        // this arm landed on allowance tick 16 and read as a broken premise).
        ServerPlayerAvatar av = spawn(ctx, ctx.originZ() + 12.5, DEEP_START, false);
        ServerPlayer fp = av.fakePlayer();
        int t = 0;
        while (t < 20 && !JourneyNetherRungs.stillFalling(fp)) { step(av); t++; }
        ctx.record("fall.began", "放下去 " + t + " tick 之后才算「在下坠」；" + where(ctx, fp));
        if (!JourneyNetherRungs.stillFalling(fp))
            ctx.fail("THE RIG, not the subject: 放下去 " + t + " tick 之后身体还没算「在下坠」（"
                    + where(ctx, fp) + "）");

        Allowance spent = allowanceToLand(ctx, av, "subject");
        String judgedAfter = JourneyNetherRungs.hazardBlockingARetry(fp, fp.blockPosition());
        ctx.record("subject.judgedAfterAllowance", judgedAfter == null
                ? "没有障碍（放行）" : judgedAfter);
        ctx.record("subject.after", where(ctx, fp));

        ctx.check(spent.landedAt()).as("A 这一臂的前提是余量不够用：身体不许在 "
                + JourneyNetherRungs.LANDING_TICKS + " tick 内落地，" + where(ctx, fp)).isEqualTo(-1);
        ctx.check(judgedAfter).as("B 余量花完身体还在下坠，判决必须照样停手 —— 否则下一段计划是"
                + "对着一具还在半空中的身体下的令，那正是这一级早就命过名的「换汤不换药的重试」")
                .isNotNull();
    }

    // ── the plan the guard throws away ───────────────────────────────────────────────────────

    /**
     * <b>A sustained pin discards the plan, and until now it did so in total silence.</b>
     *
     * <h2>The run this is a copy of</h2>
     *
     * Rung 14's 2026-08-20 shuttle: four hops of 900 ticks each, 61–76 walk edges apiece, net −8 to
     * −28 blocks, all four inside one 27×31 box. The terrain, read out of that run's own region
     * files, is a lava sea — 18 458 lava cells against 2 543 netherrack in the walk band — and the
     * only ground in it beyond one netherrack shelf is a <b>127-block dirt causeway the body built
     * itself</b>. The stride guard fired 491 times in that crossing across 184 distinct cells and
     * plugged 142 of them; only 5 cells ever reached the 12-fire plug dwell.
     *
     * <p>With {@link Walker#GUARD_PIN_HOLD} = 8, a fire every eight ticks keeps
     * {@code guardPinStreak} alive, and at ≥30 it throws the plan away. So two opposite diagnoses
     * fit every row that run produced — the body was given plans that route backwards, or the body
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
     *   <li>a LIVELOCK — the body held against one lip, pinning on the same cell, which is the
     *       567-pins-at-one-cell run the forced repath exists for;</li>
     *   <li>a RIM WALK — the body travelling along a lava shore, pinning on a new cell every few
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
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;      // paving the trench is another answer; the pin is the subject
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        BotConfig.lethalEdgeBrake = false; // isolate the STRIDE guard, as the shore/rim pairs do
        ctx.cleanup(() -> clearTrench(ctx));

        stageTrench(ctx);
        ctx.record("rig", "一条沿 z 通到底的岩浆沟（沟面 dy=" + (TRENCH_DECK - 4) + "，" + TRENCH_ROWS
                + " 层岩浆），东侧 dx≥" + TRENCH_EDGE + " 是一条 " + TRENCH_CELLS
                + " 格长的石台。两条臂踩同一条岸，唯一的自变量是身体准不准往前走");

        Pin walk = drivePin(ctx, "rimWalk", true);
        Pin lock = drivePin(ctx, "livelock", false);
        ctx.record("rimWalk", walk.line());
        ctx.record("livelock", lock.line());

        // THE CONTROL: both situations must actually reach the discard, or the arm has measured
        // nothing and must not report that the reading separated them.
        if (walk.repaths() < 1)
            ctx.fail("THE RIG, not the subject: 沿岸走那一臂一次都没触发强制重规划（" + walk.line()
                    + "） —— 没有事件就没有读数可比");
        if (lock.repaths() < 1)
            ctx.fail("THE RIG, not the subject: 原地卡死那一臂一次都没触发强制重规划（" + lock.line()
                    + "） —— 那么「沿岸走报了很多格」就分不清是读数在起作用还是这一臂根本没跑到");

        ctx.check(lock.cells()).as("A 原地卡死必须报成一格 —— 这正是这个计数器当初为之而生的那种情形："
                + lock.line()).isEqualTo(1);
        ctx.check(walk.cells() > lock.cells()).as("B 沿岸走必须报出比它多的格子 —— 否则这条线还是"
                + "把「走了九十格」和「卡在一格上」印成同一句话：沿岸走 " + walk.cells()
                + " 格，原地 " + lock.cells() + " 格").isTrue();
    }

    /** What one drive against the trench produced. */
    private record Pin(int ticks, int repaths, int cells, int pinnedTicks, double travelled) {
        String line() {
            return String.format(Locale.ROOT,
                    "%d tick，钉住 %d tick，强制重规划 %d 次，最后一次覆盖 %d 个不同格子，沿岸走了 %.2f 格",
                    ticks, pinnedTicks, repaths, cells, travelled);
        }
    }

    /**
     * Drive the trench once and report the discard the pin forced.
     *
     * <p>{@code travelling} is the arm's only variable. Both bodies stand on the same shore and
     * both are steered at the lava; the travelling one is also pushed along +z, so its stride cell
     * sweeps, while the other is held against one lip and pins on the same cell for as long as it
     * takes. Sneak is not re-imposed — it is the channel the pin uses and the thing being measured.
     */
    private static Pin drivePin(SceneContext ctx, String arm, boolean travelling) {
        stageTrench(ctx);
        ServerLevel level = ctx.level();
        int standY = ctx.rel(0, TRENCH_DECK + 1, 0).getY();
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level,
                ctx.originX() + TRENCH_EDGE + 0.5, standY, ctx.originZ() + 1.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        fp.getInventory().clearContent();
        // Face into the trench (−x) for the livelock, and RIM_LEAN for the rim walk so the body
        // also travels along +z — the heading the ladder's own rim pins were all measured on.
        fp.setYRot(travelling ? RIM_LEAN : 90f);
        fp.yHeadRot = fp.getYRot();
        fp.yBodyRot = fp.getYRot();
        for (int i = 0; i < SETTLE_TICKS; i++) step(av);

        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(ctx.rel(TRENCH_EDGE - 6, TRENCH_DECK + 1, TRENCH_CELLS - 2)));
        int before = Walker.guardForcedRepaths;
        double z0 = fp.getZ();
        int pinned = 0, t = 0;
        for (; t < PIN_TICKS; t++) {
            walker.tick(av, w);
            fp.setYRot(travelling ? RIM_LEAN : 90f);
            fp.yHeadRot = fp.getYRot();
            fp.yBodyRot = fp.getYRot();
            av.commandMove(0f, 1f);
            av.commandJump(false);
            if (av.dbgSneak()) pinned++;
            av.step();
            if (Walker.guardForcedRepaths - before >= 2) break;   // two is enough to read the line
        }
        int repaths = Walker.guardForcedRepaths - before;
        int cells = cellsIn(Walker.lastGuardRepath);
        ctx.record(arm + ".lastLine", repaths == 0 ? "（没有强制重规划）" : Walker.lastGuardRepath);
        return new Pin(t, repaths, cells, pinned, Math.abs(fp.getZ() - z0));
    }

    /** The distinct-cell count out of the walker's forced-repath line, or −1 when it has none.
     *  Parsed rather than recomputed: the arm's whole claim is about what that LINE says. */
    private static int cellsIn(String line) {
        var m = java.util.regex.Pattern.compile("点火过 (\\d+) 个不同的格子").matcher(line);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** dy of the shore deck's top block. */
    private static final int TRENCH_DECK = 20;
    /** Rows of lava in the trench. Four, so a body that goes in is in it. */
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
     *  150°, which is mostly −z: the body walked backwards off the arena and pinned on one cell,
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
     * <b>Two bodies, opposite situations, one identical evidence row.</b>
     *
     * <h2>The reading this is a copy of</h2>
     *
     * The 2026-08-20 ladder ended rung 14 in a shuttle, and the rows a reader goes to first are the
     * ones the crossing prints for each wedged hop. Two of the four were unreadable:
     *
     * <pre>
     * fortress.around.4 = 脚下=dirt …… onGround=true 落速=-0.08 血=20 脚下到实心=0
     * fortress.around.8 = 脚下=air  …… onGround=true 落速=-0.08 血=20 脚下到实心=&gt;16
     * </pre>
     *
     * <p>{@code around.8} reads as a body hanging over a void. It was not: hop 9 started from that
     * cell and its own flight row says {@code y 43→43（途中最高 44，最低 41）}, so the body was
     * standing — balanced on the corner of a NEIGHBOURING block, with its own centre column open
     * sixteen blocks down. Recovering that took cross-referencing a different row from a different
     * hop, and the same three readings are also what a genuinely airborne body prints on the tick it
     * walks off a lip: {@code onGround} is a tick stale there, so the flag says {@code true} while
     * the sole is on nothing.
     *
     * <p>{@link JourneyFlight}'s class note already names this — three different bugs all end with a
     * body hanging in {@code cave_air} and the snapshot reporting it is the same line in all three —
     * and fixed it for the RECORDER by reading the footprint. {@code surroundings}, the row every
     * wedged hop prints, never got that fix: it asks only the cell under the body's centre, and a
     * player is 0.6 wide.
     *
     * <h2>What this arm measures</h2>
     *
     * Both bodies stand over the same bottomless shaft and both must print {@code 脚下=air},
     * {@code onGround=true} and {@code 脚下到实心=&gt;16} — that is the CONTROL, and it is a
     * measurement rather than an assertion of intent: if the two situations do not produce those
     * same three readings, this arena has not reproduced the ambiguity and the arm fails as THE RIG
     * instead of reporting that the new clause separated them. What must then separate them is the
     * sole, printed through {@link WalkerGeometry#soleRow} — this repo's single enumeration of
     * 「身体站在哪一格上」, not a fourth opinion invented here.
     *
     * <p><b>Red before the row learned to say it.</b> Without the sole clause the two rows are
     * byte-identical and the first check cannot pass.
     */
    private static void rowSeparatesAPerchFromMidAir(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.allowPlace = false;
        BotConfig.allowBreak = false;
        BotConfig.walkerDebug = false;
        ctx.cleanup(() -> clearShaft(ctx));

        stageShaft(ctx);
        ctx.record("rig", "一口井：井底 dy=" + SHAFT_FLOOR + "，离脚下那一格 18 格 —— 比 surroundings"
                + " 自己往下探的 16 格深（所以两具身体都读到「>16」），又比两个 walker 守卫拒绝的落差浅"
                + "（所以走的那一半走得下去）；台面 dy=" + DECK + "，台缘外什么都没有；另有一块孤零零的"
                + "下界岩在 dx=-1、dy=" + DECK + " —— 身体骑在它的边角上，自己那一格底下是空的");

        // A: a body cornered on a neighbour. x = +0.05 puts its 0.6-wide box across the cell
        // boundary, so 0.15 of the sole is on the lone block and its own centre column is air.
        ServerPlayerAvatar perch = ServerPlayerAvatar.createUnique(ctx.level(),
                ctx.originX() + 0.05, ctx.rel(0, DECK + 1, 0).getY(), ctx.originZ() + 0.5);
        ServerPlayer pf = perch.fakePlayer();
        ctx.cleanup(pf::discard);
        pf.getInventory().clearContent();
        aim(pf);
        for (int i = 0; i < SETTLE_TICKS; i++) step(perch);
        String rowPerch = JourneyNetherRungs.surroundings(pf, pf.blockPosition());
        ctx.record("perch.row", rowPerch);
        ctx.record("perch.sole", String.format(Locale.ROOT, "%.4f/0.36 @ %.3f,%.3f,%.3f",
                WalkerGeometry.soleOnSolid(new LevelWorldView(ctx.level(), pf), pf),
                pf.getX(), pf.getY(), pf.getZ()));
        if (!pf.onGround())
            ctx.fail("THE RIG, not the subject: 骑在邻格边角上的身体没站住（" + where(ctx, pf)
                    + "） —— 这一臂的前提就是它 onGround=true 却不站在自己那一格上");

        // B: the stale-flag tick. A body one tick past the lip still reports onGround=true with its
        // whole sole on nothing — the same three readings, the opposite situation.
        ServerPlayerAvatar off = spawn(ctx, ctx.originZ() + 1.5, DECK + 1, true);
        ServerPlayer wf = off.fakePlayer();
        String rowAir = walkToTheStaleTick(ctx, off);
        ctx.record("midAir.row", rowAir);
        ctx.record("midAir.sole", String.format(Locale.ROOT, "%.4f/0.36 @ %.3f,%.3f,%.3f",
                WalkerGeometry.soleOnSolid(new LevelWorldView(ctx.level(), wf), wf),
                wf.getX(), wf.getY(), wf.getZ()));

        // THE CONTROL. Three readings, both bodies, or this arena is not the one that was confusing.
        for (String must : List.of("脚下=air", "onGround=true", "脚下到实心=>16")) {
            if (!rowPerch.contains(must) || !rowAir.contains(must))
                ctx.fail("THE RIG, not the subject: 两具身体本该给出同一句「" + must
                        + "」，实测 骑边角=「" + rowPerch + "」／半空中=「" + rowAir
                        + "」 —— 那么「新读数把它们分开了」就分不清是新读数起了作用，还是"
                        + "这座场地本来就分得开");
        }

        // B and C read the NEW clause only. The old row already contains the character 实 inside
        // 「脚下到实心」, so a whole-row contains() would be satisfied by the very sentence this arm
        // exists to say is not enough — a check that passes on the pre-fix row is not a check.
        String soleOf = soleClause(rowPerch), soleOfAir = soleClause(rowAir);
        ctx.record("perch.soleClause", soleOf.isEmpty() ? "（这一句里没有脚底那一段）" : soleOf);
        ctx.record("midAir.soleClause", soleOfAir.isEmpty() ? "（这一句里没有脚底那一段）" : soleOfAir);

        ctx.check(rowPerch).as("A 两句必须不再一样 —— 旧读数只问身体正中那一格，而身体宽 0.6 格，"
                + "所以「骑在邻格边角上」和「真的悬空」印出来是同一句话：" + rowPerch)
                .isNotEqualTo(rowAir);
        ctx.check(soleOf.contains("实")).as("B 骑边角的那一句，脚底那一段里必须有一格是实的："
                + (soleOf.isEmpty() ? rowPerch : soleOf)).isTrue();
        ctx.check(soleOfAir.contains("实")).as("C 半空中的那一句，脚底那一段里一格实的都不许有 —— "
                + "否则 B 会被一句对两种情形都成立的话满足：" + (soleOfAir.isEmpty() ? rowAir : soleOfAir))
                .isFalse();
    }

    /** The tail of a {@code surroundings} row from its sole clause on, or empty when the row has no
     *  such clause. Empty is the pre-fix answer and it must make B fail rather than throw. */
    private static String soleClause(String row) {
        int i = row.indexOf("脚底=");
        return i < 0 ? "" : row.substring(i);
    }

    /**
     * dy of the shaft's floor — eighteen rows under the foot cell, and the number is squeezed
     * between three constants rather than picked.
     *
     * <p>It has to be deeper than {@code surroundings}' own 16-cell probe, or neither body reads
     * {@code 脚下到实心=>16} and the control has nothing to be about. It has to be SHALLOWER than
     * what the walker's two guards refuse, or the walking half never happens: measured on the first
     * cut of this arena, over a bottomless shaft {@link Walker#strideFloorGuard} fired, killed the
     * horizontal momentum and sneak-pinned the body on a 0.0001-wide sliver of the lip for all 160
     * ticks — the guard doing exactly its job, and an arena that mistook it for a rig failure.
     * Eighteen clears both: the stride guard's fall scan reaches 23 at full health and finds this
     * floor, and {@code survivableFall(20) = 22} keeps {@link Walker#footingGuard} out too.
     */
    private static final int SHAFT_FLOOR = DECK + 1 - 18 - 1;

    /** dy the shaft is cleared up from — one row over its floor. */
    private static final int SHAFT_CLEAR = SHAFT_FLOOR + 1;

    /**
     * Walk the deck until the body is ONE TICK past the lip, and take the row there.
     *
     * <p>Not the first {@code stillFalling} tick — that is four ticks later, by which time
     * {@code onGround} has caught up and the two rows would differ for a reason that has nothing to
     * do with the sole. The tick wanted is the stale one: the sole already on nothing and the flag
     * still saying {@code true}, which is what {@code fortress.ground.*} printed on the live falls.
     */
    private static String walkToTheStaleTick(SceneContext ctx, ServerPlayerAvatar av) {
        ServerLevel level = ctx.level();
        ServerPlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(ctx.rel(0, DECK + 1, SHELF_CELLS + 3)));
        for (int t = 0; t < WALK_TICKS; t++) {
            walker.tick(av, w);
            aim(fp);
            av.commandMove(0f, 1f);
            av.commandJump(false);
            av.step();
            if (fp.onGround() && WalkerGeometry.soleOnSolid(w, fp) <= 0.0)
                return JourneyNetherRungs.surroundings(fp, fp.blockPosition());
            if (!fp.onGround()) break;                        // the stale tick was overshot
        }
        ctx.fail("THE RIG, not the subject: 走完 " + WALK_TICKS
                + " tick 也没抓到「脚底实心=0 而 onGround 还报 true」那一 tick（" + where(ctx, fp)
                + "） —— 这一臂的另一半没有布出来");
        return "";
    }

    /** Air out the shaft arena, well below the origin too: {@code surroundings} probes 16 cells down
     *  and an unstaged floor inside that reach would decide the control's own premise. */
    private static void clearShaft(SceneContext ctx) {
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -3; dz <= 16; dz++)
                for (int dy = SHAFT_CLEAR; dy <= DECK + 4; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The deck, the lone block beside it that a body can corner on, and the floor eighteen rows
     *  down. Nothing in between — the column under BOTH bodies has to be open past the row's own
     *  16-cell probe, which is the whole premise of the control. */
    private static void stageShaft(SceneContext ctx) {
        clearShaft(ctx);
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -3; dz <= 16; dz++)
                ctx.setBlock(dx, SHAFT_FLOOR, dz, Blocks.NETHERRACK);
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = 1; dz <= SHELF_CELLS - 2; dz++)
                ctx.setBlock(dx, DECK, dz, Blocks.NETHERRACK);
        ctx.setBlock(-1, DECK, 0, Blocks.NETHERRACK);         // the perch's neighbour, on its own
    }

    // ── the rig ──────────────────────────────────────────────────────────────────────────────

    /** What one walk out to the lip produced. */
    private record Walk(int ticks, double walked, boolean leftTheGround, int pinnedTicks,
                        double soleAtLaunch, boolean onGroundAtLaunch, boolean sweptAtLaunch) {
        String line() {
            return String.format(Locale.ROOT,
                    "%d tick，沿台面走了 %.2f 格，离地=%s，守卫钉住 %d tick；"
                            + "离地前一 tick：脚底实心 %.4f/0.36，onGround=%s，"
                            + "vanilla 自己那一问（脚下 0.0784 格内有碰撞吗）=%s",
                    ticks, walked, leftTheGround ? "是" : "否", pinnedTicks,
                    soleAtLaunch, onGroundAtLaunch, sweptAtLaunch ? "有" : "没有");
        }
    }

    /**
     * Walk the shelf until the body is in the state a leg gets judged in, and say what it cost.
     *
     * <p>The walker is ticked so its guards run — they live in {@code Walker#tick}'s single-exit
     * wrapper, after every branch of {@code tickInner} — and the heading and impulse are re-imposed
     * afterwards so the body walks one straight line whatever the walker would rather do. Sneak is
     * NOT re-imposed: it is the channel a guard pins on, and {@code pinnedTicks} is the reading that
     * says whether one did.
     *
     * <p>The three readings taken on the tick before the launch are the ones
     * {@code fortress.ground.6.0} printed live, in the same order: the sole, the flag, and vanilla's
     * own ground question. They are RECORDED and not asserted — what the guards do at a survivable
     * lip belongs to {@code wd.serverWalksOffASurvivableLedge}, and an arm asserting it here would
     * be a second opinion about a question that already has an owner.
     */
    private static Walk walkOffTheLip(SceneContext ctx, ServerPlayerAvatar av) {
        ServerLevel level = ctx.level();
        ServerPlayer fp = av.fakePlayer();
        LevelWorldView w = new LevelWorldView(level, fp);
        Walker walker = new Walker();
        walker.setGoal(new Goal.Block(ctx.rel(0, BAY_BED + 1, SHELF_CELLS + 3)));

        double startZ = fp.getZ(), farZ = fp.getZ();
        double prevSole = 0;
        boolean prevOnGround = false, prevSwept = false;
        boolean left = false;
        int pinned = 0, t = 0;
        for (; t < WALK_TICKS; t++) {
            double sole = WalkerGeometry.soleOnSolid(w, fp);
            boolean onGround = fp.onGround();
            boolean swept = !level.noCollision(fp, groundSlab(fp.getBoundingBox()));
            walker.tick(av, w);
            aim(fp);
            av.commandMove(0f, 1f);
            av.commandJump(false);
            if (av.dbgSneak()) pinned++;
            av.step();
            farZ = Math.max(farZ, fp.getZ());
            if (!left && !fp.onGround() && fp.getY() < ctx.rel(0, DECK + 1, 0).getY() - 0.05) {
                left = true;
                prevSole = sole;
                prevOnGround = onGround;
                prevSwept = swept;
            }
            if (left && JourneyNetherRungs.stillFalling(fp)) break;
            if (left && fp.onGround()) break;                 // landed before it ever counted as falling
        }
        return new Walk(t, farZ - startZ, left, pinned, prevSole, prevOnGround, prevSwept);
    }

    /** What spending the allowance cost, and the last verdict taken while the body was still in the
     *  air — the live row's own reading ({@code 脚下到实心 0 格}) rather than the first one, which is
     *  taken three blocks up and understates how close the crossing was to a landing. */
    private record Allowance(int landedAt, String lastMidAir) {}

    /**
     * Spend the crossing's landing allowance and say which tick the body landed on, or −1.
     *
     * <p>The impulse is released first, every tick, because that is what {@link HoldStill} does and
     * the crossing waits under it: a leftover forward impulse would walk the body off whatever it
     * lands on, which is「松手不是刹车」with the brake left off.
     */
    private static Allowance allowanceToLand(SceneContext ctx, ServerPlayerAvatar av, String arm) {
        ServerPlayer fp = av.fakePlayer();
        int landedAt = -1;
        String lastMidAir = "没有 —— 身体从来没进入过「还在下坠」";
        for (int i = 1; i <= JourneyNetherRungs.LANDING_TICKS; i++) {
            String verdict = JourneyNetherRungs.hazardBlockingARetry(fp, fp.blockPosition());
            if (landedAt < 0 && verdict != null) lastMidAir = "第 " + i + " tick：" + verdict;
            step(av);
            if (landedAt < 0 && fp.onGround()) landedAt = i;
        }
        ctx.record(arm + ".allowance", "余量 " + JourneyNetherRungs.LANDING_TICKS
                + " tick，第 " + landedAt + " tick 落地（-1 = 没落地）；落地前最后一次判决 = "
                + lastMidAir + "；" + where(ctx, fp));
        return new Allowance(landedAt, lastMidAir);
    }

    /** One idle physics tick with everything released — {@link HoldStill}'s own body. */
    private static void step(ServerPlayerAvatar av) {
        av.commandMove(0f, 0f);
        av.commandJump(false);
        av.breakHold(false);
        av.step();
    }

    /** A body at {@code dy}, optionally left to stand for {@link #SETTLE_TICKS} first. The settle is
     *  for an arm that starts ON something — it proves vanilla itself holds the stance up before the
     *  measurement rather than during it. An arm that starts in the air must NOT have it: those
     *  ticks are its own fall. */
    private static ServerPlayerAvatar spawn(SceneContext ctx, double z, int dy, boolean settle) {
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(ctx.level(),
                ctx.originX() + 0.5, ctx.rel(0, dy, 0).getY(), z);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        fp.getInventory().clearContent();
        aim(fp);
        if (!settle) return av;
        for (int i = 0; i < SETTLE_TICKS; i++) step(av);
        if (fp.getY() < ctx.rel(0, dy, 0).getY() - 0.5)
            ctx.fail("THE RIG, not the subject: vanilla 自己就没端住这个站位（" + SETTLE_TICKS
                    + " 个空 tick 之后 " + where(ctx, fp) + "）");
        return av;
    }

    /** Face +z, head and body with it — see the shore pair: a heading the walker may slew turns a
     *  straight walk into a measurement of A*. */
    private static void aim(ServerPlayer fp) {
        fp.setYRot(0f);
        fp.yHeadRot = 0f;
        fp.yBodyRot = 0f;
    }

    /** The slab vanilla sweeps to decide {@code onGround}: one tick of gravity under the box. Asked
     *  directly so a stale flag can be told from a reading that looked at the wrong cells — the
     *  two produce the same line and want opposite fixes. */
    private static AABB groundSlab(AABB box) {
        return new AABB(box.minX, box.minY - 0.0784, box.minZ, box.maxX, box.minY, box.maxZ);
    }

    private static String where(SceneContext ctx, ServerPlayer fp) {
        return String.format(Locale.ROOT, "身体=(%.2f,%.2f,%.2f) onGround=%s 落速=%.3f 脚下=%s 脚下到实心=%s",
                fp.getX(), fp.getY(), fp.getZ(), fp.onGround(), fp.getDeltaMovement().y,
                blockUnder(ctx, fp), dropBelow(ctx, fp.blockPosition()));
    }

    private static String blockUnder(SceneContext ctx, ServerPlayer fp) {
        BlockPos below = fp.blockPosition().below();
        return BuiltInRegistries.BLOCK.getKey(ctx.level().getBlockState(below).getBlock()).getPath();
    }

    /**
     * How far it is straight down to the first block that would hold the body.
     *
     * <p><b>Not the same reading as the rung's own {@code dropBelow}</b>, which this comment used to
     * claim it was. {@link JourneyNetherRungs}' probe stops at 16 and answers「虚空」below the build
     * limit; this one looks 48 down and has no void case, because the arena's bay is deeper than a
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

    /** Air out the working box. Called before staging and again on cleanup, so an arm that fails
     *  mid-drive still hands the shared dogfood world back empty. */
    private static void clear(SceneContext ctx) {
        for (int dx = -4; dx <= 4; dx++)
            for (int dz = -3; dz <= 16; dz++)
                for (int dy = 0; dy <= 43; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    /** The shelf, the lip, and the bay under it — the same terrain for both arms. */
    private static void stage(SceneContext ctx) {
        clear(ctx);
        for (int dx = -1; dx <= 1; dx++)                       // the shelf the body walks out on
            for (int dz = -2; dz <= SHELF_CELLS - 2; dz++)
                for (int dy = 0; dy <= DECK; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.NETHERRACK);
        for (int dx = -4; dx <= 4; dx++)                       // the bay it steps down into
            for (int dz = SHELF_CELLS - 1; dz <= 16; dz++)
                for (int dy = 0; dy <= BAY_BED; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.NETHERRACK);
    }
}
