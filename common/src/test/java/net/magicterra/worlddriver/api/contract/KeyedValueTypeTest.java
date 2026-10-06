package net.magicterra.worlddriver.api.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.magicterra.worlddriver.api.contract.KeyedValueType.Entry;
import net.magicterra.worlddriver.protocol.JsonCodec;
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
        return assertThrows(ApiException.class, () -> type.decode(JsonCodec.decode(json))).error().issues();
    }

    @Test
    void membersDecodeInTheOrderSentAndEncodeBack() {
        List<Entry> entries = KNOBS.decode(JsonCodec.decode("{\"depth\":4,\"flag\":true}"));
        assertEquals(List.of(new Entry("depth", 4), new Entry("flag", true)), entries);
        assertEquals("{\"depth\":4,\"flag\":true}", JsonCodec.encode(KNOBS.encode(entries)));
        assertEquals(List.of(), KNOBS.decode(JsonCodec.decode("{}")));
    }

    @Test
    void eachBadMemberIsReportedAtItsOwnKey() {
        List<ApiIssue> got = issues(KNOBS, "{\"flag\":\"yes\",\"depth\":99,\"nope\":1,\"dotted.name\":null}");
        Map<String, ApiIssue.Code> byPath = new LinkedHashMap<>();
        for (ApiIssue i : got) byPath.put(i.path(), i.code());
        assertEquals(Map.of("/flag", ApiIssue.Code.TYPE_MISMATCH, "/depth", ApiIssue.Code.OUT_OF_RANGE,
                "/nope", ApiIssue.Code.UNKNOWN_FIELD, "/dotted.name", ApiIssue.Code.TYPE_MISMATCH), byPath);
    }

    @Test
    void aMinimumRefusesTheEmptyObject() {
        assertEquals(ApiIssue.Code.OUT_OF_RANGE, issues(KNOBS.atLeast(1), "{}").get(0).code());
        assertEquals(ApiIssue.Code.TYPE_MISMATCH, issues(KNOBS, "[]").get(0).code());
    }

    @Test
    void theSchemaIsClosedWithOnePropertyPerKey() {
        Map<String, Object> schema = KNOBS.atLeast(1).schema();
        assertEquals(false, schema.get("additionalProperties"));
        assertEquals(1, schema.get("minProperties"));
        Map<?, ?> props = (Map<?, ?>) schema.get("properties");
        assertEquals(List.of("flag", "depth", "dotted.name"), List.copyOf(props.keySet()));
        assertEquals("On or off.", ((Map<?, ?>) props.get("flag")).get("description"));
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
