package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.stagewright.kit.ByName;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

/**
 * Vanilla flyers driven by the server bot stack:
 *
 * <ul>
 *   <li>each small flyer crosses a sealed stone hall over a wall that leaves a gap only under the ceiling, so the way
 *       climbs, crosses and comes back down;
 *   <li>a ghast crosses a room whose dividing wall has a small hole on the straight line and one wide enough for it off
 *       to the side, and an elder guardian does the same in a sealed tank: both plan with their whole body;
 *   <li>a parrot sent to a point in open air holds that point once it gets there;
 *   <li>a wither and an ender dragon are refused.
 * </ul>
 *
 * <p>Every flyer goes through one {@code MobBody.driveFlight}; the types differ only in {@code Locomotion.modesOf} and
 * their flight speed, so one per branch is kept: parrot (flying navigation), bat (fly-only by type), phantom (its own
 * minimum-speed travel), blaze (walk and fly). No player entity is created; the mobs are invulnerable.
 */
@Scene("wd.scenes")
class FlyingMobTest {

    /** The ref of a mob an order takes over by its uuid. */
    private static final String ENTITY = "entity:";

    /**
     * The hall's inside: x from 0 to {@code HALL}, z from -2 to 2, y from 0 to 7; the wall at {@code HALL_WALL}, 5
     * high.
     */
    private static final int HALL = 10;

    private static final int HALL_WALL = 5;
    private static final int HALL_TOP = 7;
    private static final int WALL_TOP = 4;
    /** Ticks for a fresh mob to be ticked at all. */
    private static final int SETTLE_TICKS = 100;
    /** Ticks a flight gets before the test reads where it got to. */
    private static final int DEADLINE = 700;
    /** Ticks a parrot that arrived must hold its spot for. */
    private static final int HOVER_TICKS = 60;
    /** Samples of the navigator's trace, one every five ticks, kept for a flight that does not succeed. */
    private static final int TRAIL = 24;

    @Test
    void mobParrotFliesOverAWall(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        fliesOverAWall(game, plot, reporter, EntityType.PARROT);
    }

    @Test
    void mobBatFliesOverAWall(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        fliesOverAWall(game, plot, reporter, EntityType.BAT);
    }

    @Test
    void mobPhantomFliesOverAWall(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        fliesOverAWall(game, plot, reporter, EntityType.PHANTOM);
    }

    @Test
    void mobBlazeFliesOverAWall(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        fliesOverAWall(game, plot, reporter, EntityType.BLAZE);
    }

    /** A flyer crosses the hall over its wall, up through the gap under the ceiling, and the order succeeds. */
    private static void fliesOverAWall(Game game, Plot plot, TestReporter reporter, EntityType<? extends Mob> type) {
        pin(game);
        plot.fill(-1, -1, -3, HALL + 1, HALL_TOP + 1, 3, Blocks.STONE);
        plot.fill(0, 0, -2, HALL, HALL_TOP, 2, Blocks.AIR);
        plot.fill(HALL_WALL, 0, -2, HALL_WALL, WALL_TOP, 2, Blocks.STONE);
        BlockPos o = plot.at(0, 0, 0);
        Mob mob = spawn(game, plot, type, new Vec3(o.getX() + 1.5, o.getY() + 0.1, o.getZ() + 0.5));
        fly(game, reporter, mob, plot.at(HALL - 1, 0, 0), o.getY() + WALL_TOP + 1, "climbs over the wall");
    }

    /**
     * A ghast crosses a room whose dividing wall has a three-wide hole on the straight line from start to goal and a
     * five-wide one off to the side: it plans for its own four-wide box, so it takes the wide one.
     */
    @Test
    void mobGhastTakesTheHoleItFits(
            Game game, @Plot.Centered @Plot.Size(x = 40, y = 24, z = 20) Plot plot, TestReporter reporter) {
        pin(game);
        plot.fill(-1, -1, -9, 17, 10, 9, Blocks.STONE);
        plot.fill(0, 0, -8, 16, 9, 8, Blocks.AIR);
        plot.fill(8, 0, -8, 8, 9, 8, Blocks.STONE);
        plot.fill(8, 1, -1, 8, 3, 1, Blocks.AIR); // three wide, on the straight line
        plot.fill(8, 2, 3, 8, 6, 7, Blocks.AIR); // five wide, off to the side
        BlockPos o = plot.at(0, 0, 0);
        Mob ghast = spawn(game, plot, EntityType.GHAST, new Vec3(o.getX() + 3.5, o.getY() + 2.1, o.getZ() + 0.5));
        fly(game, reporter, ghast, plot.at(13, 2, 0), Integer.MIN_VALUE, "takes the five-wide hole");
    }

