package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.TestJson;
import net.magicterra.worlddriver.api.schema.JsonValue;
import net.magicterra.worlddriver.api.game.DriverEvent;
import net.magicterra.worlddriver.api.game.EventWire;
import net.magicterra.worlddriver.api.methods.event.EventContracts;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * {@code DriverEvent.data} must reach the wire as a typed value.
 *
 * <p>It was declared {@code String}, so every structured emitter pre-encoded its map
 * to JSON text and the payload shipped as JSON escaped inside a JSON string. It is a
 * {@link JsonValue} built from a Gson tree now, so a payload handed over as text no
 * longer compiles. The field was then an undiscriminated union — scalar payloads
 * ({@code block.place} carries a block id) and documents ({@code time.phase}
 * carries {@code {phase, dayTime}}) were both just strings, and a consumer had to
 * know the event type to guess which. Journeyman guessed structurally with
 * {@code isinstance(d, dict)}; that was never true, so its dusk interrupt was dead
 * code for every {@code time.phase} event.
 */
class DriverEventWireTest {

    private static Map<?, ?> encodeThenDecode(DriverEvent e) {
        Object decoded = TestJson.plain(String.valueOf(EventContracts.EVENT.encode(EventWire.dto(e))));
        return assertInstanceOf(Map.class, decoded);
    }

    private static DriverEvent event(long seq, String type, BlockPos pos, Object data) {
        return new DriverEvent(seq, type, pos, JsonValue.of(TestJson.json(data)));
    }

    @Test
    void structuredPayloadArrivesAsAnObject() {
        DriverEvent e = event(1, "time.phase", new BlockPos(1, 2, 3),
                Map.of("phase", "sunset", "dayTime", 12800L));
        Map<?, ?> wire = encodeThenDecode(e);

        Object data = wire.get("data");
        assertInstanceOf(Map.class, data, "data must be an object, not JSON inside a string");
        assertEquals("sunset", ((Map<?, ?>) data).get("phase"));
        // The regression in one line: this is Journeyman's actual dusk-interrupt test.
        assertFalse(data instanceof String, "a String here silently disables consumers "
                + "that branch on the payload being structured");
    }

    @Test
    void scalarPayloadStaysAString() {
        // block.place / entity.death carry a bare id and must keep doing so —
        // Journeyman compares e["data"] == "minecraft:player" directly.
        DriverEvent e = event(2, "entity.death", null, "minecraft:player");
        assertEquals("minecraft:player", encodeThenDecode(e).get("data"));
    }

    @Test
    void nestedAndListPayloadsSurvive() {
        DriverEvent e = event(3, "wait.done", null,
                Map.of("result", Map.of("ok", true), "seen", List.of(1L, 2L)));
        Map<?, ?> data = assertInstanceOf(Map.class, encodeThenDecode(e).get("data"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) data.get("result")).get("ok"));
        assertEquals(2, ((List<?>) data.get("seen")).size());
    }

    @Test
    void absentPayloadAndPositionAreOmittedNotNull() {
        DriverEvent e = new DriverEvent(5, "x", null, JsonValue.NULL);
        Map<?, ?> wire = encodeThenDecode(e);
        assertFalse(wire.containsKey("data"), wire.toString());
        assertFalse(wire.containsKey("pos"), wire.toString());
    }

    @Test
    void envelopeFieldsKeepTheirDeclaredTypes() {
        DriverEvent e = event(9, "block.place", new BlockPos(4, 5, 6), "minecraft:stone");
        Map<?, ?> wire = encodeThenDecode(e);
        assertEquals("9", wire.get("seq"), "seq is a 64-bit number, so it travels as a decimal string");
        assertEquals("block.place", wire.get("type"));
        Map<?, ?> pos = assertInstanceOf(Map.class, wire.get("pos"));
        assertEquals(4L, pos.get("x"));
        assertEquals(6L, pos.get("z"));
        assertInstanceOf(Long.class, wire.get("timestamp"), "a timestamp stays a number");
    }

    /** 2^53 + 1 is the first seq a JavaScript number would round. */
    @Test
    void aSeqPastJavaScriptPrecisionArrivesExact() {
        DriverEvent e = event(9_007_199_254_740_993L, "x", null, "y");
        assertEquals("9007199254740993", encodeThenDecode(e).get("seq"));
        Map<?, ?> frame = assertInstanceOf(Map.class, TestJson.plain(EventNotifications.frame(EventWire.dto(e))));
        assertEquals("9007199254740993", ((Map<?, ?>) ((Map<?, ?>) frame.get("params")).get("data")).get("seq"));
    }

    /** An entity id inside a payload is a 32-bit int: only seq is stringified, never every number. */
    @Test
    void numbersInsideThePayloadStayNumbers() {
        DriverEvent e = event(4, "entity.death", null, Map.of("id", 123, "health", 2.5));
        Map<?, ?> data = assertInstanceOf(Map.class, encodeThenDecode(e).get("data"));
        assertEquals(123L, data.get("id"));
        assertInstanceOf(Number.class, data.get("health"));
    }

    @Test
    void notificationFrameCarriesTheSameTypedPayload() {
        // The push channel wraps the event as MCP notifications/message; the payload
        // must not be re-flattened on the way through.
        DriverEvent e = event(6, "threat.appeared", new BlockPos(0, 64, 0),
                Map.of("type", "minecraft:zombie", "dist", 7L));
        Map<?, ?> frame = assertInstanceOf(Map.class,
                TestJson.plain(EventNotifications.frame(EventWire.dto(e))));
        Map<?, ?> params = assertInstanceOf(Map.class, frame.get("params"));
        Map<?, ?> event = assertInstanceOf(Map.class, params.get("data"));
        Map<?, ?> payload = assertInstanceOf(Map.class, event.get("data"),
                "the event's own data must stay an object inside the notification");
        assertEquals("minecraft:zombie", payload.get("type"));
        assertEquals("notifications/message", frame.get("method"));
    }
}
