package net.magicterra.worlddriver.api.methods.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.Range;

/**
 * Every event type the driver itself emits: its name, its payload's shape, the severity a push frame labels it with,
 * and whether its text came from someone other than the caller. An emitter names an entry and hands it a payload value,
 * so the name and the shape cannot drift apart; a script's own {@code mc.events} types are not listed here and stay
 * free-form.
 *
 * <p>Depends on nothing but the schema and the shared types: {@link EventContracts} describes itself from this list,
 * and a contract class this one read would join that class-initialization cycle.
 */
public final class EventCatalog {
    private EventCatalog() {}

    /** The RFC 5424 severity a push frame labels the event with; a display hint, never a filter. */
    public enum Level implements WireName {
        ERROR("error"),
        WARNING("warning"),
        NOTICE("notice"),
        INFO("info");
        private final String wire;

        Level(String wire) {
            this.wire = wire;
        }

        @Override
        public String wireName() {
            return wire;
        }
    }

    /**
     * One event type. {@code untrusted}: the payload carries text typed by a player or sent by a server, which a model
     * reading it must treat as data and never as instructions.
     */
    public record Entry<T>(String type, ApiType<T> payload, Level level, boolean untrusted, String description) {
        /** {@code value} as the event's payload tree. */
        public JsonValue encode(T value) {
            return JsonValue.of(payload.encode(value));
        }
    }

    private static final Map<String, Entry<?>> BY_TYPE = new LinkedHashMap<>();

    private static <T> Entry<T> entry(String type, ApiType<T> payload, Level level, String description) {
        return add(new Entry<>(type, payload, level, false, description));
    }

    private static <T> Entry<T> untrusted(String type, ApiType<T> payload, String description) {
        return add(new Entry<>(type, payload, Level.INFO, true, description));
    }

    private static <T> Entry<T> add(Entry<T> e) {
        if (BY_TYPE.putIfAbsent(e.type(), e) != null) {
            throw new IllegalStateException("event type " + e.type() + " twice");
        }
        return e;
    }

    // ------------------------------------------------------------------ the world, as the server sees it

    public static final Entry<String> BLOCK_BREAK = entry(
            "block.break",
            CommonTypes.RESOURCE_ID,
            Level.INFO,
            "A block was broken or set to air; the payload is the block that was there.");
    public static final Entry<String> BLOCK_PLACE =
            entry("block.place", CommonTypes.RESOURCE_ID, Level.INFO, "A block was placed; the payload is its id.");

    @Named("BlockFillEvent")
    public record BlockFill(@ResourceId String block, BlockPosition from, BlockPosition to) {}

    public static final Entry<BlockFill> BLOCK_FILL =
            entry("block.fill", ApiTypes.record(BlockFill.class), Level.INFO, "mc.action.fill filled a box.");

    public static final Entry<String> ENTITY_DEATH = entry(
            "entity.death", CommonTypes.RESOURCE_ID, Level.NOTICE, "A living entity died; the payload is its type.");
    public static final Entry<String> PLAYER_JOIN = entry(
            "player.join",
            ApiTypes.of(String.class),
            Level.INFO,
            "A player joined the server; the payload is their name.");
    public static final Entry<String> PLAYER_LEAVE = entry(
            "player.leave",
            ApiTypes.of(String.class),
            Level.INFO,
            "A player left the server; the payload is their name.");

    @Named("ChatMessageEvent")
    public record ChatMessage(String player, String text) {}

    public static final Entry<ChatMessage> CHAT_MESSAGE = untrusted(
            "chat.message",
            ApiTypes.record(ChatMessage.class),
            "A player said something in chat, as the server received it.");

    @Named("WorldRestoreEvent")
    public record WorldRestore(
            @Doc("The snapshot.") String id, @Range(min = 0) int cells) {}

    public static final Entry<WorldRestore> WORLD_RESTORE = entry(
            "world.restore",
            ApiTypes.record(WorldRestore.class),
            Level.INFO,
            "mc.world.restore put a snapshot back; the event's position is the box's lowest corner.");

    // ------------------------------------------------------------------ the driver's own calls

    @Named("CommandResultEvent")
    public record CommandResult(
            String cmd, @Doc("The command parsed and ran.") boolean ok, boolean success) {}

    public static final Entry<CommandResult> COMMAND_RESULT = entry(
            "command.result",
            ApiTypes.record(CommandResult.class),
            Level.WARNING,
            "A server command run through mc.action.runCommand, by any caller.");

