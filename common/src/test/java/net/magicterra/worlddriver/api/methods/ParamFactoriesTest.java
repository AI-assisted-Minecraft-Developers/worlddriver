package net.magicterra.worlddriver.api.methods;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.methods.CommonTypes.BlockPosition;
import net.magicterra.worlddriver.bot.SettingsContract;
import net.magicterra.worlddriver.protocol.JsonCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The parameter factories Java callers build requests with spell out the contract's defaults by hand.
 * Each must equal what the contract decodes from the shortest JSON a caller could send for the same
 * request, so a default changed in a contract cannot leave its factory sending the old one.
 */
class ParamFactoriesTest {

    private static final BlockPosition POS = new BlockPosition(1, 64, -2);
    private static final String POS_JSON = "{\"x\":1,\"y\":64,\"z\":-2}";

    private static <T> void same(ApiType<T> type, String json, T built) {
        assertEquals(type.decode(JsonCodec.decode(json)), built, json);
    }

    @Test
    void gotoFactoriesMatchTheContractsDefaults() {
        same(GotoContract.PARAMS, "{\"target\":{\"kind\":\"position\",\"pos\":" + POS_JSON + "}}",
                GotoContract.Params.to(POS));
        same(GotoContract.PARAMS, "{\"target\":{\"kind\":\"level\",\"y\":12}}",
                GotoContract.Params.toTarget(new GotoContract.Level(12)));
        same(GotoContract.PARAMS, "{\"planId\":\"p-1\"}", GotoContract.Params.walkPlan("p-1"));
        same(GotoContract.PARAMS, "{\"target\":{\"kind\":\"position\",\"pos\":" + POS_JSON + "},\"awaitMs\":500}",
                GotoContract.Params.to(POS).awaiting(500));
    }

    @Test
    void controlFactoriesMatchTheContractsDefaults() {
        same(ControlContracts.CANCEL.input(), "{\"actionId\":\"a-1\"}", ControlContracts.CancelParams.action("a-1"));
        same(ControlContracts.CANCEL.input(), "{\"process\":\"sleep\"}", ControlContracts.CancelParams.process("sleep"));
        same(ControlContracts.CANCEL.input(), "{}", ControlContracts.CancelParams.all());
        same(ControlContracts.STATUS.input(), "{}", ControlContracts.StatusParams.self());
    }

    @Test
    void queryFactoriesMatchTheContractsDefaults() {
        same(QueryContract.PARAMS, "{\"q\":\"blocks\",\"center\":" + POS_JSON + ",\"filter\":{\"in_radius\":3}}",
                QueryContract.Blocks.around(POS, 3));
        same(QueryContract.PARAMS, "{\"q\":\"blocks\",\"center\":" + POS_JSON
                        + ",\"filter\":{\"in_radius\":3,\"type\":\"minecraft:stone\"}}",
                QueryContract.Blocks.around(POS, 3).ofType("minecraft:stone"));
        same(ClientContracts.BLOCKS_PARAMS, "{\"radius\":2}", ClientContracts.BlocksParams.aroundPlayer(2));
        same(ClientContracts.BLOCKS_PARAMS, "{\"center\":" + POS_JSON + ",\"radius\":2}",
                ClientContracts.BlocksParams.around(POS, 2));
    }

    @Test
    void eventFactoriesMatchTheContractsDefaults() {
        same(EventContracts.EVENTS_SINCE.input(), "{\"cursor\":\"7\",\"types\":[\"player.hurt\"]}",
                EventContracts.SinceParams.after(7, "player.hurt"));
        same(EventContracts.EVENTS_SINCE.input(), "{\"cursor\":\"7\"}", EventContracts.SinceParams.after(7));
    }

    @Test
    void settingsFactoriesMatchTheContractsDefaults() {
        same(SettingsContract.PARAMS, "{\"op\":\"read\",\"keys\":[\"autoEat\",\"allowBreak\"]}",
                SettingsContract.Read.of("autoEat", "allowBreak"));
        same(SettingsContract.PARAMS, "{\"op\":\"read\"}", SettingsContract.Read.all());
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("autoEat", true);
        values.put("allowBreak", false);
        same(SettingsContract.PARAMS, "{\"op\":\"patch\",\"values\":{\"autoEat\":true,\"allowBreak\":false}}",
                SettingsContract.Patch.of(values));
    }
}
