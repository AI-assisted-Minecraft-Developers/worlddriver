package net.magicterra.worlddriver.application.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientReply;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeybindDriven;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.KeybindsListed;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeybindParams;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.Keybinding;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ScreenTree;
import net.magicterra.worlddriver.game.ClientHop;
import net.magicterra.worlddriver.game.ui.ClientThread;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

/**
 * {@code mc.client.input.keybind}: drives a key mapping by name rather than by key.
 *
 * <p>Why this sits beside {@link ClientInput#key}, which already synthesizes keys: a modifier is not a key press as far
 * as a mapping is concerned. A modified binding — ALT+Y, and a mod pack is full of them — is matched through
 * {@code KeyModifier.isActive}, which asks {@code Screen.hasAltDown()}, which reads the real keyboard through
 * {@code glfwGetKey}. A synthesized ALT press is driver-side state the window knows nothing about, so pressing ALT and
 * then Y fires nothing however the two are ordered. This verb skips the question: it marks the mapping down and counts
 * a click on it, which is what {@code consumeClick()} hands the mod that owns the binding. It is also the only way to
 * name a binding whose key the caller cannot know — the human may have rebound it.
 *
 * <p>With no name it lists every mapping, which is how a caller finds the one it wants.
 */
public final class ClientKeybinds {
    private ClientKeybinds() {}

    /** How long a click waits for the tick that carries its release before reporting it undelivered. */
    private static final long CLICK_RELEASE_MS = 2_000;

    /**
     * What a press or a release left: the reply, and for a click's press the mapping its release lets go of and the
     * tree before the press, which returnTree compares the release against.
     */
    private record Pressed(ClientReply reply, KeyMapping held, ScreenTree before) {}

    /**
     * A list, a press or a release, on the client thread; a click's release follows at the next client tick, and no
     * thread is held between the two. Same reason as {@code mc.client.input.key}'s click: a keystroke spans ticks, and
     * the mapping's owner only gets its turn on a tick boundary. Holding it down until then is what makes a click of a
     * movement binding move anything.
     */
    public static CompletionStage<ClientReply> keybind(KeybindParams p, CallContext ctx) {
        return ClientHop.hop().submit(() -> press(p), ctx).thenCompose(pr -> {
            if (!(pr.reply() instanceof KeybindDriven pressed) || pr.held() == null)
                return CompletableFuture.completedFuture(pr.reply());
            KeyMapping km = pr.held();
            // The screen as of the release tick, not of the press: the press is what opens one, and
            // that is the tick it becomes visible on.
            return ClientThread.nextTick(
                            () -> {
                                km.setDown(false);
                                fireRaw(km, false);
                                return new Release(
                                        screenName(), ScreenContext.current(), ScreenIntrospection.after(pr.before()));
                            },
                            CLICK_RELEASE_MS)
                    .thenApply(rel -> released(pressed, rel));
        });
    }

    /** On the client thread. */
    private static Pressed press(KeybindParams p) {
        String want = p.name().map(String::trim).orElse("");
        KeyAction act = p.action();
        ScreenContext.require(p.contextId());
        KeyMapping[] all = Minecraft.getInstance().options.keyMappings;
        if (want.isEmpty()) return new Pressed(list(all), null, null);
        List<KeyMapping> hits = resolve(all, want);
        if (hits.isEmpty()) {
            return new Pressed(
                    new Refused(
                            ClientRefusal.NO_TARGET,
                            "no key mapping matches '" + want + "' — call this with no name to list every mapping"),
                    null,
                    null);
        }
        if (hits.size() > 1) {
            return new Pressed(
                    new Refused(
                            ClientRefusal.AMBIGUOUS,
                            "'" + want + "' matches " + hits.size() + " mappings: " + names(hits)),
                    null,
                    null);
        }
        KeyMapping km = hits.get(0);
        ScreenTree before = ScreenIntrospection.before(p.returnTree());
        if (act == KeyAction.RELEASE) {
            String raw = fireRaw(km, false);
            // Unconditionally, unlike the press: vanilla's release does the same thing, and a
            // mapping left down walks the player into whatever runs next.
            km.setDown(false);
            return new Pressed(reply(km, act, km.clickCount, true, raw, ScreenIntrospection.after(before)), null, null);
        }
        // Count the click BEFORE the event, then take one back if vanilla counted one too.
        //
        // A press is both things for vanilla: the key goes down AND a click is counted, and
        // driving only one of them works for half the bindings in a pack. Vanilla's keyPress
        // does both before it fires the loader's key event, so letting it do the counting
        // looked right — until a real ALT+Y binding came back with zero clicks pending: the
        // lookup that keyPress counts through is indexed by modifier, and clearing the
        // binding's modifier for the event does not reindex it. An event-driven mod did not
        // care; one that polls consumeClick() would have seen nothing. So the click is ours
        // and it is in place before any handler runs; if vanilla managed to add its own, the
        // count is one too high afterwards and gives it back.
        // clickCount is private in vanilla, opened by worlddriver.accesswidener.
        int clicksBefore = km.clickCount;
        km.setDown(true);
        km.clickCount = clicksBefore + 1;
        String raw = fireRaw(km, true);
        if (km.clickCount >= clicksBefore + 2) km.clickCount--;
        if (act == KeyAction.CLICK)
            return new Pressed(reply(km, act, km.clickCount, false, raw, ScreenIntrospection.After.NONE), km, before);
        return new Pressed(reply(km, act, km.clickCount, false, raw, ScreenIntrospection.after(before)), null, null);
    }

