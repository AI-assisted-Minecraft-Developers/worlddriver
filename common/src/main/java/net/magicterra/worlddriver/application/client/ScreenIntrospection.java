package net.magicterra.worlddriver.application.client;

import com.google.common.collect.MapMaker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Box;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenInfo;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotItem;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.WidgetNode;
import net.magicterra.worlddriver.game.ClientHop;
import net.magicterra.worlddriver.game.ui.DeathCause;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Screen / widget-tree introspection for {@code mc.client.screen.*}, and the refs that let a click name a node of a
 * tree it read earlier.
 */
public final class ScreenIntrospection {

    /**
     * Each widget, list row and slot object's ref, handed out the first time a walk meets it. A node id is only a place
     * in one read's list and shifts as soon as a widget is added above it; the ref is bound to the object, so a click
     * by ref lands on the widget the caller saw or is refused, never on its neighbour. Weak and by identity: a closed
     * screen's widgets are not kept, and a widget's own equals plays no part. Client thread only, like the walk.
     */
    private static final Map<Object, Integer> REFS = new MapMaker().weakKeys().makeMap();

    private static int nextRef = 1;

    private ScreenIntrospection() {}

    private static int refOf(Object o) {
        return REFS.computeIfAbsent(o, k -> nextRef++);
    }

    /** Where a click on a ref lands: the centre of its box as it is now, or why it cannot be clicked. */
    record Aim(double x, double y, Optional<String> refusal) {}

    /**
     * Aims at {@code ref} on the open screen. Refused when the ref is not on it any more, is hidden, has no box, or its
     * centre lies outside the screen or outside the box of anything holding it — a list row scrolled out of its list,
     * where a click would land on whatever is drawn there instead. Client thread.
     */
    static Aim aim(int ref) {
        ScreenTree tree = screenTree();
        Map<Integer, WidgetNode> byRef = new HashMap<>();
        for (WidgetNode n : tree.nodes()) {
            byRef.put(n.ref(), n);
        }
        WidgetNode hit = byRef.get(ref);
        if (hit == null) {
            return refused(refName(ref) + " is not on the open screen: it closed, rebuilt its widgets, or the ref came "
                    + "from another screen. Read screen.tree again");
        }
        if (Boolean.FALSE.equals(hit.visible().orElse(true))) {
            return refused(refName(ref) + " is hidden");
        }
        if (hit.box().isEmpty()) {
            return refused(refName(ref) + " (" + hit.type() + ") has no box to click");
        }
        Box b = hit.box().get();
        double x = b.x() + b.width() / 2.0;
        double y = b.y() + b.height() / 2.0;
        if (x < 0 || y < 0 || x >= tree.width().orElse(0) || y >= tree.height().orElse(0)) {
            return refused(refName(ref) + " lies outside the screen, at " + x + "," + y);
        }
        for (WidgetNode holder = byRef.get(hit.parent().orElse(-1));
                holder != null;
                holder = byRef.get(holder.parent().orElse(-1))) {
            if (Boolean.FALSE.equals(holder.visible().orElse(true))) {
                return refused(
                        refName(ref) + " sits in " + holder.type() + " (ref " + holder.ref() + "), which is hidden");
            }
            if (holder.box().isPresent() && !inside(holder.box().get(), x, y)) {
                return refused(refName(ref) + " lies outside the visible part of " + holder.type() + " (ref "
                        + holder.ref() + "), scrolled out of view");
            }
        }
        return new Aim(x, y, Optional.empty());
    }

    private static Aim refused(String why) {
        return new Aim(0, 0, Optional.of(why));
    }

    /** How a refusal names the widget it was given. */
    private static String refName(int ref) {
        return "ref " + ref;
    }

    /** The live object a ref names on the open screen, or why there is none. */
    record Target(GuiEventListener widget, Optional<String> refusal) {}

    /**
     * The widget or list row {@code ref} names, found by walking the open screen through the same children the tree
     * walk does. Refused when it is not on the screen any more. Client thread.
     */
    static Target widget(int ref) {
        Screen s = Minecraft.getInstance().screen;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (s != null) {
            seen.add(s);
        }
        GuiEventListener hit = s == null ? null : find(s, ref, seen);
        if (hit == null) {
            return new Target(
                    null,
                    Optional.of(refName(ref) + " is not on the open screen: it closed, rebuilt its "
                            + "widgets, or the ref came from another screen. Read screen.tree again"));
        }
        return new Target(hit, Optional.empty());
    }

