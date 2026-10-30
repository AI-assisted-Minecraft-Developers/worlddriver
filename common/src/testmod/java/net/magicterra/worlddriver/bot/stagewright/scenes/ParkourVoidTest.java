package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.NavigatorReading;
import net.magicterra.worlddriver.api.methods.debug.BotDebugContracts.PlanCursor;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanEdge;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.PlanParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.SinglePlan;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.stagewright.kit.NavWalk;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneBot;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.SimProbes;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.magicterra.worlddriver.testcontent.TestMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

/**
 * A FLAT parkour leap over a LETHAL drop.
 *
 * <h2>What was uncovered</h2>
 *
 * The only other execution-level jump test is {@code TerrainTest.parkourAscend}, and it leaps a gap that lands <b>+1
 * higher</b> — which is what exempted it from the defect this family is built around: the sprint gate in
 * {@code DrivePhase} used to read
 *
 * <pre>{@code
 * boolean parkourAscend = parkourEdge && wp.getY() > foot.getY();   // RISING leaps only
 * boolean sprint = ... && (!lethalNear || parkourAscend) && ...;    // now: (!lethalNear || parkourEdge)
 * }</pre>
 *
 * so a <b>rising</b> parkour kept its sprint next to a killer edge and a <b>flat</b> one did not. {@code lethalNear} is
 * ANY of the foot cell's eight horizontal neighbours dropping further than {@code SurvivalMath#survivableFall} — which
 * on the rim of a small platform over void is true on <i>every grounded tick</i>. Observed on the End arrival platform
 * (rung 20): the bot met the first {@code parkour3} across two cells of void and fell out of the world, {@code h:0.1530
 * -> 0.1382} across the takeoff — exactly the x0.91 decay of a player with no impulse at all.
 *
 * <h2>The two tests</h2>
 *
 * <ul>
 *   <li>{@code parkourVoidLongRunway} — the anti-overfit arm. <b>Without it, "forbid every parkour and always bridge
 *       instead" is a full-marks answer.</b>
 *   <li>{@code parkourVoidRunwayGate} — planner-only. It pins the run-up threshold ITSELF as a criterion, which is the
 *       thing {@code Move.hasRunway} claims to model and does not.
 * </ul>
 *
 * <h2>Rig rules this family keeps</h2>
 *
 * <ul>
 *   <li><b>The drop must READ as lethal, or the test measures nothing.</b> The catch floor hangs {@value #VOID_DEPTH}
 *       blocks under the pad: deep enough that the gap columns are unambiguously lethal. A shallower rig would silently
 *       turn the sprint gate OFF and every test here would pass while proving nothing.
 *   <li><b>{@link SimProbes#grantWaterEffects} is granted</b>, matching {@code parkourAscend}. It cannot weaken the
 *       gate: {@code survivableFall(float health)} takes health alone and never reads a resistance modifier.
 *   <li><b>Evidence is published on both outcomes</b>, because "the green run placed no blocks" is the reading that
 *       separates a leap from a bridge.
 * </ul>
 */
@Scene("wd.scenes")
@Execution(ExecutionMode.SAME_THREAD)
class ParkourVoidTest {

    /** Cells of pure void between the launch lip and the landing deck — {@code parkour3}'s gap. */
    private static final int GAP = 2;

    /**
     * How far the landing deck runs past the gap, in cells, so "arrived" cannot be satisfied by hanging on the deck's
     * first block.
     */
    private static final int DECK_LEN = 8;

    /**
     * Depth of the catch floor below the pad: the 30 cells directly under the gap hold nothing solid, and the column
     * still exceeds {@code survivableFall} (about 22 at full HP) so {@code lethalDropAdjacent} reads TRUE.
     */
    private static final int VOID_DEPTH = 32;

    /**
     * Ticks allowed between "foot cell reaches the lip column" and "standing on the deck". A sprint-jump arc is about
     * 12 ticks; a two-cell bridge is two aim+place rounds and an order of magnitude more.
     */
    private static final int LEAP_TICKS = 40;

    /** The pad's own y, plot-relative. Standing y is one above; the catch floor is {@link #VOID_DEPTH} below. */
    private static final int PAD_Y = 30;