    /** The screen as of a click's release tick, and returnTree's answer then. */
    private record Release(String screenAfter, String contextId, ScreenIntrospection.After after) {}

    /** A click's reply from its press and its release; {@code rel} null when the release tick never came. */
    private static ClientReply released(KeybindDriven pressed, Release rel) {
        if (rel == null) {
            return new KeybindDriven(
                    pressed.name(),
                    pressed.title(),
                    pressed.key(),
                    pressed.boundTo(),
                    pressed.category(),
                    pressed.down(),
                    KeyAction.CLICK,
                    pressed.clicks(),
                    false,
                    pressed.rawEvent(),
                    pressed.windowActive(),
                    pressed.mouseGrabbed(),
                    pressed.screenAfter(),
                    Optional.of("the client did not tick within " + CLICK_RELEASE_MS
                            + " ms, so the binding is still down — send action release once it ticks again"),
                    Optional.empty(),
                    Optional.empty(),
                    pressed.contextId());
        }
        return new KeybindDriven(
                pressed.name(),
                pressed.title(),
                pressed.key(),
                pressed.boundTo(),
                pressed.category(),
                pressed.down(),
                KeyAction.CLICK,
                pressed.clicks(),
                true,
                pressed.rawEvent(),
                pressed.windowActive(),
                pressed.mouseGrabbed(),
                rel.screenAfter(),
                Optional.empty(),
                rel.after().changed(),
                rel.after().tree(),
                rel.contextId());
    }

    private static String screenName() {
        var s = Minecraft.getInstance().screen;
        return s == null ? "none" : s.getClass().getSimpleName();
    }