    /** The same in water: an elder guardian takes a three-wide hole off the line over a two-wide one on it. */
    @Test
    void mobElderGuardianTakesTheHoleItFits(
            Game game, @Plot.Centered @Plot.Size(x = 32, y = 24, z = 16) Plot plot, TestReporter reporter) {
        pin(game);
        plot.fill(-1, -1, -6, 13, 7, 6, Blocks.STONE);
        plot.fill(0, 0, -5, 12, 6, 5, Blocks.WATER);
        plot.fill(6, 0, -5, 6, 6, 5, Blocks.STONE);
        plot.fill(6, 2, -1, 6, 3, 0, Blocks.WATER); // two wide, on the straight line
        plot.fill(6, 1, 2, 6, 3, 4, Blocks.WATER); // three wide, off to the side
        BlockPos o = plot.at(0, 0, 0);
        Mob elder =
                spawn(game, plot, EntityType.ELDER_GUARDIAN, new Vec3(o.getX() + 2.5, o.getY() + 2.1, o.getZ() + 0.5));
        // Its own step gives every player within 50 blocks Mining Fatigue now and then; a client topology's
        // player digs in later tests.
        game.afterTest(s -> s.getPlayerList().getPlayers().forEach(p -> p.removeEffect(MobEffects.DIG_SLOWDOWN)));
        fly(game, reporter, elder, plot.at(10, 2, 0), Integer.MIN_VALUE, "takes the three-wide hole");
    }

