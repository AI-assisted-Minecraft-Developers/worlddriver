package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.CommonTypes.Look;
import net.magicterra.worlddriver.api.methods.CommonTypes.NoParams;

import static net.magicterra.worlddriver.api.methods.CommonTypes.NO_PARAMS;

/**
 * What this game client sees and says: its own player, its scene and blocks, chat, overlays and the
 * framebuffer. Every one runs on the client thread and answers {@code capability_unavailable} in a JVM
 * with no client. The screen and the input verbs are in {@link ScreenContracts}; the pieces both use,
 * the refusal and the screen context, are here.
 */
public final class ClientContracts {
    private ClientContracts() {}

    // ------------------------------------------------------------------ shared by the client verbs

    /** Why a client verb did nothing: what it needed is not on this client right now. */
    public enum ClientRefusal implements WireName {
        /** No screen is open. */
        NO_SCREEN,
        /** The open screen holds no container menu. */
        NO_CONTAINER,
        /** No player is in a world on this client. */
        NO_PLAYER,
        /** Nothing on the screen matches what the request named. */
        NO_TARGET,
        /** More than one thing matches, and the request did not say which. */
        AMBIGUOUS;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** A client verb's reply: {@code done} with what it did, or {@link Refused} with nothing done. */
    public sealed interface ClientReply permits Refused, ChatSent, ScreenContracts.Clicked, ScreenContracts.SlotClicked,
            ScreenContracts.HotbarSelected, ScreenContracts.Typed, ScreenContracts.Replaced, ScreenContracts.SlidersRead,
            ScreenContracts.SliderSet, ScreenContracts.KeySent, ScreenContracts.KeybindsListed, ScreenContracts.KeybindDriven {}

    public record Refused(ClientRefusal reason, String message) implements ClientReply {}

    /**
     * The open screen as of a read, opaque. It changes whenever a screen opens or closes, so closing and
     * reopening the same kind of screen gives a new one; it survives neither a restart nor a reconnect.
     */
    public static final ApiType<String> CONTEXT_ID = ApiTypes.string(1, 64)
            .describe("The screen context from screen.info, screen.tree or observe.container. Given, the write "
                    + "is refused as context_changed, with nothing done, unless that same screen is still open.");

    private static final RecordType<Refused> REFUSED = ApiTypes.record(null,
            ApiField.field("reason", ApiTypes.enumOf("ClientRefusal", ClientRefusal.class)).required()
                    .forGetter(Refused::reason),
            ApiField.field("message", ApiTypes.string()).required().forGetter(Refused::message),
            Refused::new);

    /** {@code <name>Response}, tagged on {@code outcome}: {@code done} as {@code done} describes it, or refused. */
    @SuppressWarnings("unchecked")
    public static <D extends ClientReply> UnionType<ClientReply> reply(String name, Class<D> type, RecordType<D> done) {
        return ApiTypes.taggedUnion(name + "Response", "outcome",
                ApiTypes.<ClientReply, D>variant("done", type, done),
                refusedVariant());
    }

    public static UnionType.Variant<ClientReply, Refused> refusedVariant() {
        return ApiTypes.variant("refused", Refused.class, REFUSED);
    }

    /** Points of wear on a damageable item; all three are absent on a stackable one. */
    public record Wear(Optional<Integer> maxDamage, Optional<Integer> damage, Optional<Integer> durability) {
        public static final Wear NONE = new Wear(Optional.empty(), Optional.empty(), Optional.empty());

        public static Wear of(int maxDamage, int damage) {
            return new Wear(Optional.of(maxDamage), Optional.of(damage), Optional.of(maxDamage - damage));
        }
    }

    // ------------------------------------------------------------------ player


    /** The client's own physics, which the server's copy of the player does not report. */
    public record ClientBody(boolean onGround, Point eyePos, String pose, boolean inWall, boolean inWater,
                             boolean underWater, boolean crouching, Optional<String> eyeBlock, Optional<String> feetBlock) {}

    public record ActiveEffect(String id, int amplifier, int durationTicks) {}

