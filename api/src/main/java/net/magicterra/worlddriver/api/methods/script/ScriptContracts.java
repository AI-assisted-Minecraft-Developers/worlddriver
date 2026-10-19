package net.magicterra.worlddriver.api.methods.script;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The script runs: {@code mc.script.eval}, the skill library {@code mc.skill} and the boss playbooks
 * {@code mc.bot.playbook}. Each run has a {@code runId}; the actions its calls start belong to it, so
 * no other caller can take their bodies over, and stopping the run (its budget running out, a playbook
 * cancel) cancels them. A run that returns leaves its actions running.
 *
 * <p>A script's value and a skill's or playbook's {@code args} are open JSON: what they hold is the
 * script's business, not the API's.
 */
public final class ScriptContracts {
    private ScriptContracts() {}

    public static final int MAX_SOURCE_CHARS = 64 * 1024;
    public static final int DEFAULT_TIMEOUT_MS = 3000;
    public static final int MAX_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_PLAYBOOK_BUDGET_MS = 10 * 60_000;
    public static final int MAX_PLAYBOOK_BUDGET_MS = 20 * 60_000;

    // ------------------------------------------------------------------ the outcome of a run

    /** One problem with a value an API call was given, as the API reported it. */
    @Named("ScriptIssue")
    public record Issue(
            String path,
            @As(value = ScriptContracts.class, field = "ISSUE_CODE") String code,
            String message) {}

    /**
     * Why a script ended without a value. {@code code} is an API error code when a {@code Driver} call threw
     * and the script let it through, with that call's {@code issues} and {@code execution}; otherwise
     * {@code script_error} (the script threw), {@code script_timeout} or {@code script_stopped}. {@code line}
     * is where in the source it was thrown, when the engine knows.
     */
    @Named("ScriptError")
    public record ScriptError(
            @As(value = ScriptContracts.class, field = "ERROR_CODE") String code,
            String message,
            List<Issue> issues,
            @Doc("Present when a Driver call threw: whether that call may have taken effect.")
            Optional<@As(value = ScriptContracts.class, field = "EXECUTION") String> execution,
            Optional<@Range(min = 0) Integer> line) {}

    /** {@code result} is the value of the script's last expression; absent when it is null or undefined, or {@code error} is set. */
    @Named("ScriptOutcome")
    public record Outcome(
            @Length(min = 1, max = 64) String runId,
            @Doc("Absent when the value is null or undefined, or the script failed.") Optional<JsonValue> result,
            Optional<ScriptError> error,
            @Doc("What console.log and console.error wrote, in order.") List<String> log,
            @Range(min = 0) long ms) {}

    public static final String SCRIPT_ERROR = "script_error";
    public static final String SCRIPT_TIMEOUT = "script_timeout";
    public static final String SCRIPT_STOPPED = "script_stopped";

    // Spelled from the enums they mirror, so a new API error code is a script error code too; declared before
    // the records that use them are built.
    private static final ApiType<String> ERROR_CODE = errorCode();
    private static final ApiType<String> ISSUE_CODE = issueCode();
    private static final ApiType<String> EXECUTION = executionWord();

    private static ApiType<String> errorCode() {
        List<String> codes = new ArrayList<>();
        for (ApiError.Code c : ApiError.Code.values()) codes.add(c.wireName());
        codes.add(SCRIPT_ERROR);
        codes.add(SCRIPT_TIMEOUT);
        codes.add(SCRIPT_STOPPED);
        return ApiTypes.stringEnum("ScriptErrorCode", codes.toArray(String[]::new));
    }

    private static ApiType<String> issueCode() {
        List<String> codes = new ArrayList<>();
        for (ApiIssue.Code c : ApiIssue.Code.values()) codes.add(c.wireName());
        return ApiTypes.stringEnum("ScriptIssueCode", codes.toArray(String[]::new));
    }

