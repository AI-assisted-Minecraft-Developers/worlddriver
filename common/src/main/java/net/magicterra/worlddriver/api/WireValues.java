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
        if (value instanceof DriverEvent event) return EventWire.tree(event);
        return value;
    }
}
