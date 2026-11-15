package net.magicterra.worlddriver.bot.stagewright.scenes;

import java.util.List;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Locks;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseAttempt;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseBody;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRace;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRun;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseWorld;
import net.magicterra.worlddriver.bot.stagewright.kit.RouteCourse;
import net.magicterra.worlddriver.testcontent.TestMod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * A server bot against the client's bot on the benchmark's natural-terrain route, {@link TerrainCourseTest}'s
 * cross-country course. The terrain is the world's own and cannot be copied, so both bots run it at once from the same
 * start, in one team that does not push, and each keeps the block events nearer itself; the course and its settled
 * water are prepared as for the client's own run. Its own scene, so it starts on terrain no other run has walked.
 */
@Scene("wd.crossCountryRace")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
@ResourceLock(Locks.WORLD_CLOCK)
class TerrainCourseRaceTest {

    /** About 620 blocks over hills to the top of a cliff, with the route kit and every move the bot ships with. */
    @Test
    void crossCountryRace(Game game, Client client, Player player) {
        TerrainCourseTest.generateCourse(game);
        int settled = game.onServer(s -> TerrainCourseTest.settleFluids(s.overworld()));
        TestMod.LOG.info("[course] cross-country race: settled {} fluid ticks before the clock", settled);
        CourseWorld.bench(game);
        RouteCourse course = TerrainCourseTest.CROSS_COUNTRY;
        CourseBody c = CourseBody.client(game, client, player);
        CourseBody s = CourseBody.server(game);
        course.stage(game, c);
        course.stage(game, s);
        CourseRace.noPushing(game, c, s);
        List<CourseRun> runs = CourseAttempt.race(
                game,
                List.of(
                        course.begin(game, c, CourseRace.nearer(c, s)),
                        course.begin(game, s, CourseRace.nearer(s, c))));
        new CourseRace(course.name())
                .judge(game, runs.get(0), course.score(runs.get(0)), runs.get(1), course.score(runs.get(1)));
    }
}