    private static ApiType<String> executionWord() {
        List<String> words = new ArrayList<>();
        for (ApiError.Execution e : ApiError.Execution.values()) words.add(e.wireName());
        return ApiTypes.stringEnum("ScriptExecution", words.toArray(String[]::new));
    }

    public static final ApiType<ScriptError> SCRIPT_ERROR_TYPE = ApiTypes.record(ScriptError.class);

    public static final ApiType<Outcome> OUTCOME = ApiTypes.record(Outcome.class);

    // ------------------------------------------------------------------ mc.script.eval

    @Named("EvalParams")
    public record EvalParams(
            @Doc("JavaScript. The value of its last expression is the result.")
            @Length(min = 1, max = 65536) String source,
            @Doc("The run's budget; when it runs out the run is stopped.") @Default("3000")
            @Range(min = 1, max = 30000) int timeoutMs) {}

    public static final ApiType<EvalParams> EVAL_PARAMS = ApiTypes.record(EvalParams.class);

    // The sandbox sentence is negative on purpose: the filter is off by default, and this text reaches every
    // client's prompt, so claiming a sandbox would invite untrusted source.
    public static final MethodSpec<EvalParams, Outcome> EVAL = MethodSpec.of("mc.script.eval", EVAL_PARAMS, OUTCOME,
                    "Prefer this when a task would otherwise need three or more calls (observe, decide, act). Runs "
                            + "JavaScript against the in-process API in a fresh scope: Driver.invoke(method, params) calls "
                            + "any method, Driver.bot / Driver.observe / Driver.client / … wrap them, console.log appends "
                            + "to log. A failed Driver call throws a DriverApiError with code, issues and execution. The "
                            + "script's own failure is in error, not a failed call: the call failed only when the script "
                            + "could not be run. Not sandboxed unless the server runs with -Dworlddriver.sandbox=on.")
            .effects(MethodSpec.Effect.SCRIPT_RUN)
            .execution(MethodSpec.ExecutionClass.SCRIPT)
            .mayWait();

