package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import java.util.Map;
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
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Checkpoint;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's built route courses ({@code docs/dev/benchmark.md}), on the real
 * client's player: each course's terrain is the structure the bench built in the Lab world, placed in a plot, and the
 * run is judged as {@code bench.py report} judges it, against the human's best recorded run. The course positions are
 * the course file's, in the Lab world, moved with the terrain into the plot.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_RULES)
@ResourceLock(Locks.WORLD_CLOCK)
class BuiltCourseTest {

    private static final double CHECKPOINT_RADIUS = 1.5;

    private static final int FIVE_MINUTES = 300 * CourseRecorder.TICKS_PER_SECOND;

    /** Where every structure goes in its plot: a little in from the plot's minimum corner. */
    private static final int MARGIN = 2;

    /** {@code parkour}'s {@code terrain.box} minimum corner in the Lab world: where its structure file starts. */
    private static final BlockPos PARKOUR_CORNER = new BlockPos(991, 146, 997);

    /** {@code scripts/bench/courses/parkour.json}. */
    private static final RouteCourse PARKOUR = new RouteCourse(
            "parkour",
            new Vec3(994.5, 150, 1000.5),
            -90f,
            checkpoints(
                    1024, 150, 1000, 1032, 150, 1000, 1039, 150, 1000, 1053, 150, 1000, 1068, 150, 1000, 1073, 150,
                    1000),
            new Goal(new Vec3(1076, 155, 1000), CHECKPOINT_RADIUS, false),
            Map.of("allowBreak", false, "allowWaterBucketFall", false, "allowPlace", false, "allowParkour4", true),
            FIVE_MINUTES);

    /**
     * {@code parkour}'s human baseline: the best of its three finished human runs, 22.109 s, which is also the one with
     * the fewest errors, 4 (two stalls and two bumps).
     */
    private static final HumanBest PARKOUR_HUMAN = new HumanBest("parkour", 22.109, 4);

    private static final BlockPos SWIMMING_CORNER = new BlockPos(991, 145, 1097);

    /** {@code scripts/bench/courses/swimming.json}. */
    private static final RouteCourse SWIMMING = new RouteCourse(
            "swimming",
            new Vec3(994.5, 150, 1100.5),
            -90f,
            checkpoints(1037, 150, 1100, 1052, 150, 1100, 1069, 150, 1100),
            new Goal(new Vec3(1082, 150, 1100), CHECKPOINT_RADIUS, false),
            Map.of(
                    "allowBreak", false,
                    "allowWaterBucketFall", false,
                    "allowPlace", false,
                    "allowSwimEscapeBreak", false,
                    "allowSwimEscapePlace", false),
            FIVE_MINUTES);

    /**
     * {@code swimming}'s human baseline: the best of its three finished human runs, 22.831 s, with 1 error (a bump),
     * the fewest of any; the second run also had one.
     */
    private static final HumanBest SWIMMING_HUMAN = new HumanBest("swimming", 22.831, 1);

    private static final BlockPos DIG_ASHORE_CORNER = new BlockPos(991, 145, 1197);

    /** {@code scripts/bench/courses/dig-ashore.json}. */
    private static final RouteCourse DIG_ASHORE = new RouteCourse(
            "dig-ashore",
            new Vec3(994.5, 150, 1200.5),
            -90f,
            checkpoints(1018, 151, 1200, 1034, 152, 1200),
            new Goal(new Vec3(1055, 152, 1200), CHECKPOINT_RADIUS, false),
            Map.of("allowPlace", false, "allowWaterBucketFall", false, "allowSwimEscapePlace", false),
            FIVE_MINUTES);

    /**
     * {@code dig-ashore}'s human baseline: the best of its three finished human runs, 31.478 s, with 3 errors (three
     * bumps), the fewest of any; the first run also had three.
     */
    private static final HumanBest DIG_ASHORE_HUMAN = new HumanBest("dig-ashore", 31.478, 3);

    private static final BlockPos DIG_AND_BUILD_CORNER = new BlockPos(991, 134, 1297);

    /** {@code scripts/bench/courses/dig-and-build.json}; the shipped settings, no {@code botSettings}. */
    private static final RouteCourse DIG_AND_BUILD = new RouteCourse(
            "dig-and-build",
            new Vec3(994.5, 150, 1300.5),
            -90f,
            checkpoints(1016, 150, 1300, 1020, 154, 1300, 1029, 154, 1300),
            new Goal(new Vec3(1038, 136, 1300), CHECKPOINT_RADIUS, false),
            Map.of(),
            FIVE_MINUTES);

    /**
     * {@code dig-and-build}'s human baseline: the best of its three finished human runs, 25.921 s, which is also the
     * one with the fewest errors, 3 (two stalls and a bump).
     */
    private static final HumanBest DIG_AND_BUILD_HUMAN = new HumanBest("dig-and-build", 25.921, 3);

    /**
     * Gaps of one to three blocks, a jump up and down across a gap, diagonal jumps between pillars, stairs up and down,
     * a ladder up and down a wall and a vine up a wall, with breaking, placing and the water bucket off: the bot is
     * faster than the human's best run and makes no more errors than the human's fewest.
     */
    @Test
    void parkourBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 96, y = 24, z = 16) Plot plot) {
        run(game, client, player, plot, PARKOUR, PARKOUR_CORNER, PARKOUR_HUMAN);
    }

    /**
     * A thirty-block swim, a dive under a wall, a drop of six into a pool and a wade, each pool left over a one-block
     * bank, with breaking, placing and the water bucket off.
     */
    @Test
    void swimmingBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 100, y = 24, z = 12) Plot plot) {
        run(game, client, player, plot, SWIMMING, SWIMMING_CORNER, SWIMMING_HUMAN);
    }

    /**
     * Climbing out of water where no jump reaches the bank: banks two and three above the surface and a pocket under a
     * low ceiling. Breaking is on; placing and the water bucket are off.
     */
    @Test
    void digAshoreBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 80, y = 24, z = 12) Plot plot) {
        run(game, client, player, plot, DIG_ASHORE, DIG_ASHORE_CORNER, DIG_ASHORE_HUMAN);
    }

    /**
     * A wall six thick to tunnel through, a wall four high to pillar up, a six-block gap to bridge and an
     * eighteen-block fall that only a water bucket makes harmless, with every move the bot ships with.
     */
    @Test
    void digAndBuildBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 60, y = 32, z = 12) Plot plot) {
        run(game, client, player, plot, DIG_AND_BUILD, DIG_AND_BUILD_CORNER, DIG_AND_BUILD_HUMAN);
    }

    private static void run(
            Game game, Client client, Player player, Plot plot, RouteCourse course, BlockPos corner, HumanBest human) {
        CourseWorld.noRandomTicks(game);
        BlockPos shift = CourseTerrain.place(plot, course.name(), corner, MARGIN, 0, MARGIN);
        RouteCourse placed = course.shifted(shift);
        CourseRun run = placed.runBot(game, client, player);
        human.judge(game, run, placed.score(run));
    }

    /** Checkpoints of radius 1.5 at each {@code x, y, z} in turn. */
    private static List<Checkpoint> checkpoints(int... xyz) {
        Checkpoint[] out = new Checkpoint[xyz.length / 3];
        for (int i = 0; i < out.length; i++) {
            out[i] = new Checkpoint(new Vec3(xyz[3 * i], xyz[3 * i + 1], xyz[3 * i + 2]), CHECKPOINT_RADIUS);
        }
        return List.of(out);
    }
}
