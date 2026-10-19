package net.magicterra.worlddriver.testcontent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;

/**
 * The hand-built scene verbs' contracts: {@code worlddriver.mark} and {@code worlddriver.scene.*}. All
 * hidden — reachable by name on every transport, absent from the MCP tool list. A fixture, a verdict
 * record and a run's numbers are file content whose shape the fixture format owns, so they travel as
 * open JSON objects rather than being restated here.
 */
public final class SceneContracts {
    private SceneContracts() {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[A-Za-z0-9][A-Za-z0-9_.-]*", expected = "a scene name: letters, digits, '.', '_' and '-'")
    @Length(max = 128)
    public @interface SceneName {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @OneOf(name = "SceneWalkVerb", value = {"goto", "mine", "escape", "elytra"})
    public @interface WalkVerb {}

    // ------------------------------------------------------------------ worlddriver.mark

    @Named("MarkArgs")
    public record MarkArgs(
            @Doc("The bot's facing, on start.") Optional<Double> yaw,
            @Doc("On an anchor (origin, or start): the scene's box as six offsets from the marker, min corner then "
                    + "max; all zero clears it.")
            Optional<@Length(min = 6, max = 6) List<Integer>> box) {}

    @Named("MarkParams")
    public record MarkParams(
            MarkerRole role,
            BlockPosition pos,
            @Doc("goal: block|near:<r>|y:<y>; via/pass: a number; watch: same|<block id>; an anchor's scene name.")
            @Default("") String label,
            @Doc("Entity args.") Optional<MarkArgs> args) {}

    @Named("Marked")
    public record Marked(@OnlyTrue boolean placed, MarkerRole role, BlockPosition pos, Optional<String> label) {}

    public static final MethodSpec<MarkParams, Marked> MARK = MethodSpec.of("worlddriver.mark", MarkParams.class, Marked.class,
                    "Place a scene marker block (testmod verb). role picks what it means; label goes into the block "
                            + "entity. A marker put into a water or lava source keeps it; one put onto a marker rewrites "
                            + "that marker.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.save

    @Named("SceneSaveParams")
    public record SaveParams(
            @SceneName String name,
            @Default("goto") @WalkVerb String verb,
            @Doc("Each walk's tick budget.") @Default("1200") @Range(min = 1, max = 100_000) int budget,
            @Doc("Scan centre; omitted: the first player's position.") Optional<BlockPosition> around,
            @Doc("Omitted: the first player's name.") Optional<String> author) {}

    @Named("SceneSaved")
    public record Saved(
            @OnlyTrue boolean saved,
            String json,
            String nbt,
            @Doc("The fixture file's content.") @OnlyObject JsonValue fixture) {}

    public static final MethodSpec<SaveParams, Saved> SAVE = MethodSpec.of("worlddriver.scene.save",
                    SaveParams.class, Saved.class,
                    "Save the scene the markers around `around` describe: terrain between the two corner markers to "
                            + "config/worlddriver/scenes/<name>.nbt, the fixture to <name>.json. Markers stay in the world. "
                            + "A scene already anchored under this name is saved where it stands.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.list

    @Named("SceneListParams")
    public record ListParams() {}

    @Named("SceneLastVerdict")
    public record LastVerdict(Optional<String> when, Optional<String> who, String human, Optional<String> note) {}

    /** A file that does not load carries only {@code file} and {@code error}. */
    @Named("SceneRow")
    public record SceneRow(
            String file,
            Optional<String> name,
            Optional<@Length(min = 3, max = 3) List<@Range(min = 0) Integer>> size,
            Optional<@Range(min = 0) Integer> chunkRadius,
            Optional<@Range(min = 0) Integer> legs,
            Optional<Boolean> hasNbt,
            Optional<LastVerdict> lastVerdict,
            @Doc("Why the file did not load.") Optional<String> error) {}

    @Named("SceneList")
    public record SceneList(String dir, @Length(max = 100_000) List<SceneRow> scenes) {}

    public static final MethodSpec<ListParams, SceneList> LIST = MethodSpec.of("worlddriver.scene.list",
                    ListParams.class, SceneList.class,
                    "List the scene fixtures under config/worlddriver/scenes with each one's latest human verdict.")
            .hidden();

    // ------------------------------------------------------------------ worlddriver.scene.place

    @Named("ScenePlaceParams")
    public record PlaceParams(@SceneName String name, Optional<BlockPosition> pos, Optional<BlockPosition> around) {}

    @Named("ScenePlaced")
    public record Placed(
            @OnlyTrue boolean placed,
            String name,
            BlockPosition origin,
            BlockPosition min,
            @Length(min = 3, max = 3) List<@Range(min = 0) Integer> size) {}

    public static final MethodSpec<PlaceParams, Placed> PLACE = MethodSpec.of("worlddriver.scene.place",
                    PlaceParams.class, Placed.class,
                    "Put a saved scene's terrain and markers back into the world (a reset) with its origin cell at the "
                            + "anchor marker labelled with its name when this world has one, else at `pos`, else at "
                            + "`around` / the first player's feet. An unknown name is invalid_params at /name.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.run

    @Named("SceneRunParams")
    public record RunParams(
            @Doc("A saved scene, or `here` for the markers in the world.") @SceneName String name,
            @Doc("server (headless, dedicated server), self (the real player, integrated server), or npc / "
                    + "npc:<name> (a driven piglin, any topology, no inventory). Omitted: the file's npc when it "
                    + "names one, else by topology.")
            Optional<@Length(min = 1, max = 64) String> body,
            @Doc("A progress line to the first player every 20 ticks.") @Default("false") boolean watch,
            Optional<BlockPosition> pos,
            Optional<BlockPosition> around,
            @Doc("For `here`, or over the file's: the walks' verb.") Optional<@WalkVerb String> verb,
            @Doc("For `here`, or over the file's: each walk's tick budget.")
            Optional<@Range(min = 1, max = 100_000) Integer> budget,
            @Doc("Wait this long for the outcome; refused from a game thread.")
            @Default("0") @Range(min = 0, max = CallContext.SERVER_CAP_MS) int awaitMs) {}

    /** {@code status} and the rest are present only when the call waited ({@code awaitMs} above zero). */
    @Named("SceneRunReply")
    public record RunReply(
            @OnlyTrue boolean started,
            String name,
            BlockPosition origin,
            @Doc("running: awaitMs ran out first.")
            Optional<@OneOf(name = "SceneRunStatus", value = {"pass", "fail", "skip", "error", "running"}) String> status,
            Optional<String> reason,
            @Doc("The automatic checks, by name.") Optional<@OnlyObject JsonValue> auto,
            @Doc("ticks, repaths, hops, digs.") Optional<@OnlyObject JsonValue> observed,
            @Doc("The tester-facing report.") Optional<@Length(max = 10_000) List<String>> lines) {}

    public static final MethodSpec<RunParams, RunReply> RUN = MethodSpec.of("worlddriver.scene.run",
                    RunParams.class, RunReply.class,
                    "Run a scene in place. `here`: the corner box around `around` (default the first player's feet), "
                            + "markers as the truth, terrain as is. A name: the same at the anchor marker labelled with "
                            + "that name when this world has one (the file adds hand/equip/config/expect and the walks' "
                            + "verb and budget); otherwise the saved terrain is placed at `pos` and run there (no `pos`: "
                            + "invalid_params at /pos — `scene.place` first). Each run appends to <name>.verdicts.jsonl.")
            .hidden()
            .effects(MethodSpec.Effect.WORLD_WRITE, MethodSpec.Effect.BODY_TASK)
            .mayWait();

    // ------------------------------------------------------------------ worlddriver.scene.verdict

    @Named("SceneVerdictParams")
    public record VerdictParams(
            @SceneName String name,
            @OneOf(name = "SceneVerdict", value = {"pass", "fail", "flaky"}) String verdict,
            Optional<String> note) {}

    @Named("SceneVerdictRecorded")
    public record Recorded(
            @OnlyTrue boolean recorded,
            String file,
            @Doc("The line appended to the file.") @OnlyObject JsonValue record) {}

    public static final MethodSpec<VerdictParams, Recorded> VERDICT = MethodSpec.of("worlddriver.scene.verdict",
                    VerdictParams.class, Recorded.class,
                    "Record a human judgement of the scene's latest run into <name>.verdicts.jsonl, copying that run's "
                            + "numbers.")
            .hidden()
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ worlddriver.scene.accept

    @Named("SceneAcceptParams")
    public record AcceptParams(@SceneName String name) {}

    @Named("SceneAccepted")
    public record Accepted(
            @OnlyTrue boolean accepted,
            String json,
            @Doc("The bounds now in the fixture.") @OnlyObject JsonValue expect) {}

    public static final MethodSpec<AcceptParams, Accepted> ACCEPT = MethodSpec.of("worlddriver.scene.accept",
                    AcceptParams.class, Accepted.class,
                    "Write the numbers of the latest run judged `pass` (plus 20%) into the fixture's `expect`, the "
                            + "bounds the suite will hold it to. Not for `here`.")
            .hidden()
            .effects(MethodSpec.Effect.CONFIG_WRITE);
}
