package net.magicterra.worlddriver.api.methods.control;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.Responses.ActionId;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.methods.shared.Responses.ImmediateResponse;
import net.magicterra.worlddriver.api.methods.shared.Responses.Rostered;
import net.magicterra.worlddriver.api.methods.shared.Responses.Status;
import net.magicterra.worlddriver.api.methods.shared.Responses.Stopped;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.wait.WaitContracts;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.magicterra.worlddriver.api.schema.annotation.Wire;

/**
 * Reading and stopping what the bots do, and which bots there are: {@code mc.bot.status}, {@code mc.bot.cancel},
 * {@code mc.bot.waypoint} and {@code mc.bot.roster}.
 */
public final class ControlContracts {
    private ControlContracts() {}

    // ------------------------------------------------------------------ cancel

    /**
     * What {@code mc.bot.cancel} stops by name: everything, a process by its kind, or a reflex chain. The bot's
     * layers spell these names themselves, since they may not name the api; {@code ProcessNameTest} holds the
     * two lists equal.
     */
    @Named("ProcessName")
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

    @Named("CancelParams")
    public record CancelParams(
            @Doc("Cancel this action, on whichever bot runs it.") Optional<@ActionId String> actionId,
            @Doc("Without actionId: whose processes. Omitted: self.") Optional<@BotRef String> bot,
            @Doc("Without actionId: all (the default), a process kind (goto, mine, …) or a reflex chain (combat, "
                    + "bunker, retreat, …).")
            Optional<ProcessName> process) implements SelfChecked {

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

        @Override public void check(DecodeContext at) {
            if (actionId.isEmpty()) return;
            if (bot.isPresent()) at.field("bot").fail(ApiIssue.Code.INVALID_VALUE, "an action names its own bot");
            if (process.isPresent()) at.field("process").fail(ApiIssue.Code.INVALID_VALUE, "cancel an action or processes, not both");
        }
    }

    public static final ApiType<CancelParams> CANCEL_PARAMS = ApiTypes.record(CancelParams.class);

    public static final MethodSpec<CancelParams, ImmediateResponse> CANCEL = MethodSpec.of("mc.bot.cancel", CANCEL_PARAMS,
                    Responses.immediate("Cancel",
                            ApiTypes.record(Stopped.class, Map.of("action", WaitContracts.ANY_ACTION))),
                    "Stop an action by its actionId, which ends it cancelled with reason requested, or a bot's processes "
                            + "by name; all also releases the held inputs and stands every reflex chain down. Works on a "
                            + "bot that cannot act. An unknown or expired actionId is action_not_found.")
            .effects(MethodSpec.Effect.BODY_TASK);

    // ------------------------------------------------------------------ status

    @Named("StatusParams")
    public record StatusParams(
            @Doc("Omitted: self when there is a client; the bots list either way.") Optional<@BotRef String> bot) {

        /** Self's status, with the registered bots listed. */
        public static StatusParams self() { return new StatusParams(Optional.empty()); }
    }

    /** One process slot. {@code lastError} also holds success summaries; {@code lastProcessEnd} says how a task ended. */
    @Named("ProcessSlot")
    public record Slot(
            boolean active,
            @Doc("For display only; its wording is not a format to parse.") Optional<String> goal,
            Optional<BlockPosition> target,
            @Range(min = 0) int pathLen,
            @Range(min = 0) int pathStep,
            Optional<@Range(min = 0) Long> startedAtMs,
            @Doc("Why the slot last stopped: an Unready word when the bot could not act (no_hands), else a message "
                    + "for display.")
            Optional<String> lastError,
            Optional<Boolean> goalReached,
            Optional<String> endReason,
            Optional<Double> finalDist) {}

