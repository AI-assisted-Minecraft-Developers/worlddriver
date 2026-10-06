package net.magicterra.worlddriver.api.methods;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.RecordType;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.contract.WireName;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.ClientContracts.NoParams;
import net.magicterra.worlddriver.api.methods.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;

/**
 * The open screen and the input that reaches it: reading the screen and its menu, then clicking, typing
 * and pressing keys. A read answers a {@code contextId} naming the screen it read; a write given that id
 * checks it on the client thread just before acting and is refused as {@code context_changed}, with
 * nothing done, once another screen has opened since. Without one, a write acts on whatever is open.
 * A read followed by a write is never one atomic step.
 */
public final class ScreenContracts {
    private ScreenContracts() {}

    private static final ApiType<Integer> COUNT = ApiTypes.integer(0, Integer.MAX_VALUE);
    private static final ApiType<Integer> GUI = ApiTypes.integer();

    // ------------------------------------------------------------------ screen.info

    public record ScreenInfo(String contextId, boolean hasScreen, boolean worldOpen, boolean hasPlayer,
                             boolean overlayActive, Optional<String> overlayType, boolean windowActive, boolean mouseGrabbed,
                             Optional<String> type, Optional<String> title, Optional<Integer> width,
                             Optional<Integer> height, Optional<String> causeOfDeath) {}

    public static final RecordType<ScreenInfo> SCREEN_INFO = ApiTypes.record("ScreenInfo",
            ApiField.field("contextId", ClientContracts.CONTEXT_ID).required().forGetter(ScreenInfo::contextId),
            ApiField.field("hasScreen", ApiTypes.bool()).required().forGetter(ScreenInfo::hasScreen),
            ApiField.field("worldOpen", ApiTypes.bool()).required().forGetter(ScreenInfo::worldOpen),
            ApiField.field("hasPlayer", ApiTypes.bool()).required().forGetter(ScreenInfo::hasPlayer),
            ApiField.field("overlayActive", ApiTypes.bool())
                    .describe("A loading overlay is drawn over the screen; wait for it to go.")
                    .required().forGetter(ScreenInfo::overlayActive),
            ApiField.field("overlayType", ApiTypes.string()).optional().forGetter(ScreenInfo::overlayType),
            ApiField.field("windowActive", ApiTypes.bool())
                    .describe("The window has focus. A mod's key handler usually ignores keys without it.")
                    .required().forGetter(ScreenInfo::windowActive),
            ApiField.field("mouseGrabbed", ApiTypes.bool()).describe("The other gate such a handler puts first.")
                    .required().forGetter(ScreenInfo::mouseGrabbed),
            ApiField.field("type", ApiTypes.string()).describe("The screen's class, e.g. InventoryScreen.")
                    .optional().forGetter(ScreenInfo::type),
            ApiField.field("title", ApiTypes.string()).optional().forGetter(ScreenInfo::title),
            ApiField.field("width", GUI).describe("In GUI coordinates, which input.click takes.").optional().forGetter(ScreenInfo::width),
            ApiField.field("height", GUI).optional().forGetter(ScreenInfo::height),
            ApiField.field("causeOfDeath", ApiTypes.string()).describe("On the death screen: what killed the player.")
                    .optional().forGetter(ScreenInfo::causeOfDeath),
            ScreenInfo::new);

    public static final MethodSpec<NoParams, ScreenInfo> INFO = MethodSpec.of("mc.client.screen.info", ClientContracts.NO_PARAMS,
                    SCREEN_INFO,
                    "The open screen in brief, and whether a world and a player are there: the cheap probe to make "
                            + "before any other mc.client.* call, and the contextId a screen write can be held to. "
                            + "windowActive and mouseGrabbed are what a mod's key handler usually demands before it "
                            + "acts; read them when a keystroke seems to vanish.")
            .asPollable();

    // ------------------------------------------------------------------ screen.tree

    public record Box(int x, int y, int width, int height) {}

    public record SlotItem(String id, int count) {}

    /**
     * One widget, list row or menu slot. {@code parent} is the id of the node holding it, absent at the top;
     * {@code index} is a row's place in its list or a slot's index in the menu, which slotClick takes.
     */
    public record WidgetNode(int id, Optional<Integer> parent, int depth, String type, Optional<Integer> index,
                             Optional<Box> box, Optional<Boolean> visible, Optional<Boolean> active, Optional<Boolean> focused,
                             Optional<Boolean> selected, Optional<String> message, Optional<String> key, Optional<String> value,
                             Optional<SlotItem> item, Optional<String> error) {}

    public record ScreenTree(String contextId, boolean hasScreen, Optional<String> type, Optional<String> title,
                             Optional<Integer> width, Optional<Integer> height, Optional<String> causeOfDeath,
                             Optional<String> error, Optional<String> slotsError, List<WidgetNode> nodes) {}

