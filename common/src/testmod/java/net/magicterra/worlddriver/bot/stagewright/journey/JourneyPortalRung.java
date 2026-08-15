package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.pathfinder.CapabilityProfile;
import net.magicterra.worlddriver.bot.pathfinder.constraints.NoBreak;
import net.magicterra.worlddriver.bot.process.Intent;
import net.magicterra.worlddriver.bot.process.IntentProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * PORTAL_LIT — ten obsidian in a frame, at the lava's own level, then a flint-and-steel.
 *
 * <p>Moved out of {@code WorldDriverJourneyScenes} the same mechanical way {@link JourneyShaft} and
 * {@link JourneyTerrain} were, and for the same reason: the ladder file is over its source budget
 * and this is the one rung large enough to carry its own file. Nothing changed in the move. The
 * scene is still registered by the ladder, still runs on {@link JourneyRig}, and still borrows the
 * ladder's walking and aiming helpers — which is why a handful of those are package-private now
 * rather than private.
 *
 * <p>{@link JourneyForge} holds the mould's geometry and the rules about where it may go; this
 * holds the body's half of it — sink a shaft, hollow an alcove under the lake, and then make ten
 * round trips between a pool twelve blocks up and a cell that is cut open one at a time.
 */
public final class JourneyPortalRung {

    private JourneyPortalRung() {}


    /** The mould's shape and the rules about where it may go — see {@link JourneyForge}. */
    private static final int[][] RING = JourneyForge.RING;

    /**
     * Build and light the portal, without a diamond pickaxe and without staging.
     *
     * <p>The technique is the one {@code wd.serverBuildsAndLightsAPortal} proves end to end, and the
     * three shapes it cost to find are worth restating where the rung uses them:
     *
     * <ul>
     *   <li><b>The water is carried, not left.</b> A source goes into the interior cell ADJACENT to
     *       the cell being cast, which reproduces the single-cast geometry for every cell and needs
     *       no flow at all — the conversion is a neighbour update, not a fluid tick. One bucket then
     *       suffices because it is empty exactly when it needs to be: after placing the water (go
     *       fetch lava) and again after pouring the lava (take the water back).</li>
     *   <li><b>The top pair cannot cast against the interior.</b> Vanilla looks above the lava and
     *       to its four sides, never below, so those two cast against a notch carved one block
     *       higher — which is why this rung hollows TWELVE cells and not ten.</li>
     *   <li><b>A scoop takes the source.</b> Ten casts need ten DISTINCT lava cells, so the rung
     *       enumerates the pool rather than returning to one spot.</li>
     * </ul>
     *
     * <p><b>Why underground.</b> Obsidian cannot be carried, so the frame is cast where it stands.
     * At the surface each of the ten fills would be a climb out of a 36-block shaft — the mechanism
     * this ladder has the least confidence in. At the lava's own level the rock is its own mould:
     * the frame is carved into a face, the stone behind it is the backing every bucket aims at, and
     * the ten walks are a few blocks each.
     */
    static void portalLit(SceneContext ctx) {
        JourneyRig rig = JourneyRig.enter(ctx, JourneyStage.PORTAL_LIT);
        if (WorldDriverJourneyScenes.requireSurvey(ctx, rig)) return;
        rig.generousPathfinding();

        // The LAKE, not the fill point. firstLava is the one the OBSIDIAN rung empties into its
        // bucket, and a bucket takes the source block itself — five runs died here reporting zero
        // sources at a landmark that had genuinely ceased to exist. Falls back only so that an old
        // baked route still runs and still says which landmark it used.
        boolean haveLake = !JourneyRoute.lavaLake.equals(JourneyRoute.UNSURVEYED);
        BlockPos lava = haveLake ? JourneyRoute.lavaLake : JourneyRoute.firstLava;
        rig.evidence("lava.landmark", (haveLake
                ? "lavaLake " + lava.toShortString() + "（勘测到 " + JourneyRoute.lavaLakeSources + " 格源块）"
                : "回退到 firstLava " + lava.toShortString() + " —— 没有勘测到够十格的岩浆湖"));
        rig.evidence("bucket.before", rig.carrying("minecraft:bucket"));
        rig.evidence("flintAndSteel.before", rig.carrying("minecraft:flint_and_steel"));
        rig.evidence("cobblestone.before", rig.carrying("minecraft:cobblestone"));
        if (rig.carrying("minecraft:flint_and_steel") < 1) {
            ctx.fail("没有打火石：PORTAL_KIT 应当留下一把（当前 0）");
            return;
        }
        if (rig.carrying("minecraft:bucket") < 1 && rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("没有桶：OBSIDIAN 用完之后应当把空桶带回来（bucket=0, water_bucket=0, lava_bucket="
                    + rig.carrying("minecraft:lava_bucket") + "）");
            return;
        }

        // Water first, at the surface, while there is still water to be had: the whole rung below
        // ground runs on one source and there is none down there to go back for.
        fillWaterAtTheSurface(ctx, rig, () -> descendToTheForge(ctx, rig, lava));
    }

    /** Put water in the bucket before going under. OBSIDIAN ends beside standing water, so this is
     *  normally one aim away; the walk is the fallback for a run that ended somewhere else. */
    private static void fillWaterAtTheSurface(SceneContext ctx, JourneyRig rig, Runnable then) {
        if (rig.carrying("minecraft:water_bucket") >= 1) {
            rig.evidence("water.alreadyCarried", "是");
            then.run();
            return;
        }
        BlockPos water = JourneyTerrain.shallowWaterNear(rig, 24);
        if (water == null) {
            BlockPos w = JourneyRoute.firstWater;
            rig.attempting("身边没有水，走到勘测过的水域装水");
            WorldDriverJourneyScenes.walkToColumn(rig, "water", w.getX(), w.getZ(), 0, 16_000,
                    () -> scoopWater(ctx, rig, JourneyTerrain.shallowWaterNear(rig, 12), then),
                    () -> ctx.fail("走不到 firstWater " + w.toShortString()
                            + "：停在 " + rig.player().blockPosition()));
            return;
        }
        scoopWater(ctx, rig, water, then);
    }

    private static void scoopWater(SceneContext ctx, JourneyRig rig, BlockPos water, Runnable then) {
        if (water == null) {
            ctx.fail("装不到水：附近没有底下实心的水面（身体在 " + rig.player().blockPosition() + "）");
            return;
        }
        rig.attempting("装一桶水带下去 —— 底下没有水可回头取");
        rig.settle(new IntentProcess(new Intent(new Goal.Near(water, 2))), 2_000, () -> {
            BlockPos aim = JourneyTerrain.shallowWaterNear(rig, 8);
            if (aim == null) aim = water;
            WorldDriverJourneyScenes.holdForUse(rig, Items.BUCKET, "waterFill");
            rig.body().avatar().aimAtBlock(aim);
            final BlockPos at = aim;
            rig.settle(new HoldStill(2), 10, () -> {
                // Increment, for the same reason `scoop` measures one — see its own note. The
                // short-circuit above means `before` is 0 today, so this changes nothing now and
                // stops being a lie the moment the body arrives here already holding water.
                int before = rig.carrying("minecraft:water_bucket");
                rig.evidence("waterFill.result", String.valueOf(rig.body().avatar().useItemInHand()));
                int after = rig.carrying("minecraft:water_bucket");
                rig.evidence("water_bucket", after);
                rig.evidence("waterFill.cellAfter", String.valueOf(ctx.level().getBlockState(at).getBlock()));
                if (after <= before) {
                    ctx.fail("装水失败：瞄了 " + at.toShortString() + "，这一次没装上（water_bucket "
                            + before + "→" + after + "）—— "
                            + "这一级底下全程靠这一桶水，装不上就没有下一步");
                    return;
                }
                then.run();
            });
        });
    }

    /** Walk to the surveyed lava and sink to its level, reusing OBSIDIAN's own descent. */
    /**
     * The floor the mould is carved on — deliberately NOT the lava's own level.
     *
     * <p>The frame is five cells tall plus a row of cap notches, and the technique is to carve that
     * shape out of solid rock so every cell has a back for the buckets to aim at. Laid at the lava's
     * level that holds underground and fails at a surface lake: run 10 walked to this seed's only
     * usable lake, at <b>y=63</b>, cast the first two cells and died on the third with
     * {@code 想放 -9,65,23 … 现在是 air} — the upper rows were open sky, so the water ran off.
     *
     * <p>Seven below the lava puts all twelve cells in rock whatever the lake's depth, and costs a
     * seven-block climb per fill against the thirty-six the OBSIDIAN rung already climbs carrying
     * lava — well inside proven ground.
     */
    private static int forgeFloorY(BlockPos lava) {
        return lava.getY() - JourneyForge.BELOW_LAVA;
    }

    /** Where the staircase starts and ends, and which way it runs.
     *
     * <p>Both ends are walked to BY NAME — {@link #goUpToThePool} asks for {@link #stairTop} and
     * {@link #returnToTheForge} for {@link #stairBottom} — which is the whole point of cutting a
     * staircase instead of a shaft: the two legs of every cast become one {@code IntentProcess} walk
     * each, with no scripted climb and no scripted descent to go wrong between them. */
    private static BlockPos stairTop, stairBottom;
    private static Direction stairDir = Direction.SOUTH;

    /** Every fourth step is waypoint enough: consecutive waypoints are four blocks apart INSIDE the
     *  stairwell, and there is no shorter way between two such cells that leaves it. */
    private static final int STAIR_WAYPOINT_STRIDE = 4;

