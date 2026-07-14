package net.magicterra.agent.rpc;

import net.magicterra.agent.model.AgentEvent;

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
 *            "data":{seq,timestamp,type,pos,data}}}
 * }</pre>
 */
public final class EventNotifications {
    private EventNotifications() {}

    /** The full JSON-RPC {@code notifications/message} frame for an event. */
    public static String frame(AgentEvent e) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("level", levelFor(e.type));
        params.put("logger", "minecraft.events");
        params.put("data", e); // JsonCodec encodes AgentEvent → {seq,timestamp,type,pos,data}
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
        if (type == null) return "info";
        return switch (type) {
            case "player.death" -> "error";
            // High-attention signals (incoming threat, taking damage, a background wait or
            // another transport's command completing, the dusk reflex auto-starting an
            // unattended dig, a mainhand tool breaking mid-task) surface as warning so
            // they stand out in a client UI.
            case "threat.appeared", "player.hurt", "wait.done", "command.result", "duskSecure.triggered",
                 "tool.broke" -> "warning";
            case "entity.death" -> "notice";
            default -> "info";
        };
    }

    /** RFC 5424 severity rank (higher = more severe). Used to honor an MCP
     *  client's {@code logging/setLevel} minimum. Unknown → info. */
    public static int rank(String level) {
        if (level == null) return 1;
        return switch (level) {
            case "debug" -> 0;
            case "info" -> 1;
            case "notice" -> 2;
            case "warning" -> 3;
            case "error" -> 4;
            case "critical" -> 5;
            case "alert" -> 6;
            case "emergency" -> 7;
            default -> 1;
        };
    }
}