    private static final RecordType<Box> BOX = ApiTypes.record("ScreenBox",
            ApiField.field("x", GUI).required().forGetter(Box::x),
            ApiField.field("y", GUI).required().forGetter(Box::y),
            ApiField.field("width", GUI).required().forGetter(Box::width),
            ApiField.field("height", GUI).required().forGetter(Box::height),
            Box::new);

    private static final RecordType<SlotItem> SLOT_ITEM = ApiTypes.record("SlotItem",
            ApiField.field("id", CommonTypes.RESOURCE_ID).required().forGetter(SlotItem::id),
            ApiField.field("count", COUNT).required().forGetter(SlotItem::count),
            SlotItem::new);

    private static final RecordType<WidgetNode> WIDGET_NODE = ApiTypes.record("WidgetNode",
            ApiField.field("id", COUNT).describe("Its place in nodes.").required().forGetter(WidgetNode::id),
            ApiField.field("parent", COUNT).describe("The id of the node holding it; absent at the top.")
                    .optional().forGetter(WidgetNode::parent),
            ApiField.field("depth", COUNT).describe("0 at the top.").required().forGetter(WidgetNode::depth),
            ApiField.field("type", ApiTypes.string())
                    .describe("The widget's class; ContainerSlots holds the menu's Slot nodes.")
                    .required().forGetter(WidgetNode::type),
            ApiField.field("index", COUNT).describe("A list row's place, or a Slot's menu index for input.slotClick.")
                    .optional().forGetter(WidgetNode::index),
            ApiField.field("box", BOX).describe("Where it is, in the GUI coordinates input.click takes.")
                    .optional().forGetter(WidgetNode::box),
            ApiField.field("visible", ApiTypes.bool()).optional().forGetter(WidgetNode::visible),
            ApiField.field("active", ApiTypes.bool()).optional().forGetter(WidgetNode::active),
            ApiField.field("focused", ApiTypes.bool()).optional().forGetter(WidgetNode::focused),
            ApiField.field("selected", ApiTypes.bool())
                    .describe("The row a selection list reports chosen. A widget that paints its own selection says nothing here.")
                    .optional().forGetter(WidgetNode::selected),
            ApiField.field("message", ApiTypes.string()).describe("The label, in the player's language.")
                    .optional().forGetter(WidgetNode::message),
            ApiField.field("key", ApiTypes.string()).describe("The label's translation key, the same in every language.")
                    .optional().forGetter(WidgetNode::key),
            ApiField.field("value", ApiTypes.string()).describe("A text box's contents.").optional().forGetter(WidgetNode::value),
            ApiField.field("item", SLOT_ITEM).describe("A Slot's stack; absent when empty.").optional().forGetter(WidgetNode::item),
            ApiField.field("error", ApiTypes.string()).describe("This node threw while being read; the rest of the tree stands.")
                    .optional().forGetter(WidgetNode::error),
            WidgetNode::new);

    public static final RecordType<ScreenTree> SCREEN_TREE = ApiTypes.record("ScreenTree",
            ApiField.field("contextId", ClientContracts.CONTEXT_ID).required().forGetter(ScreenTree::contextId),
            ApiField.field("hasScreen", ApiTypes.bool()).required().forGetter(ScreenTree::hasScreen),
            ApiField.field("type", ApiTypes.string()).optional().forGetter(ScreenTree::type),
            ApiField.field("title", ApiTypes.string()).optional().forGetter(ScreenTree::title),
            ApiField.field("width", GUI).optional().forGetter(ScreenTree::width),
            ApiField.field("height", GUI).optional().forGetter(ScreenTree::height),
            ApiField.field("causeOfDeath", ApiTypes.string()).optional().forGetter(ScreenTree::causeOfDeath),
            ApiField.field("error", ApiTypes.string()).describe("The widget walk failed as a whole.")
                    .optional().forGetter(ScreenTree::error),
            ApiField.field("slotsError", ApiTypes.string()).describe("The menu's slots could not be listed.")
                    .optional().forGetter(ScreenTree::slotsError),
            ApiField.field("nodes", ApiTypes.list(WIDGET_NODE, 65_536))
                    .describe("Every node, depth first: a node comes right before the nodes it holds.")
                    .required().forGetter(ScreenTree::nodes),
            ScreenTree::new);

    public static final MethodSpec<NoParams, ScreenTree> TREE = MethodSpec.of("mc.client.screen.tree", ClientContracts.NO_PARAMS,
                    SCREEN_TREE,
                    "Every widget, list row and menu slot on the open screen, with its box in GUI coordinates: what "
                            + "to pick a click target from without a screenshot, and right for modded, self-drawn "
                            + "screens too. The tree is flattened into nodes, each naming its parent. A container "
                            + "screen adds a ContainerSlots node whose Slot nodes carry the menu index and stack. "
                            + "STRUCTURE, NOT PAINT: a widget that draws its own selection reads the same before and "
                            + "after the click that chose it; read that with a screenshot.")
            .asPollable();

