package net.magicterra.worlddriver.rpc;

import net.magicterra.worlddriver.api.methods.event.EventCatalog;
import net.magicterra.worlddriver.api.methods.event.EventContracts;
import net.magicterra.worlddriver.protocol.JsonCodec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Frames a driver event as a standard MCP server→client notification so every
 * transport (WebSocket {@code /rpc}, MCP Streamable-HTTP {@code GET /mcp} SSE)
 * pushes the byte-identical message. We use {@code notifications/message} — the
 * MCP logging notification — because it is the one server-initiated notification
 * any MCP-aware client already knows how to consume.
 *
 * Shape:
 * <pre>{@code
 * {"jsonrpc":"2.0","method":"notifications/message",
 *  "params":{"level":"warning","logger":"minecraft.events",
 *            "data":{seq,timestamp,type,pos?,data?}}}
 * }</pre>
 * {@code data} is the shared {@code EventContracts.Event}: {@code seq} a decimal string.
 */
public final class EventNotifications {
    private EventNotifications() {}

    /** The full JSON-RPC {@code notifications/message} frame for an event. */
    public static String frame(EventContracts.Event e) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("level", levelFor(e.type()));
        params.put("logger", "minecraft.events");
        params.put("data", EventContracts.EVENT.encode(e));
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("jsonrpc", "2.0");
        msg.put("method", "notifications/message");
        msg.put("params", params);
        return JsonCodec.encode(msg);
    }

    /** Map an event type to an RFC 5424 / MCP logging severity level. Since the channel
     *  now forwards EVERY non-muted event (McpServer.onEvent / RpcServer.onEvent), this
     *  level is a DISPLAY/severity LABEL on the frame — a client can colour or prioritise
     *  by it — not a push gate. */
    public static String levelFor(String type) {
        EventCatalog.Entry<?> entry = EventCatalog.byType(type);
        return (entry == null ? EventCatalog.Level.INFO : entry.level()).wireName();
    }

    // A rank(String) severity-ordering helper used to live here, existing solely to
    // compare against the MCP client's logging/setLevel minimum. That comparison was
    // removed deliberately (McpServer.onEvent explains why a client defaulting to
    // `warning` must not silence info/notice driver events), which left the helper
    // feeding a field that was written and never read. Both are gone; if severity
    // ordering is ever needed again, it is eight lines.
}
