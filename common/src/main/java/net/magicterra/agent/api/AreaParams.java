package net.magicterra.agent.api;

import net.minecraft.core.BlockPos;
import java.util.Map;

public final class AreaParams {
    public BlockPos center;
    public int radius;
    public String typeFilter;

    public AreaParams() {}

    public static AreaParams from(Map<String, Object> m) {
        AreaParams p = new AreaParams();
        Object c = m.get("center");
        if (c instanceof BlockPos bp) p.center = bp;
        else if (c instanceof String s) p.center = ApiSupport.parsePos(s);
        else if (c instanceof Map<?, ?> cm) {
            p.center = new BlockPos(
                ((Number) cm.get("x")).intValue(),
                ((Number) cm.get("y")).intValue(),
                ((Number) cm.get("z")).intValue()
            );
        }
        Object r = m.get("radius");
        int radius = (r instanceof Number n) ? n.intValue() : 4;
        // Clamp to prevent O(n^3) DoS: radius=64 = ~2M getBlockState calls, already plenty
        p.radius = Math.max(0, Math.min(64, radius));
        Object f = m.get("filter");
        if (f instanceof Map<?, ?> fm) {
            Object t = fm.get("type");
            if (t != null) p.typeFilter = t.toString();
        }
        return p;
    }
}