    /**
     * The flight as a list of waypoints, top-first when {@code down}.
     *
     * <p>Waypoints down the flight ITSELF, not just its two ends, and that is a measurement rather
     * than a precaution. Given {@code Goal.Block(stairBottom)} the walker takes the shortest line it
     * can see, and from the top of the stairs the shortest line is ACROSS THE SURFACE: measured
     * twice, the body walked to {@code -10,66,34} — ground level directly above the staircase at
     * z=34 — and then re-searched for a cell eight blocks below it through untouched rock, every two
     * seconds, until the leg ran out. Naming the mouth fixed the first half and the second half did
     * it again from the mouth. These are what make the staircase the route and not merely a hole
     * that happens to connect two places.
     */
    private static List<BlockPos> stairRoute(boolean down) {
        List<BlockPos> cells = JourneyStairs.cells;
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < cells.size(); i += STAIR_WAYPOINT_STRIDE) out.add(cells.get(i));
        BlockPos last = cells.get(cells.size() - 1);
        if (!out.get(out.size() - 1).equals(last)) out.add(last);
        if (!down) java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Walk the waypoints in order, best effort. A leg that falls short is not failed here — the
     * caller checks the height it actually reached, which is the only thing that matters.
     *
     * <p><b>The walk may not dig.</b> {@link NoBreak} is on every one of these intents, and it is
     * there because of what happened without it. On the run that stopped this rung at four casts,
     * the body left the flight on a return leg, fell through the lake's one-block crust into the
     * cave below it, and — with the pathfinder free to plan digs — mined its way back to daylight
     * through {@code -9,64,22}: the block holding up its own second step. Every later ascent then
     * walked into a two-deep hole where a step used to be, and the rung reported
     * {@code 走不上楼梯} about a staircase it had eaten itself.
     *
     * <p>These legs cross ground the rung cut with its own pick and nothing else, so a dig here is
     * never the answer to anything. A leg that genuinely cannot get through now stalls instead,
     * which {@link #walkHome}'s pillar-out recovery already handles and {@link JourneyStairs#faults} now
     * names.
     */
    private static void walkTheStairs(JourneyRig rig, List<BlockPos> route, int i, boolean down,
                                      Runnable then) {
        if (i >= route.size()) { then.run(); return; }
        BlockPos want = route.get(i);
        rig.settle(new IntentProcess(new Intent(new Goal.Block(want), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 600, () -> {
            BlockPos got = rig.player().blockPosition();
            double off = Math.sqrt(got.distSqr(want));
            // BOTH CELLS, not just the one the body is standing in. `cast5.returnStopped` read
            // `停在 -9,66,21 … 脚下 cobblestone，身处 air，头顶 air，起跳格 air` — four cells all
            // clear, on a leg that moved zero blocks. They were clear because the body had climbed
            // through them one leg earlier; the cell that stopped it is the one it was trying to
            // REACH, and that cell was not in the message at all.
            if (off > LEG_ARRIVED && flightShortfall == null)
                flightShortfall = "第 " + i + "/" + (route.size() - 1) + " 段：想到 "
                        + want.toShortString() + "，停在 " + got.toShortString() + "，差 "
                        + String.format("%.2f", off) + " 格 —— 身体处："
                        + cellStory(rig.ctx().level(), got, !down) + "；要去的那格："
                        + cellStory(rig.ctx().level(), want, !down);
            walkTheStairs(rig, route, i + 1, down, then);
        });
    }

    /** How far from a waypoint still counts as having arrived. A body standing on the right cell
     *  reads one cell off when its 0.6-wide box straddles the edge — the {@code cell.5.standMissed}
     *  lesson — and a leg that stops a cell short has still walked the flight. */
    private static final double LEG_ARRIVED = 1.5;

    /**
     * The FIRST leg of the current flight that did not arrive, or null. Reset per flight.
     *
     * <p>Both ends of the flight used to read only the height the body finished at, which says the
     * walk failed and nothing about where. The run of 2026-08-15 failed the ascent with
     * {@code lava0.upEnded=-9,56,36} — the stair bottom, which is the FIRST of five waypoints — and
     * the message it produced ({@code 走不上楼梯：停在 …}) reads identically whether the body never
     * left the alcove or climbed four fifths of the flight and stalled.
     */
    private static String flightShortfall;

    /**
     * What holds a cell up, what fills it, and its head room — plus, going UP only, the cell a body
     * must jump THROUGH, which is the last line of {@code StepUp.valid}.
     *
     * <p>{@code upward} is not decoration. Those four cells are {@code StepUp.valid}'s question, and
     * {@code StepUp} takes no part in a descent — so printing 起跳格 on a return leg answers a
     * question nobody asked and reads like an all-clear. That is exactly how `cast5.returnStopped`
     * certified a body that had not moved: 起跳格 air, on a leg that never needed to jump.
     */
    private static String cellStory(ServerLevel level, BlockPos foot, boolean upward) {
        String story = "脚下 " + level.getBlockState(foot.below()).getBlock() + "，身处 "
                + level.getBlockState(foot).getBlock() + "，头顶 "
                + level.getBlockState(foot.above()).getBlock();
        if (!upward) return story;
        BlockPos jump = foot.above(2);
        return story + "，起跳格 " + jump.toShortString()
                + "=" + level.getBlockState(jump).getBlock()
                + (level.getBlockState(jump).blocksMotion() ? "（挡着，跳不起来）" : "");
    }

    /**
     * Put back what the flight has lost, then walk it.
     *
     * <p>Both legs of every cast go through here rather than straight into {@link #walkTheStairs},
     * because the damage is done BETWEEN legs and the only cheap moment to notice is just before
     * the flight is used. {@link JourneyStairs} holds the audit and the repair.
     */
    private static void walkTheFlight(JourneyRig rig, String tag, boolean down, Runnable then) {
        flightShortfall = null;
        JourneyStairs.aboutToWalk(rig, tag);
        List<JourneyStairs.StairFault> faults = JourneyStairs.faults(rig.ctx().level());
        if (faults.isEmpty()) { walkTheStairs(rig, stairRoute(down), 0, down, then); return; }
        rig.evidence(tag + ".stairsBroken", JourneyStairs.report(rig.ctx().level()));
        JourneyStairs.mend(rig, tag, faults, 0,
                () -> walkTheStairs(rig, stairRoute(down), 0, down, then));
    }

    /** Every cell the alcove was hollowed out of — the space the body walks in, and nothing else.
     *  {@link #clearPourLine} is allowed to break inside this and nowhere else, which is what stops
     *  a blocked pour from answering by digging a hole in the mould's own floor. */
    private static Set<BlockPos> forgeCorridor = Set.of();

    /** The corridor cells the carve could not open. Not a failure list — a BASELINE: it is what
     *  makes "solid in the corridor" mean "something put it there" for every later reading. See
     *  {@link #tidyTheAlcove}. */
    private static Set<BlockPos> forgeStuck = Set.of();

    /**
     * Which way to run from the lava — one axis, never a diagonal.
     *
     * <p>Shared by the staircase and the mould so they cannot disagree. The staircase runs AWAY from
     * the pool and the mould's face is cut on the same side, which is what keeps the two of them from
     * meeting: the alcove sits at the foot of the last step, and every step above it is both higher
     * and further back.
     */
    /** Package-visible so a rehearsal can pick a standing spot BY the answer this returns, rather
     *  than re-deriving it. A second copy of this rule is a second thing to keep in step, and the
     *  whole point of the orientation parameter is that the staged side and the carved side agree. */
    static Direction awayFrom(BlockPos lava, BlockPos at) {
        int dx = Integer.signum(at.getX() - lava.getX());
        int dz = Integer.signum(at.getZ() - lava.getZ());
        return Math.abs(at.getX() - lava.getX()) >= Math.abs(at.getZ() - lava.getZ())
                ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /**
     * Cut the way down as a STAIRCASE, and let the walker use it in both directions.
     *
     * <h2>What this replaces, and why</h2>
     *
     * A one-wide vertical shaft. It was the cheapest hole to dig and it has no route: the walker
     * cannot climb one, so both legs of every cast had to be scripted, and each scripted leg failed
     * in its own way. Four separate bugs, all of them the shaft's:
     *
     * <ul>
     *   <li><b>The tower drifted.</b> {@code TowerProcess} places under the body and jumps, and where
     *       the body lands is pinned to nothing. Measured: {@code -9,51,21} to {@code -9,54,23} in
     *       three courses — two cells into the frame's own plane, which it then mined out and filled
     *       with cobblestone on the way up.</li>
     *   <li><b>The drift breached the lake.</b> Off the shaft the tower had to mine fresh rock, and
     *       twelve blocks up that rock is the lava the mould is cut under. {@code drain.0=等了 200
     *       tick 仍有流体：-7,54,21 = lava}, in an alcove far below it, with the corridor set to stone
     *       where the lava met the cast's own water.</li>
     *   <li><b>The descent needed the body exactly over the hole.</b> {@code 回程站到壁龛外面了：…
     *       停在 -9,60,20}, one cell off and nine blocks up, with nowhere legal to dig.</li>
     *   <li><b>And when it dug anyway, it dug outside the alcove</b> — sixty passes of
     *       {@code below=stone → broke=air} at {@code -8,51,20}, because the lava that the breach had
     *       let in kept flowing back and setting.</li>
     * </ul>
     *
     * <p>A staircase costs more blocks and more ticks than a shaft and it is worth it: this rung has
     * a 250 000-tick budget and has never spent a third of it. What it buys is that the descent, the
     * ascent and the return are the same three cells of ordinary walking, so none of the four
     * failures above has anywhere to happen.
     *
     * <h2>The shape</h2>
     *
     * One block along {@code stairDir}, one block down, per step. Three cells are cut for each: the
     * step itself, the cell above it (head room standing there) and the cell above that. The third is
     * not spare — going back UP, the body jumps from a step to the one behind it, and a jump needs
     * clearance two above the feet it starts from. Leaving it out gives a staircase that descends
     * perfectly and cannot be climbed, which is the same rung failure wearing a different hat.
     *
     * <p>Nothing is cut that touches a fluid, checked cell by cell before the pick swings — the
     * lesson of the breach above, applied to the leg that does the digging rather than to the leg
     * that discovered it.
     */
    private static void digStairsDown(SceneContext ctx, JourneyRig rig, int targetY, int budget,
                                      int cap, Runnable then) {
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= targetY) {
            stairBottom = at;
            rig.evidence("stairs.bottom", at.toShortString() + "（" + stairDir + " 向，顶在 "
                    + (stairTop == null ? "?" : stairTop.toShortString()) + "）");
            then.run();
            return;
        }
        if (budget <= 0) {
            ctx.fail("楼梯挖不到底：目标 y=" + targetY + "，试了 " + cap + " 级仍停在 "
                    + at.toShortString() + "（" + stairDir + " 向）");
            return;
        }
        int step = cap - budget;
        BlockPos foot = at.relative(stairDir).below();
        List<BlockPos> cut = List.of(foot, foot.above(), foot.above(2));
        for (BlockPos c : cut) {
            String wet = JourneyShaft.fluidTouching(ctx.level(), c);
            if (wet != null) {
                ctx.fail("楼梯挖不下去：" + c.toShortString() + " 挖开会放出 " + wet
                        + "（身体在 " + at.toShortString() + "，正往 " + stairDir + " 下挖到 y="
                        + targetY + "）—— 这一段石头后面是流体，不能开");
                return;
            }
        }
        rig.evidence("stair." + step, at.toShortString() + " → " + foot.toShortString());
        JourneyStairs.cut(foot);
        cutStairCells(rig, cut, 0, () ->
                rig.settle(new IntentProcess(new Intent(new Goal.Block(foot))), 300, () -> {
            BlockPos now = rig.player().blockPosition();
            if (now.getY() >= at.getY()) {
                // The cells are open and the body has not stepped into them yet. That is a settle,
                // not a failure — the same "breaking the floor is not falling through it" the shaft
                // descent learned — so give it the tick and try the same step again.
                rig.evidence("stair." + step + ".waited", now.toShortString() + " 还没迈下去");
                rig.settle(new HoldStill(20), 40,
                        () -> digStairsDown(ctx, rig, targetY, budget - 1, cap, then));
                return;
            }
            digStairsDown(ctx, rig, targetY, budget - 1, cap, then);
        }));
    }

    private static void cutStairCells(JourneyRig rig, List<BlockPos> cells, int i, Runnable then) {
        if (i >= cells.size()) { then.run(); return; }
        // Top down. The cell two above the step is the one the body can already see; opening it
        // first keeps every following cell adjacent to air, which is what `canBreak` asks for.
        BlockPos c = cells.get(cells.size() - 1 - i);
        if (rig.ctx().level().getBlockState(c).isAir()) { cutStairCells(rig, cells, i + 1, then); return; }
        rig.mineCellOrGiveUp(c, 600, () -> cutStairCells(rig, cells, i + 1, then));
    }

    /** Attempts per block of depth. Four, because a step is three mines and a walk, and a body that
     *  has not stepped down yet costs one of its own. */
    private static final int STAIR_ATTEMPTS_PER_BLOCK = 4;

    /**
     * Walk up to the pool. An ordinary walk, up an ordinary staircase.
     *
     * <p>{@link JourneyShaft#climbOut} used to do this and does not any more — see
     * {@link #digStairsDown} for the four bugs that were all really one bug. The goal is the
     * staircase's own top cell rather than the lake or a Y level, because a named waypoint is a
     * question the pathfinder can answer in one search, and "get to y=63 somehow" is the question
     * that burnt a whole 100 000-node budget every three seconds.
     */
    private static void goUpToThePool(SceneContext ctx, JourneyRig rig, int poolY, String tag,
                                      Runnable then) {
        if (rig.player().blockPosition().getY() >= poolY - 1) { then.run(); return; }
        if (stairTop == null) {
            ctx.fail("没有楼梯顶坐标：descendToTheForge 没有记下来，走不上去装岩浆");
            return;
        }
        rig.evidence(tag + ".up", rig.player().blockPosition().toShortString() + " → 楼梯顶 "
                + stairTop.toShortString());
        walkTheFlight(rig, tag, false, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".upEnded", here.toShortString() + "（楼梯顶 "
                    + stairTop.toShortString() + "）");
            if (flightShortfall != null) rig.evidence(tag + ".upStopped", flightShortfall);
            if (here.getY() < poolY - 1) {
                // The audit, not a guess. The two guesses this message used to make — "a step is
                // blocked?" "it cannot make the jump?" — were both wrong the one time the message
                // was read: the flight had lost the block UNDER a step, which is invisible from
                // above because the cell reads air whether or not anything holds it up.
                ctx.fail("走不上楼梯：停在 " + here.toShortString() + "，楼梯顶 "
                        + stairTop.toShortString() + " 在 y=" + stairTop.getY()
                        + " —— " + (flightShortfall == null ? "每一段都走到了" : flightShortfall)
                        + "；楼梯自检：" + JourneyStairs.report(ctx.level())
                        + "（本级共自检 " + JourneyStairs.tally() + "）");
                return;
            }
            then.run();
        });
    }

    /**
     * Walk back down to the mould. The mirror of {@link #goUpToThePool}, and equally unremarkable.
     *
     * <p>Placing stays OFF across it, which is the one thing this leg still has to say: everything
     * downstream is a pour, and a pathfinder that paves the alcove on its way in takes away the cell
     * the pour has to stand in.
     */
    private static void returnToTheForge(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                         Runnable then) {
        returnToTheForge(ctx, rig, floorY, tag, RETURN_TRIES, then);
    }

    /** How many times the return may try. Three: one for the walk and two for a body that fell into
     *  the hole its own bucket left in the lake — see the recovery leg below. */
    private static final int RETURN_TRIES = 3;

    private static void returnToTheForge(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                         int tries, Runnable then) {
        BotConfig.allowPlace = false;
        BlockPos at = rig.player().blockPosition();
        // HOME IS THE ALCOVE, not a height. Height was a fine proxy while the only way to be in the
        // mould was to be standing on its floor, and `liftInPlace` broke that: a body up a
        // one-block pillar beside the cell it just poured into is at y=58 in a mould whose floor is
        // y=56, so run 41 — which had cast NINE cells, every fill from the station and not one
        // drowning — reported `走不回模腔：停在 -11,58,36` about a body already standing in it.
        if (at.getY() <= floorY + 1 || forgeCorridor.contains(at)) { then.run(); return; }
        if (stairBottom == null) {
            ctx.fail("没有楼梯底坐标：descendToTheForge 没有记下来，回不到模腔");
            return;
        }
        rig.evidence(tag + ".return", at.toShortString() + " → 楼梯口 " + stairTop.toShortString()
                + " → 楼梯底 " + stairBottom.toShortString() + "（模腔地板 y=" + floorY + "）");
        // LET THE LAKE CLOSE FIRST. A filled bucket takes a source out and leaves an air cell in the
        // middle of the lava, and air is what the pathfinder plans through — so the route home ran
        // straight into the hole the fill had just made, and the lava flowed back in on top of the
        // body. Sixty ticks is longer than lava takes to spread one cell, so by the time the route
        // is planned the pit reads as what it is.
        rig.settle(new HoldStill(60), 80, () -> walkHome(ctx, rig, floorY, tag, tries, then));
    }

    private static void walkHome(SceneContext ctx, JourneyRig rig, int floorY, String tag,
                                 int tries, Runnable then) {
        // VIA THE STAIRWELL MOUTH, not straight at the bottom. The bottom is ten blocks down at the
        // far end of fifteen steps, and asked for directly the walker takes the shortest line it can
        // see — overland. Measured: the body walked to -9,66,34, which is the surface directly ABOVE
        // the staircase at z=34, and then re-searched for -9,57,36 every two seconds from a spot
        // separated from it by eight blocks of untouched rock. The stairs are a corridor and a
        // corridor is entered at its mouth; naming the mouth turns one impossible search into two
        // easy ones.
        walkTheFlight(rig, tag, true, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".returnedY", here.getY() + "（楼梯底 y=" + stairBottom.getY()
                    + "，身体 " + here.toShortString() + "）");
            if (flightShortfall != null) rig.evidence(tag + ".returnStopped", flightShortfall);
            if (here.getY() > floorY + 1) {
                // A BODY THAT CANNOT WALK TO THE STAIRS IS USUALLY IN A HOLE IT DUG ITSELF.
                //
                // Filling a bucket takes a lava SOURCE out of the lake, which leaves a pit where a
                // source used to be — and the walk back crosses the lake's own rim. Run 33 measured
                // it at the third cast: `lava2.aimsAt=-11,63,21 lava 源块=true`, `CONSUME`, and then
                // `cast2.return` starting from `-11,60,21` — three blocks under the surface, inside
                // the pit, with placing off so it could not build its way out. Every waypoint of the
                // flight then failed in turn and the rung reported the last one.
                //
                // So the recovery is the one thing the descent deliberately forbids: let it place.
                // That is safe HERE and nowhere else — the mouth is ten blocks above the alcove and
                // fifteen across, so a pillar built to climb out of the lake cannot land in the room
                // the pours have to stand in, and the leg turns placing off again on its way down.
                if (tries > 1) {
                    rig.evidence(tag + ".returnStuck." + tries, here.toShortString()
                            + " 走不到楼梯（多半掉进了自己舀空的岩浆坑）—— 开着放置权垒回地面 y="
                            + stairTop.getY() + " 再走去楼梯口 " + stairTop.toShortString());
                    // TOWER, not walk. Asking the walker again is a retry that changes nothing: it
                    // failed because there is no walkable route out of a pit, and run 34 measured
                    // exactly that — `cast2.returnStuck.2` at `-10,60,21`, then two thousand ticks of
                    // pathing, then the same cell. What gets a body out of a hole is the scripted
                    // pillar the shaft already owns, and it needs placing, which is why this leg is
                    // the one place in the casting phase that turns it back on.
                    BotConfig.allowPlace = true;
                    JourneyShaft.climbOut(rig, stairTop.getY(), () ->
                        rig.settle(new IntentProcess(new Intent(new Goal.Block(stairTop))), 2_000, () -> {
                            rig.evidence(tag + ".backToMouth", rig.player().blockPosition().toShortString()
                                    + "（楼梯口 " + stairTop.toShortString() + "）");
                            returnToTheForge(ctx, rig, floorY, tag, tries - 1, then);
                        }));
                    return;
                }
                ctx.fail("走不回模腔：停在 " + here.toShortString() + "，楼梯底 "
                        + stairBottom.toShortString() + " 在 y=" + stairBottom.getY()
                        + " —— 带着一桶岩浆停在半路，浇下去只会浇进楼梯。"
                        + (flightShortfall == null ? "每一段都走到了" : flightShortfall)
                        + "；楼梯自检：" + JourneyStairs.report(ctx.level()));
                return;
            }
            then.run();
        });
    }

    private static void descendToTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava) {
        rig.attempting("背着一桶水走到岩浆柱，挖一段楼梯下到岩浆层");
        WorldDriverJourneyScenes.walkToColumn(rig, "lava", lava.getX(), lava.getZ(), 0, 24_000, () -> {
            BlockPos at = rig.player().blockPosition();
            final int surfaceY = JourneyTerrain.daylightY(rig, at);
            rig.evidence("forge.surfaceY", surfaceY + "（脚下 y=" + at.getY() + "）");
            if (at.getY() <= forgeFloorY(lava) + 1) { carveTheForge(ctx, rig, lava, surfaceY); return; }
            ServerLevel level = ctx.level();
            Map<String, Integer> rejected = new java.util.LinkedHashMap<>();
            BlockPos dig = JourneyTerrain.pickDigColumn(level, lava, surfaceY, rejected);
            if (dig == null) {
                ctx.fail("岩浆柱周围没有可下挖的柱子（目标 " + lava.toShortString()
                        + "，地表 y=" + surfaceY + "）——各项否决计数：" + rejected);
                return;
            }
            WorldDriverJourneyScenes.stepOntoDiggableColumn(rig, dig, lava, surfaceY,
                    WorldDriverJourneyScenes.MAX_WALK_ATTEMPTS, () -> {
                BotConfig.allowPlace = false;
                BlockPos start = rig.player().blockPosition();
                stairTop = start;
                JourneyStairs.reset(start);
                stairDir = awayFrom(lava, start);
                int depth = Math.max(0, start.getY() - forgeFloorY(lava));
                int cap = depth * STAIR_ATTEMPTS_PER_BLOCK + 40;
                rig.evidence("stairs.top", start.toShortString() + " 往 " + stairDir + " 下 "
                        + depth + " 级到 y=" + forgeFloorY(lava) + "（给 " + cap + " 次）");
                digStairsDown(ctx, rig, forgeFloorY(lava), cap, cap, () -> {
                    rig.evidence("forge.landedY", rig.player().blockPosition().getY());
                    // The baseline the twenty later audits are read against. A flight that is
                    // already faulty the moment it is cut is a digging bug; one that goes faulty
                    // later is a ferrying bug, and without this line the two read alike.
                    rig.evidence("stairs.asCut", JourneyStairs.report(ctx.level()));
                    carveTheForge(ctx, rig, lava, surfaceY);
                });
            }, () -> ctx.fail("站不到可下挖的柱子上：想去 " + dig.getX() + "," + dig.getZ()
                    + "，停在 " + rig.player().blockPosition()));
        }, () -> ctx.fail("走不到岩浆柱：目标 " + lava.getX() + "," + lava.getZ()
                + "，停在 " + rig.player().blockPosition()));
    }

    /**
     * How many times the forge may be cut deeper when its shell will not hold, and by how much.
     *
     * <p>Five blocks a go, three goes. Deepening is the ONLY move that answers a wet ceiling: pushing
     * the frame further along {@code away} makes the excavation wider under the same lake, which is
     * what the previous version did and why run 21 carved its alcove's roof out from under a surface
     * pool. Fifteen blocks of extra descent is inside what this rung already pays for the shaft, and
     * the loop stops rather than digging to bedrock because a shell that is still wet fifteen blocks
     * down is a different finding and should read as one.
     */
    private static final int FORGE_DEEPENINGS = 3;
    private static final int FORGE_DEEPEN_BY = 5;

    /**
     * Hollow the alcove the casting is done from, and the twelve cells of the frame in its far wall.
     *
     * <p>The face is put on the side of the body AWAY from the pool, so that nothing carved opens
     * into lava — the one mistake down here that ends the run rather than costing it a retry.
     */
    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY) {
        carveTheForge(ctx, rig, lava, surfaceY, FORGE_DEEPENINGS);
    }

    private static void carveTheForge(SceneContext ctx, JourneyRig rig, BlockPos lava, int surfaceY,
                                      int deepenings) {
        BlockPos at = rig.player().blockPosition();
        // The staircase's own direction, not a fresh guess. They are the same axis by construction —
        // the stairs ran away from the pool and the body is standing at their foot — but saying so
        // once removes the case where a body that stopped a cell short computes the OTHER axis and
        // carves the mould back across its own way home.
        Direction away = stairDir;
        rig.evidence("forge.away", away + "（楼梯方向；从岩浆看这里是 "
                + awayFrom(lava, at) + "）");
        // Two questions decide where the mould goes, and only one of them used to be asked.
        //
        // HOW FAR OUT (`push`) answers "is there fluid in what I am about to dig". That is a real
        // question and this loop still asks it.
        //
        // HOW DEEP answers "is there fluid in what will be HOLDING IT IN", and nothing asked it. The
        // alcove is seven cells tall and its floor was fixed at seven below the lava, so beside a
        // SURFACE lake its ceiling is the lake's own floor. Run 21 carved exactly that: sixty-three
        // cells opened cleanly, and then cell zero read `-9,56,23 = lava` with the water cell beside
        // it lava too — the pool had come in through the roof, into a mould whose every cell had
        // tested dry. `JourneyForge.blocked` now asks both, and a wet shell is answered by digging
        // DEEPER, because pushing sideways only makes the excavation wider under the same lake.
        ServerLevel level = ctx.level();
        int push = 2;
        String bad = JourneyForge.blocked(level, at, away, push);
        while (bad != null && push < 8) {
            push++;
            bad = JourneyForge.blocked(level, at, away, push);
        }
        if (bad != null) {
            if (deepenings > 0) {
                int deeper = at.getY() - FORGE_DEEPEN_BY;
                rig.evidence("forge.deepen." + (FORGE_DEEPENINGS - deepenings + 1),
                        "y=" + at.getY() + " → " + deeper + "：" + bad);
                rig.attempting("模腔外壳不干，楼梯再往下修 " + FORGE_DEEPEN_BY + " 级");
                // Deepened as MORE STAIRCASE, not as a shaft. Sinking straight down here was the old
                // behaviour and it severed the route the moment it was used: the stairs ended at
                // y=56 and the alcove at y=51, with nothing walkable between them, which is exactly
                // the disconnection this whole redesign exists to remove.
                BotConfig.allowPlace = false;
                int cap = FORGE_DEEPEN_BY * STAIR_ATTEMPTS_PER_BLOCK + 20;
                digStairsDown(ctx, rig, deeper, cap, cap,
                        () -> carveTheForge(ctx, rig, lava, surfaceY, deepenings - 1));
                return;
            }
            ctx.fail("模腔怎么摆都不成立：外推 2..8 格、下挖 " + (FORGE_DEEPENINGS * FORGE_DEEPEN_BY)
                    + " 格都试过，最后一处 " + bad + "（身体在 " + at + "）");
            return;
        }
        final int out = push;
        List<BlockPos> cells = JourneyForge.cells(at, away, push);
        // Remembered as a SET, because a retry needs to know what it is allowed to break. See
        // clearPourLine: a pour whose line is blocked may mine the blocker, and the difference
        // between "a stray block in the corridor" and "the alcove's own floor" is exactly this set.
        forgeCorridor = Set.copyOf(JourneyForge.corridor(at, away, push));
        // Cleared WITH the corridor it describes. A stuck list belongs to one excavation, and a
        // second spot's corridor can overlap the first's — carrying the old one across would tell
        // litterAt that a cell of the new alcove is rock nobody could break, when it was never tried.
        forgeStuck = Set.of();
        BlockPos base = at.relative(away, push);
        rig.evidence("forge.face", base.toShortString() + " 朝 " + away
                + "（背离岩浆，外推 " + push + " 格，井底 y=" + at.getY() + "，岩浆层 y=" + lava.getY() + "）");
        // Corridor only. The twelve frame cells were checked for fluid above (via `cells`) but are
        // left SOLID here — each is opened in castCell just before it is filled, so that its floor
        // is still rock or already-cast obsidian at the moment the fluid lands in it.
        List<BlockPos> todo = new ArrayList<>();
        for (BlockPos c : JourneyForge.corridor(at, away, push)) {
            if (level.getBlockState(c).isAir()) continue;
            todo.add(c);
        }
        rig.evidence("forge.toCarve", todo.size() + "/" + cells.size() + " 格");
        rig.attempting("挖出浇筑用的壁龛和十二格门框");
        BotConfig.allowPlace = false;
        carveNext(ctx, rig, todo, 0, new ArrayList<>(), () -> {
            // Placing stays OFF from here to the last cast, and that is the fix run 16 asked for.
            // The alcove is finished: every cell the body needs is already open, so anything the
            // pathfinder puts down inside it is pure obstruction — and it does not land harmlessly.
            // Measured: standToPour rejected all 140 candidates, with 头顶被占 on BOTH cells of the
            // only line that can see the backing (-9,52,21 and -9,52,22) and stray cobblestone at
            // -10,53,21. That is a body pillaring up its own shaft while walking to a frame cell,
            // and it walls off exactly the two spots the pour has to stand in. The rung then had no
            // ray-verified spot at all, fell back to a standable one, and stopped on its own gate.
            //
            // The only place that still needs to build is the ascent, and climbOut turns it back on
            // for itself; returnToTheForge turns it off again on the way down.
            BotConfig.allowPlace = false;
            // NOT "完成" WHEN IT IS NOT. The ladder run of 2026-08-15 printed `forge.carved=完成`
            // directly under `carve.stuck=12 格挖不动`, and the two rows were written by the same
            // method one line apart. A caption that says the excavation finished, beside a
            // measurement that says twelve of its cells are still rock, is a caption that can only
            // mislead.
            //
            // It reports rather than FAILS, and that is a decision the data forced. Stuck cells are
            // not uniformly fatal: the two rehearsals that cast 10/10 both carried
            // `carve.stuck=4 格` at the alcove's ceiling, and the twelve that the ladder run carried
            // were at the top two rows as well — the cell that actually killed that run,
            // `7,56,19`, had been carved perfectly and was refilled afterwards. Failing here would
            // have ended three runs earlier than their real finding, which is the opposite of what a
            // gate is for.
            int carved = todo.size() - forgeStuck.size();
            rig.evidence("forge.carved", forgeStuck.isEmpty()
                    ? todo.size() + "/" + todo.size() + " 格全开"
                    : carved + "/" + todo.size() + " 格开了，" + forgeStuck.size()
                      + " 格没挖动 —— 见 carve.stuck，壁龛不是完整的");
            // Is the mould still a mould? The backings were solid when the spot was CHOSEN, and the
            // carve is the only thing that has happened since — but `allowBreak` stays on through it,
            // so the pathfinder is free to chew a way through the back wall while reaching a corridor
            // cell. A hole there is not a cosmetic loss: every bucket in this rung is aimed at the
            // backing, and a ray that passes through it puts the fluid a cell or more beyond, which
            // the rung would then report as "the cast does not work".
            String open = JourneyForge.firstOpenBacking(ctx.level(), at, away, out);
            rig.evidence("forge.backings", open == null ? "十四格背板都还是实心" : "挖穿了：" + open);
            if (open != null) {
                ctx.fail("挖模腔时把门框背后挖穿了：" + open
                        + " —— 选址时这些格子都是实心的，是挖的过程（allowBreak 全程开着，"
                        + "寻路自己会破墙）把背板打通的。背板一旦是空的，瞄它的每一桶都会穿过去");
                return;
            }
            castTheFrame(ctx, rig, base, away, lava, surfaceY);
        });
    }

    private static BlockPos frameCell(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.frameCell(base, away, dx, dy);
    }

    /**
     * Carve the list, and let a cell that will not open be DATA rather than death.
     *
     * <p>The first field run died here on {@code await step exceeded within=900} and recorded
     * nothing at all about which cell — {@code mineBlock} is drive-shaped, so its timeout ends the
     * rung before the line that would have named the block. Every cell now gets a bounded attempt
     * and the run carries on, so the failure that arrives at the end is a LIST of what could not be
     * reached, which is the thing a plan can be corrected from.
     */
    private static void carveNext(SceneContext ctx, JourneyRig rig, List<BlockPos> todo, int i,
                                  List<BlockPos> stuck, Runnable then) {
        if (i >= todo.size()) {
            // The baseline every later tidy is read against — see tidyTheAlcove. A corridor cell
            // that is solid AND in here was never opened; one that is solid and NOT in here arrived
            // after the carve, which is the only way the rung can tell its own scaffolding from the
            // rock it failed to break without guessing at block ids.
            forgeStuck = Set.copyOf(stuck);
            rig.evidence("carve.stuck", stuck.isEmpty() ? "无"
                    : stuck.size() + " 格挖不动：" + describeStuck(rig, stuck));
            then.run();
            return;
        }
        BlockPos c = todo.get(i);
        if (ctx.level().getBlockState(c).isAir()) { carveNext(ctx, rig, todo, i + 1, stuck, then); return; }
        rig.mineCellOrGiveUp(c, 240, () -> {
            if (!ctx.level().getBlockState(c).isAir()) {
                if (stuck.isEmpty()) noteStuckCarve(rig, c);   // the FIRST one, and only that one
                stuck.add(c);
            }
            carveNext(ctx, rig, todo, i + 1, stuck, then);
        });
    }

    /**
     * Why the first corridor cell that would not open did not open.
     *
     * <p>{@code carve.stuck} has counted these for several runs and cannot say a word about the
     * cause: a cell the body never got near, a cell it stood next to and ran out of budget on, and a
     * cell walled in on all six faces all arrive as the same coordinate in the same list. They want
     * completely different work — a different carve ORDER, a bigger number, a different standing spot
     * — so the list on its own can only support guesses, and this rung has paid for guesses before.
     *
     * <p>Three readings separate them, and they are the same three {@link #noteCellDig} uses on the
     * frame: how far the body was, what {@code canBreak} said, and how many of the six neighbours are
     * full solid faces. {@code canBreak=false} with 6/6 solid is the walled-in clause and an ordering
     * problem; {@code canBreak=false} at range is a body that never arrived; {@code canBreak=true}
     * beside the cell is a budget that ran out.
     *
     * <p>The first only. Twelve of these would bury the one that matters, and they are consecutive
     * cells of one wall — whatever stopped the first almost certainly stopped its neighbours.
     */
    private static void noteStuckCarve(JourneyRig rig, BlockPos c) {
        ServerLevel level = rig.ctx().level();
        BlockPos at = rig.player().blockPosition();
        int solid = 0;
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockPos n = c.relative(d);
            boolean s = level.getBlockState(n).isSolidRender(level, n);
            if (s) solid++;
            around.append(' ').append(d).append('=').append(level.getBlockState(n).getBlock())
                    .append(s ? "(实心)" : "");
        }
        rig.evidence("carve.firstStuck", String.format(java.util.Locale.ROOT,
                "%s=%s：身体 %s，距 %.1f 格，canBreak=%s，六邻实心 %d/6，手上 %s；六邻%s",
                c.toShortString(), level.getBlockState(c).getBlock(), at.toShortString(),
                Math.sqrt(at.distSqr(c)), rig.body().avatar().canBreak(c), solid,
                BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()), around));
    }

    /** Stuck cells summarised by height above the body's floor — the shape of the failure matters
     *  more than the coordinates, because "everything above y+3" and "one awkward corner" want
     *  completely different fixes. */
    private static String describeStuck(JourneyRig rig, List<BlockPos> stuck) {
        int floor = rig.player().blockPosition().getY();
        Map<Integer, Integer> byHeight = new java.util.TreeMap<>();
        for (BlockPos c : stuck) byHeight.merge(c.getY() - floor, 1, Integer::sum);
        // The coordinates too, not only the histogram. "Two cells at floor level" reads as terrain
        // and "both of them the far edge of the corridor" reads as the carve ORDER, and the shape
        // alone cannot tell those apart — while a cell left standing inside the alcove is what
        // seals the frame cell behind it against `canBreak`.
        StringBuilder where = new StringBuilder();
        for (int i = 0; i < Math.min(stuck.size(), 8); i++)
            where.append(i == 0 ? "" : " ").append(stuck.get(i).toShortString());
        // SAY WHAT THE KEY IS RELATIVE TO. "离脚下的高度" is measured from wherever the body finished
        // the carve, which is not the alcove floor and is not the same place twice — read without
        // that y the histogram put the ladder run's twelve stuck cells at "0 and 1", which reads as
        // the floor and is in fact the ceiling.
        return byHeight + "（键=离 y=" + floor + " 的高度，即挖完时身体脚下那一层，值=格数）"
                + " 分别在：" + where + (stuck.size() > 8 ? " …" : "");
    }

    /**
     * Ten casts from one bucket, then the flint-and-steel.
     *
     * <p>The loop is the arena's, verbatim in shape: place the water in the interior cell adjacent
     * to the target, fetch lava from a pool cell nobody has spent yet, pour, take the water back.
     * The bucket is empty at both of the moments that need it to be.
     */
    private static void castTheFrame(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                     BlockPos lava, int surfaceY) {
        // Searched around the SURVEYED lava, not around the body — and that is the fix for a run
        // that reported "0 格" while standing in a chamber it had just carved. The shaft column is
        // chosen up to eight cells clear of the pool (it must not open into it), and then the alcove
        // is carved further away again, so by the time the casting starts the body can be a dozen
        // blocks from the lava it came down for. The caller knows where the pool is; ask there.
        BlockPos here = rig.player().blockPosition();
        List<BlockPos> pool = JourneyTerrain.lavaSourcesNear(ctx.level(), lava, 16, here);   // may be widened below
        rig.evidence("pool.sources", pool.size() + " 格岩浆源（需要 " + RING.length + "）"
                + (pool.isEmpty() ? "" : "，最近一格 " + pool.get(0).toShortString() + " 距身体 "
                        + Math.round(Math.sqrt(pool.get(0).distSqr(here))) + " 格"));
        // Widen before giving up. firstLava is the OBSIDIAN rung's fill point and a bucket takes the
        // source block itself, so the surveyed cell can simply be gone by now — measured, zero
        // sources within sixteen of it. The body is already standing at lava level with its chunks
        // loaded, which is the one moment a wider look is cheap, so ask again from here before
        // declaring the rung impossible.
        if (pool.size() < RING.length) {
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 40, here);
            rig.evidence("pool.widened", pool.size() + " → " + wider.size() + " 格（以身体为心 40 格）"
                    + (wider.isEmpty() ? "" : "，最近 " + wider.get(0).toShortString() + " 距 "
                        + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " 格"));
            if (wider.size() >= RING.length) pool = wider;
        }
        if (pool.size() < RING.length) {
            // Say where the lava ACTUALLY is before saying there is not enough of it. "0 within 16"
            // and "the nearest source is 40 blocks that way" are the same red row and want opposite
            // fixes — a wider search versus a different landmark.
            List<BlockPos> wider = JourneyTerrain.lavaSourcesNear(ctx.level(), here, 48, here);
            rig.evidence("pool.nearestAnywhere", wider.isEmpty() ? "48 格内一格都没有"
                    : wider.get(0).toShortString() + " 距身体 "
                      + Math.round(Math.sqrt(wider.get(0).distSqr(here))) + " 格，共 "
                      + wider.size() + " 格源块");
            rig.evidence("pool.column", JourneyTerrain.lavaColumnReport(ctx.level(), here, 24));
            ctx.fail("岩浆源不够：以勘测点 " + lava.toShortString() + " 为心 16 格内只找到 "
                    + pool.size() + " 格源块，浇十块需要十格。**firstLava 是 OBSIDIAN 装桶用的那一处，"
                    + "装一次拿走的就是源块本身** —— 这一级需要的是一片有十格以上源块的岩浆湖，"
                    + "是一个独立的地标，不是同一个点（身体在 " + here + "）");
            return;
        }
        JourneyFill.pinTheFillStation(ctx, rig, lava, surfaceY, stairTop);
        rig.attempting("一只桶浇十块黑曜石（水搬着走）");
        openTheFrameWatch();
        castCell(ctx, rig, base, away, pool, 0, () -> lightIt(ctx, rig, base, away, surfaceY));
    }

    // ---- the frame watch: CAST IS NOT KEPT ----

    /** Ring cells this run has watched turn to obsidian, and is therefore entitled to still have. */
    private static final Set<BlockPos> frameCast = new java.util.LinkedHashSet<>();

    /** How many losses have been reported, so each gets its own evidence key. */
    private static int frameLosses;

    /** The last step that ended with every cast cell still obsidian — the other half of "when". */
    private static String frameLastSound = "浇筑开始前";

    private static void openTheFrameWatch() {
        frameCast.clear();
        frameLosses = 0;
        frameLastSound = "浇筑开始前";
    }

    /**
     * Check every cell already cast is STILL obsidian, and name the step that took one that is not.
     *
     * <p><b>Poured is not kept, and until this the rung could not tell the two apart.</b> The
     * rehearsal of 2026-08-15 recorded {@code CONSUME} for all ten casts, not one
     * {@code cast.missed.*} — so at the instant of each pour all ten cells WERE obsidian — and then
     * finished {@code frame.cast=6/10}. Four cells went missing after being cast and the only
     * reading that existed was the final count, which can date a loss to "somewhere in the ten
     * round trips" and no closer. One of the four left a trace ({@code recover9.clearedLine.3},
     * a fill breaking the frame to see past it); the other three left nothing at all.
     *
     * <p>So the frame is re-read after every step that can move a block, and a cell that has stopped
     * being obsidian is reported ONCE, with the step it disappeared inside and the last step it was
     * still whole after. That pair is the whole diagnosis: a count says four are gone, this says
     * which four, and between which two instructions.
     *
     * <p>Reported and dropped rather than reported and kept, so ten later checks do not each
     * re-announce the same cell. The running total goes on every row, which is what makes a second
     * loss legible as a second loss.
     */
    private static void auditFrame(JourneyRig rig, String step) {
        ServerLevel level = rig.ctx().level();
        for (var it = frameCast.iterator(); it.hasNext(); ) {
            BlockPos c = it.next();
            if (level.getBlockState(c).getBlock() == Blocks.OBSIDIAN) continue;
            it.remove();
            BlockPos at = rig.player().blockPosition();
            rig.evidence("frame.lost." + (++frameLosses), c.toShortString() + " 浇成黑曜石之后又没了："
                    + "现在是 " + level.getBlockState(c).getBlock()
                    + "，丢在「" + step + "」这一步里（上一次它还在，是「" + frameLastSound + "」之后）；"
                    + "身体 " + at.toShortString() + " 距 "
                    + String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(at.distSqr(c)))
                    + " 格，手上 "
                    + BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem())
                    + "；已浇 " + (frameCast.size() + frameLosses) + " 格，现存 " + frameCast.size() + " 格");
        }
        // Unconditionally, INCLUDING after a loss. The cells still standing were verifiably whole at
        // the end of this step, so this step is what the next loss should name as its last-seen —
        // freezing the marker on a loss would date every later loss to the same stale instruction.
        frameLastSound = step;
    }

    /** Run {@code then}, having first checked the frame survived {@code step}. */
    private static Runnable watchFrame(JourneyRig rig, String step, Runnable then) {
        return () -> { auditFrame(rig, step); then.run(); };
    }

    private static BlockPos wetCellFor(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.wetCellFor(base, away, dx, dy);
    }

    private static void castCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                 List<BlockPos> pool, int i, Runnable then) {
        if (i >= RING.length) {
            auditFrame(rig, "最后一格收尾之后");
            // BOTH NUMBERS. "6/10" alone is the row that started this: it cannot say whether four
            // cells never cast or four cast and were taken back, and those want opposite work.
            rig.evidence("frame.cast", countObsidian(ctx.level(), base, away) + "/" + RING.length
                    + "（浇成过 " + (frameCast.size() + frameLosses) + " 格，浇成之后又丢了 "
                    + frameLosses + " 格 —— 见 frame.lost.*）");
            then.run();
            return;
        }
        BlockPos cell = frameCell(base, away, RING[i][0], RING[i][1]);
        BlockPos wet = wetCellFor(base, away, RING[i][0], RING[i][1]);
        // What the rung has left to dig with, per cell. A snapped pickaxe and a cell the body cannot
        // reach produce the same line — `opened.N=…=stone` — and they want opposite fixes. The kit is
        // two stone pickaxes (262 uses) on purpose, and this rung breaks roughly a hundred cells plus
        // whatever the ten descents re-mine, so "the tool ran out on cast eight" is a live possibility
        // that nothing was recording.
        rig.evidence("tools." + i, toolReport(rig));
        // WHICH ROUND TRIP LOST A BACKING. `forge.backings` says the mould was sound when it was
        // carved and a pour says it is not any more; between them lie ten trips, and without a
        // per-cast count the loss can only be dated to "somewhere in the casting". Silent while the
        // fourteen are intact — which a whole run has now been, so the silence is a real reading and
        // not a wire that was never connected. See mendBacking for what it costs when it is not.
        List<BlockPos> openBackings = JourneyForge.openBackings(ctx.level(), base, away);
        if (!openBackings.isEmpty()) {
            StringBuilder where = new StringBuilder();
            for (BlockPos b : openBackings)
                where.append(where.isEmpty() ? "" : " ").append(b.toShortString()).append('=')
                        .append(ctx.level().getBlockState(b).getBlock());
            rig.evidence("backings." + i, openBackings.size() + "/14 格背板已经不是实心：" + where);
        }
        // Open exactly these two, now. Everything else in the frame is still solid, which is what
        // gives this cell a floor — see forgeCorridor for why carving them all up front cast 0/10.
        // 1200, not 400. Twenty seconds has to cover pathing to the cell as well as breaking it, and
        // `mineCellOrGiveUp` carries on regardless when it runs out — so a budget that is merely tight
        // does not report itself, it reports a pour into rock two steps later. UNVERIFIED: this is a
        // plausible reason run 20 left `wet` as stone, not a confirmed one; the assertion below is
        // what will actually name the cause next run.
        reopen(ctx, rig, "cell." + i, cell, away, REOPEN_TRIES,
                watchFrame(rig, "cell." + i + " 挖开门框格 " + cell.toShortString(), () ->
                reopen(ctx, rig, "wet." + i, wet, away, REOPEN_TRIES,
                watchFrame(rig, "wet." + i + " 挖开水位格 " + wet.toShortString(), () ->
                        tidyTheAlcove(ctx, rig, "tidy." + i,
                                watchFrame(rig, "tidy." + i + " 清壁龛里自己垒的方块", () ->
                                        castOpenedCell(ctx, rig, base, away, pool, i, cell, wet,
                                                then)))))));
    }

    /** How many times a cell may be opened before the rung accepts that it is shut. Four: one dig
     *  plus three refills, which is a gravel column three deep. */
    private static final int REOPEN_TRIES = 4;

    /**
     * Open a cell and KEEP it open — gravel falls, and a cell is only air until the tick after.
     *
     * <p>Run 30 cast six cells and stopped on the seventh with {@code opened.6=-8,59,38=air} two
     * lines above {@code cast6.before=-8,59,38=gravel}. Nothing had gone wrong with the dig: the cell
     * directly over that one is gravel, mining out from under it dropped it in, and the reading that
     * says the cell is open was taken in the same tick as the swing that opened it. The pour then
     * aimed at the target, hit the gravel standing in it, and put the lava a cell short —
     * {@code cast6.picks=-8,59,38 gravel face=north → 落进 -8,59,37}.
     *
     * <p>So the check is: dig, let the world settle, look again. Fluids are left alone — a cell with
     * the rung's own water in it is not shut, and asking {@code mine} to break water spends the whole
     * budget on a no-op. When the tries run out the cell is described rather than mined, which is
     * {@link #noteCellDig}'s job and the reading that separates "walled in" from "out of reach".
     *
     * <p><b>"Refilled" is now a measurement, not a caption.</b> It used to be printed on every retry
     * that found the cell solid — which is also what a dig that never opened it looks like — so the
     * ladder run of 2026-08-12 reported {@code -9,56,34 又被 granite 填上了（上面塌下来的）} three
     * times about a granite block that had never once been air. Granite is not a {@code FallingBlock}
     * and nothing fell; the dig simply failed, and the line named a mechanism instead of saying so.
     *
     * <p><b>The body stands in the corridor first.</b> {@code ServerWorldDriver.mine} is
     * {@code walker.setGoal(Near(cell, 2))} with breaking on, and a walker asked to get near a cell
     * in a wall will happily tunnel through the wall — which here is the mould. That is what the same
     * run did: it ended at {@code -10,59,34}, and {@code -10,59,34} is not a corridor cell at all, it
     * is an <b>interior cell of the portal's own doorway</b>, three of which the save shows opened.
     * The cell behind a frame cell is a corridor cell by construction, so the dig is aimed from
     * there, and the walk to it may not break anything.
     */
    private static void reopen(SceneContext ctx, JourneyRig rig, String tag, BlockPos cell,
                               Direction away, int tries, Runnable then) {
        reopen(ctx, rig, tag, cell, away, tries, false, then);
    }

    private static void reopen(SceneContext ctx, JourneyRig rig, String tag, BlockPos cell,
                               Direction away, int tries, boolean wasOpen, Runnable then) {
        ServerLevel level = ctx.level();
        if (level.getBlockState(cell).isAir() || !level.getFluidState(cell).isEmpty()) {
            then.run();
            return;
        }
        if (tries <= 0) { noteCellDig(rig, tag, cell, away); then.run(); return; }
        if (tries < REOPEN_TRIES)
            rig.evidence(tag + (wasOpen ? ".refilled." : ".stillShut.") + tries,
                    cell.toShortString() + "=" + level.getBlockState(cell).getBlock()
                    + (wasOpen ? "：开过又被填上了（这一格上面是会掉的方块），再挖一次"
                               : "：这一格从头到尾没开过，不是被填上的 —— 挖没挖动，再试一次"));
        standBehind(rig, tag, cell, away, () ->
            rig.mineCellOrGiveUp(cell, tries == REOPEN_TRIES ? 1_200 : 400,
                () -> rig.settle(new HoldStill(10), 30, () -> {
                    // Read the cell BETWEEN the swing and the settle, so "it opened and something
                    // dropped into it" and "it never opened" stop being the same reading.
                    boolean open = wasOpen || level.getBlockState(cell).isAir();
                    reopen(ctx, rig, tag, cell, away, tries - 1, open, then);
                })));
    }

    /**
     * Put the body in the corridor cell directly behind {@code cell} before digging it.
     *
     * <p>Behind, because that cell is in {@link #forgeCorridor} by construction — the corridor is the
     * two ranks between the shaft and the frame plane, and every frame cell's own dx is inside the
     * corridor's width — so it is a place the rung has already hollowed and is entitled to stand in.
     * {@link NoBreak} on the walk is the whole point: the alternative is the walker inventing its own
     * route, and the route it invented went through the doorway.
     *
     * <p><b>Only when that cell has a floor</b>, and the guard is a measurement rather than caution.
     * The corridor is hollowed from the alcove floor to its ceiling, so the cell behind a frame cell
     * is standable for the BOTTOM row and for nothing above it: behind {@code -11,58,38} is
     * {@code -11,58,37}, which is air over {@code -11,57,37}, which is corridor and therefore also
     * air. Sending the body there anyway is what the first version did, and it measurably made the
     * rung worse — the rehearsal that had been reaching cast 9 stopped at cell 5, having spent the
     * walk's whole budget failing to stand in mid-air and then digging from wherever that left it.
     *
     * <p>So the upper rows keep the behaviour they had: {@code mine}'s own {@code Near(cell, 2)}
     * goal, which reaches them from the floor when it can. What that goal cannot do is the thing
     * {@link #noteCellDig} now measures — {@code -11,56,36} to {@code -11,58,38} is 2.83 blocks, so
     * for a cell two rows up there is no standable cell inside the radius at all, and the dig never
     * arrives. That is the next cut in this rung and it wants a step to stand on, not a longer walk.
     *
     * <p><b>And one step, when one step is all that is missing.</b> {@code behind} sits level with the
     * cell (1.00 away) and {@code behind.below()} one row under it (1.41) — both inside the gate, and
     * both corridor cells for every row above the floor. Whichever of the two already has something
     * under it is walked to. When neither does, a single cobblestone goes into the lower one's own
     * support, which is a corridor cell resting on the untouched rock below the alcove floor, and the
     * body steps up exactly one block onto it — ordinary walking, no tower, no drift.
     *
     * <p>That covers the frame's bottom three rows and stops there, on purpose. A cell four or five
     * rows up would need two or three blocks arranged as STAIRS, not stacked: a filled column is a
     * wall the body cannot climb, and building a staircase in a corridor is a different piece of work
     * from placing one block. Those rows keep {@code mine}'s own goal and get told, by name, how many
     * blocks short they were — which is the reading the next attempt should start from rather than
     * the silence that was there before.
     *
     * <p>Best effort throughout. A body that cannot get there still gets its dig attempted from
     * wherever it is, and {@link #noteCellDig} reports the geometry if it was not.
     */
    private static void standBehind(JourneyRig rig, String tag, BlockPos cell, Direction away,
                                    Runnable then) {
        standBehind(rig, tag, cell, away, LITTER_CLEARS, then);
    }

    /** How many blocking cells one stand may clear before it gives up and digs from where it is.
     *  Two: the stand is one cell and its head cell, and a third is a different finding. */
    private static final int LITTER_CLEARS = 2;

    private static void standBehind(JourneyRig rig, String tag, BlockPos cell, Direction away,
                                    int clears, Runnable then) {
        ServerLevel level = rig.ctx().level();
        BlockPos here = rig.player().blockPosition();
        if (forgeCorridor.isEmpty() || withinDigReach(here, cell)) { then.run(); return; }

        BlockPos behind = cell.relative(away.getOpposite());
        BlockPos lower = behind.below();
        // TAKE BACK WHAT THE DIG ITSELF PUT HERE, one cell, before deciding this stand is impossible.
        //
        // `MineProcess` reaches a cell above head height by pillaring, and it pillars with
        // `JourneyShaft.pillarBlock` — whichever of seven spoils the body carries MOST of. Every
        // rehearsal is handed `cobblestone×64`, so for thirty runs that was cobblestone and
        // `tidyTheAlcove` swept it. A real climb arrives with what eleven rungs left: the ladder run
        // of 2026-08-15 arrived holding DIRT, and its first frame cell then read `canBreak=false`
        // with all six neighbours solid because the corridor cell behind it had become one of them —
        // `cell.0.noStand = … 7,56,19 被 Block{minecraft:dirt} 占着`, at floor level, in a chamber cut
        // through granite where dirt is not terrain. Three retries then re-asked an unchanged
        // question and the rung died five casts' worth of wall clock later, at the pour.
        //
        // ONE CELL, not a sweep. The wider version — clear every corridor cell solid that the carve
        // did not leave solid — was tried and regressed the rung twice from 2/2: gravel falls into a
        // seven-tall excavation and PLUGS the alcove floor, and those plugs are what the cast's water
        // drains through. See tidyTheAlcove for the measurement. What a dig needs is its own standing
        // cell back, and that is all this takes.
        BlockPos blocked = litterAt(level, behind, lower);
        if (blocked != null && clears > 0) {
            rig.evidence(tag + ".litter." + clears, blocked.toShortString() + "="
                    + level.getBlockState(blocked).getBlock()
                    + " 挖门框时自己垒进落脚格的，敲掉它再站（挖完之后才出现，不在 carve.stuck 里）");
            rig.mineCellOrGiveUp(blocked, 300,
                    () -> standBehind(rig, tag, cell, away, clears - 1, then));
            return;
        }
        String whyBehind = whyNotStandable(level, behind);
        if (whyBehind == null) { walkToStand(rig, tag, cell, behind, then); return; }
        String whyLower = whyNotStandable(level, lower);
        if (whyLower == null) { walkToStand(rig, tag, cell, lower, then); return; }

        // One block, and only where it can rest on something. `lower`'s own support is the corridor
        // cell at the alcove's floor level, whose floor is the untouched rock the alcove was cut
        // into — so this is a step, not the first course of a pillar the body would then have to
        // climb. Anywhere else and the honest answer is "not enough blocks", which is what it says.
        BlockPos step = lower.below();
        String whyStep = whyNotStep(level, step, here);
        if (whyStep != null) {
            rig.evidence(tag + ".noStand", cell.toShortString() + " 够不着：身体 " + here.toShortString()
                    + " 距 " + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(here.distSqr(cell)))
                    + " 格（>" + DIG_ARRIVE + "）；站不了：" + whyBehind + "；" + whyLower
                    + "；垫不了：" + whyStep);
            then.run();
            return;
        }
        boolean held = rig.body().avatar().holdItem(Items.COBBLESTONE);
        if (held) JourneyStairs.placeInto(level, rig, step);
        // THE WORLD, not the call. A placement can be refused for reasons the caller cannot see, and
        // a step that was never there leaves exactly the "the dig just did not work" row this rung
        // has already been misled by twice.
        boolean stood = level.getBlockState(step).blocksMotion();
        rig.evidence(tag + ".step", step.toShortString() + " 垫一格给 " + cell.toShortString() + " 用 → "
                + (stood ? "站得住了（" + level.getBlockState(step).getBlock() + "）"
                         : (held ? "没垫上（" + level.getBlockState(step).getBlock() + "）" : "手上没有圆石")));
        if (!stood) { then.run(); return; }
        walkToStand(rig, tag, cell, lower, then);
    }

    /** Walk to a chosen stand and say where the body actually ended up — a walk that fell short and
     *  a walk that arrived produce identical digs otherwise, and only one of them is a bug. */
    private static void walkToStand(JourneyRig rig, String tag, BlockPos cell, BlockPos spot,
                                    Runnable then) {
        if (rig.player().blockPosition().equals(spot)) { then.run(); return; }
        rig.settle(new IntentProcess(new Intent(new Goal.Block(spot), List.of(),
                CapabilityProfile.ALL, List.of(new NoBreak()))), 300, () -> {
            BlockPos now = rig.player().blockPosition();
            if (withinDigReach(now, cell)) { then.run(); return; }
            // THE CONTINUOUS POSITION, not only the cell. The run of 2026-08-13 reported
            // `cell.5.standMissed=想站 -11,57,37，停在 -11,57,36` — the right height and the near
            // rank — and two very different bodies produce that line: one that never got onto the
            // step, and one that IS on the step with its centre a hand's width back, so that the
            // cell its feet round to is the neighbour. A 0.6-wide box resting on a block edge is the
            // second, and the two want opposite fixes (a second step versus a nudge). What is under
            // the feet says which.
            ServerLevel lvl = rig.ctx().level();
            rig.evidence(tag + ".standMissed", "想站 " + spot.toShortString() + "，停在 "
                    + now.toShortString() + "，距 " + cell.toShortString() + " 还有 "
                    + String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(now.distSqr(cell)))
                    + " 格（精确 " + String.format(java.util.Locale.ROOT, "%.2f/%.2f/%.2f",
                            rig.player().getX(), rig.player().getY(), rig.player().getZ())
                    + "，脚下 " + now.below().toShortString() + "="
                    + lvl.getBlockState(now.below()).getBlock()
                    + "，想站那格脚下 " + spot.below().toShortString() + "="
                    + lvl.getBlockState(spot.below()).getBlock() + "）");
            then.run();
        });
    }

    /**
     * Why the body cannot stand in {@code spot}, in the words of the clause that refused it — or
     * null when it can.
     *
     * <p>Four clauses, and the message used to name one of them for all four:
     * {@code cell.0.noStand} reported {@code -9,56,37 和 -9,55,37 都没有地板} about the mould's BOTTOM
     * row, and an offline read of that run's saved world says {@code -9,55,37 = andesite} — a
     * perfectly good floor. So the row was false, and WHICH of the other three refused it is not
     * recoverable from the run: both remaining candidates are live in that alcove (the cell itself
     * occupied — gravel arrives in a seven-tall excavation on its own, and the staircase audit caught
     * exactly that at {@code -9,56,36} in the same run — or the head cell occupied). A row that names
     * a mechanism it did not test is worse than no row, because it ends the search; this one filed
     * the reading under "no floor" and it does not belong there.
     */
    private static String whyNotStandable(ServerLevel level, BlockPos spot) {
        if (!forgeCorridor.contains(spot)) return spot.toShortString() + " 不是壁龛格";
        if (level.getBlockState(spot).blocksMotion())
            return spot.toShortString() + " 被 " + level.getBlockState(spot).getBlock() + " 占着";
        if (level.getBlockState(spot.above()).blocksMotion())
            return spot.toShortString() + " 头顶 " + spot.above().toShortString() + "="
                    + level.getBlockState(spot.above()).getBlock() + " 被占";
        if (!level.getBlockState(spot.below()).blocksMotion())
            return spot.toShortString() + " 脚下 " + spot.below().toShortString() + "="
                    + level.getBlockState(spot.below()).getBlock() + " 不是地板";
        return null;
    }

    /**
     * The first of a stand's own cells that this rung put a block into after carving it — or null.
     *
     * <p>Feet and head of both candidate stands, because either one seals the stand and the head is
     * the one the rehearsals actually hit ({@code cell.0.noStand = … -9,56,37 头顶 -9,57,37=
     * cobblestone 被占}). {@link #forgeStuck} is what makes this answerable without guessing at block
     * ids: a corridor cell that is solid and was left solid by the carve is rock the pick could not
     * reach, and re-attempting it every cast is exactly the thrash the block-id test was protecting
     * against; a corridor cell that is solid and was NOT is something that arrived since, and the
     * only thing placing blocks down here is the rung's own digging.
     */
    private static BlockPos litterAt(ServerLevel level, BlockPos behind, BlockPos lower) {
        for (BlockPos c : List.of(behind, behind.above(), lower, lower.above())) {
            if (!forgeCorridor.contains(c)) continue;
            if (forgeStuck.contains(c)) continue;
            if (!level.getBlockState(c).blocksMotion()) continue;   // air, and the rung's own water
            return c;
        }
        return null;
    }

    /** Why one cobblestone will not turn {@code step} into a stand, or null when it will. Same
     *  discipline as {@link #whyNotStandable}: six clauses, six different sentences, because
     *  "there is already something there" and "a brick here would hang in mid-air" are the two the
     *  rung has actually met and they want completely different work. */
    private static String whyNotStep(ServerLevel level, BlockPos step, BlockPos here) {
        if (!forgeCorridor.contains(step)) return step.toShortString() + " 不是壁龛格";
        if (!level.getBlockState(step).isAir())
            return step.toShortString() + " 已经是 " + level.getBlockState(step).getBlock();
        if (!level.getFluidState(step).isEmpty())
            return step.toShortString() + " 里有流体";
        if (!level.getBlockState(step.below()).blocksMotion())
            return step.toShortString() + " 脚下 " + step.below().toShortString() + "="
                    + level.getBlockState(step.below()).getBlock()
                    + " 撑不住 —— 一块砖会悬空，这一格要的是楼梯不是一块砖";
        if (step.equals(here) || step.equals(here.above()))
            return step.toShortString() + " 正被身体占着";
        double d = Math.sqrt(here.distSqr(step));
        if (d > JourneyStairs.MEND_REACH) return step.toShortString() + " 距身体 " + Math.round(d) + " 格，够不着";
        return null;
    }

    /** {@code ServerWorldDriver.mine} walks to {@code Goal.Near(cell, 2)}, so this is the radius the
     *  dig will and will not start inside. Named because it is the number the whole stand exists to
     *  satisfy: measured, {@code -11,56,36} to {@code -11,58,38} is 2.83 and the dig never began. */
    private static final int DIG_ARRIVE = 2;

    private static boolean withinDigReach(BlockPos from, BlockPos cell) {
        return from.distSqr(cell) <= (double) DIG_ARRIVE * DIG_ARRIVE;
    }

    /**
     * Take the body's own scaffolding back out of the alcove before it pours into it.
     *
     * <p>{@code allowPlace} is off for the whole casting phase and the alcove fills with cobblestone
     * anyway, because the placer is not the pathfinder: {@code MineProcess} reaches a cell above head
     * height by planning a <b>pillar-up</b> stand, and the two frame cells opened at the top of every
     * {@code castCell} are exactly that shape. So each cast leaves a column or two behind, in the
     * only volume this rung has to stand and aim in.
     *
     * <p>Measured, run 29: cells 0–2 cast, and cell 3 at {@code -11,57,38} then had no ray-verified
     * spot at all — {@code -11,57,36}, {@code -11,58,36}, {@code -11,59,36}, {@code -11,58,37} and
     * {@code -10,58,37} were all cobblestone, so {@code standToPour} fell back to a standable cell
     * three columns away and its ray stopped on the litter: {@code cast3.picks=-10,58,37
     * cobblestone face=north → 落进 -10,58,36}. {@link #clearPourLine} cleans the LINE and that was
     * not enough; what a pour needs clear is the room.
     *
     * <p><b>Cobblestone only, and that is a MEASURED restriction rather than the original lazy one.</b>
     * The sweep was widened once — to "any corridor cell that is solid and not in {@link #forgeStuck}",
     * which is the honest reading of "anything solid in here arrived afterwards" — and it regressed
     * the rung twice in a row from a standing 2/2. The reason is a block nobody had thought of as
     * structural: <b>gravel falls into a seven-tall excavation and plugs the alcove's floor</b>, and
     * those plugs are what the cast's water drains away through instead of pooling.
     *
     * <p>Measured, both runs, same three cells: {@code tidy.0} removed {@code -7,56,36=gravel},
     * {@code -9,56,36=gravel}, {@code -8,57,36=gravel}, and from cast six onward
     * {@code drain.6 = 等了 200 tick 仍有流体：-7,56,36 = water} — the very cell the gravel had been
     * cleared from. With the alcove wet three casts earlier than before, the body then floated in it
     * ({@code climb.4…10 = -7,56,36 onGround=false water=true}) and the top-row pours failed on their
     * own flooded line. The two runs before the widening reported {@code drain.0…6 = 壁龛已排干}.
     *
     * <p>So the widening is reverted and the case that motivated it is answered where it actually
     * bites: {@link #standBehind} clears the ONE corridor cell a dig needs, by the same
     * {@link #forgeStuck} baseline and without touching the floor. See its note for the ladder run
     * that could not open its first frame cell because {@code 7,56,19} had been pillared full of dirt.
     *
     * <p>Top down, so each cell is adjacent to air when its turn comes and the body simply rides the
     * column down as it goes.
     */
    private static void tidyTheAlcove(SceneContext ctx, JourneyRig rig, String tag, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> litter = new ArrayList<>();
        for (BlockPos c : forgeCorridor)
            if (level.getBlockState(c).getBlock() == Blocks.COBBLESTONE) litter.add(c.immutable());
        if (litter.isEmpty()) { then.run(); return; }
        litter.sort((a, b) -> b.getY() - a.getY());
        // WITH THE BLOCK, now that it is no longer cobblestone by definition. What the body pillars
        // with is whatever it happens to be carrying most of, so the id is the reading that says
        // which spoil this climb arrived on — and it is the one that would have named `dirt` in the
        // run above instead of leaving the corridor silently full of it.
        StringBuilder where = new StringBuilder();
        for (BlockPos c : litter)
            where.append(where.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                    .append(level.getBlockState(c).getBlock());
        rig.evidence(tag, litter.size() + " 格是挖完之后才出现的，要清（挖门框时 MineProcess 自己垒的）："
                + where);
        clearNext(rig, litter, 0, 240, then);
    }

    /** Every pickaxe in the bag with the uses it has left, commonest failure first. */
    private static String toolReport(JourneyRig rig) {
        var inv = rig.player().getInventory();
        StringBuilder out = new StringBuilder();
        for (int s = 0; s < inv.getContainerSize(); s++) {
            var stack = inv.getItem(s);
            if (stack.isEmpty() || !stack.isDamageableItem()) continue;
            String id = String.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            if (!id.endsWith("_pickaxe")) continue;
            out.append(out.isEmpty() ? "" : "  ").append(id).append(' ')
                    .append(stack.getMaxDamage() - stack.getDamageValue()).append('/')
                    .append(stack.getMaxDamage());
        }
        return out.isEmpty() ? "没有镐子了" : out.toString();
    }

    /**
     * Why a frame cell did or did not open, at the moment the digger let go of it.
     *
     * <p>{@code mineCellOrGiveUp} is "dig, and carry on either way" by design, and until now the
     * only thing carried was the outcome: {@code opened.1=-10,51,23=stone} says the cell is shut and
     * nothing at all about why. Two very different answers look the same from there — the process
     * ran out of its budget still swinging, or it stopped early because it could not get within
     * reach — and they want opposite fixes (a bigger number versus a different standing spot).
     *
     * <p>Recorded only for a cell that is still solid. Ten successful digs of two cells each would
     * bury the one that mattered, and this rung's evidence line is already the longest in the suite.
     *
     * <p><b>It no longer prints {@code mine.lastError} or {@code mine.endReason}, and that is a
     * correction rather than a trim.</b> Those two live in {@code botState().mine}, which only a
     * {@link net.magicterra.worlddriver.bot.process.MineProcess} ever writes — and this dig is not
     * one. {@code ServerWorldDriver.mine(BlockPos)} sets {@code mineTarget} and a walker goal and
     * explicitly clears {@code process}, so what the line reported was the LAST MineProcess to have
     * run, from somewhere else entirely. On the ladder run of 2026-08-12 it printed
     * {@code end=collect swept everything it could reach (broke 64/64 …)} beside a cell that had
     * never been touched, which reads as a dig that succeeded 64 times and failed once. What replaces
     * it is the geometry of THIS dig: where the body stood, and whether that was even a cell the rung
     * hollowed — the run above ended inside the portal's own doorway and the line could not say so.
     */
    private static void noteCellDig(JourneyRig rig, String tag, BlockPos cell, Direction away) {
        ServerLevel level = rig.ctx().level();
        if (level.getBlockState(cell).isAir()) return;
        BlockPos at = rig.player().blockPosition();
        double eyes = rig.player().getEyePosition()
                .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(cell));
        // The six neighbours, because `canBreak` has two clauses and they want opposite fixes. Out of
        // RANGE is a standing-spot problem; WALLED IN — every neighbour a full solid face, which is
        // the honest server-side form of "no ray could reach it" — is an ORDER problem, and the only
        // way to tell them apart is to say which faces are closed. Measured: `canBreak=false` at
        // 2.1 m with a pickaxe in hand, which rules the range clause out and names the other.
        StringBuilder around = new StringBuilder();
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            around.append(' ').append(d).append('=').append(level.getBlockState(n).getBlock())
                    .append(level.getBlockState(n).isSolidRender(level, n) ? "(实心)" : "");
        }
        // WHERE THE BODY IS, in the rung's own vocabulary. "距 4.2m" alone cannot tell a body that
        // stopped short in the corridor from one that tunnelled into the mould, and those want
        // opposite fixes.
        BlockPos behind = cell.relative(away.getOpposite());
        String where = forgeCorridor.contains(at) ? "壁龛内"
                : at.equals(behind) ? "正对着这一格的壁龛格"
                : "壁龛之外（离壁龛最近的格都不是它）—— 多半是自己挖进门框里去了";
        rig.evidence("dig." + tag, String.format(java.util.Locale.ROOT,
                "%s 仍是 %s：身体 %s（%s），距 %.1fm，canBreak=%s，该站的壁龛格 %s=%s，手上 %s；六邻%s",
                cell.toShortString(), level.getBlockState(cell).getBlock(), at.toShortString(), where,
                eyes, rig.body().avatar().canBreak(cell),
                behind.toShortString(), level.getBlockState(behind).getBlock(),
                BuiltInRegistries.ITEM.getKey(rig.player().getMainHandItem().getItem()), around));
    }

    private static void castOpenedCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                       List<BlockPos> pool, int i, BlockPos cell, BlockPos wet,
                                       Runnable then) {
        rig.evidence("opened." + i, cell.toShortString() + "=" + ctx.level().getBlockState(cell).getBlock()
                + " 水位 " + wet.toShortString() + "=" + ctx.level().getBlockState(wet).getBlock());
        // Say it outright when a cell did not open. `mineCellOrGiveUp` is "dig, and carry on either
        // way" by design — which is right for an excavation and wrong here, where pouring into rock
        // is not a smaller version of pouring into a cavity. Run 20 poured water at a `wet` that was
        // still stone and the failure surfaced as `cobblestone` in the target, three inferences away
        // from the cause. Flowing lava meeting water gives cobblestone; a lava SOURCE meeting water
        // gives obsidian — so that reading also says the floor is now holding, and only the opening
        // is missing.
        if (!ctx.level().getBlockState(cell).isAir() || !ctx.level().getBlockState(wet).isAir()) {
            ctx.fail("第 " + (i + 1) + " 格没挖开就要浇：" + cell.toShortString() + "="
                    + ctx.level().getBlockState(cell).getBlock() + "，水位 " + wet.toShortString()
                    + "=" + ctx.level().getBlockState(wet).getBlock()
                    + "（两格都必须是空气；mineCellOrGiveUp 挖不动会静默继续）");
            return;
        }
        // A water bucket is what this cell is about to spend. Say so before spending the walk: the
        // recover fill one cell back is best-effort, so a body that lost the water arrives here with
        // an empty bucket, places nothing, pours lava into a dry cell and reports "cast.missed" —
        // which reads as a casting bug and is really a fill that failed a cell ago.
        if (rig.carrying("minecraft:water_bucket") < 1) {
            ctx.fail("第 " + (i + 1) + " 格开浇前手上没有水桶：bucket=" + rig.carrying("minecraft:bucket")
                    + " water_bucket=0 lava_bucket=" + rig.carrying("minecraft:lava_bucket")
                    + " —— 上一格的 recover 没把水收回来，没有水就浇不出黑曜石");
            return;
        }
        // Water in, from the block behind it: a bucket fills the neighbour of the face its ray lands
        // on, and an air cell stops no ray. Standing level with the target keeps that ray horizontal.
        placeFluid(ctx, rig, wet, away, Items.WATER_BUCKET, "water" + i,
                watchFrame(rig, "water" + i + " 放水进 " + wet.toShortString(), () -> {
            // Record where the water settled; do not fail on it. The claim is the obsidian, so let
            // the cast decide — `cast.missed.i` names any cell that did not turn.
            //
            // NOTE: this was relaxed on the theory that for the bottom pair the water falls into the
            // very cell about to be cast and lava poured there still yields obsidian. Run 17 ran all
            // ten cells on that assumption and returned `frame.cast=0/10` — so the theory is WRONG
            // and the problem is not this precondition. Nothing casts in a carved mould at all,
            // while the built arena mould casts 10/10. Keep the relaxation (the precondition was
            // never the blocker) but do not read it as evidence the geometry works.
            if (ctx.level().getFluidState(wet).isEmpty())
                rig.evidence("water.fell." + i, wet.toShortString() + " 空了，水多半落进了目标格 "
                        + cell.toShortString() + "（现在是 " + ctx.level().getBlockState(cell).getBlock() + "）");
            BlockPos src = pool.get(Math.min(i, pool.size() - 1));
            // Reopened first, because the trip that fetched this lava is thousands of ticks long and
            // the cell was left open at the top of it. Gravel that has not finished falling by the
            // water pour has certainly finished by the time the lava comes back. (Cheap and still
            // right on a cell poured from a bucket already in the bag: the cell was opened moments
            // ago and the reopen finds nothing to do.)
            Runnable pour = () -> reopen(ctx, rig, "cast" + i + ".reopen", cell, away, REOPEN_TRIES,
                    watchFrame(rig, "cast" + i + ".reopen 浇前再挖一次 " + cell.toShortString(), () ->
                    placeFluid(ctx, rig, cell, away, Items.LAVA_BUCKET,
                    "cast" + i, () -> rig.settle(new HoldStill(3), 12, () -> {
                var got = ctx.level().getBlockState(cell).getBlock();
                // THE MOMENT THIS CELL BECAME OBSIDIAN, which is what makes every later check able
                // to say it stopped being obsidian. Recorded here rather than counted at the end
                // because the end can only say how many are left.
                if (got == Blocks.OBSIDIAN) frameCast.add(cell.immutable());
                if (got != Blocks.OBSIDIAN)
                    rig.evidence("cast.missed." + i, cell.toShortString() + " = " + got
                            + "（旁边 " + wet.toShortString() + " 是 "
                            + ctx.level().getBlockState(wet).getBlock() + "）");
                // Stop on the FIRST cell that will not cast. Nothing is forfeited: a frame missing
                // one cell can reach 9/10 at best, and `lightIt` fails on anything under ten — so
                // every run that would have continued was already a failing run. What it buys is the
                // clock. Run 17 spent 39 240 ticks (32 minutes) walking all ten cells to report
                // `0/10`, which is the same finding cell one had already made in about a minute, and
                // that cost is paid on every future attempt at this geometry.
                if (i == 0 && got != Blocks.OBSIDIAN) {
                    ctx.fail("第一格就没浇成黑曜石：" + cell.toShortString() + " = " + got
                            + "（水在 " + wet.toShortString() + " = "
                            + ctx.level().getBlockState(wet).getBlock() + "）—— 十格都会一样，"
                            + "不再走完。挖出来的模腔浇不出黑曜石，砌出来的竞技场模腔可以："
                            + "差别在每一格有没有底和背，不在某一格");
                    return;
                }
                // The bucket is empty again, which is exactly what taking the water back needs —
                // and it is also what leaves the interior clear without a separate clean-up trip.
                // Strict, including on the last cell: water left standing in an interior cell is a
                // cell that cannot become portal, so `lightIt` would report 5/6 for a frame that is
                // actually complete.
                JourneyFill.fillFrom(ctx, rig, wet, "recover" + i, Items.WATER_BUCKET,
                        watchFrame(rig, "recover" + i + " 从 " + wet.toShortString() + " 收水", () ->
                        drainTheAlcove(ctx, rig, i, DRAIN_LEGS,
                        watchFrame(rig, "drain." + i + " 等壁龛排干",
                        () -> castCell(ctx, rig, base, away, pool, i + 1, then)))));
            }))));
            // THE STAIRS ARE THE EXPENSIVE PART, so climb them only when there is nothing to pour.
            // A lava bucket does not survive the pour — it becomes obsidian and an empty bucket — so
            // this is the one fluid the rung cannot recycle the way it recycles its single water
            // source. What it CAN do is carry several at once, which turns ten commutes into
            // ceil(10 / buckets) of them. See loadBuckets for why that number needs no flag.
            int inBag = rig.carrying("minecraft:lava_bucket");
            if (inBag >= 1) {
                rig.evidence("lava" + i + ".fromBag", inBag + " 桶岩浆还在包里 —— 这一格不上楼");
                pour.run();
                return;
            }
            // climb UP to the pool → fill every bucket → climb back DOWN to the mould → pour. Both
            // climbs are spelled out; neither was, and each cost a run to find. See goUpToThePool
            // and returnToTheForge.
            goUpToThePool(ctx, rig, src.getY(), "lava" + i,
                    watchFrame(rig, "lava" + i + " 上楼去岩浆池", () ->
                    JourneyFill.loadBuckets(ctx, rig, src, "lava" + i,
                    watchFrame(rig, "lava" + i + " 在池边装桶", () ->
                    returnToTheForge(ctx, rig, base.getY(), "cast" + i,
                    watchFrame(rig, "cast" + i + " 下楼回模腔", pour))))));
        }));
    }

    /** Stand level with {@code target} and empty the held bucket into it, aiming at the solid block
     *  behind it. Level, because a steep ray enters the face a block low and lands in the wrong cell. */
    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, Runnable then) {
        mendBacking(ctx, rig, target, away, tag, () ->
                standLevelWith(ctx, rig, target, away, tag,
                        () -> placeFluid(ctx, rig, target, away, held, tag, POUR_APPROACHES, then)));
    }

    /**
     * Put back the block this pour is about to aim at, when the digging has taken it out.
     *
     * <p>The mould is declared sound once, right after the carve, and {@code forge.backings=十四格
     * 背板都还是实心} is that declaration. Nothing re-asked it, and by the ninth cast of the run of
     * 2026-08-13 two backings were air — {@code -9,59,39} and {@code -9,60,39}, read out of the saved
     * world, both behind the column whose frame cells are dug from a body that pillars up into the
     * doorway. Every bucket in this rung is aimed at the block BEHIND the cell it fills, so an air
     * backing is not a leak, it is an aim with nothing to stop it: {@code cast8.stand} rejected both
     * candidates with {@code -9,60,39 不是实心的，弹不出流体}, fell back to a merely standable cell, and
     * {@code cast8.picks} measured the ray reaching {@code -9,60,40} and dropping the lava into
     * {@code -9,60,39} — a cell BEHIND the frame.
     *
     * <p><b>It does not happen every run, which is exactly why it is worth mending rather than
     * hunting.</b> The next rehearsal on the same seed and the same geometry reached
     * {@code cast8.picks=-9,60,39 Block{minecraft:granite}} — the backing untouched — and cast all ten
     * without needing this at all. A cell that survives three runs in four is not a cell to reason
     * about from one sample; it is a cell to re-read before aiming at it.
     *
     * <p><b>Arm's length or nothing</b>, for the reason {@link JourneyStairs#mend} spells out: {@code placeOn}
     * goes straight to {@code gameMode.useItemOn}, which has no reach gate on this avatar, so without
     * the check a wall could be rebuilt through ten blocks of rock and read as a repair that worked.
     * Out of reach is reported and the pour's own ray gate still refuses to spend the bucket.
     */
    private static void mendBacking(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                    String tag, Runnable then) {
        ServerLevel level = ctx.level();
        BlockPos backing = target.relative(away);
        if (level.getBlockState(backing).isSolidRender(level, backing)) { then.run(); return; }
        BlockPos here = rig.player().blockPosition();
        double reach = Math.sqrt(here.distSqr(backing));
        String was = String.valueOf(level.getBlockState(backing).getBlock());
        if (reach > JourneyStairs.MEND_REACH) {
            rig.evidence(tag + ".backingGone", backing.toShortString() + "=" + was
                    + " 不是实心的（在 " + target.toShortString() + " 后面），身体 "
                    + here.toShortString() + " 距 " + Math.round(reach) + " 格，够不着补不上"
                    + " —— 这一桶会穿过去落在更远的一格");
            then.run();
            return;
        }
        boolean held = rig.body().avatar().holdItem(Items.COBBLESTONE);
        if (held) JourneyStairs.placeInto(level, rig, backing);
        // THE WORLD, not the call. Same reason the step and the stair mend read it back: a placement
        // can be refused for reasons the caller cannot see, and a backing that was never rebuilt
        // leaves exactly the "the cast just did not work" row this rung has been misled by twice.
        boolean solid = level.getBlockState(backing).isSolidRender(level, backing);
        rig.evidence(tag + ".backingMend", backing.toShortString() + " 背板是 " + was
                + "（在 " + target.toShortString() + " 后面，挖门框时被打通的）→ "
                + (solid ? "补回来了（" + level.getBlockState(backing).getBlock() + "）"
                         : (held ? "补不上（现在是 " + level.getBlockState(backing).getBlock() + "）"
                                 : "手上没有圆石")));
        then.run();
    }

    /**
     * Get the body up to the row it is about to pour into, building the step if there is none.
     *
     * <p>The alcove is hollow, so the only solid floor in it is the one seven cells down — and a
     * bucket aimed from there at a cell four rows up traces a line that leaves the frame's plane
     * before it reaches the backing. Worked through for this mould: from {@code y=51} the ray to the
     * backing of {@code y=55} enters the plane at {@code y=53} and lands on the backing of the
     * interior cell two rows low, so {@link #standToPour} rejects every candidate and the rung
     * stops on its own gate. Rows up to {@code y=54} are reachable from the floor and the top pair
     * is not, which is why the ladder has never yet been stopped by this: no run had ever cast
     * eight cells.
     *
     * <p>So the step is BUILT, out of the cobblestone the rung is already carrying, by the same
     * scripted tower that leaves the shaft — and taken down again by nothing, because the corridor
     * is where the next pours stand and {@link #clearPourLine} owns that problem. Best-effort: a
     * body that cannot get up says so and lets the pour's own ray gate decide, which is the only
     * gate in this rung entitled to spend a bucket.
     */
    private static void standLevelWith(SceneContext ctx, JourneyRig rig, BlockPos target,
                                       Direction away, String tag, Runnable then) {
        int wantY = target.getY() - 1;
        if (rig.player().blockPosition().getY() >= wantY) { then.run(); return; }
        // Only when the geometry says so. Every row up to y+3 above the floor already has a spot
        // with a clear line, and building a step for those would put cobblestone in the corridor
        // that the NEXT pour then has to stand around. Asking standToPour in verified-only mode is
        // the same question the pour is about to ask, so this cannot raise for a cell that would
        // have poured anyway.
        if (standToPour(ctx.level(), rig, target, away, new java.util.LinkedHashMap<>(), true) != null) {
            then.run();
            return;
        }
        BlockPos verified = raiseColumn(ctx.level(), rig, target, away, wantY);
        BlockPos col = verified != null ? verified : target.relative(away.getOpposite(), 1);
        BlockPos here = rig.player().blockPosition();
        rig.evidence(tag + ".raise", here.toShortString() + " → y=" + wantY
                + "（在 " + col.getX() + "," + col.getZ() + " 这一柱上垒台阶，浇 "
                + target.toShortString() + " 得跟它同高）"
                + (verified != null ? "：站上去射线落得进目标格，钉住这一柱"
                        : "：没有一柱验得过射线，退回门框正后方那一柱，不钉"));
        // ALREADY IN IT — do not walk. The walk is what put the body one cell out of the column in
        // the first place (`raiseTo.arrivedDistance=1`), and a body standing in the right column has
        // nothing to gain from a leg that can only move it out of one. Same short-circuit the fill
        // and the pour both grew for the same reason.
        if (here.getX() == col.getX() && here.getZ() == col.getZ()) {
            raiseInColumn(rig, target, col, wantY, verified != null, tag, then);
            return;
        }
        WorldDriverJourneyScenes.walkToColumn(rig, tag + ".raiseTo", col.getX(), col.getZ(), 1, 800,
                () -> raiseInColumn(rig, target, col, wantY, verified != null, tag, then),
                () -> {
            rig.evidence(tag + ".raiseStuck", "走不到 " + col.getX() + "," + col.getZ()
                    + "，从当前高度浇（多半会被射线闸拦下）");
            then.run();
        });
    }

    private static void raiseInColumn(JourneyRig rig, BlockPos target, BlockPos col, int wantY,
                                      boolean pin, String tag, Runnable then) {
        Runnable done = () -> {
            BotConfig.allowPlace = false;          // the casting phase is place-free again
            // THE COLUMN AS WELL AS THE HEIGHT. `water9.raisedY=60/60` was a true statement about a
            // body two cells out of the column its aim had been computed for, and reading it alone
            // is what made a lost raise look like a finished one.
            BlockPos now = rig.player().blockPosition();
            rig.evidence(tag + ".raisedY", now.getY() + "/" + wantY + "（停在 " + now.getX() + ","
                    + now.getZ() + "，指定柱 " + col.getX() + "," + col.getZ()
                    + (now.getX() == col.getX() && now.getZ() == col.getZ() ? "，同一柱"
                            : "，不是同一柱 —— 射线是照那一柱算的") + "）");
            then.run();
        };
        // Pinned only when the column was CHOSEN by the ray. Falling back to the arithmetic column
        // means the rung does not know that column works, and pinning a guess buys nothing while it
        // can still cost the climb — so that path keeps the exit's own adopt-on-drift policy.
        if (pin) JourneyShaft.climbOutInColumn(rig, wantY, col.getX(), col.getZ(), done);
        else JourneyShaft.climbOut(rig, wantY, done);
    }

    /**
     * Which column to build the step in — one whose eye can actually see the target's backing.
     *
     * <p>It used to be arithmetic: one cell back along {@code away} from the target. That column is
     * a good guess and it is not a checked one, and when the body cannot reach it the climb starts
     * somewhere else and the aim silently becomes a different aim. Run 43's tenth cell went that way
     * — the arithmetic column was {@code x=-10}, the body could only get to {@code x=-9} (there is
     * no floor at {@code y=57} anywhere else in a hollow alcove), and the tower then drifted to
     * {@code x=-8} and {@code x=-7} and adopted it.
     *
     * <p>So ask the question the pour is going to ask, one row down: standing HERE at {@code wantY},
     * does the same clip vanilla runs land the fluid in the target? Nearest wins and the body's own
     * column is at distance zero, so a column that already works costs no walk at all — which for
     * that run is the fix, because {@code x=-9} verifies.
     *
     * <p>Corridor cells only, feet and head both, so the column is inside the volume this rung
     * hollowed out and the head has somewhere to go. Null when none of them verify, and the caller
     * says so rather than pretending.
     */
    private static BlockPos raiseColumn(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, int wantY) {
        BlockPos here = rig.player().blockPosition();
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (int back = 1; back <= POUR_LINE; back++)
            for (int side = -2; side <= 2; side++) {
                BlockPos foot = target.relative(away.getOpposite(), back)
                        .relative(away.getClockWise(), side).above(wantY - target.getY());
                if (!forgeCorridor.contains(foot) || !forgeCorridor.contains(foot.above())) continue;
                if (!pourLandsFrom(level, rig, foot, target, away)) continue;
                long dx = foot.getX() - here.getX(), dz = foot.getZ() - here.getZ();
                long d = dx * dx + dz * dz;
                if (d < bestD) { bestD = d; best = foot; }
            }
        return best;
    }

    /** Would a bucket emptied by a body standing at {@code foot} land in {@code target}? The same
     *  clip {@link #standToAimAt} runs, from the eye that body WOULD have — a prediction about a
     *  cell the rung is about to build a floor under, which is why it cannot ask for one. */
    private static boolean pourLandsFrom(ServerLevel level, JourneyRig rig, BlockPos foot,
                                         BlockPos target, Direction away) {
        var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                foot.getY() + rig.player().getEyeHeight(), foot.getZ() + 0.5);
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            if (!level.getBlockState(aim).isSolidRender(level, aim)) continue;
            var to = net.minecraft.world.phys.Vec3.atCenterOf(aim);
            if (eye.distanceTo(to) > JourneyFill.BUCKET_REACH) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, rig.player()));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
            if (!hit.getBlockPos().equals(aim)) continue;
            if (aim.relative(hit.getDirection()).equals(target)) return true;
        }
        return false;
    }

    /**
     * Last resort before a pour gives up: pillar up where the body IS, rather than where it should be.
     *
     * <p>{@link #standLevelWith} builds a step when the geometry says no spot can see the target, and
     * it is right about the geometry — but it is asked before the walk, and the walk is what fails.
     * Run 40 cell six: {@code water6.stand=-9,56,37} verified, so no step was built, and the body
     * then ended at {@code -8,56,37} one cell east and stayed there through both retries, its ray
     * landing in {@code -9,58,38} every time. The one good cell existed and the walker could not
     * reach it, which no amount of re-choosing fixes.
     *
     * <p>Pillaring under the body needs no walk at all, and lifting to the target's own row is what
     * makes the backing aim horizontal wherever the body happens to be standing. The cobblestone is
     * left behind on purpose — {@link #tidyTheAlcove} takes it out before the next cell.
     */
    private static void liftInPlace(SceneContext ctx, JourneyRig rig, BlockPos target, String tag,
                                    int tries, Runnable then) {
        int wantY = target.getY() - 1;
        if (tries > 2 || rig.player().blockPosition().getY() >= wantY) { then.run(); return; }
        rig.evidence(tag + ".lift", rig.player().blockPosition().toShortString() + " → y=" + wantY
                + "（走不到选定的落脚格，就地垒上去和 " + target.toShortString() + " 同高）");
        BotConfig.allowPlace = true;
        JourneyShaft.climbOut(rig, wantY, () -> {
            BotConfig.allowPlace = false;
            rig.evidence(tag + ".liftedY", rig.player().blockPosition().getY() + "/" + wantY);
            then.run();
        });
    }

    /** How many times a pour may re-walk at its cell before the rung stops. Two, plus the one it
     *  started with: this is a few blocks inside a chamber the body just carved, so a leg that ends
     *  out of reach three times is not a slow walk, it is a body that cannot get there. */
    private static final int POUR_APPROACHES = 3;

    /**
     * A cell the body can STAND in and from which this pour provably lands in {@code target}.
     *
     * <p>The spot used to be arithmetic — two blocks back along {@code away}, at the target's own
     * height — and level with the target is the right idea for the ray. It is the wrong idea for the
     * body: the alcove is hollow, so "level with a cell four rows up" is a cell with nothing under
     * it, and asking the walker to occupy thin air is what wedged a run at {@code -9,53,20}, three
     * blocks outside the corridor it had just carved, unable to move for three identical attempts.
     *
     * <p>So both halves are asked properly. <b>Standable</b> — feet and head clear, something solid
     * underfoot — and <b>useful</b>, meaning the same clip vanilla is about to run lands on the
     * backing's near face, which is what puts the fluid in {@code target} and nowhere else. Nearest
     * to the body wins, so a cell it is already standing in costs no walk at all.
     */
    private static PourSpot standToPour(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, Map<String, Integer> why) {
        return standToPour(level, rig, target, away, why, false);
    }

    /** Where to stand and what to aim at — one answer, because the two are chosen together. */
    private record PourSpot(BlockPos stand, BlockPos aim) {}

    /**
     * {@code verifiedOnly} drops the standable fallback, which is what makes this answerable as a
     * QUESTION — "is there anywhere down here with a clear line to this cell" — rather than only as
     * a place to walk to. {@link #standLevelWith} asks it that way.
     *
     * <h2>Two aims, because a floating body cannot use the first one</h2>
     *
     * The backing is the natural thing to aim at and it needs the eye almost exactly level with the
     * target: the ray has to cross the frame's plane inside the target's own row, and the plane is
     * two blocks away, so a body one block too high enters the row ABOVE and the fluid lands there.
     * That is not a hypothetical — the alcove floods with the cast's own water, a body in water
     * floats one block, and run 29's cell three recorded exactly it twice
     * ({@code 射线停在 -11,58,38 granite}) with no verified spot left over.
     *
     * <p>So when the backing yields nothing, aim at the target's FLOOR instead and hit its top face:
     * the fluid still lands in the target, and looking down at a block one row below is precisely
     * what a body standing a block too high can do. That the floor is solid is not an assumption —
     * it is {@link JourneyForge}'s first invariant, which is why the ring is cast in the order it is.
     * The exception is the top pair, whose floor is an interior cell opened three casts earlier;
     * there this finds nothing and {@link #standLevelWith} still has to build the step.
     */
    private static PourSpot standToPour(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, Map<String, Integer> why,
                                        boolean verifiedOnly) {
        BlockPos standable = firstStandable(level, rig, target, away);
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            if (!level.getBlockState(aim).isSolidRender(level, aim)) {
                why.merge(aim.toShortString() + " 不是实心的，弹不出流体", 1, Integer::sum);
                continue;
            }
            BlockPos best = standToAimAt(level, rig, target, away, aim, why);
            if (best != null) return new PourSpot(best, aim);
        }
        return standable == null || verifiedOnly ? null
                : new PourSpot(standable, target.relative(away));
    }

    /**
     * Which block, aimed at from where the body is STANDING RIGHT NOW, puts the fluid in the target.
     *
     * <p>The same two candidates {@link #standToPour} weighs — the backing's near face and the
     * target's own floor — clipped from the real eye rather than from a predicted one, and returning
     * null when neither works so the caller's own gate can refuse to spend the bucket.
     */
    private static BlockPos aimThatLandsIn(ServerLevel level, JourneyRig rig, BlockPos target,
                                           Direction away) {
        var eye = rig.player().getEyePosition();
        for (BlockPos aim : List.of(target.relative(away), target.below())) {
            if (!level.getBlockState(aim).isSolidRender(level, aim)) continue;
            var to = net.minecraft.world.phys.Vec3.atCenterOf(aim);
            if (eye.distanceTo(to) > JourneyFill.BUCKET_REACH) continue;
            var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, to,
                    net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, rig.player()));
            if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
            if (!hit.getBlockPos().equals(aim)) continue;
            if (!aim.relative(hit.getDirection()).equals(target)) continue;
            return aim;
        }
        return null;
    }

    /** The nearest cell the body could stand in at all, ray or no ray. Kept apart from the aim scan
     *  so a body is never left with nowhere to go because the ray test is stricter than it should be
     *  — the pour's own {@code .picks} gate still refuses to spend the bucket, so falling back here
     *  cannot cause a wrong-cell pour. */
    private static BlockPos firstStandable(ServerLevel level, JourneyRig rig, BlockPos target,
                                           Direction away) {
        BlockPos from = rig.player().blockPosition();
        BlockPos standable = null;
        double standableD = Double.MAX_VALUE;
        for (int back = 1; back <= 4; back++)
            for (int side = -2; side <= 2; side++)
                for (int dy = 0; dy >= -6; dy--) {
                    BlockPos foot = target.relative(away.getOpposite(), back)
                            .relative(away.getClockWise(), side).above(dy);
                    if (!level.getBlockState(foot.below()).blocksMotion()) continue;
                    if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) continue;
                    BlockPos head = foot.above();
                    if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) continue;
                    double d = foot.distSqr(from);
                    if (d < standableD) { standableD = d; standable = foot; }
                }
        return standable;
    }

    private static BlockPos standToAimAt(ServerLevel level, JourneyRig rig, BlockPos target,
                                         Direction away, BlockPos backing, Map<String, Integer> why) {
        BlockPos from = rig.player().blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int back = 1; back <= 4; back++)
            for (int side = -2; side <= 2; side++)
                for (int dy = 0; dy >= -6; dy--) {
                    BlockPos foot = target.relative(away.getOpposite(), back)
                            .relative(away.getClockWise(), side).above(dy);
                    double d = foot.distSqr(from);
                    if (!level.getBlockState(foot.below()).blocksMotion()) {
                        why.merge("脚下不实心", 1, Integer::sum); continue;
                    }
                    if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) {
                        why.merge("落脚格被占", 1, Integer::sum); continue;
                    }
                    BlockPos head = foot.above();
                    if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                        why.merge("头顶被占", 1, Integer::sum); continue;
                    }
                    // A BODY IN WATER FLOATS, and the whole aim turns on one block of height.
                    //
                    // The cast's own bucket floods the corridor — the source sits in an interior cell
                    // open to it — so by the third cell the row the pours stand in is water. The body
                    // then does not stand in the cell this loop picked; it bobs a block above it.
                    // Measured, cell 2: `water2.stand=-9,56,37` chosen and `water2.picks=…身体
                    // -9,57,37` an instant later, and from that extra block the ray to a backing two
                    // away enters the plane one row high — `落进 -9,58,37` for a target at
                    // `-9,57,38`. Nothing was wrong with the choice; the body was not where the
                    // choice assumed. So predict the float instead of assuming it away, and require
                    // the extra headroom the floating body actually occupies.
                    boolean afloat = !level.getFluidState(foot).isEmpty();
                    if (afloat) {
                        BlockPos over = foot.above(2);
                        if (!level.getBlockState(over).getCollisionShape(level, over).isEmpty()) {
                            why.merge("浮起来会顶到 " + over.toShortString(), 1, Integer::sum);
                            continue;
                        }
                    }
                    // The eye a body standing here would have, and the clip a filled bucket runs
                    // from it. `Fluid.NONE`, because that is what a non-empty bucket uses.
                    var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                            foot.getY() + (afloat ? 1 : 0) + rig.player().getEyeHeight(),
                            foot.getZ() + 0.5);
                    var aim = net.minecraft.world.phys.Vec3.atCenterOf(backing);
                    if (eye.distanceTo(aim) > JourneyFill.BUCKET_REACH) {
                        why.merge("够不着 " + backing.toShortString(), 1, Integer::sum); continue;
                    }
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.NONE, rig.player()));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                        why.merge("射线没打到方块", 1, Integer::sum); continue;
                    }
                    if (!hit.getBlockPos().equals(backing)) {
                        why.merge("射线停在 " + hit.getBlockPos().toShortString() + " "
                                + level.getBlockState(hit.getBlockPos()).getBlock(), 1, Integer::sum);
                        continue;
                    }
                    if (!backing.relative(hit.getDirection()).equals(target)) {
                        why.merge("打中 " + backing.toShortString() + " 的 "
                                + hit.getDirection() + " 面", 1, Integer::sum); continue;
                    }
                    if (d < bestD) { bestD = d; best = foot; }
                }
        return best;
    }

    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, int tries,
                                   Runnable then) {
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        // IF IT CAN BE DONE FROM HERE, DO IT FROM HERE — before choosing anywhere to walk to.
        //
        // Otherwise the walk undoes the work that made the pour possible. Run 42's last cell:
        // `cast9.lift=-9,56,36 → y=59`, `cast9.liftedY=59/59`, the body up its own pillar exactly
        // level with the cell — and then the retry chose a stand, walked to it, and reported
        // `身体在 -10,57,35`, two rows below the row it had just built to reach. The fill has had
        // this short-circuit since run 36 for the same reason; this is it on the pour side.
        PourSpot spot = null;
        BlockPos already = aimThatLandsIn(ctx.level(), rig, target, away);
        if (already != null) {
            rig.evidence(tag + ".fromHere", rig.player().blockPosition().toShortString()
                    + " 就地瞄 " + already.toShortString() + "，流体会落进 "
                    + target.toShortString() + "（不走了）");
            spot = new PourSpot(rig.player().blockPosition(), already);
        }
        if (spot == null) spot = standToPour(ctx.level(), rig, target, away, why);
        if (spot == null) {
            ctx.fail("模腔里没有能浇到 " + target.toShortString() + " 的落脚点："
                    + "要求脚下实心、头顶两格空、射线打在背板 " + target.relative(away).toShortString()
                    + " 的近面或地板 " + target.below().toShortString()
                    + " 的顶面上 —— 身体在 " + rig.player().blockPosition()
                    + "，各项否决计数：" + why);
            return;
        }
        BlockPos goal = spot.stand();
        BlockPos backing = spot.aim();
        rig.evidence(tag + ".stand", goal.toShortString() + " 瞄 " + backing.toShortString()
                + (backing.equals(target.below()) ? "（地板顶面）" : "（背板近面）")
                + " 否决计数 " + why);
        rig.settle(new IntentProcess(new Intent(new Goal.Block(goal))), 1_200, () -> {
            WorldDriverJourneyScenes.holdForUse(rig, held, tag);
            // RE-ASK FROM WHERE THE BODY ACTUALLY ENDED UP. The fill has done this for a while and
            // the pour never did, and it is the same bug on the other side of the trip: the stand
            // and the aim are chosen together, so a walk that ends one cell off leaves the aim
            // answering a question about a body that is not there. Measured, run 39 cell one —
            // `water1.stand=-9,56,37 瞄 -10,57,39（背板近面）` and, an instant later,
            // `water1.picks=… 身体 -9,57,37 → 落进 -9,58,37`: the body floated a block up between
            // choosing and pouring, and from there the backing is the wrong thing to aim at while
            // the target's floor would still have worked. Both are clipped from the real eye here,
            // so whichever one lands in the target is the one used.
            BlockPos aimNow = aimThatLandsIn(ctx.level(), rig, target, away);
            if (aimNow != null && !aimNow.equals(backing))
                rig.evidence(tag + ".reaimed", backing.toShortString() + " → " + aimNow.toShortString()
                        + "（走完发现身体在 " + rig.player().blockPosition().toShortString() + "）");
            BlockPos at = aimNow != null ? aimNow : backing;
            rig.body().avatar().aimAtBlock(at);
            // Clear a plant off the line first. This rung's lake is at y=63 — on the SURFACE — so
            // unlike the underground forge it is standing in grass, and grass is REPLACEABLE: the
            // pour would not miss, it would succeed into the grass cell and be read as "no obsidian
            // here". Same swing the obsidian rung uses, and for the same reason mine cannot do it.
            clearPlantOnLine(ctx, rig, at, tag, () -> rig.settle(new HoldStill(2), 10, () -> {
                // Where the fluid is actually going to land, recorded BEFORE it is spent. A filled
                // bucket clips with `Fluid.NONE` and empties into the cell in front of the face it
                // hits, so this pick IS the destination — and without it a pour that succeeded into
                // the wrong cell is indistinguishable from a pour that did not work, which is the
                // shape of the last three rounds of this rung's investigation. `pourInto` has had
                // this instrument for a while; the ten casts that matter never did.
                ServerLevel lvl = ctx.level();
                var hit = WorldDriverJourneyScenes.aimedAt(rig.player(), JourneyFill.BUCKET_REACH, false);
                BlockPos lands = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().relative(hit.getDirection()) : null;
                rig.evidence(tag + ".picks", (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().toShortString() + " " + lvl.getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → 落进 " + lands.toShortString()
                        : String.valueOf(hit.getType()))
                        + "（想浇 " + target.toShortString() + "，瞄 " + at.toShortString()
                        + "=" + lvl.getBlockState(at).getBlock()
                        + "，身体 " + rig.player().blockPosition().toShortString() + "）");
                rig.evidence(tag + ".before", target.toShortString() + "="
                        + lvl.getBlockState(target).getBlock());
                // Do not spend the bucket unless the ray lands where the plan says. This is the same
                // clip vanilla is about to do, so it is a PREDICTION and not a heuristic — which is
                // why it replaced a distance test: "within arm's length of the backing's centre" was
                // the first guard here and it rejected a pour at 4.4 m that would have worked, three
                // times, from a body that never moved between attempts. What actually decides the
                // outcome is which cell the fluid lands in, and that is knowable exactly.
                if (lands == null || !lands.equals(target)) {
                    if (tries > 1) {
                        // Clear the line before asking again, because asking again on its own is a
                        // retry that changes nothing: standToPour is deterministic in the world it
                        // reads, so three approaches from a body that only moved a block or two get
                        // three identical answers. What changes is the world — and the thing in the
                        // way is a block in a corridor the rung hollowed out itself.
                        clearPourLine(ctx, rig, target, away, tag + ".clear" + tries,
                                () -> liftInPlace(ctx, rig, target, tag, tries,
                                () -> placeFluid(ctx, rig, target, away, held, tag, tries - 1, then)));
                        return;
                    }
                    ctx.fail("浇不到指定格：想浇 " + target.toShortString() + "（瞄背板 "
                            + backing.toShortString() + "），射线会把流体放进 "
                            + (lands == null ? String.valueOf(hit.getType()) : lands.toShortString())
                            + "，身体在 " + rig.player().blockPosition()
                            + "；浇线上是 " + pourLine(lvl, target, away)
                            + " —— 没有倒；倒下去 use 照样报 CONSUME，"
                            + "然后这一级会把失败写成「浇不出黑曜石」");
                    return;
                }
                rig.evidence(tag + ".result", String.valueOf(rig.body().avatar().useItemInHand()));
                then.run();
            }));
        });
    }

    /** How long to let the alcove empty after the water is taken back, and how many such legs.
     *  Water without a source is gone in under a second, so five legs of forty ticks is generous —
     *  it is sized to be long enough that "still wet" means the SOURCE is still there. */
    private static final int DRAIN_TICKS = 40;
    private static final int DRAIN_LEGS = 5;

    /**
     * Wait for the water the cast borrowed to run out of the alcove.
     *
     * <p>Taking the bucket back is not the same as the alcove being dry, and the gap between those
     * two is a whole cell. One source placed in an open mould floods everything below it: measured,
     * the rung recovered its bucket, opened the next frame cell one tick later and read
     * {@code opened.1=-10,51,23=water} — a cell it had just cut out of solid rock. Pouring into that
     * is the mistake the "两格都必须是空气" gate exists to stop, so it stopped, and the finding read
     * as a mining failure.
     *
     * <p>Flowing water with no source disappears on its own, so this is a wait and not a repair. If
     * it is still wet after all the legs, the source was never picked up — a different failure, and
     * this says so rather than letting the next cell report it second-hand.
     */
    private static void drainTheAlcove(SceneContext ctx, JourneyRig rig, int i, int legs,
                                       Runnable then) {
        String wet = JourneyForge.firstFluid(ctx.level(), List.copyOf(forgeCorridor));
        if (wet == null || legs <= 0) {
            // THE SENTENCE USED TO NAME A CAUSE THE ROW ITSELF DISPROVES. It said "水源没被收回来"
            // — the source was never picked up — and every recover in the run reports CONSUME. Now
            // that firstFluid states source-or-flowing, the answer is in: on the run that lit the
            // portal, drain.6 through drain.9 all read `（流动，没源就会自己退）`. Nothing is
            // feeding the alcove; the water is simply still on its way out after 200 ticks, in a
            // seven-tall room whose floor the tidy has just unplugged. That is a wait to lengthen or
            // a floor to leave alone, not a bucket to chase.
            rig.evidence("drain." + i, wet == null ? "壁龛已排干"
                    : "等了 " + (DRAIN_LEGS * DRAIN_TICKS) + " tick 仍有流体：" + wet
                      + " —— 挖开下一格它会灌进去；是不是源块见括号，流动的只是还没退完");
            then.run();
            return;
        }
        rig.settle(new HoldStill(DRAIN_TICKS / 2), DRAIN_TICKS,
                () -> drainTheAlcove(ctx, rig, i, legs - 1, then));
    }

    /** How far back along its own line a pour may look. Three, which is one more than the usual
     *  {@code push} and one less than {@code standToPour}'s reach — far enough to cover the cells a
     *  body standing in the corridor sees through, short of the alcove's back wall. */
    private static final int POUR_LINE = 3;

    /** The cells the ray goes through on its way to the backing, and what is standing in them.
     *
     *  <p>Two rows: the target's own, and the one above it. A body pours from a foot cell one below
     *  the target, so its eyes are in the upper row and the ray crosses into the lower one on the
     *  way in — both have to be clear, and naming which is not is the difference between "the pour
     *  does not work" and "there is a cobblestone at -9,52,22". */
    private static String pourLine(ServerLevel level, BlockPos target, Direction away) {
        StringBuilder out = new StringBuilder();
        for (int k = 1; k <= POUR_LINE; k++)
            for (int dy = -1; dy <= 2; dy++) {
                BlockPos c = target.relative(away.getOpposite(), k).above(dy);
                if (level.getBlockState(c).isAir()) continue;
                out.append(out.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                        .append(level.getBlockState(c).getBlock())
                        .append(forgeCorridor.contains(c) ? "(壁龛内)" : "(壁龛外)");
            }
        return out.isEmpty() ? "全是空气" : out.toString();
    }

    /**
     * Mine whatever is standing in the pour's line, but only inside the alcove.
     *
     * <p><b>Four rows, not two.</b> One below the target (the cell the body's feet go in), the
     * target's own (where the ray travels), and TWO above — because by the third cell the corridor is
     * flooded by the cast's own water and the body floats a block higher, so the cell its head
     * occupies is two above its feet, not one. Measured, run 27: {@code standToPour} correctly
     * rejected the one standing spot with a clean line — {@code 浮起来会顶到 -10,58,37} — and the
     * clear could not reach {@code y=58} to do anything about it, so the pour fell back to a cell one
     * column over and its ray hit cell zero's obsidian on the way past.
     *
     * <p>The answer to a pour that cannot see its backing, and it is deliberately not a search. The
     * corridor is a volume this rung hollowed out itself and recorded while doing it, so a solid
     * block inside it is by definition something that arrived afterwards — {@code allowPlace} is off
     * for the whole casting phase now, so this should find nothing, and finding something is itself
     * the report. Outside that set nothing is touched: one cell below the bottom frame row is the
     * mould's own floor, and answering a blocked ray by breaking it would drain every cast.
     */
    private static void clearPourLine(SceneContext ctx, JourneyRig rig, BlockPos target,
                                      Direction away, String tag, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> blocked = new ArrayList<>();
        for (int k = 1; k <= POUR_LINE; k++)
            for (int dy = -1; dy <= 2; dy++) {
                BlockPos c = target.relative(away.getOpposite(), k).above(dy);
                if (!forgeCorridor.contains(c)) continue;
                if (level.getBlockState(c).isAir()) continue;
                if (!level.getFluidState(c).isEmpty()) continue;   // the rung's own water, not a wall
                blocked.add(c);
            }
        rig.evidence(tag, blocked.isEmpty() ? "浇线上没有可清的方块（" + pourLine(level, target, away) + "）"
                : blocked.size() + " 格要清：" + pourLine(level, target, away));
        clearNext(rig, blocked, 0, then);
    }

    private static void clearNext(JourneyRig rig, List<BlockPos> blocked, int i, Runnable then) {
        clearNext(rig, blocked, i, 600, then);
    }

    /** The per-cell budget is the caller's, because the two callers are not the same size. A pour's
     *  line is three or four cells and each one is genuinely in the way; the alcove sweep can be
     *  twenty, most of them already reachable, and a cell that will not open in four seconds there
     *  is one to walk past rather than one to spend twenty on ten times over. */
    private static void clearNext(JourneyRig rig, List<BlockPos> blocked, int i, int ticks,
                                  Runnable then) {
        if (i >= blocked.size()) { then.run(); return; }
        rig.mineCellOrGiveUp(blocked.get(i), ticks, () -> clearNext(rig, blocked, i + 1, ticks, then));
    }

    /** Break whatever no-collider block the aim ray stops on before {@code want}, then continue.
     *  One swing only: if the line is blocked by something solid, that is a placement problem and
     *  the caller's own evidence should say so rather than this quietly digging through it. */
    static void clearPlantOnLine(SceneContext ctx, JourneyRig rig, BlockPos want,
                                         String tag, Runnable then) {
        ServerLevel level = ctx.level();
        var hit = WorldDriverJourneyScenes.aimedAt(rig.player(), WorldDriverJourneyScenes.TUNNEL_REACH, false);
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK
                || hit.getBlockPos().equals(want)
                || !level.getBlockState(hit.getBlockPos()).getCollisionShape(level, hit.getBlockPos()).isEmpty()) {
            then.run();
            return;
        }
        BlockPos plant = hit.getBlockPos();
        rig.evidence(tag + ".clearedPlant", plant.toShortString() + " "
                + level.getBlockState(plant).getBlock());
        var av = rig.body().avatar();
        av.aimAtBlock(plant);
        av.breakHold(true);
        av.continueDestroy(plant);
        av.breakHold(false);
        rig.settle(new HoldStill(3), 12, () -> {
            av.aimAtBlock(want);
            then.run();
        });
    }

    private static int countObsidian(ServerLevel level, BlockPos base, Direction away) {
        return JourneyForge.countObsidian(level, base, away);
    }

    /**
     * Strike the frame.
     *
     * <p>{@code FlintAndSteelItem} overrides {@code useOn} and has no {@code use}, so this must go
     * through {@code useBlock(cell, face)} — called the other way it returns {@code PASS} and the
     * world does not move. The fire lands at {@code clickedPos.relative(clickedFace)}, so the click
     * is on the frame's bottom-left obsidian with the face pointing UP into the interior.
     */
    private static void lightIt(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                int surfaceY) {
        ServerLevel level = ctx.level();
        int cast = countObsidian(level, base, away);
        rig.evidence("frame.obsidian", cast + "/" + RING.length);
        // Reported on the way past whether or not it ever fired. A guard that only speaks when it
        // trips cannot be told apart from a guard that was never wired up, and this one has to
        // survive twenty legs of a rung nobody watches.
        rig.evidence("stairs.audit", "自检 " + JourneyStairs.tally()
                + "，收工时 " + JourneyStairs.report(level));
        if (cast < RING.length) {
            ctx.fail("门框没浇满：只有 " + cast + "/" + RING.length + " 块黑曜石 —— 点不着一个缺角的门");
            return;
        }
        BlockPos hearth = frameCell(base, away, 0, 0);
        BlockPos doorway = hearth.above();
        rig.attempting("清门洞并点火");
        clearTheDoorway(ctx, rig, base, away, () -> strike(ctx, rig, base, away, hearth, doorway));
    }

    /**
     * Empty the six interior cells before striking, because casting fills two of them with slag.
     *
     * <p>A portal needs its doorway to be AIR, and this rung spends ten buckets of lava inside a
     * mould full of water: flowing lava that meets water is cobblestone, and it sets in whichever
     * cell the two met in. Measured, run 31 — {@code frame.cast=10/10}, {@code frame.obsidian=10/10},
     * the flint struck and {@code light.cellAfter=fire}, and {@code portal.cells=0/6}, because
     * {@code -9,58,38} and {@code -10,58,38} — the middle row of the doorway — had been cobblestone
     * since the fifth cast. Every reading about the frame was right and the door was bricked up.
     *
     * <p>Cheap to do and expensive to skip: the frame is finished by this point, so the six cells are
     * reachable from the alcove and nothing above them can fall in (the top pair is obsidian). The
     * evidence names what was in there, because "the cast leaves slag in the doorway" is a finding
     * about the mould's geometry and not a chore.
     *
     * <p><b>And slag is not the only thing that gets in — WATER does, and a pick cannot take it
     * out.</b> The first ladder run ever to cast all ten cells died here:
     * {@code portal.slag = 2 格要清：-9,57,38=water -10,58,38=granite}, then
     * {@code portal.doorway = 还堵着：-9,57,38=water}. The granite went; the water was swung at six
     * hundred ticks' worth of nothing, because {@code mine} on a fluid cell is a no-op. It is fed
     * from the alcove — {@code drain.9 = 等了 200 tick 仍有流体：-9,57,37 = water} names the cell
     * immediately behind it — so the doorway is where this rung's long-standing wet alcove finally
     * stops being a cost and becomes the failure.
     *
     * <p>Three steps, in this order, because each one is pointless without the one before:
     * <b>dam</b> the corridor cell behind each interior cell when it holds fluid (a corridor cell is
     * this rung's own spoil heap and nothing downstream stands there), <b>wait</b> for what is
     * already inside to run out now that nothing feeds it, and only then <b>plug</b> whatever fluid
     * is left with a cobblestone so the existing pick can take it out as a block. A portal needs
     * {@code isEmpty()} in all six, and flowing water fails that exactly as hard as a source does.
     */
    private static void clearTheDoorway(SceneContext ctx, JourneyRig rig, BlockPos base,
                                        Direction away, Runnable then) {
        ServerLevel level = ctx.level();
        List<BlockPos> interior = new ArrayList<>();
        for (int ix = 0; ix <= 1; ix++)
            for (int iy = 1; iy <= 3; iy++) interior.add(frameCell(base, away, ix, iy));

        // DAM FIRST. Clearing a cell that something is still pouring into buys one tick of air.
        boolean held = rig.body().avatar().holdItem(Items.COBBLESTONE);
        StringBuilder dammed = new StringBuilder();
        for (BlockPos c : interior) {
            BlockPos behind = c.relative(away.getOpposite());
            if (!forgeCorridor.contains(behind)) continue;
            if (level.getFluidState(behind).isEmpty()) continue;
            boolean was = level.getFluidState(behind).isSource();
            if (held) JourneyStairs.placeInto(level, rig, behind);
            // READ IT BACK, and do not call it dammed until the world says so. The first run of this
            // reported `堵住…-10,57,37(流动)→Block{minecraft:water}` — a sentence that claims a dam
            // and prints the water still standing there, which is the shape of row this rung has
            // been misled by twice. Best-effort is fine here (the wait and the plug below carried
            // that run to 6/6 anyway); claiming success is not.
            boolean now = level.getBlockState(behind).blocksMotion();
            dammed.append(dammed.isEmpty() ? "" : " ").append(behind.toShortString())
                    .append(was ? "(源块)" : "(流动)").append(now ? "→堵上了 " : "→没堵上，还是 ")
                    .append(level.getBlockState(behind).getBlock());
        }
        rig.evidence("portal.dam", dammed.isEmpty() ? "门洞背后没有流体，不用堵"
                : (held ? "" : "手上没有圆石，堵不上；") + "门洞背后的壁龛格：" + dammed);

        rig.settle(new HoldStill(20), DOORWAY_DRAIN_TICKS, () -> {
            List<BlockPos> slag = new ArrayList<>();
            StringBuilder what = new StringBuilder();
            for (BlockPos c : interior) {
                if (level.getBlockState(c).isAir() && level.getFluidState(c).isEmpty()) continue;
                // A FLUID BECOMES A BLOCK BEFORE IT BECOMES A JOB. `clearNext` mines, and mining
                // water is the six hundred ticks of nothing that killed the run above.
                if (!level.getFluidState(c).isEmpty()) {
                    // BEFORE THE PLUG, because plugging is what empties the cell. The first run of
                    // this read the fluid back after placing and printed
                    // `流动 …material.EmptyFluid@1835b783` — the state it had just destroyed, under
                    // an object identity nobody can read. What the row is for is naming the fluid
                    // that was in the way.
                    boolean source = level.getFluidState(c).isSource();
                    String fluid = BuiltInRegistries.FLUID.getKey(level.getFluidState(c).getType())
                            .toString();
                    boolean plugged = rig.body().avatar().holdItem(Items.COBBLESTONE)
                            && JourneyStairs.placeInto(level, rig, c);
                    rig.evidence("portal.plug." + c.toShortString(),
                            (source ? "源块 " : "流动 ") + fluid
                            + " → " + (plugged ? "塞成 " + level.getBlockState(c).getBlock()
                                               + "，接下来当方块挖掉" : "塞不上，挖也挖不动"));
                }
                slag.add(c);
                what.append(what.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                        .append(level.getBlockState(c).getBlock());
            }
            rig.evidence("portal.slag", slag.isEmpty() ? "门洞六格都是空气" : slag.size() + " 格要清：" + what);
            if (slag.isEmpty()) { then.run(); return; }
            clearNext(rig, slag, 0, 600, () -> {
                StringBuilder left = new StringBuilder();
                for (BlockPos c : slag)
                    if (!level.getBlockState(c).isAir() || !level.getFluidState(c).isEmpty())
                        left.append(left.isEmpty() ? "" : " ").append(c.toShortString()).append('=')
                                .append(level.getBlockState(c).getBlock())
                                .append(level.getFluidState(c).isEmpty() ? ""
                                        : level.getFluidState(c).isSource() ? "(源块)" : "(流动)");
                rig.evidence("portal.doorway", left.isEmpty() ? "六格都清干净了" : "还堵着：" + left);
                if (!left.isEmpty()) {
                    ctx.fail("门洞清不干净：" + left + " —— 传送门要的是六格空气；"
                            + "圆石是浇筑时岩浆碰水结的渣，流体是壁龛里没排干的水顺着背后灌进来的，"
                            + "两者要的手段不一样，看 portal.dam / portal.plug 哪一步没成");
                    return;
                }
                then.run();
            });
        });
    }

    /** How long to let the doorway run dry once its feeders are dammed. Water clears a cell in a
     *  handful of ticks when nothing replaces it, so this is generous by an order of magnitude on
     *  purpose: it is the difference between "the dam worked" and "the dam worked slowly", and only
     *  the first is worth a hundred ticks of a rung that has already spent eight thousand. */
    private static final int DOORWAY_DRAIN_TICKS = 120;

    private static void strike(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                               BlockPos hearth, BlockPos doorway) {
        ServerLevel level = ctx.level();
        rig.settle(new IntentProcess(new Intent(new Goal.Near(hearth, 3))), 1_500, () -> {
            WorldDriverJourneyScenes.holdForUse(rig, Items.FLINT_AND_STEEL, "light");
            rig.body().avatar().aimAtBlock(hearth);
            rig.settle(new HoldStill(2), 10, () -> {
                rig.body().avatar().useBlock(hearth, Direction.UP);
                rig.settle(new HoldStill(5), 20, () -> {
                    int lit = 0;
                    for (int ix = 0; ix <= 1; ix++)
                        for (int iy = 1; iy <= 3; iy++)
                            if (level.getBlockState(frameCell(base, away, ix, iy)).getBlock()
                                    == Blocks.NETHER_PORTAL) lit++;
                    rig.evidence("portal.cells", lit + "/6");
                    rig.evidence("light.cellAfter", String.valueOf(level.getBlockState(doorway).getBlock()));
                    rig.evidence("bucket.after", rig.carrying("minecraft:bucket")
                            + " 空 / " + rig.carrying("minecraft:water_bucket") + " 水");
                    ctx.expect(lit).as("the portal the body carved, cast and struck is lit").isEqualTo(6);
                    rig.reach("在 y=" + doorway.getY() + " 就地浇出十块黑曜石并点亮 " + lit
                            + " 格传送门（自带一桶水下井，浇完水还在桶里）");
                });
            });
        });
    }
}
