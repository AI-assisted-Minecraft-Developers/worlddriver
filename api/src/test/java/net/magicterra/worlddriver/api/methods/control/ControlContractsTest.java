package net.magicterra.worlddriver.api.methods.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.BotStatus;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.Slot;
import net.magicterra.worlddriver.api.methods.control.ControlContracts.Slots;
import net.magicterra.worlddriver.api.methods.shared.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import org.junit.jupiter.api.Test;

/** status, cancel, waypoint and roster: which params go together, and what each answers. */
class ControlContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonParser.parseString(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json))
                .error()
                .issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    @Test
    void aCancelStopsAnActionOrABotsProcessesNotBoth() {
        ControlContracts.CancelParams all = decode(ControlContracts.CANCEL_PARAMS, "{}");
        assertTrue(
                all.actionId().isEmpty() && all.bot().isEmpty() && all.process().isEmpty());
        assertEquals(
                "/bot",
                issue(ControlContracts.CANCEL_PARAMS, "{\"actionId\":\"a1\",\"bot\":\"self\"}")
                        .path());
        assertEquals(
                "/process",
                issue(ControlContracts.CANCEL_PARAMS, "{\"actionId\":\"a1\",\"process\":\"goto\"}")
                        .path());
    }

    @Test
    void aCancelNamesWhatItStopped() {
        assertEquals(
                "{\"outcome\":\"done\",\"cancelled\":[\"combat\"]}",
                String.valueOf(ControlContracts.CANCEL
                        .output()
                        .encode(new Responses.Stopped(List.of("combat"), Optional.empty()))));
    }

    @Test
    void aStatusIsAPollableReadThatSpellsTheGotoSlotOnTheWire() {
        assertTrue(ControlContracts.STATUS.pollable());
        Slot idle = new Slot(
                false,
                Optional.empty(),
                Optional.empty(),
                0,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Slot walking = new Slot(
                true,
                Optional.of("GoalBlock"),
                Optional.of(new BlockPosition(1, 2, 3)),
                9,
                4,
                Optional.of(1_700_000_000_000L),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        Slots slots =
                new Slots(walking, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle, idle);
        String json = String.valueOf(ControlContracts.STATUS
                .output()
                .encode(new Responses.Status(
                        List.of(),
                        Optional.of(new BotStatus(
                                "npc:a", true, Optional.of("goto"), Optional.empty(), slots, Optional.empty())))));
        assertTrue(
                json.startsWith("{\"outcome\":\"done\",\"bots\":[],\"status\":{\"bot\":\"npc:a\",\"busy\":true,"
                        + "\"activeProcess\":\"goto\",\"slots\":{\"goto\":{\"active\":true,\"goal\":\"GoalBlock\","
                        + "\"target\":{\"x\":1,\"y\":2,\"z\":3},\"pathLen\":9,\"pathStep\":4,\"startedAtMs\":1700000000000},"
                        + "\"mine\":{\"active\":false,\"pathLen\":0,\"pathStep\":0},"),
                json);
    }

    @Test
    void aWaypointOpCarriesOnlyItsOwnFields() {
        assertInstanceOf(ControlContracts.ListAll.class, decode(ControlContracts.WAYPOINT_PARAMS, "{\"op\":\"list\"}"));
        ControlContracts.Save save = assertInstanceOf(
                ControlContracts.Save.class,
                decode(ControlContracts.WAYPOINT_PARAMS, "{\"op\":\"save\",\"name\":\"home\"}"));
        assertTrue(save.pos().isEmpty());
        assertEquals(
                "/name",
                issue(ControlContracts.WAYPOINT_PARAMS, "{\"op\":\"get\"}").path());
        assertEquals(
                "/name",
                issue(ControlContracts.WAYPOINT_PARAMS, "{\"op\":\"list\",\"name\":\"home\"}")
                        .path());
        assertEquals(
                "/op",
                issue(ControlContracts.WAYPOINT_PARAMS, "{\"op\":\"rename\"}").path());
    }

    @Test
    void aWaypointAnswerIsTaggedWithItsOp() {
        assertEquals(
                "{\"op\":\"delete\",\"name\":\"home\",\"existed\":true,\"count\":0}",
                String.valueOf(
                        ControlContracts.WAYPOINT_SPEC.output().encode(new ControlContracts.Deleted("home", true, 0))));
    }

    @Test
    void aRosterOpNamesItsBotAsAPlayerNameAndNeverRemovesSelf() {
        ControlContracts.Spawn spawn = assertInstanceOf(
                ControlContracts.Spawn.class,
                decode(ControlContracts.ROSTER_PARAMS, "{\"op\":\"spawn\",\"name\":\"alex_2\"}"));
        assertTrue(spawn.dimension().isEmpty() && spawn.pos().isEmpty());
        ControlContracts.Adopt adopt = assertInstanceOf(
                ControlContracts.Adopt.class,
                decode(ControlContracts.ROSTER_PARAMS, "{\"op\":\"adopt\",\"entityId\":7}"));
        assertTrue(adopt.name().isEmpty());
        assertEquals(
                "/name",
                issue(ControlContracts.ROSTER_PARAMS, "{\"op\":\"spawn\",\"name\":\"a:b\"}")
                        .path());
        assertEquals(
                "/name",
                issue(ControlContracts.ROSTER_PARAMS, "{\"op\":\"spawn\",\"name\":\"seventeen_letters\"}")
                        .path());
        assertEquals(
                "/entityId",
                issue(ControlContracts.ROSTER_PARAMS, "{\"op\":\"adopt\"}").path());
        assertEquals(
                "/bot",
                issue(ControlContracts.ROSTER_PARAMS, "{\"op\":\"remove\",\"bot\":\"self\"}")
                        .path());
        assertInstanceOf(
                ControlContracts.Remove.class,
                decode(ControlContracts.ROSTER_PARAMS, "{\"op\":\"remove\",\"bot\":\"mob:pig\"}"));
    }

    @Test
    void aRosterChangeAnswersTheBot() {
        assertEquals(
                "{\"outcome\":\"done\",\"bot\":\"player:alex\"}",
                String.valueOf(ControlContracts.ROSTER.output().encode(new Responses.Rostered("player:alex"))));
    }
}
