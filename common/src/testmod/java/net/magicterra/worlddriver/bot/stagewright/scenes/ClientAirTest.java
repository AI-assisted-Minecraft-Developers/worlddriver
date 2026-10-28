package net.magicterra.worlddriver.bot.stagewright.scenes;

import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.at;
import static net.magicterra.worlddriver.bot.stagewright.scenes.ClientWalk.f;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.LinkedHashMap;
import java.util.Map;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.ClientRig;
import net.magicterra.worlddriver.bot.stagewright.kit.Targets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Walks that begin with the client's REAL player in the air — falling off something, knocked back, or simply asked to
 * go somewhere before it has landed — or that must leave the ground to get anywhere. The navigator's search starts
 * from a foot cell that has nothing under it, and everything downstream of that (the first path, the first
 * step-advance, the first stall clock) has to survive the landing.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class ClientAirTest {

    /** Top of the slab: the ground's foot cell is {@code GROUND + 1}. */
    private static final int GROUND = 0;
    private static final int HALF = 12;
    /** Blocks of air under the player's feet at the start — a fall that hurts but does not kill. */
    private static final int DROP = 5;
    private static final int SHAFT_DEPTH = 6;
    private static final int LEG_TICKS = 600;
    private static final int SETTLE_TICKS = 10;

    /**
     * The walk is ordered while the player is still five blocks up over flat stone. It must land, plan from where it
     * landed and walk the six cells to the goal — inside 200 ticks, which is the fall plus a plain walk with room for
     * one repath, not for a search that keeps starting from a cell the player has already left.
     */
    @Test
    void clientGotoStartsMidAir(Game game, Client client, Player player,
                                @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        stageSlab(plot);
        BlockPos start = plot.at(0, GROUND + 1 + DROP, 0);
        BlockPos goal = plot.at(6, GROUND + 1, 0);
        int arriveBy = 200;
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        // The stone landing is the walk that measured the diagonal overshoot of the last node, and its budget is what
        // notices it: the shipped defaults, restored over the pinned baseline.
        rig.settings().set(shipped(true, true));
        // Put back up after the settings writes, which take round trips the player spends falling: the point is to
        // order the walk while it still is. Two ticks for the teleport to reach the client.
        player.moveTo(start);
        rig.sync(2);
        ClientWalk.Sample before = ClientWalk.sample(rig);
        if (before.onGround()) {
            fail("test setup invalid: the player was already on the ground before the goto started: " + rig.where());
        }

        ClientWalk walk = new ClientWalk(rig);
        int[] firstGround = { -1 };
        int[] arrived = { -1 };
        double[] minY = { before.y() };
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            if (firstGround[0] < 0 && s.onGround()) firstGround[0] = walk.tick(s);
            if (arrived[0] < 0 && s.onGround() && s.flat(goal) <= 1.0) arrived[0] = walk.tick(s);
            minY[0] = Math.min(minY[0], s.y());
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        String progress = "first touched the ground at tick " + at(firstGround[0]) + ", reached the goal at tick "
                + at(arrived[0]) + ", lowest y=" + f(minY[0]) + ", health " + end.health() + "; " + rig.where();
        game.check(end.onGround() && !end.inWater(), "A: the player ends standing on dry ground: " + progress);
        game.check(end.flat(goal) <= 1.5,
                "B: stops within 1.5 blocks of the goal cell: horizontal distance " + f(end.flat(goal)) + "; " + progress);
        game.check(arrived[0] >= 0 && arrived[0] <= arriveBy, "C: arrives within " + arriveBy + " ticks: " + progress);
        game.check(end.health() > 0, "D: the player is alive: " + progress);
    }

    /**
     * A two-wide chasm across the only way to the goal, deep enough to hurt and with nothing to place. The walk is a
     * run-up, one leap and a landing; the budget is that plus a short walk, and the floor of the chasm is the line the
     * player must never reach.
     */
    @Test
    void clientParkourOverChasm(Game game, Client client, Player player,
                                @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        stageSlab(plot);
        plot.fill(2, GROUND - 5, -HALF, 3, GROUND, HALF, Blocks.AIR);
        BlockPos start = plot.at(-2, GROUND + 1, 0);
        BlockPos goal = plot.at(7, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        rig.settings().set(shipped(false, false));
        rig.sync(30);

        ClientWalk walk = new ClientWalk(rig);
        int[] arrived = { -1 };
        double[] minY = { ClientWalk.sample(rig).y() };
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            if (arrived[0] < 0 && s.onGround() && s.flat(goal) <= 1.0) arrived[0] = walk.tick(s);
            minY[0] = Math.min(minY[0], s.y());
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        int foot = goal.getY();
        String progress = "reached the goal at tick " + at(arrived[0]) + ", lowest y=" + f(minY[0]) + " (ground foot level "
                + foot + "), health " + end.health() + "; " + rig.where();
        game.check(end.flat(goal) <= 1.5,
                "A: stops within 1.5 blocks of the goal cell: horizontal distance " + f(end.flat(goal)) + "; " + progress);
        game.check(minY[0] >= foot - 0.05, "B: never falls into the chasm: " + progress);
        game.check(arrived[0] >= 0 && arrived[0] <= 150, "C: arrives within 150 ticks: " + progress);
    }

    /**
     * The player stands at the bottom of a one-wide shaft six deep with dirt in hand; the goal is on the surface three
     * cells from the mouth. The only way out is six pillar rungs. Each rung is a jump and a placement, a dozen ticks;
     * the budget is those plus the walk, not a search that rediscovers the shaft every rung.
     */
    @Test
    void clientPillarOutOfShaft(Game game, Client client, Player player,
                                @Plot.Centered @Plot.Size(x = 32, y = 24, z = 32) Plot plot) {
        stageSlab(plot);
        plot.fill(0, GROUND - SHAFT_DEPTH + 1, 0, 0, GROUND, 0, Blocks.AIR);
        BlockPos start = plot.at(0, GROUND + 1 - SHAFT_DEPTH, 0);
        BlockPos goal = plot.at(3, GROUND + 1, 0);
        ClientRig rig = ClientRig.adopt(game, client, player, start, -90f);
        rig.hold(new ItemStack(Items.DIRT, 30));
        rig.settings().set(shipped(true, true));
        rig.sync(30);

        ClientWalk walk = new ClientWalk(rig);
        int[] arrived = { -1 };
        int[] surfaced = { -1 };
        walk.go(Targets.block(goal), LEG_TICKS, SETTLE_TICKS, s -> {
            // After tick 5: on the first ticks of the goto the server's copy can still show the pre-teleport surface
            // position, which would read as "surfaced at tick 0".
            int t = walk.tick(s);
            if (t > 5 && surfaced[0] < 0 && s.onGround() && s.y() >= goal.getY() - 0.05) surfaced[0] = t;
            if (arrived[0] < 0 && s.onGround() && s.flat(goal) <= 1.0) arrived[0] = t;
        });
        ClientWalk.Sample end = ClientWalk.sample(rig);
        int used = 30 - ClientWalk.count(rig, Items.DIRT);
        String progress = "left the shaft at tick " + at(surfaced[0]) + ", reached the goal at tick " + at(arrived[0])
                + ", dirt used " + used + "; " + rig.where();
        game.check(end.flat(goal) <= 1.5,
                "A: stops within 1.5 blocks of the goal cell: horizontal distance " + f(end.flat(goal)) + "; " + progress);
        game.check(arrived[0] >= 0 && arrived[0] <= 300, "B: arrives within 300 ticks: " + progress);
        game.check(used <= SHAFT_DEPTH + 1, "C: uses no more dirt than the shaft depth plus one: " + progress);
    }

    /** The navigator settings these walks run with: the shipped defaults the pinned baseline turns off. */
    private static Map<String, Object> shipped(boolean allowBreak, boolean allowPlace) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("allowBreak", allowBreak);
        s.put("allowPlace", allowPlace);
        s.put("navigatorDebug", true);
        s.put("navigatorFinalNodeDirectAim", true);
        s.put("navigatorHoldLastNodeUntilStanding", true);
        s.put("navigatorOrbitBreaksAimLag", true);
        s.put("navigatorSurfaceSprintSwim", true);
        return s;
    }

    /** Stone from {@code GROUND - 6} to {@code GROUND}, air above (the plot is handed over empty). */
    private static void stageSlab(Plot plot) {
        plot.fill(-HALF, GROUND - 6, -HALF, HALF, GROUND, HALF, Blocks.STONE);
    }
}
