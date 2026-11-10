package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseErrors;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseTerrain;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Checkpoint;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Goal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's built courses ({@code docs/dev/benchmark.md}), on the real client's
 * player: each course's terrain is the structure the bench built in the Lab world, placed in a plot, and the run is
 * judged as {@code bench.py report} judges it, against the human's best recorded run. The course positions are the
 * course file's, in the Lab world, moved with the terrain into the plot.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class BuiltCourseTest {

    private static final double CHECKPOINT_RADIUS = 1.5;

    /** {@code parkour}'s {@code terrain.box} minimum corner in the Lab world: where its structure file starts. */
    private static final BlockPos PARKOUR_CORNER = new BlockPos(991, 146, 997);

    /** {@code scripts/bench/courses/parkour.json}. */
    private static final RouteCourse PARKOUR = new RouteCourse(
            "parkour",
            new Vec3(994.5, 150, 1000.5),
            -90f,
            List.of(
                    new Checkpoint(new Vec3(1024, 150, 1000), CHECKPOINT_RADIUS),
                    new Checkpoint(new Vec3(1032, 150, 1000), CHECKPOINT_RADIUS),
                    new Checkpoint(new Vec3(1039, 150, 1000), CHECKPOINT_RADIUS),
                    new Checkpoint(new Vec3(1053, 150, 1000), CHECKPOINT_RADIUS),
                    new Checkpoint(new Vec3(1068, 150, 1000), CHECKPOINT_RADIUS),
                    new Checkpoint(new Vec3(1073, 150, 1000), CHECKPOINT_RADIUS)),
            new Goal(new Vec3(1076, 155, 1000), CHECKPOINT_RADIUS, false),
            Map.of("allowBreak", false, "allowWaterBucketFall", false, "allowPlace", false, "allowParkour4", true),
            300 * CourseRecorder.TICKS_PER_SECOND);

    /**
     * {@code parkour}'s human baseline: the best of its three finished human runs, 22.109 s, which is also the one with
     * the fewest errors, 4 (two stalls and two bumps).
     */
    private static final HumanBest PARKOUR_HUMAN = new HumanBest("parkour", 22.109, 4);

    /**
     * Gaps of one to three blocks, a jump up and down across a gap, diagonal jumps between pillars, stairs up and down,
     * a ladder up and down a wall and a vine up a wall, with breaking, placing and the water bucket off: the bot is
     * faster than the human's best run and makes no more errors than the human's fewest.
     */
    @Test
    void parkourBeatsTheHumanBest(
            Game game, Client client, Player player, @Plot.Size(x = 96, y = 24, z = 16) Plot plot) {
        BlockPos shift = CourseTerrain.place(plot, PARKOUR.name(), PARKOUR_CORNER, 3, 0, 4);
        RouteCourse course = PARKOUR.shifted(shift);
        RouteCourse.Run run = course.runBot(game, client, player);
        PARKOUR_HUMAN.judge(game, run, CourseErrors.score(run.samples(), CourseErrors.Rules.route(course.targets())));
    }
}
