package net.magicterra.worlddriver.api.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.TsContext;
import net.magicterra.worlddriver.api.methods.GotoContract.GoalMode;
import net.magicterra.worlddriver.api.methods.GotoContract.Params;
import net.magicterra.worlddriver.api.methods.GotoContract.PlanMode;
import net.magicterra.worlddriver.api.methods.GotoContract.Position;
import net.magicterra.worlddriver.api.methods.GotoContract.Scored;
import net.magicterra.worlddriver.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

class GotoContractTest {

    private static Params decode(String json) {
        return GotoContract.PARAMS.decode(JsonCodec.decode(json));
    }

    private static List<String> paths(String json) {
        return assertThrows(ApiException.class, () -> decode(json)).error().issues().stream().map(ApiIssue::path).toList();
    }

    private static final String POS = "{\"kind\":\"position\",\"pos\":{\"x\":1,\"y\":64,\"z\":2}}";

    @Test
    void aPositionTakesItsDefaults() {
        Params p = decode("{\"target\":" + POS + "}");
        assertEquals("self", p.body());
        Position t = assertInstanceOf(Position.class, p.target().orElseThrow());
        assertEquals(0, t.near());
        assertEquals(GoalMode.IN, t.goalMode());
        assertEquals(Optional.empty(), p.route());
        assertEquals(Optional.empty(), p.plan());
    }

    @Test
    void everyTargetKindDecodes() {
        for (String target : List.of(POS,
                "{\"kind\":\"column\",\"x\":1,\"z\":2}",
                "{\"kind\":\"level\",\"y\":-54}",
                "{\"kind\":\"block\",\"block\":\"#minecraft:logs\"}",
                "{\"kind\":\"entityType\",\"type\":\"minecraft:cow\"}",
                "{\"kind\":\"entity\",\"entityId\":42}",
                "{\"kind\":\"direction\",\"direction\":\"forward\",\"distance\":20}",
                "{\"kind\":\"heading\",\"direction\":\"north\"}",
                "{\"kind\":\"waypoint\",\"name\":\"home\",\"goalMode\":\"adjacent\"}",
                "{\"kind\":\"axis\"}")) {
            assertTrue(decode("{\"target\":" + target + "}").target().isPresent(), target);
        }
    }

    @Test
    void aTargetIsRequiredForAWalkAndAPreview() {
        assertEquals(List.of("/target"), paths("{}"));
        assertEquals(List.of("/target"), paths("{\"plan\":\"preview\"}"));
    }

    @Test
    void aHeadingIsHorizontal() {
        assertEquals(List.of("/target/direction"), paths("{\"target\":{\"kind\":\"heading\",\"direction\":\"up\"}}"));
    }

    @Test
    void planIdTakesNothingThePreviewAlreadyFixed() {
        assertEquals("p-3", decode("{\"planId\":\"p-3\",\"awaitMs\":500}").planId().orElseThrow());
        assertEquals(List.of("/target", "/route", "/invert"),
                paths("{\"planId\":\"p-3\",\"target\":" + POS + ",\"route\":{},\"invert\":true}"));
    }

    @Test
    void scoreNeedsACorridorAndNoTarget() {
        Params p = decode("{\"plan\":\"score\",\"route\":{\"corridor\":{\"points\":[{\"x\":0,\"y\":64,\"z\":0}]}}}");
        assertEquals(PlanMode.SCORE, p.plan().orElseThrow());
        assertEquals(List.of("/route/corridor"), paths("{\"plan\":\"score\"}"));
        assertEquals(List.of("/target", "/awaitMs", "/route/corridor"), paths("{\"plan\":\"score\",\"target\":" + POS + ",\"awaitMs\":5}"));
    }

    @Test
    void includePathIsOnlyForAPreview() {
        assertTrue(decode("{\"plan\":\"preview\",\"includePath\":true,\"target\":" + POS + "}").includePath());
        assertEquals(List.of("/includePath"), paths("{\"includePath\":true,\"target\":" + POS + "}"));
    }

    @Test
    void flyingNeedsATargetCellAndNoPreview() {
        String fly = ",\"route\":{\"mode\":[\"fly\"]}";
        decode("{\"target\":" + POS + fly + "}");
        assertEquals(List.of("/route/mode"), paths("{\"target\":{\"kind\":\"level\",\"y\":100}" + fly + "}"));
        assertEquals(List.of("/route/mode"), paths("{\"target\":" + POS + ",\"invert\":true" + fly + "}"));
        assertEquals(List.of("/plan"), paths("{\"target\":" + POS + ",\"plan\":\"preview\"" + fly + "}"));
    }

    @Test
    void scoredIsAnOutcomeBesideAcceptedAndRefused() {
        Object wire = GotoContract.RESPONSE.encode(new Scored(4, List.of(), 1.0, false, false));
        assertEquals("scored", ((Map<?, ?>) wire).get("outcome"));
        String ts = GotoContract.RESPONSE.ts(new TsContext());
        assertTrue(ts.contains("GotoResponse"), ts);
    }
}
