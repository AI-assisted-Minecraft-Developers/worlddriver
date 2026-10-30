package net.magicterra.worlddriver.api.methods.client;

import static net.magicterra.worlddriver.api.methods.shared.CommonTypes.NO_PARAMS;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockOrTag;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.EntityId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Point;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * What this game client sees and says: its own player, its scene and blocks, chat, overlays and the framebuffer. Every
 * one runs on the client thread and answers {@code capability_unavailable} in a JVM with no client. The screen and the
 * input verbs are in {@link ScreenContracts}; the pieces both use, the refusal and the screen context, are here.
 */
public final class ClientContracts {

    public static final ApiType<String> CONTEXT_ID = ApiTypes.of(String.class, ContextId.class);

    public static final ApiType<ClientPlayer> CLIENT_PLAYER = ApiTypes.record(ClientPlayer.class);

    public static final MethodSpec<NoParams, ClientPlayer> PLAYER = MethodSpec.of(
                    "mc.client.player",
                    NO_PARAMS,
                    CLIENT_PLAYER,
                    "This client's own player as the CLIENT predicts it, read from the LocalPlayer and ClientLevel even "
                            + "when a server is attached: position, look, vitals, time, attack readiness, inventory, the "
                            + "crosshair, plus the client physics the server copy lacks (pose, eye position, inWall, "
                            + "inWater, the blocks at eye and feet). Diff it against mc.observe.player to see a "
                            + "client/server desync. present:false when no player is in a world.")
            .asPollable();

    public static final ApiType<ClientScene> CLIENT_SCENE = ApiTypes.record(ClientScene.class);

    public static final MethodSpec<NoParams, ClientScene> SCENE = MethodSpec.of(
                    "mc.client.scene",
                    NO_PARAMS,
                    CLIENT_SCENE,
                    "The facts the client bot derives every tick from its hazard grid: day phase, sky exposure, "
                            + "whether it is cornered, lethal-fall cells, and the grid as an ASCII map. present:false "
                            + "when no player is in a world.")
            .asPollable();

    public static final ApiType<BlocksParams> BLOCKS_PARAMS = ApiTypes.record(BlocksParams.class);

    public static final ApiType<BlockScan> BLOCK_SCAN = ApiTypes.record(BlockScan.class);

    public static final MethodSpec<BlocksParams, BlockScan> BLOCKS = MethodSpec.of(
                    "mc.client.blocks",
                    BLOCKS_PARAMS,
                    BLOCK_SCAN,
                    "The blocks the CLIENT has loaded around center, to diff client against server state. Not for "
                            + "reading terrain: it lists every non-air cell, so underground a radius of 5 or more "
                            + "overflows a reply budget; mc.observe.map draws terrain as a compact ASCII grid. Use this "
                            + "to confirm a few cells, with a type and the smallest radius. capability_unavailable when "
                            + "no world is open.")
            .execution(MethodSpec.ExecutionClass.BULK);

    public static final ApiType<ChatSendParams> CHAT_SEND_PARAMS = ApiTypes.record(ChatSendParams.class);

