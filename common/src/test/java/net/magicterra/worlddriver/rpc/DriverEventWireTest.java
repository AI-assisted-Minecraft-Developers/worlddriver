package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.model.DriverEvent;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DriverEvent.data} must reach the wire as a typed value.
 *
 * <p>It was declared {@code String}, so every structured emitter pre-encoded with
 * {@code JsonCodec.encode(map)} and the payload shipped as JSON escaped inside a
 * JSON string. The field was then an undiscriminated union — scalar payloads
 * ({@code block.place} carries a block id) and documents ({@code time.phase}
 * carries {@code {phase, dayTime}}) were both just strings, and a consumer had to
 * know the event type to guess which. Journeyman guessed structurally with
 * {@code isinstance(d, dict)}; that was never true, so its dusk interrupt was dead
 * code for every {@code time.phase} event.
 */
class DriverEventWireTest {

    private static Map<?, ?> encodeThenDecode(DriverEvent e) {
        Object decoded = JsonCodec.decode(JsonCodec.encode(e));
        return assertInstanceOf(Map.class, decoded);
    }

    @Test
    void structuredPayloadArrivesAsAnObject() {
        DriverEvent e = new DriverEvent(1, "time.phase", new BlockPos(1, 2, 3),
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
        DriverEvent e = new DriverEvent(2, "entity.death", null, "minecraft:player");
        assertEquals("minecraft:player", encodeThenDecode(e).get("data"));
    }

    @Test
    void nestedAndListPayloadsSurvive() {
        DriverEvent e = new DriverEvent(3, "wait.done", null,
                Map.of("result", Map.of("ok", true), "seen", List.of(1L, 2L)));
        Map<?, ?> data = assertInstanceOf(Map.class, encodeThenDecode(e).get("data"));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) data.get("result")).get("ok"));
        assertEquals(2, ((List<?>) data.get("seen")).size());
    }

    @Test
    void payloadIsNotDoubleEscaped() {
        DriverEvent e = new DriverEvent(4, "x", null, Map.of("k", "v"));
        String json = JsonCodec.encode(e);
        assertTrue(json.contains("\"data\":{"), "data should open an object: " + json);
        assertFalse(json.contains("\\\""), "no escaped quotes — that is the double encoding: " + json);
    }

    @Test
    void absentPayloadEncodesAsNull() {
        DriverEvent e = new DriverEvent(5, "x", null, null);
        assertNull(encodeThenDecode(e).get("data"));
    }

    @Test
    void envelopeFieldsAreUnchanged() {
        DriverEvent e = new DriverEvent(9, "block.place", new BlockPos(4, 5, 6), "minecraft:stone");
        Map<?, ?> wire = encodeThenDecode(e);
        assertEquals(9L, wire.get("seq"));
        assertEquals("block.place", wire.get("type"));
        Map<?, ?> pos = assertInstanceOf(Map.class, wire.get("pos"));
        assertEquals(4L, pos.get("x"));
        assertEquals(6L, pos.get("z"));
        assertInstanceOf(Long.class, wire.get("timestamp"));
    }

    /**
     * The tests above build an {@link DriverEvent} directly, so they prove the CODEC
     * is right — not that the emitters stopped pre-encoding. Nothing else can:
     * {@code emit} takes an {@code Object}, so a leftover
     * {@code emit(type, pos, JsonCodec.encode(map))} compiles cleanly and quietly
     * restores the escaped-string payload for that one event type. This is the only
     * thing standing between that and a silent regression, so it reads the source.
     */
    @Test
    void noEmitterPreEncodesItsPayload() throws Exception {
        // The loader modules emit too (chat.message comes from each platform's chat
        // hook), so they are in scope even though this test lives in :common.
        Path own = Path.of("src/main/java");
        assertTrue(Files.isDirectory(own),
                "expected to run with the module dir as cwd; got " + Path.of(".").toAbsolutePath());
        List<Path> roots = new ArrayList<>(List.of(own));
        for (String sibling : List.of("../fabric/src/main/java", "../neoforge/src/main/java")) {
            Path p = Path.of(sibling);
            assertTrue(Files.isDirectory(p), "loader module source root moved: " + sibling);
            roots.add(p);
        }
        Pattern offender = Pattern.compile(
                "\\.emit(?:External)?\\s*\\([^;]{0,400}?"
                + "(?:net\\.magicterra\\.agent\\.rpc\\.)?JsonCodec\\.encode\\s*\\(", Pattern.DOTALL);
        List<String> hits = new ArrayList<>();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (offender.matcher(Files.readString(p)).find()) hits.add(p.toString());
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "these emit sites still pre-encode their payload, which puts JSON back "
                + "inside a JSON string on the wire — pass the value itself: " + hits);
    }

    @Test
    void notificationFrameCarriesTheSameTypedPayload() {
        // The push channel wraps the event as MCP notifications/message; the payload
        // must not be re-flattened on the way through.
        DriverEvent e = new DriverEvent(6, "threat.appeared", new BlockPos(0, 64, 0),
                Map.of("type", "minecraft:zombie", "dist", 7L));
        Map<?, ?> frame = assertInstanceOf(Map.class,
                JsonCodec.decode(EventNotifications.frame(e)));
        Map<?, ?> params = assertInstanceOf(Map.class, frame.get("params"));
        Map<?, ?> event = assertInstanceOf(Map.class, params.get("data"));
        Map<?, ?> payload = assertInstanceOf(Map.class, event.get("data"),
                "the event's own data must stay an object inside the notification");
        assertEquals("minecraft:zombie", payload.get("type"));
        assertEquals("notifications/message", frame.get("method"));
    }
}
