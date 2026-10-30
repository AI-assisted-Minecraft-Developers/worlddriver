package net.magicterra.worlddriver.api.methods.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Params;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Summary;
import net.magicterra.worlddriver.api.methods.bot.ClearAreaContracts.Tunnel;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.Direction;
import net.magicterra.worlddriver.api.methods.shared.Responses.Accepted;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.methods.shared.Responses.Succeeded;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import org.junit.jupiter.api.Test;

class ClearAreaContractsTest {

    private static Params decode(String json) {
        return ClearAreaContracts.PARAMS.decode(JsonParser.parseString(json));
    }

    private static List<ApiIssue> issues(String json) {
        return assertThrows(ApiException.class, () -> decode(json)).error().issues();
    }

    @Test
    void omittedFieldsTakeTheirDefaults() {
        Params p = decode("{\"target\":{\"kind\":\"tunnel\",\"distance\":12}}");
        assertEquals("self", p.bot());
        assertEquals(0, p.awaitMs());
        assertInstanceOf(ClearAreaContracts.Clear.class, p.edit());
        Tunnel t = assertInstanceOf(Tunnel.class, p.target());
        assertEquals(Direction.FORWARD, t.direction());
        assertEquals(1, t.width());
        assertEquals(2, t.height());
        assertEquals(Optional.empty(), t.widthBias());
    }

    @Test
    void aStringDistanceIsATypeMismatchAtItsPath() {
        List<ApiIssue> is = issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":\"12\"}}");
        assertEquals("/target/distance", is.get(0).path());
        assertEquals(ApiIssue.Code.TYPE_MISMATCH, is.get(0).code());
    }

    @Test
    void anotherBranchsFieldIsUnknown() {
        List<ApiIssue> is =
                issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":12,\"from\":{\"x\":1,\"y\":2,\"z\":3}}}");
        assertEquals("/target/from", is.get(0).path());
        assertEquals(ApiIssue.Code.UNKNOWN_FIELD, is.get(0).code());
    }

    @Test
    void rangesAreErrorsNotClamps() {
        assertEquals(
                ApiIssue.Code.OUT_OF_RANGE,
                issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":65}}")
                        .get(0)
                        .code());
        assertEquals(
                "/awaitMs",
                issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":1},\"awaitMs\":-1}")
                        .get(0)
                        .path());
    }

    @Test
    void aVerticalTunnelTakesNoBias() {
        List<ApiIssue> is = issues(
                "{\"target\":{\"kind\":\"tunnel\",\"direction\":\"down\",\"distance\":3,\"widthBias\":\"left\"}}");
        assertEquals("/target/widthBias", is.get(0).path());
    }

    @Test
    void nullIsNotOmission() {
        assertEquals(
                "/edit",
                issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":1},\"edit\":null}")
                        .get(0)
                        .path());
    }

    @Test
    void anIntegerMayBeWrittenWithAZeroFraction() {
        assertEquals(
                12,
                ((Tunnel) decode("{\"target\":{\"kind\":\"tunnel\",\"distance\":12.0}}")
                                .target())
                        .distance());
        assertEquals(
                ApiIssue.Code.TYPE_MISMATCH,
                issues("{\"target\":{\"kind\":\"tunnel\",\"distance\":1.2}}")
                        .get(0)
                        .code());
    }

    @Test
    void aBotRefIsChecked() {
        String tunnel = ",\"target\":{\"kind\":\"tunnel\",\"distance\":1}}";
        assertEquals("/bot", issues("{\"bot\":\"player:\"" + tunnel).get(0).path());
        assertEquals("npc:x", decode("{\"bot\":\"npc:x\"" + tunnel).bot());
        assertEquals("mob:x", decode("{\"bot\":\"mob:x\"" + tunnel).bot());
        String uuid = "entity:0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d";
        assertEquals(uuid, decode("{\"bot\":\"" + uuid + "\"" + tunnel).bot());
        assertEquals(
                "/bot", issues("{\"bot\":\"entity:zombie\"" + tunnel).get(0).path());
    }

    @Test
    void theOldBodyKeyIsNoLongerAccepted() {
        assertEquals(
                "/body",
                issues("{\"body\":\"npc:x\",\"target\":{\"kind\":\"tunnel\",\"distance\":1}}")
                        .get(0)
                        .path());
    }

    @Test
    void responsesEncodeToTheContractsShapes() {
        JsonObject m = ClearAreaContracts.RESPONSE
                .encode(new Accepted<>(
                        new Succeeded<>(
                                "a1",
                                "player:builder",
                                new Summary(
                                        new BlockPosition(10, 64, 21),
                                        new BlockPosition(10, 65, 32),
                                        Optional.empty())),
                        false))
                .getAsJsonObject();
        assertEquals("accepted", m.get("outcome").getAsString());
        JsonObject action = m.getAsJsonObject("action");
        assertEquals("succeeded", action.get("state").getAsString());
        assertEquals("clearArea", action.getAsJsonObject("result").get("kind").getAsString());

        String refused = ClearAreaContracts.RESPONSE
                .encode(new Refused<>(RefusalReason.BOT_UNAVAILABLE, "gone"))
                .toString();
        assertEquals("{\"outcome\":\"refused\",\"reason\":\"bot_unavailable\",\"message\":\"gone\"}", refused);
    }

    @Test
    void theTypeScriptNamesTheResponseUnion() {
        net.magicterra.worlddriver.api.schema.TsContext ts = new net.magicterra.worlddriver.api.schema.TsContext();
        assertEquals("ClearAreaResponse", ClearAreaContracts.RESPONSE.ts(ts));
        assertEquals("ClearAreaParams", ClearAreaContracts.PARAMS.ts(ts));
        String d = ts.render("");
        assertTrue(
                d.contains("kind: 'tunnel'")
                        && d.contains("widthBias?: WidthBias")
                        && d.contains("state: 'superseded'"),
                d);
    }
}