    private static KeybindsListed list(KeyMapping[] all) {
        List<Keybinding> rows = new ArrayList<>(all.length);
        for (KeyMapping km : all) rows.add(describe(km));
        return new KeybindsListed(rows);
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

    private static Keybinding describe(KeyMapping km) {
        // key and boundTo both, because they answer different questions: saveString() is the bound
        // key's id ("key.keyboard.y"), which reads as an unmodified binding and is why ALT+Y looked
        // pressable through input.key, while the translated message is what the human sees in the
        // controls screen and carries the modifier ("Alt + Y") on a loader that has them.
        return new Keybinding(
                km.getName(),
                title(km),
                km.saveString(),
                km.getTranslatedKeyMessage().getString(),
                !km.isUnbound(),
                km.getCategory(),
                km.isDown());
    }

    private static KeybindDriven reply(
            KeyMapping km, KeyAction act, int clicks, boolean released, String raw, ScreenIntrospection.After after) {
        Minecraft mc = Minecraft.getInstance();
        // windowActive/mouseGrabbed: the two gates a mod's key handler is most likely to put in front
        // of itself, reported so a caller whose keystroke vanished can tell "the mod refused it" from
        // "it never arrived": Yes Steve Model, to pick the one that was measured, does nothing unless
        // the window is focused and the mouse is grabbed, whatever the binding says.
        // screenAfter: what this keystroke left standing. The caller's NEXT one is routed by it, and
        // a key sent at a screen nobody knows is open comes back undelivered and reads like a broken verb.
        return new KeybindDriven(
                km.getName(),
                title(km),
                km.saveString(),
                km.getTranslatedKeyMessage().getString(),
                km.getCategory(),
                km.isDown(),
                act,
                clicks,
                released,
                raw,
                mc.isWindowActive(),
                mc.mouseHandler.isMouseGrabbed(),
                screenName(),
                Optional.empty(),
                after.changed(),
                after.tree(),
                ScreenContext.current());
    }

    /**
     * Also deliver the keystroke as a raw key event, and say what became of it.
     *
     * <p>Marking a mapping down reaches only the mods that poll it. A large family instead subscribes to the loader's
     * key-input event and asks the mapping whether the event matches, so with no event nothing of theirs runs at all.
     * Yes Steve Model is one, and its test — {@code km.matches(key, scanCode) &&
     * km.getKeyModifier().equals(KeyModifier.getActiveModifier())} — is also why no synthesized modifier can satisfy
     * it: the active modifier is read from the physical keyboard. So the binding's own modifier is cleared for the
     * length of the event, which makes that comparison NONE against NONE, and restored immediately after. On a loader
     * with no key modifiers the clearing step is absent and only the event is sent.
     */
    private static String fireRaw(KeyMapping km, boolean down) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return "skipped: a screen is open, and a raw key belongs to it";
        if (km.isUnbound()) return "skipped: the mapping is unbound";
        com.mojang.blaze3d.platform.InputConstants.Key k;
        try {
            k = com.mojang.blaze3d.platform.InputConstants.getKey(km.saveString());
        } catch (RuntimeException e) {
            return "skipped: " + km.saveString() + " is not a key that can be synthesized";
        }
        if (k.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM) {
            return "skipped: " + km.saveString() + " is not a keyboard key";
        }
        Object modifier = keyModifier(km);
        String modName = modifier == null ? "NONE" : modifierName(modifier);
        boolean cleared = !"NONE".equals(modName) && setModifier(km, modifierNamed("NONE"), k);
        try {
            mc.keyboardHandler.keyPress(
                    mc.getWindow().getWindow(),
                    k.getValue(),
                    org.lwjgl.glfw.GLFW.glfwGetKeyScancode(k.getValue()),
                    down ? org.lwjgl.glfw.GLFW.GLFW_PRESS : org.lwjgl.glfw.GLFW.GLFW_RELEASE,
                    0);
        } finally {
            if (cleared) setModifier(km, modifier, k);
        }
        if (cleared) return "sent, with the " + modName + " modifier cleared for it";
        return "NONE".equals(modName)
                ? "sent"
                : "sent, but the " + modName
                        + " modifier could not be cleared — a handler that compares it will refuse";
    }

    /**
     * The binding's modifier, or null on a loader that has none. Reflective: the method and its type belong to the
     * loader, not to the game, so neither exists in common's mappings.
     */
    private static Object keyModifier(KeyMapping km) {
        try {
            return km.getClass().getMethod("getKeyModifier").invoke(km);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Object modifierNamed(String name) {
        try {
            Class<?> type = Class.forName("net.neoforged.neoforge.client.settings.KeyModifier");
            Object[] all = type.getEnumConstants();
            if (all != null) {
                for (Object c : all) if (c instanceof Enum<?> e && e.name().equals(name)) return c;
            }
        } catch (ClassNotFoundException | RuntimeException ignored) {
            /* no modifiers here */
        }
        return null;
    }

    private static String modifierName(Object modifier) {
        return modifier instanceof Enum<?> e ? e.name() : String.valueOf(modifier);
    }

    /** Puts {@code modifier} back on the binding, keeping its key. False when this loader has none. */
    private static boolean setModifier(
            KeyMapping km, Object modifier, com.mojang.blaze3d.platform.InputConstants.Key k) {
        if (modifier == null) return false;
        try {
            Class<?> type = Class.forName("net.neoforged.neoforge.client.settings.KeyModifier");
            km.getClass()
                    .getMethod("setKeyModifierAndCode", type, com.mojang.blaze3d.platform.InputConstants.Key.class)
                    .invoke(km, modifier, k);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
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
