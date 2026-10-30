package net.magicterra.worlddriver.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.magicterra.worlddriver.api.schema.KeyedValueType.Entry;
import org.junit.jupiter.api.Test;

/** A {key: value} object whose members are typed by their key. */
class KeyedValueTypeTest {

    private static final KeyedValueType KNOBS = ApiTypes.keyedValues("Knobs", knobs(), Map.of("flag", "On or off."));

    private static Map<String, ApiType<?>> knobs() {
        Map<String, ApiType<?>> types = new LinkedHashMap<>();
        types.put("flag", ApiTypes.bool());
        types.put("depth", ApiTypes.integer(1, 16));
        types.put("dotted.name", ApiTypes.string(1, 8));
        return types;
    }

    private static List<ApiIssue> issues(KeyedValueType type, String json) {
        return assertThrows(ApiException.class, () -> type.decode(JsonParser.parseString(json)))
                .error()
                .issues();
    }

    @Test
    void membersDecodeInTheOrderSentAndEncodeBack() {
        List<Entry> entries = KNOBS.decode(JsonParser.parseString("{\"depth\":4,\"flag\":true}"));
        assertEquals(List.of(new Entry("depth", 4), new Entry("flag", true)), entries);
        assertEquals("{\"depth\":4,\"flag\":true}", String.valueOf(KNOBS.encode(entries)));
        assertEquals(List.of(), KNOBS.decode(JsonParser.parseString("{}")));
    }

    @Test
    void eachBadMemberIsReportedAtItsOwnKey() {
        List<ApiIssue> got = issues(KNOBS, "{\"flag\":\"yes\",\"depth\":99,\"nope\":1,\"dotted.name\":null}");
        Map<String, ApiIssue.Code> byPath = new LinkedHashMap<>();
        for (ApiIssue i : got) {
            byPath.put(i.path(), i.code());
        }
        assertEquals(
                Map.of(
                        "/flag",
                        ApiIssue.Code.TYPE_MISMATCH,
                        "/depth",
                        ApiIssue.Code.OUT_OF_RANGE,
                        "/nope",
                        ApiIssue.Code.UNKNOWN_FIELD,
                        "/dotted.name",
                        ApiIssue.Code.TYPE_MISMATCH),
                byPath);
    }

    @Test
    void aJavaCallerMayHandAnIntegerToALongOrDoubleKey() {
        KeyedValueType wide = ApiTypes.keyedValues(
                "Wide", Map.of("seed", ApiTypes.int64(0, Long.MAX_VALUE), "scale", ApiTypes.number()), Map.of());
        List<Entry> checked = wide.validate(List.of(new Entry("seed", 7), new Entry("scale", 2)));
        assertEquals(List.of(new Entry("seed", 7L), new Entry("scale", 2.0)), checked);
        assertEquals(
                ApiIssue.Code.TYPE_MISMATCH,
                assertThrows(ApiException.class, () -> wide.validate(List.of(new Entry("seed", "7"))))
                        .error()
                        .issues()
                        .get(0)
                        .code());
    }

    @Test
    void aMinimumRefusesTheEmptyObject() {
        assertEquals(
                ApiIssue.Code.OUT_OF_RANGE,
                issues(KNOBS.atLeast(1), "{}").get(0).code());
        assertEquals(ApiIssue.Code.TYPE_MISMATCH, issues(KNOBS, "[]").get(0).code());
    }

    @Test
    void theSchemaIsClosedWithOnePropertyPerKey() {
        JsonObject schema = KNOBS.atLeast(1).schema();
        assertEquals(false, schema.get("additionalProperties").getAsBoolean());
        assertEquals(1, schema.get("minProperties").getAsInt());
        JsonObject props = schema.getAsJsonObject("properties");
        assertEquals(List.of("flag", "depth", "dotted.name"), List.copyOf(props.keySet()));
        assertEquals(
                "On or off.", props.getAsJsonObject("flag").get("description").getAsString());
    }

    @Test
    void theTypeScriptIsOneInterfaceOfOptionalMembersSharedByItsCopies() {
        TsContext ts = new TsContext();
        assertEquals("Knobs", KNOBS.ts(ts));
        assertEquals("Knobs", KNOBS.atLeast(1).ts(ts));
        String d = ts.render("");
        assertTrue(d.contains("interface Knobs {"), d);
        assertTrue(d.contains("flag?: boolean;") && d.contains("'dotted.name'?: string;"), d);
    }
}
