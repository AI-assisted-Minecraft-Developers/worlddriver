package net.magicterra.worlddriver.api.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.magicterra.worlddriver.api.schema.annotation.As;
import net.magicterra.worlddriver.api.schema.annotation.AsString;
import net.magicterra.worlddriver.api.schema.annotation.Default;
import net.magicterra.worlddriver.api.schema.annotation.Doc;
import net.magicterra.worlddriver.api.schema.annotation.Length;
import net.magicterra.worlddriver.api.schema.annotation.Named;
import net.magicterra.worlddriver.api.schema.annotation.OneOf;
import net.magicterra.worlddriver.api.schema.annotation.OnlyObject;
import net.magicterra.worlddriver.api.schema.annotation.OnlyTrue;
import net.magicterra.worlddriver.api.schema.annotation.Pattern;
import net.magicterra.worlddriver.api.schema.annotation.Range;
import net.magicterra.worlddriver.api.schema.annotation.Tag;
import net.magicterra.worlddriver.api.schema.annotation.TsAlias;
import net.magicterra.worlddriver.api.schema.annotation.Variant;
import net.magicterra.worlddriver.api.schema.annotation.Wire;
import org.junit.jupiter.api.Test;

/** Wire types read off records, enums and sealed interfaces, and the mistakes in their annotations. */
class ReflectedTypesTest {

    // ------------------------------------------------------------------ declarations under test

