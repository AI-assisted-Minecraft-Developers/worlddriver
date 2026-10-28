package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.bot.stagewright.kit.ByName;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.magicterra.worlddriver.bot.stagewright.kit.Staging;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestReporter;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Vanilla water mobs driven by the server bot stack:
 *
 * <ul>
 *   <li>the ones that only swim cross a sealed tank whose middle is walled off except for a gap in one bottom
 *       corner, so the way turns and dives: cod (factor 1), dolphin (smooth-swimming factors), squid (its own
 *       {@code driveSquid});</li>
 *   <li>the ones that walk and swim walk a stone course with a pool across its middle, swimming the pool: axolotl
 *       (its own factor branch), frog (amphibious navigation and smooth-swimming factors), drowned (the default
 *       walk-and-swim branch).</li>
 * </ul>
 *
 * <p>No player entity is created. The mobs are invulnerable.
 */
@Scene("wd.scenes")
class WaterMobTest {

    /** The ref of a mob an order takes over by its uuid. */
    private static final String ENTITY = "entity:";
    private static final int COURSE = 10;
    /** Where the tank's dividing wall stands, and the first and last pool column. */
    private static final int WALL_X = 5, POOL_FROM = 4, POOL_TO = 7;
    /** Ticks for a fresh mob to be ticked at all. */
    private static final int SETTLE_TICKS = 100;
    /** Ticks a swim gets before the test reads where it got to. */
    private static final int DEADLINE = 600;
    /** Samples of the navigator's trace, one every five ticks, kept for a swim that does not succeed. */
    private static final int TRAIL = 24;

    @Test
    void mobCodSwimsUnderAWall(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                               TestReporter reporter) {
        swimsUnderAWall(game, plot, reporter, EntityType.COD);
    }

    @Test
    void mobDolphinSwimsUnderAWall(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                   TestReporter reporter) {
        swimsUnderAWall(game, plot, reporter, EntityType.DOLPHIN);
    }

    @Test
    void mobSquidSwimsUnderAWall(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                 TestReporter reporter) {
        swimsUnderAWall(game, plot, reporter, EntityType.SQUID);
    }

    @Test
    void mobAxolotlWalksAndSwims(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                 TestReporter reporter) {
        walksAndSwims(game, plot, reporter, EntityType.AXOLOTL);
    }

    @Test
    void mobFrogWalksAndSwims(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                              TestReporter reporter) {
        walksAndSwims(game, plot, reporter, EntityType.FROG);
    }

    @Test
    void mobDrownedWalksAndSwims(Game game, @Plot.Centered @Plot.Size(x = 32, y = 16, z = 16) Plot plot,
                                 TestReporter reporter) {
        walksAndSwims(game, plot, reporter, EntityType.DROWNED);
    }