    /** Hang the catch floor under the working box; the plot is otherwise air. */
    private static void catchFloor(Plot plot, int halfZ) {
        plot.fill(-14, PAD_Y - VOID_DEPTH, -halfZ, 14, PAD_Y - VOID_DEPTH, halfZ, Blocks.STONE);
    }

    /**
     * Launch pad {@code x in [0, padLen-1]}, {@link #GAP} cells of nothing, landing deck running back from
     * {@code -GAP-1}. All at {@link #PAD_Y}, all {@code 2*halfW+1} cells wide in z, centred on {@code z}.
     */
    private static void buildPadGapDeck(Plot plot, int z, int padLen, int halfW) {
        plot.fill(0, PAD_Y, z - halfW, padLen - 1, PAD_Y, z + halfW, Blocks.STONE);
        plot.fill(-GAP - DECK_LEN, PAD_Y, z - halfW, -GAP - 1, PAD_Y, z + halfW, Blocks.STONE);
    }

    private static int carrying(ServerPlayer fp, Item item) {
        int n = 0;
        for (ItemStack st : fp.getInventory().items) {
            if (st.is(item)) {
                n += st.getCount();
            }
        }
        return n;
    }

    /** Solid cells still standing in the gap columns — the direct reading of "did it bridge". */
    private static int gapSolidCells(ServerLevel level, int cx, int cz, int padY, int halfW) {
        int n = 0;
        for (int i = 1; i <= GAP; i++) {
            for (int dz = -halfW; dz <= halfW; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (!level.getBlockState(new BlockPos(cx - i, padY + dy, cz + dz))
                            .isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /** What one leap produced. */
    private record Leap(double minY, boolean crossed, int spent, int tLip, int tDeck, String tally) {}

    /**
     * <b>The anti-overfit arm.</b> A 12-cell launch pad and ten cells of run-up over two cells of lethal void.
     *
     * <ol>
     *   <li>it arrives at deck level without falling;
     *   <li><b>it spent no blocks</b> — it must not have bridged;
     *   <li><b>it crossed within {@value #LEAP_TICKS} ticks of reaching the lip.</b> The block count alone is fooled by
     *       a bot that places a block and then does not cross on it; the clock is what distinguishes them. Written so
     *       it <b>cannot be satisfied vacuously</b>: a run that never reaches the deck is a violation.
     * </ol>
     *
     * <p><b>If this arm is RED, the premise "a long run-up gets across" is itself false</b> — a finding about the
     * product. Do not lengthen the pad, shrink the gap or widen the tolerance to make it green.
     */
    @Test
    void parkourVoidLongRunway(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 16) Plot plot, TestReporter reporter) {
        final int padLen = 12;
        final int spawnDx = 10;
        final int halfW = 1; // 3 cells wide: the End platform is not a plank
        final int standY = PAD_Y + 1;
        catchFloor(plot, halfW + 1);
        buildPadGapDeck(plot, 0, padLen, halfW);
        BlockPos goal = plot.at(-GAP - 6, standY, 0);

        Settings.pinUntimed(game)
                .set("allowPlace", true) // bridging is a legitimate way across
                .set("allowBreak", false) // digging around it is not
                .set("allowParkour4", false) // the gap is a parkour3; 4-cell leaps stay off
                .set("navigatorDebug", true);

        SceneBot bot = SceneBot.spawn(game, plot.at(spawnDx, standY, 0));
        final int cx = plot.origin().getX();
        final int cz = plot.origin().getZ();
        final int padAbs = plot.at(0, PAD_Y, 0).getY();
        final int standAbs = padAbs + 1;

        Leap leap = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            ServerLevel level = bot.level();
            SimProbes.grantWaterEffects(fp);
            for (int i = 0; i < 4; i++) {
                fp.getInventory().add(new ItemStack(Items.COBBLESTONE, 64));
            }
            fp.getInventory().selected = 0;
            final int cobbleBefore = carrying(fp, Items.COBBLESTONE);
            bot.goTo(Targets.block(goal));

            boolean walking = true;
            NavigatorReading nav = bot.navigator();
            double minY = fp.getY();
            double prevX = fp.getX();
            double prevH = 0.0;
            double takeoffX = Double.NaN;
            double takeoffHBefore = Double.NaN;
            double takeoffHAfter = Double.NaN;
            int takeoffTick = -1;
            int tLip = -1;
            int tDeck = -1;
            int tFell = -1;
            int t = 0;
            String gapMove = null;
            String firstPlan = null;
            for (; t < 400 && walking; t++) {
                bot.tick();
                walking = bot.busy();
                nav = bot.navigator();
                double h = Math.hypot(fp.getDeltaMovement().x, fp.getDeltaMovement().z);
                minY = Math.min(minY, fp.getY());
                if (firstPlan == null && nav.plan().map(PlanCursor::len).orElse(0) > 0) {
                    firstPlan = NavWalk.trace(nav, "planTally");
                }
                if (tLip < 0 && Mth.floor(fp.getX()) <= cx) {
                    tLip = t;
                }
                if (gapMove == null && fp.getX() < cx) {
                    gapMove = nav.plan().flatMap(PlanCursor::move).orElse("null");
                }
                if (takeoffTick < 0 && fp.getY() > standAbs + 0.05) {
                    takeoffTick = t;
                    takeoffX = prevX;
                    takeoffHBefore = prevH;
                    takeoffHAfter = h;
                }
                if (tDeck < 0 && Mth.floor(fp.getX()) <= cx - GAP - 1 && Math.abs(fp.getY() - standAbs) <= 0.4) {
                    tDeck = t;
                }
                // Stop the moment the player is in the void: a player that has fallen off spends the rest of the
                // budget asking A* about terrain 30 blocks down.
                if (fp.getY() < padAbs - 3) {
                    tFell = t;
                    break;
                }
                prevX = fp.getX();
                prevH = h;
            }

            int spent = cobbleBefore - carrying(fp, Items.COBBLESTONE);
            boolean crossed = fp.getX() < cx - GAP - 0.5 && Math.abs(fp.getY() - standAbs) <= 0.4;
            String st = NavWalk.step(bot);
            BotDebugContracts.Hands hands = bot.body().hands();

            reporter.publishEntry(
                    "rig",
                    String.format(
                            Locale.ROOT,
                            "pad y=%d stand y=%d | pad x=[%d,%d] (%d cells) |"
                                    + " gap x=%d..%d (%d clear cells below, catch floor y=%d) | deck x<=%d | goal %s",
                            padAbs,
                            standAbs,
                            cx,
                            cx + padLen - 1,
                            padLen,
                            cx - 1,
                            cx - GAP,
                            VOID_DEPTH - 1,
                            padAbs - VOID_DEPTH,
                            cx - GAP - 1,
                            goal.toShortString()));
            reporter.publishEntry(
                    "takeoff.x",
                    takeoffTick < 0
                            ? "never jumped"
                            : String.format(
                                    Locale.ROOT,
                                    "%.4f (lip cell x=%d, so the jump fired %.4f cells short of the lip)",
                                    takeoffX,
                                    cx,
                                    takeoffX - cx));
            reporter.publishEntry(
                    "takeoff.h",
                    takeoffTick < 0
                            ? "never jumped"
                            : String.format(
                                    Locale.ROOT,
                                    "before %.4f -> after %.4f (a sprint-jump shows about 0.35; a x0.91 ratio is pure air decay)",
                                    takeoffHBefore,
                                    takeoffHAfter));
            reporter.publishEntry(
                    "takeoff.navigator",
                    nav.plan().flatMap(PlanCursor::parkourTakeoff).orElse("none"));
            reporter.publishEntry("gap.move", gapMove == null ? "never got past the lip" : gapMove);
            reporter.publishEntry("gap.solidCells", String.valueOf(gapSolidCells(level, cx, cz, padAbs, halfW)));
            reporter.publishEntry("plan.first", firstPlan == null ? "none - the run never held a path" : firstPlan);
            reporter.publishEntry("plan.last", NavWalk.trace(nav, "planTally"));
            reporter.publishEntry("place.spent", spent + " cobblestone (started with " + cobbleBefore + ")");
            reporter.publishEntry(
                    "ticks",
                    String.format(
                            Locale.ROOT,
                            "ran %d | reached lip cell t=%s | stood on deck" + " t=%s | left the pad t=%s",
                            t,
                            tLip < 0 ? "never" : String.valueOf(tLip),
                            tDeck < 0 ? "never" : String.valueOf(tDeck),
                            tFell < 0 ? "no" : String.valueOf(tFell)));
            TestMod.LOG.info(
                    "[wd.parkourVoidLongRunway] step={} pos=({},{},{}) minY={} crossed={} spent={} takeoffX={}"
                            + " takeoffH={}->{} gapMove={} tLip={} tDeck={}",
                    st,
                    fp.getX(),
                    fp.getY(),
                    fp.getZ(),
                    minY,
                    crossed,
                    spent,
                    takeoffX,
                    takeoffHBefore,
                    takeoffHAfter,
                    gapMove,
                    tLip,
                    tDeck);
            return new Leap(
                    minY,
                    crossed,
                    spent,
                    tLip,
                    tDeck,
                    hands.placeTally()
                            + String.format(
                                    Locale.ROOT,
                                    " | end step=%s pos=(%.3f,%.3f,%.3f)",
                                    st,
                                    fp.getX(),
                                    fp.getY(),
                                    fp.getZ()));
        });

        // Soft checks so every verdict is reported: "fell into the void" and "stood still on the lip" are opposite
        // outcomes and one merged line prints them the same.
        game.check(
                leap.minY() > padAbs - 3,
                "A must not fall: lowest y of the whole run " + leap.minY() + " (pad y=" + padAbs
                        + ", criterion minY > " + (padAbs - 3) + ")");
        game.check(
                leap.crossed(),
                "B must get across (criterion x < " + (cx - GAP - 0.5) + " and |y - " + standAbs
                        + "| <= 0.4; leaping and bridging both count, standing still does not): " + leap.tally());
        game.check(
                leap.spent() == 0,
                "C must have LEAPT, not bridged: " + leap.spent() + " cobblestone spent" + " (place.tally="
                        + leap.tally() + ")");
        game.check(
                leap.tDeck() >= 0 && leap.tLip() >= 0 && leap.tDeck() - leap.tLip() <= LEAP_TICKS,
                "D must have leapt FAST: from reaching the lip cell t=" + (leap.tLip() < 0 ? "never" : leap.tLip())
                        + " to standing on the deck t=" + (leap.tDeck() < 0 ? "never" : leap.tDeck()) + ", budget "
                        + LEAP_TICKS + " ticks (never reaching the deck is a violation too)");
    }

    /**
     * <b>Planner-only.</b> The same gap twice, over a ONE-cell launch pad and over a SIX-cell one, asking only what A*
     * proposes.
     *
     * <p>A leap over two cells of void is priced at {@code Parkour3}'s cost 32 — <b>the sprint-jump maximum</b>. The
     * only precondition the move enforces about the run-up is {@code Move.hasRunway}, whose entire body is
     * {@code return w.isSolid(from.offset(0, -1, 0));} — which a 1x1 pillar top satisfies exactly as well as a
     * twelve-cell runway. So the planner proposes, from a standing start on an isolated block, a leap costed for a
     * player at full sprint. This test asserts a DIFFERENCE between the two searches; its long half must stay green
     * through any fix, since a run-up model that refuses both pads has deleted the move.
     *
     * <p><b>The inventory is cleared</b>, so the bot's view answers {@code canPlace} false and no bridge can appear in
     * either plan; otherwise the short half could go green by routing a bridge.
     */
    @Test
    void parkourVoidRunwayGate(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 16) Plot plot, TestReporter reporter) {
        final int standY = PAD_Y + 1;
        final int shortDz = -4;
        final int longDz = 4; // two 1-wide lanes, 8 cells apart across the void

        Settings.pinUntimed(game).set("allowBreak", false).set("allowParkour4", false);
        catchFloor(plot, 6);
        buildPadGapDeck(plot, shortDz, 1, 0);
        buildPadGapDeck(plot, longDz, 6, 0);

        SceneBot bot = SceneBot.spawn(game, plot.at(0, standY, shortDz)); // spawned empty: no bridge bypass
        final int cx = plot.origin().getX();

        SinglePlan shortPlan = bot.plan(PlanParams.of(bot.ref(), Targets.block(plot.at(-GAP - 6, standY, shortDz)))
                .from(Targets.pos(plot.at(0, standY, shortDz))));
        SinglePlan longPlan = bot.plan(PlanParams.of(bot.ref(), Targets.block(plot.at(-GAP - 6, standY, longDz)))
                .from(Targets.pos(plot.at(5, standY, longDz))));

        boolean shortLeaps = crossesGapByParkour(shortPlan, cx);
        boolean longLeaps = crossesGapByParkour(longPlan, cx);
        // Move.hasRunway's whole body: is the cell under the launch foot solid, in the planner's view. It answers the
        // same for a one-cell pillar and a six-cell runway, which is the finding, not an aside.
        boolean shortRunway = NavWalk.cell(bot, plot.at(0, standY - 1, shortDz)).solid();
        boolean longRunway = NavWalk.cell(bot, plot.at(0, standY - 1, longDz)).solid();
        BotDebugContracts.Hands hands = bot.body().hands();

        reporter.publishEntry("short.plan", planShape(shortPlan));
        reporter.publishEntry("long.plan", planShape(longPlan));
        reporter.publishEntry("hasRunway", "short lip " + shortRunway + ", long lip " + longRunway);
        reporter.publishEntry("canPlace", hands.canPlace() + ", placeableBlockCount " + hands.placeableBlocks());
        TestMod.LOG.info(
                "[wd.parkourVoidRunwayGate] short.reached={} short.parkour={} long.reached={} long.parkour={}"
                        + " hasRunway(short)={} hasRunway(long)={}",
                shortPlan.goalReached(),
                shortLeaps,
                longPlan.goalReached(),
                longLeaps,
                shortRunway,
                longRunway);

        game.check(
                !shortLeaps,
                "A a 1-cell launch pad must NOT plan a parkour across the void (a standing start, priced"
                        + " at the sprint-jump maximum 32): " + planShape(shortPlan));
        game.check(
                longLeaps,
                "B a 6-cell launch pad MUST plan a parkour across the void (otherwise 'model the run-up'"
                        + " has deleted the move rather than given it a precondition): " + planShape(longPlan));
    }

