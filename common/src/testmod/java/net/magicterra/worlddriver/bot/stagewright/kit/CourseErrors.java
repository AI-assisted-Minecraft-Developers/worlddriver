package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.BlockEvent;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;
import net.minecraft.world.phys.Vec3;

/**
 * The errors of a benchmark run, counted from its samples by the rules of {@code scripts/bench/bench.py}'s
 * {@code analyse} and {@code contacts}, which {@code docs/dev/benchmark.md} explains. They are ported here once so that
 * every course test counts them the same way, and the same way the bench counted the human runs the tests are judged
 * against: the constants and the order of the checks are bench.py's.
 *
 * <ul>
 *   <li><b>stall</b>: horizontal speed under 1 block/s for 0.5 s or longer, with no work event within half a second of
 *       it and no climb;
 *   <li><b>bump</b>: a run of samples with {@code horizontalCollision}, unless it is a graze or a ladder climb;
 *   <li><b>backtrack</b>: the distance to the next checkpoint grows 2 blocks past the closest it has been, on a course
 *       with checkpoints;
 *   <li><b>damage</b>: each sample whose health is below the one before.
 * </ul>
 */
public final class CourseErrors {

    private static final double STALL_SPEED = 1.0;

    private static final double STALL_S = 0.5;

    /** A block event this close to a slow spell, before or after, makes it work rather than a stall. */
    private static final double WORK_SLACK_S = 0.5;

    /** A contact this long that rose this far without dipping is a ladder climb. */
    private static final double LADDER_S = 0.4;

    private static final double LADDER_RISE = 0.8;

    /** Blocks of height in each half second that make a sideways-still body a climbing one. */
    private static final double CLIMB_HALF_S = 0.4;

    private static final double BACKTRACK = 2.0;

    private static final double GRAZE_S = 0.2;

    private static final double GRAZE_CLIMB_S = 1.0;

    private static final double GRAZE_RISE = 0.9;

    /** The sample rate of bench.py; the climb test looks back this many samples, in two halves. */
    private static final int HZ = CourseRecorder.TICKS_PER_SECOND;

    private CourseErrors() {}

    /**
     * What a course scores. {@code targets}: the checkpoints and then the goal, whose distance the backtrack rule
     * watches, or empty on a course that roams (no backtracks counted). {@code work}: the block event types that make a
     * slow spell work, {@code block.break} on most courses.
     */
    public record Rules(List<Vec3> targets, Set<String> work, boolean fighting) {
        /** A route course: backtracks against {@code targets}, a break is work. */
        public static Rules route(List<Vec3> targets) {
            return new Rules(List.copyOf(targets), Set.of("block.break"), false);
        }

        /**
         * A course that roams, with no checkpoints to go back from: mining and clearing ({@code block.break} is work),
         * building and farming ({@code block.place} too).
         */
        public static Rules roaming(String... work) {
            return new Rules(List.of(), Set.of(work), false);
        }

        /**
         * A combat course: no backtracks, and a slow spell with a living mob of the wave within reach on every sample
         * of it is fighting, not a stall.
         */
        public static Rules combat() {
            return new Rules(List.of(), Set.of("block.break"), true);
        }
    }

    /**
     * The counts of one run. {@code backtracks}, {@code stalls}, {@code grazes}, {@code wrong} and {@code misses} are
     * -1 on a course that does not count them.
     */
    public record Score(
            int stalls,
            double stallSeconds,
            int bumps,
            int grazes,
            int backtracks,
            int hurts,
            double hurt,
            double waterSeconds,
            int placed,
            int broken,
            double walked,
            int wrong,
            int misses) {

        /** The sum the criterion uses. */
        public int errors() {
            return Math.max(stalls, 0)
                    + bumps
                    + Math.max(backtracks, 0)
                    + hurts
                    + Math.max(wrong, 0)
                    + Math.max(misses, 0);
        }

        /** This score with {@code n} wrong blocks counted. */
        public Score withWrong(int n) {
            return new Score(
                    stalls,
                    stallSeconds,
                    bumps,
                    grazes,
                    backtracks,
                    hurts,
                    hurt,
                    waterSeconds,
                    placed,
                    broken,
                    walked,
                    n,
                    misses);
        }

        @Override
        public String toString() {
            return String.format(
                    Locale.ROOT,
                    "errors=%d (stalls=%d %.1fs, bumps=%d, backtracks=%d, damage=%d %.0fhp, wrong=%d, misses=%d;"
                            + " grazes=%d) water=%.1fs placed=%d broken=%d walked=%.0f",
                    errors(),
                    stalls,
                    stallSeconds,
                    bumps,
                    backtracks,
                    hurts,
                    hurt,
                    wrong,
                    misses,
                    grazes,
                    waterSeconds,
                    placed,
                    broken,
                    walked);
        }
    }

    /** Score {@code rows} under {@code rules}. */
    public static Score score(List<Sample> rows, Rules rules) {
        List<Double> work = new ArrayList<>();
        int placed = 0;
        int broken = 0;
        for (Sample r : rows) {
            for (BlockEvent e : r.events()) {
                if (rules.work().contains(e.type())) {
                    work.add(r.seconds());
                }
                placed += "block.place".equals(e.type()) ? 1 : 0;
                broken += "block.break".equals(e.type()) ? 1 : 0;
            }
        }
        Walk walk = walk(rows, rules, work);
        int[] contacts = contacts(rows);
        return new Score(
                walk.stalls,
                walk.stallSeconds,
                contacts[0],
                contacts[1],
                rules.targets().isEmpty() ? -1 : walk.backtracks,
                walk.hurts,
                walk.hurt,
                walk.water,
                placed,
                broken,
                walk.walked,
                -1,
                -1);
    }

