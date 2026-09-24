package net.magicterra.worlddriver.testcontent;

import static net.magicterra.worlddriver.mcp.schema.Schemas.array;
import static net.magicterra.worlddriver.mcp.schema.Schemas.bool;
import static net.magicterra.worlddriver.mcp.schema.Schemas.integer;
import static net.magicterra.worlddriver.mcp.schema.Schemas.number;
import static net.magicterra.worlddriver.mcp.schema.Schemas.object;
import static net.magicterra.worlddriver.mcp.schema.Schemas.pos;
import static net.magicterra.worlddriver.mcp.schema.Schemas.string;
import static net.magicterra.worlddriver.mcp.schema.Schemas.stringEnum;
import static net.magicterra.worlddriver.mcp.schema.Schemas.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import net.magicterra.worlddriver.WorldDriverCommon;
import net.magicterra.worlddriver.api.ServerThreadHop;
import net.magicterra.worlddriver.mcp.ToolCatalog;
import net.magicterra.worlddriver.mcp.schema.ToolSchema;
import net.magicterra.worlddriver.model.Params;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The hand-built scene verbs — {@code worlddriver.mark}, {@code worlddriver.scene.save|list|place},
 * {@code worlddriver.scene.run|verdict|accept} — registered into the driver's
 * {@link ToolCatalog} at SERVER_STARTED, after the route sink exists, the way StageWright's
 * {@code mc.test.run} is. Hidden schemas: reachable on every transport, absent from the MCP tool
 * list. Commands ({@link SceneCommands}) only turn arguments into one {@code route} call.
 *
 * <p>Handlers run on the transport's thread and hop to the server thread for anything that touches
 * the level, mirroring {@code DriverApi.onServerThread}; a command, already on the server thread,
 * runs inline.
 */
