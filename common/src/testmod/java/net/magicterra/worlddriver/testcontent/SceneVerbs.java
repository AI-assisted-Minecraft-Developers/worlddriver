package net.magicterra.worlddriver.testcontent;

import static net.magicterra.worlddriver.api.game.Positions.blockPos;
import static net.magicterra.worlddriver.api.game.Positions.position;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.game.Positions;
import net.magicterra.worlddriver.api.registry.ApiBatch;
import net.magicterra.worlddriver.api.registry.ApiHandlers;
import net.magicterra.worlddriver.api.registry.ApiMethod;
import net.magicterra.worlddriver.api.registry.ApiRegistry;
import net.magicterra.worlddriver.api.thread.ServerThreadHop;
import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.testcontent.SceneContracts.AcceptParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.Accepted;
import net.magicterra.worlddriver.testcontent.SceneContracts.LastVerdict;
import net.magicterra.worlddriver.testcontent.SceneContracts.ListParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.MarkParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.Marked;
import net.magicterra.worlddriver.testcontent.SceneContracts.PlaceParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.Placed;
import net.magicterra.worlddriver.testcontent.SceneContracts.Recorded;
import net.magicterra.worlddriver.testcontent.SceneContracts.RunParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.RunReply;
import net.magicterra.worlddriver.testcontent.SceneContracts.SaveParams;
import net.magicterra.worlddriver.testcontent.SceneContracts.Saved;
import net.magicterra.worlddriver.testcontent.SceneContracts.SceneList;
import net.magicterra.worlddriver.testcontent.SceneContracts.SceneRow;
import net.magicterra.worlddriver.testcontent.SceneContracts.VerdictParams;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The hand-built scene verbs — {@code worlddriver.mark}, {@code worlddriver.scene.save|list|place},
 * {@code worlddriver.scene.run|verdict|accept} — published as one batch under the testmod's own owner at
 * SERVER_STARTED, once the driver's API exists. Their contracts ({@link SceneContracts}) are hidden:
 * reachable on every transport, absent from the MCP tool list. Commands ({@link SceneCommands}) only turn
 * arguments into one {@code route} call.
 *
 * <p>Anything that touches the level hops to the server thread without holding the caller; a command,
 * already on the server thread, runs inline.
 */
public final class SceneVerbs {
    private SceneVerbs() {}

    /** Not a {@code mc.*} name, so the registry lets an owner other than the driver publish it. */
    static final String OWNER = "worlddriver-testmod";

