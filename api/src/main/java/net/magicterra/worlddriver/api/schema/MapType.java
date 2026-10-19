package net.magicterra.worlddriver.api.schema;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An object whose keys are data and whose values share one type; see {@link ApiTypes#map}. */
final class MapType<T> extends ApiType<Map<String, T>> {
    private final ApiType<T> value;
    private final int maxEntries;

    MapType(ApiType<T> value, int maxEntries) {
        this.value = value;
        this.maxEntries = maxEntries;
    }

    @Override protected Map<String, T> read(Object v, DecodeContext at) {
        if (!(v instanceof Map<?, ?> m)) return at.mismatch(expected(), v);
        if (m.size() > maxEntries) return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected at most " + maxEntries + " entries, got " + m.size());
        int before = at.issues().size();
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            DecodeContext eat = at.field(k);
            if (e.getValue() == null) { eat.mismatch(value.expected(), null); continue; }
            out.put(k, value.decode(e.getValue(), eat));
        }
        return at.issues().size() == before ? Collections.unmodifiableMap(out) : null;
    }

    @Override protected Object write(Map<String, T> v) {
        Map<String, Object> out = new LinkedHashMap<>();
        v.forEach((k, x) -> out.put(k, value.write(x)));
        return out;
    }

    @Override protected Map<String, Object> schemaBody() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "object");
        s.put("additionalProperties", value.schema());
        s.put("maxProperties", maxEntries);
        return s;
    }

    @Override public String ts(TsContext ts) { return "Record<string, " + value.ts(ts) + ">"; }

    @Override public String expected() { return "an object of " + value.expected(); }
}