public final class SceneVerbs {
    private SceneVerbs() {}

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]*");
    private static final long SERVER_THREAD_TIMEOUT_MS = Long.getLong("worlddriver.serverThreadTimeoutMs", 8_000L);

    private static volatile MinecraftServer server;
    private static volatile boolean registered;

    private static String[] roleNames() {
        MarkerRole[] roles = MarkerRole.values();
        String[] out = new String[roles.length];
        for (int i = 0; i < roles.length; i++) out[i] = roles[i].getSerializedName();
        return out;
    }

    /**
     * The fixture's JSON path, or a plain "no scene named …" for a name that was never saved —
     * the {@code NoSuchFileException} the load would throw carries only the path.
     */
    private static Path fixtureJson(String name) {
        Path json = FixtureIO.jsonPath(name);
        if (!Files.exists(json)) throw noScene(name);
        return json;
    }

    private static IllegalArgumentException noScene(String name) {
        return new IllegalArgumentException("no scene named '" + name + "' (" + FixtureIO.jsonPath(name)
                + "); `scene list` shows the saved names, `here` runs the markers in the world");
    }

    public static final ToolSchema MARK = tool("worlddriver.mark",
            "Place a scene marker block (testmod verb). role picks what it means; label goes into the "
            + "block entity (goal: block|near:<r>|y:, via/pass: a number, watch: same|<block id>). A marker "
            + "put into a water or lava source keeps it; one put onto a marker rewrites that marker.",
            object().additionalProperties(false)
                    .req("role", stringEnum(roleNames()))
                    .req("pos", pos())
                    .prop("label", string())
                    .prop("args", object()
                            .prop("yaw", number().desc("the bot's facing, on start"))
                            .prop("box", array(integer()).desc("on an anchor (origin, or start): the scene's box as six "
                                    + "offsets from the marker, min corner then max; all zero clears it"))
                            .desc("entity args"))).asHidden();

    public static final ToolSchema SAVE = tool("worlddriver.scene.save",
            "Save the scene the markers around `around` describe: terrain between the two corner markers "
            + "to config/worlddriver/scenes/<name>.nbt, the fixture to <name>.json. Markers stay in the world.",
            object().additionalProperties(false)
                    .req("name", string())
                    .prop("verb", stringEnum("goto", "mine", "escape", "elytra"))
                    .prop("budget", integer(1, 100_000))
                    .prop("around", pos().desc("scan centre; defaults to the first player's position"))
                    .prop("author", string())).asHidden();

    public static final ToolSchema LIST = tool("worlddriver.scene.list",
            "List the scene fixtures under config/worlddriver/scenes with each one's latest human verdict.",
            object().additionalProperties(false)).asHidden();

    public static final ToolSchema PLACE = tool("worlddriver.scene.place",
            "Put a saved scene's terrain and markers back into the world (a reset) with its origin cell "
            + "at the anchor marker labelled with its name when this world has one, else at `pos`, else "
            + "at `around` / the first player's feet.",
            object().additionalProperties(false)
                    .req("name", string())
                    .prop("pos", pos())
                    .prop("around", pos())).asHidden();

    public static final ToolSchema RUN = tool("worlddriver.scene.run",
            "Run a scene in place. `here`: the corner box around `around` (default the first player's feet), "
            + "markers as the truth, terrain as is. A name: the same at the anchor marker labelled with that "
            + "name when this world has one (the file adds hand/equip/config/expect and the walks' verb and "
            + "budget); otherwise the saved terrain is placed at `pos` and run there (no `pos`: refused — "
            + "`scene.place` first). body: which bot runs the scene: "
            + "server (headless, dedicated server), self (the real player, integrated server), or npc / "
            + "npc:<name> (a driven piglin, any topology, no inventory); default: the file's npc when it names "
            + "one, else by topology. watch: a progress line to the first player every 20 ticks. awaitMs: wait "
            + "for the outcome (not from the server thread).",
            object().additionalProperties(false)
                    .req("name", string())
                    .prop("body", string())
                    .prop("watch", bool())
                    .prop("pos", pos())
                    .prop("around", pos())
                    .prop("verb", stringEnum("goto", "mine", "escape", "elytra").desc("for `here`: the verb of the walks to the goals"))
                    .prop("budget", integer(1, 100_000).desc("for `here`: each walk's tick budget"))
                    .prop("awaitMs", integer(0, 3_600_000))).asHidden();

    public static final ToolSchema VERDICT = tool("worlddriver.scene.verdict",
            "Record a human judgement of the scene's latest run into <name>.verdicts.jsonl.",
            object().additionalProperties(false)
                    .req("name", string())
                    .req("verdict", stringEnum("pass", "fail", "flaky"))
                    .prop("note", string())).asHidden();

    public static final ToolSchema ACCEPT = tool("worlddriver.scene.accept",
            "Write the numbers of the latest run judged `pass` (plus 20%) into the fixture's `expect`, "
            + "the bounds the suite will hold it to.",
            object().additionalProperties(false)
                    .req("name", string())).asHidden();

    /** Subscribes the lifecycle, tick and command hooks. Called once from {@link MarkerContent#register()}. */
    static void install() {
        LifecycleEvent.SERVER_STARTED.register(s -> {
            server = s;
            registerVerbs();
        });
        LifecycleEvent.SERVER_STOPPING.register(s -> server = null);
        // The in-place runner has no harness to advance it; the testmod subscribes its own tick.
        TickEvent.SERVER_POST.register(FixtureRunner::tickInPlace);
        CommandRegistrationEvent.EVENT.register((dispatcher, context, selection) -> SceneCommands.register(dispatcher));
    }

    private static synchronized void registerVerbs() {
        if (registered) return;
        registered = true;
        ToolCatalog.registerVerb(MARK, SceneVerbs::mark);
        ToolCatalog.registerVerb(SAVE, SceneVerbs::save);
        ToolCatalog.registerVerb(LIST, SceneVerbs::list);
        ToolCatalog.registerVerb(PLACE, SceneVerbs::place);
        ToolCatalog.registerVerb(RUN, SceneVerbs::run);
        ToolCatalog.registerVerb(VERDICT, SceneVerbs::verdict);
        ToolCatalog.registerVerb(ACCEPT, SceneVerbs::accept);
        WorldDriverCommon.LOG.info("[{}] scene verbs registered: mark, scene.save|list|place|run|verdict|accept", WorldDriverCommon.MOD_ID);
    }

    // ------------------------------------------------------------------ handlers

    static Object mark(Map<String, Object> raw) {
        Params p = Params.of(raw);
        MarkerRole role = MarkerRole.valueOf(p.getNonBlank("role").toUpperCase(java.util.Locale.ROOT));
        BlockPos pos = p.getPos("pos");
        String label = p.getString("label", "");
        Map<String, Object> args = p.getMap("args");
        return onServerThread(() -> {
            ServerLevel level = overworld();
            FixtureIO.put(level, pos, role, label, args);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("placed", true);
            out.put("role", role.getSerializedName());
            out.put("pos", posMap(pos));
            if (!label.isEmpty()) out.put("label", label);
            return out;
        });
    }

    static Object save(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String name = sceneName(p.getNonBlank("name"));
        String verb = p.getString("verb", "goto");
        int budget = p.getInt("budget", 1200);
        String author = p.getString("author", "");
        BlockPos around = p.getPos("around");
        return onServerThread(() -> {
            ServerLevel level = overworld();
            // A scene already anchored under this name is saved where it stands, wherever the caller is.
            BlockPos anchor = FixtureIO.anchorNamed(level, name);
            BlockPos centre = anchor != null ? anchor : around != null ? around : firstPlayerFeet("around");
            String who = author.isEmpty() ? firstPlayerName() : author;
            try {
                SceneFixture fixture = FixtureIO.save(level, centre, name, who, verb, budget);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("saved", true);
                out.put("json", FixtureIO.jsonPath(name).toString());
                out.put("nbt", FixtureIO.nbtPath(name).toString());
                out.put("fixture", fixture.toMap());
                return out;
            } catch (IOException e) {
                throw new IllegalStateException("scene.save: " + e.getMessage(), e);
            }
        });
    }

    static Object list(Map<String, Object> raw) {
        Path dir = FixtureIO.scenesDir();
        List<Object> scenes = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path json : files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("file", json.getFileName().toString());
                    try {
                        SceneFixture f = FixtureIO.loadJson(json);
                        row.put("name", f.name());
                        row.put("size", List.of(f.size()[0], f.size()[1], f.size()[2]));
                        row.put("chunkRadius", f.chunkRadius());
                        row.put("legs", f.legs().size());
                        row.put("hasNbt", Files.exists(FixtureIO.nbtPath(f.name())));
                        Map<String, Object> verdict = latestVerdict(dir.resolve(f.verdicts()));
                        if (verdict != null) row.put("lastVerdict", verdict);
                    } catch (RuntimeException | IOException e) {
                        row.put("error", String.valueOf(e.getMessage()));
                    }
                    scenes.add(row);
                }
            } catch (IOException e) {
                throw new IllegalStateException("scene.list: " + e.getMessage(), e);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dir", dir.toString());
        out.put("scenes", scenes);
        return out;
    }

    static Object place(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String name = sceneName(p.getNonBlank("name"));
        BlockPos at = p.getPos("pos");
        BlockPos around = p.getPos("around");
        return onServerThread(() -> {
            ServerLevel level = overworld();
            try {
                SceneFixture fixture = FixtureIO.loadJson(fixtureJson(name));
                var template = FixtureIO.loadNbt(level, FixtureIO.nbtPath(name));
                // Back where the scene stands in this world when it is anchored here; else at `pos`, else the feet.
                BlockPos anchored = at == null ? FixtureIO.locate(level, name, fixture) : null;
                BlockPos origin = at != null ? at : anchored != null ? anchored : around != null ? around : firstPlayerFeet("pos");
                FixtureIO.placeTerrain(level, fixture, template, origin);
                FixtureIO.placeMarkers(level, fixture, origin);
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("placed", true);
                out.put("name", fixture.name());
                out.put("origin", posMap(origin));
                out.put("min", posMap(FixtureIO.minCorner(fixture, origin)));
                out.put("size", List.of(fixture.size()[0], fixture.size()[1], fixture.size()[2]));
                return out;
            } catch (IOException e) {
                throw new IllegalStateException("scene.place: " + e.getMessage(), e);
            }
        });
    }

    // ------------------------------------------------------------------ run / verdict / accept

    /** The latest run per scene name, so a judgement can copy its numbers without re-reading the file. */
    private static final Map<String, FixtureVerdicts.Record> LAST_RUNS = new java.util.concurrent.ConcurrentHashMap<>();

    static Object run(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String name = sceneName(p.getNonBlank("name"));
        String body = p.getString("body");
        boolean watch = p.getBool("watch");
        BlockPos at = p.getPos("pos");
        BlockPos around = p.getPos("around");
        String verb = p.getString("verb", "goto");
        int budget = p.getInt("budget", 1200);
        int awaitMs = p.getInt("awaitMs", 0);
        FixtureRunner.InPlace ip = onServerThread(() -> {
            ServerLevel level = overworld();
            ServerPlayer caller = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            String who = firstPlayerName();
            SceneFixture saved = null;
            if (!name.equals("here") && Files.exists(FixtureIO.jsonPath(name))) {
                try {
                    saved = FixtureIO.loadJson(FixtureIO.jsonPath(name));
                } catch (IOException e) {
                    throw new IllegalStateException("scene.run: " + e.getMessage(), e);
                }
            }
            // Where the scene stands in this world: for `here` the box around the caller; for a
            // name its anchor marker, when loaded or loadable from where the file says it was
            // saved. Either way the markers are the truth for positions, the terrain runs as is,
            // and the file adds what markers cannot say. A name with no anchor in this world is
            // the file's terrain placed at an explicit `pos` and run there, else refused.
            BlockPos inWorld = name.equals("here")
                    ? (around != null ? around : at != null ? at : firstPlayerFeet("around"))
                    : at != null ? null : FixtureIO.locate(level, name, saved);
            final SceneFixture fixture;
            final BlockPos origin;
            if (inWorld != null) {
                List<FixtureBuilder.Placed> markers = FixtureIO.sceneAround(level, inWorld);
                boolean fileLegs = saved != null && !saved.legs().isEmpty();
                String legVerb = fileLegs && !raw.containsKey("verb") ? saved.legs().get(0).verb() : verb;
                int legBudget = fileLegs && !raw.containsKey("budget") ? saved.legs().get(0).budget() : budget;
                SceneFixture live = FixtureBuilder.fixture(name, who, java.time.OffsetDateTime.now().toString(), markers,
                        legVerb, legBudget,
                        pos -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos.below()).getBlock()).toString());
                fixture = saved != null ? live.withFileOf(saved) : live;
                origin = FixtureBuilder.origin(markers, FixtureBuilder.box(markers));
            } else {
                if (saved == null) throw noScene(name);
                // Never the caller's feet: a run whose anchor had gone missing once dumped its
                // terrain over the neighbouring scene. Writing terrain is `place`'s job, or an
                // explicit `pos` here.
                if (at == null) {
                    throw new IllegalArgumentException("scene '" + name + "' has no anchor marker in this world"
                            + " (none loaded, none at its saved position); `scene place " + name
                            + "` puts it back, or pass `pos` to place its terrain there for this run");
                }
                try {
                    var template = FixtureIO.loadNbt(level, FixtureIO.nbtPath(name));
                    origin = at;
                    FixtureIO.placeTerrain(level, saved, template, origin);
                    fixture = saved;
                } catch (IOException e) {
                    throw new IllegalStateException("scene.run: " + e.getMessage(), e);
                }
            }
            java.util.function.Consumer<String> progress = watch && caller != null
                    ? s -> caller.sendSystemMessage(Component.literal("[scene " + name + "] " + s)) : null;
            FixtureRunner.InPlace started = FixtureRunner.startInPlace(level, name, fixture, origin, body, progress);
            String topology = server.isDedicatedServer() ? "dedicatedServer" : "integratedServer";
            // Resolution happens on the server thread (the tick hook), so this runs there too.
            started.done.thenAccept(out -> {
                FixtureVerdicts.Record rec = new FixtureVerdicts.Record(java.time.OffsetDateTime.now().toString(), who,
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
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("started", true);
        out.put("name", name);
        out.put("origin", posMap(ip.ctx.origin()));
        MinecraftServer s = server;
        if (awaitMs > 0 && s != null && !s.isSameThread()) {
            try {
                FixtureRunner.Outcome o = ip.done.get(awaitMs, TimeUnit.MILLISECONDS);
                out.put("status", o.status());
                if (!o.reason().isEmpty()) out.put("reason", o.reason());
                out.put("auto", o.auto());
                out.put("observed", o.observed());
                out.put("lines", o.lines());
            } catch (TimeoutException e) {
                out.put("status", "running");
            } catch (ExecutionException | InterruptedException e) {
                throw new IllegalStateException("scene.run: " + e.getMessage(), e);
            }
        }
        return out;
    }

    static Object verdict(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String name = sceneName(p.getNonBlank("name"));
        String verdict = p.getNonBlank("verdict").toLowerCase(java.util.Locale.ROOT);
        if (!verdict.equals("pass") && !verdict.equals("fail") && !verdict.equals("flaky"))
            throw new IllegalArgumentException("verdict must be pass, fail or flaky");
        String note = p.getString("note", "");
        Path file = verdictsFile(name);
        try {
            FixtureVerdicts.Record base = LAST_RUNS.get(name);
            if (base == null) base = FixtureVerdicts.latest(FixtureVerdicts.read(file));
            if (base == null) throw new IllegalStateException("scene.verdict: '" + name + "' has no run to judge yet");
            FixtureVerdicts.Record rec = base.judged(java.time.OffsetDateTime.now().toString(), firstPlayerName(), verdict, note);
            FixtureVerdicts.append(file, rec);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("recorded", true);
            out.put("file", file.toString());
            out.put("record", rec.toMap());
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("scene.verdict: " + e.getMessage(), e);
        }
    }

    static Object accept(Map<String, Object> raw) {
        Params p = Params.of(raw);
        String name = sceneName(p.getNonBlank("name"));
        if (name.equals("here")) throw new IllegalArgumentException("scene.accept: save the scene first; `here` has no fixture file");
        try {
            SceneFixture f = FixtureIO.loadJson(fixtureJson(name));
            Map<String, Object> expect = FixtureVerdicts.acceptFrom(FixtureVerdicts.read(FixtureIO.scenesDir().resolve(f.verdicts())));
            if (expect == null) throw new IllegalStateException("scene.accept: no run of '" + name + "' has been judged pass");
            SceneFixture accepted = new SceneFixture(f.name(), f.author(), f.created(), f.terrain(), f.size(), f.origin(),
                    f.placedAt(), f.chunkRadius(), f.body(), f.hand(), f.equip(), f.config(), f.legs(), f.markers(), expect, f.verdicts());
            Files.writeString(FixtureIO.jsonPath(name), accepted.toJson() + "\n", StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("accepted", true);
            out.put("json", FixtureIO.jsonPath(name).toString());
            out.put("expect", expect);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("scene.accept: " + e.getMessage(), e);
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

    /** The newest judged line of a verdicts file, as {when, who, human, note}; null when there is none. */
    static Map<String, Object> latestVerdict(Path verdicts) {
        try {
            FixtureVerdicts.Record r = FixtureVerdicts.latestJudged(FixtureVerdicts.read(verdicts));
            if (r == null) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("when", r.when());
            out.put("who", r.who());
            out.put("human", r.human());
            if (r.note() != null) out.put("note", r.note());
            return out;
        } catch (IOException | RuntimeException ignored) {
            // an unreadable verdict file is reported as none; the file is a human's, not the gate's
            return null;
        }
    }

    static String sceneName(String name) {
        if (!NAME.matcher(name).matches())
            throw new IllegalArgumentException("scene name '" + name + "' — letters, digits, '.', '_' and '-' only");
        return name;
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
        if (s == null) throw new IllegalStateException("no server is running");
        return s.overworld();
    }

    private static BlockPos firstPlayerFeet(String param) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty())
            throw new IllegalArgumentException("'" + param + "' is required when no player is on the server");
        return players.get(0).blockPosition();
    }

    private static String firstPlayerName() {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        return players.isEmpty() ? "rpc" : players.get(0).getGameProfile().getName();
    }

    static <T> T onServerThread(Supplier<T> task) {
        MinecraftServer s = server;
        if (s == null) throw new IllegalStateException("no server is running");
        return new ServerThreadHop(s, s::isSameThread, SERVER_THREAD_TIMEOUT_MS).call(task);
    }
}