    /**
     * Send {@code mob} to {@code goal} and check it arrives and the order succeeds; with {@code mustReachY} above the
     * minimum, also that its feet got that high on the way.
     */
    private static void fly(Game game, TestReporter reporter, Mob mob, BlockPos goal, int mustReachY, String what) {
        ApiRegistry api = game.registry();
        String name = game.onServer(s -> mob.getType().toShortString());
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);
        game.await().within(SETTLE_TICKS).as("the " + name + " is ticked").until(() -> mob.tickCount > 5);
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        if (ByName.actionId(go) == null) {
            fail("a goto naming the " + name + " did not start: " + go);
        }
        int[] ticks = {0};
        int[] highest = {game.onServer(s -> mob.blockPosition().getY())};
        int[] widest = {game.onServer(s -> mob.blockPosition().getZ())};
        Deque<String> trail = new ArrayDeque<>();
        game.await().within(DEADLINE + 50).as("the " + name + "'s flight ends").until(() -> {
            highest[0] = Math.max(highest[0], mob.blockPosition().getY());
            if (Math.abs(mob.blockPosition().getZ() - goal.getZ()) > Math.abs(widest[0] - goal.getZ())) {
                widest[0] = mob.blockPosition().getZ();
            }
            if (ticks[0] % 5 == 0) {
                trail.addLast("t" + ticks[0] + ": " + ByName.lastTickTrace(api, ref));
                if (trail.size() > TRAIL) {
                    trail.removeFirst();
                }
            }
            return !ByName.busy(api, ref) || ++ticks[0] >= DEADLINE;
        });
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        boolean ok =
                stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && highest[0] >= mustReachY;
        reporter.publishEntry(
                "arrived",
                stoppedAt.toShortString() + " ticks=" + ticks[0] + " highestY=" + (highest[0] - goal.getY())
                        + " widestZ=" + (widest[0] - goal.getZ()) + " " + action);
        if (!ok) {
            reporter.publishEntry("trail", String.join(" | ", trail));
        }
        game.check(
                ok,
                "the " + name + " " + what + " to the goal: stopped at " + stoppedAt.toShortString() + ", highest y "
                        + highest[0] + ", " + action);
    }

    /** A parrot sent to a point six up in open air gets there, and holds that point, not falling, once it has. */
    @Test
    void mobParrotHoversWhereItArrived(
            Game game, @Plot.Centered @Plot.Size(x = 16, y = 24, z = 16) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        pin(game);
        plot.fill(-1, -1, -1, 1, -1, 1, Blocks.STONE);
        BlockPos o = plot.at(0, 0, 0);
        BlockPos goal = plot.at(4, 6, 0);
        Mob parrot = spawn(game, plot, EntityType.PARROT, new Vec3(o.getX() + 0.5, o.getY() + 0.1, o.getZ() + 0.5));
        String ref = ENTITY + parrot.getUUID();
        ByName.releaseAtEnd(game, ref);
        game.await().within(SETTLE_TICKS).as("the parrot is ticked").until(() -> parrot.tickCount > 5);
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        if (ByName.actionId(go) == null) {
            fail("a goto naming the parrot did not start: " + go);
        }
        int[] ticks = {0};
        game.await()
                .within(DEADLINE + 50)
                .as("the parrot's flight ends")
                .until(() -> !ByName.busy(api, ref) || ++ticks[0] >= DEADLINE);
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        Vec3 arrived = game.onServer(s -> parrot.position());
        reporter.publishEntry("arrived", arrived + " ticks=" + ticks[0] + " " + action);
        if (!"succeeded".equals(action.get("state"))) {
            fail("the parrot did not get to " + goal.toShortString() + ": " + action + " trace="
                    + ByName.lastTickTrace(api, ref));
        }
        int[] held = {0};
        double[] farthest = {0};
        game.await()
                .within(HOVER_TICKS + 20)
                .as("the parrot holds for " + HOVER_TICKS + " ticks")
                .until(() -> {
                    farthest[0] = Math.max(farthest[0], parrot.position().distanceTo(arrived));
                    return ++held[0] >= HOVER_TICKS;
                });
        Vec3 now = game.onServer(s -> parrot.position());
        boolean grounded = game.onServer(s -> parrot.onGround());
        reporter.publishEntry("held", now + " farthest=" + String.format(java.util.Locale.ROOT, "%.2f", farthest[0]));
        game.check(
                farthest[0] < 1.0 && now.distanceTo(arrived) < 0.5 && !grounded,
                "the parrot holds the point it arrived at for " + HOVER_TICKS + " ticks: arrived at " + arrived
                        + ", now " + now + ", drifted at most " + farthest[0]);
    }

    /** An order naming a wither or an ender dragon is refused and says why. */
    @Test
    void mobWitherAndDragonAreRefused(
            Game game, @Plot.Centered @Plot.Size(x = 64, y = 32, z = 32) Plot plot, TestReporter reporter) {
        ApiRegistry api = game.registry();
        BlockPos o = plot.at(0, 0, 0);
        Mob wither = spawn(game, plot, EntityType.WITHER, new Vec3(o.getX() + 0.5, o.getY(), o.getZ() + 0.5));
        Mob dragon = spawn(game, plot, EntityType.ENDER_DRAGON, new Vec3(o.getX() + 20.5, o.getY(), o.getZ() + 0.5));
        game.runOnServer(s -> {
            wither.setNoGravity(true);
            dragon.setNoGravity(true);
        });
        game.await()
                .within(SETTLE_TICKS)
                .as("the bosses are ticked")
                .until(() -> wither.tickCount > 1 && dragon.tickCount > 1);
        Map<String, Object> witherGo = ByName.call(
                api, "mc.bot.goto", Map.of("bot", ENTITY + wither.getUUID(), "target", ByName.at(plot.at(5, 0, 0))));
        Map<String, Object> dragonGo = ByName.call(
                api, "mc.bot.goto", Map.of("bot", ENTITY + dragon.getUUID(), "target", ByName.at(plot.at(25, 0, 0))));
        reporter.publishEntry("refusals", witherGo + " / " + dragonGo);
        boolean witherKeptGoals =
                game.onServer(s -> !wither.goalSelector.getAvailableGoals().isEmpty());
        game.check(
                ByName.refusedAs(witherGo, "bot_unavailable")
                        && String.valueOf(witherGo).contains("wither")
                        && ByName.refusedAs(dragonGo, "bot_unavailable")
                        && String.valueOf(dragonGo).contains("ender dragon")
                        && witherKeptGoals,
                "the wither and the ender dragon are refused and say why: " + witherGo + " / " + dragonGo);
    }

    private static void pin(Game game) {
        Settings.pin(game).set("allowBreak", false).set("allowPlace", false);
    }

    /** An invulnerable mob of {@code type} at {@code at}, facing +x; discarded when the test ends. */
    private static Mob spawn(Game game, Plot plot, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Mob m = type.create(level);
            if (m == null) {
                throw new IllegalStateException(type + " created no entity");
            }
            m.moveTo(at.x, at.y, at.z, -90f, 0f);
            m.setInvulnerable(true);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            return m;
        });
        game.afterTest(s -> mob.discard());
        return mob;
    }
}
