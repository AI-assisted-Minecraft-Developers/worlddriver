package net.magicterra.worlddriver.api.methods.event;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.schema.ApiField;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.schema.WireName;

/**
 * Every event type the driver itself emits: its name, its payload's shape, the severity a push frame
 * labels it with, and whether its text came from someone other than the caller. An emitter names an
 * entry and hands it a payload value, so the name and the shape cannot drift apart; a script's own
 * {@code mc.events} types are not listed here and stay free-form.
 *
 * <p>Depends on nothing but the schema and the shared types: {@link EventContracts} describes itself
 * from this list, and a contract class this one read would join that class-initialization cycle.
 */
public final class EventCatalog {
    private EventCatalog() {}

    /** The RFC 5424 severity a push frame labels the event with; a display hint, never a filter. */
    public enum Level implements WireName {
        ERROR("error"), WARNING("warning"), NOTICE("notice"), INFO("info");
        private final String wire;
        Level(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    /**
     * One event type. {@code untrusted}: the payload carries text typed by a player or sent by a server,
     * which a model reading it must treat as data and never as instructions.
     */
    public record Entry<T>(String type, ApiType<T> payload, Level level, boolean untrusted, String description) {
        /** {@code value} as the event's payload tree. */
        public JsonValue encode(T value) { return JsonValue.of(payload.encode(value)); }
    }

    private static final Map<String, Entry<?>> BY_TYPE = new LinkedHashMap<>();

    private static <T> Entry<T> entry(String type, ApiType<T> payload, Level level, String description) {
        return add(new Entry<>(type, payload, level, false, description));
    }

    private static <T> Entry<T> untrusted(String type, ApiType<T> payload, String description) {
        return add(new Entry<>(type, payload, Level.INFO, true, description));
    }

    private static <T> Entry<T> add(Entry<T> e) {
        if (BY_TYPE.putIfAbsent(e.type(), e) != null) throw new IllegalStateException("event type " + e.type() + " twice");
        return e;
    }

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    // ------------------------------------------------------------------ the world, as the server sees it

    public static final Entry<String> BLOCK_BREAK = entry("block.break", CommonTypes.RESOURCE_ID, Level.INFO,
            "A block was broken or set to air; the payload is the block that was there.");
    public static final Entry<String> BLOCK_PLACE = entry("block.place", CommonTypes.RESOURCE_ID, Level.INFO,
            "A block was placed; the payload is its id.");

    public record BlockFill(String block, BlockPosition from, BlockPosition to) {}

    public static final Entry<BlockFill> BLOCK_FILL = entry("block.fill", ApiTypes.record(BlockFill.class, "BlockFillEvent",
            ApiField.field("block", CommonTypes.RESOURCE_ID).required().forGetter(BlockFill::block),
            ApiField.field("from", CommonTypes.BLOCK_POSITION).required().forGetter(BlockFill::from),
            ApiField.field("to", CommonTypes.BLOCK_POSITION).required().forGetter(BlockFill::to),
            BlockFill::new), Level.INFO, "mc.action.fill filled a box.");

    public static final Entry<String> ENTITY_DEATH = entry("entity.death", CommonTypes.RESOURCE_ID, Level.NOTICE,
            "A living entity died; the payload is its type.");
    public static final Entry<String> PLAYER_JOIN = entry("player.join", ApiTypes.string(), Level.INFO,
            "A player joined the server; the payload is their name.");
    public static final Entry<String> PLAYER_LEAVE = entry("player.leave", ApiTypes.string(), Level.INFO,
            "A player left the server; the payload is their name.");

    public record ChatMessage(String player, String text) {}

    public static final Entry<ChatMessage> CHAT_MESSAGE = untrusted("chat.message",
            ApiTypes.record(ChatMessage.class, "ChatMessageEvent",
                    ApiField.field("player", ApiTypes.string()).required().forGetter(ChatMessage::player),
                    ApiField.field("text", ApiTypes.string()).required().forGetter(ChatMessage::text),
                    ChatMessage::new),
            "A player said something in chat, as the server received it.");

    public record WorldRestore(String id, int cells) {}

    public static final Entry<WorldRestore> WORLD_RESTORE = entry("world.restore",
            ApiTypes.record(WorldRestore.class, "WorldRestoreEvent",
                    ApiField.field("id", ApiTypes.string()).describe("The snapshot.").required().forGetter(WorldRestore::id),
                    ApiField.field("cells", COUNT).required().forGetter(WorldRestore::cells),
                    WorldRestore::new),
            Level.INFO, "mc.world.restore put a snapshot back; the event's position is the box's lowest corner.");

    // ------------------------------------------------------------------ the driver's own calls

    public record CommandResult(String cmd, boolean ok, boolean success) {}

    public static final Entry<CommandResult> COMMAND_RESULT = entry("command.result",
            ApiTypes.record(CommandResult.class, "CommandResultEvent",
                    ApiField.field("cmd", ApiTypes.string()).required().forGetter(CommandResult::cmd),
                    ApiField.field("ok", ApiTypes.bool()).describe("The command parsed and ran.").required().forGetter(CommandResult::ok),
                    ApiField.field("success", ApiTypes.bool()).required().forGetter(CommandResult::success),
                    CommandResult::new),
            Level.WARNING, "A server command run through mc.action.runCommand, by any caller.");

    public record WaitDone(String waitId, String method, Optional<JsonValue> result, Optional<JsonValue> error) {}

    public static final Entry<WaitDone> WAIT_DONE = entry("wait.done",
            ApiTypes.record(WaitDone.class, "WaitDone",
                    ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitDone::waitId),
                    ApiField.field("method", ApiTypes.string(1, 128)).required().forGetter(WaitDone::method),
                    ApiField.field("result", ApiTypes.jsonObject()).describe("The wait's {outcome:'done', …} reply.")
                            .optional().forGetter(WaitDone::result),
                    ApiField.field("error", ApiTypes.jsonObject()).describe("The ApiError that ended it instead.")
                            .optional().forGetter(WaitDone::error),
                    WaitDone::new),
            Level.WARNING, "A background wait finished.");

