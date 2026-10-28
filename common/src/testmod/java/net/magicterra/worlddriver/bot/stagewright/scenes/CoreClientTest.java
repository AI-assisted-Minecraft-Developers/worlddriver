package net.magicterra.worlddriver.bot.stagewright.scenes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.event.EventBlockingApi;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.bot.stagewright.kit.Calls;
import net.magicterra.worlddriver.bot.stagewright.kit.SceneJson;
import net.magicterra.worlddriver.bot.stagewright.kit.Settings;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The Core family, client-face half: the input verbs that go through the real client's own handlers, and the client
 * face's readings of its player. Every call goes by name through the client's registry ({@link Calls#byName}), the
 * door every transport shares. The headless half is {@link CoreTest}.
 *
 * <p>The player's game mode picks the screen the inventory binding opens (creative opens the creative inventory) and
 * what the wheel does (a spectator's changes flying speed), so each test sets survival and puts the old mode back.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class CoreClientTest {

    /**
     * {@code mc.client.input.keybind} opens what a named binding opens, and leaves it released. The verb exists for
     * the bindings no synthesized key can reach (a pack binds GUIs to modified keys, whose modifier half is read from
     * the physical keyboard). What is held here is everything else it promises, on a binding whose effect is
     * unmistakable: the screen it opens is open; the key went out as a real event ({@code rawEvent=sent}, what mods
     * that subscribe to key input need); and the mapping is down during the click and released after it, because a
     * binding left down outlives the test. {@code key.inventory}, rather than one the testmod registers: the verb's
     * subject is bindings it does not own.
     */
    @Test
    void clientKeybindOpensABinding(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            Map<String, Object> m = call(client, "mc.client.input.keybind", Map.of("name", "key.inventory"));
            game.check(() -> assertEquals("done", m.get("outcome"), "the verb took the binding's name: " + m));
            game.check(() -> assertEquals("key.inventory", m.get("name"), "the mapping it resolved the name to"));
            game.check(() -> assertEquals("sent", String.valueOf(m.get("rawEvent")),
                    "what became of the raw key event — `sent` is what an event-driven mod needs"));
            game.check(() -> assertEquals(true, m.get("released"), "the click released the mapping"));
            game.check(() -> assertEquals(true, m.get("down"), "the mapping was down while the click was in flight"));
            // One click pending, not two: the driver counts one before the event so a mod that polls consumeClick()
            // sees it, and vanilla's keyPress counts one of its own inside that event. Two would make every
            // poll-driven binding act twice.
            game.check(() -> assertEquals(1L, m.get("clicks"), "clicks left pending on the mapping"));
            // What the keystroke left open, which is what routes the caller's NEXT one.
            game.check(() -> assertEquals("InventoryScreen", m.get("screenAfter"), "the screen this keystroke left"));

            Map<String, Object> info = until(game, client, "mc.client.screen.info", Map.of(),
                    i -> Boolean.TRUE.equals(i.get("hasScreen")), 100, "a screen opens");
            game.check(() -> assertEquals("InventoryScreen", info.get("type"), "the screen the inventory binding opens"));
            // Read back from the listing, not from the reply that claimed it: a release only the replier believes in
            // is the failure this checks for.
            Map<String, Object> listing = call(client, "mc.client.input.keybind", Map.of());
            game.check(() -> assertEquals(false, keybindDown(listing, "key.inventory"),
                    "key.inventory still down after the click, read back from the listing"));
        } finally {
            call(client, "mc.client.screen.close", Map.of());
        }
    }

    /**
     * {@code mc.client.input.click} by a ref from a tree, and {@code returnTree}. The survival inventory's recipe-book
     * button, because clicking it moves it: the screen shifts right to make room for the book. So the ref is shown to
     * follow the widget rather than the place it was read at — the second click aims where the button is now — and
     * the tree that comes back with the first click is the one after it. Then the screen is reopened, which builds a
     * new button, and the old ref must be refused rather than land on it.
     */
    @Test
    void clientClickByRef(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            Map<String, Object> opened = call(client, "mc.client.input.keybind",
                    Map.of("name", "key.inventory", "returnTree", true));
            game.check(() -> assertEquals(true, opened.get("treeChanged"), "opening the inventory changed the tree"));
            Map<?, ?> tree = opened.get("tree") instanceof Map<?, ?> t ? t : Map.of();
            game.check(() -> assertEquals("InventoryScreen", tree.get("type"),
                    "the tree that came back is the screen the keystroke opened"));
            Map<?, ?> button = nodeOfType(tree, "ImageButton");
            if (button == null) fail("no ImageButton (the recipe-book toggle) in the inventory's tree: " + tree.get("nodes"));
            Object ref = button.get("ref");
            Object xBefore = boxX(button);

            Map<String, Object> toggled = call(client, "mc.client.input.click", Map.of("ref", ref, "returnTree", true));
            game.check(() -> assertEquals("done", toggled.get("outcome"), "a click by ref: " + toggled));
            game.check(() -> assertEquals(true, toggled.get("handled"), "the button took the click"));
            game.check(() -> assertEquals(true, toggled.get("treeChanged"), "opening the book changed the tree"));
            Map<?, ?> moved = nodeWithRef(toggled.get("tree") instanceof Map<?, ?> t ? t : Map.of(), ref);
            game.check(moved != null, "the same ref " + ref + " is in the tree after the click");
            game.check(() -> assertNotEquals(xBefore, moved == null ? xBefore : boxX(moved),
                    "the button's x after the book opened"));
            // Aimed where the button is now, not where the first tree put it: a click at the old point would miss it.
            Map<String, Object> back = call(client, "mc.client.input.click", Map.of("ref", ref));
            game.check(() -> assertEquals(true, back.get("handled"), "the moved button took the second click too"));
            game.check(!back.containsKey("treeChanged"), "no treeChanged without returnTree: " + back);

            call(client, "mc.client.screen.close", Map.of());
            call(client, "mc.client.input.keybind", Map.of("name", "key.inventory"));
            Map<String, Object> stale = call(client, "mc.client.input.click", Map.of("ref", ref));
            game.check(() -> assertEquals("refused", stale.get("outcome"), "a ref from a screen since rebuilt: " + stale));
            game.check(() -> assertEquals("no_target", stale.get("reason"), "why a stale ref is refused"));
        } finally {
            call(client, "mc.client.screen.close", Map.of());
        }
    }

    /**
     * {@code mc.client.input.mouseMove} with only {@code scroll}: one notch toward the user, with no screen open, goes
     * where vanilla's wheel goes — the hotbar steps one slot right — and the cursor, not named, stays where it was.
     */
    @Test
    void clientScrollStepsTheHotbar(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            call(client, "mc.client.input.setHotbarSlot", Map.of("slot", 3));
            until(game, client, "mc.client.player", Map.of(), p -> selectedSlot(p) == 3, 100, "slot 3 is selected");
            call(client, "mc.client.input.mouseMove", Map.of("scroll", -1.0));
            Map<String, Object> after = until(game, client, "mc.client.player", Map.of(), q -> selectedSlot(q) != 3, 40,
                    "the selected slot moves off 3");
            assertEquals(4, selectedSlot(after), "the selected hotbar slot after one notch toward the user from slot 3");
        } finally {
            call(client, "mc.client.input.setHotbarSlot", Map.of("slot", 0));
        }
    }

    /**
     * {@code route: "raw"} on key, click and typeText, which go through vanilla's own keyboard and mouse handlers
     * instead of straight to the screen. Each step is one a direct call cannot show it took: E with no screen opens
     * the inventory, a click on the recipe-book toggle moves it, T opens chat, typed characters land in its box, and
     * ESCAPE closes it. JEI's overlay, the reason for the route, is not in this pack; what it needs is that these
     * events go through those handlers at all.
     */
    @Test
    void clientRawInputThroughVanillaHandlers(Game game, Client client, Player player) {
        survival(game, client, player);
        Predicate<Map<String, Object>> noScreen = i -> Boolean.FALSE.equals(i.get("hasScreen"));
        try {
            Map<String, Object> e = call(client, "mc.client.input.key", Map.of("key", "E", "route", "raw"));
            game.check(() -> assertEquals("raw", e.get("via"), "where key E with route raw went"));
            Map<String, Object> tree = until(game, client, "mc.client.screen.tree", Map.of(),
                    t -> "InventoryScreen".equals(t.get("type")), 40, "E opens the inventory");
            Map<?, ?> button = nodeOfType(tree, "ImageButton");
            if (button == null) fail("no ImageButton (the recipe-book toggle) in the inventory's tree: " + tree.get("nodes"));
            Object ref = button.get("ref");
            Map<String, Object> clicked = call(client, "mc.client.input.click",
                    Map.of("ref", ref, "route", "raw", "returnTree", true));
            game.check(!clicked.containsKey("handled"), "no `handled` with route raw, which nothing answers: " + clicked);
            Map<?, ?> moved = nodeWithRef(clicked.get("tree") instanceof Map<?, ?> t ? t : Map.of(), ref);
            game.check(() -> assertNotEquals(boxX(button), moved == null ? boxX(button) : boxX(moved),
                    "the toggle's x after a raw click opened the book"));

            call(client, "mc.client.screen.close", Map.of());
            until(game, client, "mc.client.screen.info", Map.of(), noScreen, 40, "the inventory closes");
            call(client, "mc.client.input.key", Map.of("key", "T", "route", "raw"));
            until(game, client, "mc.client.screen.info", Map.of(), s -> "ChatScreen".equals(s.get("type")), 40,
                    "T opens chat");
            call(client, "mc.client.input.typeText", Map.of("text", "hi", "route", "raw"));
            Map<String, Object> read = call(client, "mc.client.input.replaceText", Map.of("text", ""));
            game.check(() -> assertEquals("hi", read.get("previous"), "the chat box after typing hi with route raw"));
            call(client, "mc.client.input.key", Map.of("key", "ESCAPE", "route", "raw"));
            until(game, client, "mc.client.screen.info", Map.of(), noScreen, 40, "ESCAPE with route raw closes chat");
        } finally {
            call(client, "mc.client.screen.close", Map.of());
        }
    }

    /**
     * A {@code player.hurt} event says what hurt the player, not just how much — the #55 permanent assertion.
     * Without attribution a fall into a self-dug pit and a mob bite are the same HP delta, so the combat chain and
     * the agent above it engage phantoms. {@code player.hurt} is emitted client-side, mirroring the server's
     * DamageSource off {@code ClientboundDamageEventPacket}, so it is read from the client's ring: the attribution has
     * to survive the network boundary to reach the agent.
     */
    @Test
    void hurtCarriesItsSource(Game game, Client client, Player player) {
        alone(game);
        String name = player.name();
        // Healed before, so two points cannot kill a player an earlier test left low; and after.
        game.afterTest(s -> command(s, "effect give " + name + " minecraft:instant_health 1 5 true"));
        game.runOnServer(s -> command(s, "effect give " + name + " minecraft:instant_health 1 5 true"));
        // A full i-frame window between the heal and the damage, for two reasons: the event fires on a drop between
        // two of the client detector's own samples (heal and damage in one tick net out, from below full health, to a
        // RISE and no event), and out_of_world bypasses invulnerability but not the cooldown, so a hit inside
        // i-frames must exceed lastHurt.
        game.ticks(20);
        EventBlockingApi events = client.api(EventBlockingApi::of);
        long cursor = events.cursor(new EventContracts.CursorParams()).cursor();
        // out_of_world lands whichever game mode the player is in.
        game.runOnServer(s -> command(s, "damage " + name + " 2 minecraft:out_of_world"));
        List<Map<?, ?>> hurt = new ArrayList<>();
        game.await().within(200).as("a player.hurt event reaches the client's ring").until(() -> {
            hurt.clear();
            hurt.addAll(hurtPayloads(events, cursor));
            return !hurt.isEmpty();
        });
        Map<?, ?> payload = hurt.get(0);
        game.check(payload.get("source") instanceof String src && !src.isEmpty(),
                "player.hurt attributes a source instead of reporting an HP delta alone: " + payload);
        game.check(payload.get("lost") instanceof Number n && n.doubleValue() > 0,
                "player.hurt reports the health actually lost: " + payload);
    }

    /**
     * {@code mc.client.player} reports a LocalPlayer that is genuinely in a world: the client face's own answer to
     * "where am I". The position is asserted on its own, because the failure this guards is a payload that arrives
     * shaped right and empty — {@code present:true} with no {@code pos} reads as in-world to a caller that does not
     * look.
     */
    @Test
    void clientPlayerInWorld(Game game, Client client) {
        alone(game);
        ClientContracts.ClientPlayer cp = client.api(ClientBlockingApi::of).player(new NoParams());
        game.check(cp.present(), "mc.client.player reports a LocalPlayer in a world: " + cp);
        game.check(cp.pos().isPresent(), "mc.client.player's pos is there, not a hole in a well-shaped payload: " + cp);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Survival for the test, the old mode back after it, from no screen (with one open the raw half has nowhere to go
     * but that screen); and only once the client agrees about the mode, since its view of it picks the screen.
     */
    private static void survival(Game game, Client client, Player player) {
        alone(game);
        GameType was = player.onServer(p -> p.gameMode.getGameModeForPlayer());
        game.afterTest(s -> player.runOnServer(p -> p.setGameMode(was)));
        player.runOnServer(p -> p.setGameMode(GameType.SURVIVAL));
        call(client, "mc.client.screen.close", Map.of());
        until(game, client, "mc.client.player", Map.of(), p -> "survival".equals(p.get("gameMode")), 100,
                "the client sees survival");
    }

    /**
     * Run alone among the tests driving the player: the {@link Settings} pin is what every {@code ClientRig} test
     * holds, and one of those (ClientBodyTest) kills the player, which left these tests reading a death screen.
     */
    private static void alone(Game game) {
        Settings.save(game);
    }

    private static Map<String, Object> call(Client client, String method, Map<String, ?> params) {
        return Calls.byName(client.registry(), method, params);
    }

    /** {@code method} asked once a tick until {@code done} holds, at most {@code ticks} ticks; its last answer. */
    private static Map<String, Object> until(Game game, Client client, String method, Map<String, ?> params,
                                             Predicate<Map<String, Object>> done, int ticks, String what) {
        Map<String, Object> last = Map.of();
        for (int t = 0; t <= ticks; t++) {
            last = call(client, method, params);
            if (done.test(last)) return last;
            game.ticks(1);
        }
        return fail(what + " within " + ticks + " ticks; " + method + " last answered " + last);
    }

    private static void command(net.minecraft.server.MinecraftServer server, String command) {
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** The {@code player.hurt} payloads emitted since {@code cursor}, oldest first. */
    private static List<Map<?, ?>> hurtPayloads(EventBlockingApi events, long cursor) {
        EventContracts.Page page = events.eventsSince(EventContracts.SinceParams.after(cursor, "player.hurt"));
        List<Map<?, ?>> out = new ArrayList<>();
        for (EventContracts.Event e : page.events())
            if (SceneJson.plain(e.data().map(JsonValue::json).orElse(null)) instanceof Map<?, ?> m) out.add(m);
        return out;
    }

    /** {@code inventory.selectedSlot} of a {@code mc.client.player} reply, -1 when it has none. */
    private static int selectedSlot(Map<?, ?> clientPlayer) {
        return clientPlayer.get("inventory") instanceof Map<?, ?> inv && inv.get("selectedSlot") instanceof Number n
                ? n.intValue() : -1;
    }

    private static Map<?, ?> nodeOfType(Map<?, ?> tree, String type) {
        if (tree.get("nodes") instanceof List<?> l)
            for (Object o : l) if (o instanceof Map<?, ?> n && type.equals(n.get("type"))) return n;
        return null;
    }

    private static Map<?, ?> nodeWithRef(Map<?, ?> tree, Object ref) {
        if (tree.get("nodes") instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> n && ref != null && String.valueOf(ref).equals(String.valueOf(n.get("ref"))))
                    return n;
        return null;
    }

    private static Object boxX(Map<?, ?> node) {
        return node.get("box") instanceof Map<?, ?> b && b.get("x") instanceof Number n ? n.intValue() : "no box";
    }

    /** What {@code mc.client.input.keybind}'s listing says about one mapping's {@code down}. */
    private static Object keybindDown(Map<?, ?> listing, String name) {
        if (listing.get("keybinds") instanceof List<?> l) {
            for (Object row : l)
                if (row instanceof Map<?, ?> k && name.equals(k.get("name"))) return k.get("down");
        }
        return "no " + name + " row in the keybind listing";
    }
}