    public record Vitals(double health, double maxHealth, int air, int maxAir, int food, double saturation, int xpLevel,
                         List<ActiveEffect> effects) {}

    public record Time(long dayTime, long dayOfWorld, long timeOfDay, String phase) {}

    public record Attack(double strengthScale, boolean ready, int cooldownTicks, int fullCooldownTicks) {}

    public record Stack(String id, int count, Wear wear) {}

    public record InventorySlot(int slot, String id, int count, Wear wear) {}

    public record Inventory(int selectedSlot, Optional<Stack> mainHand, List<InventorySlot> slots, Map<String, Integer> items) {}

    /** What the crosshair is on. */
    public sealed interface Hit permits Miss, BlockHit, EntityHit {}
    public record Miss() implements Hit {}
    public record BlockHit(BlockPosition blockPos, String face, String block, double distance) implements Hit {}
    public record EntityHit(int entityId, String entityType, Point pos, double distance) implements Hit {}

    /** Everything but {@code present} and {@code fps} is absent when no player is in a world. */
    public record ClientPlayer(boolean present, Optional<String> name, Optional<String> uuid, Optional<String> dimension,
                               Optional<Point> pos, Optional<Look> look, Optional<ClientBody> body, Optional<Vitals> vitals,
                               Optional<Time> time, Optional<String> gameMode, Optional<Attack> attack,
                               Optional<Inventory> inventory, Optional<Hit> hit, int fps) {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);

    /** Wear's three fields, inlined into an item row. */
    private static <O> ApiField<O, Optional<Integer>> wearField(String name, java.util.function.Function<O, Wear> wear,
                                                                java.util.function.Function<Wear, Optional<Integer>> part,
                                                                String text) {
        return ApiField.field(name, COUNT).describe(text).optional().forGetter(o -> part.apply(wear.apply(o)));
    }

    private static final ApiType<Stack> STACK = ApiTypes.record("ItemStack",
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(Stack::id),
            ApiField.field("count", COUNT).required().forGetter(Stack::count),
            wearField("maxDamage", Stack::wear, Wear::maxDamage, "Damageable items only."),
            wearField("damage", Stack::wear, Wear::damage, "Damageable items only."),
            wearField("durability", Stack::wear, Wear::durability,
                    "Points left (maxDamage - damage); damageable items only. Unbreaking stretches a point over several uses."),
            (id, count, max, dmg, dur) -> new Stack(id, count, new Wear(max, dmg, dur)));

    private static final ApiType<InventorySlot> INVENTORY_SLOT = ApiTypes.record("InventorySlot",
            ApiField.field("slot", COUNT).describe("Vanilla inventory index: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand.")
                    .required().forGetter(InventorySlot::slot),
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(InventorySlot::id),
            ApiField.field("count", COUNT).required().forGetter(InventorySlot::count),
            wearField("maxDamage", InventorySlot::wear, Wear::maxDamage, null),
            wearField("damage", InventorySlot::wear, Wear::damage, null),
            wearField("durability", InventorySlot::wear, Wear::durability, null),
            (slot, id, count, max, dmg, dur) -> new InventorySlot(slot, id, count, new Wear(max, dmg, dur)));

