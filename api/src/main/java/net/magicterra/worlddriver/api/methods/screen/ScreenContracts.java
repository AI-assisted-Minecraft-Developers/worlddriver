package net.magicterra.worlddriver.api.methods.screen;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.methods.client.ClientContracts.Clicked;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ContextId;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.HotbarSelected;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeySent;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeybindDriven;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeybindsListed;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Replaced;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SliderSet;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SlidersRead;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.SlotClicked;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Typed;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.ResourceId;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes;
import net.magicterra.worlddriver.api.methods.shared.Responses.BotRef;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiTypes;
import net.magicterra.worlddriver.api.schema.DecodeContext;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.SelfChecked;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.schema.WireName;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.Variant;

/**
 * The open screen and the input that reaches it: reading the screen and its menu, then clicking, typing
 * and pressing keys. A read answers a {@code contextId} naming the screen it read; a write given that id
 * checks it on the client thread just before acting and is refused as {@code context_changed}, with
 * nothing done, once another screen has opened since. Without one, a write acts on whatever is open.
 * A read followed by a write is never one atomic step.
 */
public final class ScreenContracts {
    private ScreenContracts() {}


    // ------------------------------------------------------------------ screen.info

    @Named("ScreenInfo")
    public record ScreenInfo(
            @ContextId String contextId,
            boolean hasScreen,
            boolean worldOpen,
            boolean hasPlayer,
            @Doc("A loading overlay is drawn over the screen; wait for it to go.") boolean overlayActive,
            Optional<String> overlayType,
            @Doc("The window has focus. A mod's key handler usually ignores keys without it.") boolean windowActive,
            @Doc("The other gate such a handler puts first.") boolean mouseGrabbed,
            @Doc("The screen's class, e.g. InventoryScreen.") Optional<String> type,
            Optional<String> title,
            @Doc("In GUI coordinates, which input.click takes.") Optional<Integer> width,
            Optional<Integer> height,
            @Doc("On the death screen: what killed the player.") Optional<String> causeOfDeath) {}

    public static final RecordType<ScreenInfo> SCREEN_INFO = ApiTypes.record(ScreenInfo.class);

    public static final MethodSpec<NoParams, ScreenInfo> INFO = MethodSpec.of("mc.client.screen.info", CommonTypes.NO_PARAMS,
                    SCREEN_INFO,
                    "The open screen in brief, and whether a world and a player are there: the cheap probe to make "
                            + "before any other mc.client.* call, and the contextId a screen write can be held to. "
                            + "windowActive and mouseGrabbed are what a mod's key handler usually demands before it "
                            + "acts; read them when a keystroke seems to vanish.")
            .asPollable();

    // ------------------------------------------------------------------ screen.tree

    @Named("ScreenBox")
    public record Box(int x, int y, int width, int height) {}

    @Named("SlotItem")
    public record SlotItem(@ResourceId String id, @Range(min = 0) int count) {}

    /**
     * One widget, list row or menu slot. {@code ref} names the object itself, so it outlives the read that
     * handed it out; {@code parent} is the ref of the node holding it, absent at the top. {@code index} is a
     * row's place in its list or a slot's index in the menu, which slotClick takes.
     */
    @Named("WidgetNode")
    public record WidgetNode(
            @Doc("What input.click's ref takes. The same for this widget, row or slot in every read while it lives, "
                    + "never reused; a rebuilt screen's widgets get new ones.")
            @Range(min = 0) int ref,
            @Doc("The ref of the node holding it; absent at the top.") Optional<@Range(min = 0) Integer> parent,
            @Doc("0 at the top.") @Range(min = 0) int depth,
            @Doc("The widget's class; ContainerSlots holds the menu's Slot nodes.") String type,
            @Doc("A list row's place, or a Slot's menu index for input.slotClick.")
            Optional<@Range(min = 0) Integer> index,
            @Doc("Where it is, in the GUI coordinates input.click takes.") Optional<Box> box,
            Optional<Boolean> visible,
            Optional<Boolean> active,
            Optional<Boolean> focused,
            @Doc("The row a selection list reports chosen. A widget that paints its own selection says nothing here.")
            Optional<Boolean> selected,
            @Doc("The label, in the player's language.") Optional<String> message,
            @Doc("The label's translation key, the same in every language.") Optional<String> key,
            @Doc("A text box's contents.") Optional<String> value,
            @Doc("A Slot's stack; absent when empty.") Optional<SlotItem> item,
            @Doc("This node threw while being read; the rest of the tree stands.") Optional<String> error) {}

