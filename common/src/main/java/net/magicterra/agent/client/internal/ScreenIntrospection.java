package net.magicterra.agent.client.internal;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.magicterra.agent.client.internal.ClientThread.runOnClient;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.components.EditBox;

/**
 * Screen / widget-tree introspection for {@code mc.client.screen.*}. Stateless;
 * extracted from {@code ClientAgentApiImpl}.
 */
public final class ScreenIntrospection {
    private ScreenIntrospection() {}

    public static Map<String, Object> screenInfo() {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("hasScreen", s != null);
            out.put("worldOpen", mc.level != null);
            out.put("hasPlayer", mc.player != null);
            // Overlay (LoadingOverlay during early boot, ResourceLoadProgressScreen
            // after F3+T, etc.) is rendered ON TOP of the active Screen — so
            // {@code screen.type=TitleScreen} can be true while the user still
            // sees a fading Mojang splash. Surface it so callers can wait.
            Overlay ov = mc.getOverlay();
            out.put("overlayActive", ov != null);
            if (ov != null) out.put("overlayType", ov.getClass().getSimpleName());
            if (s != null) {
                out.put("type", s.getClass().getSimpleName());
                out.put("title", s.getTitle().getString());
                out.put("width", s.width);
                out.put("height", s.height);
                // On a DeathScreen the title is just "You Died!"; the actual cause
                // ("Player was slain by Phantom", "fell from a high place", …) is a
                // separate private Component. Surface it here too so a cheap
                // screen.info probe reveals WHY the bot died without a screen.tree.
                if (s instanceof DeathScreen ds) {
                    String cause = readDeathCause(ds);
                    if (cause != null) out.put("causeOfDeath", cause);
                }
            }
            return out;
        });
    }

    public static Map<String, Object> screenTree() {
        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            Screen s = mc.screen;
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("hasScreen", s != null);
            if (s == null) return root;
            root.put("type", s.getClass().getSimpleName());
            root.put("title", s.getTitle().getString());
            root.put("width", s.width);
            root.put("height", s.height);
            List<Map<String, Object>> kids = walk(s);
            // Container screens (InventoryScreen, ChestScreen, FurnaceScreen, …)
            // expose their slots through the AbstractContainerMenu, not via the
            // GuiEventListener tree — so a plain walk omits every clickable slot.
            // Inject one synthetic ContainerSlots child carrying per-slot bbox,
            // vanilla slot index, and itemstack so agents can pick a slot by
            // label/index without resorting to pixel-counting.
            if (s instanceof AbstractContainerScreen<?> acs) {
                Map<String, Object> slotsNode = containerSlotsNode(acs);
                if (slotsNode != null) kids.add(slotsNode);
            }
            // DeathScreen's cause-of-death is rendered directly from a
            // private Component field, never a child widget — without this
            // surfaceing, the agent has no client-side way to find out what
            // killed the player (server log is the only other source).
            if (s instanceof DeathScreen ds) {
                String cause = readDeathCause(ds);
                if (cause != null) root.put("causeOfDeath", cause);
            }
            root.put("children", kids);
            return root;
        });
    }

    /** Read the DeathScreen's specific cause-of-death Component (e.g. "… was
     *  slain by Drowned", "… drowned", "… was blown up by Creeper"). Public so
     *  the death-event detector can read it the moment the screen appears —
     *  the local combat tracker lacks this at the isDeadOrDying tick; the
     *  specific message arrives with the combat-kill packet that builds the
     *  DeathScreen. Returns null when the screen carries no cause. */
    public static String readDeathCause(DeathScreen ds) {
        // Private in vanilla, opened by agent_driver.accesswidener.
        return ds.causeOfDeath == null ? null : ds.causeOfDeath.getString();
    }

    private static List<Map<String, Object>> walk(GuiEventListener node) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(node instanceof ContainerEventHandler ceh)) return out;
        for (GuiEventListener child : ceh.children()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", child.getClass().getSimpleName());
            if (child instanceof AbstractWidget w) {
                n.put("x", w.getX());
                n.put("y", w.getY());
                n.put("width", w.getWidth());
                n.put("height", w.getHeight());
                n.put("visible", w.visible);
                n.put("active", w.active);
                n.put("message", w.getMessage().getString());
            }
            // EditBox holds typed text. Without exposing the current value, agents
            // can drive the cursor and call typeText but never verify what was
            // entered (or what vanilla pre-filled, e.g. last-used server address).
            if (child instanceof EditBox eb) {
                n.put("value", eb.getValue());
                n.put("focused", eb.isFocused());
            }
            // AbstractSelectionList entries (SelectWorldScreen rows, ServerSelectionList,
            // RealmsList…) don't extend AbstractWidget, so a plain walk leaves them
            // with only a type name — agents can't pick a row by label. Project the
            // list's per-row geometry + display name onto each entry.
            if (child instanceof AbstractSelectionList<?> list) {
                List<Map<String, Object>> entries = listEntries(list);
                if (!entries.isEmpty()) n.put("children", entries);
                out.add(n);
                continue;
            }
            List<Map<String, Object>> grand = walk(child);
            if (!grand.isEmpty()) n.put("children", grand);
            out.add(n);
        }
        return out;
    }

    /** Project per-row bbox + display label for AbstractSelectionList entries.
     *  {@code getRowTop}/{@code itemHeight} are protected in vanilla and opened by
     *  agent_driver.accesswidener. */
    private static List<Map<String, Object>> listEntries(AbstractSelectionList<?> list) {
        List<Map<String, Object>> out = new ArrayList<>();
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
        for (int i = 0; i < children.size(); i++) {
            Object entry = children.get(i);
            int rowTop = list.getRowTop(i);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", entry.getClass().getSimpleName());
            n.put("index", i);
            n.put("x", rowLeft);
            n.put("y", rowTop);
            n.put("width", rowWidth);
            n.put("height", itemHeight - 4);
            String label = entryLabel(entry);
            if (label != null) n.put("message", label);
            out.add(n);
        }
        return out;
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
        return null;
    }

    /** Walk the open AbstractContainerScreen's slots and emit a synthetic node. */
    private static Map<String, Object> containerSlotsNode(AbstractContainerScreen<?> acs) {
        var menu = acs.getMenu();
        if (menu == null || menu.slots == null || menu.slots.isEmpty()) return null;
        // Container screens use leftPos/topPos as the inner-GUI origin; slot.x/y
        // are GUI-local. We expose absolute Screen coords so agents can pass
        // them straight to mc.client.input.click without doing arithmetic.
        // Both protected in vanilla, opened by agent_driver.accesswidener.
        int leftPos = acs.leftPos, topPos = acs.topPos;
        List<Map<String, Object>> slotNodes = new ArrayList<>(menu.slots.size());
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("type", "Slot");
            n.put("index", i);
            n.put("x", leftPos + slot.x);
            n.put("y", topPos + slot.y);
            n.put("width", 16);   // vanilla slot icon is 16×16 in GUI coords
            n.put("height", 16);
            n.put("active", slot.isActive());
            ItemStack stk = slot.getItem();
            if (!stk.isEmpty()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", BuiltInRegistries.ITEM.getKey(stk.getItem()).toString());
                item.put("count", stk.getCount());
                n.put("item", item);
            }
            slotNodes.add(n);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "ContainerSlots");
        out.put("children", slotNodes);
        return out;
    }
}
