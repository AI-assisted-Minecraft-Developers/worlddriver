package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.HumanBest;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse.Goal;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The bot against the human on the benchmark's natural-terrain route ({@code docs/dev/benchmark.md}), on the real
 * client's player, in a new world made from the course's seed with default settings, as the bench's save was. The run
 * is at the course file's own positions, so the class has its scene to itself: the walk changes the terrain it crosses,
 * and the next run gets a new world.
 */
@Scene("wd.crossCountryCourse")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_CLOCK)
class TerrainCourseTest {

    /** {@code scripts/bench/courses/cross-country.json}; the shipped settings, no {@code botSettings}. */
    private static final RouteCourse CROSS_COUNTRY = new RouteCourse(
            "cross-country",
            new Vec3(1574.5, 66, -101.5),
            160f,
            List.of(),
            new Goal(new Vec3(1385, 116, -625), 3, true),
            Map.of(),
            600 * CourseRecorder.TICKS_PER_SECOND);

    /** {@code cross-country}'s {@code terrain.box}, x and z: the chunks generated before the run. */
    private static final int MIN_X = 1370;

    private static final int MAX_X = 1590;

    private static final int MIN_Z = -640;

    private static final int MAX_Z = -88;

    /**
     * {@code cross-country}'s human baseline: the best of its three finished human runs, 142.575 s, which is also the
     * one with the fewest errors, 35 (three stalls, thirty-one bumps and one fall).
     */
    private static final HumanBest CROSS_COUNTRY_HUMAN = new HumanBest("cross-country", 142.575, 35);

    /**
     * About 620 blocks over hills to the top of a cliff, with the route kit and every move the bot ships with: the bot
     * is faster than the human's best run and makes no more errors than the human's fewest.
     */
    @Test
    void crossCountryBeatsTheHumanBest(Game game, Client client, Player player) {
        generateCourse(game);
        CourseRun run = CROSS_COUNTRY.runBot(game, client, player);
        CROSS_COUNTRY_HUMAN.judge(game, run, CROSS_COUNTRY.score(run));
    }

    /**
     * Generate every chunk of the course before the clock starts: the bench's save had them on disk, and a chunk
     * generated while the player walks into it reaches the client later than one loaded from disk. One chunk per hop to
     * the server thread, so no hop holds it for long.
     */
    private static void generateCourse(Game game) {
        for (int cx = SectionPos.blockToSectionCoord(MIN_X); cx <= SectionPos.blockToSectionCoord(MAX_X); cx++) {
            for (int cz = SectionPos.blockToSectionCoord(MIN_Z); cz <= SectionPos.blockToSectionCoord(MAX_Z); cz++) {
                int x = cx;
                int z = cz;
                game.runOnServer(s -> s.overworld().getChunk(x, z, ChunkStatus.FULL, true));
            }
        }
    }
}
