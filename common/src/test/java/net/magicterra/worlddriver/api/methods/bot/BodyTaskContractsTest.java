package net.magicterra.worlddriver.api.methods.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import net.magicterra.worlddriver.api.schema.MethodSpec;
import net.magicterra.worlddriver.api.schema.RecordType;
import net.magicterra.worlddriver.api.schema.UnionType;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.CoreMethods;
import net.magicterra.worlddriver.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

/** The body-task contracts beyond goto and clearArea: defaults, the rules that span fields, and the result shapes. */
class BodyTaskContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonCodec.decode(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json)).error().issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    private static String box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return "{\"from\":{\"x\":" + x0 + ",\"y\":" + y0 + ",\"z\":" + z0 + "},\"to\":{\"x\":" + x1 + ",\"y\":" + y1
                + ",\"z\":" + z1 + "}}";
    }

    // farm rescans its whole box after every harvest, on the tick thread, so the cap is on cells, Y included.

    @Test
    void aFieldAsTallAsTheWorldIsRefusedAtTo() {
        ApiIssue i = issue(GatherContracts.FARM_PARAMS, box(0, -64, 0, 63, 320, 63));
        assertEquals("/to", i.path());
        assertEquals(ApiIssue.Code.OUT_OF_RANGE, i.code());
        assertTrue(i.message().contains("4096"), i.message());
    }

    @Test
    void anAbsurdYSpanIsRefusedWithoutOverflowing() {
        assertEquals("/to", issue(GatherContracts.FARM_PARAMS, box(0, -1_000_000_000, 0, 0, 1_000_000_000, 0)).path());
    }

    @Test
    void aFlatFieldAtTheCapTakesEveryCropAndReplants() {
        GatherContracts.FarmParams p = decode(GatherContracts.FARM_PARAMS, box(0, 64, 0, 63, 64, 63));
        assertEquals(4096, GatherContracts.cells(p.from(), p.to()));
        assertEquals(List.of(GatherContracts.Crop.values()), p.crops());
        assertTrue(p.replant());
    }

    @Test
    void mineTakesIdsAndTagsAndItsDefaults() {
        GatherContracts.MineParams p = decode(GatherContracts.MINE_PARAMS, "{\"blocks\":[\"#minecraft:logs\",\"minecraft:stone\"]}");
        assertEquals(1, p.quantity());
        assertEquals(16, p.radius());
        assertEquals("self", p.bot());
        assertEquals("/blocks", issue(GatherContracts.MINE_PARAMS, "{\"blocks\":[]}").path());
        assertEquals("/quantity", issue(GatherContracts.MINE_PARAMS, "{\"blocks\":[\"minecraft:stone\"],\"quantity\":0}").path());
    }

    @Test
    void aTowerTakesExactlyOneHeight() {
        assertEquals("/structure/height", issue(BuildContracts.CONSTRUCT_PARAMS, "{\"structure\":{\"kind\":\"tower\"}}").path());
        assertEquals("/structure/targetY",
                issue(BuildContracts.CONSTRUCT_PARAMS, "{\"structure\":{\"kind\":\"tower\",\"height\":3,\"targetY\":80}}").path());
        BuildContracts.Bridge b = assertInstanceOf(BuildContracts.Bridge.class,
                decode(BuildContracts.CONSTRUCT_PARAMS, "{\"structure\":{\"kind\":\"bridge\",\"distance\":5}}").structure());
        assertEquals(BuildContracts.BridgeDirection.FORWARD, b.direction());
    }

    @Test
    void aSchematicRowMustFitItsBoxAndPalette() {
        String head = "{\"origin\":{\"x\":0,\"y\":64,\"z\":0},\"schematic\":{\"kind\":\"blocks\",\"w\":2,\"h\":1,\"d\":1,"
                + "\"palette\":[\"minecraft:stone\"],\"data\":";
        decode(BuildContracts.BUILD_PARAMS, head + "[[1,0,0,0]]}}");
        assertEquals("/schematic/data/0/3", issue(BuildContracts.BUILD_PARAMS, head + "[[1,0,0,1]]}}").path());
        assertEquals("/schematic/data/0/0", issue(BuildContracts.BUILD_PARAMS, head + "[[2,0,0,0]]}}").path());
        assertEquals("/schematic/w", issue(BuildContracts.BUILD_PARAMS, "{\"origin\":{\"x\":0,\"y\":64,\"z\":0},"
                + "\"schematic\":{\"kind\":\"blocks\",\"w\":100,\"h\":100,\"d\":1,\"palette\":[\"minecraft:stone\"],"
                + "\"data\":[[0,0,0,0]]}}").path());
    }

    @Test
    void killNeedsATargetAndOnlyKillTakesOne() {
        ApiIssue missing = issue(CombatContracts.PARAMS, "{\"mode\":\"kill\"}");
        assertEquals("/target", missing.path());
        assertEquals(ApiIssue.Code.REQUIRED, missing.code());
        assertEquals("/target", issue(CombatContracts.PARAMS, "{\"target\":{\"kind\":\"entity\",\"entityId\":3}}").path());
        CombatContracts.Params p = decode(CombatContracts.PARAMS, "{\"mode\":\"kill\",\"target\":{\"kind\":\"entityType\",\"type\":\"minecraft:zombie\"}}");
        assertEquals(new CombatContracts.FoeType("minecraft:zombie"), p.target().orElseThrow());
        assertEquals(CombatContracts.Mode.ENGAGE, decode(CombatContracts.PARAMS, "{}").mode());
    }

    @Test
    void followRefusesWhatARouteCannotMeanForIt() {
        String quarry = "\"target\":{\"kind\":\"entityType\",\"type\":\"minecraft:cow\"}";
        assertEquals("/route/via", issue(MoveContracts.FOLLOW_PARAMS,
                "{" + quarry + ",\"route\":{\"via\":[{\"x\":0,\"y\":64,\"z\":0}]}}").path());
        assertEquals("/route/mode", issue(MoveContracts.FOLLOW_PARAMS, "{" + quarry + ",\"route\":{\"mode\":[\"fly\"]}}").path());
        assertEquals("/route/leash/anchor", issue(MoveContracts.FOLLOW_PARAMS, "{" + quarry
                + ",\"route\":{\"leash\":{\"anchor\":{\"kind\":\"entity\",\"entity\":\"x\"},\"radius\":4}}}").path());
        MoveContracts.FollowParams p = decode(MoveContracts.FOLLOW_PARAMS, "{" + quarry + "}");
        assertEquals(3, p.radius());
        assertEquals(600, p.giveUpTicks());
    }

    @Test
    void walkingInsteadOfFlyingNeedsSomewhereToWalk() {
        assertEquals("/groundFallback", issue(MoveContracts.ELYTRA_PARAMS, "{\"groundFallback\":true}").path());
        assertEquals("/reactive", issue(MoveContracts.ELYTRA_PARAMS, "{\"reactive\":true}").path());
        MoveContracts.ElytraParams p = decode(MoveContracts.ELYTRA_PARAMS, "{}");
        assertEquals(40, p.fireworkEveryTicks());
        assertEquals(Optional.empty(), p.ticks());
    }

    @Test
    void resultsEncodeUnderTheirOwnKind() {
        assertEquals("{\"kind\":\"mine\",\"blocks\":[\"minecraft:stone\"],\"quantity\":2,\"radius\":16,\"broken\":2}",
                JsonCodec.encode(GatherContracts.MINE_RESULT.encode(new GatherContracts.Mined(List.of("minecraft:stone"), 2, 16, 2))));
        assertEquals("tower", ((Map<?, ?>) BuildContracts.CONSTRUCT_RESULT.encode(
                new BuildContracts.Towered(64, 70, Optional.empty(), 6))).get("kind"));
        assertEquals("groundFallback", ((Map<?, ?>) MoveContracts.ELYTRA_RESULT.encode(
                new MoveContracts.Flown(MoveContracts.FlightMode.GROUND_FALLBACK, false))).get("mode"));
    }

    /**
     * {@code ActionResults} lists the result unions by hand; a method that starts an action and is missing
     * from it answers {@code mc.wait.result} with a kind the union cannot encode. A method starts an action
     * when its response can be {@code accepted} (not every body task does: {@code mc.debug.replay} answers
     * at once); its result is the succeeded snapshot's {@code result} field.
     */
    @Test
    void actionResultsHoldsExactlyTheActionMethodsResults() {
        Set<String> fromMethods = new TreeSet<>();
        for (MethodSpec<?, ?> spec : CoreMethods.all()) {
            RecordType<?> accepted = variant(spec.output(), "accepted");
            if (accepted == null) continue;
            ApiType<?> action = field(accepted, "action");
            ApiType<?> result = field(variant(action, "succeeded"), "result");
            UnionType<?> kinds = assertInstanceOf(UnionType.class, result, spec.name() + "'s result");
            for (UnionType.Variant<?, ?> v : kinds.variants()) fromMethods.add(v.tag());
        }
        Set<String> listed = new TreeSet<>();
        // The union throws on a tag two methods share; reading it builds it.
        for (UnionType.Variant<?, ?> v : ActionResults.UNION.variants()) listed.add(v.tag());
        assertEquals(fromMethods, listed);
    }

    /** The variant tagged {@code tag}, or null when {@code type} is not a union or has no such variant. */
    private static RecordType<?> variant(ApiType<?> type, String tag) {
        if (!(type instanceof UnionType<?> u)) return null;
        return u.variants().stream().filter(v -> v.tag().equals(tag)).findFirst().map(v -> v.type()).orElse(null);
    }

    private static ApiType<?> field(RecordType<?> record, String name) {
        return record.fields().stream().filter(f -> f.name().equals(name)).findFirst().orElseThrow().type();
    }
}