    // ------------------------------------------------------------------ screen.close

    public record CloseParams(Optional<String> contextId) {}

    /** {@code closed}: the screen that was open, absent when none was. {@code contextId}: the state after. */
    public record Closed(Optional<String> closed, String contextId) {}

    private static <O> ApiField<O, Optional<String>> contextParam(java.util.function.Function<O, Optional<String>> getter) {
        return ApiField.field("contextId", ClientContracts.CONTEXT_ID).optional().forGetter(getter);
    }

    private static <O> ApiField<O, String> contextAfter(java.util.function.Function<O, String> getter) {
        return ApiField.field("contextId", ClientContracts.CONTEXT_ID).describe("The screen context after this call.")
                .required().forGetter(getter);
    }

    public static final MethodSpec<CloseParams, Closed> CLOSE = MethodSpec.of("mc.client.screen.close",
                    ApiTypes.record("ScreenCloseParams", contextParam(CloseParams::contextId), CloseParams::new),
                    ApiTypes.record("ScreenClosed",
                            ApiField.field("closed", ApiTypes.string()).describe("The screen it closed; absent when none was open.")
                                    .optional().forGetter(Closed::closed),
                            contextAfter(Closed::contextId),
                            Closed::new),
                    "Close the open screen, as setScreen(null) does. Succeeds with nothing open too.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.click / mouseMove

    public record ClickParams(double x, double y, int button, Optional<String> contextId) {}

    /** {@code handled}: a widget took the click; false means empty space. */
    public record Clicked(boolean handled, String contextId) implements ClientReply {}

    public static final MethodSpec<ClickParams, ClientReply> CLICK = MethodSpec.of("mc.client.input.click",
                    ApiTypes.record("ClickParams",
                            ApiField.field("x", ApiTypes.number()).describe("GUI coordinates, as screen.tree gives them.")
                                    .required().forGetter(ClickParams::x),
                            ApiField.field("y", ApiTypes.number()).required().forGetter(ClickParams::y),
                            ApiField.field("button", ApiTypes.integer(0, 2)).describe("0 left, 1 right, 2 middle.")
                                    .defaulted(0).forGetter(ClickParams::button),
                            contextParam(ClickParams::contextId),
                            ClickParams::new),
                    ClientContracts.reply("Click", Clicked.class, ApiTypes.record(null,
                            ApiField.field("handled", ApiTypes.bool()).describe("A widget took it; false is empty space.")
                                    .required().forGetter(Clicked::handled),
                            contextAfter(Clicked::contextId),
                            Clicked::new)),
                    "Click the open screen at GUI coordinates: hover there, press, release. Refused as no_screen "
                            + "when none is open.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    public record MoveParams(double x, double y) {}

    /** {@code wx}/{@code wy}: the window pixel the cursor went to, GUI coordinates times {@code scale}. */
    public record Moved(double wx, double wy, double scale) {}

    public static final MethodSpec<MoveParams, Moved> MOUSE_MOVE = MethodSpec.of("mc.client.input.mouseMove",
                    ApiTypes.record("MouseMoveParams",
                            ApiField.field("x", ApiTypes.number()).required().forGetter(MoveParams::x),
                            ApiField.field("y", ApiTypes.number()).required().forGetter(MoveParams::y),
                            MoveParams::new),
                    ApiTypes.record("MouseMoved",
                            ApiField.field("wx", ApiTypes.number()).required().forGetter(Moved::wx),
                            ApiField.field("wy", ApiTypes.number()).required().forGetter(Moved::wy),
                            ApiField.field("scale", ApiTypes.number()).describe("The GUI scale.").required().forGetter(Moved::scale),
                            Moved::new),
                    "Move the cursor to GUI coordinates without clicking, hover effects included: park it before a "
                            + "screenshot so no tooltip covers the screen.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.slotClick

    /** Vanilla's ClickType under the names a caller uses. */
    public enum ClickKind implements WireName {
        PICKUP("pickup"), QUICK_MOVE("quickMove"), SWAP("swap"), CLONE("clone"), THROW("throw"),
        PICKUP_ALL("pickupAll"), QUICK_CRAFT("quickCraft");

        private final String wire;
        ClickKind(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public record SlotClickParams(int slot, int button, ClickKind type, Optional<String> contextId) {}

    public record SlotClicked(int slot, int button, ClickKind type, String contextId) implements ClientReply {}

    private static final ApiType<ClickKind> CLICK_KIND = ApiTypes.enumOf("ClickKind", ClickKind.class);

    public static final MethodSpec<SlotClickParams, ClientReply> SLOT_CLICK = MethodSpec.of("mc.client.input.slotClick",
                    ApiTypes.record("SlotClickParams",
                            ApiField.field("slot", COUNT).describe("The menu's slot index, from observe.container or screen.tree.")
                                    .required().forGetter(SlotClickParams::slot),
                            ApiField.field("button", ApiTypes.integer(0, 8))
                                    .describe("0 left, 1 right, 2 middle; for swap, the hotbar slot 0-8 to swap with.")
                                    .defaulted(0).forGetter(SlotClickParams::button),
                            ApiField.field("type", CLICK_KIND).defaulted(ClickKind.PICKUP).forGetter(SlotClickParams::type),
                            contextParam(SlotClickParams::contextId),
                            SlotClickParams::new),
                    ClientContracts.reply("SlotClick", SlotClicked.class, ApiTypes.record(null,
                            ApiField.field("slot", COUNT).required().forGetter(SlotClicked::slot),
                            ApiField.field("button", ApiTypes.integer(0, 8)).required().forGetter(SlotClicked::button),
                            ApiField.field("type", CLICK_KIND).required().forGetter(SlotClicked::type),
                            contextAfter(SlotClicked::contextId),
                            SlotClicked::new)),
                    "Click a slot of the open container menu with an explicit click type, sent to the server as a "
                            + "real container click: pickup (button 0 takes or places the stack, 1 half or one), "
                            + "quickMove (shift-click), throw (button 0 drops one, 1 the stack), swap (with hotbar slot "
                            + "button), clone (creative middle-click), pickupAll (double-click), quickCraft (drag). "
                            + "Refused as no_container, no_player, or no_target for a slot past the menu's end.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ input.setHotbarSlot

    public record HotbarParams(int slot, Optional<String> contextId) {}

    public record HotbarSelected(int slot, int previous, String contextId) implements ClientReply {}

    public static final MethodSpec<HotbarParams, ClientReply> SET_HOTBAR_SLOT = MethodSpec.of("mc.client.input.setHotbarSlot",
                    ApiTypes.record("SetHotbarSlotParams",
                            ApiField.field("slot", ApiTypes.integer(0, 8)).describe("0 is leftmost.").required()
                                    .forGetter(HotbarParams::slot),
                            contextParam(HotbarParams::contextId),
                            HotbarParams::new),
                    ClientContracts.reply("SetHotbarSlot", HotbarSelected.class, ApiTypes.record(null,
                            ApiField.field("slot", ApiTypes.integer(0, 8)).required().forGetter(HotbarSelected::slot),
                            ApiField.field("previous", ApiTypes.integer(0, 8)).required().forGetter(HotbarSelected::previous),
                            contextAfter(HotbarSelected::contextId),
                            HotbarSelected::new)),
                    "Select the held hotbar slot and tell the server, so the next attack or use takes that item. "
                            + "mc.client.player's inventory says which slot holds what. Refused as no_player.")
            .effects(MethodSpec.Effect.CLIENT_INPUT, MethodSpec.Effect.INVENTORY_WRITE);

    // ------------------------------------------------------------------ input.typeText / replaceText

    public record TypeParams(String text, Optional<String> contextId) {}

    /** {@code typed}: code points delivered; {@code length}: UTF-16 units in the text. */
    public record Typed(int typed, int length, String contextId) implements ClientReply {}

    public static final MethodSpec<TypeParams, ClientReply> TYPE_TEXT = MethodSpec.of("mc.client.input.typeText",
                    ApiTypes.record("TypeTextParams",
                            ApiField.field("text", ApiTypes.string(0, 4096)).required().forGetter(TypeParams::text),
                            contextParam(TypeParams::contextId),
                            TypeParams::new),
                    ClientContracts.reply("TypeText", Typed.class, ApiTypes.record(null,
                            ApiField.field("typed", COUNT).required().forGetter(Typed::typed),
                            ApiField.field("length", COUNT).required().forGetter(Typed::length),
                            contextAfter(Typed::contextId),
                            Typed::new)),
                    "Type text into the focused widget of the open screen, one character event per code point, "
                            + "appending at the cursor. To overwrite a field use input.replaceText. Refused as no_screen.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    public record ReplaceParams(String text, Optional<String> match, Optional<String> contextId) {}

    public record Replaced(String value, String previous, String contextId) implements ClientReply {}

    public static final MethodSpec<ReplaceParams, ClientReply> REPLACE_TEXT = MethodSpec.of("mc.client.input.replaceText",
                    ApiTypes.record("ReplaceTextParams",
                            ApiField.field("text", ApiTypes.string(0, 4096)).describe("The box's new whole contents.")
                                    .required().forGetter(ReplaceParams::text),
                            ApiField.field("match", ApiTypes.string(1, 256))
                                    .describe("Case-insensitive part of the box's value or label; needed only when several "
                                            + "boxes are open and none has focus.")
                                    .optional().forGetter(ReplaceParams::match),
                            contextParam(ReplaceParams::contextId),
                            ReplaceParams::new),
                    ClientContracts.reply("ReplaceText", Replaced.class, ApiTypes.record(null,
                            ApiField.field("value", ApiTypes.string()).required().forGetter(Replaced::value),
                            ApiField.field("previous", ApiTypes.string()).required().forGetter(Replaced::previous),
                            contextAfter(Replaced::contextId),
                            Replaced::new)),
                    "Overwrite a text box's whole contents at once: the box match names, else the focused one, else "
                            + "the only one. Refused as no_screen, no_target (nothing matches) or ambiguous (several "
                            + "boxes, none focused, no match).")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.slider

    public record SliderParams(Optional<String> match, Optional<Integer> index, Optional<Double> fraction,
                               Optional<String> contextId) {}

    public record Slider(int index, String label, double value) {}

    public record SlidersRead(List<Slider> sliders, String contextId) implements ClientReply {}

    public record SliderSet(String label, double value, String previousLabel, double previousValue, String contextId)
            implements ClientReply {}

    @SuppressWarnings("unchecked")
    public static final MethodSpec<SliderParams, ClientReply> SLIDER = MethodSpec.of("mc.client.input.slider",
                    ApiTypes.record("SliderParams",
                            ApiField.field("match", ApiTypes.string(1, 256)).describe("Case-insensitive part of the slider's label.")
                                    .optional().forGetter(SliderParams::match),
                            ApiField.field("index", COUNT).describe("Its place among the screen's sliders, without match.")
                                    .optional().forGetter(SliderParams::index),
                            ApiField.field("fraction", ApiTypes.number(0, 1)).describe("The value to set. Omitted: read every slider.")
                                    .optional().forGetter(SliderParams::fraction),
                            contextParam(SliderParams::contextId),
                            SliderParams::new)
                            .checked((p, at) -> {
                                if (p.match().isPresent() && p.index().isPresent()) {
                                    at.field("index").fail(ApiIssue.Code.INVALID_VALUE, "pick a slider by match or by index, not both");
                                }
                            }),
                    ApiTypes.taggedUnion("SliderResponse", "outcome",
                            ApiTypes.<ClientReply, SlidersRead>variant("read", SlidersRead.class, ApiTypes.record(null,
                                    ApiField.field("sliders", ApiTypes.list(ApiTypes.record("SliderValue",
                                            ApiField.field("index", COUNT).required().forGetter(Slider::index),
                                            ApiField.field("label", ApiTypes.string()).required().forGetter(Slider::label),
                                            ApiField.field("value", ApiTypes.number(0, 1)).required().forGetter(Slider::value),
                                            Slider::new), 1024)).required().forGetter(SlidersRead::sliders),
                                    contextAfter(SlidersRead::contextId),
                                    SlidersRead::new)),
                            ApiTypes.<ClientReply, SliderSet>variant("set", SliderSet.class, ApiTypes.record(null,
                                    ApiField.field("label", ApiTypes.string()).required().forGetter(SliderSet::label),
                                    ApiField.field("value", ApiTypes.number(0, 1)).required().forGetter(SliderSet::value),
                                    ApiField.field("previousLabel", ApiTypes.string()).required().forGetter(SliderSet::previousLabel),
                                    ApiField.field("previousValue", ApiTypes.number(0, 1)).required().forGetter(SliderSet::previousValue),
                                    contextAfter(SliderSet::contextId),
                                    SliderSet::new)),
                            ClientContracts.refusedVariant()),
                    "Read or set a GUI slider: render distance, FOV, volume. Without fraction it reads every slider's "
                            + "label and 0-1 value, which only the shown slider can tell. With fraction it sets the "
                            + "slider match names, else index, else the only one, and commits it as dragging would. "
                            + "Refused as no_screen, no_target or ambiguous.")
            .effects(MethodSpec.Effect.CLIENT_INPUT);

    // ------------------------------------------------------------------ input.key / keybind

    public enum KeyAction implements WireName {
        PRESS, RELEASE,
        /** Press now, release on the next client tick. */
        CLICK;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum KeyRoute implements WireName {
        /** The open screen, else the key bindings: where a real keystroke goes. */
        AUTO,
        /** The key bindings, even under an open screen. */
        KEYBIND,
        /** The open screen only. */
        SCREEN;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public enum Modifier implements WireName {
        SHIFT("shift"), CTRL("ctrl"), ALT("alt"), SUPER("super"), CAPS_LOCK("capsLock"), NUM_LOCK("numLock");

        private final String wire;
        Modifier(String wire) { this.wire = wire; }
        @Override public String wireName() { return wire; }
    }

    public enum KeyVia implements WireName {
        SCREEN, KEYBIND;
        @Override public String wireName() { return name().toLowerCase(Locale.ROOT); }
    }

    public record KeyParams(String key, KeyAction action, KeyRoute route, List<Modifier> modifiers, Optional<String> contextId) {}

    /**
     * {@code pressed}/{@code released}: that half went out. {@code pressHandled}/{@code releaseHandled}: on the
     * screen route only, the screen consumed it. {@code screenAfter}: the screen the keystroke left open.
     */
    public record KeySent(String key, int code, KeyAction action, KeyRoute route, List<Modifier> modifiers, KeyVia via,
                          boolean pressed, boolean released, Optional<Boolean> pressHandled,
                          Optional<Boolean> releaseHandled, Optional<KeyVia> releaseVia, String screenAfter,
                          Optional<String> releaseNote, String contextId) implements ClientReply {}

    private static final ApiType<KeyAction> KEY_ACTION = ApiTypes.enumOf("KeyAction", KeyAction.class);
    private static final ApiType<KeyRoute> KEY_ROUTE = ApiTypes.enumOf("KeyRoute", KeyRoute.class);
    private static final ApiType<List<Modifier>> MODIFIERS = ApiTypes.list(ApiTypes.enumOf("KeyModifier", Modifier.class), 6);
    private static final ApiType<KeyVia> KEY_VIA = ApiTypes.enumOf("KeyVia", KeyVia.class);

    public static final MethodSpec<KeyParams, ClientReply> KEY = MethodSpec.of("mc.client.input.key",
                    ApiTypes.record("KeyParams",
                            ApiField.field("key", ApiTypes.pattern("[A-Za-z0-9_]{1,16}", "a key name such as ENTER"))
                                    .describe("ENTER, ESCAPE, TAB, BACKSPACE, DELETE, SPACE, LEFT, RIGHT, UP, DOWN, HOME, "
                                            + "END, PAGEUP, PAGEDOWN, F1-F25, A-Z, 0-9, or a modifier key (LEFT_SHIFT, "
                                            + "ALT, …). Any case.")
                                    .required().forGetter(KeyParams::key),
                            ApiField.field("action", KEY_ACTION).defaulted(KeyAction.CLICK).forGetter(KeyParams::action),
                            ApiField.field("route", KEY_ROUTE).defaulted(KeyRoute.AUTO).forGetter(KeyParams::route),
                            ApiField.field("modifiers", MODIFIERS)
                                    .describe("Modifier bits for a SCREEN shortcut. A modified key BINDING needs input.keybind.")
                                    .defaulted(List.of()).forGetter(KeyParams::modifiers),
                            contextParam(KeyParams::contextId),
                            KeyParams::new),
                    ClientContracts.reply("Key", KeySent.class, ApiTypes.record(null,
                            ApiField.field("key", ApiTypes.string()).required().forGetter(KeySent::key),
                            ApiField.field("code", ApiTypes.integer()).describe("The GLFW key code.").required().forGetter(KeySent::code),
                            ApiField.field("action", KEY_ACTION).required().forGetter(KeySent::action),
                            ApiField.field("route", KEY_ROUTE).required().forGetter(KeySent::route),
                            ApiField.field("modifiers", MODIFIERS).required().forGetter(KeySent::modifiers),
                            ApiField.field("via", KEY_VIA).required().forGetter(KeySent::via),
                            ApiField.field("pressed", ApiTypes.bool()).describe("The press went out.").required().forGetter(KeySent::pressed),
                            ApiField.field("released", ApiTypes.bool()).describe("The release went out.").required().forGetter(KeySent::released),
                            ApiField.field("pressHandled", ApiTypes.bool()).describe("Screen route: the screen consumed the press.")
                                    .optional().forGetter(KeySent::pressHandled),
                            ApiField.field("releaseHandled", ApiTypes.bool())
                                    .describe("Screen route: the screen consumed the release. Screens rarely do; false is no failed release.")
                                    .optional().forGetter(KeySent::releaseHandled),
                            ApiField.field("releaseVia", KEY_VIA).describe("Where the release went, when the press changed the screen.")
                                    .optional().forGetter(KeySent::releaseVia),
                            ApiField.field("screenAfter", ApiTypes.string())
                                    .describe("The screen this keystroke left open, or none; it receives the NEXT key.")
                                    .required().forGetter(KeySent::screenAfter),
                            ApiField.field("releaseNote", ApiTypes.string())
                                    .describe("The client did not tick in time: the key is still down; send release.")
                                    .optional().forGetter(KeySent::releaseNote),
                            contextAfter(KeySent::contextId),
                            KeySent::new)),
                    "Send a keyboard event. click (the default) presses now and releases on the NEXT client tick, "
                            + "routed again from whatever the press left open. route auto sends it where vanilla "
                            + "would: the open screen, else the key bindings (E inventory, ESCAPE pause, T chat); "
                            + "keybind reaches the bindings under an open screen; screen only the screen. For walking "
                            + "use mc.bot.*. Refused as no_screen (route screen with none open) or no_player (no "
                            + "screen and no player to take it). An unknown key name is invalid_params.")
            .effects(MethodSpec.Effect.CLIENT_INPUT)
            .mayWait();

    public record KeybindParams(Optional<String> name, KeyAction action, Optional<String> contextId) {}

    /** One key mapping; {@code key} is the bound key's id and {@code boundTo} what the controls screen shows. */
    public record Keybinding(String name, String title, String key, String boundTo, boolean bound, String category,
                             boolean down) {}

    public record KeybindsListed(List<Keybinding> keybinds) implements ClientReply {}

    /**
     * {@code clicks}: clicks pending on the mapping after the press. {@code rawEvent}: what became of the raw
     * key event sent alongside. {@code down}: the mapping as the press left it.
     */
    public record KeybindDriven(String name, String title, String key, String boundTo, String category, boolean down,
                                KeyAction action, int clicks, boolean released, String rawEvent, boolean windowActive,
                                boolean mouseGrabbed, String screenAfter, Optional<String> releaseNote, String contextId)
            implements ClientReply {}

    public static final MethodSpec<KeybindParams, ClientReply> KEYBIND = MethodSpec.of("mc.client.input.keybind",
                    ApiTypes.record("KeybindParams",
                            ApiField.field("name", ApiTypes.string(1, 256))
                                    .describe("A mapping id (key.inventory) or part of an id or title. Omitted: list every mapping.")
                                    .optional().forGetter(KeybindParams::name),
                            ApiField.field("action", KEY_ACTION).defaulted(KeyAction.CLICK).forGetter(KeybindParams::action),
                            contextParam(KeybindParams::contextId),
                            KeybindParams::new),
                    ApiTypes.taggedUnion("KeybindResponse", "outcome",
                            ApiTypes.<ClientReply, KeybindsListed>variant("listed", KeybindsListed.class, ApiTypes.record(null,
                                    ApiField.field("keybinds", ApiTypes.list(ApiTypes.record("Keybinding",
                                            ApiField.field("name", ApiTypes.string()).required().forGetter(Keybinding::name),
                                            ApiField.field("title", ApiTypes.string()).required().forGetter(Keybinding::title),
                                            ApiField.field("key", ApiTypes.string()).required().forGetter(Keybinding::key),
                                            ApiField.field("boundTo", ApiTypes.string()).required().forGetter(Keybinding::boundTo),
                                            ApiField.field("bound", ApiTypes.bool()).required().forGetter(Keybinding::bound),
                                            ApiField.field("category", ApiTypes.string()).required().forGetter(Keybinding::category),
                                            ApiField.field("down", ApiTypes.bool()).required().forGetter(Keybinding::down),
                                            Keybinding::new), 4096)).required().forGetter(KeybindsListed::keybinds),
                                    KeybindsListed::new)),
                            ApiTypes.<ClientReply, KeybindDriven>variant("done", KeybindDriven.class, ApiTypes.record(null,
                                    ApiField.field("name", ApiTypes.string()).required().forGetter(KeybindDriven::name),
                                    ApiField.field("title", ApiTypes.string()).required().forGetter(KeybindDriven::title),
                                    ApiField.field("key", ApiTypes.string()).required().forGetter(KeybindDriven::key),
                                    ApiField.field("boundTo", ApiTypes.string()).required().forGetter(KeybindDriven::boundTo),
                                    ApiField.field("category", ApiTypes.string()).required().forGetter(KeybindDriven::category),
                                    ApiField.field("down", ApiTypes.bool()).describe("The mapping as the press left it.")
                                            .required().forGetter(KeybindDriven::down),
                                    ApiField.field("action", KEY_ACTION).required().forGetter(KeybindDriven::action),
                                    ApiField.field("clicks", COUNT).describe("Clicks pending on the mapping after the press.")
                                            .required().forGetter(KeybindDriven::clicks),
                                    ApiField.field("released", ApiTypes.bool()).required().forGetter(KeybindDriven::released),
                                    ApiField.field("rawEvent", ApiTypes.string())
                                            .describe("What became of the raw key event sent alongside: sent, or skipped and why.")
                                            .required().forGetter(KeybindDriven::rawEvent),
                                    ApiField.field("windowActive", ApiTypes.bool()).required().forGetter(KeybindDriven::windowActive),
                                    ApiField.field("mouseGrabbed", ApiTypes.bool()).required().forGetter(KeybindDriven::mouseGrabbed),
                                    ApiField.field("screenAfter", ApiTypes.string()).required().forGetter(KeybindDriven::screenAfter),
                                    ApiField.field("releaseNote", ApiTypes.string()).optional().forGetter(KeybindDriven::releaseNote),
                                    contextAfter(KeybindDriven::contextId),
                                    KeybindDriven::new)),
                            ClientContracts.refusedVariant()),
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

    public record ContainerParams(Optional<BlockPosition> pos) {}

    /** An empty slot carries its index alone. */
    public record SlotRow(int index, Optional<String> id, Optional<Integer> count, ClientContracts.Wear wear) {
        public static SlotRow empty(int index) {
            return new SlotRow(index, Optional.empty(), Optional.empty(), ClientContracts.Wear.NONE);
        }
    }

    public sealed interface ContainerView permits BlockContainer, MenuContainer {}

    /** {@code present}: a block entity is there; {@code slots}: when it holds items. */
    public record BlockContainer(BlockPosition pos, boolean present, Optional<String> blockEntity,
                                 Optional<List<SlotRow>> slots) implements ContainerView {}

    /** {@code present}: the open screen has a container menu. */
    public record MenuContainer(String contextId, boolean present, Optional<String> screen,
                                Optional<List<SlotRow>> slots) implements ContainerView {}

    private static final ApiType<List<SlotRow>> SLOTS = ApiTypes.list(ApiTypes.record("ContainerSlot",
            ApiField.field("index", COUNT).describe("Vanilla's slot index; furnace 0 input, 1 fuel, 2 output.")
                    .required().forGetter(SlotRow::index),
            ApiField.field("id", CommonTypes.RESOURCE_ID).describe("Absent for an empty slot.").optional().forGetter(SlotRow::id),
            ApiField.field("count", COUNT).optional().forGetter(SlotRow::count),
            ApiField.field("maxDamage", COUNT).optional().forGetter(s -> s.wear().maxDamage()),
            ApiField.field("damage", COUNT).optional().forGetter(s -> s.wear().damage()),
            ApiField.field("durability", COUNT).describe("Points left; damageable items only.")
                    .optional().forGetter(s -> s.wear().durability()),
            (i, id, count, max, dmg, dur) -> new SlotRow(i, id, count, new ClientContracts.Wear(max, dmg, dur))), 4096);

    public static final UnionType<ContainerView> CONTAINER_VIEW = ApiTypes.taggedUnion("ContainerView", "source",
            ApiTypes.variant("block", BlockContainer.class, ApiTypes.record(null,
                    ApiField.field("pos", CommonTypes.BLOCK_POSITION).required().forGetter(BlockContainer::pos),
                    ApiField.field("present", ApiTypes.bool()).required().forGetter(BlockContainer::present),
                    ApiField.field("blockEntity", CommonTypes.RESOURCE_ID).describe("The block entity's type.")
                            .optional().forGetter(BlockContainer::blockEntity),
                    ApiField.field("slots", SLOTS).optional().forGetter(BlockContainer::slots),
                    BlockContainer::new)),
            ApiTypes.variant("menu", MenuContainer.class, ApiTypes.record(null,
                    ApiField.field("contextId", ClientContracts.CONTEXT_ID)
                            .describe("The screen these slots belong to; input.slotClick can be held to it.")
                            .required().forGetter(MenuContainer::contextId),
                    ApiField.field("present", ApiTypes.bool()).required().forGetter(MenuContainer::present),
                    ApiField.field("screen", ApiTypes.string()).optional().forGetter(MenuContainer::screen),
                    ApiField.field("slots", SLOTS).optional().forGetter(MenuContainer::slots),
                    MenuContainer::new)));

    public static final MethodSpec<ContainerParams, ContainerView> CONTAINER = MethodSpec.of("mc.observe.container",
                    ApiTypes.record("ContainerParams",
                            ApiField.field("pos", CommonTypes.BLOCK_POSITION)
                                    .describe("A chest, barrel, hopper, furnace… Omitted: the menu open on this client.")
                                    .optional().forGetter(ContainerParams::pos),
                            ContainerParams::new),
                    CONTAINER_VIEW,
                    "Read a container's slots. With pos, the block entity there, read on the server (source block). "
                            + "Without, whatever container menu is open on this client: the inventory, a crafting "
                            + "table, a chest the server opened (source menu), with the contextId a slotClick can be "
                            + "held to. Without pos and without a client it is capability_unavailable.")
            .asPollable();
}
