package net.magicterra.worlddriver.api.methods.control;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionSnapshot;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.Status;
import net.magicterra.worlddriver.api.methods.shared.Responses.Stopped;
import net.magicterra.worlddriver.api.methods.wait.WaitContracts;

/** Reading and stopping what the bots do: {@code mc.bot.status}, {@code mc.bot.cancel} and {@code mc.bot.waypoint}. */
public final class ControlContracts {
    private ControlContracts() {}

    // ------------------------------------------------------------------ cancel

    /**
     * What {@code mc.bot.cancel} stops by name: everything, a process by its kind, or a reflex chain. The bot's
     * layers spell these names themselves, since they may not name the api; {@code ProcessNameTest} holds the
     * two lists equal.
     */
    public enum ProcessName implements WireName {
        ALL("all"),
        GOTO("goto"), MINE("mine"), CRAFT("craft"), SMELT("smelt"), SLEEP("sleep"), FOLLOW("follow"),
        EXPLORE("explore"), ESCAPE("escape"), RUN_AWAY("runAway"), LOOK("look"), BUILDER("builder"),
        ELYTRA("elytra"), REPLAY("replay"),
        /** Both the fight process and its chain. */
        COMBAT("combat"),
        /** Both the dig-in process and its chain. */
        BUNKER("bunker"),
        USER("user"), PANIC("panic"), DUSK_SECURE("duskSecure"), DODGE("dodge"), RETREAT("retreat"),
        DROWN_ESCAPE("drownEscape");