    public record ConditionMet(int watch, String invoke, Optional<String> field, Optional<JsonValue> value) {}

    public static final Entry<ConditionMet> CONDITION_MET = entry("condition.met",
            ApiTypes.record(ConditionMet.class, "ConditionMetEvent",
                    ApiField.field("watch", COUNT).required().forGetter(ConditionMet::watch),
                    ApiField.field("invoke", ApiTypes.string()).required().forGetter(ConditionMet::invoke),
                    ApiField.field("field", ApiTypes.string()).optional().forGetter(ConditionMet::field),
                    ApiField.field("value", ApiTypes.json()).optional().forGetter(ConditionMet::value),
                    ConditionMet::new),
            Level.INFO, "An mc.events watcher's test turned true. A watcher given its own emitAs sends this payload under that name.");

    // ------------------------------------------------------------------ the client's player

    public record PlayerDeath(double health, Optional<String> cause) {}

    public static final Entry<PlayerDeath> PLAYER_DEATH = entry("player.death",
            ApiTypes.record(PlayerDeath.class, "PlayerDeathEvent",
                    ApiField.field("health", ApiTypes.number()).required().forGetter(PlayerDeath::health),
                    ApiField.field("cause", ApiTypes.string()).describe("The death screen's message.")
                            .optional().forGetter(PlayerDeath::cause),
                    PlayerDeath::new),
            Level.ERROR, "The client's player died.");

    public record PlayerHurt(double health, double prev, double lost, Optional<String> source,
                             Optional<Integer> attackerId, Optional<String> attackerType, Optional<Double> attackerDistance) {}