    /** A mob that only swims crosses the tank, under the wall through its one gap, and the order succeeds. */
    private static void swimsUnderAWall(Game game, Plot plot, TestReporter reporter, EntityType<? extends Mob> type) {
        ApiRegistry api = game.registry();
        BlockPos o = plot.at(0, 0, 0);
        BlockPos goal = plot.at(COURSE, 0, 0);
        tank(game, plot);
        // The wall fills the tank's cross-section except two cells at the bottom of the +z side.
        plot.fill(WALL_X, -1, -2, WALL_X, 1, 2, Blocks.STONE);
        plot.fill(WALL_X, -3, -2, WALL_X, -2, 1, Blocks.STONE);
        Mob mob = spawn(game, plot, type, o);
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        game.await().within(SETTLE_TICKS).as("the " + type.toShortString() + " is ticked in the water")
                .until(() -> mob.tickCount > 5 && mob.isInWater());
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        if (ByName.actionId(go) == null) fail("a goto naming the " + type.toShortString() + " did not start: " + go);
        int[] ticks = {0};
        int[] lowest = {game.onServer(s -> mob.blockPosition().getY())};
        Deque<String> trail = new ArrayDeque<>();
        game.await().within(DEADLINE + 50).as("the " + type.toShortString() + "'s swim ends").until(() -> {
            lowest[0] = Math.min(lowest[0], mob.blockPosition().getY());
            if (ticks[0] % 5 == 0) {
                trail.addLast("t" + ticks[0] + ": " + ByName.lastTickTrace(api, ref));
                if (trail.size() > TRAIL) trail.removeFirst();
            }
            return !ByName.busy(api, ref) || ++ticks[0] >= DEADLINE;
        });
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        reporter.publishEntry("arrived", stoppedAt.toShortString() + " ticks=" + ticks[0]
                + " lowestY=" + (lowest[0] - o.getY()) + " " + action);
        if (!"succeeded".equals(action.get("state"))) reporter.publishEntry("trail", String.join(" | ", trail));
        game.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && lowest[0] <= o.getY() - 2,
                "the " + type.toShortString() + " swims down through the gap under the wall to the goal: stopped at "
                        + stoppedAt.toShortString() + ", lowest y " + (lowest[0] - o.getY()) + " below the start, " + action);
    }

    /** A mob that walks and swims crosses the course, through the pool in its middle, and the order succeeds. */
    private static void walksAndSwims(Game game, Plot plot, TestReporter reporter, EntityType<? extends Mob> type) {
        ApiRegistry api = game.registry();
        BlockPos goal = plot.at(COURSE, 0, 0);
        pin(game);
        // Stone two deep under the course, walls two high on both sides, and a pool two deep across it.
        plot.fill(-2, -3, -4, COURSE + 2, -1, 4, Blocks.STONE);
        plot.fill(-2, 0, -4, COURSE + 2, 1, -4, Blocks.STONE);
        plot.fill(-2, 0, 4, COURSE + 2, 1, 4, Blocks.STONE);
        plot.fill(POOL_FROM, -2, -3, POOL_TO, -1, 3, Blocks.WATER);
        Mob mob = spawn(game, plot, type, plot.at(0, 0, 0));
        String ref = ENTITY + mob.getUUID();
        ByName.releaseAtEnd(game, ref);

        game.await().within(SETTLE_TICKS).as("the " + type.toShortString() + " settles").until(() -> Staging.settled(mob));
        Map<String, Object> go = ByName.call(api, "mc.bot.goto", Map.of("bot", ref, "target", ByName.at(goal)));
        reporter.publishEntry("goto", String.valueOf(go));
        if (ByName.actionId(go) == null) fail("a goto naming the " + type.toShortString() + " did not start: " + go);
        int[] ticks = {0};
        boolean[] swam = {false};
        game.await().within(1100).as("the " + type.toShortString() + "'s walk ends").until(() -> {
            swam[0] |= mob.isInWater();
            ticks[0]++;
            return !ByName.busy(api, ref);
        });
        BlockPos stoppedAt = game.onServer(s -> mob.blockPosition());
        Map<?, ?> action = ByName.read(api, ByName.actionId(go));
        reporter.publishEntry("arrived", stoppedAt.toShortString() + " ticks=" + ticks[0] + " swam=" + swam[0] + " " + action);
        game.check(stoppedAt.closerThan(goal, 1.5) && "succeeded".equals(action.get("state")) && swam[0],
                "the " + type.toShortString() + " walks the course and swims the pool to the goal: stopped at "
                        + stoppedAt.toShortString() + ", swam=" + swam[0] + ", " + action);
    }

    /**
     * A sealed stone tank along +x: water five wide and five deep from {@code y-3} to {@code y+1}, one
     * cell of air above it, and stone all round, rim included.
     */
    private static void tank(Game game, Plot plot) {
        pin(game);
        plot.fill(-2, -4, -3, COURSE + 2, 2, 3, Blocks.STONE);
        plot.fill(-1, 2, -2, COURSE + 1, 2, 2, Blocks.AIR);
        plot.fill(-1, -3, -2, COURSE + 1, 1, 2, Blocks.WATER);
    }

    private static void pin(Game game) {
        Settings.pin(game).set("allowBreak", false).set("allowPlace", false)
                // The shipped arrival rule, which the pinned baseline turns off.
                .set("navigatorHoldLastNodeUntilStanding", true);
    }

    /** An invulnerable mob of {@code type} at {@code at}, facing +x; discarded when the test ends. */
    private static Mob spawn(Game game, Plot plot, EntityType<? extends Mob> type, BlockPos at) {
        Mob mob = game.onServer(s -> {
            ServerLevel level = s.getLevel(plot.dimension());
            Mob m = type.create(level);
            if (m == null) throw new IllegalStateException(type + " created no entity");
            m.moveTo(at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5, -90f, 0f);
            m.setInvulnerable(true);
            m.setPersistenceRequired();
            level.addFreshEntity(m);
            return m;
        });
        game.afterTest(s -> mob.discard());
        return mob;
    }
}