        private final String wire;
        ProcessName(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public record CancelParams(Optional<String> actionId, Optional<String> bot, Optional<ProcessName> process) {

        /** Cancel the action {@code actionId}, on whichever bot runs it. */
        public static CancelParams action(String actionId) {
            return new CancelParams(Optional.of(actionId), Optional.empty(), Optional.empty());
        }

        /** Stop self's {@code process}: all, a process kind, or a reflex chain. */
        public static CancelParams process(ProcessName process) {
            return new CancelParams(Optional.empty(), Optional.empty(), Optional.of(process));
        }

        /** Stop everything self runs. */
        public static CancelParams all() {
            return new CancelParams(Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private static final ApiType<ProcessName> PROCESS_NAME = ApiTypes.enumOf("ProcessName", ProcessName.class);

    public static final ApiType<CancelParams> CANCEL_PARAMS = ApiTypes.record(CancelParams.class, "CancelParams",
            ApiField.field("actionId", Responses.ACTION_ID).describe("Cancel this action, on whichever bot runs it.")
                    .optional().forGetter(CancelParams::actionId),
            ApiField.field("bot", Responses.BOT_REF).describe("Without actionId: whose processes. Omitted: self.")
                    .optional().forGetter(CancelParams::bot),
            ApiField.field("process", PROCESS_NAME)
                    .describe("Without actionId: all (the default), a process kind (goto, mine, …) or a reflex chain "
                            + "(combat, bunker, retreat, …).")
                    .optional().forGetter(CancelParams::process),
            CancelParams::new)
            .checked((p, at) -> {
                if (p.actionId().isEmpty()) return;
                if (p.bot().isPresent()) at.field("bot").fail(ApiIssue.Code.INVALID_VALUE, "an action names its own bot");
                if (p.process().isPresent()) at.field("process").fail(ApiIssue.Code.INVALID_VALUE, "cancel an action or processes, not both");
            });

    public static final MethodSpec<CancelParams, ImmediateResponse> CANCEL = MethodSpec.of("mc.bot.cancel", CANCEL_PARAMS,
                    Responses.immediate("Cancel", Stopped.class, ApiTypes.record(Stopped.class, null,
                            ApiField.field("cancelled", ApiTypes.list(ApiTypes.string(1, 64), 64)).required()
                                    .forGetter(Stopped::cancelled),
                            ApiField.field("action", WaitContracts.ANY_ACTION).optional().forGetter(Stopped::action),
                            Stopped::new)),
                    "Stop an action by its actionId, which ends it cancelled with reason requested, or a bot's processes "
                            + "by name; all also releases the held inputs and stands every reflex chain down. Works on a "
                            + "bot that cannot act. An unknown or expired actionId is action_not_found.")
            .effects(MethodSpec.Effect.BODY_TASK);

    // ------------------------------------------------------------------ status

    public record StatusParams(Optional<String> bot) {

        /** Self's status, with the registered bots listed. */
        public static StatusParams self() { return new StatusParams(Optional.empty()); }
    }

    /** One process slot. {@code lastError} also holds success summaries; {@code lastProcessEnd} says how a task ended. */
    public record Slot(boolean active, Optional<String> goal, Optional<BlockPosition> target, int pathLen, int pathStep,
                       Optional<Long> startedAtMs, Optional<String> lastError, Optional<Boolean> goalReached,
                       Optional<String> endReason, Optional<Double> finalDist) {}

    public record Slots(Slot goTo, Slot mine, Slot builder, Slot follow, Slot explore, Slot runAway, Slot retreat, Slot look,
                        Slot elytra, Slot craft, Slot smelt, Slot escape, Slot bunker, Slot combat, Slot plan) {}

    /** How the last task a caller started ended: {@code error} is absent only when it did what it was asked. */
    public record ProcessEnd(String kind, Optional<String> error) {}

    public record PathStats(int expanded, long ms, boolean goalReached, double finalCost, int pathLen,
                            boolean sightBudgetExhausted, boolean snapshotTruncated) {}

    public record Bail(String reason, long ticksLeft) {}

    public record ChainState(double priority, Optional<String> episode, Optional<Bail> bail) {}

    public record CombatTally(int swings, int wellTimed, int crits, int kills) {}

    /** What only this client's player has: the reflex chains that bid against its task. */
    public record Reflexes(boolean paused, Optional<String> activeProcessDetail, Optional<String> activeChain,
                           boolean userTaskSuspended, Map<String, ChainState> chains, Optional<PathStats> lastPath,
                           String clutch, CombatTally combat) {}

    public record BotStatus(String bot, boolean busy, Optional<String> activeProcess, Optional<ProcessEnd> lastProcessEnd,
                             Slots slots, Optional<Reflexes> reflexes) {}

    public record BotSummary(String id, String kind, Optional<Integer> entityId, Optional<BlockPosition> pos, boolean busy) {}

    public static final ApiType<StatusParams> STATUS_PARAMS = ApiTypes.record(StatusParams.class, "StatusParams",
            ApiField.field("bot", Responses.BOT_REF).describe("Omitted: self when there is a client; the bots list either way.")
                    .optional().forGetter(StatusParams::bot),
            StatusParams::new);

    private static final ApiType<Slot> SLOT = ApiTypes.record(Slot.class, "ProcessSlot",
            ApiField.field("active", ApiTypes.bool()).required().forGetter(Slot::active),
            ApiField.field("goal", ApiTypes.string()).describe("For display only; its wording is not a format to parse.").optional()
                    .forGetter(Slot::goal),
            ApiField.field("target", CommonTypes.BLOCK_POSITION).optional().forGetter(Slot::target),
            ApiField.field("pathLen", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Slot::pathLen),
            ApiField.field("pathStep", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Slot::pathStep),
            ApiField.field("startedAtMs", ApiTypes.int64(0, Long.MAX_VALUE)).optional().forGetter(Slot::startedAtMs),
            ApiField.field("lastError", ApiTypes.string())
                    .describe("Why the slot last stopped: an Unready word when the bot could not act (no_hands), "
                            + "else a message for display.")
                    .optional().forGetter(Slot::lastError),
            ApiField.field("goalReached", ApiTypes.bool()).optional().forGetter(Slot::goalReached),
            ApiField.field("endReason", ApiTypes.string()).optional().forGetter(Slot::endReason),
            ApiField.field("finalDist", ApiTypes.number()).optional().forGetter(Slot::finalDist),
            Slot::new);

    private static final ApiType<Slots> SLOTS = ApiTypes.record(Slots.class, "ProcessSlots",
            ApiField.field("goto", SLOT).required().forGetter(Slots::goTo),
            ApiField.field("mine", SLOT).required().forGetter(Slots::mine),
            ApiField.field("builder", SLOT).describe("build, clearArea, farm and construct.").required().forGetter(Slots::builder),
            ApiField.field("follow", SLOT).required().forGetter(Slots::follow),
            ApiField.field("explore", SLOT).required().forGetter(Slots::explore),
            ApiField.field("runAway", SLOT).required().forGetter(Slots::runAway),
            ApiField.field("retreat", SLOT).describe("The retreat reflex.").required().forGetter(Slots::retreat),
            ApiField.field("look", SLOT).required().forGetter(Slots::look),
            ApiField.field("elytra", SLOT).required().forGetter(Slots::elytra),
            ApiField.field("craft", SLOT).required().forGetter(Slots::craft),
            ApiField.field("smelt", SLOT).required().forGetter(Slots::smelt),
            ApiField.field("escape", SLOT).required().forGetter(Slots::escape),
            ApiField.field("bunker", SLOT).required().forGetter(Slots::bunker),
            ApiField.field("combat", SLOT).required().forGetter(Slots::combat),
            ApiField.field("plan", SLOT).describe("A route preview's search.").required().forGetter(Slots::plan),
            Slots::new);

    private static final ApiType<ProcessEnd> PROCESS_END = ApiTypes.record(ProcessEnd.class, "ProcessEnd",
            ApiField.field("kind", ApiTypes.string()).required().forGetter(ProcessEnd::kind),
            ApiField.field("error", ApiTypes.string()).optional().forGetter(ProcessEnd::error),
            ProcessEnd::new);

    private static final ApiType<PathStats> PATH_STATS = ApiTypes.record(PathStats.class, "PathStats",
            ApiField.field("expanded", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(PathStats::expanded),
            ApiField.field("ms", ApiTypes.int64(0, Long.MAX_VALUE)).required().forGetter(PathStats::ms),
            ApiField.field("goalReached", ApiTypes.bool()).required().forGetter(PathStats::goalReached),
            ApiField.field("finalCost", ApiTypes.number()).required().forGetter(PathStats::finalCost),
            ApiField.field("pathLen", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(PathStats::pathLen),
            ApiField.field("sightBudgetExhausted", ApiTypes.bool()).required().forGetter(PathStats::sightBudgetExhausted),
            ApiField.field("snapshotTruncated", ApiTypes.bool()).required().forGetter(PathStats::snapshotTruncated),
            PathStats::new);

    private static final ApiType<ChainState> CHAIN_STATE = ApiTypes.record(ChainState.class, "ChainState",
            ApiField.field("priority", ApiTypes.number()).describe("Its bid on the last tick; the highest steers.")
                    .required().forGetter(ChainState::priority),
            ApiField.field("episode", ApiTypes.string()).optional().forGetter(ChainState::episode),
            ApiField.field("bail", ApiTypes.record(Bail.class, "ChainBail",
                    ApiField.field("reason", ApiTypes.string()).required().forGetter(Bail::reason),
                    ApiField.field("ticksLeft", ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE)).required().forGetter(Bail::ticksLeft),
                    Bail::new)).optional().forGetter(ChainState::bail),
            ChainState::new);

    private static final ApiType<Reflexes> REFLEXES = ApiTypes.record(Reflexes.class, "Reflexes",
            ApiField.field("paused", ApiTypes.bool()).required().forGetter(Reflexes::paused),
            ApiField.field("activeProcessDetail", ApiTypes.string()).describe("The task's phase, e.g. bunker SEALED.")
                    .optional().forGetter(Reflexes::activeProcessDetail),
            ApiField.field("activeChain", ApiTypes.string()).describe("What steers now; absent when nothing does.")
                    .optional().forGetter(Reflexes::activeChain),
            ApiField.field("userTaskSuspended", ApiTypes.bool()).describe("A reflex outbids the task, which waits.")
                    .required().forGetter(Reflexes::userTaskSuspended),
            ApiField.field("chains", ApiTypes.map(CHAIN_STATE, 64)).required().forGetter(Reflexes::chains),
            ApiField.field("lastPath", PATH_STATS).describe("The most recent search, by any process.")
                    .optional().forGetter(Reflexes::lastPath),
            ApiField.field("clutch", ApiTypes.stringEnum("ClutchPhase", "idle", "lip", "falling"))
                    .describe("The water-bucket clutch: idle, lip (planned, waiting to walk off) or falling.")
                    .required().forGetter(Reflexes::clutch),
            ApiField.field("combat", ApiTypes.record(CombatTally.class, "CombatTally",
                    ApiField.field("swings", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(CombatTally::swings),
                    ApiField.field("wellTimed", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(CombatTally::wellTimed),
                    ApiField.field("crits", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(CombatTally::crits),
                    ApiField.field("kills", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(CombatTally::kills),
                    CombatTally::new)).describe("This engagement's counts.").required().forGetter(Reflexes::combat),
            Reflexes::new);

    private static final ApiType<BotStatus> BOT_STATUS = ApiTypes.record(BotStatus.class, "BotStatus",
            ApiField.field("bot", Responses.BOT_REF).required().forGetter(BotStatus::bot),
            ApiField.field("busy", ApiTypes.bool()).describe("A task holds it.").required().forGetter(BotStatus::busy),
            ApiField.field("activeProcess", ApiTypes.string()).optional().forGetter(BotStatus::activeProcess),
            ApiField.field("lastProcessEnd", PROCESS_END).optional().forGetter(BotStatus::lastProcessEnd),
            ApiField.field("slots", SLOTS).required().forGetter(BotStatus::slots),
            ApiField.field("reflexes", REFLEXES).describe("self only.").optional().forGetter(BotStatus::reflexes),
            BotStatus::new);

    private static final ApiType<BotSummary> BOT_SUMMARY = ApiTypes.record(BotSummary.class, "BotSummary",
            ApiField.field("id", Responses.BOT_REF).required().forGetter(BotSummary::id),
            ApiField.field("kind", ApiTypes.string()).required().forGetter(BotSummary::kind),
            ApiField.field("entityId", CommonTypes.ENTITY_ID).describe("Absent when its entity is gone.")
                    .optional().forGetter(BotSummary::entityId),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(BotSummary::pos),
            ApiField.field("busy", ApiTypes.bool()).required().forGetter(BotSummary::busy),
            BotSummary::new);

    public static final MethodSpec<StatusParams, ImmediateResponse> STATUS = MethodSpec.of("mc.bot.status", STATUS_PARAMS,
                    Responses.immediate("Status", Status.class, ApiTypes.record(Status.class, null,
                            ApiField.field("bots", ApiTypes.list(BOT_SUMMARY, 256))
                                    .describe("The registered bots, server-side players and mobs; self is not among them.")
                                    .required().forGetter(Status::bots),
                            ApiField.field("status", BOT_STATUS).optional().forGetter(Status::status),
                            Status::new)),
                    "Every process slot of one bot, with how its last task ended, plus the list of addressable bots. "
                            + "Works where nothing else does: a dead or paused bot, and a dedicated server.")
            .effects(MethodSpec.Effect.SNAPSHOT_READ)
            .asPollable();

    // ------------------------------------------------------------------ waypoint

    public sealed interface WaypointOp permits Save, Get, ListAll, Delete, Clear {}
    public record Save(String name, Optional<BlockPosition> pos) implements WaypointOp {}
    public record Get(String name) implements WaypointOp {}
    public record ListAll() implements WaypointOp {}
    public record Delete(String name) implements WaypointOp {}
    public record Clear() implements WaypointOp {}

    public record Waypoint(String name, BlockPosition pos) {}

    public sealed interface WaypointReply permits Saved, Got, Listed, Deleted, Cleared {}
    public record Saved(String name, BlockPosition pos, int count) implements WaypointReply {}
    public record Got(String name, BlockPosition pos) implements WaypointReply {}
    public record Listed(List<Waypoint> waypoints) implements WaypointReply {}
    public record Deleted(String name, boolean existed, int count) implements WaypointReply {}
    public record Cleared(int cleared) implements WaypointReply {}

    private static final ApiType<String> WAYPOINT_NAME = ApiTypes.string(1, 64);

    public static final UnionType<WaypointOp> WAYPOINT_PARAMS = ApiTypes.taggedUnion("WaypointParams", "op",
            ApiTypes.variant("save", Save.class, ApiTypes.record(Save.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Save::name),
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).describe("Omitted: where the player stands.")
                            .optional().forGetter(Save::pos),
                    Save::new)),
            ApiTypes.variant("get", Get.class, ApiTypes.record(Get.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Get::name), Get::new)),
            ApiTypes.variant("list", ListAll.class, ApiTypes.record(ListAll.class, null, ListAll::new)),
            ApiTypes.variant("delete", Delete.class, ApiTypes.record(Delete.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Delete::name), Delete::new)),
            ApiTypes.variant("clear", Clear.class, ApiTypes.record(Clear.class, null, Clear::new)));

    private static final ApiType<Waypoint> WAYPOINT = ApiTypes.record(Waypoint.class, "Waypoint",
            ApiField.field("name", WAYPOINT_NAME).required().forGetter(Waypoint::name),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Waypoint::pos),
            Waypoint::new);

    public static final UnionType<WaypointReply> WAYPOINT_REPLY = ApiTypes.taggedUnion("WaypointReply", "op",
            ApiTypes.variant("save", Saved.class, ApiTypes.record(Saved.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Saved::name),
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Saved::pos),
                    ApiField.field("count", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Saved::count),
                    Saved::new)),
            ApiTypes.variant("get", Got.class, ApiTypes.record(Got.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Got::name),
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(Got::pos),
                    Got::new)),
            ApiTypes.variant("list", Listed.class, ApiTypes.record(Listed.class, null,
                    ApiField.field("waypoints", ApiTypes.list(WAYPOINT, Integer.MAX_VALUE)).required().forGetter(Listed::waypoints),
                    Listed::new)),
            ApiTypes.variant("delete", Deleted.class, ApiTypes.record(Deleted.class, null,
                    ApiField.field("name", WAYPOINT_NAME).required().forGetter(Deleted::name),
                    ApiField.field("existed", ApiTypes.bool()).required().forGetter(Deleted::existed),
                    ApiField.field("count", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Deleted::count),
                    Deleted::new)),
            ApiTypes.variant("clear", Cleared.class, ApiTypes.record(Cleared.class, null,
                    ApiField.field("cleared", ApiTypes.integer(0, Integer.MAX_VALUE)).required().forGetter(Cleared::cleared),
                    Cleared::new)));

    public static final MethodSpec<WaypointOp, WaypointReply> WAYPOINT_SPEC = MethodSpec.of("mc.bot.waypoint", WAYPOINT_PARAMS,
                    WAYPOINT_REPLY,
                    "Named positions this client keeps in memory, for goto's waypoint target; not saved to disk. A name "
                            + "that was never saved is invalid_params at /name. This client's own player only.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);
}