    @Named("ProcessSlots")
    public record Slots(
            @Wire("goto") Slot goTo,
            Slot mine,
            @Doc("build, clearArea, farm and construct.") Slot builder,
            Slot follow,
            Slot explore,
            Slot runAway,
            @Doc("The retreat reflex.") Slot retreat,
            Slot look,
            Slot elytra,
            Slot craft,
            Slot smelt,
            Slot escape,
            Slot bunker,
            Slot combat,
            @Doc("A route preview's search.") Slot plan) {}

    /** How the last task a caller started ended: {@code error} is absent only when it did what it was asked. */
    @Named("ProcessEnd")
    public record ProcessEnd(String kind, Optional<String> error) {}

    @Named("PathStats")
    public record PathStats(
            @Range(min = 0) int expanded,
            @Range(min = 0) long ms,
            boolean goalReached,
            double finalCost,
            @Range(min = 0) int pathLen,
            boolean sightBudgetExhausted,
            boolean snapshotTruncated) {}

    @Named("ChainBail")
    public record Bail(String reason, long ticksLeft) {}

    @Named("ChainState")
    public record ChainState(
            @Doc("Its bid on the last tick; the highest steers.") double priority,
            Optional<String> episode,
            Optional<Bail> bail,
            @Doc("The phase of the process the chain holds, e.g. bunker DIG_DOWN.") Optional<String> detail) {}

    @Named("CombatTally")
    public record CombatTally(
            @Range(min = 0) int swings,
            @Range(min = 0) int wellTimed,
            @Range(min = 0) int crits,
            @Range(min = 0) int kills) {}

    /** What only this client's player has: the reflex chains that bid against its task. */
    @Named("Reflexes")
    public record Reflexes(
            boolean paused,
            @Doc("The task's phase, e.g. bunker SEALED.") Optional<String> activeProcessDetail,
            @Doc("What steers now; absent when nothing does.") Optional<String> activeChain,
            @Doc("A reflex outbids the task, which waits.") boolean userTaskSuspended,
            @Length(max = 64) Map<String, ChainState> chains,
            @Doc("The most recent search, by any process.") Optional<PathStats> lastPath,
            @Doc("The water-bucket clutch: idle, lip (planned, waiting to walk off) or falling.")
            @OneOf(name = "ClutchPhase", value = {"idle", "lip", "falling"}) String clutch,
            @Doc("This engagement's counts.") CombatTally combat) {}

    @Named("BotStatus")
    public record BotStatus(
            @BotRef String bot,
            @Doc("A task holds it.") boolean busy,
            Optional<String> activeProcess,
            Optional<ProcessEnd> lastProcessEnd,
            Slots slots,
            @Doc("self and the server's bots.") Optional<Reflexes> reflexes) {}

    @Named("BotSummary")
    public record BotSummary(
            @BotRef String id,
            String kind,
            @Doc("Absent when its entity is gone.") Optional<@EntityId Integer> entityId,
            Optional<BlockPosition> pos,
            boolean busy) {}

    public static final ApiType<StatusParams> STATUS_PARAMS = ApiTypes.record(StatusParams.class);

    public static final MethodSpec<StatusParams, ImmediateResponse> STATUS = MethodSpec.of("mc.bot.status", STATUS_PARAMS,
                    Responses.immediate("Status", ApiTypes.record(Status.class)),
                    "Every process slot of one bot, with how its last task ended, plus the list of addressable bots. "
                            + "Works where nothing else does: a dead or paused bot, and a dedicated server.")
            .effects(MethodSpec.Effect.SNAPSHOT_READ)
            .asPollable();

    // ------------------------------------------------------------------ waypoint

    @Tag("op") @Named("WaypointParams")
    public sealed interface WaypointOp permits Save, Get, ListAll, Delete, Clear {}
    @Variant("save")
    public record Save(
            @Length(min = 1, max = 64) String name,
            @Doc("Omitted: where the player stands.") Optional<BlockPosition> pos) implements WaypointOp {}
    @Variant("get")
    public record Get(@Length(min = 1, max = 64) String name) implements WaypointOp {}
    @Variant("list")
    public record ListAll() implements WaypointOp {}
    @Variant("delete")
    public record Delete(@Length(min = 1, max = 64) String name) implements WaypointOp {}
    @Variant("clear")
    public record Clear() implements WaypointOp {}