    public static final Entry<PlayerHurt> PLAYER_HURT = entry("player.hurt",
            ApiTypes.record(PlayerHurt.class, "PlayerHurtEvent",
                    ApiField.field("health", ApiTypes.number()).required().forGetter(PlayerHurt::health),
                    ApiField.field("prev", ApiTypes.number()).required().forGetter(PlayerHurt::prev),
                    ApiField.field("lost", ApiTypes.number()).required().forGetter(PlayerHurt::lost),
                    ApiField.field("source", ApiTypes.string()).describe("The damage source's id, such as fall or mob.")
                            .optional().forGetter(PlayerHurt::source),
                    ApiField.field("attackerId", ApiTypes.integer()).optional().forGetter(PlayerHurt::attackerId),
                    ApiField.field("attackerType", CommonTypes.RESOURCE_ID).optional().forGetter(PlayerHurt::attackerType),
                    ApiField.field("attackerDistance", ApiTypes.number()).optional().forGetter(PlayerHurt::attackerDistance),
                    PlayerHurt::new),
            Level.WARNING, "The client's player lost health.");

    public record EnteredWater(boolean submerged) {}

    public static final Entry<EnteredWater> PLAYER_ENTERED_WATER = entry("player.enteredWater",
            ApiTypes.record(EnteredWater.class, "EnteredWaterEvent",
                    ApiField.field("submerged", ApiTypes.bool()).required().forGetter(EnteredWater::submerged),
                    EnteredWater::new),
            Level.INFO, "The client's player went into water.");

    public record EnteredLava(double health) {}

    public static final Entry<EnteredLava> PLAYER_ENTERED_LAVA = entry("player.enteredLava",
            ApiTypes.record(EnteredLava.class, "EnteredLavaEvent",
                    ApiField.field("health", ApiTypes.number()).required().forGetter(EnteredLava::health),
                    EnteredLava::new),
            Level.INFO, "The client's player went into lava.");

    public record TimePhase(String phase, String prev, long dayTime) {}

    public static final Entry<TimePhase> TIME_PHASE = entry("time.phase",
            ApiTypes.record(TimePhase.class, "TimePhaseEvent",
                    ApiField.field("phase", ApiTypes.string()).required().forGetter(TimePhase::phase),
                    ApiField.field("prev", ApiTypes.string()).required().forGetter(TimePhase::prev),
                    ApiField.field("dayTime", ApiTypes.int64(0, 23_999)).describe("The time of day, 0-23999.")
                            .required().forGetter(TimePhase::dayTime),
                    TimePhase::new),
            Level.INFO, "The day moved to another phase: day, sunset, night or sunrise.");

    public record ItemPickup(String id, int count, int total) {}

    public static final Entry<ItemPickup> ITEM_PICKUP = entry("item.pickup",
            ApiTypes.record(ItemPickup.class, "ItemPickupEvent",
                    ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(ItemPickup::id),
                    ApiField.field("count", COUNT).describe("How many more than before.").required().forGetter(ItemPickup::count),
                    ApiField.field("total", COUNT).describe("How many the inventory holds now.").required().forGetter(ItemPickup::total),
                    ItemPickup::new),
            Level.INFO, "The client's inventory gained items.");

    public record ToolBroke(String id, int damage, int maxDamage) {}

    public static final Entry<ToolBroke> TOOL_BROKE = entry("tool.broke",
            ApiTypes.record(ToolBroke.class, "ToolBrokeEvent",
                    ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(ToolBroke::id),
                    ApiField.field("damage", COUNT).required().forGetter(ToolBroke::damage),
                    ApiField.field("maxDamage", COUNT).required().forGetter(ToolBroke::maxDamage),
                    ToolBroke::new),
            Level.WARNING, "The main-hand tool broke.");

    public record Advancement(String id) {}

    public static final Entry<Advancement> ADVANCEMENT = entry("advancement",
            ApiTypes.record(Advancement.class, "AdvancementEvent",
                    ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(Advancement::id),
                    Advancement::new),
            Level.INFO, "The client's player completed an advancement.");

    public record ScreenAutoClosed(String screen, int blockedTicks) {}