    private static final ApiType<Hit> HIT = ApiTypes.taggedUnion("CrosshairHit", "type",
            ApiTypes.variant("miss", Miss.class, ApiTypes.record(null, Miss::new)),
            ApiTypes.variant("block", BlockHit.class, ApiTypes.record(null,
                    ApiField.field("blockPos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockHit::blockPos),
                    ApiField.field("face", ApiTypes.string()).required().forGetter(BlockHit::face),
                    ApiField.field("block", CommonTypes.RESOURCE_ID).required().forGetter(BlockHit::block),
                    ApiField.field("distance", ApiTypes.number()).describe("From the eyes.").required().forGetter(BlockHit::distance),
                    BlockHit::new)),
            ApiTypes.variant("entity", EntityHit.class, ApiTypes.record(null,
                    ApiField.field("entityId", CommonTypes.ENTITY_ID).required().forGetter(EntityHit::entityId),
                    ApiField.field("entityType", CommonTypes.RESOURCE_ID).required().forGetter(EntityHit::entityType),
                    ApiField.field("pos", CommonTypes.POINT).required().forGetter(EntityHit::pos),
                    ApiField.field("distance", ApiTypes.number()).required().forGetter(EntityHit::distance),
                    EntityHit::new)));

    public static final ApiType<ClientPlayer> CLIENT_PLAYER = ApiTypes.record("ClientPlayer",
            ApiField.field("present", ApiTypes.bool()).describe("A player is in a world on this client.")
                    .required().forGetter(ClientPlayer::present),
            ApiField.field("name", ApiTypes.string()).optional().forGetter(ClientPlayer::name),
            ApiField.field("uuid", ApiTypes.string()).optional().forGetter(ClientPlayer::uuid),
            ApiField.field("dimension", CommonTypes.RESOURCE_ID).optional().forGetter(ClientPlayer::dimension),
            ApiField.field("pos", CommonTypes.POINT).describe("The feet.").optional().forGetter(ClientPlayer::pos),
            ApiField.field("look", ApiTypes.record("ClientLook",
                    ApiField.field("yaw", ApiTypes.number()).required().forGetter(Look::yaw),
                    ApiField.field("pitch", ApiTypes.number()).required().forGetter(Look::pitch),
                    Look::new)).optional().forGetter(ClientPlayer::look),
            ApiField.field("body", ApiTypes.record("ClientBody",
                    ApiField.field("onGround", ApiTypes.bool()).required().forGetter(ClientBody::onGround),
                    ApiField.field("eyePos", CommonTypes.POINT).required().forGetter(ClientBody::eyePos),
                    ApiField.field("pose", ApiTypes.string()).describe("STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING, …")
                            .required().forGetter(ClientBody::pose),
                    ApiField.field("inWall", ApiTypes.bool()).required().forGetter(ClientBody::inWall),
                    ApiField.field("inWater", ApiTypes.bool()).required().forGetter(ClientBody::inWater),
                    ApiField.field("underWater", ApiTypes.bool()).required().forGetter(ClientBody::underWater),
                    ApiField.field("crouching", ApiTypes.bool()).required().forGetter(ClientBody::crouching),
                    ApiField.field("eyeBlock", CommonTypes.RESOURCE_ID).describe("The client's block in the eye cell.")
                            .optional().forGetter(ClientBody::eyeBlock),
                    ApiField.field("feetBlock", CommonTypes.RESOURCE_ID).describe("The client's block in the feet cell.")
                            .optional().forGetter(ClientBody::feetBlock),
                    ClientBody::new)).optional().forGetter(ClientPlayer::body),
            ApiField.field("vitals", ApiTypes.record("ClientVitals",
                    ApiField.field("health", ApiTypes.number()).required().forGetter(Vitals::health),
                    ApiField.field("maxHealth", ApiTypes.number()).required().forGetter(Vitals::maxHealth),
                    ApiField.field("air", ApiTypes.integer()).required().forGetter(Vitals::air),
                    ApiField.field("maxAir", ApiTypes.integer()).required().forGetter(Vitals::maxAir),
                    ApiField.field("food", ApiTypes.integer()).required().forGetter(Vitals::food),
                    ApiField.field("saturation", ApiTypes.number()).required().forGetter(Vitals::saturation),
                    ApiField.field("xpLevel", ApiTypes.integer()).required().forGetter(Vitals::xpLevel),
                    ApiField.field("effects", ApiTypes.list(ApiTypes.record("ActiveEffect",
                            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(ActiveEffect::id),
                            ApiField.field("amplifier", ApiTypes.integer()).required().forGetter(ActiveEffect::amplifier),
                            ApiField.field("durationTicks", ApiTypes.integer()).required().forGetter(ActiveEffect::durationTicks),
                            ActiveEffect::new), 256)).required().forGetter(Vitals::effects),
                    Vitals::new)).optional().forGetter(ClientPlayer::vitals),
            ApiField.field("time", ApiTypes.record("WorldTime",
                    ApiField.field("dayTime", ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE)).required().forGetter(Time::dayTime),
                    ApiField.field("dayOfWorld", ApiTypes.int64(Long.MIN_VALUE, Long.MAX_VALUE)).required().forGetter(Time::dayOfWorld),
                    ApiField.field("timeOfDay", ApiTypes.int64(0, 23_999)).required().forGetter(Time::timeOfDay),
                    ApiField.field("phase", ApiTypes.stringEnum("ClientDayPhase","day", "sunset", "night", "sunrise"))
                            .describe("Hostile mobs spawn from sunset 13000 and burn from sunrise 23000.")
                            .required().forGetter(Time::phase),
                    Time::new)).optional().forGetter(ClientPlayer::time),
            ApiField.field("gameMode", ApiTypes.string()).describe("survival, creative, adventure or spectator.")
                    .optional().forGetter(ClientPlayer::gameMode),
            ApiField.field("attack", ApiTypes.record("ClientAttackReadiness",
                    ApiField.field("strengthScale", ApiTypes.number(0, 1))
                            .describe("The share of the weapon's damage a swing would land now.")
                            .required().forGetter(Attack::strengthScale),
                    ApiField.field("ready", ApiTypes.bool()).required().forGetter(Attack::ready),
                    ApiField.field("cooldownTicks", COUNT).describe("Ticks until ready; swinging sooner wastes the hit.")
                            .required().forGetter(Attack::cooldownTicks),
                    ApiField.field("fullCooldownTicks", COUNT).describe("The held weapon's whole recharge.")
                            .required().forGetter(Attack::fullCooldownTicks),
                    Attack::new)).optional().forGetter(ClientPlayer::attack),
            ApiField.field("inventory", ApiTypes.record("ClientInventory",
                    ApiField.field("selectedSlot", ApiTypes.integer(0, 8)).required().forGetter(Inventory::selectedSlot),
                    ApiField.field("mainHand", STACK).describe("Absent when the hand is empty.")
                            .optional().forGetter(Inventory::mainHand),
                    ApiField.field("slots", ApiTypes.list(INVENTORY_SLOT, 256)).describe("Every non-empty slot.")
                            .required().forGetter(Inventory::slots),
                    ApiField.field("items", ApiTypes.map(COUNT, 4096))
                            .describe("Item id to total count: the have that mc.recipe.resolve and mc.plan.acquire take.")
                            .required().forGetter(Inventory::items),
                    Inventory::new)).optional().forGetter(ClientPlayer::inventory),
            ApiField.field("hit", HIT).describe("The crosshair's block or entity, by the client's raycast.")
                    .optional().forGetter(ClientPlayer::hit),
            ApiField.field("fps", COUNT).required().forGetter(ClientPlayer::fps),
            ClientPlayer::new);

