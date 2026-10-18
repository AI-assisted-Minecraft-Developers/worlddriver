package net.magicterra.worlddriver.api.methods.shared;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;

/**
 * What every method that starts a body task answers: {@code refused} when nothing started, or
 * {@code accepted} with the action's state. The state of one action only moves from running to exactly
 * one terminal state and stays there; the wait an {@code awaitMs} asked for is reported beside it, never
 * as a state of its own.
 */
public final class Responses {
    private Responses() {}

    /** Why a valid request started nothing. */
    public enum RefusalReason implements WireName {
        BOT_UNAVAILABLE("bot_unavailable"),
        UNSUPPORTED_CAPABILITY("unsupported_capability"),
        INVALID_WORLD_TARGET("invalid_world_target"),
        /** The bot is held by another script run. */
        BUSY("busy"),
        /** As many actions are running as the server admits. */
        ACTION_LIMIT("action_limit");

        private final String wire;
        RefusalReason(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /**
     * With {@code bot_unavailable}: what stops the bot from acting, for a program to switch on. The bot's
     * layers spell these words themselves ({@code Readiness.Reason}), since they may not name the api.
     */
    public enum Unready implements WireName {
        /** No world is open on the client, or it has no player. */
        NO_PLAYER("no_player"),
        LOADING("loading"),
        DEAD("dead"),
        /** The singleplayer game is paused by a screen. */
        PAUSED("paused"),
        SLEEPING("sleeping"),
        /** The chunk under the bot has not arrived. */
        CHUNK_UNLOADED("chunk_unloaded"),
        /** The bot's entity lacks a capability the verb needs: equipment, block interaction, menus. */
        NO_HANDS("no_hands"),
        /** The name given for the bot names no registered bot. */
        UNKNOWN_BOT("unknown_bot");

        private final String wire;
        Unready(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }

        /** The word {@code wire} spells, or empty for one this list does not have. */
        public static Optional<Unready> of(String wire) {
            for (Unready u : values()) if (u.wire.equals(wire)) return Optional.of(u);
            return Optional.empty();
        }
    }

    public enum FailureReason implements WireName {
        PROCESS_FAILED("process_failed");

        private final String wire;
        FailureReason(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public enum CancelReason implements WireName {
        /** {@code mc.bot.cancel}, or the script run that started it was stopped. */
        REQUESTED("requested"),
        WORLD_CLOSED("world_closed"),
        BOT_REMOVED("bot_removed"),
        TEST_RESET("test_reset"),
        /** The game client left the server the bot was in. */
        CONNECTION_CLOSED("connection_closed");

        private final String wire;
        CancelReason(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /** One action at one moment. {@code bot} is the ref it was started on, never re-resolved. */
    public sealed interface ActionSnapshot<R> permits Running, Succeeded, Failed, Cancelled, Superseded {
        String actionId();
        String bot();
        default boolean terminal() { return !(this instanceof Running<R>); }
    }

    public record Running<R>(String actionId, String bot) implements ActionSnapshot<R> {}
    public record Succeeded<R>(String actionId, String bot, R result) implements ActionSnapshot<R> {}
    public record Failed<R>(String actionId, String bot, FailureReason reason, String message) implements ActionSnapshot<R> {}
    public record Cancelled<R>(String actionId, String bot, CancelReason reason) implements ActionSnapshot<R> {}
    public record Superseded<R>(String actionId, String bot, String replacementActionId) implements ActionSnapshot<R> {}

    /**
     * A method may answer some requests at once instead of starting an action (goto's {@code plan: 'score'}),
     * with variants it adds through {@link #response(String, ApiType, UnionType.Variant[])}. Each such variant
     * is listed here too, so a Java caller's switch over the answer is checked for every case it can get.
     */
    public sealed interface ActionResponse<R> permits Accepted, Refused, Scored, Turned {}

    /**
     * What a method that acts on a body within the call answers (holding an item, one click): {@code done}
     * with what came of it, or {@link Refused} with the same reasons a task is refused for.
     */
    public sealed interface ImmediateResponse permits Refused, Held, Used, Attacked, Equipped, Stopped, Status {}

    /** {@code waitTimedOut}: the call's wait ran out with the action still running; always false for awaitMs 0. */
    public record Accepted<R>(ActionSnapshot<R> action, boolean waitTimedOut) implements ActionResponse<R> {}
    public record Refused<R>(RefusalReason reason, String message, Optional<Unready> unready)
            implements ActionResponse<R>, ImmediateResponse {
        public Refused(RefusalReason reason, String message) { this(reason, message, Optional.empty()); }
    }

    // The families' own answers to the two unions. A sealed interface's variants must share its package, so
    // they are declared here; each family's contract still defines how its variant is written.

    /** {@code mc.bot.goto}'s {@code plan: 'score'} answer. */
    public record Scored(int cells, List<GotoContracts.Segment> segments, double detourRatio,
                         boolean sightBudgetExhausted, boolean snapshotTruncated)
            implements ActionResponse<GotoContracts.Result> {}

    /** {@code mc.bot.lookAt}'s instant look: the body already faces this way. */
    public record Turned(double yaw, double pitch) implements ActionResponse<InteractContracts.Looked> {}

    public record Held(String held) implements ImmediateResponse {}

    public record Used(InteractContracts.Hand hand, InteractContracts.Interaction result, boolean consumed,
                       InteractContracts.UsedOn on) implements ImmediateResponse {}

    public record Attacked(int entityId, String type, boolean alive, double distance) implements ImmediateResponse {}

    public record Equipped(InteractContracts.Profile profile, List<String> equipped, InteractContracts.Loadout loadout,
                           List<String> lowDurability, List<InteractContracts.ArmorSlot> missing)
            implements ImmediateResponse {}

    /**
     * {@code mc.bot.cancel}: {@code cancelled} is what was running and was stopped, empty when nothing matched.
     * {@code action}: the named action as it now stands, which is its earlier ending when it had already ended.
     */
    public record Stopped(List<String> cancelled, Optional<ActionSnapshot<Object>> action) implements ImmediateResponse {}

    /** {@code mc.bot.status}: {@code status} is the asked bot; absent when no bot was named and there is no client. */
    public record Status(List<ControlContracts.BotSummary> bots, Optional<ControlContracts.BotStatus> status)
            implements ImmediateResponse {}

    /** The bot ref of this client's own player, every bot parameter's default. */
    public static final String SELF = "self";

    /** {@code self}, {@code player:<name>}, {@code mob:<name>}, {@code npc:<name>} or {@code entity:<uuid>}. */
    public static final ApiType<String> BOT_REF = ApiTypes.pattern(
                    "self|(player|mob|npc):[^\\s:]+|entity:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
                    "self, player:<name>, mob:<name>, npc:<name> or entity:<uuid>; a name has no whitespace or colon")
            .maxLength(128)
            .tsAlias("BotRef", "'self' | `player:${string}` | `mob:${string}` | `npc:${string}` | `entity:${string}`")
            .describe("self (this client's own player), player:<name>, mob:<name>, npc:<name>, or entity:<uuid>, which"
                    + " takes over that mob on its first order.");

    public static final ApiType<String> ACTION_ID = ApiTypes.string(1, 64)
            .describe("Opaque; do not take it apart.");

    /** {@code awaitMs}: how long this call may wait for the action to end; 0 returns at once. */
    public static final ApiType<Integer> AWAIT_MS = ApiTypes.integer(0, 600_000)
            .describe("Wait at most this many ms for the action to end; 0 returns at once. The action keeps "
                    + "running when the wait runs out.");

    public static final ApiType<RefusalReason> REFUSAL_REASON = ApiTypes.enumOf("RefusalReason", RefusalReason.class);
    public static final ApiType<Unready> UNREADY = ApiTypes.enumOf("Unready", Unready.class);
    public static final ApiType<FailureReason> FAILURE_REASON = ApiTypes.enumOf("FailureReason", FailureReason.class);
    public static final ApiType<CancelReason> CANCEL_REASON = ApiTypes.enumOf("CancelReason", CancelReason.class);

    /** The snapshot union for actions whose result is {@code result}, declared to TypeScript as {@code <name>Action}. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <R> UnionType<ActionSnapshot<R>> snapshot(String name, ApiType<R> result) {
        RecordType<Running<R>> running = ApiTypes.record(Running.class, null,
                ApiField.field("actionId", ACTION_ID).required().forGetter(Running<R>::actionId),
                ApiField.field("bot", BOT_REF).required().forGetter(Running<R>::bot),
                Running::new);
        RecordType<Succeeded<R>> succeeded = ApiTypes.record(Succeeded.class, null,
                ApiField.field("actionId", ACTION_ID).required().forGetter(Succeeded<R>::actionId),
                ApiField.field("bot", BOT_REF).required().forGetter(Succeeded<R>::bot),
                ApiField.field("result", result).required().forGetter(Succeeded<R>::result),
                Succeeded::new);
        RecordType<Failed<R>> failed = ApiTypes.record(Failed.class, null,
                ApiField.field("actionId", ACTION_ID).required().forGetter(Failed<R>::actionId),
                ApiField.field("bot", BOT_REF).required().forGetter(Failed<R>::bot),
                ApiField.field("reason", FAILURE_REASON).required().forGetter(Failed<R>::reason),
                ApiField.field("message", ApiTypes.string()).required().forGetter(Failed<R>::message),
                Failed::new);
        RecordType<Cancelled<R>> cancelled = ApiTypes.record(Cancelled.class, null,
                ApiField.field("actionId", ACTION_ID).required().forGetter(Cancelled<R>::actionId),
                ApiField.field("bot", BOT_REF).required().forGetter(Cancelled<R>::bot),
                ApiField.field("reason", CANCEL_REASON).required().forGetter(Cancelled<R>::reason),
                Cancelled::new);
        RecordType<Superseded<R>> superseded = ApiTypes.record(Superseded.class, null,
                ApiField.field("actionId", ACTION_ID).required().forGetter(Superseded<R>::actionId),
                ApiField.field("bot", BOT_REF).required().forGetter(Superseded<R>::bot),
                ApiField.field("replacementActionId", ACTION_ID).required().forGetter(Superseded<R>::replacementActionId),
                Superseded::new);
        return ApiTypes.taggedUnion(name + "Action", "state",
                (UnionType.Variant) ApiTypes.variant("running", (Class) Running.class, (RecordType) running),
                (UnionType.Variant) ApiTypes.variant("succeeded", (Class) Succeeded.class, (RecordType) succeeded),
                (UnionType.Variant) ApiTypes.variant("failed", (Class) Failed.class, (RecordType) failed),
                (UnionType.Variant) ApiTypes.variant("cancelled", (Class) Cancelled.class, (RecordType) cancelled),
                (UnionType.Variant) ApiTypes.variant("superseded", (Class) Superseded.class, (RecordType) superseded));
    }

    /**
     * The response union of a method whose action result is {@code result}: {@code <name>Response}, tagged
     * on {@code outcome}, with {@code extra} outcomes after accepted and refused.
     */
    @SafeVarargs
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <R> UnionType<ActionResponse<R>> response(String name, ApiType<R> result,
                                                            UnionType.Variant<ActionResponse<R>, ?>... extra) {
        UnionType<ActionSnapshot<R>> snap = snapshot(name, result);
        RecordType<Accepted<R>> accepted = ApiTypes.record(Accepted.class, null,
                ApiField.field("action", snap).required().forGetter(Accepted<R>::action),
                ApiField.field("waitTimedOut", ApiTypes.bool())
                        .describe("True only when awaitMs ran out with the action still running.")
                        .required().forGetter(Accepted<R>::waitTimedOut),
                Accepted::new);
        UnionType.Variant[] all = new UnionType.Variant[2 + extra.length];
        all[0] = ApiTypes.variant("accepted", (Class) Accepted.class, (RecordType) accepted);
        all[1] = ApiTypes.variant("refused", (Class) Refused.class, (RecordType) refused());
        System.arraycopy(extra, 0, all, 2, extra.length);
        return ApiTypes.taggedUnion(name + "Response", "outcome", all);
    }

    /** {@code <name>Response}, tagged on {@code outcome}: {@code done} as {@code done} describes it, or refused. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <D extends ImmediateResponse> UnionType<ImmediateResponse> immediate(String name, Class<D> type,
                                                                                    RecordType<D> done) {
        return ApiTypes.taggedUnion(name + "Response", "outcome",
                ApiTypes.variant("done", type, done),
                (UnionType.Variant) ApiTypes.variant("refused", (Class) Refused.class, (RecordType) refused()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RecordType<Refused<?>> refused() {
        return (RecordType) ApiTypes.record((Class) Refused.class, null,
                ApiField.field("reason", REFUSAL_REASON).required().forGetter(Refused<?>::reason),
                ApiField.field("message", ApiTypes.string()).required().forGetter(Refused<?>::message),
                ApiField.field("unready", UNREADY)
                        .describe("With bot_unavailable: what stops the bot from acting now, when the driver can tell.")
                        .optional().forGetter(Refused<?>::unready),
                (RefusalReason r, String m, Optional<Unready> u) -> new Refused<>(r, m, u));
    }
}
