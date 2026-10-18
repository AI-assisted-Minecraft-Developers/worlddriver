package net.magicterra.worlddriver.bot.stagewright.scene;

import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.actionId;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.at;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.call;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.fill;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.read;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.settled;

import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.bot.stagewright.Harness;
import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.api.game.WorldDriver;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla water mobs driven by the server bot stack:
 *
 * <ul>
 *   <li>the ones that only swim — cod, dolphin, guardian, squid, tadpole — cross a sealed tank whose
 *       middle is walled off except for a gap in one bottom corner, so the way turns and dives;</li>
 *   <li>a cod given a goal on land fails with no path and stays in the water;</li>
 *   <li>the ones that walk and swim — axolotl, turtle, frog, drowned — walk a stone course with a pool
 *       across its middle, swimming the pool.</li>
 * </ul>
 *
 * <p>Legal on every topology: no player entity is created. The mobs are invulnerable.
 */
public final class WorldDriverWaterMobScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    private static final int COURSE = 10;
    /** Where the tank's dividing wall stands, and the first and last pool column. */
    private static final int WALL_X = 5, POOL_FROM = 4, POOL_TO = 7;
    /** Ticks for a fresh mob to be ticked at all: an arena chunk is not entity-ticking at once. */
    private static final int SETTLE_TICKS = 100;
    /** Ticks a swim gets before the scene reads where it got to. */
    private static final int DEADLINE = 600;
    /** Samples of the navigator's trace, one every five ticks, kept for a swim that does not succeed. */
    private static final int TRAIL = 24;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.mobCodSwimsUnderAWall", 900, ctx -> swimsUnderAWall(ctx, EntityType.COD)),
                Scene.of("wd.mobDolphinSwimsUnderAWall", 900, ctx -> swimsUnderAWall(ctx, EntityType.DOLPHIN)),
                Scene.of("wd.mobGuardianSwimsUnderAWall", 900, ctx -> swimsUnderAWall(ctx, EntityType.GUARDIAN)),
                Scene.of("wd.mobSquidSwimsUnderAWall", 900, ctx -> swimsUnderAWall(ctx, EntityType.SQUID)),
                Scene.of("wd.mobTadpoleSwimsUnderAWall", 900, ctx -> swimsUnderAWall(ctx, EntityType.TADPOLE)),
                Scene.of("wd.mobFishWithALandGoalFailsInWater", 900, WorldDriverWaterMobScenes::landGoalFailsInWater),
                Scene.of("wd.mobAxolotlWalksAndSwims", 1300, ctx -> walksAndSwims(ctx, EntityType.AXOLOTL)),
                Scene.of("wd.mobTurtleWalksAndSwims", 1300, ctx -> walksAndSwims(ctx, EntityType.TURTLE)),
                Scene.of("wd.mobFrogWalksAndSwims", 1300, ctx -> walksAndSwims(ctx, EntityType.FROG)),
                Scene.of("wd.mobDrownedWalksAndSwims", 1300, ctx -> walksAndSwims(ctx, EntityType.DROWNED)));
    }

    /** A mob that only swims crosses the tank, under the wall through its one gap, and the order succeeds. */
    private static void swimsUnderAWall(SceneContext ctx, EntityType<? extends Mob> type) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        tank(ctx, o);
        // The wall fills the tank's cross-section except two cells at the bottom of the +z side.
        fill(ctx.level(), o.offset(WALL_X, -1, -2), o.offset(WALL_X, 1, 2), STONE);
        fill(ctx.level(), o.offset(WALL_X, -3, -2), o.offset(WALL_X, -2, 1), STONE);
        Mob mob = spawn(ctx, type, o);
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> mob.tickCount > 5 && mob.isInWater()).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the " + type.toShortString() + " did not start: " + go); return; }
            Bot bot = BotRegistry.get(ref);
            int start = ctx.ticks();
            int[] lowest = {mob.blockPosition().getY()};
            java.util.Deque<String> trail = new java.util.ArrayDeque<>();
            ctx.await(() -> {
                lowest[0] = Math.min(lowest[0], mob.blockPosition().getY());
                if ((ctx.ticks() - start) % 5 == 0) {
                    trail.addLast("t" + (ctx.ticks() - start) + ": " + NavigatorTelemetry.of(mob).lastTickTrace);
                    if (trail.size() > TRAIL) trail.removeFirst();
                }
                return !bot.busy() || ctx.ticks() - start >= DEADLINE;
            }).within(DEADLINE + 50).then(() -> {
                BlockPos stoppedAt = mob.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("arrived", stoppedAt.toShortString() + " ticks=" + (ctx.ticks() - start)
                        + " lowestY=" + (lowest[0] - o.getY()) + " " + action);
                if (!"succeeded".equals(action.get("state"))) ctx.record("trail", String.join(" | ", trail));
                ctx.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && lowest[0] <= o.getY() - 2)
                        .as("the " + type.toShortString() + " swims down through the gap under the wall to the goal: stopped at "
                                + stoppedAt.toShortString() + ", lowest y " + (lowest[0] - o.getY()) + " below the start, " + action)
                        .isTrue();
            });
        });
    }

    /** A cod sent to a cell on the tank's rim, out of the water: the order fails and the cod is still in the water. */
    private static void landGoalFailsInWater(SceneContext ctx) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos rim = o.offset(COURSE, 3, 3);
        tank(ctx, o);
        Mob cod = spawn(ctx, EntityType.COD, o);
        String ref = BotRegistry.ENTITY_PREFIX + cod.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> cod.tickCount > 5 && cod.isInWater()).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(rim)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the cod did not start: " + go); return; }
            int start = ctx.ticks();
            ctx.await(() -> !"running".equals(read(api, actionId(go)).get("state")) || ctx.ticks() - start >= DEADLINE)
                    .within(DEADLINE + 50).then(() -> {
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("ended", cod.blockPosition().toShortString() + " ticks=" + (ctx.ticks() - start) + " inWater="
                        + cod.isInWater() + " " + action + " trace=" + NavigatorTelemetry.of(cod).lastTickTrace);
                ctx.check("failed".equals(action.get("state")) && cod.isInWater())
                        .as("the cod's goto to the rim fails, and the cod is still in the water: at "
                                + cod.blockPosition().toShortString() + ", inWater=" + cod.isInWater() + ", " + action)
                        .isTrue();
            });
        });
    }

    /** A mob that walks and swims crosses the course, through the pool in its middle, and the order succeeds. */
    private static void walksAndSwims(SceneContext ctx, EntityType<? extends Mob> type) {
        ApiRegistry api = WorldDriver.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        BlockPos goal = o.offset(COURSE, 0, 0);
        ServerLevel level = ctx.level();
        pin(ctx);
        // Stone two deep under the course, walls two high on both sides, and a pool two deep across it.
        BlockPos lo = o.offset(-2, -3, -4), hi = o.offset(COURSE + 2, 3, 4);
        fill(level, lo, hi, AIR);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, lo, o.offset(COURSE + 2, -1, 4), STONE);
        fill(level, o.offset(-2, 0, -4), o.offset(COURSE + 2, 1, -4), STONE);
        fill(level, o.offset(-2, 0, 4), o.offset(COURSE + 2, 1, 4), STONE);
        fill(level, o.offset(POOL_FROM, -2, -3), o.offset(POOL_TO, -1, 3), WATER);
        Mob mob = spawn(ctx, type, o);
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));

        ctx.await(() -> settled(mob)).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the " + type.toShortString() + " did not start: " + go); return; }
            Bot bot = BotRegistry.get(ref);
            int start = ctx.ticks();
            boolean[] swam = {false};
            ctx.await(() -> {
                swam[0] |= mob.isInWater();
                return !bot.busy();
            }).within(1100).then(() -> {
                BlockPos stoppedAt = mob.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                ctx.record("arrived", stoppedAt.toShortString() + " ticks=" + (ctx.ticks() - start) + " swam=" + swam[0] + " " + action);
                ctx.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && swam[0])
                        .as("the " + type.toShortString() + " walks the course and swims the pool to the goal: stopped at "
                                + stoppedAt.toShortString() + ", swam=" + swam[0] + ", " + action)
                        .isTrue();
            });
        });
    }

    /**
     * A sealed stone tank along +x: water five wide and five deep from {@code o.y-3} to {@code o.y+1}, one
     * cell of air above it, and stone all round, rim included.
     */
    private static void tank(SceneContext ctx, BlockPos o) {
        ServerLevel level = ctx.level();
        pin(ctx);
        BlockPos lo = o.offset(-2, -4, -3), hi = o.offset(COURSE + 2, 2, 3);
        fill(level, lo, hi, STONE);
        ctx.cleanup(() -> fill(level, lo, hi.above(), AIR));
        fill(level, o.offset(-1, 2, -2), o.offset(COURSE + 1, 2, 2), AIR);
        fill(level, o.offset(-1, -3, -2), o.offset(COURSE + 1, 1, 2), WATER);
    }

    private static void pin(SceneContext ctx) {
        Harness.pin(ctx);
        Harness.set("allowBreak", false);
        Harness.set("allowPlace", false);
        // The shipped arrival rule, which the pinned baseline turns off.
        Harness.set("navigatorHoldLastNodeUntilStanding", true);
    }

    /** An invulnerable mob of {@code type} at {@code at}, facing +x. */
    private static Mob spawn(SceneContext ctx, EntityType<? extends Mob> type, BlockPos at) {
        Mob mob = type.create(ctx.level());
        if (mob == null) throw new IllegalStateException(type + " created no entity");
        mob.moveTo(at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, -90f, 0f);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        ctx.level().addFreshEntity(mob);
        ctx.cleanup(mob::discard);
        return mob;
    }
}