    public static final MethodSpec<NoParams, ClientPlayer> PLAYER = MethodSpec.of("mc.client.player", NO_PARAMS, CLIENT_PLAYER,
                    "This client's own player as the CLIENT predicts it, read from the LocalPlayer and ClientLevel even "
                            + "when a server is attached: position, look, vitals, time, attack readiness, inventory, the "
                            + "crosshair, plus the client physics the server copy lacks (pose, eye position, inWall, "
                            + "inWater, the blocks at eye and feet). Diff it against mc.observe.player to see a "
                            + "client/server desync. present:false when no player is in a world.")
            .asPollable();

    // ------------------------------------------------------------------ scene

    public record ClientScene(boolean present, Optional<BlockPosition> pos, Optional<Double> health, Optional<Integer> food,
                              Optional<String> dayPhase, Optional<Boolean> skyExposed, Optional<Boolean> exposedAtNight,
                              Optional<Boolean> cornered, Optional<Integer> lethalCount, Optional<List<String>> rows) {}

    public static final ApiType<ClientScene> CLIENT_SCENE = ApiTypes.record("ClientScene",
            ApiField.field("present", ApiTypes.bool()).required().forGetter(ClientScene::present),
            ApiField.field("pos", CommonTypes.BLOCK_POSITION).optional().forGetter(ClientScene::pos),
            ApiField.field("health", ApiTypes.number()).optional().forGetter(ClientScene::health),
            ApiField.field("food", ApiTypes.integer()).optional().forGetter(ClientScene::food),
            ApiField.field("dayPhase", ApiTypes.stringEnum("BotDayPhase", "DAY", "DUSK", "NIGHT", "DAWN"))
                    .describe("The bot's own phase, which its shelter reflex reads; its edges differ from mc.observe.player's time.phase.")
                    .optional().forGetter(ClientScene::dayPhase),
            ApiField.field("skyExposed", ApiTypes.bool()).optional().forGetter(ClientScene::skyExposed),
            ApiField.field("exposedAtNight", ApiTypes.bool()).optional().forGetter(ClientScene::exposedAtNight),
            ApiField.field("cornered", ApiTypes.bool()).describe("The hazard grid leaves no safe way to retreat.")
                    .optional().forGetter(ClientScene::cornered),
            ApiField.field("lethalCount", COUNT).describe("Lethal-fall cells in the local hazard grid.")
                    .optional().forGetter(ClientScene::lethalCount),
            ApiField.field("rows", ApiTypes.list(ApiTypes.string(), 256)).describe("The hazard field as an ASCII map.")
                    .optional().forGetter(ClientScene::rows),
            ClientScene::new);

