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
import net.magicterra.worlddriver.testcontent.TestPanel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.GameType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * The Core family, client-face half: the input verbs that go through the real client's own handlers, and the client
 * face's readings of its player. Every call goes by name through the client's registry ({@link Calls#byName}), the door
 * every transport shares. The headless half is {@link CoreTest}.
 *
 * <p>The player's game mode picks the screen the inventory binding opens (creative opens the creative inventory) and
 * what the wheel does (a spectator's changes flying speed), so each test sets survival and puts the old mode back.
 */
@Scene("wd.clientScenes")
@Requires(Requires.Capability.CLIENT)
@ResourceLock(ClientWalk.PLAYER)
class CoreClientTest {

    private static final String INVENTORY_SCREEN = "InventoryScreen";

    private static final String DONE = "done";

    private static final String HI = "hi";

    private static final String KEY = "key";

    private static final String KEY_INVENTORY = "key.inventory";

    private static final String MC_CLIENT_INPUT_CLICK = "mc.client.input.click";

    private static final String MC_CLIENT_INPUT_KEY = "mc.client.input.key";

    private static final String MC_CLIENT_INPUT_KEYBIND = "mc.client.input.keybind";

    private static final String MC_CLIENT_SCREEN_CLOSE = "mc.client.screen.close";

    private static final String MC_CLIENT_SCREEN_INFO = "mc.client.screen.info";

    private static final String NAME = "name";

    private static final String NODES = "nodes";

    private static final String OUTCOME = "outcome";

    private static final String RAW = "raw";

    private static final String REF = "ref";

    private static final String RETURN_TREE = "returnTree";

    private static final String ROUTE = "route";

    private static final String TEXT = "text";

    private static final String TREE = "tree";

    private static final String TREE_CHANGED = "treeChanged";

    private static final String TYPE = "type";

    private static final String VALUE = "value";

    /**
     * {@code mc.client.input.keybind} opens what a named binding opens, and leaves it released. The verb exists for the
     * bindings no synthesized key can reach (a pack binds GUIs to modified keys, whose modifier half is read from the
     * physical keyboard). What is held here is everything else it promises, on a binding whose effect is unmistakable:
     * the screen it opens is open; the key went out as a real event ({@code rawEvent=sent}, what mods that subscribe to
     * key input need); and the mapping is down during the click and released after it, because a binding left down
     * outlives the test. {@code key.inventory}, rather than one the testmod registers: the verb's subject is bindings
     * it does not own.
     */
    @Test
    void clientKeybindOpensBinding(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            Map<String, Object> m = call(client, MC_CLIENT_INPUT_KEYBIND, Map.of(NAME, KEY_INVENTORY));
            game.check(() -> assertEquals(DONE, m.get(OUTCOME), "the verb took the binding's name: " + m));
            game.check(() -> assertEquals(KEY_INVENTORY, m.get(NAME), "the mapping it resolved the name to"));
            game.check(() -> assertEquals(
                    "sent",
                    String.valueOf(m.get("rawEvent")),
                    "what became of the raw key event — `sent` is what an event-driven mod needs"));
            game.check(() -> assertEquals(true, m.get("released"), "the click released the mapping"));
            game.check(() -> assertEquals(true, m.get("down"), "the mapping was down while the click was in flight"));
            // One click pending, not two: the driver counts one before the event so a mod that polls consumeClick()
            // sees it, and vanilla's keyPress counts one of its own inside that event. Two would make every
            // poll-driven binding act twice.
            game.check(() -> assertEquals(1L, m.get("clicks"), "clicks left pending on the mapping"));
            // What the keystroke left open, which is what routes the caller's NEXT one.
            game.check(() -> assertEquals(INVENTORY_SCREEN, m.get("screenAfter"), "the screen this keystroke left"));

            Map<String, Object> info = until(
                    game,
                    client,
                    MC_CLIENT_SCREEN_INFO,
                    Map.of(),
                    i -> Boolean.TRUE.equals(i.get("hasScreen")),
                    100,
                    "a screen opens");
            game.check(() -> assertEquals(INVENTORY_SCREEN, info.get(TYPE), "the screen the inventory binding opens"));
            // Read back from the listing, not from the reply that claimed it: a release only the replier believes in
            // is the failure this checks for.
            Map<String, Object> listing = call(client, MC_CLIENT_INPUT_KEYBIND, Map.of());
            game.check(() -> assertEquals(
                    false,
                    keybindDown(listing, KEY_INVENTORY),
                    "key.inventory still down after the click, read back from the listing"));
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        }
    }

    /**
     * {@code mc.client.input.click} by a ref from a tree, and {@code returnTree}. The survival inventory's recipe-book
     * button, because clicking it moves it: the screen shifts right to make room for the book. So the ref is shown to
     * follow the widget rather than the place it was read at — the second click aims where the button is now — and the
     * tree that comes back with the first click is the one after it. Then the screen is reopened, which builds a new
     * button, and the old ref must be refused rather than land on it.
     */
    @Test
    void clientClickByRef(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            Map<String, Object> opened =
                    call(client, MC_CLIENT_INPUT_KEYBIND, Map.of(NAME, KEY_INVENTORY, RETURN_TREE, true));
            game.check(() -> assertEquals(true, opened.get(TREE_CHANGED), "opening the inventory changed the tree"));
            Map<?, ?> tree = opened.get(TREE) instanceof Map<?, ?> t ? t : Map.of();
            game.check(() -> assertEquals(
                    INVENTORY_SCREEN, tree.get(TYPE), "the tree that came back is the screen the keystroke opened"));
            Map<?, ?> button = nodeOfType(tree, "ImageButton");
            if (button == null) {
                fail("no ImageButton (the recipe-book toggle) in the inventory's tree: " + tree.get(NODES));
            }
            Object ref = button.get(REF);
            Object beforeX = boxX(button);

            Map<String, Object> toggled = call(client, MC_CLIENT_INPUT_CLICK, Map.of(REF, ref, RETURN_TREE, true));
            game.check(() -> assertEquals(DONE, toggled.get(OUTCOME), "a click by ref: " + toggled));
            game.check(() -> assertEquals(true, toggled.get("handled"), "the button took the click"));
            game.check(() -> assertEquals(true, toggled.get(TREE_CHANGED), "opening the book changed the tree"));
            Map<?, ?> moved = nodeWithRef(toggled.get(TREE) instanceof Map<?, ?> t ? t : Map.of(), ref);
            game.check(moved != null, "the same ref " + ref + " is in the tree after the click");
            game.check(() -> assertNotEquals(
                    beforeX, moved == null ? beforeX : boxX(moved), "the button's x after the book opened"));
            // Aimed where the button is now, not where the first tree put it: a click at the old point would miss it.
            Map<String, Object> back = call(client, MC_CLIENT_INPUT_CLICK, Map.of(REF, ref));
            game.check(() -> assertEquals(true, back.get("handled"), "the moved button took the second click too"));
            game.check(!back.containsKey(TREE_CHANGED), "no treeChanged without returnTree: " + back);

            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
            call(client, MC_CLIENT_INPUT_KEYBIND, Map.of(NAME, KEY_INVENTORY));
            Map<String, Object> stale = call(client, MC_CLIENT_INPUT_CLICK, Map.of(REF, ref));
            game.check(
                    () -> assertEquals("refused", stale.get(OUTCOME), "a ref from a screen since rebuilt: " + stale));
            game.check(() -> assertEquals("no_target", stale.get("reason"), "why a stale ref is refused"));
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        }
    }

    /**
     * {@code mc.client.screen.close} on a container screen closes the server's menu too, as Esc does. Left open there,
     * the menu outlives the screen, and the close the server sends once it goes stale shuts whatever screen the client
     * has open by then. A chest menu over a plain container, which the server never finds stale on its own.
     */
    @Test
    void clientCloseEndsTheServersMenu(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            player.runOnServer(p -> p.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) -> ChestMenu.threeRows(id, inventory), Component.literal("wd-close"))));
            until(
                    game,
                    client,
                    MC_CLIENT_SCREEN_INFO,
                    Map.of(),
                    i -> "ContainerScreen".equals(i.get(TYPE)),
                    100,
                    "the chest screen opens");
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
            game.await()
                    .within(40)
                    .as("the server's menu is the player's inventory again")
                    .until(() -> player.onServer(p -> p.containerMenu == p.inventoryMenu));

            // A screen a mod opens over the chest leaves the chest's menu open under it, on both sides.
            player.runOnServer(p -> p.openMenu(new SimpleMenuProvider(
                    (id, inventory, who) -> ChestMenu.threeRows(id, inventory), Component.literal("wd-close"))));
            until(
                    game,
                    client,
                    MC_CLIENT_SCREEN_INFO,
                    Map.of(),
                    i -> "ContainerScreen".equals(i.get(TYPE)),
                    100,
                    "the chest screen opens again");
            player.runOnServer(p -> p.sendSystemMessage(Component.literal(TestPanel.OPEN)));
            until(
                    game,
                    client,
                    MC_CLIENT_SCREEN_INFO,
                    Map.of(),
                    i -> "PanelScreen".equals(i.get(TYPE)),
                    100,
                    "the panel opens over the chest");
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
            game.await()
                    .within(40)
                    .as("closing the panel closes the chest's menu on the server too")
                    .until(() -> player.onServer(p -> p.containerMenu == p.inventoryMenu));
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
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
            Map<String, Object> after = until(
                    game,
                    client,
                    "mc.client.player",
                    Map.of(),
                    q -> selectedSlot(q) != 3,
                    40,
                    "the selected slot moves off 3");
            assertEquals(
                    4, selectedSlot(after), "the selected hotbar slot after one notch toward the user from slot 3");
        } finally {
            call(client, "mc.client.input.setHotbarSlot", Map.of("slot", 0));
        }
    }

    /**
     * {@code route: RAW} on key, click and typeText, which go through vanilla's own keyboard and mouse handlers instead
     * of straight to the screen. Each step is one a direct call cannot show it took: E with no screen opens the
     * inventory, a click on the recipe-book toggle moves it, T opens chat, typed characters land in its box, and ESCAPE
     * closes it. JEI's overlay, the reason for the route, is not in this pack; what it needs is that these events go
     * through those handlers at all.
     */
    @Test
    void clientRawInputThroughVanillaHandlers(Game game, Client client, Player player) {
        survival(game, client, player);
        Predicate<Map<String, Object>> noScreen = i -> Boolean.FALSE.equals(i.get("hasScreen"));
        try {
            Map<String, Object> e = call(client, MC_CLIENT_INPUT_KEY, Map.of(KEY, "E", ROUTE, RAW));
            game.check(() -> assertEquals(RAW, e.get("via"), "where key E with route raw went"));
            Map<String, Object> tree = until(
                    game,
                    client,
                    "mc.client.screen.tree",
                    Map.of(),
                    t -> INVENTORY_SCREEN.equals(t.get(TYPE)),
                    40,
                    "E opens the inventory");
            Map<?, ?> button = nodeOfType(tree, "ImageButton");
            if (button == null) {
                fail("no ImageButton (the recipe-book toggle) in the inventory's tree: " + tree.get(NODES));
            }
            Object ref = button.get(REF);
            Map<String, Object> clicked =
                    call(client, MC_CLIENT_INPUT_CLICK, Map.of(REF, ref, ROUTE, RAW, RETURN_TREE, true));
            game.check(
                    !clicked.containsKey("handled"), "no `handled` with route raw, which nothing answers: " + clicked);
            Map<?, ?> moved = nodeWithRef(clicked.get(TREE) instanceof Map<?, ?> t ? t : Map.of(), ref);
            game.check(() -> assertNotEquals(
                    boxX(button),
                    moved == null ? boxX(button) : boxX(moved),
                    "the toggle's x after a raw click opened the book"));

            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
            until(game, client, MC_CLIENT_SCREEN_INFO, Map.of(), noScreen, 40, "the inventory closes");
            call(client, MC_CLIENT_INPUT_KEY, Map.of(KEY, "T", ROUTE, RAW));
            until(
                    game,
                    client,
                    MC_CLIENT_SCREEN_INFO,
                    Map.of(),
                    s -> "ChatScreen".equals(s.get(TYPE)),
                    40,
                    "T opens chat");
            call(client, "mc.client.input.typeText", Map.of(TEXT, HI, ROUTE, RAW));
            Map<String, Object> read = call(client, "mc.client.input.replaceText", Map.of(TEXT, ""));
            game.check(() -> assertEquals(HI, read.get("previous"), "the chat box after typing hi with route raw"));
            call(client, MC_CLIENT_INPUT_KEY, Map.of(KEY, "ESCAPE", ROUTE, RAW));
            until(game, client, MC_CLIENT_SCREEN_INFO, Map.of(), noScreen, 40, "ESCAPE with route raw closes chat");
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        }
    }

    /**
     * {@code mc.client.input.typeText} and {@code replaceText} by a ref from a tree, and replaceText's
     * {@code returnTree}: chat's box takes the typed characters by ref, then is cleared by ref, and the tree that comes
     * back says so — the box's value is part of the tree, so clearing it changes it.
     */
    @Test
    void clientTextByRef(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            call(client, MC_CLIENT_INPUT_KEY, Map.of(KEY, "T"));
            Map<String, Object> tree = until(
                    game,
                    client,
                    "mc.client.screen.tree",
                    Map.of(),
                    t -> "ChatScreen".equals(t.get(TYPE)),
                    40,
                    "T opens chat");
            // By its value, not its type: chat's box is an anonymous EditBox, whose simple name is empty.
            Map<?, ?> box = nodeWithValue(tree);
            if (box == null) {
                fail("no text box in chat's tree: " + tree.get(NODES));
            }
            Object ref = box.get(REF);

            Map<String, Object> typed = call(client, "mc.client.input.typeText", Map.of(TEXT, HI, REF, ref));
            game.check(() -> assertEquals(DONE, typed.get(OUTCOME), "typeText by ref: " + typed));
            game.check(!typed.containsKey(TREE_CHANGED), "no treeChanged without returnTree: " + typed);

            Map<String, Object> cleared =
                    call(client, "mc.client.input.replaceText", Map.of(TEXT, "", REF, ref, RETURN_TREE, true));
            game.check(() -> assertEquals(DONE, cleared.get(OUTCOME), "replaceText by ref: " + cleared));
            game.check(() -> assertEquals(HI, cleared.get("previous"), "the box typeText by ref typed into"));
            game.check(() -> assertEquals(true, cleared.get(TREE_CHANGED), "clearing the box changed the tree"));
            Map<?, ?> after = nodeWithRef(cleared.get(TREE) instanceof Map<?, ?> t ? t : Map.of(), ref);
            game.check(() -> assertEquals(
                    "",
                    after == null ? "no node with ref " + ref : after.get(VALUE),
                    "the box's value in the tree that came back"));
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        }
    }

    /**
     * {@code mc.client.input.slider} by a ref from a tree, on the options screen's FOV slider, reached through the
     * pause menu by a click by ref. The slider is moved to the far end from where it stands and put back; a typeText
     * aimed at it by ref is refused, since it is not a text box. Every step answers on the client thread, so nothing
     * here waits on the integrated server the pause menu pauses.
     */
    @Test
    void clientSliderByRef(Game game, Client client, Player player) {
        survival(game, client, player);
        try {
            Map<String, Object> paused = call(client, MC_CLIENT_INPUT_KEY, Map.of(KEY, "ESCAPE", RETURN_TREE, true));
            Map<?, ?> pause = paused.get(TREE) instanceof Map<?, ?> t ? t : Map.of();
            game.check(() -> assertEquals("PauseScreen", pause.get(TYPE), "ESCAPE with no screen opens: " + paused));
            Map<?, ?> optionsButton = nodeWithKey(pause, "menu.options");
            if (optionsButton == null) {
                fail("no menu.options button in the pause menu's tree: " + pause.get(NODES));
            }
            Map<String, Object> opened =
                    call(client, MC_CLIENT_INPUT_CLICK, Map.of(REF, optionsButton.get(REF), RETURN_TREE, true));
            Map<?, ?> options = opened.get(TREE) instanceof Map<?, ?> t ? t : Map.of();
            game.check(() -> assertEquals("OptionsScreen", options.get(TYPE), "the options button opens: " + opened));

            Map<String, Object> read = call(client, "mc.client.input.slider", Map.of());
            Map<?, ?> first =
                    read.get("sliders") instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map<?, ?> m
                            ? m
                            : null;
            if (first == null) {
                fail("no slider on the options screen: " + read);
            }
            Map<?, ?> node = nodeWithMessage(options, first.get("label"));
            if (node == null) {
                fail("no node labelled " + first.get("label") + " in the options tree: " + options.get(NODES));
            }
            Object ref = node.get(REF);
            double was = ((Number) first.get(VALUE)).doubleValue();
            double to = was < 0.5 ? 1.0 : 0.0;

            Map<String, Object> set = call(client, "mc.client.input.slider", Map.of(REF, ref, "fraction", to));
            game.check(() -> assertEquals("set", set.get(OUTCOME), "slider by ref: " + set));
            game.check(() -> assertEquals(first.get("label"), set.get("previousLabel"), "the slider the ref named"));
            game.check(() -> assertEquals(
                    to,
                    set.get(VALUE) instanceof Number n ? n.doubleValue() : -1.0,
                    1e-9,
                    "the slider's value after setting it by ref"));
            Map<String, Object> back = call(client, "mc.client.input.slider", Map.of(REF, ref, "fraction", was));
            game.check(() -> assertEquals("set", back.get(OUTCOME), "the slider put back by ref: " + back));

            Map<String, Object> notText = call(client, "mc.client.input.typeText", Map.of(TEXT, "x", REF, ref));
            game.check(() -> assertEquals("refused", notText.get(OUTCOME), "typeText at a slider's ref: " + notText));
            game.check(() -> assertEquals("no_target", notText.get("reason"), "why typeText at a slider is refused"));
        } finally {
            call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        }
    }

    /**
     * A {@code player.hurt} event says what hurt the player, not just how much — the #55 permanent assertion. Without
     * attribution a fall into a self-dug pit and a mob bite are the same HP delta, so the combat chain and the agent
     * above it engage phantoms. {@code player.hurt} is emitted client-side, mirroring the server's DamageSource off
     * {@code ClientboundDamageEventPacket}, so it is read from the client's ring: the attribution has to survive the
     * network boundary to reach the agent.
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
        game.await()
                .within(200)
                .as("a player.hurt event reaches the client's ring")
                .until(() -> {
                    hurt.clear();
                    hurt.addAll(hurtPayloads(events, cursor));
                    return !hurt.isEmpty();
                });
        Map<?, ?> payload = hurt.get(0);
        game.check(
                payload.get("source") instanceof String src && !src.isEmpty(),
                "player.hurt attributes a source instead of reporting an HP delta alone: " + payload);
        game.check(
                payload.get("lost") instanceof Number n && n.doubleValue() > 0,
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

    /**
     * A chat line the client's player sends reaches the server driver as one {@code chat.message} carrying the text and
     * the sender. It is read from the server's ring, not the client's: the event is the server's report of chat it
     * received.
     */
    @Test
    void serverReportsTheChatTheClientSent(Game game, Client client, Player player) {
        alone(game);
        String marker = "wd-chat-" + Long.toHexString(System.nanoTime());
        EventBlockingApi events = game.api(EventBlockingApi::of);
        long cursor = events.cursor(new EventContracts.CursorParams()).cursor();
        Map<String, Object> sent = call(client, "mc.client.chat.send", Map.of(TEXT, marker));
        game.check(() -> assertEquals(DONE, sent.get(OUTCOME), "the client sent the line: " + sent));
        List<Map<?, ?>> seen = new ArrayList<>();
        game.await()
                .within(200)
                .as("a chat.message with the marker reaches the server's ring")
                .until(() -> {
                    seen.clear();
                    EventContracts.Page page =
                            events.eventsSince(EventContracts.SinceParams.after(cursor, "chat.message"));
                    for (EventContracts.Event e : page.events()) {
                        if (SceneJson.plain(e.data().map(JsonValue::json).orElse(null)) instanceof Map<?, ?> m
                                && marker.equals(m.get(TEXT))) {
                            seen.add(m);
                        }
                    }
                    return !seen.isEmpty();
                });
        game.ticks(5);
        EventContracts.Page page = events.eventsSince(EventContracts.SinceParams.after(cursor, "chat.message"));
        long count = page.events().stream()
                .map(e -> SceneJson.plain(e.data().map(JsonValue::json).orElse(null)))
                .filter(d -> d instanceof Map<?, ?> m && marker.equals(m.get(TEXT)))
                .count();
        game.check(() -> assertEquals(1L, count, "chat.message reports for the marker line"));
        game.check(() ->
                assertEquals(player.name(), seen.get(0).get("player"), "the chat.message sender: " + seen.get(0)));
    }

    /**
     * {@code mc.client.chat.history} with {@code actionBar} reads what the client showed above the hotbar, newest
     * first: a {@code /title … actionbar} line and a system message sent as an overlay, the two ways a server sets it.
     * A line a client mod sets itself goes through the same GUI call, where the line is kept, but no test here can make
     * one: the client is reached only through its registry. The chat history reads neither: an action bar a mod sets
     * every tick must not push the chat out.
     */
    @Test
    void clientKeepsTheActionBar(Game game, Client client, Player player) {
        alone(game);
        ClientBlockingApi api = client.api(ClientBlockingApi::of);
        String marker = "wd-bar-" + Long.toHexString(System.nanoTime());
        long since = api.chatHistory(new ClientContracts.ChatHistoryParams(1, 0, true))
                .nextSeq();
        final long chatSince =
                api.chatHistory(new ClientContracts.ChatHistoryParams(1, 0)).nextSeq();
        EventBlockingApi events = client.api(EventBlockingApi::of);
        final long cursor = events.cursor(new EventContracts.CursorParams()).cursor();
        player.runOnServer(
                p -> command(p.server, "title " + p.getScoreboardName() + " actionbar \"" + marker + "-title\""));
        player.runOnServer(
                p -> p.displayClientMessage(net.minecraft.network.chat.Component.literal(marker + "-overlay"), true));
        ClientContracts.ChatHistoryParams bar = new ClientContracts.ChatHistoryParams(256, since, true);
        game.await()
                .within(100)
                .as("both action bar lines reach the client")
                .until(() -> api.chatHistory(bar).messages().stream()
                                .filter(l -> l.text().startsWith(marker))
                                .count()
                        >= 2);
        List<ClientContracts.HistoryLine> lines = api.chatHistory(bar).messages().stream()
                .filter(l -> l.text().startsWith(marker))
                .toList();
        game.check(() -> assertEquals(
                List.of(marker + "-overlay", marker + "-title"),
                lines.stream().map(ClientContracts.HistoryLine::text).toList(),
                "the action bar lines, newest first"));
        game.check(() -> assertEquals(
                List.of(ClientContracts.ChatKind.ACTION_BAR, ClientContracts.ChatKind.ACTION_BAR),
                lines.stream().map(ClientContracts.HistoryLine::kind).toList(),
                "their kind"));
        game.check(() -> assertEquals(
                List.of(),
                api.chatHistory(new ClientContracts.ChatHistoryParams(256, chatSince)).messages().stream()
                        .filter(l -> l.text().startsWith(marker))
                        .toList(),
                "the chat history holds neither"));
        // The client.actionBar event is drawn from the same lines: each change of text is one.
        List<Object> barEvents = new ArrayList<>();
        game.await().within(100).as("a client.actionBar event for each line").until(() -> {
            barEvents.clear();
            events.eventsSince(EventContracts.SinceParams.after(cursor, "client.actionBar")).events().stream()
                    .map(e -> SceneJson.plain(e.data().map(JsonValue::json).orElse(null)))
                    .filter(d -> d instanceof Map<?, ?> m
                            && String.valueOf(m.get(TEXT)).startsWith(marker))
                    .map(d -> ((Map<?, ?>) d).get(TEXT))
                    .forEach(barEvents::add);
            return barEvents.size() >= 2;
        });
        game.check(() -> assertEquals(
                List.of(marker + "-title", marker + "-overlay"), barEvents, "the events, in the order shown"));
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
        call(client, MC_CLIENT_SCREEN_CLOSE, Map.of());
        until(
                game,
                client,
                "mc.client.player",
                Map.of(),
                p -> "survival".equals(p.get("gameMode")),
                100,
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
    private static Map<String, Object> until(
            Game game,
            Client client,
            String method,
            Map<String, ?> params,
            Predicate<Map<String, Object>> done,
            int ticks,
            String what) {
        Map<String, Object> last = Map.of();
        for (int t = 0; t <= ticks; t++) {
            last = call(client, method, params);
            if (done.test(last)) {
                return last;
            }
            game.ticks(1);
        }
        return fail(what + " within " + ticks + " ticks; " + method + " last answered " + last);
    }

    private static void command(net.minecraft.server.MinecraftServer server, String command) {
        server.getCommands()
                .performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
    }

    /** The {@code player.hurt} payloads emitted since {@code cursor}, oldest first. */
    private static List<Map<?, ?>> hurtPayloads(EventBlockingApi events, long cursor) {
        EventContracts.Page page = events.eventsSince(EventContracts.SinceParams.after(cursor, "player.hurt"));
        List<Map<?, ?>> out = new ArrayList<>();
        for (EventContracts.Event e : page.events()) {
            if (SceneJson.plain(e.data().map(JsonValue::json).orElse(null)) instanceof Map<?, ?> m) {
                out.add(m);
            }
        }
        return out;
    }

    /** {@code inventory.selectedSlot} of a {@code mc.client.player} reply, -1 when it has none. */
    private static int selectedSlot(Map<?, ?> clientPlayer) {
        return clientPlayer.get("inventory") instanceof Map<?, ?> inv && inv.get("selectedSlot") instanceof Number n
                ? n.intValue()
                : -1;
    }

    private static Map<?, ?> nodeOfType(Map<?, ?> tree, String type) {
        if (tree.get(NODES) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> n && type.equals(n.get(TYPE))) {
                    return n;
                }
            }
        }
        return null;
    }

    private static Map<?, ?> nodeWithRef(Map<?, ?> tree, Object ref) {
        if (tree.get(NODES) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> n
                        && ref != null
                        && String.valueOf(ref).equals(String.valueOf(n.get(REF)))) {
                    return n;
                }
            }
        }
        return null;
    }

    /** The first node with a {@code value}, which only a text box has. */
    private static Map<?, ?> nodeWithValue(Map<?, ?> tree) {
        if (tree.get(NODES) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> n && n.containsKey(VALUE)) {
                    return n;
                }
            }
        }
        return null;
    }

    private static Map<?, ?> nodeWithKey(Map<?, ?> tree, String key) {
        if (tree.get(NODES) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> n && key.equals(n.get(KEY))) {
                    return n;
                }
            }
        }
        return null;
    }

    private static Map<?, ?> nodeWithMessage(Map<?, ?> tree, Object message) {
        if (tree.get(NODES) instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> n && message != null && message.equals(n.get("message"))) {
                    return n;
                }
            }
        }
        return null;
    }

    private static Object boxX(Map<?, ?> node) {
        return node.get("box") instanceof Map<?, ?> b && b.get("x") instanceof Number n ? n.intValue() : "no box";
    }

    /** What {@code mc.client.input.keybind}'s listing says about one mapping's {@code down}. */
    private static Object keybindDown(Map<?, ?> listing, String name) {
        if (listing.get("keybinds") instanceof List<?> l) {
            for (Object row : l) {
                if (row instanceof Map<?, ?> k && name.equals(k.get(NAME))) {
                    return k.get("down");
                }
            }
        }
        return "no " + name + " row in the keybind listing";
    }
}
