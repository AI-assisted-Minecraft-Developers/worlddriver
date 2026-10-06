package net.magicterra.worlddriver.api.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.methods.ClientContracts.ClientRefusal;
import net.magicterra.worlddriver.api.methods.ClientContracts.Refused;
import net.magicterra.worlddriver.api.methods.ClientContracts.Wear;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.ScreenContracts.ClickKind;
import net.magicterra.worlddriver.api.methods.ScreenContracts.KeyAction;
import net.magicterra.worlddriver.api.methods.ScreenContracts.KeyRoute;
import net.magicterra.worlddriver.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

/** The client verbs: what a request may say, and what done, refused and the two container sources look like. */
class ClientContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonCodec.decode(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json)).error().issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    private static <R> String encode(MethodSpec<?, R> spec, R value) {
        return JsonCodec.encode(spec.output().encode(value));
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
    void writesAnswerDoneOrRefused() {
        assertEquals("{\"outcome\":\"done\",\"handled\":true,\"contextId\":\"x-1\"}",
                encode(ScreenContracts.CLICK, new ScreenContracts.Clicked(true, "x-1")));
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
    void aClientBlockScanKeepsTheShapeTheQueryFallbackReads() {
        Object tree = ClientContracts.BLOCK_SCAN.encode(new ClientContracts.BlockScan(
                List.of(new ClientContracts.BlockRow(new BlockPosition(0, 64, 0), "minecraft:oak_stairs",
                        Optional.of(Map.of("facing", "north")))),
                new BlockPosition(0, 64, 0), 1, Optional.of(List.of(new ClientContracts.ChunkRef(3, -2)))));
        Map<?, ?> m = (Map<?, ?>) tree;
        assertTrue(m.get("blocks") instanceof List<?>);
        assertEquals("{\"x\":3,\"z\":-2}", JsonCodec.encode(((List<?>) m.get("unloaded")).get(0)));
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
