package net.magicterra.worlddriver.api.methods.interact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Hand;
import net.magicterra.worlddriver.api.methods.interact.InteractContracts.Interaction;
import net.magicterra.worlddriver.api.methods.shared.ActionResults;
import net.magicterra.worlddriver.api.methods.shared.Responses;
import net.magicterra.worlddriver.api.methods.shared.Responses.RefusalReason;
import net.magicterra.worlddriver.api.methods.shared.Responses.Refused;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.schema.ApiIssue;
import net.magicterra.worlddriver.api.schema.ApiType;
import org.junit.jupiter.api.Test;

/** The verbs that act within the call: which targets go together, and what done and refused look like. */
class InteractContractsTest {

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
    void aLookAimsAtABlockOrAtBothAngles() {
        assertEquals("/pos", issue(InteractContracts.LOOK_PARAMS, "{}").path());
        assertEquals(
                "/pitch", issue(InteractContracts.LOOK_PARAMS, "{\"yaw\":90}").path());
        assertEquals(
                "/yaw", issue(InteractContracts.LOOK_PARAMS, "{\"pitch\":10}").path());
        assertEquals(
                "/yaw",
                issue(InteractContracts.LOOK_PARAMS, "{\"pos\":{\"x\":0,\"y\":0,\"z\":0},\"yaw\":1}")
                        .path());
        assertEquals(
                "/pitch",
                issue(InteractContracts.LOOK_PARAMS, "{\"yaw\":0,\"pitch\":91}").path());
        InteractContracts.LookParams p = decode(InteractContracts.LOOK_PARAMS, "{\"yaw\":90,\"pitch\":10}");
        assertEquals(Optional.of(90.0), p.yaw());
    }

    @Test
    void anInstantLookIsDoneAndASmoothOneIsAnAction() {
        assertEquals(
                "{\"outcome\":\"done\",\"yaw\":90.0,\"pitch\":10.0}",
                String.valueOf(InteractContracts.LOOK_AT.output().encode(new Responses.Turned(90, 10))));
        assertTrue(ActionResults.UNION.variants().stream().anyMatch(v -> v.tag().equals("lookAt")));
    }

    @Test
    void aUseTakesOneTargetAndOnlyItsModifiers() {
        InteractContracts.UseParams air = decode(InteractContracts.USE_PARAMS, "{}");
        assertEquals(Hand.MAIN, air.hand());
        assertTrue(air.lookAt());
        assertEquals(
                "/entityId",
                issue(InteractContracts.USE_PARAMS, "{\"pos\":{\"x\":0,\"y\":0,\"z\":0},\"entityId\":3}")
                        .path());
        assertEquals(
                "/face",
                issue(InteractContracts.USE_PARAMS, "{\"face\":\"up\"}").path());
        assertEquals(
                "/sneak",
                issue(InteractContracts.USE_PARAMS, "{\"sneak\":true}").path());
        assertEquals(
                "/hand",
                issue(InteractContracts.USE_PARAMS, "{\"hand\":\"left\"}").path());
    }

    @Test
    void aUseSaysWhatItWasUsedOn() {
        String json = String.valueOf(InteractContracts.USE_ITEM
                .output()
                .encode(new Responses.Used(
                        Hand.MAIN,
                        Interaction.SUCCESS,
                        true,
                        new InteractContracts.OnEntity(
                                7,
                                "minecraft:boat",
                                1.5,
                                Optional.of("minecraft:boat"),
                                Optional.empty(),
                                Optional.empty()))));
        assertEquals(
                "{\"outcome\":\"done\",\"hand\":\"main\",\"result\":\"success\",\"consumed\":true,\"on\":"
                        + "{\"kind\":\"entity\",\"entityId\":7,\"type\":\"minecraft:boat\",\"distance\":1.5,\"riding\":\"minecraft:boat\"}}",
                json);
    }

    @Test
    void aRefusalHasTheTasksReasons() {
        assertEquals(
                "{\"outcome\":\"refused\",\"reason\":\"unsupported_capability\",\"message\":\"npc:a has no hands\"}",
                String.valueOf(InteractContracts.HOLD_ITEM
                        .output()
                        .encode(new Refused<>(RefusalReason.UNSUPPORTED_CAPABILITY, "npc:a has no hands"))));
    }

    @Test
    void anItemIdNeedsItsNamespace() {
        assertEquals(
                "/item",
                issue(InteractContracts.HOLD_PARAMS, "{\"item\":\"stone\"}").path());
    }

    @Test
    void equipReportsEmptySlotsByAbsence() {
        String json = String.valueOf(InteractContracts.EQUIP
                .output()
                .encode(new Responses.Equipped(
                        InteractContracts.Profile.ARMOR,
                        List.of("minecraft:diamond_helmet"),
                        new InteractContracts.Loadout(
                                Optional.of("minecraft:diamond_helmet"),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty()),
                        List.of(),
                        List.of(InteractContracts.ArmorSlot.CHEST))));
        assertEquals(
                "{\"outcome\":\"done\",\"profile\":\"armor\",\"equipped\":[\"minecraft:diamond_helmet\"],"
                        + "\"loadout\":{\"head\":\"minecraft:diamond_helmet\"},\"lowDurability\":[],\"missing\":[\"chest\"]}",
                json);
    }
}
