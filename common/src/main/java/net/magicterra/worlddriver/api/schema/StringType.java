package net.magicterra.worlddriver.api.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** A string type; immutable, so the narrowing methods return new instances. */
public final class StringType extends ApiType<String> {
    private final int minLength, maxLength;
    private final Pattern pattern;
    private final String meaning;
    private String tsName, tsType;

    StringType(int minLength, int maxLength, Pattern pattern, String meaning) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.pattern = pattern;
        this.meaning = meaning;
    }

    public StringType maxLength(int max) { return copy(minLength, max); }
    public StringType minLength(int min) { return copy(min, maxLength); }

    /**
     * Declare it to TypeScript as {@code type <name> = <tsType>}, a template literal type for a
     * pattern TypeScript can approximate. TypeScript cannot check the whole pattern; the server does.
     */
    public StringType tsAlias(String name, String type) {
        StringType s = copy(minLength, maxLength);
        s.tsName = name;
        s.tsType = type;
        return s;
    }

    private StringType copy(int min, int max) {
        StringType s = new StringType(min, max, pattern, meaning);
        s.tsName = tsName;
        s.tsType = tsType;
        return s;
    }

    @Override protected String read(Object value, DecodeContext at) {
        if (!(value instanceof String s)) return at.mismatch(expected(), value);
        if (s.length() < minLength || s.length() > maxLength) {
            return at.fail(ApiIssue.Code.OUT_OF_RANGE, "expected " + expected() + ", got " + s.length() + " characters");
        }
        if (pattern != null && !pattern.matcher(s).matches()) {
            return at.fail(ApiIssue.Code.INVALID_VALUE, "expected " + expected() + ", got " + DecodeContext.describe(s));
        }
        return s;
    }

    @Override protected Object write(String value) { return Objects.requireNonNull(value, "string"); }

    @Override protected Map<String, Object> schemaBody() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("type", "string");
        if (minLength > 0) s.put("minLength", minLength);
        if (maxLength != Integer.MAX_VALUE) s.put("maxLength", maxLength);
        if (pattern != null) s.put("pattern", "^(?:" + pattern.pattern() + ")$");
        return s;
    }

    @Override public String ts(TsContext ts) {
        if (tsName == null) return "string";
        return ts.declare(tsName, this, () -> "type " + tsName + " = " + tsType + ";");
    }

    @Override public String expected() {
        if (meaning != null) return meaning;
        if (minLength > 0 && maxLength != Integer.MAX_VALUE) return "a string of " + minLength + "–" + maxLength + " characters";
        if (minLength > 0) return "a non-empty string";
        if (maxLength != Integer.MAX_VALUE) return "a string of at most " + maxLength + " characters";
        return "a string";
    }
}