    // ------------------------------------------------------------------ mc.skill

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z][a-z0-9_]{0,63}", expected = "a skill name such as dig_down")
    public @interface SkillName {}

    @Tag("op") @Named("SkillParams")
    public sealed interface SkillOp permits SaveSkill, ListSkills, GetSkill, RunSkill, DeleteSkill {}
    @Variant("save")
    public record SaveSkill(
            @SkillName String name,
            @Doc("Checked for syntax before it is saved; reads its args from SKILL.")
            @Length(min = 1, max = 65536) String source) implements SkillOp {}
    @Variant("list")
    public record ListSkills() implements SkillOp {}
    @Variant("get")
    public record GetSkill(@SkillName String name) implements SkillOp {}
    @Variant("run")
    public record RunSkill(
            @SkillName String name,
            @Doc("The SKILL global. Omitted: an empty object.") Optional<JsonValue> args,
            @Default("3000") @Range(min = 1, max = 30000) int timeoutMs) implements SkillOp {}
    @Variant("delete")
    public record DeleteSkill(@SkillName String name) implements SkillOp {}

    public static final UnionType<SkillOp> SKILL_PARAMS = ApiTypes.union(SkillOp.class);

    @Tag("op") @Named("SkillReply")
    public sealed interface SkillReply permits SkillSaved, SkillList, SkillSource, SkillRan, SkillDeleted {}
    @Variant("save")
    public record SkillSaved(@SkillName String name, @Range(min = 0) int bytes) implements SkillReply {}
    @Named("SkillEntry")
    public record SkillEntry(@SkillName String name, @Range(min = 0) long bytes) {}
    @Variant("list")
    public record SkillList(@Doc("By name.") List<SkillEntry> skills) implements SkillReply {}
    @Variant("get")
    public record SkillSource(@SkillName String name, String source) implements SkillReply {}
    @Variant("run")
    public record SkillRan(@SkillName String name, Outcome outcome) implements SkillReply {}
    @Variant("delete")
    public record SkillDeleted(@SkillName String name, boolean existed) implements SkillReply {}

    public static final UnionType<SkillReply> SKILL_REPLY = ApiTypes.union(SkillReply.class);

    public static final MethodSpec<SkillOp, SkillReply> SKILL = MethodSpec.of("mc.skill", SKILL_PARAMS, SKILL_REPLY,
                    "A library of saved scripts, kept across sessions under config/worlddriver/scripts/skills/. save "
                            + "checks the syntax before writing; run evaluates the skill as mc.script.eval does, with its "
                            + "args in the SKILL global, and answers the run's outcome. A name that is not saved is "
                            + "invalid_params at /name.")
            .effects(MethodSpec.Effect.SCRIPT_RUN, MethodSpec.Effect.CONFIG_WRITE)
            .execution(MethodSpec.ExecutionClass.SCRIPT)
            .mayWait();

    // ------------------------------------------------------------------ mc.bot.playbook

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z][a-z0-9_]{0,63}", expected = "a playbook name such as dragon")
    public @interface PlaybookName {}

    @Tag("op") @Named("PlaybookParams")
    public sealed interface PlaybookOp permits StartPlaybook, PlaybookStatus, CancelPlaybook {}
    @Variant("start")
    public record StartPlaybook(
            @Doc("Built in: dragon, wither.") @PlaybookName String name,
            @Doc("The run's budget; when it runs out the run is stopped.") @Default("600000")
            @Range(min = 1, max = 1200000) int budgetMs,
            @Doc("The PLAYBOOK global. dragon and wither read maxRounds; wither reads summon (default true; false "
                    + "only checks the gear). Omitted: an empty object.")
            Optional<@OnlyObject JsonValue> args) implements PlaybookOp {}
    @Variant("status")
    public record PlaybookStatus() implements PlaybookOp {}
    @Variant("cancel")
    public record CancelPlaybook() implements PlaybookOp {}

    public static final UnionType<PlaybookOp> PLAYBOOK_PARAMS = ApiTypes.union(PlaybookOp.class);

    @Tag("op") @Named("PlaybookReply")
    public sealed interface PlaybookReply permits PlaybookStarted, PlaybookState, PlaybookCancelled {}
    @Variant("start")
    public record PlaybookStarted(
            @PlaybookName String name,
            @Length(min = 1, max = 64) String runId) implements PlaybookReply {}

    /**
     * The running playbook, or the last one: {@code active} says which. {@code outcome} is the last run's,
     * present once it has ended.
     */
    @Variant("status")
    public record PlaybookState(
            boolean active,
            Optional<@PlaybookName String> name,
            Optional<@Length(min = 1, max = 64) String> runId,
            Optional<@Range(min = 0) Long> startedAtMs,
            @Doc("Asked to stop and not yet stopped.") boolean stopping,
            Optional<Outcome> outcome) implements PlaybookReply {}

    /** {@code stopped}: the run that was asked to stop, absent when none was running. */
    @Variant("cancel")
    public record PlaybookCancelled(Optional<@Length(min = 1, max = 64) String> stopped) implements PlaybookReply {}

    public static final UnionType<PlaybookReply> PLAYBOOK_REPLY = ApiTypes.union(PlaybookReply.class);

    public static final MethodSpec<PlaybookOp, PlaybookReply> PLAYBOOK = MethodSpec.of("mc.bot.playbook", PLAYBOOK_PARAMS,
                    PLAYBOOK_REPLY,
                    "A boss fight run by a script on a background thread, so it outlives mc.script.eval's budget. start "
                            + "answers at once with the run's id; status reports it and, once it has ended, its outcome; "
                            + "cancel stops it, which cancels the actions it started. One playbook at a time: starting a "
                            + "second is capability_unavailable. dragon clears the End crystals, then fights the dragon "
                            + "at its perch; wither checks the gear, summons the boss and fights both phases.")
            .effects(MethodSpec.Effect.SCRIPT_RUN);
}