    private static GuiEventListener find(GuiEventListener node, int ref, Set<Object> seen) {
        if (!(node instanceof ContainerEventHandler ceh)) {
            return null;
        }
        for (GuiEventListener child : ceh.children()) {
            if (!seen.add(child)) {
                continue;
            }
            // get, not refOf: an object no walk has met has no ref yet, so it cannot be the one asked for.
            Integer r = REFS.get(child);
            if (r != null && r == ref) {
                return child;
            }
            try {
                GuiEventListener deeper = find(child, ref, seen);
                if (deeper != null) {
                    return deeper;
                }
            } catch (RuntimeException e) {
                // As in the tree walk: a third-party widget that throws costs its own subtree.
            }
        }
        return null;
    }

    private static boolean inside(Box b, double x, double y) {
        return x >= b.x() && y >= b.y() && x < b.x() + b.width() && y < b.y() + b.height();
    }

    /** What returnTree adds to a reply. */
    record After(Optional<Boolean> changed, Optional<ScreenTree> tree) {
        static final After NONE = new After(Optional.empty(), Optional.empty());
    }

    /** The {@code returnTree} reading just before the input, or null when it was not asked. Client thread. */
    static ScreenTree before(boolean returnTree) {
        return returnTree ? screenTree() : null;
    }

    /**
     * The {@code returnTree} answer: whether the tree now differs from {@code before}, and the tree when it does. The
     * whole tree is compared, contextId included, so a new screen, a rebuilt one and a toggled widget on the same one
     * all count. Client thread.
     */
    static After after(ScreenTree before) {
        if (before == null) {
            return After.NONE;
        }
        ScreenTree now = screenTree();
        boolean changed = !now.equals(before);
        return new After(Optional.of(changed), changed ? Optional.of(now) : Optional.empty());
    }

