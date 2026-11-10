package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.magicterra.stagewright.test.Game;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The human's numbers a benchmark course is judged against, from the course's recorded human runs: the best time and
 * the fewest errors, each the best of any finished run, as {@code bench.py report} picks them. The criterion is
 * {@code docs/dev/benchmark.md}'s: the bot's run does the course, is faster than the human's best, and has no more
 * errors than the human's fewest; on a combat course the bot's median run is judged instead.
 *
 * @param course the course's id
 * @param seconds the human's best time, by the bench's clock
 * @param errors the fewest errors of any finished human run
 */
public record HumanBest(String course, double seconds, int errors) {

    private static final Logger LOG = LogManager.getLogger("worlddriver.course");

    /** The best time in ticks, the clock the course tests run on. */
    public int ticks() {
        return (int) Math.round(seconds * CourseRecorder.TICKS_PER_SECOND);
    }

    /** Check {@code run}, scored {@code score}, against the human; each part of the criterion fails on its own. */
    public void judge(Game game, CourseRun run, CourseErrors.Score score) {
        String summary = summary(run, score);
        LOG.info("[course] {}", summary);
        game.check(run.finished(), "the bot does the course: " + summary);
        game.check(run.finished() && run.ticks() < ticks(), "the bot beats the human's best time: " + summary);
        game.check(score.errors() <= errors, "the bot makes no more errors than the human's fewest: " + summary);
    }

    /**
     * Check a combat course's runs against the human: every run finishes, the median time beats the human's best, and
     * the median errors are no more than the human's fewest. A fight is partly luck, so the median is judged.
     */
    public void judgeMedian(Game game, List<CourseRun> runs, List<CourseErrors.Score> scores) {
        List<String> each = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            String s = summary(runs.get(i), scores.get(i));
            LOG.info("[course] {}", s);
            each.add(s);
        }
        double medianTicks = median(runs.stream()
                .filter(CourseRun::finished)
                .map(r -> (double) r.ticks())
                .toList());
        double medianErrors =
                median(scores.stream().map(s -> (double) s.errors()).toList());
        String summary = String.format(
                Locale.ROOT,
                "%s: median %.1f ticks (human best %d), median errors %.1f (human fewest %d); runs: %s",
                course,
                medianTicks,
                ticks(),
                medianErrors,
                errors,
                String.join(" | ", each));
        LOG.info("[course] {}", summary);
        game.check(runs.stream().allMatch(CourseRun::finished), "every bot run does the course: " + summary);
        game.check(medianTicks < ticks(), "the bot's median run beats the human's best time: " + summary);
        game.check(medianErrors <= errors, "the bot's median errors are no more than the human's fewest: " + summary);
    }

    private String summary(CourseRun run, CourseErrors.Score score) {
        return String.format(
                Locale.ROOT,
                "%s: %s in %d ticks (%.2f s; human best %d ticks, %.1f s), %s; human fewest errors %d; %s; splits %s;"
                        + " recording %s",
                course,
                run.end(),
                run.ticks(),
                run.seconds(),
                ticks(),
                seconds,
                score,
                errors,
                run.fps(),
                run.splits(),
                run.recording());
    }

    /**
     * The median as Python's {@code statistics.median}: the mean of the middle two of an even count; NaN when empty.
     */
    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> v = values.stream().sorted().toList();
        int n = v.size();
        return n % 2 == 1 ? v.get(n / 2) : (v.get(n / 2 - 1) + v.get(n / 2)) / 2;
    }
}
