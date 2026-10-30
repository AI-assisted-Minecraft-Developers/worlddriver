package net.magicterra.worlddriver.api.schema;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

    // A null return is ApiType.read's failure signal, checked by every caller; an empty map would hide the issues.
    @Override
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    protected Map<String, T> read(JsonElement v, DecodeContext at) {
        if (!(v instanceof JsonObject m)) {
            return at.mismatch(expected(), v);
        }
        if (m.size() > maxEntries) {
            return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected at most " + maxEntries + " entries, got " + m.size());
        }
        int before = at.issues().size();
        Map<String, T> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : m.entrySet()) {
            DecodeContext eat = at.field(e.getKey());
            if (e.getValue().isJsonNull()) {
                eat.mismatch(value.expected(), e.getValue());
                continue;
            }
            out.put(e.getKey(), value.decode(e.getValue(), eat));
        }
        return at.issues().size() == before ? Collections.unmodifiableMap(out) : null;
    }

    @Override
    protected JsonElement write(Map<String, T> v) {
        JsonObject out = new JsonObject();
        v.forEach((k, x) -> out.add(k, value.write(x)));
        return out;
    }

    @Override
    protected JsonObject schemaBody() {
        JsonObject s = new JsonObject();
        s.addProperty("type", "object");
        s.add("additionalProperties", value.schema());
        s.addProperty("maxProperties", maxEntries);
        return s;
    }

    @Override
    public String ts(TsContext ts) {
        return "Record<string, " + value.ts(ts) + ">";
    }

    @Override
    public String expected() {
        return "an object of " + value.expected();
    }
}