    /**
     * {@code allowParkourPlace}: a two-cell leap onto a cell with no floor, the block under it placed mid-air. A 1-wide
     * pad ends at x=0, x=-1 is void, and the landing cell x=-2 has no floor; its only neighbour is one block beside it
     * at z=+1, which is the place support ({@code ParkourPlace.eval}: {@code canParkourPlace()}, a runway, a real gap,
     * and {@code Move.hasPlaceSupport} for the landing floor) and the goal. No leap reaches that block from the pad, so
     * the way there is through the landing. (A goal on the floorless cell itself is snapped by {@code goto} to the
     * nearest standable cell.) Off, {@code mc.debug.plan} holds no {@code parkourPlace2} edge; on, it does, and the
     * walk ends on the goal having spent one block, which sits under the landing cell.
     */
    @Test
    void parkourPlaceLeapsOntoItsOwnBlock(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 96, z = 16) Plot plot, TestReporter reporter) {
        final int standY = PAD_Y + 1;
        catchFloor(plot, 2);
        plot.fill(0, PAD_Y, 0, 9, PAD_Y, 0, Blocks.STONE); // launch pad, lip at x=0; x=-1 is void
        plot.place(-2, PAD_Y, 1, Blocks.STONE); // the support beside the landing floor, and the goal
        BlockPos floor = plot.at(-2, PAD_Y, 0);
        BlockPos goal = plot.at(-2, standY, 1);

        Settings settings = Settings.pinUntimed(game).set("allowBreak", false).set("allowParkour4", false);
        SceneBot bot = SceneBot.spawn(game, plot.at(6, standY, 0));
        bot.give(Items.COBBLESTONE, 16);
        PlanParams ask = PlanParams.of(bot.ref(), Targets.block(goal));

        SinglePlan off = bot.plan(ask);
        settings.set("allowParkourPlace", true);
        SinglePlan on = bot.plan(ask);
        reporter.publishEntry("plan.off", planShape(off));
        reporter.publishEntry("plan.on", planShape(on));
        game.check(
                off.edges().stream().noneMatch(e -> e.move().orElse("").startsWith("parkourPlace")),
                "with allowParkourPlace off the plan holds a parkourPlace edge: " + planShape(off));
        game.check(
                on.edges().stream().anyMatch(e -> e.move().orElse("").startsWith("parkourPlace")),
                "with allowParkourPlace on the plan holds no parkourPlace edge: " + planShape(on));

        int padAbs = plot.at(0, PAD_Y, 0).getY();
        int lipX = plot.at(0, PAD_Y, 0).getX();
        String[] gapMove = {null};
        double[] walk = game.onServer(s -> {
            ServerPlayer fp = bot.player();
            SimProbes.grantWaterEffects(fp);
            fp.getInventory().selected = 0;
            bot.goTo(Targets.block(goal));
            double minY = fp.getY();
            int t = 0;
            for (; t < 400 && bot.busy(); t++) {
                bot.tick();
                minY = Math.min(minY, fp.getY());
                if (gapMove[0] == null && fp.getX() < lipX) {
                    gapMove[0] =
                            bot.navigator().plan().flatMap(PlanCursor::move).orElse("none");
                }
                if (fp.getY() < padAbs - 3) {
                    break;
                }
            }
            bot.park();
            return new double[] {minY, fp.getX(), fp.getY(), fp.getZ(), 16 - carrying(fp, Items.COBBLESTONE), t};
        });
        boolean floorPlaced =
                game.onServer(s -> bot.level().getBlockState(floor).is(Blocks.COBBLESTONE));
        String walked = String.format(
                Locale.ROOT,
                "minY=%.2f end=(%.2f,%.2f,%.2f) spent=%d ticks=%d move past the lip=%s",
                walk[0],
                walk[1],
                walk[2],
                walk[3],
                (int) walk[4],
                (int) walk[5],
                gapMove[0]);
        reporter.publishEntry("walk", walked);
        game.check(walk[0] > padAbs - 1, "the bot fell during the leap: lowest y " + walk[0] + ", pad y " + padAbs);
        // Within a block of the goal's centre, at its height: the navigator calls a walk arrived at the edge of the
        // cell before it, which here is the placed block itself.
        game.check(
                Math.hypot(walk[1] - (goal.getX() + 0.5), walk[3] - (goal.getZ() + 0.5)) <= 1.0
                        && Math.abs(walk[2] - goal.getY()) <= 0.4,
                "the bot did not end standing at the goal " + goal.toShortString() + ": " + walked);
        game.check(
                (int) walk[4] == 1,
                "the leap spent " + (int) walk[4] + " blocks, not the one landing block: " + walked);
        game.check(
                floorPlaced,
                "no cobblestone under the landing cell " + floor.toShortString() + ": " + walked + " | "
                        + planShape(on));
    }

    /** True when the plan gets from the pad side to the deck side in ONE parkour edge. */
    private static boolean crossesGapByParkour(SinglePlan plan, int lipX) {
        List<PlanEdge> edges = plan.edges();
        for (int i = 1; i < edges.size(); i++) {
            String move = edges.get(i).move().orElse(null);
            if (move == null || !move.startsWith("parkour")) {
                continue;
            }
            if (edges.get(i - 1).to().x() >= lipX && edges.get(i).to().x() <= lipX - GAP - 1) {
                return true;
            }
        }
        return false;
    }

    /** Node count, goal-reached, cost and the move histogram — the shape the navigator's {@code planTally} prints. */
    private static String planShape(SinglePlan plan) {
        Map<String, Integer> byMove = new TreeMap<>();
        for (PlanEdge e : plan.edges()) {
            e.move().ifPresent(m -> byMove.merge(m, 1, Integer::sum));
        }
        List<BlockPosition> path = plan.cells();
        return "goalReached=" + plan.goalReached() + " nodes=" + path.size()
                + " cost="
                + String.format(Locale.ROOT, "%.1f", (double) plan.search().finalCost())
                + " moves " + (byMove.isEmpty() ? "{}" : byMove.toString())
                + (path.isEmpty()
                        ? ""
                        : " last=" + Targets.cell(path.get(path.size() - 1)).toShortString());
    }
}