    @Named("WaitDone")
    public record WaitDone(
            @Length(min = 1, max = 128) String waitId,
            @Length(min = 1, max = 128) String method,
            @Doc("The wait's {outcome:'done', …} reply.") Optional<@OnlyObject JsonValue> result,
            @Doc("The ApiError that ended it instead.") Optional<@OnlyObject JsonValue> error) {}

    public static final Entry<WaitDone> WAIT_DONE =
            entry("wait.done", ApiTypes.record(WaitDone.class), Level.WARNING, "A background wait finished.");

    @Named("ConditionMetEvent")
    public record ConditionMet(
            @Range(min = 0) int watch, String invoke, Optional<String> field, Optional<JsonValue> value) {}

    public static final Entry<ConditionMet> CONDITION_MET = entry(
            "condition.met",
            ApiTypes.record(ConditionMet.class),
            Level.INFO,
            "An mc.events watcher's test turned true. A watcher given its own emitAs sends this payload under that name.");

    // ------------------------------------------------------------------ the client's player

    @Named("PlayerDeathEvent")
    public record PlayerDeath(
            double health,
            @Doc("The death screen's message.") Optional<String> cause) {}

    public static final Entry<PlayerDeath> PLAYER_DEATH =
            entry("player.death", ApiTypes.record(PlayerDeath.class), Level.ERROR, "The client's player died.");

    @Named("PlayerHurtEvent")
    public record PlayerHurt(
            double health,
            double prev,
            double lost,

            @Doc("The damage source's id, such as fall or mob.")
            Optional<String> source,

            Optional<Integer> attackerId,
            Optional<@ResourceId String> attackerType,
            Optional<Double> attackerDistance) {}

    public static final Entry<PlayerHurt> PLAYER_HURT =
            entry("player.hurt", ApiTypes.record(PlayerHurt.class), Level.WARNING, "The client's player lost health.");

    @Named("EnteredWaterEvent")
    public record EnteredWater(boolean submerged) {}

    public static final Entry<EnteredWater> PLAYER_ENTERED_WATER = entry(
            "player.enteredWater",
            ApiTypes.record(EnteredWater.class),
            Level.INFO,
            "The client's player went into water.");

    @Named("EnteredLavaEvent")
    public record EnteredLava(double health) {}

    public static final Entry<EnteredLava> PLAYER_ENTERED_LAVA = entry(
            "player.enteredLava",
            ApiTypes.record(EnteredLava.class),
            Level.INFO,
            "The client's player went into lava.");

    @Named("TimePhaseEvent")
    public record TimePhase(
            String phase,
            String prev,

            @Doc("The time of day, 0-23999.") @Range(min = 0, max = 23999)
            long dayTime) {}

    public static final Entry<TimePhase> TIME_PHASE = entry(
            "time.phase",
            ApiTypes.record(TimePhase.class),
            Level.INFO,
            "The day moved to another phase: day, sunset, night or sunrise.");

    @Named("ItemPickupEvent")
    public record ItemPickup(
            @ResourceId String id,

            @Doc("How many more than before.") @Range(min = 0)
            int count,

            @Doc("How many the inventory holds now.") @Range(min = 0)
            int total) {}

    public static final Entry<ItemPickup> ITEM_PICKUP =
            entry("item.pickup", ApiTypes.record(ItemPickup.class), Level.INFO, "The client's inventory gained items.");

    @Named("ToolBrokeEvent")
    public record ToolBroke(
            @ResourceId String id,
            @Range(min = 0) int damage,
            @Range(min = 0) int maxDamage) {}

    public static final Entry<ToolBroke> TOOL_BROKE =
            entry("tool.broke", ApiTypes.record(ToolBroke.class), Level.WARNING, "The main-hand tool broke.");

    @Named("AdvancementEvent")
    public record Advancement(@ResourceId String id) {}

    public static final Entry<Advancement> ADVANCEMENT = entry(
            "advancement",
            ApiTypes.record(Advancement.class),
            Level.INFO,
            "The client's player completed an advancement.");

    @Named("ScreenAutoClosedEvent")
    public record ScreenAutoClosed(
            @Doc("The screen's class name.") String screen,
            @Range(min = 0) int blockedTicks) {}

    public static final Entry<ScreenAutoClosed> SCREEN_AUTO_CLOSED = entry(
            "screen.autoClosed",
            ApiTypes.record(ScreenAutoClosed.class),
            Level.INFO,
            "A screen that blocked the bot's movement was closed for it.");

