package net.magicterra.worlddriver.bot.stagewright.scene;

import java.util.List;
import java.util.Locale;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.bot.BotConfig;
import net.magicterra.worlddriver.bot.Goal;
import net.magicterra.worlddriver.bot.stagewright.ClientHelm;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Water walks driven on the client's REAL player — the walker, the physics and the reflexes that
 * actually ship. Every scene here is red until the engine can do the thing; none is softened to
 * what the walker can already satisfy.
 *
 * <p>Each arena is a flat slab with a pool cut INTO it (water piled above the surface flows away
 * between staging and measuring), the bot in the water, and one walk to a cell on dry ground.
 */
public final class WorldDriverClientWaterScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientFlushBankClimbOut", 2_000, WorldDriverClientWaterScenes::flushBankClimbOut),
                Scene.of("wd.clientFlushBankClimbOutEmptyHanded", 2_000,
                        WorldDriverClientWaterScenes::flushBankClimbOutEmptyHanded),
                Scene.of("wd.clientOneHighBankPlaceOut", 2_000, WorldDriverClientWaterScenes::oneHighBankPlaceOut),
                Scene.of("wd.clientOneHighBankDigOut", 2_000, WorldDriverClientWaterScenes::oneHighBankDigOut),
                Scene.of("wd.clientTwoHighBankPlaceOut", 2_000, WorldDriverClientWaterScenes::twoHighBankPlaceOut),
                Scene.of("wd.clientThreeHighBankPlaceOut", 2_000, WorldDriverClientWaterScenes::threeHighBankPlaceOut),
                Scene.of("wd.clientTwoHighBankDigOut", 2_000, WorldDriverClientWaterScenes::twoHighBankDigOut),
                Scene.of("wd.clientShallowPoolStepOut", 2_000, WorldDriverClientWaterScenes::shallowPoolStepOut),
                Scene.of("wd.clientShallowPoolStepOutEmptyHanded", 2_000,
                        WorldDriverClientWaterScenes::shallowPoolStepOutEmptyHanded),
                Scene.of("wd.clientOneHighStoneBankPickaxeOut", 2_000,
                        WorldDriverClientWaterScenes::oneHighStoneBankPickaxeOut),
                Scene.of("wd.clientFlowingChannelPlaceOut", 2_000,
                        WorldDriverClientWaterScenes::flowingChannelPlaceOut),
                Scene.of("wd.clientFlowingTrenchPlaceOut", 2_000,
                        WorldDriverClientWaterScenes::flowingTrenchPlaceOut),
                Scene.of("wd.clientOpenWaterCross", 2_400, WorldDriverClientWaterScenes::openWaterCross));
    }

    /** Top of the slab: the ground's foot cell is {@code GROUND + 1}. */
    private static final int GROUND = 20;
    private static final int HALF = 12;
    private static final int POOL_HALF = 4;
    private static final int POOL_DEPTH = 6;
    private static final int SYNC_TICKS = 30;
    private static final int SETTLE_TICKS = 10;
    private static final int LEG_TICKS = 1_200;

    /**
     * A bot floating in a pool whose rim is FLUSH with the water surface walks to a cell on the rim
     * and ends standing on it. Staged with dirt in hand, as the ladder's bot always has.
     *
     * <p>This is TODO J47 on the real client: the headless twin ends {@code end=path-consumed} one
     * cell short with water under its feet, and the ladder's landing scene stands red on it.
     */
    private static void flushBankClimbOut(SceneContext ctx) {
        climbOut(ctx, "dirt", 0, Blocks.STONE, 400, new ItemStack(Items.DIRT, 30));
    }

    /** The same walk with nothing to place: the bank is flush, so nothing should NEED placing. */
    private static void flushBankClimbOutEmptyHanded(SceneContext ctx) {
        climbOut(ctx, "empty", 0, Blocks.STONE, 400);
    }

    /**
     * The rim stands ONE block above the water surface: vanilla's swim-out boost cannot mount it, so
     * the bot must place a foothold (it holds dirt). Digging the dirt rim instead is not a failure
     * of geometry but of judgment, and the budget is what says so: a swim to the wall, thirty ticks
     * of stall, one placement and one hop fit in 150 ticks; the shortest floating dig does not.
     */
    private static void oneHighBankPlaceOut(SceneContext ctx) {
        climbOut(ctx, "dirt", 1, Blocks.DIRT, 150, new ItemStack(Items.DIRT, 30));
    }

    /**
     * The same rim with nothing to place: the only way up is to dig the DIRT rim down to flush. A
     * floating player mines at a fifth of a fifth of its grounded speed (vanilla: off the ground, eyes
     * in water), which is exactly why this arm has a budget of its own.
     */
    private static void oneHighBankDigOut(SceneContext ctx) {
        climbOut(ctx, "empty", 1, Blocks.DIRT, 900);
    }

    /**
     * Two above the surface: one foothold lifts the feet only to the rim's lower course, so the
     * pillar has to keep going up its own wall-supported column and top out with a walk. Two
     * placements and their hops fit in 250 ticks.
     */
    private static void twoHighBankPlaceOut(SceneContext ctx) {
        climbOut(ctx, "dirt", 2, Blocks.DIRT, 250, new ItemStack(Items.DIRT, 30));
    }

    /** Three above: the same column, one rung taller — the river-cliff shape the ladder meets. */
    private static void threeHighBankPlaceOut(SceneContext ctx) {
        climbOut(ctx, "dirt", 3, Blocks.DIRT, 350, new ItemStack(Items.DIRT, 30));
    }

    /**
     * Two above with nothing to place: a staircase of two dirt cells, each dug afloat. The budget
     * is two floating dirt digs plus the hops between them, nothing more.
     */
    private static void twoHighBankDigOut(SceneContext ctx) {
        climbOut(ctx, "empty", 2, Blocks.DIRT, 900);
    }

    /**
     * A ONE-DEEP pool with a dirt rim one above the surface, dirt in hand. The player stands on the
     * floor with its eyes out of the water, yet vanilla still swims it (fluid over 0.4 high), so a
     * ground jump onto the rim is not available; the exit is the swim boost against the rim or a
     * foothold. The old planner called this cell a floor and jumped; this walk measures what the
     * real player does with the honest plan.
     */
    private static void shallowPoolStepOut(SceneContext ctx) {
        climbOut(ctx, "dirt", 1, Blocks.DIRT, 1, 200, new ItemStack(Items.DIRT, 30));
    }

    /** The same one-deep pool with nothing in hand: the swim boost against the rim, or the dig. */
    private static void shallowPoolStepOutEmptyHanded(SceneContext ctx) {
        climbOut(ctx, "empty", 1, Blocks.DIRT, 1, 600);
    }

    /**
     * A STONE rim one above the surface and an iron pickaxe in the hand: the dig must pick the
     * tool and still finish afloat. Bare-handed this rim is hopeless (the walker poisons it), so
     * a bot that ends up digging by hand has not selected its tool.
     */
    private static void oneHighStoneBankPickaxeOut(SceneContext ctx) {
        climbOut(ctx, "pickaxe", 1, Blocks.STONE, 400, new ItemStack(Items.IRON_PICKAXE));
    }

    /**
     * A one-deep channel fed by a single source, so the water the bot stands in is FLOWING toward
     * the far end; the east bank is one course above the water. The bot holds dirt. Getting out
     * means placing a block into a flowing cell while the current pushes the bot off its column,
     * which is the river-edge shape the ladder's towers kept losing blocks to.
     */
    private static void flowingChannelPlaceOut(SceneContext ctx) {
        flowingOut(ctx, 1, FLOW_DRY_BY);
    }

    /**
     * The same current at the bottom of a trench two deep: the bot stands on the trench floor in
     * one block of flowing water (a deeper FLOWING river is not a vanilla shape — water over water
     * does not spread), and the east bank is three above its feet. Three rungs in a current, or a
     * staircase dug wet; the budget is the rungs.
     */
    private static void flowingTrenchPlaceOut(SceneContext ctx) {
        flowingOut(ctx, 2, FLOW_DRY_BY + 100);
    }

    /** @param depth how far the trench floor lies under the surface cell {@code GROUND}; the water is
     *              one flowing layer on that floor either way */
    private static void flowingOut(SceneContext ctx, int depth, int dryBy) {
        stageSlab(ctx, 1, Blocks.DIRT);
        // Channel x∈[-1,1], z∈[-CHANNEL_HALF,CHANNEL_HALF-1]: floor at GROUND-depth, air above it up to
        // GROUND+1, one source column at the far end whose water runs along the floor.
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -CHANNEL_HALF; dz < CHANNEL_HALF; dz++) {
                for (int dy = 1; dy > -depth; dy--) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
                if (dz == -CHANNEL_HALF) ctx.setBlock(dx, GROUND + 1 - depth, dz, Blocks.WATER);
            }
        int goalY = GROUND + 2;
        BlockPos start = ctx.rel(0, GROUND + 1 - depth, 0);
        BlockPos goal = ctx.rel(4, goalY, 0);
        ctx.record("test setup", "trench floor y=" + (GROUND - depth) + " with one layer of flowing water, source at z="
                + (-CHANNEL_HALF) + " flowing toward +z; east bank foot level y=" + goalY + " (dirt), "
                + (goal.getY() - start.getY()) + " above the bot's feet; start " + start.toShortString() + ", goal "
                + goal.toShortString() + ", in hand=dirt");

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.hold(new ItemStack(Items.DIRT, 30));
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.walkerFootholdBeforeBankDig = true;
        BotConfig.walkerClimbIntentFromSurface = true;
        BotConfig.walkerPillarTopsOutAtFlushExit = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerShallowWaterSideFoothold = true;
        BotConfig.walkerClimbOutResyncsAim = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();

        // The source needs ~5 ticks per cell to reach the far end; wait for the current to exist.
        helm.sync(FLOW_SYNC_TICKS, () -> {
            var flow = ctx.level().getFluidState(start).getFlow(ctx.level(), start);
            ctx.record("start after sync", helm.where() + String.format(Locale.ROOT, ", water flow at the foot cell (%.2f, %.2f)", flow.x, flow.z));
            if (!body.isInWater()) ctx.fail("test setup invalid: the bot is not in water after the sync: " + helm.where());
            if (flow.length() < 0.01) ctx.fail("test setup invalid: there is no water flow at the foot cell: " + flow);
            final int[] inWaterTicks = { 0 };
            final int[] firstDryTick = { -1 };
            final int[] legTicks = { 0 };
            final double[] maxDrift = { 0 };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (body.isInWater()) inWaterTicks[0]++;
                // Ashore = standing at the bank's level. `isInWater` reads false in a shallow flowing
                // layer far from its source, so "not in water and on the ground" is true on the floor
                // of the trench itself, which is where the goto starts.
                if (firstDryTick[0] < 0 && body.onGround() && !body.isInWater() && body.getY() >= goalY - 0.05)
                    firstDryTick[0] = t;
                maxDrift[0] = Math.max(maxDrift[0], Math.abs(body.getZ() - (start.getZ() + 0.5)));
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    ctx.record("progress", String.format(Locale.ROOT,
                            "%d ticks in water, first landed on dry ground at tick %s, drifted at most %.2f blocks downstream",
                            inWaterTicks[0], firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]), maxDrift[0]));
                    double flat = helm.flatDistance(goal);
                    boolean ashore = body.onGround() && !body.isInWater() && body.getY() >= goalY - 0.05;
                    ctx.record("final position", helm.where());
                    ctx.check(ashore).as("A: the bot ends standing on dry ground: " + helm.where()).isTrue();
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "B: stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(firstDryTick[0] >= 0 && firstDryTick[0] <= dryBy).as("C: gets ashore within " + dryBy
                            + " ticks: first landed on dry ground at tick "
                            + (firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]))).isTrue();
                });
            });
        });
    }

    private static final int CHANNEL_HALF = 4;
    private static final int FLOW_SYNC_TICKS = 80;
    private static final int FLOW_DRY_BY = 200;

    /**
     * A bot in a long, deep lake swims its whole length to a cell on the far bank, flush with the
     * water. Nothing to climb, nothing to avoid: the walk measures how the planner and the walker
     * handle open water by itself — how many ticks a straight crossing costs and how many times the
     * plan is redone on the way.
     */
    private static void openWaterCross(SceneContext ctx) {
        for (int dx = -4; dx <= LAKE_LEN + 6; dx++)
            for (int dz = -LAKE_HALF - 2; dz <= LAKE_HALF + 2; dz++) {
                for (int dy = -POOL_DEPTH - 2; dy <= 0; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
                for (int dy = 1; dy <= 6; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        for (int dx = -2; dx <= LAKE_LEN + 2; dx++)
            for (int dz = -LAKE_HALF; dz <= LAKE_HALF; dz++)
                for (int dy = 0; dy > -POOL_DEPTH; dy--) ctx.setBlock(dx, GROUND + dy, dz, Blocks.WATER);
        BlockPos start = ctx.rel(0, GROUND - 1, 0);
        BlockPos goal = ctx.rel(LAKE_LEN + 4, GROUND + 1, 0);
        ctx.record("test setup", "lake at x in [-2," + (LAKE_LEN + 2) + "], z within +/-" + LAKE_HALF + ", " + POOL_DEPTH
                + " deep, water surface cell y=" + GROUND + ", both banks flush with the surface; start "
                + start.toShortString() + ", goal " + goal.toShortString());

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.hold(new ItemStack(Items.DIRT, 16));
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.walkerFootholdBeforeBankDig = true;
        BotConfig.walkerClimbIntentFromSurface = true;
        BotConfig.walkerPillarTopsOutAtFlushExit = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerShallowWaterSideFoothold = true;
        BotConfig.walkerClimbOutResyncsAim = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();

        helm.sync(SYNC_TICKS, () -> {
            ctx.record("start after sync", helm.where());
            if (!body.isInWater()) {
                ctx.fail("test setup invalid: the bot is not in water after a " + SYNC_TICKS + "-tick sync: " + helm.where());
            }
            final int[] legTicks = { 0 };
            final int[] firstDryTick = { -1 };
            final double[] minGap = { Double.MAX_VALUE };
            final double[] maxSide = { 0 };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (!body.isInWater() && body.onGround() && firstDryTick[0] < 0) firstDryTick[0] = t;
                minGap[0] = Math.min(minGap[0], helm.flatDistance(goal));
                maxSide[0] = Math.max(maxSide[0], Math.abs(body.getZ() - (goal.getZ() + 0.5)));
            };
            helm.goTo("leg", new Goal.Block(goal), goal, 2 * LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    ctx.record("progress", String.format(Locale.ROOT,
                            "first landed on dry ground at tick %s, closest approach to the goal %.2f blocks, largest deviation from the straight line %.2f blocks",
                            firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]), minGap[0], maxSide[0]));
                    double flat = helm.flatDistance(goal);
                    boolean ashore = body.onGround() && !body.isInWater() && body.getY() >= GROUND + 1 - 0.05;
                    ctx.record("final position", helm.where());
                    ctx.check(ashore).as("A: the bot ends standing on dry ground on the far bank: " + helm.where()).isTrue();
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT,
                            "B: the bot stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(firstDryTick[0] >= 0 && firstDryTick[0] <= LAKE_DRY_BY).as(
                            "C: gets ashore within " + LAKE_DRY_BY + " ticks: first landed on dry ground at tick "
                            + (firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]))).isTrue();
                });
            });
        });
    }

    private static final int LAKE_LEN = 48;
    private static final int LAKE_HALF = 6;
    /** The sprint-swim cruise lands in ~350 ticks and one breath bob costs ~150 more; the pre-cruise
     *  tread took 550, so the budget separates the two. */
    private static final int LAKE_DRY_BY = 600;

    /**
     * @param rimRaise how many blocks the slab around the pool rises above the water surface cell
     * @param rim      the block the slab's top course is made of (what a dig arm has to chew)
     * @param dryBy    tick by which the bot must first stand on dry ground
     */
    private static void climbOut(SceneContext ctx, String arm, int rimRaise, Block rim, int dryBy, ItemStack... hand) {
        climbOut(ctx, arm, rimRaise, rim, POOL_DEPTH, dryBy, hand);
    }

    /** As above with the pool {@code poolDepth} cells deep; a one-deep pool stands the bot on
     *  the floor with its eyes out of the water, the shape the planner used to treat as a floor
     *  it could jump off. */
    private static void climbOut(SceneContext ctx, String arm, int rimRaise, Block rim, int poolDepth, int dryBy, ItemStack... hand) {
        stageSlab(ctx, rimRaise, rim);
        for (int dx = -POOL_HALF; dx <= POOL_HALF; dx++)
            for (int dz = -POOL_HALF; dz <= POOL_HALF; dz++) {
                for (int dy = 0; dy > -poolDepth; dy--) ctx.setBlock(dx, GROUND + dy, dz, Blocks.WATER);
                for (int dy = 1; dy <= rimRaise; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
        int goalY = GROUND + 1 + rimRaise;
        // One cell under the surface where the pool allows it, so the bot starts wet either way.
        BlockPos start = ctx.rel(0, GROUND - Math.min(poolDepth - 1, 1), 0);
        BlockPos goal = ctx.rel(POOL_HALF + 2, goalY, 0);
        ctx.record("test setup", "water surface cell y=" + GROUND + ", bank top " + rimRaise
                + " blocks above the surface (bank foot level y=" + goalY + ", bank material " + rim + "); pool +/-"
                + POOL_HALF + ", " + poolDepth + " deep; start " + start.toShortString() + ", goal "
                + goal.toShortString() + ", in hand=" + arm);

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        if (hand.length > 0) helm.hold(hand);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Shipped default, restored over the pinned baseline: the place arm is the scene that
        // exercises it, and the budget above is what would notice it going missing.
        BotConfig.walkerFootholdBeforeBankDig = true;
        BotConfig.walkerClimbIntentFromSurface = true;
        BotConfig.walkerPillarTopsOutAtFlushExit = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerShallowWaterSideFoothold = true;
        BotConfig.walkerClimbOutResyncsAim = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();

        helm.sync(SYNC_TICKS, () -> {
            ctx.record("start after sync", helm.where());
            if (!body.isInWater()) {
                ctx.fail("test setup invalid: the bot is not in water after a " + SYNC_TICKS + "-tick sync: " + helm.where());
            }
            final int[] inWaterTicks = { 0 };
            final int[] firstDryTick = { -1 };
            final int[] legTicks = { 0 };
            final double[] maxY = { body.getY() };
            final double[] minGap = { Double.MAX_VALUE };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (body.isInWater()) inWaterTicks[0]++;
                else if (firstDryTick[0] < 0 && body.onGround()) firstDryTick[0] = t;
                maxY[0] = Math.max(maxY[0], body.getY());
                minGap[0] = Math.min(minGap[0], helm.flatDistance(goal));
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                // Judged once the bot has come to rest, and SAMPLED until then: a goto that ends on
                // the last hop of a step-up is still in the air for a few ticks, the server's copy
                // of the bot can trail the client by several more, and "arrived" is about where
                // it lands. The watcher keeps counting so the landing tick is not lost to the gap.
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    ctx.record("progress", String.format(Locale.ROOT,
                            "%d ticks in water, first landed on dry ground at tick %s, highest y=%.2f, closest approach to the goal %.2f blocks",
                            inWaterTicks[0], firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]),
                            maxY[0], minGap[0]));
                    double flat = helm.flatDistance(goal);
                    boolean ashore = body.onGround() && !body.isInWater() && body.getY() >= goalY - 0.05;
                    ctx.record("final position", helm.where());
                    ctx.check(ashore).as("A: the bot ends standing on dry ground (onGround, not in water, feet at bank foot level): " + helm.where()).isTrue();
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT,
                            "B: the bot stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(firstDryTick[0] >= 0 && firstDryTick[0] <= dryBy).as(
                            "C: gets ashore within " + dryBy + " ticks: first landed on dry ground at tick "
                            + (firstDryTick[0] < 0 ? "never" : String.valueOf(firstDryTick[0]))).isTrue();
                });
            });
        });
    }

    /** Stone from {@code GROUND-8} to {@code GROUND}, then {@code rimRaise} courses of {@code rim}, air above. */
    private static void stageSlab(SceneContext ctx, int rimRaise, Block rim) {
        for (int dx = -HALF; dx <= HALF; dx++)
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -POOL_DEPTH - 2; dy <= 0; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
                for (int dy = 1; dy <= rimRaise; dy++) ctx.setBlock(dx, GROUND + dy, dz, rim);
                for (int dy = rimRaise + 1; dy <= rimRaise + 6; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
    }
}
