package net.magicterra.worlddriver.api.schema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A bounded list of one item type, decoded unmodifiable; see {@link ApiTypes#list}. */
final class ListType<T> extends ApiType<List<T>> {
    private final ApiType<T> item;
    private final int minItems, maxItems;

    ListType(ApiType<T> item, int minItems, int maxItems) {
        this.item = Objects.requireNonNull(item);
        this.minItems = minItems;
        this.maxItems = maxItems;
    }

    @Override protected List<T> read(Object value, DecodeContext at) {
        if (!(value instanceof List<?> l)) return at.mismatch(expected(), value);
        if (l.size() < minItems || l.size() > maxItems) {
            return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + l.size() + " items");
        }
        int before = at.issues().size();
        List<T> out = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) {
            DecodeContext eat = at.index(i);
            Object raw = l.get(i);
            if (raw == null) { eat.mismatch(item.expected(), null); continue; }
            out.add(item.decode(raw, eat));
        }
        return at.issues().size() == before ? Collections.unmodifiableList(out) : null;
    }

    @Override protected Object write(List<T> value) {
        List<Object> out = new ArrayList<>(value.size());
        for (T v : value) out.add(item.write(v));
        return out;
    }

    @Override protected Map<String, Object> schemaBody() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "array");
        s.put("items", item.schema());
        if (minItems > 0) s.put("minItems", minItems);
        if (maxItems != Integer.MAX_VALUE) s.put("maxItems", maxItems);
        return s;
    }

    @Override public String ts(TsContext ts) {
        String t = item.ts(ts);
        return t.matches("[A-Za-z0-9_.$<>]+") ? t + "[]" : "Array<" + t + ">";
    }

    @Override public String expected() {
        String size = maxItems == Integer.MAX_VALUE
                ? (minItems > 0 ? " of at least " + minItems : "")
                : " of " + minItems + "–" + maxItems;
        return "an array" + size + " of " + item.expected();
    }
}