    /**
     * A cheap probe of the client: the open screen and any overlay on top of it, whether a world and player exist,
     * window focus and mouse grab, and a death screen's cause.
     */
    public static ScreenInfo screenInfo() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            // Overlay (LoadingOverlay during early boot, ResourceLoadProgressScreen
            // after F3+T, etc.) is rendered ON TOP of the active Screen — so
            // {@code screen.type=TitleScreen} can be true while the user still
            // sees a fading Mojang splash. Surface it so callers can wait.
            Overlay ov = mc.getOverlay();
            // windowActive/mouseGrabbed: the environment a mod's key handler usually demands before
            // it does anything. Yes Steve Model, measured, refuses every keystroke unless the window
            // is focused and the mouse is grabbed. On a shared desktop the window loses focus to
            // whatever the human is doing, so a driver whose keystrokes vanish needs to read this
            // before it reads anything else — it is the difference between "the mod refused it" and
            // "it never arrived", and no amount of retrying fixes the first.
            // causeOfDeath: on a DeathScreen the title is just "You Died!"; the actual cause is a
            // separate private Component, surfaced so a cheap probe reveals WHY the bot died.
            return new ScreenInfo(
                    ScreenContext.current(),
                    s != null,
                    mc.level != null,
                    mc.player != null,
                    ov != null,
                    Optional.ofNullable(ov).map(o -> o.getClass().getSimpleName()),
                    mc.isWindowActive(),
                    mc.mouseHandler.isMouseGrabbed(),
                    Optional.ofNullable(s).map(x -> x.getClass().getSimpleName()),
                    Optional.ofNullable(s).map(x -> x.getTitle().getString()),
                    Optional.ofNullable(s).map(x -> x.width),
                    Optional.ofNullable(s).map(x -> x.height),
                    s instanceof DeathScreen ds ? Optional.ofNullable(DeathCause.of(ds)) : Optional.empty());
        });
    }

    /**
     * The open screen's widget tree, each widget with the ref that input methods aim at; a widget that throws while
     * being read is reported on its own node rather than failing the whole answer.
     */
    public static ScreenTree screenTree() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            String context = ScreenContext.current();
            if (s == null) {
                return new ScreenTree(
                        context,
                        false,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        List.of());
            }
            Nodes nodes = new Nodes(s);
            Optional<String> error = Optional.empty();
            // A screen belongs to whatever mod drew it, and a widget that throws from getMessage
            // or getNarration used to take the whole answer with it — the caller got an empty
            // object and only screen.info still spoke. Each node is judged on its own now.
            try {
                walk(s, null, 0, nodes);
            } catch (Throwable t) {
                error = Optional.of(describeThrowable(t));
            }
            // Container screens (InventoryScreen, ChestScreen, FurnaceScreen, …)
            // expose their slots through the AbstractContainerMenu, not via the
            // GuiEventListener tree — so a plain walk omits every clickable slot.
            // Add one synthetic ContainerSlots node holding per-slot bbox,
            // vanilla slot index, and itemstack so agents can pick a slot by
            // label/index without resorting to pixel-counting.
            Optional<String> slotsError = Optional.empty();
            if (s instanceof AbstractContainerScreen<?> acs) {
                try {
                    containerSlots(acs, nodes);
                } catch (Throwable t) {
                    slotsError = Optional.of(describeThrowable(t));
                }
            }
            // DeathScreen's cause-of-death is rendered directly from a
            // private Component field, never a child widget — without this
            // surfacing, the agent has no client-side way to find out what
            // killed the player (server log is the only other source).
            Optional<String> cause =
                    s instanceof DeathScreen ds ? Optional.ofNullable(DeathCause.of(ds)) : Optional.empty();
            return new ScreenTree(
                    context,
                    true,
                    Optional.of(s.getClass().getSimpleName()),
                    Optional.of(s.getTitle().getString()),
                    Optional.of(s.width),
                    Optional.of(s.height),
                    cause,
                    error,
                    slotsError,
                    nodes.build());
        });
    }

    /**
     * The tree flattened depth first, a node before the nodes it holds. A node is filled in place while its children
     * are added after it, so each is a mutable row until {@link #build}.
     *
     * <p>Each object is listed once. A node is named by its object's ref, so an object a mod hangs under two holders
     * would otherwise be two nodes with one name, and its children's parent would mean either; the second sighting is
     * dropped instead. The same rule ends the walk of a holder that contains itself, or the screen, which would
     * otherwise recurse until the stack ran out.
     */
    private static final class Nodes {
        private final List<Node> all = new ArrayList<>();

        private final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        Nodes(Screen screen) {
            seen.add(screen);
        }

        /** A node for {@code target}, or null when this walk has listed it already. */
        Node add(Object target, Integer parent, int depth, String type) {
            if (!seen.add(target)) {
                return null;
            }
            Node n = new Node(refOf(target), parent, depth, type);
            all.add(n);
            return n;
        }

        List<WidgetNode> build() {
            List<WidgetNode> out = new ArrayList<>(all.size());
            for (Node n : all) {
                out.add(n.freeze());
            }
            return out;
        }
    }

    private static final class Node {
        final int ref;

        final Integer parent;

        final int depth;

        final String type;

        Integer index;

        Box box;

        Boolean visible;

        Boolean active;

        Boolean focused;

        Boolean selected;

        String message;

        String key;

        String value;

        String error;

        SlotItem item;

        Node(int ref, Integer parent, int depth, String type) {
            this.ref = ref;
            this.parent = parent;
            this.depth = depth;
            this.type = type;
        }

        WidgetNode freeze() {
            return new WidgetNode(
                    ref,
                    Optional.ofNullable(parent),
                    depth,
                    type,
                    Optional.ofNullable(index),
                    Optional.ofNullable(box),
                    Optional.ofNullable(visible),
                    Optional.ofNullable(active),
                    Optional.ofNullable(focused),
                    Optional.ofNullable(selected),
                    Optional.ofNullable(message),
                    Optional.ofNullable(key),
                    Optional.ofNullable(value),
                    Optional.ofNullable(item),
                    Optional.ofNullable(error));
        }
    }

    private static void walk(GuiEventListener node, Integer parent, int depth, Nodes out) {
        if (!(node instanceof ContainerEventHandler ceh)) {
            return;
        }
        for (GuiEventListener child : ceh.children()) {
            Node n = out.add(child, parent, depth, child.getClass().getSimpleName());
            if (n == null) {
                continue;
            }
            // Per child, so a third-party widget that throws costs its own subtree and no more.
            try {
                describe(child, n, out);
            } catch (Throwable t) {
                n.error = describeThrowable(t);
            }
        }
    }

    private static String describeThrowable(Throwable t) {
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }

    /** Fills one node's geometry and label, then adds its children after it. */
    private static void describe(GuiEventListener child, Node n, Nodes out) {
        if (child instanceof AbstractWidget w) {
            n.box = new Box(w.getX(), w.getY(), w.getWidth(), w.getHeight());
            n.visible = w.visible;
            n.active = w.active;
            n.focused = w.isFocused();
            n.message = w.getMessage().getString();
            // The message is in the player's language; the key lets a script pick a vanilla
            // button without carrying one label per locale.
            if (w.getMessage().getContents() instanceof TranslatableContents tc) {
                n.key = tc.getKey();
            }
        }
        // EditBox holds typed text. Without exposing the current value, agents
        // can drive the cursor and call typeText but never verify what was
        // entered (or what vanilla pre-filled, e.g. last-used server address).
        if (child instanceof EditBox eb) {
            n.value = eb.getValue();
            n.focused = eb.isFocused();
        }
        // AbstractSelectionList entries (SelectWorldScreen rows, ServerSelectionList,
        // RealmsList…) don't extend AbstractWidget, so a plain walk leaves them
        // with only a type name — agents can't pick a row by label. Project the
        // list's per-row geometry + display name onto each entry.
        if (child instanceof AbstractSelectionList<?> list) {
            listEntries(list, n, out);
            return;
        }
        walk(child, n.ref, n.depth + 1, out);
    }

    /**
     * Project per-row bbox + display label for AbstractSelectionList entries. {@code getRowTop}/{@code itemHeight} are
     * protected in vanilla and opened by worlddriver.accesswidener.
     */
    private static void listEntries(AbstractSelectionList<?> list, Node owner, Nodes out) {
        // Vanilla layout: row x-span comes from public getRowLeft/getRowWidth.
        // Row y is index-dependent and includes scroll + header offsets, all
        // baked into getRowTop(int).
        int rowLeft = list.getRowLeft();
        int rowWidth = list.getRowWidth();
        int itemHeight = list.itemHeight;
        // children() returns List<? extends Entry> where Entry is protected,
        // so a typed var hides the runtime class. Cast to List<?> and treat
        // each entry as Object — the concrete subclass (e.g. WorldListEntry)
        // is public, so getClass()/reflection on Object is fine.
        List<?> children = list.children();
        // Which row the list considers chosen. The only selection state vanilla exposes without
        // reflection, and the only one a caller can read at all: a widget that paints its own
        // selection — a mod's model cards, highlighted by a border — leaves this tree byte-identical
        // before and after the click that chose it, so selection there is a screenshot question.
        Object selected = list.getSelected();
        for (int i = 0; i < children.size(); i++) {
            Object entry = children.get(i);
            Node n = out.add(entry, owner.ref, owner.depth + 1, entry.getClass().getSimpleName());
            if (n == null) {
                continue;
            }
            n.index = i;
            n.box = new Box(rowLeft, list.getRowTop(i), rowWidth, itemHeight - 4);
            if (entry == selected) {
                n.selected = true;
            }
            n.message = entryLabel(entry);
            // Walk INTO the row. Every list row that holds widgets is a ContainerEventHandler
            // (ContainerObjectSelectionList.Entry, KeyBindsList's rows, the options rows), and
            // projecting only its bbox is why a key-binds screen came back as a stack of nameless
            // Entry nodes with no way to tell one binding's row from another's.
            if (entry instanceof GuiEventListener gel) {
                walk(gel, n.ref, n.depth + 1, out);
            }
        }
    }

    /**
     * Best-effort human label for an arbitrary list entry.
     *
     * <p>The name-based probes below are deliberate duck-typing: list entries have no common label interface, so we try
     * the getters vanilla happens to use. That works in dev and is what every gate exercises — but the getter NAMES are
     * Mojang-mapped string literals, so in the remapped fabric jar every probe misses and the whole screen comes back
     * with no labels at all.
     *
     * <p>The typed fallbacks run AFTER the probes, never before: the probes decide the label in dev exactly as they
     * always have (this method's dev output is unchanged by construction), and the fallbacks only get a turn in the
     * case where the probes found nothing — which is precisely the remapped case. They are ordinary virtual calls on
     * public API, so tiny-remapper rewrites them correctly.
     */
    @SuppressWarnings("PMD.AvoidAccessibilityAlteration") // a row's label sits in a private field with no getter
    private static String entryLabel(Object entry) {
        for (String getter : new String[] {"getLevelName", "getDisplayName", "getMessage", "getName"}) {
            try {
                var m = entry.getClass().getMethod(getter);
                Object v = m.invoke(entry);
                if (v == null) {
                    continue;
                }
                if (v instanceof Component c) {
                    return c.getString();
                }
                String s = v.toString();
                if (!s.isBlank()) {
                    return s;
                }
            } catch (ReflectiveOperationException ignored) {
                /* try next */
            }
        }
        // Probe a `getSummary().getLevelName()` chain (the WorldListEntry case).
        // Wrapped in try so unrelated entry classes simply fall through.
        try {
            var m = entry.getClass().getMethod("getSummary");
            Object summary = m.invoke(entry);
            if (summary != null) {
                var m2 = summary.getClass().getMethod("getLevelName");
                Object v = m2.invoke(summary);
                if (v != null) {
                    return v.toString();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            /* no summary */
        }
        // Typed fallbacks — the only branches that still work once the jar is remapped.
        if (entry instanceof AbstractWidget w) {
            String s = w.getMessage() == null ? "" : w.getMessage().getString();
            if (!s.isBlank()) {
                return s;
            }
        }
        if (entry instanceof ObjectSelectionList.Entry<?> ose) {
            // getNarration() is the accessibility label vanilla builds for every
            // selectable row — wordier than getLevelName(), but a real label beats
            // the null this method used to return.
            Component n = ose.getNarration();
            String s = n == null ? "" : n.getString();
            if (!s.isBlank()) {
                return s;
            }
        }
        // Last resort, and by TYPE rather than by name: a row that renders its text straight from a
        // private Component field has no getter to probe, and that field is the only thing saying
        // which row it is — a key-binds screen is a stack of such rows, one per binding. Most
        // derived class first, and never a static: those hold shared button titles ("Reset"), which
        // would label every row identically.
        try {
            for (Class<?> c = entry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    if (!Component.class.isAssignableFrom(f.getType())) {
                        continue;
                    }
                    f.setAccessible(true);
                    if (f.get(entry) instanceof Component comp) {
                        String s = comp.getString();
                        if (!s.isBlank()) {
                            return s;
                        }
                    }
                }
            }
        } catch (RuntimeException | ReflectiveOperationException ignored) {
            /* no label to be had */
        }
        return null;
    }

    /** Add the open AbstractContainerScreen's slots under one synthetic ContainerSlots node. */
    private static void containerSlots(AbstractContainerScreen<?> acs, Nodes out) {
        var menu = acs.getMenu();
        if (menu == null || menu.slots == null || menu.slots.isEmpty()) {
            return;
        }
        // Container screens use leftPos/topPos as the inner-GUI origin; slot.x/y
        // are GUI-local. We expose absolute Screen coords so agents can pass
        // them straight to mc.client.input.click without doing arithmetic.
        // Both protected in vanilla, opened by worlddriver.accesswidener.
        int leftPos = acs.leftPos;
        int topPos = acs.topPos;
        // The holder is named by the menu: it lives exactly as long as these slots do.
        Node holder = out.add(menu, null, 0, "ContainerSlots");
        if (holder == null) {
            return;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            Node n = out.add(slot, holder.ref, 1, "Slot");
            if (n == null) {
                continue;
            }
            n.index = i;
            // Per slot, for the same reason as the widget walk: a modded slot that throws from
            // isActive or getItem should cost its own row, not the screen's whole slot list.
            try {
                n.box = new Box(leftPos + slot.x, topPos + slot.y, 16, 16); // vanilla slot icon is 16×16
                n.active = slot.isActive();
                ItemStack stk = slot.getItem();
                if (!stk.isEmpty()) {
                    n.item = new SlotItem(
                            BuiltInRegistries.ITEM.getKey(stk.getItem()).toString(), stk.getCount());
                }
            } catch (Throwable t) {
                n.error = describeThrowable(t);
            }
        }
    }
}