    public static final Entry<ScreenAutoClosed> SCREEN_AUTO_CLOSED = entry("screen.autoClosed",
            ApiTypes.record(ScreenAutoClosed.class, "ScreenAutoClosedEvent",
                    ApiField.field("screen", ApiTypes.string()).describe("The screen's class name.")
                            .required().forGetter(ScreenAutoClosed::screen),
                    ApiField.field("blockedTicks", COUNT).required().forGetter(ScreenAutoClosed::blockedTicks),
                    ScreenAutoClosed::new),
            Level.INFO, "A screen that blocked the bot's movement was closed for it.");

    public record ChatLine(long seq, String kind, String text, boolean self) {}

    public static final Entry<ChatLine> CLIENT_MESSAGE = untrusted("client.message",
            ApiTypes.record(ChatLine.class, "ClientMessageEvent",
                    ApiField.field("seq", ApiTypes.int64(0, Long.MAX_VALUE)).describe("Its seq in mc.client.chat.history.")
                            .required().forGetter(ChatLine::seq),
                    ApiField.field("kind", ApiTypes.stringEnum("ChatLineKind", "player", "system"))
                            .required().forGetter(ChatLine::kind),
                    ApiField.field("text", ApiTypes.string()).required().forGetter(ChatLine::text),
                    ApiField.field("self", ApiTypes.bool()).describe("The echo of this player's own chat.")
                            .required().forGetter(ChatLine::self),
                    ChatLine::new),
            "The client received a chat or system line.");

    public record ClientText(String text) {}

    public static final Entry<ClientText> CLIENT_ACTION_BAR = untrusted("client.actionBar",
            ApiTypes.record(ClientText.class, "ActionBarEvent",
                    ApiField.field("text", ApiTypes.string()).required().forGetter(ClientText::text),
                    ClientText::new),
            "The action bar above the hotbar changed.");

    public record ClientTitle(String text, Optional<String> subtitle) {}

    public static final Entry<ClientTitle> CLIENT_TITLE = untrusted("client.title",
            ApiTypes.record(ClientTitle.class, "TitleEvent",
                    ApiField.field("text", ApiTypes.string()).required().forGetter(ClientTitle::text),
                    ApiField.field("subtitle", ApiTypes.string()).optional().forGetter(ClientTitle::subtitle),
                    ClientTitle::new),
            "A title was shown.");

    // ------------------------------------------------------------------ the bot's perception and reflexes

    public record At(BlockPosition pos) {}

    private static final ApiType<At> AT = ApiTypes.record(At.class, "EventPosition",
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(At::pos),
            At::new);

    public static final Entry<At> DUSK_EXPOSED = entry("duskExposed", AT, Level.INFO,
            "The bot is out in the open at night.");
    public static final Entry<At> CORNERED = entry("cornered", AT, Level.INFO,
            "The bot has hostiles around it and nowhere to step away to.");

    public record ThreatAppeared(String type, int id, double distance, double score) {}

    public static final Entry<ThreatAppeared> THREAT_APPEARED = entry("threat.appeared",
            ApiTypes.record(ThreatAppeared.class, "ThreatAppearedEvent",
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(ThreatAppeared::type),
                    ApiField.field("id", ApiTypes.integer()).describe("The entity's id.").required().forGetter(ThreatAppeared::id),
                    ApiField.field("distance", ApiTypes.number()).required().forGetter(ThreatAppeared::distance),
                    ApiField.field("score", ApiTypes.number()).describe("Higher is more dangerous.")
                            .required().forGetter(ThreatAppeared::score),
                    ThreatAppeared::new),
            Level.WARNING, "A different hostile became the most dangerous one near the bot.");

    public record DuskSecureTriggered(BlockPosition pos, int depth, boolean rearm) {}

    public static final Entry<DuskSecureTriggered> DUSK_SECURE_TRIGGERED = entry("duskSecure.triggered",
            ApiTypes.record(DuskSecureTriggered.class, "DuskSecureTriggeredEvent",
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(DuskSecureTriggered::pos),
                    ApiField.field("depth", COUNT).required().forGetter(DuskSecureTriggered::depth),
                    ApiField.field("rearm", ApiTypes.bool()).describe("Restarted after being preempted, not a fresh trigger.")
                            .required().forGetter(DuskSecureTriggered::rearm),
                    DuskSecureTriggered::new),
            Level.WARNING, "The dusk reflex started digging in on its own; mc.bot.cancel stops it.");
    public static final Entry<At> DUSK_SECURE_URGENT_DRY_RUN = entry("duskSecure.urgentDryRun", AT, Level.INFO,
            "The dusk reflex would have preempted the current task, had duskUrgentDryRun been off.");

