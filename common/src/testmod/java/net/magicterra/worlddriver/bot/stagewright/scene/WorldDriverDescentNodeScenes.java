package net.magicterra.worlddriver.bot.stagewright.scene;

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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * <b>The plan said step down, the pointer stepped down, and the body stayed where it was.</b>
 *
 * <h2>The cell this is a copy of, and why it is a copy rather than an invention</h2>
 *
 * Journey rung 14's Nether crossing (2026-08-19) spent its last four hops — 3,517 of the run's 3,551
 * ticks with no plan at all — standing at one coordinate, {@code 159,53,187}, re-asking A* about
 * twenty times a second. The terrain of that pocket is the whole story, so it is not described here,
 * it is <b>reproduced</b>: {@link #BOX} is a verbatim 17×17×13 block copy read out of
 * {@code fabric/run-journey/world/DIM-1/region/r.0.0.mca}, world {@code x∈[151,167] z∈[179,195]
 * y∈[48,60]}, mapped so the body's foot cell lands on the arena's own {@code (0, +5, 0)}. Every
 * netherrack wall, the shaft the body dug itself, the two-block notch its foot cell hangs over and
 * the lava lake at {@code y=50} three cells away are the ones that were actually there. An arena for
 * a wedge whose cause is geometry cannot afford a hand-drawn approximation, and two tidyings of this
 * copy each destroyed the defect before that was believed — see {@link #BOX} and {@link #stageBox}.
 *
 * <h2>What was standing there</h2>
 *
 * The body finished a dug descent perched on {@code 159,53,187} — a cell whose OWN floor is air —
 * held up by {@code 0.125} of {@code 0.36} of sole on the corner of {@code 158,52,187}, with the
 * column at {@code 158,·,188} dropping into lava. {@link Walker#footingGuard} sneak-pinned it and was
 * right to. A* answered with the three-node way out, {@code [158,53,187 → 159,52,187 → 159,51,188]},
 * every cell of it standable. The walker spent all three in a single tick without moving a block:
 *
 * <pre>{@code
 * 步进 序=2/8 因=within 旧步=2 新步=3 w=159,52,187 nx=159,51,188 身体=(159.092,53.000,187.700)
 *      cur2=0.207 |w.y-p.y|=1.000 onGround=true 脚底实心=0.1250
 * }</pre>
 *
 * {@code |w.y-p.y|=1.000} is printed on the line that advanced. {@code within}'s vertical clause is
 * {@code |dyNode| < 1.2}, so a waypoint a full block under the feet reads as reached — the sibling
 * defect {@code WalkerTickProgress#airborneClimbConsume}'s javadoc named and declined to fix in the
 * climbing direction. Everything after is downstream: with the plan spent, {@code path == null} makes
 * every tick a safety repath, and the crossing's own governors cannot see it (see the ledger row).
 *
 * <h2>What it asks, and the one thing it deliberately does not</h2>
 *
 * <p><b>It must take the step down the plan gave it</b>, it must have a plan to walk while doing so,
 * and both must be the hold's doing. Standing still is otherwise a full-marks answer here — standing
 * still for 900 ticks is exactly what the ladder did, and the footing guard's pin was correct on
 * every one of those ticks, so「it did not fall」would have scored the wedge as a pass.
 *
 * <p><b>Lava is recorded, not asserted.</b> Whether an unwedged body then routes around the fall this
 * pocket opens onto is {@code wd.serverStopsAtALavaShore}'s and {@code wd.serverKeepsWalkingAtALavaRim}'s
 * subject; they drive at a lake on purpose and can say why an entry happened. A scene asserting both
 * subjects has a red that names neither. Measured: neither arm enters it.
 *
 * <p>The first cut of the descent clause read「the foot cell changed」and the control arm passed it by sliding
 * <b>0.30 blocks</b> west onto the very block that was holding it up — a different cell, the same
 * standstill, full marks for 260 ticks of nothing. The perch is one cell wide, so「left」was never a
 * question about position; the defect is a planned descent the pointer spent and the body never took,
 * and the reading is whether it was taken ({@code minY ≤ perchY − 1}).
 *
 * <h2>The arm that must go red</h2>
 *
 * Both arms stage the identical box and drive the identical walker; {@link BotConfig#walkerDescentNodeHold}
 * is the only difference. The control (hold OFF = the ladder's build) must reproduce the burn, and
 * the rig <b>hard-fails</b> if it does not: an arena whose control walks out has not earned the right
 * to report that the subject did. {@code wd.serverStillWalksDownAStaircase} is the other half — the
 * same switch over an ordinary staircase, where both arms must AGREE, because a hold that fired at
 * every descending node would pass this scene and make every stair descent crawl.
 *
 * <h2>Driven by the walker, not by the fixture</h2>
 *
 * Nothing here re-imposes a heading or an impulse after {@code walker.tick} — unlike the rim arms,
 * whose subject is a guard and whose body must therefore be pushed at it. The subject here IS the
 * step pointer, so a fixture that drove the body would be answering its own question.
 *
 * <h2>Arena footprint</h2>
 *
 * {@code dx, dz ∈ [-8, 8]}, {@code dy ∈ [BOX_Y0, BOX_Y0+12]} — inside the default one-chunk window
 * ({@code dx, dz ∈ [-16, 31]}), so no {@code withChunkRadius}.
 */
public final class WorldDriverDescentNodeScenes implements SceneProvider {

    /** Half-width of the copied box, in cells: {@code dx, dz ∈ [-BOX_R, BOX_R]}. */
    private static final int BOX_R = 8;
    /** Layers of the copied box, {@code dy ∈ [BOX_Y0, BOX_Y0 + BOX_H - 1]} ↔ world {@code y ∈ [48,60]}. */
    private static final int BOX_H = 13;
    /** The copied box's bottom layer, as a dy offset from the scene origin. */
    private static final int BOX_Y0 = 8;
    /** Layer index within the box that holds the body's foot cell — world {@code y=53} is layer 5. */
    private static final int PERCH_LAYER = 5;

    /** The body's exact stance on the ladder, to three decimals: world {@code (159.092, 53.000,
     *  187.700)} relative to the foot cell's corner. Both offsets are load-bearing — the {@code 0.092}
     *  is what leaves only {@code 0.208 × 0.6 = 0.125} of the footprint on the neighbouring block and
     *  the rest over the notch, which is the whole stance. Rounding it to {@code 0.5} stands the body
     *  in mid-air. */
    private static final double PERCH_DX = 0.092, PERCH_DZ = 0.700;

    /** Hop 11's goal, as an offset from the body: {@code XZ(176,204)} from {@code (159,187)}. Outside
     *  the copied box on purpose — the crossing's goal always is, and what this measures is whether
     *  the body leaves the pocket, not whether it arrives. */
    private static final int GOAL_DX = 17, GOAL_DZ = 17;
    /** Hop 11's own tolerance. */
    private static final int GOAL_R = 6;

    /** Ticks per arm. Two thirds of the ladder's 900-tick hop — long enough that a body which is
     *  going to move has moved (the healthy hops of that run each covered 42 cells in 278–532), short
     *  enough that two arms fit a 600-tick scene budget with the staging. */
    private static final int DRIVE_TICKS = 260;

    /** Idle ticks the stance must survive untouched before the walker is allowed to act — the same
     *  settle the thin-footing arms use, and here it also proves vanilla holds this perch at all. */
    private static final int SETTLE_TICKS = 20;

    /**
     * The rung-14 pocket, verbatim, run-length encoded.
     *
     * <p>Cells in {@code dy} (13 layers, world {@code y=48} first) then {@code dz} ({@code -8..8})
     * then {@code dx} ({@code -8..8}). {@code #} netherrack, {@code .} air, {@code ~} lava, {@code o}
     * nether gold ore, {@code q} nether quartz ore.
     *
     * <p><b>All 34 lava cells are copied, including the 32 that were FLOWING.</b> The tidier reading
     * — flowing lava is the output of a source, not terrain, so copy the two sources and let the falls
     * re-form — was tried and is wrong here twice over. A scene body runs inside a single server tick,
     * so nothing re-forms: {@link #perchRow} came back「a lava-free 3×3」and the CONTROL arm, which had
     * stood still for 260 ticks with the lava present, walked 19.61 blocks out of the pocket with a
     * plan on 252 of them. The lava is not decoration around this wedge, it is the wall that makes the
     * pocket a pocket, and an arena that loses it loses the defect. Copied as static geometry it also
     * stays put, for the same reason.
     */
    private static final String BOX =
            "103#2q15#2q320#.16#2~15#2~15#.84#2q14#.16#.16#.16#.16#.68#2.6~9#.8~8#2.7~8#2.83#2q13#2.q14#" +
            "2.16#.16#.16#.53#.14#12.5#8.4~2.3#14.3#2.3#9.71#q6#q7#2.15#2.15#2.16#.16#.59#.9#12.4#11.~2." +
            "3#15.7#11.12#6.13#4.14#2.24#2q7#2.15#2.15#2.16#.16#.16#.41#2.13#4.#3.8#7.~2.8#11.11#7.11#6." +
            "12#5.13#4.15#5.12#2.15#2.15#2.15#2.16#.16#.41#2.36#~.15#4.13#6.12#5.13#4.13#4.15#8.9#5.12#2." +
            "15#2.7#~7#2.16#.16#.41#.37#~37#.15#3.13#4.12#2o3.15#10.7#9.8#5.3#.8#2.6#.o7#2.6#.9#.6#.9#." +
            "41#.108#2o.16#.17#7.8#6.2#.8#2.6#.8#2.6#.2o6#2.6#.2o7#.6#.#o7#.6#.16#.16#2.146#5.9#2.15#2.8#" +
            "2q5#2.7#3q5#2.6#2qo7#.6#.q8#.6#.16#.16#2.57#o16#o85#2.15#2.8#2q5#2.7#3q5#2.6#3q7#.7#q8#.99#o" +
            "15#2o15#2o68#2.15#2.15#2.15#2.16#.16#.132#2o53#";

    /** How one drive ended. */
    private record Leg(int ticks, int noPlan, int longestNoPlan, double minY, double moved,
                       boolean lava, boolean descended, long holds, String ended) {}

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.serverStepsDownAPerchItPlanned", 600,
                        WorldDriverDescentNodeScenes::stepsDownAPerchItPlanned).withRequired(false),
                Scene.of("wd.serverStillWalksDownAStaircase", 600,
                        WorldDriverDescentNodeScenes::stillWalksDownAStaircase).withRequired(false));
    }

    // ---------------------------------------------------------------- the copy of rung 14's pocket

    private static void stepsDownAPerchItPlanned(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        ctx.cleanup(() -> clearBox(ctx));

        stageBox(ctx);
        ctx.record("rig", "真梯 2026-08-19 第 14 级下界穿越最后钉死的那一格，17×17×13 原样搬过来（世界 "
                + "x∈[151,167] z∈[179,195] y∈[48,60]，身体那一格落在本场地的 (0," + (BOX_Y0 + PERCH_LAYER)
                + ",0)）。身体站位也是原样：格角偏 (" + PERCH_DX + ", " + PERCH_DZ + ")");
        ctx.record("perch", perchRow(ctx));

        Leg control = drive(ctx, "control", false);
        ctx.record("control.after", control.ended());
        if (control.descended())
            ctx.fail("THE RIG, not the subject: 关掉 walkerDescentNodeHold 之后身体照样往下走掉了，"
                    + "那么「主体往下走掉了」这条判据就分不清「修好了」和「这座场地本来就困不住人」 —— "
                    + control.ended());
        if (control.holds() != 0)
            ctx.fail("THE RIG, not the subject: 开关关着，拦截计数却涨了 " + control.holds()
                    + " 次 —— 两条臂就不是只差这一个变量了");

        stageBox(ctx);
        Leg subject = drive(ctx, "subject", true);
        ctx.record("subject.after", subject.ended());
        // Unconditional, both arms: the pair is the finding, and a PASS prints no evidence.
        ctx.record("delta", "无计划 " + control.noPlan() + "/" + control.ticks() + " tick → "
                + subject.noPlan() + "/" + subject.ticks() + " tick；最低 y "
                + String.format(Locale.ROOT, "%.2f → %.2f", control.minY(), subject.minY())
                + "；走了 " + String.format(Locale.ROOT, "%.2f → %.2f 格", control.moved(), subject.moved())
                + "；拦下推进 " + control.holds() + " → " + subject.holds() + " 次");
        // What the ladder's own governors did with those ticks, written down because the numbers are
        // a coincidence nobody would re-derive and the next reader will otherwise assume one of them
        // caught it. walkerFutileSearchCap is 5 and is EXEMPTED while a body's stuck penalties are
        // live; the anti-churn re-charges those every CHURN_WINDOW=400 ticks precisely BECAUSE the
        // body is not moving, and each charge lives 15 s × strength. So the fallback bound is the
        // whole of the defence — and NO_PATH_WAIT_CAP is 900, exactly the crossing's own per-hop tick
        // budget, so it can never be reached inside a hop. Four hops ended `end=null err=null`.
        // NOT a clause. Whether an unwedged body then routes around the fall this pocket opens onto
        // belongs to wd.serverStopsAtALavaShore and wd.serverKeepsWalkingAtALavaRim, which drive at a
        // lake on purpose and can say why an entry happened; a scene that asserted both subjects would
        // have a red that named neither. It is recorded every run because it is a finding either way.
        ctx.record("lava", "对照臂" + (control.lava() ? "进了岩浆" : "没进岩浆")
                + "，主体" + (subject.lava() ? "进了岩浆 —— 这一格解开之后，路上那道岩浆瀑布是下一个问题，"
                        + "不是这一幕的" : "没进岩浆"));
        ctx.record("ledger", "真梯上这具身体一个 tick 都没被拦下来过：futile cap 是 5，但只要身上还有 "
                + "stuck penalty 就整条豁免；而站着不动本身每 400 tick 就让 anti-churn 再充一次值 "
                + "15 s×strength 的 penalty，所以那个「等它衰减」的等待在等一件被等待本身挡住的事。"
                + "剩下唯一的兜底 NO_PATH_WAIT_CAP=900，正好等于这一级每段的 tick 预算 —— "
                + "段内永远够不着，四段都以 end=null err=null 收工");

        // 「往下走了一格」而不是「离开了这一格」。第一版判的是脚下那一格变没变，对照臂往西蹭了 0.30
        // 格、踩到旁边那块石头上，就算「离开了」—— 满分给了一具原地站了 260 tick 的身体。这一级的
        // 缺陷是「计划里那一步下降被指针花掉、身体没走」，所以判据就得是那一步走没走。
        ctx.check(subject.descended()).as("A 必须真的往下走掉那一格 —— 计划里那一步下降就是"
                + "被指针花掉的那一步，真梯为此站了 900 tick：实测 " + subject.ended()).isTrue();
        ctx.check(control.noPlan() > subject.noPlan()).as("B 而且身上要有计划可走：对照臂 "
                + control.noPlan() + "/" + control.ticks() + " tick 没有计划，主体 "
                + subject.noPlan() + "/" + subject.ticks() + " tick").isTrue();
        ctx.check(subject.holds() > 0).as("C 而且这一切要是那道拦截干的，不是别的什么变了："
                + "主体这一趟拦下了 " + subject.holds() + " 次「身体还站着、节点在脚下面」的推进")
                .isTrue();
    }

    /** Re-derive the perch off the LEVEL, so the staging and the guard cannot disagree about it — the
     *  same reason {@code WorldDriverThinFootingScenes#rimScanRow} exists. Prints the four readings the
     *  ladder's wedge is made of: the foot cell's own floor, how far down the first solid cell is, the
     *  sliver that is holding the body up, and where the lava actually is. */
    private static String perchRow(SceneContext ctx) {
        ServerLevel level = ctx.level();
        BlockPos foot = ctx.rel(0, BOX_Y0 + PERCH_LAYER, 0);
        int solidAt = -1;
        for (int i = 1; i <= 8 && solidAt < 0; i++)
            if (level.getBlockState(foot.below(i)).blocksMotion()) solidAt = i;
        int lavaAt = -1;
        BlockPos lavaCol = null;
        // The whole 3x3 rather than the one column the ladder's guard happened to fire on: what makes
        // a perch lethal is that SOME neighbouring column ends badly, and naming the wrong one would
        // let a staging change move the hazard without the row noticing.
        for (int dx = -1; dx <= 1 && lavaAt < 0; dx++)
            for (int dz = -1; dz <= 1 && lavaAt < 0; dz++) {
                BlockPos col = foot.offset(dx, 0, dz);
                for (int i = 1; i <= 8 && lavaAt < 0; i++)
                    if (level.getBlockState(col.below(i)).getFluidState().is(FluidTags.LAVA)) {
                        lavaAt = i;
                        lavaCol = col;
                    }
            }
        return "脚下那一格 " + foot.below().toShortString() + "="
                + name(level, foot.below()) + "（所以这一格 canStandAt 是假的），"
                + (solidAt < 0 ? "往下 8 格没有实心" : "往下第 " + solidAt + " 格才是实心的")
                + "；撑住身体的是西边那一格 " + foot.offset(-1, -1, 0).toShortString() + "="
                + name(level, foot.offset(-1, -1, 0)) + "，脚底压上去 "
                + String.format(Locale.ROOT, "%.4f/0.36", 0.208 * 0.6)
                + "；旁边 3×3 那几列里"
                + (lavaAt < 0 ? "一列都没扫到岩浆（流动的那 32 格按空气布的，两个源在 dy=+2）"
                        : lavaCol.toShortString() + " 往下第 " + lavaAt + " 格是岩浆")
                + " —— 致命落差是旁边那几列给的，不是脚下那个两格的坑";
    }

    private static String name(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getBlock().getName().getString();
    }

    /**
     * Stand the body on the perch and let the walker have it.
     *
     * <p>The goal is hop 11's, at hop 11's bearing and tolerance. Nothing steers the body but the
     * walker: this scene's subject is the step pointer, so a rig that drove the body would be
     * measuring its own impulse.
     */
    private static Leg drive(SceneContext ctx, String arm, boolean hold) {
        ServerLevel level = ctx.level();
        BotConfig.walkerDescentNodeHold = hold;

        BlockPos foot = ctx.rel(0, BOX_Y0 + PERCH_LAYER, 0);
        double x0 = foot.getX() + PERCH_DX, z0 = foot.getZ() + PERCH_DZ;
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level, x0, foot.getY(), z0);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        // The crossing arrives carrying blocks and bridges with them; a bagless body would be
        // refused moves this one had.
        fp.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        fp.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 64));
        for (int i = 0; i < SETTLE_TICKS; i++) av.step();
        double settledY = fp.getY();
        if (settledY < foot.getY() - 0.5)
            ctx.fail("THE RIG, not the subject: vanilla 自己就没端住这个站位（" + SETTLE_TICKS
                    + " 个空 tick 之后 y=" + String.format(Locale.ROOT, "%.2f", settledY) + "）");
        double sole = WalkerGeometry.soleOnSolid(w, fp);
        if (sole <= 0.0 || sole >= 0.36)
            ctx.fail("THE RIG, not the subject: 脚底实心 " + String.format(Locale.ROOT, "%.4f", sole)
                    + "，不是真梯那种「只踩住一角」的站位（要 0<脚底<0.36）");

        Walker walker = new Walker();
        walker.setGoal(new Goal.XZ(foot.getX() + GOAL_DX, foot.getZ() + GOAL_DZ, GOAL_R));
        long holds0 = Walker.descentHolds;
        double minY = fp.getY();
        int noPlan = 0, run = 0, longest = 0, t = 0;
        boolean lava = false;
        for (; t < DRIVE_TICKS; t++) {
            walker.tick(av, w);
            if (walker.pathNode() == null) { noPlan++; longest = Math.max(longest, ++run); } else run = 0;
            av.step();
            minY = Math.min(minY, fp.getY());
            if (fp.isInLava()) { lava = true; break; }
        }
        double moved = Math.hypot(fp.getX() - x0, fp.getZ() - z0);
        long holds = Walker.descentHolds - holds0;
        // DESCENDED, not「the foot cell changed」: the perch is one cell wide, the block holding the
        // body up is the cell west of it, and a body that slides 0.30 blocks onto that block has
        // changed foot cell without going anywhere. The first cut judged exactly that and handed the
        // control arm full marks for standing still for 260 ticks. The defect is a planned step DOWN
        // that the pointer spent and the body never took, so the reading is whether it was taken.
        boolean descended = minY <= foot.getY() - 1.0;
        String ended = String.format(Locale.ROOT,
                "walkerDescentNodeHold=%s → %d tick，身体=(%.2f,%.2f,%.2f) 格=%s，最低 y=%.2f（台面 y=%d），"
                + "走了 %.2f 格，无计划 %d/%d tick（最长连续 %d），脚底实心=%.4f，拦下推进 %d 次%s",
                hold, t, fp.getX(), fp.getY(), fp.getZ(), fp.blockPosition().toShortString(), minY,
                foot.getY(), moved, noPlan, t, longest, WalkerGeometry.soleOnSolid(w, fp), holds,
                lava ? "，泡在岩浆里" : (descended ? "，往下走掉了" : "，没往下走"));
        ctx.record(arm + ".drive", ended);
        return new Leg(t, noPlan, longest, minY, moved, lava, descended, holds, ended);
    }

    private static void clearBox(SceneContext ctx) {
        for (int dx = -BOX_R; dx <= BOX_R; dx++)
            for (int dz = -BOX_R; dz <= BOX_R; dz++)
                for (int k = 0; k < BOX_H; k++)
                    ctx.setBlock(dx, BOX_Y0 + k, dz, Blocks.AIR);
    }

    /**
     * Paint the copied box, then wall it in.
     *
     * <p>Solid first, then everything else, so the lava is put back into a bed that already exists —
     * the ordering {@code stageShore}/{@code stageRim} use and for the same reason. The two passes read
     * the same decoded string, so no cell can be in one and not the other.
     *
     * <p><b>The faces are then made solid, and that is the one place this stops being a copy.</b> The
     * ladder's cave carries on past {@code x=167} and its lava with it; a copy that ends in mid-cave
     * has open lava on an arena edge, and this suite shares one dogfood world. The wall stands exactly
     * where the copy ends. It costs the arena nothing it was measuring: the goal is 17 blocks outside
     * the box and was never reachable from this pocket on the ladder either — the crossing's own
     * verdict on those four hops was「连着 4 段没比纪录更近」— and the step down the plan
     * asks for is two cells from the perch.
     */
    private static void stageBox(SceneContext ctx) {
        String cells = decode();
        for (int pass = 0; pass < 2; pass++)
            for (int dx = -BOX_R; dx <= BOX_R; dx++)
                for (int dz = -BOX_R; dz <= BOX_R; dz++)
                    for (int k = 0; k < BOX_H; k++) {
                        char c = cells.charAt(((k * (2 * BOX_R + 1)) + (dz + BOX_R)) * (2 * BOX_R + 1)
                                + (dx + BOX_R));
                        boolean solid = c != '.' && c != '~';
                        if (solid != (pass == 0)) continue;
                        ctx.setBlock(dx, BOX_Y0 + k, dz, block(c));
                    }
        for (int dx = -BOX_R; dx <= BOX_R; dx++)
            for (int dz = -BOX_R; dz <= BOX_R; dz++)
                for (int k = 0; k < BOX_H; k++)
                    if (dx == -BOX_R || dx == BOX_R || dz == -BOX_R || dz == BOX_R
                            || k == 0 || k == BOX_H - 1)
                        ctx.setBlock(dx, BOX_Y0 + k, dz, Blocks.NETHERRACK);
    }

    private static Block block(char c) {
        return switch (c) {
            case '#' -> Blocks.NETHERRACK;
            case '~' -> Blocks.LAVA;
            case 'o' -> Blocks.NETHER_GOLD_ORE;
            case 'q' -> Blocks.NETHER_QUARTZ_ORE;
            default -> Blocks.AIR;
        };
    }

    /** Expand {@link #BOX}. A token is an optional decimal count followed by one symbol. */
    private static String decode() {
        StringBuilder sb = new StringBuilder(BOX_H * (2 * BOX_R + 1) * (2 * BOX_R + 1));
        for (int i = 0; i < BOX.length(); ) {
            int j = i;
            while (Character.isDigit(BOX.charAt(j))) j++;
            int n = j > i ? Integer.parseInt(BOX.substring(i, j)) : 1;
            char c = BOX.charAt(j);
            for (int k = 0; k < n; k++) sb.append(c);
            i = j + 1;
        }
        return sb.toString();
    }

    // ------------------------------------------------------- the descent that must NOT be slowed

    /** How far down and along the staircase runs. */
    private static final int STAIR_STEPS = 8;
    /** The staircase's top tread, as a dy offset from the scene origin. */
    private static final int STAIR_TOP = 20;
    /** Ticks per staircase arm. A body that walks eight treads and does not is the whole reading, and
     *  the healthy hops of the ladder covered 42 cells in as few as 278. */
    private static final int STAIR_TICKS = 200;

    /**
     * An ordinary staircase, walked twice, with {@link BotConfig#walkerDescentNodeHold} as the only
     * difference — and the two arms must AGREE.
     *
     * <p>This is the arm the fix had to earn separately. Holding the step pointer on a node below the
     * feet is right at a perch the body has to step off; done indiscriminately it would hold at every
     * tread of every descent and make walking downhill crawl or stall outright. A hold that only ever
     * fires is not distinguishable from the defect by {@code wd.serverStepsDownAPerchItPlanned} alone,
     * which passes on any change that gets the body off that one cell.
     *
     * <p>Both arms must reach the bottom, and the subject may not take materially longer than the
     * control — measured as ticks-to-bottom rather than as a bare「it arrived」, because a hold that
     * costs a tick per tread is fine and one that costs fifty is the regression.
     *
     * <h2>Arena footprint</h2>
     *
     * {@code dx ∈ [-2, 10]}, {@code dz ∈ [-2, 2]}, {@code dy ∈ [STAIR_TOP - STAIR_STEPS - 1,
     * STAIR_TOP + 3]} — inside the default one-chunk window.
     */
    private static void stillWalksDownAStaircase(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        BotConfig.walkerDebug = true;
        ctx.cleanup(() -> clearStair(ctx));

        stageStair(ctx);
        ctx.record("rig", STAIR_STEPS + " 级台阶，每级往 +x 一格、往下一格：顶面 dy=" + STAIR_TOP
                + "，底面 dy=" + (STAIR_TOP - STAIR_STEPS) + "。两条臂只差 walkerDescentNodeHold 一个变量");

        Stair control = stairDrive(ctx, "control", false);
        Stair subject = stairDrive(ctx, "subject", true);
        ctx.record("delta", "到底用了 " + control.ticks() + " → " + subject.ticks() + " tick，"
                + "下了 " + control.dropped() + " → " + subject.dropped() + " 级，"
                + "拦下推进 " + control.holds() + " → " + subject.holds() + " 次");

        if (control.dropped() < STAIR_STEPS)
            ctx.fail("THE RIG, not the subject: 关着开关都没走下这道楼梯（下了 " + control.dropped()
                    + "/" + STAIR_STEPS + " 级），那么「开着也走得下来」就什么都没证明 —— " + control.ended());

        ctx.check(subject.dropped()).as("A 开着 hold 还是要走得下整道楼梯：对照臂下了 "
                + control.dropped() + " 级，主体下了 " + subject.dropped() + " 级 —— "
                + subject.ended()).isEqualTo(STAIR_STEPS);
        // 3× is a shape bar, not a tuned constant: a hold that costs a tick or two per tread lands
        // within it on any staircase, and the failure it exists to catch — a hold that never releases
        // — cannot finish at all, so it fails clause A first. This is the floor under that.
        ctx.check(subject.ticks() <= Math.max(3 * control.ticks(), 60))
                .as("B 而且不许因此变慢一个数量级：对照臂 " + control.ticks() + " tick，主体 "
                        + subject.ticks() + " tick（上限 " + Math.max(3 * control.ticks(), 60) + "）").isTrue();
        // Without this the arm is 0==0: two identical tick counts read the same whether the hold is
        // cheap or never runs, and「never runs」would let a hold that stalls every real descent ship
        // behind a green negative control.
        ctx.check(subject.holds() > 0).as("C 而且这道拦截在普通下坡上确实跑过 —— 不然两条臂一样快"
                + "只证明它是死代码：主体拦下 " + subject.holds() + " 次，对照臂 " + control.holds()
                + " 次").isTrue();
    }

    private record Stair(int ticks, int dropped, long holds, String ended) {}

    private static Stair stairDrive(SceneContext ctx, String arm, boolean hold) {
        ServerLevel level = ctx.level();
        BotConfig.walkerDescentNodeHold = hold;

        BlockPos top = ctx.rel(0, STAIR_TOP + 1, 0);
        ServerPlayerAvatar av = ServerPlayerAvatar.createUnique(level,
                top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
        ServerPlayer fp = av.fakePlayer();
        ctx.cleanup(fp::discard);
        LevelWorldView w = new LevelWorldView(level, fp);
        fp.getInventory().clearContent();
        for (int i = 0; i < SETTLE_TICKS; i++) av.step();

        Walker walker = new Walker();
        BlockPos bottom = ctx.rel(STAIR_STEPS, STAIR_TOP - STAIR_STEPS + 1, 0);
        walker.setGoal(new Goal.Block(bottom));
        long holds0 = Walker.descentHolds;
        int t = 0, noPlan = 0;
        for (; t < STAIR_TICKS; t++) {
            walker.tick(av, w);
            if (walker.pathNode() == null) noPlan++;
            av.step();
            if (fp.blockPosition().getX() >= bottom.getX() && fp.getY() <= bottom.getY() + 0.5) break;
        }
        int dropped = Math.max(0, top.getY() - (int) Math.floor(fp.getY()));
        long holds = Walker.descentHolds - holds0;
        String ended = String.format(Locale.ROOT,
                "walkerDescentNodeHold=%s → %d tick，身体=(%.2f,%.2f,%.2f)，下了 %d/%d 级，无计划 %d tick，"
                + "拦下推进 %d 次",
                hold, t, fp.getX(), fp.getY(), fp.getZ(), dropped, STAIR_STEPS, noPlan, holds);
        ctx.record(arm + ".drive", ended);
        return new Stair(t, dropped, holds, ended);
    }

    private static void clearStair(SceneContext ctx) {
        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = STAIR_TOP - STAIR_STEPS - 1; dy <= STAIR_TOP + 3; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.AIR);
    }

    private static void stageStair(SceneContext ctx) {
        clearStair(ctx);
        for (int dx = -2; dx <= 10; dx++)
            for (int dz = -2; dz <= 2; dz++) {
                int tread = STAIR_TOP - Math.max(0, Math.min(STAIR_STEPS, dx));
                for (int dy = STAIR_TOP - STAIR_STEPS - 1; dy <= tread; dy++)
                    ctx.setBlock(dx, dy, dz, Blocks.STONE);
            }
    }
}
