package net.magicterra.worlddriver.api.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.methods.ActionContract.RefusalReason;
import net.magicterra.worlddriver.api.methods.ActionContract.Refused;
import net.magicterra.worlddriver.api.methods.HandContracts.Hand;
import net.magicterra.worlddriver.api.methods.HandContracts.Interaction;
import net.magicterra.worlddriver.protocol.JsonCodec;
import org.junit.jupiter.api.Test;

/** The verbs that act within the call: which targets go together, and what done and refused look like. */
class HandContractsTest {

    private static <T> T decode(ApiType<T> type, String json) {
        return type.decode(JsonCodec.decode(json));
    }

    private static ApiIssue issue(ApiType<?> type, String json) {
        List<ApiIssue> issues = assertThrows(ApiException.class, () -> decode(type, json)).error().issues();
        assertEquals(1, issues.size(), issues.toString());
        return issues.get(0);
    }

    @Test
    void aLookAimsAtABlockOrAtBothAngles() {
        assertEquals("/pos", issue(HandContracts.LOOK_PARAMS, "{}").path());
        assertEquals("/pitch", issue(HandContracts.LOOK_PARAMS, "{\"yaw\":90}").path());
        assertEquals("/yaw", issue(HandContracts.LOOK_PARAMS, "{\"pitch\":10}").path());
        assertEquals("/yaw", issue(HandContracts.LOOK_PARAMS, "{\"pos\":{\"x\":0,\"y\":0,\"z\":0},\"yaw\":1}").path());
        assertEquals("/pitch", issue(HandContracts.LOOK_PARAMS, "{\"yaw\":0,\"pitch\":91}").path());
        HandContracts.LookParams p = decode(HandContracts.LOOK_PARAMS, "{\"yaw\":90,\"pitch\":10}");
        assertEquals(Optional.of(90.0), p.yaw());
    }

    @Test
    void anInstantLookIsDoneAndASmoothOneIsAnAction() {
        assertEquals("{\"outcome\":\"done\",\"yaw\":90,\"pitch\":10}",
                JsonCodec.encode(HandContracts.LOOK_AT.output().encode(new HandContracts.Turned(90, 10))));
        assertTrue(ActionResults.UNION.variants().stream().anyMatch(v -> v.tag().equals("lookAt")));
    }

    @Test
    void aUseTakesOneTargetAndOnlyItsModifiers() {
        HandContracts.UseParams air = decode(HandContracts.USE_PARAMS, "{}");
        assertEquals(Hand.MAIN, air.hand());
        assertTrue(air.lookAt());
        assertEquals("/entityId", issue(HandContracts.USE_PARAMS, "{\"pos\":{\"x\":0,\"y\":0,\"z\":0},\"entityId\":3}").path());
        assertEquals("/face", issue(HandContracts.USE_PARAMS, "{\"face\":\"up\"}").path());
        assertEquals("/sneak", issue(HandContracts.USE_PARAMS, "{\"sneak\":true}").path());
        assertEquals("/hand", issue(HandContracts.USE_PARAMS, "{\"hand\":\"left\"}").path());
    }

    @Test
    void aUseSaysWhatItWasUsedOn() {
        String json = JsonCodec.encode(HandContracts.USE_ITEM.output().encode(new HandContracts.Used(Hand.MAIN,
                Interaction.SUCCESS, true, new HandContracts.OnEntity(7, "minecraft:boat", 1.5,
                        Optional.of("minecraft:boat"), Optional.empty(), Optional.empty()))));
        assertEquals("{\"outcome\":\"done\",\"hand\":\"main\",\"result\":\"success\",\"consumed\":true,\"on\":"
                + "{\"kind\":\"entity\",\"entityId\":7,\"type\":\"minecraft:boat\",\"distance\":1.5,\"riding\":\"minecraft:boat\"}}", json);
    }

    @Test
    void aRefusalHasTheTasksReasons() {
        assertEquals("{\"outcome\":\"refused\",\"reason\":\"unsupported_capability\",\"message\":\"npc:a has no hands\"}",
                JsonCodec.encode(HandContracts.HOLD_ITEM.output().encode(
                        new Refused<>(RefusalReason.UNSUPPORTED_CAPABILITY, "npc:a has no hands"))));
    }

    @Test
    void anItemIdNeedsItsNamespace() {
        assertEquals("/item", issue(HandContracts.HOLD_PARAMS, "{\"item\":\"stone\"}").path());
    }

    @Test
    void equipReportsEmptySlotsByAbsence() {
        String json = JsonCodec.encode(HandContracts.EQUIP.output().encode(new HandContracts.Equipped(HandContracts.Profile.ARMOR,
                List.of("minecraft:diamond_helmet"), new HandContracts.Loadout(Optional.of("minecraft:diamond_helmet"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), List.of(),
                List.of(HandContracts.ArmorSlot.CHEST))));
        assertEquals("{\"outcome\":\"done\",\"profile\":\"armor\",\"equipped\":[\"minecraft:diamond_helmet\"],"
                + "\"loadout\":{\"head\":\"minecraft:diamond_helmet\"},\"lowDurability\":[],\"missing\":[\"chest\"]}", json);
    }
}
