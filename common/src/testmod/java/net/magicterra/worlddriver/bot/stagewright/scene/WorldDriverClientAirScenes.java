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
import net.minecraft.world.level.block.Blocks;

/**
 * Walks that begin with the client's REAL player in the air — falling off something, knocked
 * back, or simply asked to go somewhere before it has landed. The walker's search starts from a
 * foot cell that has nothing under it, and everything downstream of that (the first path, the
 * first step-advance, the first stall clock) has to survive the landing.
 */
public final class WorldDriverClientAirScenes implements SceneProvider {

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.clientGotoStartsMidAir", 2_000, WorldDriverClientAirScenes::gotoStartsMidAir),
                Scene.of("wd.clientGotoStartsMidAirOverWater", 2_000,
                        WorldDriverClientAirScenes::gotoStartsMidAirOverWater),
                Scene.of("wd.clientParkourOverChasm", 2_000, WorldDriverClientAirScenes::parkourOverChasm),
                Scene.of("wd.clientPillarOutOfShaft", 2_000, WorldDriverClientAirScenes::pillarOutOfShaft));
    }

    /**
     * A two-wide chasm across the only way to the goal, deep enough to hurt and with nothing to
     * place. The walk is a run-up, one leap and a landing; the budget is that plus a short walk,
     * and the floor of the chasm is the line the player must never reach.
     */
    private static void parkourOverChasm(SceneContext ctx) {
        stageSlab(ctx);
        for (int dz = -HALF; dz <= HALF; dz++)
            for (int dx = 2; dx <= 3; dx++)
                for (int dy = 0; dy > -6; dy--) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
        BlockPos start = ctx.rel(-2, GROUND + 1, 0);
        BlockPos goal = ctx.rel(7, GROUND + 1, 0);
        ctx.record("test setup", "chasm at x in [2,3], 6 deep; start " + start.toShortString() + ", goal "
                + goal.toShortString() + ", empty hands");
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        BotConfig.allowBreak = false;
        BotConfig.allowPlace = false;
        BotConfig.walkerDebug = true;
        BotConfig.walkerFinalNodeDirectAim = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();
        helm.sync(30, () -> {
            ctx.record("start after sync", helm.where());
            final int[] arrivedTick = { -1 };
            final int[] legTicks = { 0 };
            final double[] minY = { body.getY() };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (arrivedTick[0] < 0 && body.onGround() && helm.flatDistance(goal) <= 1.0) arrivedTick[0] = t;
                minY[0] = Math.min(minY[0], body.getY());
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    ctx.record("progress", String.format(Locale.ROOT, "reached the goal at tick %s, lowest y=%.2f, health %.1f",
                            arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]), minY[0], body.getHealth()));
                    ctx.record("final position", helm.where());
                    double flat = helm.flatDistance(goal);
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "A: stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(minY[0] >= GROUND + 1 - 0.05).as(String.format(Locale.ROOT,
                            "B: never falls into the chasm: lowest y=%.2f (ground foot level %d)", minY[0], GROUND + 1)).isTrue();
                    ctx.check(arrivedTick[0] >= 0 && arrivedTick[0] <= 150).as("C: arrives within 150 ticks: arrived at tick "
                            + (arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]))).isTrue();
                });
            });
        });
    }

    /**
     * The player stands at the bottom of a one-wide shaft six deep with dirt in hand; the goal is on
     * the surface three cells from the mouth. The only way out is six pillar rungs. Each rung is a
     * jump and a placement, a dozen ticks; the budget is those plus the walk, not a search that
     * rediscovers the shaft every rung.
     */
    private static void pillarOutOfShaft(SceneContext ctx) {
        stageSlab(ctx);
        for (int dy = 0; dy > -SHAFT_DEPTH; dy--) ctx.setBlock(0, GROUND + dy, 0, Blocks.AIR);
        BlockPos start = ctx.rel(0, GROUND + 1 - SHAFT_DEPTH, 0);
        BlockPos goal = ctx.rel(3, GROUND + 1, 0);
        ctx.record("test setup", "1x1 shaft, " + SHAFT_DEPTH + " deep; start " + start.toShortString() + ", goal "
                + goal.toShortString() + ", 30 dirt in hand");
        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        helm.hold(new ItemStack(Items.DIRT, 30));
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        BotConfig.walkerFinalNodeDirectAim = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();
        helm.sync(30, () -> {
            ctx.record("start after sync", helm.where());
            final int[] arrivedTick = { -1 };
            final int[] surfacedTick = { -1 };
            final int[] legTicks = { 0 };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                // t > 5: on the first ticks of the goto, the server's copy of the bot can still show
                // the pre-teleport surface position, which would read as "surfaced at tick 0".
                if (t > 5 && surfacedTick[0] < 0 && body.onGround() && body.getY() >= GROUND + 1 - 0.05) surfacedTick[0] = t;
                if (arrivedTick[0] < 0 && body.onGround() && helm.flatDistance(goal) <= 1.0) arrivedTick[0] = t;
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    int dirtLeft = body.getInventory().countItem(Items.DIRT);
                    ctx.record("progress", String.format(Locale.ROOT, "left the shaft at tick %s, reached the goal at tick %s, dirt remaining %d",
                            surfacedTick[0] < 0 ? "never" : String.valueOf(surfacedTick[0]),
                            arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]), dirtLeft));
                    ctx.record("final position", helm.where());
                    double flat = helm.flatDistance(goal);
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "A: stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(arrivedTick[0] >= 0 && arrivedTick[0] <= 300).as("B: arrives within 300 ticks: arrived at tick "
                            + (arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]))).isTrue();
                    ctx.check(30 - dirtLeft <= SHAFT_DEPTH + 1).as("C: uses no more dirt than the shaft depth plus one: used " + (30 - dirtLeft)).isTrue();
                });
            });
        });
    }

    private static final int SHAFT_DEPTH = 6;

    private static final int GROUND = 20;
    private static final int HALF = 12;
    /** Blocks of air under the player's feet at the start — a fall that hurts but does not kill. */
    private static final int DROP = 5;
    private static final int LEG_TICKS = 600;
    private static final int SETTLE_TICKS = 10;

    /**
     * The walk is ordered while the player is still five blocks up over flat stone. It must land,
     * plan from where it landed and walk the six cells to the goal — inside 200 ticks, which is
     * the fall plus a plain walk with room for one repath, not for a search that keeps starting
     * from a cell the player has already left.
     */
    private static void gotoStartsMidAir(SceneContext ctx) {
        stageSlab(ctx);
        run(ctx, "stone", 200);
    }

    /**
     * The same drop over a pool: the landing is a splash, the first dry cell is the far rim, and
     * the walk ends only when the player stands on it. A bot that treats the splash as the end of
     * the fall and then hunts for the exit is what the budget is against.
     */
    private static void gotoStartsMidAirOverWater(SceneContext ctx) {
        stageSlab(ctx);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++)
                for (int dy = 0; dy > -4; dy--) ctx.setBlock(dx, GROUND + dy, dz, Blocks.WATER);
        run(ctx, "pool", 320);
    }

    private static void run(SceneContext ctx, String landing, int arriveBy) {
        BlockPos start = ctx.rel(0, GROUND + 1 + DROP, 0);
        BlockPos goal = ctx.rel(6, GROUND + 1, 0);
        ctx.record("test setup", "ground foot level y=" + (GROUND + 1) + ", landing=" + landing + ", start "
                + start.toShortString() + " (" + DROP + " blocks in the air), goal " + goal.toShortString());

        ClientHelm helm = ClientHelm.adopt(ctx, start, -90f);
        BotConfig.allowBreak = true;
        BotConfig.allowPlace = true;
        BotConfig.walkerDebug = true;
        // Shipped default, restored over the pinned baseline: the stone landing is the scene that
        // measured the diagonal overshoot of the last node, and its budget is what notices it.
        BotConfig.walkerFinalNodeDirectAim = true;
        BotConfig.walkerHoldLastNodeUntilStanding = true;
        BotConfig.walkerOrbitBreaksAimLag = true;
        BotConfig.walkerSurfaceSprintSwim = true;
        ServerPlayer body = helm.player();

        // Two ticks, not the usual thirty: the player is falling, and the point is to order the walk
        // while it still is. The teleport reaches the client within one.
        helm.sync(2, () -> {
            ctx.record("start after sync", helm.where());
            if (body.onGround()) ctx.fail("test setup invalid: the bot was already on the ground before the goto started: " + helm.where());
            final int[] firstGroundTick = { -1 };
            final int[] arrivedTick = { -1 };
            final int[] legTicks = { 0 };
            final double[] minY = { body.getY() };
            ClientHelm.TickWatcher watch = t -> {
                legTicks[0] = t;
                if (firstGroundTick[0] < 0 && body.onGround()) firstGroundTick[0] = t;
                if (arrivedTick[0] < 0 && body.onGround() && helm.flatDistance(goal) <= 1.0) arrivedTick[0] = t;
                minY[0] = Math.min(minY[0], body.getY());
            };
            helm.goTo("leg", new Goal.Block(goal), goal, LEG_TICKS, watch, () -> {
                ctx.record("end of goto", helm.where());
                helm.sync(SETTLE_TICKS, legTicks[0] + 1, watch, () -> {
                    ctx.record("progress", String.format(Locale.ROOT,
                            "first touched the ground at tick %s, reached the goal at tick %s, lowest y=%.2f, health %.1f",
                            firstGroundTick[0] < 0 ? "never" : String.valueOf(firstGroundTick[0]),
                            arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0]),
                            minY[0], body.getHealth()));
                    ctx.record("final position", helm.where());
                    double flat = helm.flatDistance(goal);
                    ctx.check(body.onGround() && !body.isInWater()).as("A: the bot ends standing on dry ground: " + helm.where()).isTrue();
                    ctx.check(flat <= 1.5).as(String.format(Locale.ROOT, "B: stops within 1.5 blocks of the goal cell: horizontal distance %.2f", flat)).isTrue();
                    ctx.check(arrivedTick[0] >= 0 && arrivedTick[0] <= arriveBy).as("C: arrives within " + arriveBy
                            + " ticks: arrived at tick " + (arrivedTick[0] < 0 ? "never" : String.valueOf(arrivedTick[0])))
                            .isTrue();
                    ctx.check(body.getHealth() > 0).as("D: the bot is alive").isTrue();
                });
            });
        });
    }

    private static void stageSlab(SceneContext ctx) {
        for (int dx = -HALF; dx <= HALF; dx++)
            for (int dz = -HALF; dz <= HALF; dz++) {
                for (int dy = -6; dy <= 0; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.STONE);
                for (int dy = 1; dy <= DROP + 4; dy++) ctx.setBlock(dx, GROUND + dy, dz, Blocks.AIR);
            }
    }
}