    @Named("ScreenTree")
    public record ScreenTree(
            @ContextId String contextId,
            boolean hasScreen,
            Optional<String> type,
            Optional<String> title,
            Optional<Integer> width,
            Optional<Integer> height,
            Optional<String> causeOfDeath,
            @Doc("The widget walk failed as a whole.") Optional<String> error,
            @Doc("The menu's slots could not be listed.") Optional<String> slotsError,
            @Doc("Every node, depth first: a node comes right before the nodes it holds.")
            @Length(max = 65536) List<WidgetNode> nodes) {}

    public static final RecordType<ScreenTree> SCREEN_TREE = ApiTypes.record(ScreenTree.class);

    public static final MethodSpec<NoParams, ScreenTree> TREE = MethodSpec.of("mc.client.screen.tree", CommonTypes.NO_PARAMS,
                    SCREEN_TREE,
                    "Every widget, list row and menu slot on the open screen, with its box in GUI coordinates: what "
                            + "to pick a click target from without a screenshot, and right for modded, self-drawn "
                            + "screens too. The tree is flattened into nodes, each naming its parent. A container "
                            + "screen adds a ContainerSlots node whose Slot nodes carry the menu index and stack. "
                            + "STRUCTURE, NOT PAINT: a widget that draws its own selection reads the same before and "
                            + "after the click that chose it; read that with a screenshot.")
            .asPollable();

    // ------------------------------------------------------------------ screen.close

    @Named("ScreenCloseParams")
    public record CloseParams(Optional<@ContextId String> contextId) {}

    /** {@code closed}: the screen that was open, absent when none was. {@code contextId}: the state after. */
    @Named("ScreenClosed")
    public record Closed(
            @Doc("The screen it closed; absent when none was open.") Optional<String> closed,
            @Doc("The screen context after this call.") @ContextId String contextId) {}

    public static final MethodSpec<CloseParams, Closed> CLOSE = MethodSpec.of("mc.client.screen.close",
                    ApiTypes.record(CloseParams.class),
                    ApiTypes.record(Closed.class),
                    "Close the open screen, as setScreen(null) does. Succeeds with nothing open too.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.click / mouseMove

    /** Where a click or typed text goes while a screen is open. */
    @Named("InputRoute")
    public enum InputRoute implements WireName {
        /** Straight to the screen's own handler, which answers whether a widget took it. */
        SCREEN,
        /** Through vanilla's mouse or keyboard handler as a real device event, so mods that listen for input
         *  events (JEI's overlay, for one) see it too; nothing answers whether it was taken. */
        RAW;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("ClickParams")
    public record ClickParams(
            @Doc("A node's ref from screen.tree: clicks the centre of its box as it is now.")
            Optional<@Range(min = 0) Integer> ref,
            @Doc("Without ref: GUI coordinates, as screen.tree gives them.") Optional<Double> x,
            Optional<Double> y,
            @Doc("0 left, 1 right, 2 middle.") @Default("0") @Range(min = 0, max = 2) int button,
            @Doc("screen: the screen's own handler, which answers handled. raw: a real mouse event, which mods "
                    + "listening for input events (JEI, Jade) also see; no handled.")
            @Default("screen") InputRoute route,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            boolean point = x.isPresent() || y.isPresent();
            if (ref.isPresent() && point) {
                at.field("ref").fail(ApiIssue.Code.INVALID_VALUE, "click a ref or a point, not both");
            } else if (ref.isEmpty() && (x.isEmpty() || y.isEmpty())) {
                at.field(x.isEmpty() ? "x" : "y").fail(ApiIssue.Code.INVALID_VALUE,
                        "give ref, or both x and y");
            }
        }
    }

    public static final MethodSpec<ClickParams, ClientReply> CLICK = MethodSpec.of("mc.client.input.click",
                    ApiTypes.record(ClickParams.class),
                    ClientContracts.reply("Click", Clicked.class),
                    "Click the open screen: hover, press, release, all on one frame. Name the target by a ref from "
                            + "screen.tree, which clicks where that widget is now, or by GUI coordinates. Refused as "
                            + "no_screen when none is open, and as no_target when the ref is no longer on the screen, "
                            + "is hidden, or lies outside the visible part of what holds it.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    @Named("MouseMoveParams")
    public record MoveParams(
            @Doc("GUI coordinates. Without them the cursor stays put: in the world a moved cursor turns the camera.")
            Optional<Double> x,
            Optional<Double> y,
            @Doc("Wheel notches, positive away from the user. On a screen it scrolls what is under the cursor; in "
                    + "the world it steps the hotbar, or a spectator's speed.")
            @Default("0.0") double scroll,
            @Doc("Horizontal wheel notches, positive to the right.") @Default("0.0") double scrollX) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (x.isPresent() != y.isPresent()) {
                at.field(x.isEmpty() ? "x" : "y").fail(ApiIssue.Code.INVALID_VALUE, "give both x and y, or neither");
            } else if (x.isEmpty() && scroll == 0 && scrollX == 0) {
                at.field("x").fail(ApiIssue.Code.INVALID_VALUE, "give x and y, a scroll, or both");
            }
        }
    }

