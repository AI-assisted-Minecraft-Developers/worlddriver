package net.magicterra.worlddriver.bot.stagewright.kit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.worlddriver.api.methods.test.TestHarnessContracts.Baseline;
import net.magicterra.worlddriver.bot.stagewright.kit.CourseRecorder.Sample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * A route course of {@code scripts/bench/courses/}, run by the bot on the real client's player as {@code bench.py run
 * --driver bot} runs it: the bench's kit, the shipped settings plus the course's own {@code botSettings}, one
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

    /** How many ticks the bench let the chunks settle after the teleport to the start. */
    private static final int SETTLE_TICKS = 40;

    /** The bench asked {@code mc.bot.status} once a second from the second second on. */
    private static final int STATUS_EVERY = CourseRecorder.TICKS_PER_SECOND;

    private static final int STATUS_FROM = 2 * CourseRecorder.TICKS_PER_SECOND;

    /** A checkpoint counts within this many blocks up or down. */
    private static final double CHECKPOINT_HEIGHT = 2;

    /** The bench's saturation effect, ten levels for a second: the most saturation the food level allows. */
    private static final float FULL_SATURATION = 20f;

    /** The view and simulation distance of the bench's client, in chunks. */
    private static final int BENCH_CHUNKS = 12;

    /** A checkpoint: reached horizontally within {@code radius} of {@code pos} and within 2 blocks of its height. */
    public record Checkpoint(Vec3 pos, double radius) {}

    /** The goal: within {@code radius} of {@code pos}, or of its x and z at any height when {@code column}. */
    public record Goal(Vec3 pos, double radius, boolean column) {}

    /**
     * How a run ended. {@code end}: {@code arrived}, {@code bot-stopped: <the bot's last process end>} or
     * {@code max-s}; {@code ticks}: from the order to the sample that saw the goal, or to the end of the run;
     * {@code splits}: the tick each checkpoint was passed; {@code recording}: the run written as a bench.py recording.
     */
    public record Run(String end, int ticks, List<Integer> splits, List<Sample> samples, Path recording) {
        /** Whether the run reached the goal. */
        public boolean arrived() {
            return "arrived".equals(end);
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

    /**
     * The bench's route kit, in the hotbar from the first slot in this order: an iron pickaxe, shovel and axe, 64 dirt,
     * 64 cobblestone, 16 cooked beef and a water bucket. The off hand holds a compass, as the bench's lodestone compass
     * that points the person at the next checkpoint.
     */
    public static List<ItemStack> routeKit() {
        return List.of(
                new ItemStack(Items.IRON_PICKAXE),
                new ItemStack(Items.IRON_SHOVEL),
                new ItemStack(Items.IRON_AXE),
                new ItemStack(Items.DIRT, 64),
                new ItemStack(Items.COBBLESTONE, 64),
                new ItemStack(Items.COOKED_BEEF, 16),
                new ItemStack(Items.WATER_BUCKET));
    }

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

    /**
     * Run the course once with the bot: stage the player at the start with the route kit and the shipped settings, the
     * course's {@code botSettings} on top, then order the walk and record it until the goal, the bot stopping, or
     * {@code maxTicks}. Only from the test's own thread.
     */
    public Run runBot(Game game, Client client, Player player) {
        benchViewDistance(game);
        ClientRig rig = ClientRig.adopt(
                game, client, player, Level.OVERWORLD, BlockPos.containing(start), yaw, Baseline.COMPILED);
        if (!botSettings.isEmpty()) {
            rig.settings().set(botSettings);
        }
        rig.hold(routeKit().toArray(ItemStack[]::new));
        player.runOnServer(p -> {
            p.getInventory().offhand.set(0, new ItemStack(Items.COMPASS));
            p.getFoodData().setSaturation(FULL_SATURATION);
            p.containerMenu.broadcastChanges();
        });
        rig.sync(SETTLE_TICKS);

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
            return new Run("refused: " + reply, 0, List.of(), List.of(), null);
        }
        List<Integer> splits = new ArrayList<>();
        String end = "max-s";
        int ticks = 0;
        int nextStatus = STATUS_FROM;
        while (true) {
            Sample s = rec.sample(splits.size());
            if (s != null) {
                ticks = s.tick();
                if (splits.size() < checkpoints.size()) {
                    if (reached(checkpoints.get(splits.size()), s)) {
                        splits.add(s.tick());
                    }
                } else if (atGoal(s)) {
                    end = "arrived";
                    break;
                }
            }
            if (ticks >= nextStatus) {
                var status = rig.status();
                if (!status.busy()) {
                    end = "bot-stopped: " + status.lastProcessEnd();
                    break;
                }
                nextStatus = ticks + STATUS_EVERY;
            }
            if (ticks > maxTicks) {
                break;
            }
            game.ticks(1);
        }
        rig.cancel();
        rec.finish();
        Path file = rec.write(name, meta(), end, ticks);
        return new Run(end, ticks, List.copyOf(splits), rec.samples(), file);
    }

    /**
     * The bench's client rendered 12 chunks and hosted its own server, which then sends and simulates 12 too; a
     * dedicated server sends 10 unless told otherwise, and the client bot plans on the chunks it was sent. Put back
     * when the test ends.
     */
    private static void benchViewDistance(Game game) {
        record Was(int view, int simulation) {}

        Was was = game.onServer(s ->
                new Was(s.getPlayerList().getViewDistance(), s.getPlayerList().getSimulationDistance()));
        game.afterTest(s -> {
            s.getPlayerList().setViewDistance(was.view());
            s.getPlayerList().setSimulationDistance(was.simulation());
        });
        game.runOnServer(s -> {
            s.getPlayerList().setViewDistance(BENCH_CHUNKS);
            s.getPlayerList().setSimulationDistance(BENCH_CHUNKS);
        });
    }

    private Map<String, Object> target() {
        BlockPos g = BlockPos.containing(goal.pos());
        return goal.column()
                ? Map.of("kind", "column", "x", g.getX(), "z", g.getZ())
                : Map.of("kind", "position", "pos", cell(goal.pos()));
    }

    private static Map<String, Object> cell(Vec3 p) {
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