    private static final long SERVER_THREAD_TIMEOUT_MS = Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L);

    private static volatile MinecraftServer server;

    /** Built once, so a second SERVER_STARTED in this JVM (another world) is recognised as a retry. */
    private static final ApiBatch BATCH = new ApiBatch(OWNER, "scenes", List.<ApiMethod<?, ?>>of(
            new ApiMethod<>(SceneContracts.MARK, SceneVerbs::mark),
            new ApiMethod<>(SceneContracts.SAVE, SceneVerbs::save),
            new ApiMethod<>(SceneContracts.LIST, ApiHandlers.immediate(SceneVerbs::list)),
            new ApiMethod<>(SceneContracts.PLACE, SceneVerbs::place),
            new ApiMethod<>(SceneContracts.RUN, SceneVerbs::run),
            new ApiMethod<>(SceneContracts.VERDICT, ApiHandlers.immediate(SceneVerbs::verdict)),
            new ApiMethod<>(SceneContracts.ACCEPT, ApiHandlers.immediate(SceneVerbs::accept))));

    /**
     * The fixture's JSON path, or a plain "no scene named …" for a name that was never saved —
     * the {@code NoSuchFileException} the load would throw carries only the path.
     */
    private static Path fixtureJson(String name) {
        Path json = FixtureIO.jsonPath(name);
        if (!Files.exists(json)) throw noScene(name);
        return json;
    }

    private static ApiException noScene(String name) {
        return ApiException.invalidParam("/name", ApiIssue.Code.INVALID_VALUE, "no scene named '" + name + "' ("
                + FixtureIO.jsonPath(name) + "); `scene list` shows the saved names, `here` runs the markers in the world");
    }

    private static ApiException io(String verb, IOException e) {
        return new ApiException(new ApiError(ApiError.Code.INTERNAL_ERROR, verb + ": " + e.getMessage(), List.of(),
                ApiError.Execution.UNKNOWN), e);
    }

    /** Subscribes the lifecycle, tick and command hooks. Called once from {@link MarkerContent#register()}. */
    static void install() {
        LifecycleEvent.SERVER_STARTED.register(s -> {
            server = s;
            publishVerbs();
        });
        LifecycleEvent.SERVER_STOPPING.register(s -> server = null);
        // The in-place runner has no harness to advance it; the testmod subscribes its own tick.
        TickEvent.SERVER_POST.register(FixtureRunner::tickInPlace);
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) -> SceneCommands.register(dispatcher));
    }

    private static void publishVerbs() {
        ApiRegistry api = WorldDriverCommon.registry();
        if (api == null) {
            WorldDriverCommon.LOG.warn("[{}] scene verbs not registered: the driver API is not up", WorldDriverCommon.MOD_ID);
            return;
        }
        if (api.registerBatch(BATCH)) {
            WorldDriverCommon.LOG.info("[{}] scene verbs registered: mark, scene.save|list|place|run|verdict|accept", WorldDriverCommon.MOD_ID);
        }
    }

    // ------------------------------------------------------------------ handlers

    static CompletionStage<Marked> mark(MarkParams p, CallContext ctx) {
        BlockPos pos = blockPos(p.pos());
        Map<String, Object> args = new LinkedHashMap<>();
        p.args().ifPresent(a -> {
            a.yaw().ifPresent(y -> args.put("yaw", y));
            a.box().ifPresent(b -> args.put("box", b));
        });
        return onServerThread(() -> {
            FixtureIO.put(overworld(), pos, p.role(), p.label(), args);
            return new Marked(true, p.role(), p.pos(), p.label().isEmpty() ? Optional.empty() : Optional.of(p.label()));
        }, ctx);
    }

    static CompletionStage<Saved> save(SaveParams p, CallContext ctx) {
        String name = p.name();
        return onServerThread(() -> {
            ServerLevel level = overworld();
            // A scene already anchored under this name is saved where it stands, wherever the caller is.
            BlockPos anchor = FixtureIO.anchorNamed(level, name);
            BlockPos centre = anchor != null ? anchor : p.around().map(Positions::blockPos).orElseGet(() -> firstPlayerFeet("around"));
            String who = p.author().filter(a -> !a.isEmpty()).orElseGet(SceneVerbs::firstPlayerName);
            try {
                SceneFixture fixture = FixtureIO.save(level, centre, name, who, p.verb(), p.budget());
                return new Saved(true, FixtureIO.jsonPath(name).toString(), FixtureIO.nbtPath(name).toString(),
                        json(fixture.toMap()));
            } catch (IOException e) {
                throw io("scene.save", e);
            }
        }, ctx);
    }

    static SceneList list(ListParams ignored) {
        Path dir = FixtureIO.scenesDir();
        List<SceneRow> scenes = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path json : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    String file = json.getFileName().toString();
                    SceneRow row;
                    try {
                        SceneFixture f = FixtureIO.loadJson(json);
                        row = new SceneRow(file, Optional.of(f.name()), Optional.of(List.of(f.size()[0], f.size()[1], f.size()[2])),
                                Optional.of(f.chunkRadius()), Optional.of(f.legs().size()),
                                Optional.of(Files.exists(FixtureIO.nbtPath(f.name()))),
                                latestVerdict(dir.resolve(f.verdicts())), Optional.empty());
                    } catch (RuntimeException | IOException e) {
                        row = new SceneRow(file, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                                Optional.empty(), Optional.empty(), Optional.of(String.valueOf(e.getMessage())));
                    }
                    scenes.add(row);
                }
            } catch (IOException e) {
                throw io("scene.list", e);
            }
        }
        return new SceneList(dir.toString(), scenes);
    }

    static CompletionStage<Placed> place(PlaceParams p, CallContext ctx) {
        String name = p.name();
        return onServerThread(() -> {
            ServerLevel level = overworld();
            try {
                SceneFixture fixture = FixtureIO.loadJson(fixtureJson(name));
                var template = FixtureIO.loadNbt(level, FixtureIO.nbtPath(name));
                // Back where the scene stands in this world when it is anchored here; else at `pos`, else the feet.
                BlockPos at = p.pos().map(Positions::blockPos).orElse(null);
                BlockPos anchored = at == null ? FixtureIO.locate(level, name, fixture) : null;
                BlockPos origin = at != null ? at : anchored != null ? anchored
                        : p.around().map(Positions::blockPos).orElseGet(() -> firstPlayerFeet("pos"));
                FixtureIO.placeTerrain(level, fixture, template, origin);
                FixtureIO.placeMarkers(level, fixture, origin);
                return new Placed(true, fixture.name(), position(origin), position(FixtureIO.minCorner(fixture, origin)),
                        List.of(fixture.size()[0], fixture.size()[1], fixture.size()[2]));
            } catch (IOException e) {
                throw io("scene.place", e);
            }
        }, ctx);
    }

    // ------------------------------------------------------------------ run / verdict / accept

    /** The latest run per scene name, so a judgement can copy its numbers without re-reading the file. */
    private static final Map<String, FixtureVerdicts.Record> LAST_RUNS = new ConcurrentHashMap<>();

    static CompletionStage<RunReply> run(RunParams p, CallContext ctx) {
        // Refused before anything starts: from the server thread the outcome could never arrive.
        if (p.awaitMs() > 0) ctx.requireBlockingAllowed("worlddriver.scene.run with awaitMs");
        String name = p.name();
        CompletableFuture<FixtureRunner.InPlace> started = onServerThread(() -> start(p), ctx);
        return started.thenCompose(ip -> {
            RunReply bare = new RunReply(true, name, position(ip.ctx.origin()), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty());
            long waitMs = Math.min(p.awaitMs(), ctx.remainingMillis());
            if (waitMs <= 0) return CompletableFuture.completedFuture(bare);
            // A dependent stage, so the timeout never completes the run's own future.
            return ip.done.thenApply(o -> new RunReply(true, name, bare.origin(), Optional.of(o.status()),
                            o.reason().isEmpty() ? Optional.empty() : Optional.of(o.reason()),
                            Optional.of(json(o.auto())), Optional.of(json(o.observed())), Optional.of(o.lines())))
                    .completeOnTimeout(new RunReply(true, name, bare.origin(), Optional.of("running"), Optional.empty(),
                            Optional.empty(), Optional.empty(), Optional.empty()), waitMs, TimeUnit.MILLISECONDS);
        });
    }

    /** On the server thread: resolve where the scene stands, start it, and hook its verdict line. */
    private static FixtureRunner.InPlace start(RunParams p) {
        String name = p.name();
        boolean here = name.equals("here");
        ServerLevel level = overworld();
        MinecraftServer s = server;
        ServerPlayer caller = s.getPlayerList().getPlayers().isEmpty() ? null : s.getPlayerList().getPlayers().get(0);
        String who = firstPlayerName();
        BlockPos at = p.pos().map(Positions::blockPos).orElse(null);
        BlockPos around = p.around().map(Positions::blockPos).orElse(null);
        SceneFixture saved = null;
        if (!here && Files.exists(FixtureIO.jsonPath(name))) {
            try {
                saved = FixtureIO.loadJson(FixtureIO.jsonPath(name));
            } catch (IOException e) {
                throw io("scene.run", e);
            }
        }
        // Where the scene stands in this world: for `here` the box around the caller; for a
        // name its anchor marker, when loaded or loadable from where the file says it was
        // saved. Either way the markers are the truth for positions, the terrain runs as is,
        // and the file adds what markers cannot say. A name with no anchor in this world is
        // the file's terrain placed at an explicit `pos` and run there, else refused.
        BlockPos inWorld = here
                ? (around != null ? around : at != null ? at : firstPlayerFeet("around"))
                : at != null ? null : FixtureIO.locate(level, name, saved);
        final SceneFixture fixture;
        final BlockPos origin;
        if (inWorld != null) {
            List<FixtureBuilder.Placed> markers = FixtureIO.sceneAround(level, inWorld);
            boolean fileLegs = saved != null && !saved.legs().isEmpty();
            String legVerb = p.verb().orElse(fileLegs ? saved.legs().get(0).verb() : "goto");
            int legBudget = p.budget().orElse(fileLegs ? saved.legs().get(0).budget() : 1200);
            SceneFixture live = FixtureBuilder.fixture(name, who, OffsetDateTime.now().toString(), markers, legVerb, legBudget,
                    pos -> BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock()).toString());
            fixture = saved != null ? live.withFileOf(saved) : live;
            origin = FixtureBuilder.origin(markers, FixtureBuilder.box(markers));
        } else {
            if (saved == null) throw noScene(name);
            // Never the caller's feet: a run whose anchor had gone missing once dumped its
            // terrain over the neighbouring scene. Writing terrain is `place`'s job, or an
            // explicit `pos` here.
            if (at == null) {
                throw ApiException.invalidParam("/pos", ApiIssue.Code.REQUIRED, "scene '" + name
                        + "' has no anchor marker in this world (none loaded, none at its saved position); `scene place "
                        + name + "` puts it back, or pass `pos` to place its terrain there for this run");
            }
            try {
                var template = FixtureIO.loadNbt(level, FixtureIO.nbtPath(name));
                origin = at;
                FixtureIO.placeTerrain(level, saved, template, origin);
                fixture = saved;
            } catch (IOException e) {
                throw io("scene.run", e);
            }
        }
        Consumer<String> progress = p.watch() && caller != null
                ? line -> caller.sendSystemMessage(Component.literal("[scene " + name + "] " + line)) : null;
        FixtureRunner.InPlace started = FixtureRunner.startInPlace(level, name, fixture, origin, p.body().orElse(null), progress);
        String topology = s.isDedicatedServer() ? "dedicatedServer" : "integratedServer";
        // Resolution happens on the server thread (the tick hook), so this runs there too.
        started.done.thenAccept(out -> {
            FixtureVerdicts.Record rec = new FixtureVerdicts.Record(OffsetDateTime.now().toString(), who,
                    System.getProperty("worlddriver.build", "dev"), topology, out.auto(), out.observed(), null,
                    out.status() + (out.reason().isEmpty() ? "" : ": " + out.reason()));
            LAST_RUNS.put(name, rec);
            try {
                FixtureVerdicts.append(FixtureIO.scenesDir().resolve(fixture.verdicts()), rec);
            } catch (IOException e) {
                WorldDriverCommon.LOG.warn("[scene.run] could not append {}: {}", fixture.verdicts(), e.getMessage());
            }
            if (caller != null) SceneCommands.report(caller, name, out);
        });
        return started;
    }

    static Recorded verdict(VerdictParams p) {
        String name = p.name();
        Path file = verdictsFile(name);
        try {
            FixtureVerdicts.Record base = LAST_RUNS.get(name);
            if (base == null) base = FixtureVerdicts.latest(FixtureVerdicts.read(file));
            if (base == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "scene.verdict: '" + name + "' has no run to judge yet");
            FixtureVerdicts.Record rec = base.judged(OffsetDateTime.now().toString(), firstPlayerName(), p.verdict(),
                    p.note().map(String::trim).orElse(""));
            FixtureVerdicts.append(file, rec);
            return new Recorded(true, file.toString(), json(rec.toMap()));
        } catch (IOException e) {
            throw io("scene.verdict", e);
        }
    }

    static Accepted accept(AcceptParams p) {
        String name = p.name();
        if (name.equals("here")) {
            throw ApiException.invalidParam("/name", ApiIssue.Code.INVALID_VALUE, "scene.accept: save the scene first; `here` has no fixture file");
        }
        try {
            SceneFixture f = FixtureIO.loadJson(fixtureJson(name));
            Map<String, Object> expect = FixtureVerdicts.acceptFrom(FixtureVerdicts.read(FixtureIO.scenesDir().resolve(f.verdicts())));
            if (expect == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "scene.accept: no run of '" + name + "' has been judged pass");
            SceneFixture accepted = new SceneFixture(f.name(), f.author(), f.created(), f.terrain(), f.size(), f.origin(),
                    f.placedAt(), f.chunkRadius(), f.body(), f.hand(), f.equip(), f.config(), f.legs(), f.markers(), expect, f.verdicts());
            Files.writeString(FixtureIO.jsonPath(name), accepted.toJson() + "\n", StandardCharsets.UTF_8);
            return new Accepted(true, FixtureIO.jsonPath(name).toString(), json(expect));
        } catch (IOException e) {
            throw io("scene.accept", e);
        }
    }

    private static Path verdictsFile(String name) {
        if (name.equals("here")) return FixtureIO.scenesDir().resolve(SceneFixture.verdictsFileFor("here"));
        try {
            return FixtureIO.scenesDir().resolve(FixtureIO.loadJson(fixtureJson(name)).verdicts());
        } catch (IOException | RuntimeException e) {
            return FixtureIO.scenesDir().resolve(SceneFixture.verdictsFileFor(name));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The newest judged line of a verdicts file; empty when there is none. */
    static Optional<LastVerdict> latestVerdict(Path verdicts) {
        try {
            FixtureVerdicts.Record r = FixtureVerdicts.latestJudged(FixtureVerdicts.read(verdicts));
            if (r == null || r.human() == null) return Optional.empty();
            return Optional.of(new LastVerdict(Optional.ofNullable(r.when()), Optional.ofNullable(r.who()), r.human(),
                    Optional.ofNullable(r.note())));
        } catch (IOException | RuntimeException ignored) {
            // an unreadable verdict file is reported as none; the file is a human's, not the gate's
            return Optional.empty();
        }
    }

    /** File content as an open JSON object. Its null entries are dropped: an absent value is an omitted key. */
    static JsonValue json(Object tree) {
        return JsonValue.of(withoutNulls(tree));
    }

    private static Object withoutNulls(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) out.put(String.valueOf(e.getKey()), withoutNulls(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object e : l) if (e != null) out.add(withoutNulls(e));
            return out;
        }
        return v;
    }

    static Map<String, Object> posMap(BlockPos p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", p.getX());
        m.put("y", p.getY());
        m.put("z", p.getZ());
        return m;
    }


    private static ServerLevel overworld() {
        MinecraftServer s = server;
        if (s == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no server is running");
        return s.overworld();
    }

    private static BlockPos firstPlayerFeet(String param) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw ApiException.invalidParam("/" + param, ApiIssue.Code.REQUIRED,
                    "'" + param + "' is required when no player is on the server");
        }
        return players.get(0).blockPosition();
    }

    private static String firstPlayerName() {
        MinecraftServer s = server;
        List<ServerPlayer> players = s == null ? List.of() : s.getPlayerList().getPlayers();
        return players.isEmpty() ? "rpc" : players.get(0).getGameProfile().getName();
    }

    private static <T> CompletableFuture<T> onServerThread(Supplier<T> task, CallContext ctx) {
        MinecraftServer s = server;
        if (s == null) return CompletableFuture.failedFuture(ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "no server is running"));
        return new ServerThreadHop(s, s::isSameThread, SERVER_THREAD_TIMEOUT_MS).submit(task, ctx);
    }
}
