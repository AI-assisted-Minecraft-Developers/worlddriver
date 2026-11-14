package net.magicterra.worlddriver.bot.stagewright.kit;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;

/**
 * How one bot run of a benchmark course ended, as {@code bench.py run} writes its end line.
 *
 * @param end {@code arrived}, {@code collected}, {@code cleared}, {@code built}, {@code farmed} or {@code landed} when
 *     the course was done; otherwise {@code bot-stopped: <why>}, {@code died}, {@code fell}, {@code overshot},
 *     {@code max-s}, {@code no-samples} (out of time with the client's player gone), or {@code refused: <reply>}
 * @param ticks from the order to the sample that saw the course done, or to the end of the run
 * @param splits the tick of each split: a checkpoint, an ore, a quarter of the cells, a ring, a kill
 * @param samples every sample, oldest first
 * @param recording the run written as a bench.py recording
 */
public record CourseRun(String end, int ticks, List<Integer> splits, List<Sample> samples, Path recording) {

    private static final Set<String> FINISHED = Set.of("arrived", "collected", "cleared", "built", "farmed", "landed");

    /** A run the bot was never ordered on: the order was refused. */
    public static CourseRun refused(Object reply) {
        return new CourseRun("refused: " + reply, 0, List.of(), List.of(), null);
    }

    /** Whether the run did the course. */
    public boolean finished() {
        return FINISHED.contains(end);
    }

    /** The run's time in the bench's seconds. */
    public double seconds() {
        return (double) ticks / CourseRecorder.TICKS_PER_SECOND;
    }

    /** The lowest and the median client frame rate over the run's samples. */
    public String fps() {
        List<Integer> f = samples.stream().map(Sample::fps).sorted().toList();
        return f.isEmpty() ? "no samples" : "fps min " + f.getFirst() + ", median " + f.get(f.size() / 2);
    }
}