    /** A reusable constraint: a radius, described wherever it is used. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Range(min = 0, max = 64)
    @Doc("Accept any cell within this radius; 0 is exact.")
    @interface Near {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE_USE)
    @Pattern(value = "[a-z]+:[a-z_]+", expected = "a namespaced id")
    @Length(max = 32)
    @TsAlias(name = "Id", type = "`${string}:${string}`")
    @interface Id {}

    @Named("Cell")
    record Cell(int x, int y, int z) {}

    enum Mode implements WireName {
        IN,
        ADJACENT;

        @Override
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Named("Walk")
    record Walk(
            @Doc("Where to go.") Cell to,
            @Near @Default("0") int near,
            @Default("in") Mode mode,
            @Doc("Shown to the player.") Optional<@Length(min = 1, max = 8) String> label,
            @Near Optional<Integer> slack,
            @Length(max = 3) List<@Range(min = 1) Integer> steps,
            @Wire("break") @Default("false") boolean breakBlocks,
            @Default("[]") List<String> tags) {}

    @Tag("kind")
    @Named("Target")
    sealed interface Goal permits Spot, Mob, Nothing {}

    @Variant("spot")
    record Spot(Cell pos) implements Goal {}

    @Variant("mob")
    record Mob(@Id String type, @Near @Default("3") int near) implements Goal {}

    @Variant("nothing")
    record Nothing() implements Goal {}

    record Window(int from, int to) implements SelfChecked {
        @Override
        public void check(DecodeContext at) {
            if (to < from) {
                at.field("to").fail(ApiIssue.Code.INVALID_VALUE, "before from");
            }
        }
    }

    record Odd(
            @OnlyTrue boolean ack,
            @OnlyObject JsonValue extra,
            @AsString @Range(min = 0) long seed,

            @OneOf(
                    value = {"day", "night"},
                    name = "Phase")
            String phase,

            Map<String, @Range(min = 0, max = 1) Double> weights) {}

    record Boxed<R>(String id, R result) {}

    static final ApiType<String> SHOUT = ApiTypes.string(1, 5).describe("Upper case.");

    record Escaped(
            @As(value = ReflectedTypesTest.class, field = "SHOUT")
            String word) {}

    // ------------------------------------------------------------------ records

    @Test
    void aRecordReadsItsComponentsInOrderWithTheirPresence() {
        RecordType<Walk> walk = ApiTypes.record(Walk.class);
        Walk w = walk.decode(
                JsonParser.parseString("{\"to\":{\"x\":1,\"y\":2,\"z\":3},\"steps\":[1,2],\"break\":true}"));
        assertEquals(
                new Walk(
                        new Cell(1, 2, 3),
                        0,
                        Mode.IN,
                        Optional.empty(),
                        Optional.empty(),
                        List.of(1, 2),
                        true,
                        List.of()),
                w);
        assertEquals(
                "{\"to\":{\"x\":1,\"y\":2,\"z\":3},\"near\":0,\"mode\":\"in\",\"steps\":[1,2],\"break\":true,\"tags\":[]}",
                walk.encode(w).toString());
        assertSame(walk, ApiTypes.of(Walk.class), "one type per class");
    }

    @Test
    void theSchemaCarriesFieldDocsTypeDocsDefaultsAndRequired() {
        JsonObject s = ApiTypes.record(Walk.class).schema();
        JsonObject props = s.getAsJsonObject("properties");
        assertEquals(
                List.of("to", "near", "mode", "label", "slack", "steps", "break", "tags"), List.copyOf(props.keySet()));
        assertEquals("[\"to\",\"steps\"]", s.get("required").toString());
        assertEquals(
                "{\"description\":\"Accept any cell within this radius; 0 is exact.\",\"type\":\"integer\",\"minimum\":0,"
                        + "\"maximum\":64,\"default\":0}",
                props.get("near").toString());
        assertEquals(
                "{\"description\":\"Shown to the player.\",\"type\":\"string\",\"minLength\":1,\"maxLength\":8}",
                props.get("label").toString());
        assertEquals(
                "{\"type\":\"array\",\"items\":{\"type\":\"integer\",\"minimum\":1},\"maxItems\":3}",
                props.get("steps").toString());
        assertEquals("[]", props.getAsJsonObject("tags").get("default").toString());
    }

    @Test
    void typeScriptDeclaresNamedTypesAndCommentsOnlyFieldDocs() {
        TsContext ts = new TsContext();
        assertEquals("Walk", ApiTypes.of(Walk.class).ts(ts));
        String d = ts.render("");
        assertTrue(d.contains("interface Cell { x: number; y: number; z: number; }"), d);
        assertTrue(d.contains("/** Where to go. */\n    to: Cell;"), d);
        assertTrue(d.contains("near?: number;"), d);
        assertFalse(d.contains("Accept any cell"), d);
        assertTrue(d.contains("mode?: 'in' | 'adjacent';"), d);
        assertTrue(d.contains("break?: boolean;"), d);
    }

    @Test
    void eachBadFieldIsReportedAtItsPath() {
        ApiException e = assertThrows(
                ApiException.class,
                () -> ApiTypes.record(Walk.class)
                        .decode(
                                JsonParser.parseString(
                                        "{\"to\":{\"x\":1,\"y\":2},\"near\":65,\"steps\":[0,1,2,3],\"label\":\"\",\"nope\":1}")));
        Map<String, ApiIssue.Code> got = new java.util.TreeMap<>();
        for (ApiIssue i : e.error().issues()) {
            got.put(i.path(), i.code());
        }
        assertEquals(
                Map.of(
                        "/nope",
                        ApiIssue.Code.UNKNOWN_FIELD,
                        "/to/z",
                        ApiIssue.Code.REQUIRED,
                        "/near",
                        ApiIssue.Code.OUT_OF_RANGE,
                        "/steps",
                        ApiIssue.Code.OUT_OF_RANGE,
                        "/label",
                        ApiIssue.Code.OUT_OF_RANGE),
                got);
    }

    @Test
    void aSelfCheckedRecordRunsItsRuleAfterTheFields() {
        RecordType<Window> w = ApiTypes.record(Window.class);
        assertEquals(new Window(1, 2), w.decode(JsonParser.parseString("{\"from\":1,\"to\":2}")));
        ApiException e =
                assertThrows(ApiException.class, () -> w.decode(JsonParser.parseString("{\"from\":3,\"to\":2}")));
        assertEquals("/to", e.error().issues().get(0).path());
    }

    @Test
    void theRarerScalarsHaveTheirOwnAnnotations() {
        RecordType<Odd> odd = ApiTypes.record(Odd.class);
        Odd o = odd.decode(
                JsonParser.parseString(
                        "{\"ack\":true,\"extra\":{\"a\":1},\"seed\":\"9007199254740993\",\"phase\":\"night\",\"weights\":{\"a\":0.5}}"));
        assertEquals(9007199254740993L, o.seed());
        assertEquals(Map.of("a", 0.5), o.weights());
        assertThrows(
                ApiException.class,
                () -> odd.decode(JsonParser.parseString(
                        "{\"ack\":false,\"extra\":{},\"seed\":\"1\",\"phase\":\"day\",\"weights\":{}}")));
        assertThrows(
                ApiException.class,
                () -> odd.decode(JsonParser.parseString(
                        "{\"ack\":true,\"extra\":[],\"seed\":\"1\",\"phase\":\"day\",\"weights\":{}}")));
        TsContext ts = new TsContext();
        odd.ts(ts);
        assertTrue(ts.render("").contains("type Phase = 'day' | 'night';"), ts.render(""));
    }

    @Test
    void aMetaAnnotatedStringIsOneSharedTypeWithItsAlias() {
        TsContext ts = new TsContext();
        ApiTypes.union(Goal.class).ts(ts);
        String d = ts.render("");
        assertTrue(d.contains("type Id = `${string}:${string}`;"), d);
        assertEquals(
                "{\"type\":\"string\",\"minLength\":1,\"maxLength\":32,\"pattern\":\"^(?:[a-z]+:[a-z_]+)$\"}",
                ApiTypes.record(Mob.class)
                        .schema()
                        .getAsJsonObject("properties")
                        .get("type")
                        .toString());
    }

    @Test
    void aConstantReadOffAConstraintIsTheFieldsOwnType() {
        ApiType<Integer> near = ApiTypes.of(Integer.class, Near.class);
        assertSame(
                near,
                ApiTypes.record(Walk.class).fields().get(1).type(),
                "an int field and an Integer constant share one type");
        assertEquals("Accept any cell within this radius; 0 is exact.", near.description());
        assertSame(
                ApiTypes.of(String.class, Id.class),
                ApiTypes.record(Mob.class).fields().get(0).type());
    }

    @Test
    void anAsComponentUsesTheNamedConstant() {
        assertEquals(
                "{\"type\":\"object\",\"properties\":{\"word\":{\"description\":\"Upper case.\",\"type\":\"string\","
                        + "\"minLength\":1,\"maxLength\":5}},\"required\":[\"word\"],\"additionalProperties\":false}",
                ApiTypes.record(Escaped.class).schema().toString());
    }

    @Test
    void aGenericRecordIsBoundPerUse() {
        RecordType<Boxed<Integer>> boxed =
                asBoxed(ApiTypes.record(Boxed.class, Map.of("result", ApiTypes.integer(0, 9))));
        assertEquals(new Boxed<>("a", 4), boxed.decode(JsonParser.parseString("{\"id\":\"a\",\"result\":4}")));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiTypes.record(Boxed.class));
        assertTrue(e.getMessage().contains("Boxed.result"), e.getMessage());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RecordType<Boxed<Integer>> asBoxed(RecordType r) {
        return r;
    }

    // ------------------------------------------------------------------ unions and enums

    @Test
    void aSealedInterfaceIsAUnionOfItsPermittedRecordsInOrder() {
        UnionType<Goal> target = ApiTypes.union(Goal.class);
        assertEquals("kind", target.tagName());
        assertEquals(
                List.of("spot", "mob", "nothing"),
                target.variants().stream().map(UnionType.Variant::tag).toList());
        Goal t = target.decode(JsonParser.parseString("{\"kind\":\"mob\",\"type\":\"minecraft:zombie\"}"));
        assertEquals(new Mob("minecraft:zombie", 3), t);
        assertEquals("{\"kind\":\"nothing\"}", target.encode(new Nothing()).toString());
        assertSame(target, ApiTypes.of(Goal.class));
    }

    @Test
    void aUnionOfChosenRecordsTakesTheirVariantTags() {
        UnionType<Goal> two =
                ApiTypes.union("Two", "kind", ApiTypes.record(Spot.class), ApiTypes.record(Nothing.class));
        assertEquals(
                List.of("spot", "nothing"),
                two.variants().stream().map(UnionType.Variant::tag).toList());
    }

    // ------------------------------------------------------------------ mistakes

    record Reversed(@Range(min = 5, max = 1) int n) {}

    record BadDefault(@Default("seven") int n) {}

    record DefaultedOptional(@Default("1") Optional<Integer> n) {}

    record RangeOnString(@Range(min = 1) String s) {}

    record HalfInteger(@Range(max = 2.5) int n) {}

    record Nested(List<Optional<String>> items) {}

    record Loop(String name, List<Loop> children) {}

    record Unmarked(int n) implements Loose {}

    @Tag("kind")
    sealed interface Loose permits Unmarked {}

    @Test
    void annotationMistakesNameTheComponent() {
        assertMistake(Reversed.class, "empty range");
        assertMistake(BadDefault.class, "BadDefault.n: default seven does not fit an integer");
        assertMistake(DefaultedOptional.class, "DefaultedOptional.n: an Optional has no default");
        assertMistake(RangeOnString.class, "RangeOnString.s: @Range does not apply here");
        assertMistake(HalfInteger.class, "HalfInteger.n: an integer's bound 2.5 is not whole");
        assertMistake(Nested.class, "Nested.items[]: Optional only as a component's own type");
        assertMistake(Loop.class, "Loop contains itself");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiTypes.union(Loose.class));
        assertTrue(e.getMessage().contains("has no @Variant"), e.getMessage());
    }

    private static void assertMistake(Class<?> type, String message) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> ApiTypes.of(type));
        assertTrue(e.getMessage().contains(message), e.getMessage());
    }
}
