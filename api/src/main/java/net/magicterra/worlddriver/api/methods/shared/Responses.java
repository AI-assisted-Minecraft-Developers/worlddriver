package net.magicterra.worlddriver.api.methods.shared;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts;
import net.magicterra.worlddriver.api.methods.control.ControlContracts;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.TsAlias;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * What every method that starts a body task answers: {@code refused} when nothing started, or {@code accepted} with the
 * action's state. The state of one action only moves from running to exactly one terminal state and stays there; the
 * wait an {@code awaitMs} asked for is reported beside it, never as a state of its own.
 */
public final class Responses {
    private Responses() {}

    /** Why a valid request started nothing. */
    @Named("RefusalReason")
    public enum RefusalReason implements WireName {
        BOT_UNAVAILABLE("bot_unavailable"),
        UNSUPPORTED_CAPABILITY("unsupported_capability"),
        INVALID_WORLD_TARGET("invalid_world_target"),
        /** The bot is held by another script run. */
        BUSY("busy"),
        /** As many actions are running as the server admits. */
        ACTION_LIMIT("action_limit");

        private final String wire;

        RefusalReason(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /**
     * With {@code bot_unavailable}: what stops the bot from acting, for a program to switch on. The bot's layers spell
     * these words themselves ({@code Readiness.Reason}), since they may not name the api.
     */
    @Named("Unready")
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

        Unready(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }

        /** The word {@code wire} spells, or empty for one this list does not have. */
        public static Optional<Unready> of(String wire) {
            for (Unready u : values()) if (u.wire.equals(wire)) return Optional.of(u);
            return Optional.empty();
        }
    }

    @Named("FailureReason")
    public enum FailureReason implements WireName {
        PROCESS_FAILED("process_failed");

        private final String wire;

