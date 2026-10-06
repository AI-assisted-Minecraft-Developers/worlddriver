package net.magicterra.worlddriver.api.methods;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.UnionType;

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
    public record Issue(String path, String code, String message) {}

    /**
     * Why a script ended without a value. {@code code} is an API error code when a {@code Driver} call threw
     * and the script let it through, with that call's {@code issues} and {@code execution}; otherwise
     * {@code script_error} (the script threw), {@code script_timeout} or {@code script_stopped}. {@code line}
     * is where in the source it was thrown, when the engine knows.
     */
    public record ScriptError(String code, String message, List<Issue> issues, Optional<String> execution,
                              Optional<Integer> line) {}

    /** {@code result} is the value of the script's last expression; absent when it is null or undefined, or {@code error} is set. */
    public record Outcome(String runId, Optional<JsonValue> result, Optional<ScriptError> error, List<String> log, long ms) {}

    public static final String SCRIPT_ERROR = "script_error";
    public static final String SCRIPT_TIMEOUT = "script_timeout";
    public static final String SCRIPT_STOPPED = "script_stopped";

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

    private static final ApiType<Issue> ISSUE = ApiTypes.record("ScriptIssue",
            ApiField.field("path", ApiTypes.string()).required().forGetter(Issue::path),
            ApiField.field("code", issueCode()).required().forGetter(Issue::code),
            ApiField.field("message", ApiTypes.string()).required().forGetter(Issue::message),
            Issue::new);

    public static final ApiType<ScriptError> SCRIPT_ERROR_TYPE = ApiTypes.record("ScriptError",
            ApiField.field("code", errorCode()).required().forGetter(ScriptError::code),
            ApiField.field("message", ApiTypes.string()).required().forGetter(ScriptError::message),
            ApiField.field("issues", ApiTypes.list(ISSUE, Integer.MAX_VALUE)).required().forGetter(ScriptError::issues),
            ApiField.field("execution", executionWord())
                    .describe("Present when a Driver call threw: whether that call may have taken effect.")
                    .optional().forGetter(ScriptError::execution),
            ApiField.field("line", ApiTypes.integer(0, Integer.MAX_VALUE)).optional().forGetter(ScriptError::line),
            ScriptError::new);

    public static final ApiType<String> RUN_ID = ApiTypes.string(1, 64);

    private static final ApiType<List<String>> LOG = ApiTypes.list(ApiTypes.string(), Integer.MAX_VALUE);

    public static final ApiType<Outcome> OUTCOME = ApiTypes.record("ScriptOutcome",
            ApiField.field("runId", RUN_ID).required().forGetter(Outcome::runId),
            ApiField.field("result", ApiTypes.json()).describe("Absent when the value is null or undefined, or the script failed.")
                    .optional().forGetter(Outcome::result),
            ApiField.field("error", SCRIPT_ERROR_TYPE).optional().forGetter(Outcome::error),
            ApiField.field("log", LOG).describe("What console.log and console.error wrote, in order.")
                    .required().forGetter(Outcome::log),
            ApiField.field("ms", ApiTypes.int64(0, Long.MAX_VALUE)).required().forGetter(Outcome::ms),
            Outcome::new);

    private static final ApiType<String> SOURCE = ApiTypes.string(1, MAX_SOURCE_CHARS);

    private static final ApiType<Integer> TIMEOUT_MS = ApiTypes.integer(1, MAX_TIMEOUT_MS);

    // ------------------------------------------------------------------ mc.script.eval

    public record EvalParams(String source, int timeoutMs) {}

    public static final ApiType<EvalParams> EVAL_PARAMS = ApiTypes.record("EvalParams",
            ApiField.field("source", SOURCE).describe("JavaScript. The value of its last expression is the result.")
                    .required().forGetter(EvalParams::source),
            ApiField.field("timeoutMs", TIMEOUT_MS).describe("The run's budget; when it runs out the run is stopped.")
                    .defaulted(DEFAULT_TIMEOUT_MS).forGetter(EvalParams::timeoutMs),
            EvalParams::new);

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

    public static final ApiType<String> SKILL_NAME = ApiTypes.pattern("[a-z][a-z0-9_]{0,63}", "a skill name such as dig_down");

    public sealed interface SkillOp permits SaveSkill, ListSkills, GetSkill, RunSkill, DeleteSkill {}
    public record SaveSkill(String name, String source) implements SkillOp {}
    public record ListSkills() implements SkillOp {}
    public record GetSkill(String name) implements SkillOp {}
    public record RunSkill(String name, Optional<JsonValue> args, int timeoutMs) implements SkillOp {}
    public record DeleteSkill(String name) implements SkillOp {}

    public static final UnionType<SkillOp> SKILL_PARAMS = ApiTypes.taggedUnion("SkillParams", "op",
            ApiTypes.variant("save", SaveSkill.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(SaveSkill::name),
                    ApiField.field("source", SOURCE).describe("Checked for syntax before it is saved; reads its args from SKILL.")
                            .required().forGetter(SaveSkill::source),
                    SaveSkill::new)),
            ApiTypes.variant("list", ListSkills.class, ApiTypes.record(null, ListSkills::new)),
            ApiTypes.variant("get", GetSkill.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(GetSkill::name), GetSkill::new)),
            ApiTypes.variant("run", RunSkill.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(RunSkill::name),
                    ApiField.field("args", ApiTypes.json()).describe("The SKILL global. Omitted: an empty object.")
                            .optional().forGetter(RunSkill::args),
                    ApiField.field("timeoutMs", TIMEOUT_MS).defaulted(DEFAULT_TIMEOUT_MS).forGetter(RunSkill::timeoutMs),
                    RunSkill::new)),
            ApiTypes.variant("delete", DeleteSkill.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(DeleteSkill::name), DeleteSkill::new)));

    public sealed interface SkillReply permits SkillSaved, SkillList, SkillSource, SkillRan, SkillDeleted {}
    public record SkillSaved(String name, int bytes) implements SkillReply {}
    public record SkillEntry(String name, long bytes) {}
    public record SkillList(List<SkillEntry> skills) implements SkillReply {}
    public record SkillSource(String name, String source) implements SkillReply {}
    public record SkillRan(String name, Outcome outcome) implements SkillReply {}
    public record SkillDeleted(String name, boolean existed) implements SkillReply {}

    private static final ApiType<SkillEntry> SKILL_ENTRY = ApiTypes.record("SkillEntry",
            ApiField.field("name", SKILL_NAME).required().forGetter(SkillEntry::name),
            ApiField.field("bytes", ApiTypes.int64(0, Long.MAX_VALUE)).required().forGetter(SkillEntry::bytes),
            SkillEntry::new);

    public static final UnionType<SkillReply> SKILL_REPLY = ApiTypes.taggedUnion("SkillReply", "op",
            ApiTypes.variant("save", SkillSaved.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(SkillSaved::name),
                    ApiField.field("bytes", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(SkillSaved::bytes),
                    SkillSaved::new)),
            ApiTypes.variant("list", SkillList.class, ApiTypes.record(null,
                    ApiField.field("skills", ApiTypes.list(SKILL_ENTRY, Integer.MAX_VALUE)).describe("By name.")
                            .required().forGetter(SkillList::skills),
                    SkillList::new)),
            ApiTypes.variant("get", SkillSource.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(SkillSource::name),
                    ApiField.field("source", ApiTypes.string()).required().forGetter(SkillSource::source),
                    SkillSource::new)),
            ApiTypes.variant("run", SkillRan.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(SkillRan::name),
                    ApiField.field("outcome", OUTCOME).required().forGetter(SkillRan::outcome),
                    SkillRan::new)),
            ApiTypes.variant("delete", SkillDeleted.class, ApiTypes.record(null,
                    ApiField.field("name", SKILL_NAME).required().forGetter(SkillDeleted::name),
                    ApiField.field("existed", ApiTypes.bool()).required().forGetter(SkillDeleted::existed),
                    SkillDeleted::new)));

    public static final MethodSpec<SkillOp, SkillReply> SKILL = MethodSpec.of("mc.skill", SKILL_PARAMS, SKILL_REPLY,
                    "A library of saved scripts, kept across sessions under config/worlddriver/scripts/skills/. save "
                            + "checks the syntax before writing; run evaluates the skill as mc.script.eval does, with its "
                            + "args in the SKILL global, and answers the run's outcome. A name that is not saved is "
                            + "invalid_params at /name.")
            .effects(MethodSpec.Effect.SCRIPT_RUN, MethodSpec.Effect.CONFIG_WRITE)
            .execution(MethodSpec.ExecutionClass.SCRIPT)
            .mayWait();

    // ------------------------------------------------------------------ mc.bot.playbook

    public static final ApiType<String> PLAYBOOK_NAME = ApiTypes.pattern("[a-z][a-z0-9_]{0,63}", "a playbook name such as dragon");

    public sealed interface PlaybookOp permits StartPlaybook, PlaybookStatus, CancelPlaybook {}
    public record StartPlaybook(String name, int budgetMs, Optional<JsonValue> args) implements PlaybookOp {}
    public record PlaybookStatus() implements PlaybookOp {}
    public record CancelPlaybook() implements PlaybookOp {}

    public static final UnionType<PlaybookOp> PLAYBOOK_PARAMS = ApiTypes.taggedUnion("PlaybookParams", "op",
            ApiTypes.variant("start", StartPlaybook.class, ApiTypes.record(null,
                    ApiField.field("name", PLAYBOOK_NAME).describe("Built in: dragon, wither.").required()
                            .forGetter(StartPlaybook::name),
                    ApiField.field("budgetMs", ApiTypes.integer(1, MAX_PLAYBOOK_BUDGET_MS))
                            .describe("The run's budget; when it runs out the run is stopped.")
                            .defaulted(DEFAULT_PLAYBOOK_BUDGET_MS).forGetter(StartPlaybook::budgetMs),
                    ApiField.field("args", ApiTypes.jsonObject())
                            .describe("The PLAYBOOK global. dragon and wither read maxRounds; wither reads summon "
                                    + "(default true; false only checks the gear). Omitted: an empty object.")
                            .optional().forGetter(StartPlaybook::args),
                    StartPlaybook::new)),
            ApiTypes.variant("status", PlaybookStatus.class, ApiTypes.record(null, PlaybookStatus::new)),
            ApiTypes.variant("cancel", CancelPlaybook.class, ApiTypes.record(null, CancelPlaybook::new)));

    public sealed interface PlaybookReply permits PlaybookStarted, PlaybookState, PlaybookCancelled {}
    public record PlaybookStarted(String name, String runId) implements PlaybookReply {}

    /**
     * The running playbook, or the last one: {@code active} says which. {@code outcome} is the last run's,
     * present once it has ended.
     */
    public record PlaybookState(boolean active, Optional<String> name, Optional<String> runId, Optional<Long> startedAtMs,
                                boolean stopping, Optional<Outcome> outcome) implements PlaybookReply {}

    /** {@code stopped}: the run that was asked to stop, absent when none was running. */
    public record PlaybookCancelled(Optional<String> stopped) implements PlaybookReply {}

    public static final UnionType<PlaybookReply> PLAYBOOK_REPLY = ApiTypes.taggedUnion("PlaybookReply", "op",
            ApiTypes.variant("start", PlaybookStarted.class, ApiTypes.record(null,
                    ApiField.field("name", PLAYBOOK_NAME).required().forGetter(PlaybookStarted::name),
                    ApiField.field("runId", RUN_ID).required().forGetter(PlaybookStarted::runId),
                    PlaybookStarted::new)),
            ApiTypes.variant("status", PlaybookState.class, ApiTypes.record(null,
                    ApiField.field("active", ApiTypes.bool()).required().forGetter(PlaybookState::active),
                    ApiField.field("name", PLAYBOOK_NAME).optional().forGetter(PlaybookState::name),
                    ApiField.field("runId", RUN_ID).optional().forGetter(PlaybookState::runId),
                    ApiField.field("startedAtMs", ApiTypes.int64(0, Long.MAX_VALUE)).optional()
                            .forGetter(PlaybookState::startedAtMs),
                    ApiField.field("stopping", ApiTypes.bool()).describe("Asked to stop and not yet stopped.")
                            .required().forGetter(PlaybookState::stopping),
                    ApiField.field("outcome", OUTCOME).optional().forGetter(PlaybookState::outcome),
                    PlaybookState::new)),
            ApiTypes.variant("cancel", PlaybookCancelled.class, ApiTypes.record(null,
                    ApiField.field("stopped", RUN_ID).optional().forGetter(PlaybookCancelled::stopped),
                    PlaybookCancelled::new)));

    public static final MethodSpec<PlaybookOp, PlaybookReply> PLAYBOOK = MethodSpec.of("mc.bot.playbook", PLAYBOOK_PARAMS,
                    PLAYBOOK_REPLY,
                    "A boss fight run by a script on a background thread, so it outlives mc.script.eval's budget. start "
                            + "answers at once with the run's id; status reports it and, once it has ended, its outcome; "
                            + "cancel stops it, which cancels the actions it started. One playbook at a time: starting a "
                            + "second is capability_unavailable. dragon clears the End crystals, then fights the dragon "
                            + "at its perch; wither checks the gear, summons the boss and fights both phases.")
            .effects(MethodSpec.Effect.SCRIPT_RUN);
}
