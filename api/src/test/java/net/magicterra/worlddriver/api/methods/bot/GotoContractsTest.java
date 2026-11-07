package net.magicterra.worlddriver.api.methods.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.GoalMode;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Params;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.PlanMode;
import net.magicterra.worlddriver.api.methods.bot.GotoContracts.Position;
import net.magicterra.worlddriver.api.methods.shared.Responses.Scored;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import org.junit.jupiter.api.Test;

class GotoContractsTest {

    private static final String TARGET_POINTER = "/target";

    private static final String TARGET = "{\"target\":";

    private static final String CLOSE_BRACE = "}";

    private static final String POS = "{\"kind\":\"position\",\"pos\":{\"x\":1,\"y\":64,\"z\":2}}";

    private static Params decode(String json) {
        return GotoContracts.PARAMS.decode(JsonParser.parseString(json));
    }

    private static List<String> paths(String json) {
        return assertThrows(ApiException.class, () -> decode(json)).error().issues().stream()
                .map(ApiIssue::path)
                .toList();
    }

    @Test
    void positionTakesItsDefaults() {
        Params p = decode(TARGET + POS + CLOSE_BRACE);
        assertEquals("self", p.bot());
        Position t = assertInstanceOf(Position.class, p.target().orElseThrow());
        assertEquals(0, t.near());
        assertEquals(GoalMode.IN, t.goalMode());
        assertEquals(Optional.empty(), p.route());
        assertEquals(Optional.empty(), p.plan());
    }

    @Test
    void everyTargetKindDecodes() {
        for (String target : List.of(
                POS,
                "{\"kind\":\"column\",\"x\":1,\"z\":2}",
                "{\"kind\":\"level\",\"y\":-54}",
                "{\"kind\":\"block\",\"block\":\"#minecraft:logs\"}",
                "{\"kind\":\"entityType\",\"type\":\"minecraft:cow\"}",
                "{\"kind\":\"entity\",\"entityId\":42}",
                "{\"kind\":\"direction\",\"direction\":\"forward\",\"distance\":20}",
                "{\"kind\":\"heading\",\"direction\":\"north\"}",
                "{\"kind\":\"waypoint\",\"name\":\"home\",\"goalMode\":\"adjacent\"}",
                "{\"kind\":\"axis\"}")) {
            assertTrue(decode(TARGET + target + CLOSE_BRACE).target().isPresent(), target);
        }
    }

    @Test
    void targetIsRequiredForWalkAndPreview() {
        assertEquals(List.of(TARGET_POINTER), paths("{}"));
        assertEquals(List.of(TARGET_POINTER), paths("{\"plan\":\"preview\"}"));
    }

    @Test
    void headingIsHorizontal() {
        assertEquals(List.of("/target/direction"), paths("{\"target\":{\"kind\":\"heading\",\"direction\":\"up\"}}"));
    }

    @Test
    void planIdTakesNothingThePreviewAlreadyFixed() {
        assertEquals(
                "p-3", decode("{\"planId\":\"p-3\",\"awaitMs\":500}").planId().orElseThrow());
        assertEquals(
                List.of(TARGET_POINTER, "/route", "/invert"),
                paths("{\"planId\":\"p-3\",\"target\":" + POS + ",\"route\":{},\"invert\":true}"));
    }

    @Test
    void scoreNeedsCorridorAndNoTarget() {
        Params p = decode("{\"plan\":\"score\",\"route\":{\"corridor\":{\"points\":[{\"x\":0,\"y\":64,\"z\":0}]}}}");
        assertEquals(PlanMode.SCORE, p.plan().orElseThrow());
        assertEquals(List.of("/route/corridor"), paths("{\"plan\":\"score\"}"));
        assertEquals(
                List.of(TARGET_POINTER, "/awaitMs", "/route/corridor"),
                paths("{\"plan\":\"score\",\"target\":" + POS + ",\"awaitMs\":5}"));
    }

    @Test
    void includePathIsOnlyForPreview() {
        assertTrue(decode("{\"plan\":\"preview\",\"includePath\":true,\"target\":" + POS + CLOSE_BRACE)
                .includePath());
        assertEquals(List.of("/includePath"), paths("{\"includePath\":true,\"target\":" + POS + CLOSE_BRACE));
    }

    @Test
    void flyingNeedsTargetCellAndNoPreview() {
        String fly = ",\"route\":{\"mode\":[\"fly\"]}";
        decode(TARGET + POS + fly + CLOSE_BRACE);
        assertEquals(List.of("/route/mode"), paths("{\"target\":{\"kind\":\"level\",\"y\":100}" + fly + CLOSE_BRACE));
        assertEquals(List.of("/route/mode"), paths(TARGET + POS + ",\"invert\":true" + fly + CLOSE_BRACE));
        assertEquals(List.of("/plan"), paths(TARGET + POS + ",\"plan\":\"preview\"" + fly + CLOSE_BRACE));
    }

    @Test
    void scoredIsAnOutcomeBesideAcceptedAndRefused() {
        JsonObject wire = GotoContracts.RESPONSE
                .encode(new Scored(4, List.of(), 1.0, false, false))
                .getAsJsonObject();
        assertEquals("scored", wire.get("outcome").getAsString());
    }
}