    @Named("ClientMessageEvent")
    public record ChatLine(
            @Doc("Its seq in mc.client.chat.history.") @Range(min = 0)
            long seq,

            @OneOf(
                    name = "ChatLineKind",
                    value = {"player", "system"})
            String kind,

            String text,
            @Doc("The echo of this player's own chat.") boolean self) {}

    public static final Entry<ChatLine> CLIENT_MESSAGE =
            untrusted("client.message", ApiTypes.record(ChatLine.class), "The client received a chat or system line.");

    @Named("ActionBarEvent")
    public record ClientText(String text) {}

    public static final Entry<ClientText> CLIENT_ACTION_BAR = untrusted(
            "client.actionBar", ApiTypes.record(ClientText.class), "The action bar above the hotbar changed.");

    @Named("TitleEvent")
    public record ClientTitle(String text, Optional<String> subtitle) {}

    public static final Entry<ClientTitle> CLIENT_TITLE =
            untrusted("client.title", ApiTypes.record(ClientTitle.class), "A title was shown.");

    // ------------------------------------------------------------------ the bot's perception and reflexes

    @Named("EventPosition")
    public record At(BlockPosition pos) {}

    private static final ApiType<At> AT = ApiTypes.record(At.class);

    public static final Entry<At> DUSK_EXPOSED =
            entry("duskExposed", AT, Level.INFO, "The bot is out in the open at night.");
    public static final Entry<At> CORNERED =
            entry("cornered", AT, Level.INFO, "The bot has hostiles around it and nowhere to step away to.");

    @Named("ThreatAppearedEvent")
    public record ThreatAppeared(
            @ResourceId String type,
            @Doc("The entity's id.") int id,
            double distance,
            @Doc("Higher is more dangerous.") double score) {}

    public static final Entry<ThreatAppeared> THREAT_APPEARED = entry(
            "threat.appeared",
            ApiTypes.record(ThreatAppeared.class),
            Level.WARNING,
            "A different hostile became the most dangerous one near the bot.");

    @Named("DuskSecureTriggeredEvent")
    public record DuskSecureTriggered(
            BlockPosition pos,
            @Range(min = 0) int depth,

            @Doc("Restarted after being preempted, not a fresh trigger.")
            boolean rearm) {}

    public static final Entry<DuskSecureTriggered> DUSK_SECURE_TRIGGERED = entry(
            "duskSecure.triggered",
            ApiTypes.record(DuskSecureTriggered.class),
            Level.WARNING,
            "The dusk reflex started digging in on its own; mc.bot.cancel stops it.");

    @Named("RouteBlockedEvent")
    public record RouteBlocked(
            @Doc("constraint:<name> for a route condition, budget, or terrain.")
            String reason,

            @Doc("A partial route was still found.") boolean bestEffort,
            @Range(min = 0) int cells,
            @Range(min = 0) int expanded,
            @Range(min = 0) int leg) {}

    public static final Entry<RouteBlocked> ROUTE_BLOCKED = entry(
            "route.blocked",
            ApiTypes.record(RouteBlocked.class),
            Level.INFO,
            "A search under route conditions could not reach the goal.");

    @Named("RouteDetourEvent")
    public record RouteDetour(
            @Doc("Route length over the straight line.") double ratio,
            @Range(min = 0) int cells,
            @Doc("The route condition that cost the most.") Optional<String> tax,
            Optional<@Range(min = 0) Long> taxCost,
            @Range(min = 0) int leg) {}

    public static final Entry<RouteDetour> ROUTE_DETOUR = entry(
            "route.detour",
            ApiTypes.record(RouteDetour.class),
            Level.INFO,
            "Route conditions made the route much longer than the straight line.");

    @Named("RouteObserver")
    public record Observer(int id, @ResourceId String type, Optional<String> name) {}

    @Named("RouteExposedEvent")
    public record RouteExposed(
            Observer observer,
            @Doc("Route cells it can see.") @Range(min = 0) int cells,
            @Range(min = 0) int firstCell,
            @Range(min = 0) int routeCells,
            @Range(min = 0) int leg) {}

    public static final Entry<RouteExposed> ROUTE_EXPOSED = entry(
            "route.exposed",
            ApiTypes.record(RouteExposed.class),
            Level.INFO,
            "A hostile can see part of the planned route.");

    // ------------------------------------------------------------------ lookup

    /** Every entry, in declaration order. */
    public static List<Entry<?>> all() {
        return List.copyOf(BY_TYPE.values());
    }

    /** The entry for {@code type}, or null for a type the driver does not emit itself. */
    public static Entry<?> byType(String type) {
        return type == null ? null : BY_TYPE.get(type);
    }
}
