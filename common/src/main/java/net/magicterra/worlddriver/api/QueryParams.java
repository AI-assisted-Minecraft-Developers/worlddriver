package net.magicterra.worlddriver.api;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Map;

public final class QueryParams {
    public String q;
    public Map<String, Object> filter;
    public List<String> select;
    /** Optional search center. When null the route falls back to {@link DriverApi#testOriginPos()}. */
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
        p.center = ApiSupport.readPos(m.get("center"));
        return p;
    }
}
