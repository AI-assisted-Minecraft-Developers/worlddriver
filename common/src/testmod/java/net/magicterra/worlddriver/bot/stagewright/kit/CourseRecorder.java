package net.magicterra.worlddriver.bot.stagewright.kit;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.event.EventBlockingApi;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * A run of a benchmark course, recorded as {@code scripts/bench/bench.py} records one: the real client's player read
 * once per sample through {@code mc.client.player} (the client's own physics, which the server's copy of the player
 * does not report: {@code horizontalCollision} above all), and the blocks placed and broken read from the server's
 * event ring every half second. {@link CourseErrors} scores the samples.
 *
 * <p>Time is the server's tick count from {@link #start}, not the number of samples or the wall clock: a sample is a
 * hop to the client and one to the server, and a slow hop must not stretch the run. The bench timed its runs by the
 * wall clock on a client whose integrated server held 20 ticks a second, so a second of the bench is twenty ticks here.
 */
public final class CourseRecorder {

    /** Ticks in a second of the bench's clock. */
    public static final int TICKS_PER_SECOND = 20;

    /** The bench read the block events every half second, and gave them to the sample that read them. */
    private static final int EVENT_EVERY = TICKS_PER_SECOND / 2;

    /** The page size {@code SinceParams.after} asks for. */
    private static final int EVENT_PAGE = 256;

    /** Half the body's height gliding and upright: its middle above the feet. */
    private static final double FLY_HALF_HEIGHT = 0.3;

    private static final double STAND_HALF_HEIGHT = 0.9;

    /** One block placed or broken: the event's type, where, and the block id its payload names. */
    public record BlockEvent(String type, BlockPos pos, String block) {
        /** The payload's block id without a state suffix. */
        public String blockId() {
            int bracket = block.indexOf('[');
            return bracket < 0 ? block : block.substring(0, bracket);
        }
    }

    /**
     * The client's player at one sample: {@code tick} after {@link #start}, the feet, the client's body flags and
     * health, whether it glides ({@code FALL_FLYING}), the client's frame rate, the checkpoint that was next when it
     * was taken, the block events read with it, and on a combat course the wave's living mobs and those within reach
     * (-1 elsewhere).
     */
    public record Sample(
            int tick,
            double x,
            double y,
            double z,
            boolean onGround,
            boolean inWater,
            boolean collided,
            double health,
            boolean flying,
            int fps,
            int checkpoint,
            List<BlockEvent> events,
            int alive,
            int near) {

        /** {@link #tick} in the bench's seconds. */
        public double seconds() {
            return (double) tick / TICKS_PER_SECOND;
        }

        /** This sample with {@code more} events after its own. */
        Sample withEvents(List<BlockEvent> more) {
            List<BlockEvent> all = new ArrayList<>(events);
            all.addAll(more);
            return new Sample(
                    tick,
                    x,
                    y,
                    z,
                    onGround,
                    inWater,
                    collided,
                    health,
                    flying,
                    fps,
                    checkpoint,
                    List.copyOf(all),
                    alive,
                    near);
        }

        /** The middle of the body: a gliding body is 0.6 tall, an upright one 1.8. */
        public Vec3 centre() {
            return new Vec3(x, y + (flying ? FLY_HALF_HEIGHT : STAND_HALF_HEIGHT), z);
        }
    }

    private final Game game;

    private final ClientBlockingApi player;

    private final EventBlockingApi events;

    private final List<Sample> samples = new ArrayList<>();

    private long startTick;

    private long cursor;

    private int nextEvents;

    /** A recorder of the real client's player in {@code game}; nothing is read until {@link #start}. */
    public CourseRecorder(Game game, Client client) {
        this.game = game;
        this.player = client.api(ClientBlockingApi::of);
        this.events = game.api(EventBlockingApi::of);
    }

    /** Start the clock now, the bench's "Go!", and read the block events from here on. */
    public void start() {
        cursor = events.cursor(new EventContracts.CursorParams()).cursor();
        startTick = serverTick();
        nextEvents = 0;
    }

    /** Ticks since {@link #start}. */
    public int elapsed() {
        return (int) (serverTick() - startTick);
    }

    /**
     * Read the client's player once, with {@code checkpoint} the one next before this sample; null while the client has
     * no player in a world. The block events since the last read come with it every half second.
     */
    public Sample sample(int checkpoint) {
        return sample(checkpoint, -1, -1);
    }

