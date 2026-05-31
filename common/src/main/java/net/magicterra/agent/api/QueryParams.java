package net.magicterra.agent.api;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

public final class QueryParams {
    public String q;
    public Map<String, Object> filter;
    public List<String> select;
    /** Optional search center. When null the route falls back to {@link AgentApi#testOriginPos()}. */
    public BlockPos center;

    @SuppressWarnings("unchecked")
    public static QueryParams from(Map<String, Object> m) {
        QueryParams p = new QueryParams();
        p.q = (String) m.get("q");
        p.filter = (Map<String, Object>) m.getOrDefault("filter", Map.of());
        Object sel = m.get("select");
        if (sel instanceof List<?> l) {
            p.select = (List<String>) l;
        }
        Object c = m.get("center");
        if (c instanceof BlockPos bp) p.center = bp;
        else if (c instanceof Map<?, ?> cm && cm.get("x") instanceof Number && cm.get("y") instanceof Number && cm.get("z") instanceof Number) {
            p.center = new BlockPos(
                ((Number) cm.get("x")).intValue(),
                ((Number) cm.get("y")).intValue(),
                ((Number) cm.get("z")).intValue()
            );
        }
        return p;
    }
}