    public static final MethodSpec<ChatSendParams, ClientReply> CHAT_SEND = MethodSpec.of(
                    "mc.client.chat.send",
                    CHAT_SEND_PARAMS,
                    reply("ChatSend", ChatSent.class),
                    "Send a chat line, or a command when it starts with /, as this client's player: pressing T, typing "
                            + "and pressing Enter. With awaitReplyMs it then waits for the chat lines that arrive after "
                            + "the send, gives multi-line feedback 150 ms to settle, and returns them as later. Those are "
                            + "a later chat observation, NOT the command's answer: an unrelated broadcast can arrive "
                            + "first, and two waits at once see the same lines. Lines a client mod writes straight to the "
                            + "chat HUD never cross the network and are not seen. For a server command's own feedback use "
                            + "mc.action.runCommand. Refused as no_player when not connected.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.WORLD_WRITE)
            .mayWait();

    public static final ApiType<ChatHistoryParams> CHAT_HISTORY_PARAMS = ApiTypes.record(ChatHistoryParams.class);

    public static final MethodSpec<ChatHistoryParams, ChatHistory> CHAT_HISTORY = MethodSpec.of(
                    "mc.client.chat.history",
                    CHAT_HISTORY_PARAMS,
                    ApiTypes.record(ChatHistory.class),
                    "The chat and system lines this client received, captured as they arrive from the server: player "
                            + "chat, command feedback, /say, broadcasts; not the action bar, and not lines a client mod "
                            + "writes straight to the chat HUD. seq is monotonic for the session and the last 512 lines "
                            + "are kept.")
            .asPollable();

    public static final MethodSpec<OverlaysParams, OverlaysCleared> OVERLAYS = MethodSpec.of(
                    "mc.client.overlays",
                    ApiTypes.record(OverlaysParams.class),
                    ApiTypes.record(OverlaysCleared.class),
                    "Dismiss the HUD overlays that are not part of the world: the tutorial hints and the toasts. "
                            + "Idempotent; {} clears both.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    public static final MethodSpec<ScreenshotParams, Screenshot> SCREENSHOT = MethodSpec.of(
                    "mc.client.screenshot",
                    ApiTypes.record(ScreenshotParams.class),
                    ApiTypes.record(Screenshot.class),
                    "Capture the framebuffer as png (lossless) or jpeg (smaller), optionally shrunk. It waits for a "
                            + "frame drawn after the request, so what was done just before is in the picture. Over MCP "
                            + "the image is a native image block and the text holds the other fields.")
            .execution(MethodSpec.ExecutionClass.BULK)
            .mayWait()
            .image("base64", "format");

    private ClientContracts() {}

    // ------------------------------------------------------------------ shared by the client verbs

    /** Why a client verb did nothing: what it needed is not on this client right now. */
    @Named("ClientRefusal")
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

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A client verb's reply: {@code done} with what it did, or {@link Refused} with nothing done. */
    public sealed interface ClientReply
            permits Refused,
                    ChatSent,
                    Clicked,
                    SlotClicked,
                    HotbarSelected,
                    Typed,
                    Replaced,
                    SlidersRead,
                    SliderSet,
                    KeySent,
                    KeybindsListed,
                    KeybindDriven {}

    /** The {@code refused} outcome: nothing was done, for {@code reason}, explained in {@code message}. */
    @Variant("refused")
    public record Refused(ClientRefusal reason, String message) implements ClientReply {}

    // The screen verbs' answers. A sealed interface's variants must share its package, so they are declared
    // here; ScreenContracts still defines how each is written. treeChanged and tree are returnTree's: absent
    // unless it was asked, and tree only when treeChanged.

    /** {@code handled}: a widget took the click; false means empty space. */
    @Variant("done")
    public record Clicked(
            @Doc("A widget took it; false is empty space. Absent with route raw, which nothing answers.")
            Optional<Boolean> handled,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** The {@code done} outcome of {@code mc.client.input.slotClick}: the slot, button and click type sent. */
    @Variant("done")
    public record SlotClicked(
            @Range(min = 0) int slot,
            @Range(min = 0, max = 8) int button,
            ScreenContracts.ClickKind type,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call; absent with bot, which has no screen.")
            Optional<@ContextId String> contextId)
            implements ClientReply {}

    /** The {@code done} outcome of {@code mc.client.input.setHotbarSlot}: the slot now held and the one before. */
    @Variant("done")
    public record HotbarSelected(
            @Range(min = 0, max = 8) int slot,
            @Range(min = 0, max = 8) int previous,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** {@code typed}: code points delivered; {@code length}: UTF-16 units in the text. */
    @Variant("done")
    public record Typed(
            @Range(min = 0) int typed,
            @Range(min = 0) int length,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** The {@code done} outcome of {@code mc.client.input.replaceText}: the box's new and previous contents. */
    @Variant("done")
    public record Replaced(
            String value,
            String previous,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** The {@code read} outcome of {@code mc.client.input.slider} without a fraction: every slider on the screen. */
    @Variant("read")
    public record SlidersRead(
            @Length(max = 1024) List<ScreenContracts.Slider> sliders,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** The {@code set} outcome of {@code mc.client.input.slider}: the slider's label and value after and before. */
    @Variant("set")
    public record SliderSet(
            String label,
            @Range(min = 0, max = 1) double value,
            String previousLabel,
            @Range(min = 0, max = 1) double previousValue,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /**
     * {@code pressed}/{@code released}: that half went out. {@code pressHandled}/{@code releaseHandled}: on the screen
     * route only, the screen consumed it. {@code screenAfter}: the screen the keystroke left open.
     */
    @Variant("done")
    public record KeySent(
            String key,
            @Doc("The GLFW key code.") int code,
            ScreenContracts.KeyAction action,
            ScreenContracts.KeyRoute route,
            @Length(max = 6) List<ScreenContracts.Modifier> modifiers,
            ScreenContracts.KeyVia via,
            @Doc("The press went out.") boolean pressed,
            @Doc("The release went out.") boolean released,

            @Doc("Screen route: the screen consumed the press.")
            Optional<Boolean> pressHandled,

            @Doc("Screen route: the screen consumed the release. Screens rarely do; false is no failed release.")
            Optional<Boolean> releaseHandled,

            @Doc("Where the release went, when the press changed the screen.")
            Optional<ScreenContracts.KeyVia> releaseVia,

            @Doc("The screen this keystroke left open, or none; it receives the NEXT key.")
            String screenAfter,

            @Doc("The client did not tick in time: the key is still down; send release.")
            Optional<String> releaseNote,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /** The {@code listed} outcome of {@code mc.client.input.keybind} without a name: every key mapping. */
    @Variant("listed")
    public record KeybindsListed(@Length(max = 4096) List<ScreenContracts.Keybinding> keybinds)
            implements ClientReply {}

    /**
     * {@code clicks}: clicks pending on the mapping after the press. {@code rawEvent}: what became of the raw key event
     * sent alongside. {@code down}: the mapping as the press left it.
     */
    @Variant("done")
    public record KeybindDriven(
            String name,
            String title,
            String key,
            String boundTo,
            String category,
            @Doc("The mapping as the press left it.") boolean down,
            ScreenContracts.KeyAction action,

            @Doc("Clicks pending on the mapping after the press.") @Range(min = 0)
            int clicks,

            boolean released,

            @Doc("What became of the raw key event sent alongside: sent, or skipped and why.")
            String rawEvent,

            boolean windowActive,
            boolean mouseGrabbed,
            String screenAfter,
            Optional<String> releaseNote,

            @Doc("With returnTree: the screen's tree differs from just before the input.")
            Optional<Boolean> treeChanged,

            @Doc("With returnTree, when treeChanged: the tree after the input; hasScreen false when it closed the "
                    + "screen.")
            Optional<ScreenContracts.ScreenTree> tree,

            @Doc("The screen context after this call.") @ContextId
            String contextId)
            implements ClientReply {}

    /**
     * The open screen as of a read, opaque. It changes whenever a screen opens or closes, so closing and reopening the
     * same kind of screen gives a new one; it survives neither a restart nor a reconnect.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Length(min = 1, max = 64)
    @Doc("The screen context from screen.info, screen.tree or observe.container. Given, the write is refused as "
            + "context_changed, with nothing done, unless that same screen is still open.")
    public @interface ContextId {}

    /** {@code <name>Response}, tagged on {@code outcome}: {@code done} as {@code done} describes it, or refused. */
    public static <D extends Record & ClientReply> UnionType<ClientReply> reply(String name, Class<D> done) {
        return ApiTypes.union(name + "Response", "outcome", ApiTypes.record(done), ApiTypes.record(Refused.class));
    }

    /** Points of wear on a damageable item; all three are absent on a stackable one. */
    public record Wear(Optional<Integer> maxDamage, Optional<Integer> damage, Optional<Integer> durability) {
        public static final Wear NONE = new Wear(Optional.empty(), Optional.empty(), Optional.empty());

        /** The wear of a damageable item with these points; durability is {@code maxDamage - damage}. */
        public static Wear of(int maxDamage, int damage) {
            return new Wear(Optional.of(maxDamage), Optional.of(damage), Optional.of(maxDamage - damage));
        }
    }

    // ------------------------------------------------------------------ player

    /** Which way the client's player faces, in degrees: the client's own numbers, unclamped. */
    @Named("ClientLook")
    public record ClientLook(double yaw, double pitch) {}

    /** The client's own physics, which the server's copy of the player does not report. */
    @Named("ClientBody")
    public record ClientBody(
            boolean onGround,
            Point eyePos,

            @Doc("STANDING, CROUCHING, SWIMMING, FALL_FLYING, SLEEPING, …")
            String pose,

            boolean inWall,
            boolean inWater,
            boolean underWater,
            boolean crouching,
            @Doc("The client's block in the eye cell.") Optional<@ResourceId String> eyeBlock,
            @Doc("The client's block in the feet cell.") Optional<@ResourceId String> feetBlock) {}

    /** One status effect active on the client's player. */
    @Named("ActiveEffect")
    public record ActiveEffect(@ResourceId String id, int amplifier, int durationTicks) {}

    /** Health, breath, hunger, experience level and active effects, as the client sees them. */
    @Named("ClientVitals")
    public record Vitals(
            double health,
            double maxHealth,
            int air,
            int maxAir,
            int food,
            double saturation,
            int xpLevel,
            @Length(max = 256) List<ActiveEffect> effects) {}

    /** The client level's clock: raw day time, day count, time within the day and its phase. */
    @Named("WorldTime")
    public record Time(
            long dayTime,
            long dayOfWorld,
            @Range(min = 0, max = 23_999) long timeOfDay,

            @Doc("Hostile mobs spawn from sunset 13000 and burn from sunrise 23000.")
            @OneOf(
                    name = "ClientDayPhase",
                    value = {"day", "sunset", "night", "sunrise"})
            String phase) {}

    /** How far the held weapon's melee recharge has come. */
    @Named("ClientAttackReadiness")
    public record Attack(
            @Doc("The share of the weapon's damage a swing would land now.") @Range(min = 0, max = 1)
            double strengthScale,

            boolean ready,

            @Doc("Ticks until ready; swinging sooner wastes the hit.") @Range(min = 0)
            int cooldownTicks,

            @Doc("The held weapon's whole recharge.") @Range(min = 0)
            int fullCooldownTicks) {}

    /** An item stack: id, count, and wear when the item is damageable. */
    @Named("ItemStack")
    public record Stack(
            @ResourceId String id,
            @Range(min = 0) int count,
            @Doc("Damageable items only.") Optional<@Range(min = 0) Integer> maxDamage,
            @Doc("Damageable items only.") Optional<@Range(min = 0) Integer> damage,

            @Doc("Points left (maxDamage - damage); damageable items only. Unbreaking stretches a point over several "
                    + "uses.")
            Optional<@Range(min = 0) Integer> durability) {
        /** A stack whose wear fields are taken from {@code wear}. */
        public Stack(String id, int count, Wear wear) {
            this(id, count, wear.maxDamage(), wear.damage(), wear.durability());
        }
    }

    /** One non-empty inventory slot, by vanilla slot index, with the stack's wear. */
    @Named("InventorySlot")
    public record InventorySlot(
            @Doc("Vanilla inventory index: 0-8 hotbar, 9-35 main, 36-39 armour, 40 offhand.") @Range(min = 0)
            int slot,

            @ResourceId String id,
            @Range(min = 0) int count,
            Optional<@Range(min = 0) Integer> maxDamage,
            Optional<@Range(min = 0) Integer> damage,
            Optional<@Range(min = 0) Integer> durability) {
        /** A slot whose wear fields are taken from {@code wear}. */
        public InventorySlot(int slot, String id, int count, Wear wear) {
            this(slot, id, count, wear.maxDamage(), wear.damage(), wear.durability());
        }
    }

    /** The client player's inventory: selected slot, main hand, every non-empty slot and item totals. */
    @Named("ClientInventory")
    public record Inventory(
            @Range(min = 0, max = 8) int selectedSlot,
            @Doc("Absent when the hand is empty.") Optional<Stack> mainHand,
            @Doc("Every non-empty slot.") @Length(max = 256) List<InventorySlot> slots,

            @Doc("Item id to total count: the have that mc.recipe.resolve and mc.plan.acquire take.")
            @Length(max = 4096)
            Map<String, @Range(min = 0) Integer> items) {}

    /** What the crosshair is on. */
    @Tag("type")
    @Named("CrosshairHit")
    public sealed interface Hit permits Miss, BlockHit, EntityHit {}

    /** The crosshair is on nothing. */
    @Variant("miss")
    public record Miss() implements Hit {}

    /** The crosshair is on a block, on the given face. */
    @Variant("block")
    public record BlockHit(
            BlockPosition blockPos,
            String face,
            @ResourceId String block,
            @Doc("From the eyes.") double distance) implements Hit {}

    /** The crosshair is on an entity. */
    @Variant("entity")
    public record EntityHit(
            @EntityId int entityId, @ResourceId String entityType, Point pos, double distance) implements Hit {}

    /** Everything but {@code present} and {@code fps} is absent when no player is in a world. */
    @Named("ClientPlayer")
    public record ClientPlayer(
            @Doc("A player is in a world on this client.") boolean present,
            Optional<String> name,
            Optional<String> uuid,
            Optional<@ResourceId String> dimension,
            @Doc("The feet.") Optional<Point> pos,
            Optional<ClientLook> look,
            Optional<ClientBody> body,
            Optional<Vitals> vitals,
            Optional<Time> time,

            @Doc("survival, creative, adventure or spectator.")
            Optional<String> gameMode,

            Optional<Attack> attack,
            Optional<Inventory> inventory,

            @Doc("The crosshair's block or entity, by the client's raycast.")
            Optional<Hit> hit,

            @Range(min = 0) int fps) {}

    // ------------------------------------------------------------------ scene

    /** The reply of {@code mc.client.scene}; everything but {@code present} is absent when no player is in a world. */
    @Named("ClientScene")
    public record ClientScene(
            boolean present,
            Optional<BlockPosition> pos,
            Optional<Double> health,
            Optional<Integer> food,

            @Doc("The bot's own phase, which its shelter reflex reads; its edges differ from mc.observe.player's "
                    + "time.phase.")
            Optional<
                            @OneOf(
                                    name = "BotDayPhase",
                                    value = {"DAY", "DUSK", "NIGHT", "DAWN"})
                            String>
                    dayPhase,

            Optional<Boolean> skyExposed,
            Optional<Boolean> exposedAtNight,

            @Doc("The hazard grid leaves no safe way to retreat.")
            Optional<Boolean> cornered,

            @Doc("Lethal-fall cells in the local hazard grid.")
            Optional<@Range(min = 0) Integer> lethalCount,

            @Doc("The hazard field as an ASCII map.") Optional<@Length(max = 256) List<String>> rows) {}

    // ------------------------------------------------------------------ blocks

    /** Parameters of {@code mc.client.blocks}: the center, radius and an optional block or tag filter. */
    @Named("ClientBlocksParams")
    public record BlocksParams(
            @Doc("Omitted: the player's feet.") Optional<BlockPosition> center,

            @Doc("Chebyshev radius.") @Default("4") @Range(min = 0, max = 16)
            int radius,

            @Doc("Only this block, or the blocks of this #tag.")
            Optional<@BlockOrTag String> type) {

        /** Every block within {@code radius} of the player's feet. */
        public static BlocksParams aroundPlayer(int radius) {
            return new BlocksParams(Optional.empty(), radius, Optional.empty());
        }

        /** Every block within {@code radius} of {@code center}. */
        public static BlocksParams around(BlockPosition center, int radius) {
            return new BlocksParams(Optional.of(center), radius, Optional.empty());
        }
    }

    /** One non-air cell as the client has it loaded. */
    @Named("ClientBlock")
    public record BlockRow(
            BlockPosition pos,
            @ResourceId String type,

            @Doc("Block state properties; absent for a state with none.")
            Optional<@Length(max = 64) Map<String, String>> state) {}

    /** A chunk by its chunk coordinates. */
    @Named("ChunkRef")
    public record ChunkRef(int x, int z) {}

    /** The reply of {@code mc.client.blocks}: the non-air cells found and any chunks the client has not loaded. */
    @Named("ClientBlockScan")
    public record BlockScan(
            @Doc("Every non-air cell, air skipped.") @Length(max = 40_000)
            List<BlockRow> blocks,

            BlockPosition center,
            @Range(min = 0, max = 16) int radius,

            @Doc("Chunks the client has not loaded, whose cells read as air.")
            Optional<@Length(max = 64) List<ChunkRef>> unloaded) {}

    // ------------------------------------------------------------------ chat

    /** Where a received chat line came from: a player, or the system. */
    @Named("ChatKind")
    public enum ChatKind implements WireName {
        /** Chat with a real sender profile. */
        PLAYER,
        /** Command feedback, broadcasts, console /say, server messages. */
        SYSTEM;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How {@code mc.client.chat.send} sent the text: as a command (it began with /) or as chat. */
    @Named("SentAs")
    public enum SentAs implements WireName {
        COMMAND,
        CHAT;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** {@code self}: the server's echo of this player's own chat. */
    @Named("ChatLine")
    public record ChatLine(
            @Range(min = 0) long seq,
            ChatKind kind,
            @Doc("Plain text, formatting stripped.") String text,

            @Doc("The server's echo of this player's own chat.")
            boolean self) {}

    /** {@code lines}: what arrived after the send, oldest first, own echoes left out. */
    @Named("LaterChat")
    public record LaterChat(
            @Length(max = 512) List<ChatLine> lines,
            @Doc("No line arrived within awaitReplyMs.") boolean timedOut,
            @Range(min = 0) long waitedMs) {}

    /** Parameters of {@code mc.client.chat.send}: the text and how long to wait for later chat. */
    @Named("ChatSendParams")
    public record ChatSendParams(
            @Doc("A leading / sends it as a command.") @Length(min = 1, max = 256)
            String text,

            @Doc("Wait up to this long for chat lines that arrive after the send; 0 returns at once.")
            @Default("0")
            @Range(min = 0, max = 30_000)
            int awaitReplyMs) {}

    /** The {@code done} outcome of {@code mc.client.chat.send}: how it was sent, its length, and any later chat. */
    @Variant("done")
    public record ChatSent(
            SentAs kind,
            @Range(min = 0) int length,
            @Doc("With awaitReplyMs only.") Optional<LaterChat> later) implements ClientReply {}

    /** Parameters of {@code mc.client.chat.history}: how many lines, and from which seq. */
    @Named("ChatHistoryParams")
    public record ChatHistoryParams(
            @Doc("The newest this many lines.") @Default("50") @Range(min = 1, max = 256)
            int limit,

            @Doc("Only lines with seq at or after this.") @Default("0") @Range(min = 0)
            long sinceSeq) {}

    /** One received chat line in the history, with how many ticks ago it arrived. */
    @Named("HistoryLine")
    public record HistoryLine(
            @Range(min = 0) long seq,
            ChatKind kind,
            String text,
            boolean self,
            @Range(min = 0) long ageTicks) {}

    /** {@code nextSeq}: pass it as sinceSeq next time to read only the lines after these. */
    @Named("ChatHistory")
    public record ChatHistory(
            @Doc("The seq the next line will get; pass it as sinceSeq to poll.") @Range(min = 0)
            long nextSeq,

            @Doc("Newest first.") @Length(max = 256) List<HistoryLine> messages) {}

    // ------------------------------------------------------------------ overlays

    /** Parameters of {@code mc.client.overlays}: which HUD overlay groups to dismiss. */
    @Named("OverlaysParams")
    public record OverlaysParams(
            @Doc("Turn the tutorial hints (Move with W,A,S,D…) off for good.") @Default("true")
            boolean tutorial,

            @Doc("Clear the toast queue: advancements, recipes, system.") @Default("true")
            boolean toasts) {}

    /** {@code tutorial}/{@code toasts}: that group was dismissed now; an error says why it was not. */
    @Named("OverlaysCleared")
    public record OverlaysCleared(
            boolean tutorial, boolean toasts, Optional<String> tutorialError, Optional<String> toastsError) {}

    // ------------------------------------------------------------------ screenshot

    /** The encoding of a screenshot: lossless png or smaller jpeg. */
    @Named("ImageFormat")
    public enum ImageFormat implements WireName {
        PNG,
        JPEG;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Parameters of {@code mc.client.screenshot}: the size limits, format and jpeg quality. */
    @Named("ScreenshotParams")
    public record ScreenshotParams(
            @Doc("Shrink, keeping the aspect, to at most this wide.")
            Optional<@Range(min = 16, max = 8192) Integer> maxWidth,

            @Doc("Shrink, keeping the aspect, to at most this high.")
            Optional<@Range(min = 16, max = 8192) Integer> maxHeight,

            @Default("png") ImageFormat format,

            @Doc("JPEG only.") @Default("85") @Range(min = 1, max = 100)
            int quality) {}

    /** The reply of {@code mc.client.screenshot}: the encoded image and the frame it was taken from. */
    @Named("Screenshot")
    public record Screenshot(
            ImageFormat format,
            @Range(min = 0) int width,
            @Range(min = 0) int height,

            @Doc("The frame captured: two captures with the same frame are the same image.") @Range(min = 0)
            long frame,

            @Doc("A frame was drawn after the request; false means the image may predate it.")
            boolean frameWaited,

            boolean windowActive,
            @Range(min = 0) int fps,
            @Doc("The encoded image.") String base64) {}
}