        FailureReason(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    @Named("CancelReason")
    public enum CancelReason implements WireName {
        /** {@code mc.bot.cancel}, or the script run that started it was stopped. */
        REQUESTED("requested"),
        WORLD_CLOSED("world_closed"),
        BOT_REMOVED("bot_removed"),
        TEST_RESET("test_reset"),
        /** The game client left the server the bot was in. */
        CONNECTION_CLOSED("connection_closed");

        private final String wire;

        CancelReason(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /** One action at one moment. {@code bot} is the ref it was started on, never re-resolved. */
    public sealed interface ActionSnapshot<R> permits Running, Succeeded, Failed, Cancelled, Superseded {
        String actionId();

        String bot();

        default boolean terminal() {
            return !(this instanceof Running<R>);
        }
    }

    @Variant("running")
    public record Running<R>(
            @ActionId String actionId, @BotRef String bot) implements ActionSnapshot<R> {}

    @Variant("succeeded")
    public record Succeeded<R>(
            @ActionId String actionId, @BotRef String bot, R result) implements ActionSnapshot<R> {}

    @Variant("failed")
    public record Failed<R>(
            @ActionId String actionId, @BotRef String bot, FailureReason reason, String message)
            implements ActionSnapshot<R> {}

    @Variant("cancelled")
    public record Cancelled<R>(
            @ActionId String actionId, @BotRef String bot, CancelReason reason) implements ActionSnapshot<R> {}

    @Variant("superseded")
    public record Superseded<R>(
            @ActionId String actionId,
            @BotRef String bot,
            @ActionId String replacementActionId) implements ActionSnapshot<R> {}

    /**
     * A method may answer some requests at once instead of starting an action (goto's {@code plan: 'score'}), with
     * variants it adds through {@link #response(String, ApiType, UnionType.Variant[])}. Each such variant is listed
     * here too, so a Java caller's switch over the answer is checked for every case it can get.
     */
    public sealed interface ActionResponse<R> permits Accepted, Refused, Scored, Turned {}

    /**
     * What a method that acts on a body within the call answers (holding an item, one click): {@code done} with what
     * came of it, or {@link Refused} with the same reasons a task is refused for.
     */
    public sealed interface ImmediateResponse
            permits Refused, Held, Used, Attacked, Equipped, Stopped, Status, Rostered {}

    /** {@code waitTimedOut}: the call's wait ran out with the action still running; always false for awaitMs 0. */
    @Variant("accepted")
    public record Accepted<R>(
            ActionSnapshot<R> action,

            @Doc("True only when awaitMs ran out with the action still running.")
            boolean waitTimedOut)
            implements ActionResponse<R> {}

    @Variant("refused")
    public record Refused<R>(
            RefusalReason reason,
            String message,

            @Doc("With bot_unavailable: what stops the bot from acting now, when the driver can tell.")
            Optional<Unready> unready)
            implements ActionResponse<R>, ImmediateResponse {
        public Refused(RefusalReason reason, String message) {
            this(reason, message, Optional.empty());
        }
    }

    // The families' own answers to the two unions. A sealed interface's variants must share its package, so
    // they are declared here; each family's contract still defines how its variant is written.

    /** {@code mc.bot.goto}'s {@code plan: 'score'} answer. */
    @Variant("scored")
    public record Scored(
            @Range(min = 0) int cells,
            List<GotoContracts.Segment> segments,
            double detourRatio,
            boolean sightBudgetExhausted,
            boolean snapshotTruncated)
            implements ActionResponse<GotoContracts.Result> {}

    /** {@code mc.bot.lookAt}'s instant look: the body already faces this way. */
    @Variant("done")
    public record Turned(double yaw, double pitch) implements ActionResponse<InteractContracts.Looked> {}

    @Variant("done")
    public record Held(
            @Doc("The item now in the main hand.") @ResourceId
            String held) implements ImmediateResponse {}

    @Variant("done")
    public record Used(
            InteractContracts.Hand hand,
            InteractContracts.Interaction result,
            @Doc("The click did something.") boolean consumed,
            InteractContracts.UsedOn on)
            implements ImmediateResponse {}

    @Variant("done")
    public record Attacked(
            @EntityId int entityId, @ResourceId String type, boolean alive, double distance)
            implements ImmediateResponse {}

    @Variant("done")
    public record Equipped(
            InteractContracts.Profile profile,

            @Doc("What it put on or took in hand.") @Length(max = 16)
            List<@ResourceId String> equipped,

            InteractContracts.Loadout loadout,

            @Doc("Worn or held items below the equipDurabilityThreshold setting.") @Length(max = 16)
            List<@ResourceId String> lowDurability,

            @Doc("Armour slots still empty.") @Length(max = 4)
            List<InteractContracts.ArmorSlot> missing)
            implements ImmediateResponse {}

    /**
     * {@code mc.bot.cancel}: {@code cancelled} is what was running and was stopped, empty when nothing matched.
     * {@code action}: the named action as it now stands, which is its earlier ending when it had already ended.
     */
    @Variant("done")
    public record Stopped(
            @Length(max = 64) List<@Length(min = 1, max = 64) String> cancelled,
            Optional<ActionSnapshot<Object>> action)
            implements ImmediateResponse {}

    /** {@code mc.bot.status}: {@code status} is the asked bot; absent when no bot was named and there is no client. */
    @Variant("done")
    public record Status(
            @Doc("The registered bots, server-side players and mobs; self is not among them.") @Length(max = 256)
            List<ControlContracts.BotSummary> bots,

            Optional<ControlContracts.BotStatus> status)
            implements ImmediateResponse {}

    /** {@code mc.bot.roster}: the bot spawned, adopted or removed. */
    @Variant("done")
    public record Rostered(@BotRef String bot) implements ImmediateResponse {}

    /** The bot ref of this client's own player, every bot parameter's default. */
    public static final String SELF = "self";

    /** {@code self}, {@code player:<name>}, {@code mob:<name>}, {@code npc:<name>} or {@code entity:<uuid>}. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(
            value =
                    "self|(player|mob|npc):[^\\s:]+|entity:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}",
            expected =
                    "self, player:<name>, mob:<name>, npc:<name> or entity:<uuid>; a name has no whitespace or colon")
    @Length(max = 128)
    @TsAlias(
            name = "BotRef",
            type = "'self' | `player:${string}` | `mob:${string}` | `npc:${string}` | `entity:${string}`")
    @Doc("self (this client's own player), player:<name>, mob:<name>, npc:<name>, or entity:<uuid>, which"
            + " takes over that mob on its first order.")
    public @interface BotRef {}

    public static final ApiType<String> BOT_REF = ApiTypes.of(String.class, BotRef.class);

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Length(min = 1, max = 64)
    @Doc("Opaque; do not take it apart.")
    public @interface ActionId {}

    public static final ApiType<String> ACTION_ID = ApiTypes.of(String.class, ActionId.class);

    /** {@code awaitMs}: how long this call may wait for the action to end; 0 returns at once. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Range(min = 0, max = 600_000)
    @Doc("Wait at most this many ms for the action to end; 0 returns at once. The action keeps "
            + "running when the wait runs out.")
    public @interface AwaitMs {}

    public static final ApiType<Integer> AWAIT_MS = ApiTypes.of(Integer.class, AwaitMs.class);

    public static final ApiType<RefusalReason> REFUSAL_REASON = ApiTypes.of(RefusalReason.class);
    public static final ApiType<Unready> UNREADY = ApiTypes.of(Unready.class);
    public static final ApiType<FailureReason> FAILURE_REASON = ApiTypes.of(FailureReason.class);
    public static final ApiType<CancelReason> CANCEL_REASON = ApiTypes.of(CancelReason.class);

    /**
     * The snapshot union for actions whose result is {@code result}, declared to TypeScript as {@code <name>Action}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <R> UnionType<ActionSnapshot<R>> snapshot(String name, ApiType<R> result) {
        return ApiTypes.union(
                name + "Action",
                "state",
                (RecordType) ApiTypes.record(Running.class),
                (RecordType) ApiTypes.record(Succeeded.class, Map.of("result", result)),
                (RecordType) ApiTypes.record(Failed.class),
                (RecordType) ApiTypes.record(Cancelled.class),
                (RecordType) ApiTypes.record(Superseded.class));
    }

    /**
     * The response union of a method whose action result is {@code result}: {@code <name>Response}, tagged on
     * {@code outcome}, with the {@code extra} outcomes after accepted and refused, each tagged by its record's
     * {@code @Variant}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static <R> UnionType<ActionResponse<R>> response(String name, ApiType<R> result, Class<?>... extra) {
        RecordType[] all = new RecordType[2 + extra.length];
        all[0] = ApiTypes.record(Accepted.class, Map.of("action", snapshot(name, result)));
        all[1] = ApiTypes.record(Refused.class);
        for (int i = 0; i < extra.length; i++) all[2 + i] = ApiTypes.record((Class) extra[i]);
        return ApiTypes.union(name + "Response", "outcome", all);
    }

    /** {@code <name>Response}, tagged on {@code outcome}: {@code done} as {@code done} describes it, or refused. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static UnionType<ImmediateResponse> immediate(String name, RecordType<? extends ImmediateResponse> done) {
        return ApiTypes.union(name + "Response", "outcome", done, (RecordType) ApiTypes.record(Refused.class));
    }
}