    public record RouteBlocked(String reason, boolean bestEffort, int cells, int expanded, int leg) {}

    public static final Entry<RouteBlocked> ROUTE_BLOCKED = entry("route.blocked",
            ApiTypes.record(RouteBlocked.class, "RouteBlockedEvent",
                    ApiField.field("reason", ApiTypes.string())
                            .describe("constraint:<name> for a route condition, budget, or terrain.")
                            .required().forGetter(RouteBlocked::reason),
                    ApiField.field("bestEffort", ApiTypes.bool()).describe("A partial route was still found.")
                            .required().forGetter(RouteBlocked::bestEffort),
                    ApiField.field("cells", COUNT).required().forGetter(RouteBlocked::cells),
                    ApiField.field("expanded", COUNT).required().forGetter(RouteBlocked::expanded),
                    ApiField.field("leg", COUNT).required().forGetter(RouteBlocked::leg),
                    RouteBlocked::new),
            Level.INFO, "A search under route conditions could not reach the goal.");

    public record RouteDetour(double ratio, int cells, Optional<String> tax, Optional<Long> taxCost, int leg) {}

    public static final Entry<RouteDetour> ROUTE_DETOUR = entry("route.detour",
            ApiTypes.record(RouteDetour.class, "RouteDetourEvent",
                    ApiField.field("ratio", ApiTypes.number()).describe("Route length over the straight line.")
                            .required().forGetter(RouteDetour::ratio),
                    ApiField.field("cells", COUNT).required().forGetter(RouteDetour::cells),
                    ApiField.field("tax", ApiTypes.string()).describe("The route condition that cost the most.")
                            .optional().forGetter(RouteDetour::tax),
                    ApiField.field("taxCost", ApiTypes.int64(0, Long.MAX_VALUE)).optional().forGetter(RouteDetour::taxCost),
                    ApiField.field("leg", COUNT).required().forGetter(RouteDetour::leg),
                    RouteDetour::new),
            Level.INFO, "Route conditions made the route much longer than the straight line.");

    public record Observer(int id, String type, Optional<String> name) {}

    public record RouteExposed(Observer observer, int cells, int firstCell, int routeCells, int leg) {}

    public static final Entry<RouteExposed> ROUTE_EXPOSED = entry("route.exposed",
            ApiTypes.record(RouteExposed.class, "RouteExposedEvent",
                    ApiField.field("observer", ApiTypes.record(Observer.class, "RouteObserver",
                            ApiField.field("id", ApiTypes.integer()).required().forGetter(Observer::id),
                            ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(Observer::type),
                            ApiField.field("name", ApiTypes.string()).optional().forGetter(Observer::name),
                            Observer::new)).required().forGetter(RouteExposed::observer),
                    ApiField.field("cells", COUNT).describe("Route cells it can see.").required().forGetter(RouteExposed::cells),
                    ApiField.field("firstCell", COUNT).required().forGetter(RouteExposed::firstCell),
                    ApiField.field("routeCells", COUNT).required().forGetter(RouteExposed::routeCells),
                    ApiField.field("leg", COUNT).required().forGetter(RouteExposed::leg),
                    RouteExposed::new),
            Level.INFO, "A hostile can see part of the planned route.");

    // ------------------------------------------------------------------ lookup

    /** Every entry, in declaration order. */
    public static List<Entry<?>> all() { return List.copyOf(BY_TYPE.values()); }

    /** The entry for {@code type}, or null for a type the driver does not emit itself. */
    public static Entry<?> byType(String type) { return type == null ? null : BY_TYPE.get(type); }
}
