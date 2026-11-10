package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.magicterra.worlddriver.bot.stagewright.kit.ElytraCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.ElytraCourse.Ring;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's elytra course ({@code docs/dev/benchmark.md}), on the real client's
 * player: the tower, the seven rings and the pad the bench built in the Lab world, placed in a plot, and the flight
 * judged as {@code bench.py report} judges it against the human's best recorded run.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
class ElytraCourseTest {

    private static final double HALF = 3.5;

    private static final Vec3 EAST = new Vec3(1, 0, 0);

    private static final Vec3 SOUTH = new Vec3(0, 0, 1);

    private static final Vec3 WEST = new Vec3(-1, 0, 0);

    private static final Vec3 NORTH = new Vec3(0, 0, -1);

    /** The minimum corner of the course's setup commands in the Lab world: where its structure file starts. */
    private static final BlockPos ELYTRA_RINGS_CORNER = new BlockPos(1206, 155, 1146);

    /** {@code scripts/bench/courses/elytra-rings.json}. */
    private static final ElytraCourse ELYTRA_RINGS = new ElytraCourse(
            "elytra-rings",
            new Vec3(1220.5, 190, 1150.5),
            -90f,
            List.of(
                    new Ring(new Vec3(1255.5, 183.5, 1150.5), EAST, HALF),
                    new Ring(new Vec3(1300.5, 177.5, 1162.5), EAST, HALF),
                    new Ring(new Vec3(1335.5, 188.5, 1195.5), SOUTH, HALF),
                    new Ring(new Vec3(1335.5, 170.5, 1245.5), SOUTH, HALF),
                    new Ring(new Vec3(1295.5, 172.5, 1275.5), WEST, HALF),
                    new Ring(new Vec3(1250.5, 180.5, 1255.5), WEST, HALF),
                    new Ring(new Vec3(1210.5, 174.5, 1225.5), NORTH, HALF)),
            new Vec3(1210, 165, 1190),
            new BlockPos(1207, 164, 1187),
            new BlockPos(1213, 164, 1193),
            150,
            180 * CourseRecorder.TICKS_PER_SECOND);

    /**
     * {@code elytra-rings}' human baseline: the best of its two finished human runs, 13.0 s, which is also the one with
     * the fewest errors, 0.
     */
    private static final HumanBest ELYTRA_RINGS_HUMAN = new HumanBest("elytra-rings", 13.0, 0);

    /**
     * Off the tower, through seven rings in order with three right turns, a climb that needs a rocket and an 18-block
     * dive, and onto the hay pad: the bot is faster than the human's best run and makes no more errors.
     */
    @Test
    void elytraRingsBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 140, y = 48, z = 140) Plot plot) {
        CourseWorld.noRandomTicks(game);
        ElytraCourse placed =
                ELYTRA_RINGS.shifted(CourseTerrain.place(plot, ELYTRA_RINGS.name(), ELYTRA_RINGS_CORNER, 2, 6, 2));
        CourseRun run = placed.runBot(game, client, player);
        ELYTRA_RINGS_HUMAN.judge(game, run, placed.score(run));
    }
}
