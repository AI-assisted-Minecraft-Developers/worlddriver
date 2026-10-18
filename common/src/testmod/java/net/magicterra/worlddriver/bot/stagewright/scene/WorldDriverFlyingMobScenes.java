package net.magicterra.worlddriver.bot.stagewright.scene;

import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.actionId;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.at;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.call;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.fill;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.read;
import static net.magicterra.worlddriver.bot.stagewright.scene.WorldDriverMobScenes.refusedAs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import net.magicterra.stagewright.scene.Scene;
import net.magicterra.stagewright.scene.SceneContext;
import net.magicterra.stagewright.scene.SceneProvider;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.Bot;
import net.magicterra.worlddriver.bot.BotRegistry;
import net.magicterra.worlddriver.bot.control.NavigatorTelemetry;
import net.magicterra.worlddriver.config.BotConfig;
import net.magicterra.worlddriver.config.WorldConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Vanilla flyers driven by the server bot stack:
 *
 * <ul>
 *   <li>each small flyer crosses a sealed stone hall over a wall that leaves a gap only under the
 *       ceiling, so the way climbs, crosses and comes back down;</li>
 *   <li>a ghast crosses a room whose dividing wall has a small hole on the straight line and one wide
 *       enough for it off to the side, and an elder guardian does the same in a sealed tank: both plan
 *       with their whole body;</li>
 *   <li>a parrot sent to a point in open air holds that point once it gets there;</li>
 *   <li>a wither and an ender dragon are refused.</li>
 * </ul>
 *
 * <p>Legal on every topology: no player entity is created. The mobs are invulnerable.
 */
public final class WorldDriverFlyingMobScenes implements SceneProvider {

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WATER = Blocks.WATER.defaultBlockState();
    /** The hall's inside: x from 0 to {@code HALL}, z from -2 to 2, y from 0 to 7; the wall at {@code HALL_WALL}, 5 high. */
    private static final int HALL = 10, HALL_WALL = 5, HALL_TOP = 7, WALL_TOP = 4;
    /** Ticks for a fresh mob to be ticked at all: an arena chunk is not entity-ticking at once. */
    private static final int SETTLE_TICKS = 100;
    /** Ticks a flight gets before the scene reads where it got to. */
    private static final int DEADLINE = 700;
    /** Ticks a parrot that arrived must hold its spot for. */
    private static final int HOVER_TICKS = 60;
    /** Samples of the navigator's trace, one every five ticks, kept for a flight that does not succeed. */
    private static final int TRAIL = 24;