    /** {@code wx}/{@code wy}: the window pixel the cursor is at, GUI coordinates times {@code scale}. */
    @Named("MouseMoved")
    public record Moved(double wx, double wy, @Doc("The GUI scale.") double scale) {}

    public static final MethodSpec<MoveParams, Moved> MOUSE_MOVE = MethodSpec.of("mc.client.input.mouseMove",
                    ApiTypes.record(MoveParams.class),
                    ApiTypes.record(Moved.class),
                    "Move the cursor to GUI coordinates without clicking, hover effects included: park it before a "
                            + "screenshot so no tooltip covers the screen. With scroll, then turn the mouse wheel there.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.slotClick

    /** Vanilla's ClickType under the names a caller uses. */
    @Named("ClickKind")
    public enum ClickKind implements WireName {
        PICKUP("pickup"), QUICK_MOVE("quickMove"), SWAP("swap"), CLONE("clone"), THROW("throw"),
        PICKUP_ALL("pickupAll"), QUICK_CRAFT("quickCraft");

        private final String wire;
        ClickKind(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    @Named("SlotClickParams")
    public record SlotClickParams(
            @Doc("The menu's slot index, from observe.container or screen.tree.") @Range(min = 0) int slot,
            @Doc("0 left, 1 right, 2 middle; for swap, the hotbar slot 0-8 to swap with.") @Default("0")
            @Range(min = 0, max = 8) int button,
            @Default("pickup") ClickKind type,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            @Doc("A bot or a joined player on this server, as player:<name>: click its open menu on the server, as the client's click "
                    + "packet would. Not with returnTree or contextId.")
            Optional<@BotRef String> bot,
            Optional<@ContextId String> contextId) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (bot.isEmpty()) return;
            if (!bot.get().startsWith(PLAYER_PREFIX)) {
                at.field("bot").fail(ApiIssue.Code.INVALID_VALUE, PLAYER_ONLY);
            } else if (returnTree) {
                at.field("returnTree").fail(ApiIssue.Code.INVALID_VALUE, "returnTree reads this client's screen, not a server player's menu");
            } else if (contextId.isPresent()) {
                at.field("contextId").fail(ApiIssue.Code.INVALID_VALUE, "a server player's menu has no screen context");
            }
        }
    }

    private static final String PLAYER_PREFIX = "player:";
    private static final String PLAYER_ONLY = "bot names a player on this server as player:<name>";

    public static final MethodSpec<SlotClickParams, ClientReply> SLOT_CLICK = MethodSpec.of("mc.client.input.slotClick",
                    ApiTypes.record(SlotClickParams.class),
                    ClientContracts.reply("SlotClick", SlotClicked.class),
                    "Click a slot of the open container menu with an explicit click type, sent to the server as a "
                            + "real container click: pickup (button 0 takes or places the stack, 1 half or one), "
                            + "quickMove (shift-click), throw (button 0 drops one, 1 the stack), swap (with hotbar slot "
                            + "button), clone (creative middle-click), pickupAll (double-click), quickCraft (drag). "
                            + "Refused as no_container, no_player, or no_target for a slot past the menu's end. "
                            + "With bot, a server player's open menu is clicked on the server instead.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ input.setHotbarSlot

    @Named("SetHotbarSlotParams")
    public record HotbarParams(
            @Doc("0 is leftmost.") @Range(min = 0, max = 8) int slot,
            Optional<@ContextId String> contextId) {}

    public static final MethodSpec<HotbarParams, ClientReply> SET_HOTBAR_SLOT = MethodSpec.of("mc.client.input.setHotbarSlot",
                    ApiTypes.record(HotbarParams.class),
                    ClientContracts.reply("SetHotbarSlot", HotbarSelected.class),
                    "Select the held hotbar slot and tell the server, so the next attack or use takes that item. "
                            + "mc.client.player's inventory says which slot holds what. Refused as no_player.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ input.typeText / replaceText

    @Named("TypeTextParams")
    public record TypeParams(
            @Length(max = 4096) String text,
            @Doc("screen: the screen's own handler. raw: real character events, which mods listening for input events "
                    + "also see — a search box in JEI's overlay, for one.")
            @Default("screen") InputRoute route,
            @Doc("A node's ref from screen.tree: the text box to type into, focused first. Without it, the focused "
                    + "widget.")
            Optional<@Range(min = 0) Integer> ref,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) {}

    public static final MethodSpec<TypeParams, ClientReply> TYPE_TEXT = MethodSpec.of("mc.client.input.typeText",
                    ApiTypes.record(TypeParams.class),
                    ClientContracts.reply("TypeText", Typed.class),
                    "Type text into the focused widget of the open screen, one character event per code point, "
                            + "appending at the cursor; name the box by a ref from screen.tree to focus it first. To "
                            + "overwrite a field use input.replaceText. Refused as no_screen, and as no_target when "
                            + "the ref is not on the screen or is not a text box.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    @Named("ReplaceTextParams")
    public record ReplaceParams(
            @Doc("The box's new whole contents.") @Length(max = 4096) String text,
            @Doc("A node's ref from screen.tree: the text box to overwrite.")
            Optional<@Range(min = 0) Integer> ref,
            @Doc("Without ref: case-insensitive part of the box's value or label; needed only when several boxes are "
                    + "open and none has focus.")
            Optional<@Length(min = 1, max = 256) String> match,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (ref.isPresent() && match.isPresent()) {
                at.field("match").fail(ApiIssue.Code.INVALID_VALUE, "pick a text box by ref or by match, not both");
            }
        }
    }

    public static final MethodSpec<ReplaceParams, ClientReply> REPLACE_TEXT = MethodSpec.of("mc.client.input.replaceText",
                    ApiTypes.record(ReplaceParams.class),
                    ClientContracts.reply("ReplaceText", Replaced.class),
                    "Overwrite a text box's whole contents at once: name the box by a ref from screen.tree, else the "
                            + "box match names, else the focused one, else the only one. Refused as no_screen, "
                            + "no_target (the ref is gone or not a text box, or nothing matches) or ambiguous "
                            + "(several boxes, none focused, no match).")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.slider

    @Named("SliderParams")
    public record SliderParams(
            @Doc("A node's ref from screen.tree: the slider to set, or alone to read.")
            Optional<@Range(min = 0) Integer> ref,
            @Doc("Case-insensitive part of the slider's label.") Optional<@Length(min = 1, max = 256) String> match,
            @Doc("Its place among the screen's sliders, without match.") Optional<@Range(min = 0) Integer> index,
            @Doc("The value to set. Omitted: read every slider, or the one ref names.")
            Optional<@Range(min = 0, max = 1) Double> fraction,
            @Doc("With fraction: also answer treeChanged, and when it is true the screen.tree read right after the "
                    + "input. Not in it: a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            int named = (ref.isPresent() ? 1 : 0) + (match.isPresent() ? 1 : 0) + (index.isPresent() ? 1 : 0);
            if (named > 1) {
                at.field(index.isPresent() ? "index" : "match").fail(ApiIssue.Code.INVALID_VALUE,
                        "pick a slider by one of ref, match or index");
            }
        }
    }

    @Named("SliderValue")
    public record Slider(@Range(min = 0) int index, String label, @Range(min = 0, max = 1) double value) {}

    public static final MethodSpec<SliderParams, ClientReply> SLIDER = MethodSpec.of("mc.client.input.slider",
                    ApiTypes.record(SliderParams.class),
                    ApiTypes.<ClientReply>union("SliderResponse", "outcome", ApiTypes.record(SlidersRead.class), ApiTypes.record(SliderSet.class),
                            ApiTypes.record(ClientContracts.Refused.class)),
                    "Read or set a GUI slider: render distance, FOV, volume. Without fraction it reads every slider's "
                            + "label and 0-1 value, which only the shown slider can tell. With fraction it sets the "
                            + "slider named by a ref from screen.tree, else match, else index, else the only one, and "
                            + "commits it as dragging would. Refused as no_screen, no_target or ambiguous.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.key / keybind

    @Named("KeyAction")
    public enum KeyAction implements WireName {
        PRESS, RELEASE,
        /** Press now, release on the next client tick. */
        CLICK;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("KeyRoute")
    public enum KeyRoute implements WireName {
        /** The open screen, else the key bindings: where a real keystroke goes. */
        AUTO,
        /** The key bindings, even under an open screen. */
        KEYBIND,
        /** The open screen only. */
        SCREEN,
        /** Vanilla's keyboard handler as a real key event, whatever is open: it reaches the screen and the
         *  bindings as vanilla decides, and mods listening for input events (JEI's U and R) see it too. */
        RAW;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("KeyModifier")
    public enum Modifier implements WireName {
        SHIFT("shift"), CTRL("ctrl"), ALT("alt"), SUPER("super"), CAPS_LOCK("capsLock"), NUM_LOCK("numLock");

        private final String wire;
        Modifier(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    @Named("KeyVia")
    public enum KeyVia implements WireName {
        SCREEN, KEYBIND, RAW;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    @Named("KeyParams")
    public record KeyParams(
            @Doc("ENTER, ESCAPE, TAB, BACKSPACE, DELETE, SPACE, LEFT, RIGHT, UP, DOWN, HOME, END, PAGEUP, PAGEDOWN, "
                    + "F1-F25, A-Z, 0-9, or a modifier key (LEFT_SHIFT, ALT, …). Any case.")
            @Pattern(value = "[A-Za-z0-9_]{1,16}", expected = "a key name such as ENTER") String key,
            @Default("click") KeyAction action,
            @Default("auto") KeyRoute route,
            @Doc("Modifier bits for a SCREEN shortcut. A modified key BINDING needs input.keybind.") @Default("[]")
            @Length(max = 6) List<Modifier> modifiers,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) {}

    public static final MethodSpec<KeyParams, ClientReply> KEY = MethodSpec.of("mc.client.input.key",
                    ApiTypes.record(KeyParams.class),
                    ClientContracts.reply("Key", KeySent.class),
                    "Send a keyboard event. click (the default) presses now and releases on the NEXT client tick, "
                            + "routed again from whatever the press left open. route auto sends it where vanilla "
                            + "would: the open screen, else the key bindings (E inventory, ESCAPE pause, T chat); "
                            + "keybind reaches the bindings under an open screen; screen only the screen; raw is a real "
                            + "key event through vanilla's keyboard handler, which mods listening for input events see "
                            + "too, and answers no handled. For walking "
                            + "use mc.bot.*. Refused as no_screen (route screen with none open) or no_player (no "
                            + "screen and no player to take it). An unknown key name is invalid_params.")
            .effects(MethodSpec.Effect.CLIENT_INPUT)
            .mayWait();

    @Named("KeybindParams")
    public record KeybindParams(
            @Doc("A mapping id (key.inventory) or part of an id or title. Omitted: list every mapping.")
            Optional<@Length(min = 1, max = 256) String> name,
            @Default("click") KeyAction action,
            @Doc("Also answer treeChanged, and when it is true the screen.tree read right after the input. Not in it: "
                    + "a screen the server opens in reply, and slots the server fills later.")
            @Default("false")
            boolean returnTree,
            Optional<@ContextId String> contextId) {}

    /** One key mapping; {@code key} is the bound key's id and {@code boundTo} what the controls screen shows. */
    @Named("Keybinding")
    public record Keybinding(String name, String title, String key, String boundTo, boolean bound, String category,
                             boolean down) {}


    public static final MethodSpec<KeybindParams, ClientReply> KEYBIND = MethodSpec.of("mc.client.input.keybind",
                    ApiTypes.record(KeybindParams.class),
                    ApiTypes.<ClientReply>union("KeybindResponse", "outcome", ApiTypes.record(KeybindsListed.class), ApiTypes.record(KeybindDriven.class),
                            ApiTypes.record(ClientContracts.Refused.class)),
                    "Drive a key mapping BY NAME. Use it rather than input.key when the binding carries a modifier "
                            + "(ALT+Y, common in mod packs): that match reads the real keyboard, where a synthesized ALT "
                            + "never appears. It also reaches a binding the player rebound. It marks the mapping down "
                            + "and counts a click, and sends the key as a raw event too, with the binding's modifier "
                            + "cleared for that event, for the mods that listen to key events. A mod's handler usually "
                            + "refuses keys unless windowActive and mouseGrabbed. Without a name it lists every "
                            + "mapping. Refused as no_target or ambiguous.")
            .effects(MethodSpec.Effect.CLIENT_INPUT)
            .mayWait();

    // ------------------------------------------------------------------ observe.container

    @Named("ContainerParams")
    public record ContainerParams(
            @Doc("A chest, barrel, hopper, furnace… Omitted: the menu open on this client.")
            Optional<BlockPosition> pos,
            @Doc("A bot or a joined player on this server, as player:<name>: the menu open on it, read on the server "
                    + "(source player). Not with pos.")
            Optional<@BotRef String> bot) implements SelfChecked {
        @Override public void check(DecodeContext at) {
            if (bot.isEmpty()) return;
            if (pos.isPresent()) {
                at.field("bot").fail(ApiIssue.Code.INVALID_VALUE, "give pos or bot, not both");
            } else if (!bot.get().startsWith(PLAYER_PREFIX)) {
                at.field("bot").fail(ApiIssue.Code.INVALID_VALUE, PLAYER_ONLY);
            }
        }
    }

    /** An empty slot carries its index alone. */
    @Named("ContainerSlot")
    public record SlotRow(
            @Doc("Vanilla's slot index; furnace 0 input, 1 fuel, 2 output.") @Range(min = 0) int index,
            @Doc("Absent for an empty slot.") Optional<@ResourceId String> id,
            Optional<@Range(min = 0) Integer> count,
            Optional<@Range(min = 0) Integer> maxDamage,
            Optional<@Range(min = 0) Integer> damage,
            @Doc("Points left; damageable items only.") Optional<@Range(min = 0) Integer> durability) {
        public SlotRow(int index, Optional<String> id, Optional<Integer> count, ClientContracts.Wear wear) {
            this(index, id, count, wear.maxDamage(), wear.damage(), wear.durability());
        }

        public static SlotRow empty(int index) {
            return new SlotRow(index, Optional.empty(), Optional.empty(), ClientContracts.Wear.NONE);
        }
    }

    @Tag("source") @Named("ContainerView")
    public sealed interface ContainerView permits BlockContainer, MenuContainer, PlayerMenu {}

    /** {@code present}: a block entity is there; {@code slots}: when it holds items. */
    @Variant("block")
    public record BlockContainer(
            BlockPosition pos,
            boolean present,
            @Doc("The block entity's type.") Optional<@ResourceId String> blockEntity,
            Optional<@Length(max = 4096) List<SlotRow>> slots) implements ContainerView {}

    /** {@code present}: the open screen has a container menu. */
    @Variant("menu")
    public record MenuContainer(
            @Doc("The screen these slots belong to; input.slotClick can be held to it.") @ContextId String contextId,
            boolean present,
            Optional<String> screen,
            Optional<@Length(max = 4096) List<SlotRow>> slots) implements ContainerView {}

    /** The menu open on a server player; {@code carried} is a slot row whose index is 0. */
    @Variant("player")
    public record PlayerMenu(
            @BotRef String bot,
            @Doc("The open menu's type; absent for the player's own inventory, which is open when nothing else is.")
            Optional<@ResourceId String> menuType,
            @Doc("Vanilla's container id; 0 is the player's own inventory.") @Range(min = 0) int containerId,
            @Doc("Changes on every slot change; a click sent with an older one is resynced by vanilla.") @Range(min = 0) int stateId,
            @Length(max = 4096) List<SlotRow> slots,
            @Doc("The stack on the cursor; absent when empty.") Optional<SlotRow> carried) implements ContainerView {}

    public static final UnionType<ContainerView> CONTAINER_VIEW = ApiTypes.union(ContainerView.class);

    public static final MethodSpec<ContainerParams, ContainerView> CONTAINER = MethodSpec.of("mc.observe.container",
                    ApiTypes.record(ContainerParams.class),
                    CONTAINER_VIEW,
                    "Read a container's slots. With pos, the block entity there, read on the server (source block). "
                            + "Without, whatever container menu is open on this client: the inventory, a crafting "
                            + "table, a chest the server opened (source menu), with the contextId a slotClick can be "
                            + "held to. Without pos and without a client it is capability_unavailable. "
                            + "With bot, the menu open on that server player (source player).")
            .asPollable();
}
