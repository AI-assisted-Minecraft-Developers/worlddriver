package net.magicterra.worlddriver.api;

import java.util.LinkedHashMap;
import java.util.Map;
import net.magicterra.worlddriver.model.DriverEvent;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.minecraft.core.BlockPos;

/** Domain-to-wire adaptation shared by in-process JSON, RPC and MCP. */
public final class WireValues {
    private WireValues() {}

    public static String encode(Object value) { return JsonCodec.encode(value, WireValues::adapt); }

    private static Object adapt(Object value) {
        if (value instanceof BlockPos pos) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("x", pos.getX());
            out.put("y", pos.getY());
            out.put("z", pos.getZ());
            return out;
        }
        if (value instanceof DriverEvent event) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("seq", event.seq);
            out.put("timestamp", event.timestamp);
            out.put("type", event.type);
            out.put("pos", event.pos);
            out.put("data", event.data);
            return out;
        }
        return value;
    }
}
