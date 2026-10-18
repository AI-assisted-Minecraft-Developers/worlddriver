package net.magicterra.worlddriver.testcontent;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * The hand-built scene verbs' contracts: {@code worlddriver.mark} and {@code worlddriver.scene.*}. All
 * hidden — reachable by name on every transport, absent from the MCP tool list. A fixture, a verdict
 * record and a run's numbers are file content whose shape the fixture format owns, so they travel as
 * open JSON objects rather than being restated here.
 */
public final class SceneContracts {
    private SceneContracts() {}

    private static final ApiType<String> NAME = ApiTypes.pattern("[A-Za-z0-9][A-Za-z0-9_.-]*",
            "a scene name: letters, digits, '.', '_' and '-'").maxLength(128);
    private static final ApiType<String> WALK_VERB = ApiTypes.stringEnum("SceneWalkVerb", "goto", "mine", "escape", "elytra");
    private static final ApiType<Integer> BUDGET = ApiTypes.integer(1, 100_000);
    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);
    private static final ApiType<List<Integer>> SIZE = ApiTypes.list(COUNT, 3, 3);
    private static final ApiType<String> PATH = ApiTypes.string();

    // ------------------------------------------------------------------ worlddriver.mark

    public record MarkArgs(Optional<Double> yaw, Optional<List<Integer>> box) {}
    public record MarkParams(MarkerRole role, BlockPosition pos, String label, Optional<MarkArgs> args) {}
    public record Marked(boolean placed, MarkerRole role, BlockPosition pos, Optional<String> label) {}

    private static final ApiType<MarkerRole> ROLE = ApiTypes.enumOf("MarkerRole", MarkerRole.class, MarkerRole::getSerializedName);

    public static final MethodSpec<MarkParams, Marked> MARK = MethodSpec.of("worlddriver.mark",
                    ApiTypes.record(MarkParams.class, "MarkParams",
                            ApiField.field("role", ROLE).required().forGetter(MarkParams::role),
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(MarkParams::pos),
                            ApiField.field("label", ApiTypes.string())
                                    .describe("goal: block|near:<r>|y:<y>; via/pass: a number; watch: same|<block id>; "
                                            + "an anchor's scene name.")
                                    .defaulted("").forGetter(MarkParams::label),
                            ApiField.field("args", ApiTypes.record(MarkArgs.class, "MarkArgs",
                                    ApiField.field("yaw", ApiTypes.number()).describe("The bot's facing, on start.")
                                            .optional().forGetter(MarkArgs::yaw),
                                    ApiField.field("box", ApiTypes.list(ApiTypes.integer(), 6, 6))
                                            .describe("On an anchor (origin, or start): the scene's box as six offsets "
                                                    + "from the marker, min corner then max; all zero clears it.")
                                            .optional().forGetter(MarkArgs::box),
                                    MarkArgs::new)).describe("Entity args.").optional().forGetter(MarkParams::args),
                            MarkParams::new),
                    ApiTypes.record(Marked.class, "Marked",
                            ApiField.field("placed", ApiTypes.trueOnly()).required().forGetter(Marked::placed),
                            ApiField.field("role", ROLE).required().forGetter(Marked::role),
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Marked::pos),
                            ApiField.field("label", ApiTypes.string()).optional().forGetter(Marked::label),
                            Marked::new),
                    "Place a scene marker block (testmod verb). role picks what it means; label goes into the block "
                            + "entity. A marker put into a water or lava source keeps it; one put onto a marker rewrites "
                            + "that marker.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.save

    public record SaveParams(String name, String verb, int budget, Optional<BlockPosition> around, Optional<String> author) {}
    public record Saved(boolean saved, String json, String nbt, JsonValue fixture) {}

    public static final MethodSpec<SaveParams, Saved> SAVE = MethodSpec.of("worlddriver.scene.save",
                    ApiTypes.record(SaveParams.class, "SceneSaveParams",
                            ApiField.field("name", NAME).required().forGetter(SaveParams::name),
                            ApiField.field("verb", WALK_VERB).defaulted("goto").forGetter(SaveParams::verb),
                            ApiField.field("budget", BUDGET).describe("Each walk's tick budget.")
                                    .defaulted(1200).forGetter(SaveParams::budget),
                            ApiField.field("around", CommonTypes.BLOCK_POSITION)
                                    .describe("Scan centre; omitted: the first player's position.")
                                    .optional().forGetter(SaveParams::around),
                            ApiField.field("author", ApiTypes.string()).describe("Omitted: the first player's name.")
                                    .optional().forGetter(SaveParams::author),
                            SaveParams::new),
                    ApiTypes.record(Saved.class, "SceneSaved",
                            ApiField.field("saved", ApiTypes.trueOnly()).required().forGetter(Saved::saved),
                            ApiField.field("json", PATH).required().forGetter(Saved::json),
                            ApiField.field("nbt", PATH).required().forGetter(Saved::nbt),
                            ApiField.field("fixture", ApiTypes.jsonObject()).describe("The fixture file's content.")
                                    .required().forGetter(Saved::fixture),
                            Saved::new),
                    "Save the scene the markers around `around` describe: terrain between the two corner markers to "
                            + "config/worlddriver/scenes/<name>.nbt, the fixture to <name>.json. Markers stay in the world. "
                            + "A scene already anchored under this name is saved where it stands.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.list

    public record ListParams() {}
    public record LastVerdict(Optional<String> when, Optional<String> who, String human, Optional<String> note) {}
    /** A file that does not load carries only {@code file} and {@code error}. */
    public record SceneRow(String file, Optional<String> name, Optional<List<Integer>> size, Optional<Integer> chunkRadius,
                           Optional<Integer> legs, Optional<Boolean> hasNbt, Optional<LastVerdict> lastVerdict,
                           Optional<String> error) {}
    public record SceneList(String dir, List<SceneRow> scenes) {}

    public static final MethodSpec<ListParams, SceneList> LIST = MethodSpec.of("worlddriver.scene.list",
                    ApiTypes.record(ListParams.class, "SceneListParams", ListParams::new),
                    ApiTypes.record(SceneList.class, "SceneList",
                            ApiField.field("dir", PATH).required().forGetter(SceneList::dir),
                            ApiField.field("scenes", ApiTypes.list(ApiTypes.record(SceneRow.class, "SceneRow",
                                    ApiField.field("file", ApiTypes.string()).required().forGetter(SceneRow::file),
                                    ApiField.field("name", ApiTypes.string()).optional().forGetter(SceneRow::name),
                                    ApiField.field("size", SIZE).optional().forGetter(SceneRow::size),
                                    ApiField.field("chunkRadius", COUNT).optional().forGetter(SceneRow::chunkRadius),
                                    ApiField.field("legs", COUNT).optional().forGetter(SceneRow::legs),
                                    ApiField.field("hasNbt", ApiTypes.bool()).optional().forGetter(SceneRow::hasNbt),
                                    ApiField.field("lastVerdict", ApiTypes.record(LastVerdict.class, "SceneLastVerdict",
                                            ApiField.field("when", ApiTypes.string()).optional().forGetter(LastVerdict::when),
                                            ApiField.field("who", ApiTypes.string()).optional().forGetter(LastVerdict::who),
                                            ApiField.field("human", ApiTypes.string()).required().forGetter(LastVerdict::human),
                                            ApiField.field("note", ApiTypes.string()).optional().forGetter(LastVerdict::note),
                                            LastVerdict::new)).optional().forGetter(SceneRow::lastVerdict),
                                    ApiField.field("error", ApiTypes.string()).describe("Why the file did not load.")
                                            .optional().forGetter(SceneRow::error),
                                    SceneRow::new), 100_000)).required().forGetter(SceneList::scenes),
                            SceneList::new),
                    "List the scene fixtures under config/worlddriver/scenes with each one's latest human verdict.")
            .hidden();

    // ------------------------------------------------------------------ worlddriver.scene.place

    public record PlaceParams(String name, Optional<BlockPosition> pos, Optional<BlockPosition> around) {}
    public record Placed(boolean placed, String name, BlockPosition origin, BlockPosition min, List<Integer> size) {}

    public static final MethodSpec<PlaceParams, Placed> PLACE = MethodSpec.of("worlddriver.scene.place",
                    ApiTypes.record(PlaceParams.class, "ScenePlaceParams",
                            ApiField.field("name", NAME).required().forGetter(PlaceParams::name),
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(PlaceParams::pos),
                            ApiField.field("around", CommonTypes.BLOCK_POSITION).optional().forGetter(PlaceParams::around),
                            PlaceParams::new),
                    ApiTypes.record(Placed.class, "ScenePlaced",
                            ApiField.field("placed", ApiTypes.trueOnly()).required().forGetter(Placed::placed),
                            ApiField.field("name", ApiTypes.string()).required().forGetter(Placed::name),
                            ApiField.field("origin", CommonTypes.BLOCK_POSITION).required().forGetter(Placed::origin),
                            ApiField.field("min", CommonTypes.BLOCK_POSITION).required().forGetter(Placed::min),
                            ApiField.field("size", SIZE).required().forGetter(Placed::size),
                            Placed::new),
                    "Put a saved scene's terrain and markers back into the world (a reset) with its origin cell at the "
                            + "anchor marker labelled with its name when this world has one, else at `pos`, else at "
                            + "`around` / the first player's feet. An unknown name is invalid_params at /name.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.run

    public record RunParams(String name, Optional<String> body, boolean watch, Optional<BlockPosition> pos,
                            Optional<BlockPosition> around, Optional<String> verb, Optional<Integer> budget, int awaitMs) {}
    /** {@code status} and the rest are present only when the call waited ({@code awaitMs} above zero). */
    public record RunReply(boolean started, String name, BlockPosition origin, Optional<String> status,
                           Optional<String> reason, Optional<JsonValue> auto, Optional<JsonValue> observed,
                           Optional<List<String>> lines) {}

    public static final MethodSpec<RunParams, RunReply> RUN = MethodSpec.of("worlddriver.scene.run",
                    ApiTypes.record(RunParams.class, "SceneRunParams",
                            ApiField.field("name", NAME).describe("A saved scene, or `here` for the markers in the world.")
                                    .required().forGetter(RunParams::name),
                            ApiField.field("body", ApiTypes.string(1, 64))
                                    .describe("server (headless, dedicated server), self (the real player, integrated "
                                            + "server), or npc / npc:<name> (a driven piglin, any topology, no inventory). "
                                            + "Omitted: the file's npc when it names one, else by topology.")
                                    .optional().forGetter(RunParams::body),
                            ApiField.field("watch", ApiTypes.bool()).describe("A progress line to the first player every 20 ticks.")
                                    .defaulted(false).forGetter(RunParams::watch),
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(RunParams::pos),
                            ApiField.field("around", CommonTypes.BLOCK_POSITION).optional().forGetter(RunParams::around),
                            ApiField.field("verb", WALK_VERB).describe("For `here`, or over the file's: the walks' verb.")
                                    .optional().forGetter(RunParams::verb),
                            ApiField.field("budget", BUDGET).describe("For `here`, or over the file's: each walk's tick budget.")
                                    .optional().forGetter(RunParams::budget),
                            ApiField.field("awaitMs", ApiTypes.integer(0, (int) CallContext.SERVER_CAP_MS))
                                    .describe("Wait this long for the outcome; refused from a game thread.")
                                    .defaulted(0).forGetter(RunParams::awaitMs),
                            RunParams::new),
                    ApiTypes.record(RunReply.class, "SceneRunReply",
                            ApiField.field("started", ApiTypes.trueOnly()).required().forGetter(RunReply::started),
                            ApiField.field("name", ApiTypes.string()).required().forGetter(RunReply::name),
                            ApiField.field("origin", CommonTypes.BLOCK_POSITION).required().forGetter(RunReply::origin),
                            ApiField.field("status", ApiTypes.stringEnum("SceneRunStatus", "pass", "fail", "skip", "error", "running"))
                                    .describe("running: awaitMs ran out first.").optional().forGetter(RunReply::status),
                            ApiField.field("reason", ApiTypes.string()).optional().forGetter(RunReply::reason),
                            ApiField.field("auto", ApiTypes.jsonObject()).describe("The automatic checks, by name.")
                                    .optional().forGetter(RunReply::auto),
                            ApiField.field("observed", ApiTypes.jsonObject()).describe("ticks, repaths, hops, digs.")
                                    .optional().forGetter(RunReply::observed),
                            ApiField.field("lines", ApiTypes.list(ApiTypes.string(), 10_000)).describe("The tester-facing report.")
                                    .optional().forGetter(RunReply::lines),
                            RunReply::new),
                    "Run a scene in place. `here`: the corner box around `around` (default the first player's feet), "
                            + "markers as the truth, terrain as is. A name: the same at the anchor marker labelled with "
                            + "that name when this world has one (the file adds hand/equip/config/expect and the walks' "
                            + "verb and budget); otherwise the saved terrain is placed at `pos` and run there (no `pos`: "
                            + "invalid_params at /pos — `scene.place` first). Each run appends to <name>.verdicts.jsonl.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.BODY_TASK)
            .mayWait();

    // ------------------------------------------------------------------ worlddriver.scene.verdict

    public record VerdictParams(String name, String verdict, Optional<String> note) {}
    public record Recorded(boolean recorded, String file, JsonValue record) {}

    public static final MethodSpec<VerdictParams, Recorded> VERDICT = MethodSpec.of("worlddriver.scene.verdict",
                    ApiTypes.record(VerdictParams.class, "SceneVerdictParams",
                            ApiField.field("name", NAME).required().forGetter(VerdictParams::name),
                            ApiField.field("verdict", ApiTypes.stringEnum("SceneVerdict", "pass", "fail", "flaky"))
                                    .required().forGetter(VerdictParams::verdict),
                            ApiField.field("note", ApiTypes.string()).optional().forGetter(VerdictParams::note),
                            VerdictParams::new),
                    ApiTypes.record(Recorded.class, "SceneVerdictRecorded",
                            ApiField.field("recorded", ApiTypes.trueOnly()).required().forGetter(Recorded::recorded),
                            ApiField.field("file", PATH).required().forGetter(Recorded::file),
                            ApiField.field("record", ApiTypes.jsonObject()).describe("The line appended to the file.")
                                    .required().forGetter(Recorded::record),
                            Recorded::new),
                    "Record a human judgement of the scene's latest run into <name>.verdicts.jsonl, copying that run's "
                            + "numbers.")
            .hidden()
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.accept

    public record AcceptParams(String name) {}
    public record Accepted(boolean accepted, String json, JsonValue expect) {}

    public static final MethodSpec<AcceptParams, Accepted> ACCEPT = MethodSpec.of("worlddriver.scene.accept",
                    ApiTypes.record(AcceptParams.class, "SceneAcceptParams",
                            ApiField.field("name", NAME).required().forGetter(AcceptParams::name),
                            AcceptParams::new),
                    ApiTypes.record(Accepted.class, "SceneAccepted",
                            ApiField.field("accepted", ApiTypes.trueOnly()).required().forGetter(Accepted::accepted),
                            ApiField.field("json", PATH).required().forGetter(Accepted::json),
                            ApiField.field("expect", ApiTypes.jsonObject()).describe("The bounds now in the fixture.")
                                    .required().forGetter(Accepted::expect),
                            Accepted::new),
                    "Write the numbers of the latest run judged `pass` (plus 20%) into the fixture's `expect`, the "
                            + "bounds the suite will hold it to. Not for `here`.")
            .hidden()
            .effects(MethodSpec.Effect.CONFIG_WRITE);
}
