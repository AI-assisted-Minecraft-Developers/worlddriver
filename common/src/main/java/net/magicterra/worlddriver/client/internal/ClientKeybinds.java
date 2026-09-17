package net.magicterra.worlddriver.client.internal;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * {@code mc.client.input.keybind}: drives a key mapping by name rather than by key.
 *
 * <p>Why this sits beside {@link ClientInput#key}, which already synthesizes keys: a modifier is
 * not a key press as far as a mapping is concerned. A modified binding — ALT+Y, and a mod pack is
 * full of them — is matched through {@code KeyModifier.isActive}, which asks
 * {@code Screen.hasAltDown()}, which reads the real keyboard through {@code glfwGetKey}. A
 * synthesized ALT press is driver-side state the window knows nothing about, so pressing ALT and
 * then Y fires nothing however the two are ordered. This verb skips the question: it marks the
 * mapping down and counts a click on it, which is what {@code consumeClick()} hands the mod that
 * owns the binding. It is also the only way to name a binding whose key the caller cannot know —
 * the human may have rebound it.
 *
 * <p>With no name it lists every mapping, which is how a caller finds the one it wants.
 */
public final class ClientKeybinds {
    private ClientKeybinds() {}

    /** How long a click waits for the tick that carries its release before reporting it undelivered. */
    private static final long CLICK_RELEASE_MS = 2_000;

    public static Map<String, Object> keybind(String name, String action) {
        final String want = name == null ? "" : name.trim();
        final String act = (action == null || action.isBlank()) ? "click" : action.trim().toLowerCase(Locale.ROOT);
        if (!act.equals("press") && !act.equals("release") && !act.equals("click")) {
            return Map.of("ok", false, "error", "action must be press|release|click (got " + act + ")");
        }
        final KeyMapping[] held = new KeyMapping[1];
        Map<String, Object> out = runOnClient(() -> {
            KeyMapping[] all = Minecraft.getInstance().options.keyMappings;
            if (want.isEmpty()) return list(all);
            List<KeyMapping> hits = resolve(all, want);
            if (hits.isEmpty()) {
                return Map.of("ok", false, "reason", "no_such_keybind",
                        "error", "no key mapping matches '" + want
                                + "' — call this with no name to list every mapping");
            }
            if (hits.size() > 1) {
                return Map.of("ok", false, "reason", "ambiguous",
                        "error", "'" + want + "' matches " + hits.size() + " mappings: " + names(hits));
            }
            KeyMapping km = hits.get(0);
            if (act.equals("release")) {
                km.setDown(false);
                return reply(km, act, 0);
            }
            km.setDown(true);
            // A press is both for vanilla: the key goes down AND a click is counted. Owners that
            // poll isDown() see the first, owners that poll consumeClick() see the second, and a
            // verb that set only one of them would work for half the bindings in a pack.
            // Private in vanilla, opened by worlddriver.accesswidener.
            km.clickCount++;
            held[0] = km;
            return reply(km, act, 1);
        });
        if (!act.equals("click") || !Boolean.TRUE.equals(out.get("ok")) || held[0] == null) return out;
        // Same reason as mc.client.input.key's click: a keystroke spans ticks, and the mapping's
        // owner only gets its turn on a tick boundary. Holding it down until then is what makes a
        // click of a movement binding move anything.
        final KeyMapping km = held[0];
        Boolean up = ClientThread.runNextTick(() -> {
            km.setDown(false);
            return Boolean.TRUE;
        }, CLICK_RELEASE_MS);
        Map<String, Object> full = new LinkedHashMap<>(out);
        full.put("released", Boolean.TRUE.equals(up));
        if (up == null) {
            full.put("releaseNote", "the client did not tick within " + CLICK_RELEASE_MS
                    + " ms, so the binding is still down — send action release once it ticks again");
        }
        return full;
    }

    private static Map<String, Object> list(KeyMapping[] all) {
        List<Map<String, Object>> rows = new ArrayList<>(all.length);
        for (KeyMapping km : all) rows.add(describe(km));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("count", rows.size());
        m.put("keybinds", rows);
        return m;
    }

    /** Exact id first, so a caller that knows the id is never told its name is ambiguous. */
    private static List<KeyMapping> resolve(KeyMapping[] all, String want) {
        List<KeyMapping> exact = new ArrayList<>();
        for (KeyMapping km : all) if (km.getName().equals(want)) exact.add(km);
        if (!exact.isEmpty()) return exact;
        String needle = want.toLowerCase(Locale.ROOT);
        List<KeyMapping> loose = new ArrayList<>();
        for (KeyMapping km : all) {
            if (km.getName().toLowerCase(Locale.ROOT).contains(needle)
                    || title(km).toLowerCase(Locale.ROOT).contains(needle)) loose.add(km);
        }
        return loose;
    }

    private static Map<String, Object> describe(KeyMapping km) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", km.getName());
        r.put("title", title(km));
        // Both, because they answer different questions: saveString() is the bound key's id
        // ("key.keyboard.y"), which reads as an unmodified binding and is why ALT+Y looked
        // pressable through input.key, while the translated message is what the human sees in the
        // controls screen and carries the modifier ("Alt + Y") on a loader that has them.
        r.put("key", km.saveString());
        r.put("boundTo", km.getTranslatedKeyMessage().getString());
        r.put("bound", !km.isUnbound());
        r.put("category", km.getCategory());
        r.put("down", km.isDown());
        return r;
    }

    private static Map<String, Object> reply(KeyMapping km, String act, int clicks) {
        Map<String, Object> m = new LinkedHashMap<>(describe(km));
        m.put("ok", true);
        m.put("action", act);
        m.put("clicks", clicks);
        m.put("released", act.equals("release"));
        return m;
    }

    private static String title(KeyMapping km) {
        return I18n.get(km.getName());
    }

    private static String names(List<KeyMapping> hits) {
        List<String> out = new ArrayList<>(hits.size());
        for (KeyMapping km : hits) out.add(km.getName());
        return String.join(", ", out);
    }
}
