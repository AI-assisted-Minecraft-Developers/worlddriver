package net.magicterra.worlddriver.bot.stagewright.journey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
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
                rig.evidence("waterFill.result", String.valueOf(rig.body().avatar().useItemInHand()));
                rig.evidence("water_bucket", rig.carrying("minecraft:water_bucket"));
                rig.evidence("waterFill.cellAfter", String.valueOf(ctx.level().getBlockState(at).getBlock()));
                if (rig.carrying("minecraft:water_bucket") < 1) {
                    ctx.fail("装水失败：瞄了 " + at.toShortString() + "，桶里还是空的 —— "
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

    /** Every step's foot cell, top first — the route itself, not just its ends.
     *
     * <p>The ends alone are not enough, and that is a measurement rather than a precaution. Given
     * {@code Goal.Block(stairBottom)} the walker takes the shortest line it can see, and from the top
     * of the stairs the shortest line is ACROSS THE SURFACE: measured twice, the body walked to
     * {@code -10,66,34} — ground level directly above the staircase at z=34 — and then re-searched
     * for a cell eight blocks below it through untouched rock, every two seconds, until the leg ran
     * out. Naming the mouth fixed the first half and the second half did it again from the mouth.
     * Waypoints down the flight itself are what make the staircase the route and not merely a hole
     * that happens to connect two places. */
    private static final List<BlockPos> stairCells = new ArrayList<>();

    /** Every fourth step is waypoint enough: consecutive waypoints are four blocks apart INSIDE the
     *  stairwell, and there is no shorter way between two such cells that leaves it. */
    private static final int STAIR_WAYPOINT_STRIDE = 4;

    /** The flight as a list of waypoints, top-first when {@code down}. */
    private static List<BlockPos> stairRoute(boolean down) {
        List<BlockPos> out = new ArrayList<>();
        for (int i = 0; i < stairCells.size(); i += STAIR_WAYPOINT_STRIDE) out.add(stairCells.get(i));
        BlockPos last = stairCells.get(stairCells.size() - 1);
        if (!out.get(out.size() - 1).equals(last)) out.add(last);
        if (!down) java.util.Collections.reverse(out);
        return out;
    }

    /** Walk the waypoints in order, best effort. A leg that falls short is not failed here — the
     *  caller checks the height it actually reached, which is the only thing that matters. */
    private static void walkTheStairs(JourneyRig rig, List<BlockPos> route, int i, Runnable then) {
        if (i >= route.size()) { then.run(); return; }
        rig.settle(new IntentProcess(new Intent(new Goal.Block(route.get(i)))), 600,
                () -> walkTheStairs(rig, route, i + 1, then));
    }

    /** Every cell the alcove was hollowed out of — the space the body walks in, and nothing else.
     *  {@link #clearPourLine} is allowed to break inside this and nowhere else, which is what stops
     *  a blocked pour from answering by digging a hole in the mould's own floor. */
    private static Set<BlockPos> forgeCorridor = Set.of();

    /**
     * Which way to run from the lava — one axis, never a diagonal.
     *
     * <p>Shared by the staircase and the mould so they cannot disagree. The staircase runs AWAY from
     * the pool and the mould's face is cut on the same side, which is what keeps the two of them from
     * meeting: the alcove sits at the foot of the last step, and every step above it is both higher
     * and further back.
     */
    private static Direction awayFrom(BlockPos lava, BlockPos at) {
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
        if (stairCells.isEmpty() || !stairCells.get(stairCells.size() - 1).equals(foot))
            stairCells.add(foot);
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
        walkTheStairs(rig, stairRoute(false), 0, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".upEnded", here.toShortString() + "（楼梯顶 "
                    + stairTop.toShortString() + "）");
            if (here.getY() < poolY - 1) {
                ctx.fail("走不上楼梯：停在 " + here.toShortString() + "，楼梯顶 "
                        + stairTop.toShortString() + " 在 y=" + stairTop.getY()
                        + " —— 楼梯是挖出来了，但走不上去（台阶被堵？跨不上去？）");
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
        BotConfig.allowPlace = false;
        BlockPos at = rig.player().blockPosition();
        if (at.getY() <= floorY + 1) { then.run(); return; }
        if (stairBottom == null) {
            ctx.fail("没有楼梯底坐标：descendToTheForge 没有记下来，回不到模腔");
            return;
        }
        rig.evidence(tag + ".return", at.toShortString() + " → 楼梯口 " + stairTop.toShortString()
                + " → 楼梯底 " + stairBottom.toShortString() + "（模腔地板 y=" + floorY + "）");
        // VIA THE STAIRWELL MOUTH, not straight at the bottom. The bottom is ten blocks down at the
        // far end of fifteen steps, and asked for directly the walker takes the shortest line it can
        // see — overland. Measured: the body walked to -9,66,34, which is the surface directly ABOVE
        // the staircase at z=34, and then re-searched for -9,57,36 every two seconds from a spot
        // separated from it by eight blocks of untouched rock. The stairs are a corridor and a
        // corridor is entered at its mouth; naming the mouth turns one impossible search into two
        // easy ones.
        walkTheStairs(rig, stairRoute(true), 0, () -> {
            BlockPos here = rig.player().blockPosition();
            rig.evidence(tag + ".returnedY", here.getY() + "（楼梯底 y=" + stairBottom.getY()
                    + "，身体 " + here.toShortString() + "）");
            if (here.getY() > floorY + 1) {
                ctx.fail("走不回模腔：停在 " + here.toShortString() + "，楼梯底 "
                        + stairBottom.toShortString() + " 在 y=" + stairBottom.getY()
                        + " —— 带着一桶岩浆停在半路，浇下去只会浇进楼梯");
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
                stairCells.clear();
                stairCells.add(start);
                stairDir = awayFrom(lava, start);
                int depth = Math.max(0, start.getY() - forgeFloorY(lava));
                int cap = depth * STAIR_ATTEMPTS_PER_BLOCK + 40;
                rig.evidence("stairs.top", start.toShortString() + " 往 " + stairDir + " 下 "
                        + depth + " 级到 y=" + forgeFloorY(lava) + "（给 " + cap + " 次）");
                digStairsDown(ctx, rig, forgeFloorY(lava), cap, cap, () -> {
                    rig.evidence("forge.landedY", rig.player().blockPosition().getY());
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
            rig.evidence("forge.carved", "完成");
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
            rig.evidence("carve.stuck", stuck.isEmpty() ? "无"
                    : stuck.size() + " 格挖不动：" + describeStuck(rig, stuck));
            then.run();
            return;
        }
        BlockPos c = todo.get(i);
        if (ctx.level().getBlockState(c).isAir()) { carveNext(ctx, rig, todo, i + 1, stuck, then); return; }
        rig.mineCellOrGiveUp(c, 240, () -> {
            if (!ctx.level().getBlockState(c).isAir()) stuck.add(c);
            carveNext(ctx, rig, todo, i + 1, stuck, then);
        });
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
        return byHeight.toString() + "（键=离脚下的高度，值=格数）"
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
        rig.attempting("一只桶浇十块黑曜石（水搬着走）");
        castCell(ctx, rig, base, away, pool, 0, () -> lightIt(ctx, rig, base, away, surfaceY));
    }


    private static BlockPos wetCellFor(BlockPos base, Direction away, int dx, int dy) {
        return JourneyForge.wetCellFor(base, away, dx, dy);
    }

    private static void castCell(SceneContext ctx, JourneyRig rig, BlockPos base, Direction away,
                                 List<BlockPos> pool, int i, Runnable then) {
        if (i >= RING.length) {
            rig.evidence("frame.cast", countObsidian(ctx.level(), base, away) + "/" + RING.length);
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
        // Open exactly these two, now. Everything else in the frame is still solid, which is what
        // gives this cell a floor — see forgeCorridor for why carving them all up front cast 0/10.
        // 1200, not 400. Twenty seconds has to cover pathing to the cell as well as breaking it, and
        // `mineCellOrGiveUp` carries on regardless when it runs out — so a budget that is merely tight
        // does not report itself, it reports a pour into rock two steps later. UNVERIFIED: this is a
        // plausible reason run 20 left `wet` as stone, not a confirmed one; the assertion below is
        // what will actually name the cause next run.
        rig.mineCellOrGiveUp(cell, 1_200, () -> {
            noteCellDig(rig, "cell." + i, cell);
            rig.mineCellOrGiveUp(wet, 1_200, () -> {
                noteCellDig(rig, "wet." + i, wet);
                castOpenedCell(ctx, rig, base, away, pool, i, cell, wet, then);
            });
        });
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
     */
    private static void noteCellDig(JourneyRig rig, String tag, BlockPos cell) {
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
        rig.evidence("dig." + tag, String.format(java.util.Locale.ROOT,
                "%s 仍是 %s：身体 %s 距 %.1fm，canBreak=%s，mine.lastError=%s end=%s，手上 %s；六邻%s",
                cell.toShortString(), level.getBlockState(cell).getBlock(), at.toShortString(), eyes,
                rig.body().avatar().canBreak(cell),
                rig.body().botState().mine.lastError, rig.body().botState().mine.endReason,
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
        placeFluid(ctx, rig, wet, away, Items.WATER_BUCKET, "water" + i, () -> {
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
            // climb UP to the pool → fill → climb back DOWN to the mould → pour. Both climbs are
            // spelled out; neither was, and each cost a run to find. See goUpToThePool and
            // returnToTheForge.
            goUpToThePool(ctx, rig, src.getY(), "lava" + i, () ->
            fillFrom(ctx, rig, src, "lava" + i, Items.LAVA_BUCKET,
                    () -> returnToTheForge(ctx, rig, base.getY(), "cast" + i,
                    () -> placeFluid(ctx, rig, cell, away, Items.LAVA_BUCKET,
                    "cast" + i, () -> rig.settle(new HoldStill(3), 12, () -> {
                var got = ctx.level().getBlockState(cell).getBlock();
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
                fillFrom(ctx, rig, wet, "recover" + i, Items.WATER_BUCKET,
                        () -> drainTheAlcove(ctx, rig, i, DRAIN_LEGS,
                        () -> castCell(ctx, rig, base, away, pool, i + 1, then)));
            })))));
        });
    }

    /** Stand level with {@code target} and empty the held bucket into it, aiming at the solid block
     *  behind it. Level, because a steep ray enters the face a block low and lands in the wrong cell. */
    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, Runnable then) {
        standLevelWith(ctx, rig, target, away, tag,
                () -> placeFluid(ctx, rig, target, away, held, tag, POUR_APPROACHES, then));
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
        BlockPos col = target.relative(away.getOpposite(), 1);
        rig.evidence(tag + ".raise", rig.player().blockPosition().toShortString() + " → y=" + wantY
                + "（在 " + col.getX() + "," + col.getZ() + " 这一柱上垒台阶，浇 "
                + target.toShortString() + " 得跟它同高）");
        WorldDriverJourneyScenes.walkToColumn(rig, tag + ".raiseTo", col.getX(), col.getZ(), 1, 800, () -> {
            JourneyShaft.climbOut(rig, wantY, () -> {
                BotConfig.allowPlace = false;      // the casting phase is place-free again
                rig.evidence(tag + ".raisedY", rig.player().blockPosition().getY() + "/" + wantY);
                then.run();
            });
        }, () -> {
            rig.evidence(tag + ".raiseStuck", "走不到 " + col.getX() + "," + col.getZ()
                    + "，从当前高度浇（多半会被射线闸拦下）");
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
    private static BlockPos standToPour(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, Map<String, Integer> why) {
        return standToPour(level, rig, target, away, why, false);
    }

    /** {@code verifiedOnly} drops the standable fallback, which is what makes this answerable
     *  as a QUESTION — "is there anywhere down here with a clear line to this cell" — rather
     *  than only as a place to walk to. {@link #standLevelWith} asks it that way. */
    private static BlockPos standToPour(ServerLevel level, JourneyRig rig, BlockPos target,
                                        Direction away, Map<String, Integer> why,
                                        boolean verifiedOnly) {
        BlockPos backing = target.relative(away);
        BlockPos from = rig.player().blockPosition();
        BlockPos best = null, standable = null;
        double bestD = Double.MAX_VALUE, standableD = Double.MAX_VALUE;
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
                    // Standable, whatever the ray says. Kept separately so a body that can stand
                    // somewhere sensible is never left with nowhere to go because the ray test is
                    // stricter than it should be — the pour's own `.picks` gate still refuses to
                    // spend the bucket, so falling back here cannot cause a wrong-cell pour.
                    if (d < standableD) { standableD = d; standable = foot; }
                    // The eye a body standing here would have, and the clip a filled bucket runs
                    // from it. `Fluid.NONE`, because that is what a non-empty bucket uses.
                    var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                            foot.getY() + (afloat ? 1 : 0) + rig.player().getEyeHeight(),
                            foot.getZ() + 0.5);
                    var aim = net.minecraft.world.phys.Vec3.atCenterOf(backing);
                    if (eye.distanceTo(aim) > BUCKET_REACH) {
                        why.merge("够不着背板", 1, Integer::sum); continue;
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
                        why.merge("打中背板的 " + hit.getDirection() + " 面", 1, Integer::sum); continue;
                    }
                    if (d < bestD) { bestD = d; best = foot; }
                }
        return best != null || verifiedOnly ? best : standable;
    }

    private static void placeFluid(SceneContext ctx, JourneyRig rig, BlockPos target, Direction away,
                                   net.minecraft.world.item.Item held, String tag, int tries,
                                   Runnable then) {
        BlockPos backing = target.relative(away);
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        BlockPos goal = standToPour(ctx.level(), rig, target, away, why);
        if (goal == null) {
            ctx.fail("模腔里没有能浇到 " + target.toShortString() + " 的落脚点："
                    + "要求脚下实心、头顶两格空、射线打在背板 " + backing.toShortString()
                    + " 的近面上 —— 身体在 " + rig.player().blockPosition()
                    + "，各项否决计数：" + why);
            return;
        }
        rig.evidence(tag + ".stand", goal.toShortString() + " 否决计数 " + why);
        rig.settle(new IntentProcess(new Intent(new Goal.Block(goal))), 1_200, () -> {
            WorldDriverJourneyScenes.holdForUse(rig, held, tag);
            rig.body().avatar().aimAtBlock(backing);
            // Clear a plant off the line first. This rung's lake is at y=63 — on the SURFACE — so
            // unlike the underground forge it is standing in grass, and grass is REPLACEABLE: the
            // pour would not miss, it would succeed into the grass cell and be read as "no obsidian
            // here". Same swing the obsidian rung uses, and for the same reason mine cannot do it.
            clearPlantOnLine(ctx, rig, backing, tag, () -> rig.settle(new HoldStill(2), 10, () -> {
                // Where the fluid is actually going to land, recorded BEFORE it is spent. A filled
                // bucket clips with `Fluid.NONE` and empties into the cell in front of the face it
                // hits, so this pick IS the destination — and without it a pour that succeeded into
                // the wrong cell is indistinguishable from a pour that did not work, which is the
                // shape of the last three rounds of this rung's investigation. `pourInto` has had
                // this instrument for a while; the ten casts that matter never did.
                ServerLevel lvl = ctx.level();
                var hit = WorldDriverJourneyScenes.aimedAt(rig.player(), BUCKET_REACH, false);
                BlockPos lands = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().relative(hit.getDirection()) : null;
                rig.evidence(tag + ".picks", (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        ? hit.getBlockPos().toShortString() + " " + lvl.getBlockState(hit.getBlockPos()).getBlock()
                          + " face=" + hit.getDirection() + " → 落进 " + lands.toShortString()
                        : String.valueOf(hit.getType()))
                        + "（想浇 " + target.toShortString() + "，背板 " + backing.toShortString()
                        + "=" + lvl.getBlockState(backing).getBlock()
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
                                () -> placeFluid(ctx, rig, target, away, held, tag, tries - 1, then));
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
            rig.evidence("drain." + i, wet == null ? "壁龛已排干"
                    : "等了 " + (DRAIN_LEGS * DRAIN_TICKS) + " tick 仍有流体：" + wet
                      + " —— 水源没被收回来，下一格挖开就会灌满");
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
            for (int dy = 0; dy <= 1; dy++) {
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
            for (int dy = 0; dy <= 1; dy++) {
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
        if (i >= blocked.size()) { then.run(); return; }
        rig.mineCellOrGiveUp(blocked.get(i), 600, () -> clearNext(rig, blocked, i + 1, then));
    }

    /** Break whatever no-collider block the aim ray stops on before {@code want}, then continue.
     *  One swing only: if the line is blocked by something solid, that is a placement problem and
     *  the caller's own evidence should say so rather than this quietly digging through it. */
    private static void clearPlantOnLine(SceneContext ctx, JourneyRig rig, BlockPos want,
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

    /**
     * Fill the (empty) bucket from a fluid source, standing beside it — and stop when it does not.
     *
     * <p>This used to record the {@code InteractionResult} and carry on regardless, which is how run
     * 21 poured nothing into cell zero and reported it as a casting failure. What actually happened
     * is one line above that: {@code lava0.result=FAIL}, the bucket still empty, and the pour then
     * ran with <b>cobblestone in the hand</b> ({@code cast0.hand=拿不到 minecraft:lava_bucket}).
     * A `PASS` from a block item is byte-identical to a bucket whose ray missed, so the rung's
     * verdict named the cast — three inferences from a fill nobody checked.
     *
     * <p>{@code FAIL} from an empty bucket means vanilla saw a block that is not a pickable source:
     * either the source is gone, or there is rock between the eyes and it. Those want opposite
     * responses, so the miss line records the range and what the ray actually stopped on, and the
     * retry re-targets the nearest source to WHERE THE BODY NOW IS rather than asking the same
     * question from the same cell — a retry that changes nothing is not a retry.
     */
    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                                 net.minecraft.world.item.Item wanted, Runnable then) {
        fillFrom(ctx, rig, src, tag, wanted, FILL_APPROACHES, then);
    }

    /** How many sources a fill may try before the rung stops. Three: one for a walk that ended
     *  short, one for a source another cast already spent, and one to be unlucky with. */
    private static final int FILL_APPROACHES = 3;

    private static void fillFrom(SceneContext ctx, JourneyRig rig, BlockPos src, String tag,
                                 net.minecraft.world.item.Item wanted, int tries, Runnable then) {
        String id = String.valueOf(BuiltInRegistries.ITEM.getKey(wanted));
        boolean lava = wanted == Items.LAVA_BUCKET;
        // WHERE TO STAND is chosen before the walk, not discovered after it. `Goal.Near(src, 2)` puts
        // the body within two blocks of a source and says nothing about what is between them, so
        // whether the bucket filled came down to where the climb happened to emerge: the same code
        // filled at `-12,63,21` one run and reported `射线停在 -10,63,21 stone` the next, two runs
        // apart, with nothing changed. That is the pour's old bug on the other side of the trip, and
        // this is the pour's fix on the other side of the trip.
        Map<String, Integer> why = new java.util.LinkedHashMap<>();
        FillSpot spot = standToFill(ctx.level(), rig, src, lava, FILL_RESEARCH, why);
        rig.evidence(tag + ".spot", spot == null
                ? "没找到能看见源块的落脚点，退回 Near(" + src.toShortString() + ",2)；否决计数 " + why
                : "站 " + spot.stand().toShortString() + " 瞄 " + spot.source().toShortString());
        Goal where = spot == null ? new Goal.Near(src, 2) : new Goal.Block(spot.stand());
        rig.settle(new IntentProcess(new Intent(where)), 1_500, () -> {
            // Re-ask from where the body ACTUALLY ended up. The plan above is what makes a good spot
            // likely; this is what makes the aim correct, because a walk that stopped a cell short
            // has a different set of sources in view and only the clip from here knows which.
            BlockPos seen = visibleSourceNear(rig, lava, FILL_RESEARCH);
            BlockPos aim = seen != null ? seen : (spot == null ? src : spot.source());
            if (!aim.equals(src)) rig.evidence(tag + ".aim", src.toShortString() + " → "
                    + aim.toShortString() + "（计划的那格被挡住，改瞄看得见的一格）");
            WorldDriverJourneyScenes.holdForUse(rig, Items.BUCKET, tag);
            scoop(ctx, rig, src, aim, tag, wanted, id, lava, tries, AIM_TRIES, then);
        });
    }

    /** How many times a fill may re-aim, or clear its own line, before it spends the attempt.
     *  Three, and each one changes something — see {@link #scoop}. */
    private static final int AIM_TRIES = 3;

    /**
     * Aim, check where the ray actually goes, and only then use the bucket.
     *
     * <p>The pour has had this gate for a while and the fill did not, which is the whole of run 18's
     * failure: {@code lava0.spot} planned a stand from which the clip landed on the source,
     * {@code lava0.result=FAIL} an instant later, and {@code lava0.miss.3} explained why —
     * {@code 瞄 -11,63,21（现在是 lava），距 1.8m，射线停在 -11,64,22 Block{minecraft:gravel}}. Between
     * choosing the spot and using the bucket, <b>a gravel block fell into the line</b>. Nothing was
     * wrong with the plan; the world moved under it.
     *
     * <p>So the ray is predicted rather than assumed, and a prediction that misses gets one of two
     * answers, both of which change the world rather than repeat the question:
     * <ul>
     *   <li>the ray landed somewhere else and a DIFFERENT source is now visible — aim at that one;
     *   <li>the ray stopped on a solid block inside arm's reach — break it. At a lake's edge that
     *       block is gravel or a lip of stone, and breaking it is what a player does.
     * </ul>
     */
    private static void scoop(SceneContext ctx, JourneyRig rig, BlockPos src, BlockPos aim, String tag,
                              net.minecraft.world.item.Item wanted, String id, boolean lava,
                              int tries, int aims, Runnable then) {
        rig.body().avatar().aimAtBlock(aim);
        rig.settle(new HoldStill(2), 10, () -> {
            ServerLevel level = ctx.level();
            var pre = WorldDriverJourneyScenes.aimedAt(rig.player(), BUCKET_REACH, true);
            // A SOURCE, not merely the right cell with the right fluid in it. `BucketItem.use` clips
            // with `Fluid.SOURCE_ONLY` and returns PASS — doing nothing whatsoever — when that clip
            // finds none, and PASS is exactly what run 26 got: `recover1.result=PASS` beside
            // `射线停在 -10,57,38 Block{minecraft:water}` at 1.8 m. The cell was water and was not a
            // source, and nothing here could tell those apart, so a fill vanilla had refused outright
            // read as a fill that missed — and the retry then aimed at the same non-source again.
            var fluid = pre.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    ? level.getFluidState(pre.getBlockPos()) : null;
            rig.evidence(tag + ".aimsAt", fluid == null ? String.valueOf(pre.getType())
                    : pre.getBlockPos().toShortString() + " "
                      + level.getBlockState(pre.getBlockPos()).getBlock()
                      + " 源块=" + fluid.isSource() + " 液位=" + fluid.getAmount()
                      + (pre.getBlockPos().equals(aim) ? "" : "（想瞄 " + aim.toShortString() + "）"));
            boolean onTarget = fluid != null && pre.getBlockPos().equals(aim) && fluid.isSource();
            if (!onTarget && aims > 0) {
                BlockPos again = visibleSourceNear(rig, lava, FILL_RESEARCH);
                if (again != null && !again.equals(aim)) {
                    rig.evidence(tag + ".reaim." + aims, aim.toShortString() + " → "
                            + again.toShortString() + "（射线没落在计划那格上）");
                    scoop(ctx, rig, src, again, tag, wanted, id, lava, tries, aims - 1, then);
                    return;
                }
                if (pre.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                        && level.getFluidState(pre.getBlockPos()).isEmpty()) {
                    BlockPos wall = pre.getBlockPos();
                    rig.evidence(tag + ".clearedLine." + aims, wall.toShortString() + " "
                            + level.getBlockState(wall).getBlock() + " 挡在眼睛和 "
                            + aim.toShortString() + " 之间，敲掉它");
                    rig.mineCellOrGiveUp(wall, 600,
                            () -> scoop(ctx, rig, src, aim, tag, wanted, id, lava, tries, aims - 1, then));
                    return;
                }
            }
            rig.evidence(tag + ".result", String.valueOf(rig.body().avatar().useItemInHand()));
            if (rig.carrying(id) >= 1) { then.run(); return; }
            var hit = WorldDriverJourneyScenes.aimedAt(rig.player(), BUCKET_REACH, true);
            double range = rig.player().getEyePosition()
                    .distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(aim));
            rig.evidence(tag + ".miss." + tries, String.format(java.util.Locale.ROOT,
                    "桶里还是空的；瞄 %s（现在是 %s），距 %.1fm，射线停在 %s",
                    aim.toShortString(), level.getBlockState(aim).getBlock(), range,
                    hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            ? hit.getBlockPos().toShortString() + " "
                              + level.getBlockState(hit.getBlockPos()).getBlock()
                            : String.valueOf(hit.getType())));
            // A DIFFERENT source, explicitly. The old line asked for "the nearest one" and got back
            // the cell that had just failed, so the guard below refused the retry and the rung died
            // with two of its three approaches unspent — measured as
            // 「瞄了 -11,63,21 没装上，改瞄 -11,63,21 仍然不行」.
            BlockPos other = nextSourceBesides(ctx, rig, lava, aim);
            if (tries > 1 && other != null) {
                rig.evidence(tag + ".retarget." + tries, aim.toShortString() + " → "
                        + other.toShortString());
                fillFrom(ctx, rig, other, tag, wanted, tries - 1, then);
                return;
            }
            ctx.fail("装不到 " + id + "：瞄了 " + aim.toShortString() + " 没装上，"
                    + (other == null ? "身边 " + FILL_RESEARCH + " 格内没有别的源块可换"
                                     : "改瞄 " + other + " 仍然不行")
                    + "；身边的源块：" + sourcesNear(level, rig.player().blockPosition(),
                            FILL_RESEARCH, lava)
                    + " —— 空着桶走下去只会把失败写成「浇不出黑曜石」，而真正的失败在这里"
                    + "（见 " + tag + ".miss.*）");
        });
    }

    /**
     * Every source of the right fluid within reach of the body, listed.
     *
     * <p>The reading that separates "the bucket missed" from "there is nothing left to fill from",
     * and this rung has spent runs unable to tell those apart. It matters most on the recover: the
     * ten casts run on ONE water source, so a recover that comes back empty either aimed badly or
     * has just discovered that the cast spends the water — and only the second means the rung as
     * designed cannot finish.
     */
    private static String sourcesNear(ServerLevel level, BlockPos centre, int radius, boolean lava) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    var f = level.getFluidState(c);
                    if (!f.isSource()) continue;
                    if (f.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (++n > 6) continue;
                    out.append(out.isEmpty() ? "" : " ").append(c.toShortString());
                }
        return n == 0 ? "一格也没有" : n + " 格（" + out + (n > 6 ? " …" : "") + "）";
    }

    /** The nearest source of the right fluid that is NOT the one just tried. */
    private static BlockPos nextSourceBesides(SceneContext ctx, JourneyRig rig, boolean lava,
                                              BlockPos tried) {
        BlockPos here = rig.player().blockPosition();
        if (!lava) {
            BlockPos w = JourneyTerrain.shallowWaterNear(rig, FILL_RESEARCH);
            return w == null || w.equals(tried) ? null : w;
        }
        for (BlockPos c : JourneyTerrain.lavaSourcesNear(ctx.level(), here, FILL_RESEARCH, here))
            if (!c.equals(tried)) return c;
        return null;
    }

    /** How far to look for another source when a fill did not take. Small: the body is standing at
     *  the pool it walked to, and a source further than this is a different walk, not a retry. */
    private static final int FILL_RESEARCH = 8;

    /** Where to stand to fill a bucket, and which source to aim at from there. */
    private record FillSpot(BlockPos stand, BlockPos source) {}

    /** How many sources a fill spot may be searched around. The pool has seventy-five and they are
     *  sorted by how far the body has to walk, so the near dozen is the whole useful set. */
    private static final int FILL_SOURCES_TRIED = 16;

    /**
     * A cell beside the pool the body can STAND in, and a source it can provably reach from there.
     *
     * <p>The exact counterpart of {@link #standToPour}, and it is missing for the same reason that
     * one was: the rung asked the walker to get NEAR a coordinate and then hoped the geometry worked
     * out. It does not, at a lake's edge — a bucket clips from the eyes with {@code Fluid.SOURCE_ONLY}
     * and a finger of bank one cell wide is enough to stop it, so "there is lava two blocks away" and
     * "this bucket will fill" are different claims. Measured twice at 2.1 m and 1.9 m from live lava:
     * {@code 射线停在 -10,63,21 Block{minecraft:stone}}, bucket still empty.
     *
     * <p>So both halves are decided before the walk: a cell that is standable (feet and head clear of
     * blocks AND of fluid — this one stands next to lava) and from which the clip vanilla is about to
     * run lands on the source. Sources are tried nearest-first by how far the BODY must walk, so the
     * answer is also the cheapest trip.
     */
    private static FillSpot standToFill(ServerLevel level, JourneyRig rig, BlockPos pool, boolean lava,
                                        int radius, Map<String, Integer> why) {
        BlockPos from = rig.player().blockPosition();
        List<BlockPos> sources = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = pool.offset(dx, dy, dz);
                    var fluid = level.getFluidState(c);
                    if (!fluid.isSource()) continue;
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (lava && !level.getBlockState(c).is(Blocks.LAVA)) continue;
                    sources.add(c.immutable());
                }
        sources.sort(java.util.Comparator.comparingDouble(a -> a.distSqr(from)));
        FillSpot best = null;
        double bestD = Double.MAX_VALUE;
        int tried = 0;
        for (BlockPos src : sources) {
            if (++tried > FILL_SOURCES_TRIED) break;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;             // not IN the pool
                    // BELOW the source as well as level with it. Two rows down, because the cell a
                    // bucket has to take back is usually one ABOVE the floor the body stands on: the
                    // rung's own water sits in the frame's interior at y+1, and a search that only
                    // looked at the source's own level and higher answered "没找到能看见源块的落脚点"
                    // for a source two blocks away in a chamber the body was standing in.
                    for (int dy = -2; dy <= 1; dy++) {
                        BlockPos foot = src.offset(dx, dy, dz);
                        double d = foot.distSqr(from);
                        if (d >= bestD) continue;
                        if (!level.getBlockState(foot.below()).blocksMotion()) {
                            why.merge("脚下不实心", 1, Integer::sum); continue;
                        }
                        // Water underfoot is a wet floor, not a disqualification — and refusing it
                        // is what left the recover with nowhere to stand, because the bucket the
                        // rung is trying to take BACK is the thing that flooded the alcove. Lava is
                        // still a refusal: standing in it costs the body, not the bucket.
                        if (level.getFluidState(foot).is(net.minecraft.tags.FluidTags.LAVA)) {
                            why.merge("落脚格是岩浆", 1, Integer::sum); continue;
                        }
                        if (!level.getBlockState(foot).getCollisionShape(level, foot).isEmpty()) {
                            why.merge("落脚格被占", 1, Integer::sum); continue;
                        }
                        BlockPos head = foot.above();
                        if (level.getFluidState(head).is(net.minecraft.tags.FluidTags.LAVA)) {
                            why.merge("头顶是岩浆", 1, Integer::sum); continue;
                        }
                        if (!level.getBlockState(head).getCollisionShape(level, head).isEmpty()) {
                            why.merge("头顶被占", 1, Integer::sum); continue;
                        }
                        var eye = new net.minecraft.world.phys.Vec3(foot.getX() + 0.5,
                                foot.getY() + rig.player().getEyeHeight(), foot.getZ() + 0.5);
                        var aim = net.minecraft.world.phys.Vec3.atCenterOf(src);
                        if (eye.distanceTo(aim) > BUCKET_REACH) {
                            why.merge("够不着源块", 1, Integer::sum); continue;
                        }
                        var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, aim,
                                net.minecraft.world.level.ClipContext.Block.OUTLINE,
                                net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, rig.player()));
                        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                            why.merge("射线没打到方块", 1, Integer::sum); continue;
                        }
                        if (!hit.getBlockPos().equals(src)) {
                            why.merge("射线停在 " + level.getBlockState(hit.getBlockPos()).getBlock(),
                                    1, Integer::sum);
                            continue;
                        }
                        bestD = d;
                        best = new FillSpot(foot, src);
                    }
                }
        }
        return best;
    }

    /** A survival player's block reach, which is what {@code Item.getPlayerPOVHitResult} traces with.
     *  {@link WorldDriverJourneyScenes#TUNNEL_REACH} is half a block longer and is the digging figure; using it here made a
     *  miss report a blocker that was never inside the bucket's own ray. */
    private static final double BUCKET_REACH = 4.5;

    /**
     * The nearest source of the right fluid whose line from the body's eyes is CLEAR.
     *
     * <p>The question a bucket actually asks, and the one nothing was asking. {@code useItemInHand}
     * clips from the eyes with {@code Fluid.SOURCE_ONLY} and fills from whatever it lands on, so
     * "there is a source two blocks away" and "this bucket will fill" are different claims — the
     * second needs the cells between to be empty, and at a lake's edge they routinely are not.
     *
     * <p>Clipped per candidate rather than aimed-and-tried, so choosing costs no ticks and no bucket.
     * The same clip vanilla will do is done here first, which makes this a prediction rather than a
     * heuristic: a cell this returns is a cell the bucket fills from.
     */
    private static BlockPos visibleSourceNear(JourneyRig rig, boolean lava, int radius) {
        ServerLevel level = rig.ctx().level();
        var fp = rig.player();
        net.minecraft.world.phys.Vec3 eye = fp.getEyePosition();
        BlockPos centre = fp.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++)
            for (int dy = -radius; dy <= radius; dy++)
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos c = centre.offset(dx, dy, dz);
                    var fluid = level.getFluidState(c);
                    if (!fluid.isSource()) continue;
                    if (fluid.is(net.minecraft.tags.FluidTags.LAVA) != lava) continue;
                    if (lava && !level.getBlockState(c).is(Blocks.LAVA)) continue;
                    var target = net.minecraft.world.phys.Vec3.atCenterOf(c);
                    double d = eye.distanceToSqr(target);
                    if (d >= bestD || d > BUCKET_REACH * BUCKET_REACH) continue;
                    var hit = level.clip(new net.minecraft.world.level.ClipContext(eye, target,
                            net.minecraft.world.level.ClipContext.Block.OUTLINE,
                            net.minecraft.world.level.ClipContext.Fluid.SOURCE_ONLY, fp));
                    if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) continue;
                    if (!hit.getBlockPos().equals(c)) continue;
                    bestD = d;
                    best = c;
                }
        return best;
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
        if (cast < RING.length) {
            ctx.fail("门框没浇满：只有 " + cast + "/" + RING.length + " 块黑曜石 —— 点不着一个缺角的门");
            return;
        }
        BlockPos hearth = frameCell(base, away, 0, 0);
        BlockPos doorway = hearth.above();
        rig.attempting("点火");
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
