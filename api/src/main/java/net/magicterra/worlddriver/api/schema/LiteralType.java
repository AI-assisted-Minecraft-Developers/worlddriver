package net.magicterra.worlddriver.api.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Exactly one string; see {@link ApiTypes#literal}. */
final class LiteralType extends ApiType<String> {
    private final String value;

    LiteralType(String value) { this.value = Objects.requireNonNull(value); }

    @Override protected String read(Object v, DecodeContext at) {
        if (!(v instanceof String s)) return at.mismatch(expected(), v);
        return value.equals(s) ? s : at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got \"" + s + "\"");
    }
    @Override protected Object write(String v) { return v; }
    @Override protected Map<String, Object> schemaBody() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        s.put("const", value);
        return s;
    }
    @Override public String ts(TsContext ts) { return TsContext.literal(value); }
    @Override public String expected() { return "\"" + value + "\""; }
}