    @Override
    public List<Scene> scenes() {
        return List.of(
                Scene.of("wd.mobParrotFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.PARROT)),
                Scene.of("wd.mobBeeFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.BEE)),
                Scene.of("wd.mobAllayFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.ALLAY)),
                Scene.of("wd.mobBatFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.BAT)),
                Scene.of("wd.mobVexFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.VEX)),
                Scene.of("wd.mobPhantomFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.PHANTOM)),
                Scene.of("wd.mobBlazeFliesOverAWall", 1000, ctx -> fliesOverAWall(ctx, EntityType.BLAZE)),
                Scene.of("wd.mobGhastTakesTheHoleItFits", 1000, WorldDriverFlyingMobScenes::ghastTakesTheHoleItFits),
                Scene.of("wd.mobElderGuardianTakesTheHoleItFits", 1000, WorldDriverFlyingMobScenes::elderGuardianTakesTheHoleItFits),
                Scene.of("wd.mobParrotHoversWhereItArrived", 1000, WorldDriverFlyingMobScenes::parrotHoversWhereItArrived),
                Scene.of("wd.mobWitherAndDragonAreRefused", 400, WorldDriverFlyingMobScenes::bossesAreRefused));
    }

    /** A flyer crosses the hall over its wall, up through the gap under the ceiling, and the order succeeds. */
    private static void fliesOverAWall(SceneContext ctx, EntityType<? extends Mob> type) {
        BlockPos o = ctx.origin().above(20);
        ServerLevel level = ctx.level();
        pin(ctx);
        BlockPos lo = o.offset(-1, -1, -3), hi = o.offset(HALL + 1, HALL_TOP + 1, 3);
        fill(level, lo, hi, STONE);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, o.offset(0, 0, -2), o.offset(HALL, HALL_TOP, 2), AIR);
        fill(level, o.offset(HALL_WALL, 0, -2), o.offset(HALL_WALL, WALL_TOP, 2), STONE);
        Mob mob = spawn(ctx, type, new Vec3(o.getX() + 1.5, o.getY() + 0.1, o.getZ() + 0.5));
        fly(ctx, mob, o.offset(HALL - 1, 0, 0), o.getY() + WALL_TOP + 1, "climbs over the wall");
    }

    /**
     * A ghast crosses a room whose dividing wall has a three-wide hole on the straight line from start to
     * goal and a five-wide one off to the side: it plans for its own four-wide box, so it takes the wide one.
     */
    private static void ghastTakesTheHoleItFits(SceneContext ctx) {
        BlockPos o = ctx.origin().above(20);
        ServerLevel level = ctx.level();
        pin(ctx);
        BlockPos lo = o.offset(-1, -1, -9), hi = o.offset(17, 10, 9);
        fill(level, lo, hi, STONE);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, o.offset(0, 0, -8), o.offset(16, 9, 8), AIR);
        fill(level, o.offset(8, 0, -8), o.offset(8, 9, 8), STONE);
        fill(level, o.offset(8, 1, -1), o.offset(8, 3, 1), AIR);     // three wide, on the straight line
        fill(level, o.offset(8, 2, 3), o.offset(8, 6, 7), AIR);      // five wide, off to the side
        Mob ghast = spawn(ctx, EntityType.GHAST, new Vec3(o.getX() + 3.5, o.getY() + 2.1, o.getZ() + 0.5));
        fly(ctx, ghast, o.offset(13, 2, 0), Integer.MIN_VALUE, "takes the five-wide hole");
    }

    /** The same in water: an elder guardian takes a three-wide hole off the line over a two-wide one on it. */
    private static void elderGuardianTakesTheHoleItFits(SceneContext ctx) {
        BlockPos o = ctx.origin().above(20);
        ServerLevel level = ctx.level();
        pin(ctx);
        BlockPos lo = o.offset(-1, -1, -6), hi = o.offset(13, 7, 6);
        fill(level, lo, hi, STONE);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        fill(level, o.offset(0, 0, -5), o.offset(12, 6, 5), WATER);
        fill(level, o.offset(6, 0, -5), o.offset(6, 6, 5), STONE);
        fill(level, o.offset(6, 2, -1), o.offset(6, 3, 0), WATER);   // two wide, on the straight line
        fill(level, o.offset(6, 1, 2), o.offset(6, 3, 4), WATER);    // three wide, off to the side
        Mob elder = spawn(ctx, EntityType.ELDER_GUARDIAN, new Vec3(o.getX() + 2.5, o.getY() + 2.1, o.getZ() + 0.5));
        // Its own step gives every player within 50 blocks Mining Fatigue now and then; a client topology's
        // player digs in later scenes.
        ctx.cleanup(() -> level.players().forEach(p -> p.removeEffect(net.minecraft.world.effect.MobEffects.DIG_SLOWDOWN)));
        fly(ctx, elder, o.offset(10, 2, 0), Integer.MIN_VALUE, "takes the three-wide hole");
    }

    /**
     * Send {@code mob} to {@code goal} and check it arrives and the order succeeds; with {@code mustReachY}
     * above the minimum, also that its feet got that high on the way.
     */
    private static void fly(SceneContext ctx, Mob mob, BlockPos goal, int mustReachY, String what) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        String name = mob.getType().toShortString();
        String ref = BotRegistry.ENTITY_PREFIX + mob.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));
        ctx.await(() -> mob.tickCount > 5).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the " + name + " did not start: " + go); return; }
            Bot bot = BotRegistry.get(ref);
            int start = ctx.ticks();
            int[] highest = {mob.blockPosition().getY()};
            int[] widest = {mob.blockPosition().getZ()};
            Deque<String> trail = new ArrayDeque<>();
            ctx.await(() -> {
                highest[0] = Math.max(highest[0], mob.blockPosition().getY());
                if (Math.abs(mob.blockPosition().getZ() - goal.getZ()) > Math.abs(widest[0] - goal.getZ())) widest[0] = mob.blockPosition().getZ();
                if ((ctx.ticks() - start) % 5 == 0) {
                    trail.addLast("t" + (ctx.ticks() - start) + ": " + NavigatorTelemetry.of(mob).lastTickTrace);
                    if (trail.size() > TRAIL) trail.removeFirst();
                }
                return !bot.busy() || ctx.ticks() - start >= DEADLINE;
            }).within(DEADLINE + 50).then(() -> {
                BlockPos stoppedAt = mob.blockPosition();
                Map<?, ?> action = read(api, actionId(go));
                boolean ok = stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && highest[0] >= mustReachY;
                ctx.record("arrived", stoppedAt.toShortString() + " ticks=" + (ctx.ticks() - start) + " highestY="
                        + (highest[0] - goal.getY()) + " widestZ=" + (widest[0] - goal.getZ()) + " " + action);
                if (!ok) ctx.record("trail", String.join(" | ", trail));
                ctx.check(ok).as("the " + name + " " + what + " to the goal: stopped at " + stoppedAt.toShortString()
                                + ", highest y " + highest[0] + ", " + action)
                        .isTrue();
            });
        });
    }

    /** A parrot sent to a point six up in open air gets there, and holds that point, not falling, once it has. */
    private static void parrotHoversWhereItArrived(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(20);
        ServerLevel level = ctx.level();
        pin(ctx);
        BlockPos lo = o.offset(-1, -1, -1), hi = o.offset(1, -1, 1);
        fill(level, lo, hi, STONE);
        ctx.cleanup(() -> fill(level, lo, hi, AIR));
        BlockPos goal = o.offset(4, 6, 0);
        Mob parrot = spawn(ctx, EntityType.PARROT, new Vec3(o.getX() + 0.5, o.getY() + 0.1, o.getZ() + 0.5));
        String ref = BotRegistry.ENTITY_PREFIX + parrot.getUUID();
        ctx.cleanup(() -> BotRegistry.unregister(ref));
        ctx.await(() -> parrot.tickCount > 5).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> go = call(api, "mc.bot.goto", Map.of("bot", ref, "target", at(goal)));
            ctx.record("goto", String.valueOf(go));
            if (actionId(go) == null) { ctx.fail("a goto naming the parrot did not start: " + go); return; }
            Bot bot = BotRegistry.get(ref);
            int start = ctx.ticks();
            ctx.await(() -> !bot.busy() || ctx.ticks() - start >= DEADLINE).within(DEADLINE + 50).then(() -> {
                Map<?, ?> action = read(api, actionId(go));
                Vec3 arrived = parrot.position();
                ctx.record("arrived", arrived + " ticks=" + (ctx.ticks() - start) + " " + action);
                if (!"succeeded".equals(action.get("state"))) {
                    ctx.fail("the parrot did not get to " + goal.toShortString() + ": " + action + " trace=" + NavigatorTelemetry.of(parrot).lastTickTrace);
                    return;
                }
                int held = ctx.ticks();
                double[] farthest = {0};
                ctx.await(() -> {
                    farthest[0] = Math.max(farthest[0], parrot.position().distanceTo(arrived));
                    return ctx.ticks() - held >= HOVER_TICKS;
                }).within(HOVER_TICKS + 20).then(() -> {
                    Vec3 now = parrot.position();
                    ctx.record("held", now + " farthest=" + String.format(java.util.Locale.ROOT, "%.2f", farthest[0]));
                    ctx.check(farthest[0] < 1.0 && now.distanceTo(arrived) < 0.5 && !parrot.onGround())
                            .as("the parrot holds the point it arrived at for " + HOVER_TICKS + " ticks: arrived at " + arrived
                                    + ", now " + now + ", drifted at most " + farthest[0])
                            .isTrue();
                });
            });
        });
    }

    /** An order naming a wither or an ender dragon is refused and says why. */
    private static void bossesAreRefused(SceneContext ctx) {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) { ctx.fail("the API registry is not initialized — was the mod loaded?"); return; }
        BlockPos o = ctx.origin().above(30);
        Mob wither = spawn(ctx, EntityType.WITHER, new Vec3(o.getX() + 0.5, o.getY(), o.getZ() + 0.5));
        Mob dragon = spawn(ctx, EntityType.ENDER_DRAGON, new Vec3(o.getX() + 20.5, o.getY(), o.getZ() + 0.5));
        wither.setNoGravity(true);
        dragon.setNoGravity(true);
        ctx.await(() -> wither.tickCount > 1 && dragon.tickCount > 1).within(SETTLE_TICKS).then(() -> {
            Map<String, Object> witherGo = call(api, "mc.bot.goto", Map.of("bot", BotRegistry.ENTITY_PREFIX + wither.getUUID(),
                    "target", at(o.offset(5, 0, 0))));
            Map<String, Object> dragonGo = call(api, "mc.bot.goto", Map.of("bot", BotRegistry.ENTITY_PREFIX + dragon.getUUID(),
                    "target", at(o.offset(25, 0, 0))));
            ctx.record("refusals", witherGo + " / " + dragonGo);
            ctx.check(refusedAs(witherGo, "bot_unavailable") && String.valueOf(witherGo).contains("wither")
                            && refusedAs(dragonGo, "bot_unavailable") && String.valueOf(dragonGo).contains("ender dragon")
                            && !wither.goalSelector.getAvailableGoals().isEmpty())
                    .as("the wither and the ender dragon are refused and say why: " + witherGo + " / " + dragonGo)
                    .isTrue();
        });
    }

    private static void pin(SceneContext ctx) {
        var pin = BotConfig.pinnedBaseline();
        ctx.cleanup(pin::close);
        WorldConfig.allowBreak = false;
        WorldConfig.allowPlace = false;
    }

    /** An invulnerable mob of {@code type} at {@code at}, facing +x. */
    private static Mob spawn(SceneContext ctx, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = type.create(ctx.level());
        if (mob == null) throw new IllegalStateException(type + " created no entity");
        mob.moveTo(at.x, at.y, at.z, -90f, 0f);
        mob.setInvulnerable(true);
        mob.setPersistenceRequired();
        ctx.level().addFreshEntity(mob);
        ctx.cleanup(mob::discard);
        return mob;
    }
}
