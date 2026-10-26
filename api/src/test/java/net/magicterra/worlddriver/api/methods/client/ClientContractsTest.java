package net.magicterra.worlddriver.api.methods.client;

import com.google.gson.JsonObject;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.Wear;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.ClickKind;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.screen.ScreenContracts.KeyRoute;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

/** The client verbs: what a request may say, and what done, refused and the two container sources look like. */
class ClientContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonParser.parseString(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json)).error().issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    private static <R> String encode(MethodSpec<?, R> spec, R value) {
        return String.valueOf(spec.output().encode(value));
    }

    @Test
    void aKeyDefaultsToAClickRoutedAsVanillaWould() {
        ScreenContracts.KeyParams p = decode(ScreenContracts.KEY.input(), "{\"key\":\"e\"}");
        assertEquals(KeyAction.CLICK, p.action());
        assertEquals(KeyRoute.AUTO, p.route());
        assertEquals(List.of(), p.modifiers());
        assertEquals("/action", issue(ScreenContracts.KEY.input(), "{\"key\":\"E\",\"action\":\"smash\"}").path());
        assertEquals("/modifiers/0", issue(ScreenContracts.KEY.input(), "{\"key\":\"E\",\"modifiers\":[\"hyper\"]}").path());
    }

    @Test
    void aSlotClickNamesItsClickTypeAndMayBeHeldToAContext() {
        ScreenContracts.SlotClickParams p = decode(ScreenContracts.SLOT_CLICK.input(),
                "{\"slot\":3,\"type\":\"quickMove\",\"contextId\":\"abc-7\"}");
        assertEquals(ClickKind.QUICK_MOVE, p.type());
        assertEquals(Optional.of("abc-7"), p.contextId());
        assertEquals("/type", issue(ScreenContracts.SLOT_CLICK.input(), "{\"slot\":0,\"type\":\"wiggle\"}").path());
        assertEquals("/slot", issue(ScreenContracts.SLOT_CLICK.input(), "{}").path());
    }

    @Test
    void aSliderIsPickedOneWay() {
        assertEquals("/index", issue(ScreenContracts.SLIDER.input(), "{\"match\":\"fov\",\"index\":1}").path());
        assertEquals("/fraction", issue(ScreenContracts.SLIDER.input(), "{\"fraction\":1.5}").path());
    }

    @Test
    void aClickNamesARefOrAPointAndNeverBoth() {
        ScreenContracts.ClickParams byRef = decode(ScreenContracts.CLICK.input(), "{\"ref\":12}");
        assertEquals(Optional.of(12), byRef.ref());
        assertEquals(false, byRef.returnTree());
        ScreenContracts.ClickParams byPoint = decode(ScreenContracts.CLICK.input(), "{\"x\":10,\"y\":20,\"returnTree\":true}");
        assertEquals(Optional.of(10.0), byPoint.x());
        assertEquals(true, byPoint.returnTree());
        assertEquals("/ref", issue(ScreenContracts.CLICK.input(), "{\"ref\":1,\"x\":10,\"y\":20}").path());
        assertEquals("/y", issue(ScreenContracts.CLICK.input(), "{\"x\":10}").path());
        assertEquals("/x", issue(ScreenContracts.CLICK.input(), "{}").path());
    }

    @Test
    void aTreeNodeIsNamedByItsRefAndPointsAtItsHoldersRef() {
        ScreenContracts.WidgetNode slot = new ScreenContracts.WidgetNode(41, Optional.of(40), 1, "Slot", Optional.of(0),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ScreenContracts.ScreenTree tree = new ScreenContracts.ScreenTree("x-1", true, Optional.of("InventoryScreen"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(slot));
        assertEquals("{\"contextId\":\"x-1\",\"hasScreen\":true,\"type\":\"InventoryScreen\","
                        + "\"nodes\":[{\"ref\":41,\"parent\":40,\"depth\":1,\"type\":\"Slot\",\"index\":0}]}",
                String.valueOf(ScreenContracts.SCREEN_TREE.encode(tree)));
    }

    @Test
    void writesAnswerDoneOrRefused() {
        assertEquals("{\"outcome\":\"done\",\"handled\":true,\"contextId\":\"x-1\"}",
                encode(ScreenContracts.CLICK, new ClientContracts.Clicked(Optional.of(true), Optional.empty(), Optional.empty(), "x-1")));
        assertEquals("{\"outcome\":\"done\",\"handled\":true,\"treeChanged\":false,\"contextId\":\"x-1\"}",
                encode(ScreenContracts.CLICK, new ClientContracts.Clicked(Optional.of(true), Optional.of(false), Optional.empty(), "x-1")));
        assertEquals("{\"outcome\":\"done\",\"contextId\":\"x-1\"}",
                encode(ScreenContracts.CLICK, new ClientContracts.Clicked(Optional.empty(), Optional.empty(), Optional.empty(), "x-1")));
        assertEquals("{\"outcome\":\"refused\",\"reason\":\"no_screen\",\"message\":\"no screen is open\"}",
                encode(ScreenContracts.CLICK, new Refused(ClientRefusal.NO_SCREEN, "no screen is open")));
    }

    @Test
    void aContainerSaysWhereItWasRead() {
        ScreenContracts.SlotRow diamond = new ScreenContracts.SlotRow(0, Optional.of("minecraft:diamond"), Optional.of(5), Wear.NONE);
        String block = encode(ScreenContracts.CONTAINER, new ScreenContracts.BlockContainer(new BlockPosition(1, 2, 3), true,
                Optional.of("minecraft:chest"), Optional.of(List.of(diamond, ScreenContracts.SlotRow.empty(1)))));
        assertEquals("{\"source\":\"block\",\"pos\":{\"x\":1,\"y\":2,\"z\":3},\"present\":true,"
                + "\"blockEntity\":\"minecraft:chest\",\"slots\":[{\"index\":0,\"id\":\"minecraft:diamond\",\"count\":5},"
                + "{\"index\":1}]}", block);
        String menu = encode(ScreenContracts.CONTAINER,
                new ScreenContracts.MenuContainer("x-2", false, Optional.empty(), Optional.empty()));
        assertEquals("{\"source\":\"menu\",\"contextId\":\"x-2\",\"present\":false}", menu);
    }

    @Test
    void aServerPlayersMenuIsNamedByBotAndNeverWithPos() {
        assertEquals(Optional.of("player:menubot"),
                decode(ScreenContracts.CONTAINER.input(), "{\"bot\":\"player:menubot\"}").bot());
        ApiIssue both = issue(ScreenContracts.CONTAINER.input(),
                "{\"pos\":{\"x\":1,\"y\":2,\"z\":3},\"bot\":\"player:menubot\"}");
        assertEquals("/bot", both.path());
        assertEquals(ApiIssue.Code.INVALID_VALUE, both.code());
        ApiIssue mob = issue(ScreenContracts.CONTAINER.input(), "{\"bot\":\"mob:cow\"}");
        assertEquals("/bot", mob.path());
        assertEquals(ApiIssue.Code.INVALID_VALUE, mob.code());

        ScreenContracts.SlotClickParams click = decode(ScreenContracts.SLOT_CLICK.input(),
                "{\"slot\":0,\"bot\":\"player:menubot\"}");
        assertEquals(Optional.of("player:menubot"), click.bot());
        assertEquals("/bot", issue(ScreenContracts.SLOT_CLICK.input(), "{\"slot\":0,\"bot\":\"mob:cow\"}").path());
        assertEquals("/returnTree", issue(ScreenContracts.SLOT_CLICK.input(),
                "{\"slot\":0,\"bot\":\"player:menubot\",\"returnTree\":true}").path());
        assertEquals("/contextId", issue(ScreenContracts.SLOT_CLICK.input(),
                "{\"slot\":0,\"bot\":\"player:menubot\",\"contextId\":\"x-1\"}").path());
    }

    @Test
    void aPlayerMenuEncodesWithAndWithoutItsTypeAndCursor() {
        ScreenContracts.SlotRow diamond = new ScreenContracts.SlotRow(0, Optional.of("minecraft:diamond"), Optional.of(5), Wear.NONE);
        String chest = encode(ScreenContracts.CONTAINER, new ScreenContracts.PlayerMenu("player:b",
                Optional.of("minecraft:generic_9x3"), 2, 7, List.of(diamond, ScreenContracts.SlotRow.empty(1)),
                Optional.of(new ScreenContracts.SlotRow(0, Optional.of("minecraft:dirt"), Optional.of(1), Wear.NONE))));
        assertEquals("{\"source\":\"player\",\"bot\":\"player:b\",\"menuType\":\"minecraft:generic_9x3\","
                + "\"containerId\":2,\"stateId\":7,\"slots\":[{\"index\":0,\"id\":\"minecraft:diamond\",\"count\":5},"
                + "{\"index\":1}],\"carried\":{\"index\":0,\"id\":\"minecraft:dirt\",\"count\":1}}", chest);
        String own = encode(ScreenContracts.CONTAINER, new ScreenContracts.PlayerMenu("player:b",
                Optional.empty(), 0, 0, List.of(), Optional.empty()));
        assertEquals("{\"source\":\"player\",\"bot\":\"player:b\",\"containerId\":0,\"stateId\":0,\"slots\":[]}", own);
    }

    @Test
    void aServerSideSlotClickAnswersWithoutAScreenContext() {
        assertEquals("{\"outcome\":\"done\",\"slot\":0,\"button\":0,\"type\":\"quickMove\"}",
                encode(ScreenContracts.SLOT_CLICK, new ClientContracts.SlotClicked(0, 0, ClickKind.QUICK_MOVE,
                        Optional.empty(), Optional.empty(), Optional.empty())));
        assertEquals("{\"outcome\":\"done\",\"slot\":0,\"button\":0,\"type\":\"pickup\",\"contextId\":\"x-1\"}",
                encode(ScreenContracts.SLOT_CLICK, new ClientContracts.SlotClicked(0, 0, ClickKind.PICKUP,
                        Optional.empty(), Optional.empty(), Optional.of("x-1"))));
    }

    @Test
    void aClientBlockScanKeepsTheShapeTheQueryFallbackReads() {
        JsonObject m = ClientContracts.BLOCK_SCAN.encode(new ClientContracts.BlockScan(
                List.of(new ClientContracts.BlockRow(new BlockPosition(0, 64, 0), "minecraft:oak_stairs",
                        Optional.of(Map.of("facing", "north")))),
                new BlockPosition(0, 64, 0), 1, Optional.of(List.of(new ClientContracts.ChunkRef(3, -2))))).getAsJsonObject();
        assertTrue(m.get("blocks").isJsonArray());
        assertEquals("{\"x\":3,\"z\":-2}", m.getAsJsonArray("unloaded").get(0).toString());
    }

    @Test
    void chatSendIsAWaitingWriteAndTheReadsArePollable() {
        assertTrue(ClientContracts.CHAT_SEND.blocking() == MethodSpec.Blocking.MAY_WAIT);
        assertTrue(ScreenContracts.INFO.pollable());
        assertTrue(ScreenContracts.CONTAINER.pollable());
        assertTrue(ClientContracts.PLAYER.pollable());
        assertEquals("/text", issue(ClientContracts.CHAT_SEND.input(), "{}").path());
        assertEquals("/awaitReplyMs", issue(ClientContracts.CHAT_SEND.input(), "{\"text\":\"hi\",\"awaitReplyMs\":30001}").path());
    }
}
