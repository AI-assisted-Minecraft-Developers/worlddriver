package net.magicterra.worlddriver.bot.stagewright.jupiter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.magicterra.stagewright.test.Client;
import net.magicterra.stagewright.test.Game;
import net.magicterra.stagewright.test.Logs;
import net.magicterra.stagewright.test.Player;
import net.magicterra.stagewright.test.Requires;
import net.magicterra.stagewright.test.Scene;
import net.magicterra.stagewright.test.methods.AdvancementsContracts;
import net.magicterra.stagewright.test.methods.LogsContracts;
import net.magicterra.stagewright.test.methods.TestQueriesBlockingApi;
import net.magicterra.stagewright.test.methods.ToastsContracts;
import net.magicterra.worlddriver.api.methods.client.ClientBlockingApi;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenBlockingApi;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.NoParams;
import net.magicterra.worlddriver.testcontent.TestClientLog;
import net.magicterra.worlddriver.testcontent.TestPanel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/** The client a test reaches through Client: what it sees, and input it sends that the server receives. */
@Scene("wd.jupiterSmoke")
@Requires(Requires.Capability.CLIENT)
class JupiterClientTest {

    private static final String MINE_STONE = "minecraft:story/mine_stone";

    @Test
    void theClientSeesTheServersPlayerAsItsOwn(Client client, Player player) {
        ClientContracts.ClientPlayer seen = client.api(ClientBlockingApi::of).player(new NoParams());
        assertTrue(seen.present(), "a player is in the client's world");
        assertEquals(Optional.of(player.name()), seen.name());
        ScreenContracts.ScreenInfo screen = client.api(ScreenBlockingApi::of).screenInfo(new NoParams());
        assertTrue(screen.worldOpen(), "the client has the world open");
    }

    @Test
    void anAdvancementTheServerGrantsShowsItsToastOnTheClient(Game game, Client client, Player player) {
        TestQueriesBlockingApi queries = game.api(TestQueriesBlockingApi::of);
        String ref = "player:" + player.name();
        // Taken back first: an advancement already done is granted again without a toast.
        queries.advancements(new AdvancementsContracts.Revoke(ref, MINE_STONE));
        try {
            queries.advancements(new AdvancementsContracts.Grant(ref, MINE_STONE));
            TestQueriesBlockingApi seen = client.api(TestQueriesBlockingApi::of);
            ToastsContracts.Toast[] shown = new ToastsContracts.Toast[1];
            game.await().within(100).as("the client shows an advancement toast").until(() -> {
                var list = assertInstanceOf(
                        ToastsContracts.ToastList.class, seen.toasts(new ToastsContracts.ListToasts()));
                shown[0] = list.visible().stream()
                        .filter(t -> t.type().endsWith(".AdvancementToast"))
                        .findFirst()
                        .orElse(null);
                return shown[0] != null;
            });
            assertTrue(shown[0].title().filter(t -> !t.isBlank()).isPresent(), "the toast has the advancement's title");
            assertTrue(shown[0].message().filter(t -> !t.isBlank()).isPresent(), "and its description");
        } finally {
            queries.advancements(new AdvancementsContracts.Revoke(ref, MINE_STONE));
        }
    }

    @Test
    void eachSidesWarningsAreReadFromItsOwnLog(Game game, Client client, Player player) {
        Logs server = Logs.of(game.registry());
        Logs joined = Logs.of(client.registry());
        long serverMark = server.mark();
        long clientMark = joined.mark();
        player.onServer(p -> {
            LogManager.getLogger(TestClientLog.PREFIX).warn("the server warns");
            // The client logs the error itself, on receiving this (TestClientLog).
            p.sendSystemMessage(Component.literal(TestClientLog.PREFIX + ":the client fails"));
            return null;
        });
        List<List<LogsContracts.Line>> fromClient = new ArrayList<>(List.of(List.of()));
        game.await().within(100).as("the client logs the error").until(() -> {
            fromClient.set(0, joined.since(clientMark, LogsContracts.Level.ERROR, TestClientLog.PREFIX));
            return !fromClient.get(0).isEmpty();
        });

        List<LogsContracts.Line> fromServer = server.since(serverMark, LogsContracts.Level.WARN, TestClientLog.PREFIX);
        // An integrated server's log is the client's: the two are one JVM.
        if (game.onServerHolds(MinecraftServer::isDedicatedServer)) {
            assertEquals(
                    List.of("the server warns"),
                    fromServer.stream().map(LogsContracts.Line::message).toList(),
                    "the server's log has its own warning, not the client's error");
        }
        List<LogsContracts.Line> errors = fromClient.get(0);
        assertEquals(1, errors.size(), errors::toString);
        assertEquals("the client fails", errors.get(0).message());
        assertEquals(
                Optional.of("java.lang.IllegalStateException: on purpose"),
                errors.get(0).thrown());
        assertEquals(List.of(), joined.since(clientMark, LogsContracts.Level.ERROR, "wd.nobodyLogsHere"));
    }