    /** {@link #sample(int)} on a combat course, with the wave's living mobs and those in reach. */
    public Sample sample(int checkpoint, int alive, int near) {
        ClientContracts.ClientPlayer p = player.player(new NoParams());
        int tick = elapsed();
        if (!p.present() || p.pos().isEmpty() || p.body().isEmpty()) {
            return null;
        }
        List<BlockEvent> evs = List.of();
        if (tick >= nextEvents) {
            evs = readEvents();
            nextEvents = tick + EVENT_EVERY;
        }
        ClientContracts.ClientBody body = p.body().get();
        Sample s = new Sample(
                tick,
                p.pos().get().x(),
                p.pos().get().y(),
                p.pos().get().z(),
                body.onGround(),
                body.inWater(),
                body.horizontalCollision(),
                p.vitals().map(ClientContracts.Vitals::health).orElse(Double.NaN),
                "FALL_FLYING".equals(body.pose()),
                p.fps(),
                checkpoint,
                evs,
                alive,
                near);
        samples.add(s);
        return s;
    }

    /**
     * Read the block events once more after the last sample and give them to it: the run ends on a sample, and what was
     * placed or broken since the last half-second read belongs to it.
     */
    public void finish() {
        List<BlockEvent> late = readEvents();
        if (late.isEmpty() || samples.isEmpty()) {
            return;
        }
        samples.add(samples.removeLast().withEvents(late));
    }

    /** Every sample so far, oldest first. */
    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /**
     * Write the run as a bench.py recording, so that {@code bench.py --out <dir> report <course>} reads it beside the
     * bench's own: a meta line ({@code meta} gives the course's fields), one line per sample, and the end line. The
     * file goes to {@code bench/<course>/runs/} under the game's working directory, where bench.py's default output
     * would put it. Answers the file written.
     */
    public Path write(String course, Map<String, Object> meta, String end, int ticks) {
        Map<String, Object> head = new LinkedHashMap<>(meta);
        head.put("meta", true);
        head.put("course", course);
        head.put("label", "scene");
        head.put("driver", "bot");
        head.put("hz", TICKS_PER_SECOND);
        final Gson gson = new Gson();
        final List<String> lines = new ArrayList<>();
        lines.add(gson.toJson(head));
        for (Sample s : samples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", s.seconds());
            row.put("x", s.x());
            row.put("y", s.y());
            row.put("z", s.z());
            row.put("g", s.onGround());
            row.put("w", s.inWater());
            row.put("hc", s.collided());
            row.put("hp", s.health());
            row.put("cp", s.checkpoint());
            row.put("fps", s.fps());
            row.put("fly", s.flying());
            if (s.alive() >= 0) {
                row.put("n", s.alive());
                row.put("near", s.near());
            }
            if (!s.events().isEmpty()) {
                row.put(
                        "ev",
                        s.events().stream()
                                .map(e -> List.of(
                                        e.type(),
                                        Map.of(
                                                "x", e.pos().getX(),
                                                "y", e.pos().getY(),
                                                "z", e.pos().getZ()),
                                        e.block()))
                                .toList());
            }
            lines.add(gson.toJson(row));
        }
        Map<String, Object> tail = new LinkedHashMap<>();
        tail.put("end", end);
        tail.put("t", (double) ticks / TICKS_PER_SECOND);
        lines.add(gson.toJson(tail));
        Path dir = Path.of("bench", course, "runs");
        Path file = dir.resolve(String.format(
                Locale.ROOT, "%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS-bot-scene.jsonl", System.currentTimeMillis()));
        try {
            Files.createDirectories(dir);
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the recording " + file.toAbsolutePath(), e);
        }
        return file.toAbsolutePath();
    }

    private List<BlockEvent> readEvents() {
        List<BlockEvent> out = new ArrayList<>();
        while (true) {
            EventContracts.Page page =
                    events.eventsSince(EventContracts.SinceParams.after(cursor, "block.place", "block.break"));
            for (EventContracts.Event e : page.events()) {
                if (e.pos().isEmpty()) {
                    continue;
                }
                String block = e.data()
                        .map(d -> d.json().isJsonPrimitive()
                                ? d.json().getAsString()
                                : d.json().toString())
                        .orElse("");
                out.add(new BlockEvent(
                        e.type(),
                        new BlockPos(
                                e.pos().get().x(),
                                e.pos().get().y(),
                                e.pos().get().z()),
                        block));
            }
            cursor = page.cursor();
            // A full page may have more behind it.
            if (page.events().size() < EVENT_PAGE) {
                return out;
            }
        }
    }

    private long serverTick() {
        return game.onServer(MinecraftServer::getTickCount);
    }
}