    /** What one pass over the sample pairs counts. */
    private static final class Walk {
        int stalls;

        double stallSeconds;

        int backtracks;

        int hurts;

        double hurt;

        double water;

        double walked;
    }

    /**
     * Whether the slow spell over samples {@code from..to + 1}, which started at {@code start} seconds, was work: a
     * work event within half a second of it; or, on a combat course, a living mob of the wave in reach on every sample
     * of it {@code from..to}, where a sample on which a mob that was in reach on the sample before died still counts as
     * in reach (a creeper's blast that kills the pack ends the fight rather than stalling it).
     */
    private static boolean excused(List<Sample> rows, Rules rules, List<Double> work, double start, int from, int to) {
        double end = rows.get(to + 1).seconds();
        if (work.stream().anyMatch(t -> start - WORK_SLACK_S <= t && t <= end + WORK_SLACK_S)) {
            return true;
        }
        if (!rules.fighting()) {
            return false;
        }
        for (int j = from; j <= to; j++) {
            Sample q = rows.get(j);
            Sample prev = j > 0 ? rows.get(j - 1) : null;
            boolean justKilled = prev != null && prev.near() > 0 && q.alive() < prev.alive();
            if (q.near() <= 0 && !justKilled) {
                return false;
            }
        }
        return true;
    }

    private static Walk walk(List<Sample> rows, Rules rules, List<Double> work) {
        Walk w = new Walk();
        Double runStart = null;
        int spellFrom = 0;
        Double best = null;
        int half = HZ / 2;
        for (int i = 0; i + 1 < rows.size(); i++) {
            Sample a = rows.get(i);
            Sample b = rows.get(i + 1);
            double dt = Math.max(b.seconds() - a.seconds(), 1e-3);
            double step = Math.hypot(b.x() - a.x(), b.z() - a.z());
            w.walked += step;
            // Climbing a ladder or vine, or pillaring, moves the body up or down without moving it sideways. It counts
            // as progress when the height went the same way in both halves of the last second.
            double y0 = rows.get(Math.max(0, i + 1 - 2 * half)).y();
            double y1 = rows.get(Math.max(0, i + 1 - half)).y();
            double y2 = rows.get(i + 1).y();
            boolean climbing =
                    Math.min(y1 - y0, y2 - y1) >= CLIMB_HALF_S || Math.max(y1 - y0, y2 - y1) <= -CLIMB_HALF_S;
            boolean slow = step / dt < STALL_SPEED && !climbing;
            if (slow && runStart == null) {
                runStart = a.seconds();
                spellFrom = i;
            }
            if (!slow && runStart != null) {
                double span = b.seconds() - runStart;
                if (span >= STALL_S && !excused(rows, rules, work, runStart, spellFrom, i)) {
                    w.stalls++;
                    w.stallSeconds += span;
                }
                runStart = null;
            }
            if (!Double.isNaN(a.health()) && !Double.isNaN(b.health()) && b.health() < a.health()) {
                w.hurt += a.health() - b.health();
                w.hurts++;
            }
            if (b.inWater()) {
                w.water += dt;
            }
            if (b.checkpoint() != a.checkpoint()) {
                best = null;
            }
            if (!rules.targets().isEmpty()) {
                Vec3 t = rules.targets()
                        .get(Math.min(b.checkpoint(), rules.targets().size() - 1));
                double dist = Math.hypot(t.x - b.x(), t.z - b.z());
                best = best == null ? dist : best;
                if (dist > best + BACKTRACK) {
                    w.backtracks++;
                    best = dist;
                }
                best = Math.min(best, dist);
            }
        }
        return w;
    }

    /**
     * {@code {bumps, grazes}}: each run of {@code horizontalCollision} samples is one contact. A graze lasts at most
     * 0.2 s and is followed within 1 s by standing at least 0.9 above the floor stood on before it: the face of a step
     * the body went on to climb. A contact the body rose along for 0.4 s or more, at least 0.8 blocks without dipping,
     * is a ladder or vine climb and neither.
     */
    static int[] contacts(List<Sample> rows) {
        int bumps = 0;
        int grazes = 0;
        Double floor = null;
        int n = rows.size();
        int i = 0;
        while (i < n) {
            Sample r = rows.get(i);
            if (r.onGround()) {
                floor = r.y();
            }
            if (!r.collided()) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && rows.get(j).collided()) {
                j++;
            }
            boolean brief = rows.get(Math.min(j, n - 1)).seconds() - r.seconds() <= GRAZE_S;
            boolean climbed = false;
            for (int k = i; floor != null && k < n && rows.get(k).seconds() - r.seconds() <= GRAZE_CLIMB_S; k++) {
                climbed |= rows.get(k).onGround() && rows.get(k).y() >= floor + GRAZE_RISE;
            }
            boolean rising = true;
            for (int k = i + 1; k < j; k++) {
                rising &= rows.get(k).y() >= rows.get(k - 1).y() - 1e-3;
            }
            boolean ladder = rows.get(j - 1).seconds() - r.seconds() >= LADDER_S
                    && rows.get(j - 1).y() - r.y() >= LADDER_RISE
                    && rising;
            if (!ladder && brief && climbed) {
                grazes++;
            } else if (!ladder) {
                bumps++;
            }
            i = j;
        }
        return new int[] {bumps, grazes};
    }
}
