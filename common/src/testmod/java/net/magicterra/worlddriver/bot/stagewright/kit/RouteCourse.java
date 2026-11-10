package net.magicterra.worlddriver.bot.stagewright.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * A route course of {@code scripts/bench/courses/}, run by the bot on the real client's player as {@code bench.py run
 * --driver bot} runs it: the route kit, the shipped settings plus the course's own {@code botSettings}, one
 * {@code mc.bot.goto} to the goal with the checkpoints as {@code route.via}, and the clock running from the order to
 * the sample that sees the goal reached. The checkpoints and the goal are judged as the bench judges them.
 *
 * <p>The positions are the course file's, in the world the bench ran it in; {@link #shifted} moves the whole course,
 * for a built course placed in a plot.
 *
 * @param name the course's id, as its file under {@code scripts/bench/courses/} names it
 * @param start the feet at the start; the player stands in the centre of its cell
 * @param yaw the way the player faces at the start
 * @param checkpoints passed in order: horizontally within the radius, vertically within 2
 * @param goal where the run ends
 * @param botSettings the course's own settings for the bot's runs
 * @param maxTicks the bench's {@code maxSeconds}, in ticks
 */
public record RouteCourse(
        String name,
        Vec3 start,
        float yaw,
        List<Checkpoint> checkpoints,
        Goal goal,
        Map<String, Object> botSettings,
        int maxTicks) {

    /** The bench asked {@code mc.bot.status} once a second from the second second on. */
    private static final int STATUS_EVERY = CourseRecorder.TICKS_PER_SECOND;

    private static final int STATUS_FROM = 2 * CourseRecorder.TICKS_PER_SECOND;

    /** A checkpoint counts within this many blocks up or down. */
    private static final double CHECKPOINT_HEIGHT = 2;

    /** A checkpoint: reached horizontally within {@code radius} of {@code pos} and within 2 blocks of its height. */
    public record Checkpoint(Vec3 pos, double radius) {}

    /** The goal: within {@code radius} of {@code pos}, or of its x and z at any height when {@code column}. */
    public record Goal(Vec3 pos, double radius, boolean column) {}

    /** The same course moved by {@code by}: a built course exported at its bench position and placed in a plot. */
    public RouteCourse shifted(Vec3i by) {
        Vec3 d = Vec3.atLowerCornerOf(by);
        return new RouteCourse(
                name,
                start.add(d),
                yaw,
                checkpoints.stream()
                        .map(c -> new Checkpoint(c.pos().add(d), c.radius()))
                        .toList(),
                new Goal(goal.pos().add(d), goal.radius(), goal.column()),
                botSettings,
                maxTicks);
    }

    /** The checkpoints and then the goal: what the backtrack rule measures the distance to. */
    public List<Vec3> targets() {
        List<Vec3> t = new ArrayList<>(checkpoints.stream().map(Checkpoint::pos).toList());
        t.add(goal.pos());
        return t;
    }

    /** This course's errors in {@code run}, by the route courses' rules. */
    public CourseErrors.Score score(CourseRun run) {
        return CourseErrors.score(run.samples(), CourseErrors.Rules.route(targets()));
    }

    /**
     * Run the course once with the bot: stage the player at the start with the route kit ({@link CourseKit#stage}),
     * then order the walk and record it until the goal, the bot stopping, or {@code maxTicks}. Only from the test's own
     * thread, in a class holding the locks {@link CourseWorld#bench} needs.
     */
    public CourseRun runBot(Game game, Client client, Player player) {
        final ClientRig rig = CourseKit.route().stage(game, client, player, start, yaw, botSettings);
        CourseRecorder rec = new CourseRecorder(game, client);
        rec.start();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("target", target());
        if (!checkpoints.isEmpty()) {
            params.put(
                    "route",
                    Map.of("via", checkpoints.stream().map(c -> cell(c.pos())).toList()));
        }
        Map<String, Object> reply = Calls.byName(client.registry(), "mc.bot.goto", params);
        if (!"accepted".equals(reply.get("outcome"))) {
            rig.cancel();
            return CourseRun.refused(reply);
        }
        List<Integer> splits = new ArrayList<>();
        CourseLoop.End end = CourseLoop.run(
                game,
                rec,
                maxTicks,
                splits::size,
                s -> {
                    if (splits.size() < checkpoints.size()) {
                        if (reached(checkpoints.get(splits.size()), s)) {
                            splits.add(s.tick());
                        }
                        return null;
                    }
                    return atGoal(s) ? "arrived" : null;
                },
                () -> {
                    var status = rig.status();
                    return status.busy() ? null : "bot-stopped: " + status.lastProcessEnd();
                },
                STATUS_FROM,
                STATUS_EVERY);
        rig.cancel();
        rec.finish();
        return new CourseRun(
                end.end(),
                end.ticks(),
                List.copyOf(splits),
                rec.samples(),
                rec.write(name, meta(), end.end(), end.ticks()));
    }

    private Map<String, Object> target() {
        BlockPos g = BlockPos.containing(goal.pos());
        return goal.column()
                ? Map.of("kind", "column", "x", g.getX(), "z", g.getZ())
                : Map.of("kind", "position", "pos", cell(goal.pos()));
    }

    /** A cell as a JSON position, the cell {@code p} is in, as bench.py's {@code bp}. */
    static Map<String, Object> cell(Vec3 p) {
        BlockPos c = BlockPos.containing(p);
        return Map.of("x", c.getX(), "y", c.getY(), "z", c.getZ());
    }

    private static boolean reached(Checkpoint c, Sample s) {
        return Math.hypot(s.x() - c.pos().x, s.z() - c.pos().z) <= c.radius()
                && Math.abs(s.y() - c.pos().y) <= CHECKPOINT_HEIGHT;
    }

    private boolean atGoal(Sample s) {
        double flat = Math.hypot(s.x() - goal.pos().x, s.z() - goal.pos().z);
        double d = goal.column() ? flat : Math.hypot(flat, s.y() - goal.pos().y);
        return d <= goal.radius();
    }

    /** The meta line's course fields bench.py's {@code analyse} reads. */
    private Map<String, Object> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("start", List.of(start.x, start.y, start.z));
        m.put(
                "checkpoints",
                checkpoints.stream()
                        .map(c -> List.of(c.pos().x, c.pos().y, c.pos().z))
                        .toList());
        m.put("goal", Map.of("pos", List.of(goal.pos().x, goal.pos().y, goal.pos().z)));
        return m;
    }
}