    public static final MethodSpec<NoParams, ClientScene> SCENE = MethodSpec.of("mc.client.scene", NO_PARAMS, CLIENT_SCENE,
                    "The facts the client bot derives every tick from its hazard grid: day phase, sky exposure, "
                            + "whether it is cornered, lethal-fall cells, and the grid as an ASCII map. present:false "
                            + "when no player is in a world.")
            .asPollable();

    // ------------------------------------------------------------------ blocks

    public record BlocksParams(Optional<BlockPosition> center, int radius, Optional<String> type) {

        /** Every block within {@code radius} of the player's feet. */
        public static BlocksParams aroundPlayer(int radius) {
            return new BlocksParams(Optional.empty(), radius, Optional.empty());
        }

        /** Every block within {@code radius} of {@code center}. */
        public static BlocksParams around(BlockPosition center, int radius) {
            return new BlocksParams(Optional.of(center), radius, Optional.empty());
        }
    }

    public record BlockRow(BlockPosition pos, String type, Optional<Map<String, String>> state) {}

    public record ChunkRef(int x, int z) {}

    public record BlockScan(List<BlockRow> blocks, BlockPosition center, int radius, Optional<List<ChunkRef>> unloaded) {}

    public static final ApiType<BlocksParams> BLOCKS_PARAMS = ApiTypes.record("ClientBlocksParams",
            ApiField.field("center", CommonTypes.BLOCK_POSITION).describe("Omitted: the player's feet.")
                    .optional().forGetter(BlocksParams::center),
            ApiField.field("radius", ApiTypes.integer(0, 16)).describe("Chebyshev radius.").defaulted(4)
                    .forGetter(BlocksParams::radius),
            ApiField.field("type", CommonTypes.BLOCK_OR_TAG).describe("Only this block, or the blocks of this #tag.")
                    .optional().forGetter(BlocksParams::type),
            BlocksParams::new);

    public static final ApiType<BlockScan> BLOCK_SCAN = ApiTypes.record("ClientBlockScan",
            ApiField.field("blocks", ApiTypes.list(ApiTypes.record("ClientBlock",
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockRow::pos),
                    ApiField.field("type", CommonTypes.RESOURCE_ID).required().forGetter(BlockRow::type),
                    ApiField.field("state", ApiTypes.map(ApiTypes.string(), 64))
                            .describe("Block state properties; absent for a state with none.")
                            .optional().forGetter(BlockRow::state),
                    BlockRow::new), 40_000)).describe("Every non-air cell, air skipped.").required().forGetter(BlockScan::blocks),
            ApiField.field("center", CommonTypes.BLOCK_POSITION).required().forGetter(BlockScan::center),
            ApiField.field("radius", ApiTypes.integer(0, 16)).required().forGetter(BlockScan::radius),
            ApiField.field("unloaded", ApiTypes.list(ApiTypes.record("ChunkRef",
                    ApiField.field("x", ApiTypes.integer()).required().forGetter(ChunkRef::x),
                    ApiField.field("z", ApiTypes.integer()).required().forGetter(ChunkRef::z),
                    ChunkRef::new), 64)).describe("Chunks the client has not loaded, whose cells read as air.")
                    .optional().forGetter(BlockScan::unloaded),
            BlockScan::new);

