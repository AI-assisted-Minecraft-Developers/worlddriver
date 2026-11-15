package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Plot;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.BlockEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A server bot racing the client's bot on a benchmark course: both started on the same tick, each sampled on every tick
 * and scored by the same rules ({@link CourseErrors}). The server bot does the course, takes no more than
 * {@value #TIME_SLACK} times the client's ticks and makes no more than {@value #ERROR_SLACK} errors more than the
 * client; on a combat course the medians of the runs are compared, as a combat course is judged against the human.
 *
 * @param course the course's id
 */
public record CourseRace(String course) {

    /** The server's ticks may be at most this many times the client's. */
    public static final double TIME_SLACK = 1.15;

    /** The server may make at most this many errors more than the client. */
    public static final int ERROR_SLACK = 2;

    /** On a task course the server's ticks must be at least this many times the client's. */
    public static final double TASK_FLOOR = 0.85;

    private static final Logger LOG = LogManager.getLogger("worlddriver.course");

    private static final String TEAM = "course_race";

    /** The block events inside {@code plot}: those of the body racing on the copy of the course placed there. */
    public static Predicate<BlockEvent> inPlot(Plot plot) {
        BlockPos a = plot.at(plot.min().getX(), plot.min().getY(), plot.min().getZ());
        BlockPos b = plot.at(plot.max().getX(), plot.max().getY(), plot.max().getZ());
        return e -> CourseTask.in(e.pos(), a, b);
    }

    /**
     * The block events nearer {@code self} than {@code other} when they are read: two bodies racing on one terrain
     * share the server's event ring, which names no player, and each breaks and places only within its reach.
     */
    public static Predicate<BlockEvent> nearer(CourseBody self, CourseBody other) {
        return e -> {
            Vec3 at = Vec3.atCenterOf(e.pos());
            return self.position().distanceToSqr(at) <= other.position().distanceToSqr(at);
        };
    }

    /**
     * Put both bodies' players in one team that does not push, for the rest of the test: two bots on one terrain at
     * once would otherwise shove each other off their lines.
     */
    public static void noPushing(Game game, CourseBody... bodies) {
        List<String> names = new ArrayList<>();
        for (CourseBody b : bodies) {
            names.add(b.onServer(ServerPlayer::getScoreboardName));
        }
        game.afterTest(s -> {
            Scoreboard board = s.getScoreboard();
            PlayerTeam team = board.getPlayerTeam(TEAM);
            if (team != null) {
                board.removePlayerTeam(team);
            }
        });
        game.runOnServer(s -> {
            Scoreboard board = s.getScoreboard();
            PlayerTeam team = board.getPlayerTeam(TEAM);
            if (team == null) {
                team = board.addPlayerTeam(TEAM);
            }
            team.setCollisionRule(Team.CollisionRule.NEVER);
            for (String n : names) {
                board.addPlayerToTeam(n, team);
            }
        });
    }

    /** Check one race: the server's run against the client's. Each part fails on its own. */
    public void judge(
            Game game,
            CourseRun client,
            CourseErrors.Score clientScore,
            CourseRun server,
            CourseErrors.Score serverScore) {
        String summary = String.format(
                Locale.ROOT,
                "%s race: client %s; server %s",
                course,
                line(client, clientScore),
                line(server, serverScore));
        LOG.info("[race] {}", summary);
        judged(
                game,
                client.finished(),
                server.finished(),
                client.ticks(),
                server.ticks(),
                clientScore.errors(),
                serverScore.errors(),
                summary);
    }

    /**
     * {@link #judge} for a task course, where the server bot must not be much faster either: a body that digs, plants
     * or builds faster than the client's is not playing by a player's rules, so it takes at least {@value #TASK_FLOOR}
     * times the client's ticks.
     */
    public void judgeTask(
            Game game,
            CourseRun client,
            CourseErrors.Score clientScore,
            CourseRun server,
            CourseErrors.Score serverScore) {
        judge(game, client, clientScore, server, serverScore);
        game.check(
                !server.finished() || !client.finished() || server.ticks() >= client.ticks() * TASK_FLOOR,
                String.format(
                        Locale.ROOT,
                        "%s: the server bot takes at least %s times the client's ticks: server %d, client %d",
                        course,
                        TASK_FLOOR,
                        server.ticks(),
                        client.ticks()));
    }

    /** Check races run several times: every run finishes, and the server's medians against the client's. */
    public void judgeMedian(
            Game game,
            List<CourseRun> client,
            List<CourseErrors.Score> clientScores,
            List<CourseRun> server,
            List<CourseErrors.Score> serverScores) {
        List<String> each = new ArrayList<>();
        for (int i = 0; i < client.size(); i++) {
            String s = String.format(
                    Locale.ROOT,
                    "run %d: client %s; server %s",
                    i + 1,
                    line(client.get(i), clientScores.get(i)),
                    line(server.get(i), serverScores.get(i)));
            LOG.info("[race] {} {}", course, s);
            each.add(s);
        }
        double ct = median(finishedTicks(client));
        double st = median(finishedTicks(server));
        double ce = median(clientScores.stream().map(s -> (double) s.errors()).toList());
        double se = median(serverScores.stream().map(s -> (double) s.errors()).toList());
        String summary = String.format(
                Locale.ROOT,
                "%s race medians: client %.1f ticks %.1f errors, server %.1f ticks %.1f errors; %s",
                course,
                ct,
                ce,
                st,
                se,
                String.join(" | ", each));
        LOG.info("[race] {}", summary);
        judged(
                game,
                client.stream().allMatch(CourseRun::finished),
                server.stream().allMatch(CourseRun::finished),
                ct,
                st,
                ce,
                se,
                summary);
    }

    private static void judged(
            Game game,
            boolean clientDone,
            boolean serverDone,
            double clientTicks,
            double serverTicks,
            double clientErrors,
            double serverErrors,
            String summary) {
        game.check(serverDone, "the server bot does the course: " + summary);
        game.check(clientDone, "the client bot does the course it is raced on: " + summary);
        game.check(
                serverDone && clientDone && serverTicks <= clientTicks * TIME_SLACK,
                "the server bot takes no more than " + TIME_SLACK + " times the client's ticks: " + summary);
        game.check(
                serverErrors <= clientErrors + ERROR_SLACK,
                "the server bot makes no more than " + ERROR_SLACK + " errors more than the client: " + summary);
    }

    private static String line(CourseRun run, CourseErrors.Score score) {
        return String.format(
                Locale.ROOT,
                "%s in %d ticks, %s; splits %s; recording %s",
                run.end(),
                run.ticks(),
                score,
                run.splits(),
                run.recording());
    }

    private static List<Double> finishedTicks(List<CourseRun> runs) {
        return runs.stream()
                .filter(CourseRun::finished)
                .map(r -> (double) r.ticks())
                .toList();
    }

    /** The median as Python's {@code statistics.median}; NaN when empty. */
    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> v = values.stream().sorted().toList();
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }
}
