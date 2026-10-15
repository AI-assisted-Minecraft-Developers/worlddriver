package net.magicterra.worlddriver.game.ui;

import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Box;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenInfo;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.SlotItem;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.WidgetNode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.game.ClientHop;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.components.EditBox;

/**
 * Screen / widget-tree introspection for {@code mc.client.screen.*}. Stateless;
 * extracted from {@code ClientDriverApiImpl}.
 */
public final class ScreenIntrospection {
    private ScreenIntrospection() {}

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
            return new ScreenInfo(ScreenContext.current(), s != null, mc.level != null, mc.player != null,
                    ov != null, Optional.ofNullable(ov).map(o -> o.getClass().getSimpleName()),
                    mc.isWindowActive(), mc.mouseHandler.isMouseGrabbed(),
                    Optional.ofNullable(s).map(x -> x.getClass().getSimpleName()),
                    Optional.ofNullable(s).map(x -> x.getTitle().getString()),
                    Optional.ofNullable(s).map(x -> x.width), Optional.ofNullable(s).map(x -> x.height),
                    s instanceof DeathScreen ds ? Optional.ofNullable(readDeathCause(ds)) : Optional.empty());
        });
    }

    public static ScreenTree screenTree() {
        return ClientHop.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            String context = ScreenContext.current();
            if (s == null) {
                return new ScreenTree(context, false, Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), List.of());
            }
            Nodes nodes = new Nodes();
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
            Optional<String> cause = s instanceof DeathScreen ds ? Optional.ofNullable(readDeathCause(ds)) : Optional.empty();
            return new ScreenTree(context, true, Optional.of(s.getClass().getSimpleName()),
                    Optional.of(s.getTitle().getString()), Optional.of(s.width), Optional.of(s.height), cause,
                    error, slotsError, nodes.build());
        });
    }

    /** Read the DeathScreen's specific cause-of-death Component (e.g. "… was
     *  slain by Drowned", "… drowned", "… was blown up by Creeper"). Public so
     *  the death-event detector can read it the moment the screen appears —
     *  the local combat tracker lacks this at the isDeadOrDying tick; the
     *  specific message arrives with the combat-kill packet that builds the
     *  DeathScreen. Returns null when the screen carries no cause. */
    public static String readDeathCause(DeathScreen ds) {
        // Private in vanilla, opened by worlddriver.accesswidener.
        return ds.causeOfDeath == null ? null : ds.causeOfDeath.getString();
    }

    /**
     * The tree flattened depth first, a node before the nodes it holds. A node is filled in place while
     * its children are added after it, so each is a mutable row until {@link #build}.
     */
    private static final class Nodes {
        private final List<Node> all = new ArrayList<>();

        Node add(Integer parent, int depth, String type) {
            Node n = new Node(all.size(), parent, depth, type);
            all.add(n);
            return n;
        }

        List<WidgetNode> build() {
            List<WidgetNode> out = new ArrayList<>(all.size());
            for (Node n : all) out.add(n.freeze());
            return out;
        }
    }

    private static final class Node {
        final int id;
        final Integer parent;
        final int depth;
        final String type;
        Integer index;
        Box box;
        Boolean visible, active, focused, selected;
        String message, key, value, error;
        SlotItem item;

        Node(int id, Integer parent, int depth, String type) {
            this.id = id;
            this.parent = parent;
            this.depth = depth;
            this.type = type;
        }

        WidgetNode freeze() {
            return new WidgetNode(id, Optional.ofNullable(parent), depth, type, Optional.ofNullable(index),
                    Optional.ofNullable(box), Optional.ofNullable(visible), Optional.ofNullable(active),
                    Optional.ofNullable(focused), Optional.ofNullable(selected), Optional.ofNullable(message),
                    Optional.ofNullable(key), Optional.ofNullable(value), Optional.ofNullable(item),
                    Optional.ofNullable(error));
        }
    }

    private static void walk(GuiEventListener node, Integer parent, int depth, Nodes out) {
        if (!(node instanceof ContainerEventHandler ceh)) return;
        for (GuiEventListener child : ceh.children()) {
            Node n = out.add(parent, depth, child.getClass().getSimpleName());
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
            if (w.getMessage().getContents() instanceof TranslatableContents tc) n.key = tc.getKey();
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
        walk(child, n.id, n.depth + 1, out);
    }

    /** Project per-row bbox + display label for AbstractSelectionList entries.
     *  {@code getRowTop}/{@code itemHeight} are protected in vanilla and opened by
     *  worlddriver.accesswidener. */
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
            Node n = out.add(owner.id, owner.depth + 1, entry.getClass().getSimpleName());
            n.index = i;
            n.box = new Box(rowLeft, list.getRowTop(i), rowWidth, itemHeight - 4);
            if (entry == selected) n.selected = true;
            n.message = entryLabel(entry);
            // Walk INTO the row. Every list row that holds widgets is a ContainerEventHandler
            // (ContainerObjectSelectionList.Entry, KeyBindsList's rows, the options rows), and
            // projecting only its bbox is why a key-binds screen came back as a stack of nameless
            // Entry nodes with no way to tell one binding's row from another's.
            if (entry instanceof GuiEventListener gel) walk(gel, n.id, n.depth + 1, out);
        }
    }

    /**
     * Best-effort human label for an arbitrary list entry.
     *
     * <p>The name-based probes below are deliberate duck-typing: list entries have no
     * common label interface, so we try the getters vanilla happens to use. That works
     * in dev and is what every gate exercises — but the getter NAMES are Mojang-mapped
     * string literals, so in the remapped fabric jar every probe misses and the whole
     * screen comes back with no labels at all.
     *
     * <p>The typed fallbacks run AFTER the probes, never before: the probes decide the
     * label in dev exactly as they always have (this method's dev output is unchanged
     * by construction), and the fallbacks only get a turn in the case where the probes
     * found nothing — which is precisely the remapped case. They are ordinary virtual
     * calls on public API, so tiny-remapper rewrites them correctly.
     */
    private static String entryLabel(Object entry) {
        for (String getter : new String[]{"getLevelName", "getDisplayName", "getMessage", "getName"}) {
            try {
                var m = entry.getClass().getMethod(getter);
                Object v = m.invoke(entry);
                if (v == null) continue;
                if (v instanceof Component c) return c.getString();
                String s = v.toString();
                if (!s.isBlank()) return s;
            } catch (ReflectiveOperationException ignored) { /* try next */ }
        }
        // Probe a `getSummary().getLevelName()` chain (the WorldListEntry case).
        // Wrapped in try so unrelated entry classes simply fall through.
        try {
            var m = entry.getClass().getMethod("getSummary");
            Object summary = m.invoke(entry);
            if (summary != null) {
                var m2 = summary.getClass().getMethod("getLevelName");
                Object v = m2.invoke(summary);
                if (v != null) return v.toString();
            }
        } catch (ReflectiveOperationException ignored) { /* no summary */ }
        // Typed fallbacks — the only branches that still work once the jar is remapped.
        if (entry instanceof AbstractWidget w) {
            String s = w.getMessage() == null ? "" : w.getMessage().getString();
            if (!s.isBlank()) return s;
        }
        if (entry instanceof ObjectSelectionList.Entry<?> ose) {
            // getNarration() is the accessibility label vanilla builds for every
            // selectable row — wordier than getLevelName(), but a real label beats
            // the null this method used to return.
            Component n = ose.getNarration();
            String s = n == null ? "" : n.getString();
            if (!s.isBlank()) return s;
        }
        // Last resort, and by TYPE rather than by name: a row that renders its text straight from a
        // private Component field has no getter to probe, and that field is the only thing saying
        // which row it is — a key-binds screen is a stack of such rows, one per binding. Most
        // derived class first, and never a static: those hold shared button titles ("Reset"), which
        // would label every row identically.
        try {
            for (Class<?> c = entry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (!Component.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    if (f.get(entry) instanceof Component comp) {
                        String s = comp.getString();
                        if (!s.isBlank()) return s;
                    }
                }
            }
        } catch (RuntimeException | ReflectiveOperationException ignored) { /* no label to be had */ }
        return null;
    }

    /** Add the open AbstractContainerScreen's slots under one synthetic ContainerSlots node. */
    private static void containerSlots(AbstractContainerScreen<?> acs, Nodes out) {
        var menu = acs.getMenu();
        if (menu == null || menu.slots == null || menu.slots.isEmpty()) return;
        // Container screens use leftPos/topPos as the inner-GUI origin; slot.x/y
        // are GUI-local. We expose absolute Screen coords so agents can pass
        // them straight to mc.client.input.click without doing arithmetic.
        // Both protected in vanilla, opened by worlddriver.accesswidener.
        int leftPos = acs.leftPos, topPos = acs.topPos;
        Node holder = out.add(null, 0, "ContainerSlots");
        for (int i = 0; i < menu.slots.size(); i++) {
            Node n = out.add(holder.id, 1, "Slot");
            n.index = i;
            // Per slot, for the same reason as the widget walk: a modded slot that throws from
            // isActive or getItem should cost its own row, not the screen's whole slot list.
            try {
                Slot slot = menu.slots.get(i);
                n.box = new Box(leftPos + slot.x, topPos + slot.y, 16, 16);   // vanilla slot icon is 16×16
                n.active = slot.isActive();
                ItemStack stk = slot.getItem();
                if (!stk.isEmpty()) n.item = new SlotItem(BuiltInRegistries.ITEM.getKey(stk.getItem()).toString(), stk.getCount());
            } catch (Throwable t) {
                n.error = describeThrowable(t);
            }
        }
    }
}