    @Test
    @ResourceLock("wd.clientScreen")
    void aWidgetsOwnElementsAreListedAndTakeInputByRef(Game game, Client client, Player player) {
        ScreenBlockingApi screen = client.api(ScreenBlockingApi::of);
        player.onServer(p -> {
            p.sendSystemMessage(Component.literal(TestPanel.OPEN));
            return null;
        });
        game.await()
                .within(100)
                .as("the client opens the panel")
                .until(() -> screen.screenTree(new NoParams()).type().equals(Optional.of("PanelScreen")));
        try {
            ScreenContracts.ScreenTree tree = screen.screenTree(new NoParams());
            ScreenContracts.WidgetNode panel = node(tree, "TestPanel");
            ScreenContracts.WidgetNode tabB = node(tree, "Tab B");
            assertEquals(Optional.of(panel.ref()), tabB.parent(), "an element is listed under its widget");
            assertEquals(Optional.of(new ScreenContracts.Box(84, 30, 50, 16)), tabB.box());
            assertEquals(Optional.of(false), tabB.selected());
            ScreenContracts.WidgetNode close = node(tree, "Close");
            assertEquals(Optional.of(node(tree, "Overlay").ref()), close.parent(), "and one inside another under it");

            assertInstanceOf(ClientContracts.Refused.class, click(screen, close), "a hidden element takes no click");
            assertInstanceOf(ClientContracts.Clicked.class, click(screen, tabB));
            assertInstanceOf(ClientContracts.Clicked.class, click(screen, node(tree, "Open overlay")));
            tree = screen.screenTree(new NoParams());
            assertEquals(Optional.of(true), node(tree, "Tab B").selected(), "the click reached the tab");
            assertEquals(Optional.of(true), node(tree, "Overlay").visible(), "and the overlay opened");
            assertInstanceOf(ClientContracts.Clicked.class, click(screen, node(tree, "Close")));
            assertEquals(
                    Optional.of(false),
                    node(screen.screenTree(new NoParams()), "Overlay").visible());

            int field = node(tree, "Name").ref();
            assertInstanceOf(
                    ClientContracts.Typed.class,
                    screen.typeText(new ScreenContracts.TypeParams(
                            "guild", ScreenContracts.InputRoute.SCREEN, Optional.of(field), false, Optional.empty())));
            assertEquals(
                    Optional.of("guild"),
                    node(screen.screenTree(new NoParams()), "Name").value());
        } finally {
            screen.closeScreen(new ScreenContracts.CloseParams(Optional.empty()));
        }
    }

    private static ScreenContracts.WidgetNode node(ScreenContracts.ScreenTree tree, String label) {
        return tree.nodes().stream()
                .filter(n -> n.message().equals(Optional.of(label)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node labelled " + label + " in " + tree.nodes()));
    }

    private static ClientContracts.ClientReply click(ScreenBlockingApi screen, ScreenContracts.WidgetNode node) {
        return screen.click(new ScreenContracts.ClickParams(
                Optional.of(node.ref()),
                Optional.empty(),
                Optional.empty(),
                0,
                ScreenContracts.InputRoute.SCREEN,
                false,
                Optional.empty()));
    }

    @Test
    @ResourceLock("wd.clientHotbar")
    void aHotbarSlotPickedOnTheClientReachesTheServer(Game game, Client client, Player player) {
        ScreenBlockingApi screen = client.api(ScreenBlockingApi::of);
        try {
            screen.setHotbarSlot(new ScreenContracts.HotbarParams(3, Optional.empty()));
            game.await()
                    .within(40)
                    .as("the server sees slot 3 selected")
                    .until(() -> player.<Integer>onServer(p -> p.getInventory().selected) == 3);
        } finally {
            screen.setHotbarSlot(new ScreenContracts.HotbarParams(0, Optional.empty()));
        }
    }

    @Test
    @ResourceLock("wd.clientLook")
    void aRotationTheServerTeleportsTheClientToIsKept(Game game, Player player) {
        turnTo(player, 0F, 0F);
        game.ticks(20);
        // Further than the camera clamp turns in a tick, which took one step towards it and stayed there.
        turnTo(player, 180F, 28F);
        game.ticks(40);
        float[] look = player.onServer(p -> new float[] {p.getYRot(), p.getXRot()});
        assertEquals(0F, Mth.degreesDifference(180F, look[0]), 0.01F, "yaw " + look[0]);
        assertEquals(28F, look[1], 0.01F, "pitch " + look[1]);
    }

    private static void turnTo(Player player, float yaw, float pitch) {
        player.onServer(p -> {
            p.teleportTo(p.serverLevel(), p.getX(), p.getY(), p.getZ(), yaw, pitch);
            return null;
        });
    }
}