    public static final MethodSpec<BlocksParams, BlockScan> BLOCKS = MethodSpec.of("mc.client.blocks", BLOCKS_PARAMS, BLOCK_SCAN,
                    "The blocks the CLIENT has loaded around center, to diff client against server state. Not for "
                            + "reading terrain: it lists every non-air cell, so underground a radius of 5 or more "
                            + "overflows a reply budget; mc.observe.map draws terrain as a compact ASCII grid. Use this "
                            + "to confirm a few cells, with a type and the smallest radius. capability_unavailable when "
                            + "no world is open.")
            .execution(MethodSpec.ExecutionClass.BULK);

    // ------------------------------------------------------------------ chat

    public enum ChatKind implements WireName {
        /** Chat with a real sender profile. */
        PLAYER,
        /** Command feedback, broadcasts, console /say, server messages. */
        SYSTEM;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum SentAs implements WireName {
        COMMAND, CHAT;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    /** {@code self}: the server's echo of this player's own chat. */
    public record ChatLine(long seq, ChatKind kind, String text, boolean self) {}

    /** {@code lines}: what arrived after the send, oldest first, own echoes left out. */
    public record LaterChat(List<ChatLine> lines, boolean timedOut, long waitedMs) {}

    public record ChatSendParams(String text, int awaitReplyMs) {}

    public record ChatSent(SentAs kind, int length, Optional<LaterChat> later) implements ClientReply {}

    private static final ApiType<ChatKind> CHAT_KIND = ApiTypes.enumOf("ChatKind", ChatKind.class);
    private static final ApiType<Long> SEQ = ApiTypes.int64(0, Long.MAX_VALUE);

    private static final RecordType<ChatLine> CHAT_LINE = ApiTypes.record("ChatLine",
            ApiField.field("seq", SEQ).required().forGetter(ChatLine::seq),
            ApiField.field("kind", CHAT_KIND).required().forGetter(ChatLine::kind),
            ApiField.field("text", ApiTypes.string()).describe("Plain text, formatting stripped.").required().forGetter(ChatLine::text),
            ApiField.field("self", ApiTypes.bool()).describe("The server's echo of this player's own chat.")
                    .required().forGetter(ChatLine::self),
            ChatLine::new);

    public static final ApiType<ChatSendParams> CHAT_SEND_PARAMS = ApiTypes.record("ChatSendParams",
            ApiField.field("text", ApiTypes.string(1, 256)).describe("A leading / sends it as a command.")
                    .required().forGetter(ChatSendParams::text),
            ApiField.field("awaitReplyMs", ApiTypes.integer(0, 30_000))
                    .describe("Wait up to this long for chat lines that arrive after the send; 0 returns at once.")
                    .defaulted(0).forGetter(ChatSendParams::awaitReplyMs),
            ChatSendParams::new);

    public static final MethodSpec<ChatSendParams, ClientReply> CHAT_SEND = MethodSpec.of("mc.client.chat.send", CHAT_SEND_PARAMS,
                    reply("ChatSend", ChatSent.class, ApiTypes.record(null,
                            ApiField.field("kind", ApiTypes.enumOf("SentAs", SentAs.class)).required().forGetter(ChatSent::kind),
                            ApiField.field("length", COUNT).required().forGetter(ChatSent::length),
                            ApiField.field("later", ApiTypes.record("LaterChat",
                                    ApiField.field("lines", ApiTypes.list(CHAT_LINE, 512)).required().forGetter(LaterChat::lines),
                                    ApiField.field("timedOut", ApiTypes.bool()).describe("No line arrived within awaitReplyMs.")
                                            .required().forGetter(LaterChat::timedOut),
                                    ApiField.field("waitedMs", ApiTypes.int64(0, Long.MAX_VALUE)).required()
                                            .forGetter(LaterChat::waitedMs),
                                    LaterChat::new)).describe("With awaitReplyMs only.").optional().forGetter(ChatSent::later),
                            ChatSent::new)),
                    "Send a chat line, or a command when it starts with /, as this client's player: pressing T, typing "
                            + "and pressing Enter. With awaitReplyMs it then waits for the chat lines that arrive after "
                            + "the send, gives multi-line feedback 150 ms to settle, and returns them as later. Those are "
                            + "a later chat observation, NOT the command's answer: an unrelated broadcast can arrive "
                            + "first, and two waits at once see the same lines. Lines a client mod writes straight to the "
                            + "chat HUD never cross the network and are not seen. For a server command's own feedback use "
                            + "mc.action.runCommand. Refused as no_player when not connected.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public record ChatHistoryParams(int limit, long sinceSeq) {}

    public record HistoryLine(long seq, ChatKind kind, String text, boolean self, long ageTicks) {}

    /** {@code nextSeq}: pass it as sinceSeq next time to read only the lines after these. */
    public record ChatHistory(long nextSeq, List<HistoryLine> messages) {}

    public static final ApiType<ChatHistoryParams> CHAT_HISTORY_PARAMS = ApiTypes.record("ChatHistoryParams",
            ApiField.field("limit", ApiTypes.integer(1, 256)).describe("The newest this many lines.").defaulted(50)
                    .forGetter(ChatHistoryParams::limit),
            ApiField.field("sinceSeq", SEQ).describe("Only lines with seq at or after this.").defaulted(0L)
                    .forGetter(ChatHistoryParams::sinceSeq),
            ChatHistoryParams::new);

    public static final MethodSpec<ChatHistoryParams, ChatHistory> CHAT_HISTORY = MethodSpec.of("mc.client.chat.history",
                    CHAT_HISTORY_PARAMS,
                    ApiTypes.record("ChatHistory",
                            ApiField.field("nextSeq", SEQ).describe("The seq the next line will get; pass it as sinceSeq to poll.")
                                    .required().forGetter(ChatHistory::nextSeq),
                            ApiField.field("messages", ApiTypes.list(ApiTypes.record("HistoryLine",
                                    ApiField.field("seq", SEQ).required().forGetter(HistoryLine::seq),
                                    ApiField.field("kind", CHAT_KIND).required().forGetter(HistoryLine::kind),
                                    ApiField.field("text", ApiTypes.string()).required().forGetter(HistoryLine::text),
                                    ApiField.field("self", ApiTypes.bool()).required().forGetter(HistoryLine::self),
                                    ApiField.field("ageTicks", ApiTypes.int64(0, Long.MAX_VALUE)).required()
                                            .forGetter(HistoryLine::ageTicks),
                                    HistoryLine::new), 256)).describe("Newest first.").required().forGetter(ChatHistory::messages),
                            ChatHistory::new),
                    "The chat and system lines this client received, captured as they arrive from the server: player "
                            + "chat, command feedback, /say, broadcasts; not the action bar, and not lines a client mod "
                            + "writes straight to the chat HUD. seq is monotonic for the session and the last 512 lines "
                            + "are kept.")
            .asPollable();

    // ------------------------------------------------------------------ overlays

    public record OverlaysParams(boolean tutorial, boolean toasts) {}

    /** {@code tutorial}/{@code toasts}: that group was dismissed now; an error says why it was not. */
    public record OverlaysCleared(boolean tutorial, boolean toasts, Optional<String> tutorialError,
                                  Optional<String> toastsError) {}

    public static final MethodSpec<OverlaysParams, OverlaysCleared> OVERLAYS = MethodSpec.of("mc.client.overlays",
                    ApiTypes.record("OverlaysParams",
                            ApiField.field("tutorial", ApiTypes.bool())
                                    .describe("Turn the tutorial hints (Move with W,A,S,D…) off for good.")
                                    .defaulted(true).forGetter(OverlaysParams::tutorial),
                            ApiField.field("toasts", ApiTypes.bool()).describe("Clear the toast queue: advancements, recipes, system.")
                                    .defaulted(true).forGetter(OverlaysParams::toasts),
                            OverlaysParams::new),
                    ApiTypes.record("OverlaysCleared",
                            ApiField.field("tutorial", ApiTypes.bool()).required().forGetter(OverlaysCleared::tutorial),
                            ApiField.field("toasts", ApiTypes.bool()).required().forGetter(OverlaysCleared::toasts),
                            ApiField.field("tutorialError", ApiTypes.string()).optional().forGetter(OverlaysCleared::tutorialError),
                            ApiField.field("toastsError", ApiTypes.string()).optional().forGetter(OverlaysCleared::toastsError),
                            OverlaysCleared::new),
                    "Dismiss the HUD overlays that are not part of the world: the tutorial hints and the toasts. "
                            + "Idempotent; {} clears both.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ screenshot

    public enum ImageFormat implements WireName {
        PNG, JPEG;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record ScreenshotParams(Optional<Integer> maxWidth, Optional<Integer> maxHeight, ImageFormat format, int quality) {}

    public record Screenshot(ImageFormat format, int width, int height, long frame, boolean frameWaited,
                             boolean windowActive, int fps, String base64) {}

    private static final ApiType<ImageFormat> IMAGE_FORMAT = ApiTypes.enumOf("ImageFormat", ImageFormat.class);

    public static final MethodSpec<ScreenshotParams, Screenshot> SCREENSHOT = MethodSpec.of("mc.client.screenshot",
                    ApiTypes.record("ScreenshotParams",
                            ApiField.field("maxWidth", ApiTypes.integer(16, 8192)).describe("Shrink, keeping the aspect, to at most this wide.")
                                    .optional().forGetter(ScreenshotParams::maxWidth),
                            ApiField.field("maxHeight", ApiTypes.integer(16, 8192)).describe("Shrink, keeping the aspect, to at most this high.")
                                    .optional().forGetter(ScreenshotParams::maxHeight),
                            ApiField.field("format", IMAGE_FORMAT).defaulted(ImageFormat.PNG).forGetter(ScreenshotParams::format),
                            ApiField.field("quality", ApiTypes.integer(1, 100)).describe("JPEG only.").defaulted(85)
                                    .forGetter(ScreenshotParams::quality),
                            ScreenshotParams::new),
                    ApiTypes.record("Screenshot",
                            ApiField.field("format", IMAGE_FORMAT).required().forGetter(Screenshot::format),
                            ApiField.field("width", COUNT).required().forGetter(Screenshot::width),
                            ApiField.field("height", COUNT).required().forGetter(Screenshot::height),
                            ApiField.field("frame", ApiTypes.int64(0, Long.MAX_VALUE))
                                    .describe("The frame captured: two captures with the same frame are the same image.")
                                    .required().forGetter(Screenshot::frame),
                            ApiField.field("frameWaited", ApiTypes.bool())
                                    .describe("A frame was drawn after the request; false means the image may predate it.")
                                    .required().forGetter(Screenshot::frameWaited),
                            ApiField.field("windowActive", ApiTypes.bool()).required().forGetter(Screenshot::windowActive),
                            ApiField.field("fps", COUNT).required().forGetter(Screenshot::fps),
                            ApiField.field("base64", ApiTypes.string()).describe("The encoded image.").required()
                                    .forGetter(Screenshot::base64),
                            Screenshot::new),
                    "Capture the framebuffer as png (lossless) or jpeg (smaller), optionally shrunk. It waits for a "
                            + "frame drawn after the request, so what was done just before is in the picture. Over MCP "
                            + "the image is a native image block and the text holds the other fields.")
            .execution(MethodSpec.ExecutionClass.BULK)
            .mayWait()
            .image("base64", "format");
}