    @Named("Waypoint")
    public record Waypoint(@Length(min = 1, max = 64) String name, BlockPosition pos) {}

    @Tag("op") @Named("WaypointReply")
    public sealed interface WaypointReply permits Saved, Got, Listed, Deleted, Cleared {}
    @Variant("save")
    public record Saved(
            @Length(min = 1, max = 64) String name,
            BlockPosition pos,
            @Range(min = 0) int count) implements WaypointReply {}
    @Variant("get")
    public record Got(@Length(min = 1, max = 64) String name, BlockPosition pos) implements WaypointReply {}
    @Variant("list")
    public record Listed(List<Waypoint> waypoints) implements WaypointReply {}
    @Variant("delete")
    public record Deleted(
            @Length(min = 1, max = 64) String name,
            boolean existed,
            @Range(min = 0) int count) implements WaypointReply {}
    @Variant("clear")
    public record Cleared(@Range(min = 0) int cleared) implements WaypointReply {}

    public static final UnionType<WaypointOp> WAYPOINT_PARAMS = ApiTypes.union(WaypointOp.class);

    public static final UnionType<WaypointReply> WAYPOINT_REPLY = ApiTypes.union(WaypointReply.class);

    public static final MethodSpec<WaypointOp, WaypointReply> WAYPOINT_SPEC = MethodSpec.of("mc.bot.waypoint", WAYPOINT_PARAMS,
                    WAYPOINT_REPLY,
                    "Named positions this client keeps in memory, for goto's waypoint target; not saved to disk. A name "
                            + "that was never saved is invalid_params at /name. This client's own player only.")
            .effects(MethodSpec.Effect.CONFIG_WRITE);

    // ------------------------------------------------------------------ roster

    /** A bot's own name, held to what a player name may be. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[A-Za-z0-9_]{1,16}", expected = "letters, digits and underscores, at most 16, as a player name")
    public @interface BotName {}

    @Tag("op") @Named("RosterParams")
    public sealed interface RosterOp permits Spawn, Adopt, Remove {}
    @Variant("spawn")
    public record Spawn(
            @Doc("The bot is player:<name>.") @BotName String name,
            @Doc("Omitted: the saved one when pos is omitted too, else minecraft:overworld.")
            Optional<@ResourceId String> dimension,
            @Doc("Omitted: where the bot's save left it (if dimension is omitted or the saved one), else the world "
                    + "spawn. Given: it is moved there.") Optional<BlockPosition> pos) implements RosterOp {}
    @Variant("adopt")
    public record Adopt(
            @Doc("The mob to take over.") @EntityId int entityId,
            @Doc("The bot is mob:<name>. Omitted: entity:<uuid>.") Optional<@BotName String> name) implements RosterOp {}
    @Variant("remove")
    public record Remove(@BotRef String bot) implements RosterOp, SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (Responses.SELF.equals(bot)) at.field("bot").fail(ApiIssue.Code.INVALID_VALUE,
                    "self is this client's own player, not a bot on the roster");
        }
    }


    public static final UnionType<RosterOp> ROSTER_PARAMS = ApiTypes.union(RosterOp.class);

    public static final MethodSpec<RosterOp, ImmediateResponse> ROSTER = MethodSpec.of("mc.bot.roster", ROSTER_PARAMS,
                    Responses.immediate("Roster", ApiTypes.record(Rostered.class)),
                    "Which bots exist. spawn joins a server-side player; adopt takes a mob over, its own AI off until "
                            + "it is removed; remove ends the bot's running action as bot_removed, gives a mob its AI "
                            + "back and takes a player out of the world. done names the bot. A taken name, or an entity "
                            + "that is not a mob, a player or already a bot, is invalid_world_target.")
            .effects(MethodSpec.Effect.WORLD_WRITE);
}
